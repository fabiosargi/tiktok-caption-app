package com.fabio.tiktokcaption

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import java.time.Instant
import java.util.Calendar
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
    private lateinit var scheduleStatusText: TextView
    private lateinit var btnSchedule: Button
    private lateinit var btnCancelSchedule: TextView

    private var latestVideoUri: Uri? = null

    // Dia/horário escolhido pra agendar a publicação (opcional). Se ficar nulo,
    // publica assim que a geração terminar — como sempre foi.
    private var scheduledAtMillis: Long? = null

    // Guardados só pra permitir tentar de novo manualmente (tocando no botão) se a
    // publicação automática falhar — a publicação em si já dispara sozinha assim
    // que o tratamento do vídeo + legenda + thumbnail terminam de ser gerados, sem
    // precisar de nenhum toque extra além do toque inicial em "Gerar legenda com IA".
    private var pendingVideoFile: File? = null
    private var pendingCaption: String? = null
    private var pendingThumbnailBytes: ByteArray? = null
    private var pendingThumbnailTimestampMs: Long? = null
    private var pendingScheduledAtMillis: Long? = null
    private var awaitingRetry = false

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

    // Chamado quando volta da tela de gravação: só guarda o vídeo recém-gravado e
    // deixa a tela pronta pra você escolher um dia/horário (se quiser) ANTES de
    // tocar em "Gerar legenda com IA" — é esse toque que dispara tudo de uma vez
    // (tratamento do vídeo, legenda pela IA e a publicação ou o agendamento no
    // final), sem precisar de nenhum toque extra depois disso. Gerar sozinho aqui,
    // sem esperar esse toque, não deixava tempo de escolher o horário antes.
    private val recordLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uriString = result.data?.getStringExtra(RecordActivity.EXTRA_VIDEO_URI)
            if (uriString != null) {
                latestVideoUri = Uri.parse(uriString)
                videoInfoText.text = "Vídeo gravado agora"
                hidePostButtons()
                awaitingRetry = false
                btnPublish.isEnabled = true
                btnPublish.text = "Gerar legenda com IA"
                statusText.text = "Vídeo pronto. Se quiser, escolha o dia e horário antes de tocar em \"Gerar legenda com IA\"."
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
        scheduleStatusText = findViewById(R.id.scheduleStatusText)
        btnSchedule = findViewById(R.id.btnSchedule)
        btnCancelSchedule = findViewById(R.id.btnCancelSchedule)

        btnRecordVideo.setOnClickListener {
            recordLauncher.launch(Intent(this, RecordActivity::class.java))
        }
        btnRefresh.setOnClickListener { checkPermissionAndLoad() }
        btnPublish.setOnClickListener {
            if (awaitingRetry) retryPublish() else generateCaption()
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnPostTikTok.setOnClickListener { latestVideoUri?.let { openAppWithVideo(it, tiktokPackages) } }
        btnPostInstagram.setOnClickListener { latestVideoUri?.let { openAppWithVideo(it, instagramPackages) } }
        btnPostYouTube.setOnClickListener { latestVideoUri?.let { openAppWithVideo(it, youtubePackages) } }
        btnSchedule.setOnClickListener { pickScheduleDateTime() }
        btnCancelSchedule.setOnClickListener {
            scheduledAtMillis = null
            updateScheduleUi()
        }

        updateScheduleUi()
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

    /**
     * Abre um seletor de dia e depois de horário pra agendar a publicação. Só é
     * usado se você quiser agendar — deixando sem escolher, publica assim que a
     * IA terminar de gerar, como sempre.
     */
    private fun pickScheduleDateTime() {
        val now = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        picked.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        picked.set(Calendar.MINUTE, minute)
                        picked.set(Calendar.SECOND, 0)
                        picked.set(Calendar.MILLISECOND, 0)
                        if (picked.timeInMillis <= System.currentTimeMillis()) {
                            Toast.makeText(this, "Escolha um horário no futuro", Toast.LENGTH_SHORT).show()
                        } else {
                            scheduledAtMillis = picked.timeInMillis
                            updateScheduleUi()
                        }
                    },
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    true
                ).show()
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateScheduleUi() {
        val millis = scheduledAtMillis
        if (millis == null) {
            scheduleStatusText.text = "Publica assim que a IA terminar de gerar"
            btnCancelSchedule.visibility = View.GONE
            btnSchedule.text = "Escolher dia e horário"
        } else {
            val dateStr = SimpleDateFormat("dd/MM 'às' HH:mm", Locale.getDefault()).format(Date(millis))
            scheduleStatusText.text = "Agendado para $dateStr"
            btnCancelSchedule.visibility = View.VISIBLE
            btnSchedule.text = "Alterar horário"
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

        awaitingRetry = false
        pendingVideoFile = null
        pendingCaption = null
        pendingThumbnailBytes = null
        pendingThumbnailTimestampMs = null
        // Congela o agendamento escolhido até aqui pra essa geração específica, e já
        // limpa a seleção na tela pra não reaproveitar sem querer no próximo vídeo.
        pendingScheduledAtMillis = scheduledAtMillis
        scheduledAtMillis = null
        updateScheduleUi()

        btnPublish.isEnabled = false
        btnPublish.text = "Gerando..."
        hidePostButtons()
        statusText.text = "Tratando o vídeo e gerando a legenda com IA..."

        // O tratamento leve do vídeo e a extração de áudio + legenda pela IA rodam em
        // paralelo: os dois começam assim que você toca em "Gerar legenda com IA". Só
        // quando AMBOS terminarem é que os dados ficam prontos — e a publicação (ou o
        // agendamento) dispara sozinha na hora, sem esperar nenhum toque extra.
        val pending = AtomicInteger(2)
        var treatedVideoFile: File? = null
        var originalVideoFile: File? = null
        var captionResult: Result<String>? = null
        var bestFrame: FrameSelector.BestFrame? = null

        fun finishIfReady() {
            if (pending.decrementAndGet() != 0) return

            val caption = captionResult?.getOrNull()
            val frame = bestFrame

            // A thumbnail (pra Instagram/YouTube) é sempre um frame real do próprio
            // vídeo — nunca uma imagem desenhada por IA generativa — com só um texto
            // curto por cima (o gancho da legenda). O TikTok usa o mesmo frame direto,
            // via thumbnail_timestamp_ms, sem overlay (a API dele não aceita imagem
            // customizada).
            val thumbBytes = if (caption != null && frame != null) {
                try {
                    ThumbnailComposer.addTitleOverlay(frame.pngBytes, caption)
                } catch (e: Exception) {
                    frame.pngBytes
                }
            } else {
                frame?.pngBytes
            }

            runOnUiThread {
                if (caption == null) {
                    btnPublish.isEnabled = true
                    btnPublish.text = "Gerar e publicar automaticamente"
                    statusText.text = "Erro ao gerar legenda: ${captionResult?.exceptionOrNull()?.message ?: "erro desconhecido"}"
                    return@runOnUiThread
                }

                prefs.edit().putString(Prefs.KEY_PENDING_CAPTION, caption).apply()
                copyToClipboard(caption)
                btnPostTikTok.visibility = View.VISIBLE
                btnPostInstagram.visibility = View.VISIBLE
                btnPostYouTube.visibility = View.VISIBLE

                // Nunca lemos o vídeo inteiro pra memória aqui — é exatamente isso que
                // causava o OutOfMemoryError ("Failed to allocate ... byte allocation")
                // que às vezes aparecia na segunda geração seguida: o vídeo tratado e o
                // original, cada um podendo ter dezenas/centenas de MB, ficavam os dois
                // como ByteArray na RAM ao mesmo tempo. Agora só passamos o arquivo em si
                // adiante, e o upload lê ele aos poucos direto do disco.
                val videoFile = treatedVideoFile ?: originalVideoFile

                if (videoFile == null) {
                    btnPublish.isEnabled = true
                    btnPublish.text = "Gerar e publicar automaticamente"
                    statusText.text = "Legenda pronta (copiada), mas não consegui ler o vídeo pra publicar."
                    return@runOnUiThread
                }

                val postForMeKey = prefs.getString(Prefs.KEY_POSTFORME_API_KEY, null)
                if (postForMeKey.isNullOrBlank()) {
                    btnPublish.isEnabled = true
                    btnPublish.text = "Gerar legenda com IA"
                    statusText.text = "Legenda pronta (também copiada). Escolha onde publicar:"
                    return@runOnUiThread
                }

                pendingVideoFile = videoFile
                pendingCaption = caption
                pendingThumbnailBytes = thumbBytes
                pendingThumbnailTimestampMs = frame?.timestampMs

                val scheduledMillis = pendingScheduledAtMillis
                btnPublish.isEnabled = false
                btnPublish.text = if (scheduledMillis != null) "Agendando..." else "Publicando..."
                statusText.text = if (scheduledMillis != null) "Agendando a publicação..." else "Publicando automaticamente..."

                publishAutomatically(postForMeKey, videoFile, caption, thumbBytes, frame?.timestampMs, scheduledMillis)
            }
        }

        // 1) Tratamento leve de qualidade do vídeo (mais contraste e saturação), feito
        // no próprio aparelho. Se falhar por qualquer motivo (inclusive se sair mais
        // curto que o original), seguimos com o vídeo original.
        //
        // Logo depois, ainda em paralelo com a legenda: escolhe (sem nenhuma IA
        // generativa, só análise local no aparelho) o melhor instante do vídeo — e já
        // extrai o frame correspondente — pra servir de capa, já que a API do TikTok
        // não aceita subir uma imagem customizada, só um recorte de dentro do próprio
        // vídeo.
        VideoEnhancer.enhance(this, uri) { result ->
            treatedVideoFile = result.getOrNull()
            Thread {
                bestFrame = try {
                    val path = treatedVideoFile?.absolutePath
                    if (path != null) {
                        FrameSelector.pickBestFrame(path)
                    } else {
                        FrameSelector.pickBestFrame(this, uri)
                    }
                } catch (e: Exception) {
                    null
                }
                finishIfReady()
            }.start()
        }

        // 2) Extração de áudio + legenda pela IA, em background.
        Thread {
            var audioFile: File? = null
            try {
                // Copia o vídeo original pro cache aos poucos (buffer pequeno), sem
                // nunca montar ele inteiro como ByteArray na memória — antes isso lia
                // o vídeo inteiro pra RAM sempre, mesmo quando a extração de áudio
                // funcionava e ele nem era usado, o que podia estourar a memória do
                // aparelho num vídeo grande (era a causa do OutOfMemoryError).
                val videoFile = copyUriToCacheFile(uri)
                originalVideoFile = videoFile

                if (videoFile == null) {
                    captionResult = Result.failure(IOException("Não consegui ler o arquivo de vídeo"))
                    finishIfReady()
                    return@Thread
                }

                audioFile = try {
                    AudioExtractor.extractAudioTrack(this, uri)
                } catch (e: Exception) {
                    null
                }

                val mediaFile: File
                val mimeType: String
                if (audioFile != null) {
                    mediaFile = audioFile
                    mimeType = "audio/mp4"
                } else {
                    mediaFile = videoFile
                    mimeType = "video/mp4"
                }

                GeminiClient.generateCaption(apiKey, mediaFile, mimeType) { result ->
                    captionResult = result
                    finishIfReady()
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
     * Copia o conteúdo do vídeo (via Uri) pra um arquivo temporário no cache aos
     * poucos, com um buffer pequeno de cada vez — nunca monta o vídeo inteiro
     * como ByteArray na memória. Isso é o que evita o OutOfMemoryError que podia
     * acontecer ao ler um vídeo grande inteiro pra RAM (às vezes até duas vezes
     * ao mesmo tempo: o original e o já tratado).
     */
    private fun copyUriToCacheFile(uri: Uri): File? {
        return try {
            val file = File.createTempFile("video_original_", ".mp4", cacheDir)
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                file.delete()
                return null
            }
            input.use { inStream ->
                file.outputStream().use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /** Tenta publicar de novo com os mesmos dados já gerados, depois de uma falha. */
    private fun retryPublish() {
        val caption = pendingCaption ?: return
        val videoFile = pendingVideoFile ?: return
        val postForMeKey = prefs.getString(Prefs.KEY_POSTFORME_API_KEY, null)
        if (postForMeKey.isNullOrBlank()) return

        awaitingRetry = false
        val scheduledMillis = pendingScheduledAtMillis
        btnPublish.isEnabled = false
        btnPublish.text = if (scheduledMillis != null) "Agendando..." else "Publicando..."
        statusText.text = "Tentando de novo..."
        publishAutomatically(postForMeKey, videoFile, caption, pendingThumbnailBytes, pendingThumbnailTimestampMs, scheduledMillis)
    }

    /**
     * Publicação automática via Post for Me: usa o vídeo (já tratado, se o tratamento
     * deu certo), a legenda, a thumbnail (um frame real do vídeo com um texto curto
     * por cima, pra Instagram/YouTube), o instante de capa escolhido analiticamente
     * (pro TikTok) e, se um dia/horário foi escolhido, agenda em vez de publicar na
     * hora — e publica direto nas contas conectadas, sem precisar abrir cada app nem
     * de nenhum toque extra depois de gerar. Os botões manuais continuam disponíveis
     * como alternativa, caso algo falhe aqui.
     */
    private fun publishAutomatically(
        apiKey: String,
        videoFile: File,
        caption: String,
        thumbnailBytes: ByteArray?,
        thumbnailTimestampMs: Long?,
        scheduledAtMillis: Long?
    ) {
        val scheduledAtIso = scheduledAtMillis?.let { Instant.ofEpochMilli(it).toString() }
        val scheduledDateStr = scheduledAtMillis?.let {
            SimpleDateFormat("dd/MM 'às' HH:mm", Locale.getDefault()).format(Date(it))
        }
        try {
            PostForMeClient.publishVideo(
                apiKey, videoFile, caption, thumbnailBytes, thumbnailTimestampMs, scheduledAtIso
            ) { result ->
                runOnUiThread {
                    btnPublish.isEnabled = true
                    result.onSuccess { publishResult ->
                        awaitingRetry = false
                        btnPublish.text = "Gerar e publicar automaticamente"
                        val platforms = publishResult.publishedPlatforms.joinToString(", ")
                        statusText.text = if (scheduledDateStr != null) {
                            "Agendado com sucesso pra $scheduledDateStr em: $platforms ✅"
                        } else {
                            "Publicado automaticamente em: $platforms ✅"
                        }
                    }.onFailure { error ->
                        awaitingRetry = true
                        btnPublish.text = "Tentar publicar de novo"
                        statusText.text = "A ${if (scheduledAtMillis != null) "agenda" else "publicação"} automática falhou: " +
                            "${error.message}. Toque no botão pra tentar de novo, ou publique manualmente:"
                    }
                }
            }
        } catch (t: Throwable) {
            runOnUiThread {
                btnPublish.isEnabled = true
                awaitingRetry = true
                btnPublish.text = "Tentar publicar de novo"
                statusText.text = "A publicação automática falhou: ${t.message ?: t.javaClass.simpleName}. " +
                    "Toque no botão pra tentar de novo, ou publique manualmente:"
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
