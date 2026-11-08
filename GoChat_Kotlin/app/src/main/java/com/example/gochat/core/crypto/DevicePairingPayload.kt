package com.example.gochat.core.crypto

import android.net.Uri

/**
 * The QR payload a device shows during E2EE multi-device pairing. It identifies the
 * device presenting it (same account on both sides — both are logged in) so the other
 * device can authorize it and verify its identity fingerprint.
 *
 * Wire form (compact, URI): gochat-e2ee-device://pair?uid=..&dev=..&did=..&ikf=..&ik=..
 */
data class DevicePairingPayload(
    val userId: String,
    val deviceNumber: Int,
    val deviceId: String,
    /** Short human-comparable fingerprint of [identityKeyB64]. */
    val fingerprint: String,
    /** Full identity public key (base64), for the scanner to store/verify. */
    val identityKeyB64: String
) {
    fun toQrString(): String =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendQueryParameter("uid", userId)
            .appendQueryParameter("dev", deviceNumber.toString())
            .appendQueryParameter("did", deviceId)
            .appendQueryParameter("ikf", fingerprint)
            .appendQueryParameter("ik", identityKeyB64)
            .build()
            .toString()

    companion object {
        const val SCHEME = "gochat-e2ee-device"
        const val HOST = "pair"

        /** Parse a scanned QR string; null if it isn't a GoChat device-pairing payload. */
        fun parse(scanned: String?): DevicePairingPayload? {
            val data = scanned?.trim().orEmpty()
            if (data.isBlank()) return null
            return try {
                val uri = Uri.parse(data)
                if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
                val uid = uri.getQueryParameter("uid").orEmpty()
                val did = uri.getQueryParameter("did").orEmpty()
                val dev = uri.getQueryParameter("dev")?.toIntOrNull() ?: 0
                if (uid.isBlank() || did.isBlank() || dev <= 0) return null
                DevicePairingPayload(
                    userId = uid,
                    deviceNumber = dev,
                    deviceId = did,
                    fingerprint = uri.getQueryParameter("ikf").orEmpty(),
                    identityKeyB64 = uri.getQueryParameter("ik").orEmpty()
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
