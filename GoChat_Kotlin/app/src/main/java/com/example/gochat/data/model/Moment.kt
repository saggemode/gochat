package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Moment(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    val content: String = "",
    val caption: String = "",
    @SerialName("media_url") val mediaUrl: String = "",
    @SerialName("media_type") val mediaType: String = "",
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("has_liked") val hasLiked: Boolean = false,
    @SerialName("user_display_name") val userDisplayName: String = "",
    @SerialName("user_avatar_url") val userAvatarUrl: String = "",
    val comments: List<MomentComment> = emptyList()
) {
    val displayText: String get() = content.ifBlank { caption }
}

@Serializable
data class MomentComment(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_display_name") val userDisplayName: String = "",
    val text: String = "",
    @SerialName("created_at") val createdAt: Long = 0
)

@Serializable
data class MomentsFeedResponse(
    val moments: List<Moment> = emptyList()
)
