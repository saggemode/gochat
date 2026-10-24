package com.example.gochat.core.crypto

import android.content.Context

/**
 * Per-conversation end-to-end encryption opt-in, plus a global kill-switch.
 *
 * E2EE is OFF by default for every conversation, so existing plaintext chats are untouched.
 * When enabled for a direct chat, outgoing text is encrypted with the Signal session; if a
 * session can't be established the sender falls back to plaintext (never a lost message).
 * The global kill-switch lets the user disable the whole feature instantly.
 */
object E2EESettings {

    private const val PREFS = "e2ee_prefs"
    private const val KEY_GLOBAL = "e2ee_global_enabled"
    private const val KEY_CONV_PREFIX = "e2ee_conv_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Global kill-switch. Default true = feature available (but each chat is still opt-in). */
    fun isAvailable(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_GLOBAL, true) } catch (_: Exception) { true }

    fun setAvailable(context: Context, enabled: Boolean) {
        try { prefs(context).edit().putBoolean(KEY_GLOBAL, enabled).apply() } catch (_: Exception) {}
    }

    /** Whether E2EE is turned on for this specific conversation (default false). */
    fun isEnabled(context: Context, conversationId: String): Boolean =
        try {
            isAvailable(context) && prefs(context).getBoolean(KEY_CONV_PREFIX + conversationId, false)
        } catch (_: Exception) { false }

    fun setEnabled(context: Context, conversationId: String, enabled: Boolean) {
        try { prefs(context).edit().putBoolean(KEY_CONV_PREFIX + conversationId, enabled).apply() } catch (_: Exception) {}
    }
}
