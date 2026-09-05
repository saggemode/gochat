package com.example.gochat.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.ImageView
import coil.load
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation
import com.example.gochat.R
import com.example.gochat.data.api.ApiConstants
import java.io.File

/**
 * Universal media image loader mirroring Flutter's `MediaImageHelper`.
 * Safely handles Base64 Data URIs, relative backend URLs (/media/...),
 * full http/https URLs, content:// URIs, file:// URIs, local storage paths,
 * and fallback errors with zero OutOfMemory crash risk.
 */
object MediaImageHelper {

    fun isLocalDevicePath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val p = path.trim().lowercase()
        return p.startsWith("file://") ||
                p.startsWith("content://") ||
                p.startsWith("/storage/") ||
                p.startsWith("/data/") ||
                p.startsWith("/sdcard/") ||
                p.startsWith("/mnt/") ||
                p.startsWith("/users/")
    }

    fun loadSafeImage(
        imageView: ImageView,
        url: String?,
        isCircle: Boolean = false,
        cornerRadiusDp: Float? = null,
        placeholderRes: Int = R.drawable.ic_gallery,
        errorRes: Int = R.drawable.ic_gallery
    ) {
        val clean = url?.trim().orEmpty()
        if (clean.isBlank()) {
            imageView.setImageResource(errorRes)
            return
        }

        val radiusPx = cornerRadiusDp?.let { dp ->
            imageView.context.resources.displayMetrics.density * dp
        }

        // 1. Base64 Data URI (e.g. data:image/jpeg;base64,...)
        if (clean.startsWith("data:") && clean.contains(";base64,")) {
            try {
                val b64 = clean.substringAfter(";base64,")
                val bytes = Base64.decode(b64, Base64.DEFAULT)

                // Decode bounds first to prevent OOM on large Base64 images
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

                var sampleSize = 1
                val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                while (maxDim / (sampleSize * 2) >= 1280) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                if (bitmap != null) {
                    imageView.load(bitmap) {
                        crossfade(true)
                        placeholder(placeholderRes)
                        error(errorRes)
                        when {
                            isCircle -> transformations(CircleCropTransformation())
                            radiusPx != null -> transformations(RoundedCornersTransformation(radiusPx))
                        }
                    }
                    return
                } else {
                    imageView.setImageResource(errorRes)
                    return
                }
            } catch (t: Throwable) {
                // Catches OutOfMemoryError and any parsing exception
                imageView.setImageResource(errorRes)
                return
            }
        }

        // 2. Local Android Content URI
        if (clean.startsWith("content://")) {
            try {
                imageView.load(Uri.parse(clean)) {
                    crossfade(true)
                    placeholder(placeholderRes)
                    error(errorRes)
                    when {
                        isCircle -> transformations(CircleCropTransformation())
                        radiusPx != null -> transformations(RoundedCornersTransformation(radiusPx))
                    }
                }
                return
            } catch (_: Throwable) {
                imageView.setImageResource(errorRes)
                return
            }
        }

        // 3. Local file:// URI
        if (clean.startsWith("file://")) {
            try {
                val file = File(clean.removePrefix("file://"))
                imageView.load(file) {
                    crossfade(true)
                    placeholder(placeholderRes)
                    error(errorRes)
                    when {
                        isCircle -> transformations(CircleCropTransformation())
                        radiusPx != null -> transformations(RoundedCornersTransformation(radiusPx))
                    }
                }
                return
            } catch (_: Throwable) {
                imageView.setImageResource(errorRes)
                return
            }
        }

        // 4. Absolute local device storage paths (/data/..., /storage/..., /sdcard/...)
        if (clean.startsWith("/data/") || clean.startsWith("/storage/") || clean.startsWith("/sdcard/") || clean.startsWith("/mnt/")) {
            try {
                val file = File(clean)
                if (file.exists()) {
                    imageView.load(file) {
                        crossfade(true)
                        placeholder(placeholderRes)
                        error(errorRes)
                        when {
                            isCircle -> transformations(CircleCropTransformation())
                            radiusPx != null -> transformations(RoundedCornersTransformation(radiusPx))
                        }
                    }
                    return
                }
            } catch (_: Throwable) {}
        }

        // 5. Relative API / media path (e.g. /media/uploads/..., /api/...)
        val finalUrl = if (clean.startsWith("/")) {
            "${ApiConstants.BASE_URL.removeSuffix("/")}$clean"
        } else {
            clean
        }

        try {
            imageView.load(finalUrl) {
                crossfade(true)
                placeholder(placeholderRes)
                error(errorRes)
                when {
                    isCircle -> transformations(CircleCropTransformation())
                    radiusPx != null -> transformations(RoundedCornersTransformation(radiusPx))
                }
            }
        } catch (t: Throwable) {
            imageView.setImageResource(errorRes)
        }
    }
}
