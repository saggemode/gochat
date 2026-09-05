package com.example.gochat.core.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Manages in-chat sounds for sent and received messages.
 * Allows users to pick custom tunes from their phone.
 */
class ChatSoundManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun playSentSound() {
        if (!isInChatSoundsEnabled()) return
        val uriStr = prefs.getString(KEY_SENT_SOUND, null)
        val uri = if (uriStr != null) Uri.parse(uriStr) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        playSound(uri)
    }

    fun playReceivedSound() {
        if (!isInChatSoundsEnabled()) return
        val uriStr = prefs.getString(KEY_RECEIVED_SOUND, null)
        val uri = if (uriStr != null) Uri.parse(uriStr) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        playSound(uri)
    }

    private fun playSound(uri: Uri?) {
        try {
            val ringtone = RingtoneManager.getRingtone(context, uri)
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ringtone.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                ringtone.play()
            }
        } catch (e: Exception) {
            Log.e("ChatSoundManager", "Failed to play sound: ${e.message}")
        }
    }

    fun setSentSound(uri: Uri?) {
        prefs.edit().putString(KEY_SENT_SOUND, uri?.toString()).apply()
    }

    fun getSentSound(): Uri? {
        val uriStr = prefs.getString(KEY_SENT_SOUND, null)
        return if (uriStr != null) Uri.parse(uriStr) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    fun setReceivedSound(uri: Uri?) {
        prefs.edit().putString(KEY_RECEIVED_SOUND, uri?.toString()).apply()
    }

    fun getReceivedSound(): Uri? {
        val uriStr = prefs.getString(KEY_RECEIVED_SOUND, null)
        return if (uriStr != null) Uri.parse(uriStr) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    fun setInChatSoundsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IN_CHAT_SOUNDS, enabled).apply()
    }

    fun isInChatSoundsEnabled(): Boolean {
        return prefs.getBoolean(KEY_IN_CHAT_SOUNDS, true)
    }

    companion object {
        private const val PREFS_NAME = "gochat_sound_prefs"
        private const val KEY_SENT_SOUND = "sent_sound_uri"
        private const val KEY_RECEIVED_SOUND = "received_sound_uri"
        private const val KEY_IN_CHAT_SOUNDS = "in_chat_sounds_enabled"
    }
}
