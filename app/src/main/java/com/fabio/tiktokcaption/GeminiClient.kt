package com.fabio.tiktokcaption

import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente para a API do Gemini, usando a Files API (upload) em vez de mandar
 * a mídia em base64 dentro do JSON.
 *
 * Por padrão manda só o ÁUDIO extraído do vídeo (bem mais barato em tokens e
 * mais leve em memória que o vídeo inteiro, já que só o que foi falado importa
 * pra gerar a legenda). Se a extração de áudio falhar, o app cai pra mandar o
 * vídeo inteiro mesmo, então o mimeType é sempre informado por quem chama.
 *
 * Também gera uma imagem de thumbnail chamativa via IA generativa (generateThumbnail),
 * pra usar como capa do post em vez do primeiro frame cru do vídeo.
 */
object GeminiClient {

    private const val MODEL = "gemini-3.6-flash"
    private const val IMAGE_MODEL = "gemini-3.6-flash-image"
    private const val BASE_URL = "https://generativelanguage.googleapis.com"
    private const val UPLOAD_URL = "$BASE_URL/upload/v1beta/files"
    private const val GENERATE_URL = "$BASE_URL/v1beta/models/$MODEL:generateContent"
    private const val IMAGE_GENERATE_URL = "$BASE_URL/v1beta/models/$IMAGE_MODEL:generateContent"
    private const val MAX_STATUS_CHECKS = 20

    private val PROMPT = "Isto é uma gravação (o áudio de um vídeo que fiz, narrando ao vivo) para eu " +
        "postar nas minhas redes sociais. Com base apenas no que eu disse em voz alta, escreva um texto " +
        "para a legenda com: primeiro uma linha de abertura curta e chamativa (um gancho, tipo título, " +
        "que prenda atenção), seguida de um texto de tamanho médio pra longo — NUNCA um texto curto — no " +
        "tom de quem fala naturalmente e de forma espontânea. Não seja apenas uma transcrição do que eu " +
        "disse: expanda a ideia, acrescente comentários, contexto ou observações relacionadas para " +
        "engordar o texto e aumentar o engajamento, sem inventar fatos que contradigam o que eu disse. " +
        "O objetivo é viralizar. Termine com exatamente 5 hashtags relevantes em português. Responda " +
        "apenas com o texto final, pronto para colar na descrição do post, sem nenhuma explicação, " +
        "introdução ou aspas."

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    fun generateCaption(
        apiKey: String,
        mediaBytes: ByteArray,
        mimeType: String = "video/mp4",
        callback: (Result<String>) -> Unit
    ) {
        startUpload(apiKey, mediaBytes, mimeType) { startResult ->
            startResult.onSuccess { uploadUrl ->
                finishUpload(uploadUrl, mediaBytes) { finishResult ->
                    finishResult.onSuccess { fileUri ->
                        waitUntilActive(apiKey, fileUri, 0) { activeResult ->
                            activeResult.onSuccess {
                                requestCaption(apiKey, fileUri, mimeType, callback)
                            }.onFailure { callback(Result.failure(it)) }
                        }
                    }.onFailure { callback(Result.failure(it)) }
                }
            }.onFailure { callback(Result.failure(it)) }
        }
    }

