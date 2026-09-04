package com.example.gochat.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.MessageType
import com.example.gochat.data.repository.ChatRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class ChatRoomViewModel(application: Application) : AndroidViewModel(application) {

    private val chatRepository = ChatRepository(application)
    private val tokenManager = TokenManager.getInstance(application)
    private val webSocket = NetworkModule.getWebSocket(application)

    private val _conversationId = MutableStateFlow("")
    val conversationId: StateFlow<String> = _conversationId.asStateFlow()

    private val _replyingTo = MutableStateFlow<Message?>(null)
    val replyingTo: StateFlow<Message?> = _replyingTo.asStateFlow()

    private val _editingMessage = MutableStateFlow<Message?>(null)
    val editingMessage: StateFlow<Message?> = _editingMessage.asStateFlow()

    private val _isOtherUserTyping = MutableStateFlow(false)
    val isOtherUserTyping: StateFlow<Boolean> = _isOtherUserTyping.asStateFlow()

    private val _screenShakeEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val screenShakeEvent: SharedFlow<Unit> = _screenShakeEvent.asSharedFlow()

    // Real-time messages from Room Database
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = _conversationId
        .flatMapLatest { id ->
            if (id.isEmpty()) flowOf(emptyList())
            else chatRepository.observeMessages(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Collect incoming WebSocket events
        viewModelScope.launch {
            webSocket.events.collect { eventJson ->
                handleWebSocketEvent(eventJson)
            }
        }
    }

    fun initConversation(convId: String) {
        _conversationId.value = convId
        chatRepository.activeConversationId = convId

        viewModelScope.launch {
            chatRepository.markConversationAsRead(convId)
            chatRepository.refreshMessages(convId)
        }
    }

    fun setReplyingTo(message: Message?) {
        _replyingTo.value = message
    }

    fun clearReply() {
        _replyingTo.value = null
    }

    fun setEditingMessage(message: Message?) {
        _editingMessage.value = message
    }

    fun clearEditing() {
        _editingMessage.value = null
    }

    fun sendTextMessage(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return

        val convId = _conversationId.value
        if (convId.isEmpty()) return

        val edit = _editingMessage.value
        if (edit != null) {
            viewModelScope.launch {
                chatRepository.editMessageLocally(edit.id, trimmed)
                clearEditing()
            }
            return
        }

        val reply = _replyingTo.value

        viewModelScope.launch {
            chatRepository.sendMessage(
                conversationId = convId,
                content = trimmed,
                type = 0, // text
                replyToId = reply?.id,
                replyToText = reply?.content,
                replyToSenderName = reply?.senderName
            )
            clearReply()

            // Send live typing stop
            sendTypingEvent(false)
        }
    }

    fun addReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            chatRepository.addReactionLocally(messageId, emoji)
        }
    }

    fun deleteMessageForEveryone(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessageLocally(messageId)
        }
    }

    fun toggleStar(messageId: String, isStarred: Boolean) {
        viewModelScope.launch {
            chatRepository.toggleMessageStar(messageId, isStarred)
        }
    }

    fun forwardMessage(message: Message, targetConversationId: String) {
        viewModelScope.launch {
            chatRepository.forwardMessage(message, targetConversationId)
        }
    }

    fun sendMediaMessage(mediaUrl: String, type: Int, caption: String = "") {
        val convId = _conversationId.value
        if (convId.isEmpty()) return

        val reply = _replyingTo.value
        viewModelScope.launch {
            chatRepository.sendMessage(
                conversationId = convId,
                content = caption.ifBlank { if (type == 1) "📷 Photo" else "🎙️ Voice Note" },
                type = type,
                mediaUrl = mediaUrl,
                replyToId = reply?.id,
                replyToText = reply?.content,
                replyToSenderName = reply?.senderName
            )
            clearReply()
        }
    }

    /**
     * Sends BBM PING! event across WebSocket and posts local ping bubble.
     */
    fun sendPing() {
        val convId = _conversationId.value
        if (convId.isEmpty()) return

        viewModelScope.launch {
            // Send ping message to repository
            chatRepository.sendMessage(
                conversationId = convId,
                content = "💥 PING!!!",
                type = 7 // ping
            )

            // Emit live WebSocket ping event
            val pingPayload = buildJsonObject {
                put("type", "ping")
                put("conversation_id", convId)
                put("sender_id", tokenManager.userId ?: "")
            }
            webSocket.send(pingPayload)

            // Trigger local screen shake
            _screenShakeEvent.tryEmit(Unit)
        }
    }

    fun sendTypingEvent(isTyping: Boolean) {
        val convId = _conversationId.value
        if (convId.isEmpty()) return

        val payload = buildJsonObject {
            put("type", "typing")
            put("conversation_id", convId)
            put("is_typing", isTyping)
        }
        webSocket.send(payload)
    }

    private fun handleWebSocketEvent(event: JsonObject) {
        val type = (event["type"] ?: event["event_type"])?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        val rawMsg = event["message"] ?: event["Message"]
        val convId = (event["conversation_id"] ?: event["conversationId"]
            ?: (if (rawMsg is JsonObject) rawMsg["conversation_id"] ?: rawMsg["conversationId"] else null))
            ?.jsonPrimitive?.contentOrNull.orEmpty()

        if (convId.isNotEmpty() && convId != _conversationId.value) return

        when {
            type == "ping" -> {
                _screenShakeEvent.tryEmit(Unit)
            }
            type == "typing" -> {
                val isTyping = event["is_typing"]?.jsonPrimitive?.booleanOrNull ?: false
                _isOtherUserTyping.value = isTyping
            }
            type == "new_message" || type == "message_edited" || type == "message_deleted" || 
            type == "reaction_added" || type == "reaction_removed" || 
            type == "event_new_message" || type == "message" || type == "chat_message" || event.containsKey("message") -> {
                try {
                    val msgObj = if (rawMsg is JsonObject) rawMsg else event
                    val currentUserId = tokenManager.userId ?: ""
                    val msg = Message.fromJson(msgObj, currentUserId)
                    if (msg.type == MessageType.PING) {
                        _screenShakeEvent.tryEmit(Unit)
                    }
                    viewModelScope.launch {
                        chatRepository.insertWebSocketMessage(msg)
                        if (convId.isNotEmpty()) {
                            chatRepository.markConversationAsRead(convId)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            chatRepository.updateMessageStatus(messageId, com.example.gochat.data.model.MessageStatus.FAILED)
        }
    }

    override fun onCleared() {
        super.onCleared()
        chatRepository.activeConversationId = null
    }
}
