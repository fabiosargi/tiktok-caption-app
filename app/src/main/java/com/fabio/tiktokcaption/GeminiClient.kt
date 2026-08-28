package com.fabio.tiktokcaption

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
 */
object GeminiClient {

    private const val MODEL = "gemini-2.5-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com"
    private const val UPLOAD_URL = "$BASE_URL/upload/v1beta/files"
    private const val GENERATE_URL = "$BASE_URL/v1beta/models/$MODEL:generateContent"
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
