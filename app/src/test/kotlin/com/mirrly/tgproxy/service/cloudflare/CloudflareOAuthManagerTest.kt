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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareOAuthManagerTest {

    @Test
    fun testBuildAuthorizationData() {
        val (authUrl, verifier, state) = CloudflareOAuthManager.buildAuthorizationData()

        assertNotNull(authUrl)
        assertNotNull(verifier)
        assertNotNull(state)

        assertTrue("Verifier should not be empty", verifier.isNotBlank())
        assertTrue("State should not be empty", state.isNotBlank())
        assertTrue("Auth URL should contain client_id", authUrl.contains(CloudflareOAuthManager.WRANGLER_CLIENT_ID))
        assertTrue("Auth URL should contain redirect_uri", authUrl.contains("http%3A%2F%2Flocalhost%3A8976%2Foauth%2Fcallback") || authUrl.contains("http://localhost:8976/oauth/callback"))
        assertTrue("Auth URL should contain state", authUrl.contains(state))
        assertTrue("Auth URL should contain response_type=code", authUrl.contains("response_type=code"))
    }

    @Test
    fun testInitialStateAndCancel() {
        CloudflareOAuthManager.cancelActiveListener()
        assertEquals(ServerAuthState.Idle, CloudflareOAuthManager.authState.value)
    }

    @Test
    fun testConstants() {
        assertEquals("54d11594-84e4-41aa-b438-e81b8fa78ee7", CloudflareOAuthManager.WRANGLER_CLIENT_ID)
        assertEquals(8976, CloudflareOAuthManager.CALLBACK_PORT)
        assertEquals("http://localhost:8976/oauth/callback", CloudflareOAuthManager.REDIRECT_URI)
    }

    @Test
    fun testWaitingCallbackState() {
        val portalUrl = "http://localhost:8976/"
        val authUrl = "https://dash.cloudflare.com/oauth2/auth"
        val state = ServerAuthState.WaitingCallback(portalUrl, authUrl)
        assertEquals(portalUrl, state.portalUrl)
        assertEquals(authUrl, state.authUrl)
    }
}
