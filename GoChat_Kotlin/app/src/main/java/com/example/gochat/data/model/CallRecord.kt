package com.example.gochat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class CallType {
    @SerialName("audio") AUDIO,
    @SerialName("video") VIDEO
}

@Serializable
enum class CallStatus {
    @SerialName("incoming") INCOMING,
    @SerialName("outgoing") OUTGOING,
    @SerialName("missed") MISSED
}

@Serializable
@Entity(tableName = "calls")
data class CallRecord(
    @PrimaryKey val id: String = "",
    @SerialName("peer_id") val peerId: String = "",
    @SerialName("peer_name") val peerName: String = "",
    @SerialName("peer_avatar") val peerAvatar: String = "",
    val type: CallType = CallType.AUDIO,
    val status: CallStatus = CallStatus.OUTGOING,
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromJson(json: JsonObject, currentUserId: String = ""): CallRecord {
            val id = (json["id"] ?: json["Id"])?.jsonPrimitive?.contentOrNull ?: "call_${System.currentTimeMillis()}"

            val callerId = (json["caller_id"] ?: json["callerId"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val receiverId = (json["receiver_id"] ?: json["receiverId"])?.jsonPrimitive?.contentOrNull.orEmpty()

            val callerName = (json["caller_name"] ?: json["callerName"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val receiverName = (json["receiver_name"] ?: json["receiverName"])?.jsonPrimitive?.contentOrNull.orEmpty()

            val callerAvatar = (json["caller_avatar_url"] ?: json["callerAvatarUrl"] ?: json["caller_avatar"])
                ?.jsonPrimitive?.contentOrNull.orEmpty()
            val receiverAvatar = (json["receiver_avatar_url"] ?: json["receiverAvatarUrl"] ?: json["receiver_avatar"])
                ?.jsonPrimitive?.contentOrNull.orEmpty()

            val isOutgoing = callerId.isNotBlank() && callerId == currentUserId

            val peerId = if (isOutgoing) receiverId.ifBlank { callerId } else callerId.ifBlank { receiverId }
            var peerName = if (isOutgoing) receiverName.ifBlank { callerName } else callerName.ifBlank { receiverName }
            if (peerName.isBlank()) peerName = "GoChat Contact"

            val peerAvatar = if (isOutgoing) receiverAvatar.ifBlank { callerAvatar } else callerAvatar.ifBlank { receiverAvatar }

            // Type
            val rawType = (json["type"] ?: json["Type"])?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val typeInt = (json["type"] ?: json["Type"])?.jsonPrimitive?.intOrNull
            val callType = if (typeInt == 1 || rawType.contains("video")) CallType.VIDEO else CallType.AUDIO

            // Status
            val rawStatus = (json["status"] ?: json["Status"])?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val statusInt = (json["status"] ?: json["Status"])?.jsonPrimitive?.intOrNull

            val isMissed = statusInt == 2 || statusInt == 3 || rawStatus.contains("missed") || rawStatus.contains("reject")
            val callStatus = when {
                isMissed -> CallStatus.MISSED
                isOutgoing -> CallStatus.OUTGOING
                else -> CallStatus.INCOMING
            }

            val duration = (json["duration_sec"] ?: json["durationSec"] ?: json["duration_seconds"] ?: json["duration"])
                ?.jsonPrimitive?.intOrNull ?: 0

            val startTimeElem = json["start_time"] ?: json["startTime"] ?: json["timestamp"] ?: json["created_at"]
            val timestamp = parseTimestamp(startTimeElem)

            return CallRecord(
                id = id,
                peerId = peerId,
                peerName = peerName,
                peerAvatar = peerAvatar,
                type = callType,
                status = callStatus,
                durationSeconds = duration,
                timestamp = timestamp
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
