/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui.theme

import androidx.compose.ui.graphics.Color
import com.mirrly.tgproxy.core.ProxyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ThemeAccentTest {

    @Test
    fun testThemeAccentModeFromStorageKey() {
        assertEquals(ThemeAccentMode.PROTOCOL_DEFAULT, ThemeAccentMode.fromStorageKey("PROTOCOL_DEFAULT"))
        assertEquals(ThemeAccentMode.STEALTH, ThemeAccentMode.fromStorageKey("STEALTH"))
        assertEquals(ThemeAccentMode.PRESET, ThemeAccentMode.fromStorageKey("PRESET"))
        assertEquals(ThemeAccentMode.MONET, ThemeAccentMode.fromStorageKey("MONET"))
        assertEquals(ThemeAccentMode.CUSTOM, ThemeAccentMode.fromStorageKey("CUSTOM"))

        // Case insensitivity
        assertEquals(ThemeAccentMode.STEALTH, ThemeAccentMode.fromStorageKey("stealth"))
        assertEquals(ThemeAccentMode.CUSTOM, ThemeAccentMode.fromStorageKey("Custom"))

        // Fallback for null or unknown
        assertEquals(ThemeAccentMode.PROTOCOL_DEFAULT, ThemeAccentMode.fromStorageKey(null))
        assertEquals(ThemeAccentMode.PROTOCOL_DEFAULT, ThemeAccentMode.fromStorageKey(""))
        assertEquals(ThemeAccentMode.PROTOCOL_DEFAULT, ThemeAccentMode.fromStorageKey("UNKNOWN_MODE"))
    }

    @Test
    fun testColorEngineHexParsingValid() {
        // Standard 6-digit with #
        val ruby = ColorEngine.parseHexColor("#FF2A6D")
        assertNotNull(ruby)
        assertEquals(1.0f, ruby!!.alpha, 0.01f)
        assertEquals(1.0f, ruby.red, 0.01f)
        assertEquals(42f / 255f, ruby.green, 0.01f)
        assertEquals(109f / 255f, ruby.blue, 0.01f)

        // Standard 6-digit without #
        val lime = ColorEngine.parseHexColor("A6FF00")
        assertNotNull(lime)
        assertEquals(166f / 255f, lime!!.red, 0.01f)
        assertEquals(1.0f, lime.green, 0.01f)
        assertEquals(0.0f, lime.blue, 0.01f)

        // 3-digit shorthand
        val whiteShort = ColorEngine.parseHexColor("#FFF")
        assertNotNull(whiteShort)
        assertEquals(1.0f, whiteShort!!.red, 0.01f)
        assertEquals(1.0f, whiteShort.green, 0.01f)
        assertEquals(1.0f, whiteShort.blue, 0.01f)

        val blueShort = ColorEngine.parseHexColor("00F")
        assertNotNull(blueShort)
        assertEquals(0.0f, blueShort!!.red, 0.01f)
        assertEquals(0.0f, blueShort.green, 0.01f)
        assertEquals(1.0f, blueShort.blue, 0.01f)

        // 8-digit ARGB
        val semiGreen = ColorEngine.parseHexColor("#8000FF00")
        assertNotNull(semiGreen)
        assertEquals(128f / 255f, semiGreen!!.alpha, 0.02f)
        assertEquals(0.0f, semiGreen.red, 0.01f)
        assertEquals(1.0f, semiGreen.green, 0.01f)
    }

    @Test
    fun testColorEngineHexParsingInvalidReturnsNull() {
        assertNull(ColorEngine.parseHexColor(null))
        assertNull(ColorEngine.parseHexColor(""))
        assertNull(ColorEngine.parseHexColor("   "))
        assertNull(ColorEngine.parseHexColor("#12"))
        assertNull(ColorEngine.parseHexColor("#12345"))
        assertNull(ColorEngine.parseHexColor("#GGGGGG"))
        assertNull(ColorEngine.parseHexColor("INVALID_HEX"))
    }

    @Test
    fun testColorEngineToHex() {
        assertEquals("#FF2A6D", ColorEngine.toHex(Color(0xFFFF2A6D)))
        assertEquals("#A6FF00", ColorEngine.toHex(Color(0xFFA6FF00)))
        assertEquals("#000000", ColorEngine.toHex(Color.Black))
        assertEquals("#FFFFFF", ColorEngine.toHex(Color.White))
    }

    @Test
    fun testHslRoundtripConversion() {
        val testColors = listOf(
            Color(0xFFFF0000), // Pure Red
            Color(0xFF00FF00), // Pure Green
            Color(0xFF0000FF), // Pure Blue
            Color(0xFF00F5D4), // Cyan
            Color(0xFFB388FF), // Purple
            Color(0xFFFFFFFF), // White
            Color(0xFF000000)  // Black
        )

        for (c in testColors) {
            val (h, s, l) = ColorEngine.rgbToHsl(c.red, c.green, c.blue)
            val reconstructed = ColorEngine.hslToColor(h, s, l)

            assertEquals("Red mismatch for $c", c.red, reconstructed.red, 0.02f)
            assertEquals("Green mismatch for $c", c.green, reconstructed.green, 0.02f)
            assertEquals("Blue mismatch for $c", c.blue, reconstructed.blue, 0.02f)
        }
    }

    @Test
    fun testColorEngineGenerateProtocolColors() {
        val baseColor = Color(0xFFE056FD)
        val palette = ColorEngine.generateProtocolColors(baseColor)

        // Primary is base color
        assertEquals(baseColor.red, palette.primary.red, 0.01f)
        assertEquals(baseColor.green, palette.primary.green, 0.01f)
        assertEquals(baseColor.blue, palette.primary.blue, 0.01f)
        assertEquals(1f, palette.primary.alpha, 0.01f)

        // Glow has 24% alpha (0x3D)
        assertEquals(0.24f, palette.glow.alpha, 0.01f)
        assertEquals(baseColor.red, palette.glow.red, 0.01f)

        // Light has higher luminance
        val baseHsl = ColorEngine.rgbToHsl(baseColor.red, baseColor.green, baseColor.blue)
        val lightHsl = ColorEngine.rgbToHsl(palette.light.red, palette.light.green, palette.light.blue)
        assertTrue("Light should have higher luminance", lightHsl[2] >= baseHsl[2])

        // Orbs are present and non-null
        assertNotNull(palette.orb1)
        assertNotNull(palette.orb2)
        assertNotNull(palette.orb3)
        assertNotNull(palette.orb4)
    }

    @Test
    fun testStealthPaletteValues() {
        assertEquals(Color(0xFFFFFFFF), StealthPalette.primary)
        assertEquals(Color(0x33FFFFFF), StealthPalette.glow)
        assertEquals(Color(0xFFB0B0B8), StealthPalette.secondary)
        assertEquals(Color(0xFFFFFFFF), StealthPalette.light)
        assertEquals(Color(0xFF383840), StealthPalette.orb1)
        assertEquals(Color(0xFF282830), StealthPalette.orb2)
        assertEquals(Color(0xFF1E1E24), StealthPalette.orb3)
        assertEquals(Color(0xFF121216), StealthPalette.orb4)
    }

    @Test
    fun testThemePresetsIntegrity() {
        assertEquals(5, ThemePresets.all.size)

        val ruby = ThemePresets.getPresetById("CYBER_RUBY")
        assertNotNull(ruby)
        assertEquals("#FF2A6D", ruby!!.hex)
        assertEquals(Color(0xFFFF2A6D), ruby.primaryColor)

        val lime = ThemePresets.getPresetById("ACID_LIME")
        assertNotNull(lime)
        assertEquals("#A6FF00", lime!!.hex)

        val amber = ThemePresets.getPresetById("ELECTRIC_AMBER")
        assertNotNull(amber)
        assertEquals("#FF6B2B", amber!!.hex)

        val blue = ThemePresets.getPresetById("COBALT_BLUE")
        assertNotNull(blue)
        assertEquals("#2979FF", blue!!.hex)

        val green = ThemePresets.getPresetById("MATRIX_GREEN")
        assertNotNull(green)
        assertEquals("#00E676", green!!.hex)

        // Case insensitivity
        assertEquals(ruby, ThemePresets.getPresetById("cyber_ruby"))
        assertNull(ThemePresets.getPresetById("NON_EXISTENT"))
    }
}
