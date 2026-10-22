package com.example.gochat.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.example.gochat.databinding.ActivityCameraCaptureBinding
import android.content.ContentValues
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * WhatsApp-style in-app camera: tap the shutter for a photo, press-and-hold to record a
 * video (release to stop). Returns the captured media to the caller:
 *   result extras — [EXTRA_RESULT_URI] (String), [EXTRA_RESULT_TYPE] ("photo" | "video").
 */
class CameraCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT_URI = "extra_result_uri"
        const val EXTRA_RESULT_TYPE = "extra_result_type"
        private const val HOLD_THRESHOLD_MS = 250L
    }

    private lateinit var binding: ActivityCameraCaptureBinding

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val holdHandler = Handler(Looper.getMainLooper())
    private var isRecording = false
    private var recordStartMs = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - recordStartMs) / 1000
            binding.tvRecTimer.text = String.format(Locale.US, "● %02d:%02d", elapsed / 60, elapsed % 60)
            timerHandler.postDelayed(this, 500)
        }
    }

    private val startRecordingRunnable = Runnable { startRecording() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCloseCamera.setOnClickListener { finish() }
        binding.btnFlipCamera.setOnClickListener { flipCamera() }
        setupShutter()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Suppress("ClickableViewAccessibility")
    private fun setupShutter() {
        binding.btnShutter.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(120).start()
                    // Hold past the threshold → start recording. A quick release before that = photo.
                    holdHandler.postDelayed(startRecordingRunnable, HOLD_THRESHOLD_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    holdHandler.removeCallbacks(startRecordingRunnable)
                    if (isRecording) {
                        stopRecording()
                    } else {
                        takePhoto()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bindUseCases(provider)
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to start camera", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera binding failed", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun flipCamera() {
        if (isRecording) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        cameraProvider?.let { bindUseCases(it) }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val photoFile = File(File(cacheDir, "images").apply { mkdirs() }, "cam_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri ?: Uri.fromFile(photoFile)
                    finishWithResult(uri.toString(), "photo")
                }
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@CameraCaptureActivity, "Failed to capture photo", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun startRecording() {
        val capture = videoCapture ?: return
        if (isRecording) return

        val name = "cam_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        }
        val outputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        var pending = capture.output.prepareRecording(this, outputOptions)
        if (hasAudioPermission()) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isRecording = true
                    recordStartMs = System.currentTimeMillis()
                    binding.tvRecTimer.visibility = View.VISIBLE
                    binding.tvCameraHint.visibility = View.GONE
                    binding.viewShutterInner.animate().scaleX(0.6f).scaleY(0.6f).setDuration(150).start()
                    timerHandler.post(timerRunnable)
                }
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    timerHandler.removeCallbacks(timerRunnable)
                    if (!event.hasError()) {
                        finishWithResult(event.outputResults.outputUri.toString(), "video")
                    } else {
                        Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
                        binding.tvRecTimer.visibility = View.GONE
                        binding.tvCameraHint.visibility = View.VISIBLE
                    }
                }
                else -> {}
            }
        }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
    }

    private fun finishWithResult(uri: String, type: String) {
        setResult(RESULT_OK, intent.apply {
            putExtra(EXTRA_RESULT_URI, uri)
            putExtra(EXTRA_RESULT_TYPE, type)
        })
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (isRecording) stopRecording()
    }

    override fun onDestroy() {
        holdHandler.removeCallbacks(startRecordingRunnable)
        timerHandler.removeCallbacks(timerRunnable)
        cameraProvider?.unbindAll()
        super.onDestroy()
    }
}
