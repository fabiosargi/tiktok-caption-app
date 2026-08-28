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
 * Cliente simples para a API do Gemini (generateContent), enviando o vídeo
  * gravado como inline_data em base64 e pedindo uma legenda pronta pro TikTok.
   *
    * Se o vídeo for grande (> ~15MB) essa chamada pode falhar com "request too large".
     * Nesse caso é preciso trocar pela Files API do Gemini (upload em duas etapas).
      */
object GeminiClient {

      private const val MODEL = "gemini-2.5-flash"
      private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

      private const val PROMPT = "Este é um vídeo (ou uma gravação de tela) que gravei narrando ao vivo " +
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
                      .writeTimeout(120, TimeUnit.SECONDS)
                              .readTimeout(120, TimeUnit.SECONDS)
                                      .build()

                                          fun generateCaption(apiKey: String, videoBytes: ByteArray, callback: (Result<String>) -> Unit) {
                                                    val base64Video = Base64.encodeToString(videoBytes, Base64.NO_WRAP)

                                                            val part1 = JSONObject().put("text", PROMPT)
                                                                    val part2 = JSONObject().put(
                                                                                  "inline_data",
                                                                                  JSONObject()
                                                                                                  .put("mime_type", "video/mp4")
                                                                                                                  .put("data", base64Video)
                                                                                                                          )
                                                                            val parts = JSONArray().put(part1).put(part2)
                                                                                    val content = JSONObject().put("parts", parts)
                                                                                            val body = JSONObject().put("contents", JSONArray().put(content))

                                                                                                    val requestBody = body.toString().toRequestBody("application/json".toMediaType())
                                                                                                    
                                                                                                            val request = Request.Builder()
                                                                                                                        .url("$ENDPOINT?key=$apiKey")
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
