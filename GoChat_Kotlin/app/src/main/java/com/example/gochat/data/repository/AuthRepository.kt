package com.example.gochat.data.repository

import android.content.Context
import com.example.gochat.core.backup.ChatBackupManager
import com.example.gochat.data.api.ApiConstants
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.db.AppDatabase
import com.example.gochat.data.model.User
import com.example.gochat.data.model.LinkedDevice
import com.example.gochat.data.websocket.GoChatWebSocket
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles authentication flows — register, login, OTP, profile updates.
 * Persists tokens and user metadata through [TokenManager].
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GoChatApiService,
    private val tokenManager: TokenManager,
    private val webSocket: GoChatWebSocket,
    private val json: Json,
    private val backupManager: ChatBackupManager,
    private val database: AppDatabase
) {

    // ── Register ─────────────────────────────────────────────────

    suspend fun register(
        phone: String = "",
        email: String = "",
        password: String = "",
        displayName: String = "",
        countryCode: String = "NG"
    ): Result<JsonObject> {
        return try {
            val cleanPhone = phone.replace(Regex("[^\\d+]"), "")
            val safeName = displayName.ifBlank {
                if (cleanPhone.isNotEmpty()) "User ${cleanPhone.takeLast(4)}" else "GoChat User"
            }
            val safePassword = password.ifBlank { "GoChat@Password123!" }

            val body = buildJsonObject {
                if (cleanPhone.isNotEmpty()) put("phone", cleanPhone)
                if (email.isNotEmpty()) put("email", email)
                put("password", safePassword)
                put("display_name", safeName)
                put("country_code", countryCode.ifBlank { "NG" })
            }

            val response = api.register(body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                resetLocalDatabaseAndSession()
                extractAndSaveToken(data)
                extractAndSaveUser(data)
                syncPushTokenIfAvailable()
                Result.success(data)
            } else {
                // If user already exists, try login
                val errorBody = response.errorBody()?.string().orEmpty()
                if (response.code() == 409 ||
                    errorBody.lowercase().contains("already exists") ||
                    errorBody.lowercase().contains("already registered")
                ) {
                    val identifier = cleanPhone.ifBlank { email }
                    if (identifier.isNotEmpty()) {
                        return login(identifier, safePassword)
                    }
                }
                Result.failure(Exception(parseError(errorBody, response.code())))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Login ────────────────────────────────────────────────────

    suspend fun login(identifier: String, password: String = ""): Result<JsonObject> {
        return try {
            val body = buildJsonObject {
                put("email", identifier.trim())
                put("password", password)
            }

            val response = api.login(body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val userObj = data["user"]?.jsonObject ?: data
                val newUserId = userObj["id"]?.jsonPrimitive?.contentOrNull
                if (tokenManager.userId != newUserId || tokenManager.userId == null) {
                    resetLocalDatabaseAndSession()
                }
                extractAndSaveToken(data)
                extractAndSaveUser(data)
                syncPushTokenIfAvailable()
                Result.success(data)
            } else {
                val errorBody = response.errorBody()?.string().orEmpty()
                Result.failure(Exception(parseError(errorBody, response.code())))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── OTP ──────────────────────────────────────────────────────

    suspend fun requestOtp(phone: String): Result<JsonObject> {
        return try {
            val body = buildJsonObject { put("phone", phone) }
            val response = api.requestOtp(body)
            if (response.isSuccessful) {
                Result.success(response.body() ?: buildJsonObject {})
            } else {
                Result.failure(Exception("OTP request failed (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyOtp(phone: String, code: String): Result<JsonObject> {
        return try {
            val body = buildJsonObject {
                put("phone", phone)
                put("code", code)
            }
            val response = api.verifyOtp(body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val userObj = data["user"]?.jsonObject ?: data
                val newUserId = userObj["id"]?.jsonPrimitive?.contentOrNull
                if (tokenManager.userId != newUserId || tokenManager.userId == null) {
                    resetLocalDatabaseAndSession()
                }
                extractAndSaveToken(data)
                extractAndSaveUser(data)
                syncPushTokenIfAvailable()
                Result.success(data)
            } else {
                Result.failure(Exception("OTP verification failed (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Profile ──────────────────────────────────────────────────

    suspend fun updateProfile(
        displayName: String? = null,
        statusText: String? = null,
        avatarUrl: String? = null
    ): Result<User> {
        return try {
            val body = buildJsonObject {
                displayName?.let { put("display_name", it) }
                statusText?.let {
                    put("status_text", it)
                    put("bio", it)
                }
                avatarUrl?.let { put("avatar_url", it) }
            }
            val response = api.updateProfile(body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val userJson = data["user"]?.jsonObject ?: data
                val user = json.decodeFromJsonElement<User>(userJson)

                // Update local cache
                if (user.displayName.isNotBlank()) tokenManager.userDisplayName = user.displayName
                else displayName?.let { tokenManager.userDisplayName = it }

                if (user.avatarUrl.isNotBlank()) tokenManager.userAvatarUrl = user.avatarUrl
                else avatarUrl?.let { tokenManager.userAvatarUrl = it }

                val resolvedStatus = user.statusText.ifBlank { user.bio }
                if (resolvedStatus.isNotBlank()) tokenManager.userStatusText = resolvedStatus
                else statusText?.let { tokenManager.userStatusText = it }

                // Broadcast profile update via WebSocket mirroring Flutter
                try {
                    val wsPayload = buildJsonObject {
                        put("type", "user_profile_updated")
                        put("event_type", "EVENT_USER_PROFILE_UPDATED")
                        put("user", buildJsonObject {
                            put("id", user.id.ifBlank { tokenManager.userId.orEmpty() })
                            put("display_name", tokenManager.userDisplayName.orEmpty())
                            put("status_text", tokenManager.userStatusText.orEmpty())
                            put("avatar_url", tokenManager.userAvatarUrl.orEmpty())
                            put("pin", user.pin.ifBlank { tokenManager.userPin.orEmpty() })
                        })
                    }
                    webSocket.send(wsPayload)
                } catch (_: Exception) {}

                Result.success(user)
            } else {
                Result.failure(Exception("Profile update failed (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Push Notifications ─────────────────────────────────────────

    suspend fun subscribePush(token: String, platform: String = "android"): Result<Boolean> {
        return try {
            val body = buildJsonObject {
                put("push_token", token)
                put("platform", platform)
            }
            val response = api.subscribePush(body)
            if (response.isSuccessful) {
                tokenManager.fcmToken = token
                Result.success(true)
            } else {
                Result.failure(Exception("Failed to register push token (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── E2EE ──────────────────────────────────────────────────────

    suspend fun uploadE2EEKeys(
        registrationId: Int,
        identityKey: String,
        signedPreKey: String,
        signedPreKeySignature: String,
        oneTimeKeys: List<JsonObject>
    ): Result<Unit> {
        return try {
            val body = buildJsonObject {
                put("registration_id", registrationId)
                put("prekey_identity", identityKey)
                put("prekey_signed", signedPreKey)
                put("prekey_signature", signedPreKeySignature)
                put("one_time_keys", JsonArray(oneTimeKeys))
            }
            val response = api.uploadE2EEKeys(body)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to upload E2EE keys (${response.code()})"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getE2EEKeys(userId: String): Result<JsonObject> {
        return try {
            val response = api.getE2EEKeys(userId)
            if (response.isSuccessful) {
                Result.success(response.body() ?: buildJsonObject {})
            } else {
                Result.failure(Exception("Failed to get E2EE keys (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Linked Devices ───────────────────────────────────────────

    suspend fun getLinkedDevices(): Result<List<LinkedDevice>> {
        return try {
            val response = api.getLinkedDevices()
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val list = (data["devices"] ?: data["sessions"])?.jsonArray ?: JsonArray(emptyList())
                val devices = list.mapNotNull { element ->
                    if (element !is JsonObject) return@mapNotNull null
                    LinkedDevice(
                        id = element["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        deviceName = element["device_name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Device" },
                        platform = element["platform"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "android" },
                        os = element["os"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        browser = element["browser"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        ipAddress = element["ip_address"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        lastActiveAt = element["last_active_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        isCurrent = element["is_current"]?.jsonPrimitive?.booleanOrNull ?: false
                    )
                }
                Result.success(devices)
            } else {
                Result.failure(Exception("Failed to fetch linked devices (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerCurrentDevice(
        deviceId: String = "",
        deviceName: String = "",
        platform: String = "android",
        os: String = "Android",
        browser: String = "GoChat App"
    ): Result<Unit> {
        return try {
            val devId = deviceId.ifBlank {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ) ?: "dev_android"
            }
            val devName = deviceName.ifBlank {
                "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}"
            }

            val body = buildJsonObject {
                put("device_id", devId)
                put("device_name", devName)
                put("platform", platform)
                put("os", os)
                put("browser", browser)
            }

            val response = api.registerDevice(body)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to register device (${response.code()})"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unlinkDevice(deviceId: String): Result<Unit> {
        return try {
            val response = api.unlinkDevice(deviceId)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to unlink device (${response.code()})"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Privacy Settings ─────────────────────────────────────────

    suspend fun getPrivacySettings(): Result<JsonObject> {
        return try {
            val response = api.getPrivacySettings()
            if (response.isSuccessful) {
                Result.success(response.body() ?: buildJsonObject {})
            } else {
                Result.failure(Exception("Failed to fetch privacy settings"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updatePrivacySettings(
        profilePhoto: String? = null,
        status: String? = null,
        readReceipts: Boolean? = null,
        online: String? = null,
        lastSeen: String? = null
    ): Result<Unit> {
        return try {
            val body = buildJsonObject {
                profilePhoto?.let { put("profile_photo_privacy", it) }
                status?.let { put("status_privacy", it) }
                readReceipts?.let { put("read_receipts_enabled", it) }
                online?.let { put("online_privacy", it) }
                lastSeen?.let { put("last_seen_privacy", it) }
            }
            val response = api.updatePrivacySettings(body)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to update privacy settings"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadMedia(
        bytes: ByteArray,
        mimeType: String = "image/jpeg",
        fileName: String = "avatar.jpg"
    ): String? {
        return try {
            val mediaType = mimeType.toMediaTypeOrNull()
            val reqBody = bytes.toRequestBody(mediaType)
            val part = MultipartBody.Part.createFormData("file", fileName, reqBody)
            val response = api.uploadMedia(part)
            if (response.isSuccessful) {
                val json = response.body()
                val rawUrl = (json?.get("url") ?: json?.get("Url") ?: json?.get("URL") ?: json?.get("media_url"))?.jsonPrimitive?.contentOrNull
                if (!rawUrl.isNullOrBlank()) {
                    if (rawUrl.startsWith("/")) {
                        "${ApiConstants.BASE_URL.removeSuffix("/")}$rawUrl"
                    } else {
                        rawUrl
                    }
                } else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Lookup by PIN ────────────────────────────────────────────

    suspend fun lookupUserByPin(pin: String): Result<User> {
        val cleanPin = pin.trim().uppercase()
        if (cleanPin.length < 4) return Result.failure(Exception("PIN too short"))

        return try {
            val response = api.lookupUserByPin(cleanPin)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val userJson = data["user"]?.jsonObject ?: data
                val user = json.decodeFromJsonElement<User>(userJson)
                Result.success(user)
            } else {
                Result.failure(Exception("User not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Sync Contacts ────────────────────────────────────────────

    suspend fun syncContacts(identifiers: List<String>): Result<List<JsonObject>> {
        if (identifiers.isEmpty()) return Result.success(emptyList())

        return try {
            val body = buildJsonObject {
                put("identifiers", JsonArray(identifiers.map { JsonPrimitive(it) }))
            }
            val response = api.syncContacts(body)
            if (response.isSuccessful) {
                val data = response.body()
                val list = when (data) {
                    is JsonArray -> data.map { it.jsonObject }
                    else -> emptyList()
                }
                Result.success(list)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.success(emptyList())
        }
    }

    // ── Token / Session ──────────────────────────────────────────

    val isLoggedIn: Boolean get() = tokenManager.isLoggedIn
    val currentUserId: String? get() = tokenManager.userId
    val currentToken: String? get() = tokenManager.getToken()

    suspend fun logout() {
        resetLocalDatabaseAndSession()
        tokenManager.clearAll()
    }

    /**
     * Purges all local room tables, disconnects WebSocket, and resets backup prefs.
     */
    private suspend fun resetLocalDatabaseAndSession() {
        try {
            webSocket.disconnect()
        } catch (_: Exception) {}

        withContext(Dispatchers.IO) {
            try {
                database.clearAllTables()
            } catch (_: Exception) {}
        }

        try {
            backupManager.clearBackupInfo()
        } catch (_: Exception) {}
    }

    /**
     * Re-authenticate silently using stored credentials to refresh an expired JWT.
     */
    suspend fun ensureValidToken(): String? {
        val current = tokenManager.getToken()
        if (tokenManager.isValidJwt(current)) return current

        val phone = tokenManager.userPhone
        if (!phone.isNullOrBlank()) {
            val result = login(phone)
            if (result.isSuccess) return tokenManager.getToken()
        }
        return null
    }

    // ── Private Helpers ──────────────────────────────────────────

    private fun extractAndSaveToken(data: JsonObject) {
        val token = (data["access_token"] ?: data["token"])
            ?.jsonPrimitive?.contentOrNull
        if (token != null && tokenManager.isValidJwt(token)) {
            tokenManager.saveToken(token)
        }
    }

    private fun extractAndSaveUser(data: JsonObject) {
        val userObj = data["user"]?.jsonObject ?: data
        tokenManager.userId = userObj["id"]?.jsonPrimitive?.contentOrNull
        tokenManager.userPin = userObj["pin"]?.jsonPrimitive?.contentOrNull
        tokenManager.userPhone = userObj["phone"]?.jsonPrimitive?.contentOrNull
        tokenManager.userDisplayName = (userObj["display_name"] ?: userObj["displayName"])
            ?.jsonPrimitive?.contentOrNull
        tokenManager.userAvatarUrl = (userObj["avatar_url"] ?: userObj["avatarUrl"])
            ?.jsonPrimitive?.contentOrNull
        val status = (userObj["status_text"] ?: userObj["statusText"] ?: userObj["bio"])
            ?.jsonPrimitive?.contentOrNull
        if (!status.isNullOrBlank()) {
            tokenManager.userStatusText = status
        }
    }

    suspend fun syncPushTokenIfAvailable() {
        var pushToken = tokenManager.fcmToken
        if (pushToken.isNullOrBlank()) {
            try {
                pushToken = suspendCancellableCoroutine { cont ->
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful && !task.result.isNullOrBlank()) {
                                if (cont.isActive) cont.resume(task.result)
                            } else {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                }
                if (!pushToken.isNullOrBlank()) {
                    tokenManager.fcmToken = pushToken
                }
            } catch (_: Exception) {}
        }
        if (!pushToken.isNullOrBlank()) {
            try {
                subscribePush(pushToken, "android")
            } catch (_: Exception) {}
        }
    }

    private fun parseError(body: String, code: Int): String {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            (obj["error"] ?: obj["message"])?.jsonPrimitive?.contentOrNull
                ?: "Request failed ($code)"
        } catch (_: Exception) {
            "Server error ($code)"
        }
    }
}
