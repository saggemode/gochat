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
import com.example.gochat.data.repository.GroupRepository
import com.example.gochat.core.sound.ChatSoundManager
import com.example.gochat.core.currency.CurrencyManager
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
    private val groupRepository: GroupRepository,
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

    private val _commandSuggestions = MutableStateFlow<List<BotCommand>>(emptyList())
    val commandSuggestions: StateFlow<List<BotCommand>> = _commandSuggestions.asStateFlow()

    private val _isGroup = MutableStateFlow(false)
    val isGroup: StateFlow<Boolean> = _isGroup.asStateFlow()

    private val _botConfig = MutableStateFlow<BotConfig?>(null)
    val botConfig: StateFlow<BotConfig?> = _botConfig.asStateFlow()

    private val _isGroupAdmin = MutableStateFlow(false)
    val isGroupAdmin: StateFlow<Boolean> = _isGroupAdmin.asStateFlow()

    private val _disappearingDuration = MutableStateFlow(0) // Seconds, 0 means off
    val disappearingDuration: StateFlow<Int> = _disappearingDuration.asStateFlow()

    private val _screenshotNotificationsEnabled = MutableStateFlow(false)
    val screenshotNotificationsEnabled: StateFlow<Boolean> = _screenshotNotificationsEnabled.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

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

    fun initConversation(convId: String, isGroupChat: Boolean = false) {
        _conversationId.value = convId
        chatRepository.activeConversationId = convId
        isScreenResumed = true
        _isGroup.value = isGroupChat
        _isGroupAdmin.value = false
        _botConfig.value = null
        _commandSuggestions.value = emptyList()
        _mentionSuggestions.value = emptyList()

        viewModelScope.launch {
            val conv = chatRepository.getConversationById(convId)
            val currentUserId = tokenManager.userId ?: ""
            val isGroup = isGroupChat || (conv?.isGroup == true)
            _isGroup.value = isGroup

            if (conv != null) {
                _screenshotNotificationsEnabled.value = conv.screenshotNotificationsEnabled
                _isMuted.value = conv.isMuted
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
                    val resolvedIsGroup = isGroup || remoteConv.isGroup
                    _isGroup.value = resolvedIsGroup
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
            
            if (isGroup) {
                // Populate members for mentions
                loadMembersFromLocal(convId)
                // Mock Bot Config loading only for group chats
                loadBotConfig(convId)
            } else {
                _isGroupAdmin.value = false
                _botConfig.value = null
                _commandSuggestions.value = emptyList()
                loadDirectChatMembers(convId, conv)
            }
        }
    }

    private fun loadBotConfig(convId: String) {
        if (!_isGroup.value) {
            _botConfig.value = null
            return
        }
        // In a real app, this would fetch from a database or API
        _botConfig.value = BotConfig(
            botId = "bot_123",
            groupId = convId,
            permissions = listOf(BotPermission.ANTI_SPAM, BotPermission.WELCOME_MEMBERS),
            rules = "No spamming allowed!"
        )
    }

    private suspend fun loadDirectChatMembers(convId: String, conv: Conversation?) {
        val myId = tokenManager.userId.orEmpty()
        val c = conv ?: chatRepository.getConversationById(convId)
        val recentMessages = messages.value
        val senderMap = recentMessages.associate { it.senderId to it.senderName }

        val fallbackUsers = (c?.memberIds.orEmpty()).map { id ->
            val senderName = senderMap[id]
            val name = if (!senderName.isNullOrBlank()) senderName else if (id == myId) "You" else "User_${id.takeLast(4)}"
            User(id = id, displayName = name)
        }.toMutableList()

        if (fallbackUsers.isEmpty()) {
            recentMessages.map {
                val name = it.senderName.ifBlank { "User_${it.senderId.takeLast(4)}" }
                User(id = it.senderId, displayName = name)
            }.distinctBy { it.id }.forEach { fallbackUsers.add(it) }
        }

        if (fallbackUsers.isNotEmpty()) {
            _members.value = fallbackUsers
        }
    }

    private suspend fun loadMembersFromLocal(convId: String) {
        val myId = tokenManager.userId.orEmpty()
        // Try to load members from backend (includes role info)
        val membersResult = groupRepository.getGroupMembers(convId)
        val fetchedList = membersResult.getOrNull()
        if (membersResult.isSuccess && !fetchedList.isNullOrEmpty()) {
            // Check if current user is admin or owner
            val myMember = fetchedList.firstOrNull { it.id == myId }
            val role = myMember?.role ?: "member"
            val isAdmin = role.equals("owner", ignoreCase = true) || role.equals("admin", ignoreCase = true)
            _isGroupAdmin.value = isAdmin

            val visibleList = if (isAdmin) fetchedList else fetchedList.filter { !it.isBot }
            _members.value = visibleList.map {
                val name = if (it.displayName.isNotBlank()) it.displayName else "User_${it.id.takeLast(4)}"
                User(id = it.id, displayName = name, avatarUrl = it.avatarUrl)
            }
        } else {
            _isGroupAdmin.value = false
            // Fallback: extract from conversation memberIds and recent messages
            val conv = chatRepository.getConversationById(convId)
            val recentMessages = messages.value
            val senderMap = recentMessages.associate { it.senderId to it.senderName }

            val fallbackUsers = (conv?.memberIds.orEmpty()).map { id ->
                val senderName = senderMap[id]
                val name = if (!senderName.isNullOrBlank()) senderName else if (id == myId) "You" else "User_${id.takeLast(4)}"
                User(id = id, displayName = name)
            }.toMutableList()

            if (fallbackUsers.isEmpty()) {
                recentMessages.map {
                    val name = it.senderName.ifBlank { "User_${it.senderId.takeLast(4)}" }
                    User(id = it.senderId, displayName = name)
                }.distinctBy { it.id }.forEach { fallbackUsers.add(it) }
            }

            if (fallbackUsers.isNotEmpty()) {
                _members.value = fallbackUsers
            }
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

        // Check if message is a bot moderation slash command (e.g. /ban @john spam)
        val isSlashCommand = _isGroup.value && _isGroupAdmin.value && trimmed.startsWith("/") && BotCommand.ALL_COMMANDS.any {
            trimmed.substringBefore(" ").equals(it.command, ignoreCase = true)
        }
        if (isSlashCommand) {
            handleModerationSlashCommand(trimmed, convId)
        }

        viewModelScope.launch {
            if (!isSlashCommand) {
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
            }
            clearReply()

            // Send live typing stop
            sendTypingEvent(false)
        }
    }

    private fun handleModerationSlashCommand(rawCommand: String, convId: String) {
        // Only admins in a group can execute moderation commands
        if (!_isGroup.value || !_isGroupAdmin.value) return
        val parts = rawCommand.split("\\s+".toRegex())
        val command = parts.getOrNull(0)?.lowercase() ?: return
        val targetArg = parts.getOrNull(1).orEmpty()

        // Robust member resolution: matches @mention with or without spaces, or member ID
        val targetUser = _members.value.find { member ->
            val cleanName = member.displayName.trim()
            cleanName.isNotBlank() && (
                rawCommand.contains("@$cleanName", ignoreCase = true) ||
                rawCommand.contains("@${cleanName.replace(" ", "_")}", ignoreCase = true) ||
                member.id == targetArg.removePrefix("@") ||
                member.displayName.equals(targetArg.removePrefix("@"), ignoreCase = true)
            )
        }
        val targetDisplayName = targetUser?.displayName ?: targetArg.removePrefix("@").ifBlank { "User" }

        // Extract reason or duration (anything typed after the @mention)
        val afterMention = if (targetUser != null && rawCommand.contains("@${targetUser.displayName}", ignoreCase = true)) {
            val idx = rawCommand.indexOf("@${targetUser.displayName}", ignoreCase = true)
            rawCommand.substring(idx + targetUser.displayName.length + 1).trim()
        } else if (parts.size > 2) {
            parts.subList(2, parts.size).joinToString(" ").trim()
        } else {
            ""
        }
        val reason = afterMention

        when (command) {
            "/help" -> {
                val helpMsg = "🤖 *GoChat Moderation Bot Commands:*\n" +
                        "• /ban @user [reason] - Permanently ban member (e.g. /ban @john spam)\n" +
                        "• /kick @user - Remove member from group (e.g. /kick @john)\n" +
                        "• /mute @user [duration] - Mute member (e.g. /mute @john 10m)\n" +
                        "• /unmute @user - Restore member chat privileges (e.g. /unmute @john)\n" +
                        "• /warn @user [reason] - Issue formal strike (e.g. /warn @john advertising)\n" +
                        "• /unban @user - Lift ban on member (e.g. /unban @john)\n" +
                        "• /slowmode [seconds] - Cooldown delay (e.g. /slowmode 30s)\n" +
                        "• /rules - View group rules and guidelines"
                viewModelScope.launch {
                    chatRepository.sendMessage(
                        conversationId = convId,
                        content = helpMsg,
                        type = 12 // MessageType.SYSTEM
                    )
                }
            }
            "/rules" -> {
                val rulesContent = _botConfig.value?.rules?.ifBlank { null }
                    ?: "1. Be respectful to all members\n2. No spam or unauthorized promotional links\n3. Adhere to group admin guidelines"
                viewModelScope.launch {
                    chatRepository.sendMessage(
                        conversationId = convId,
                        content = "📜 *Group Moderation Rules:*\n$rulesContent",
                        type = 12 // MessageType.SYSTEM
                    )
                }
            }
            "/ban", "/kick" -> {
                viewModelScope.launch {
                    if (targetUser != null) {
                        groupRepository.removeMember(convId, targetUser.id)
                        val action = if (command == "/ban") "banned from the group" else "removed from the group"
                        val reasonText = if (reason.isNotBlank()) " for: $reason" else ""
                        chatRepository.sendMessage(
                            conversationId = convId,
                            content = "🚨 @${targetUser.displayName} was $action by Admin$reasonText",
                            type = 12, // MessageType.SYSTEM
                            mentionedUserIds = listOf(targetUser.id)
                        )
                    } else {
                        chatRepository.sendMessage(
                            conversationId = convId,
                            content = "⚠️ User '$targetArg' not found in group members. Format: $command @username [reason]",
                            type = 12 // MessageType.SYSTEM
                        )
                    }
                }
            }
            "/warn" -> {
                val warningReason = if (reason.isNotBlank()) reason else "Violation of group rules"
                viewModelScope.launch {
                    val mentions = targetUser?.let { listOf(it.id) } ?: emptyList()
                    chatRepository.sendMessage(
                        conversationId = convId,
                        content = "⚠️ *Moderation Strike:* @$targetDisplayName has been warned for: $warningReason",
                        type = 12, // MessageType.SYSTEM
                        mentionedUserIds = mentions
                    )
                }
            }
            "/mute" -> {
                val duration = if (reason.isNotBlank()) reason else "1 hour"
                viewModelScope.launch {
                    val mentions = targetUser?.let { listOf(it.id) } ?: emptyList()
                    chatRepository.sendMessage(
                        conversationId = convId,
                        content = "🔇 *Member Muted:* @$targetDisplayName has been muted for $duration by Admin",
                        type = 12, // MessageType.SYSTEM
                        mentionedUserIds = mentions
                    )
                }
            }
            "/unmute" -> {
                viewModelScope.launch {
                    val mentions = targetUser?.let { listOf(it.id) } ?: emptyList()
                    chatRepository.sendMessage(
                        conversationId = convId,
                        content = "🔊 *Member Unmuted:* @$targetDisplayName is now unmuted",
                        type = 12, // MessageType.SYSTEM
                        mentionedUserIds = mentions
                    )
                }
            }
            "/unban" -> {
                viewModelScope.launch {
                    chatRepository.sendMessage(
                        conversationId = convId,
                        content = "✅ Ban lifted for @$targetDisplayName. They may now rejoin.",
                        type = 12 // MessageType.SYSTEM
                    )
                }
            }
            "/slowmode" -> {
                val duration = targetArg.ifBlank { reason }.ifBlank { "30s" }
                val content = if (duration == "0" || duration.equals("off", ignoreCase = true)) {
                    "⚡ *Slowmode Disabled:* Members can send messages without delay."
                } else {
                    "⏳ *Slowmode Enabled:* $duration cooldown between messages."
                }
                viewModelScope.launch {
                    chatRepository.sendMessage(
                        conversationId = convId,
                        content = content,
                        type = 12 // MessageType.SYSTEM
                    )
                }
            }
            else -> {
                // Unknown command, send as standard text message
                viewModelScope.launch {
                    chatRepository.sendMessage(
                        conversationId = convId,
                        content = rawCommand,
                        type = 0 // MessageType.TEXT
                    )
                }
            }
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
        val convId = _conversationId.value
        viewModelScope.launch {
            chatRepository.deleteMessageForEveryone(messageId, convId)
        }
    }

    fun deleteMessageForMe(messageId: String) {
        val convId = _conversationId.value
        viewModelScope.launch {
            chatRepository.deleteMessageForMe(messageId, convId)
        }
    }

    fun deleteMessagesForMe(messageIds: List<String>) {
        val convId = _conversationId.value
        viewModelScope.launch {
            chatRepository.deleteMessagesForMe(messageIds, convId)
        }
    }

    fun deleteMessagesForEveryone(messageIds: List<String>) {
        val convId = _conversationId.value
        viewModelScope.launch {
            chatRepository.deleteMessagesForEveryone(messageIds, convId)
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
                put("currency", CurrencyManager.getUserCurrency())
                put("currency_symbol", CurrencyManager.getUserCurrencySymbol())
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
                put("currency", CurrencyManager.getUserCurrency())
                put("currency_symbol", CurrencyManager.getUserCurrencySymbol())
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

    fun sendCustomOfferMessage(
        itemName: String,
        originalPrice: Double,
        offeredPrice: Double,
        note: String,
        targetConvId: String? = null
    ) {
        val convId = targetConvId ?: _conversationId.value
        if (convId.isEmpty()) return

        val offerId = "offer_${System.currentTimeMillis()}"
        val offerJson = buildJsonObject {
            put("type", "custom_offer")
            put("custom_offer", buildJsonObject {
                put("id", offerId)
                put("item_name", itemName)
                put("original_price", originalPrice)
                put("offered_price", offeredPrice)
                put("currency", CurrencyManager.getUserCurrency())
                put("currency_symbol", CurrencyManager.getUserCurrencySymbol())
                put("note", note)
                put("status", "pending")
            })
        }.toString()

        viewModelScope.launch {
            chatRepository.sendMessage(
                conversationId = convId,
                content = offerJson,
                type = 10
            )
        }
    }

    fun updateCustomOfferStatus(
        messageId: String,
        currentContent: String,
        newStatus: String
    ) {
        viewModelScope.launch {
            try {
                val rootObj = Json.decodeFromString<JsonObject>(currentContent)
                val offerObj = rootObj["custom_offer"]?.jsonObject ?: rootObj

                val updatedOfferObj = buildJsonObject {
                    offerObj.forEach { (key, value) ->
                        if (key == "status") {
                            put("status", newStatus)
                        } else {
                            put(key, value)
                        }
                    }
                    if (!offerObj.containsKey("status")) {
                        put("status", newStatus)
                    }
                }

                val newContent = buildJsonObject {
                    put("type", "custom_offer")
                    put("custom_offer", updatedOfferObj)
                }.toString()

                chatRepository.editMessage(messageId, newContent)
            } catch (e: Exception) {
                Log.e("ChatRoomVM", "Failed to update custom offer status: ${e.message}")
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
                put("currency", CurrencyManager.getUserCurrency())
                put("currency_symbol", CurrencyManager.getUserCurrencySymbol())
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

        if (beforeCursor.startsWith("/")) {
            // Only group admins can use slash commands in groups
            if (!_isGroup.value || !_isGroupAdmin.value) {
                _commandSuggestions.value = emptyList()
                _mentionSuggestions.value = emptyList()
                return
            }
            // Slash command mode
            val query = beforeCursor.substring(1).lowercase().trim()
            if (lastAt != -1 && (lastAt == 0 || beforeCursor[lastAt - 1] == ' ')) {
                // User is picking a target mention after a slash command like /ban @
                val mentionQuery = beforeCursor.substring(lastAt + 1).lowercase()
                val allMembers = _members.value.map {
                    GroupMember(id = it.id, displayName = it.displayName, avatarUrl = it.avatarUrl)
                }
                _mentionSuggestions.value = if (mentionQuery.isEmpty()) {
                    // Show ALL members when admin just typed '@' with no filter
                    allMembers
                } else {
                    allMembers.filter { it.displayName.lowercase().contains(mentionQuery) }
                }
                _commandSuggestions.value = emptyList()
            } else {
                _commandSuggestions.value = BotCommand.ALL_COMMANDS.filter {
                    it.command.removePrefix("/").lowercase().contains(query) ||
                            it.syntax.lowercase().contains(query)
                }
                _mentionSuggestions.value = emptyList()
            }
        } else if (lastAt != -1 && (lastAt == 0 || beforeCursor[lastAt - 1] == ' ')) {
            val query = beforeCursor.substring(lastAt + 1).lowercase()
            val allMembers = _members.value.map {
                GroupMember(id = it.id, displayName = it.displayName, avatarUrl = it.avatarUrl)
            }
            _mentionSuggestions.value = if (query.isEmpty()) {
                allMembers
            } else {
                allMembers.filter { it.displayName.lowercase().contains(query) }
            }
            _commandSuggestions.value = emptyList()
        } else {
            _mentionSuggestions.value = emptyList()
            _commandSuggestions.value = emptyList()
        }
    }

    fun setMembers(membersList: List<User>) {
        _members.value = membersList
    }

    fun setGroupAdmin(isAdmin: Boolean) {
        _isGroupAdmin.value = isAdmin
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

    private var lastScreenshotTimestamp: Long = 0L

    fun toggleScreenshotNotifications(enabled: Boolean) {
        val convId = _conversationId.value
        if (convId.isEmpty()) return

        viewModelScope.launch {
            val result = chatRepository.toggleScreenshotNotifications(convId, enabled)
            if (result.isSuccess) {
                _screenshotNotificationsEnabled.value = enabled

                val myName = tokenManager.userDisplayName?.ifBlank { null } ?: "Someone"
                val noticeText = if (enabled) {
                    "📸 $myName enabled screenshot alerts. Both participants will be notified if a screenshot is taken."
                } else {
                    "📸 $myName disabled screenshot alerts."
                }

                // Send system notification message in chat
                chatRepository.sendMessage(
                    conversationId = convId,
                    content = noticeText,
                    type = 12 // MessageType.SYSTEM
                )

                // Notify partner about setting change via WebSocket
                val payload = buildJsonObject {
                    put("type", "screenshot_setting_changed")
                    put("conversation_id", convId)
                    put("enabled", enabled)
                    put("sender_name", myName)
                    if (!currentPartnerId.isNullOrEmpty()) {
                        put("recipient_id", currentPartnerId)
                    }
                }
                webSocket.send(payload)
            }
        }
    }

    fun sendScreenshotNotification() {
        val convId = _conversationId.value
        if (convId.isEmpty() || !_screenshotNotificationsEnabled.value) return

        // Debounce: prevent multiple triggers within 2 seconds
        val now = System.currentTimeMillis()
        if (now - lastScreenshotTimestamp < 2000L) return
        lastScreenshotTimestamp = now

        viewModelScope.launch {
            val myName = tokenManager.userDisplayName?.ifBlank { null } ?: "Someone"
            // Sending as a system message so it persists in history and displays prominently
            chatRepository.sendMessage(
                conversationId = convId,
                content = "📸 $myName took a screenshot",
                type = 12 // MessageType.SYSTEM
            )

            val payload = buildJsonObject {
                put("type", "screenshot_taken")
                put("conversation_id", convId)
                put("sender_name", myName)
                if (!currentPartnerId.isNullOrEmpty()) {
                    put("recipient_id", currentPartnerId)
                }
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
                val eventConvId = (event["conversation_id"] ?: event["conversationId"])?.jsonPrimitive?.contentOrNull.orEmpty()
                if (eventConvId.isEmpty() || eventConvId == convId) {
                    val enabled = event["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                    _screenshotNotificationsEnabled.value = enabled
                    viewModelScope.launch {
                        chatRepository.toggleScreenshotNotifications(convId, enabled)
                    }
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
                val eventConvId = (event["conversation_id"] ?: event["conversationId"])?.jsonPrimitive?.contentOrNull.orEmpty()
                if (eventConvId.isEmpty() || eventConvId == convId) {
                    val name = event["sender_name"]?.jsonPrimitive?.contentOrNull ?: "Someone"
                    _toastEvent.tryEmit("📸 $name took a screenshot!")
                }
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
            type == "group_updated" -> {
                val updatedConvId = (event["conversation_id"] ?: event["conv_id"] ?: event["id"])?.jsonPrimitive?.contentOrNull.orEmpty()
                if (updatedConvId.isNotEmpty() && updatedConvId == _conversationId.value) {
                    viewModelScope.launch {
                        chatRepository.fetchConversation(updatedConvId)
                    }
                }
            }
            type == "message_deleted" || type == "event_message_deleted" -> {
                val msgId = (event["msg_id"] ?: event["message_id"] ?: event["id"]
                    ?: if (rawMsg is JsonObject) rawMsg["id"] ?: rawMsg["message_id"] ?: rawMsg["msg_id"] else null)
                    ?.jsonPrimitive?.contentOrNull.orEmpty()
                val targetConvId = _conversationId.value
                if (msgId.isNotEmpty()) {
                    viewModelScope.launch {
                        chatRepository.deleteMessageLocally(msgId, targetConvId)
                    }
                }
            }
            type == "new_message" || 
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
        if (!_isGroup.value) return
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

    fun setConversationMuted(muted: Boolean) {
        val convId = _conversationId.value
        if (convId.isEmpty()) return
        viewModelScope.launch {
            val result = chatRepository.setConversationMuted(convId, muted)
            if (result.isSuccess) {
                _isMuted.value = muted
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        isScreenResumed = false
        chatRepository.activeConversationId = null
    }
}
