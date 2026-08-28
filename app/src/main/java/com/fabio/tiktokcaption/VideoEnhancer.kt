package com.fabio.tiktokcaption

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.transformer.Effects
import androidx.media3.common.MediaItem
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Aplica um tratamento leve de qualidade no vídeo antes de publicar — mais
 * contraste e um toque de saturação, pra sempre sair "melhorzinho" sem exagerar —
 * usando o processamento de vídeo do próprio Android (Media3 Transformer), direto
 * no aparelho, sem mandar o vídeo pra nenhum servidor externo pra isso.
 *
 * ADAPTATIVO: antes de tratar, dá uma espiada num frame do meio do próprio vídeo
 * pra medir se o ambiente puxa pro laranja/vermelho (luz quente/incandescente,
 * comum à noite com a luz do quarto ligada) — e, se sim, reduz automaticamente o
 * quanto de saturação/vermelho é aplicado (e ainda dá uma leve puxada pro azul),
 * proporcional ao quão forte é esse viés. Cenário sem esse viés recebe o
 * tratamento padrão normalmente. Isso evita o problema de aplicar sempre o mesmo
 * "filtro fixo" e sair com a pele vermelha/alaranjada demais em ambientes com luz
 * quente. Se a análise falhar por qualquer motivo, cai num tratamento neutro e leve.
 *
 * Essa etapa roda em PARALELO com a geração da legenda: as duas começam assim que
 * você toca em "Gerar legenda com IA", e a publicação só acontece depois que as
 * duas tiverem prontas (ver MainActivity.generateCaption). Se o tratamento falhar
 * por qualquer motivo, quem chamar essa função deve simplesmente seguir com o
 * vídeo original.
 *
 * IMPORTANTE: depois de tratar, a duração do vídeo tratado é comparada com a do
 * original. Se sair bem mais curta (sinal de que o Transformer cortou o vídeo —
 * isso já aconteceu na prática), o resultado é descartado e quem chamou cai pro
 * vídeo original. Nunca publica um vídeo cortado por causa do tratamento.
 *
 * Precisa ser chamado a partir de uma thread com Looper — a thread principal do
 * app serve, e é assim que é usado aqui — porque o Transformer do Media3 exige
 * isso. Ele mesmo já é assíncrono por baixo dos panos, então não trava a tela.
 */
object VideoEnhancer {

    fun enhance(context: Context, inputUri: Uri, callback: (Result<File>) -> Unit) {
        val outputFile = try {
            File.createTempFile("video_tratado_", ".mp4", context.cacheDir)
        } catch (e: Exception) {
            callback(Result.failure(e))
            return
        }

        val originalDurationMs = readDurationMsFromUri(context, inputUri)

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .setEffects(
                Effects(
                    /* audioProcessors= */ emptyList(),
                    /* videoEffects= */ buildAdaptiveVideoEffects(context, inputUri)
                )
            )
            .build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val treatedDurationMs = readDurationMsFromPath(outputFile.absolutePath)
                    if (isSuspiciouslyShort(originalDurationMs, treatedDurationMs)) {
                        // O Transformer "terminou" mas devolveu um vídeo bem mais
                        // curto que o original — tratamos como falha e caímos
                        // pro vídeo original, pra nunca publicar algo cortado.
                        outputFile.delete()
                        callback(
                            Result.failure(
                                IllegalStateException(
                                    "Vídeo tratado saiu mais curto que o original " +
                                        "(original: ${originalDurationMs}ms, tratado: ${treatedDurationMs}ms)"
                                )
                            )
                        )
                    } else {
                        callback(Result.success(outputFile))
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    // Se o tratamento falhar, quem chamou usa o vídeo original —
                    // isso nunca deve impedir a publicação.
                    outputFile.delete()
                    callback(Result.failure(exportException))
                }
            })
            .build()

        try {
            transformer.start(editedMediaItem, outputFile.absolutePath)
        } catch (e: Exception) {
            outputFile.delete()
            callback(Result.failure(e))
        }
    }

    /**
     * Mede (com base num frame real do próprio vídeo) o quanto o ambiente puxa pro
     * vermelho/laranja e monta a lista de efeitos com a correção proporcional a
     * isso — em vez de sempre aplicar o mesmo valor fixo de saturação. Se a
     * medição falhar por qualquer motivo, usa os valores neutros de sempre.
     */
    private fun buildAdaptiveVideoEffects(context: Context, uri: Uri): List<Effect> {
        val warmth = try {
            measureWarmth(context, uri)
        } catch (e: Exception) {
            null
        } ?: 0f

        // Quanto mais "quente"/avermelhado o ambiente, mais a correção reduz o
        // vermelho e realça um pouco o azul, e menos saturação extra é aplicada —
        // os limites (coerceIn) evitam qualquer correção exagerada mesmo se a
        // cena medida for um caso bem extremo.
        val redScale = 1f - (warmth * 0.6f).coerceIn(0f, 0.14f)
        val blueScale = 1f + (warmth * 0.3f).coerceIn(0f, 0.07f)
        val saturationBoost = 10f - (warmth * 40f).coerceIn(0f, 6f)
        val lightnessBoost = 2f - (warmth * 10f).coerceIn(0f, 1.5f)

        return listOf(
            Contrast(0.12f),
            RgbAdjustment.Builder()
                .setRedScale(redScale)
                .setBlueScale(blueScale)
                .build(),
            HslAdjustment.Builder()
                .adjustSaturation(saturationBoost)
                .adjustLightness(lightnessBoost)
                .build()
        )
    }

    /**
     * Pega um frame perto do meio do vídeo e devolve um número de 0 (ambiente
     * neutro ou frio) a 1 (bem avermelhado/alaranjado), baseado em quanto o
     * vermelho médio do frame passa do azul médio.
     */
    private fun measureWarmth(context: Context, uri: Uri): Float? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return null
            val timestampMs = (durationMs * 0.4f).toLong()
            val frame = retriever.getFrameAtTime(
                timestampMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return null
            val warmth = try {
                averageWarmth(frame)
            } finally {
                frame.recycle()
            }
            warmth
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun averageWarmth(bitmap: Bitmap): Float {
        // Reduz pra uma amostra bem pequena só pra tirar a média de cor rápido —
        // não precisa (nem deve, por custo) olhar pixel a pixel na resolução cheia.
        val sample = Bitmap.createScaledBitmap(bitmap, 40, 40, true)
        return try {
            val pixels = IntArray(sample.width * sample.height)
            sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
            var totalR = 0L
            var totalB = 0L
            for (pixel in pixels) {
                totalR += (pixel shr 16) and 0xFF
                totalB += pixel and 0xFF
            }
            val count = pixels.size
            val avgR = totalR.toFloat() / count
            val avgB = totalB.toFloat() / count
            ((avgR - avgB) / 255f).coerceIn(0f, 1f)
        } finally {
            sample.recycle()
        }
    }

    private fun readDurationMsFromUri(context: Context, uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun readDurationMsFromPath(path: String): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun isSuspiciouslyShort(originalMs: Long?, treatedMs: Long?): Boolean {
        if (originalMs == null || treatedMs == null) return false
        if (originalMs <= 0) return false
        // Tolerância de 15% pra diferenças normais de arredondamento entre
        // contêineres — qualquer coisa além disso é sinal de corte real.
        return treatedMs < originalMs * 0.85
    }
}
