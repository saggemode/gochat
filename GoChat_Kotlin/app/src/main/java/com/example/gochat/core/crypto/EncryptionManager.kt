package com.example.gochat.core.crypto

import android.content.Context
import android.util.Base64
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.repository.AuthRepository
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

class EncryptionManager(private val context: Context) {

    private val signalStore = SignalStore(context)
    private val authRepo = AuthRepository(context)
    private val tokenManager = TokenManager.getInstance(context)

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

    fun encryptMessage(targetUserId: String, plainText: String): String {
        try {
            val address = SignalProtocolAddress(targetUserId, 1)
            val sessionCipher = SessionCipher(signalStore, address)
            val ciphertext = sessionCipher.encrypt(plainText.toByteArray())
            return Base64.encodeToString(ciphertext.serialize(), Base64.DEFAULT)
        } catch (e: Exception) {
            return plainText // Fallback to plain if no session exists
        }
    }

    fun decryptMessage(senderUserId: String, base64Ciphertext: String): String {
        if (base64Ciphertext.isBlank()) return base64Ciphertext
        try {
            val address = SignalProtocolAddress(senderUserId, 1)
            if (!signalStore.containsSession(address)) return base64Ciphertext
            val sessionCipher = SessionCipher(signalStore, address)
            val decodedBytes = Base64.decode(base64Ciphertext, Base64.DEFAULT)
            val ciphertext = SignalMessage(decodedBytes)
            val decrypted = sessionCipher.decrypt(ciphertext)
            return String(decrypted)
        } catch (e: Exception) {
            // Might be a PreKeyMessage
            try {
                val address = SignalProtocolAddress(senderUserId, 1)
                val sessionCipher = SessionCipher(signalStore, address)
                val decodedBytes = Base64.decode(base64Ciphertext, Base64.DEFAULT)
                val ciphertext = PreKeySignalMessage(decodedBytes)
                val decrypted = sessionCipher.decrypt(ciphertext)
                return String(decrypted)
            } catch (e2: Exception) {
                // Never corrupt plaintext with "[Encrypted Message]" - preserve original text
                return base64Ciphertext
            }
        }
    }

    suspend fun establishSession(targetUserId: String) = withContext(Dispatchers.IO) {
        val address = SignalProtocolAddress(targetUserId, 1)
        if (signalStore.containsSession(address)) return@withContext

        authRepo.getE2EEKeys(targetUserId).onSuccess { json ->
            try {
                val otk = json["one_time_key"]?.jsonObject
                val bundle = PreKeyBundle(
                    json["registration_id"]?.jsonPrimitive?.intOrNull ?: 0,
                    1,
                    otk?.get("key_id")?.jsonPrimitive?.intOrNull ?: 0,
                    Curve.decodePoint(Base64.decode(otk?.get("public_key")?.jsonPrimitive?.contentOrNull, Base64.DEFAULT), 0),
                    json["signed_prekey_id"]?.jsonPrimitive?.intOrNull ?: 0,
                    Curve.decodePoint(Base64.decode(json["prekey_signed"]?.jsonPrimitive?.contentOrNull, Base64.DEFAULT), 0),
                    Base64.decode(json["prekey_signature"]?.jsonPrimitive?.contentOrNull, Base64.DEFAULT),
                    IdentityKey(Base64.decode(json["prekey_identity"]?.jsonPrimitive?.contentOrNull, Base64.DEFAULT), 0)
                )

                val sessionBuilder = SessionBuilder(signalStore, address)
                sessionBuilder.process(bundle)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
