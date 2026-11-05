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
        assertEquals(6, tools.length())

        val toolNames = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertTrue(toolNames.contains("get_service_status"))
        assertTrue(toolNames.contains("get_network_health"))
        assertTrue(toolNames.contains("get_recent_failure_logs"))
        assertTrue(toolNames.contains("test_uplink_connection"))
        assertTrue(toolNames.contains("get_reference_icon_code"))
        assertTrue(toolNames.contains("apply_svg_button_icon"))
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

    @Test
    fun testAiSmartDnsInitialization() {
        val dns = com.mirrly.tgproxy.ai.AiSmartDns.dohDns
        org.junit.Assert.assertNotNull(dns)
    }

    @Test
    fun testAiSmartDnsProvidersConstants() {
        assertEquals("geohide", com.mirrly.tgproxy.ai.AiSmartDns.PROVIDER_GEOHIDE)
        assertEquals("dns_ai", com.mirrly.tgproxy.ai.AiSmartDns.PROVIDER_DNS_AI)
        assertEquals("comss", com.mirrly.tgproxy.ai.AiSmartDns.PROVIDER_COMSS)
        assertEquals("auto", com.mirrly.tgproxy.ai.AiSmartDns.PROVIDER_AUTO)
    }

    @Test
    fun testGeoHideDnsResolution() {
        try {
            val addresses = com.mirrly.tgproxy.ai.AiSmartDns.resolveWithGeoHide("generativelanguage.googleapis.com")
            assertTrue("GeoHide must resolve IP addresses", addresses.isNotEmpty())
            val ipStrings = addresses.map { it.hostAddress }
            println("GeoHide resolved IPs: $ipStrings")
        } catch (e: Exception) {
            println("GeoHide test skipped if offline: ${e.message}")
        }
    }

    @Test
    fun testCloudflareAiPopularModels() {
        val models = com.mirrly.tgproxy.ai.CloudflareAiClient.POPULAR_MODELS
        assertTrue(models.isNotEmpty())
        val ids = models.map { it.id }
        assertTrue(ids.contains("@cf/meta/llama-3.3-70b-instruct"))
        assertTrue(ids.contains("@cf/deepseek-ai/deepseek-r1-distill-qwen-32b"))
        assertTrue(ids.contains("@cf/qwen/qwen2.5-72b-instruct"))
    }

    @Test
    fun testOpenRouterAiPopularModels() {
        val models = com.mirrly.tgproxy.ai.OpenRouterAiClient.POPULAR_MODELS
        assertTrue(models.isNotEmpty())
        val ids = models.map { it.id }
        assertTrue(ids.contains("deepseek/deepseek-r1:free"))
        assertTrue(ids.contains("anthropic/claude-3.5-sonnet"))
        assertTrue(ids.contains("openai/gpt-4o"))
        assertTrue(ids.contains("meta-llama/llama-3.3-70b-instruct:free"))
    }

    @Test
    fun testAiPromptsConstants() {
        assertEquals("DIAGNOSTIC", com.mirrly.tgproxy.ai.AiPrompts.MODE_DIAGNOSTIC)
        assertEquals("FUN", com.mirrly.tgproxy.ai.AiPrompts.MODE_FUN)
        assertEquals("CUSTOM", com.mirrly.tgproxy.ai.AiPrompts.MODE_CUSTOM)
        assertEquals("NONE", com.mirrly.tgproxy.ai.AiPrompts.MODE_NONE)
        assertTrue(com.mirrly.tgproxy.ai.AiPrompts.PROMPT_DIAGNOSTIC.contains("get_service_status"))
        assertTrue(com.mirrly.tgproxy.ai.AiPrompts.PROMPT_DIAGNOSTIC.contains("apply_svg_button_icon"))
    }

    @Test
    fun testGeminiApiClientPopularModels() {
        val models = com.mirrly.tgproxy.ai.GeminiApiClient.POPULAR_MODELS
        assertTrue(models.isNotEmpty())
        val ids = models.map { it.id }
        assertTrue(ids.contains("gemini-2.5-flash"))
        assertTrue(ids.contains("gemini-2.5-pro"))
        assertTrue(ids.contains("gemini-2.0-flash"))
    }

    @Test
    fun testAiModelFormatter() {
        // Cloudflare
        assertEquals("Llama 3.3 70B", com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName("@cf/meta/llama-3.3-70b-instruct"))
        assertEquals("DeepSeek R1 32B", com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName("@cf/deepseek-ai/deepseek-r1-distill-qwen-32b"))
        assertEquals("Llama 3.1 8B", com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName("@cf/meta/llama-3.1-8b-instruct"))
        assertEquals("Qwen 2.5 72B", com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName("@cf/qwen/qwen2.5-72b-instruct"))
        assertEquals("Mistral 7B", com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName("@cf/mistral/mistral-7b-instruct-v0.2"))

        // OpenRouter
        assertEquals("DeepSeek R1", com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName("deepseek/deepseek-r1"))
        assertEquals("DeepSeek R1", com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName("deepseek/deepseek-r1:free"))
        assertEquals("DeepSeek V3", com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName("deepseek/deepseek-chat"))
        assertEquals("Claude 3.5 Sonnet", com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName("anthropic/claude-3.5-sonnet"))
        assertEquals("GPT-4o", com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName("openai/gpt-4o"))

        // Gemini
        assertEquals("Gemini 3.6 Flash", com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName("gemini-3.6-flash"))
        assertEquals("Gemini 2.5 Flash", com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName("gemini-2.5-flash"))
        assertEquals("Gemini 3.5 Lite", com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName("gemini-3.5-flash-lite"))
    }
}
