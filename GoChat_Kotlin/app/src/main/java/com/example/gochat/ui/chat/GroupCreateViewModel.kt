package com.example.gochat.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.data.repository.GroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupCreateViewModel @Inject constructor(
    application: Application,
    private val chatRepository: ChatRepository,
    private val groupRepository: GroupRepository
) : AndroidViewModel(application) {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvent: SharedFlow<String> = _errorEvent.asSharedFlow()

    private val _successEvent = MutableSharedFlow<Conversation>(extraBufferCapacity = 1)
    val successEvent: SharedFlow<Conversation> = _successEvent.asSharedFlow()

    // Selected avatar URI from gallery/camera
    private val _avatarUri = MutableStateFlow<Uri?>(null)
    val avatarUri: StateFlow<Uri?> = _avatarUri.asStateFlow()

    fun setAvatarUri(uri: Uri?) {
        _avatarUri.value = uri
    }

    fun createGroup(name: String, memberIds: List<String>) {
        viewModelScope.launch {
            _isLoading.value = true

            // Step 1: Create the conversation
            val result = chatRepository.createConversation(
                name = name,
                memberIds = memberIds,
                isGroup = true
            )

            result.fold(
                onSuccess = { conversation ->
                    // Step 2: If avatar was selected, upload it and update group metadata
                    val avatarUri = _avatarUri.value
                    if (avatarUri != null) {
                        uploadAvatarAndUpdateGroup(conversation, avatarUri)
                    } else {
                        _isLoading.value = false
                        _successEvent.tryEmit(conversation)
                    }
                },
                onFailure = {
                    _isLoading.value = false
                    _errorEvent.tryEmit(it.message ?: "Failed to create group")
                }
            )
        }
    }

    private suspend fun uploadAvatarAndUpdateGroup(conversation: Conversation, avatarUri: Uri) {
        try {
            val context = getApplication<Application>()
            val inputStream = context.contentResolver.openInputStream(avatarUri)
            val bytes = inputStream?.readBytes()
            inputStream?.close()

            if (bytes == null || bytes.isEmpty()) {
                // Avatar upload failed but group was created — still succeed
                _isLoading.value = false
                _successEvent.tryEmit(conversation)
                return
            }

            // Determine mime type
            val mimeType = context.contentResolver.getType(avatarUri) ?: "image/jpeg"
            val fileName = "group_avatar_${conversation.id}.jpg"

            // Upload the media
            val uploadedUrl = chatRepository.uploadMedia(bytes, mimeType, fileName)

            if (!uploadedUrl.isNullOrBlank()) {
                // Update group metadata with the new avatar URL
                groupRepository.updateGroupMetadata(
                    convId = conversation.id,
                    avatarUrl = uploadedUrl
                )
            }

            _isLoading.value = false
            _successEvent.tryEmit(conversation)
        } catch (e: Exception) {
            // Group was created, avatar upload is best-effort
            _isLoading.value = false
            _successEvent.tryEmit(conversation)
        }
    }
}
