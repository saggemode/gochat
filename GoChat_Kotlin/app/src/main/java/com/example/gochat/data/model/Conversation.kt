package com.example.gochat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ConversationType {
    @SerialName("direct") DIRECT,
    @SerialName("group") GROUP,
    @SerialName("channel") CHANNEL
}

@Serializable
enum class InvitationStatus {
    @SerialName("none") NONE,
    @SerialName("pending_incoming") PENDING_INCOMING,
    @SerialName("pending_outgoing") PENDING_OUTGOING,
    @SerialName("accepted") ACCEPTED,
    @SerialName("declined") DECLINED
}

@Serializable
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val id: String,
    val title: String,
    @SerialName("avatar_url") val avatarUrl: String = "",
    val type: ConversationType = ConversationType.DIRECT,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("is_muted") val isMuted: Boolean = false,
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("partner_pin") val partnerPin: String? = null,
    @SerialName("invitation_status") val invitationStatus: InvitationStatus = InvitationStatus.NONE,
    @SerialName("invitation_sender_id") val invitationSenderId: String? = null,
    @SerialName("member_ids") val memberIds: List<String> = emptyList(),
    @SerialName("last_message_text") val lastMessageText: String? = null,
    @SerialName("last_message_time") val lastMessageTime: Long? = null,
    @SerialName("updated_at") val updatedAt: Long = System.currentTimeMillis()
)
