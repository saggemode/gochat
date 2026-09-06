package com.example.gochat.ui.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.R
import com.example.gochat.core.contacts.ContactSyncManager
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.SyncedContact
import com.example.gochat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelectContactViewModel(application: Application) : AndroidViewModel(application) {

    private val syncManager = ContactSyncManager.getInstance(application)
    private val chatRepository = ChatRepository(application)

    private val _allContacts = MutableStateFlow<List<SyncedContact>>(emptyList())
    val allContacts: StateFlow<List<SyncedContact>> = _allContacts.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _hasPermission = MutableStateFlow(syncManager.hasPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    val registeredCount: StateFlow<Int> = _allContacts.map { contacts ->
        contacts.count { it.isRegistered }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val uiItems: StateFlow<List<ContactListItem>> = combine(
        _allContacts,
        _searchQuery
    ) { contacts, query ->
        val trimmedQuery = query.trim().lowercase()

        val registered = contacts.filter { it.isRegistered }
        val invite = contacts.filter { !it.isRegistered }

        val items = mutableListOf<ContactListItem>()

        if (trimmedQuery.isBlank()) {
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

            // 2. Contacts on GoChat
            if (registered.isNotEmpty()) {
                items.add(ContactListItem.Header("CONTACTS ON GOCHAT", registered.size))
                registered.forEach { items.add(ContactListItem.Contact(it)) }
            }

            // 3. Invite to GoChat
            if (invite.isNotEmpty()) {
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
                filteredReg.forEach { items.add(ContactListItem.Contact(it)) }
            }

            if (filteredInvite.isNotEmpty()) {
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
            _allContacts.value = synced
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
}
