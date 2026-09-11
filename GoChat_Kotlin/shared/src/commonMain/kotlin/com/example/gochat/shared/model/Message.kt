package com.example.gochat.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class MessageType {
    @SerialName("text") TEXT,
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
    @SerialName("audio") AUDIO,
    @SerialName("voice") VOICE,
    @SerialName("file") FILE,
    @SerialName("location") LOCATION,
    @SerialName("contact") CONTACT,
    @SerialName("poll") POLL,
    @SerialName("sticker") STICKER,
    @SerialName("product") PRODUCT,
    @SerialName("ping") PING
}

@Serializable
enum class MessageStatus {
    @SerialName("sending") SENDING,
    @SerialName("sent") SENT,
    @SerialName("delivered") DELIVERED,
    @SerialName("read") READ,
    @SerialName("failed") FAILED
}

@Serializable
data class PollOption(
    val id: String,
    val text: String,
    val votes: Int = 0,
    @SerialName("voter_ids") val voterIds: List<String> = emptyList()
)

@Serializable
data class PollData(
    val question: String,
    val options: List<PollOption> = emptyList(),
    @SerialName("allow_multiple_answers") val allowMultipleAnswers: Boolean = false
)

@Serializable
data class Reaction(
    @SerialName("user_id") val userId: String,
    @SerialName("user_name") val userName: String,
    val emoji: String,
    @SerialName("created_at") val createdAt: Long = 0
)

@Serializable
data class Message(
    val id: String = "",
    @SerialName("conversation_id") val conversationId: String = "",
    @SerialName("sender_id") val senderId: String = "",
    @SerialName("sender_name") val senderName: String = "",
    val content: String = "",
    val type: MessageType = MessageType.TEXT,
    val status: MessageStatus = MessageStatus.SENT,
    @SerialName("media_url") val mediaUrl: String? = null,
    @SerialName("media_thumbnail") val mediaThumbnail: String? = null,
    @SerialName("media_duration") val mediaDuration: Int? = null,
    @SerialName("media_size") val mediaSize: Long? = null,
    @SerialName("telegram_file_id") val telegramFileId: String? = null,
    @SerialName("is_view_once") val isViewOnce: Boolean = false,
    @SerialName("is_viewed") val isViewed: Boolean = false,
    @SerialName("is_ping") val isPing: Boolean = false,
    @SerialName("disappearing_duration_seconds") val disappearingDurationSeconds: Int? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("reply_to_text") val replyToText: String? = null,
    @SerialName("reply_to_sender_name") val replyToSenderName: String? = null,
    @SerialName("blur_hash") val blurHash: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    val isMe: Boolean = false,
    val reactions: List<Reaction> = emptyList(),
    @SerialName("is_edited") val isEdited: Boolean = false,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("is_starred") val isStarred: Boolean = false,
    @SerialName("is_forwarded") val isForwarded: Boolean = false,
    @SerialName("original_sender_name") val originalSenderName: String? = null
)
