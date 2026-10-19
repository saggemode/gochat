package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LinkedDevice(
    val id: String = "",
    @SerialName("device_name") val deviceName: String = "",
    val platform: String = "android",
    val os: String = "",
    val browser: String = "",
    @SerialName("ip_address") val ipAddress: String = "",
    @SerialName("last_active_at") val lastActiveAt: String = "",
    @SerialName("is_current") val isCurrent: Boolean = false,
    @SerialName("public_key") val publicKey: String = "",
    @SerialName("identity_key") val identityKey: String = "",
    val location: String = ""
) {
    val displayDeviceName: String
        get() = deviceName.ifBlank {
            when {
                platform.equals("web", ignoreCase = true) -> "GoChat Web"
                platform.equals("desktop", ignoreCase = true) -> "GoChat Desktop"
                browser.isNotBlank() -> browser
                os.isNotBlank() -> os
                else -> "Linked Device"
            }
        }

    val isWebOrDesktop: Boolean
        get() = platform.contains("web", ignoreCase = true) ||
                platform.contains("desktop", ignoreCase = true) ||
                browser.contains("Chrome", ignoreCase = true) ||
                browser.contains("Safari", ignoreCase = true) ||
                browser.contains("Firefox", ignoreCase = true) ||
                browser.contains("Edge", ignoreCase = true)

    val formattedStatus: String
        get() = buildString {
            if (isCurrent) {
                append("Active now • This phone")
            } else if (lastActiveAt.isNotBlank()) {
                val cleaned = if (lastActiveAt.length >= 16) {
                    lastActiveAt.substring(0, 10) + " " + lastActiveAt.substring(11, 16)
                } else {
                    lastActiveAt.take(10)
                }
                append("Last active: $cleaned")
            } else {
                append("Linked")
            }
            if (location.isNotBlank()) {
                append(" • $location")
            } else if (ipAddress.isNotBlank()) {
                append(" • $ipAddress")
            }
        }
}

