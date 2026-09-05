package com.example.gochat.data.repository

import android.content.Context
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.db.AppDatabase
import com.example.gochat.data.db.ChatDao
import com.example.gochat.data.model.*
import com.example.gochat.core.crypto.EncryptionManager
import com.example.gochat.core.notification.NotificationHelper
import com.example.gochat.data.api.ApiConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Central repository for conversations, messages, and real-time event processing.
 * Combines Retrofit API, Room DB (offline-first), and WebSocket event handling.
 */
class ChatRepository(private val context: Context) {

    private val api: GoChatApiService get() = NetworkModule.getApiService(context)
    private val dao: ChatDao get() = AppDatabase.getInstance(context).chatDao()
    private val tokenManager: TokenManager get() = TokenManager.getInstance(context)
    private val encryptionManager = EncryptionManager(context)

    /** The ID of the conversation the user is currently viewing (null = chat list). */
    var activeConversationId: String? = null

    // ═══════════════════════════════════════════════════════════════
    // ── Conversations ────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    fun observeConversations(): Flow<List<Conversation>> = dao.getAllConversations()

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
                if (conversations.isEmpty()) {
                    dao.clearAllConversations()
                } else {
                    val remoteIds = conversations.map { it.id }.toSet()
                    val localConvs = dao.getAllConversationsList()
                    val toDelete = localConvs.filter { it.id !in remoteIds }
                    toDelete.forEach { dao.deleteConversation(it.id) }
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
                        tryDecryptMessage(msg)
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
        mentionedUserIds: List<String> = emptyList()
    ): Result<Message> {
        return try {
            val body = buildJsonObject {
                put("content", content)
                put("type", type)
                mediaUrl?.let { put("media_url", it) }
                telegramFileId?.let { put("telegram_file_id", it) }
                mediaThumbnail?.let { put("media_thumbnail", it) }
                replyToId?.let { put("parent_id", it) }
                if (mentionedUserIds.isNotEmpty()) {
                    put("mentioned_user_ids", JsonArray(mentionedUserIds.map { JsonPrimitive(it) }))
                }
            }

            val response = api.sendMessage(conversationId, body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val msgJson = data["message"]?.jsonObject ?: data
                val parsed = Message.fromJson(msgJson, tokenManager.userId ?: "")
                val finalMsg = parsed.copy(
                    isMe = true,
                    content = content, // Preserve original text typed by user
                    mediaUrl = if (!parsed.mediaUrl.isNullOrBlank()) parsed.mediaUrl else mediaUrl
                )
                dao.insertMessage(finalMsg)
                dao.updateLastMessage(
                    convId = conversationId,
                    lastText = if (finalMsg.content.length > 50) finalMsg.content.take(47) + "..." else finalMsg.content.ifBlank { "Media" },
                    lastTime = finalMsg.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
                Result.success(finalMsg)
            } else {
                val err = response.errorBody()?.string().orEmpty()
                val localMsg = createOptimisticMessage(
                    conversationId, content, type, mediaUrl,
                    replyToId, replyToText, replyToSenderName
                )
                dao.insertMessage(localMsg)
                Result.failure(Exception(err.ifBlank { "Failed to send message" }))
            }
        } catch (e: Exception) {
            val localMsg = createOptimisticMessage(
                conversationId, content, type, mediaUrl,
                replyToId, replyToText, replyToSenderName
            )
            dao.insertMessage(localMsg)
            Result.failure(e)
        }
    }

    suspend fun handleIncomingWebSocketEvent(event: JsonObject): Message? {
        val eventType = (event["event_type"] ?: event["eventType"] ?: event["type"])
            ?.jsonPrimitive?.contentOrNull.orEmpty()
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
            var msg = tryDecryptMessage(Message.fromJson(msgObj, currentUserId))
            if (msg.conversationId.isEmpty() && convId.isNotEmpty()) {
                msg = msg.copy(conversationId = convId)
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
        // Only attempt Signal decryption if it matches a long base64 payload
        if (trimmed.length > 40 && trimmed.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
            val decrypted = encryptionManager.decryptMessage(message.senderId, trimmed)
            if (decrypted.isNotBlank() && decrypted != "[Encrypted Message]" && decrypted != trimmed) {
                return message.copy(content = decrypted)
            }
        }
        return message
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
        val reaction = Reaction(userId = currentUserId, userName = currentUserName, emoji = emoji)
        val reactions = listOf(reaction)
        dao.updateMessageReactions(messageId, reactions)
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
