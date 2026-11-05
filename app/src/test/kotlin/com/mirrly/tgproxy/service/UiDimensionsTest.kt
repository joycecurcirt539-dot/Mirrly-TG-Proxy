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
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDimensionsTest {

    @Test
    fun uiDimensions_constantsValidation() {
        assertEquals(0.80f, PreferencesManager.UI_SCALE_MIN, 0.001f)
        assertEquals(1.25f, PreferencesManager.UI_SCALE_MAX, 0.001f)
        assertEquals(1.00f, PreferencesManager.UI_SCALE_DEFAULT, 0.001f)

        assertEquals(0.80f, PreferencesManager.TEXT_SCALE_MIN, 0.001f)
        assertEquals(1.30f, PreferencesManager.TEXT_SCALE_MAX, 0.001f)
        assertEquals(1.00f, PreferencesManager.TEXT_SCALE_DEFAULT, 0.001f)

        assertEquals(0.70f, PreferencesManager.ORBITAL_SPHERES_SCALE_MIN, 0.001f)
        assertEquals(1.30f, PreferencesManager.ORBITAL_SPHERES_SCALE_MAX, 0.001f)
        assertEquals(1.00f, PreferencesManager.ORBITAL_SPHERES_SCALE_DEFAULT, 0.001f)

        assertEquals(0.50f, PreferencesManager.WALLPAPER_PARTICLES_SIZE_MIN, 0.001f)
        assertEquals(2.50f, PreferencesManager.WALLPAPER_PARTICLES_SIZE_MAX, 0.001f)
        assertEquals(1.00f, PreferencesManager.WALLPAPER_PARTICLES_SIZE_DEFAULT, 0.001f)
    }

    @Test
    fun uiScale_clampingLogic() {
        val belowMin = 0.50f
        val aboveMax = 2.00f
        val inRange = 1.15f

        val clampedLow = belowMin.coerceIn(PreferencesManager.UI_SCALE_MIN, PreferencesManager.UI_SCALE_MAX)
        val clampedHigh = aboveMax.coerceIn(PreferencesManager.UI_SCALE_MIN, PreferencesManager.UI_SCALE_MAX)
        val clampedNormal = inRange.coerceIn(PreferencesManager.UI_SCALE_MIN, PreferencesManager.UI_SCALE_MAX)

        assertEquals(PreferencesManager.UI_SCALE_MIN, clampedLow, 0.001f)
        assertEquals(PreferencesManager.UI_SCALE_MAX, clampedHigh, 0.001f)
        assertEquals(inRange, clampedNormal, 0.001f)
    }

    @Test
    fun textScale_clampingLogic() {
        val belowMin = 0.20f
        val aboveMax = 3.00f
        val inRange = 1.10f

        val clampedLow = belowMin.coerceIn(PreferencesManager.TEXT_SCALE_MIN, PreferencesManager.TEXT_SCALE_MAX)
        val clampedHigh = aboveMax.coerceIn(PreferencesManager.TEXT_SCALE_MIN, PreferencesManager.TEXT_SCALE_MAX)
        val clampedNormal = inRange.coerceIn(PreferencesManager.TEXT_SCALE_MIN, PreferencesManager.TEXT_SCALE_MAX)

        assertEquals(PreferencesManager.TEXT_SCALE_MIN, clampedLow, 0.001f)
        assertEquals(PreferencesManager.TEXT_SCALE_MAX, clampedHigh, 0.001f)
        assertEquals(inRange, clampedNormal, 0.001f)
    }

    @Test
    fun orbitalSpheresScale_clampingLogic() {
        val belowMin = 0.10f
        val aboveMax = 2.50f
        val inRange = 0.85f

        val clampedLow = belowMin.coerceIn(PreferencesManager.ORBITAL_SPHERES_SCALE_MIN, PreferencesManager.ORBITAL_SPHERES_SCALE_MAX)
        val clampedHigh = aboveMax.coerceIn(PreferencesManager.ORBITAL_SPHERES_SCALE_MIN, PreferencesManager.ORBITAL_SPHERES_SCALE_MAX)
        val clampedNormal = inRange.coerceIn(PreferencesManager.ORBITAL_SPHERES_SCALE_MIN, PreferencesManager.ORBITAL_SPHERES_SCALE_MAX)

        assertEquals(PreferencesManager.ORBITAL_SPHERES_SCALE_MIN, clampedLow, 0.001f)
        assertEquals(PreferencesManager.ORBITAL_SPHERES_SCALE_MAX, clampedHigh, 0.001f)
        assertEquals(inRange, clampedNormal, 0.001f)
    }

    @Test
    fun particleSize_clampingLogic() {
        val belowMin = 0.10f
        val aboveMax = 5.00f
        val inRange = 1.75f

        val clampedLow = belowMin.coerceIn(PreferencesManager.WALLPAPER_PARTICLES_SIZE_MIN, PreferencesManager.WALLPAPER_PARTICLES_SIZE_MAX)
        val clampedHigh = aboveMax.coerceIn(PreferencesManager.WALLPAPER_PARTICLES_SIZE_MIN, PreferencesManager.WALLPAPER_PARTICLES_SIZE_MAX)
        val clampedNormal = inRange.coerceIn(PreferencesManager.WALLPAPER_PARTICLES_SIZE_MIN, PreferencesManager.WALLPAPER_PARTICLES_SIZE_MAX)

        assertEquals(PreferencesManager.WALLPAPER_PARTICLES_SIZE_MIN, clampedLow, 0.001f)
        assertEquals(PreferencesManager.WALLPAPER_PARTICLES_SIZE_MAX, clampedHigh, 0.001f)
        assertEquals(inRange, clampedNormal, 0.001f)
    }

    @Test
    fun proportionalRingScaling_preservesAspectRatio() {
        val baseRing = 215f
        val scale = 1.20f
        val scaledRing = baseRing * scale
        val powerIcon = scaledRing * (155f / 220f)

        assertEquals(258f, scaledRing, 0.001f)
        val ratio = powerIcon / scaledRing
        assertEquals(155f / 220f, ratio, 0.001f)
    }

    @Test
    fun cardCornerStyle_parsingAndFallback() {
        assertEquals(CardCornerStyle.ROUNDED, CardCornerStyle.fromString("rounded"))
        assertEquals(CardCornerStyle.SHARP, CardCornerStyle.fromString("sharp"))
        assertEquals(CardCornerStyle.CYBER_CHISEL, CardCornerStyle.fromString("cyber_chisel"))
        assertEquals(CardCornerStyle.ROUNDED, CardCornerStyle.fromString(null))
        assertEquals(CardCornerStyle.ROUNDED, CardCornerStyle.fromString("invalid"))
    }

    @Test
    fun cardCornerRadius_clampingLogic() {
        val belowMin = 2.0f
        val aboveMax = 36.0f
        val inRange = 18.0f

        val clampedLow = belowMin.coerceIn(PreferencesManager.CARD_CORNER_RADIUS_DP_MIN, PreferencesManager.CARD_CORNER_RADIUS_DP_MAX)
        val clampedHigh = aboveMax.coerceIn(PreferencesManager.CARD_CORNER_RADIUS_DP_MIN, PreferencesManager.CARD_CORNER_RADIUS_DP_MAX)
        val clampedNormal = inRange.coerceIn(PreferencesManager.CARD_CORNER_RADIUS_DP_MIN, PreferencesManager.CARD_CORNER_RADIUS_DP_MAX)

        assertEquals(PreferencesManager.CARD_CORNER_RADIUS_DP_MIN, clampedLow, 0.001f)
        assertEquals(PreferencesManager.CARD_CORNER_RADIUS_DP_MAX, clampedHigh, 0.001f)
        assertEquals(inRange, clampedNormal, 0.001f)
    }

    @Test
    fun hapticProfile_parsingAndFallback() {
        assertEquals(HapticProfile.MECHANICAL_CLICK, HapticProfile.fromString("mechanical_click"))
        assertEquals(HapticProfile.SUBBASS_PULSE, HapticProfile.fromString("subbass_pulse"))
        assertEquals(HapticProfile.CYBERNETIC_DOUBLE_TICK, HapticProfile.fromString("cybernetic_double_tick"))
        assertEquals(HapticProfile.STEALTH, HapticProfile.fromString("stealth"))
        assertEquals(HapticProfile.MECHANICAL_CLICK, HapticProfile.fromString(null))
        assertEquals(HapticProfile.MECHANICAL_CLICK, HapticProfile.fromString("unknown"))
    }

    @Test
    fun agslShaderPreset_parsingAndFallback() {
        assertEquals(AgslShaderPreset.PERSPECTIVE_GRID, AgslShaderPreset.fromString("perspective_grid"))
        assertEquals(AgslShaderPreset.DIGITAL_STREAM, AgslShaderPreset.fromString("digital_stream"))
        assertEquals(AgslShaderPreset.SCANLINES, AgslShaderPreset.fromString("scanlines"))
        assertEquals(AgslShaderPreset.PERSPECTIVE_GRID, AgslShaderPreset.fromString(null))
        assertEquals(AgslShaderPreset.PERSPECTIVE_GRID, AgslShaderPreset.fromString("unknown"))
    }

    @Test
    fun parallaxDepth_clampingLogic() {
        val belowMin = 0.05f
        val aboveMax = 5.0f
        val inRange = 1.8f

        val clampedLow = belowMin.coerceIn(PreferencesManager.WALLPAPER_PARALLAX_DEPTH_MIN, PreferencesManager.WALLPAPER_PARALLAX_DEPTH_MAX)
        val clampedHigh = aboveMax.coerceIn(PreferencesManager.WALLPAPER_PARALLAX_DEPTH_MIN, PreferencesManager.WALLPAPER_PARALLAX_DEPTH_MAX)
        val clampedNormal = inRange.coerceIn(PreferencesManager.WALLPAPER_PARALLAX_DEPTH_MIN, PreferencesManager.WALLPAPER_PARALLAX_DEPTH_MAX)

        assertEquals(PreferencesManager.WALLPAPER_PARALLAX_DEPTH_MIN, clampedLow, 0.001f)
        assertEquals(PreferencesManager.WALLPAPER_PARALLAX_DEPTH_MAX, clampedHigh, 0.001f)
        assertEquals(inRange, clampedNormal, 0.001f)
    }

    @Test
    fun liquidGlass_constantsValidation() {
        assertEquals("liquid_glass_enabled", PreferencesManager.KEY_LIQUID_GLASS_ENABLED)
    }
}
