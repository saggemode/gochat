package com.example.gochat.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LinkedDevice(
    val id: String = "",
    val deviceName: String = "",
    val platform: String = "android",
    val os: String = "",
    val browser: String = "",
    val ipAddress: String = "",
    val lastActiveAt: String = "",
    val isCurrent: Boolean = false
)
