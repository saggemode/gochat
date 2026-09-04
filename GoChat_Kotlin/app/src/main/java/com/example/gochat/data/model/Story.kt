package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class StoryViewer(
    @SerialName("user_id") val userId: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("viewed_at") val viewedAt: String = ""
) {
    companion object {
        fun fromJson(json: JsonObject): StoryViewer {
            val userId = (json["user_id"] ?: json["userId"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val displayName = (json["display_name"] ?: json["displayName"])?.jsonPrimitive?.contentOrNull ?: "Contact"
            val avatarUrl = (json["avatar_url"] ?: json["avatarUrl"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val viewedAt = (json["viewed_at"] ?: json["viewedAt"])?.jsonPrimitive?.contentOrNull.orEmpty()

            return StoryViewer(
                userId = userId,
                displayName = displayName,
                avatarUrl = avatarUrl,
                viewedAt = viewedAt
            )
        }
    }
}

@Serializable
data class StoryItem(
    val id: String = "",
    @SerialName("media_url") val mediaUrl: String = "",
    val caption: String = "",
    @SerialName("media_type") val mediaType: String = "image", // "image", "video", "text"
    @SerialName("background_color") val backgroundColor: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("view_count") val viewCount: Int = 0,
    val viewers: List<StoryViewer> = emptyList()
) {
    companion object {
        fun fromJson(json: JsonObject): StoryItem {
            val id = (json["id"] ?: json["Id"])?.jsonPrimitive?.contentOrNull ?: "story_${System.currentTimeMillis()}"
            val mediaUrl = (json["media_url"] ?: json["mediaUrl"] ?: json["url"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val caption = (json["caption"] ?: json["content"] ?: json["text"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val mediaType = (json["media_type"] ?: json["mediaType"] ?: json["type"])?.jsonPrimitive?.contentOrNull ?: "image"
            val backgroundColor = (json["background_color"] ?: json["backgroundColor"])?.jsonPrimitive?.contentOrNull

            val rawCreated = json["created_at"] ?: json["createdAt"]
            val createdAtStr = when {
                rawCreated == null || rawCreated is JsonNull -> "Just now"
                rawCreated.jsonPrimitive.isString -> {
                    val s = rawCreated.jsonPrimitive.content
                    val asLong = s.toLongOrNull()
                    if (asLong != null) formatEpochTime(asLong) else s
                }
                rawCreated.jsonPrimitive.longOrNull != null -> {
                    formatEpochTime(rawCreated.jsonPrimitive.long)
                }
                else -> "Just now"
            }

            val viewCount = (json["view_count"] ?: json["viewCount"])?.jsonPrimitive?.intOrNull ?: 0

            val viewersList = (json["viewers"] ?: json["Viewers"])?.jsonArray?.mapNotNull { elem ->
                if (elem is JsonObject) StoryViewer.fromJson(elem) else null
            } ?: emptyList()

            return StoryItem(
                id = id,
                mediaUrl = mediaUrl,
                caption = caption,
                mediaType = mediaType,
                backgroundColor = backgroundColor,
                createdAt = createdAtStr,
                viewCount = viewCount,
                viewers = viewersList
            )
        }

        private fun formatEpochTime(epoch: Long): String {
            val ms = if (epoch < 100_000_000_000L) epoch * 1000L else epoch
            val diff = (System.currentTimeMillis() - ms).coerceAtLeast(0)
            val minutes = diff / (1000 * 60)
            val hours = diff / (1000 * 60 * 60)
            return when {
                minutes < 1 -> "Just now"
                minutes < 60 -> "${minutes}m ago"
                hours < 24 -> "${hours}h ago"
                else -> java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ms))
            }
        }
    }
}

@Serializable
data class UserStories(
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_name") val userName: String = "",
    @SerialName("user_avatar") val userAvatar: String = "",
    val stories: List<StoryItem> = emptyList(),
    val isMe: Boolean = false
) {
    val totalViewCount: Int get() = stories.sumOf { it.viewCount }
    val hasUnseenStories: Boolean get() = stories.isNotEmpty()
}
