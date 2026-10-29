/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.service

import com.mirrly.tgproxy.ai.AiChatMessage
import com.mirrly.tgproxy.ai.AiDiagnosticTools
import com.mirrly.tgproxy.ai.AiSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChatModelTest {

    @Test
    fun testAiSanitizerWorkersDevMasking() {
        val input = "Connected to my-personal-tg.workers.dev on endpoint /ws"
        val sanitized = AiSanitizer.sanitize(input)
        assertEquals("Connected to ***.workers.dev on endpoint /ws", sanitized)
    }

    @Test
    fun testAiSanitizerCustomDomainMasking() {
        val input = "Traffic routed via proxy.myfamily.com:443"
        val sanitized = AiSanitizer.sanitize(input, setOf("proxy.myfamily.com"))
        assertTrue(sanitized.contains("p***y.com") || sanitized.contains("[CUSTOM_DOMAIN]"))
        assertFalse(sanitized.contains("proxy.myfamily.com"))
    }

    @Test
    fun testAiSanitizerCredentialsMasking() {
        val textWithApiKey = "Google API Key is AIzaSyD3x9kL4mN2pQ8rS1tU7vW5xY0zAbCdEfG"
        val textWithTgToken = "Telegram bot token is 123456789:ABCdefGHIjklMNOpqrSTUvwxYZ_01234567"
        val textWithWgKey = "Private key is aGVsbG93b3JsZDEyMzQ1Njc4OWFiY2RlZmdoaWprbA=="
        val textWithBearer = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.1234567890abcdef"

        assertEquals("Google API Key is [REDACTED_CREDENTIAL]", AiSanitizer.sanitize(textWithApiKey))
        assertEquals("Telegram bot token is [REDACTED_CREDENTIAL]", AiSanitizer.sanitize(textWithTgToken))
        assertEquals("Private key is [REDACTED_CREDENTIAL]", AiSanitizer.sanitize(textWithWgKey))
        assertEquals("Authorization: Bearer [REDACTED_CREDENTIAL]", AiSanitizer.sanitize(textWithBearer))
    }

    @Test
    fun testAiSanitizerIpAddresses() {
        // Loopback 127.0.0.1 and ports 1080, 10808 must be preserved
        val localText = "MTProto listening on 127.0.0.1:1080 and SOCKS5 on 127.0.0.1:10808"
        assertEquals("MTProto listening on 127.0.0.1:1080 and SOCKS5 on 127.0.0.1:10808", AiSanitizer.sanitize(localText))

        // Public IPs must be masked
        val publicIpText = "Connecting to DC4 at 149.154.167.91:443 and CDN 104.16.249.5"
        val sanitizedIps = AiSanitizer.sanitize(publicIpText)
        assertEquals("Connecting to DC4 at xxx.xxx.xxx.xxx:443 and CDN xxx.xxx.xxx.xxx", sanitizedIps)

        // IPv6 must be masked
        val ipv6Text = "Uplink routed via 2606:4700:4700::1111:53"
        val sanitizedIpv6 = AiSanitizer.sanitize(ipv6Text)
        assertTrue(sanitizedIpv6.contains("xxxx::xxxx"))
        assertFalse(sanitizedIpv6.contains("2606:4700"))
    }

    @Test
    fun testAiDiagnosticToolsDeclarations() {
        val tools = AiDiagnosticTools.getToolDeclarations()
        assertEquals(4, tools.length())

        val toolNames = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertTrue(toolNames.contains("get_service_status"))
        assertTrue(toolNames.contains("get_network_health"))
        assertTrue(toolNames.contains("get_recent_failure_logs"))
        assertTrue(toolNames.contains("test_uplink_connection"))
    }

    @Test
    fun testAiChatMessageModel() {
        val message = AiChatMessage(
            id = "test-123",
            role = "model",
            text = "Прокси работает стабильно.",
            thought = "Выполнен запрос get_service_status()",
            toolName = "get_service_status"
        )

        assertEquals("test-123", message.id)
        assertTrue(message.isModel)
        assertFalse(message.isUser)
        assertEquals("Прокси работает стабильно.", message.text)
        assertEquals("Выполнен запрос get_service_status()", message.thought)
        assertEquals("get_service_status", message.toolName)
    }
}
