package com.example.gochat.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.data.model.GroupMember
import com.example.gochat.data.model.GroupMetadata
import com.example.gochat.data.repository.AuthRepository
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
    private val authRepo: AuthRepository
) : AndroidViewModel(application) {

    private val _metadata = MutableStateFlow<GroupMetadata?>(null)
    val metadata: StateFlow<GroupMetadata?> = _metadata.asStateFlow()

    private val _members = MutableStateFlow<List<GroupMember>>(emptyList())
    val members: StateFlow<List<GroupMember>> = _members.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadGroupData(convId: String, memberIds: List<String>) {
        viewModelScope.launch {
            _isLoading.value = true
            
            // Load Metadata
            groupRepo.getGroupMetadata(convId).onSuccess {
                _metadata.value = it
            }

            // Load Member Details
            val memberList = mutableListOf<GroupMember>()
            memberIds.forEach { id ->
                // In a real app, we'd fetch actual member details from API/DB
                // Using placeholder with role for now
                memberList.add(GroupMember(id = id, displayName = "User $id", role = if (id.startsWith("admin")) "admin" else "member"))
            }
            _members.value = memberList

            _isLoading.value = false
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
                // Refresh data
            }
        }
    }

    fun removeMember(convId: String, userId: String) {
        viewModelScope.launch {
            groupRepo.removeMember(convId, userId).onSuccess {
                // Refresh data
            }
        }
    }
}
