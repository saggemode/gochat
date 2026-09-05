package com.example.gochat.core.wallpaper

import android.content.Context
import android.content.SharedPreferences

class ChatWallpaperManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getWallpaper(conversationId: String): ChatWallpaper {
        val convKey = "$KEY_CONV_PREFIX$conversationId"
        val convJson = prefs.getString(convKey, null)
        if (!convJson.isNullOrBlank()) {
            val wallpaper = ChatWallpaper.fromJson(convJson)
            if (wallpaper != null) return wallpaper
        }

        // Fallback to global user wallpaper
        val globalJson = prefs.getString(KEY_GLOBAL, null)
        if (!globalJson.isNullOrBlank()) {
            val wallpaper = ChatWallpaper.fromJson(globalJson)
            if (wallpaper != null) return wallpaper
        }

        return ChatWallpaper.DEFAULT_EMERALD
    }

    fun getGlobalWallpaper(): ChatWallpaper {
        val globalJson = prefs.getString(KEY_GLOBAL, null)
        if (!globalJson.isNullOrBlank()) {
            val wallpaper = ChatWallpaper.fromJson(globalJson)
            if (wallpaper != null) return wallpaper
        }
        return ChatWallpaper.DEFAULT_EMERALD
    }

    fun setWallpaperForConversation(conversationId: String, wallpaper: ChatWallpaper) {
        val convKey = "$KEY_CONV_PREFIX$conversationId"
        prefs.edit().putString(convKey, wallpaper.toJson()).apply()
    }

    fun setGlobalWallpaper(wallpaper: ChatWallpaper) {
        prefs.edit().putString(KEY_GLOBAL, wallpaper.toJson()).apply()
    }

    fun resetWallpaper(conversationId: String) {
        val convKey = "$KEY_CONV_PREFIX$conversationId"
        prefs.edit().remove(convKey).apply()
    }

    companion object {
        private const val PREFS_NAME = "gochat_wallpapers_prefs"
        private const val KEY_CONV_PREFIX = "gochat_wallpaper_conv_"
        private const val KEY_GLOBAL = "gochat_wallpaper_global"
    }
}
