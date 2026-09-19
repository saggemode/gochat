package com.example.gochat.core.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.File

data class VoiceRecordingResult(
    val file: File,
    val durationSeconds: Int,
    val base64DataUri: String
)

class AudioRecorderManager(private val context: Context) {

    private val tag = "AudioRecorderManager"

    private var mediaRecorder: MediaRecorder? = null
    private var currentSegmentFile: File? = null
    private val recordedSegments = mutableListOf<File>()
    private var tempPreviewFile: File? = null

    private var recordingSessionId = 0L
    private var segmentStartTime = 0L
    private var accumulatedDurationMs = 0L

    var isRecording: Boolean = false
        private set

    var isPaused: Boolean = false
        private set

    private val voiceDir: File by lazy {
        File(context.cacheDir, "voice_notes").apply {
            if (!exists()) mkdirs()
        }
    }

    fun startRecording(): Boolean {
        cancelRecording()

        recordingSessionId = System.currentTimeMillis()
        accumulatedDurationMs = 0L

        val started = startNewSegment()
        if (started) {
            isRecording = true
            isPaused = false
        }
        return started
    }

    private fun startNewSegment(): Boolean {
        return try {
            val segmentIndex = recordedSegments.size
            val file = File(voiceDir, "voice_${recordingSessionId}_seg_$segmentIndex.m4a")
            currentSegmentFile = file

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
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            segmentStartTime = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start audio recording segment", e)
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
            currentSegmentFile?.delete()
            currentSegmentFile = null
            false
        }
    }

    fun pauseRecording(): File? {
        if (!isRecording || isPaused) {
            return getPreviewFile()
        }

        stopCurrentSegment()
        isPaused = true
        return getPreviewFile()
    }

    fun resumeRecording(): Boolean {
        if (!isRecording || !isPaused) return false

        val started = startNewSegment()
        if (started) {
            isPaused = false
        }
        return started
    }

    private fun stopCurrentSegment() {
        val recorder = mediaRecorder ?: return
        val file = currentSegmentFile

        try {
            recorder.stop()
        } catch (e: Exception) {
            Log.w(tag, "Stop recorder failed or recording too short", e)
        }

        try {
            recorder.release()
        } catch (_: Exception) {}

        mediaRecorder = null

        val segDuration = System.currentTimeMillis() - segmentStartTime
        if (segDuration > 0) {
            accumulatedDurationMs += segDuration
        }

        if (file != null && file.exists() && file.length() > 0) {
            recordedSegments.add(file)
        }
        currentSegmentFile = null
    }

    fun getPreviewFile(): File? {
        val validSegments = recordedSegments.filter { it.exists() && it.length() > 0 }
        if (validSegments.isEmpty()) return null

        if (validSegments.size == 1) {
            return validSegments[0]
        }

        val previewFile = File(voiceDir, "voice_preview_${recordingSessionId}.m4a")
        tempPreviewFile = previewFile

        val success = AudioMerger.mergeM4aFiles(validSegments, previewFile)
        return if (success && previewFile.exists()) previewFile else validSegments.lastOrNull()
    }

    fun stopRecording(): VoiceRecordingResult? {
        if (!isRecording) return null

        if (!isPaused) {
            stopCurrentSegment()
        }

        isRecording = false
        isPaused = false

        val validSegments = recordedSegments.filter { it.exists() && it.length() > 0 }
        if (validSegments.isEmpty()) {
            cancelRecording()
            return null
        }

        val finalFile: File = if (validSegments.size == 1) {
            validSegments[0]
        } else {
            val mergedFile = File(voiceDir, "voice_${recordingSessionId}_final.m4a")
            val success = AudioMerger.mergeM4aFiles(validSegments, mergedFile)
            if (success && mergedFile.exists()) {
                mergedFile
            } else {
                validSegments.first()
            }
        }

        val totalDurationSeconds = (accumulatedDurationMs / 1000L).toInt().coerceAtLeast(1)

        return try {
            val bytes = finalFile.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUri = "data:audio/m4a;base64,$base64"

            VoiceRecordingResult(
                file = finalFile,
                durationSeconds = totalDurationSeconds,
                base64DataUri = dataUri
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to read final recording audio file", e)
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
        isPaused = false
        accumulatedDurationMs = 0L

        currentSegmentFile?.delete()
        currentSegmentFile = null

        for (seg in recordedSegments) {
            try {
                if (seg.exists()) seg.delete()
            } catch (_: Exception) {}
        }
        recordedSegments.clear()

        tempPreviewFile?.let {
            try {
                if (it.exists()) it.delete()
            } catch (_: Exception) {}
        }
        tempPreviewFile = null
    }

    fun getMaxAmplitude(): Int {
        return try {
            if (isRecording && !isPaused) mediaRecorder?.maxAmplitude ?: 0 else 0
        } catch (_: Exception) {
            0
        }
    }

    fun getDurationSeconds(): Int {
        val currentSeg = if (isRecording && !isPaused) {
            (System.currentTimeMillis() - segmentStartTime).coerceAtLeast(0L)
        } else {
            0L
        }
        return ((accumulatedDurationMs + currentSeg) / 1000L).toInt()
    }
}
