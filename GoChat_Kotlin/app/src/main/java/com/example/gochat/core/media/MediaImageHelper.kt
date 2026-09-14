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

        // 1. Base64 Data URI (e.g. data:image/jpeg;base64,...) or raw Base64 image payload
        val isDataUri = clean.startsWith("data:") && clean.contains(";base64,")
        val isRawBase64 = !isDataUri && clean.length > 200 && !clean.contains(" ") &&
                (clean.startsWith("/9j/") || clean.startsWith("iVBOR") || clean.startsWith("R0lGOD") || clean.startsWith("UklGR"))

        if (isDataUri || isRawBase64) {
            try {
                val b64 = if (isDataUri) clean.substringAfter(";base64,").trim() else clean
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
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
                    }
                }
                imageView.setImageResource(errorRes)
                return
            } catch (_: Throwable) {
                imageView.setImageResource(errorRes)
                return
            }
        }

        // 2. Local Android Content URI (content://)
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

        // 3. Local file:// URI or device path (/data/..., /storage/..., /sdcard/...)
        if (clean.startsWith("file://") || (clean.startsWith("/") && isLocalDevicePath(clean))) {
            val localPath = if (clean.startsWith("file://")) clean.removePrefix("file://") else clean
            try {
                val file = File(localPath)
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
                } else {
                    // Local file does not exist on this device (e.g. sender's private path sent to friend)
                    if (!blurHash.isNullOrBlank()) {
                        val blurBitmap = BlurHashUtil.decode(blurHash, 32, 32)
                        if (blurBitmap != null) {
                            imageView.setImageDrawable(BitmapDrawable(imageView.context.resources, blurBitmap))
                            return
                        }
                    }
                    imageView.setImageResource(placeholderRes)
                    return
                }
            } catch (_: Throwable) {
                imageView.setImageResource(errorRes)
                return
            }
        }

        // 4. Relative API / media path (e.g. /media/uploads/..., /api/..., api/..., media/...)
        var finalUrl = when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("/") -> "${ApiConstants.BASE_URL.removeSuffix("/")}$clean"
            clean.startsWith("api/") -> "${ApiConstants.BASE_URL.removeSuffix("/")}/$clean"
            clean.startsWith("media/") -> "${ApiConstants.BASE_URL.removeSuffix("/")}/api/v1/$clean"
            else -> clean
        }

        // Remap internal emulator/container addresses to host reachable address
        if (finalUrl.contains("localhost:9000") || finalUrl.contains("127.0.0.1:9000") || finalUrl.contains("minio:9000")) {
            finalUrl = finalUrl.replace("localhost:9000", "10.0.2.2:9000")
                .replace("127.0.0.1:9000", "10.0.2.2:9000")
                .replace("minio:9000", "10.0.2.2:9000")
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
