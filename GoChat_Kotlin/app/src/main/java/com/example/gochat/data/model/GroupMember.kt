package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GroupMember(
    val id: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    val role: String = "member", // "owner", "admin", "member"
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null
)

@Serializable
data class GroupMetadata(
    @SerialName("conversation_id") val conversationId: String,
    val description: String = "",
    @SerialName("announcements_only") val announcementsOnly: Boolean = false,
    @SerialName("admins_only_edit_info") val adminsOnlyEditInfo: Boolean = false,
    @SerialName("invite_code") val inviteCode: String? = null,
    @SerialName("join_approval_required") val joinApprovalRequired: Boolean = false
)
