package com.example.gochat.core.media

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

object AudioPlayerManager {

    private var mediaPlayer: MediaPlayer? = null
    var currentPlayingMessageId: String? = null
        private set

    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true

    var onPlaybackStateChanged: ((messageId: String, isPlaying: Boolean) -> Unit)? = null
    var onProgressUpdate: ((messageId: String, currentPositionMs: Int, totalDurationMs: Int) -> Unit)? = null
    var onPlaybackCompleted: ((messageId: String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            val msgId = currentPlayingMessageId
            if (player != null && player.isPlaying && msgId != null) {
                try {
                    val currentPos = player.currentPosition
                    val duration = player.duration.coerceAtLeast(1)
                    onProgressUpdate?.invoke(msgId, currentPos, duration)
                } catch (_: Exception) {}
                handler.postDelayed(this, 100)
            }
        }
    }

    fun playOrPause(context: Context, messageId: String, audioSource: String) {
        if (currentPlayingMessageId == messageId && mediaPlayer != null) {
            if (mediaPlayer!!.isPlaying) {
                mediaPlayer!!.pause()
                handler.removeCallbacks(progressRunnable)
                onPlaybackStateChanged?.invoke(messageId, false)
            } else {
                mediaPlayer!!.start()
                handler.post(progressRunnable)
                onPlaybackStateChanged?.invoke(messageId, true)
            }
            return
        }

        stop()

        try {
            val resolvedPath = resolveAudioSourceToPath(context, audioSource) ?: return

            val player = MediaPlayer().apply {
                setDataSource(resolvedPath)
                setOnPreparedListener { mp ->
                    mp.start()
                    currentPlayingMessageId = messageId
                    handler.post(progressRunnable)
                    onPlaybackStateChanged?.invoke(messageId, true)
                }
                setOnCompletionListener {
                    handler.removeCallbacks(progressRunnable)
                    val id = currentPlayingMessageId ?: messageId
                    currentPlayingMessageId = null
                    onPlaybackStateChanged?.invoke(id, false)
                    onPlaybackCompleted?.invoke(id)
                }
                setOnErrorListener { _, _, _ ->
                    handler.removeCallbacks(progressRunnable)
                    val id = currentPlayingMessageId ?: messageId
                    currentPlayingMessageId = null
                    onPlaybackStateChanged?.invoke(id, false)
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (_: Exception) {
            stop()
        }
    }

    fun seekTo(positionMs: Int) {
        try {
            mediaPlayer?.seekTo(positionMs)
        } catch (_: Exception) {}
    }

    fun stop() {
        handler.removeCallbacks(progressRunnable)
        currentPlayingMessageId?.let { id ->
            onPlaybackStateChanged?.invoke(id, false)
        }
        currentPlayingMessageId = null

        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}

        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}

        mediaPlayer = null
    }

    fun release() {
        stop()
        onPlaybackStateChanged = null
        onProgressUpdate = null
        onPlaybackCompleted = null
    }

    private fun resolveAudioSourceToPath(context: Context, audioSource: String): String? {
        if (audioSource.isBlank()) return null

        if (audioSource.startsWith("data:audio") || audioSource.startsWith("data:application")) {
            val base64Part = audioSource.substringAfter("base64,", "")
            if (base64Part.isNotBlank()) {
                val bytes = Base64.decode(base64Part, Base64.DEFAULT)
                val tempFile = File(context.cacheDir, "temp_voice_playback.m4a")
                FileOutputStream(tempFile).use { it.write(bytes) }
                return tempFile.absolutePath
            }
        }

        if (audioSource.startsWith("/") || audioSource.startsWith("file://")) {
            return audioSource.removePrefix("file://")
        }

        return audioSource
    }
}
