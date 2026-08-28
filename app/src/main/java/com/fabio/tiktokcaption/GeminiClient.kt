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
 * o vídeo em base64 dentro do JSON.
 *
 * Antes, o vídeo inteiro virava uma string base64 (~33% maior) e depois era
 * embutido dentro de um JSON gigante — isso cria várias cópias do vídeo na
 * memória ao mesmo tempo e derruba o app (OutOfMemoryError) com vídeos
 * gravados em boa qualidade pela própria câmera. Agora o vídeo é enviado cru
 * (sem base64) pra Files API, e só a referência (file_uri) entra no pedido
 * de legenda — muito mais leve em memória.
 */
object GeminiClient {

    private const val MODEL = "gemini-2.5-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com"
    private const val UPLOAD_URL = "$BASE_URL/upload/v1beta/files"
    private const val GENERATE_URL = "$BASE_URL/v1beta/models/$MODEL:generateContent"
    private const val MAX_STATUS_CHECKS = 20

    private val PROMPT = "Este é um vídeo (ou uma gravação de tela) que gravei narrando ao vivo " +
        "para postar nas minhas redes sociais. Ignore qualquer elemento de interface de aplicativo que " +
        "aparecer na imagem (botões, ícones, barras) e foque no que eu disse em voz alta e no assunto do " +
        "vídeo. Com base nisso, escreva um texto para a legenda com: primeiro uma linha de abertura curta " +
        "e chamativa (um gancho, tipo título, que prenda atenção), seguida de um texto de tamanho médio " +
        "pra longo — NUNCA um texto curto — no tom de quem fala naturalmente e de forma espontânea. Não " +
        "seja apenas uma transcrição do que eu disse: expanda a ideia, acrescente comentários, contexto " +
        "ou observações relacionadas para engordar o texto e aumentar o engajamento, sem inventar fatos " +
        "que contradigam o vídeo. O objetivo é viralizar. Termine com exatamente 5 hashtags relevantes " +
        "em português. Responda apenas com o texto final, pronto para colar na descrição do post, sem " +
        "nenhuma explicação, introdução ou aspas."

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    fun generateCaption(apiKey: String, videoBytes: ByteArray, callback: (Result<String>) -> Unit) {
        startUpload(apiKey, videoBytes) { startResult ->
            startResult.onSuccess { uploadUrl ->
                finishUpload(uploadUrl, videoBytes) { finishResult ->
                    finishResult.onSuccess { fileUri ->
                        waitUntilActive(apiKey, fileUri, 0) { activeResult ->
                            activeResult.onSuccess {
                                requestCaption(apiKey, fileUri, callback)
                            }.onFailure { callback(Result.failure(it)) }
                        }
                    }.onFailure { callback(Result.failure(it)) }
                }
            }.onFailure { callback(Result.failure(it)) }
        }
    }

    private fun startUpload(apiKey: String, videoBytes: ByteArray, callback: (Result<String>) -> Unit) {
        val metadata = JSONObject().put("file", JSONObject().put("display_name", "video_legenda"))
            .toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$UPLOAD_URL?key=$apiKey")
            .addHeader("X-Goog-Upload-Protocol", "resumable")
            .addHeader("X-Goog-Upload-Command", "start")
            .addHeader("X-Goog-Upload-Header-Content-Length", videoBytes.size.toString())
            .addHeader("X-Goog-Upload-Header-Content-Type", "video/mp4")
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

    private fun finishUpload(uploadUrl: String, videoBytes: ByteArray, callback: (Result<String>) -> Unit) {
        val request = Request.Builder()
            .url(uploadUrl)
            .addHeader("X-Goog-Upload-Offset", "0")
            .addHeader("X-Goog-Upload-Command", "upload, finalize")
            .post(videoBytes.toRequestBody("video/mp4".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    val body = it.body?.string()
                    if (!it.isSuccessful || body == null) {
                        callback(Result.failure(IOException("Erro ao enviar vídeo pro Gemini (HTTP ${it.code})")))
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
            callback(Result.failure(IOException("O vídeo demorou demais para processar no Gemini")))
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
                        callback(Result.failure(IOException("Erro ao checar status do vídeo no Gemini (HTTP ${it.code})")))
                        return
                    }
                    val state = try { JSONObject(body).optString("state") } catch (e: Exception) { "" }
                    when (state) {
                        "ACTIVE" -> callback(Result.success(Unit))
                        "FAILED" -> callback(Result.failure(IOException("O Gemini não conseguiu processar o vídeo")))
                        else -> {
                            Thread.sleep(2000)
                            waitUntilActive(apiKey, fileUri, attempt + 1, callback)
                        }
                    }
                }
            }
        })
    }

    private fun requestCaption(apiKey: String, fileUri: String, callback: (Result<String>) -> Unit) {
        val part1 = JSONObject().put("text", PROMPT)
        val part2 = JSONObject().put(
            "file_data",
            JSONObject().put("mime_type", "video/mp4").put("file_uri", fileUri)
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
