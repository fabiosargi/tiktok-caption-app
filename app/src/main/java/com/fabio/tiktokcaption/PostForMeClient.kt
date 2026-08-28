package com.fabio.tiktokcaption

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente para a API do Post for Me (postforme.dev) — depois que a legenda já foi
 * gerada pelo Gemini, este cliente sobe o vídeo e publica automaticamente nas contas
 * (TikTok, Instagram, YouTube) que você conectou no painel deles. Sem precisar abrir
 * cada aplicativo manualmente nem passar por auditoria própria em cada plataforma.
 *
 * Fluxo (conforme a documentação oficial em api.postforme.dev/docs):
 * 1. POST /v1/media/create-upload-url -> devolve upload_url (assinada) e media_url
 * 2. PUT dos bytes do vídeo na upload_url
 * 3. GET /v1/social-accounts -> lista as contas conectadas (filtra tiktok/instagram/youtube)
 * 4. POST /v1/social-posts com a legenda + media_url + ids das contas -> publica na hora
 */
object PostForMeClient {

    private const val BASE_URL = "https://api.postforme.dev/v1"
    private val targetPlatforms = setOf("tiktok", "instagram", "youtube")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    data class PublishResult(val publishedPlatforms: List<String>)

    private data class Account(val id: String, val platform: String)

    fun publishVideo(
        apiKey: String,
        videoBytes: ByteArray,
        caption: String,
        callback: (Result<PublishResult>) -> Unit
    ) {
        getConnectedAccounts(apiKey) { accountsResult ->
            accountsResult.onSuccess { accounts ->
                if (accounts.isEmpty()) {
                    callback(Result.failure(IOException(
                        "Nenhuma conta do TikTok/Instagram/YouTube conectada no Post for Me ainda"
                    )))
                    return@onSuccess
                }

                requestUploadUrl(apiKey) { uploadResult ->
                    uploadResult.onSuccess { (uploadUrl, mediaUrl) ->
                        uploadBytes(uploadUrl, videoBytes) { putResult ->
                            putResult.onSuccess {
                                createPost(apiKey, caption, mediaUrl, accounts.map { it.id }) { postResult ->
                                    postResult.onSuccess {
                                        callback(Result.success(PublishResult(accounts.map { it.platform })))
                                    }.onFailure { callback(Result.failure(it)) }
                                }
                            }.onFailure { callback(Result.failure(it)) }
                        }
                    }.onFailure { callback(Result.failure(it)) }
                }
            }.onFailure { callback(Result.failure(it)) }
        }
    }

    private fun getConnectedAccounts(apiKey: String, callback: (Result<List<Account>>) -> Unit) {
        val request = Request.Builder()
            .url("$BASE_URL/social-accounts?limit=50")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string()
                    if (!it.isSuccessful || body == null) {
                        callback(Result.failure(IOException("Erro ao buscar contas conectadas (HTTP ${it.code})")))
                        return
                    }
                    try {
                        val data = JSONObject(body).getJSONArray("data")
                        val accounts = mutableListOf<Account>()
                        for (i in 0 until data.length()) {
                            val obj = data.getJSONObject(i)
                            val platform = obj.optString("platform")
                            val status = obj.optString("status")
                            if (status == "connected" && targetPlatforms.contains(platform)) {
                                accounts.add(Account(obj.getString("id"), platform))
                            }
                        }
                        callback(Result.success(accounts))
                    } catch (e: Exception) {
                        callback(Result.failure(IOException("Resposta inesperada ao buscar contas: $body", e)))
                    }
                }
            }
        })
    }

    private fun requestUploadUrl(apiKey: String, callback: (Result<Pair<String, String>>) -> Unit) {
        val request = Request.Builder()
            .url("$BASE_URL/media/create-upload-url")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string()
                    if (!it.isSuccessful || body == null) {
                        callback(Result.failure(IOException("Erro ao pedir URL de upload (HTTP ${it.code})")))
                        return
                    }
                    try {
                        val json = JSONObject(body)
                        callback(Result.success(json.getString("upload_url") to json.getString("media_url")))
                    } catch (e: Exception) {
                        callback(Result.failure(IOException("Resposta inesperada ao pedir upload: $body", e)))
                    }
                }
            }
        })
    }

    private fun uploadBytes(uploadUrl: String, bytes: ByteArray, callback: (Result<Unit>) -> Unit) {
        val request = Request.Builder()
            .url(uploadUrl)
            .put(bytes.toRequestBody("video/mp4".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("Erro ao enviar o vídeo (HTTP ${it.code})")))
                    } else {
                        callback(Result.success(Unit))
                    }
                }
            }
        })
    }

    private fun createPost(
        apiKey: String,
        caption: String,
        mediaUrl: String,
        accountIds: List<String>,
        callback: (Result<Unit>) -> Unit
    ) {
        val mediaArray = JSONArray().put(JSONObject().put("url", mediaUrl))
        val accountsArray = JSONArray()
        accountIds.forEach { accountsArray.put(it) }

        val payload = JSONObject().apply {
            put("caption", caption)
            put("social_accounts", accountsArray)
            put("media", mediaArray)
        }

        val request = Request.Builder()
            .url("$BASE_URL/social-posts")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val body = it.body?.string()
                        callback(Result.failure(IOException("Erro ao publicar (HTTP ${it.code}): $body")))
                    } else {
                        callback(Result.success(Unit))
                    }
                }
            }
        })
    }
}
