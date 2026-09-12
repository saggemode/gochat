package com.example.gochat.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.gochat.core.crypto.EncryptionManager
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.*
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.core.sound.ChatSoundManager
import com.example.gochat.data.websocket.GoChatWebSocket
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern
import com.example.gochat.core.network.NetworkMonitor
import javax.inject.Inject

@HiltViewModel
class ChatRoomViewModel @Inject constructor(
    application: Application,
    private val chatRepository: ChatRepository,
    private val tokenManager: TokenManager,
    private val webSocket: GoChatWebSocket,
    private val soundManager: ChatSoundManager,
    private val encryptionManager: EncryptionManager,
    private val networkMonitor: NetworkMonitor
) : AndroidViewModel(application) {

    val isDeviceOnline: Flow<Boolean> = networkMonitor.isOnline

    private val _conversationId = MutableStateFlow("")
    val conversationId: StateFlow<String> = _conversationId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val messagesPaged: Flow<PagingData<Message>> = _conversationId
        .flatMapLatest { id ->
            if (id.isEmpty()) flowOf(PagingData.empty())
            else chatRepository.getMessagesPaged(id)
        }
        .cachedIn(viewModelScope)

    private val _replyingTo = MutableStateFlow<Message?>(null)
    val replyingTo: StateFlow<Message?> = _replyingTo.asStateFlow()

    private val _editingMessage = MutableStateFlow<Message?>(null)
    val editingMessage: StateFlow<Message?> = _editingMessage.asStateFlow()

    private val _isOtherUserTyping = MutableStateFlow(false)
    val isOtherUserTyping: StateFlow<Boolean> = _isOtherUserTyping.asStateFlow()

    private val _isPartnerOnline = MutableStateFlow(false)
    val isPartnerOnline: StateFlow<Boolean> = _isPartnerOnline.asStateFlow()

    private val _partnerLastSeen = MutableStateFlow<Long?>(null)
    val partnerLastSeen: StateFlow<Long?> = _partnerLastSeen.asStateFlow()

    private var currentPartnerId: String? = null

    fun setInitialPresence(isOnline: Boolean, lastSeen: Long?, partnerId: String? = null) {
        _isPartnerOnline.value = isOnline
        _partnerLastSeen.value = if (lastSeen != null && lastSeen > 0L) lastSeen else null
        if (!partnerId.isNullOrEmpty()) {
            currentPartnerId = partnerId
        }
    }

    fun queryPresence() {
        val payload = buildJsonObject {
            put("type", "query_presence")
            put("conversation_id", _conversationId.value)
            currentPartnerId?.let { put("target_user_id", it) }
        }
        webSocket.send(payload)
    }

    private val _members = MutableStateFlow<List<User>>(emptyList())
    val members: StateFlow<List<User>> = _members.asStateFlow()

    private val _mentionSuggestions = MutableStateFlow<List<GroupMember>>(emptyList())
    val mentionSuggestions: StateFlow<List<GroupMember>> = _mentionSuggestions.asStateFlow()

    private val _botConfig = MutableStateFlow<BotConfig?>(null)
    val botConfig: StateFlow<BotConfig?> = _botConfig.asStateFlow()

    private val _disappearingDuration = MutableStateFlow(0) // Seconds, 0 means off
    val disappearingDuration: StateFlow<Int> = _disappearingDuration.asStateFlow()

    private val _screenShakeEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val screenShakeEvent: SharedFlow<Unit> = _screenShakeEvent.asSharedFlow()

    // Real-time messages from Room Database
    @OptIn(ExperimentalCoroutinesApi::class)
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

        // Auto-refresh messages and presence when internet connectivity returns
        viewModelScope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                val id = _conversationId.value
                if (isOnline && id.isNotEmpty()) {
                    chatRepository.refreshMessages(id)
                    chatRepository.fetchConversation(id)
                    queryPresence()
                }
            }
        }
    }

    fun initConversation(convId: String) {
        _conversationId.value = convId
        chatRepository.activeConversationId = convId

        viewModelScope.launch {
            val conv = chatRepository.getConversationById(convId)
            val currentUserId = tokenManager.userId ?: ""
            if (conv != null) {
                if (conv.isDirect) {
                    val partner = conv.memberIds.find { it != currentUserId }
                    if (!partner.isNullOrEmpty()) {
                        currentPartnerId = partner
                    }
                    currentPartnerId?.let { partnerId ->
                        launch(Dispatchers.IO) {
                            try {
                                encryptionManager.establishSession(partnerId)
                            } catch (_: Exception) {}
                        }
                    }
                }

                _isPartnerOnline.value = conv.isOnline
                if (conv.lastSeen != null && conv.lastSeen > 0L) {
                    _partnerLastSeen.value = conv.lastSeen
                }
            }

            // Asynchronously fetch fresh conversation data (including real-time is_online and last_seen)
            launch {
                val remoteResult = chatRepository.fetchConversation(convId)
                remoteResult.getOrNull()?.let { remoteConv ->
                    _isPartnerOnline.value = remoteConv.isOnline
                    if (remoteConv.lastSeen != null && remoteConv.lastSeen > 0L) {
                        _partnerLastSeen.value = remoteConv.lastSeen
                    }
                    if (currentPartnerId == null && remoteConv.isDirect) {
                        val partner = remoteConv.memberIds.find { it != currentUserId }
                        if (!partner.isNullOrEmpty()) {
                            currentPartnerId = partner
                        }
                    }
                }
            }

            chatRepository.markConversationAsRead(convId)
            chatRepository.refreshMessages(convId)
            
            // Send read receipt and query presence from server
            sendReadReceipt(convId)
            queryPresence()
            
            // Populate members for mentions
            loadMembersFromLocal(convId)

            // Mock Bot Config loading
            loadBotConfig(convId)
        }
    }

    private fun loadBotConfig(convId: String) {
        // In a real app, this would fetch from a database or API
        _botConfig.value = BotConfig(
            botId = "bot_123",
            groupId = convId,
            permissions = listOf(BotPermission.ANTI_SPAM, BotPermission.WELCOME_MEMBERS),
            rules = "No spamming allowed!"
        )
    }

    private suspend fun loadMembersFromLocal(convId: String) {
        // Heuristic: extract unique senders from recent messages
        val recentMessages = messages.value
        val users = recentMessages.map { 
            User(id = it.senderId, displayName = it.senderName)
        }.distinctBy { it.id }
        
        if (users.isNotEmpty()) {
            _members.value = users
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
        
        // Extract mentions
        val mentions = extractMentionedUserIds(trimmed)

        viewModelScope.launch {
            chatRepository.sendMessage(
                conversationId = convId,
                content = trimmed,
                type = 0, // text
                replyToId = reply?.id,
                replyToText = reply?.content,
                replyToSenderName = reply?.senderName,
                mentionedUserIds = mentions,
                disappearingDurationSeconds = _disappearingDuration.value
            )
            clearReply()

            // Send live typing stop
            sendTypingEvent(false)
        }
    }

    private fun extractMentionedUserIds(text: String): List<String> {
        val pattern = Pattern.compile("@[\\w]+")
        val matcher = pattern.matcher(text)
        val mentionedNames = mutableListOf<String>()
        while (matcher.find()) {
            mentionedNames.add(matcher.group().substring(1))
        }
        
        return _members.value.filter { it.displayName in mentionedNames }.map { it.id }
    }

    fun addReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            chatRepository.addReactionLocally(messageId, emoji)
            
            // Send to server
            val payload = buildJsonObject {
                put("type", "add_reaction")
                put("message_id", messageId)
                put("emoji", emoji)
                put("conversation_id", _conversationId.value)
            }
            webSocket.send(payload)
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

    fun sendProductMessage(
        productId: String,
        name: String,
        price: Double,
        image: String,
        inquiry: String
    ) {
        val convId = _conversationId.value
        if (convId.isEmpty()) return

        val productJson = buildJsonObject {
            put("inquiry", inquiry)
            put("product", buildJsonObject {
                put("id", productId)
                put("name", name)
                put("price", price)
                put("image", image)
            })
        }.toString()

        viewModelScope.launch {
            chatRepository.sendMessage(
                conversationId = convId,
                content = productJson,
                type = 7 // Product
            )
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
                replyToSenderName = reply?.senderName,
                disappearingDurationSeconds = _disappearingDuration.value
            )
            clearReply()
        }
    }

    fun sendImageMessage(
        bytes: ByteArray,
        dataUriFallback: String,
        caption: String = ""
    ) {
        val convId = _conversationId.value
        if (convId.isEmpty()) return

        val reply = _replyingTo.value
        viewModelScope.launch {
            // 1. Save to a temporary file for background upload
            val filePath = withContext(Dispatchers.IO) {
                try {
                    val file = File(getApplication<Application>().cacheDir, "pending_upload_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { it.write(bytes) }
                    file.absolutePath
                } catch (e: Exception) {
                    null
                }
            }

            val finalMediaUrl = filePath ?: dataUriFallback

            // 2. Send immediately with the local path
            chatRepository.sendMessage(
                conversationId = convId,
                content = caption.ifBlank { "Photo" },
                type = 1, // Image
                mediaUrl = finalMediaUrl,
                replyToId = reply?.id,
                replyToText = reply?.content,
                replyToSenderName = reply?.senderName,
                disappearingDurationSeconds = _disappearingDuration.value
            )
            clearReply()
        }
    }

    fun sendPing() {
        val convId = _conversationId.value
        if (convId.isEmpty()) return

        viewModelScope.launch {
            chatRepository.sendMessage(
                conversationId = convId,
                content = "💥 PING!!!",
                type = 7 // ping
            )

            val pingPayload = buildJsonObject {
                put("type", "ping")
                put("conversation_id", convId)
                put("sender_id", tokenManager.userId ?: "")
            }
            webSocket.send(pingPayload)
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

    fun sendReadReceipt(convId: String) {
        val payload = buildJsonObject {
            put("type", "read_receipt")
            put("conversation_id", convId)
            put("sender_id", tokenManager.userId ?: "")
        }
        webSocket.send(payload)
    }

    fun onInputTextChanged(text: String, cursorPosition: Int) {
        val beforeCursor = text.take(cursorPosition)
        val lastAt = beforeCursor.lastIndexOf('@')
        
        if (lastAt != -1 && (lastAt == 0 || beforeCursor[lastAt - 1] == ' ')) {
            val query = beforeCursor.substring(lastAt + 1).lowercase()
            _mentionSuggestions.value = _members.value.filter {
                it.displayName.lowercase().contains(query)
            }.map { GroupMember(id = it.id, displayName = it.displayName, avatarUrl = it.avatarUrl) }
        } else {
            _mentionSuggestions.value = emptyList()
        }
    }

    fun setMembers(membersList: List<User>) {
        _members.value = membersList
    }

    fun setDisappearingMessages(durationSeconds: Int) {
        _disappearingDuration.value = durationSeconds
    }

    private fun handleWebSocketEvent(event: JsonObject) {
        val type = (event["type"] ?: event["event_type"])?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()

        if (type == "presence") {
            val userId = (event["user_id"] ?: event["userId"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val isOnline = (event["is_online"] ?: event["isOnline"])?.jsonPrimitive?.booleanOrNull ?: false
            val lastSeenSec = (event["last_seen"] ?: event["lastSeen"])?.jsonPrimitive?.longOrNull
            val lastSeenMs = if (lastSeenSec != null) {
                if (lastSeenSec < 100_000_000_000L) lastSeenSec * 1000L else lastSeenSec
            } else System.currentTimeMillis()

            val currentUserId = tokenManager.userId ?: ""
            if (userId.isNotEmpty() && userId != currentUserId) {
                if (currentPartnerId.isNullOrEmpty()) {
                    currentPartnerId = userId
                }
                val isPartner = userId == currentPartnerId
                val isMember = _members.value.any { it.id == userId }
                
                if (isPartner || isMember || _members.value.isEmpty()) {
                    _isPartnerOnline.value = isOnline
                    if (!isOnline) {
                        _partnerLastSeen.value = lastSeenMs
                    }
                }
            }
            return
        }

        val rawMsg = event["message"] ?: event["Message"]
        val convId = (event["conversation_id"] ?: event["conversationId"]
            ?: (if (rawMsg is JsonObject) rawMsg["conversation_id"] ?: rawMsg["conversationId"] else null))
            ?.jsonPrimitive?.contentOrNull.orEmpty()

        if (convId.isNotEmpty() && convId != _conversationId.value) return

        when {
            type == "ping" -> {
                _screenShakeEvent.tryEmit(Unit)
                _isPartnerOnline.value = true
            }
            type == "typing" -> {
                val isTyping = event["is_typing"]?.jsonPrimitive?.booleanOrNull ?: false
                _isOtherUserTyping.value = isTyping
                val senderId = (event["sender_id"] ?: event["user_id"])?.jsonPrimitive?.contentOrNull.orEmpty()
                val currentUserId = tokenManager.userId ?: ""
                if (senderId.isNotEmpty() && senderId != currentUserId && currentPartnerId.isNullOrEmpty()) {
                    currentPartnerId = senderId
                }
                // Typing implies the user is online
                if (isTyping) {
                    _isPartnerOnline.value = true
                }
            }
            type == "read_receipt" || type == "event_read_receipt" -> {
                val readerId = (event["sender_id"] ?: event["user_id"])?.jsonPrimitive?.contentOrNull.orEmpty()
                val currentUserId = tokenManager.userId ?: ""
                if (readerId.isNotEmpty() && readerId != currentUserId) {
                    _isPartnerOnline.value = true
                    if (currentPartnerId.isNullOrEmpty()) {
                        currentPartnerId = readerId
                    }
                    viewModelScope.launch {
                        chatRepository.markOutgoingMessagesAsRead(convId)
                    }
                }
            }
            type == "message_status" || type == "status_update" -> {
                val msgId = (event["message_id"] ?: event["messageId"])?.jsonPrimitive?.contentOrNull.orEmpty()
                val statusStr = (event["status"] ?: event["Status"])?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                
                val status = when {
                    statusStr.contains("read") -> MessageStatus.READ
                    statusStr.contains("deliver") -> MessageStatus.DELIVERED
                    statusStr.contains("sent") -> MessageStatus.SENT
                    else -> null
                }
                
                if (msgId.isNotEmpty() && status != null) {
                    viewModelScope.launch {
                        chatRepository.updateMessageStatus(msgId, status)
                        // Play sound only on 'delivered' for my message
                        if (status == MessageStatus.DELIVERED) {
                            soundManager.playSentSound()
                        }
                    }
                }
            }
            type == "new_message" || type == "message_edited" || type == "message_deleted" || 
            type == "reaction_added" || type == "reaction_removed" || 
            type == "event_new_message" || type == "message" || type == "chat_message" || event.containsKey("message") -> {
                try {
                    val msgObj = if (rawMsg is JsonObject) rawMsg else event
                    val currentUserId = tokenManager.userId ?: ""
                    val msg = Message.fromJson(msgObj, currentUserId)
                    
                    // Simple Bot Moderation Logic (Client-side Simulation)
                    handleBotModeration(msg)

                    if (msg.type == MessageType.PING) {
                        _screenShakeEvent.tryEmit(Unit)
                    }
                    if (msg.senderId != currentUserId) {
                        _isPartnerOnline.value = true
                        if (currentPartnerId.isNullOrEmpty()) {
                            currentPartnerId = msg.senderId
                        }
                        soundManager.playReceivedSound()
                        // If we are active, mark as read immediately
                        if (convId == _conversationId.value) {
                            sendReadReceipt(convId)
                        }
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

    private fun handleBotModeration(message: Message) {
        val config = _botConfig.value ?: return
        if (!config.isActive) return

        // Anti-spam simulation
        if (config.permissions.contains(BotPermission.ANTI_SPAM)) {
            if (message.content.lowercase().contains("spam")) {
                // In a real bot, this would trigger a warning/deletion on the server
                // Client-side, we just show a mock indication or log it
                Log.d("BotModerator", "Detected spam in message: ${message.id}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        chatRepository.activeConversationId = null
    }
}
