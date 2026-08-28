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
* gerada pelo Gemini, este cliente sobe o vídeo (e a thumbnail, se tiver uma pronta)
* e publica automaticamente nas contas (TikTok, Instagram, YouTube) que você conectou
* no painel deles. Sem precisar abrir cada aplicativo manualmente nem passar por
* auditoria própria em cada plataforma.
*
* Fluxo (conforme a documentação oficial em api.postforme.dev/docs):
* 1. POST /v1/media/create-upload-url -> devolve upload_url (assinada) e media_url
* (usado tanto pro vídeo quanto pra imagem da thumbnail, quando houver)
* 2. PUT dos bytes na upload_url
* 3. GET /v1/social-accounts -> lista as contas conectadas (filtra tiktok/instagram/youtube)
* 4. POST /v1/social-posts com a legenda + media_url (+ thumbnail_url e/ou
* thumbnail_timestamp_ms, se houver) + ids das contas -> publica na hora
*
* Sobre a capa do vídeo: cada plataforma aceita uma coisa diferente (confirmado na
* documentação oficial do Post for Me). O TikTok (API normal, que é a que a gente usa)
* NÃO aceita nenhuma imagem customizada como capa — só deixa escolher um instante de
* dentro do próprio vídeo (thumbnail_timestamp_ms). Já Instagram e YouTube (vídeo
* longo) aceitam uma imagem customizada (thumbnail_url). Por isso mandamos os dois
* campos juntos no mesmo item de mídia sempre que tiver algo pra mandar: cada
* plataforma usa o que ela suporta e ignora o resto.
*
* Os dois são só um "extra": se a geração ou o upload da thumbnail falhar por
* qualquer motivo, a publicação do vídeo segue normalmente, só que sem capa
* personalizada.
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
thumbnailBytes: ByteArray? = null,
thumbnailTimestampMs: Long? = null,
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

uploadMedia(apiKey, videoBytes, "video/mp4") { videoUploadResult ->
videoUploadResult.onSuccess { videoUrl ->
if (thumbnailBytes == null) {
createPost(
apiKey, caption, videoUrl, null, thumbnailTimestampMs, accounts.map { it.id }
) { postResult ->
postResult.onSuccess {
callback(Result.success(PublishResult(accounts.map { it.platform })))
}.onFailure { callback(Result.failure(it)) }
}
} else {
// Se a thumbnail falhar ao subir, publica mesmo assim sem ela —
// isso nunca deve travar a publicação do vídeo em si.
uploadMedia(apiKey, thumbnailBytes, "image/png") { thumbUploadResult ->
val thumbnailUrl = thumbUploadResult.getOrNull()
createPost(
apiKey, caption, videoUrl, thumbnailUrl, thumbnailTimestampMs, accounts.map { it.id }
) { postResult ->
postResult.onSuccess {
callback(Result.success(PublishResult(accounts.map { it.platform })))
}.onFailure { callback(Result.failure(it)) }
}
}
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

/**
* Sobe quaisquer bytes de mídia (vídeo ou imagem) pro Post for Me e devolve a
* media_url pronta pra usar num post. Reaproveitado tanto pro vídeo quanto pra
* imagem da thumbnail.
*/
private fun uploadMedia(
apiKey: String,
bytes: ByteArray,
mimeType: String,
callback: (Result<String>) -> Unit
) {
requestUploadUrl(apiKey) { uploadResult ->
uploadResult.onSuccess { (uploadUrl, mediaUrl) ->
uploadBytes(uploadUrl, bytes, mimeType) { putResult ->
putResult.onSuccess { callback(Result.success(mediaUrl)) }
.onFailure { callback(Result.failure(it)) }
}
}.onFailure { callback(Result.failure(it)) }
}
}

private fun uploadBytes(
uploadUrl: String,
bytes: ByteArray,
mimeType: String,
callback: (Result<Unit>) -> Unit
) {
val request = Request.Builder()
.url(uploadUrl)
.put(bytes.toRequestBody(mimeType.toMediaType()))
.build()

client.newCall(request).enqueue(object : Callback {
override fun onFailure(call: Call, e: IOException) {
callback(Result.failure(e))
}

override fun onResponse(call: Call, response: Response) {
response.use {
if (!it.isSuccessful) {
callback(Result.failure(IOException("Erro ao enviar mídia (HTTP ${it.code})")))
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
thumbnailUrl: String?,
thumbnailTimestampMs: Long?,
accountIds: List<String>,
callback: (Result<Unit>) -> Unit
) {
val mediaItem = JSONObject().put("url", mediaUrl)
if (thumbnailUrl != null) {
mediaItem.put("thumbnail_url", thumbnailUrl)
}
if (thumbnailTimestampMs != null) {
mediaItem.put("thumbnail_timestamp_ms", thumbnailTimestampMs)
}
val mediaArray = JSONArray().put(mediaItem)
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
