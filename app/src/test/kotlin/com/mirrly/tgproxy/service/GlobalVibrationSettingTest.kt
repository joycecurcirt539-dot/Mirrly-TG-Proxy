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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class GlobalVibrationSettingTest {

    private fun locateResFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("..", relativePath),
            File("../app", relativePath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Resource file not found: $relativePath")
    }

    private fun parseStringsXml(file: File): Map<String, String> {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = builder.parse(file)
        val nodes = doc.getElementsByTagName("string")
        val result = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as Element
            result[node.getAttribute("name")] = node.textContent
        }
        return result
    }

    @Test
    fun preferenceKey_matchesExpectedName() {
        assertEquals("disable_all_vibration", PreferencesManager.KEY_VIBRATION_DISABLED)
    }

    @Test
    fun isVibrationMuted_respectsGlobalToggleAndStealthProfile() {
        // Active haptic profiles are enabled when global disable toggle is off.
        assertFalse(HapticProfile.isVibrationMuted(isGlobalDisabled = false, profile = HapticProfile.MECHANICAL_CLICK))
        assertFalse(HapticProfile.isVibrationMuted(isGlobalDisabled = false, profile = HapticProfile.SUBBASS_PULSE))
        assertFalse(HapticProfile.isVibrationMuted(isGlobalDisabled = false, profile = HapticProfile.CYBERNETIC_DOUBLE_TICK))

        // Stealth profile mutes UI/game haptics even when the global toggle is off.
        assertTrue(HapticProfile.isVibrationMuted(isGlobalDisabled = false, profile = HapticProfile.STEALTH))

        // Global disable toggle mutes every profile unconditionally.
        for (profile in HapticProfile.entries) {
            assertTrue(
                "Profile ${profile.name} must be muted when global vibration is disabled",
                HapticProfile.isVibrationMuted(isGlobalDisabled = true, profile = profile)
            )
        }
    }

    @Test
    fun disableVibrationStrings_existInAllLocalesWithoutParentheses() {
        val requiredKeys = listOf(
            "settings_disable_vibration_title",
            "settings_disable_vibration_desc",
            "settings_vibration_disabled_status",
            "info_disable_vibration_title",
            "info_disable_vibration_body"
        )
        val localeMaps = listOf(
            "ru" to parseStringsXml(locateResFile("src/main/res/values/strings.xml")),
            "en" to parseStringsXml(locateResFile("src/main/res/values-en/strings.xml")),
            "fa" to parseStringsXml(locateResFile("src/main/res/values-fa/strings.xml"))
        )

        for ((locale, strings) in localeMaps) {
            for (key in requiredKeys) {
                val value = strings[key]
                assertNotNull("Missing key '$key' in locale '$locale'", value)
                assertTrue("Key '$key' in locale '$locale' must not be blank", value!!.isNotBlank())
                if (key.startsWith("settings_")) {
                    assertFalse(
                        "UI label '$key' in locale '$locale' must not contain parentheses",
                        value.contains('(') || value.contains(')')
                    )
                }
            }
        }
    }
}
