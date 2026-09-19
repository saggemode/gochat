package com.example.gochat.core.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.db.ChatDao
import com.example.gochat.data.model.MessageStatus
import com.example.gochat.data.model.MessageType
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.core.utils.BlurHashUtil
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import com.example.gochat.core.crypto.EncryptionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.*
import java.io.File

@HiltWorker
class MessageSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dao: ChatDao,
    private val authRepo: AuthRepository,
    private val api: GoChatApiService,
    private val encryptionManager: EncryptionManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val pendingMessages = dao.getPendingMessages()
        if (pendingMessages.isEmpty()) return Result.success()

        var hasFailure = false

        for (msg in pendingMessages) {
            try {
                var finalMediaUrl = msg.mediaUrl
                var blurHash = msg.blurHash
                
                // 1. Upload media if it's a local URI
                if (!finalMediaUrl.isNullOrBlank() && !finalMediaUrl.startsWith("http")) {
                    val uri = Uri.parse(finalMediaUrl)
                    val bytes = readUriBytes(uri)
                    if (bytes != null) {
                        // Generate BlurHash for images if missing
                        if (msg.type == MessageType.IMAGE && blurHash.isNullOrBlank()) {
                            try {
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) {
                                    val scaled = Bitmap.createScaledBitmap(bitmap, 100, 100, false)
                                    blurHash = BlurHashUtil.encode(scaled, 4, 3)
                                }
                            } catch (_: Exception) {}
                        }

                        val mimeType = when(msg.type) {
                            MessageType.IMAGE -> "image/jpeg"
                            MessageType.VIDEO -> "video/mp4"
                            MessageType.VOICE, MessageType.AUDIO -> "audio/mp4"
                            else -> "application/octet-stream"
                        }
                        val uploaded = authRepo.uploadMedia(bytes, mimeType, uri.lastPathSegment ?: "file")
                        if (!uploaded.isNullOrBlank()) {
                            finalMediaUrl = uploaded
                        } else if (msg.type == MessageType.IMAGE) {
                            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            finalMediaUrl = "data:$mimeType;base64,$b64"
                        }
                    }
                }

                // 2. Content to send (E2EE)
                var finalContent = msg.content
                if (msg.type == MessageType.TEXT) {
                    val conv = dao.getConversationById(msg.conversationId)
                    val currentUserId = msg.senderId
                    if (conv != null && conv.isDirect) {
                        val targetUserId = conv.memberIds.find { it != currentUserId }
                        if (targetUserId != null) {
                            finalContent = encryptionManager.encryptMessage(targetUserId, msg.content)
                        }
                    }
                }

                // 3. Send to server
                val body = buildJsonObject {
                    put("content", finalContent)
                    put("type", getMessageTypeInt(msg.type))
                    finalMediaUrl?.let { put("media_url", it) }
                    blurHash?.let { put("blur_hash", it) }
                    msg.replyToId?.let { put("parent_id", it) }
                    if (msg.expiresAt != null && msg.expiresAt > 0) {
                        put("expires_at", msg.expiresAt / 1000L) // Backend expects seconds
                    }
                }

                val response = api.sendMessage(msg.conversationId, body)
                if (response.isSuccessful) {
                    Log.d("MessageSyncWorker", "Successfully synced message ${msg.id}")
                    dao.updateMessageStatus(msg.id, MessageStatus.SENT)
                    // Update local message with remote URL and blurHash
                    dao.insertMessage(msg.copy(
                        status = MessageStatus.SENT, 
                        mediaUrl = finalMediaUrl,
                        blurHash = blurHash
                    ))
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e("MessageSyncWorker", "Failed to sync message ${msg.id}: Code ${response.code()}, Error: $errorBody")
                    hasFailure = true
                }
            } catch (e: Exception) {
                Log.e("MessageSyncWorker", "Failed to sync message ${msg.id}", e)
                hasFailure = true
            }
        }

        return if (hasFailure) Result.retry() else Result.success()
    }

    private fun readUriBytes(uri: Uri): ByteArray? {
        return try {
            if (uri.scheme == null || uri.scheme == "file") {
                val file = File(uri.path ?: uri.toString())
                if (file.exists()) {
                    return file.readBytes()
                }
            }
            applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun getMessageTypeInt(type: MessageType): Int {
        return when (type) {
            MessageType.TEXT -> 0
            MessageType.IMAGE -> 1
            MessageType.VIDEO -> 2
            MessageType.VOICE -> 5
            MessageType.AUDIO -> 3
            MessageType.FILE -> 4
            MessageType.POLL -> 6
            MessageType.PRODUCT -> 7
            MessageType.PING -> 8
            MessageType.ORDER -> 9
            MessageType.PAYMENT_REQUEST -> 10
            MessageType.CATALOG -> 11
            MessageType.SYSTEM -> 12
            else -> 0
        }
    }
}
