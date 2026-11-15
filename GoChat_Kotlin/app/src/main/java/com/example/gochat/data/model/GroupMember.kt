package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GroupMember(
    val id: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    val role: String = "member", // "owner", "admin", "member"
    @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null
)

@Serializable
data class GroupMetadata(
    @SerialName("conversation_id") val conversationId: String,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val description: String = "",
    @SerialName("announcements_only") val announcementsOnly: Boolean = false,
    @SerialName("admins_only_edit_info") val adminsOnlyEditInfo: Boolean = false,
    @SerialName("invite_code") val inviteCode: String? = null,
    @SerialName("join_approval_required") val joinApprovalRequired: Boolean = false
)

@Serializable
data class GroupMembersResponse(
    val members: List<GroupMember> = emptyList()
)

@Serializable
data class GroupEvent(
    val id: String = "",
    @SerialName("conversation_id") val conversationId: String = "",
    val title: String = "",
    val description: String = "",
    val location: String = "",
    @SerialName("event_time") val eventTime: Long = 0,
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("going_count") val goingCount: Int = 0,
    @SerialName("maybe_count") val maybeCount: Int = 0,
    @SerialName("my_response") val myResponse: String = ""
)
