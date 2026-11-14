package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class StoryViewer(
    @SerialName("user_id") val userId: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("viewed_at") val viewedAt: String = "",
    @SerialName("reaction_emoji") val reactionEmoji: String = ""
) {
    companion object {
        fun fromJson(json: JsonObject): StoryViewer {
            val userId = (json["user_id"] ?: json["userId"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val displayName = (json["display_name"] ?: json["displayName"])?.jsonPrimitive?.contentOrNull ?: "Contact"
            val avatarUrl = (json["avatar_url"] ?: json["avatarUrl"])?.jsonPrimitive?.contentOrNull.orEmpty()

            val rawViewed = json["viewed_at"] ?: json["viewedAt"]
            val viewedAtStr = when {
                rawViewed == null || rawViewed is JsonNull -> "Just now"
                rawViewed.jsonPrimitive.longOrNull != null -> {
                    StoryItem.formatEpochTime(rawViewed.jsonPrimitive.long)
                }
                rawViewed.jsonPrimitive.isString -> {
                    val s = rawViewed.jsonPrimitive.content
                    val asLong = s.toLongOrNull()
                    if (asLong != null) StoryItem.formatEpochTime(asLong) else s.ifBlank { "Just now" }
                }
                else -> "Just now"
            }

            val reactionEmoji = (json["reaction_emoji"] ?: json["reactionEmoji"])?.jsonPrimitive?.contentOrNull.orEmpty()

            return StoryViewer(
                userId = userId,
                displayName = displayName,
                avatarUrl = avatarUrl,
                viewedAt = viewedAtStr,
                reactionEmoji = reactionEmoji
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
    val viewers: List<StoryViewer> = emptyList(),
    @SerialName("allow_reshare") val allowReshare: Boolean = true,
    @SerialName("is_reshare") val isReshare: Boolean = false,
    @SerialName("reshared_from_story_id") val resharedFromStoryId: String? = null,
    @SerialName("original_author_id") val originalAuthorId: String? = null,
    @SerialName("original_author_name") val originalAuthorName: String? = null,
    @SerialName("audience_type") val audienceType: String = "contacts",
    @SerialName("mentioned_user_ids") val mentionedUserIds: List<String> = emptyList(),
    @SerialName("music_url") val musicUrl: String = "",
    @SerialName("music_title") val musicTitle: String = "",
    @SerialName("music_artist") val musicArtist: String = "",
    @SerialName("music_start_ms") val musicStartMs: Int = 0
) {
    val hasMusic: Boolean get() = musicUrl.isNotBlank()
    val isProductStory: Boolean get() = caption.contains("🏷️") || caption.contains("#prod_")
    val extractedProductId: String? get() = if (caption.contains("#prod_")) {
        caption.substringAfter("#prod_").substringBefore("\n").substringBefore(" ").trim().takeIf { it.isNotBlank() }
    } else null
    val displayCaption: String get() = caption.replace(Regex("#prod_[a-zA-Z0-9_-]+"), "").trim()

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

            val viewCount = (json["view_count"] ?: json["viewCount"] ?: json["viewer_count"] ?: json["viewerCount"])?.jsonPrimitive?.intOrNull ?: 0
            val allowReshare = (json["allow_reshare"] ?: json["allowReshare"])?.jsonPrimitive?.booleanOrNull ?: true
            val isReshare = (json["is_reshare"] ?: json["isReshare"])?.jsonPrimitive?.booleanOrNull ?: false
            val resharedFromStoryId = (json["reshared_from_story_id"] ?: json["resharedFromStoryId"])?.jsonPrimitive?.contentOrNull
            val originalAuthorId = (json["original_author_id"] ?: json["originalAuthorId"])?.jsonPrimitive?.contentOrNull
            val originalAuthorName = (json["original_author_name"] ?: json["originalAuthorName"])?.jsonPrimitive?.contentOrNull

            val viewersList = (json["viewers"] ?: json["Viewers"])?.jsonArray?.mapNotNull { elem ->
                if (elem is JsonObject) StoryViewer.fromJson(elem) else null
            } ?: emptyList()

            val audienceType = (json["audience_type"] ?: json["audienceType"])?.jsonPrimitive?.contentOrNull ?: "contacts"
            val mentionedUserIds = (json["mentioned_user_ids"] ?: json["mentionedUserIds"])?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val musicUrl = (json["music_url"] ?: json["musicUrl"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val musicTitle = (json["music_title"] ?: json["musicTitle"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val musicArtist = (json["music_artist"] ?: json["musicArtist"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val musicStartMs = (json["music_start_ms"] ?: json["musicStartMs"])?.jsonPrimitive?.intOrNull ?: 0

            return StoryItem(
                id = id,
                mediaUrl = mediaUrl,
                caption = caption,
                mediaType = mediaType,
                backgroundColor = backgroundColor,
                createdAt = createdAtStr,
                viewCount = viewCount,
                viewers = viewersList,
                allowReshare = allowReshare,
                isReshare = isReshare,
                resharedFromStoryId = resharedFromStoryId,
                originalAuthorId = originalAuthorId,
                originalAuthorName = originalAuthorName,
                audienceType = audienceType,
                mentionedUserIds = mentionedUserIds,
                musicUrl = musicUrl,
                musicTitle = musicTitle,
                musicArtist = musicArtist,
                musicStartMs = musicStartMs
            )
        }

        fun formatEpochTime(epoch: Long): String {
            val ms = if (epoch < 100_000_000_000L) epoch * 1000L else epoch
            val diff = (System.currentTimeMillis() - ms).coerceAtLeast(0)
            val minutes = diff / (1000 * 60)
            val hours = diff / (1000 * 60 * 60)
            val days = diff / (1000 * 60 * 60 * 24)
            return when {
                minutes < 1 -> "Just now"
                minutes < 60 -> "${minutes}m ago"
                hours < 24 -> "${hours}h ago"
                days == 1L -> "Yesterday at " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ms))
                else -> java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ms))
            }
        }
    }
}

/**
 * Status composer options for privacy/audience (A), mentions (B) and music (C).
 * - audienceType: "contacts" (all), "except" (all minus [audienceContactIds]),
 *   "only" (only [audienceContactIds]).
 */
@Serializable
data class StatusComposerOptions(
    val audienceType: String = "contacts",
    val audienceContactIds: List<String> = emptyList(),
    val mentionedUserIds: List<String> = emptyList(),
    val musicUrl: String = "",
    val musicTitle: String = "",
    val musicArtist: String = "",
    val musicStartMs: Int = 0
)

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
