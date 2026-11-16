package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MiniApp(
    val id: String = "",
    @SerialName("developer_id") val developerId: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("icon_url") val iconUrl: String = "",
    @SerialName("manifest_url") val manifestUrl: String = "",
    val category: String = "",
    @SerialName("is_approved") val isApproved: Boolean = false,
    @SerialName("install_count") val installCount: Int = 0
)

@Serializable
data class MiniAppsResponse(
    val miniapps: List<MiniApp> = emptyList()
)

@Serializable
data class MiniAppLaunch(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("manifest_url") val manifestUrl: String = ""
)
