package com.example.gochat.data.api

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.KeyStore
import com.example.gochat.ui.auth.CountryData

/**
 * Secure JWT token and user-session storage using EncryptedSharedPreferences.
 * Replaces Flutter's `StorageService` + `flutter_secure_storage`.
 */
class TokenManager private constructor(context: Context) {

    companion object {
        private const val PREFS_FILE = "gochat_secure_prefs"
        private const val PREFS_FALLBACK_FILE = "gochat_prefs_compat"
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_REFRESH_TOKEN = "jwt_refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_PIN = "user_pin"
        private const val KEY_USER_PHONE = "user_phone"
        private const val KEY_USER_NAME = "user_display_name"
        private const val KEY_USER_AVATAR = "user_avatar_url"
        private const val KEY_USER_STATUS = "user_status_text"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_BIOMETRIC_LOCK = "biometric_lock_enabled"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_COUNTRY_CODE = "user_country_code"
        private const val KEY_USER_CURRENCY_CODE = "user_currency_code"
        private const val KEY_USER_CURRENCY_SYMBOL = "user_currency_symbol"
        private const val KEY_USER_CONTINENT = "user_continent"

        // Reused JSON parser for decoding JWT payload claims (creating one per call is slow).
        private val JWT_JSON = Json { ignoreUnknownKeys = true }

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

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun saveRefreshToken(token: String) {
        prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }

    fun saveTokenPair(accessToken: String, refreshToken: String?) {
        val editor = prefs.edit().putString(KEY_TOKEN, accessToken)
        if (!refreshToken.isNullOrBlank()) {
            editor.putString(KEY_REFRESH_TOKEN, refreshToken)
        }
        editor.apply()
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_REFRESH_TOKEN).apply()
    }

    /**
     * Quick JWT structure validation (3 dot-separated Base64 parts).
     */
    fun isValidJwt(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        if (token.startsWith("gochat_session_")) return false
        return token.split(".").size == 3
    }

