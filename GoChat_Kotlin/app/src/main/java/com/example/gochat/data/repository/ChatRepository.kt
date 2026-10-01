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
import com.example.gochat.core.contacts.ContactSyncManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
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
    private val workManager: WorkManager,
    private val contactSyncManager: ContactSyncManager
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

    suspend fun insertConversationLocally(conv: Conversation) = dao.insertConversation(conv)

    suspend fun fetchConversation(convId: String): Result<Conversation> {
        return try {
            val response = api.getConversation(convId)
            if (response.isSuccessful) {
                val body = response.body() ?: return Result.failure(Exception("Empty body"))
                val convObj = body["conversation"]?.jsonObject ?: body
                val isOnline = (body["is_online"] ?: body["isOnline"])?.jsonPrimitive?.booleanOrNull ?: false
                val lastSeen = (body["last_seen"] ?: body["lastSeen"])?.jsonPrimitive?.longOrNull
                val currentUserId = tokenManager.userId ?: ""
                val conv = Conversation.fromJson(convObj, currentUserId).copy(
                    isOnline = isOnline,
                    lastSeen = lastSeen
                )
                dao.insertConversation(conv)
                Result.success(conv)
            } else {
                Result.failure(Exception("Failed to fetch conversation (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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

    suspend fun toggleScreenshotNotifications(convId: String, enabled: Boolean): Result<Unit> {
        return try {
            dao.updateScreenshotNotifications(convId, enabled)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Messages ─────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    fun observeMessages(convId: String): Flow<List<Message>> =
        dao.getMessagesForConversation(convId)

    suspend fun searchMessagesInConversation(convId: String, query: String): List<Message> {
        if (convId.isBlank() || query.isBlank()) return emptyList()
        return dao.searchMessagesInConversation(convId, query.trim())
    }

    suspend fun searchAllMessages(query: String): List<Message> {
        if (query.isBlank()) return emptyList()
        return dao.searchAllMessages(query.trim())
    }

    fun observeSharedMedia(convId: String): Flow<List<Message>> {
        return dao.getMediaMessagesForConversation(convId)
    }

    suspend fun refreshMessages(convId: String): Result<List<Message>> {
        return try {
            dao.deleteExpiredMessages(System.currentTimeMillis())
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

    suspend fun getMessagesForExport(convId: String): List<Message> = withContext(Dispatchers.IO) {
        dao.getMessagesForExport(convId)
    }

    suspend fun getMessagesByIds(messageIds: List<String>): List<Message> = withContext(Dispatchers.IO) {
        dao.getMessagesByIds(messageIds)
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
        disappearingDurationSeconds: Int? = null,
        isViewOnce: Boolean = false
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
        ).copy(status = MessageStatus.SENDING, blurHash = blurHash, isViewOnce = isViewOnce)

        
        val effectiveDisappearingDuration = if (disappearingDurationSeconds != null && disappearingDurationSeconds > 0) {
            disappearingDurationSeconds
        } else if (conv != null && conv.disappearingMessagesDuration > 0) {
            conv.disappearingMessagesDuration
        } else {
            null
        }

        if (effectiveDisappearingDuration != null && effectiveDisappearingDuration > 0) {
            localMsg = localMsg.copy(
                disappearingDurationSeconds = effectiveDisappearingDuration,
                expiresAt = System.currentTimeMillis() + (effectiveDisappearingDuration * 1000L)
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
                } else if (localMsg.type == MessageType.IMAGE) {
                    // Critical fallback: If CDN/storage is unconfigured, offline, or returns error (e.g. Render no-storage),
                    // fallback to standard Base64 Data URI so recipient immediately renders the real photo,
                    // rather than failing on an unreachable local device path.
                    val b64 = android.util.Base64.encodeToString(cachedMediaBytes, android.util.Base64.NO_WRAP)
                    finalMediaUrl = "data:$mimeType;base64,$b64"
                }
            }

            // CRITICAL SANITIZATION: Ensure the mediaUrl sent across the wire is NEVER a local device path (/data/..., /storage/..., file://)
            var networkMediaUrl: String? = finalMediaUrl
            if (networkMediaUrl != null && !networkMediaUrl.startsWith("http://") && !networkMediaUrl.startsWith("https://") && !networkMediaUrl.startsWith("data:")) {
                if (cachedMediaBytes != null && localMsg.type == MessageType.IMAGE) {
                    val b64 = android.util.Base64.encodeToString(cachedMediaBytes, android.util.Base64.NO_WRAP)
                    networkMediaUrl = "data:image/jpeg;base64,$b64"
                } else {
                    try {
                        val file = java.io.File(networkMediaUrl.removePrefix("file://"))
                        if (file.exists() && localMsg.type == MessageType.IMAGE) {
                            val fileBytes = file.readBytes()
                            val b64 = android.util.Base64.encodeToString(fileBytes, android.util.Base64.NO_WRAP)
                            networkMediaUrl = "data:image/jpeg;base64,$b64"
                        } else {
                            networkMediaUrl = null
                        }
                    } catch (_: Exception) {
                        networkMediaUrl = null
                    }
                }
            }

            // 3. Directly deliver via HTTP API for instant sub-second delivery
            val body = buildJsonObject {
                put("content", encryptedContent)
                put("type", type)
                if (isViewOnce) {
                    put("is_view_once", true)
                }

                networkMediaUrl?.let { put("media_url", it) }
                blurHash?.let { put("blur_hash", it) }
                telegramFileId?.let { put("telegram_file_id", it) }

                mediaThumbnail?.let { put("media_thumbnail", it) }
                replyToId?.let { put("parent_id", it) }
                if (mentionedUserIds.isNotEmpty()) {
                    put("mentioned_user_ids", JsonArray(mentionedUserIds.map { JsonPrimitive(it) }))
                }
                if (localMsg.disappearingDurationSeconds != null && localMsg.disappearingDurationSeconds > 0) {
                    put("disappearing_messages_duration", localMsg.disappearingDurationSeconds)
                    put("disappearing_duration_seconds", localMsg.disappearingDurationSeconds)
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
                    status = MessageStatus.SENT,
                    isViewOnce = isViewOnce || parsed.isViewOnce,
                    disappearingDurationSeconds = parsed.disappearingDurationSeconds ?: localMsg.disappearingDurationSeconds,
                    expiresAt = parsed.expiresAt ?: localMsg.expiresAt
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

                    if (!msg.isMe) {
                        val conv = dao.getConversationById(msg.conversationId)
                        val isGroup = conv?.isGroup ?: false
                        val displayTitle = resolveSenderTitle(
                            conversationId = msg.conversationId,
                            senderId = msg.senderId,
                            candidateName = msg.senderName,
                            isGroup = isGroup
                        )
                        val avatarUrl = conv?.avatarUrl.orEmpty()
                        val isMuted = conv?.isMuted ?: false

                        NotificationHelper.showChatNotification(
                            context = context,
                            conversationId = msg.conversationId,
                            title = displayTitle,
                            body = msg.content.ifBlank { "New message" },
                            senderAvatar = avatarUrl,
                            isGroup = isGroup,
                            isMuted = isMuted
                        )
                    }
                }
                return msg
            }
        }
        return null
    }

    /**
     * Resolves the human-readable peer or sender display name for a conversation.
     * Prioritizes phonebook contact names, local conversation titles, and verified usernames.
     * Guaranteed never to return a placeholder like "User".
     */
    suspend fun resolveSenderTitle(
        conversationId: String,
        senderId: String,
        candidateName: String = "",
        isGroup: Boolean = false
    ): String {
        val cleanCandidate = candidateName.trim()
        val isValidCandidate = cleanCandidate.isNotBlank() &&
                !cleanCandidate.equals("User", ignoreCase = true) &&
                !cleanCandidate.equals("GoChat Message", ignoreCase = true) &&
                !cleanCandidate.equals("New Message", ignoreCase = true) &&
                !cleanCandidate.equals("Chat", ignoreCase = true)

        // 1. If conversation exists in local Room DB, check its title
        val conv = if (conversationId.isNotBlank()) dao.getConversationById(conversationId) else null
        val effectiveIsGroup = isGroup || (conv?.isGroup == true)

        // 2. Check phonebook / synced contacts for the sender
        val contactName = if (senderId.isNotBlank()) contactSyncManager.getContactName(senderId) else null

        if (effectiveIsGroup) {
            val groupTitle = conv?.title?.takeIf {
                it.isNotBlank() &&
                        !it.equals("Chat", ignoreCase = true) &&
                        !it.equals("User", ignoreCase = true)
            } ?: "Group Chat"

            val memberName = contactName
                ?: (if (isValidCandidate) cleanCandidate else null)
                ?: "GoChat Member"

            return "$memberName ($groupTitle)"
        }

        // 3. For 1-on-1 chats:
        // Priority A: Phonebook contact name (what the user saved the peer as in their phone)
        if (!contactName.isNullOrBlank()) {
            return contactName
        }

        // Priority B: Local conversation title if already established
        val convTitle = conv?.title?.trim()
        if (!convTitle.isNullOrBlank() &&
            !convTitle.equals("Chat", ignoreCase = true) &&
            !convTitle.equals("User", ignoreCase = true) &&
            !convTitle.equals("GoChat Message", ignoreCase = true)
        ) {
            return convTitle
        }

        // Priority C: Candidate name sent in payload (e.g. sender's display_name or username from backend)
        if (isValidCandidate) {
            return cleanCandidate
        }

        // Priority D: If conversation exists but has generic title, fetch latest conversation from API
        if (conversationId.isNotBlank()) {
            try {
                val apiResult = api.getConversation(conversationId)
                if (apiResult.isSuccessful) {
                    val body = apiResult.body()
                    val convObj = body?.get("conversation")?.jsonObject ?: body
                    val remoteTitle = (convObj?.get("name") ?: convObj?.get("title") ?: convObj?.get("display_name"))
                        ?.jsonPrimitive?.contentOrNull?.trim()
                    if (!remoteTitle.isNullOrBlank() &&
                        !remoteTitle.equals("Chat", ignoreCase = true) &&
                        !remoteTitle.equals("User", ignoreCase = true)
                    ) {
                        if (conv != null) {
                            dao.insertConversation(conv.copy(title = remoteTitle))
                        }
                        return remoteTitle
                    }
                }
            } catch (_: Exception) {}
        }

        // Priority E: Fallback
        return "GoChat Contact"
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
            9 -> MessageType.ORDER
            10 -> MessageType.PAYMENT_REQUEST
            11 -> MessageType.CATALOG
            12 -> MessageType.SYSTEM
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
            MessageType.CATALOG -> "🛍️ Product Catalog"
            MessageType.PRODUCT -> "🛒 Product"
            MessageType.PAYMENT_REQUEST -> "💳 Payment Request"
            MessageType.LOCATION -> "📍 Location"
            MessageType.CONTACT -> "👤 Contact"
            MessageType.STICKER -> "🏷️ Sticker"
            MessageType.ORDER -> "📦 Order"
            MessageType.SYSTEM -> "System message"
            else -> "Media"
        }

        val existingConv = dao.getConversationById(conversationId)
        val now = System.currentTimeMillis()
        if (existingConv != null) {
            if (existingConv.title.isBlank() ||
                existingConv.title.equals("Chat", ignoreCase = true) ||
                existingConv.title.equals("User", ignoreCase = true)
            ) {
                val resolvedTitle = resolveSenderTitle(
                    conversationId = conversationId,
                    senderId = senderId,
                    candidateName = senderName,
                    isGroup = existingConv.isGroup
                )
                if (resolvedTitle.isNotBlank() && !resolvedTitle.equals("GoChat Contact", ignoreCase = true)) {
                    dao.insertConversation(existingConv.copy(title = resolvedTitle))
                }
            }
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
            val resolvedTitle = resolveSenderTitle(
                conversationId = conversationId,
                senderId = senderId,
                candidateName = senderName,
                isGroup = false
            )
            val newConv = Conversation(
                id = conversationId,
                title = resolvedTitle,
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
            val conv = if (finalMsg.conversationId.isNotEmpty()) dao.getConversationById(finalMsg.conversationId) else null
            val isGroup = conv?.isGroup ?: false
            val displayTitle = resolveSenderTitle(
                conversationId = finalMsg.conversationId,
                senderId = finalMsg.senderId,
                candidateName = finalMsg.senderName,
                isGroup = isGroup
            )
            val avatarUrl = conv?.avatarUrl.orEmpty()
            val isMuted = conv?.isMuted ?: false

            NotificationHelper.showChatNotification(
                context = context,
                conversationId = finalMsg.conversationId,
                title = displayTitle,
                body = finalMsg.content.ifBlank { "New message" },
                senderAvatar = avatarUrl,
                isGroup = isGroup,
                isMuted = isMuted
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

    suspend fun toggleMessagePin(messageId: String, isPinned: Boolean, conversationId: String = "") {
        dao.updateMessagePinned(messageId, isPinned)
        if (conversationId.isNotEmpty()) {
            try {
                val body = buildJsonObject {
                    put("conversation_id", conversationId)
                }
                if (isPinned) {
                    api.pinMessage(messageId, body)
                } else {
                    api.unpinMessage(messageId, body)
                }
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to sync message pin state to remote server", e)
            }
        }
    }

    suspend fun toggleMessagePinLocally(messageId: String, isPinned: Boolean) {
        dao.updateMessagePinned(messageId, isPinned)
    }

    fun getPinnedMessages(conversationId: String): Flow<List<Message>> {
        return dao.getPinnedMessagesForConversation(conversationId)
    }

    suspend fun deleteMessageLocally(messageId: String) {
        dao.markMessageAsDeleted(messageId)
    }

    suspend fun markMessageAsViewed(messageId: String) {
        dao.markMessageAsViewed(messageId)
    }

    suspend fun editMessageLocally(messageId: String, newContent: String) {
        dao.updateMessageContent(messageId, newContent)
    }

    suspend fun editMessage(messageId: String, newContent: String, conversationId: String = ""): Result<Unit> {
        // 1. Update Room DB locally for instant feedback
        dao.updateMessageContent(messageId, newContent)

        // 2. Sync to backend via PUT /api/v1/chat/messages/:id
        return try {
            val body = buildJsonObject {
                put("content", newContent)
                if (conversationId.isNotBlank()) {
                    put("conversation_id", conversationId)
                }
            }
            val resp = api.editMessage(messageId, body)
            if (resp.isSuccessful) {
                Result.success(Unit)
            } else {
                Log.w("ChatRepository", "Remote editMessage returned code: ${resp.code()}")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.w("ChatRepository", "Failed to sync message edit to remote server", e)
            Result.success(Unit)
        }
    }

    suspend fun translateText(
        text: String,
        targetLanguage: String,
        sourceLanguage: String = ""
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("text", text)
                put("target_language", targetLanguage)
                if (sourceLanguage.isNotBlank()) {
                    put("source_language", sourceLanguage)
                }
            }
            val resp = api.translateMessage(body)
            if (resp.isSuccessful && resp.body() != null) {
                val json = resp.body()!!
                val translated = json["translated_text"]?.jsonPrimitive?.contentOrNull ?: text
                val detected = json["detected_language"]?.jsonPrimitive?.contentOrNull ?: "auto"
                Result.success(Pair(translated, detected))
            } else {
                Result.failure(Exception("Translation API error: ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adjustTone(
        text: String,
        tone: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("text", text)
                put("tone", tone.lowercase())
            }
            val resp = api.adjustTone(body)
            if (resp.isSuccessful && resp.body() != null) {
                val json = resp.body()!!
                val adjusted = json["adjusted_text"]?.jsonPrimitive?.contentOrNull ?: text
                Result.success(adjusted)
            } else {
                Result.failure(Exception("Tone adjustment API error: ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
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

    fun observeStarredMessagesForConversation(conversationId: String): Flow<List<Message>> =
        dao.getStarredMessagesForConversation(conversationId)

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
            MessageType.ORDER -> 9
            MessageType.PAYMENT_REQUEST -> 10
            MessageType.CATALOG -> 11
            MessageType.SYSTEM -> 12
            else -> 0
        }
    }

    suspend fun sendCatalogMessage(
        conversationId: String,
        catalogData: com.example.gochat.data.model.CatalogData
    ): Result<Message> {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val content = json.encodeToString(com.example.gochat.data.model.CatalogData.serializer(), catalogData)
        return sendMessage(
            conversationId = conversationId,
            content = content,
            type = 11
        )
    }

    suspend fun sendSystemMessage(
        conversationId: String,
        content: String
    ): Result<Message> {
        return sendMessage(
            conversationId = conversationId,
            content = content,
            type = 12
        )
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
                val mediaObj = json?.get("media")?.jsonObject ?: json?.get("data")?.jsonObject ?: json
                var rawUrl = (mediaObj?.get("url") ?: mediaObj?.get("Url") ?: mediaObj?.get("URL") ?: mediaObj?.get("media_url"))?.jsonPrimitive?.contentOrNull
                val fileId = (mediaObj?.get("file_id") ?: mediaObj?.get("object_key") ?: mediaObj?.get("fileId"))?.jsonPrimitive?.contentOrNull
                if (rawUrl.isNullOrBlank() && !fileId.isNullOrBlank()) {
                    rawUrl = "/api/v1/media/download/$fileId"
                }
                if (!rawUrl.isNullOrBlank()) {
                    when {
                        rawUrl.startsWith("http://") || rawUrl.startsWith("https://") || rawUrl.startsWith("data:") -> rawUrl
                        rawUrl.startsWith("/") -> "${ApiConstants.BASE_URL.removeSuffix("/")}$rawUrl"
                        rawUrl.startsWith("api/") -> "${ApiConstants.BASE_URL.removeSuffix("/")}/$rawUrl"
                        else -> "${ApiConstants.BASE_URL.removeSuffix("/")}/$rawUrl"
                    }
                } else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun setConversationDisappearingMessages(convId: String, durationSeconds: Int): Result<Unit> {
        return try {
            dao.updateDisappearingMessagesDuration(convId, durationSeconds)

            val noticeText = when (durationSeconds) {
                86400 -> "You set messages to disappear after 24 hours"
                604800 -> "You set messages to disappear after 7 days"
                7776000 -> "You set messages to disappear after 90 days"
                0 -> "You turned off disappearing messages"
                else -> if (durationSeconds > 0) "You set messages to disappear after ${durationSeconds / 3600} hours" else "You turned off disappearing messages"
            }

            val noticeMsg = Message(
                id = "notice_${System.currentTimeMillis()}",
                conversationId = convId,
                senderId = tokenManager.userId ?: "u_me",
                senderName = tokenManager.userDisplayName ?: "Me",
                content = noticeText,
                type = MessageType.TEXT,
                status = MessageStatus.SENT,
                isMe = true,
                createdAt = System.currentTimeMillis()
            )
            dao.insertMessage(noticeMsg)
            dao.updateLastMessage(convId, noticeText, noticeMsg.createdAt, System.currentTimeMillis())

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateConversationDisappearingDurationLocally(convId: String, durationSeconds: Int) {
        try {
            dao.updateDisappearingMessagesDuration(convId, durationSeconds)
        } catch (_: Exception) {}
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
            9 -> MessageType.ORDER
            10 -> MessageType.PAYMENT_REQUEST
            11 -> MessageType.CATALOG
            12 -> MessageType.SYSTEM
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

    fun getMediaMessages(conversationId: String): Flow<List<Message>> =
        dao.getMediaMessagesForConversation(conversationId)

    fun getDocumentMessages(conversationId: String): Flow<List<Message>> =
        dao.getDocumentMessagesForConversation(conversationId)

    fun getLinkMessages(conversationId: String): Flow<List<Message>> =
        dao.getLinkMessagesForConversation(conversationId)

    fun getSharedMediaCount(conversationId: String): Flow<Int> =
        dao.getSharedMediaCount(conversationId)

    fun getRecentMediaPreviews(conversationId: String): Flow<List<Message>> =
        dao.getRecentMediaPreviews(conversationId)
}
