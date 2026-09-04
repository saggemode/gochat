package com.example.gochat.core.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BackupMetadata(
    @SerialName("file_path") val filePath: String = "",
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0L,
    @SerialName("conversation_count") val conversationCount: Int = 0,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("media_count") val mediaCount: Int = 0,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis(),
    @SerialName("is_cloud_backup") val isCloudBackup: Boolean = false,
    @SerialName("cloud_file_id") val cloudFileId: String? = null,
    @SerialName("account_email") val accountEmail: String? = null
) {
    val formattedSize: String
        get() {
            if (fileSizeBytes < 1024) return "$fileSizeBytes B"
            if (fileSizeBytes < 1024 * 1024) return String.format("%.1f KB", fileSizeBytes / 1024.0)
            return String.format("%.1f MB", fileSizeBytes / (1024.0 * 1024.0))
        }
}
