/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui

import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.core.SpeedPreset
import com.mirrly.tgproxy.core.TcpNoDelayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AdvancedSettingsModeTest {

    private fun locateResFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("..", relativePath),
            File("../app", relativePath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Resource file not found: $relativePath in candidates: $candidates")
    }

    private fun parseStringsXml(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val stringNodes = doc.getElementsByTagName("string")
        val result = mutableMapOf<String, String>()

        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i) as Element
            val name = node.getAttribute("name")
            val text = node.textContent
            result[name] = text
        }
        return result
    }

    @Test
    fun testAdvancedModeStringsParityBetweenRuAndEn() {
        val ruFile = locateResFile("src/main/res/values/strings.xml")
        val enFile = locateResFile("src/main/res/values-en/strings.xml")

        val ruStrings = parseStringsXml(ruFile)
        val enStrings = parseStringsXml(enFile)

        val requiredKeys = listOf(
            "settings_advanced_mode_title",
            "settings_advanced_mode_desc",
            "settings_advanced_section_title",
            "settings_happy_eyeballs_title",
            "settings_happy_eyeballs_desc",
            "settings_ip_family_title",
            "settings_ip_family_dual",
            "settings_ip_family_v4",
            "settings_ip_family_v6",
            "settings_warp_anycast_title",
            "settings_warp_anycast_desc",
            "settings_socket_buffer_title",
            "settings_socket_buffer_desc",
            "settings_ws_keepalive_title",
            "settings_ws_keepalive_desc"
        )

        for (key in requiredKeys) {
            assertTrue("RU strings must contain '$key'", ruStrings.containsKey(key))
            assertTrue("EN strings must contain '$key'", enStrings.containsKey(key))

            val ruText = ruStrings[key]?.trim() ?: ""
            val enText = enStrings[key]?.trim() ?: ""

            assertFalse("RU string for '$key' must not be empty", ruText.isEmpty())
            assertFalse("EN string for '$key' must not be empty", enText.isEmpty())
        }
    }

    @Test
    fun testDefaultProxyConfigHasCleanSimpleModeDefaults() {
        val config = ProxyConfig()

        // In simple mode, auto speed preset and auto tcp_nodelay are active by default
        assertEquals(SpeedPreset.AUTO.name, config.speedPresetName)
        assertEquals(SpeedPreset.AUTO, config.speedPreset)
        assertTrue(config.isAutoSpeedPreset)
        assertEquals(TcpNoDelayMode.AUTO.name, config.tcpNoDelayModeName)
        assertEquals(TcpNoDelayMode.AUTO, config.tcpNoDelayMode)
        assertTrue(config.warpUserEndpointOverride.isEmpty())
        assertEquals(262144, config.bufferSizeBytes)
    }

    @Test
    fun testEngineeringParameterModificationDoesNotCorruptOtherSettings() {
        val config = ProxyConfig()
        config.warpUserEndpointOverride = "162.159.192.1:2408"
        config.tcpNoDelayModeName = TcpNoDelayMode.ON.name
        config.applyPreset(SpeedPreset.TURBO)

        assertEquals("162.159.192.1:2408", config.warpUserEndpointOverride)
        assertEquals(TcpNoDelayMode.ON, config.tcpNoDelayMode)
        assertEquals(1048576, config.bufferSizeBytes)
        assertEquals(3, config.mtprotoStandbyPerActiveSlotValue)

        // Changing custom endpoint does not reset preset
        config.warpUserEndpointOverride = ""
        assertEquals(1048576, config.bufferSizeBytes)
        assertEquals(SpeedPreset.TURBO.name, config.speedPresetName)
    }

    @Test
    fun testEngineeringValueBoundsClamping() {
        val delayMin = 20L.coerceIn(50L, 1000L)
        val delayMax = 2500L.coerceIn(50L, 1000L)
        val delayNormal = 200L.coerceIn(50L, 1000L)

        assertEquals(50L, delayMin)
        assertEquals(1000L, delayMax)
        assertEquals(200L, delayNormal)

        val keepAliveMin = 5.coerceIn(10, 300)
        val keepAliveMax = 500.coerceIn(10, 300)
        val keepAliveNormal = 30.coerceIn(10, 300)

        assertEquals(10, keepAliveMin)
        assertEquals(300, keepAliveMax)
        assertEquals(30, keepAliveNormal)
    }
}
