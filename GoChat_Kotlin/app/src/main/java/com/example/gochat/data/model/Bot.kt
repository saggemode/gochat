package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BotPermission {
    @SerialName("manage_messages") MANAGE_MESSAGES,
    @SerialName("warn_members") WARN_MEMBERS,
    @SerialName("mute_members") MUTE_MEMBERS,
    @SerialName("remove_members") REMOVE_MEMBERS,
    @SerialName("ban_members") BAN_MEMBERS,
    @SerialName("approve_members") APPROVE_MEMBERS,
    @SerialName("manage_invites") MANAGE_INVITES,
    @SerialName("manage_group_info") MANAGE_GROUP_INFO,
    @SerialName("pin_messages") PIN_MESSAGES,
    @SerialName("send_announcements") SEND_ANNOUNCEMENTS,
    @SerialName("manage_rules") MANAGE_RULES,
    @SerialName("manage_roles") MANAGE_ROLES,
    @SerialName("manage_links") MANAGE_LINKS,
    @SerialName("anti_spam") ANTI_SPAM,
    @SerialName("anti_flood") ANTI_FLOOD,
    @SerialName("anti_scam") ANTI_SCAM,
    @SerialName("keyword_filter") KEYWORD_FILTER,
    @SerialName("welcome_members") WELCOME_MEMBERS,
    @SerialName("goodbye_messages") GOODBYE_MESSAGES,
    @SerialName("auto_reply") AUTO_REPLY,
    @SerialName("poll_management") POLL_MANAGEMENT,
    @SerialName("event_management") EVENT_MANAGEMENT,
    @SerialName("member_statistics") MEMBER_STATISTICS,
    @SerialName("audit_logs") AUDIT_LOGS
}

@Serializable
data class BotConfig(
    @SerialName("bot_id") val botId: String,
    @SerialName("group_id") val groupId: String,
    val permissions: List<BotPermission> = emptyList(),
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("commands_prefix") val commandsPrefix: String = "/",
    @SerialName("rules") val rules: String = "",
    @SerialName("welcome_message") val welcomeMessage: String = "",
    @SerialName("spam_protection_level") val spamProtectionLevel: Int = 1 // 0: Off, 1: Low, 2: Medium, 3: High
)

@Serializable
data class BotAction(
    val id: String,
    @SerialName("bot_id") val botId: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("action_type") val actionType: String,
    @SerialName("target_user_id") val targetUserId: String?,
    @SerialName("reason") val reason: String?,
    @SerialName("timestamp") val timestamp: Long = System.currentTimeMillis()
)
