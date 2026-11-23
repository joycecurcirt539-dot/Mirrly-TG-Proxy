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

import com.mirrly.tgproxy.ui.resolveStaticPreviewPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperPresetTest {

    @Test
    fun builtinPresets_containExpectedPresets() {
        val presets = WallpaperPreset.BUILTIN_PRESETS
        assertEquals(5, presets.size)
        assertEquals(WallpaperPreset.PRESET_ID_DEFAULT_AMOLED, presets.first().id)

        val defaultAmoled = presets.find { it.id == WallpaperPreset.PRESET_ID_DEFAULT_AMOLED }
        assertNotNull(defaultAmoled)
        assertEquals(PreferencesManager.WALLPAPER_TYPE_IMAGE, defaultAmoled?.type)
        assertEquals(0f, defaultAmoled?.blurDp ?: -1f, 0.001f)
        assertEquals(0f, defaultAmoled?.dimFactor ?: -1f, 0.001f)
        assertTrue(defaultAmoled?.isBuiltIn == true)

        val spaceToilet = presets.find { it.id == WallpaperPreset.PRESET_ID_SPACE_TOILET }
        assertNotNull(spaceToilet)
        assertEquals(PreferencesManager.WALLPAPER_TYPE_VIDEO, spaceToilet?.type)
        assertEquals(0f, spaceToilet?.blurDp ?: -1f, 0.001f)
        assertEquals(0.70f, spaceToilet?.dimFactor ?: -1f, 0.001f)
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

        val ranaldu = presets.find { it.id == WallpaperPreset.PRESET_ID_RANALDU }
        assertNotNull(ranaldu)
        assertEquals("RANALDU", ranaldu?.title)
        assertEquals(PreferencesManager.WALLPAPER_TYPE_IMAGE, ranaldu?.type)
        assertEquals("wallpapers/RANALDU.png", ranaldu?.assetPath)
        assertEquals("wallpapers/wallpaper_ranaldu_preview.jpg", ranaldu?.previewAssetPath)
        assertEquals(0f, ranaldu?.blurDp ?: -1f, 0.001f)
        assertEquals(0.45f, ranaldu?.dimFactor ?: -1f, 0.001f)
        assertTrue(ranaldu?.isBuiltIn == true)
        assertEquals("wallpapers/wallpaper_ranaldu_preview.jpg", ranaldu?.resolveStaticPreviewPath())
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

    @Test
    fun resolveStaticPreviewPath_skipsMp4AndUsesSpaceToiletJpg() {
        val spaceToilet = WallpaperPreset.BUILTIN_PRESETS.first {
            it.id == WallpaperPreset.PRESET_ID_SPACE_TOILET
        }
        assertEquals("wallpapers/space_toilet_preview.jpg", spaceToilet.resolveStaticPreviewPath())

        val userVideoWithThumb = WallpaperPreset(
            id = "user_vid_1",
            title = "Clip",
            type = PreferencesManager.WALLPAPER_TYPE_VIDEO,
            filePath = "/data/user/0/com.mirrly.tgproxy/files/clip.mp4",
            previewFilePath = "/data/user/0/com.mirrly.tgproxy/files/clip_preview.jpg",
            blurDp = 0f,
            dimFactor = 0.5f
        )
        assertEquals(
            "/data/user/0/com.mirrly.tgproxy/files/clip_preview.jpg",
            userVideoWithThumb.resolveStaticPreviewPath()
        )

        val userVideoWithoutThumb = userVideoWithThumb.copy(previewFilePath = "/data/user/0/com.mirrly.tgproxy/files/clip.mp4")
        assertNull(userVideoWithoutThumb.resolveStaticPreviewPath())
    }
}
