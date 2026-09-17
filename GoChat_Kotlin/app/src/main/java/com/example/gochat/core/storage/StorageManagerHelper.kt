package com.example.gochat.core.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.MessageType
import com.example.gochat.data.repository.ChatRepository
import java.io.File

data class StorageBreakdown(
    val goChatMediaBytes: Long,
    val voiceNotesBytes: Long,
    val cacheBytes: Long,
    val otherAppsBytes: Long,
    val freeBytes: Long,
    val totalDeviceBytes: Long
)

data class ChatStorageItem(
    val conversationId: String,
    val title: String,
    val avatarUrl: String?,
    val totalSizeBytes: Long,
    val mediaCount: Int,
    val photosBytes: Long,
    val voiceBytes: Long,
    val filesBytes: Long
)

data class LargeMediaItem(
    val messageId: String,
    val conversationId: String,
    val mediaUrl: String,
    val sizeBytes: Long,
    val isVideo: Boolean
)

object StorageManagerHelper {

    fun getStorageBreakdown(context: Context): StorageBreakdown {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes

        val cacheBytes = getFolderSize(context.cacheDir)
        val filesBytes = getFolderSize(context.filesDir)
        val extBytes = context.getExternalFilesDir(null)?.let { getFolderSize(it) } ?: 0L

        val goChatTotal = cacheBytes + filesBytes + extBytes
        val otherApps = (totalBytes - freeBytes - goChatTotal).coerceAtLeast(0L)

        return StorageBreakdown(
            goChatMediaBytes = filesBytes + extBytes,
            voiceNotesBytes = (goChatTotal * 0.25).toLong(),
            cacheBytes = cacheBytes,
            otherAppsBytes = otherApps,
            freeBytes = freeBytes,
            totalDeviceBytes = totalBytes
        )
    }

    suspend fun getChatStorageItems(
        context: Context,
        chatRepository: ChatRepository,
        conversations: List<Conversation>
    ): List<ChatStorageItem> {
        val result = mutableListOf<ChatStorageItem>()

        for (conv in conversations) {
            val messages = chatRepository.searchMessagesInConversation(conv.id, "")
            var photoBytes = 0L
            var voiceBytes = 0L
            var fileBytes = 0L
            var mediaCount = 0

            for (msg in messages) {
                if (msg.isDeleted) continue
                val url = msg.mediaUrl.orEmpty()
                val size = estimateMediaSize(context, url)
                if (size > 0) {
                    mediaCount++
                    when (msg.type) {
                        MessageType.IMAGE, MessageType.VIDEO -> photoBytes += size
                        MessageType.VOICE, MessageType.AUDIO -> voiceBytes += size
                        MessageType.FILE -> fileBytes += size
                        else -> {
                            if (url.contains(".m4a") || url.contains("audio")) voiceBytes += size
                            else photoBytes += size
                        }
                    }
                }
            }

            val total = photoBytes + voiceBytes + fileBytes
            if (total > 0 || mediaCount > 0) {
                result.add(
                    ChatStorageItem(
                        conversationId = conv.id,
                        title = conv.title.ifBlank { "GoChat Contact" },
                        avatarUrl = conv.avatarUrl,
                        totalSizeBytes = total,
                        mediaCount = mediaCount,
                        photosBytes = photoBytes,
                        voiceBytes = voiceBytes,
                        filesBytes = fileBytes
                    )
                )
            }
        }

        return result.sortedByDescending { it.totalSizeBytes }
    }

    suspend fun getLargeMediaItems(
        context: Context,
        chatRepository: ChatRepository
    ): List<LargeMediaItem> {
        val allMessages = chatRepository.searchAllMessages("")
        val thresholdBytes = 5 * 1024 * 1024L // 5 MB
        val items = mutableListOf<LargeMediaItem>()

        for (msg in allMessages) {
            val url = msg.mediaUrl.orEmpty()
            val size = estimateMediaSize(context, url)
            if (size >= thresholdBytes) {
                items.add(
                    LargeMediaItem(
                        messageId = msg.id,
                        conversationId = msg.conversationId,
                        mediaUrl = url,
                        sizeBytes = size,
                        isVideo = msg.type == MessageType.VIDEO || url.contains(".mp4")
                    )
                )
            }
        }

        return items.sortedByDescending { it.sizeBytes }
    }

    fun clearConversationMedia(
        context: Context,
        messages: List<Message>,
        clearPhotos: Boolean,
        clearVoice: Boolean
    ): Long {
        var freedBytes = 0L
        for (msg in messages) {
            val url = msg.mediaUrl.orEmpty()
            val isPhoto = msg.type == MessageType.IMAGE || msg.type == MessageType.VIDEO
            val isVoice = msg.type == MessageType.VOICE || msg.type == MessageType.AUDIO || url.contains("audio")

            if ((isPhoto && clearPhotos) || (isVoice && clearVoice)) {
                if (url.startsWith("/") || url.startsWith("file://")) {
                    val file = File(url.removePrefix("file://"))
                    if (file.exists()) {
                        val len = file.length()
                        if (file.delete()) freedBytes += len
                    }
                }
            }
        }
        return freedBytes
    }

    private fun estimateMediaSize(context: Context, mediaUrl: String): Long {
        if (mediaUrl.isBlank()) return 0L
        if (mediaUrl.startsWith("data:")) {
            val base64Data = mediaUrl.substringAfter("base64,", "")
            return (base64Data.length * 3L / 4L)
        }
        if (mediaUrl.startsWith("/") || mediaUrl.startsWith("file://")) {
            val f = File(mediaUrl.removePrefix("file://"))
            if (f.exists()) return f.length()
        }
        // Fallback estimate for thumbnail/remote cached media
        return 120 * 1024L // ~120KB average
    }

    private fun getFolderSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var result = 0L
        val stack = mutableListOf(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.size - 1)
            val files = current.listFiles() ?: continue
            for (f in files) {
                if (f.isDirectory) stack.add(f)
                else result += f.length()
            }
        }
        return result
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
