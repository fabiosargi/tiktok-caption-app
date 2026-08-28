package com.fabio.tiktokcaption

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Escolhe, sem usar nenhuma IA generativa (só análise local no aparelho, de
 * graça e offline), o melhor instante do vídeo pra servir de capa.
 *
 * Isso existe por causa de uma limitação do TikTok: a API deles não aceita subir
 * uma imagem de capa customizada — só deixa escolher um recorte (timestamp) de
 * dentro do próprio vídeo. Sem isso, o TikTok pegaria sempre o frame 0, que pode
 * ser bem no meio de uma piscada ou uma careta.
 *
 * A técnica: varre alguns pontos espaçados pelo vídeo (evitando bem o início e o
 * fim), roda a detecção de rosto do ML Kit (100% no aparelho) em cada um e
 * pontua pela probabilidade dos olhos estarem abertos, evitando também sorrisos
 * muito extremos (que costumam vir com a boca bem aberta no meio de uma fala ou
 * risada). O timestamp com a melhor pontuação vence.
 *
 * É uma heurística, não uma garantia — não existe no ML Kit uma detecção direta
 * de "boca aberta", só uma aproximação via olhos + expressão. Mas já evita boa
 * parte das pisadas de bola óbvias.
 *
 * Precisa rodar fora da thread principal (usa chamadas bloqueantes).
 */
object FrameSelector {

    private const val CANDIDATE_COUNT = 8

    fun pickBestTimestampMs(context: Context, uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            pickBestTimestampMs(retriever)
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (e: Exception) { }
        }
    }

    fun pickBestTimestampMs(path: String): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            pickBestTimestampMs(retriever)
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (e: Exception) { }
        }
    }

    private fun pickBestTimestampMs(retriever: MediaMetadataRetriever): Long? {
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
        if (durationMs == null || durationMs <= 0) return null

        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )

        try {
            // Evita os primeiros/últimos 10% do vídeo (geralmente é você ainda se
            // ajustando no início ou já saindo do enquadramento no fim).
            val start = (durationMs * 0.1).toLong()
            val end = (durationMs * 0.9).toLong()
            val span = (end - start).coerceAtLeast(1)
            val step = span / (CANDIDATE_COUNT - 1).coerceAtLeast(1)

            var bestTimestampMs: Long? = null
            var bestScore = Double.NEGATIVE_INFINITY

            for (i in 0 until CANDIDATE_COUNT) {
                val timestampMs = start + step * i
                val bitmap = try {
                    retriever.getFrameAtTime(timestampMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (e: Exception) {
                    null
                } ?: continue

                val score = try {
                    scoreFrame(bitmap, detector)
                } finally {
                    bitmap.recycle()
                }

                if (score != null && score > bestScore) {
                    bestScore = score
                    bestTimestampMs = timestampMs
                }
            }
            return bestTimestampMs
        } finally {
            try { detector.close() } catch (e: Exception) { }
        }
    }

    private fun scoreFrame(bitmap: Bitmap, detector: FaceDetector): Double? {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = Tasks.await(detector.process(image))
            if (faces.isEmpty()) return 0.0 // sem rosto detectado: nota neutra, nem descarta nem prioriza

            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: return 0.0

            val leftEyeOpen = (face.leftEyeOpenProbability ?: 0.5f).toDouble()
            val rightEyeOpen = (face.rightEyeOpenProbability ?: 0.5f).toDouble()
            val smiling = (face.smilingProbability ?: 0.35f).toDouble()

            // Prioriza olhos bem abertos (evita piscadas) e uma expressão
            // "neutra pra sorriso leve" — um smilingProbability muito alto costuma
            // vir junto de boca bem aberta (risada/fala), por isso penalizamos os
            // extremos em vez de simplesmente premiar sorrisos.
            val eyesScore = (leftEyeOpen + rightEyeOpen) / 2.0
            val expressionScore = 1.0 - kotlin.math.abs(smiling - 0.35)

            eyesScore * 0.7 + expressionScore * 0.3
        } catch (e: Exception) {
            null
        }
    }
}
