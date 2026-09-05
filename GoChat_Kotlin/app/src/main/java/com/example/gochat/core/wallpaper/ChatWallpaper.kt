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

data class ChatWallpaper(
    val id: String,
    val name: String,
    val type: WallpaperType = WallpaperType.PRESET,
    val bgGradientColors: List<Int> = listOf(0xFF111B21.toInt(), 0xFF0B141A.toInt()),
    val solidColor: Int? = null,
    val imageUriOrPath: String? = null,
    val showDoodle: Boolean = true,
    val doodleOpacity: Float = 0.06f
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
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): ChatWallpaper? {
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

                ChatWallpaper(
                    id = id,
                    name = name,
                    type = type,
                    bgGradientColors = gradList,
                    solidColor = solidColor,
                    imageUriOrPath = imageUriOrPath,
                    showDoodle = showDoodle,
                    doodleOpacity = doodleOpacity
                )
            } catch (_: Exception) {
                null
            }
        }

        // ── Curated Presets ──────────────────────────────────────────
        val DEFAULT_EMERALD = ChatWallpaper(
            id = "default",
            name = "GoChat Emerald",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF111B21.toInt(), 0xFF0B141A.toInt()),
            showDoodle = true,
            doodleOpacity = 0.06f
        )

        val CYBERPUNK_NEON = ChatWallpaper(
            id = "cyberpunk",
            name = "Cyberpunk Neon",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF0D0221.toInt(), 0xFF05010D.toInt()),
            showDoodle = true,
            doodleOpacity = 0.08f
        )

        val EMERALD_GLASS = ChatWallpaper(
            id = "emerald_glass",
            name = "Emerald Glass",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF0A231C.toInt(), 0xFF03140F.toInt()),
            showDoodle = true,
            doodleOpacity = 0.07f
        )

        val SUNSET_AURORA = ChatWallpaper(
            id = "sunset_aurora",
            name = "Sunset Aurora",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF200F21.toInt(), 0xFF0F0612.toInt()),
            showDoodle = true,
            doodleOpacity = 0.06f
        )

        val MIDNIGHT_OLED = ChatWallpaper(
            id = "midnight_oled",
            name = "Midnight OLED",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF000000.toInt(), 0xFF000000.toInt()),
            showDoodle = false,
            doodleOpacity = 0.0f
        )

        val NORDIC_ICE = ChatWallpaper(
            id = "nordic_ice",
            name = "Nordic Ice",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF0F172A.toInt(), 0xFF020617.toInt()),
            showDoodle = true,
            doodleOpacity = 0.05f
        )

        val LAVENDER_DREAM = ChatWallpaper(
            id = "lavender_dream",
            name = "Lavender Dream",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF1E1035.toInt(), 0xFF0F071D.toInt()),
            showDoodle = true,
            doodleOpacity = 0.06f
        )

        val CYBER_MATRIX = ChatWallpaper(
            id = "matrix_lime",
            name = "Cyber Matrix",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF041208.toInt(), 0xFF000502.toInt()),
            showDoodle = true,
            doodleOpacity = 0.10f
        )

        val DEEP_OCEAN = ChatWallpaper(
            id = "deep_ocean",
            name = "Deep Ocean",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF031B33.toInt(), 0xFF010E1A.toInt()),
            showDoodle = true,
            doodleOpacity = 0.06f
        )

        val SLATE_CHARCOAL = ChatWallpaper(
            id = "slate_charcoal",
            name = "Slate Charcoal",
            type = WallpaperType.PRESET,
            bgGradientColors = listOf(0xFF1E293B.toInt(), 0xFF0F172A.toInt()),
            showDoodle = true,
            doodleOpacity = 0.05f
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

        // ── Curated Solid Colors ─────────────────────────────────────
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
    }
}
