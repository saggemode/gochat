package com.example.gochat.core.crypto

import android.content.Context
import android.util.Base64
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
            
            // Generate Signed PreKey
            val signedPreKeyId = 1
            val signedPreKey = KeyHelper.generateSignedPreKey(identityKeyPair, signedPreKeyId)
            signalStore.storeSignedPreKey(signedPreKeyId, signedPreKey)

            // Generate One-Time PreKeys
            val oneTimePreKeys = KeyHelper.generatePreKeys(1, 20)
            oneTimePreKeys.forEach { signalStore.storePreKey(it.id, it) }

            // Upload to server
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
        try {
            val address = SignalProtocolAddress(targetUserId, 1)
            if (!signalStore.containsSession(address)) {
                establishSession(targetUserId)
            }
            if (signalStore.containsSession(address)) {
                val sessionCipher = SessionCipher(signalStore, address)
                val ciphertext = sessionCipher.encrypt(plainText.toByteArray(Charsets.UTF_8))
                return@withContext Base64.encodeToString(ciphertext.serialize(), Base64.NO_WRAP)
            }
            plainText
        } catch (e: Exception) {
            plainText // Fallback to plain if no session exists or error occurs
        }
    }


    fun decryptMessage(senderUserId: String, base64Ciphertext: String): String {
        if (base64Ciphertext.isBlank()) return base64Ciphertext
        val address = SignalProtocolAddress(senderUserId, 1)
        val decodedBytes = try {
            Base64.decode(base64Ciphertext, Base64.DEFAULT)
        } catch (_: Exception) {
            return base64Ciphertext
        }

        // 1. Try PreKeySignalMessage (establishes new session on recipient device for initial messages)
        try {
            val sessionCipher = SessionCipher(signalStore, address)
            val ciphertext = PreKeySignalMessage(decodedBytes)
            val decrypted = sessionCipher.decrypt(ciphertext)
            return String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            // Not a PreKeySignalMessage, fall through to check standard SignalMessage
        }

        // 2. Try standard SignalMessage for existing established sessions
        try {
            if (signalStore.containsSession(address)) {
                val sessionCipher = SessionCipher(signalStore, address)
                val ciphertext = SignalMessage(decodedBytes)
                val decrypted = sessionCipher.decrypt(ciphertext)
                return String(decrypted, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            // Decryption failed or not an encrypted payload
        }

        // Never corrupt plaintext with error markers; preserve original text
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
}
