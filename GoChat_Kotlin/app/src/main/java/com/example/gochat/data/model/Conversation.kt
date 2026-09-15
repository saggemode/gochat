package com.example.gochat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

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
    @PrimaryKey val id: String = "",
    val title: String = "Chat",
    @SerialName("avatar_url") val avatarUrl: String = "",
    val type: ConversationType = ConversationType.DIRECT,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("is_muted") val isMuted: Boolean = false,
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("last_seen") val lastSeen: Long? = null,
    @SerialName("partner_pin") val partnerPin: String? = null,
    @SerialName("invitation_status") val invitationStatus: InvitationStatus = InvitationStatus.NONE,
    @SerialName("invitation_sender_id") val invitationSenderId: String? = null,
    @SerialName("member_ids") val memberIds: List<String> = emptyList(),
    @SerialName("last_message_text") val lastMessageText: String? = null,
    @SerialName("last_message_time") val lastMessageTime: Long? = null,
    @SerialName("screenshot_notifications_enabled") val screenshotNotificationsEnabled: Boolean = false,
    @SerialName("disappearing_messages_duration") val disappearingMessagesDuration: Int = 0,
    @SerialName("updated_at") val updatedAt: Long = System.currentTimeMillis()
) {
    val isGroup: Boolean get() = type == ConversationType.GROUP
    val isDirect: Boolean get() = type == ConversationType.DIRECT
    val isChannel: Boolean get() = type == ConversationType.CHANNEL

    companion object {
        fun fromJson(json: JsonObject, currentUserId: String = ""): Conversation {
            val id = (json["id"] ?: json["Id"] ?: json["conversation_id"] ?: json["conversationId"])
                ?.jsonPrimitive?.contentOrNull.orEmpty()

            var title = (json["name"] ?: json["Name"] ?: json["title"] ?: json["Title"] ?: json["display_name"])
                ?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            if (title.isBlank() || title.equals("User", ignoreCase = true)) {
                title = ""
            }
            if (title.contains("BBM", ignoreCase = true)) {
                title = title.replace("BBM", "GoChat", ignoreCase = true)
            }

            val avatarUrl = (json["avatar_url"] ?: json["avatarUrl"] ?: json["AvatarUrl"])
                ?.jsonPrimitive?.contentOrNull.orEmpty()

            // Parse ConversationType (handles 0, 1, 2, "group", "channel", "direct", "CONVERSATION_TYPE_GROUP", etc.)
            val typeElem = json["type"] ?: json["Type"]
            val typeStr = typeElem?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val typeInt = typeElem?.jsonPrimitive?.intOrNull
            val convType = when {
                typeInt == 1 || typeStr.contains("group") -> ConversationType.GROUP
                typeInt == 2 || typeStr.contains("channel") -> ConversationType.CHANNEL
                else -> ConversationType.DIRECT
            }

            // Parse InvitationStatus
            val statusStr = (json["invitation_status"] ?: json["invitationStatus"])
                ?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val invStatus = when {
                statusStr.contains("pending_outgoing") || statusStr.contains("pendingoutgoing") -> InvitationStatus.PENDING_OUTGOING
                statusStr.contains("pending_incoming") || statusStr.contains("pendingincoming") -> InvitationStatus.PENDING_INCOMING
                statusStr.contains("declined") -> InvitationStatus.DECLINED
                statusStr.contains("accepted") -> InvitationStatus.ACCEPTED
                else -> InvitationStatus.NONE
            }

            val unreadCount = (json["unread_count"] ?: json["unreadCount"] ?: json["UnreadCount"])
                ?.jsonPrimitive?.intOrNull ?: 0

            val isPinned = (json["is_pinned"] ?: json["isPinned"] ?: json["IsPinned"])
                ?.jsonPrimitive?.booleanOrNull ?: false

            val isMuted = (json["is_muted"] ?: json["isMuted"] ?: json["IsMuted"])
                ?.jsonPrimitive?.booleanOrNull ?: false

            val isOnline = (json["is_online"] ?: json["isOnline"] ?: json["IsOnline"])
                ?.jsonPrimitive?.booleanOrNull ?: false

            val lastSeen = parseOptionalTimestamp(json["last_seen"] ?: json["lastSeen"] ?: json["LastSeen"])

            val partnerPin = (json["partner_pin"] ?: json["partnerPin"] ?: json["PartnerPin"])
                ?.jsonPrimitive?.contentOrNull

            val invitationSenderId = (json["invitation_sender_id"] ?: json["invitationSenderId"])
                ?.jsonPrimitive?.contentOrNull

            val membersList = (json["member_ids"] ?: json["memberIds"] ?: json["MemberIds"])?.jsonArray?.mapNotNull {
                it.jsonPrimitive.contentOrNull
            } ?: emptyList()

            // Parse last message
            var lastText: String? = (json["last_message_text"] ?: json["lastMessageText"])?.jsonPrimitive?.contentOrNull
            var lastTime: Long? = parseTimestamp(json["last_message_time"] ?: json["lastMessageTime"])

            val lastMsgElem = json["last_message"] ?: json["lastMessage"] ?: json["LastMessage"]
            if (lastMsgElem is JsonObject) {
                lastText = (lastMsgElem["content"] ?: lastMsgElem["text"])?.jsonPrimitive?.contentOrNull ?: lastText
                lastTime = parseTimestamp(lastMsgElem["created_at"] ?: lastMsgElem["send_at"] ?: lastMsgElem["createdAt"])
            }

            val screenshotNotificationsEnabled = (json["screenshot_notifications_enabled"] ?: json["screenshotNotificationsEnabled"])
                ?.jsonPrimitive?.booleanOrNull ?: false

            val disappearingDuration = (json["disappearing_messages_duration"] ?: json["disappearingMessagesDuration"] ?: json["disappearing_duration"])
                ?.jsonPrimitive?.intOrNull ?: 0

            val updatedAt = parseTimestamp(json["updated_at"] ?: json["updatedAt"] ?: json["UpdatedAt"])

            return Conversation(
                id = id,
                title = title,
                avatarUrl = avatarUrl,
                type = convType,
                unreadCount = unreadCount,
                isPinned = isPinned,
                isMuted = isMuted,
                isOnline = isOnline,
                lastSeen = lastSeen,
                partnerPin = partnerPin,
                invitationStatus = invStatus,
                invitationSenderId = invitationSenderId,
                memberIds = membersList,
                lastMessageText = lastText,
                lastMessageTime = lastTime,
                screenshotNotificationsEnabled = screenshotNotificationsEnabled,
                disappearingMessagesDuration = disappearingDuration,
                updatedAt = updatedAt
            )
        }

        private fun parseOptionalTimestamp(element: JsonElement?): Long? {
            if (element == null || element is JsonNull) return null
            val prim = element.jsonPrimitive
            prim.longOrNull?.let { return if (it < 100_000_000_000L) it * 1000L else it }
            val str = prim.contentOrNull ?: return null
            str.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000L else it }
            return try {
                java.time.Instant.parse(str).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }

        private fun parseTimestamp(element: JsonElement?): Long {
            return parseOptionalTimestamp(element) ?: System.currentTimeMillis()
        }
    }
}
