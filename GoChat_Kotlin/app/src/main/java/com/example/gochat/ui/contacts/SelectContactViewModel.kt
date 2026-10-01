package com.example.gochat.ui.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.R
import com.example.gochat.core.contacts.ContactSyncManager
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.SyncedContact
import com.example.gochat.data.model.User
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SelectContactViewModel @Inject constructor(
    application: Application,
    private val syncManager: ContactSyncManager,
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository
) : AndroidViewModel(application) {

    private val _allContacts = MutableStateFlow<List<SyncedContact>>(emptyList())
    val allContacts: StateFlow<List<SyncedContact>> = _allContacts.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    private val _selectedContactIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedContactIds: StateFlow<Set<String>> = _selectedContactIds.asStateFlow()

    private val _hasPermission = MutableStateFlow(syncManager.hasPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    val registeredCount: StateFlow<Int> = _allContacts.map { contacts ->
        contacts.count { it.isRegistered }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val uiItems: StateFlow<List<ContactListItem>> = combine(
        _allContacts,
        _searchQuery,
        _isMultiSelectMode,
        _selectedContactIds
    ) { contacts, query, isMulti, selectedIds ->
        val trimmedQuery = query.trim().lowercase()

        val registered = contacts.filter { it.isRegistered }
        val invite = contacts.filter { !it.isRegistered }

        val items = mutableListOf<ContactListItem>()

        if (trimmedQuery.isBlank()) {
            if (!isMulti) {
                // 1. Top Quick Action Tiles
                items.add(
                    ContactListItem.Action(
                        id = "action_group",
                        title = "New group",
                        iconRes = R.drawable.ic_tab_chats
                    )
                )
                items.add(
                    ContactListItem.Action(
                        id = "action_pin",
                        title = "New contact by PIN",
                        iconRes = R.drawable.ic_message_add
                    )
                )
            }

            // 2. Contacts on GoChat
            if (registered.isNotEmpty()) {
                items.add(ContactListItem.Header("CONTACTS ON GOCHAT", registered.size))
                registered.forEach { 
                    items.add(ContactListItem.Contact(it, isSelected = selectedIds.contains(it.finalUserId))) 
                }
            }

            // 3. Invite to GoChat
            if (invite.isNotEmpty() && !isMulti) {
                items.add(ContactListItem.Header("INVITE TO GOCHAT", invite.size))
                invite.forEach { items.add(ContactListItem.Contact(it)) }
            }
        } else {
            // Search Active
            val filteredReg = registered.filter {
                it.displayName.lowercase().contains(trimmedQuery) ||
                        it.phone.contains(trimmedQuery) ||
                        it.pin.lowercase().contains(trimmedQuery)
            }
            val filteredInvite = invite.filter {
                it.displayName.lowercase().contains(trimmedQuery) ||
                        it.phone.contains(trimmedQuery)
            }

            if (filteredReg.isNotEmpty()) {
                items.add(ContactListItem.Header("CONTACTS ON GOCHAT", filteredReg.size))
                filteredReg.forEach { 
                    items.add(ContactListItem.Contact(it, isSelected = selectedIds.contains(it.finalUserId))) 
                }
            }

            if (filteredInvite.isNotEmpty() && !isMulti) {
                items.add(ContactListItem.Header("INVITE TO GOCHAT", filteredInvite.size))
                filteredInvite.forEach { items.add(ContactListItem.Contact(it)) }
            }
        }

        items
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Load cached contacts immediately
        val cached = syncManager.getCachedContacts()
        if (cached.isNotEmpty()) {
            _allContacts.value = cached
        }
        // Auto sync if permission is granted
        checkPermissionAndSync(force = false)
    }

    fun updatePermissionStatus(granted: Boolean) {
        _hasPermission.value = granted
        if (granted) {
            checkPermissionAndSync(force = true)
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setMultiSelectMode(enabled: Boolean) {
        _isMultiSelectMode.value = enabled
        if (!enabled) {
            _selectedContactIds.value = emptySet()
        }
    }

    fun selectContact(contactId: String) {
        if (contactId.isBlank()) return
        val current = _selectedContactIds.value.toMutableSet()
        current.add(contactId)
        _selectedContactIds.value = current
    }

    fun preselectContact(
        userId: String?,
        name: String?,
        phone: String?,
        avatarUrl: String?
    ) {
        val cleanUserId = userId?.trim().orEmpty()
        val cleanName = name?.trim().orEmpty()
        val cleanPhone = phone?.trim().orEmpty()
        val cleanAvatar = avatarUrl?.trim().orEmpty()

        if (cleanUserId.isBlank() && cleanName.isBlank() && cleanPhone.isBlank()) return

        val currentList = _allContacts.value.toMutableList()
        val existing = currentList.firstOrNull { c ->
            (cleanUserId.isNotBlank() && (c.finalUserId == cleanUserId || c.id == cleanUserId || c.userId == cleanUserId)) ||
            (cleanPhone.isNotBlank() && c.phone == cleanPhone) ||
            (cleanName.isNotBlank() && c.displayName.equals(cleanName, ignoreCase = true))
        }

        val targetId = if (existing != null) {
            existing.finalUserId
        } else {
            val fallbackId = cleanUserId.ifBlank { "user_${System.currentTimeMillis()}" }
            val synthetic = SyncedContact(
                id = fallbackId,
                phonebookName = cleanName.ifBlank { "Contact" },
                phone = cleanPhone,
                isRegistered = true,
                userId = fallbackId,
                name = cleanName.ifBlank { "Contact" },
                avatarUrl = cleanAvatar
            )
            currentList.add(0, synthetic)
            _allContacts.value = currentList
            fallbackId
        }

        val currentSelected = _selectedContactIds.value.toMutableSet()
        currentSelected.add(targetId)
        _selectedContactIds.value = currentSelected
    }

    fun toggleContactSelection(contactId: String) {
        val current = _selectedContactIds.value.toMutableSet()
        if (current.contains(contactId)) {
            current.remove(contactId)
        } else {
            current.add(contactId)
        }
        _selectedContactIds.value = current
    }

    fun getSelectedContacts(): List<SyncedContact> {
        val ids = _selectedContactIds.value
        return _allContacts.value.filter { ids.contains(it.finalUserId) }
    }

    fun checkPermissionAndSync(force: Boolean) {
        val granted = syncManager.hasPermission()
        _hasPermission.value = granted

        if (!granted && _allContacts.value.isEmpty()) {
            _allContacts.value = syncManager.getCachedContacts()
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            val synced = syncManager.scanAndSyncContacts(force = force)
            val selectedIds = _selectedContactIds.value
            val syntheticToPreserve = _allContacts.value.filter { contact ->
                selectedIds.contains(contact.finalUserId) && synced.none { it.finalUserId == contact.finalUserId }
            }
            _allContacts.value = syntheticToPreserve + synced
            _isLoading.value = false
        }
    }

    fun openOrCreateChat(contact: SyncedContact, onComplete: (Conversation?) -> Unit) {
        viewModelScope.launch {
            val contactId = contact.finalUserId
            if (contactId.isBlank()) {
                onComplete(null)
                return@launch
            }

            // 1. Check existing conversations
            val existing: Conversation? = withContext(Dispatchers.IO) {
                chatRepository.getAllConversationsList().firstOrNull { conv: Conversation ->
                    !conv.isGroup && (conv.id == contactId || conv.title.equals(contact.displayName, ignoreCase = true))
                }
            }

            if (existing != null) {
                onComplete(existing)
                return@launch
            }

            // 2. Create new conversation on server
            val result = chatRepository.createConversation(
                name = contact.displayName,
                memberIds = listOf(contactId),
                isGroup = false
            )

            onComplete(result.getOrNull())
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
            // 1. Check in synced contacts first
            val local = _allContacts.value.firstOrNull { it.pin.equals(cleanPin, ignoreCase = true) }
            if (local != null) {
                onResult(
                    User(
                        id = local.finalUserId,
                        displayName = local.displayName,
                        avatarUrl = local.avatarUrl,
                        pin = local.pin
                    )
                )
                return@launch
            }
            // 2. Query backend
            val result = authRepository.lookupUserByPin(cleanPin)
            onResult(result.getOrNull())
        }
    }

    /**
     * Open or create direct chat with a pre-resolved User object.
     */
    fun openOrCreateChatWithUser(user: User, onComplete: (Conversation?) -> Unit) {
        viewModelScope.launch {
            val userId = user.id
            if (userId.isBlank()) {
                onComplete(null)
                return@launch
            }
            val existing = withContext(Dispatchers.IO) {
                chatRepository.getAllConversationsList().firstOrNull { conv ->
                    !conv.isGroup && (conv.memberIds.contains(userId) || (user.pin.isNotBlank() && conv.partnerPin?.equals(user.pin, ignoreCase = true) == true))
                }
            }
            if (existing != null) {
                onComplete(existing)
                return@launch
            }
            val result = chatRepository.createConversation(
                name = user.displayName.ifBlank { "User ${user.pin}" },
                memberIds = listOf(userId),
                isGroup = false
            )
            onComplete(result.getOrNull())
        }
    }
}
