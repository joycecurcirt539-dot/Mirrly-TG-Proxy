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

internal enum class WallpaperMigrationAction {
    NOOP_ALREADY_MIGRATED,
    RESET_SILENT_TO_BLACK,
    PRESERVE_CUSTOM_AND_RESET_TO_BLACK
}

internal data class WallpaperMigrationInput(
    val alreadyMigrated: Boolean,
    val wallpaperEnabled: Boolean,
    val wallpaperType: String?,
    val activePresetId: String?,
    val hasValidMediaFile: Boolean,
    val matchesBuiltinAssetSize: Boolean,
    val matchingExistingUserPresetId: String?
)

internal data class WallpaperMigrationPlan(
    val action: WallpaperMigrationAction,
    val reuseExistingPresetId: String? = null
)

/**
 * Pure decision planner for one-time default wallpaper migration to Pure Black AMOLED.
 * Preserves any genuine user-imported photo/video as a personal preset and suppresses
 * notification whenever a built-in wallpaper, procedural shader, or empty/missing file was used.
 */
internal object WallpaperDefaultMigrationPlanner {
    val BUILTIN_PRESET_IDS = setOf(
        WallpaperPreset.PRESET_ID_DEFAULT_AMOLED,
        WallpaperPreset.PRESET_ID_SPACE_TOILET,
        WallpaperPreset.PRESET_ID_GRASS,
        WallpaperPreset.PRESET_ID_BREEZE
    )

    // Exact byte sizes of bundled built-in assets in assets/wallpapers/ for legacy installs without activePresetId.
    const val SPACE_TOILET_VIDEO_BYTES = 3_541_999L
    const val GRASS_IMAGE_BYTES = 538_956L
    const val BREEZE_IMAGE_BYTES = 763_538L

    fun isLegacyBuiltinAssetSize(wallpaperType: String?, fileLengthBytes: Long): Boolean {
        return when (wallpaperType) {
            PreferencesManager.WALLPAPER_TYPE_VIDEO -> fileLengthBytes == SPACE_TOILET_VIDEO_BYTES
            PreferencesManager.WALLPAPER_TYPE_IMAGE ->
                fileLengthBytes == GRASS_IMAGE_BYTES || fileLengthBytes == BREEZE_IMAGE_BYTES
            else -> false
        }
    }

    fun plan(input: WallpaperMigrationInput): WallpaperMigrationPlan {
        if (input.alreadyMigrated) {
            return WallpaperMigrationPlan(WallpaperMigrationAction.NOOP_ALREADY_MIGRATED)
        }

        val isCustomMediaType = input.wallpaperType == PreferencesManager.WALLPAPER_TYPE_IMAGE ||
            input.wallpaperType == PreferencesManager.WALLPAPER_TYPE_VIDEO
        val isBuiltinPreset = input.activePresetId != null && input.activePresetId in BUILTIN_PRESET_IDS
        val isLegacyBuiltin = input.activePresetId == null && input.matchesBuiltinAssetSize

        val hasRealCustomMedia = input.wallpaperEnabled &&
            isCustomMediaType &&
            !isBuiltinPreset &&
            !isLegacyBuiltin &&
            input.hasValidMediaFile

        if (!hasRealCustomMedia) {
            return WallpaperMigrationPlan(WallpaperMigrationAction.RESET_SILENT_TO_BLACK)
        }

        return WallpaperMigrationPlan(
            action = WallpaperMigrationAction.PRESERVE_CUSTOM_AND_RESET_TO_BLACK,
            reuseExistingPresetId = input.matchingExistingUserPresetId
        )
    }
}
