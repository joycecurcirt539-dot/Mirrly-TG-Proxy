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

package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TgConstantsTest {

    @Test
    fun testFindDcByTargetIpv4() {
        assertEquals(Pair(1, false), TgConstants.findDcByTarget("149.154.175.50"))
        assertEquals(Pair(1, true), TgConstants.findDcByTarget("149.154.175.51"))
        assertEquals(Pair(2, false), TgConstants.findDcByTarget("149.154.167.51"))
        assertEquals(Pair(2, true), TgConstants.findDcByTarget("149.154.167.52"))
        assertEquals(Pair(4, false), TgConstants.findDcByTarget("149.154.167.91"))
        assertEquals(Pair(5, false), TgConstants.findDcByTarget("91.108.56.130"))
    }

    @Test
    fun testFindDcByTargetIpv6() {
        assertEquals(Pair(1, false), TgConstants.findDcByTarget("2001:b28:f23d:f001::a"))
        assertEquals(Pair(1, true), TgConstants.findDcByTarget("2001:b28:f23d:f001::b"))
        assertEquals(Pair(2, false), TgConstants.findDcByTarget("2001:67c:4e8:f002::a"))
        assertEquals(Pair(2, true), TgConstants.findDcByTarget("2001:67c:4e8:f002::b"))
        assertEquals(Pair(3, false), TgConstants.findDcByTarget("2001:b28:f23d:f003::a"))
        assertEquals(Pair(4, false), TgConstants.findDcByTarget("2001:67c:4e8:f004::a"))
        assertEquals(Pair(5, false), TgConstants.findDcByTarget("2001:b28:f23f:f005::a"))
        assertEquals(Pair(5, true), TgConstants.findDcByTarget("2001:b28:f23f:f005::b"))
    }

    @Test
    fun testFindDcByTargetNat64Synthesized() {
        // DC2 IPv4: 149.154.167.51 -> synthesized NAT64 address
        val nat64Dc2 = TgConstants.synthesizeNat64("149.154.167.51")
        assertNotNull(nat64Dc2)
        assertEquals(Pair(2, false), TgConstants.findDcByTarget(nat64Dc2!!))

        // DC1 Media IPv4: 149.154.175.51 -> synthesized NAT64 address
        val nat64Dc1Media = TgConstants.synthesizeNat64("149.154.175.51")
        assertNotNull(nat64Dc1Media)
        assertEquals(Pair(1, true), TgConstants.findDcByTarget(nat64Dc1Media!!))
    }

    @Test
    fun testFindDcIpv6Helper() {
        assertEquals("2001:b28:f23d:f001::a", TgConstants.findDcIpv6(1, isMedia = false))
        assertEquals("2001:b28:f23d:f001::b", TgConstants.findDcIpv6(1, isMedia = true))
        assertEquals("2001:67c:4e8:f002::a", TgConstants.findDcIpv6(2, isMedia = false))
        assertEquals("2001:67c:4e8:f002::b", TgConstants.findDcIpv6(2, isMedia = true))
        assertEquals("2001:b28:f23f:f005::a", TgConstants.findDcIpv6(5, isMedia = false))
        assertEquals("2001:b28:f23f:f005::b", TgConstants.findDcIpv6(5, isMedia = true))
    }

    @Test
    fun testSynthesizeAndExtractNat64() {
        val ipv4 = "192.0.2.33"
        val synthesized = TgConstants.synthesizeNat64(ipv4)
        assertNotNull(synthesized)
        assertTrue(synthesized!!.startsWith("64:ff9b::"))
        assertTrue(TgConstants.isNat64Address(synthesized))

        val extracted = TgConstants.extractIpv4FromNat64(synthesized)
        assertEquals(ipv4, extracted)
    }

    @Test
    fun testSynthesizeNat64InvalidInputs() {
        assertNull(TgConstants.synthesizeNat64("invalid-ip"))
        assertNull(TgConstants.synthesizeNat64("256.1.1.1"))
        assertNull(TgConstants.synthesizeNat64("1.2.3"))
        assertNull(TgConstants.synthesizeNat64(""))
    }

    @Test
    fun testIsNat64Address() {
        assertTrue(TgConstants.isNat64Address("64:ff9b::192.0.2.1"))
        assertTrue(TgConstants.isNat64Address("64:ff9b::c000:0221"))
        assertFalse(TgConstants.isNat64Address("2001:db8::1"))
        assertFalse(TgConstants.isNat64Address("192.168.1.1"))
    }
}
