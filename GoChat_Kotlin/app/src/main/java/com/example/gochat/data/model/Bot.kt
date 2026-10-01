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

@Serializable
data class BotCommand(
    val command: String,
    val syntax: String,
    val description: String,
    val example: String,
    val template: String = "$command @"
) {
    companion object {
        val ALL_COMMANDS = listOf(
            BotCommand(
                command = "/ban",
                syntax = "/ban @user [reason]",
                description = "Permanently ban member from group",
                example = "e.g. /ban @john spam",
                template = "/ban @"
            ),
            BotCommand(
                command = "/kick",
                syntax = "/kick @user",
                description = "Remove a member from the group",
                example = "e.g. /kick @john",
                template = "/kick @"
            ),
            BotCommand(
                command = "/mute",
                syntax = "/mute @user [duration]",
                description = "Temporarily mute member (e.g. 10m, 1h, 1d)",
                example = "e.g. /mute @john 1h",
                template = "/mute @"
            ),
            BotCommand(
                command = "/unmute",
                syntax = "/unmute @user",
                description = "Restore member's ability to chat",
                example = "e.g. /unmute @john",
                template = "/unmute @"
            ),
            BotCommand(
                command = "/warn",
                syntax = "/warn @user [reason]",
                description = "Issue a formal moderation strike / warning",
                example = "e.g. /warn @john advertising",
                template = "/warn @"
            ),
            BotCommand(
                command = "/unban",
                syntax = "/unban @user",
                description = "Lift ban and allow user to rejoin",
                example = "e.g. /unban @john",
                template = "/unban @"
            ),
            BotCommand(
                command = "/slowmode",
                syntax = "/slowmode [seconds]",
                description = "Set message delay (e.g. 30s, 0 to disable)",
                example = "e.g. /slowmode 30s",
                template = "/slowmode 30s"
            ),
            BotCommand(
                command = "/rules",
                syntax = "/rules",
                description = "Display group rules and guidelines",
                example = "e.g. /rules",
                template = "/rules"
            ),
            BotCommand(
                command = "/help",
                syntax = "/help",
                description = "List all moderation commands & formats",
                example = "e.g. /help",
                template = "/help"
            )
        )
    }
}

