package com.example.gochat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a user blocked by the current user.
 * Stored locally in Room for instant filtering and also synced with the backend.
 */
@Serializable
@Entity(tableName = "blocked_users")
data class BlockedUser(
    @PrimaryKey
    @SerialName("user_id") val userId: String,
    @SerialName("user_name") val userName: String = "",
    @SerialName("blocked_at") val blockedAt: Long = System.currentTimeMillis()
)

/**
 * Reasons a user can be reported — maps to WhatsApp-style report categories.
 */
enum class ReportReason(val displayLabel: String, val apiValue: String) {
    SPAM("Spam", "spam"),
    SCAM("Scam or fraud", "scam"),
    HARASSMENT("Harassment or bullying", "harassment"),
    IMPERSONATION("Impersonation", "impersonation"),
    INAPPROPRIATE_CONTENT("Inappropriate content", "inappropriate_content"),
    THREAT("Threats or violence", "threat"),
    OTHER("Something else", "other");
}

@Serializable
data class ReportRequest(
    @SerialName("reported_user_id") val reportedUserId: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("reason") val reason: String,
    @SerialName("details") val details: String = "",
    @SerialName("evidence_msg_ids") val evidenceMsgIds: List<String> = emptyList()
)
