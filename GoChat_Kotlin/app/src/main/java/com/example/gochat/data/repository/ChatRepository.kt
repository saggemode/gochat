package com.example.gochat.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.gochat.data.api.GoChatApiService

import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.db.AppDatabase
import com.example.gochat.data.db.ChatDao
import com.example.gochat.data.model.*
import com.example.gochat.core.crypto.EncryptionManager
import com.example.gochat.core.notification.NotificationHelper
import com.example.gochat.data.api.ApiConstants
import androidx.work.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.gochat.core.sync.MessageSyncWorker
import com.example.gochat.core.utils.BlurHashUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central repository for conversations, messages, and real-time event processing.
 * Combines Retrofit API, Room DB (offline-first), and WebSocket event handling.
 */
@Singleton
class ChatRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GoChatApiService,
    private val dao: ChatDao,
    private val tokenManager: TokenManager,
    private val encryptionManager: EncryptionManager,
    private val workManager: WorkManager
) {

    fun getMessagesPaged(convId: String): Flow<PagingData<Message>> {
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = false,
                initialLoadSize = 50
            ),
            pagingSourceFactory = { dao.getMessagesForConversationPaged(convId) }
        ).flow
    }

    fun getConversationsPaged(): Flow<PagingData<Conversation>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { dao.getAllConversationsPaged() }
        ).flow
    }

    /** The ID of the conversation the user is currently viewing (null = chat list). */
    var activeConversationId: String?
        get() = activeConversationIdStatic
        set(value) { activeConversationIdStatic = value }

    companion object {
        /** Global tracking of the active conversation to avoid new instances losing state. */
        var activeConversationIdStatic: String? = null
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Conversations ────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    fun observeConversations(): Flow<List<Conversation>> = dao.getAllConversations()

    suspend fun getAllConversationsList(): List<Conversation> = dao.getAllConversationsList()

    suspend fun getConversationById(id: String): Conversation? = dao.getConversationById(id)

    suspend fun refreshConversations(): Result<List<Conversation>> {
        return try {
            val response = api.getConversations()
            if (response.isSuccessful) {
                val body = response.body()
                val rawList = when (body) {
                    is JsonArray -> body
                    is JsonObject -> body["conversations"]?.jsonArray ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
                val currentUserId = tokenManager.userId ?: ""
                val conversations = rawList.mapNotNull {
                    if (it is JsonObject) Conversation.fromJson(it, currentUserId) else null
                }
                if (conversations.isNotEmpty()) {
                    dao.insertConversations(conversations)
                }
                Result.success(conversations)
            } else {
                Result.failure(Exception("Failed to fetch conversations (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createConversation(
        name: String,
        memberIds: List<String>,
        isGroup: Boolean = false
    ): Result<Conversation> {
        return try {
            val body = buildJsonObject {
                put("name", name)
                put("member_ids", JsonArray(memberIds.map { JsonPrimitive(it) }))
                put("type", if (isGroup) 1 else 0)
                put("is_group", isGroup)
            }
            val response = api.createConversation(body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val convJson = data["conversation"]?.jsonObject ?: data
                val conv = Conversation.fromJson(convJson, tokenManager.userId ?: "")
                dao.insertConversation(conv)
                Result.success(conv)
            } else {
                Result.failure(Exception("Failed to create conversation (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markConversationAsRead(convId: String) {
        dao.markConversationAsRead(convId)
        dao.markIncomingMessagesAsRead(convId)
    }

    suspend fun markOutgoingMessagesAsRead(convId: String) {
        dao.markOutgoingMessagesAsRead(convId)
    }

    suspend fun deleteConversation(convId: String) {
        dao.deleteConversation(convId)
        dao.clearMessagesForConversation(convId)
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Messages ─────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    fun observeMessages(convId: String): Flow<List<Message>> =
        dao.getMessagesForConversation(convId)

    suspend fun refreshMessages(convId: String): Result<List<Message>> {
        return try {
            val response = api.getMessages(convId)
            if (response.isSuccessful) {
                val body = response.body()
                val rawList = when (body) {
                    is JsonArray -> body
                    is JsonObject -> body["messages"]?.jsonArray ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
                val currentUserId = tokenManager.userId ?: ""
                val messages = rawList.mapNotNull { element ->
                    if (element is JsonObject) {
                        val msg = Message.fromJson(element, currentUserId)
                        // For ALL messages (sent or received), check if we already have
                        // a local decrypted version. Signal Protocol ratcheting means we
                        // can't decrypt the same ciphertext twice, so we must preserve
                        // locally-stored plaintext content.
                        val localExisting = dao.getMessageById(msg.id)
                        if (localExisting != null && localExisting.content.isNotBlank() && !isBase64Ciphertext(localExisting.content)) {
                            // Local DB has readable plaintext — preserve it
                            msg.copy(content = localExisting.content)
                        } else if (msg.isMe) {
                            // Own message with no local plaintext — keep server content as-is
                            msg
                        } else {
                            // Received message not yet decrypted locally — try decrypting
                            tryDecryptMessage(msg)
                        }
                    } else null
                }
                dao.insertMessages(messages)
                Result.success(messages)
            } else {
                Result.failure(Exception("Failed to fetch messages (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        conversationId: String,
        content: String,
        type: Int = 0,
        mediaUrl: String? = null,
        telegramFileId: String? = null,
        mediaThumbnail: String? = null,
        replyToId: String? = null,
        replyToText: String? = null,
        replyToSenderName: String? = null,
        mentionedUserIds: List<String> = emptyList(),
        disappearingDurationSeconds: Int? = null
    ): Result<Message> {
        val conv = dao.getConversationById(conversationId)
        val currentUserId = tokenManager.userId ?: ""
        
        var encryptedContent = content
        var blurHash: String? = null

        // NOTE: Client-side Signal Protocol encryption is disabled until session
        // management is fully stable. Messages are already secured in transit
        // via HTTPS/WSS. Re-enable once E2EE key exchange is verified end-to-end.
        // if (conv != null && conv.isDirect && type == 0) {
        //     val targetUserId = conv.memberIds.find { it != currentUserId }
        //     if (targetUserId != null) {
        //         encryptedContent = encryptionManager.encryptMessage(targetUserId, content)
        //     }
        // }

        // 1. Pre-read media bytes and generate BlurHash BEFORE the optimistic insert,
        //    so the UI immediately shows the blurry placeholder while uploading.
        var cachedMediaBytes: ByteArray? = null
        var cachedMediaUri: Uri? = null
        if (!mediaUrl.isNullOrBlank() && !mediaUrl.startsWith("http://") && !mediaUrl.startsWith("https://")) {
            cachedMediaUri = Uri.parse(mediaUrl)
            cachedMediaBytes = readUriBytes(cachedMediaUri!!)
            if (cachedMediaBytes != null && type == 1) { // IMAGE
                try {
                    val bitmap = BitmapFactory.decodeByteArray(cachedMediaBytes, 0, cachedMediaBytes.size)
                    if (bitmap != null) {
                        val scaled = Bitmap.createScaledBitmap(bitmap, 100, 100, false)
                        blurHash = BlurHashUtil.encode(scaled, 4, 3)
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Insert optimistic message into local Room DB immediately (status = SENDING)
        var localMsg = createOptimisticMessage(
            conversationId, content, type, mediaUrl,
            replyToId, replyToText, replyToSenderName
        ).copy(status = MessageStatus.SENDING, blurHash = blurHash)


        
        if (disappearingDurationSeconds != null && disappearingDurationSeconds > 0) {
            localMsg = localMsg.copy(
                disappearingDurationSeconds = disappearingDurationSeconds,
                expiresAt = System.currentTimeMillis() + (disappearingDurationSeconds * 1000L)
            )
        }
        dao.insertMessage(localMsg)

        return try {
            // 3. Upload media using the pre-cached bytes (no second read needed)
            var finalMediaUrl = mediaUrl
            if (cachedMediaBytes != null && cachedMediaUri != null) {
                val mimeType = when (localMsg.type) {
                    MessageType.IMAGE -> "image/jpeg"
                    MessageType.VIDEO -> "video/mp4"
                    MessageType.VOICE, MessageType.AUDIO -> "audio/mp4"
                    else -> "application/octet-stream"
                }
                val uploaded = uploadMedia(cachedMediaBytes, mimeType, cachedMediaUri.lastPathSegment ?: "media.jpg")
                if (!uploaded.isNullOrBlank()) {
                    finalMediaUrl = uploaded
                }
            }

            // 3. Directly deliver via HTTP API for instant sub-second delivery
            val body = buildJsonObject {
                put("content", encryptedContent)
                put("type", type)

                finalMediaUrl?.let { put("media_url", it) }
                blurHash?.let { put("blur_hash", it) }
                telegramFileId?.let { put("telegram_file_id", it) }

                mediaThumbnail?.let { put("media_thumbnail", it) }
                replyToId?.let { put("parent_id", it) }
                if (mentionedUserIds.isNotEmpty()) {
                    put("mentioned_user_ids", JsonArray(mentionedUserIds.map { JsonPrimitive(it) }))
                }
                if (localMsg.expiresAt != null && localMsg.expiresAt > 0) {
                    put("expires_at", localMsg.expiresAt / 1000L)
                }
            }

            val response = api.sendMessage(conversationId, body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val msgJson = data["message"]?.jsonObject ?: data
                val parsed = Message.fromJson(msgJson, tokenManager.userId ?: "")
                val finalMsg = parsed.copy(
                    isMe = true,
                    content = content, // Preserve local plaintext
                    mediaUrl = if (!parsed.mediaUrl.isNullOrBlank()) parsed.mediaUrl else finalMediaUrl,
                    blurHash = if (!parsed.blurHash.isNullOrBlank()) parsed.blurHash else blurHash,
                    status = MessageStatus.SENT
                )


                if (finalMsg.id != localMsg.id) {
                    dao.deleteMessage(localMsg.id)
                }
                dao.insertMessage(finalMsg)
                dao.updateLastMessage(
                    convId = conversationId,
                    lastText = if (finalMsg.content.length > 50) finalMsg.content.take(47) + "..." else finalMsg.content.ifBlank { "Media" },
                    lastTime = finalMsg.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
                Result.success(finalMsg)
            } else {
                // Server returned non-200, enqueue WorkManager for background sync retry
                enqueueSyncWorker(localMsg.id)
                Result.success(localMsg)
            }
        } catch (_: Exception) {
            // Offline / network failure: enqueue WorkManager for background retry
            enqueueSyncWorker(localMsg.id)
            Result.success(localMsg)
        }
    }

    private fun enqueueSyncWorker(messageId: String) {
        try {
            val syncRequest = OneTimeWorkRequestBuilder<MessageSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork("msg_sync_$messageId", ExistingWorkPolicy.REPLACE, syncRequest)
        } catch (_: Exception) {}
    }

    private fun readUriBytes(uri: Uri): ByteArray? {
        return try {
            if (uri.scheme == null || uri.scheme == "file") {
                val file = File(uri.path ?: uri.toString())
                if (file.exists()) {
                    return file.readBytes()
                }
            }
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun handleIncomingWebSocketEvent(event: JsonObject): Message? {
        val eventType = (event["type"] ?: event["event_type"] ?: event["eventType"])
            ?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()

        if (eventType == "presence") {
            val userId = (event["user_id"] ?: event["userId"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val isOnline = (event["is_online"] ?: event["isOnline"])?.jsonPrimitive?.booleanOrNull ?: false
            val lastSeenSec = (event["last_seen"] ?: event["lastSeen"])?.jsonPrimitive?.longOrNull
            val lastSeenMs = if (lastSeenSec != null) {
                if (lastSeenSec < 100_000_000_000L) lastSeenSec * 1000L else lastSeenSec
            } else System.currentTimeMillis()

            if (userId.isNotEmpty() && userId != (tokenManager.userId ?: "")) {
                dao.updatePresenceGlobal(userId, isOnline, lastSeenMs)
            }
            return null
        }

        val rawMsg = event["message"] ?: event["Message"] ?: event["payload"] ?: event["data"]

        val isMessageEvent = eventType == "0" ||
                eventType == "1" ||
                eventType.equals("EVENT_NEW_MESSAGE", ignoreCase = true) ||
                eventType.equals("new_message", ignoreCase = true) ||
                eventType.equals("chat_message", ignoreCase = true) ||
                eventType.equals("message", ignoreCase = true) ||
                (rawMsg is JsonObject && (rawMsg.containsKey("content") || rawMsg.containsKey("conversation_id")))

        if (isMessageEvent) {
            val convId = (event["conversation_id"] ?: event["conversationId"]
                ?: if (rawMsg is JsonObject) rawMsg["conversation_id"] ?: rawMsg["conversationId"] else null)
                ?.jsonPrimitive?.contentOrNull.orEmpty()

            val msgObj = if (rawMsg is JsonObject) rawMsg else event
            val currentUserId = tokenManager.userId ?: ""
            var msg = Message.fromJson(msgObj, currentUserId)
            if (msg.conversationId.isEmpty() && convId.isNotEmpty()) {
                msg = msg.copy(conversationId = convId)
            }

            // Guard: preserve locally-stored plaintext to prevent WS echo from
            // overwriting our decrypted/original content with server ciphertext.
            val localExisting = dao.getMessageById(msg.id)
            if (localExisting != null && localExisting.content.isNotBlank() && !isBase64Ciphertext(localExisting.content)) {
                if (msg.isMe) {
                    // Own message already stored correctly by sendMessage() HTTP response.
                    // Skip insert entirely — the WS echo may have incomplete fields
                    // (missing mediaUrl, blurHash, etc.) that would corrupt local data.
                    val isViewing = activeConversationId == msg.conversationId
                    if (!isViewing && msg.conversationId.isNotEmpty()) {
                        dao.updateLastMessageAndIncrementUnread(
                            convId = msg.conversationId,
                            lastText = localExisting.content.ifBlank { "Media" },
                            lastTime = localExisting.createdAt,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    return localExisting
                }
                // Received message with local plaintext — preserve content
                msg = msg.copy(content = localExisting.content)
            } else if (msg.isMe) {
                // Own message echo with no local plaintext — keep as-is
            } else {
                // Received message — attempt decryption
                msg = tryDecryptMessage(msg)
            }

            if (msg.conversationId.isNotEmpty()) {
                dao.insertMessage(msg)

                val isViewing = activeConversationId == msg.conversationId
                if (isViewing) {
                    dao.markConversationAsRead(msg.conversationId)
                    dao.updateLastMessage(
                        convId = msg.conversationId,
                        lastText = msg.content.ifBlank { "Media" },
                        lastTime = msg.createdAt,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    dao.updateLastMessageAndIncrementUnread(
                        convId = msg.conversationId,
                        lastText = msg.content.ifBlank { "Media" },
                        lastTime = msg.createdAt,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                return msg
            }
        }
        return null
    }

    /**
     * Ingest an incoming message payload delivered via FCM push notification.
     * Inserts the message into Room DB and updates conversation metadata in the background
     * so that messages are already cached locally even if the app was closed or network is later cut off.
     */
    suspend fun ingestIncomingPushMessage(
        messageId: String,
        conversationId: String,
        senderId: String,
        senderName: String,
        content: String,
        mediaUrl: String? = null,
        type: Int = 0,
        createdAt: Long = System.currentTimeMillis()
    ): Message {
        val currentUserId = tokenManager.userId ?: ""
        val isMe = senderId.isNotEmpty() && senderId == currentUserId

        val msgType = when (type) {
            1 -> MessageType.IMAGE
            2 -> MessageType.VIDEO
            3 -> MessageType.AUDIO
            4 -> MessageType.FILE
            5 -> MessageType.VOICE
            6 -> MessageType.POLL
            7 -> MessageType.PRODUCT
            8 -> MessageType.PING
            else -> MessageType.TEXT
        }

        val existing = dao.getMessageById(messageId)
        val rawMsg = Message(
            id = messageId,
            conversationId = conversationId,
            senderId = senderId,
            senderName = senderName,
            content = content,
            type = msgType,
            status = MessageStatus.DELIVERED,
            mediaUrl = mediaUrl,
            isMe = isMe,
            createdAt = createdAt
        )

        val finalMsg = if (existing != null && existing.content.isNotBlank() && !isBase64Ciphertext(existing.content)) {
            existing
        } else {
            tryDecryptMessage(rawMsg)
        }

        dao.insertMessage(finalMsg)

        // Update or create conversation record
        val isViewing = activeConversationId == conversationId
        val lastText = when (finalMsg.type) {
            MessageType.TEXT -> finalMsg.content.ifBlank { "Message" }
            MessageType.IMAGE -> "📷 Photo"
            MessageType.VIDEO -> "🎥 Video"
            MessageType.VOICE, MessageType.AUDIO -> "🎵 Voice note"
            MessageType.FILE -> "📄 Document"
            MessageType.POLL -> "📊 Poll"
            MessageType.PING -> "💥 PING"
            else -> "Media"
        }

        val existingConv = dao.getConversationById(conversationId)
        val now = System.currentTimeMillis()
        if (existingConv != null) {
            if (isViewing) {
                dao.updateLastMessage(
                    convId = conversationId,
                    lastText = lastText,
                    lastTime = finalMsg.createdAt,
                    updatedAt = now
                )
            } else {
                dao.updateLastMessageAndIncrementUnread(
                    convId = conversationId,
                    lastText = lastText,
                    lastTime = finalMsg.createdAt,
                    updatedAt = now
                )
            }
        } else {
            // First time seeing this conversation - insert minimal conversation so it appears in chat list
            val memberIds = listOf(senderId, currentUserId).filter { it.isNotBlank() }.distinct()
            val newConv = Conversation(
                id = conversationId,
                title = senderName.ifBlank { "Chat" },
                avatarUrl = "",
                type = ConversationType.DIRECT,
                unreadCount = if (isViewing) 0 else 1,
                memberIds = memberIds,
                lastMessageText = lastText,
                lastMessageTime = finalMsg.createdAt,
                updatedAt = now
            )
            dao.insertConversation(newConv)
        }

        return finalMsg
    }

    suspend fun insertWebSocketMessage(message: Message) {
        val decrypted = tryDecryptMessage(message)
        val finalMsg = decrypted.copy(
            isMe = decrypted.senderId == (tokenManager.userId ?: "")
        )
        dao.insertMessage(finalMsg)

        // If the message is from another user and the recipient is not currently looking at this conversation,
        // show the notification banner in the notification bar
        if (!finalMsg.isMe && activeConversationId != finalMsg.conversationId) {
            NotificationHelper.showChatNotification(
                context = context,
                conversationId = finalMsg.conversationId,
                title = finalMsg.senderName.ifBlank { "GoChat Message" },
                body = finalMsg.content.ifBlank { "New message" },
                senderAvatar = finalMsg.mediaThumbnail.orEmpty(),
                isGroup = false
            )
        }
    }

    private fun tryDecryptMessage(message: Message): Message {
        if (message.type != MessageType.TEXT || message.isMe || message.content.isBlank()) {
            return message
        }
        
        val trimmed = message.content.trim()
        // Standard text messages usually have spaces or common punctuation. 
        // Ciphertext is a dense block of alphanumeric chars.
        if (isBase64Ciphertext(trimmed)) {
            val decrypted = encryptionManager.decryptMessage(message.senderId, trimmed)
            if (decrypted.isNotBlank() && decrypted != trimmed) {
                return message.copy(content = decrypted)
            }
            // Decryption failed — return the message unchanged so we don't permanently
            // mask content that could be decrypted later when the session is established.
            // The raw ciphertext will be re-attempted on next fetch.
            Log.w("ChatRepository", "Decryption failed for msg ${message.id} from ${message.senderId}")
            return message
        }
        return message
    }

    private fun isBase64Ciphertext(text: String): Boolean {
        val trimmed = text.trim()
        // Signal Protocol ciphertext for even a single character is 50+ bytes,
        // which Base64-encodes to ~70+ characters. A threshold of 44 chars
        // (32 bytes) avoids false positives on short IDs, hashes, and normal text.
        if (trimmed.length < 44 || trimmed.contains(" ") || trimmed.contains("\n")) return false
        // Quick reject: must be pure Base64 character set
        if (!trimmed.matches(Regex("^[A-Za-z0-9+/=]+$"))) return false
        
        return try {
            Base64.decode(trimmed, Base64.DEFAULT)
            true
        } catch (_: Exception) {
            false
        }
    }


    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        dao.updateMessageStatus(messageId, status)
    }

    // ── Messaging Enhancements ───────────────────────────────────

    suspend fun toggleMessageStar(messageId: String, isStarred: Boolean) {
        dao.updateMessageStarred(messageId, isStarred)
    }

    suspend fun deleteMessageLocally(messageId: String) {
        dao.markMessageAsDeleted(messageId)
    }

    suspend fun editMessageLocally(messageId: String, newContent: String) {
        dao.updateMessageContent(messageId, newContent)
    }

    suspend fun addReactionLocally(messageId: String, emoji: String) {
        val currentUserId = tokenManager.userId ?: "u_me"
        val currentUserName = tokenManager.userDisplayName ?: "Me"
        
        val message = dao.getMessageById(messageId) ?: return
        val currentReactions = message.reactions.toMutableList()
        
        // Remove existing reaction from this user if any
        currentReactions.removeAll { it.userId == currentUserId }
        
        // Add new one
        currentReactions.add(Reaction(userId = currentUserId, userName = currentUserName, emoji = emoji))
        
        dao.updateMessageReactions(messageId, currentReactions)
    }

    fun observeStarredMessages(): Flow<List<Message>> = dao.getStarredMessages()

    suspend fun forwardMessage(originalMessage: Message, targetConversationId: String): Result<Message> {
        return sendMessage(
            conversationId = targetConversationId,
            content = originalMessage.content,
            type = getMessageTypeInt(originalMessage.type),
            mediaUrl = originalMessage.mediaUrl,
        ).onSuccess { forwardedMsg ->
            dao.insertMessage(forwardedMsg.copy(
                isForwarded = true,
                originalSenderName = originalMessage.senderName
            ))
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
            else -> 0
        }
    }

    suspend fun votePoll(pollId: String, optionId: String): Result<Unit> {
        return try {
            val body = buildJsonObject { put("option_id", optionId) }
            val response = api.votePoll(pollId, body)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Vote failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadMedia(
        bytes: ByteArray,
        mimeType: String = "image/jpeg",
        fileName: String = "chat_image.jpg"
    ): String? {
        return try {
            val mediaType = mimeType.toMediaTypeOrNull()
            val reqBody = bytes.toRequestBody(mediaType)
            val part = MultipartBody.Part.createFormData("file", fileName, reqBody)
            val response = api.uploadMedia(part)
            if (response.isSuccessful) {
                val json = response.body()
                val rawUrl = (json?.get("url") ?: json?.get("Url") ?: json?.get("URL") ?: json?.get("media_url"))?.jsonPrimitive?.contentOrNull
                if (!rawUrl.isNullOrBlank()) {
                    if (rawUrl.startsWith("/")) {
                        "${ApiConstants.BASE_URL.removeSuffix("/")}$rawUrl"
                    } else {
                        rawUrl
                    }
                } else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createOptimisticMessage(
        conversationId: String,
        content: String,
        type: Int,
        mediaUrl: String?,
        replyToId: String?,
        replyToText: String?,
        replyToSenderName: String?
    ): Message {
        val msgType = when (type) {
            1 -> MessageType.IMAGE
            2 -> MessageType.VIDEO
            3 -> MessageType.AUDIO
            4 -> MessageType.FILE
            5 -> MessageType.VOICE
            6 -> MessageType.POLL
            7 -> MessageType.PRODUCT
            8 -> MessageType.PING
            else -> MessageType.TEXT
        }
        return Message(
            id = "msg_${System.currentTimeMillis()}",
            conversationId = conversationId,
            senderId = tokenManager.userId ?: "u_me",
            senderName = tokenManager.userDisplayName ?: "Me",
            content = content,
            type = msgType,
            status = MessageStatus.SENDING,
            mediaUrl = mediaUrl,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSenderName = replyToSenderName,
            isMe = true,
            createdAt = System.currentTimeMillis()
        )
    }
}
