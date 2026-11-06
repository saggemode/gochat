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
            val signedPreKey = if (signalStore.containsSignedPreKey(signedPreKeyId)) {
                signalStore.loadSignedPreKey(signedPreKeyId)
            } else {
                KeyHelper.generateSignedPreKey(identityKeyPair, signedPreKeyId).also {
                    signalStore.storeSignedPreKey(signedPreKeyId, it)
                }
            }

            val existingOtkIds = (1..20).filter { signalStore.containsPreKey(it) }
            val oneTimePreKeys = if (existingOtkIds.isNotEmpty()) {
                existingOtkIds.map { signalStore.loadPreKey(it) }
            } else {
                KeyHelper.generatePreKeys(1, 20).also { keys ->
                    keys.forEach { signalStore.storePreKey(it.id, it) }
                }
            }

            // (Re)publish the current PUBLIC keys so the server can hand them out.
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
            )
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
            plainText
        } catch (e: Exception) {
            Log.e("EncryptionManager", "Encryption failed for $targetUserId: ${e.message}")
            plainText 
        }
    }

    fun decryptMessage(senderUserId: String, base64Ciphertext: String): String {
        if (base64Ciphertext.isBlank() || senderUserId.isBlank()) return base64Ciphertext
        
        val address = SignalProtocolAddress(senderUserId, 1)
        val decodedBytes = try {
            // Try both DEFAULT and NO_WRAP implicitly via DEFAULT
            Base64.decode(base64Ciphertext.trim(), Base64.DEFAULT)
        } catch (e: Exception) {
            Log.w("EncryptionManager", "Base64 decode failed for message from $senderUserId")
            return base64Ciphertext
        }

        // 1. Try PreKeySignalMessage (initial session setup)
        try {
            val sessionCipher = SessionCipher(signalStore, address)
            val ciphertext = PreKeySignalMessage(decodedBytes)
            val decrypted = sessionCipher.decrypt(ciphertext)
            return String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) { }

        // 2. Try standard SignalMessage
        try {
            if (signalStore.containsSession(address)) {
                val sessionCipher = SessionCipher(signalStore, address)
                val ciphertext = SignalMessage(decodedBytes)
                val decrypted = sessionCipher.decrypt(ciphertext)
                return String(decrypted, Charsets.UTF_8)
            } else {
                Log.w("EncryptionManager", "No session for $senderUserId to decrypt standard message")
            }
        } catch (e: Exception) {
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
