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
    private lateinit var btnRecordVideo: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnPublish: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var btnPostTikTok: Button
    private lateinit var btnPostInstagram: Button
    private lateinit var btnPostYouTube: Button

    private var latestVideoUri: Uri? = null

    // Cada plataforma abre com o mesmo vídeo + a mesma legenda gerada uma única vez.
    // "com.zhiliaoapp.musically"/"com.ss.android.ugc.trill" = TikTok, "com.instagram.android" = Instagram,
    // "com.google.android.youtube" = YouTube. Sem token/API oficial: é o Compartilhar padrão do Android,
    // o mesmo mecanismo que qualquer app de galeria usa.
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

    // Chamado quando volta da tela de gravação: pega o vídeo recém-gravado e já
    // manda gerar a legenda automaticamente, sem precisar de outro toque.
    private val recordLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uriString = result.data?.getStringExtra(RecordActivity.EXTRA_VIDEO_URI)
            if (uriString != null) {
                latestVideoUri = Uri.parse(uriString)
                videoInfoText.text = "Vídeo gravado agora — gerando legenda..."
                hidePostButtons()
                generateCaption()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        videoInfoText = findViewById(R.id.videoInfoText)
        statusText = findViewById(R.id.statusText)
        btnRecordVideo = findViewById(R.id.btnRecordVideo)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnPublish = findViewById(R.id.btnPublish)
        btnSettings = findViewById(R.id.btnSettings)
        btnPostTikTok = findViewById(R.id.btnPostTikTok)
        btnPostInstagram = findViewById(R.id.btnPostInstagram)
        btnPostYouTube = findViewById(R.id.btnPostYouTube)

        btnRecordVideo.setOnClickListener {
            recordLauncher.launch(Intent(this, RecordActivity::class.java))
        }
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
        // Recarrega ao voltar pro app, caso você tenha acabado de gravar um vídeo novo.
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
            Toast.makeText(this, "Nenhum vídeo encontrado. Toque em Gravar vídeo.", Toast.LENGTH_LONG).show()
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
            try {
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
                            btnPostTikTok.visibility = View.VISIBLE
                            btnPostInstagram.visibility = View.VISIBLE
                            btnPostYouTube.visibility = View.VISIBLE

                            val postForMeKey = prefs.getString(Prefs.KEY_POSTFORME_API_KEY, null)
                            if (!postForMeKey.isNullOrBlank()) {
                                statusText.text = "Legenda pronta. Publicando automaticamente..."
                                publishAutomatically(postForMeKey, bytes, caption)
                            } else {
                                statusText.text = "Legenda pronta (também copiada). Escolha onde publicar:"
                            }
                        }.onFailure { error ->
                            statusText.text = "Erro ao gerar legenda: ${error.message}"
                        }
                    }
                }
            } catch (t: Throwable) {
                // Rede de segurança: qualquer falha inesperada (inclusive falta de memória)
                // vira uma mensagem na tela em vez de fechar o app sozinho.
                runOnUiThread {
                    btnPublish.isEnabled = true
                    statusText.text = "Erro inesperado ao processar o vídeo: ${t.message ?: t.javaClass.simpleName}"
                }
            }
        }.start()
    }

    /**
     * Publicação automática via Post for Me: usa o vídeo e a legenda que o Gemini já
     * gerou e publica direto nas contas conectadas, sem precisar abrir cada app.
     * Os botões manuais continuam disponíveis como alternativa, caso algo falhe aqui.
     */
    private fun publishAutomatically(apiKey: String, videoBytes: ByteArray, caption: String) {
        try {
            PostForMeClient.publishVideo(apiKey, videoBytes, caption) { result ->
                runOnUiThread {
                    result.onSuccess { publishResult ->
                        val platforms = publishResult.publishedPlatforms.joinToString(", ")
                        statusText.text = "Publicado automaticamente em: $platforms ✅"
                    }.onFailure { error ->
                        statusText.text = "Legenda pronta (copiada), mas a publicação automática falhou: " +
                            "${error.message}. Publique manualmente:"
                    }
                }
            }
        } catch (t: Throwable) {
            runOnUiThread {
                statusText.text = "Legenda pronta (copiada), mas a publicação automática falhou: " +
                    "${t.message ?: t.javaClass.simpleName}. Publique manualmente:"
            }
        }
    }

    private fun hidePostButtons() {
        btnPostTikTok.visibility = View.GONE
        btnPostInstagram.visibility = View.GONE
        btnPostYouTube.visibility = View.GONE
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("legenda_gerada", text))
    }

    /**
     * Abre o app de destino já com o vídeo anexado, usando o Compartilhar padrão do Android
     * (o mesmo mecanismo que qualquer app de galeria usa) — sem precisar de API oficial nem
     * de app registrado em nenhuma das plataformas. A legenda é colada automaticamente pelo
     * CaptionAccessibilityService assim que a tela de descrição daquele app aparecer.
     */
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
                // tenta o próximo pacote conhecido
            }
        }

        // Se nenhum pacote conhecido funcionou, deixa você escolher manualmente.
        sendIntent.setPackage(null)
        startActivity(Intent.createChooser(sendIntent, "Compartilhar vídeo"))
    }
}
