package com.example.gochat.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.ImageView
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.data.api.ApiConstants

/**
 * Universal media image loader mirroring Flutter's `MediaImageHelper`.
 * Safely handles Base64 Data URIs, relative backend URLs (/media/...),
 * full http/https URLs, and fallback errors with zero OutOfMemory crash risk.
 */
object MediaImageHelper {

    fun loadSafeImage(
        imageView: ImageView,
        url: String?,
        isCircle: Boolean = false,
        placeholderRes: Int = R.drawable.ic_account,
        errorRes: Int = R.drawable.ic_account
    ) {
        val clean = url?.trim().orEmpty()
        if (clean.isBlank()) {
            imageView.setImageResource(errorRes)
            return
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
                    if (isCircle) {
                        imageView.load(bitmap) {
                            crossfade(true)
                            placeholder(placeholderRes)
                            error(errorRes)
                            transformations(CircleCropTransformation())
                        }
                    } else {
                        imageView.setImageBitmap(bitmap)
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

        // 2. Relative API / media path (e.g. /media/uploads/..., /api/...)
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
                if (isCircle) transformations(CircleCropTransformation())
            }
        } catch (t: Throwable) {
            imageView.setImageResource(errorRes)
        }
    }
}
