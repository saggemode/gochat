package com.example.gochat.core.theme

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * Universal theme manager managing System Default, Dark Mode, and Light Mode.
 * Mirrors Flutter's ThemeMode switching in `app_state.dart` and `settings_screen.dart`.
 */
object ThemeManager {

    private const val PREFS_NAME = "gochat_theme_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    const val THEME_SYSTEM = 0
    const val THEME_DARK = 1
    const val THEME_LIGHT = 2

    fun init(context: Context) {
        val mode = getThemeMode(context)
        applyTheme(mode)
    }

    fun getThemeMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM)
    }

    fun setThemeMode(context: Context, mode: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
        applyTheme(mode)
    }

    fun applyTheme(mode: Int) {
        val nightMode = when (mode) {
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun getThemeTitle(mode: Int): String {
        return when (mode) {
            THEME_DARK -> "Dark Mode (Emerald)"
            THEME_LIGHT -> "Light Mode (Clean)"
            else -> "System Default"
        }
    }

    fun isDarkThemeActive(context: Context): Boolean {
        return when (getThemeMode(context)) {
            THEME_DARK -> true
            THEME_LIGHT -> false
            else -> {
                val currentNightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                currentNightMode == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}
