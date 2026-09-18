package com.example.gochat.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.ConversationType
import com.example.gochat.data.model.InvitationStatus
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.User
import com.example.gochat.data.model.UserStories
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.data.repository.StoryRepository
import com.example.gochat.data.websocket.GoChatWebSocket
import com.example.gochat.core.network.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

enum class ConnectionState {
    CONNECTED,
    CONNECTING,
    WAITING_FOR_NETWORK
}

@HiltViewModel
class ChatListViewModel @Inject constructor(
    application: Application,
    private val chatRepository: ChatRepository,
    private val storyRepository: StoryRepository,
    private val marketplaceRepository: MarketplaceRepository,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val webSocket: GoChatWebSocket,
    private val networkMonitor: NetworkMonitor
) : AndroidViewModel(application) {

    val selectedFilter = MutableStateFlow("All")
    val searchQuery = MutableStateFlow("")
    val isRefreshing = MutableStateFlow(false)
    val currentUserId: String get() = tokenManager.userId ?: ""

    private val _stories = MutableStateFlow<List<UserStories>>(emptyList())
    val stories: StateFlow<List<UserStories>> = _stories.asStateFlow()

    private val _newConversationEvent = MutableSharedFlow<Conversation>(extraBufferCapacity = 1)
    val newConversationEvent: SharedFlow<Conversation> = _newConversationEvent.asSharedFlow()

    // Paged Conversations Flow
    val pagedConversations: Flow<PagingData<Conversation>> = chatRepository.getConversationsPaged()
        .cachedIn(viewModelScope)
        .combine(combine(selectedFilter, searchQuery) { f, q -> f to q }) { pagingData, (filter, query) ->
            pagingData.filter { conv ->
                val matchesFilter = when (filter) {
                    "Unread" -> conv.unreadCount > 0
                    "Groups" -> conv.type == ConversationType.GROUP
                    "Channels" -> conv.type == ConversationType.CHANNEL
                    else -> true
                }
                val matchesQuery = if (query.isBlank()) true else {
                    conv.title.contains(query, ignoreCase = true) || 
                    conv.lastMessageText?.contains(query, ignoreCase = true) == true
                }
                matchesFilter && matchesQuery
            }
        }

    // Room DB Flow of all conversations (keep for counts)
    val allConversations: StateFlow<List<Conversation>> = chatRepository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered list based on selectedFilter and searchQuery
    val filteredConversations: StateFlow<List<Conversation>> = combine(
        allConversations,
        selectedFilter,
        searchQuery
    ) { list, filter, query ->
        var result = list

        // Filter by category
        result = when (filter) {
            "Unread" -> result.filter { it.unreadCount > 0 }
            "Groups" -> result.filter { it.type == ConversationType.GROUP }
            "Channels" -> result.filter { it.type == ConversationType.CHANNEL }
            else -> result
        }

        // Filter by search query
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            result = result.filter { conv ->
                conv.title.lowercase().contains(q) ||
                (conv.lastMessageText?.lowercase()?.contains(q) == true)
            }
        }

        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val globalMessageSearchResults: StateFlow<List<Message>> = searchQuery
        .debounce(300)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else flow {
                emit(chatRepository.searchAllMessages(q.trim()))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Count of pending incoming contact invitations
    val pendingInvitationsCount: StateFlow<Int> = allConversations.map { list ->
        list.count { it.invitationStatus == InvitationStatus.PENDING_INCOMING }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Total unread messages count for badges
    val totalUnreadCount: StateFlow<Int> = allConversations.map { list ->
        list.sumOf { it.unreadCount }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // WhatsApp-style Connection State: WAITING_FOR_NETWORK, CONNECTING, or CONNECTED
    val connectionState: StateFlow<ConnectionState> = combine(
        networkMonitor.isOnline,
        webSocket.isConnected
    ) { isOnline, isConnected ->
        when {
            !isOnline -> ConnectionState.WAITING_FOR_NETWORK
            !isConnected -> ConnectionState.CONNECTING
            else -> ConnectionState.CONNECTED
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        if (!networkMonitor.isCurrentlyOnline()) ConnectionState.WAITING_FOR_NETWORK else ConnectionState.CONNECTING
    )

    init {
        // Dynamic token provider: WebSocket reconnects will automatically query a fresh/renewed JWT
        webSocket.tokenProvider = {
            authRepository.ensureValidToken()
        }

        connectWebSocket()
        refreshData()

        // Auto-reconnect & Auto-sync when internet connectivity is restored
        viewModelScope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                if (isOnline) {
                    connectWebSocket(force = true)
                    refreshData()
                }
            }
        }

        viewModelScope.launch {
            webSocket.events.collect { event ->
                val type = event["type"]?.jsonPrimitive?.contentOrNull
                if (type == "new_product") {
                    marketplaceRepository.handleIncomingWebSocketEvent(event)
                } else {
                    val msg = chatRepository.handleIncomingWebSocketEvent(event)
                    if (msg != null) {
                        val exists = allConversations.value.any { it.id == msg.conversationId }
                        if (!exists) {
                            chatRepository.refreshConversations()
                        }
                    }
                }
            }
        }
    }

    fun connectWebSocket(force: Boolean = false) {
        viewModelScope.launch {
            val token = authRepository.ensureValidToken()
            if (!token.isNullOrBlank()) {
                webSocket.connect(token, force = force)
            } else {
                android.util.Log.w("ChatListViewModel", "No valid token available to connect WebSocket")
            }
        }
    }

    fun setFilter(filter: String) {
        selectedFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun markAsRead(conversationId: String) {
        viewModelScope.launch {
            chatRepository.markConversationAsRead(conversationId)
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            isRefreshing.value = true
            try {
                authRepository.ensureValidToken()
                chatRepository.refreshConversations()
                val storyResult = storyRepository.getStories()
                if (storyResult.isSuccess) {
                    _stories.value = storyResult.getOrDefault(emptyList())
                }
                // Eagerly refresh marketplace cache on app start / refresh data as well
                marketplaceRepository.refreshProducts()
            } catch (_: Exception) {
            } finally {
                isRefreshing.value = false
            }
        }
    }

    /**
     * Start chat via GoChat unique PIN.
     * Looks up user by PIN and creates a direct conversation.
     */
    fun startChatByPin(pin: String, onResult: (Result<Conversation>) -> Unit) {
        viewModelScope.launch {
            val userResult = authRepository.lookupUserByPin(pin)
            if (userResult.isFailure) {
                onResult(Result.failure(Exception("No user found with PIN: $pin")))
                return@launch
            }

            val targetUser = userResult.getOrNull()
            if (targetUser == null) {
                onResult(Result.failure(Exception("User not found")))
                return@launch
            }

            // Check if conversation already exists in local DB
            val existing = allConversations.value.firstOrNull { conv ->
                conv.memberIds.contains(targetUser.id) ||
                conv.partnerPin.equals(pin, ignoreCase = true)
            }

            if (existing != null) {
                _newConversationEvent.tryEmit(existing)
                onResult(Result.success(existing))
                return@launch
            }

            // Otherwise create new conversation via repository
            val createResult = chatRepository.createConversation(
                name = targetUser.displayName.ifBlank { "User ${targetUser.pin}" },
                memberIds = listOf(targetUser.id),
                isGroup = false
            )

            createResult.onSuccess { newConv ->
                _newConversationEvent.tryEmit(newConv)
            }
            onResult(createResult)
        }
    }

    /**
     * Fast real-time PIN lookup to preview user as the PIN is being typed.
     */
    fun previewUserByPin(pin: String, onResult: (User?) -> Unit): kotlinx.coroutines.Job {
        return viewModelScope.launch {
            val cleanPin = pin.trim().uppercase()
            if (cleanPin.length < 4) {
                onResult(null)
                return@launch
            }
            // 1. Check local conversations first for instant match
            val existing = allConversations.value.firstOrNull { conv ->
                conv.partnerPin?.equals(cleanPin, ignoreCase = true) == true
            }
            if (existing != null) {
                onResult(
                    User(
                        id = existing.id,
                        displayName = existing.title,
                        avatarUrl = existing.avatarUrl,
                        pin = cleanPin
                    )
                )
                return@launch
            }

            // 2. Fetch from backend
            val result = authRepository.lookupUserByPin(cleanPin)
            onResult(result.getOrNull())
        }
    }

    /**
     * Start chat directly with a pre-resolved User object (from PIN preview).
     */
    fun startChatWithUser(targetUser: User, onResult: (Result<Conversation>) -> Unit) {
        viewModelScope.launch {
            val existing = allConversations.value.firstOrNull { conv ->
                conv.memberIds.contains(targetUser.id) ||
                (targetUser.pin.isNotBlank() && conv.partnerPin.equals(targetUser.pin, ignoreCase = true))
            }

            if (existing != null) {
                _newConversationEvent.tryEmit(existing)
                onResult(Result.success(existing))
                return@launch
            }

            val createResult = chatRepository.createConversation(
                name = targetUser.displayName.ifBlank { "User ${targetUser.pin}" },
                memberIds = listOf(targetUser.id),
                isGroup = false
            )

            createResult.onSuccess { newConv ->
                _newConversationEvent.tryEmit(newConv)
            }
            onResult(createResult)
        }
    }
}
