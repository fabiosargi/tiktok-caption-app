package com.fabio.tiktokcaption

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

      private lateinit var videoInfoText: TextView
      private lateinit var statusText: TextView
      private lateinit var btnRefresh: Button
      private lateinit var btnPublish: Button
      private lateinit var btnSettings: ImageButton
      private lateinit var btnPostTikTok: Button
      private lateinit var btnPostInstagram: Button
      private lateinit var btnPostYouTube: Button

      private var latestVideoUri: Uri? = null

      private val tiktokPackages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
          private val instagramPackages = listOf("com.instagram.android")
              private val youtubePackages = listOf("com.google.android.youtube")

                  private val mediaPermission =
          if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
          else Manifest.permission.READ_EXTERNAL_STORAGE

      private val permissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
                    ) { granted ->
                if (granted) {
                              loadLatestVideo()
                } else {
                              statusText.text = "Preciso da permissão de mídia pra encontrar o último vídeo"
                }
      }

          override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                            setContentView(R.layout.activity_main)

                                    videoInfoText = findViewById(R.id.videoInfoText)
                                            statusText = findViewById(R.id.statusText)
                                                    btnRefresh = findViewById(R.id.btnRefresh)
                                                            btnPublish = findViewById(R.id.btnPublish)
                                                                    btnSettings = findViewById(R.id.btnSettings)
                                                                            btnPostTikTok = findViewById(R.id.btnPostTikTok)
                                                                                    btnPostInstagram = findViewById(R.id.btnPostInstagram)
                                                                                            btnPostYouTube = findViewById(R.id.btnPostYouTube)

                                                                                                    btnRefresh.setOnClickListener { checkPermissionAndLoad() }
                                                                                                            btnPublish.setOnClickListener { generateCaption() }
                                                                                                                    btnSettings.setOnClickListener {
                                                                                                                                  startActivity(Intent(this, SettingsActivity::class.java))
                                                                                                                    }
                                                                                                                            btnPostTikTok.setOnClickListener { latestVideoUri?.let { openAppWithVideo(it, tiktokPackages) } }
                                                                                                                                    btnPostInstagram.setOnClickListener { latestVideoUri?.let { openAppWithVideo(it, instagramPackages) } }
                                                                                                                                            btnPostYouTube.setOnClickListener { latestVideoUri?.let { openAppWithVideo(it, youtubePackages) } }
                                                                                                                                            
                                                                                                                                                    checkPermissionAndLoad()
          }

              override fun onResume() {
                        super.onResume()
                                if (ContextCompat.checkSelfPermission(this, mediaPermission) == PackageManager.PERMISSION_GRANTED) {
                                              loadLatestVideo()
                                }
              }

                  private fun checkPermissionAndLoad() {
                            if (ContextCompat.checkSelfPermission(this, mediaPermission) == PackageManager.PERMISSION_GRANTED) {
                                          loadLatestVideo()
                            } else {
                                          permissionLauncher.launch(mediaPermission)
                            }
                  }

                      private fun loadLatestVideo() {
                                val projection = arrayOf(
                                              MediaStore.Video.Media._ID,
                                              MediaStore.Video.Media.DISPLAY_NAME,
                                              MediaStore.Video.Media.DATE_ADDED
                                          )
                                        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

                                contentResolver.query(
                                              MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                              projection,
                                              null,
                                              null,
                                              sortOrder
                                          )?.use { cursor ->
                                              if (cursor.moveToFirst()) {
                                                                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                                                                                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                                                                                                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                                                                                                                val id = cursor.getLong(idCol)
                                                                                                                                val name = cursor.getString(nameCol) ?: "vídeo"
                                                                val dateSeconds = cursor.getLong(dateCol)

                                                                                latestVideoUri = ContentUris.withAppendedId(
                                                                                                      MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                                                                                                  )

                                                                                                val dateStr = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                                                                                                                    .format(Date(dateSeconds * 1000))
                                                                                                                                    videoInfoText.text = "$name — $dateStr"
                                              } else {
                                                                latestVideoUri = null
                                                                videoInfoText.text = "Nenhum vídeo encontrado na galeria"
                                              }
                                }
                      }

                          private fun generateCaption() {
                                    val uri = latestVideoUri
                                    if (uri == null) {
                                                  Toast.makeText(this, "Nenhum vídeo encontrado. Grave um vídeo e toque em Atualizar.", Toast.LENGTH_LONG).show()
                                                              return
                                    }

                                            val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                                                    val apiKey = prefs.getString(Prefs.KEY_API_KEY, null)
                                                            if (apiKey.isNullOrBlank()) {
                                                                          Toast.makeText(this, "Configure sua chave do Gemini primeiro", Toast.LENGTH_LONG).show()
                                                                                      startActivity(Intent(this, SettingsActivity::class.java))
                                                                                                  return
                                                            }

                                                                    btnPublish.isEnabled = false
                                    statusText.text = "Lendo vídeo e gerando legenda com a IA..."

                                    Thread {
                                                  val bytes = try {
                                                                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                                  } catch (e: Exception) {
                                                                    null
                                                  }

                                                              if (bytes == null) {
                                                                                runOnUiThread {
                                                                                                      btnPublish.isEnabled = true
                                                                                                      statusText.text = "Não consegui ler o arquivo de vídeo"
                                                                                }
                                                                                                return@Thread
                                                              }

                                                                          GeminiClient.generateCaption(apiKey, bytes) { result ->
                                                                                            runOnUiThread {
                                                                                                                  btnPublish.isEnabled = true
                                                                                                                  result.onSuccess { caption ->
                                                                                                                                            prefs.edit().putString(Prefs.KEY_PENDING_CAPTION, caption).apply()
                                                                                                                                                                    copyToClipboard(caption)
                                                                                                                                                                                            statusText.text = "Legenda pronta (também copiada). Escolha onde publicar:"
                                                                                                                                            btnPostTikTok.visibility = View.VISIBLE
                                                                                                                                            btnPostInstagram.visibility = View.VISIBLE
                                                                                                                                            btnPostYouTube.visibility = View.VISIBLE
                                                                                                                  }.onFailure { error ->
                                                                                                                                            statusText.text = "Erro ao gerar legenda: ${error.message}"
                                                                                                                  }
                                                                                            }
                                                                          }
                                    }.start()
                          }

                              private fun copyToClipboard(text: String) {
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("legenda_gerada", text))
                              }

                                  private fun openAppWithVideo(uri: Uri, packages: List<String>) {
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                          type = "video/*"
                                                          putExtra(Intent.EXTRA_STREAM, uri)
                                                                      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }

                                                    for (pkg in packages) {
                                                                  try {
                                                                                    sendIntent.setPackage(pkg)
                                                                                                    startActivity(sendIntent)
                                                                                                                    return
                                                                  } catch (e: ActivityNotFoundException) {
                                                                  }
                                                    }

                                                            sendIntent.setPackage(null)
                                                                    startActivity(Intent.createChooser(sendIntent, "Compartilhar vídeo"))
                                  }
}
