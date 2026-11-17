/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BuiltinVpnServersTest {

    @Test
    fun testAllFourteenBuiltinServersExistAndParseSuccessfully() {
        val all = BuiltinVpnServers.ALL
        assertEquals(14, all.size, "Must have exactly 14 builtin servers")

        for (server in all) {
            assertFalse(server.id.isBlank(), "Server ID must not be blank")
            assertFalse(server.name.isBlank(), "Server name must not be blank")
            assertFalse(server.endpoint.isBlank(), "Server endpoint must not be blank: ${server.name}")
            assertFalse(server.countryCode.isBlank(), "Country code must not be blank: ${server.name}")

            val parsed = server.parseProfile()
            assertNotNull(parsed, "Profile parsing must succeed for ${server.name}")
            val isUsable: Boolean = parsed!!.isUsable()
            assertTrue(isUsable, "Parsed profile must be usable for ${server.name}")
            assertFalse(parsed.privateKeyBase64.isBlank(), "PrivateKey must be set for ${server.name}")
            assertFalse(parsed.peerPublicKey.isBlank(), "PeerPublicKey must be set for ${server.name}")
            assertEquals(server.endpoint, parsed.peerEndpoint, "Peer endpoint must match for ${server.name}")
            val hasAwg: Boolean = parsed.isWgValid || parsed.awgI1.isNotBlank() || parsed.awgRawConfig.isNotBlank()
            assertTrue(hasAwg, "Must have valid AWG data for ${server.name}")
        }
    }

    @Test
    fun testTelServersClassification() {
        val telServers = BuiltinVpnServers.ALL.filter { it.isTel }
        assertEquals(3, telServers.size, "Must have exactly 3 TEL servers")
        assertTrue(telServers.all { it.name.startsWith("TEL") })
        assertTrue(telServers.all { it.speedLimitText == "Лимит 25 Мбит/с" })
    }

    @Test
    fun testAnycastServersClassification() {
        val anycastServers = BuiltinVpnServers.ALL.filter { it.isAnycast }
        assertEquals(2, anycastServers.size, "Must have exactly 2 Anycast servers")
        assertTrue(anycastServers.any { it.id == "builtin_default" })
        assertTrue(anycastServers.any { it.id == "builtin_alternative" })
    }

    @Test
    fun testGetByIdFallback() {
        val fallback = BuiltinVpnServers.getById("non_existent_id")
        assertEquals(BuiltinVpnServers.SERVER_BUILTIN_DEFAULT, fallback)
    }
}
