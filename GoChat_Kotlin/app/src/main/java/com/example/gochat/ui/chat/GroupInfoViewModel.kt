package com.example.gochat.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.GroupMember
import com.example.gochat.data.model.GroupMetadata
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.data.repository.GroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupInfoViewModel @Inject constructor(
    application: Application,
    private val groupRepo: GroupRepository,
    private val authRepo: AuthRepository,
    private val chatRepository: ChatRepository,
    private val tokenManager: TokenManager
) : AndroidViewModel(application) {

    private val _metadata = MutableStateFlow<GroupMetadata?>(null)
    val metadata: StateFlow<GroupMetadata?> = _metadata.asStateFlow()

    private val _members = MutableStateFlow<List<GroupMember>>(emptyList())
    val members: StateFlow<List<GroupMember>> = _members.asStateFlow()

    private val _currentUserRole = MutableStateFlow("member")
    val currentUserRole: StateFlow<String> = _currentUserRole.asStateFlow()

    private val _isAdminOrOwner = MutableStateFlow(false)
    val isAdminOrOwner: StateFlow<Boolean> = _isAdminOrOwner.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val currentUserId: String
        get() = tokenManager.userId.orEmpty()

    fun loadGroupData(convId: String, memberIds: List<String> = emptyList()) {
        viewModelScope.launch {
            _isLoading.value = true

            val myId = currentUserId

            // 1. Immediately load from local database for instant offline display
            val localConv = chatRepository.getConversationById(convId)
            val combinedIds = (localConv?.memberIds ?: emptyList()).ifEmpty { memberIds }

            if (combinedIds.isNotEmpty() && _members.value.isEmpty()) {
                val initialList = combinedIds.map { id ->
                    val isMe = id == myId
                    GroupMember(
                        id = id,
                        displayName = if (isMe) "You" else "User ${id.takeLast(4)}",
                        role = "member"
                    )
                }.sortedBy { if (it.id == myId) 0 else 1 }
                _members.value = initialList
            }

            // 2. Load Metadata
            groupRepo.getGroupMetadata(convId).onSuccess {
                _metadata.value = it
            }

            // 3. Load Member Details from backend
            val membersResult = groupRepo.getGroupMembers(convId)
            val fetchedList: List<GroupMember>? = membersResult.getOrNull()
            if (membersResult.isSuccess && !fetchedList.isNullOrEmpty()) {
                val fetchedIds = fetchedList.map { it.id }.toSet()
                val missingLocalMembers = combinedIds.filterNot { fetchedIds.contains(it) }.map { id ->
                    GroupMember(
                        id = id,
                        displayName = if (id == myId) "You" else "User ${id.takeLast(4)}",
                        role = "member"
                    )
                }
                val allMembers = (fetchedList + missingLocalMembers).map { m ->
                    if (m.displayName.isBlank()) {
                        m.copy(displayName = if (m.id == myId) "You" else "User ${m.id.takeLast(4)}")
                    } else m
                }.sortedWith(
                    compareBy<GroupMember> { member ->
                        when {
                            member.id == myId -> 0
                            member.role.equals("owner", ignoreCase = true) -> 1
                            member.role.equals("admin", ignoreCase = true) -> 2
                            else -> 3
                        }
                    }.thenBy { it.displayName.lowercase() }
                )
                _members.value = allMembers

                val myMember = allMembers.firstOrNull { m -> m.id == myId }
                val role = myMember?.role ?: "member"
                _currentUserRole.value = role
                _isAdminOrOwner.value = (role.equals("owner", ignoreCase = true) || role.equals("admin", ignoreCase = true))
            } else if (_members.value.isEmpty() && combinedIds.isNotEmpty()) {
                // Fallback to placeholder if backend listing is empty/failed
                val fallbackList = combinedIds.map { id ->
                    val isMe = id == myId
                    GroupMember(
                        id = id,
                        displayName = if (isMe) "You" else "User ${id.takeLast(4)}",
                        role = "member"
                    )
                }.sortedBy { if (it.id == myId) 0 else 1 }
                _members.value = fallbackList
            }

            _isLoading.value = false
        }
    }

    fun updateGroupDetails(
        convId: String,
        name: String? = null,
        description: String? = null,
        avatarUrl: String? = null,
        onResult: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            groupRepo.updateGroupMetadata(
                convId = convId,
                name = name,
                description = description,
                avatarUrl = avatarUrl
            ).onSuccess { updated ->
                _metadata.value = updated
                _isLoading.value = false
                loadGroupData(convId)
                onResult(true, null)
            }.onFailure { err ->
                _isLoading.value = false
                onResult(false, err.message)
            }
        }
    }

    fun generateInviteLink(convId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            groupRepo.generateInviteLink(convId).onSuccess {
                onResult(it)
            }
        }
    }

    fun promoteMember(convId: String, userId: String) {
        viewModelScope.launch {
            groupRepo.promoteMember(convId, userId).onSuccess {
                loadGroupData(convId)
            }
        }
    }

    fun demoteMember(convId: String, userId: String) {
        viewModelScope.launch {
            groupRepo.demoteMember(convId, userId).onSuccess {
                loadGroupData(convId)
            }
        }
    }

    fun removeMember(convId: String, userId: String) {
        viewModelScope.launch {
            groupRepo.removeMember(convId, userId).onSuccess {
                loadGroupData(convId)
            }
        }
    }

    fun addMember(convId: String, userId: String, onResult: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            groupRepo.addMember(convId, userId).onSuccess {
                loadGroupData(convId)
                onResult?.invoke(true)
            }.onFailure {
                onResult?.invoke(false)
            }
        }
    }
}
