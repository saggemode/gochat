package com.example.gochat.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Transcodes a picked/recorded video down to a size that fits under the send limit
 * (~15 MB), so it can be served back from the Telegram-backed CDN (whose bot download
 * API caps at ~20 MB). Uses Media3 [Transformer] to re-encode to H.264/AAC at a target
 * bitrate computed from the clip duration, downscaling very large resolutions.
 */
@UnstableApi
object VideoCompressor {

    /** Hard ceiling for an outgoing video. */
    const val MAX_BYTES: Long = 15L * 1024 * 1024

    /** Bitrate budget targets a slightly smaller file to leave headroom for container overhead. */
    private const val TARGET_BYTES: Long = 14L * 1024 * 1024
    private const val AUDIO_BITRATE: Int = 128_000
    private const val MIN_VIDEO_BITRATE: Int = 300_000
    private const val MAX_VIDEO_BITRATE: Int = 5_000_000
    private const val MAX_LONG_SIDE: Int = 1280

    sealed class Result {
        /** Compression succeeded; [file] is the new ≤15 MB clip. */
        data class Success(val file: File, val sizeBytes: Long) : Result()
        /** Could not get the clip under the limit (e.g. extremely long video). */
        data class TooLarge(val sizeBytes: Long) : Result()
        data class Failed(val error: Throwable) : Result()
    }

    /**
     * Returns a compressed copy of [input] no larger than [MAX_BYTES], or a failure.
     * If the source is already small enough, it is still re-encoded to normalize the
     * format/bitrate (keeps server-side handling uniform).
     */
    suspend fun compress(context: Context, input: Uri): Result = withContext(Dispatchers.Main) {
        val probe = probe(context, input)
        if (probe.durationMs <= 0L) {
            return@withContext Result.Failed(IllegalStateException("Could not read video duration"))
        }

        val durationSec = probe.durationMs / 1000.0
        val totalBudget = (TARGET_BYTES * 8 / durationSec).toInt()
        val videoBitrate = (totalBudget - AUDIO_BITRATE).coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)

        val output = File(context.cacheDir, "vid_${System.currentTimeMillis()}.mp4")

        val effects = buildList<Effect> {
            scaledDimensions(probe.width, probe.height)?.let { (w, h) ->
                add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
            }
        }

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(videoBitrate).build()
            )
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .build()

        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(Effects(emptyList(), effects))
            .build()

        try {
            runTransform(transformer, editedItem, output.absolutePath)
            val size = output.length()
            when {
                size <= 0L -> Result.Failed(IllegalStateException("Empty output"))
                size <= MAX_BYTES -> Result.Success(output, size)
                else -> {
                    output.delete()
                    Result.TooLarge(size)
                }
            }
        } catch (e: Throwable) {
            output.delete()
            Result.Failed(e)
        }
    }

    private suspend fun runTransform(
        transformer: Transformer,
        item: EditedMediaItem,
        outputPath: String
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onError(
                composition: Composition,
                result: ExportResult,
                exception: ExportException
            ) {
                if (cont.isActive) cont.cancel(exception)
            }
        }
        transformer.addListener(listener)
        cont.invokeOnCancellation {
            try { transformer.cancel() } catch (_: Exception) {}
        }
        transformer.start(item, outputPath)
    }

    private data class Probe(val durationMs: Long, val width: Int, val height: Int)

    /** Returns duration and dimensions for the source, or a zeroed probe on failure. */
    private fun probe(context: Context, uri: Uri): Probe {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            Probe(dur, w, h)
        } catch (_: Exception) {
            Probe(0L, 0, 0)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * If the source's longest side exceeds [MAX_LONG_SIDE], returns the downscaled
     * (width, height) preserving aspect ratio (even values for the encoder); otherwise null.
     */
    private fun scaledDimensions(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val longSide = maxOf(width, height)
        if (longSide <= MAX_LONG_SIDE) return null
        val scale = MAX_LONG_SIDE.toDouble() / longSide
        val w = (width * scale).toInt().let { if (it % 2 == 0) it else it - 1 }.coerceAtLeast(2)
        val h = (height * scale).toInt().let { if (it % 2 == 0) it else it - 1 }.coerceAtLeast(2)
        return w to h
    }
}
