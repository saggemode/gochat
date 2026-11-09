package com.example.gochat.core.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.gochat.data.api.TokenManager

import com.example.gochat.data.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.whispersystems.libsignal.*
import org.whispersystems.libsignal.protocol.PreKeySignalMessage
import org.whispersystems.libsignal.protocol.SignalMessage
import org.whispersystems.libsignal.state.PreKeyBundle
import org.whispersystems.libsignal.util.KeyHelper
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.IdentityKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepo: AuthRepository,
    private val tokenManager: TokenManager
) {

    private val signalStore = SignalStore(context)

    suspend fun initializeAndRegisterKeys() {
        if (tokenManager.userId == null) return

        withContext(Dispatchers.IO) {
            val identityKeyPair = signalStore.identityKeyPair
            val registrationId = signalStore.localRegistrationId

            // CRITICAL: generate the signed prekey and one-time prekeys ONCE and keep
            // them. This runs on every app launch; regenerating here (the old behavior)
            // rotated the PRIVATE keys each launch, so any X3DH handshake another device
            // built against the previously-published bundle could no longer complete —
            // the recipient saw permanent "encrypted message" ciphertext. We reuse the
            // stored keys when present and only generate the first time; we still
            // re-publish the SAME public keys each launch so the server stays in sync.
            val signedPreKeyId = 1
            val signedPreKeyExisted = signalStore.containsSignedPreKey(signedPreKeyId)
            val signedPreKey = if (signedPreKeyExisted) {
                signalStore.loadSignedPreKey(signedPreKeyId)
            } else {
                KeyHelper.generateSignedPreKey(identityKeyPair, signedPreKeyId).also {
                    signalStore.storeSignedPreKey(signedPreKeyId, it)
                }
            }

            val existingOtkIds = (1..20).filter { signalStore.containsPreKey(it) }
            val otksExisted = existingOtkIds.isNotEmpty()
            val oneTimePreKeys = if (otksExisted) {
                existingOtkIds.map { signalStore.loadPreKey(it) }
            } else {
                KeyHelper.generatePreKeys(1, 20).also { keys ->
                    keys.forEach { signalStore.storePreKey(it.id, it) }
                }
            }

            // CRITICAL: publish to the server ONLY when we just created keys (first init,
            // or a full regeneration once all one-time prekeys are used up). The server's
            // upload is DELETE-all-then-insert, so re-publishing the SAME set on every
            // launch RESTORES one-time prekeys the server already handed out (consumed) —
            // the next sender then grabs a "fresh" prekey the recipient no longer holds,
            // and decryption dies with "no such prekey". Skipping re-upload lets the
            // server's prekey set only ever shrink (via consumption), so every offered
            // prekey still has a matching private key on this device. Identity + signed
            // prekey are stable and already on the server from the first upload.
            if (!signedPreKeyExisted || !otksExisted) {
                val otks = oneTimePreKeys.map {
                    buildJsonObject {
                        put("key_id", it.id)
                        put("public_key", Base64.encodeToString(it.keyPair.publicKey.serialize(), Base64.DEFAULT))
                    }
                }
                authRepo.uploadE2EEKeys(
                    registrationId = registrationId,
                    identityKey = Base64.encodeToString(identityKeyPair.publicKey.serialize(), Base64.DEFAULT),
                    signedPreKey = Base64.encodeToString(signedPreKey.keyPair.publicKey.serialize(), Base64.DEFAULT),
                    signedPreKeySignature = Base64.encodeToString(signedPreKey.signature, Base64.DEFAULT),
                    oneTimeKeys = otks
                ).onSuccess { assignedDeviceNumber ->
                    // Persist the server-assigned device_number as our Signal deviceId.
                    // First/primary device = 1 → single-device installs are unchanged;
                    // a second linked device gets 2+, so its envelope entries never
                    // collide with the primary's. This makes localDeviceNumber() real.
                    persistDeviceNumber(assignedDeviceNumber)
                }
            }
        }
    }

    suspend fun encryptMessage(targetUserId: String, plainText: String): String = withContext(Dispatchers.IO) {
        if (targetUserId.isBlank()) return@withContext plainText
        try {
            val address = SignalProtocolAddress(targetUserId, 1)
            if (!signalStore.containsSession(address)) {
                establishSession(targetUserId)
            }
            if (signalStore.containsSession(address)) {
                val sessionCipher = SessionCipher(signalStore, address)
                val ciphertext = sessionCipher.encrypt(plainText.toByteArray(Charsets.UTF_8))
                // Use NO_WRAP to ensure the entire ciphertext is a single string without newlines
                return@withContext Base64.encodeToString(ciphertext.serialize(), Base64.NO_WRAP)
            }
            // No session could be established (recipient keys unavailable). Caller
            // treats unchanged plaintext as "encryption failed" and fails the message.
            Log.w("EncryptionManager", "No Signal session for $targetUserId; could not encrypt")
            plainText
        } catch (e: Exception) {
            Log.e("EncryptionManager", "Encryption failed for $targetUserId: ${e.message}")
            plainText 
        }
    }

    /** Human-readable reason the LAST decryptMessage() attempt failed ("" on success).
     *  Surfaced on-screen for diagnosis. */
    @Volatile
    var lastDecryptDiagnostic: String = ""
        private set

    /** Whether a Signal session already exists with this user (handshake completed). */
    fun hasSession(userId: String): Boolean = try {
        signalStore.containsSession(SignalProtocolAddress(userId, 1))
    } catch (_: Exception) { false }

    /**
     * Tear down the Signal session with [userId] so the NEXT outgoing message starts a
     * fresh X3DH handshake (a PreKeySignalMessage the peer can always process). Used by
     * session self-heal: when a peer can't decrypt our messages (their session diverged
     * — e.g. we reinstalled and re-keyed), they ask us to reset and we rebuild cleanly.
     */
    suspend fun resetSession(userId: String) = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext
        try {
            signalStore.deleteSession(SignalProtocolAddress(userId, 1))
            Log.i("EncryptionManager", "Session with $userId reset; next message will re-handshake")
        } catch (e: Exception) {
            Log.w("EncryptionManager", "resetSession failed for $userId: ${e.message}")
        }
    }

    // Idempotency cache: ciphertext → plaintext. A Signal message can only be run
    // through the ratchet ONCE; a second attempt throws DuplicateMessageException
    // ("received message with old counter"). The same ciphertext can legitimately
    // reach us more than once (duplicate WS/FCM/refresh delivery, the same message
    // arriving under different local ids, OR — critically — after the app is killed
    // and reopened, when a sync re-feeds an already-decrypted message). Returning the
    // cached plaintext without touching the ratchet makes decryption idempotent.
    //
    // PERSISTED to SharedPreferences (keyed by a hash of the ciphertext) so it
    // SURVIVES process death — otherwise force-closing the app and reopening would
    // re-decrypt and desync the ratchet. The in-memory map is a fast front for it.
    private val decryptCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 500
    }
    private val dedupPrefs by lazy { context.getSharedPreferences("e2ee_decrypt_dedup", Context.MODE_PRIVATE) }

    private fun dedupKey(ciphertext: String): String = try {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(ciphertext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(d, Base64.NO_WRAP or Base64.URL_SAFE)
    } catch (_: Exception) { ciphertext.take(64) }

    private fun cacheGet(ciphertext: String): String? {
        val k = dedupKey(ciphertext)
        synchronized(decryptCache) { decryptCache[k] }?.let { return it }
        return try { dedupPrefs.getString(k, null)?.also { p -> synchronized(decryptCache) { decryptCache[k] = p } } } catch (_: Exception) { null }
    }

    private fun cachePut(ciphertext: String, plaintext: String) {
        val k = dedupKey(ciphertext)
        synchronized(decryptCache) { decryptCache[k] = plaintext }
        try { dedupPrefs.edit().putString(k, plaintext).apply() } catch (_: Exception) {}
    }

    fun decryptMessage(senderUserId: String, base64Ciphertext: String, senderDeviceNumber: Int = 1): String {
        if (base64Ciphertext.isBlank() || senderUserId.isBlank()) return base64Ciphertext

        val cacheKey = base64Ciphertext.trim()
        cacheGet(cacheKey)?.let {
            lastDecryptDiagnostic = ""
            return it
        }

        val address = SignalProtocolAddress(senderUserId, if (senderDeviceNumber > 0) senderDeviceNumber else 1)
        val decodedBytes = try {
            // Try both DEFAULT and NO_WRAP implicitly via DEFAULT
            Base64.decode(cacheKey, Base64.DEFAULT)
        } catch (e: Exception) {
            lastDecryptDiagnostic = "base64 decode failed"
            Log.w("EncryptionManager", "Base64 decode failed for message from $senderUserId")
            return base64Ciphertext
        }

        // 1. Try PreKeySignalMessage (initial session setup / first contact).
        val preKeyMsg = try { PreKeySignalMessage(decodedBytes) } catch (_: Exception) { null }
        if (preKeyMsg != null) {
            try {
                val sessionCipher = SessionCipher(signalStore, address)
                val out = String(sessionCipher.decrypt(preKeyMsg), Charsets.UTF_8)
                lastDecryptDiagnostic = ""
                cachePut(cacheKey, out)
                return out
            } catch (e: Exception) {
                // Do NOT delete the session and rebuild here: that needs the one-time
                // prekey again, which is already consumed → "no such prekey", and it
                // would also destroy a perfectly good established session. Just report;
                // the message is retried on the next sync and succeeds once the session
                // is in place.
                lastDecryptDiagnostic = "prekey(handshake) decrypt failed: ${e.message}"
                Log.e("EncryptionManager", "PreKey decrypt failed for $senderUserId: ${e.message}")
                return base64Ciphertext
            }
        }

        // 2. Try standard SignalMessage
        try {
            if (signalStore.containsSession(address)) {
                val sessionCipher = SessionCipher(signalStore, address)
                val ciphertext = SignalMessage(decodedBytes)
                val decrypted = sessionCipher.decrypt(ciphertext)
                lastDecryptDiagnostic = ""
                val out = String(decrypted, Charsets.UTF_8)
                cachePut(cacheKey, out)
                return out
            } else {
                lastDecryptDiagnostic = "no session with sender (handshake never completed — sender used a stale/mismatched session, or this isn't a first-contact message)"
                Log.w("EncryptionManager", "No session for $senderUserId to decrypt standard message")
            }
        } catch (e: Exception) {
            lastDecryptDiagnostic = "session decrypt failed (ratchet desync / wrong key): ${e.message}"
            Log.e("EncryptionManager", "Decryption failed for $senderUserId: ${e.message}")
        }

        return base64Ciphertext
    }


    suspend fun establishSession(targetUserId: String) = withContext(Dispatchers.IO) {
        val address = SignalProtocolAddress(targetUserId, 1)
        if (signalStore.containsSession(address)) return@withContext

        authRepo.getE2EEKeys(targetUserId).onSuccess { json ->
            try {
                val prekeySignedB64 = json["prekey_signed"]?.jsonPrimitive?.contentOrNull
                val prekeySigB64 = json["prekey_signature"]?.jsonPrimitive?.contentOrNull
                val prekeyIdentB64 = json["prekey_identity"]?.jsonPrimitive?.contentOrNull

                if (prekeySignedB64.isNullOrBlank() || prekeySigB64.isNullOrBlank() || prekeyIdentB64.isNullOrBlank()) {
                    return@onSuccess
                }

                val otk = json["one_time_key"]?.jsonObject
                val otkId = otk?.get("key_id")?.jsonPrimitive?.intOrNull ?: 0
                val otkPublicB64 = otk?.get("public_key")?.jsonPrimitive?.contentOrNull
                val otkKey = if (!otkPublicB64.isNullOrBlank()) {
                    Curve.decodePoint(Base64.decode(otkPublicB64, Base64.DEFAULT), 0)
                } else null

                val registrationId = json["registration_id"]?.jsonPrimitive?.intOrNull ?: 1
                val signedPreKeyId = json["signed_prekey_id"]?.jsonPrimitive?.intOrNull ?: 1

                val bundle = PreKeyBundle(
                    registrationId,
                    1,
                    otkId,
                    otkKey,
                    signedPreKeyId,
                    Curve.decodePoint(Base64.decode(prekeySignedB64, Base64.DEFAULT), 0),
                    Base64.decode(prekeySigB64, Base64.DEFAULT),
                    IdentityKey(Base64.decode(prekeyIdentB64, Base64.DEFAULT), 0)
                )

                val sessionBuilder = SessionBuilder(signalStore, address)
                sessionBuilder.process(bundle)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ── Multi-device fan-out (Phase 2) ──────────────────────────────────────
    // DORMANT until device-linking (Phase 3) exists: today every recipient has
    // exactly ONE device, so encryptForUser() takes the single-device branch and
    // returns a raw ciphertext byte-for-byte identical to encryptMessage() — the
    // stable 1:1 path is untouched. The envelope branch only activates once a
    // recipient genuinely has >1 device. See MULTI_DEVICE_PLAN.md §Phase 2.

    /** Marker prefixing a multi-device envelope so receivers can tell it apart from
     *  a raw single-device ciphertext (which is bare base64, never starts with this). */
    private val mdEnvelopeMarker = "\u0001E2EEMD1:"

    /** This device's server-assigned device_number (used as the Signal deviceId).
     *  First device = 1, so existing single-device installs keep deviceId 1 and the
     *  stored sessions/identities never move. */
    fun localDeviceNumber(): Int = try {
        context.getSharedPreferences("e2ee_prefs", Context.MODE_PRIVATE)
            .getInt("e2ee_device_number", 1).let { if (it > 0) it else 1 }
    } catch (_: Exception) { 1 }

    /** Persist the server-assigned device_number (this device's Signal deviceId). */
    private fun persistDeviceNumber(n: Int) {
        if (n <= 0) return
        try {
            context.getSharedPreferences("e2ee_prefs", Context.MODE_PRIVATE)
                .edit().putInt("e2ee_device_number", n).apply()
        } catch (_: Exception) {}
        // A device_number > 1 means we're a companion → the account is multi-device.
        if (n > 1) markAccountMultiDevice()
    }

    /**
     * Whether THIS ACCOUNT is known to have more than one device. Sticky once set.
     * This is the go-live gate for the fan-out: while it's false (every account today),
     * sends take the stable 1:1 path with NO network fetch and NO one-time-prekey
     * consumption. It flips true when this device is a companion (device_number > 1)
     * or when the "Encryption devices" screen observes more than one registered device.
     * Using an account-level signal (not just the recipient) is what closes the
     * sender-multi-device ratchet-collision gap: a multi-device sender ALWAYS uses the
     * envelope, so the sender device (`sd`) is carried and the receiver builds the
     * session against the correct (senderId, sd) address.
     */
    fun isAccountMultiDevice(): Boolean = try {
        localDeviceNumber() > 1 ||
            context.getSharedPreferences("e2ee_prefs", Context.MODE_PRIVATE)
                .getBoolean("e2ee_account_multidevice", false)
    } catch (_: Exception) { false }

    /** Mark the account multi-device (sticky). Called from the linked-devices screen. */
    fun markAccountMultiDevice() {
        try {
            context.getSharedPreferences("e2ee_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("e2ee_account_multidevice", true).apply()
        } catch (_: Exception) {}
    }

    /** Record that a companion device (by device_id) was paired and trusted, storing
     *  its identity key for later change-detection. Also flips the account to
     *  multi-device so sends fan out. Idempotent. */
    fun recordPairedDevice(deviceId: String, identityKeyB64: String) {
        if (deviceId.isBlank()) return
        try {
            context.getSharedPreferences("e2ee_paired_devices", Context.MODE_PRIVATE)
                .edit().putString(deviceId, identityKeyB64).apply()
        } catch (_: Exception) {}
        markAccountMultiDevice()
    }

    /** The identity key (base64) we recorded when [deviceId] was paired/trusted, or
     *  null if we've never verified it. Used to detect a changed key on a known device. */
    fun pairedIdentityFor(deviceId: String): String? = try {
        context.getSharedPreferences("e2ee_paired_devices", Context.MODE_PRIVATE)
            .getString(deviceId, null)
    } catch (_: Exception) { null }

    /** True if [content] is a multi-device envelope rather than a raw ciphertext. */
    fun isEnvelope(content: String): Boolean = content.startsWith(mdEnvelopeMarker)

    // ── Device pairing (Phase 3 QR) ─────────────────────────────────────────

    /** This device's Signal identity public key (base64), the stable per-install
     *  identity a companion presents for trust verification during QR pairing. */
    fun localDeviceIdentityB64(): String = try {
        Base64.encodeToString(signalStore.identityKeyPair.publicKey.serialize(), Base64.NO_WRAP)
    } catch (_: Exception) { "" }

    /** A short, human-comparable fingerprint of an identity public key (base64 in):
     *  16 uppercase hex chars grouped in 4s, e.g. "A1B2 C3D4 E5F6 7890". Returns "" on
     *  bad input. Two devices showing the SAME fingerprint hold the same identity key. */
    fun identityFingerprint(identityB64: String): String {
        if (identityB64.isBlank()) return ""
        return try {
            val raw = Base64.decode(identityB64.trim(), Base64.DEFAULT)
            val hex = java.security.MessageDigest.getInstance("SHA-256").digest(raw)
                .joinToString("") { "%02X".format(it) }.take(16)
            hex.chunked(4).joinToString(" ")
        } catch (_: Exception) { "" }
    }

    /** Fetch (from the server) the identity public key this account has on record for
     *  the device numbered [deviceNumber], so a scanned companion fingerprint can be
     *  checked against what the server actually stores (MITM / key-swap detection).
     *  Returns "" if unavailable. */
    suspend fun serverIdentityForOwnDevice(deviceNumber: Int): String = withContext(Dispatchers.IO) {
        val myId = tokenManager.userId ?: return@withContext ""
        var found = ""
        authRepo.getE2EEKeys(myId).onSuccess { json ->
            val bundles = json["bundles"]?.jsonArray ?: return@onSuccess
            for (b in bundles) {
                val o = b.jsonObject
                if ((o["device_number"]?.jsonPrimitive?.intOrNull ?: 0) == deviceNumber) {
                    found = o["prekey_identity"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    break
                }
            }
        }
        found
    }

    /** Fetch all of [targetUserId]'s per-device bundles (Phase 2 `bundles` list).
     *  Returns null when the server supplies no list (older server, or a single-device
     *  response) so callers fall back cleanly to the legacy 1:1 path. */
    private suspend fun fetchDeviceBundles(targetUserId: String): JsonArray? = withContext(Dispatchers.IO) {
        var result: JsonArray? = null
        authRepo.getE2EEKeys(targetUserId).onSuccess { json ->
            result = json["bundles"]?.jsonArray?.takeIf { it.isNotEmpty() }
        }
        result
    }

    /**
     * Encrypt [plainText] for [targetUserId]. SINGLE-DEVICE FAST PATH: while the
     * account is not multi-device (every account today), this delegates straight to
     * `encryptMessage()` — the exact legacy path, with NO device-bundle fetch, so it
     * neither changes behavior nor consumes one-time prekeys on each send. Only once
     * the account is known multi-device do we fan out to every device (recipient's
     * devices + the sender's own other devices, for self-sync) as a marked envelope.
     */
    suspend fun encryptForUser(targetUserId: String, plainText: String): String = withContext(Dispatchers.IO) {
        if (targetUserId.isBlank()) return@withContext plainText
        // Gate: no fan-out (and no key fetch) unless this account is multi-device.
        if (!isAccountMultiDevice()) {
            return@withContext encryptMessage(targetUserId, plainText)
        }
        val bundles = fetchDeviceBundles(targetUserId)
        // Recipient still single-device: a raw ciphertext reaches their one device,
        // but we must self-sync to our OWN other devices — so fall through to the
        // envelope below whenever self-sync targets exist; if the recipient has one
        // device and we somehow have none to self-sync, the legacy path is fine.
        if (bundles == null) {
            return@withContext encryptMessage(targetUserId, plainText)
        }
        try {
            val myId = tokenManager.userId
            val myDev = localDeviceNumber()
            val perDevice = buildJsonObject {
                // recipient's devices
                for (b in bundles) {
                    val obj = b.jsonObject
                    val devNum = obj["device_number"]?.jsonPrimitive?.intOrNull ?: continue
                    val ct = encryptForDevice(targetUserId, devNum, obj, plainText) ?: continue
                    put("$targetUserId:$devNum", ct)
                }
                // sender's own OTHER devices (self-sync) — best effort
                if (!myId.isNullOrBlank()) {
                    fetchDeviceBundles(myId)?.forEach { sb ->
                        val obj = sb.jsonObject
                        val devNum = obj["device_number"]?.jsonPrimitive?.intOrNull ?: return@forEach
                        if (devNum == myDev) return@forEach  // never encrypt to ourselves
                        val ct = encryptForDevice(myId, devNum, obj, plainText) ?: return@forEach
                        put("$myId:$devNum", ct)
                    }
                }
            }
            if (perDevice.isEmpty()) return@withContext encryptMessage(targetUserId, plainText)
            val envelope = buildJsonObject {
                put("v", 1)
                put("sd", myDev)          // sender's device number (Signal deviceId)
                put("to", perDevice)      // "<userId>:<deviceNumber>" → ciphertext
            }
            mdEnvelopeMarker + envelope.toString()
        } catch (e: Exception) {
            Log.e("EncryptionManager", "encryptForUser fan-out failed, falling back to 1:1: ${e.message}")
            encryptMessage(targetUserId, plainText)
        }
    }

    /** Encrypt [plainText] to a single (userId, deviceNumber), establishing the
     *  per-device session from [bundleObj] on first contact. Null on any failure. */
    private fun encryptForDevice(userId: String, deviceNumber: Int, bundleObj: JsonObject, plainText: String): String? {
        return try {
            val address = SignalProtocolAddress(userId, if (deviceNumber > 0) deviceNumber else 1)
            if (!signalStore.containsSession(address)) {
                establishSessionForDevice(address, bundleObj) ?: return null
            }
            if (!signalStore.containsSession(address)) return null
            val cipher = SessionCipher(signalStore, address)
            val ct = cipher.encrypt(plainText.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(ct.serialize(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w("EncryptionManager", "encryptForDevice($userId:$deviceNumber) failed: ${e.message}")
            null
        }
    }

    /** Build a Signal session for ONE device from its per-device bundle JSON. */
    private fun establishSessionForDevice(address: SignalProtocolAddress, obj: JsonObject): Unit? {
        return try {
            val prekeySignedB64 = obj["prekey_signed"]?.jsonPrimitive?.contentOrNull
            val prekeySigB64 = obj["prekey_signature"]?.jsonPrimitive?.contentOrNull
            val prekeyIdentB64 = obj["prekey_identity"]?.jsonPrimitive?.contentOrNull
            if (prekeySignedB64.isNullOrBlank() || prekeySigB64.isNullOrBlank() || prekeyIdentB64.isNullOrBlank()) return null

            val otk = obj["one_time_key"]?.jsonObject
            val otkId = otk?.get("key_id")?.jsonPrimitive?.intOrNull ?: 0
            val otkPublicB64 = otk?.get("public_key")?.jsonPrimitive?.contentOrNull
            val otkKey = if (!otkPublicB64.isNullOrBlank()) Curve.decodePoint(Base64.decode(otkPublicB64, Base64.DEFAULT), 0) else null

            val registrationId = obj["registration_id"]?.jsonPrimitive?.intOrNull ?: 1
            val signedPreKeyId = obj["signed_prekey_id"]?.jsonPrimitive?.intOrNull ?: 1

            val bundle = PreKeyBundle(
                registrationId,
                address.deviceId,
                otkId,
                otkKey,
                signedPreKeyId,
                Curve.decodePoint(Base64.decode(prekeySignedB64, Base64.DEFAULT), 0),
                Base64.decode(prekeySigB64, Base64.DEFAULT),
                IdentityKey(Base64.decode(prekeyIdentB64, Base64.DEFAULT), 0)
            )
            SessionBuilder(signalStore, address).process(bundle)
            Unit
        } catch (e: Exception) {
            Log.w("EncryptionManager", "establishSessionForDevice(${address.name}:${address.deviceId}) failed: ${e.message}")
            null
        }
    }

    /**
     * Decrypt a multi-device envelope: pick THIS device's ciphertext (by my userId +
     * device_number) and run it through the normal decrypt path (dedup/ratchet/mutex
     * guards all still apply, keyed on the per-device ciphertext). Returns the content
     * unchanged if our entry isn't present or parsing fails.
     */
    fun decryptEnvelope(senderUserId: String, envelope: String): String {
        if (!envelope.startsWith(mdEnvelopeMarker)) return envelope
        val myId = tokenManager.userId ?: return envelope
        return try {
            val json = Json.parseToJsonElement(envelope.substring(mdEnvelopeMarker.length)).jsonObject
            val senderDev = json["sd"]?.jsonPrimitive?.intOrNull ?: 1
            val to = json["to"]?.jsonObject ?: return envelope
            val ct = to["$myId:${localDeviceNumber()}"]?.jsonPrimitive?.contentOrNull
                ?: to["$myId:1"]?.jsonPrimitive?.contentOrNull   // fallback: device 1
                ?: return envelope
            decryptMessage(senderUserId, ct, senderDev)
        } catch (e: Exception) {
            Log.w("EncryptionManager", "decryptEnvelope failed: ${e.message}")
            envelope
        }
    }

    // ── Group messaging (Signal "sender keys") ──────────────────────────────
    // Each member has its own sender key per group. To send, a member creates a
    // sender key and distributes the resulting SenderKeyDistributionMessage
    // (SKDM) to every other member over their pairwise 1:1 session; recipients
    // process it, then can decrypt that member's group messages.

    private fun groupSenderName(groupId: String, senderUserId: String) =
        org.whispersystems.libsignal.groups.SenderKeyName(groupId, SignalProtocolAddress(senderUserId, 1))

    /** Create (or return existing) this device's sender key for [groupId];
     *  returns the base64 SKDM to distribute to members. */
    suspend fun createGroupSenderKey(groupId: String): String? = withContext(Dispatchers.IO) {
        val myId = tokenManager.userId ?: return@withContext null
        try {
            val name = groupSenderName(groupId, myId)
            val builder = org.whispersystems.libsignal.groups.GroupSessionBuilder(signalStore)
            val skdm = builder.create(name)
            Base64.encodeToString(skdm.serialize(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("EncryptionManager", "createGroupSenderKey failed: ${e.message}")
            null
        }
    }

    /** Process a member's SKDM so we can later decrypt their group messages. */
    suspend fun processGroupSenderKey(groupId: String, senderUserId: String, skdmB64: String) = withContext(Dispatchers.IO) {
        try {
            val name = groupSenderName(groupId, senderUserId)
            val skdm = org.whispersystems.libsignal.protocol.SenderKeyDistributionMessage(Base64.decode(skdmB64, Base64.NO_WRAP))
            org.whispersystems.libsignal.groups.GroupSessionBuilder(signalStore).process(name, skdm)
        } catch (e: Exception) {
            Log.e("EncryptionManager", "processGroupSenderKey failed: ${e.message}")
        }
    }

    /** Encrypt a group message with this device's sender key. Returns null if no
     *  sender key exists yet (caller must create + distribute one first). */
    suspend fun encryptGroupMessage(groupId: String, plainText: String): String? = withContext(Dispatchers.IO) {
        val myId = tokenManager.userId ?: return@withContext null
        try {
            val cipher = org.whispersystems.libsignal.groups.GroupCipher(signalStore, groupSenderName(groupId, myId))
            Base64.encodeToString(cipher.encrypt(plainText.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("EncryptionManager", "encryptGroupMessage failed: ${e.message}")
            null
        }
    }

    /** Decrypt a group message from [senderUserId]. Returns null if we don't yet
     *  hold that sender's key (their SKDM hasn't arrived/processed). */
    fun decryptGroupMessage(groupId: String, senderUserId: String, base64Ciphertext: String): String? {
        return try {
            val cipher = org.whispersystems.libsignal.groups.GroupCipher(signalStore, groupSenderName(groupId, senderUserId))
            String(cipher.decrypt(Base64.decode(base64Ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("EncryptionManager", "decryptGroupMessage failed (no sender key yet?): ${e.message}")
            null
        }
    }
}
