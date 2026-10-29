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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperPresetTest {

    @Test
    fun builtinPresets_containExpectedThreePresets() {
        val presets = WallpaperPreset.BUILTIN_PRESETS
        assertEquals(3, presets.size)

        val spaceToilet = presets.find { it.id == WallpaperPreset.PRESET_ID_SPACE_TOILET }
        assertNotNull(spaceToilet)
        assertEquals(PreferencesManager.WALLPAPER_TYPE_VIDEO, spaceToilet?.type)
        assertEquals(0f, spaceToilet?.blurDp ?: -1f, 0.001f)
        assertEquals(0.50f, spaceToilet?.dimFactor ?: -1f, 0.001f)
        assertTrue(spaceToilet?.isBuiltIn == true)

        val grass = presets.find { it.id == WallpaperPreset.PRESET_ID_GRASS }
        assertNotNull(grass)
        assertEquals(PreferencesManager.WALLPAPER_TYPE_IMAGE, grass?.type)
        assertEquals(5f, grass?.blurDp ?: -1f, 0.001f)
        assertEquals(0.50f, grass?.dimFactor ?: -1f, 0.001f)
        assertTrue(grass?.isBuiltIn == true)

        val breeze = presets.find { it.id == WallpaperPreset.PRESET_ID_BREEZE }
        assertNotNull(breeze)
        assertEquals(PreferencesManager.WALLPAPER_TYPE_IMAGE, breeze?.type)
        assertEquals(5f, breeze?.blurDp ?: -1f, 0.001f)
        assertEquals(0.50f, breeze?.dimFactor ?: -1f, 0.001f)
        assertTrue(breeze?.isBuiltIn == true)
    }

    @Test
    fun userPreset_jsonSerializationCycle() {
        val original = WallpaperPreset(
            id = "custom_123",
            title = "Мой пресет",
            type = PreferencesManager.WALLPAPER_TYPE_IMAGE,
            filePath = "/data/user/0/com.mirrly.tgproxy/files/custom.jpg",
            blurDp = 12f,
            dimFactor = 0.40f,
            isBuiltIn = false
        )

        val json = original.toJson()
        val restored = WallpaperPreset.fromJson(json)

        assertEquals(original.id, restored.id)
        assertEquals(original.title, restored.title)
        assertEquals(original.type, restored.type)
        assertEquals(original.filePath, restored.filePath)
        assertEquals(original.blurDp, restored.blurDp, 0.001f)
        assertEquals(original.dimFactor, restored.dimFactor, 0.001f)
        assertEquals(original.isBuiltIn, restored.isBuiltIn)
    }
}
