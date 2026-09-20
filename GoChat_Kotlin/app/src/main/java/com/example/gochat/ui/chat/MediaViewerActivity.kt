package com.example.gochat.ui.chat

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.databinding.ActivityMediaViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MediaViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_URL = "extra_media_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_IS_VIDEO = "extra_is_video"
        const val EXTRA_IS_VIEW_ONCE = "extra_is_view_once"
    }

    private lateinit var binding: ActivityMediaViewerBinding
    private var exoPlayer: ExoPlayer? = null
    private var mediaUrl: String = ""
    private var isVideo: Boolean = false
    private var isViewOnce: Boolean = false
    private var isOverlayVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        isViewOnce = intent.getBooleanExtra(EXTRA_IS_VIEW_ONCE, false)
        if (isViewOnce) {
            // Strict View Once screenshot & screen recording blocking:
            // Prevents screenshots, video captures, and system recents previews at the OS level
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        mediaUrl = intent.getStringExtra(EXTRA_MEDIA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Media"
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
        isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false) || mediaUrl.contains(".mp4") || mediaUrl.startsWith("data:video")

        binding.tvMediaTitle.text = title
        binding.tvMediaSubtitle.text = when {
            isViewOnce -> if (isVideo) "① View Once Video" else "① View Once Photo"
            subtitle != null -> subtitle
            isVideo -> "Video"
            else -> "Photo"
        }

        if (isViewOnce) {
            // Strict privacy: View Once media cannot be exported, shared, or saved to gallery
            binding.btnShareMedia.visibility = View.GONE
            binding.btnDownloadMedia.visibility = View.GONE
        } else {
            binding.btnShareMedia.setOnClickListener {
                shareMedia()
            }
            binding.btnDownloadMedia.setOnClickListener {
                saveMediaToGallery()
            }
        }

        binding.btnBackViewer.setOnClickListener { finish() }

        binding.ivZoomableMedia.setOnClickListener {
            toggleOverlays()
        }

        setupMedia()
    }

    private fun toggleOverlays() {
        isOverlayVisible = !isOverlayVisible
        val visibility = if (isOverlayVisible) View.VISIBLE else View.GONE
        binding.layoutTopBar.visibility = visibility
        if (binding.tvMediaCaption.text.isNotBlank()) {
            binding.tvMediaCaption.visibility = visibility
        }
    }

    private fun setupMedia() {
        if (mediaUrl.isBlank()) {
            Toast.makeText(this, "Media unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        if (isVideo) {
            binding.ivZoomableMedia.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
            playVideo(mediaUrl)
        } else {
            binding.ivZoomableMedia.visibility = View.VISIBLE
            binding.playerView.visibility = View.GONE

            MediaImageHelper.loadSafeImage(
                imageView = binding.ivZoomableMedia,
                url = mediaUrl,
                isCircle = false,
                placeholderRes = R.drawable.ic_gallery,
                errorRes = R.drawable.ic_gallery
            )
        }
    }

    private fun playVideo(url: String) {
        try {
            val player = ExoPlayer.Builder(this).build()
            exoPlayer = player
            binding.playerView.player = player

            val uri = if (url.startsWith("data:video")) {
                val base64Part = url.substringAfter("base64,", "")
                val bytes = Base64.decode(base64Part, Base64.DEFAULT)
                val tempFile = File(cacheDir, "temp_video_playback.mp4")
                FileOutputStream(tempFile).use { it.write(bytes) }
                Uri.fromFile(tempFile)
            } else if (url.startsWith("/")) {
                Uri.fromFile(File(url))
            } else {
                Uri.parse(url)
            }

            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play video: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareMedia() {
        lifecycleScope.launch {
            val shareFile = withContext(Dispatchers.IO) {
                prepareFileForSharing()
            }
            if (shareFile != null && shareFile.exists()) {
                val contentUri = FileProvider.getUriForFile(
                    this@MediaViewerActivity,
                    "${applicationContext.packageName}.provider",
                    shareFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = if (isVideo) "video/*" else "image/*"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share media via"))
            } else {
                Toast.makeText(this@MediaViewerActivity, "Failed to prepare media for sharing", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun prepareFileForSharing(): File? {
        return try {
            if (mediaUrl.startsWith("data:")) {
                val base64Data = mediaUrl.substringAfter("base64,")
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val ext = if (isVideo) ".mp4" else ".jpg"
                val file = File(cacheDir, "shared_media_${System.currentTimeMillis()}$ext")
                FileOutputStream(file).use { it.write(bytes) }
                file
            } else if (mediaUrl.startsWith("/") || mediaUrl.startsWith("file://")) {
                val path = mediaUrl.removePrefix("file://")
                File(path)
            } else {
                // Network URL: load bitmap with Coil and write to cache
                val loader = ImageLoader(this)
                val request = ImageRequest.Builder(this)
                    .data(mediaUrl)
                    .allowHardware(false)
                    .build()
                val result = (loader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? BitmapDrawable)?.bitmap ?: return null
                val file = File(cacheDir, "shared_image_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                file
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveMediaToGallery() {
        lifecycleScope.launch {
            binding.pbMediaLoading.visibility = View.VISIBLE
            val success = withContext(Dispatchers.IO) {
                saveToGalleryInternal()
            }
            binding.pbMediaLoading.visibility = View.GONE
            if (success) {
                Toast.makeText(this@MediaViewerActivity, "Saved to Gallery", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MediaViewerActivity, "Failed to save media", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun saveToGalleryInternal(): Boolean {
        return try {
            val fileName = "GoChat_${System.currentTimeMillis()}" + if (isVideo) ".mp4" else ".jpg"
            val mimeType = if (isVideo) "video/mp4" else "image/jpeg"

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GoChat")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = contentResolver.insert(collection, values) ?: return false

            var written = false
            contentResolver.openOutputStream(itemUri)?.use { outputStream ->
                if (mediaUrl.startsWith("data:")) {
                    val base64Data = mediaUrl.substringAfter("base64,")
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    outputStream.write(bytes)
                    written = true
                } else if (mediaUrl.startsWith("/") || mediaUrl.startsWith("file://")) {
                    val path = mediaUrl.removePrefix("file://")
                    File(path).inputStream().use { input ->
                        input.copyTo(outputStream)
                    }
                    written = true
                } else {
                    val loader = ImageLoader(this)
                    val request = ImageRequest.Builder(this)
                        .data(mediaUrl)
                        .allowHardware(false)
                        .build()
                    val result = (loader.execute(request) as? SuccessResult)?.drawable
                    val bitmap = (result as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                        written = true
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(itemUri, values, null, null)
            }

            written
        } catch (e: Exception) {
            false
        }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}
