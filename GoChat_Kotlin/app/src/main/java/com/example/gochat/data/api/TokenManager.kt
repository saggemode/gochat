package com.example.gochat.data.api

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

/**
 * Secure JWT token and user-session storage using EncryptedSharedPreferences.
 * Replaces Flutter's `StorageService` + `flutter_secure_storage`.
 */
class TokenManager private constructor(context: Context) {

    companion object {
        private const val PREFS_FILE = "gochat_secure_prefs"
        private const val PREFS_FALLBACK_FILE = "gochat_prefs_compat"
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
        prefs = createPreferences(context.applicationContext)
    }

    private fun createPreferences(appContext: Context): SharedPreferences {
        // Attempt 1: Normal EncryptedSharedPreferences creation
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            Log.e("TokenManager", "EncryptedSharedPreferences creation failed (attempt 1): ${e.message}", e)
        }

        // Attempt 2: Clear corrupted Keystore keys & shared_prefs files (e.g. AEADBadTagException / KeyMint error -30)
        try {
            wipeCorruptedEncryptedPrefs(appContext)

            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            Log.e("TokenManager", "EncryptedSharedPreferences creation failed after wipe (attempt 2): ${e.message}", e)
        }

        // Fallback: If hardware Keystore/KeyMint remains broken on this device,
        // use app-private SharedPreferences so the app never crashes on startup.
        Log.w("TokenManager", "Falling back to app-private SharedPreferences store")
        return appContext.getSharedPreferences(PREFS_FALLBACK_FILE, Context.MODE_PRIVATE)
    }

    private fun wipeCorruptedEncryptedPrefs(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.deleteSharedPreferences(PREFS_FILE)
                context.deleteSharedPreferences("__androidx_security_crypto_encrypted_prefs__$PREFS_FILE")
            } else {
                context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
            }
            val sharedPrefsDir = File(context.filesDir.parent, "shared_prefs")
            if (sharedPrefsDir.exists()) {
                File(sharedPrefsDir, "$PREFS_FILE.xml").delete()
                File(sharedPrefsDir, "__androidx_security_crypto_encrypted_prefs__$PREFS_FILE.xml").delete()
            }
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        } catch (t: Throwable) {
            Log.w("TokenManager", "Failed to clear corrupted secure prefs/keys: ${t.message}")
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
