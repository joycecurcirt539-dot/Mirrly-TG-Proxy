/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTunManagerTest {

    private fun ipToLong(ip: String): Long {
        val parts = ip.split('.').map { it.toLong() }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    private fun isIpCoveredByCidr(ipStr: String, cidrIp: String, prefixLen: Int): Boolean {
        val ip = ipToLong(ipStr)
        val cidr = ipToLong(cidrIp)
        val mask = if (prefixLen == 0) 0L else ((0xFFFFFFFFL shl (32 - prefixLen)) and 0xFFFFFFFFL)
        return (ip and mask) == (cidr and mask)
    }

    @Test
    fun testIsIpInSubnetBoundaryConditions() {
        // 10.0.0.0/8
        assertTrue(VpnTunManager.isIpInSubnet("10.0.0.1", "10.0.0.0", 8))
        assertTrue(VpnTunManager.isIpInSubnet("10.255.255.255", "10.0.0.0", 8))
        assertFalse(VpnTunManager.isIpInSubnet("9.255.255.255", "10.0.0.0", 8))
        assertFalse(VpnTunManager.isIpInSubnet("11.0.0.0", "10.0.0.0", 8))

        // 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
        assertTrue(VpnTunManager.isIpInSubnet("172.16.0.1", "172.16.0.0", 12))
        assertTrue(VpnTunManager.isIpInSubnet("172.31.255.255", "172.16.0.0", 12))
        assertFalse(VpnTunManager.isIpInSubnet("172.15.255.255", "172.16.0.0", 12))
        assertFalse(VpnTunManager.isIpInSubnet("172.32.0.0", "172.16.0.0", 12))

        // 192.168.0.0/16
        assertTrue(VpnTunManager.isIpInSubnet("192.168.0.1", "192.168.0.0", 16))
        assertTrue(VpnTunManager.isIpInSubnet("192.168.255.255", "192.168.0.0", 16))
        assertFalse(VpnTunManager.isIpInSubnet("192.167.255.255", "192.168.0.0", 16))
        assertFalse(VpnTunManager.isIpInSubnet("192.169.0.0", "192.168.0.0", 16))

        // 127.0.0.0/8 (Loopback)
        assertTrue(VpnTunManager.isIpInSubnet("127.0.0.1", "127.0.0.0", 8))
        assertTrue(VpnTunManager.isIpInSubnet("127.255.255.255", "127.0.0.0", 8))
        assertFalse(VpnTunManager.isIpInSubnet("128.0.0.1", "127.0.0.0", 8))

        // Invalid IP string handling
        assertFalse(VpnTunManager.isIpInSubnet("invalid.ip", "10.0.0.0", 8))
        assertFalse(VpnTunManager.isIpInSubnet("", "10.0.0.0", 8))
    }

    @Test
    fun testCalculateNonLanRoutesExcludesAllPrivateAndReservedRanges() {
        val routes = VpnTunManager.calculateNonLanRoutes(
            exclude10 = true,
            exclude172 = true,
            exclude192 = true
        )

        assertTrue("Generated routes count must be > 0", routes.isNotEmpty())

        val testExcludedIps = listOf(
            "0.0.0.0", "0.255.255.255",         // 0.0.0.0/8
            "10.0.0.1", "10.255.255.254",       // 10.0.0.0/8 (LAN)
            "127.0.0.1", "127.255.255.255",     // 127.0.0.0/8 (Loopback)
            "169.254.1.1", "169.254.254.254",   // 169.254.0.0/16 (Link-Local)
            "172.16.0.1", "172.31.255.254",     // 172.16.0.0/12 (LAN)
            "192.168.1.1", "192.168.254.254",   // 192.168.0.0/16 (LAN)
            "224.0.0.1", "239.255.255.255",     // 224.0.0.0/4 (Multicast)
            "240.0.0.1", "255.255.255.255"      // 240.0.0.0/4 (Reserved / Broadcast)
        )

        for (excludedIp in testExcludedIps) {
            val matchingRoutes = routes.filter { (cidrIp, prefix) ->
                isIpCoveredByCidr(excludedIp, cidrIp, prefix)
            }
            assertTrue(
                "IP $excludedIp must NOT be covered by any non-LAN route, but matched: $matchingRoutes",
                matchingRoutes.isEmpty()
            )
        }

        val testPublicIps = listOf(
            "1.1.1.1",
            "1.0.0.1",
            "8.8.8.8",
            "77.88.8.8",
            "149.154.167.50",
            "162.159.192.1",
            "188.114.96.1"
        )

        for (publicIp in testPublicIps) {
            val matchingRoutes = routes.filter { (cidrIp, prefix) ->
                isIpCoveredByCidr(publicIp, cidrIp, prefix)
            }
            assertEquals(
                "Public IP $publicIp must match exactly one non-LAN route",
                1,
                matchingRoutes.size
            )
        }
    }

    @Test
    fun testCalculateNonLanRoutesSelectiveExclusionPreservesSelectedSubnet() {
        // When exclude10 = false (e.g. tun ip or DNS is in 10.x.x.x)
        val routesInclude10 = VpnTunManager.calculateNonLanRoutes(
            exclude10 = false,
            exclude172 = true,
            exclude192 = true
        )

        // 10.0.0.1 must now be covered
        val matches10 = routesInclude10.filter { (cidrIp, prefix) ->
            isIpCoveredByCidr("10.0.0.1", cidrIp, prefix)
        }
        assertEquals("10.0.0.1 must be covered when exclude10=false", 1, matches10.size)

        // But 172.16.x.x, 192.168.x.x, 127.x.x.x must still be excluded
        assertTrue(
            routesInclude10.none { (cidrIp, prefix) -> isIpCoveredByCidr("172.16.0.1", cidrIp, prefix) }
        )
        assertTrue(
            routesInclude10.none { (cidrIp, prefix) -> isIpCoveredByCidr("192.168.1.1", cidrIp, prefix) }
        )
        assertTrue(
            routesInclude10.none { (cidrIp, prefix) -> isIpCoveredByCidr("127.0.0.1", cidrIp, prefix) }
        )
    }

    @Test
    fun testMtuConstantsAndBoundaries() {
        assertEquals(1280, VpnTunManager.VPN_MTU_SAFE)
        assertEquals(1420, VpnTunManager.VPN_MTU_HIGH_PERF)
        assertEquals(VpnTunManager.VPN_MTU_SAFE, VpnTunManager.VPN_MTU)
    }
}