    /**
     * Decodes the JWT payload to inspect the `exp` (expiration) timestamp claim.
     * Returns true if the token is null, blank, structurally invalid, or expired
     * (or within bufferSeconds of expiring, default 120s).
     */
    fun isTokenExpired(token: String? = getToken(), bufferSeconds: Long = 120): Boolean {
        if (token.isNullOrBlank()) return true
        val parts = token.split(".")
        if (parts.size != 3) return true
        return try {
            val payloadBytes = Base64.decode(
                parts[1],
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val payloadJson = String(payloadBytes, Charsets.UTF_8)
            val json = JWT_JSON.parseToJsonElement(payloadJson).jsonObject
            val exp = json["exp"]?.jsonPrimitive?.longOrNull ?: return false
            val nowSeconds = System.currentTimeMillis() / 1000L
            nowSeconds >= (exp - bufferSeconds)
        } catch (e: Exception) {
            Log.w("TokenManager", "Failed to parse JWT exp claim: ${e.message}")
            true
        }
    }

    // ── User Fields ──────────────────────────────────────────────

    var userId: String?
        get() {
            val stored = prefs.getString(KEY_USER_ID, null)
            if (!stored.isNullOrBlank()) return stored
            val token = getToken()
            if (!token.isNullOrBlank() && token.split(".").size == 3) {
                try {
                    val payloadBytes = Base64.decode(
                        token.split(".")[1],
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                    )
                    val payloadJson = String(payloadBytes, Charsets.UTF_8)
                    val json = JWT_JSON.parseToJsonElement(payloadJson).jsonObject
                    val uid = (json["uid"] ?: json["sub"] ?: json["user_id"])?.jsonPrimitive?.contentOrNull
                    if (!uid.isNullOrBlank()) {
                        prefs.edit().putString(KEY_USER_ID, uid).apply()
                        return uid
                    }
                } catch (_: Exception) {}
            }
            return null
        }
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

    var hasCompletedMigrationCheck: Boolean
        get() = prefs.getBoolean("has_completed_migration_check", false)
        set(value) = prefs.edit().putBoolean("has_completed_migration_check", value).apply()

    // Cached global privacy preference: may others reshare my status? Defaults to
    // true (allowed). Used by the status composers so a new status inherits the
    // setting without a per-post prompt.
    var allowStoryReshare: Boolean
        get() = prefs.getBoolean("allow_story_reshare", true)
        set(value) = prefs.edit().putBoolean("allow_story_reshare", value).apply()

    // Remembered status-privacy default (WhatsApp-style): the last audience the user
    // chose, so the composer pre-selects it instead of resetting to "My contacts".
    var statusAudienceType: String
        get() = prefs.getString("status_audience_type", "contacts") ?: "contacts"
        set(value) = prefs.edit().putString("status_audience_type", value).apply()

    // The except/only contact-id list that goes with statusAudienceType.
    var statusAudienceIds: List<String>
        get() = prefs.getString("status_audience_ids", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = prefs.edit().putString("status_audience_ids", value.filter { it.isNotBlank() }.joinToString(",")).apply()

    // ── Notification preferences ────────────────────────────────────────────
    /** Custom notification-tone URI (null/blank = system default). */
    var notificationToneUri: String?
        get() = prefs.getString("notif_tone_uri", null)
        set(value) = prefs.edit().putString("notif_tone_uri", value ?: "").apply()

    /** Show message content in the notification/lock screen (off = "New message"). */
    var notificationPreviewEnabled: Boolean
        get() = prefs.getBoolean("notif_preview_enabled", true)
        set(value) = prefs.edit().putBoolean("notif_preview_enabled", value).apply()

    /** Show an unread count badge on the app icon / notification. */
    var notificationBadgeEnabled: Boolean
        get() = prefs.getBoolean("notif_badge_enabled", true)
        set(value) = prefs.edit().putBoolean("notif_badge_enabled", value).apply()

    /** Notify on reactions to my messages. */
    var reactionNotificationsEnabled: Boolean
        get() = prefs.getBoolean("notif_reactions_enabled", true)
        set(value) = prefs.edit().putBoolean("notif_reactions_enabled", value).apply()

    /** Quiet hours (DND window): silences non-priority notifications. */
    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean("notif_quiet_enabled", false)
        set(value) = prefs.edit().putBoolean("notif_quiet_enabled", value).apply()

    /** Quiet-hours start/end as minutes-from-midnight (default 22:00 → 07:00). */
    var quietHoursStartMinutes: Int
        get() = prefs.getInt("notif_quiet_start", 22 * 60)
        set(value) = prefs.edit().putInt("notif_quiet_start", value).apply()

    var quietHoursEndMinutes: Int
        get() = prefs.getInt("notif_quiet_end", 7 * 60)
        set(value) = prefs.edit().putInt("notif_quiet_end", value).apply()

    /** Conversations flagged "priority" — they break through mute and quiet hours. */
    var priorityConversationIds: Set<String>
        get() = prefs.getString("notif_priority_convs", "")?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        set(value) = prefs.edit().putString("notif_priority_convs", value.filter { it.isNotBlank() }.joinToString(",")).apply()

    fun isPriorityConversation(conversationId: String): Boolean =
        conversationId.isNotBlank() && priorityConversationIds.contains(conversationId)

    fun setPriorityConversation(conversationId: String, priority: Boolean) {
        if (conversationId.isBlank()) return
        val current = priorityConversationIds.toMutableSet()
        if (priority) current.add(conversationId) else current.remove(conversationId)
        priorityConversationIds = current
    }

    /** Silence calls from people who aren't in your contacts (still logged as missed). */
    var silenceUnknownCallersEnabled: Boolean
        get() = prefs.getBoolean("silence_unknown_callers", false)
        set(value) = prefs.edit().putBoolean("silence_unknown_callers", value).apply()

    /** Be notified when a contact's security code changes (active once E2EE is on). */
    var securityNotificationsEnabled: Boolean
        get() = prefs.getBoolean("security_notifications_enabled", true)
        set(value) = prefs.edit().putBoolean("security_notifications_enabled", value).apply()

    /** True when the current local time falls inside the quiet-hours window. */
    fun isWithinQuietHours(): Boolean {
        if (!quietHoursEnabled) return false
        val cal = java.util.Calendar.getInstance()
        val now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val start = quietHoursStartMinutes
        val end = quietHoursEndMinutes
        return if (start <= end) now in start until end else now >= start || now < end // window wraps midnight
    }

    var userCountryCode: String
        get() {
            val phone = userPhone.orEmpty()
            if (phone.isNotBlank()) {
                val inferred = CountryData.getByPhone(phone).code
                prefs.edit().putString(KEY_USER_COUNTRY_CODE, inferred).apply()
                return inferred
            }
            val stored = prefs.getString(KEY_USER_COUNTRY_CODE, null)
            if (!stored.isNullOrBlank()) return stored.uppercase()
            return "NG"
        }
        set(value) = prefs.edit().putString(KEY_USER_COUNTRY_CODE, value.uppercase()).apply()

    var userCurrencyCode: String
        get() {
            val phone = userPhone.orEmpty()
            if (phone.isNotBlank()) {
                val country = CountryData.getByPhone(phone)
                prefs.edit().putString(KEY_USER_CURRENCY_CODE, country.currencyCode).apply()
                return country.currencyCode
            }
            val stored = prefs.getString(KEY_USER_CURRENCY_CODE, null)
            if (!stored.isNullOrBlank()) return stored.uppercase()
            val country = CountryData.getByCountryCode(userCountryCode)
            val curr = country.currencyCode
            prefs.edit().putString(KEY_USER_CURRENCY_CODE, curr).apply()
            return curr
        }
        set(value) = prefs.edit().putString(KEY_USER_CURRENCY_CODE, value.uppercase()).apply()

    var userCurrencySymbol: String
        get() {
            val phone = userPhone.orEmpty()
            if (phone.isNotBlank()) {
                val country = CountryData.getByPhone(phone)
                prefs.edit().putString(KEY_USER_CURRENCY_SYMBOL, country.currencySymbol).apply()
                return country.currencySymbol
            }
            val stored = prefs.getString(KEY_USER_CURRENCY_SYMBOL, null)
            if (!stored.isNullOrBlank()) return stored
            val country = CountryData.getByCountryCode(userCountryCode)
            val sym = country.currencySymbol
            prefs.edit().putString(KEY_USER_CURRENCY_SYMBOL, sym).apply()
            return sym
        }
        set(value) = prefs.edit().putString(KEY_USER_CURRENCY_SYMBOL, value).apply()

    var userContinent: String
        get() {
            val phone = userPhone.orEmpty()
            if (phone.isNotBlank()) {
                val country = CountryData.getByPhone(phone)
                prefs.edit().putString(KEY_USER_CONTINENT, country.continent).apply()
                return country.continent
            }
            val stored = prefs.getString(KEY_USER_CONTINENT, null)
            if (!stored.isNullOrBlank()) return stored
            val country = CountryData.getByCountryCode(userCountryCode)
            val cont = country.continent
            prefs.edit().putString(KEY_USER_CONTINENT, cont).apply()
            return cont
        }
        set(value) = prefs.edit().putString(KEY_USER_CONTINENT, value).apply()

    fun setUserCountryAndCurrency(
        countryCode: String,
        currencyCode: String? = null,
        currencySymbol: String? = null,
        continent: String? = null
    ) {
        val c = CountryData.getByCountryCode(countryCode)
        prefs.edit()
            .putString(KEY_USER_COUNTRY_CODE, c.code)
            .putString(KEY_USER_CURRENCY_CODE, currencyCode?.ifBlank { null } ?: c.currencyCode)
            .putString(KEY_USER_CURRENCY_SYMBOL, currencySymbol?.ifBlank { null } ?: c.currencySymbol)
            .putString(KEY_USER_CONTINENT, continent?.ifBlank { null } ?: c.continent)
            .apply()
    }

    // ── Session Helpers ──────────────────────────────────────────

    val isLoggedIn: Boolean
        get() {
            val token = getToken()
            if (token.isNullOrBlank()) return false
            if (!isValidJwt(token)) return false
            // User is logged in if access token is not expired OR we have a refresh token OR user phone for auto-renewal
            return !isTokenExpired(token) || !getRefreshToken().isNullOrBlank() || !userPhone.isNullOrBlank()
        }

    fun getDeviceId(): String {
        var devId = prefs.getString(KEY_DEVICE_ID, null)
        if (devId.isNullOrBlank()) {
            devId = "dev_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString(KEY_DEVICE_ID, devId).apply()
        }
        return devId
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
