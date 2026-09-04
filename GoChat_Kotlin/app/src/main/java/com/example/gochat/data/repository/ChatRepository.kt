package com.example.gochat.data.repository

import android.content.Context
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.db.AppDatabase
import com.example.gochat.data.db.ChatDao
import com.example.gochat.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*

/**
 * Central repository for conversations, messages, and real-time event processing.
 * Combines Retrofit API, Room DB (offline-first), and WebSocket event handling.
 *
 * Replaces the conversation/message portions of Flutter's monolithic `AppState`.
 */
class ChatRepository(private val context: Context) {

    private val api: GoChatApiService get() = NetworkModule.getApiService(context)
    private val dao: ChatDao get() = AppDatabase.getInstance(context).chatDao()
    private val tokenManager: TokenManager get() = TokenManager.getInstance(context)

    /** The ID of the conversation the user is currently viewing (null = chat list). */
    var activeConversationId: String? = null

    // ═══════════════════════════════════════════════════════════════
    // ── Conversations ────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    /** Room Flow that auto-updates the UI when conversations change. */
    fun observeConversations(): Flow<List<Conversation>> = dao.getAllConversations()

    /**
     * Fetch conversations from the API and upsert into Room.
     */
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

    /**
     * Create a new conversation (direct or group).
     */
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

    /** Room Flow for real-time message list updates in a chat room. */
    fun observeMessages(convId: String): Flow<List<Message>> =
        dao.getMessagesForConversation(convId)

    /**
     * Fetch messages from the API and cache in Room.
     */
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
                    if (element is JsonObject) Message.fromJson(element, currentUserId) else null
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

    /**
     * Send a text or media message to a conversation.
     */
    suspend fun sendMessage(
        conversationId: String,
        content: String,
        type: Int = 0,
        mediaUrl: String? = null,
        telegramFileId: String? = null,
        mediaThumbnail: String? = null,
        replyToId: String? = null,
        replyToText: String? = null,
        replyToSenderName: String? = null
    ): Result<Message> {
        return try {
            val body = buildJsonObject {
                put("content", content)
                put("type", type)
                mediaUrl?.let { put("media_url", it) }
                telegramFileId?.let { put("telegram_file_id", it) }
                mediaThumbnail?.let { put("media_thumbnail", it) }
                replyToId?.let { put("parent_id", it) }
            }

            val response = api.sendMessage(conversationId, body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val msgJson = data["message"]?.jsonObject ?: data
                val finalMsg = Message.fromJson(msgJson, tokenManager.userId ?: "").copy(isMe = true)
                dao.insertMessage(finalMsg)
                dao.updateLastMessage(
                    convId = conversationId,
                    lastText = finalMsg.content.ifBlank { "Media" },
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
                Result.failure(Exception(err.ifBlank { "Failed to send message (${response.code()})" }))
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

    /**
     * Ingest and process an incoming real-time WebSocket event.
     */
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
            var msg = Message.fromJson(msgObj, currentUserId)
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
        val finalMsg = message.copy(
            isMe = message.senderId == (tokenManager.userId ?: "")
        )
        dao.insertMessage(finalMsg)
    }

    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        dao.updateMessageStatus(messageId, status)
    }

    // ── Polls ────────────────────────────────────────────────────

    suspend fun votePoll(pollId: String, optionId: String): Result<Unit> {
        return try {
            val body = buildJsonObject { put("option_id", optionId) }
            val response = api.votePoll(pollId, body)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val err = response.errorBody()?.string().orEmpty()
                Result.failure(Exception(err.ifBlank { "Vote failed" }))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Messaging Enhancements ───────────────────────────────────

    suspend fun toggleMessageStar(messageId: String, isStarred: Boolean) {
        dao.updateMessageStarred(messageId, isStarred)
    }

    suspend fun deleteMessageLocally(messageId: String) {
        dao.markMessageAsDeleted(messageId)
        // Placeholder for API call: api.deleteMessage(messageId)
    }

    suspend fun editMessageLocally(messageId: String, newContent: String) {
        dao.updateMessageContent(messageId, newContent)
        // Placeholder for API call: api.editMessage(messageId, buildJsonObject { put("content", newContent) })
    }

    suspend fun addReactionLocally(messageId: String, emoji: String) {
        val currentUserId = tokenManager.userId ?: "u_me"
        val currentUserName = tokenManager.userDisplayName ?: "Me"
        val reaction = Reaction(userId = currentUserId, userName = currentUserName, emoji = emoji)
        
        // This is a simplified logic for demo; real logic should merge with existing reactions
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
            // Tagging as forwarded
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

    // ── Private Helpers ──────────────────────────────────────────

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
            3 -> MessageType.VOICE
            4 -> MessageType.FILE
            5 -> MessageType.POLL
            6 -> MessageType.PRODUCT
            7 -> MessageType.PING
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
