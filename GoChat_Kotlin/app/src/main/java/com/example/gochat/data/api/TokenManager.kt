package com.example.gochat.data.api

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure JWT token and user-session storage using EncryptedSharedPreferences.
 * Replaces Flutter's `StorageService` + `flutter_secure_storage`.
 */
class TokenManager private constructor(context: Context) {

    companion object {
        private const val PREFS_FILE = "gochat_secure_prefs"
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_PIN = "user_pin"
        private const val KEY_USER_PHONE = "user_phone"
        private const val KEY_USER_NAME = "user_display_name"
        private const val KEY_USER_AVATAR = "user_avatar_url"

        @Volatile
        private var INSTANCE: TokenManager? = null

        fun getInstance(context: Context): TokenManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TokenManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── Token ────────────────────────────────────────────────────

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    /**
     * Quick JWT structure validation (3 dot-separated Base64 parts).
     */
    fun isValidJwt(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        if (token.startsWith("gochat_session_")) return false
        return token.split(".").size == 3
    }

    // ── User Fields ──────────────────────────────────────────────

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var userPin: String?
        get() = prefs.getString(KEY_USER_PIN, null)
        set(value) = prefs.edit().putString(KEY_USER_PIN, value).apply()

    var userPhone: String?
        get() = prefs.getString(KEY_USER_PHONE, null)
        set(value) = prefs.edit().putString(KEY_USER_PHONE, value).apply()

    var userDisplayName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var userAvatarUrl: String?
        get() = prefs.getString(KEY_USER_AVATAR, null)
        set(value) = prefs.edit().putString(KEY_USER_AVATAR, value).apply()

    // ── Session Helpers ──────────────────────────────────────────

    val isLoggedIn: Boolean
        get() = isValidJwt(getToken())

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
