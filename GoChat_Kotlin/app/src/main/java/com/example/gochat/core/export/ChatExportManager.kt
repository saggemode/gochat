package com.example.gochat.core.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.example.gochat.data.api.ApiConstants
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.MessageType
import com.example.gochat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Handles exporting chat conversations into human-readable text files (_chat.txt)
 * and optional ZIP packages containing media files, replicating WhatsApp's "Export Chat"
 * feature with support for:
 * 1. Export without media (.txt)
 * 2. Export with media (.zip)
 * 3. Export selected messages
 */
class ChatExportManager(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val tokenManager: TokenManager
) {

    companion object {
        private const val TAG = "ChatExportManager"
        private const val EXPORT_FOLDER = "exports"

        /**
         * Cleans up stale export files in the cache older than 24 hours.
         */
        fun cleanOldExports(context: Context) {
            try {
                val dir = File(context.cacheDir, EXPORT_FOLDER)
                if (dir.exists()) {
                    val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
                    dir.listFiles()?.forEach { file ->
                        if (file.lastModified() < oneDayAgo) {
                            file.deleteRecursively()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean old exports: ${e.message}")
            }
        }

        /**
         * Launches the standard Android Share Sheet for an exported chat.
         */
        fun shareExport(
            context: Context,
            result: ExportResult,
            conversationTitle: String
        ) {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                result.file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = result.mimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "GoChat Chat - $conversationTitle")
                val mediaInfo = if (result.mediaCount > 0) " (${result.mediaCount} media files included)" else ""
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Here is the exported chat with $conversationTitle ($mediaInfo)"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Export chat via").apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(chooser)
        }
    }

    data class ExportResult(
        val file: File,
        val mimeType: String,
        val messageCount: Int,
        val mediaCount: Int,
        val isZip: Boolean
    )

    private val okHttpClient: OkHttpClient by lazy {
        NetworkModule.getOkHttpClient(context)
    }

    private val messageDateFormat = SimpleDateFormat("dd/MM/yyyy, HH:mm", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Executes the export workflow.
     *
     * @param conversationId The ID of the conversation.
     * @param conversationTitle The name/title of the conversation.
     * @param includeMedia Whether to package media files along with the chat transcript.
     * @param targetMessages Optional list of specific messages to export. If null, queries all messages from DB.
     * @param onProgress Callback receiving progress percent (0..100) and user-facing status.
     */
    suspend fun exportChat(
        conversationId: String,
        conversationTitle: String,
        includeMedia: Boolean,
        targetMessages: List<Message>? = null,
        onProgress: ((progress: Int, status: String) -> Unit)? = null
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        try {
            cleanOldExports(context)

            onProgress?.invoke(5, "Fetching messages...")
            val messages = targetMessages ?: chatRepository.getMessagesForExport(conversationId)

            if (messages.isEmpty()) {
                return@withContext Result.failure(Exception("No messages found to export"))
            }

            val safeTitle = conversationTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30)
            val timestampStr = fileDateFormat.format(Date())

            val exportDir = File(context.cacheDir, EXPORT_FOLDER).apply {
                if (!exists()) mkdirs()
            }

            val currentUserName = tokenManager.userDisplayName?.takeIf { it.isNotBlank() } ?: "You"

            if (!includeMedia) {
                // ── Simple Text Export (.txt) ─────────────────────────
                onProgress?.invoke(25, "Formatting chat transcript...")
                val txtFile = File(exportDir, "GoChat_Chat_${safeTitle}_$timestampStr.txt")

                val transcript = buildTranscript(
                    title = conversationTitle,
                    messages = messages,
                    includeMedia = false,
                    currentUserName = currentUserName,
                    mediaMap = emptyMap()
                )

                txtFile.writeText(transcript, Charsets.UTF_8)
                onProgress?.invoke(100, "Export complete")

                Result.success(
                    ExportResult(
                        file = txtFile,
                        mimeType = "text/plain",
                        messageCount = messages.size,
                        mediaCount = 0,
                        isZip = false
                    )
                )
            } else {
                // ── Full Media Package Export (.zip) ───────────────────
                onProgress?.invoke(15, "Scanning media files...")
                val tempFolder = File(exportDir, "temp_export_${safeTitle}_${System.currentTimeMillis()}").apply {
                    if (!exists()) mkdirs()
                }

                // Filter messages that have media
                val mediaMessages = messages.filter { msg ->
                    !msg.isDeleted && !msg.isViewOnce && !msg.mediaUrl.isNullOrBlank() &&
                            (msg.type == MessageType.IMAGE || msg.type == MessageType.VIDEO ||
                                    msg.type == MessageType.AUDIO || msg.type == MessageType.VOICE ||
                                    msg.type == MessageType.FILE || msg.type == MessageType.STICKER)
                }

                val mediaMap = mutableMapOf<String, String>() // messageId -> saved filename
                var mediaSuccessCount = 0

                mediaMessages.forEachIndexed { index, msg ->
                    val progressPercent = 20 + ((index.toFloat() / mediaMessages.size.coerceAtLeast(1)) * 60).toInt()
                    onProgress?.invoke(
                        progressPercent,
                        "Gathering media (${index + 1} of ${mediaMessages.size})..."
                    )

                    val filename = generateMediaFilename(msg, index)
                    val destinationFile = File(tempFolder, filename)

                    val saved = fetchAndSaveMedia(msg.mediaUrl.orEmpty(), destinationFile)
                    if (saved && destinationFile.exists() && destinationFile.length() > 0) {
                        mediaMap[msg.id] = filename
                        mediaSuccessCount++
                    }
                }

                onProgress?.invoke(85, "Generating chat transcript...")
                val transcript = buildTranscript(
                    title = conversationTitle,
                    messages = messages,
                    includeMedia = true,
                    currentUserName = currentUserName,
                    mediaMap = mediaMap
                )

                val chatTxtFile = File(tempFolder, "_chat.txt")
                chatTxtFile.writeText(transcript, Charsets.UTF_8)

                onProgress?.invoke(90, "Creating ZIP archive...")
                val zipFile = File(exportDir, "GoChat_Export_${safeTitle}_$timestampStr.zip")
                zipFolder(tempFolder, zipFile)

                // Clean up temporary uncompressed files
                tempFolder.deleteRecursively()

                onProgress?.invoke(100, "Export ready!")
                Result.success(
                    ExportResult(
                        file = zipFile,
                        mimeType = "application/zip",
                        messageCount = messages.size,
                        mediaCount = mediaSuccessCount,
                        isZip = true
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting chat: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Builds the WhatsApp-standard formatted conversation transcript.
     */
    private fun buildTranscript(
        title: String,
        messages: List<Message>,
        includeMedia: Boolean,
        currentUserName: String,
        mediaMap: Map<String, String>
    ): String {
        val sb = StringBuilder()

        // WhatsApp / GoChat Export Header
        sb.append("============================================================\n")
        sb.append("GoChat Conversation Export: $title\n")
        sb.append("Export Date: ${messageDateFormat.format(Date())}\n")
        sb.append("Messages: ${messages.size}\n")
        sb.append("============================================================\n\n")

        messages.forEach { msg ->
            val dateStr = messageDateFormat.format(Date(msg.createdAt))

            // 1. System messages
            if (msg.type == MessageType.SYSTEM ||
                msg.senderName.equals("System", ignoreCase = true) ||
                msg.content.startsWith("SYSTEM:", ignoreCase = true)
            ) {
                val clean = msg.content.removePrefix("SYSTEM:").removePrefix("system:").trim()
                sb.append("$dateStr - $clean\n")
                return@forEach
            }

            // 2. Sender Name
            val sender = if (msg.isMe) currentUserName else msg.senderName.ifBlank { "Unknown" }

            // 3. Message Body formatting
            val body = when {
                msg.isDeleted -> "<This message was deleted>"
                msg.isViewOnce -> "<View Once media expired/omitted>"
                msg.type == MessageType.TEXT -> {
                    var text = msg.content
                    if (msg.isEdited) {
                        text += " <This message was edited>"
                    }
                    text
                }
                msg.type == MessageType.IMAGE -> {
                    val mediaFile = mediaMap[msg.id]
                    val label = if (includeMedia && mediaFile != null) "[Image: $mediaFile]" else "<Media omitted>"
                    if (msg.content.isNotBlank()) "$label ${msg.content}" else label
                }
                msg.type == MessageType.VIDEO -> {
                    val mediaFile = mediaMap[msg.id]
                    val label = if (includeMedia && mediaFile != null) "[Video: $mediaFile]" else "<Media omitted>"
                    if (msg.content.isNotBlank()) "$label ${msg.content}" else label
                }
                msg.type == MessageType.AUDIO || msg.type == MessageType.VOICE -> {
                    val mediaFile = mediaMap[msg.id]
                    if (includeMedia && mediaFile != null) "[Voice Note: $mediaFile]" else "<Media omitted>"
                }
                msg.type == MessageType.STICKER -> {
                    val mediaFile = mediaMap[msg.id]
                    if (includeMedia && mediaFile != null) "[Sticker: $mediaFile]" else "<Media omitted>"
                }
                msg.type == MessageType.FILE -> {
                    val mediaFile = mediaMap[msg.id]
                    val label = if (includeMedia && mediaFile != null) "[Document: $mediaFile]" else "<Media omitted>"
                    if (msg.content.isNotBlank() && !msg.content.equals(mediaFile, ignoreCase = true)) {
                        "$label (${msg.content})"
                    } else label
                }
                msg.type == MessageType.LOCATION -> "[Location: ${msg.content}]"
                msg.type == MessageType.CONTACT -> "[Contact: ${msg.content}]"
                msg.type == MessageType.POLL -> "[Poll: ${msg.content}]"
                msg.type == MessageType.PRODUCT -> "[Product: ${msg.content}]"
                msg.type == MessageType.ORDER -> "[Order: ${msg.content}]"
                msg.type == MessageType.PAYMENT_REQUEST -> "[Payment Request: ${msg.content}]"
                msg.type == MessageType.PING -> "💥 PING!!!"
                else -> msg.content.ifBlank { "<Media omitted>" }
            }

            // Reactions (if present)
            val reactionsPart = if (msg.reactions.isNotEmpty()) {
                val emojis = msg.reactions.joinToString(" ") { it.emoji }
                " (Reactions: $emojis)"
            } else ""

            sb.append("$dateStr - $sender: $body$reactionsPart\n")
        }

        return sb.toString()
    }

    /**
     * Determines a clean WhatsApp-style media filename based on type and timestamp.
     */
    private fun generateMediaFilename(message: Message, index: Int): String {
        val dateStamp = fileDateFormat.format(Date(message.createdAt))
        val idxStr = String.format(Locale.US, "%04d", index + 1)
        val url = message.mediaUrl.orEmpty()

        return when (message.type) {
            MessageType.IMAGE -> "IMG_${dateStamp}_$idxStr.jpg"
            MessageType.VIDEO -> "VID_${dateStamp}_$idxStr.mp4"
            MessageType.AUDIO, MessageType.VOICE -> "AUD_${dateStamp}_$idxStr.opus"
            MessageType.STICKER -> "STK_${dateStamp}_$idxStr.webp"
            MessageType.FILE -> {
                val ext = url.substringAfterLast('.', "").takeIf { it.length in 2..5 && !it.contains('/') }
                    ?: "pdf"
                val base = message.content.takeIf { it.isNotBlank() && !it.contains("/") && !it.contains("\\") }
                    ?.take(25) ?: "DOC_${dateStamp}_$idxStr"
                if (base.endsWith(".$ext", ignoreCase = true)) base else "$base.$ext"
            }
            else -> "MEDIA_${dateStamp}_$idxStr.bin"
        }
    }

    /**
     * Saves media to the target destination file: handles base64 data URIs, local files,
     * content URIs, and remote network URLs.
     */
    private fun fetchAndSaveMedia(mediaUrl: String, destFile: File): Boolean {
        return try {
            val clean = mediaUrl.trim()
            if (clean.isBlank()) return false

            // 1. Base64 Data URI
            if (clean.startsWith("data:") || (clean.length > 200 && !clean.contains(" ") &&
                        (clean.startsWith("/9j/") || clean.startsWith("iVBOR") || clean.startsWith("R0lGOD") || clean.startsWith("UklGR")))) {
                val b64 = if (clean.startsWith("data:")) clean.substringAfter(";base64,").trim() else clean
                val bytes = try {
                    Base64.decode(b64, Base64.DEFAULT)
                } catch (_: Exception) {
                    Base64.decode(b64, Base64.NO_WRAP)
                }
                FileOutputStream(destFile).use { it.write(bytes) }
                return true
            }

            // 2. Local File or Device Path
            if (clean.startsWith("file://") || (clean.startsWith("/") && !clean.startsWith("/api/"))) {
                val localPath = clean.removePrefix("file://")
                val localFile = File(localPath)
                if (localFile.exists()) {
                    localFile.copyTo(destFile, overwrite = true)
                    return true
                }
            }

            // 3. Android Content URI
            if (clean.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(clean))?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                return destFile.exists() && destFile.length() > 0
            }

            // 4. Remote HTTP/HTTPS / API URL
            var finalUrl = when {
                clean.startsWith("http://") || clean.startsWith("https://") -> clean
                clean.startsWith("/") -> "${ApiConstants.BASE_URL.removeSuffix("/")}$clean"
                clean.startsWith("api/") -> "${ApiConstants.BASE_URL.removeSuffix("/")}/$clean"
                clean.startsWith("media/") -> "${ApiConstants.BASE_URL.removeSuffix("/")}/api/v1/$clean"
                else -> clean
            }

            // Remap localhost for emulator
            if (finalUrl.contains("localhost:9000") || finalUrl.contains("127.0.0.1:9000") || finalUrl.contains("minio:9000")) {
                finalUrl = finalUrl.replace("localhost:9000", "10.0.2.2:9000")
                    .replace("127.0.0.1:9000", "10.0.2.2:9000")
                    .replace("minio:9000", "10.0.2.2:9000")
            }

            val request = Request.Builder().url(finalUrl).build()
            val response = okHttpClient.newCall(request).execute()

            if (response.isSuccessful && response.body != null) {
                response.body!!.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                return true
            } else {
                Log.w(TAG, "Failed downloading media ($finalUrl): HTTP ${response.code}")
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception saving media for export ($mediaUrl): ${e.message}")
            false
        }
    }

    /**
     * Packages all files inside [folder] into a single [zipFile].
     */
    private fun zipFolder(folder: File, zipFile: File) {
        val files = folder.listFiles() ?: return
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            val buffer = ByteArray(8192)
            for (file in files) {
                if (file.isFile) {
                    val entry = ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { fis ->
                        var count: Int
                        while (fis.read(buffer).also { count = it } != -1) {
                            zos.write(buffer, 0, count)
                        }
                    }
                    zos.closeEntry()
                }
            }
        }
    }
}
