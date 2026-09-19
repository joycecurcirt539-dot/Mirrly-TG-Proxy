/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class VpnDnsCacheTest {

    @Test
    fun testCachePutAndGetWithGeneration() {
        val cache = VpnDnsCache()
        val addr1 = InetAddress.getByName("1.1.1.1")
        val addr2 = InetAddress.getByName("1.0.0.1")

        cache.put(
            type = 1, // Type A
            domain = "example.com",
            addresses = listOf(addr1, addr2),
            ttlSeconds = 60L,
            currentNetworkGeneration = 1L
        )

        // Matching generation -> hit
        val cached = cache.get(1, "example.com", currentNetworkGeneration = 1L)
        assertNotNull(cached)
        assertEquals(2, cached!!.addresses.size)

        // Mismatched generation (e.g. Wi-Fi -> LTE migration) -> invalidated (null)
        val invalidated = cache.get(1, "example.com", currentNetworkGeneration = 2L)
        assertNull(invalidated)
    }

    @Test
    fun testCacheCaseInsensitiveAndTrimTrailingDot() {
        val cache = VpnDnsCache()
        val addr = InetAddress.getByName("93.184.216.34")

        cache.put(
            type = 1,
            domain = "ExAmPlE.CoM.",
            addresses = listOf(addr),
            ttlSeconds = 60L,
            currentNetworkGeneration = 5L
        )

        val cached = cache.get(1, "example.com", currentNetworkGeneration = 5L)
        assertNotNull(cached)
        assertEquals(addr, cached!!.addresses.first())
    }

    @Test
    fun testNxDomainEntry() {
        val cache = VpnDnsCache()
        cache.put(
            type = 1,
            domain = "nonexistent.domain.test",
            addresses = emptyList(),
            ttlSeconds = 10L,
            currentNetworkGeneration = 1L,
            isNxDomain = true
        )

        val entry = cache.get(1, "nonexistent.domain.test", 1L)
        assertNotNull(entry)
        assertTrue(entry!!.isNxDomain)
        assertTrue(entry.addresses.isEmpty())
    }

    @Test
    fun testClear() {
        val cache = VpnDnsCache()
        cache.put(1, "test.com", listOf(InetAddress.getByName("127.0.0.1")), 60L, 1L)
        assertEquals(1, cache.size)
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get(1, "test.com", 1L))
    }
}
