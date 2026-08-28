package com.fabio.tiktokcaption

import android.content.Context
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
 * Aplica um tratamento leve de qualidade no vídeo antes de publicar — um pouco
 * mais de contraste e de saturação, pra sempre sair "melhorzinho" sem exagerar —
 * usando o processamento de vídeo do próprio Android (Media3 Transformer), direto
 * no aparelho, sem mandar o vídeo pra nenhum servidor externo pra isso.
 *
 * Essa etapa roda em PARALELO com a geração da legenda: as duas começam assim que
 * a gravação termina, e a publicação só acontece depois que as duas tiverem prontas
 * (ver MainActivity.generateCaption). Se o tratamento falhar por qualquer motivo,
 * quem chamar essa função deve simplesmente seguir com o vídeo original.
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

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .setEffects(
                Effects(
                    /* audioProcessors= */ emptyList(),
                    /* videoEffects= */ listOf(
                        Contrast(0.08f),
                        HslAdjustment.Builder()
                            .adjustSaturation(12f)
                            .adjustLightness(3f)
                            .build()
                    )
                )
            )
            .build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    callback(Result.success(outputFile))
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
}
