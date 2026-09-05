package com.example.gochat.core.wallpaper

import android.content.Context
import android.content.SharedPreferences

class ChatThemeManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTheme(conversationId: String): ChatTheme {
        val convKey = "$KEY_CONV_PREFIX$conversationId"
        val convJson = prefs.getString(convKey, null)
        if (!convJson.isNullOrBlank()) {
            val theme = ChatTheme.fromJson(convJson)
            if (theme != null) return theme
        }

        // Fallback to global user theme
        val globalJson = prefs.getString(KEY_GLOBAL, null)
        if (!globalJson.isNullOrBlank()) {
            val theme = ChatTheme.fromJson(globalJson)
            if (theme != null) return theme
        }

        return ChatTheme.DEFAULT_EMERALD
    }

    fun getGlobalTheme(): ChatTheme {
        val globalJson = prefs.getString(KEY_GLOBAL, null)
        if (!globalJson.isNullOrBlank()) {
            val theme = ChatTheme.fromJson(globalJson)
            if (theme != null) return theme
        }
        return ChatTheme.DEFAULT_EMERALD
    }

    fun setThemeForConversation(conversationId: String, theme: ChatTheme) {
        val convKey = "$KEY_CONV_PREFIX$conversationId"
        prefs.edit().putString(convKey, theme.toJson()).apply()
    }

    fun setGlobalTheme(theme: ChatTheme) {
        prefs.edit().putString(KEY_GLOBAL, theme.toJson()).apply()
    }

    fun resetTheme(conversationId: String) {
        val convKey = "$KEY_CONV_PREFIX$conversationId"
        prefs.edit().remove(convKey).apply()
    }

    companion object {
        private const val PREFS_NAME = "gochat_themes_prefs"
        private const val KEY_CONV_PREFIX = "gochat_theme_conv_"
        private const val KEY_GLOBAL = "gochat_theme_global"
    }
}
