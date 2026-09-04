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

    fun sendTextMessage(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return

        val convId = _conversationId.value
        if (convId.isEmpty()) return

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
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return
        val convId = event["conversation_id"]?.jsonPrimitive?.contentOrNull ?: return

        if (convId != _conversationId.value) return

        when (type) {
            "ping" -> {
                _screenShakeEvent.tryEmit(Unit)
            }
            "typing" -> {
                val isTyping = event["is_typing"]?.jsonPrimitive?.booleanOrNull ?: false
                _isOtherUserTyping.value = isTyping
            }
            "message" -> {
                // Ingest new message
                try {
                    val msgObj = event["message"]?.jsonObject ?: event
                    val msg = NetworkModule.json.decodeFromJsonElement<Message>(msgObj)
                    viewModelScope.launch {
                        chatRepository.insertWebSocketMessage(msg)
                        chatRepository.markConversationAsRead(convId)
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
