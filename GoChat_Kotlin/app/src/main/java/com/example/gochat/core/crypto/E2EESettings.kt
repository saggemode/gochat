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

    /**
     * MASTER OFF SWITCH. While true, end-to-end encryption is fully disabled:
     *  - nothing is encrypted on send — messages go out as plaintext,
     *  - nothing is decrypted on receive — no Signal calls, no "stays encrypted" banners,
     *  - no sessions/keys are touched, no self-heal reset requests are sent.
     * The entire E2EE codebase stays in place and dormant; set this back to `false`
     * to re-enable it. This exists because the untested E2EE stack was producing
     * unrecoverable decrypt errors.
     */
    const val DISABLED = true

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Global kill-switch. Returns false while [DISABLED]; otherwise the stored flag. */
    fun isAvailable(context: Context): Boolean =
        if (DISABLED) false
        else try { prefs(context).getBoolean(KEY_GLOBAL, true) } catch (_: Exception) { true }

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

    /**
     * Clear all E2EE state: per-conversation opt-in flags + the group sender-key
     * distribution tracking. Call on logout / account deletion so a re-register
     * doesn't inherit the previous account's conversation flags or re-key markers.
     */
    fun clearAll(context: Context) {
        try { prefs(context).edit().clear().apply() } catch (_: Exception) {}
        try { context.getSharedPreferences("gochat_group_e2ee", Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) {}
        try { context.getSharedPreferences("e2ee_decrypt_dedup", Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) {}
        // Multi-device: device_number + multi-device flag, and paired-device trust.
        try { context.getSharedPreferences("e2ee_prefs", Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) {}
        try { context.getSharedPreferences("e2ee_paired_devices", Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) {}
        try { context.getSharedPreferences("e2ee_control_processed", Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) {}
    }
}
