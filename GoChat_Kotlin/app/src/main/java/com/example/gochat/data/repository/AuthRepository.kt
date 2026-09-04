package com.example.gochat.data.repository

import android.content.Context
import com.example.gochat.core.backup.ChatBackupManager
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.db.AppDatabase
import com.example.gochat.data.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Handles authentication flows — register, login, OTP, profile updates.
 * Persists tokens and user metadata through [TokenManager].
 */
class AuthRepository(private val context: Context) {

    private val api: GoChatApiService get() = NetworkModule.getApiService(context)
    private val tokenManager: TokenManager get() = TokenManager.getInstance(context)
    private val json = NetworkModule.json

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
                statusText?.let { put("status_text", it) }
                avatarUrl?.let { put("avatar_url", it) }
            }
            val response = api.updateProfile(body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val userJson = data["user"]?.jsonObject ?: data
                val user = json.decodeFromJsonElement<User>(userJson)
                // Update local cache
                tokenManager.userDisplayName = user.displayName
                tokenManager.userAvatarUrl = user.avatarUrl
                Result.success(user)
            } else {
                Result.failure(Exception("Profile update failed (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
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
            NetworkModule.getWebSocket(context).disconnect()
        } catch (_: Exception) {}

        withContext(Dispatchers.IO) {
            try {
                AppDatabase.getInstance(context).clearAllTables()
            } catch (_: Exception) {}
        }

        try {
            ChatBackupManager(context).clearBackupInfo()
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
