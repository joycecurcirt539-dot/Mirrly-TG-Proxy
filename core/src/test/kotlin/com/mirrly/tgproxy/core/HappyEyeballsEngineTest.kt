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
}
