package com.example.gochat.core.wallpaper

import org.json.JSONArray
import org.json.JSONObject

enum class WallpaperType {
    PRESET,
    SOLID,
    CUSTOM_IMAGE
}

data class SolidColorOption(
    val name: String,
    val color: Int
)

data class ChatTheme(
    val id: String,
    val name: String,
    val type: WallpaperType = WallpaperType.PRESET,
    val bgGradientColors: List<Int> = listOf(0xFF111B21.toInt(), 0xFF0B141A.toInt()),
    val solidColor: Int? = null,
    val imageUriOrPath: String? = null,
    val showDoodle: Boolean = true,
    val doodleOpacity: Float = 0.06f,
    val accentColor: Int = 0xFF00A884.toInt() // Default GoChat Emerald
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("id", id)
        json.put("name", name)
        json.put("type", type.name)
        val gradArray = JSONArray()
        bgGradientColors.forEach { gradArray.put(it) }
        json.put("bgGradientColors", gradArray)
        if (solidColor != null) json.put("solidColor", solidColor)
        if (imageUriOrPath != null) json.put("imageUriOrPath", imageUriOrPath)
        json.put("showDoodle", showDoodle)
        json.put("doodleOpacity", doodleOpacity.toDouble())
        json.put("accentColor", accentColor)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): ChatTheme? {
            return try {
                val json = JSONObject(jsonStr)
                val id = json.optString("id", "default")
                val name = json.optString("name", "GoChat Emerald")
                val typeStr = json.optString("type", WallpaperType.PRESET.name)
                val type = try {
                    WallpaperType.valueOf(typeStr)
                } catch (_: Exception) {
                    WallpaperType.PRESET
                }
                val gradList = mutableListOf<Int>()
                val gradArray = json.optJSONArray("bgGradientColors")
                if (gradArray != null) {
                    for (i in 0 until gradArray.length()) {
                        gradList.add(gradArray.getInt(i))
                    }
                }
                if (gradList.isEmpty()) {
                    gradList.add(0xFF111B21.toInt())
                    gradList.add(0xFF0B141A.toInt())
                }
                val solidColor = if (json.has("solidColor")) json.getInt("solidColor") else null
                val imageUriOrPath = if (json.has("imageUriOrPath")) json.getString("imageUriOrPath") else null
                val showDoodle = json.optBoolean("showDoodle", true)
                val doodleOpacity = json.optDouble("doodleOpacity", 0.06).toFloat()
                val accentColor = json.optInt("accentColor", 0xFF00A884.toInt())

                ChatTheme(
                    id = id,
                    name = name,
                    type = type,
                    bgGradientColors = gradList,
                    solidColor = solidColor,
                    imageUriOrPath = imageUriOrPath,
                    showDoodle = showDoodle,
                    doodleOpacity = doodleOpacity,
                    accentColor = accentColor
                )
            } catch (_: Exception) {
                null
            }
        }

        // ── Curated Presets ──────────────────────────────────────────
        val DEFAULT_EMERALD = ChatTheme(
            id = "default",
            name = "GoChat Emerald",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF111B21.toInt(), 0xFF0B141A.toInt()),
            showDoodle = true,
            doodleOpacity = 0.06f,
            accentColor = 0xFF00A884.toInt()
        )

        val CYBERPUNK_NEON = ChatTheme(
            id = "cyberpunk",
            name = "Cyberpunk Neon",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF0D0221.toInt(), 0xFF05010D.toInt()),
            showDoodle = true,
            doodleOpacity = 0.08f,
            accentColor = 0xFFBB86FC.toInt()
        )

        val EMERALD_GLASS = ChatTheme(
            id = "emerald_glass",
            name = "Emerald Glass",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF0A231C.toInt(), 0xFF03140F.toInt()),
            showDoodle = true,
            doodleOpacity = 0.07f,
            accentColor = 0xFF34D399.toInt()
        )

        val SUNSET_AURORA = ChatTheme(
            id = "sunset_aurora",
            name = "Sunset Aurora",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF200F21.toInt(), 0xFF0F0612.toInt()),
            showDoodle = true,
            doodleOpacity = 0.06f,
            accentColor = 0xFFFB923C.toInt()
        )

        val MIDNIGHT_OLED = ChatTheme(
            id = "midnight_oled",
            name = "Midnight OLED",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF000000.toInt(), 0xFF000000.toInt()),
            showDoodle = false,
            doodleOpacity = 0.0f,
            accentColor = 0xFFFFFFFF.toInt()
        )

        val NORDIC_ICE = ChatTheme(
            id = "nordic_ice",
            name = "Nordic Ice",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF0F172A.toInt(), 0xFF020617.toInt()),
            showDoodle = true,
            doodleOpacity = 0.05f,
            accentColor = 0xFF38BDF8.toInt()
        )

        val LAVENDER_DREAM = ChatTheme(
            id = "lavender_dream",
            name = "Lavender Dream",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF1E1035.toInt(), 0xFF0F071D.toInt()),
            showDoodle = true,
            doodleOpacity = 0.06f,
            accentColor = 0xFFC084FC.toInt()
        )

        val CYBER_MATRIX = ChatTheme(
            id = "matrix_lime",
            name = "Cyber Matrix",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF041208.toInt(), 0xFF000502.toInt()),
            showDoodle = true,
            doodleOpacity = 0.10f,
            accentColor = 0xFF4ADE80.toInt()
        )

        val DEEP_OCEAN = ChatTheme(
            id = "deep_ocean",
            name = "Deep Ocean",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF031B33.toInt(), 0xFF010E1A.toInt()),
            showDoodle = true,
            doodleOpacity = 0.06f,
            accentColor = 0xFF60A5FA.toInt()
        )

        val SLATE_CHARCOAL = ChatTheme(
            id = "slate_charcoal",
            name = "Slate Charcoal",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF1E293B.toInt(), 0xFF0F172A.toInt()),
            showDoodle = true,
            doodleOpacity = 0.05f,
            accentColor = 0xFF94A3B8.toInt()
        )

        val PRESETS = listOf(
            DEFAULT_EMERALD,
            CYBERPUNK_NEON,
            EMERALD_GLASS,
            SUNSET_AURORA,
            MIDNIGHT_OLED,
            NORDIC_ICE,
            LAVENDER_DREAM,
            CYBER_MATRIX,
            DEEP_OCEAN,
            SLATE_CHARCOAL
        )

        val SOLID_COLORS = listOf(
            SolidColorOption("Classic Dark", 0xFF111B21.toInt()),
            SolidColorOption("Teal Dark", 0xFF0B2428.toInt()),
            SolidColorOption("Navy Blue", 0xFF0D1B2A.toInt()),
            SolidColorOption("Deep Plum", 0xFF241126.toInt()),
            SolidColorOption("Forest", 0xFF0B2215.toInt()),
            SolidColorOption("Graphite", 0xFF181C20.toInt()),
            SolidColorOption("Espresso", 0xFF201614.toInt()),
            SolidColorOption("Pure Black", 0xFF000000.toInt())
        )

        val ACCENT_COLORS = listOf(
            SolidColorOption("Emerald", 0xFF00A884.toInt()),
            SolidColorOption("Blue", 0xFF0084FF.toInt()),
            SolidColorOption("Purple", 0xFF7C3AED.toInt()),
            SolidColorOption("Pink", 0xFFEC4899.toInt()),
            SolidColorOption("Orange", 0xFFF59E0B.toInt()),
            SolidColorOption("Red", 0xFFEF4444.toInt()),
            SolidColorOption("Sky", 0xFF38BDF8.toInt()),
            SolidColorOption("Lime", 0xFF84CC16.toInt())
        )
    }
}
