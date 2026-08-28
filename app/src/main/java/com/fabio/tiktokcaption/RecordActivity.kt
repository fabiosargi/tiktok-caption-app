package com.fabio.tiktokcaption

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnRecord: Button
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var timerText: TextView

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var isRecording = false
    private var startTimeMs = 0L
    private val timerHandler = Handler(Looper.getMainLooper())

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startCamera()
        } else {
            Toast.makeText(this, "Preciso de câmera e microfone pra gravar", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mantém a tela ligada enquanto a câmera está em uso, pra não escurecer/travar
        // no meio de uma gravação (o Android trata isso como tela ociosa senão).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_record)

        previewView = findViewById(R.id.previewView)
        btnRecord = findViewById(R.id.btnRecord)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnClose = findViewById(R.id.btnClose)
        timerText = findViewById(R.id.timerText)

        btnRecord.setOnClickListener { if (isRecording) stopRecording() else startRecording() }
        btnSwitchCamera.setOnClickListener { switchCamera() }
        btnClose.setOnClickListener { finish() }

        if (hasPermissions()) {
            startCamera()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun hasPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            // Liga a estabilização de vídeo nativa do Android (a mesma tecnologia
            // que o app de Câmera padrão usa) direto na captura — sem processamento
            // extra depois de gravar. Se o aparelho não suportar esse modo, o
            // Android simplesmente ignora a opção; nunca trava a gravação por isso.
            val videoCaptureBuilder = VideoCapture.Builder(recorder)
            try {
                Camera2Interop.Extender(videoCaptureBuilder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                )
            } catch (e: Exception) {
                // Segue sem estabilização se não for suportado nesse aparelho.
            }
            videoCapture = videoCaptureBuilder.build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao abrir a câmera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        if (isRecording) return
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        startCamera()
    }

    private fun startRecording() {
        val capture = videoCapture ?: return

        val name = "LegendaTikTok_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LegendaTikTok")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val pendingRecording = capture.output.prepareRecording(this, outputOptions).apply {
            if (hasAudioPermission) withAudioEnabled()
        }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isRecording = true
                    startTimeMs = System.currentTimeMillis()
                    btnRecord.text = "Parar"
                    btnSwitchCamera.isEnabled = false
                    tickTimer()
                }
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    btnRecord.text = "Gravar"
                    btnSwitchCamera.isEnabled = true
                    timerHandler.removeCallbacksAndMessages(null)
                    timerText.text = "00:00"

                    if (!event.hasError()) {
                        val uri = event.outputResults.outputUri
                        val resultIntent = Intent().putExtra(EXTRA_VIDEO_URI, uri.toString())
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    } else {
                        Toast.makeText(this, "Erro ao gravar: ${event.error}", Toast.LENGTH_LONG).show()
                    }
                }
                else -> {}
            }
        }
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    private fun tickTimer() {
        if (!isRecording) return
        val elapsedSeconds = (System.currentTimeMillis() - startTimeMs) / 1000
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        timerText.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        timerHandler.postDelayed({ tickTimer() }, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        activeRecording?.stop()
    }

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
    }
}
