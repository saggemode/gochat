package com.example.gochat.core.backup

import android.content.Context
import android.util.Base64
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.db.AppDatabase
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.Message
import com.example.gochat.data.db.ChatDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

class InvalidPasswordException(message: String = "Incorrect password for encrypted backup") : Exception(message)

@Singleton
class ChatBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ChatDao,
    private val json: Json
) {

    private val prefs = context.getSharedPreferences("gochat_backup_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val HEADER_MAGIC = "GCBACKUP_V1"      // legacy (repeating-XOR); read-only
        private const val HEADER_MAGIC_V2 = "GCBACKUP_V2"   // AES-256-GCM + PBKDF2 (both 11 bytes)
        private const val PBKDF2_ITERATIONS = 120_000
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_BITS = 128
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

            // 4. Encrypt with AES-256-GCM (authenticated) using a PBKDF2-derived key.
            onProgress?.invoke(0.8f, "Encrypting archive (AES-256-GCM)...")
            val salt = generateSalt()
            val key = deriveKeyPbkdf2(password, salt)
            val iv = ByteArray(GCM_IV_LEN).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            val encryptedData = cipher.doFinal(rawBytes) // ciphertext || 16-byte GCM tag

            // Build binary container: [MAGIC_V2 (11)] [SALT (16)] [IV (12)] [GCM ciphertext+tag]
            val container = ByteArrayOutputStream().apply {
                write(HEADER_MAGIC_V2.toByteArray(Charsets.UTF_8))
                write(salt)
                write(iv)
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
            val magicV1 = HEADER_MAGIC.toByteArray(Charsets.UTF_8)
            val magicV2 = HEADER_MAGIC_V2.toByteArray(Charsets.UTF_8)
            val magicLen = magicV2.size // both magics are the same length

            if (bytes.size < magicLen + 16 + 16) {
                return@withContext Result.failure(Exception("Invalid or corrupted GoChat backup file."))
            }
            val header = bytes.copyOfRange(0, magicLen)
            val isV2 = header.contentEquals(magicV2)
            val isV1 = header.contentEquals(magicV1)
            if (!isV2 && !isV1) {
                return@withContext Result.failure(Exception("Not a valid GoChat backup file."))
            }

            onProgress?.invoke(0.3f, "Verifying password and decrypting...")

            val decryptedBytes: ByteArray = if (isV2) {
                // v2: [MAGIC][SALT 16][IV 12][AES-GCM ciphertext+tag]
                var offset = magicLen
                val salt = bytes.copyOfRange(offset, offset + 16); offset += 16
                val iv = bytes.copyOfRange(offset, offset + GCM_IV_LEN); offset += GCM_IV_LEN
                val ciphertext = bytes.copyOfRange(offset, bytes.size)
                val key = deriveKeyPbkdf2(password, salt)
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                    cipher.doFinal(ciphertext) // throws AEADBadTagException on wrong password / tampering
                } catch (e: javax.crypto.AEADBadTagException) {
                    return@withContext Result.failure(InvalidPasswordException("Incorrect backup password or corrupted file."))
                }
            } else {
                // v1 legacy: [MAGIC][SALT 16][HMAC 32][XOR data] — read-only for old backups.
                var offset = magicLen
                val salt = bytes.copyOfRange(offset, offset + 16); offset += 16
                val expectedHmac = bytes.copyOfRange(offset, offset + 32); offset += 32
                val encryptedData = bytes.copyOfRange(offset, bytes.size)
                val derivedKey = deriveKey(password, salt)
                val hmacKey = sha256(derivedKey + byteArrayOf(0x01))
                if (!expectedHmac.contentEquals(computeHmacSha256(encryptedData, hmacKey))) {
                    return@withContext Result.failure(InvalidPasswordException("Incorrect backup password or corrupted file."))
                }
                xorCipher(encryptedData, derivedKey)
            }

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

    // AES-256 key via PBKDF2-HMAC-SHA256 (used by the v2 backup format).
    private fun deriveKeyPbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    // Legacy v1 KDF — kept only to decrypt old backups.
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
