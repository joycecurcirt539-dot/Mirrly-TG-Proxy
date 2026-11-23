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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperDefaultMigrationTest {

    @Test
    fun alreadyMigrated_alwaysReturnsNoop_evenWithCustomPhoto() {
        val plan = WallpaperDefaultMigrationPlanner.plan(
            WallpaperMigrationInput(
                alreadyMigrated = true,
                wallpaperEnabled = true,
                wallpaperType = PreferencesManager.WALLPAPER_TYPE_IMAGE,
                activePresetId = null,
                hasValidMediaFile = true,
                matchesBuiltinAssetSize = false,
                matchingExistingUserPresetId = null
            )
        )

        assertEquals(WallpaperMigrationAction.NOOP_ALREADY_MIGRATED, plan.action)
        assertNull(plan.reuseExistingPresetId)
    }

    @Test
    fun freshInstallWithoutWallpaper_resetsSilentlyToBlack() {
        val plan = WallpaperDefaultMigrationPlanner.plan(
            WallpaperMigrationInput(
                alreadyMigrated = false,
                wallpaperEnabled = false,
                wallpaperType = null,
                activePresetId = null,
                hasValidMediaFile = false,
                matchesBuiltinAssetSize = false,
                matchingExistingUserPresetId = null
            )
        )

        assertEquals(WallpaperMigrationAction.RESET_SILENT_TO_BLACK, plan.action)
        assertNull(plan.reuseExistingPresetId)
    }

    @Test
    fun builtinPresets_allResetSilentlyWithoutNotification() {
        val builtinCases = listOf(
            WallpaperPreset.PRESET_ID_DEFAULT_AMOLED to PreferencesManager.WALLPAPER_TYPE_IMAGE,
            WallpaperPreset.PRESET_ID_SPACE_TOILET to PreferencesManager.WALLPAPER_TYPE_VIDEO,
            WallpaperPreset.PRESET_ID_GRASS to PreferencesManager.WALLPAPER_TYPE_IMAGE,
            WallpaperPreset.PRESET_ID_BREEZE to PreferencesManager.WALLPAPER_TYPE_IMAGE,
            WallpaperPreset.PRESET_ID_RANALDU to PreferencesManager.WALLPAPER_TYPE_IMAGE
        )

        builtinCases.forEach { (presetId, mediaType) ->
            val plan = WallpaperDefaultMigrationPlanner.plan(
                WallpaperMigrationInput(
                    alreadyMigrated = false,
                    wallpaperEnabled = true,
                    wallpaperType = mediaType,
                    activePresetId = presetId,
                    hasValidMediaFile = true,
                    matchesBuiltinAssetSize = false,
                    matchingExistingUserPresetId = "user_unrelated"
                )
            )

            assertEquals(
                "Expected silent reset for built-in preset $presetId",
                WallpaperMigrationAction.RESET_SILENT_TO_BLACK,
                plan.action
            )
            assertNull(plan.reuseExistingPresetId)
        }
    }

    @Test
    fun legacyInstallWithNullPresetIdAndBuiltinAssetSize_resetsSilently() {
        assertTrue(
            WallpaperDefaultMigrationPlanner.isLegacyBuiltinAssetSize(
                PreferencesManager.WALLPAPER_TYPE_VIDEO,
                WallpaperDefaultMigrationPlanner.SPACE_TOILET_VIDEO_BYTES
            )
        )
        assertTrue(
            WallpaperDefaultMigrationPlanner.isLegacyBuiltinAssetSize(
                PreferencesManager.WALLPAPER_TYPE_IMAGE,
                WallpaperDefaultMigrationPlanner.GRASS_IMAGE_BYTES
            )
        )
        assertTrue(
            WallpaperDefaultMigrationPlanner.isLegacyBuiltinAssetSize(
                PreferencesManager.WALLPAPER_TYPE_IMAGE,
                WallpaperDefaultMigrationPlanner.BREEZE_IMAGE_BYTES
            )
        )
        assertFalse(
            WallpaperDefaultMigrationPlanner.isLegacyBuiltinAssetSize(
                PreferencesManager.WALLPAPER_TYPE_IMAGE,
                WallpaperDefaultMigrationPlanner.SPACE_TOILET_VIDEO_BYTES
            )
        )
        assertFalse(
            WallpaperDefaultMigrationPlanner.isLegacyBuiltinAssetSize(
                PreferencesManager.WALLPAPER_TYPE_VIDEO,
                WallpaperDefaultMigrationPlanner.GRASS_IMAGE_BYTES
            )
        )
        assertFalse(
            WallpaperDefaultMigrationPlanner.isLegacyBuiltinAssetSize(
                PreferencesManager.WALLPAPER_TYPE_IMAGE,
                123_456L
            )
        )

        val plan = WallpaperDefaultMigrationPlanner.plan(
            WallpaperMigrationInput(
                alreadyMigrated = false,
                wallpaperEnabled = true,
                wallpaperType = PreferencesManager.WALLPAPER_TYPE_VIDEO,
                activePresetId = null,
                hasValidMediaFile = true,
                matchesBuiltinAssetSize = true,
                matchingExistingUserPresetId = null
            )
        )

        assertEquals(WallpaperMigrationAction.RESET_SILENT_TO_BLACK, plan.action)
    }

    @Test
    fun proceduralShaderBackground_resetsSilently() {
        val plan = WallpaperDefaultMigrationPlanner.plan(
            WallpaperMigrationInput(
                alreadyMigrated = false,
                wallpaperEnabled = true,
                wallpaperType = PreferencesManager.WALLPAPER_TYPE_SHADER,
                activePresetId = null,
                hasValidMediaFile = true,
                matchesBuiltinAssetSize = false,
                matchingExistingUserPresetId = null
            )
        )

        assertEquals(WallpaperMigrationAction.RESET_SILENT_TO_BLACK, plan.action)
    }

    @Test
    fun missingOrEmptyMediaFile_resetsSilentlyEvenWhenEnabled() {
        val plan = WallpaperDefaultMigrationPlanner.plan(
            WallpaperMigrationInput(
                alreadyMigrated = false,
                wallpaperEnabled = true,
                wallpaperType = PreferencesManager.WALLPAPER_TYPE_IMAGE,
                activePresetId = null,
                hasValidMediaFile = false,
                matchesBuiltinAssetSize = false,
                matchingExistingUserPresetId = "user_123"
            )
        )

        assertEquals(WallpaperMigrationAction.RESET_SILENT_TO_BLACK, plan.action)
    }

    @Test
    fun unsavedCustomPhoto_preservesAsNewProfileAndNotifies() {
        val plan = WallpaperDefaultMigrationPlanner.plan(
            WallpaperMigrationInput(
                alreadyMigrated = false,
                wallpaperEnabled = true,
                wallpaperType = PreferencesManager.WALLPAPER_TYPE_IMAGE,
                activePresetId = null,
                hasValidMediaFile = true,
                matchesBuiltinAssetSize = false,
                matchingExistingUserPresetId = null
            )
        )

        assertEquals(WallpaperMigrationAction.PRESERVE_CUSTOM_AND_RESET_TO_BLACK, plan.action)
        assertNull(plan.reuseExistingPresetId)
    }

    @Test
    fun unsavedCustomVideo_preservesAsNewProfileAndNotifies() {
        val plan = WallpaperDefaultMigrationPlanner.plan(
            WallpaperMigrationInput(
                alreadyMigrated = false,
                wallpaperEnabled = true,
                wallpaperType = PreferencesManager.WALLPAPER_TYPE_VIDEO,
                activePresetId = null,
                hasValidMediaFile = true,
                matchesBuiltinAssetSize = false,
                matchingExistingUserPresetId = null
            )
        )

        assertEquals(WallpaperMigrationAction.PRESERVE_CUSTOM_AND_RESET_TO_BLACK, plan.action)
        assertNull(plan.reuseExistingPresetId)
    }

    @Test
    fun existingSavedUserPreset_reusesExistingPresetIdWithoutDuplication() {
        val plan = WallpaperDefaultMigrationPlanner.plan(
            WallpaperMigrationInput(
                alreadyMigrated = false,
                wallpaperEnabled = true,
                wallpaperType = PreferencesManager.WALLPAPER_TYPE_IMAGE,
                activePresetId = "user_123",
                hasValidMediaFile = true,
                matchesBuiltinAssetSize = false,
                matchingExistingUserPresetId = "user_123"
            )
        )

        assertEquals(WallpaperMigrationAction.PRESERVE_CUSTOM_AND_RESET_TO_BLACK, plan.action)
        assertEquals("user_123", plan.reuseExistingPresetId)
    }

    @Test
    fun deletedUserPresetWithActiveCustomMedia_fallsBackToCreatingNewProfile() {
        val plan = WallpaperDefaultMigrationPlanner.plan(
            WallpaperMigrationInput(
                alreadyMigrated = false,
                wallpaperEnabled = true,
                wallpaperType = PreferencesManager.WALLPAPER_TYPE_VIDEO,
                activePresetId = "deleted_user_preset",
                hasValidMediaFile = true,
                matchesBuiltinAssetSize = false,
                matchingExistingUserPresetId = null
            )
        )

        assertEquals(WallpaperMigrationAction.PRESERVE_CUSTOM_AND_RESET_TO_BLACK, plan.action)
        assertNull(plan.reuseExistingPresetId)
    }
}
