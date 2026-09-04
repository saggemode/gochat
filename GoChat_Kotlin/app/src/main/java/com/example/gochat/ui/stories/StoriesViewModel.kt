package com.example.gochat.ui.stories

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.UserStories
import com.example.gochat.data.repository.StoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StoriesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StoryRepository(application)
    private val tokenManager = TokenManager.getInstance(application)

    private val _myStories = MutableStateFlow<UserStories?>(null)
    val myStories: StateFlow<UserStories?> = _myStories.asStateFlow()

    private val _recentStories = MutableStateFlow<List<UserStories>>(emptyList())
    val recentStories: StateFlow<List<UserStories>> = _recentStories.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadStories()
    }

    fun loadStories() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _error.value = null

            val result = withContext(Dispatchers.IO) {
                repository.getStories()
            }

            val currentUserId = tokenManager.userId.orEmpty()
            val currentUserName = tokenManager.userDisplayName ?: "My Status"
            val currentUserAvatar = tokenManager.userAvatarUrl.orEmpty()

            result.onSuccess { allStories ->
                val myStoryList = allStories.find { it.userId == currentUserId }
                if (myStoryList != null) {
                    _myStories.value = myStoryList.copy(isMe = true)
                } else {
                    _myStories.value = UserStories(
                        userId = currentUserId,
                        userName = currentUserName,
                        userAvatar = currentUserAvatar,
                        isMe = true,
                        stories = emptyList()
                    )
                }

                _recentStories.value = allStories.filter { it.userId != currentUserId && it.stories.isNotEmpty() }
            }.onFailure { e ->
                _error.value = e.localizedMessage ?: "Failed to load status updates"
                if (_myStories.value == null) {
                    _myStories.value = UserStories(
                        userId = currentUserId,
                        userName = currentUserName,
                        userAvatar = currentUserAvatar,
                        isMe = true,
                        stories = emptyList()
                    )
                }
            }

            _isRefreshing.value = false
        }
    }

    fun postTextStatus(text: String, backgroundColorHex: String, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.postStory(
                    mediaUrl = "",
                    caption = text,
                    mediaType = "text",
                    backgroundColor = backgroundColorHex
                )
            }

            result.onSuccess {
                loadStories()
                onComplete(true, null)
            }.onFailure { e ->
                onComplete(false, e.localizedMessage ?: "Failed to post status")
            }
        }
    }

    fun postMediaStatus(mediaUrl: String, caption: String, mediaType: String = "image", onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.postStory(
                    mediaUrl = mediaUrl,
                    caption = caption,
                    mediaType = mediaType
                )
            }

            result.onSuccess {
                loadStories()
                onComplete(true, null)
            }.onFailure { e ->
                onComplete(false, e.localizedMessage ?: "Failed to post status")
            }
        }
    }
}
