package com.example.gochat.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.core.media.ImageCompressor
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager
) : AndroidViewModel(application) {

    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _avatarUrl = MutableStateFlow("")
    val avatarUrl: StateFlow<String> = _avatarUrl.asStateFlow()

    private val _pin = MutableStateFlow("")
    val pin: StateFlow<String> = _pin.asStateFlow()

    private val _phone = MutableStateFlow("")
    val phone: StateFlow<String> = _phone.asStateFlow()

    private val _isUploadingAvatar = MutableStateFlow(false)
    val isUploadingAvatar: StateFlow<Boolean> = _isUploadingAvatar.asStateFlow()

    private val _isUpdatingProfile = MutableStateFlow(false)
    val isUpdatingProfile: StateFlow<Boolean> = _isUpdatingProfile.asStateFlow()

    private val _eventMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val eventMessage: SharedFlow<String> = _eventMessage.asSharedFlow()

    init {
        loadProfile()
    }

    fun loadProfile() {
        _displayName.value = tokenManager.userDisplayName ?: "GoChat User"
        _statusText.value = tokenManager.userStatusText ?: "Hey there! I am using GoChat."
        _avatarUrl.value = tokenManager.userAvatarUrl.orEmpty()
        _pin.value = tokenManager.userPin ?: "N/A"
        _phone.value = tokenManager.userPhone.orEmpty()
    }

    fun updateProfile(newDisplayName: String, newStatusText: String) {
        val trimmedName = newDisplayName.trim()
        val trimmedStatus = newStatusText.trim()
        if (trimmedName.isBlank()) {
            _eventMessage.tryEmit("Display name cannot be empty")
            return
        }

        viewModelScope.launch {
            _isUpdatingProfile.value = true
            val result = authRepository.updateProfile(
                displayName = trimmedName,
                statusText = trimmedStatus.ifBlank { "Hey there! I am using GoChat." }
            )
            _isUpdatingProfile.value = false

            result.onSuccess {
                _displayName.value = trimmedName
                _statusText.value = trimmedStatus.ifBlank { "Hey there! I am using GoChat." }
                _eventMessage.tryEmit("Profile updated successfully")
            }.onFailure { error ->
                _eventMessage.tryEmit(error.message ?: "Failed to update profile")
            }
        }
    }

    fun uploadAndSetAvatar(uri: Uri) {
        viewModelScope.launch {
            _isUploadingAvatar.value = true
            try {
                // Compress image to prevent OutOfMemory and reduce network payload (mirroring Flutter maxWidth/maxHeight: 320, quality: 70)
                val compressed = ImageCompressor.compressImageUri(
                    getApplication(),
                    uri,
                    maxDimension = 512,
                    quality = 70
                )

                if (compressed == null) {
                    _eventMessage.tryEmit("Failed to process selected image")
                    _isUploadingAvatar.value = false
                    return@launch
                }

                // 1. Try uploading to remote CDN/storage first (mirroring Flutter ApiService.uploadMedia)
                var finalAvatarUrl: String? = null
                try {
                    val uploadedUrl = authRepository.uploadMedia(
                        bytes = compressed.bytes,
                        mimeType = compressed.mimeType,
                        fileName = "avatar_${System.currentTimeMillis()}.jpg"
                    )
                    if (!uploadedUrl.isNullOrBlank() &&
                        (uploadedUrl.startsWith("http://") || uploadedUrl.startsWith("https://") || uploadedUrl.startsWith("/"))
                    ) {
                        finalAvatarUrl = uploadedUrl
                    }
                } catch (_: Exception) {}

                // 2. Fallback to compact Base64 Data URI so other devices can render directly
                if (finalAvatarUrl.isNullOrBlank()) {
                    finalAvatarUrl = compressed.dataUri
                }

                // 3. Persist avatarUrl to user profile and broadcast on WebSocket
                val result = authRepository.updateProfile(avatarUrl = finalAvatarUrl)
                result.onSuccess {
                    _avatarUrl.value = finalAvatarUrl
                    _eventMessage.tryEmit("✅ Profile photo updated!")
                }.onFailure { error ->
                    _eventMessage.tryEmit("Failed to update avatar: ${error.message}")
                }
            } catch (e: Exception) {
                _eventMessage.tryEmit("Failed to update avatar: ${e.message}")
            } finally {
                _isUploadingAvatar.value = false
            }
        }
    }
}
