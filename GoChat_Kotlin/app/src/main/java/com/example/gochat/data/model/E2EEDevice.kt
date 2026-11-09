package com.example.gochat.data.model

/**
 * One device registered in the E2EE (Signal-key) device registry — i.e. an
 * install that publishes its own identity + prekeys and receives a copy of every
 * message (multi-device fan-out). This is DISTINCT from [LinkedDevice], which
 * tracks web/login *sessions*. Source: GET /auth/e2ee/devices.
 */
data class E2EEDevice(
    val deviceId: String,
    val deviceNumber: Int,
    val registrationId: Int = 0,
    val createdAt: String = "",
    val lastSeen: String = "",
    /** Device's public identity key (base64), for per-device verification. */
    val identityKey: String = "",
    /** True for the install we're running on (matches our own X-Device-Id). */
    val isCurrent: Boolean = false
) {
    /** Per-device trust state (Phase 6), computed against locally stored pairing. */
    enum class Verification { CURRENT, PRIMARY, VERIFIED, CHANGED, UNVERIFIED }
    /** device_number 1 is the primary device and cannot be unlinked. */
    val isPrimary: Boolean get() = deviceNumber == 1

    val displayName: String
        get() = if (isPrimary) "Primary device" else "Device $deviceNumber"

    /** Short, human-readable id for display (the raw id can be long/opaque). */
    val shortId: String
        get() = deviceId.takeLast(8).ifBlank { "unknown" }
}
