package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StoryViewer(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("viewed_at") val viewedAt: String = ""
)

@Serializable
data class StoryItem(
    val id: String,
    @SerialName("media_url") val mediaUrl: String,
    val caption: String = "",
    @SerialName("media_type") val mediaType: String = "image", // "image", "video", "text"
    @SerialName("background_color") val backgroundColor: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("view_count") val viewCount: Int = 0,
    val viewers: List<StoryViewer> = emptyList()
)

@Serializable
data class UserStories(
    @SerialName("user_id") val userId: String,
    @SerialName("user_name") val userName: String,
    @SerialName("user_avatar") val userAvatar: String = "",
    val stories: List<StoryItem> = emptyList(),
    val isMe: Boolean = false
) {
    val totalViewCount: Int get() = stories.sumOf { it.viewCount }
    val hasUnseenStories: Boolean get() = stories.isNotEmpty()
}
