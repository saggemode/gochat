package com.example.gochat.core.media

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.gochat.data.model.MessageType

/**
 * Manages user preferences for automatic media downloads over Cellular vs. Wi-Fi networks.
 * Helps users conserve mobile data according to their individual network and storage preferences.
 */
object MediaAutoDownloadManager {

    private const val PREFS_NAME = "gochat_media_auto_download"

    // Cellular keys
    const val KEY_CELLULAR_PHOTOS = "cellular_photos"
    const val KEY_CELLULAR_AUDIO = "cellular_audio"
    const val KEY_CELLULAR_VIDEOS = "cellular_videos"
    const val KEY_CELLULAR_DOCUMENTS = "cellular_documents"

    // Wi-Fi keys
    const val KEY_WIFI_PHOTOS = "wifi_photos"
    const val KEY_WIFI_AUDIO = "wifi_audio"
    const val KEY_WIFI_VIDEOS = "wifi_videos"
    const val KEY_WIFI_DOCUMENTS = "wifi_documents"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isCellularEnabled(context: Context, key: String): Boolean {
        // Default: Photos enabled on cellular, heavier media disabled to save data
        val defaultVal = (key == KEY_CELLULAR_PHOTOS)
        return getPrefs(context).getBoolean(key, defaultVal)
    }

    fun setCellularEnabled(context: Context, key: String, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(key, enabled).apply()
    }

    fun isWifiEnabled(context: Context, key: String): Boolean {
        // Default: All media types enabled on Wi-Fi
        return getPrefs(context).getBoolean(key, true)
    }

    fun setWifiEnabled(context: Context, key: String, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(key, enabled).apply()
    }

    fun getCellularSummary(context: Context): String {
        val selected = mutableListOf<String>()
        if (isCellularEnabled(context, KEY_CELLULAR_PHOTOS)) selected.add("Photos")
        if (isCellularEnabled(context, KEY_CELLULAR_AUDIO)) selected.add("Audio")
        if (isCellularEnabled(context, KEY_CELLULAR_VIDEOS)) selected.add("Videos")
        if (isCellularEnabled(context, KEY_CELLULAR_DOCUMENTS)) selected.add("Documents")
        return when {
            selected.isEmpty() -> "No media"
            selected.size == 4 -> "All media"
            else -> selected.joinToString(", ")
        }
    }

    fun getWifiSummary(context: Context): String {
        val selected = mutableListOf<String>()
        if (isWifiEnabled(context, KEY_WIFI_PHOTOS)) selected.add("Photos")
        if (isWifiEnabled(context, KEY_WIFI_AUDIO)) selected.add("Audio")
        if (isWifiEnabled(context, KEY_WIFI_VIDEOS)) selected.add("Videos")
        if (isWifiEnabled(context, KEY_WIFI_DOCUMENTS)) selected.add("Documents")
        return when {
            selected.isEmpty() -> "No media"
            selected.size == 4 -> "All media"
            else -> selected.joinToString(", ")
        }
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun shouldAutoDownload(context: Context, messageType: MessageType): Boolean {
        val onWifi = isWifiConnected(context)
        return when (messageType) {
            MessageType.IMAGE -> if (onWifi) isWifiEnabled(context, KEY_WIFI_PHOTOS) else isCellularEnabled(context, KEY_CELLULAR_PHOTOS)
            MessageType.AUDIO, MessageType.VOICE -> if (onWifi) isWifiEnabled(context, KEY_WIFI_AUDIO) else isCellularEnabled(context, KEY_CELLULAR_AUDIO)
            MessageType.VIDEO -> if (onWifi) isWifiEnabled(context, KEY_WIFI_VIDEOS) else isCellularEnabled(context, KEY_CELLULAR_VIDEOS)
            MessageType.FILE -> if (onWifi) isWifiEnabled(context, KEY_WIFI_DOCUMENTS) else isCellularEnabled(context, KEY_CELLULAR_DOCUMENTS)
            else -> true
        }
    }
}
