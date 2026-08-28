package com.fabio.tiktokcaption

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.transformer.Effects
import androidx.media3.common.MediaItem
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
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
 * Os valores de saturação/luminosidade abaixo foram reduzidos depois que o
 * ambiente com luz quente (lâmpada amarela/incandescente ligada à noite) saiu
 * com a pele vermelha/alaranjada demais — ambientes com luz quente já têm um
 * viés natural pro laranja/vermelho, e empurrar a saturação pra cima só piora
 * esse efeito. Por isso o reforço agora é bem mais sutil.
 *
 * Essa etapa roda em PARALELO com a geração da legenda: as duas começam assim que
 * a gravação termina, e a publicação só acontece depois que as duas tiverem prontas
 * (ver MainActivity.generateCaption). Se o tratamento falhar por qualquer motivo,
 * quem chamar essa função deve simplesmente seguir com o vídeo original.
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
                    /* videoEffects= */ listOf(
                        Contrast(0.12f),
                        HslAdjustment.Builder()
                            .adjustSaturation(10f)
                            .adjustLightness(2f)
                            .build()
                    )
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
