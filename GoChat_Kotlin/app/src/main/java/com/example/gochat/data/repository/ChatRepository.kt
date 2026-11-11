package com.example.gochat.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.gochat.data.api.GoChatApiService

import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.db.ChatDao
import com.example.gochat.data.model.*
import com.example.gochat.core.contacts.ContactSyncManager
import com.example.gochat.core.crypto.EncryptionManager
import com.example.gochat.core.crypto.E2EESettings
import com.example.gochat.core.notification.NotificationHelper
import com.example.gochat.data.api.ApiConstants
import androidx.work.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.gochat.core.sync.MessageSyncWorker
import com.example.gochat.core.utils.BlurHashUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central repository for conversations, messages, and real-time event processing.
 * Combines Retrofit API, Room DB (offline-first), and WebSocket event handling.
 */
@Singleton
class ChatRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GoChatApiService,
    private val dao: ChatDao,
    private val tokenManager: TokenManager,
    private val encryptionManager: EncryptionManager,
    private val workManager: WorkManager,
    private val contactSyncManager: ContactSyncManager
) {

    fun getMessagesPaged(convId: String): Flow<PagingData<Message>> {
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = false,
                initialLoadSize = 50
            ),
            pagingSourceFactory = { dao.getMessagesForConversationPaged(convId) }
        ).flow
    }

    fun getConversationsPaged(): Flow<PagingData<Conversation>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { dao.getAllConversationsPaged() }
        ).flow
    }

    /** The ID of the conversation the user is currently viewing (null = chat list). */
    var activeConversationId: String?
        get() = activeConversationIdStatic
        set(value) { activeConversationIdStatic = value }

    companion object {
        /** Global tracking of the active conversation to avoid new instances losing state. */
        var activeConversationIdStatic: String? = null
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Conversations ────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    fun observeConversations(): Flow<List<Conversation>> = dao.getAllConversations()

    suspend fun getAllConversationsList(): List<Conversation> = dao.getAllConversationsList()

    suspend fun getConversationById(id: String): Conversation? = dao.getConversationById(id)

    suspend fun insertConversationLocally(conv: Conversation) = dao.insertConversation(conv)

    suspend fun fetchConversation(convId: String): Result<Conversation> {
        return try {
            val response = api.getConversation(convId)
            if (response.isSuccessful) {
                val body = response.body() ?: return Result.failure(Exception("Empty body"))
                val convObj = body["conversation"]?.jsonObject ?: body
                val isOnline = (body["is_online"] ?: body["isOnline"])?.jsonPrimitive?.booleanOrNull ?: false
                val lastSeen = (body["last_seen"] ?: body["lastSeen"])?.jsonPrimitive?.longOrNull
                val currentUserId = tokenManager.userId ?: ""
                val existingLocal = dao.getConversationById(convId)
                val hasRemoteScreenshot = convObj.containsKey("screenshot_notifications_enabled") || convObj.containsKey("screenshotNotificationsEnabled")
                val hasRemoteMuted = convObj.containsKey("is_muted") || convObj.containsKey("isMuted") || convObj.containsKey("IsMuted")
                val parsedConv = Conversation.fromJson(convObj, currentUserId)
                val conv = parsedConv.copy(
                    isOnline = isOnline,
                    lastSeen = lastSeen,
                    screenshotNotificationsEnabled = if (hasRemoteScreenshot) parsedConv.screenshotNotificationsEnabled else (existingLocal?.screenshotNotificationsEnabled ?: parsedConv.screenshotNotificationsEnabled),
                    isMuted = if (hasRemoteMuted) parsedConv.isMuted else (existingLocal?.isMuted ?: parsedConv.isMuted)
                )
                dao.insertConversation(conv)
                Result.success(conv)
            } else {
                Result.failure(Exception("Failed to fetch conversation (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshConversations(): Result<List<Conversation>> {
        return try {
            val response = api.getConversations()
            if (response.isSuccessful) {
                val body = response.body()
                val rawList = when (body) {
                    is JsonArray -> body
                    is JsonObject -> body["conversations"]?.jsonArray ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
                val currentUserId = tokenManager.userId ?: ""
                val existingMap = dao.getAllConversationsList().associateBy { it.id }
                val conversations = rawList.mapNotNull {
                    if (it is JsonObject) {
                        val parsed = Conversation.fromJson(it, currentUserId)
                        val existing = existingMap[parsed.id]
                        val hasRemoteScreenshot = it.containsKey("screenshot_notifications_enabled") || it.containsKey("screenshotNotificationsEnabled")
                        val hasRemoteMuted = it.containsKey("is_muted") || it.containsKey("isMuted") || it.containsKey("IsMuted")
                        val preservedScreenshot = if (!hasRemoteScreenshot && existing != null) existing.screenshotNotificationsEnabled else parsed.screenshotNotificationsEnabled
                        val preservedMuted = if (!hasRemoteMuted && existing != null) existing.isMuted else parsed.isMuted
                        if (preservedScreenshot != parsed.screenshotNotificationsEnabled || preservedMuted != parsed.isMuted) {
                            parsed.copy(screenshotNotificationsEnabled = preservedScreenshot, isMuted = preservedMuted)
                        } else {
                            parsed
                        }
                    } else null
                }
                if (conversations.isNotEmpty()) {
                    dao.insertConversations(conversations)
                }
                Result.success(conversations)
            } else {
                Result.failure(Exception("Failed to fetch conversations (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createConversation(
        name: String,
        memberIds: List<String>,
        isGroup: Boolean = false
    ): Result<Conversation> {
        return try {
            val body = buildJsonObject {
                put("name", name)
                put("member_ids", JsonArray(memberIds.map { JsonPrimitive(it) }))
                put("type", if (isGroup) 1 else 0)
                put("is_group", isGroup)
            }
            val response = api.createConversation(body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val convJson = data["conversation"]?.jsonObject ?: data
                val conv = Conversation.fromJson(convJson, tokenManager.userId ?: "")
                dao.insertConversation(conv)
                // E2EE migration: new chats are end-to-end encrypted by default —
                // 1:1 via pairwise Signal sessions, groups via sender keys
                // (existing chats keep their current setting).
                com.example.gochat.core.crypto.E2EESettings.setEnabled(context, conv.id, true)
                Result.success(conv)
            } else {
                Result.failure(Exception("Failed to create conversation (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markConversationAsRead(convId: String) {
        dao.markConversationAsRead(convId)
        dao.markIncomingMessagesAsRead(convId)
    }

    suspend fun markOutgoingMessagesAsRead(convId: String) {
        dao.markOutgoingMessagesAsRead(convId)
    }

    suspend fun deleteConversation(convId: String) {
        dao.deleteConversation(convId)
        dao.clearMessagesForConversation(convId)
    }

    suspend fun toggleScreenshotNotifications(convId: String, enabled: Boolean): Result<Unit> {
        return try {
            dao.updateScreenshotNotifications(convId, enabled)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setConversationMuted(convId: String, isMuted: Boolean): Result<Unit> {
        return try {
            dao.updateConversationMuted(convId, isMuted)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Drafts ──────────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    suspend fun saveDraft(convId: String, text: String?) {
        val draft = text?.trim()?.ifBlank { null }
        dao.updateDraft(convId, draft)
    }

    suspend fun loadDraft(convId: String): String? = dao.getDraft(convId)

    suspend fun clearDraft(convId: String) = dao.updateDraft(convId, null)

    // ═══════════════════════════════════════════════════════════════
    // ── Messages ─────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    fun observeMessages(convId: String): Flow<List<Message>> =
        dao.getMessagesForConversation(convId)

    suspend fun searchMessagesInConversation(convId: String, query: String): List<Message> {
        if (convId.isBlank() || query.isBlank()) return emptyList()
        return dao.searchMessagesInConversation(convId, query.trim())
    }

    suspend fun searchAllMessages(query: String): List<Message> {
        if (query.isBlank()) return emptyList()
        return dao.searchAllMessages(query.trim())
    }

    fun observeSharedMedia(convId: String): Flow<List<Message>> {
        return dao.getMediaMessagesForConversation(convId)
    }

    suspend fun refreshMessages(convId: String): Result<List<Message>> {
        return try {
            dao.deleteExpiredMessages(System.currentTimeMillis())
            val response = api.getMessages(convId)
            if (response.isSuccessful) {
                val body = response.body()
                val rawList = when (body) {
                    is JsonArray -> body
                    is JsonObject -> body["messages"]?.jsonArray ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
                val currentUserId = tokenManager.userId ?: ""
                val conv = dao.getConversationById(convId)
                val clearedAt = conv?.clearedAt ?: 0L
                // Explicit loop (not mapNotNull) so we can call the suspend decryptors:
                // the HTTP sync path must decrypt text (1:1 + group) AND media, the same
                // way the live WebSocket path does — otherwise anything received while the
                // app was closed stays encrypted / shows a placeholder until re-received.
                val messages = mutableListOf<Message>()
                val healTargets = mutableSetOf<Pair<String, String>>() // (convId, senderId) that stayed encrypted
                for (element in rawList) {
                    if (element !is JsonObject) continue
                    // Serialize decrypt+store with the live WS paths so each message is
                    // decrypted exactly once on the shared Signal ratchet. withLock is
                    // inline, so return@withLock just skips this element.
                    messageIngestMutex.withLock {
                    val msg = Message.fromJson(element, currentUserId)

                    // Group sender-key distribution control messages are never displayed;
                    // process any addressed to us so a missed live SKD still lands on sync.
                    if (msg.content.startsWith(groupSkdMarker)) {
                        if (!msg.isMe) handleGroupKeyDistribution(msg)
                        return@withLock
                    }
                    // Session-reset request (self-heal), never displayed. Process once —
                    // re-running it on every refresh would re-delete the session.
                    if (msg.content.startsWith(sessionResetMarker)) {
                        if (!msg.isMe && claimControlMessage(msg.id)) handleSessionReset(msg.senderId)
                        return@withLock
                    }
                    // Disappearing-messages timer change, never displayed as a bubble.
                    // Process once so a refresh doesn't re-insert the timer system message.
                    if (msg.content.startsWith(dmTimerMarker)) {
                        if (!msg.isMe && claimControlMessage(msg.id)) handleDisappearingTimerChange(msg.conversationId, msg.content.removePrefix(dmTimerMarker).trim().toIntOrNull() ?: 0)
                        return@withLock
                    }

                    val localExisting = dao.getMessageById(msg.id)
                    val isDeletedForMe = (localExisting?.deletedForMe == true) ||
                            (clearedAt > 0L && msg.createdAt <= clearedAt && !msg.isStarred && localExisting?.isStarred != true)
                    val isDeleted = (localExisting?.isDeleted == true) || msg.isDeleted
                    val isStarred = if (isDeleted) false else (localExisting?.isStarred ?: msg.isStarred)
                    val isPinned = if (isDeleted) false else (localExisting?.isPinned ?: msg.isPinned)

                    val hasGoodLocalText = localExisting != null &&
                            localExisting.content.isNotBlank() && !isBase64Ciphertext(localExisting.content)
                    val localMediaGood = localExisting?.mediaUrl?.let { it.isNotBlank() && !it.startsWith("http") } == true

                    // Decrypt received text + media — but ONLY if we don't already hold the
                    // decrypted copy. A Signal session is a stateful ratchet; decrypting the
                    // SAME ciphertext again (on every sync) desyncs it and breaks all later
                    // messages ("first message shows, rest encrypted"). Decrypt exactly once.
                    var decrypted = msg
                    if (!isDeleted && !msg.isMe) {
                        if (!hasGoodLocalText) {
                            decrypted = if (conv != null && !conv.isDirect) tryDecryptGroupMessage(msg) else tryDecryptMessage(msg)
                        }
                        if (!localMediaGood) {
                            decrypted = decryptIncomingMedia(decrypted)
                        }
                    }

                    val contentToUse = if (isDeleted) {
                        "This message was deleted"
                    } else if (hasGoodLocalText) {
                        localExisting!!.content
                    } else if (localMediaGood) {
                        // Already-decrypted media: its stored content is "" (the key was
                        // consumed). Keep that — do NOT fall through and re-show the key
                        // ciphertext as "[Encrypted Message]".
                        localExisting!!.content
                    } else if (msg.isMe) {
                        // We can't decrypt our own outgoing 1:1 ciphertext (it's encrypted
                        // to the recipient). Without a local plaintext copy it's unrecoverable,
                        // so show blank rather than gibberish.
                        if (isBase64Ciphertext(msg.content.trim())) "" else msg.content
                    } else {
                        decrypted.content
                    }

                    // Prefer an already-decrypted local media file (a non-http path).
                    val mediaToUse = if (isDeleted) null
                        else if (localMediaGood) localExisting!!.mediaUrl
                        else decrypted.mediaUrl

                    // Preserve the highest message status – never downgrade
                    // (e.g. local READ must not revert to server's SENT)
                    // Priority: SENDING(0) < FAILED(1) < SENT(2) < DELIVERED(3) < READ(4)
                    val mergedStatus = if (localExisting != null) {
                        val priority = mapOf(
                            MessageStatus.SENDING to 0,
                            MessageStatus.FAILED to 1,
                            MessageStatus.SENT to 2,
                            MessageStatus.DELIVERED to 3,
                            MessageStatus.READ to 4
                        )
                        val localPri = priority[localExisting.status] ?: 0
                        val serverPri = priority[msg.status] ?: 0
                        if (localPri >= serverPri) localExisting.status else msg.status
                    } else {
                        msg.status
                    }

                    messages.add(
                        msg.copy(
                            content = contentToUse,
                            status = mergedStatus,
                            isDeleted = isDeleted,
                            mediaUrl = mediaToUse,
                            mediaThumbnail = if (isDeleted) null else msg.mediaThumbnail,
                            deletedForMe = isDeletedForMe,
                            isStarred = isStarred,
                            isPinned = isPinned
                        )
                    )
                    // Flag received 1:1 messages that stayed encrypted for self-heal.
                    if (!msg.isMe && conv?.isDirect == true && msg.type == MessageType.TEXT &&
                        isBase64Ciphertext(contentToUse.trim())) {
                        healTargets.add(msg.conversationId to msg.senderId)
                    }
                    }
                }
                dao.insertMessages(messages)
                // Ask senders of still-encrypted 1:1 messages to re-establish (throttled),
                // outside the ingest lock so we don't hold it during the network call.
                for ((convIdH, senderIdH) in healTargets) {
                    requestSessionReset(convIdH, senderIdH)
                }
                Result.success(messages)
            } else {
                Result.failure(Exception("Failed to fetch messages (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMessagesForExport(convId: String): List<Message> = withContext(Dispatchers.IO) {
        dao.getMessagesForExport(convId)
    }

    suspend fun getMessagesByIds(messageIds: List<String>): List<Message> = withContext(Dispatchers.IO) {
        dao.getMessagesByIds(messageIds)
    }

    suspend fun sendMessage(
        conversationId: String,
        content: String,
        type: Int = 0,
        mediaUrl: String? = null,
        telegramFileId: String? = null,
        mediaThumbnail: String? = null,
        replyToId: String? = null,
        replyToText: String? = null,
        replyToSenderName: String? = null,
        replyToMediaUrl: String? = null,
        replyToMediaType: String? = null,
        mentionedUserIds: List<String> = emptyList(),
        disappearingDurationSeconds: Int? = null,
        isViewOnce: Boolean = false
    ): Result<Message> {
        val conv = dao.getConversationById(conversationId)
        val currentUserId = tokenManager.userId ?: ""
        
        var encryptedContent = content
        var blurHash: String? = null

        // End-to-end encryption (direct text chats). The local copy keeps the
        // plaintext (below) so the sender always sees their own text; only the
        // wire copy is encrypted and the recipient decrypts it.
        //
        // Phase 1 "no false privacy" rule: in an E2EE conversation we must NOT
        // silently transmit plaintext if encryption fails (e.g. the recipient's
        // keys aren't available yet). encryptMessage() returns the plaintext
        // unchanged when it can't establish a Signal session; we detect that and
        // fail the message (status FAILED, retryable) rather than leak cleartext.
        if (conv != null && conv.isDirect && type == 0 &&
            com.example.gochat.core.crypto.E2EESettings.isAvailable(context)
        ) {
            val targetUserId = conv.memberIds.find { it != currentUserId }
            if (!targetUserId.isNullOrBlank()) {
                // encryptForUser is N=1-safe: with one recipient device it returns the
                // same raw ciphertext encryptMessage() would (legacy path); only a
                // multi-device recipient yields a marked envelope. Either way, a return
                // equal to the plaintext means "could not encrypt" → fail, don't leak.
                encryptedContent = encryptionManager.encryptForUser(targetUserId, content)
                if (encryptedContent == content) {
                    // Encryption did not produce ciphertext → do not send in the clear.
                    diag("Could NOT encrypt to …${targetUserId.takeLast(6)} — no Signal session could be established (recipient's keys missing on the server, or not fetched). Message marked FAILED.")
                    val failed = createOptimisticMessage(
                        conversationId, content, type, mediaUrl,
                        replyToId, replyToText, replyToSenderName,
                        replyToMediaUrl, replyToMediaType
                    ).copy(status = MessageStatus.FAILED)
                    dao.insertMessage(failed)
                    return Result.success(failed)
                }
            }
        }

        // Group E2EE (Signal sender keys) for text messages. Ensure this device's
        // sender key is distributed to all members, then encrypt with the group
        // cipher. No plaintext fallback (fail instead of leaking).
        if (conv != null && !conv.isDirect && type == 0 &&
            com.example.gochat.core.crypto.E2EESettings.isAvailable(context)
        ) {
            ensureGroupSenderKeyDistributed(conv, currentUserId)
            val enc = encryptionManager.encryptGroupMessage(conversationId, content)
            if (enc == null) {
                val failed = createOptimisticMessage(
                    conversationId, content, type, mediaUrl,
                    replyToId, replyToText, replyToSenderName,
                    replyToMediaUrl, replyToMediaType
                ).copy(status = MessageStatus.FAILED)
                dao.insertMessage(failed)
                return Result.success(failed)
            }
            encryptedContent = enc
        }

        // 1. Pre-read media bytes and generate BlurHash BEFORE the optimistic insert,
        //    so the UI immediately shows the blurry placeholder while uploading.
        var cachedMediaBytes: ByteArray? = null
        var cachedMediaUri: Uri? = null
        if (!mediaUrl.isNullOrBlank() && !mediaUrl.startsWith("http://") && !mediaUrl.startsWith("https://")) {
            cachedMediaUri = Uri.parse(mediaUrl)
            cachedMediaBytes = readUriBytes(cachedMediaUri!!)
            if (cachedMediaBytes != null && type == 1) { // IMAGE
                try {
                    val bitmap = BitmapFactory.decodeByteArray(cachedMediaBytes, 0, cachedMediaBytes.size)
                    if (bitmap != null) {
                        val scaled = Bitmap.createScaledBitmap(bitmap, 100, 100, false)
                        blurHash = BlurHashUtil.encode(scaled, 4, 3)
                    }
                } catch (_: Exception) {}
            } else if (type == 2) { // VIDEO (incl. round notes): blur preview from first frame
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    val path = cachedMediaUri?.path
                    if (!path.isNullOrBlank()) {
                        retriever.setDataSource(path)
                        val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (frame != null) {
                            val scaled = Bitmap.createScaledBitmap(frame, 100, 100, false)
                            blurHash = BlurHashUtil.encode(scaled, 4, 3)
                        }
                    }
                } catch (_: Exception) {} finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }
        }

        // 2. Insert optimistic message into local Room DB immediately (status = SENDING)
        var localMsg = createOptimisticMessage(
            conversationId, content, type, mediaUrl,
            replyToId, replyToText, replyToSenderName,
            replyToMediaUrl, replyToMediaType
        ).copy(status = MessageStatus.SENDING, blurHash = blurHash, isViewOnce = isViewOnce)

        
        val effectiveDisappearingDuration = if (disappearingDurationSeconds != null && disappearingDurationSeconds > 0) {
            disappearingDurationSeconds
        } else if (conv != null && conv.disappearingMessagesDuration > 0) {
            conv.disappearingMessagesDuration
        } else {
            null
        }

        if (effectiveDisappearingDuration != null && effectiveDisappearingDuration > 0) {
            localMsg = localMsg.copy(
                disappearingDurationSeconds = effectiveDisappearingDuration,
                expiresAt = System.currentTimeMillis() + (effectiveDisappearingDuration * 1000L)
            )
        }
        dao.insertMessage(localMsg)

        return try {
            // 3. Upload media using the pre-cached bytes (no second read needed)
            // Is this an E2EE direct conversation? (drives media encryption below)
            val e2eeDirect = conv != null && conv.isDirect &&
                com.example.gochat.core.crypto.E2EESettings.isAvailable(context)
            val e2eeTarget = if (e2eeDirect) conv!!.memberIds.find { it != currentUserId } else null
            var e2eeMediaKeyB64: String? = null

            var finalMediaUrl = mediaUrl
            if (cachedMediaBytes != null && cachedMediaUri != null) {
                val mimeType = when (localMsg.type) {
                    MessageType.IMAGE -> "image/jpeg"
                    MessageType.VIDEO -> "video/mp4"
                    MessageType.VOICE, MessageType.AUDIO -> "audio/mp4"
                    else -> "application/octet-stream"
                }
                if (e2eeDirect && !e2eeTarget.isNullOrBlank()) {
                    // E2EE media: encrypt the blob with a random key, upload only the
                    // ciphertext, and carry the key E2EE in `content` (set below). No
                    // plaintext data-URI fallback — if the upload fails we fail the
                    // message rather than leak the media in the clear.
                    val sealed = com.example.gochat.core.crypto.MediaCrypto.seal(cachedMediaBytes)
                    e2eeMediaKeyB64 = sealed.keyB64
                    val uploaded = uploadMedia(sealed.ciphertext, "application/octet-stream",
                        (cachedMediaUri.lastPathSegment ?: "media") + ".enc")
                    if (uploaded.isNullOrBlank()) {
                        val failed = createOptimisticMessage(
                            conversationId, content, type, mediaUrl,
                            replyToId, replyToText, replyToSenderName,
                            replyToMediaUrl, replyToMediaType
                        ).copy(status = MessageStatus.FAILED, mediaUrl = mediaUrl)
                        dao.insertMessage(failed)
                        return Result.success(failed)
                    }
                    finalMediaUrl = uploaded
                } else {
                    val uploaded = uploadMedia(cachedMediaBytes, mimeType, cachedMediaUri.lastPathSegment ?: "media.jpg")
                    if (!uploaded.isNullOrBlank()) {
                        finalMediaUrl = uploaded
                    } else if (localMsg.type == MessageType.IMAGE) {
                        // Critical fallback: If CDN/storage is unconfigured, offline, or returns error (e.g. Render no-storage),
                        // fallback to standard Base64 Data URI so recipient immediately renders the real photo,
                        // rather than failing on an unreachable local device path.
                        val b64 = android.util.Base64.encodeToString(cachedMediaBytes, android.util.Base64.NO_WRAP)
                        finalMediaUrl = "data:$mimeType;base64,$b64"
                    }
                }
            }

            // For E2EE media, the wire `content` carries the (E2EE-encrypted) media
            // key instead of a caption, so the recipient can decrypt the blob. We also
            // carry the original filename E2EE (key \u0001 filename) so the recipient can
            // restore the real name/extension — the server only ever sees ciphertext.
            if (e2eeMediaKeyB64 != null && !e2eeTarget.isNullOrBlank()) {
                val origName = cachedMediaUri?.let { resolveDisplayName(it) }
                val keyPayload = e2eeMediaKeyB64 + (origName?.takeIf { it.isNotBlank() }?.let { "\u0001$it" } ?: "")
                val wrapped = encryptionManager.encryptMessage(e2eeTarget, keyPayload)
                if (wrapped == keyPayload) {
                    val failed = createOptimisticMessage(
                        conversationId, content, type, mediaUrl,
                        replyToId, replyToText, replyToSenderName,
                        replyToMediaUrl, replyToMediaType
                    ).copy(status = MessageStatus.FAILED, mediaUrl = mediaUrl)
                    dao.insertMessage(failed)
                    return Result.success(failed)
                }
                encryptedContent = wrapped
            }

            // CRITICAL SANITIZATION: Ensure the mediaUrl sent across the wire is NEVER a local device path (/data/..., /storage/..., file://)
            var networkMediaUrl: String? = finalMediaUrl
            if (networkMediaUrl != null && !networkMediaUrl.startsWith("http://") && !networkMediaUrl.startsWith("https://") && !networkMediaUrl.startsWith("data:")) {
                if (cachedMediaBytes != null && localMsg.type == MessageType.IMAGE) {
                    val b64 = android.util.Base64.encodeToString(cachedMediaBytes, android.util.Base64.NO_WRAP)
                    networkMediaUrl = "data:image/jpeg;base64,$b64"
                } else {
                    try {
                        val file = java.io.File(networkMediaUrl.removePrefix("file://"))
                        if (file.exists() && localMsg.type == MessageType.IMAGE) {
                            val fileBytes = file.readBytes()
                            val b64 = android.util.Base64.encodeToString(fileBytes, android.util.Base64.NO_WRAP)
                            networkMediaUrl = "data:image/jpeg;base64,$b64"
                        } else {
                            networkMediaUrl = null
                        }
                    } catch (_: Exception) {
                        networkMediaUrl = null
                    }
                }
            }

            // 3. Directly deliver via HTTP API for instant sub-second delivery
            val body = buildJsonObject {
                put("content", encryptedContent)
                put("type", type)
                if (isViewOnce) {
                    put("is_view_once", true)
                }

                networkMediaUrl?.let { put("media_url", it) }
                blurHash?.let { put("blur_hash", it) }
                telegramFileId?.let { put("telegram_file_id", it) }

                mediaThumbnail?.let { put("media_thumbnail", it) }
                replyToId?.let { put("parent_id", it) }
                if (mentionedUserIds.isNotEmpty()) {
                    put("mentioned_user_ids", JsonArray(mentionedUserIds.map { JsonPrimitive(it) }))
                }
                if (localMsg.disappearingDurationSeconds != null && localMsg.disappearingDurationSeconds > 0) {
                    put("disappearing_messages_duration", localMsg.disappearingDurationSeconds)
                    put("disappearing_duration_seconds", localMsg.disappearingDurationSeconds)
                }
                if (localMsg.expiresAt != null && localMsg.expiresAt > 0) {
                    put("expires_at", localMsg.expiresAt / 1000L)
                }
            }

            val response = api.sendMessage(conversationId, body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val msgJson = data["message"]?.jsonObject ?: data
                val parsed = Message.fromJson(msgJson, tokenManager.userId ?: "")
                val localExisting = dao.getMessageById(localMsg.id) ?: dao.getMessageById(parsed.id)
                val currentStatus = localExisting?.status ?: MessageStatus.SENT
                val resolvedStatus = if (currentStatus == MessageStatus.READ || currentStatus == MessageStatus.DELIVERED) {
                    currentStatus
                } else if (parsed.status == MessageStatus.READ || parsed.status == MessageStatus.DELIVERED) {
                    parsed.status
                } else {
                    MessageStatus.SENT
                }
                // E2EE media: the server copy is ciphertext, so the sender must NOT
                // point at parsed.mediaUrl (the encrypted blob URL) or they'd render
                // garbage. Persist the plaintext locally (survives restart) and point
                // the sender's own copy at that file.
                val senderLocalMediaUrl: String? = if (e2eeMediaKeyB64 != null && cachedMediaBytes != null) {
                    try {
                        val dir = java.io.File(context.cacheDir, "e2ee_media").apply { if (!exists()) mkdirs() }
                        val origName = cachedMediaUri?.let { resolveDisplayName(it) }
                            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.takeIf { it.isNotBlank() }
                        val fileName = if (origName != null) "${parsed.id}_$origName" else {
                            val ext = when (localMsg.type) {
                                MessageType.IMAGE -> "jpg"; MessageType.VIDEO -> "mp4"
                                MessageType.VOICE, MessageType.AUDIO -> "m4a"; else -> "bin"
                            }
                            "${parsed.id}.$ext"
                        }
                        val out = java.io.File(dir, fileName)
                        out.writeBytes(cachedMediaBytes)
                        out.absolutePath
                    } catch (_: Exception) { mediaUrl }
                } else null

                val finalMsg = parsed.copy(
                    isMe = true,
                    content = content, // Preserve local plaintext
                    mediaUrl = senderLocalMediaUrl
                        ?: if (!parsed.mediaUrl.isNullOrBlank()) parsed.mediaUrl else finalMediaUrl,
                    blurHash = if (!parsed.blurHash.isNullOrBlank()) parsed.blurHash else blurHash,
                    status = resolvedStatus,
                    isViewOnce = isViewOnce || parsed.isViewOnce,
                    disappearingDurationSeconds = parsed.disappearingDurationSeconds ?: localMsg.disappearingDurationSeconds,
                    expiresAt = parsed.expiresAt ?: localMsg.expiresAt,
                    replyToId = parsed.replyToId ?: localMsg.replyToId,
                    replyToText = parsed.replyToText ?: localMsg.replyToText,
                    replyToSenderName = parsed.replyToSenderName ?: localMsg.replyToSenderName,
                    replyToMediaUrl = parsed.replyToMediaUrl ?: localMsg.replyToMediaUrl,
                    replyToMediaType = parsed.replyToMediaType ?: localMsg.replyToMediaType,
                    isStarred = localExisting?.isStarred ?: localMsg.isStarred,
                    isPinned = localExisting?.isPinned ?: localMsg.isPinned
                )


                if (finalMsg.id != localMsg.id) {
                    dao.deleteMessage(localMsg.id)
                }
                dao.insertMessage(finalMsg)
                dao.updateLastMessage(
                    convId = conversationId,
                    lastText = formatLastMessageSummary(finalMsg),
                    lastTime = finalMsg.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
                Result.success(finalMsg)
            } else {
                // Server returned non-200, enqueue WorkManager for background sync retry
                enqueueSyncWorker(localMsg.id)
                Result.success(localMsg)
            }
        } catch (_: Exception) {
            // Offline / network failure: enqueue WorkManager for background retry
            enqueueSyncWorker(localMsg.id)
            Result.success(localMsg)
        }
    }

    /** Manual retry of a FAILED outgoing message: flip it back to SENDING and re-enqueue the sender. */
    suspend fun retryFailedMessage(messageId: String) {
        val requeued = dao.requeueFailedMessage(messageId)
        if (requeued > 0) enqueueSyncWorker(messageId)
    }

    private fun enqueueSyncWorker(messageId: String) {
        try {
            val syncRequest = OneTimeWorkRequestBuilder<MessageSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork("msg_sync_$messageId", ExistingWorkPolicy.REPLACE, syncRequest)
        } catch (_: Exception) {}
    }

    /** Resolve a human-readable display name (with extension) for a content/file URI. */
    private fun resolveDisplayName(uri: Uri): String? {
        try {
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = c.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun readUriBytes(uri: Uri): ByteArray? {
        return try {
            if (uri.scheme == null || uri.scheme == "file") {
                val file = File(uri.path ?: uri.toString())
                if (file.exists()) {
                    return file.readBytes()
                }
            }
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun handleIncomingWebSocketEvent(event: JsonObject): Message? =
        messageIngestMutex.withLock { handleIncomingWebSocketEventLocked(event) }

    private suspend fun handleIncomingWebSocketEventLocked(event: JsonObject): Message? {
        val eventType = (event["type"] ?: event["event_type"] ?: event["eventType"] ?: event["event"])
            ?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()

        if (eventType == "presence") {
            val userId = (event["user_id"] ?: event["userId"])?.jsonPrimitive?.contentOrNull.orEmpty()
            val isOnline = (event["is_online"] ?: event["isOnline"])?.jsonPrimitive?.booleanOrNull ?: false
            val lastSeenSec = (event["last_seen"] ?: event["lastSeen"])?.jsonPrimitive?.longOrNull
            val lastSeenMs = if (lastSeenSec != null) {
                if (lastSeenSec < 100_000_000_000L) lastSeenSec * 1000L else lastSeenSec
            } else System.currentTimeMillis()

            if (userId.isNotEmpty() && userId != (tokenManager.userId ?: "")) {
                dao.updatePresenceGlobal(userId, isOnline, lastSeenMs)
            }
            return null
        }

        val rawMsg = event["message"] ?: event["Message"] ?: event["payload"] ?: event["data"]

        if (eventType == "message_deleted" || eventType == "event_message_deleted" || eventType == "2") {
            val msgId = (event["msg_id"] ?: event["message_id"] ?: event["id"]
                ?: if (rawMsg is JsonObject) rawMsg["id"] ?: rawMsg["message_id"] ?: rawMsg["msg_id"] else null)
                ?.jsonPrimitive?.contentOrNull.orEmpty()
            val convId = (event["conversation_id"] ?: event["conversationId"] ?: event["conv_id"]
                ?: if (rawMsg is JsonObject) rawMsg["conversation_id"] ?: rawMsg["conv_id"] else null)
                ?.jsonPrimitive?.contentOrNull.orEmpty()
            if (msgId.isNotEmpty()) {
                deleteMessageLocally(msgId, convId)
            }
            return null
        }

        val isMessageEvent = eventType == "0" ||
                eventType == "1" ||
                eventType.equals("EVENT_NEW_MESSAGE", ignoreCase = true) ||
                eventType.equals("new_message", ignoreCase = true) ||
                eventType.equals("chat_message", ignoreCase = true) ||
                eventType.equals("message", ignoreCase = true) ||
                (rawMsg is JsonObject && (rawMsg.containsKey("content") || rawMsg.containsKey("conversation_id")))

        if (isMessageEvent) {
            val convId = (event["conversation_id"] ?: event["conversationId"]
                ?: if (rawMsg is JsonObject) rawMsg["conversation_id"] ?: rawMsg["conversationId"] else null)
                ?.jsonPrimitive?.contentOrNull.orEmpty()

            val msgObj = if (rawMsg is JsonObject) rawMsg else event
            val currentUserId = tokenManager.userId ?: ""
            var msg = Message.fromJson(msgObj, currentUserId)
            if (msg.conversationId.isEmpty() && convId.isNotEmpty()) {
                msg = msg.copy(conversationId = convId)
            }

            // WhatsApp Behavior: If sender is blocked locally, drop incoming message & do not deliver notification
            if (!msg.isMe && dao.isUserBlocked(msg.senderId)) {
                Log.d("ChatRepository", "Incoming message dropped: sender ${msg.senderId} is blocked.")
                return null
            }

            // Group sender-key distribution control message: process (if from
            // another member) and never display it. Our own echo is also dropped.
            if (msg.content.startsWith(groupSkdMarker)) {
                if (!msg.isMe) handleGroupKeyDistribution(msg)
                return null
            }
            // Session-reset request from a peer who couldn't decrypt us. Process once.
            if (msg.content.startsWith(sessionResetMarker)) {
                if (!msg.isMe && claimControlMessage(msg.id)) handleSessionReset(msg.senderId)
                return null
            }
            // Disappearing-messages timer change announced by the peer. Process once.
            if (msg.content.startsWith(dmTimerMarker)) {
                if (!msg.isMe && claimControlMessage(msg.id)) handleDisappearingTimerChange(msg.conversationId, msg.content.removePrefix(dmTimerMarker).trim().toIntOrNull() ?: 0)
                return null
            }

            // Guard: preserve locally-stored plaintext to prevent WS echo from
            // overwriting our decrypted/original content with server ciphertext.
            val localExisting = dao.getMessageById(msg.id)
            if (localExisting != null && localExisting.content.isNotBlank() && !isBase64Ciphertext(localExisting.content)) {
                if (msg.isMe) {
                    // Own message already stored correctly by sendMessage() HTTP response.
                    // Skip insert entirely — the WS echo may have incomplete fields
                    // (missing mediaUrl, blurHash, etc.) that would corrupt local data.
                    val isViewing = activeConversationId == msg.conversationId
                    if (!isViewing && msg.conversationId.isNotEmpty()) {
                        dao.updateLastMessageAndIncrementUnread(
                            convId = msg.conversationId,
                            lastText = localExisting.content.ifBlank { "Media" },
                            lastTime = localExisting.createdAt,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    return localExisting
                }
                // Received message with local plaintext — preserve content
                msg = msg.copy(content = localExisting.content)
            } else if (msg.isMe) {
                // Own message echo with no local plaintext. A 1:1 message is encrypted
                // TO THE RECIPIENT, so we can never decrypt our own outgoing ciphertext —
                // storing it would show the sender gibberish forever. Drop the echo; the
                // optimistic/HTTP-response copy holds our plaintext.
                if (isBase64Ciphertext(msg.content.trim())) {
                    return localExisting
                }
                // else: plaintext own echo — keep as-is
            } else {
                // Received message — attempt decryption (text), then media.
                // Groups use sender-key (group) decryption; 1:1 uses pairwise.
                val incomingConv = dao.getConversationById(msg.conversationId)
                msg = if (incomingConv != null && !incomingConv.isDirect) {
                    tryDecryptGroupMessage(msg)
                } else {
                    tryDecryptMessage(msg)
                }
                msg = decryptIncomingMedia(msg)
            }

            if (localExisting != null) {
                val priority = mapOf(
                    MessageStatus.SENDING to 0,
                    MessageStatus.FAILED to 1,
                    MessageStatus.SENT to 2,
                    MessageStatus.DELIVERED to 3,
                    MessageStatus.READ to 4
                )
                val localPri = priority[localExisting.status] ?: 0
                val incomingPri = priority[msg.status] ?: 0
                if (localPri >= incomingPri) {
                    msg = msg.copy(status = localExisting.status)
                }
            }

            // Ignore content-less signalling stubs (e.g. thin `new_message` WS events
            // that carry only ids, or events whose body we couldn't resolve). They'd
            // otherwise insert empty/duplicate bubbles. Real content arrives embedded
            // in the event or via HTTP sync, both keyed by the real message id.
            if (msg.type == MessageType.TEXT && msg.content.isBlank() && msg.mediaUrl.isNullOrBlank()) {
                return localExisting
            }

            if (msg.conversationId.isNotEmpty()) {
                dao.insertMessage(msg)

                // Delete-after-delivery: confirm to the server we persisted this
                // received message, so it can purge its copy once every recipient
                // has it. Explicit ack (vs. relying on socket-write success).
                if (!msg.isMe) {
                    ackDeliveryBestEffort(msg.conversationId, listOf(msg.id))
                }
                // Self-heal: if a 1:1 message couldn't be decrypted, ask the sender to
                // re-establish the session.
                maybeHealSession(msg, msg.content)

                val isViewing = activeConversationId == msg.conversationId
                if (isViewing) {
                    dao.markConversationAsRead(msg.conversationId)
                    dao.updateLastMessage(
                        convId = msg.conversationId,
                        lastText = msg.content.ifBlank { "Media" },
                        lastTime = msg.createdAt,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    dao.updateLastMessageAndIncrementUnread(
                        convId = msg.conversationId,
                        lastText = msg.content.ifBlank { "Media" },
                        lastTime = msg.createdAt,
                        updatedAt = System.currentTimeMillis()
                    )

                    if (!msg.isMe) {
                        val conv = dao.getConversationById(msg.conversationId)
                        val isGroup = conv?.isGroup ?: false
                        val displayTitle = resolveSenderTitle(
                            conversationId = msg.conversationId,
                            senderId = msg.senderId,
                            candidateName = msg.senderName,
                            isGroup = isGroup
                        )
                        val avatarUrl = conv?.avatarUrl.orEmpty()
                        val isMuted = conv?.isMuted ?: false

                        NotificationHelper.showChatNotification(
                            context = context,
                            conversationId = msg.conversationId,
                            title = displayTitle,
                            body = msg.content.ifBlank { "New message" },
                            senderAvatar = avatarUrl,
                            isGroup = isGroup,
                            isMuted = isMuted
                        )
                    }
                }
                return msg
            }
        }
        return null
    }

    /**
     * Resolves the human-readable peer or sender display name for a conversation.
     * Prioritizes phonebook contact names, local conversation titles, and verified usernames.
     * Guaranteed never to return a placeholder like "User".
     */
    suspend fun resolveSenderTitle(
        conversationId: String,
        senderId: String,
        candidateName: String = "",
        isGroup: Boolean = false
    ): String {
        val cleanCandidate = candidateName.trim()
        val isValidCandidate = cleanCandidate.isNotBlank() &&
                !cleanCandidate.equals("User", ignoreCase = true) &&
                !cleanCandidate.equals("GoChat Message", ignoreCase = true) &&
                !cleanCandidate.equals("New Message", ignoreCase = true) &&
                !cleanCandidate.equals("Chat", ignoreCase = true)

        // 1. If conversation exists in local Room DB, check its title
        val conv = if (conversationId.isNotBlank()) dao.getConversationById(conversationId) else null
        val effectiveIsGroup = isGroup || (conv?.isGroup == true)

        // 2. Check phonebook / synced contacts for the sender
        val contactName = if (senderId.isNotBlank()) contactSyncManager.getContactName(senderId) else null

        if (effectiveIsGroup) {
            val groupTitle = conv?.title?.takeIf {
                it.isNotBlank() &&
                        !it.equals("Chat", ignoreCase = true) &&
                        !it.equals("User", ignoreCase = true)
            } ?: "Group Chat"

            val memberName = contactName
                ?: (if (isValidCandidate) cleanCandidate else null)
                ?: "GoChat Member"

            return "$memberName ($groupTitle)"
        }

        // 3. For 1-on-1 chats:
        // Priority A: Phonebook contact name (what the user saved the peer as in their phone)
        if (!contactName.isNullOrBlank()) {
            return contactName
        }

        // Priority B: Local conversation title if already established
        val convTitle = conv?.title?.trim()
        if (!convTitle.isNullOrBlank() &&
            !convTitle.equals("Chat", ignoreCase = true) &&
            !convTitle.equals("User", ignoreCase = true) &&
            !convTitle.equals("GoChat Message", ignoreCase = true)
        ) {
            return convTitle
        }

        // Priority C: Candidate name sent in payload (e.g. sender's display_name or username from backend)
        if (isValidCandidate) {
            return cleanCandidate
        }

        // Priority D: If conversation exists but has generic title, fetch latest conversation from API
        if (conversationId.isNotBlank()) {
            try {
                val apiResult = api.getConversation(conversationId)
                if (apiResult.isSuccessful) {
                    val body = apiResult.body()
                    val convObj = body?.get("conversation")?.jsonObject ?: body
                    val remoteTitle = (convObj?.get("name") ?: convObj?.get("title") ?: convObj?.get("display_name"))
                        ?.jsonPrimitive?.contentOrNull?.trim()
                    if (!remoteTitle.isNullOrBlank() &&
                        !remoteTitle.equals("Chat", ignoreCase = true) &&
                        !remoteTitle.equals("User", ignoreCase = true)
                    ) {
                        if (conv != null) {
                            dao.insertConversation(conv.copy(title = remoteTitle))
                        }
                        return remoteTitle
                    }
                }
            } catch (_: Exception) {}
        }

        // Priority E: Fallback
        return "GoChat Contact"
    }

    /**
     * Ingest an incoming message payload delivered via FCM push notification.
     * Inserts the message into Room DB and updates conversation metadata in the background
     * so that messages are already cached locally even if the app was closed or network is later cut off.
     */
    suspend fun ingestIncomingPushMessage(
        messageId: String,
        conversationId: String,
        senderId: String,
        senderName: String,
        content: String,
        mediaUrl: String? = null,
        type: Int = 0,
        createdAt: Long = System.currentTimeMillis()
    ): Message = messageIngestMutex.withLock {
        ingestIncomingPushMessageLocked(messageId, conversationId, senderId, senderName, content, mediaUrl, type, createdAt)
    }

    private suspend fun ingestIncomingPushMessageLocked(
        messageId: String,
        conversationId: String,
        senderId: String,
        senderName: String,
        content: String,
        mediaUrl: String? = null,
        type: Int = 0,
        createdAt: Long = System.currentTimeMillis()
    ): Message {
        val currentUserId = tokenManager.userId ?: ""
        val isMe = senderId.isNotEmpty() && senderId == currentUserId

        val isOrderPayload = content.contains("\"order_number\"") || content.contains("\"type\":\"order\"")
        val msgType = if (isOrderPayload) {
            MessageType.ORDER
        } else when (type) {
            1 -> MessageType.IMAGE
            2 -> MessageType.VIDEO
            3 -> MessageType.AUDIO
            4 -> MessageType.FILE
            5 -> MessageType.VOICE
            6 -> MessageType.POLL
            7 -> MessageType.PRODUCT
            8 -> MessageType.PING
            9 -> MessageType.ORDER
            10 -> MessageType.PAYMENT_REQUEST
            11 -> MessageType.CATALOG
            12 -> MessageType.SYSTEM
            else -> MessageType.TEXT
        }

        val existing = dao.getMessageById(messageId)
        val rawMsg = Message(
            id = messageId,
            conversationId = conversationId,
            senderId = senderId,
            senderName = senderName,
            content = content,
            type = msgType,
            status = MessageStatus.DELIVERED,
            mediaUrl = mediaUrl,
            isMe = isMe,
            createdAt = createdAt
        )

        val finalMsg = if (existing != null && existing.content.isNotBlank() && !isBase64Ciphertext(existing.content)) {
            existing
        } else {
            tryDecryptMessage(rawMsg)
        }

        dao.insertMessage(finalMsg)

        // Update or create conversation record
        val isViewing = activeConversationId == conversationId
        val lastText = formatLastMessageSummary(finalMsg)

        val existingConv = dao.getConversationById(conversationId)
        val now = System.currentTimeMillis()
        if (existingConv != null) {
            if (existingConv.title.isBlank() ||
                existingConv.title.equals("Chat", ignoreCase = true) ||
                existingConv.title.equals("User", ignoreCase = true)
            ) {
                val resolvedTitle = resolveSenderTitle(
                    conversationId = conversationId,
                    senderId = senderId,
                    candidateName = senderName,
                    isGroup = existingConv.isGroup
                )
                if (resolvedTitle.isNotBlank() && !resolvedTitle.equals("GoChat Contact", ignoreCase = true)) {
                    dao.insertConversation(existingConv.copy(title = resolvedTitle))
                }
            }
            if (isViewing) {
                dao.updateLastMessage(
                    convId = conversationId,
                    lastText = lastText,
                    lastTime = finalMsg.createdAt,
                    updatedAt = now
                )
            } else {
                dao.updateLastMessageAndIncrementUnread(
                    convId = conversationId,
                    lastText = lastText,
                    lastTime = finalMsg.createdAt,
                    updatedAt = now
                )
            }
        } else {
            // First time seeing this conversation - insert minimal conversation so it appears in chat list
            val memberIds = listOf(senderId, currentUserId).filter { it.isNotBlank() }.distinct()
            val resolvedTitle = resolveSenderTitle(
                conversationId = conversationId,
                senderId = senderId,
                candidateName = senderName,
                isGroup = false
            )
            val newConv = Conversation(
                id = conversationId,
                title = resolvedTitle,
                avatarUrl = "",
                type = ConversationType.DIRECT,
                unreadCount = if (isViewing) 0 else 1,
                memberIds = memberIds,
                lastMessageText = lastText,
                lastMessageTime = finalMsg.createdAt,
                updatedAt = now
            )
            dao.insertConversation(newConv)
        }

        return finalMsg
    }

    fun formatLastMessageSummary(msg: Message): String {
        return when (msg.type) {
            MessageType.ORDER -> {
                try {
                    val json = Json { ignoreUnknownKeys = true; isLenient = true }
                    val root = json.decodeFromString<JsonObject>(msg.content.trim())
                    val ord = root["order"]?.let { if (it is JsonObject) it else null } ?: root
                    val num = ord["order_number"]?.jsonPrimitive?.contentOrNull ?: "ORD"
                    val summary = ord["items_summary"]?.jsonPrimitive?.contentOrNull ?: "Marketplace Order"
                    val total = ord["total_amount"]?.jsonPrimitive?.doubleOrNull
                    val totalStr = if (total != null && total > 0) " ($%.2f)".format(java.util.Locale.US, total) else ""
                    "📦 Order #$num: $summary$totalStr"
                } catch (_: Exception) {
                    "📦 Order update"
                }
            }
            MessageType.TEXT -> {
                val trimmed = msg.content.trim()
                if (trimmed.startsWith("{") && (trimmed.contains("\"order_number\"") || trimmed.contains("\"type\":\"order\"") || trimmed.contains("\"order\""))) {
                    try {
                        val json = Json { ignoreUnknownKeys = true; isLenient = true }
                        val root = json.decodeFromString<JsonObject>(trimmed)
                        val ord = root["order"]?.let { if (it is JsonObject) it else null } ?: root
                        val num = ord["order_number"]?.jsonPrimitive?.contentOrNull ?: "ORD"
                        val summary = ord["items_summary"]?.jsonPrimitive?.contentOrNull ?: "Marketplace Order"
                        val total = ord["total_amount"]?.jsonPrimitive?.doubleOrNull
                        val totalStr = if (total != null && total > 0) " ($%.2f)".format(java.util.Locale.US, total) else ""
                        "📦 Order #$num: $summary$totalStr"
                    } catch (_: Exception) {
                        "📦 Order update"
                    }
                } else if (trimmed.startsWith("{") && (trimmed.contains("\"type\":\"catalog\"") || trimmed.contains("\"catalog\""))) {
                    "🛍️ Product Catalog"
                } else if (trimmed.startsWith("{") && (trimmed.contains("\"payment_request\"") || trimmed.contains("\"invoice\""))) {
                    "💳 Payment Request"
                } else if (trimmed.startsWith("{") && trimmed.contains("\"product\"")) {
                    "🛒 Product inquiry"
                } else {
                    if (msg.content.length > 50) msg.content.take(47) + "..." else msg.content.ifBlank { "Message" }
                }
            }
            MessageType.IMAGE -> "📷 Photo"
            MessageType.VIDEO -> "🎥 Video"
            MessageType.VOICE, MessageType.AUDIO -> "🎵 Voice note"
            MessageType.FILE -> "📄 Document"
            MessageType.POLL -> "📊 Poll"
            MessageType.PING -> "💥 PING"
            MessageType.CATALOG -> "🛍️ Product Catalog"
            MessageType.PRODUCT -> "🛒 Product"
            MessageType.PAYMENT_REQUEST -> "💳 Payment Request"
            MessageType.LOCATION -> "📍 Location"
            MessageType.CONTACT -> "👤 Contact"
            MessageType.STICKER -> "🏷️ Sticker"
            MessageType.SYSTEM -> "System message"
        }
    }

    suspend fun insertWebSocketMessage(message: Message) =
        messageIngestMutex.withLock { insertWebSocketMessageLocked(message) }

    private suspend fun insertWebSocketMessageLocked(message: Message) {
        val isMe = message.senderId == (tokenManager.userId ?: "")
        if (!isMe && dao.isUserBlocked(message.senderId)) {
            Log.d("ChatRepository", "insertWebSocketMessage: sender ${message.senderId} is blocked, dropping.")
            return
        }

        // Group sender-key distribution control message: process + never display.
        if (message.content.startsWith(groupSkdMarker)) {
            if (!isMe) handleGroupKeyDistribution(message)
            return
        }
        // Session-reset request: a peer couldn't decrypt us → drop our session. Once.
        if (message.content.startsWith(sessionResetMarker)) {
            if (!isMe && claimControlMessage(message.id)) handleSessionReset(message.senderId)
            return
        }
        // Disappearing-messages timer change announced by the peer. Process once.
        if (message.content.startsWith(dmTimerMarker)) {
            if (!isMe && claimControlMessage(message.id)) handleDisappearingTimerChange(message.conversationId, message.content.removePrefix(dmTimerMarker).trim().toIntOrNull() ?: 0)
            return
        }

        val localExisting = dao.getMessageById(message.id)
        val hasGoodLocalText = localExisting != null &&
                localExisting.content.isNotBlank() && !isBase64Ciphertext(localExisting.content)
        val localMediaGood = localExisting?.mediaUrl?.let { it.isNotBlank() && !it.startsWith("http") } == true

        // Decrypt received text + media, but ONLY if we don't already hold the decrypted
        // copy. A Signal session is a stateful ratchet — re-decrypting the SAME ciphertext
        // (e.g. a duplicate WS delivery) desyncs it and breaks every later message. Decrypt
        // each message exactly once. NEVER our own (encrypted to the recipient, not us).
        val conv = if (message.conversationId.isNotEmpty()) dao.getConversationById(message.conversationId) else null
        var decrypted = message
        if (!isMe) {
            if (!hasGoodLocalText) {
                decrypted = if (conv != null && !conv.isDirect) tryDecryptGroupMessage(message) else tryDecryptMessage(message)
            }
            if (!localMediaGood) {
                decrypted = decryptIncomingMedia(decrypted)
            }
        }

        val clearedAt = conv?.clearedAt ?: 0L
        val isDeleted = (localExisting?.isDeleted == true) || decrypted.isDeleted
        val isStarred = if (isDeleted) false else (localExisting?.isStarred ?: decrypted.isStarred)
        val isPinned = if (isDeleted) false else (localExisting?.isPinned ?: decrypted.isPinned)
        val isDeletedForMe = (localExisting?.deletedForMe == true) || decrypted.deletedForMe ||
                (clearedAt > 0L && decrypted.createdAt <= clearedAt && !isStarred)

        val contentToUse = when {
            isDeleted -> "This message was deleted"
            // Preserve our own plaintext / an already-decrypted received text message.
            hasGoodLocalText -> localExisting!!.content
            // Already-decrypted media: stored content is "" (key consumed) — keep it,
            // don't re-show the key ciphertext as "[Encrypted Message]".
            localMediaGood -> localExisting!!.content
            // Own outgoing ciphertext echo — we can't decrypt it; drop and keep the
            // local plaintext copy rather than overwrite it with gibberish.
            isMe && isBase64Ciphertext(message.content.trim()) -> return
            else -> decrypted.content
        }

        // Drop content-less TEXT stubs (thin signalling events) so they don't insert
        // empty/duplicate bubbles.
        if (decrypted.type == MessageType.TEXT && contentToUse.isBlank() && decrypted.mediaUrl.isNullOrBlank()) return

        val mediaToUse = if (isDeleted) null else if (localMediaGood) localExisting!!.mediaUrl else decrypted.mediaUrl

        val mergedStatus = if (localExisting != null) {
            val priority = mapOf(
                MessageStatus.SENDING to 0,
                MessageStatus.FAILED to 1,
                MessageStatus.SENT to 2,
                MessageStatus.DELIVERED to 3,
                MessageStatus.READ to 4
            )
            val localPri = priority[localExisting.status] ?: 0
            val incomingPri = priority[decrypted.status] ?: 0
            if (localPri >= incomingPri) localExisting.status else decrypted.status
        } else {
            decrypted.status
        }
        val finalMsg = decrypted.copy(
            isMe = isMe,
            content = contentToUse,
            status = mergedStatus,
            isDeleted = isDeleted,
            mediaUrl = mediaToUse,
            mediaThumbnail = if (isDeleted) null else decrypted.mediaThumbnail,
            deletedForMe = isDeletedForMe,
            isStarred = isStarred,
            isPinned = isPinned
        )
        dao.insertMessage(finalMsg)

        if (!isMe && finalMsg.conversationId.isNotEmpty()) {
            ackDeliveryBestEffort(finalMsg.conversationId, listOf(finalMsg.id))
        }
        // Self-heal: if this 1:1 message couldn't be decrypted, ask the sender to
        // re-establish the session (throttled).
        maybeHealSession(message, contentToUse)

        // If the message is from another user and the recipient is not currently looking at this conversation,
        // show the notification banner in the notification bar
        if (!finalMsg.isMe && activeConversationId != finalMsg.conversationId) {
            val conv = if (finalMsg.conversationId.isNotEmpty()) dao.getConversationById(finalMsg.conversationId) else null
            val isGroup = conv?.isGroup ?: false
            val displayTitle = resolveSenderTitle(
                conversationId = finalMsg.conversationId,
                senderId = finalMsg.senderId,
                candidateName = finalMsg.senderName,
                isGroup = isGroup
            )
            val avatarUrl = conv?.avatarUrl.orEmpty()
            val isMuted = conv?.isMuted ?: false

            NotificationHelper.showChatNotification(
                context = context,
                conversationId = finalMsg.conversationId,
                title = displayTitle,
                body = formatLastMessageSummary(finalMsg),
                senderAvatar = avatarUrl,
                isGroup = isGroup,
                isMuted = isMuted
            )
        }
    }

    // ── Group E2EE (Signal sender keys) distribution + decrypt ──────────────
    // A sender-key distribution message (SKDM) is sent as an ordinary group
    // "message" whose content carries this marker + a base64 JSON payload:
    //   { "gid": <groupId>, "keys": { <memberId>: <1:1-E2EE-encrypted SKDM>, … } }
    // Each member's SKDM is wrapped with that member's pairwise 1:1 session so
    // only they can read it. Receivers intercept the marker, decrypt their own
    // entry, process it, and never display the control message.
    // Serializes incoming-message ingestion. Two live WS consumers (ChatListViewModel
    // via handleIncomingWebSocketEvent, ChatRoomViewModel via insertWebSocketMessage)
    // can process the SAME message concurrently; without this both would run the Signal
    // decrypt on the same ciphertext and desync the ratchet ("first msg ok, rest
    // encrypted"). The lock makes the "already have plaintext? → skip decrypt" check
    // atomic with the store, so each message is decrypted exactly once.
    private val messageIngestMutex = kotlinx.coroutines.sync.Mutex()

    // On-screen E2EE diagnostics: a human-readable reason whenever encrypt/decrypt
    // fails, surfaced as a dismissible banner in the chat so failures can be
    // diagnosed from a screenshot (no logcat needed).
    val cryptoDiag = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 16)
    private fun diag(msg: String) {
        cryptoDiag.tryEmit(msg)
        Log.w("E2EEDiag", msg)
    }

    private val groupSkdMarker = "\u0001GKD1:"

    // ── Session self-heal ───────────────────────────────────────────────────────
    // When we receive an undecryptable 1:1 message (the sender's session diverged
    // from ours — e.g. they reinstalled/re-keyed), we send this control message back
    // asking them to reset their session; their next message is then a fresh X3DH
    // handshake we CAN decrypt. Sent unencrypted (the point is the session is broken)
    // and never displayed. Throttled so we don't spam.
    private val sessionResetMarker = "\u0001E2EERST1:"
    private val resetThrottlePrefs by lazy { context.getSharedPreferences("e2ee_reset_req", Context.MODE_PRIVATE) }

    /** Ask [peerId] to reset their Signal session with us (throttled: once/min/peer). */
    private suspend fun requestSessionReset(conversationId: String, peerId: String) {
        if (E2EESettings.DISABLED) return
        if (conversationId.isBlank() || peerId.isBlank()) return
        val now = System.currentTimeMillis()
        val last = try { resetThrottlePrefs.getLong("req_$peerId", 0L) } catch (_: Exception) { 0L }
        if (now - last < 60_000L) return
        try { resetThrottlePrefs.edit().putLong("req_$peerId", now).apply() } catch (_: Exception) {}
        try {
            val body = buildJsonObject {
                put("content", sessionResetMarker)
                put("type", 0)
            }
            api.sendMessage(conversationId, body)
            diag("Couldn't decrypt a message from …${peerId.takeLast(6)} — asked them to re-establish the secure session.")
        } catch (e: Exception) {
            Log.w("ChatRepository", "requestSessionReset failed: ${e.message}")
        }
    }

    // ── Disappearing messages (per-chat timer) ─────────────────────────────────
    // Setting the timer updates it locally AND announces it to the other party via
    // this control message so both sides apply the same auto-delete duration (like
    // WhatsApp). A visible system message records the change.
    private val dmTimerMarker = "\u0001DMT1:"

    /** Set the per-conversation disappearing-messages timer (seconds; 0 = off). */
    suspend fun setDisappearingTimer(conversationId: String, seconds: Int) {
        if (conversationId.isBlank()) return
        dao.updateDisappearingMessagesDuration(conversationId, seconds.coerceAtLeast(0))
        try {
            val body = buildJsonObject {
                put("content", dmTimerMarker + seconds.coerceAtLeast(0).toString())
                put("type", 0)
            }
            api.sendMessage(conversationId, body)
        } catch (e: Exception) {
            Log.w("ChatRepository", "announce disappearing timer failed: ${e.message}")
        }
        insertTimerSystemMessage(conversationId, seconds, byMe = true)
    }

    private suspend fun handleDisappearingTimerChange(conversationId: String, seconds: Int) {
        if (conversationId.isBlank()) return
        dao.updateDisappearingMessagesDuration(conversationId, seconds.coerceAtLeast(0))
        insertTimerSystemMessage(conversationId, seconds, byMe = false)
    }

    private fun disappearingLabel(seconds: Int): String = when {
        seconds <= 0 -> "off"
        seconds >= 7776000 -> "90 days"
        seconds >= 604800 -> "7 days"
        seconds >= 86400 -> "24 hours"
        seconds >= 3600 -> "${seconds / 3600} hours"
        else -> "${seconds / 60} minutes"
    }

    private suspend fun insertTimerSystemMessage(conversationId: String, seconds: Int, byMe: Boolean) {
        val who = if (byMe) "You" else "Your contact"
        val text = if (seconds <= 0) {
            "SYSTEM:$who turned off disappearing messages"
        } else {
            "SYSTEM:$who set disappearing messages to ${disappearingLabel(seconds)}"
        }
        try {
            dao.insertMessage(
                Message(
                    id = java.util.UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    senderId = "system",
                    content = text,
                    type = MessageType.SYSTEM,
                    status = MessageStatus.SENT,
                    isMe = byMe,
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.w("ChatRepository", "insert timer system msg failed: ${e.message}")
        }
    }

    // Control messages (session-reset, timer change) must be processed EXACTLY ONCE.
    // The server re-delivers them until delete-after-delivery purges them, so without
    // this a force-refresh re-runs the handler every time — and for the reset marker
    // that means re-deleting the Signal session on every refresh, destroying a freshly
    // re-handshaked session and causing "no valid session" on later messages. Keyed by
    // message id and persisted so it survives refreshes and process restarts.
    private val controlProcessedPrefs by lazy {
        context.getSharedPreferences("e2ee_control_processed", Context.MODE_PRIVATE)
    }

    /** True the FIRST time this control-message id is claimed; false if already done.
     *  A blank id can't be deduped, so it's processed (best effort). */
    private fun claimControlMessage(id: String): Boolean {
        if (id.isBlank()) return true
        return try {
            if (controlProcessedPrefs.getBoolean(id, false)) {
                false
            } else {
                controlProcessedPrefs.edit().putBoolean(id, true).apply()
                true
            }
        } catch (_: Exception) { true }
    }

    /** A peer couldn't decrypt our messages and asked us to reset; drop our session
     *  with them so our next message re-handshakes. */
    private suspend fun handleSessionReset(fromUserId: String) {
        if (E2EESettings.DISABLED) return
        encryptionManager.resetSession(fromUserId)
        diag("Peer …${fromUserId.takeLast(6)} asked to re-establish the secure session; will re-handshake on next message.")
    }

    /** After a received 1:1 message, if it stayed encrypted (decrypt failed) ask the
     *  sender to re-establish. No-op for groups / own / successfully-decrypted msgs. */
    private suspend fun maybeHealSession(message: Message, resolvedContent: String) {
        if (E2EESettings.DISABLED) return
        if (message.isMe) return
        if (message.type != MessageType.TEXT) return
        if (resolvedContent.isBlank() || !isBase64Ciphertext(resolvedContent.trim())) return
        val conv = if (message.conversationId.isNotEmpty()) dao.getConversationById(message.conversationId) else null
        if (conv == null || !conv.isDirect) return // group recovery is a separate mechanism
        requestSessionReset(message.conversationId, message.senderId)
    }
    private fun groupSkdPrefs() =
        context.getSharedPreferences("gochat_group_e2ee", Context.MODE_PRIVATE)

    /**
     * Ensure this device's group sender key has been created and distributed to
     * the current member set. Re-distributes (re-keys) whenever the member set
     * changes. Sends the SKDM as a dedicated control message via [api]. Best
     * effort: failures are swallowed so sending never hard-fails here (the
     * message send itself will fail-closed if encryption can't proceed).
     */
    private suspend fun ensureGroupSenderKeyDistributed(conv: Conversation, myId: String) {
        val others = conv.memberIds.filter { it.isNotBlank() && it != myId }.distinct()
        if (others.isEmpty()) return
        val signature = others.sorted().joinToString(",")
        val prefs = groupSkdPrefs()
        val key = "skd_${conv.id}"
        // Already distributed to this exact member set → nothing to do.
        if (prefs.getString(key, null) == signature) return

        val skdm = encryptionManager.createGroupSenderKey(conv.id) ?: return
        val keysObj = buildJsonObject {
            for (m in others) {
                // Wrap the SKDM under each member's pairwise session. If no session
                // could be established, encryptMessage returns the plaintext
                // unchanged — skip that member (they'll be re-keyed on next change).
                val wrapped = encryptionManager.encryptMessage(m, skdm)
                if (wrapped != skdm) put(m, wrapped)
            }
        }
        if (keysObj.isEmpty()) return // couldn't reach any member yet; retry on next send

        val payload = buildJsonObject {
            put("gid", conv.id)
            put("keys", keysObj)
        }.toString()
        val encoded = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val body = buildJsonObject {
            put("content", groupSkdMarker + encoded)
            put("type", 0)
        }
        try {
            api.sendMessage(conv.id, body)
            prefs.edit().putString(key, signature).apply()
        } catch (e: Exception) {
            Log.w("ChatRepository", "SKDM distribution failed for ${conv.id}: ${e.message}")
        }
    }

    /** Handle an incoming sender-key distribution control message: decrypt our
     *  own entry and process it so we can decrypt that sender's group messages. */
    private suspend fun handleGroupKeyDistribution(message: Message) {
        try {
            val encoded = message.content.removePrefix(groupSkdMarker)
            val jsonStr = String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
            val parser = Json { ignoreUnknownKeys = true; isLenient = true }
            val obj = parser.parseToJsonElement(jsonStr).jsonObject
            val gid = obj["gid"]?.jsonPrimitive?.contentOrNull ?: return
            val myId = tokenManager.userId ?: return
            val myEntry = obj["keys"]?.jsonObject?.get(myId)?.jsonPrimitive?.contentOrNull ?: return
            val skdm = encryptionManager.decryptMessage(message.senderId, myEntry)
            if (skdm.isNotBlank() && skdm != myEntry) {
                encryptionManager.processGroupSenderKey(gid, message.senderId, skdm)
            }
        } catch (e: Exception) {
            Log.w("ChatRepository", "handleGroupKeyDistribution failed: ${e.message}")
        }
    }

    /** Decrypt a received group text message with the sender's group cipher.
     *  Returns the message unchanged if we can't decrypt yet (SKDM not arrived). */
    private fun tryDecryptGroupMessage(message: Message): Message {
        if (E2EESettings.DISABLED) return message
        if (message.type != MessageType.TEXT || message.isMe || message.content.isBlank()) {
            return message
        }
        val trimmed = message.content.trim()
        if (!isBase64Ciphertext(trimmed)) return message
        val decrypted = encryptionManager.decryptGroupMessage(message.conversationId, message.senderId, trimmed)
        return if (!decrypted.isNullOrBlank()) message.copy(content = decrypted) else message
    }

    /** Best-effort delivery acknowledgement (delete-after-delivery). Never throws. */
    private suspend fun ackDeliveryBestEffort(convId: String, msgIds: List<String>) {
        if (convId.isBlank() || msgIds.isEmpty()) return
        try {
            val body = buildJsonObject {
                put("message_ids", buildJsonArray { msgIds.forEach { add(it) } })
            }
            api.ackDelivery(convId, body)
        } catch (e: Exception) {
            Log.w("ChatRepository", "ackDelivery failed: ${e.message}")
        }
    }

    // Message ids we've already surfaced a "stays encrypted" warning for. A force
    // refresh re-attempts EVERY still-encrypted message, which previously re-fired the
    // diagnostic banner each time. We keep retrying the decrypt (so an out-of-order
    // message still recovers once its handshake arrives), but warn at most once per
    // message per app session. Cleared when a message finally decrypts.
    private val warnedStuckMsgIds = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun warnStuckOnce(message: Message) {
        if (warnedStuckMsgIds.add(message.id)) {
            diag("Text from …${message.senderId.takeLast(6)} stays encrypted — ${encryptionManager.lastDecryptDiagnostic}")
        }
    }

    private fun tryDecryptMessage(message: Message): Message {
        if (E2EESettings.DISABLED) return message
        if (message.type != MessageType.TEXT || message.isMe || message.content.isBlank()) {
            return message
        }

        val trimmed = message.content.trim()
        // Multi-device envelope (Phase 2): the sender fanned this message out to
        // several of our devices. Pick our device's ciphertext and decrypt that.
        // Dormant today (recipients have one device), but handle it if it arrives.
        if (encryptionManager.isEnvelope(trimmed)) {
            val decrypted = encryptionManager.decryptEnvelope(message.senderId, trimmed)
            if (decrypted.isNotBlank() && decrypted != trimmed) {
                warnedStuckMsgIds.remove(message.id)
                return message.copy(content = decrypted)
            }
            warnStuckOnce(message)
            return message
        }
        // Standard text messages usually have spaces or common punctuation.
        // Ciphertext is a dense block of alphanumeric chars.
        if (isBase64Ciphertext(trimmed)) {
            val decrypted = encryptionManager.decryptMessage(message.senderId, trimmed)
            if (decrypted.isNotBlank() && decrypted != trimmed) {
                warnedStuckMsgIds.remove(message.id)
                return message.copy(content = decrypted)
            }
            // Decryption failed — return the message unchanged so we don't permanently
            // mask content that could be decrypted later when the session is established.
            // The raw ciphertext will be re-attempted on next fetch; the banner is shown
            // only once per message so a force-refresh doesn't re-spam it.
            warnStuckOnce(message)
            return message
        }
        return message
    }

    /**
     * For a received E2EE media message, the wire `content` is the E2EE-encrypted
     * media key and `mediaUrl` points to the encrypted blob. Decrypt the key,
     * download the ciphertext, decrypt it to a local file, and repoint mediaUrl
     * at that file so the UI renders it normally. No-op for non-E2EE / own / text.
     */
    private suspend fun decryptIncomingMedia(message: Message): Message = withContext(Dispatchers.IO) {
        if (E2EESettings.DISABLED) return@withContext message
        if (message.isMe || message.type == MessageType.TEXT) return@withContext message
        val url = message.mediaUrl
        if (url.isNullOrBlank() || !url.startsWith("http")) return@withContext message
        // NOTE: decryption is heuristic (like tryDecryptMessage), NOT gated on the
        // recipient's local E2EESettings flag. The recipient often didn't create the
        // conversation, so that flag is false on their device — gating on it left
        // E2EE media undecryptable (shown as ciphertext + placeholder). Instead we
        // detect an E2EE media message by: content is a base64 ciphertext that our
        // Signal session decrypts to a valid media key. Non-E2EE media (plaintext
        // caption / no session) falls through unchanged.
        val keyCipher = message.content.trim()
        if (!isBase64Ciphertext(keyCipher)) return@withContext message

        val decryptedPayload = encryptionManager.decryptMessage(message.senderId, keyCipher)
        if (decryptedPayload.isBlank() || decryptedPayload == keyCipher) {
            diag("Image key from …${message.senderId.takeLast(6)} won't decrypt — ${encryptionManager.lastDecryptDiagnostic}")
            return@withContext message // can't decrypt yet
        }

        // Payload is `base64(iv||key)` optionally followed by `\u0001<original filename>`.
        val sep = decryptedPayload.indexOf('\u0001')
        val keyB64 = if (sep >= 0) decryptedPayload.substring(0, sep) else decryptedPayload
        val origName = if (sep >= 0) decryptedPayload.substring(sep + 1).takeIf { it.isNotBlank() } else null

        try {
            val req = okhttp3.Request.Builder().url(url).build()
            val bytes = NetworkModule.getOkHttpClient(context).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext message
                resp.body?.bytes() ?: return@withContext message
            }
            val plain = com.example.gochat.core.crypto.MediaCrypto.open(bytes, keyB64)
            val dir = java.io.File(context.cacheDir, "e2ee_media").apply { if (!exists()) mkdirs() }
            // Prefer the original filename (restores name + extension for documents);
            // fall back to <id>.<ext-by-type>. Sanitize and prefix with the message id
            // to avoid collisions between files that share a name.
            val fileName = origName
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?.takeIf { it.isNotBlank() }
                ?.let { "${message.id}_$it" }
                ?: run {
                    val ext = when (message.type) {
                        MessageType.IMAGE -> "jpg"; MessageType.VIDEO -> "mp4"
                        MessageType.VOICE, MessageType.AUDIO -> "m4a"; else -> "bin"
                    }
                    "${message.id}.$ext"
                }
            val out = java.io.File(dir, fileName)
            out.writeBytes(plain)
            message.copy(mediaUrl = out.absolutePath, content = "")
        } catch (e: Exception) {
            Log.w("ChatRepository", "E2EE media decrypt failed for ${message.id}: ${e.message}")
            message
        }
    }

    private fun isBase64Ciphertext(text: String): Boolean {
        val trimmed = text.trim()
        // Signal Protocol ciphertext for even a single character is 50+ bytes,
        // which Base64-encodes to ~70+ characters. A threshold of 44 chars
        // (32 bytes) avoids false positives on short IDs, hashes, and normal text.
        if (trimmed.length < 44 || trimmed.contains(" ") || trimmed.contains("\n")) return false
        // Quick reject: must be pure Base64 character set
        if (!trimmed.matches(Regex("^[A-Za-z0-9+/=]+$"))) return false
        
        return try {
            Base64.decode(trimmed, Base64.DEFAULT)
            true
        } catch (_: Exception) {
            false
        }
    }


    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        val existing = dao.getMessageById(messageId)
        if (existing != null) {
            val priority = mapOf(
                MessageStatus.SENDING to 0,
                MessageStatus.FAILED to 1,
                MessageStatus.SENT to 2,
                MessageStatus.DELIVERED to 3,
                MessageStatus.READ to 4
            )
            val currentPri = priority[existing.status] ?: 0
            val newPri = priority[status] ?: 0
            if (newPri > currentPri) {
                dao.updateMessageStatus(messageId, status)
            }
        } else {
            dao.updateMessageStatus(messageId, status)
        }
    }

    // ── Messaging Enhancements ───────────────────────────────────

    suspend fun toggleMessageStar(messageId: String, isStarred: Boolean) {
        dao.updateMessageStarred(messageId, isStarred)
    }

    suspend fun toggleMessagePin(messageId: String, isPinned: Boolean, conversationId: String = "") {
        dao.updateMessagePinned(messageId, isPinned)
        if (conversationId.isNotEmpty()) {
            try {
                val body = buildJsonObject {
                    put("conversation_id", conversationId)
                }
                if (isPinned) {
                    api.pinMessage(messageId, body)
                } else {
                    api.unpinMessage(messageId, body)
                }
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to sync message pin state to remote server", e)
            }
        }
    }

    suspend fun toggleMessagePinLocally(messageId: String, isPinned: Boolean) {
        dao.updateMessagePinned(messageId, isPinned)
    }

    fun getPinnedMessages(conversationId: String): Flow<List<Message>> {
        return dao.getPinnedMessagesForConversation(conversationId)
    }

    suspend fun clearChat(convId: String, deleteStarred: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            if (deleteStarred) {
                dao.clearAllMessagesForConversation(convId)
            } else {
                dao.clearNonStarredMessagesForConversation(convId)
            }
            val remainingLatest = dao.getLatestActiveMessage(convId)
            val lastText = remainingLatest?.let {
                when (it.type) {
                    MessageType.IMAGE -> "📷 Photo"
                    MessageType.VIDEO -> "📹 Video"
                    MessageType.AUDIO, MessageType.VOICE -> "🎙️ Voice note"
                    MessageType.FILE -> "📄 Document"
                    else -> it.content
                }
            }
            val lastTime = remainingLatest?.createdAt ?: now
            dao.updateConversationCleared(convId, now, lastText, lastTime)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateConversationLastMessageFromDb(convId: String) = withContext(Dispatchers.IO) {
        val remainingLatest = dao.getLatestActiveMessage(convId)
        val lastText = remainingLatest?.let {
            if (it.isDeleted) "🚫 This message was deleted"
            else when (it.type) {
                MessageType.IMAGE -> "📷 Photo"
                MessageType.VIDEO -> "📹 Video"
                MessageType.AUDIO, MessageType.VOICE -> "🎙️ Voice note"
                MessageType.FILE -> "📄 Document"
                else -> it.content
            }
        } ?: ""
        val lastTime = remainingLatest?.createdAt ?: System.currentTimeMillis()
        dao.updateLastMessage(convId, lastText, lastTime, System.currentTimeMillis())
    }

    suspend fun deleteMessageForMe(messageId: String, convId: String = "") = withContext(Dispatchers.IO) {
        val targetConvId = if (convId.isNotEmpty()) convId else dao.getMessageById(messageId)?.conversationId.orEmpty()
        dao.markMessageDeletedForMe(messageId)
        if (targetConvId.isNotEmpty()) {
            updateConversationLastMessageFromDb(targetConvId)
        }
    }

    suspend fun deleteMessagesForMe(messageIds: List<String>, convId: String = "") = withContext(Dispatchers.IO) {
        val targetConvId = if (convId.isNotEmpty()) convId else messageIds.firstNotNullOfOrNull { dao.getMessageById(it)?.conversationId }.orEmpty()
        dao.markMessagesDeletedForMe(messageIds)
        if (targetConvId.isNotEmpty()) {
            updateConversationLastMessageFromDb(targetConvId)
        }
    }

    suspend fun deleteMessageForEveryone(messageId: String, convId: String = "") = withContext(Dispatchers.IO) {
        val targetConvId = if (convId.isNotEmpty()) convId else dao.getMessageById(messageId)?.conversationId.orEmpty()
        dao.markMessageAsDeleted(messageId)
        if (targetConvId.isNotEmpty()) {
            updateConversationLastMessageFromDb(targetConvId)
        }
        try {
            api.deleteMessage(messageId)
        } catch (e: Exception) {
            Log.w("ChatRepository", "Failed to sync deleteMessage to remote server", e)
        }
    }

    suspend fun deleteMessagesForEveryone(messageIds: List<String>, convId: String = "") = withContext(Dispatchers.IO) {
        val targetConvId = if (convId.isNotEmpty()) convId else messageIds.firstNotNullOfOrNull { dao.getMessageById(it)?.conversationId }.orEmpty()
        dao.markMessagesAsDeleted(messageIds)
        if (targetConvId.isNotEmpty()) {
            updateConversationLastMessageFromDb(targetConvId)
        }
        messageIds.forEach { msgId ->
            try {
                api.deleteMessage(msgId)
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to sync deleteMessage to remote server for $msgId", e)
            }
        }
    }

    suspend fun deleteMessageLocally(messageId: String, convId: String = "") = withContext(Dispatchers.IO) {
        dao.markMessageAsDeleted(messageId)
        val targetConvId = if (convId.isNotEmpty()) convId else dao.getMessageById(messageId)?.conversationId.orEmpty()
        if (targetConvId.isNotEmpty()) {
            updateConversationLastMessageFromDb(targetConvId)
        }
    }

    suspend fun getStarredMessagesCount(convId: String): Int = withContext(Dispatchers.IO) {
        dao.getStarredMessagesCount(convId)
    }

    suspend fun markMessageAsViewed(messageId: String) {
        dao.markMessageAsViewed(messageId)
    }

    suspend fun editMessageLocally(messageId: String, newContent: String) {
        dao.updateMessageContent(messageId, newContent)
    }

    suspend fun editMessage(messageId: String, newContent: String, conversationId: String = ""): Result<Unit> {
        // 1. Update Room DB locally for instant feedback
        dao.updateMessageContent(messageId, newContent)

        // 2. Sync to backend via PUT /api/v1/chat/messages/:id
        return try {
            val body = buildJsonObject {
                put("content", newContent)
                if (conversationId.isNotBlank()) {
                    put("conversation_id", conversationId)
                }
            }
            val resp = api.editMessage(messageId, body)
            if (resp.isSuccessful) {
                Result.success(Unit)
            } else {
                Log.w("ChatRepository", "Remote editMessage returned code: ${resp.code()}")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.w("ChatRepository", "Failed to sync message edit to remote server", e)
            Result.success(Unit)
        }
    }

    suspend fun getEditHistory(messageId: String): Result<List<com.example.gochat.data.model.EditHistoryEntry>> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getEditHistory(messageId)
            if (resp.isSuccessful) {
                Result.success(resp.body() ?: emptyList())
            } else {
                Log.w("ChatRepository", "getEditHistory returned code: ${resp.code()}")
                Result.failure(Exception("Failed to fetch edit history (${resp.code()})"))
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Failed to fetch edit history", e)
            Result.failure(e)
        }
    }

    suspend fun translateText(
        text: String,
        targetLanguage: String,
        sourceLanguage: String = ""
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("text", text)
                put("target_language", targetLanguage)
                if (sourceLanguage.isNotBlank()) {
                    put("source_language", sourceLanguage)
                }
            }
            val resp = api.translateMessage(body)
            if (resp.isSuccessful && resp.body() != null) {
                val json = resp.body()!!
                val translated = json["translated_text"]?.jsonPrimitive?.contentOrNull ?: text
                val detected = json["detected_language"]?.jsonPrimitive?.contentOrNull ?: "auto"
                Result.success(Pair(translated, detected))
            } else {
                Result.failure(Exception("Translation API error: ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adjustTone(
        text: String,
        tone: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("text", text)
                put("tone", tone.lowercase())
            }
            val resp = api.adjustTone(body)
            if (resp.isSuccessful && resp.body() != null) {
                val json = resp.body()!!
                val adjusted = json["adjusted_text"]?.jsonPrimitive?.contentOrNull ?: text
                Result.success(adjusted)
            } else {
                Result.failure(Exception("Tone adjustment API error: ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addReactionLocally(messageId: String, emoji: String) {
        val currentUserId = tokenManager.userId ?: "u_me"
        val currentUserName = tokenManager.userDisplayName ?: "Me"
        
        val message = dao.getMessageById(messageId) ?: return
        val currentReactions = message.reactions.toMutableList()
        
        // Remove existing reaction from this user if any
        currentReactions.removeAll { it.userId == currentUserId }
        
        // Add new one
        currentReactions.add(Reaction(userId = currentUserId, userName = currentUserName, emoji = emoji))
        
        dao.updateMessageReactions(messageId, currentReactions)
    }

    fun observeStarredMessages(): Flow<List<Message>> = dao.getStarredMessages()

    fun observeStarredMessagesForConversation(conversationId: String): Flow<List<Message>> =
        dao.getStarredMessagesForConversation(conversationId)

    suspend fun forwardMessage(originalMessage: Message, targetConversationId: String): Result<Message> {
        return sendMessage(
            conversationId = targetConversationId,
            content = originalMessage.content,
            type = getMessageTypeInt(originalMessage.type),
            mediaUrl = originalMessage.mediaUrl,
        ).onSuccess { forwardedMsg ->
            dao.insertMessage(forwardedMsg.copy(
                isForwarded = true,
                originalSenderName = originalMessage.senderName
            ))
        }
    }

    private fun getMessageTypeInt(type: MessageType): Int {
        return when (type) {
            MessageType.TEXT -> 0
            MessageType.IMAGE -> 1
            MessageType.VIDEO -> 2
            MessageType.VOICE -> 5
            MessageType.AUDIO -> 3
            MessageType.FILE -> 4
            MessageType.POLL -> 6
            MessageType.PRODUCT -> 7
            MessageType.PING -> 8
            MessageType.ORDER -> 9
            MessageType.PAYMENT_REQUEST -> 10
            MessageType.CATALOG -> 11
            MessageType.SYSTEM -> 12
            else -> 0
        }
    }

    suspend fun sendCatalogMessage(
        conversationId: String,
        catalogData: com.example.gochat.data.model.CatalogData
    ): Result<Message> {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val content = json.encodeToString(com.example.gochat.data.model.CatalogData.serializer(), catalogData)
        return sendMessage(
            conversationId = conversationId,
            content = content,
            type = 11
        )
    }

    suspend fun sendSystemMessage(
        conversationId: String,
        content: String
    ): Result<Message> {
        return sendMessage(
            conversationId = conversationId,
            content = content,
            type = 12
        )
    }

    suspend fun votePoll(pollId: String, optionId: String): Result<Unit> {
        return votePoll(pollId, listOf(optionId))
    }

    suspend fun votePoll(pollId: String, optionIds: List<String>): Result<Unit> {
        return try {
            val body = buildJsonObject {
                if (optionIds.isNotEmpty()) {
                    put("option_id", optionIds.first())
                }
                put("option_ids", buildJsonArray {
                    optionIds.forEach { add(JsonPrimitive(it)) }
                })
            }
            val response = api.votePoll(pollId, body)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Vote failed: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadMedia(
        bytes: ByteArray,
        mimeType: String = "image/jpeg",
        fileName: String = "chat_image.jpg"
    ): String? {
        return try {
            val mediaType = mimeType.toMediaTypeOrNull()
            val reqBody = bytes.toRequestBody(mediaType)
            val part = MultipartBody.Part.createFormData("file", fileName, reqBody)
            val response = api.uploadMedia(part)
            if (response.isSuccessful) {
                val json = response.body()
                val mediaObj = json?.get("media")?.jsonObject ?: json?.get("data")?.jsonObject ?: json
                var rawUrl = (mediaObj?.get("url") ?: mediaObj?.get("Url") ?: mediaObj?.get("URL") ?: mediaObj?.get("media_url"))?.jsonPrimitive?.contentOrNull
                val fileId = (mediaObj?.get("file_id") ?: mediaObj?.get("object_key") ?: mediaObj?.get("fileId"))?.jsonPrimitive?.contentOrNull
                if (rawUrl.isNullOrBlank() && !fileId.isNullOrBlank()) {
                    rawUrl = "/api/v1/media/download/$fileId"
                }
                if (!rawUrl.isNullOrBlank()) {
                    when {
                        rawUrl.startsWith("http://") || rawUrl.startsWith("https://") || rawUrl.startsWith("data:") -> rawUrl
                        rawUrl.startsWith("/") -> "${ApiConstants.BASE_URL.removeSuffix("/")}$rawUrl"
                        rawUrl.startsWith("api/") -> "${ApiConstants.BASE_URL.removeSuffix("/")}/$rawUrl"
                        else -> "${ApiConstants.BASE_URL.removeSuffix("/")}/$rawUrl"
                    }
                } else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun setConversationDisappearingMessages(convId: String, durationSeconds: Int): Result<Unit> {
        return try {
            dao.updateDisappearingMessagesDuration(convId, durationSeconds)

            val noticeText = when (durationSeconds) {
                86400 -> "You set messages to disappear after 24 hours"
                604800 -> "You set messages to disappear after 7 days"
                7776000 -> "You set messages to disappear after 90 days"
                0 -> "You turned off disappearing messages"
                else -> if (durationSeconds > 0) "You set messages to disappear after ${durationSeconds / 3600} hours" else "You turned off disappearing messages"
            }

            val noticeMsg = Message(
                id = "notice_${System.currentTimeMillis()}",
                conversationId = convId,
                senderId = tokenManager.userId ?: "u_me",
                senderName = tokenManager.userDisplayName ?: "Me",
                content = noticeText,
                type = MessageType.TEXT,
                status = MessageStatus.SENT,
                isMe = true,
                createdAt = System.currentTimeMillis()
            )
            dao.insertMessage(noticeMsg)
            dao.updateLastMessage(convId, noticeText, noticeMsg.createdAt, System.currentTimeMillis())

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateConversationDisappearingDurationLocally(convId: String, durationSeconds: Int) {
        try {
            dao.updateDisappearingMessagesDuration(convId, durationSeconds)
        } catch (_: Exception) {}
    }

    private fun createOptimisticMessage(
        conversationId: String,
        content: String,
        type: Int,
        mediaUrl: String?,
        replyToId: String?,
        replyToText: String?,
        replyToSenderName: String?,
        replyToMediaUrl: String? = null,
        replyToMediaType: String? = null
    ): Message {
        val msgType = when (type) {
            1 -> MessageType.IMAGE
            2 -> MessageType.VIDEO
            3 -> MessageType.AUDIO
            4 -> MessageType.FILE
            5 -> MessageType.VOICE
            6 -> MessageType.POLL
            7 -> MessageType.PRODUCT
            8 -> MessageType.PING
            9 -> MessageType.ORDER
            10 -> MessageType.PAYMENT_REQUEST
            11 -> MessageType.CATALOG
            12 -> MessageType.SYSTEM
            else -> MessageType.TEXT
        }
        return Message(
            id = "msg_${System.currentTimeMillis()}",
            conversationId = conversationId,
            senderId = tokenManager.userId ?: "u_me",
            senderName = tokenManager.userDisplayName ?: "Me",
            content = content,
            type = msgType,
            status = MessageStatus.SENDING,
            mediaUrl = mediaUrl,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSenderName = replyToSenderName,
            replyToMediaUrl = replyToMediaUrl,
            replyToMediaType = replyToMediaType,
            isMe = true,
            createdAt = System.currentTimeMillis()
        )
    }

    fun getMediaMessages(conversationId: String): Flow<List<Message>> =
        dao.getMediaMessagesForConversation(conversationId)

    fun getDocumentMessages(conversationId: String): Flow<List<Message>> =
        dao.getDocumentMessagesForConversation(conversationId)

    fun getLinkMessages(conversationId: String): Flow<List<Message>> =
        dao.getLinkMessagesForConversation(conversationId)

    fun getSharedMediaCount(conversationId: String): Flow<Int> =
        dao.getSharedMediaCount(conversationId)

    fun getRecentMediaPreviews(conversationId: String): Flow<List<Message>> =
        dao.getRecentMediaPreviews(conversationId)

    // ═══════════════════════════════════════════════════════════════
    // ── Privacy: Block & Report ───────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    /**
     * Blocks a user locally in Room immediately (offline-first) and syncs with the backend.
     */
    suspend fun blockUser(userId: String, userName: String = ""): Result<Unit> {
        return try {
            // 1. Immediately update Room DB
            dao.insertBlockedUser(
                BlockedUser(
                    userId = userId,
                    userName = userName,
                    blockedAt = System.currentTimeMillis()
                )
            )
            // 2. Sync to server in background
            try {
                api.blockUser(userId)
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to sync block to server for $userId: ${e.message}")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unblocks a user locally in Room immediately and syncs with the backend.
     */
    suspend fun unblockUser(userId: String): Result<Unit> {
        return try {
            // 1. Immediately remove from Room DB
            dao.removeBlockedUser(userId)
            // 2. Sync to server in background
            try {
                api.unblockUser(userId)
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to sync unblock to server for $userId: ${e.message}")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Checks if a user is currently blocked.
     */
    suspend fun isUserBlocked(userId: String): Boolean = dao.isUserBlocked(userId)

    /**
     * Observes whether a user is currently blocked as a Flow.
     */
    fun observeIsUserBlocked(userId: String): Flow<Boolean> = dao.observeIsUserBlocked(userId)

    /**
     * Observes all blocked users.
     */
    fun observeBlockedUsers(): Flow<List<BlockedUser>> = dao.getAllBlockedUsers()

    suspend fun getBlockedUsersList(): List<BlockedUser> = dao.getAllBlockedUsersList()

    /**
     * Syncs blocked users from the server into the local database.
     */
    suspend fun syncBlockedUsers(): Result<List<BlockedUser>> {
        return try {
            val response = api.getBlockedUsers()
            if (response.isSuccessful) {
                val body = response.body()
                val list = mutableListOf<BlockedUser>()
                val rawArray = when (body) {
                    is JsonArray -> body
                    is JsonObject -> body["blocked_users"]?.jsonArray
                        ?: body["blockedUsers"]?.jsonArray
                        ?: body["blocked"]?.jsonArray
                    else -> null
                }
                rawArray?.forEach { element ->
                    when (element) {
                        is JsonPrimitive -> {
                            val uId = element.contentOrNull.orEmpty()
                            if (uId.isNotEmpty()) {
                                list.add(BlockedUser(userId = uId, userName = ""))
                            }
                        }
                        is JsonObject -> {
                            val uId = element["user_id"]?.jsonPrimitive?.contentOrNull
                                ?: element["userId"]?.jsonPrimitive?.contentOrNull
                                ?: element["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val uName = element["user_name"]?.jsonPrimitive?.contentOrNull
                                ?: element["userName"]?.jsonPrimitive?.contentOrNull
                                ?: element["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (uId.isNotEmpty()) {
                                list.add(BlockedUser(userId = uId, userName = uName))
                            }
                        }
                        else -> {}
                    }
                }
                list.forEach { dao.insertBlockedUser(it) }
                Result.success(list)
            } else {
                Result.failure(Exception("Failed to fetch blocked users (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reports a piece of content (story | product | message | user) to the
     * moderation queue. Used by the story viewer, product detail and message
     * long-press "Report" actions.
     */
    suspend fun reportContent(
        targetType: String,
        targetId: String,
        reason: String = ""
    ): Result<Unit> {
        return try {
            val body = buildJsonObject {
                put("target_type", targetType)
                put("target_id", targetId)
                if (reason.isNotBlank()) put("reason", reason)
            }
            api.reportContent(body)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w("ChatRepository", "Failed to report $targetType: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Submits a report for a user to the backend and optionally blocks them.
     */
    suspend fun reportUser(
        reportedUserId: String,
        reportedUserName: String = "",
        conversationId: String? = null,
        reason: String,
        details: String = "",
        evidenceMsgIds: List<String> = emptyList(),
        alsoBlock: Boolean = false
    ): Result<Unit> {
        return try {
            val body = buildJsonObject {
                put("reported_user_id", reportedUserId)
                if (!conversationId.isNullOrBlank()) {
                    put("conversation_id", conversationId)
                }
                put("reason", reason)
                if (details.isNotBlank()) {
                    put("details", details)
                }
                if (evidenceMsgIds.isNotEmpty()) {
                    put("evidence_msg_ids", buildJsonArray {
                        evidenceMsgIds.forEach { add(JsonPrimitive(it)) }
                    })
                }
            }

            try {
                api.reportUser(body)
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to submit report online: ${e.message}")
            }

            if (alsoBlock) {
                blockUser(reportedUserId, reportedUserName)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (alsoBlock) {
                blockUser(reportedUserId, reportedUserName)
            }
            Result.success(Unit)
        }
    }
}
