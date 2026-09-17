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

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class HappyEyeballsEngineTest {

    @BeforeEach
    fun setUp() {
        HappyEyeballsEngine.clearRating()
    }

    @Test
    fun testPrioritizeAddressesByEwmaRtt() {
        val ip1 = InetAddress.getByName("104.21.1.1")
        val ip2 = InetAddress.getByName("172.67.1.1")
        val ip3 = InetAddress.getByName("104.21.2.2")

        HappyEyeballsEngine.recordIpRtt("104.21.1.1", 150L)
        HappyEyeballsEngine.recordIpRtt("172.67.1.1", 35L)
        HappyEyeballsEngine.recordIpRtt("104.21.2.2", 280L)

        val prioritized = HappyEyeballsEngine.prioritizeAddresses(listOf(ip1, ip2, ip3))
        assertEquals(3, prioritized.size)
        assertEquals("172.67.1.1", prioritized[0].hostAddress)
        assertEquals("104.21.1.1", prioritized[1].hostAddress)
        assertEquals("104.21.2.2", prioritized[2].hostAddress)
    }

    @Test
    fun testRecordIpRttEwmaSmoothing() {
        val ip = "104.21.5.5"
        HappyEyeballsEngine.recordIpRtt(ip, 100L)
        assertEquals(100L, HappyEyeballsEngine.getKnownRatings()[ip])

        // 100 * 0.7 + 200 * 0.3 = 70 + 60 = 130
        HappyEyeballsEngine.recordIpRtt(ip, 200L)
        assertEquals(130L, HappyEyeballsEngine.getKnownRatings()[ip])
    }

    @Test
    fun testClearRating() {
        HappyEyeballsEngine.recordIpRtt("1.1.1.1", 50L)
        assertTrue(HappyEyeballsEngine.getKnownRatings().isNotEmpty())

        HappyEyeballsEngine.clearRating()
        assertTrue(HappyEyeballsEngine.getKnownRatings().isEmpty())
    }

    @Test
    fun testSingleAddressFastPath() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val acceptThread = thread {
            try {
                val client = serverSocket.accept()
                client.close()
            } catch (_: Exception) {}
        }

        try {
            val loopback = InetAddress.getByName("127.0.0.1")
            val result = runBlocking {
                HappyEyeballsEngine.raceConnect(listOf(loopback), port = port, timeoutMs = 1000L)
            }

            assertNotNull(result)
            assertEquals("127.0.0.1", result?.winningAddress?.hostAddress)
            assertTrue((result?.handshakeRttMs ?: -1L) >= 0L)
        } finally {
            serverSocket.close()
            acceptThread.join(500)
        }
    }

    @Test
    fun testStaggeredRacingWithMultipleAddresses() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val acceptThread = thread {
            try {
                val client = serverSocket.accept()
                client.close()
            } catch (_: Exception) {}
        }

        try {
            // Unroutable IP (TEST-NET-1: 192.0.2.1) + reachable local IP
            val unreachableIp = InetAddress.getByName("192.0.2.1")
            val reachableIp = InetAddress.getByName("127.0.0.1")

            val result = runBlocking {
                HappyEyeballsEngine.raceConnect(
                    addresses = listOf(unreachableIp, reachableIp),
                    port = port,
                    attemptDelayMs = 100L,
                    timeoutMs = 1500L
                )
            }

            assertNotNull(result)
            assertEquals("127.0.0.1", result?.winningAddress?.hostAddress)
        } finally {
            serverSocket.close()
            acceptThread.join(500)
        }
    }

    @Test
    fun testAllAddressesUnreachableReturnsNull() {
        val unreachable1 = InetAddress.getByName("192.0.2.1")
        val unreachable2 = InetAddress.getByName("192.0.2.2")

        val result = runBlocking {
            HappyEyeballsEngine.raceConnect(
                addresses = listOf(unreachable1, unreachable2),
                port = 65530,
                attemptDelayMs = 50L,
                timeoutMs = 200L
            )
        }

        assertNull(result)
    }

    @Test
    fun testTcpSuccessDoesNotPromoteToReady() {
        val gen = 1L
        val host = "worker1.pages.dev"
        val ip = "104.21.1.1"

        // TCP stage success recorded
        HappyEyeballsEngine.recordStageSuccess(
            generation = gen,
            hostname = host,
            ip = ip,
            family = AddressFamily.IPV4,
            stage = EstablishmentStage.TCP,
            rttMs = 25L
        )

        // CRITICAL: TCP success alone does NOT promote to Ready!
        assertEquals(false, HappyEyeballsEngine.isRouteReady(gen, host, ip))

        // Full establishment success (READY) recorded
        HappyEyeballsEngine.recordStageSuccess(
            generation = gen,
            hostname = host,
            ip = ip,
            family = AddressFamily.IPV4,
            stage = EstablishmentStage.READY,
            rttMs = 55L
        )

        // NOW route is Ready!
        assertEquals(true, HappyEyeballsEngine.isRouteReady(gen, host, ip))
    }

    @Test
    fun testBrokenIpv6RapidDemotionOnlyForSpecificPath() {
        val gen = 1L
        val hostA = "worker1.pages.dev"
        val hostB = "worker2.pages.dev"
        val ipv6 = InetAddress.getByName("2606:4700::1")
        val ipv4 = InetAddress.getByName("104.21.1.1")

        // Fail IPv6 on host A
        HappyEyeballsEngine.recordStageFailure(
            generation = gen,
            hostname = hostA,
            ip = ipv6.hostAddress ?: "",
            family = AddressFamily.IPV6,
            stage = EstablishmentStage.TLS
        )

        assertEquals(1, HappyEyeballsEngine.getFamilyFailures(gen, hostA, AddressFamily.IPV6))
        assertEquals(0, HappyEyeballsEngine.getFamilyFailures(gen, hostA, AddressFamily.IPV4))

        // Host A rapidly demotes broken IPv6: IPv4 must be first!
        val orderedA = HappyEyeballsEngine.prioritizeAddresses(hostA, listOf(ipv6, ipv4))
        assertEquals(ipv4, orderedA[0])
        assertEquals(ipv6, orderedA[1])

        // Host B is unaffected by failure on Host A!
        assertEquals(0, HappyEyeballsEngine.getFamilyFailures(gen, hostB, AddressFamily.IPV6))
        assertEquals(0, HappyEyeballsEngine.getFamilyFailures(gen, hostB, AddressFamily.IPV4))
    }

    @Test
    fun testNewNetworkGenerationDoesNotInheritBan() {
        val gen1 = 1L
        val gen2 = 2L
        val host = "worker1.pages.dev"
        val ipv6 = InetAddress.getByName("2606:4700::1")
        val ipv4 = InetAddress.getByName("104.21.1.1")

        HappyEyeballsEngine.setNetworkGeneration(gen1)
        HappyEyeballsEngine.recordStageFailure(
            generation = gen1,
            hostname = host,
            ip = ipv6.hostAddress ?: "",
            family = AddressFamily.IPV6,
            stage = EstablishmentStage.TCP
        )

        assertEquals(1, HappyEyeballsEngine.getFamilyFailures(gen1, host, AddressFamily.IPV6))
        val orderedGen1 = HappyEyeballsEngine.prioritizeAddresses(host, listOf(ipv6, ipv4))
        assertEquals(ipv4, orderedGen1[0])
        assertEquals(ipv6, orderedGen1[1])

        // Handover to network generation 2: clean slate, no inherited ban
        HappyEyeballsEngine.setNetworkGeneration(gen2)
        assertEquals(0, HappyEyeballsEngine.getFamilyFailures(gen2, host, AddressFamily.IPV6))
        assertEquals(0, HappyEyeballsEngine.getFamilyFailures(gen2, host, AddressFamily.IPV4))
    }

    @Test
    fun testCompositeScoreKeyDimensionTracking() {
        val key = RouteScoreKey(
            networkGeneration = 3L,
            networkFingerprint = "cell:LTE:25001",
            hostname = "worker1.pages.dev",
            ip = "104.21.1.1",
            family = AddressFamily.IPV4,
            stage = EstablishmentStage.READY
        )

        assertEquals(3L, key.networkGeneration)
        assertEquals("cell:LTE:25001", key.networkFingerprint)
        assertEquals("worker1.pages.dev", key.hostname)
        assertEquals("104.21.1.1", key.ip)
        assertEquals(AddressFamily.IPV4, key.family)
        assertEquals(EstablishmentStage.READY, key.stage)
        assertEquals(true, key.stage.isReady)
        assertEquals(false, EstablishmentStage.TCP.isReady)
    }

    @Test
    fun testIpv6OnlyNetworkPrioritizesAllIpv6First() {
        val ipv6_1 = InetAddress.getByName("2606:4700::1")
        val ipv6_2 = InetAddress.getByName("2606:4700::2")
        val ipv4_1 = InetAddress.getByName("104.21.1.1")
        val ipv4_2 = InetAddress.getByName("172.67.1.1")

        HappyEyeballsEngine.setIpv6OnlyNetwork(true)
        val ordered = HappyEyeballsEngine.prioritizeAddresses(listOf(ipv4_1, ipv6_1, ipv4_2, ipv6_2))

        assertEquals(4, ordered.size)
        // In IPv6-only, all IPv6 come first without IPv4 interleaved
        assertTrue(ordered[0] is java.net.Inet6Address)
        assertTrue(ordered[1] is java.net.Inet6Address)
        assertTrue(ordered[2] is java.net.Inet4Address)
        assertTrue(ordered[3] is java.net.Inet4Address)
    }

    @Test
    fun testRfc8305InterleavingDualStack() {
        val ipv6_1 = InetAddress.getByName("2606:4700::1")
        val ipv6_2 = InetAddress.getByName("2606:4700::2")
        val ipv4_1 = InetAddress.getByName("104.21.1.1")
        val ipv4_2 = InetAddress.getByName("172.67.1.1")

        HappyEyeballsEngine.setIpv6OnlyNetwork(false)
        val ordered = HappyEyeballsEngine.prioritizeAddresses(listOf(ipv4_1, ipv6_1, ipv4_2, ipv6_2))

        assertEquals(4, ordered.size)
        // Dual-stack: IPv6, IPv4, IPv6, IPv4
        assertEquals(ipv6_1, ordered[0])
        assertEquals(ipv4_1, ordered[1])
        assertEquals(ipv6_2, ordered[2])
        assertEquals(ipv4_2, ordered[3])
    }

    @Test
    fun testNat64SynthesisAndExtraction() {
        val originalIpv4 = InetAddress.getByName("149.154.167.51")
        val synthesized = HappyEyeballsEngine.synthesizeNat64(originalIpv4)

        assertNotNull(synthesized)
        assertTrue(synthesized is java.net.Inet6Address)
        assertTrue(HappyEyeballsEngine.isNat64Address(synthesized!!))

        val extracted = HappyEyeballsEngine.extractIpv4FromNat64(synthesized)
        assertNotNull(extracted)
        assertEquals(originalIpv4.hostAddress, extracted?.hostAddress)
    }
}
