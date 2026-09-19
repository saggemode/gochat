package com.example.gochat.core.utils

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Helper to check and request Battery Optimization Exemption and OEM Autostart settings.
 * Ensures GoChat background workers and push notifications wake up 100% reliably like WhatsApp.
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptHelper"
    private const val PREFS_NAME = "battery_opt_prefs"
    private const val KEY_DISMISSED = "battery_card_dismissed"

    fun isDismissed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DISMISSED, false)
    }

    fun setDismissed(context: Context, dismissed: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DISMISSED, dismissed).apply()
    }

    /**
     * Returns true if the app is already whitelisted from battery optimizations.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } catch (e: Exception) {
            Log.w(TAG, "Error checking battery optimization status", e)
            true
        }
    }

    /**
     * Prompts the official Android system dialog to request battery exemption.
     */
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, trying fallback", e)
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                activity.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.w(TAG, "Failed fallback to battery optimization settings", e2)
                openAppSettings(activity)
            }
        }
    }

    /**
     * Opens application detail settings where user can configure background permissions.
     */
    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open app settings", e)
        }
    }

    /**
     * Checks if current device manufacturer is known for aggressive background process killing (e.g. Xiaomi, Oppo, Samsung).
     */
    fun needsAutostartWarning(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
                m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ||
                m.contains("vivo") || m.contains("iqoo") ||
                m.contains("huawei") || m.contains("honor") ||
                m.contains("samsung") ||
                m.contains("transsion") || m.contains("tecno") || m.contains("infinix") || m.contains("itel") ||
                m.contains("asus")
    }

    fun getManufacturerDisplayName(): String {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "Xiaomi / MIUI / HyperOS"
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> "Oppo / Realme / OnePlus"
            m.contains("vivo") || m.contains("iqoo") -> "Vivo / FuntouchOS"
            m.contains("huawei") || m.contains("honor") -> "Huawei / Honor"
            m.contains("samsung") -> "Samsung One UI"
            m.contains("transsion") || m.contains("tecno") || m.contains("infinix") -> "Tecno / Infinix"
            else -> Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Attempts to open the OEM-specific Autostart / Background Management screen.
     * Returns true if an OEM intent launched successfully.
     */
    fun openAutostartSettings(context: Context): Boolean {
        val intents = listOf(
            // Xiaomi / MIUI / HyperOS
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")),
            // Oppo / Realme / ColorOS
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            // Vivo / iQOO / FuntouchOS
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            // Samsung
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")),
            // Huawei / Honor
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            // Transsion / Tecno / Infinix
            Intent().setComponent(ComponentName("com.transsion.phonemanager", "com.transsion.phonemanager.settings.AutoStartActivity")),
            Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.AutoStartActivity"))
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // Try next OEM intent
            }
        }

        // Fallback to app details
        openAppSettings(context)
        return false
    }

    /**
     * Displays an informative dialog to guide users on aggressive OEM devices to enable Autostart.
     */
    fun showAutostartGuidanceDialog(activity: Activity) {
        val oemName = getManufacturerDisplayName()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Enable Background Wakeup")
            .setMessage("On $oemName devices, aggressive battery management may pause background message delivery when GoChat is closed.\n\nTo wake up your device instantly when calls or messages arrive, please enable Autostart / Background activity for GoChat.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAutostartSettings(activity)
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