    /**
     * Gera uma imagem de thumbnail chamativa com IA, baseada no assunto da legenda já
     * pronta, pra usar como capa do post em vez do frame cru do início do vídeo.
     *
     * Assim como aconteceu com o modelo de texto (o Google descontinuou o gemini-2.5-flash
     * de uma hora pra outra), o nome do modelo de imagem também pode mudar no futuro — se
     * isso parar de funcionar, o próprio erro que a Google devolve costuma dizer qual é o
     * modelo certo pra usar agora.
     */
    fun generateThumbnail(apiKey: String, caption: String, callback: (Result<ByteArray>) -> Unit) {
        val prompt = "Crie uma imagem de thumbnail/capa para um vídeo de rede social (TikTok, " +
            "Instagram, YouTube), no formato vertical, bem chamativa, com cores vibrantes e alto " +
            "contraste, estilo que prenda a atenção e gere cliques (like uma boa thumbnail de " +
            "YouTube Shorts), sobre o seguinte assunto: \"$caption\". Não inclua texto ilegível " +
            "nem marcas d'água, e não tente reescrever a legenda dentro da imagem."

        val content = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        val body = JSONObject().apply {
            put("contents", JSONArray().put(content))
            put(
                "generationConfig",
                JSONObject().put("responseModalities", JSONArray().put("IMAGE"))
            )
        }

        val requestBody = body.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$IMAGE_GENERATE_URL?key=$apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("Gemini (imagem) HTTP ${it.code}: $raw")))
                        return
                    }
                    try {
                        val parts = JSONObject(raw).getJSONArray("candidates")
                            .getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                        var imageBytes: ByteArray? = null
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            val inline = part.optJSONObject("inline_data") ?: part.optJSONObject("inlineData")
                            if (inline != null) {
                                val b64 = inline.optString("data")
                                imageBytes = Base64.decode(b64, Base64.DEFAULT)
                                break
                            }
                        }
                        if (imageBytes != null) {
                            callback(Result.success(imageBytes))
                        } else {
                            callback(Result.failure(IOException("O Gemini não retornou nenhuma imagem: $raw")))
                        }
                    } catch (e: Exception) {
                        callback(Result.failure(IOException("Resposta inesperada do Gemini (imagem): $raw", e)))
                    }
                }
            }
        })
    }

    private fun startUpload(
        apiKey: String,
        mediaBytes: ByteArray,
        mimeType: String,
        callback: (Result<String>) -> Unit
    ) {
        val metadata = JSONObject().put("file", JSONObject().put("display_name", "midia_legenda"))
            .toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$UPLOAD_URL?key=$apiKey")
            .addHeader("X-Goog-Upload-Protocol", "resumable")
            .addHeader("X-Goog-Upload-Command", "start")
            .addHeader("X-Goog-Upload-Header-Content-Length", mediaBytes.size.toString())
            .addHeader("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(metadata)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    val uploadUrl = it.header("X-Goog-Upload-URL")
                    if (!it.isSuccessful || uploadUrl == null) {
                        callback(Result.failure(IOException("Erro ao iniciar upload pro Gemini (HTTP ${it.code})")))
                        return
                    }
                    callback(Result.success(uploadUrl))
                }
            }
        })
    }

    private fun finishUpload(uploadUrl: String, mediaBytes: ByteArray, callback: (Result<String>) -> Unit) {
        val request = Request.Builder()
            .url(uploadUrl)
            .addHeader("X-Goog-Upload-Offset", "0")
            .addHeader("X-Goog-Upload-Command", "upload, finalize")
            .post(mediaBytes.toRequestBody(null))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    val body = it.body?.string()
                    if (!it.isSuccessful || body == null) {
                        callback(Result.failure(IOException("Erro ao enviar mídia pro Gemini (HTTP ${it.code})")))
                        return
                    }
                    try {
                        val uri = JSONObject(body).getJSONObject("file").getString("uri")
                        callback(Result.success(uri))
                    } catch (e: Exception) {
                        callback(Result.failure(IOException("Resposta inesperada do upload do Gemini: $body", e)))
                    }
                }
            }
        })
    }

    private fun waitUntilActive(apiKey: String, fileUri: String, attempt: Int, callback: (Result<Unit>) -> Unit) {
        if (attempt >= MAX_STATUS_CHECKS) {
            callback(Result.failure(IOException("A mídia demorou demais para processar no Gemini")))
            return
        }

        val request = Request.Builder().url("$fileUri?key=$apiKey").get().build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    val body = it.body?.string()
                    if (!it.isSuccessful || body == null) {
                        callback(Result.failure(IOException("Erro ao checar status da mídia no Gemini (HTTP ${it.code})")))
                        return
                    }
                    val state = try { JSONObject(body).optString("state") } catch (e: Exception) { "" }
                    when (state) {
                        "ACTIVE" -> callback(Result.success(Unit))
                        "FAILED" -> callback(Result.failure(IOException("O Gemini não conseguiu processar a mídia")))
                        else -> {
                            Thread.sleep(2000)
                            waitUntilActive(apiKey, fileUri, attempt + 1, callback)
                        }
                    }
                }
            }
        })
    }

    private fun requestCaption(
        apiKey: String,
        fileUri: String,
        mimeType: String,
        callback: (Result<String>) -> Unit
    ) {
        val part1 = JSONObject().put("text", PROMPT)
        val part2 = JSONObject().put(
            "file_data",
            JSONObject().put("mime_type", mimeType).put("file_uri", fileUri)
        )
        val parts = JSONArray().put(part1).put(part2)
        val content = JSONObject().put("parts", parts)
        val body = JSONObject().put("contents", JSONArray().put(content))

        val requestBody = body.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$GENERATE_URL?key=$apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("Gemini HTTP ${it.code}: $raw")))
                        return
                    }
                    try {
                        val json = JSONObject(raw)
                        val text = json.getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                        callback(Result.success(text.trim()))
                    } catch (e: Exception) {
                        callback(Result.failure(IOException("Resposta inesperada do Gemini: $raw", e)))
                    }
                }
            }
        })
    }
}
