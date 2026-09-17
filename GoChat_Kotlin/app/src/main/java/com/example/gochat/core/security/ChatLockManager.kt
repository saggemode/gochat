package com.example.gochat.core.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object ChatLockManager {
    private const val PREFS_NAME = "gochat_chat_locks"
    private const val KEY_LOCKED_CHATS = "locked_conversations"

    private val sessionUnlockedChats = mutableSetOf<String>()

    fun isLocked(context: Context, conversationId: String): Boolean {
        if (conversationId.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lockedSet = prefs.getStringSet(KEY_LOCKED_CHATS, emptySet()) ?: emptySet()
        return lockedSet.contains(conversationId)
    }

    fun isSessionUnlocked(conversationId: String): Boolean {
        return sessionUnlockedChats.contains(conversationId)
    }

    fun unlockForSession(conversationId: String) {
        sessionUnlockedChats.add(conversationId)
    }

    fun lockForSession(conversationId: String) {
        sessionUnlockedChats.remove(conversationId)
    }

    fun clearAllSessionUnlocks() {
        sessionUnlockedChats.clear()
    }

    fun setLocked(context: Context, conversationId: String, locked: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lockedSet = (prefs.getStringSet(KEY_LOCKED_CHATS, emptySet()) ?: emptySet()).toMutableSet()
        if (locked) {
            lockedSet.add(conversationId)
            sessionUnlockedChats.add(conversationId)
        } else {
            lockedSet.remove(conversationId)
            sessionUnlockedChats.remove(conversationId)
        }
        prefs.edit().putStringSet(KEY_LOCKED_CHATS, lockedSet).apply()
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Chat",
        subtitle: String = "Confirm your fingerprint, face, or PIN to continue",
        onSuccess: () -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        onError?.invoke(errString.toString())
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        val biometricManager = BiometricManager.from(activity)
        val canDeviceCred = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canDeviceCred == BiometricManager.BIOMETRIC_SUCCESS || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                promptInfoBuilder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } catch (_: Exception) {
                promptInfoBuilder.setNegativeButtonText("Cancel")
            }
        } else {
            promptInfoBuilder.setNegativeButtonText("Cancel")
        }

        biometricPrompt.authenticate(promptInfoBuilder.build())
    }
}
