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
    val partnerId: String? get() = currentPartnerId

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

    private val _screenshotNotificationsEnabled = MutableStateFlow(false)
    val screenshotNotificationsEnabled: StateFlow<Boolean> = _screenshotNotificationsEnabled.asStateFlow()

    private val _screenShakeEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val screenShakeEvent: SharedFlow<Unit> = _screenShakeEvent.asSharedFlow()

    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    // Real-time messages from Room Database
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = _conversationId
        .flatMapLatest { id ->
            if (id.isEmpty()) flowOf(emptyList())
            else chatRepository.observeMessages(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val pinnedMessages: StateFlow<List<Message>> = _conversationId
        .flatMapLatest { id ->
            if (id.isEmpty()) flowOf(emptyList())
            else chatRepository.getPinnedMessages(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Message>>(emptyList())
    val searchResults: StateFlow<List<Message>> = _searchResults.asStateFlow()

    private val _currentSearchIndex = MutableStateFlow(0)
    val currentSearchIndex: StateFlow<Int> = _currentSearchIndex.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _currentSearchIndex.value = 0
            return
        }
        viewModelScope.launch {
            val results = chatRepository.searchMessagesInConversation(_conversationId.value, query)
            _searchResults.value = results
            _currentSearchIndex.value = if (results.isNotEmpty()) 0 else -1
        }
    }

    fun nextSearchResult() {
        val list = _searchResults.value
        if (list.isEmpty()) return
        val next = (_currentSearchIndex.value + 1) % list.size
        _currentSearchIndex.value = next
    }

    fun prevSearchResult() {
        val list = _searchResults.value
        if (list.isEmpty()) return
        val prev = if (_currentSearchIndex.value - 1 < 0) list.size - 1 else _currentSearchIndex.value - 1
        _currentSearchIndex.value = prev
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _currentSearchIndex.value = 0
    }

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

    @Volatile
    private var isScreenResumed: Boolean = false

    fun onScreenResumed(convId: String) {
        isScreenResumed = true
        _conversationId.value = convId
        chatRepository.activeConversationId = convId
        viewModelScope.launch {
            if (convId.isNotEmpty()) {
                chatRepository.markConversationAsRead(convId)
                sendReadReceipt(convId)
            }
        }
    }

    fun onScreenPaused() {
        isScreenResumed = false
        chatRepository.activeConversationId = null
    }

    fun initConversation(convId: String) {
        _conversationId.value = convId
        chatRepository.activeConversationId = convId
        isScreenResumed = true

        viewModelScope.launch {
            val conv = chatRepository.getConversationById(convId)
            val currentUserId = tokenManager.userId ?: ""
            if (conv != null) {
                _screenshotNotificationsEnabled.value = conv.screenshotNotificationsEnabled
                _disappearingDuration.value = conv.disappearingMessagesDuration
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
                    _disappearingDuration.value = remoteConv.disappearingMessagesDuration
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

    private fun getReplyPreviewText(message: Message): String {
        return when (message.type) {
            MessageType.TEXT -> message.content.take(200)
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
            else -> message.content.take(200).ifBlank { "Media" }
        }
    }

    fun sendTextMessage(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return

        val convId = _conversationId.value
        if (convId.isEmpty()) return

        val edit = _editingMessage.value
        if (edit != null) {
            val isWithin15Minutes = (System.currentTimeMillis() - edit.createdAt) <= 15 * 60 * 1000L
            if (!isWithin15Minutes) {
                clearEditing()
                return
            }
            viewModelScope.launch {
                chatRepository.editMessage(edit.id, trimmed, convId)
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
                replyToText = reply?.let { getReplyPreviewText(it) },
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
            chatRepository.deleteMessageForEveryone(messageId)
        }
    }

    fun deleteMessageForMe(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessageForMe(messageId)
        }
    }

    fun deleteMessagesForMe(messageIds: List<String>) {
        viewModelScope.launch {
            chatRepository.deleteMessagesForMe(messageIds)
        }
    }

    fun deleteMessagesForEveryone(messageIds: List<String>) {
        viewModelScope.launch {
            messageIds.forEach { chatRepository.deleteMessageForEveryone(it) }
        }
    }

    fun clearChat(deleteStarred: Boolean, onComplete: (() -> Unit)? = null) {
        val convId = _conversationId.value
        if (convId.isEmpty()) return
        viewModelScope.launch {
            val result = chatRepository.clearChat(convId, deleteStarred)
            if (result.isSuccess) {
                onComplete?.invoke()
            }
        }
    }

    fun getStarredMessagesCount(onResult: (Int) -> Unit) {
        val convId = _conversationId.value
        if (convId.isEmpty()) {
            onResult(0)
            return
        }
        viewModelScope.launch {
            val count = chatRepository.getStarredMessagesCount(convId)
            onResult(count)
        }
    }

    fun toggleStar(messageId: String, isStarred: Boolean) {
        viewModelScope.launch {
            chatRepository.toggleMessageStar(messageId, isStarred)
        }
    }

    fun togglePin(messageId: String, isPinned: Boolean) {
        val convId = _conversationId.value
        viewModelScope.launch {
            chatRepository.toggleMessagePin(messageId, isPinned, convId)
        }
    }

    suspend fun translateMessage(text: String, targetLang: String): Result<Pair<String, String>> {
        return chatRepository.translateText(text, targetLang)
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
        inquiry: String,
        targetConvId: String? = null
    ) {
        val convId = targetConvId ?: _conversationId.value
        if (convId.isEmpty()) return

        val productJson = buildJsonObject {
            put("type", "product")
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

    fun sendCatalogMessage(
        selectedProducts: List<Product>,
        targetConvId: String? = null
    ) {
        val convId = targetConvId ?: _conversationId.value
        if (convId.isEmpty() || selectedProducts.isEmpty()) return

        val firstProd = selectedProducts.first()
        val storeId = firstProd.storeId.ifBlank { "store_default" }
        val storeName = firstProd.storeName.ifBlank { "Official Store" }
        val previewImages = selectedProducts.mapNotNull { it.primaryImage.ifBlank { null } }.take(3)
        val catalogId = "cat_${System.currentTimeMillis()}"

        val catalogJson = buildJsonObject {
            put("type", "catalog")
            put("catalog_id", catalogId)
            put("store_id", storeId)
            put("store_name", storeName)
            put("item_count", selectedProducts.size)
            put("preview_images", buildJsonArray {
                previewImages.forEach { add(JsonPrimitive(it)) }
            })
        }.toString()

        viewModelScope.launch {
            chatRepository.sendMessage(
                conversationId = convId,
                content = catalogJson,
                type = 11 // MessageType.CATALOG
            )
        }
    }

    fun sendPaymentRequestMessage(
        itemName: String,
        amount: Double,
        note: String,
        targetConvId: String? = null
    ) {
        val convId = targetConvId ?: _conversationId.value
        if (convId.isEmpty()) return

        val paymentId = "pay_req_${System.currentTimeMillis()}"
        val paymentJson = buildJsonObject {
            put("type", "payment_request")
            put("payment_request", buildJsonObject {
                put("id", paymentId)
                put("item_name", itemName)
                put("amount", amount)
                put("note", note)
                put("status", "pending")
            })
        }.toString()

        viewModelScope.launch {
            chatRepository.sendMessage(
                conversationId = convId,
                content = paymentJson,
                type = 10 // Payment Request
            )
        }
    }

    fun updatePaymentRequestStatus(
        messageId: String,
        currentContent: String,
        newStatus: String
    ) {
        viewModelScope.launch {
            try {
                val rootObj = Json.decodeFromString<JsonObject>(currentContent)
                val invObj = rootObj["payment_request"]?.jsonObject ?: rootObj["invoice"]?.jsonObject ?: rootObj

                val updatedPaymentObj = buildJsonObject {
                    invObj.forEach { (key, value) ->
                        if (key == "status") {
                            put("status", newStatus)
                        } else {
                            put(key, value)
                        }
                    }
                    if (!invObj.containsKey("status")) {
                        put("status", newStatus)
                    }
                }

                val updatedRoot = buildJsonObject {
                    put("type", "payment_request")
                    put("payment_request", updatedPaymentObj)
                }.toString()

                chatRepository.editMessageLocally(messageId, updatedRoot)

                val wsPayload = buildJsonObject {
                    put("type", "payment_status_update")
                    put("conversation_id", _conversationId.value)
                    put("message_id", messageId)
                    put("status", newStatus)
                    put("updated_content", updatedRoot)
                }
                webSocket.send(wsPayload)

                // Post shared timeline system message
                val itemName = invObj["item_name"]?.jsonPrimitive?.contentOrNull
                    ?: invObj["name"]?.jsonPrimitive?.contentOrNull ?: "Item"
                val timelineMsg = when (newStatus) {
                    "paid_escrow" -> "🛡️ Escrow Payment: Payment for \"$itemName\" is held securely in Escrow"
                    "shipped" -> "📦 Order marked as shipped 🚚 · \"$itemName\" is in transit to buyer"
                    "completed" -> "🎉 Order completed! Receipt for \"$itemName\" confirmed & funds released to seller"
                    else -> null
                }
                if (timelineMsg != null && _conversationId.value.isNotBlank()) {
                    chatRepository.sendSystemMessage(_conversationId.value, timelineMsg)
                }
            } catch (e: Exception) {
                Log.e("ChatRoomVM", "Failed to update payment status: ${e.message}")
            }
        }
    }

    fun sendSystemMessage(content: String, targetConvId: String? = null) {
        val convId = targetConvId ?: _conversationId.value
        if (convId.isEmpty()) return

        viewModelScope.launch {
            chatRepository.sendSystemMessage(
                conversationId = convId,
                content = content
            )
        }
    }

    fun sendOrderMessage(
        order: Order,
        targetConvId: String? = null
    ) {
        val convId = targetConvId ?: _conversationId.value
        if (convId.isEmpty()) return

        val itemsSummary = if (order.items.isNotEmpty()) {
            order.items.joinToString(", ") { "${it.productName} (x${it.quantity})" }
        } else {
            "Marketplace Order"
        }

        val orderJson = buildJsonObject {
            put("type", "order")
            put("order", buildJsonObject {
                put("id", order.id)
                put("order_number", order.orderNumber)
                put("store_id", order.storeId)
                put("store_name", order.storeName)
                put("buyer_id", order.buyerId)
                put("buyer_name", order.buyerName)
                put("total_amount", order.totalAmount)
                put("status", order.status.name)
                put("shipping_address", order.shippingAddress ?: "Lagos, Nigeria")
                put("items_count", order.items.size)
                put("items_summary", itemsSummary)
                put("created_at", order.createdAt)
            })
        }.toString()

        viewModelScope.launch {
            chatRepository.sendMessage(
                conversationId = convId,
                content = orderJson,
                type = 9 // Order
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
                replyToText = reply?.let { getReplyPreviewText(it) },
                replyToSenderName = reply?.senderName,
                disappearingDurationSeconds = _disappearingDuration.value
            )
            clearReply()
        }
    }

    fun sendImageMessage(
        bytes: ByteArray,
        dataUriFallback: String,
        caption: String = "",
        isViewOnce: Boolean = false
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
                content = caption.ifBlank { if (isViewOnce) "① Photo" else "Photo" },
                type = 1, // Image
                mediaUrl = finalMediaUrl,
                replyToId = reply?.id,
                replyToText = reply?.let { getReplyPreviewText(it) },
                replyToSenderName = reply?.senderName,
                disappearingDurationSeconds = _disappearingDuration.value,
                isViewOnce = isViewOnce
            )
            clearReply()
        }
    }

    fun markMessageAsViewed(messageId: String) {
        viewModelScope.launch {
            chatRepository.markMessageAsViewed(messageId)
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
            put("event_type", "typing")
            put("conversation_id", convId)
            put("is_typing", isTyping)
            tokenManager.userId?.let { put("sender_id", it) }
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
        val convId = _conversationId.value
        if (convId.isNotEmpty()) {
            viewModelScope.launch {
                chatRepository.setConversationDisappearingMessages(convId, durationSeconds)
                val payload = buildJsonObject {
                    put("type", "disappearing_messages_changed")
                    put("conversation_id", convId)
                    put("duration", durationSeconds)
                    put("sender_id", tokenManager.userId ?: "")
                    currentPartnerId?.let { put("target_user_id", it) }
                }
                webSocket.send(payload)
            }
        }
    }

    fun toggleScreenshotNotifications(enabled: Boolean) {
        val convId = _conversationId.value
        if (convId.isEmpty()) return

        viewModelScope.launch {
            val result = chatRepository.toggleScreenshotNotifications(convId, enabled)
            if (result.isSuccess) {
                _screenshotNotificationsEnabled.value = enabled
                
                // Notify partner about setting change via WebSocket
                val payload = buildJsonObject {
                    put("type", "screenshot_setting_changed")
                    put("conversation_id", convId)
                    put("enabled", enabled)
                    put("sender_name", tokenManager.userDisplayName ?: "Someone")
                }
                webSocket.send(payload)
            }
        }
    }

    fun sendScreenshotNotification() {
        val convId = _conversationId.value
        if (convId.isEmpty() || !_screenshotNotificationsEnabled.value) return

        viewModelScope.launch {
            // Send special message or just a WS event
            // Sending as a message so it persists in history
            chatRepository.sendMessage(
                conversationId = convId,
                content = "📸 Took a screenshot",
                type = 0 // Using text for now, but could be a specific type
            )

            val payload = buildJsonObject {
                put("type", "screenshot_taken")
                put("conversation_id", convId)
                put("sender_name", tokenManager.userDisplayName ?: "Someone")
            }
            webSocket.send(payload)
        }
    }

    private fun handleWebSocketEvent(event: JsonObject) {
        val type = (event["type"] ?: event["event_type"] ?: event["event"])?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()

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
            type == "message_pinned" || type == "event_message_pinned" -> {
                val msgId = (event["msg_id"] ?: event["message_id"] ?: event["messageId"])?.jsonPrimitive?.contentOrNull.orEmpty()
                if (msgId.isNotEmpty()) {
                    viewModelScope.launch {
                        chatRepository.toggleMessagePinLocally(msgId, true)
                    }
                }
            }
            type == "message_unpinned" || type == "event_message_unpinned" -> {
                val msgId = (event["msg_id"] ?: event["message_id"] ?: event["messageId"])?.jsonPrimitive?.contentOrNull.orEmpty()
                if (msgId.isNotEmpty()) {
                    viewModelScope.launch {
                        chatRepository.toggleMessagePinLocally(msgId, false)
                    }
                }
            }
            type == "screenshot_setting_changed" -> {
                val enabled = event["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                _screenshotNotificationsEnabled.value = enabled
                // Optionally update local DB if we want settings to sync across devices via WS
                viewModelScope.launch {
                    chatRepository.toggleScreenshotNotifications(convId, enabled)
                }
            }
            type == "disappearing_messages_changed" || type == "disappearing_duration_changed" -> {
                val duration = (event["duration"] ?: event["disappearing_messages_duration"] ?: event["disappearing_duration"])
                    ?.jsonPrimitive?.intOrNull ?: 0
                _disappearingDuration.value = duration
                viewModelScope.launch {
                    chatRepository.updateConversationDisappearingDurationLocally(convId, duration)
                }
            }
            type == "screenshot_taken" -> {
                val name = event["sender_name"]?.jsonPrimitive?.contentOrNull ?: "Someone"
                _toastEvent.tryEmit("📸 $name took a screenshot!")
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
            type == "message_edited" -> {
                val editedMsgId = event["message_id"]?.jsonPrimitive?.contentOrNull
                    ?: event["id"]?.jsonPrimitive?.contentOrNull
                val newContent = event["content"]?.jsonPrimitive?.contentOrNull
                if (!editedMsgId.isNullOrBlank() && newContent != null) {
                    viewModelScope.launch {
                        chatRepository.editMessageLocally(editedMsgId, newContent)
                    }
                }
            }
            type == "new_message" || type == "message_deleted" || 
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
                        if (isScreenResumed) {
                            soundManager.playReceivedSound()
                            // If we are active, mark as read immediately
                            if (convId == _conversationId.value) {
                                sendReadReceipt(convId)
                            }
                        }
                    }
                    viewModelScope.launch {
                        chatRepository.insertWebSocketMessage(msg)
                        if (isScreenResumed && convId.isNotEmpty()) {
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
        isScreenResumed = false
        chatRepository.activeConversationId = null
    }
}
