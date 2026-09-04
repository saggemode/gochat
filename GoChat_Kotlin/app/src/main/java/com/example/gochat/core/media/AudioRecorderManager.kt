package com.example.gochat.core.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import java.io.File

data class VoiceRecordingResult(
    val file: File,
    val durationSeconds: Int,
    val base64DataUri: String
)

class AudioRecorderManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var recordingStartTime = 0L

    var isRecording: Boolean = false
        private set

    fun startRecording(): Boolean {
        if (isRecording) return false

        return try {
            val voiceDir = File(context.cacheDir, "voice_notes").apply {
                if (!exists()) mkdirs()
            }
            val outputFile = File(voiceDir, "voice_${System.currentTimeMillis()}.m4a")
            currentOutputFile = outputFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            recordingStartTime = System.currentTimeMillis()
            isRecording = true
            true
        } catch (e: Exception) {
            cancelRecording()
            false
        }
    }

    fun stopRecording(): VoiceRecordingResult? {
        if (!isRecording) return null

        val recorder = mediaRecorder ?: return null
        val file = currentOutputFile ?: return null

        return try {
            recorder.stop()
            recorder.release()
            mediaRecorder = null
            isRecording = false

            val durationMs = System.currentTimeMillis() - recordingStartTime
            val durationSeconds = (durationMs / 1000L).toInt().coerceAtLeast(1)

            if (file.exists() && file.length() > 0) {
                val bytes = file.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val dataUri = "data:audio/m4a;base64,$base64"

                VoiceRecordingResult(
                    file = file,
                    durationSeconds = durationSeconds,
                    base64DataUri = dataUri
                )
            } else {
                null
            }
        } catch (e: Exception) {
            cancelRecording()
            null
        }
    }

    fun cancelRecording() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}

        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}

        mediaRecorder = null
        isRecording = false

        currentOutputFile?.let {
            if (it.exists()) it.delete()
        }
        currentOutputFile = null
    }

    fun getMaxAmplitude(): Int {
        return try {
            if (isRecording) mediaRecorder?.maxAmplitude ?: 0 else 0
        } catch (_: Exception) {
            0
        }
    }
}
