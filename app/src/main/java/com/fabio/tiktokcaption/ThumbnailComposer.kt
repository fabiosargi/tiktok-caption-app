package com.fabio.tiktokcaption

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * Escreve um título curto por cima da imagem de thumbnail gerada pela IA.
 *
 * A legenda que a IA gera sempre começa com uma linha de abertura curta (o
 * "gancho"), e é essa linha que vira o título desenhado na imagem — assim cada
 * vídeo sai com uma combinação diferente de imagem + texto. O texto é desenhado
 * de verdade com o Canvas do Android, em vez de pedir pra IA escrever dentro da
 * imagem (IAs de imagem erram muito na hora de renderizar texto legível).
 */
object ThumbnailComposer {

    fun addTitleOverlay(imageBytes: ByteArray, caption: String): ByteArray {
        val bitmap = try {
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            null
        } ?: return imageBytes

        val title = extractTitle(caption)
        if (title.isBlank()) return imageBytes

        return try {
            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)
            val width = mutableBitmap.width
            val height = mutableBitmap.height

            // Faixa escura em degradê na parte de baixo, pra garantir contraste
            // com o texto branco por cima não importa a cor de fundo da imagem.
            val bandHeight = height * 0.34f
            val bandTop = height - bandHeight
            val gradientPaint = Paint().apply {
                shader = LinearGradient(
                    0f, bandTop, 0f, height.toFloat(),
                    Color.TRANSPARENT, Color.argb(215, 0, 0, 0),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, bandTop, width.toFloat(), height.toFloat(), gradientPaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
                textSize = width * 0.078f
                setShadowLayer(10f, 0f, 3f, Color.argb(190, 0, 0, 0))
            }

            drawWrappedText(
                canvas,
                title,
                textPaint,
                left = width * 0.08f,
                right = width * 0.92f,
                baselineBottom = height - bandHeight * 0.4f
            )

            val output = ByteArrayOutputStream()
            mutableBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        } catch (e: Exception) {
            imageBytes
        }
    }

    private fun extractTitle(caption: String): String {
        val firstLine = caption.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (firstLine.length > 70) firstLine.take(67).trimEnd() + "..." else firstLine
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        paint: Paint,
        left: Float,
        right: Float,
        baselineBottom: Float
    ) {
        val maxWidth = right - left
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""

        for (word in words) {
            val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(candidate) > maxWidth && currentLine.isNotEmpty()) {
                lines.add(currentLine)
                currentLine = word
            } else {
                currentLine = candidate
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)

        // No máximo 3 linhas, pra não tomar a imagem inteira com texto.
        val visibleLines = lines.take(3)

        val lineHeight = paint.textSize * 1.22f
        val totalHeight = lineHeight * visibleLines.size
        var y = baselineBottom - totalHeight + lineHeight
        for (line in visibleLines) {
            canvas.drawText(line, left, y, paint)
            y += lineHeight
        }
    }
}
