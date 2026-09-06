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
    @SerialName("status_text") val statusText: String = "Hey there! I am using GoChat.",
    val status: String = "offline",
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null
)

@Serializable
data class SyncedContact(
    val id: String = "",
    @SerialName("phonebook_name") val phonebookName: String = "",
    @SerialName("gochat_name") val gochatName: String? = null,
    val phone: String = "",
    val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("status_text") val statusText: String = "",
    @SerialName("is_registered") val isRegistered: Boolean = false,
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("last_seen") val lastSeen: Long? = null,
    val pin: String = "",
    // Backwards compatibility fields
    val name: String = "",
    @SerialName("user_id") val userId: String = ""
) {
    val displayName: String
        get() = phonebookName.ifBlank {
            gochatName?.ifBlank { null } ?: name.ifBlank { phone }
        }

    val finalUserId: String
        get() = id.ifBlank { userId }
}
