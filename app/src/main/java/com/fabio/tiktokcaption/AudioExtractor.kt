package com.fabio.tiktokcaption

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Extrai só a trilha de áudio de um vídeo gravado, copiando os pacotes direto
 * do container original pra um .m4a novo (sem recodificar nada). Isso deixa o
 * arquivo mandado pro Gemini muito menor que o vídeo inteiro — mais barato em
 * tokens e mais leve em memória, já que só o que foi falado importa pra gerar
 * a legenda (o cenário ao fundo não entra na legenda de qualquer forma).
 */
object AudioExtractor {

    fun extractAudioTrack(context: Context, videoUri: Uri): File? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, videoUri, null)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                return null
            }

            extractor.selectTrack(audioTrackIndex)

            val outputFile = File.createTempFile("audio_legenda_", ".m4a", context.cacheDir)
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val maxInputSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                1024 * 1024
            }
            val buffer = ByteBuffer.allocate(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            outputFile
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}
