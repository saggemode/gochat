package com.example.gochat.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Base64
import android.widget.ImageView
import coil.imageLoader
import coil.load
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation
import com.example.gochat.R
import com.example.gochat.core.utils.BlurHashUtil
import com.example.gochat.data.api.ApiConstants
import java.io.File

/**
 * Universal media image loader mirroring Flutter's `MediaImageHelper`.
 * Safely handles Base64 Data URIs, relative backend URLs (/media/...),
 * full http/https URLs, content:// URIs, file:// URIs, local storage paths,
 * and fallback errors with zero OutOfMemory crash risk.
 */
object MediaImageHelper {

    /**
     * Clears both memory and disk cache for Coil.
     */
    fun clearImageCache(context: Context) {
        context.imageLoader.memoryCache?.clear()
        context.imageLoader.diskCache?.clear()
    }

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
        errorRes: Int = R.drawable.ic_gallery,
        thumbnailWidth: Int? = null,
        blurHash: String? = null
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
                val b64 = clean.substringAfter(";base64,").trim()
                val bytes = try {
                    Base64.decode(b64, Base64.DEFAULT)
                } catch (_: Exception) {
                    try {
                        Base64.decode(b64, Base64.NO_WRAP)
                    } catch (_: Exception) {
                        Base64.decode(b64, Base64.URL_SAFE)
                    }
                }

                if (bytes != null && bytes.isNotEmpty()) {
                    imageView.load(bytes) {
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
            } catch (_: Throwable) {
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
        if (clean.startsWith("/") && isLocalDevicePath(clean)) {
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
            } catch (_: Throwable) {
            }
        }

        // 5. Relative API / media path (e.g. /media/uploads/..., /api/...)
        var finalUrl = if (clean.startsWith("/") && !isLocalDevicePath(clean)) {
            "${ApiConstants.BASE_URL.removeSuffix("/")}$clean"
        } else {
            clean
        }

        // Apply thumbnail resizing if requested and it's a server URL
        if (thumbnailWidth != null && (finalUrl.startsWith(ApiConstants.BASE_URL) || finalUrl.startsWith("http"))) {
            if (finalUrl.contains("/media/download/")) {
                finalUrl = finalUrl.replace("/media/download/", "/media/thumbnail/")
                finalUrl += if (finalUrl.contains("?")) "&w=$thumbnailWidth" else "?w=$thumbnailWidth"
            }
        }



        try {
            imageView.load(finalUrl) {
                crossfade(true)
                if (!blurHash.isNullOrBlank()) {
                    val bitmap = BlurHashUtil.decode(blurHash, 32, 32)
                    if (bitmap != null) {
                        placeholder(BitmapDrawable(imageView.context.resources, bitmap))
                    } else {
                        placeholder(placeholderRes)
                    }
                } else {
                    placeholder(placeholderRes)
                }
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
