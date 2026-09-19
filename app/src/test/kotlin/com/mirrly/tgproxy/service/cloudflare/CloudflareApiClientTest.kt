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

package com.mirrly.tgproxy.service.cloudflare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareApiClientTest {

    @Test
    fun testIsAuthError() {
        assertTrue(CloudflareApiClient.isAuthError(CloudflareAuthException("Auth failed")))
        assertTrue(CloudflareApiClient.isAuthError("Токен истек"))
        assertTrue(CloudflareApiClient.isAuthError("Требуется авторизация"))
        assertTrue(CloudflareApiClient.isAuthError("Authentication error: 10000"))
        assertTrue(CloudflareApiClient.isAuthError("HTTP 401 Unauthorized"))
        assertFalse(CloudflareApiClient.isAuthError("Connection refused"))
    }

    @Test
    fun testParseCloudflareError() {
        val authJson = """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}"""
        val authMsg = CloudflareApiClient.parseCloudflareError(authJson, 401)
        assertTrue(authMsg.contains("Сессия Cloudflare истекла"))

        val rateLimitJson = """{"success":false,"errors":[{"code":10026,"message":"daily request limit reached"}]}"""
        val rateLimitMsg = CloudflareApiClient.parseCloudflareError(rateLimitJson, 429)
        assertTrue(rateLimitMsg.contains("Исчерпан суточный лимит"))

        val existsJson = """{"success":false,"errors":[{"code":10013,"message":"worker already exists"}]}"""
        val existsMsg = CloudflareApiClient.parseCloudflareError(existsJson, 400)
        assertTrue(existsMsg.contains("уже существует"))
    }
}
