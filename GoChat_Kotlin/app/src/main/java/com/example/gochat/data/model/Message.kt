package com.example.gochat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
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
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String = "",
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
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis(),
    val isMe: Boolean = false,

    // Enhancements
    val reactions: List<Reaction> = emptyList(),
    @SerialName("is_edited") val isEdited: Boolean = false,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("is_starred") val isStarred: Boolean = false,
    @SerialName("is_forwarded") val isForwarded: Boolean = false,
    @SerialName("original_sender_name") val originalSenderName: String? = null
) {
    companion object {
        fun fromJson(json: JsonObject, currentUserId: String = ""): Message {
            val id = (json["id"] ?: json["Id"] ?: json["message_id"] ?: json["messageId"])
                ?.jsonPrimitive?.contentOrNull ?: "msg_${System.currentTimeMillis()}"

            val conversationId = (json["conversation_id"] ?: json["conversationId"] ?: json["ConversationId"] ?: json["conv_id"])
                ?.jsonPrimitive?.contentOrNull.orEmpty()

            val senderId = (json["sender_id"] ?: json["senderId"] ?: json["SenderId"] ?: json["actor_id"])
                ?.jsonPrimitive?.contentOrNull.orEmpty()

            val senderName = (json["sender_name"] ?: json["senderName"] ?: json["SenderName"])
                ?.jsonPrimitive?.contentOrNull ?: if (currentUserId.isNotBlank() && senderId == currentUserId) "Me" else "User"

            val content = (json["content"] ?: json["text"] ?: json["Content"])
                ?.jsonPrimitive?.contentOrNull.orEmpty()

            // Parse MessageType
            val typeElem = json["type"] ?: json["Type"] ?: json["media_type"]
            val typeStr = typeElem?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val typeInt = typeElem?.jsonPrimitive?.intOrNull
            val isPingVal = json["is_ping"]?.jsonPrimitive?.booleanOrNull == true ||
                    json["isPing"]?.jsonPrimitive?.booleanOrNull == true ||
                    typeStr.contains("ping") ||
                    content.contains("💥 PING") ||
                    content.contains("[PING]")

            val msgType = when {
                isPingVal -> MessageType.PING
                typeInt == 1 || typeStr.contains("image") -> MessageType.IMAGE
                typeInt == 2 || typeStr.contains("video") -> MessageType.VIDEO
                typeInt == 3 || typeStr.contains("audio") -> MessageType.AUDIO
                typeInt == 4 || typeStr.contains("file") || typeStr.contains("document") -> MessageType.FILE
                typeInt == 5 || typeStr.contains("voice") -> MessageType.VOICE
                typeInt == 6 || typeStr.contains("poll") -> MessageType.POLL
                typeInt == 7 || typeStr.contains("product") -> MessageType.PRODUCT
                typeInt == 8 || typeStr.contains("ping") -> MessageType.PING
                else -> MessageType.TEXT
            }

            // Parse MessageStatus
            val statusElem = json["status"] ?: json["Status"]
            val statusStr = statusElem?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val statusInt = statusElem?.jsonPrimitive?.intOrNull
            val msgStatus = when {
                statusInt == 3 || statusStr.contains("read") -> MessageStatus.READ
                statusInt == 2 || statusStr.contains("deliver") -> MessageStatus.DELIVERED
                statusInt == 1 || statusStr.contains("sent") -> MessageStatus.SENT
                statusInt == 0 || statusStr.contains("send") || statusStr.contains("pending") -> MessageStatus.SENDING
                statusInt == 4 || statusStr.contains("fail") -> MessageStatus.FAILED
                else -> MessageStatus.SENT
            }

            val mediaUrl = (json["media_url"] ?: json["mediaUrl"] ?: json["MediaUrl"] ?: json["url"])
                ?.jsonPrimitive?.contentOrNull

            val mediaThumbnail = (json["media_thumbnail"] ?: json["mediaThumbnail"] ?: json["thumbnail_url"] ?: json["ThumbnailUrl"])
                ?.jsonPrimitive?.contentOrNull

            val mediaDuration = (json["media_duration"] ?: json["mediaDuration"] ?: json["duration"] ?: json["Duration"])
                ?.jsonPrimitive?.intOrNull

            val mediaSize = (json["media_size"] ?: json["mediaSize"] ?: json["file_size"] ?: json["size"])
                ?.jsonPrimitive?.longOrNull

            val telegramFileId = (json["telegram_file_id"] ?: json["telegramFileId"] ?: json["file_id"])
                ?.jsonPrimitive?.contentOrNull

            val isViewOnce = (json["is_view_once"] ?: json["isViewOnce"])
                ?.jsonPrimitive?.booleanOrNull ?: false

            val isViewed = (json["is_viewed"] ?: json["isViewed"])
                ?.jsonPrimitive?.booleanOrNull ?: false

            val disappearingDuration = (json["disappearing_duration_seconds"] ?: json["disappearing_duration"])
                ?.jsonPrimitive?.intOrNull

            val expiresAt = parseTimestamp(json["expires_at"] ?: json["expiresAt"])

            val replyToId = (json["reply_to_id"] ?: json["replyToId"] ?: json["parent_id"] ?: json["ParentId"])
                ?.jsonPrimitive?.contentOrNull

            val replyToText = (json["reply_to_text"] ?: json["replyToText"])
                ?.jsonPrimitive?.contentOrNull

            val replyToSenderName = (json["reply_to_sender_name"] ?: json["replyToSenderName"])
                ?.jsonPrimitive?.contentOrNull

            val reactions = (json["reactions"] ?: json["Reactions"])?.jsonArray?.map {
                val obj = it.jsonObject
                Reaction(
                    userId = obj["user_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    userName = obj["user_name"]?.jsonPrimitive?.contentOrNull ?: "User",
                    emoji = obj["emoji"]?.jsonPrimitive?.contentOrNull ?: "👍",
                    createdAt = parseTimestamp(obj["created_at"])
                )
            } ?: emptyList()

            val isEdited = json["is_edited"]?.jsonPrimitive?.booleanOrNull ?: false
            val isDeleted = json["is_deleted"]?.jsonPrimitive?.booleanOrNull ?: false
            val isStarred = json["is_starred"]?.jsonPrimitive?.booleanOrNull ?: false
            
            val isForwarded = json["is_forwarded"]?.jsonPrimitive?.booleanOrNull == true ||
                    json.containsKey("forwarded_from_id") ||
                    json.containsKey("ForwardedFromId")
            
            val originalSenderName = json["original_sender_name"]?.jsonPrimitive?.contentOrNull ?:
                    json["forwarded_from_sender_name"]?.jsonPrimitive?.contentOrNull ?: "Someone"

            val createdAt = parseTimestamp(json["created_at"] ?: json["createdAt"] ?: json["send_at"] ?: json["SendAt"])

            val isMe = currentUserId.isNotBlank() && senderId == currentUserId

            return Message(
                id = id,
                conversationId = conversationId,
                senderId = senderId,
                senderName = senderName,
                content = content,
                type = msgType,
                status = msgStatus,
                mediaUrl = mediaUrl,
                mediaThumbnail = mediaThumbnail,
                mediaDuration = mediaDuration,
                mediaSize = mediaSize,
                telegramFileId = telegramFileId,
                isViewOnce = isViewOnce,
                isViewed = isViewed,
                isPing = isPingVal,
                disappearingDurationSeconds = disappearingDuration,
                expiresAt = expiresAt,
                replyToId = replyToId,
                replyToText = replyToText,
                replyToSenderName = replyToSenderName,
                createdAt = createdAt,
                isMe = isMe,
                reactions = reactions,
                isEdited = isEdited,
                isDeleted = isDeleted,
                isStarred = isStarred,
                isForwarded = isForwarded,
                originalSenderName = originalSenderName
            )
        }

        private fun parseTimestamp(element: JsonElement?): Long {
            if (element == null || element is JsonNull) return System.currentTimeMillis()
            val prim = element.jsonPrimitive
            prim.longOrNull?.let { return if (it < 100_000_000_000L) it * 1000L else it }
            val str = prim.contentOrNull ?: return System.currentTimeMillis()
            str.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000L else it }
            return try {
                java.time.Instant.parse(str).toEpochMilli()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }
    }
}
