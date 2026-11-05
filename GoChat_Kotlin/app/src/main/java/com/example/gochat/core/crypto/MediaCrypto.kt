package com.example.gochat.core.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric encryption for media blobs in E2EE chats.
 *
 * Model (WhatsApp-style): the sender generates a random per-blob AES-256 key,
 * encrypts the media bytes with AES-GCM, uploads only the ciphertext to the
 * media service, and sends the key inside the end-to-end-encrypted message
 * (so the media service and the server never see the key or the plaintext).
 * The recipient decrypts the message, gets the key, downloads the ciphertext,
 * and decrypts it locally.
 *
 * This is the primitive only. Wiring it into the upload/download/render
 * pipeline (incl. a Coil fetcher for encrypted images and the media viewer)
 * is a separate, device-tested step.
 */
object MediaCrypto {

    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12
    private const val KEY_LEN = 32 // AES-256

    /** A freshly generated per-blob key + IV, and the key material to embed in the message. */
    data class Sealed(val ciphertext: ByteArray, val keyB64: String)

    /** Encrypt [plain] under a new random key; returns ciphertext + the base64 key|iv to send E2EE. */
    fun seal(plain: ByteArray): Sealed {
        val key = ByteArray(KEY_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain) // ciphertext || 16-byte tag
        // Embed key material as base64(iv || key); the message carrying this is itself E2EE.
        val keyB64 = Base64.encodeToString(iv + key, Base64.NO_WRAP)
        return Sealed(ct, keyB64)
    }

    /** Decrypt [ciphertext] using the base64(iv || key) delivered in the E2EE message. */
    fun open(ciphertext: ByteArray, keyB64: String): ByteArray {
        val raw = Base64.decode(keyB64, Base64.NO_WRAP)
        require(raw.size == IV_LEN + KEY_LEN) { "bad media key length" }
        val iv = raw.copyOfRange(0, IV_LEN)
        val key = raw.copyOfRange(IV_LEN, IV_LEN + KEY_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
