/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.mirrly.tgproxy.service

import androidx.annotation.StringRes
import com.mirrly.tgproxy.R
import org.json.JSONObject

/**
 * Wallpaper preset representing either a built-in pre-packaged asset or a custom user-created preset.
 */
data class WallpaperPreset(
    val id: String,
    val title: String,
    @StringRes val titleRes: Int? = null,
    val type: String, // PreferencesManager.WALLPAPER_TYPE_VIDEO or WALLPAPER_TYPE_IMAGE
    val assetPath: String? = null,
    val previewAssetPath: String? = null,
    val filePath: String? = null,
    val previewFilePath: String? = null,
    val blurDp: Float,
    val dimFactor: Float,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("type", type)
        put("assetPath", assetPath ?: "")
        put("previewAssetPath", previewAssetPath ?: "")
        put("filePath", filePath ?: "")
        put("previewFilePath", previewFilePath ?: "")
        put("blurDp", blurDp.toDouble())
        put("dimFactor", dimFactor.toDouble())
        put("isBuiltIn", isBuiltIn)
        put("createdAt", createdAt)
    }

    companion object {
        const val PRESET_ID_SPACE_TOILET = "builtin_space_toilet"
        const val PRESET_ID_GRASS = "builtin_grass"
        const val PRESET_ID_BREEZE = "builtin_breeze"

        val BUILTIN_PRESETS: List<WallpaperPreset> = listOf(
            WallpaperPreset(
                id = PRESET_ID_SPACE_TOILET,
                title = "Space Toilet",
                titleRes = R.string.settings_customization_preset_space_toilet,
                type = PreferencesManager.WALLPAPER_TYPE_VIDEO,
                assetPath = "wallpapers/space_toilet.mp4",
                previewAssetPath = "wallpapers/space_toilet_thumb.mp4",
                blurDp = 0f,
                dimFactor = 0.50f,
                isBuiltIn = true
            ),
            WallpaperPreset(
                id = PRESET_ID_GRASS,
                title = "Утренняя роса",
                titleRes = R.string.settings_customization_preset_grass,
                type = PreferencesManager.WALLPAPER_TYPE_IMAGE,
                assetPath = "wallpapers/wallpaper_grass.jpg",
                previewAssetPath = "wallpapers/wallpaper_grass_preview.jpg",
                blurDp = 5f,
                dimFactor = 0.50f,
                isBuiltIn = true
            ),
            WallpaperPreset(
                id = PRESET_ID_BREEZE,
                title = "Морской бриз",
                titleRes = R.string.settings_customization_preset_breeze,
                type = PreferencesManager.WALLPAPER_TYPE_IMAGE,
                assetPath = "wallpapers/wallpaper_breeze.jpg",
                previewAssetPath = "wallpapers/wallpaper_breeze_preview.jpg",
                blurDp = 5f,
                dimFactor = 0.50f,
                isBuiltIn = true
            )
        )

        fun fromJson(json: JSONObject): WallpaperPreset {
            return WallpaperPreset(
                id = json.getString("id"),
                title = json.optString("title", "Пресет"),
                type = json.optString("type", PreferencesManager.WALLPAPER_TYPE_IMAGE),
                assetPath = json.optString("assetPath").takeIf { it.isNotBlank() },
                previewAssetPath = json.optString("previewAssetPath").takeIf { it.isNotBlank() },
                filePath = json.optString("filePath").takeIf { it.isNotBlank() },
                previewFilePath = json.optString("previewFilePath").takeIf { it.isNotBlank() },
                blurDp = json.optDouble("blurDp", 25.0).toFloat(),
                dimFactor = json.optDouble("dimFactor", 0.50).toFloat(),
                isBuiltIn = json.optBoolean("isBuiltIn", false),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
