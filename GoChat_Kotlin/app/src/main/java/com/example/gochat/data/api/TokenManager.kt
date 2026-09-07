package com.example.gochat.data.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
        private const val KEY_USER_STATUS = "user_status_text"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_BIOMETRIC_LOCK = "biometric_lock_enabled"

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
        val masterKey = try {
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: Throwable) {
            // Android 15 workaround: If KeyStore initialization fails with Binder error during early startup,
            // we log it and try a fallback or just let it fail gracefully if it's a platform bug.
            Log.e("TokenManager", "MasterKey initialization failed: ${e.message}", e)
            throw e
        }

        prefs = try {
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            Log.e("TokenManager", "EncryptedSharedPreferences creation failed: ${e.message}", e)
            throw e
        }
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

    var userStatusText: String?
        get() = prefs.getString(KEY_USER_STATUS, "Hey there! I am using GoChat.")
        set(value) = prefs.edit().putString(KEY_USER_STATUS, value).apply()

    var fcmToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    var isBiometricLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, value).apply()

    // ── Session Helpers ──────────────────────────────────────────

    val isLoggedIn: Boolean
        get() = isValidJwt(getToken())

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
