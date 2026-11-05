/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgPathDataParserTest {

    @Test
    fun testExtractPathDataFromSvgTag() {
        val svgXml = """
            <svg viewBox="0 0 100 100">
                <path d="M 50 12 L 86 82 L 50 68 L 14 82 Z" stroke="#00F5D4"/>
            </svg>
        """.trimIndent()

        val extracted = SvgPathDataParser.extractPathData(svgXml)
        assertEquals("M 50 12 L 86 82 L 50 68 L 14 82 Z", extracted)
    }

    @Test
    fun testExtractPathDataFromMarkdownCodeBlock() {
        val markdown = """
            ```xml
            <svg>
                <path d="M 10 10 L 90 90 Z" />
            </svg>
            ```
        """.trimIndent()

        val extracted = SvgPathDataParser.extractPathData(markdown)
        assertEquals("M 10 10 L 90 90 Z", extracted)
    }

    @Test
    fun testRawPathDataPreserved() {
        val raw = "M 50 14 L 62 38 L 92 68 Z"
        val extracted = SvgPathDataParser.extractPathData(raw)
        assertEquals(raw, extracted)
    }

    @Test
    fun testValidateValidPath() {
        val valid = "M 50 12 L 86 82 L 50 68 L 14 82 Z"
        assertTrue(SvgPathDataParser.validatePathData(valid))
    }

    @Test
    fun testValidateInvalidPath() {
        val invalid = "INVALID SPLINE NOT A PATH !!!"
        assertFalse(SvgPathDataParser.validatePathData(invalid))
    }

    @Test
    fun testValidateEmptyPath() {
        assertFalse(SvgPathDataParser.validatePathData(""))
        assertFalse(SvgPathDataParser.validatePathData("   "))
    }

    @Test
    fun testCentralButtonPresetsAvailability() {
        val mtprotoPresets = CentralButtonPresets.getPresetsForProtocol("mtproto")
        val socks5Presets = CentralButtonPresets.getPresetsForProtocol("socks5")
        val vpnPresets = CentralButtonPresets.getPresetsForProtocol("vpn")

        assertTrue(mtprotoPresets.any { it.id == "default" })
        assertTrue(mtprotoPresets.any { it.id == "warp_glider" })
        assertTrue(mtprotoPresets.any { it.isCustom })

        assertTrue(socks5Presets.any { it.id == "default" })
        assertTrue(socks5Presets.any { it.id == "warp_glider" })
        assertTrue(socks5Presets.any { it.isCustom })

        assertTrue(vpnPresets.any { it.id == "default" })
        assertTrue(vpnPresets.any { it.isCustom })
    }

    @Test
    fun testPresetVectorCodesValid() {
        val presetIds = listOf("default", "warp_glider", "warp_cloud")
        for (id in presetIds) {
            val code = CentralButtonPresets.getPresetVectorCode(id)
            assertNotNull("Preset $id must have vector code", code)
            assertTrue("Preset $id vector code must be valid", SvgPathDataParser.validatePathData(code!!))
        }
    }

    @Test
    fun testAiPromptContainsMirrlyUiRequirements() {
        val prompt = CentralButtonPresets.buildAiPromptForProtocol("mtproto")
        assertTrue(prompt.contains("MirrlyUI"))
        assertTrue(prompt.contains("apply_svg_button_icon"))
        assertTrue(prompt.contains("100 100"))
        assertTrue(prompt.contains("Stroke"))
    }
}
