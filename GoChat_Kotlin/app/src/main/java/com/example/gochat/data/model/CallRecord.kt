package com.example.gochat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @PrimaryKey val id: String,
    @SerialName("peer_id") val peerId: String,
    @SerialName("peer_name") val peerName: String,
    @SerialName("peer_avatar") val peerAvatar: String = "",
    val type: CallType = CallType.AUDIO,
    val status: CallStatus = CallStatus.OUTGOING,
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)
