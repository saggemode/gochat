package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Channel(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("subscribers_count") val subscribersCount: Int = 0
)

@Serializable
data class ChannelMessage(
    val id: String = "",
    @SerialName("channel_id") val channelId: String = "",
    @SerialName("sender_id") val senderId: String = "",
    val content: String = "",
    val type: String = "text",
    @SerialName("media_url") val mediaUrl: String = "",
    @SerialName("media_mime") val mediaMime: String = "",
    @SerialName("media_size") val mediaSize: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0
)
