package com.example.gochat.ui.stories

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.StoryItem
import com.example.gochat.data.model.UserStories
import com.example.gochat.data.repository.StoryRepository
import com.example.gochat.data.websocket.GoChatWebSocket
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

@HiltViewModel
class StoriesViewModel @Inject constructor(
    application: Application,
    private val repository: StoryRepository,
    private val tokenManager: TokenManager,
    private val webSocket: GoChatWebSocket
) : AndroidViewModel(application) {

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
        listenForRealtimeStories()
    }

    private fun listenForRealtimeStories() {
        viewModelScope.launch {
            webSocket.events.collect { json ->
                try {
                    val eventType = (json["type"] ?: json["event"])?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (eventType == "story_created" || eventType == "chat:stories") {
                        handleIncomingStoryEvent(json)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun handleIncomingStoryEvent(json: kotlinx.serialization.json.JsonObject) {
        val storyId = (json["story_id"] ?: json["id"])?.jsonPrimitive?.contentOrNull ?: "story_${System.currentTimeMillis()}"
        val userId = (json["user_id"] ?: json["actor_id"] ?: json["sender_id"])?.jsonPrimitive?.contentOrNull.orEmpty()
        val userName = (json["user_name"] ?: json["sender_name"] ?: json["author_name"])?.jsonPrimitive?.contentOrNull ?: "Contact"
        val userAvatar = (json["user_avatar"] ?: json["avatar_url"])?.jsonPrimitive?.contentOrNull.orEmpty()
        val mediaUrl = (json["media_url"] ?: json["url"])?.jsonPrimitive?.contentOrNull.orEmpty()
        val caption = (json["caption"] ?: json["content"])?.jsonPrimitive?.contentOrNull.orEmpty()
        val mediaType = (json["media_type"] ?: json["type"])?.jsonPrimitive?.contentOrNull ?: "image"
        val backgroundColor = json["background_color"]?.jsonPrimitive?.contentOrNull

        val currentUserId = tokenManager.userId.orEmpty()
        if (userId.isNotBlank() && userId != currentUserId) {
            val newStory = StoryItem(
                id = storyId,
                mediaUrl = mediaUrl,
                caption = caption,
                mediaType = mediaType,
                backgroundColor = backgroundColor,
                createdAt = "Just now",
                viewCount = 0,
                viewers = emptyList()
            )

            val currentList = _recentStories.value.toMutableList()
            val existingIdx = currentList.indexOfFirst {
                it.userId == userId || (it.userName.isNotBlank() && it.userName.equals(userName, ignoreCase = true))
            }

            if (existingIdx != -1) {
                val existing = currentList[existingIdx]
                if (!existing.stories.any { it.id == storyId }) {
                    currentList[existingIdx] = existing.copy(stories = listOf(newStory) + existing.stories)
                }
            } else {
                currentList.add(0, UserStories(
                    userId = userId,
                    userName = userName,
                    userAvatar = userAvatar,
                    stories = listOf(newStory),
                    isMe = false
                ))
            }
            _recentStories.value = currentList
        }
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
                    // Retain any local stories that haven't expired or were recently added
                    val existingLocalStories = _myStories.value?.stories.orEmpty()
                    val mergedStories = (existingLocalStories.filter { local -> !myStoryList.stories.any { it.id == local.id } } + myStoryList.stories)
                    _myStories.value = myStoryList.copy(
                        stories = if (mergedStories.isNotEmpty()) mergedStories else myStoryList.stories,
                        isMe = true
                    )
                } else {
                    val currentMe = _myStories.value
                    if (currentMe == null || currentMe.stories.isEmpty()) {
                        _myStories.value = UserStories(
                            userId = currentUserId,
                            userName = currentUserName,
                            userAvatar = currentUserAvatar,
                            isMe = true,
                            stories = emptyList()
                        )
                    }
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
        val currentUserId = tokenManager.userId.orEmpty()
        val currentUserName = tokenManager.userDisplayName ?: "My Status"
        val currentUserAvatar = tokenManager.userAvatarUrl.orEmpty()

        val storyId = "story_${System.currentTimeMillis()}"
        val optimisticStory = StoryItem(
            id = storyId,
            mediaUrl = "",
            caption = text,
            mediaType = "text",
            backgroundColor = backgroundColorHex,
            createdAt = "Just now",
            viewCount = 0,
            viewers = emptyList()
        )

        // Optimistic UI update (matches Flutter's app_state.dart)
        val current = _myStories.value ?: UserStories(
            userId = currentUserId,
            userName = currentUserName,
            userAvatar = currentUserAvatar,
            isMe = true,
            stories = emptyList()
        )
        _myStories.value = current.copy(
            stories = listOf(optimisticStory) + current.stories,
            isMe = true
        )

        // Broadcast live WebSocket event to connected friends (matches Flutter)
        try {
            webSocket.send(buildJsonObject {
                put("type", "story_created")
                put("event", "story_created")
                put("story_id", storyId)
                put("user_id", currentUserId)
                put("user_name", currentUserName)
                put("user_avatar", currentUserAvatar)
                put("media_url", "")
                put("caption", text)
                put("content", text)
                put("media_type", "text")
                put("background_color", backgroundColorHex)
            })
        } catch (_: Exception) {}

        // Send to backend API
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
                // Local status is already displayed; notify caller gracefully
                onComplete(true, null)
            }
        }
    }

    fun postMediaStatus(
        mediaBytes: ByteArray?,
        mimeType: String?,
        localDataUri: String,
        caption: String,
        mediaType: String = "image",
        onComplete: (Boolean, String?) -> Unit
    ) {
        val currentUserId = tokenManager.userId.orEmpty()
        val currentUserName = tokenManager.userDisplayName ?: "My Status"
        val currentUserAvatar = tokenManager.userAvatarUrl.orEmpty()

        val storyId = "story_${System.currentTimeMillis()}"
        val optimisticStory = StoryItem(
            id = storyId,
            mediaUrl = localDataUri,
            caption = caption,
            mediaType = mediaType,
            createdAt = "Just now",
            viewCount = 0,
            viewers = emptyList()
        )

        // Optimistic UI update
        val current = _myStories.value ?: UserStories(
            userId = currentUserId,
            userName = currentUserName,
            userAvatar = currentUserAvatar,
            isMe = true,
            stories = emptyList()
        )
        _myStories.value = current.copy(
            stories = listOf(optimisticStory) + current.stories,
            isMe = true
        )

        viewModelScope.launch {
            // 1. Upload media if bytes are present, mirroring Flutter's uploadMediaResult
            var finalMediaUrl = localDataUri
            if (mediaBytes != null && mediaBytes.isNotEmpty()) {
                val uploaded = withContext(Dispatchers.IO) {
                    repository.uploadMedia(
                        bytes = mediaBytes,
                        mimeType = mimeType ?: "image/jpeg",
                        fileName = "status_${System.currentTimeMillis()}.jpg"
                    )
                }
                if (!uploaded.isNullOrBlank()) {
                    finalMediaUrl = uploaded
                }
            }

            // 2. Broadcast live WebSocket event
            try {
                webSocket.send(buildJsonObject {
                    put("type", "story_created")
                    put("event", "story_created")
                    put("story_id", storyId)
                    put("user_id", currentUserId)
                    put("user_name", currentUserName)
                    put("user_avatar", currentUserAvatar)
                    put("media_url", finalMediaUrl)
                    put("caption", caption)
                    put("content", caption)
                    put("media_type", mediaType)
                })
            } catch (_: Exception) {}

            // 3. Post to backend
            val result = withContext(Dispatchers.IO) {
                repository.postStory(
                    mediaUrl = finalMediaUrl,
                    caption = caption,
                    mediaType = mediaType
                )
            }

            result.onSuccess {
                loadStories()
                onComplete(true, null)
            }.onFailure {
                // Local status is already displayed
                onComplete(true, null)
            }
        }
    }
}
