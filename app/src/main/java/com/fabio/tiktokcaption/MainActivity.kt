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
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

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

    // Preenchidos quando o tratamento do vídeo + legenda + thumbnail ficam prontos.
    // Só são usados quando o usuário toca em "Publicar" — a publicação automática
    // nunca dispara sozinha, assim sempre dá pra conferir antes de ir pro ar.
    private var pendingVideoBytes: ByteArray? = null
    private var pendingCaption: String? = null
    private var pendingThumbnailBytes: ByteArray? = null
    private var readyToPublish = false

    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }

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
    // manda gerar a legenda automaticamente, sem precisar de outro toque. A
    // publicação em si continua exigindo confirmação manual depois.
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
        btnPublish.setOnClickListener {
            if (readyToPublish) publishNow() else generateCaption()
        }
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

        val apiKey = prefs.getString(Prefs.KEY_API_KEY, null)
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, "Configure sua chave do Gemini primeiro", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        readyToPublish = false
        pendingVideoBytes = null
        pendingCaption = null
        pendingThumbnailBytes = null
        btnPublish.isEnabled = false
        btnPublish.text = "Gerando..."
        hidePostButtons()
        statusText.text = "Tratando o vídeo e gerando a legenda com IA..."

        // O tratamento leve do vídeo e a extração de áudio + legenda pela IA rodam em
        // paralelo: os dois começam assim que a gravação termina. Só quando AMBOS
        // terminarem é que os dados ficam prontos pra publicar — em vez de esperar
        // um terminar pra começar o outro.
        val pending = AtomicInteger(2)
        var treatedVideoFile: File? = null
        var originalBytes: ByteArray? = null
        var captionResult: Result<String>? = null
        var thumbnailBytes: ByteArray? = null

        fun finishIfReady() {
            if (pending.decrementAndGet() != 0) return
            runOnUiThread {
                val result = captionResult
                val caption = result?.getOrNull()
                if (caption == null) {
                    btnPublish.isEnabled = true
                    btnPublish.text = "Gerar legenda com IA"
                    statusText.text = "Erro ao gerar legenda: ${result?.exceptionOrNull()?.message ?: "erro desconhecido"}"
                    return@runOnUiThread
                }

                prefs.edit().putString(Prefs.KEY_PENDING_CAPTION, caption).apply()
                copyToClipboard(caption)
                btnPostTikTok.visibility = View.VISIBLE
                btnPostInstagram.visibility = View.VISIBLE
                btnPostYouTube.visibility = View.VISIBLE
                btnPublish.isEnabled = true

                val videoBytes = treatedVideoFile?.let { file ->
                    try { file.readBytes() } catch (e: Exception) { null }
                } ?: originalBytes

                if (videoBytes == null) {
                    btnPublish.text = "Gerar legenda com IA"
                    statusText.text = "Legenda pronta (copiada), mas não consegui ler o vídeo pra publicar."
                    return@runOnUiThread
                }

                val postForMeKey = prefs.getString(Prefs.KEY_POSTFORME_API_KEY, null)
                if (!postForMeKey.isNullOrBlank()) {
                    pendingVideoBytes = videoBytes
                    pendingCaption = caption
                    pendingThumbnailBytes = thumbnailBytes
                    readyToPublish = true
                    btnPublish.text = "Publicar automaticamente"
                    statusText.text = "Tudo pronto! Confira a legenda" +
                        (if (thumbnailBytes != null) " e a thumbnail" else "") +
                        " e toque em Publicar quando quiser postar."
                } else {
                    btnPublish.text = "Gerar legenda com IA"
                    statusText.text = "Legenda pronta (também copiada). Escolha onde publicar:"
                }
            }
        }

        // 1) Tratamento leve de qualidade do vídeo (mais contraste e saturação), feito
        // no próprio aparelho. Se falhar por qualquer motivo (inclusive se sair mais
        // curto que o original), seguimos com o vídeo original.
        VideoEnhancer.enhance(this, uri) { result ->
            treatedVideoFile = result.getOrNull()
            finishIfReady()
        }

        // 2) Extração de áudio + legenda pela IA e, depois, a thumbnail com IA baseada
        // nessa legenda — tudo em background.
        Thread {
            var audioFile: File? = null
            try {
                val bytes = try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    null
                }
                originalBytes = bytes

                if (bytes == null) {
                    captionResult = Result.failure(IOException("Não consegui ler o arquivo de vídeo"))
                    finishIfReady()
                    return@Thread
                }

                audioFile = try {
                    AudioExtractor.extractAudioTrack(this, uri)
                } catch (e: Exception) {
                    null
                }

                val mediaBytes: ByteArray
                val mimeType: String
                if (audioFile != null) {
                    mediaBytes = audioFile.readBytes()
                    mimeType = "audio/mp4"
                } else {
                    mediaBytes = bytes
                    mimeType = "video/mp4"
                }

                GeminiClient.generateCaption(apiKey, mediaBytes, mimeType) { result ->
                    captionResult = result
                    val caption = result.getOrNull()
                    if (caption != null) {
                        // A thumbnail depende do texto da legenda, então só começa depois
                        // que ela fica pronta — mas não trava a publicação se falhar. O
                        // título que aparece por cima da imagem também sai dessa legenda.
                        GeminiClient.generateThumbnail(apiKey, caption) { thumbResult ->
                            thumbnailBytes = thumbResult.getOrNull()?.let { bytes ->
                                try {
                                    ThumbnailComposer.addTitleOverlay(bytes, caption)
                                } catch (e: Exception) {
                                    bytes
                                }
                            }
                            finishIfReady()
                        }
                    } else {
                        finishIfReady()
                    }
                }
            } catch (t: Throwable) {
                // Rede de segurança: qualquer falha inesperada (inclusive falta de memória)
                // vira uma mensagem na tela em vez de fechar o app sozinho.
                captionResult = Result.failure(t)
                finishIfReady()
            } finally {
                audioFile?.delete()
            }
        }.start()
    }

    /**
     * Chamado só quando o usuário toca em "Publicar", depois de já ter conferido a
     * legenda (e a thumbnail, se veio uma). A publicação nunca dispara sozinha.
     */
    private fun publishNow() {
        val caption = pendingCaption ?: return
        val videoBytes = pendingVideoBytes ?: return
        val postForMeKey = prefs.getString(Prefs.KEY_POSTFORME_API_KEY, null)
        if (postForMeKey.isNullOrBlank()) return

        readyToPublish = false
        btnPublish.isEnabled = false
        btnPublish.text = "Publicando..."
        statusText.text = "Publicando..."
        publishAutomatically(postForMeKey, videoBytes, caption, pendingThumbnailBytes)
    }

    /**
     * Publicação automática via Post for Me: usa o vídeo (já tratado, se o tratamento
     * deu certo), a legenda e a thumbnail (se o Gemini gerou uma) e publica direto nas
     * contas conectadas, sem precisar abrir cada app. Os botões manuais continuam
     * disponíveis como alternativa, caso algo falhe aqui.
     */
    private fun publishAutomatically(
        apiKey: String,
        videoBytes: ByteArray,
        caption: String,
        thumbnailBytes: ByteArray?
    ) {
        try {
            PostForMeClient.publishVideo(apiKey, videoBytes, caption, thumbnailBytes) { result ->
                runOnUiThread {
                    btnPublish.isEnabled = true
                    result.onSuccess { publishResult ->
                        btnPublish.text = "Gerar legenda com IA"
                        val platforms = publishResult.publishedPlatforms.joinToString(", ")
                        statusText.text = "Publicado automaticamente em: $platforms ✅"
                    }.onFailure { error ->
                        readyToPublish = true
                        btnPublish.text = "Publicar automaticamente"
                        statusText.text = "A publicação automática falhou: ${error.message}. " +
                            "Toque em Publicar pra tentar de novo, ou publique manualmente:"
                    }
                }
            }
        } catch (t: Throwable) {
            runOnUiThread {
                btnPublish.isEnabled = true
                readyToPublish = true
                btnPublish.text = "Publicar automaticamente"
                statusText.text = "A publicação automática falhou: ${t.message ?: t.javaClass.simpleName}. " +
                    "Toque em Publicar pra tentar de novo, ou publique manualmente:"
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
     * de app registrado em nenhuma das plataformas. Manda a legenda junto via EXTRA_TEXT: os
     * apps que aceitam texto compartilhado (a maioria) já abrem com a descrição preenchida;
     * de qualquer forma ela também fica copiada na área de transferência como reforço, já
     * que nem todo app lê o EXTRA_TEXT pra pré-preencher o campo de descrição.
     */
    private fun openAppWithVideo(uri: Uri, packages: List<String>) {
        val caption = prefs.getString(Prefs.KEY_PENDING_CAPTION, null)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!caption.isNullOrBlank()) {
                putExtra(Intent.EXTRA_TEXT, caption)
            }
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
