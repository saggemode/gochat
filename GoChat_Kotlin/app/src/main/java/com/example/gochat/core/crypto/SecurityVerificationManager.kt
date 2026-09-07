package com.example.gochat.core.crypto

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurityVerificationManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("e2ee_verification", Context.MODE_PRIVATE)
    private val verifiedPrefix = "e2ee_verified_"

    /**
     * Generates a deterministic 60-digit safety number grouped into 12 blocks of 5 digits.
     * Ported from Flutter implementation for compatibility.
     */
    fun generateSafetyNumber(
        myUserId: String,
        peerUserId: String,
        conversationId: String,
        myPin: String?,
        peerPin: String?
    ): String {
        val ids = listOf(myUserId.trim(), peerUserId.trim()).sorted()
        val pins = listOf((myPin ?: "").trim(), (peerPin ?: "").trim()).sorted()
        val seed = "${ids[0]}:${pins[0]}--${ids[1]}:${pins[1]}--${conversationId.trim()}--gochat_e2ee_salt_v1"

        val h1 = MessageDigest.getInstance("SHA-512").digest(seed.toByteArray())
        val h2 = MessageDigest.getInstance("SHA-512").digest(h1)
        val combined = h1 + h2

        val sb = StringBuilder()
        var i = 0
        while (i < combined.size - 1 && sb.length < 60) {
            val b1 = combined[i].toInt() and 0xFF
            val b2 = combined[i+1].toInt() and 0xFF
            val valInt = ((b1 shl 8) or b2) % 100000
            sb.append(String.format(Locale.US, "%05d", valInt))
            i += 2
        }

        val raw60 = sb.toString().substring(0, 60)
        val chunks = mutableListOf<String>()
        for (j in 0 until 60 step 5) {
            chunks.add(raw60.substring(j, j + 5))
        }
        return chunks.joinToString(" ")
    }

    fun generateQrPayload(conversationId: String, safetyNumber: String): String {
        val rawNumber = safetyNumber.replace(" ", "")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(rawNumber.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)
        return "gochat-e2ee://v1/$conversationId/$hash"
    }

    fun validateScannedQr(
        scannedData: String,
        expectedConversationId: String,
        expectedSafetyNumber: String
    ): Boolean {
        val expectedPayload = generateQrPayload(expectedConversationId, expectedSafetyNumber)
        if (scannedData.trim() == expectedPayload.trim()) return true

        // Also support direct match on safety number digits
        val cleanScanned = scannedData.replace(Regex("\\s+"), "")
        val cleanExpected = expectedSafetyNumber.replace(" ", "")
        return cleanScanned == cleanExpected
    }

    fun isVerified(conversationId: String): Boolean {
        return prefs.getBoolean("$verifiedPrefix$conversationId", false)
    }

    fun setVerified(conversationId: String, verified: Boolean) {
        prefs.edit().putBoolean("$verifiedPrefix$conversationId", verified).apply()
    }
}
