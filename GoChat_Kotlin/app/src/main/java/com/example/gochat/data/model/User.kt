package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val phone: String = "",
    val pin: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    val bio: String = "Hey there! I am using GoChat.",
    val status: String = "offline",
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null
)

@Serializable
data class SyncedContact(
    val phone: String,
    val name: String,
    @SerialName("is_registered") val isRegistered: Boolean = false,
    val pin: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("user_id") val userId: String = ""
)
