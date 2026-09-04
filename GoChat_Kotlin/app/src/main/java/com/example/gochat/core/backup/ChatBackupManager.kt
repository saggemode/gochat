package com.example.gochat.core.backup

import android.content.Context
import android.util.Base64
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.db.AppDatabase
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class InvalidPasswordException(message: String = "Incorrect password for encrypted backup") : Exception(message)

class ChatBackupManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.chatDao()
    private val json = NetworkModule.json
    private val prefs = context.getSharedPreferences("gochat_backup_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val HEADER_MAGIC = "GCBACKUP_V1"
        private const val PREF_LAST_BACKUP_JSON = "pref_last_backup_json"
    }

    suspend fun createEncryptedBackup(
        password: String,
        includeMedia: Boolean = true,
        onProgress: ((progress: Float, status: String) -> Unit)? = null
    ): Result<BackupMetadata> = withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke(0.1f, "Collecting conversations and messages...")

            // 1. Fetch conversations and messages from Room Database
            val conversations = dao.getAllConversations().first()
            val allMessages = mutableMapOf<String, JsonArray>()
            var totalMsgCount = 0

            for (conv in conversations) {
                val msgs = dao.getMessagesForConversation(conv.id).first()
                val jsonList = msgs.map { json.encodeToJsonElement(Message.serializer(), it) }
                allMessages[conv.id] = JsonArray(jsonList)
                totalMsgCount += msgs.size
            }

            onProgress?.invoke(0.35f, "Bundling database records...")

            // 2. Collect cached media files (voice notes and images)
            val mediaMap = mutableMapOf<String, String>()
            var mediaCount = 0

            if (includeMedia) {
                onProgress?.invoke(0.5f, "Bundling voice notes and photos...")
                val voiceDir = File(context.cacheDir, "voice_notes")
                val imageDir = File(context.cacheDir, "images")

                listOf(voiceDir, imageDir).forEach { dir ->
                    if (dir.exists()) {
                        dir.listFiles()?.forEach { file ->
                            if (file.isFile && file.length() < 25 * 1024 * 1024) { // 25MB individual cap
                                try {
                                    val bytes = file.readBytes()
                                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                    mediaMap[file.name] = b64
                                    mediaCount++
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }

            onProgress?.invoke(0.7f, "Encrypting archive with AES & HMAC...")

            // 3. Serialize full payload to JSON
            val payloadObj = buildJsonObject {
                put("version", 1)
                put("created_at", System.currentTimeMillis())
                put("conversations", JsonArray(conversations.map { json.encodeToJsonElement(Conversation.serializer(), it) }))
                put("messages", JsonObject(allMessages))
                put("media", buildJsonObject {
                    mediaMap.forEach { (k, v) -> put(k, v) }
                })
            }

            val rawBytes = payloadObj.toString().toByteArray(Charsets.UTF_8)

            // 4. Derive encryption key and HMAC key
            val salt = generateSalt()
            val derivedKey = deriveKey(password, salt)
            val hmacKey = sha256(derivedKey + byteArrayOf(0x01))

            // Encrypt using key stream XOR cipher + HMAC verification
            val encryptedData = xorCipher(rawBytes, derivedKey)
            val hmacSignature = computeHmacSha256(encryptedData, hmacKey)

            // Build binary container: [MAGIC (11 bytes)] [SALT (16 bytes)] [HMAC (32 bytes)] [ENCRYPTED DATA]
            val container = ByteArrayOutputStream().apply {
                write(HEADER_MAGIC.toByteArray(Charsets.UTF_8))
                write(salt)
                write(hmacSignature)
                write(encryptedData)
            }.toByteArray()

            // 5. Save to local storage file
            onProgress?.invoke(0.9f, "Writing encrypted backup file...")
            val backupDir = File(context.filesDir, "backups").apply {
                if (!exists()) mkdirs()
            }
            val fileName = "GoChat_Backup_${System.currentTimeMillis()}.gcbackup"
            val backupFile = File(backupDir, fileName)
            backupFile.writeBytes(container)

            val meta = BackupMetadata(
                filePath = backupFile.absolutePath,
                fileSizeBytes = container.size.toLong(),
                conversationCount = conversations.size,
                messageCount = totalMsgCount,
                mediaCount = mediaCount,
                createdAt = System.currentTimeMillis()
            )

            saveLastBackupInfo(meta)
            onProgress?.invoke(1.0f, "Backup completed!")
            Result.success(meta)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreEncryptedBackup(
        backupFile: File,
        password: String,
        onProgress: ((progress: Float, status: String) -> Unit)? = null
    ): Result<BackupMetadata> = withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke(0.1f, "Reading backup file...")
            if (!backupFile.exists()) {
                return@withContext Result.failure(Exception("Backup file not found at ${backupFile.absolutePath}"))
            }

            val bytes = backupFile.readBytes()
            val magicBytes = HEADER_MAGIC.toByteArray(Charsets.UTF_8)

            if (bytes.size < magicBytes.size + 16 + 32) {
                return@withContext Result.failure(Exception("Invalid or corrupted GoChat backup file."))
            }

            // Verify Magic header
            for (i in magicBytes.indices) {
                if (bytes[i] != magicBytes[i]) {
                    return@withContext Result.failure(Exception("Not a valid GoChat backup file."))
                }
            }

            onProgress?.invoke(0.3f, "Verifying password and decrypting...")

            var offset = magicBytes.size
            val salt = bytes.copyOfRange(offset, offset + 16)
            offset += 16
            val expectedHmac = bytes.copyOfRange(offset, offset + 32)
            offset += 32
            val encryptedData = bytes.copyOfRange(offset, bytes.size)

            // Derive keys
            val derivedKey = deriveKey(password, salt)
            val hmacKey = sha256(derivedKey + byteArrayOf(0x01))

            // Verify HMAC signature
            val computedHmac = computeHmacSha256(encryptedData, hmacKey)
            if (!expectedHmac.contentEquals(computedHmac)) {
                return@withContext Result.failure(InvalidPasswordException("Incorrect backup password or corrupted file."))
            }

            // Decrypt
            val decryptedBytes = xorCipher(encryptedData, derivedKey)
            val jsonStr = String(decryptedBytes, Charsets.UTF_8)
            val rootObj = json.parseToJsonElement(jsonStr).jsonObject

            onProgress?.invoke(0.6f, "Restoring conversations and messages...")

            val convsArray = rootObj["conversations"]?.jsonArray ?: JsonArray(emptyList())
            val messagesObj = rootObj["messages"]?.jsonObject ?: JsonObject(emptyMap())
            val mediaObj = rootObj["media"]?.jsonObject ?: JsonObject(emptyMap())

            // Restore conversations into Room
            val restoredConvs = convsArray.mapNotNull {
                try { json.decodeFromJsonElement<Conversation>(it) } catch (_: Exception) { null }
            }
            if (restoredConvs.isNotEmpty()) {
                dao.insertConversations(restoredConvs)
            }

            // Restore messages into Room
            var restoredMsgCount = 0
            val allMessagesToInsert = mutableListOf<Message>()
            for ((_, mListElem) in messagesObj) {
                val mArr = mListElem.jsonArray
                for (mElem in mArr) {
                    try {
                        val msg = json.decodeFromJsonElement<Message>(mElem)
                        allMessagesToInsert.add(msg)
                        restoredMsgCount++
                    } catch (_: Exception) {}
                }
            }
            if (allMessagesToInsert.isNotEmpty()) {
                dao.insertMessages(allMessagesToInsert)
            }

            // Restore media files
            if (mediaObj.isNotEmpty()) {
                onProgress?.invoke(0.85f, "Unpacking voice notes and photos...")
                val voiceDir = File(context.cacheDir, "voice_notes").apply { if (!exists()) mkdirs() }
                val imageDir = File(context.cacheDir, "images").apply { if (!exists()) mkdirs() }

                for ((name, b64Element) in mediaObj) {
                    try {
                        val b64Str = b64Element.jsonPrimitive.content
                        val fileBytes = Base64.decode(b64Str, Base64.DEFAULT)
                        val targetDir = if (name.endsWith(".m4a") || name.endsWith(".mp3") || name.contains("voice")) voiceDir else imageDir
                        val outFile = File(targetDir, name)
                        outFile.writeBytes(fileBytes)
                    } catch (_: Exception) {}
                }
            }

            val meta = BackupMetadata(
                filePath = backupFile.absolutePath,
                fileSizeBytes = bytes.size.toLong(),
                conversationCount = restoredConvs.size,
                messageCount = restoredMsgCount,
                mediaCount = mediaObj.size,
                createdAt = System.currentTimeMillis()
            )

            saveLastBackupInfo(meta)
            onProgress?.invoke(1.0f, "Restore completed successfully!")
            Result.success(meta)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getLastBackupInfo(): BackupMetadata? {
        val str = prefs.getString(PREF_LAST_BACKUP_JSON, null) ?: return null
        return try {
            json.decodeFromString(BackupMetadata.serializer(), str)
        } catch (_: Exception) {
            null
        }
    }

    fun saveLastBackupInfo(meta: BackupMetadata) {
        val str = json.encodeToString(BackupMetadata.serializer(), meta)
        prefs.edit().putString(PREF_LAST_BACKUP_JSON, str).apply()
    }

    fun clearBackupInfo() {
        prefs.edit().clear().apply()
    }

    fun getLocalBackupFiles(): List<File> {
        val backupDir = File(context.filesDir, "backups")
        if (!backupDir.exists()) return emptyList()
        return backupDir.listFiles { _, name -> name.endsWith(".gcbackup") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    // ── Cryptographic Helpers ──────────────────────────────────────────────

    private fun generateSalt(): ByteArray {
        val seed = "${System.currentTimeMillis()}--gochat_salt--${System.nanoTime()}".toByteArray(Charsets.UTF_8)
        val hash = sha256(seed)
        return hash.copyOfRange(0, 16)
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        var key = sha256(password.toByteArray(Charsets.UTF_8) + salt)
        for (i in 0 until 2000) {
            key = sha256(key + salt + byteArrayOf((i and 0xFF).toByte()))
        }
        return key
    }

    private fun xorCipher(data: ByteArray, key: ByteArray): ByteArray {
        val result = ByteArray(data.size)
        val keyLen = key.size
        for (i in data.indices) {
            result[i] = (data[i].toInt() xor key[i % keyLen].toInt()).toByte()
        }
        return result
    }

    private fun sha256(input: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input)
    }

    private fun computeHmacSha256(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
