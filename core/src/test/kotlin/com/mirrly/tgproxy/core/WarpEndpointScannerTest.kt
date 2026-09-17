package com.mirrly.tgproxy.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class WarpEndpointScannerTest {

    @AfterEach
    fun tearDown() {
        WarpEndpointScanner.clearStickyProfile()
    }

    @Test
    fun testParseEndpointHandlesIPv4AndIPv6() {
        val (ip4, port4) = WarpEndpointScanner.parseEndpoint("188.114.96.1:8095")
        assertEquals("188.114.96.1", ip4)
        assertEquals(8095, port4)

        val (ip6, port6) = WarpEndpointScanner.parseEndpoint("[2606:4700:d0::a29f:c001]:8443")
        assertEquals("2606:4700:d0::a29f:c001", ip6)
        assertEquals(8443, port6)

        val (defIp, defPort) = WarpEndpointScanner.parseEndpoint("162.159.192.1")
        assertEquals("162.159.192.1", defIp)
        assertEquals(500, defPort, "Default port must be 500 when omitted")
    }

    @Test
    fun testGetDefaultCandidatesHasPrioritizedCleanPorts() {
        val candidates = WarpEndpointScanner.getDefaultCandidates()
        assertTrue(candidates.isNotEmpty(), "Candidates list must not be empty")
        assertTrue(candidates.any { it.endsWith(":8095") }, "Must include clean port 8095")
        assertTrue(candidates.any { it.endsWith(":8443") }, "Must include clean port 8443")
        assertTrue(candidates.any { it.endsWith(":500") }, "Must include standard port 500")
        assertTrue(candidates.any { it.startsWith("188.114.96.") }, "Must include 188.114.96.x range")
    }

    @Test
    fun testPreconfiguredProfiles50ReturnsExactLimit() {
        val profiles = WarpEndpointScanner.getPreconfiguredProfiles50(includeIpv6 = true)
        assertEquals(50, profiles.size, "Must return exactly 50 unique profiles")
        val unique = profiles.toSet()
        assertEquals(50, unique.size, "All 50 profiles must be distinct")
    }

    @Test
    fun testCandidateComparisonSortsByAliveAndLatency() {
        val fast = WarpEndpointCandidate("1.1.1.1", 500, rttMs = 35, isAlive = true)
        val slow = WarpEndpointCandidate("1.1.1.2", 500, rttMs = 120, isAlive = true)
        val dead = WarpEndpointCandidate("1.1.1.3", 500, rttMs = -1, isAlive = false)

        val list = listOf(dead, slow, fast).sorted()
        assertEquals(fast, list[0], "Fastest alive candidate must be first")
        assertEquals(slow, list[1], "Slower alive candidate must be second")
        assertEquals(dead, list[2], "Dead candidate must be last")
    }

    @Test
    fun testStickyProfileLifecycle() {
        assertNull(WarpEndpointScanner.getStickyProfile(), "Initial sticky profile must be null")

        val candidate = WarpEndpointCandidate(
            ip = "188.114.96.1",
            port = 8095,
            endpoint = "188.114.96.1:8095",
            rttMs = 45,
            isAlive = true,
            scanProtocol = WarpProbeProtocol.MASQUE_QUIC
        )
        WarpEndpointScanner.setStickyProfile(candidate)
        assertEquals(candidate, WarpEndpointScanner.getStickyProfile())

        WarpEndpointScanner.clearStickyProfile()
        assertNull(WarpEndpointScanner.getStickyProfile())
    }

    @Test
    fun testPreconfiguredProfiles50ConstMatchesGetter() {
        val profiles = WarpEndpointScanner.PRECONFIGURED_PROFILES_50
        assertEquals(50, profiles.size, "Must return exactly 50 unique profiles")
        val unique = profiles.toSet()
        assertEquals(50, unique.size, "All 50 profiles must be distinct")
        assertTrue(profiles.any { it.endsWith(":8095") })
        assertTrue(profiles.any { it.endsWith(":8443") })
        assertTrue(profiles.any { it.endsWith(":500") })
        assertTrue(profiles.any { it.endsWith(":1701") })
    }

    @Test
    fun testStickyProfileInvalidationAfter3ConsecutiveTimeouts() {
        WarpEndpointScanner.clearStickyProfile()
        assertEquals(0, WarpEndpointScanner.getConsecutiveTimeouts())

        val candidate = WarpEndpointCandidate(
            ip = "188.114.96.1",
            port = 8095,
            endpoint = "188.114.96.1:8095",
            rttMs = 45,
            isAlive = true,
            scanProtocol = WarpProbeProtocol.MASQUE_QUIC
        )
        WarpEndpointScanner.setStickyProfile(candidate)
        assertEquals(candidate, WarpEndpointScanner.getStickyProfile())
        assertEquals(0, WarpEndpointScanner.getConsecutiveTimeouts())

        // Timeout 1: Not invalidated yet, sticky profile retained
        val inv1 = WarpEndpointScanner.recordStickyTimeout()
        assertFalse(inv1, "Timeout 1 must not invalidate sticky profile")
        assertEquals(1, WarpEndpointScanner.getConsecutiveTimeouts())
        assertEquals(candidate, WarpEndpointScanner.getStickyProfile())

        // Timeout 2: Not invalidated yet, sticky profile retained
        val inv2 = WarpEndpointScanner.recordStickyTimeout()
        assertFalse(inv2, "Timeout 2 must not invalidate sticky profile")
        assertEquals(2, WarpEndpointScanner.getConsecutiveTimeouts())
        assertEquals(candidate, WarpEndpointScanner.getStickyProfile())

        // Timeout 3: Invalidated after 3 consecutive timeouts!
        val inv3 = WarpEndpointScanner.recordStickyTimeout()
        assertTrue(inv3, "Timeout 3 must invalidate sticky profile")
        assertEquals(0, WarpEndpointScanner.getConsecutiveTimeouts(), "Counter must be reset after invalidation")
        assertNull(WarpEndpointScanner.getStickyProfile(), "Sticky profile must be null after 3 consecutive timeouts")

        // Setting a new profile resets counter
        WarpEndpointScanner.setStickyProfile(candidate)
        assertEquals(0, WarpEndpointScanner.getConsecutiveTimeouts())
        WarpEndpointScanner.recordStickyTimeout()
        assertEquals(1, WarpEndpointScanner.getConsecutiveTimeouts())
        WarpEndpointScanner.recordStickySuccess()
        assertEquals(0, WarpEndpointScanner.getConsecutiveTimeouts(), "Success must reset consecutive timeouts")
    }

    @Test
    fun testFastProbeParallelFindsFastCandidate() = runBlocking {
        val mockSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val mockPort = mockSocket.localPort

        val serverThread = thread {
            try {
                val recvBuf = ByteArray(2048)
                val packet = DatagramPacket(recvBuf, recvBuf.size)
                mockSocket.receive(packet)

                // Reply with 40-byte QUIC response immediately
                val resp = ByteArray(40)
                resp[0] = 0xC0.toByte()
                val replyPacket = DatagramPacket(resp, resp.size, packet.address, packet.port)
                mockSocket.send(replyPacket)
            } catch (_: Exception) {}
        }

        try {
            val list = listOf("127.0.0.1:$mockPort", "192.0.2.1:8095")
            val results = WarpEndpointScanner.fastProbeParallel(
                endpoints = list,
                timeoutMs = 400L,
                maxConcurrency = 2,
                useFragmentation = false,
                protocol = WarpProbeProtocol.MASQUE_QUIC
            )

            assertTrue(results.isNotEmpty(), "Fast probe must return alive candidates")
            assertEquals("127.0.0.1:$mockPort", results[0].endpoint)
            assertTrue(results[0].rttMs in 0..400)
        } finally {
            mockSocket.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun testMockUdpQuicInitialProbeFallbackToWireGuard() = runBlocking {
        val mockSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val mockPort = mockSocket.localPort

        val serverThread = thread {
            try {
                val recvBuf = ByteArray(2048)
                val packet = DatagramPacket(recvBuf, recvBuf.size)
                mockSocket.receive(packet)

                // WireGuard initiation packet sent for WARP endpoints (148 bytes)
                assertEquals(148, packet.length, "WARP initiation probe must be 148 bytes")

                // Reply with 92-byte Handshake Response
                val resp = ByteArray(92)
                resp[0] = 0x02 // Handshake Response message type

                val replyPacket = DatagramPacket(resp, resp.size, packet.address, packet.port)
                mockSocket.send(replyPacket)
            } catch (_: Exception) {}
        }

        try {
            val res = WarpEndpointScanner.probeEndpoint(
                endpoint = "127.0.0.1:$mockPort",
                timeoutMs = 1500,
                useFragmentation = false,
                protocol = WarpProbeProtocol.MASQUE_QUIC
            )

            assertTrue(res.isAlive, "Endpoint should be detected as alive")
            assertTrue(res.rttMs >= 0, "RTT should be positive")
            assertEquals(WarpProbeProtocol.MASQUE_QUIC, res.scanProtocol)
            assertEquals("WireGuard (Handshake Response)", res.probeProtocol)
        } finally {
            mockSocket.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun testMockUdpWireguardProbeDetection() = runBlocking {
        val mockSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val mockPort = mockSocket.localPort

        val serverThread = thread {
            try {
                val recvBuf = ByteArray(2048)
                val packet = DatagramPacket(recvBuf, recvBuf.size)
                mockSocket.receive(packet)

                // WireGuard handshake initiation is 148 bytes
                assertEquals(148, packet.length, "WireGuard Initiation probe must be 148 bytes")

                // Reply with 92-byte Handshake Response
                val resp = ByteArray(92)
                resp[0] = 0x02 // Handshake Response message type

                val replyPacket = DatagramPacket(resp, resp.size, packet.address, packet.port)
                mockSocket.send(replyPacket)
            } catch (_: Exception) {}
        }

        try {
            val res = WarpEndpointScanner.probeEndpoint(
                endpoint = "127.0.0.1:$mockPort",
                timeoutMs = 1500,
                useFragmentation = false,
                protocol = WarpProbeProtocol.WIREGUARD
            )

            assertTrue(res.isAlive, "Endpoint should be detected as alive")
            assertEquals(WarpProbeProtocol.WIREGUARD, res.scanProtocol)
            assertEquals("WireGuard (Handshake Response)", res.probeProtocol)
            assertEquals(92, res.responseBytes)
        } finally {
            mockSocket.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun testVerifyThroughLocalSocksWithMockServer() {
        val mockSocksServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val socksPort = mockSocksServer.localPort

        val serverThread = thread {
            var client: Socket? = null
            try {
                client = mockSocksServer.accept()
                val inp = client.getInputStream()
                val out = client.getOutputStream()

                // 1. Read SOCKS5 greeting: 0x05, 0x01, 0x00
                val greeting = ByteArray(3)
                inp.read(greeting)
                assertEquals(0x05.toByte(), greeting[0])
                // Reply: 0x05 (version), 0x00 (no auth required)
                out.write(byteArrayOf(0x05, 0x00))
                out.flush()

                // 2. Read CONNECT request
                val req = ByteArray(10)
                val n = inp.read(req)
                assertTrue(n >= 7)
                assertEquals(0x05.toByte(), req[0]) // VER
                assertEquals(0x01.toByte(), req[1]) // CMD CONNECT
                assertEquals(0x00.toByte(), req[2]) // RSV
                assertEquals(0x01.toByte(), req[3]) // ATYP IPv4 (149.154.167.50)

                // Reply: 0x05, 0x00 (Success), 0x00, 0x01, bind IP (127.0.0.1), bind port (10808)
                val successReply = byteArrayOf(
                    0x05, 0x00, 0x00, 0x01,
                    127, 0, 0, 1,
                    (10808 shr 8).toByte(), (10808 and 0xFF).toByte()
                )
                out.write(successReply)
                out.flush()
            } catch (_: Exception) {
            } finally {
                client?.close()
            }
        }

        try {
            val metric = WarpEndpointScanner.verifyThroughLocalSocks(
                socksPort = socksPort,
                targetIp = "149.154.167.50",
                targetPort = 443,
                timeoutMs = 2000
            )
            assertEquals(NodeProbeStatus.AVAILABLE, metric.status)
            assertTrue(metric.latencyMs != null && metric.latencyMs!! >= 0)
        } finally {
            mockSocksServer.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun testFindBestEndpointReusesStickyProfile() = runBlocking {
        val mockSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val mockPort = mockSocket.localPort

        val serverThread = thread {
            try {
                val recvBuf = ByteArray(2048)
                val packet = DatagramPacket(recvBuf, recvBuf.size)
                mockSocket.receive(packet)

                // Reply with a 40-byte QUIC Initial response (QUIC v1, pktType 0x00)
                val resp = ByteArray(40)
                resp[0] = 0xC0.toByte()
                resp[1] = 0; resp[2] = 0; resp[3] = 0; resp[4] = 1

                val replyPacket = DatagramPacket(resp, resp.size, packet.address, packet.port)
                mockSocket.send(replyPacket)
            } catch (_: Exception) {}
        }

        try {
            val sticky = WarpEndpointCandidate(
                ip = "127.0.0.1",
                port = mockPort,
                endpoint = "127.0.0.1:$mockPort",
                rttMs = 30,
                isAlive = true,
                scanProtocol = WarpProbeProtocol.MASQUE_QUIC,
                stage = WarpEndpointStage.PROTOCOL_RECOGNIZED
            )
            WarpEndpointScanner.setStickyProfile(sticky)

            val best = WarpEndpointScanner.findBestEndpoint(
                useFragmentation = false,
                protocol = WarpProbeProtocol.MASQUE_QUIC,
                preferSticky = true
            )

            assertNotNull(best)
            assertEquals("127.0.0.1:$mockPort", best?.endpoint)
            assertTrue(best!!.isAlive)
            assertTrue(best.isProtocolRecognized)
        } finally {
            mockSocket.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun testQuicVersionNegotiationAndEchoNeverBecomeWorkingWarpOrSticky() = runBlocking {
        val mockSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val mockPort = mockSocket.localPort

        val serverThread = thread {
            try {
                val recvBuf = ByteArray(2048)
                val packet = DatagramPacket(recvBuf, recvBuf.size)
                mockSocket.receive(packet)

                // Reply with QUIC Version Negotiation (bytes 1..4 = 0x00000000)
                val resp = ByteArray(40)
                resp[0] = 0xC0.toByte()
                resp[1] = 0; resp[2] = 0; resp[3] = 0; resp[4] = 0

                val replyPacket = DatagramPacket(resp, resp.size, packet.address, packet.port)
                mockSocket.send(replyPacket)
            } catch (_: Exception) {}
        }

        try {
            WarpEndpointScanner.clearStickyProfile()
            val res = WarpEndpointScanner.probeEndpoint(
                endpoint = "127.0.0.1:$mockPort",
                timeoutMs = 1000,
                useFragmentation = false,
                protocol = WarpProbeProtocol.MASQUE_QUIC
            )

            assertTrue(res.isAlive)
            assertTrue(res.isUdpResponsive)
            assertEquals(WarpEndpointStage.UDP_RESPONSIVE, res.stage)
            assertFalse(res.isProtocolRecognized, "QUIC Version Negotiation must NOT be recognized as working protocol")
            assertFalse(res.isWorkingWarp(), "QUIC Version Negotiation must NOT be treated as working WARP")
            assertFalse(res.isSuitableForSticky(WarpEndpointStage.PROTOCOL_RECOGNIZED))

            // findBestEndpoint must reject Version Negotiation candidate from becoming sticky
            val best = WarpEndpointScanner.findBestEndpoint(
                useFragmentation = false,
                maxCandidatesToProbe = 1,
                timeoutMs = 500,
                protocol = WarpProbeProtocol.MASQUE_QUIC,
                preferSticky = false,
                minStage = WarpEndpointStage.PROTOCOL_RECOGNIZED
            )
            assertNull(best, "findBestEndpoint must not return a candidate that only replied with Version Negotiation")
            assertNull(WarpEndpointScanner.getStickyProfile(), "Sticky profile must not be set from Version Negotiation candidate")
        } finally {
            mockSocket.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun testWireGuardEchoNeverBecomesWorkingWarp() = runBlocking {
        val mockSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val mockPort = mockSocket.localPort

        val serverThread = thread {
            try {
                val recvBuf = ByteArray(2048)
                val packet = DatagramPacket(recvBuf, recvBuf.size)
                mockSocket.receive(packet)

                // Echo back the initiation packet (type 1)
                val replyPacket = DatagramPacket(recvBuf, packet.length, packet.address, packet.port)
                mockSocket.send(replyPacket)
            } catch (_: Exception) {}
        }

        try {
            val res = WarpEndpointScanner.probeEndpoint(
                endpoint = "127.0.0.1:$mockPort",
                timeoutMs = 1000,
                useFragmentation = false,
                protocol = WarpProbeProtocol.WIREGUARD
            )

            assertTrue(res.isAlive)
            assertTrue(res.isUdpResponsive)
            assertEquals(WarpEndpointStage.UDP_RESPONSIVE, res.stage)
            assertFalse(res.isProtocolRecognized, "UDP echo must NOT be recognized as WireGuard Handshake Response")
            assertFalse(res.isWorkingWarp(), "UDP echo must NOT be treated as working WARP")
        } finally {
            mockSocket.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun testRttComparisonOnlyForSameStage() {
        val echoFast = WarpEndpointCandidate(
            ip = "1.1.1.1", port = 500, rttMs = 5, isAlive = true,
            stage = WarpEndpointStage.UDP_RESPONSIVE
        )
        val recognizedSlow = WarpEndpointCandidate(
            ip = "1.1.1.2", port = 500, rttMs = 60, isAlive = true,
            stage = WarpEndpointStage.PROTOCOL_RECOGNIZED
        )
        val authenticatedMedium = WarpEndpointCandidate(
            ip = "1.1.1.3", port = 500, rttMs = 40, isAlive = true,
            stage = WarpEndpointStage.AUTHENTICATED
        )
        val dataPlaneVerifiedSlow = WarpEndpointCandidate(
            ip = "1.1.1.4", port = 500, rttMs = 90, isAlive = true,
            stage = WarpEndpointStage.DATA_PLANE_VERIFIED
        )

        val list = listOf(echoFast, dataPlaneVerifiedSlow, recognizedSlow, authenticatedMedium).sorted()
        assertEquals(dataPlaneVerifiedSlow, list[0], "DataPlaneVerified must be first regardless of RTT")
        assertEquals(authenticatedMedium, list[1], "Authenticated must be second")
        assertEquals(recognizedSlow, list[2], "ProtocolRecognized must be third")
        assertEquals(echoFast, list[3], "UdpResponsive (echo) must be last even with fastest RTT")

        // For identical stage, RTT is compared
        val recognizedFast = WarpEndpointCandidate(
            ip = "1.1.1.5", port = 500, rttMs = 25, isAlive = true,
            stage = WarpEndpointStage.PROTOCOL_RECOGNIZED
        )
        val sameStageList = listOf(recognizedSlow, recognizedFast).sorted()
        assertEquals(recognizedFast, sameStageList[0], "Within same stage, lower RTT wins")
        assertEquals(recognizedSlow, sameStageList[1])
    }

    @Test
    fun testProbeDiscardsResponseFromForeignSource() = runBlocking {
        val mockSocket1 = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val mockSocket2 = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val targetPort = mockSocket1.localPort
        val foreignPort = mockSocket2.localPort

        val serverThread = thread {
            try {
                val recvBuf = ByteArray(2048)
                val packet = DatagramPacket(recvBuf, recvBuf.size)
                mockSocket1.receive(packet)

                // Send reply from mockSocket2 (different port) instead of mockSocket1!
                val resp = ByteArray(92)
                resp[0] = 0x02
                val replyPacket = DatagramPacket(resp, resp.size, packet.address, packet.port)
                mockSocket2.send(replyPacket)
            } catch (_: Exception) {}
        }

        try {
            val res = WarpEndpointScanner.probeEndpoint(
                endpoint = "127.0.0.1:$targetPort",
                timeoutMs = 600,
                useFragmentation = false,
                protocol = WarpProbeProtocol.WIREGUARD
            )

            assertFalse(res.isAlive, "Response from wrong port must be discarded as non-matching source")
            assertEquals(-1L, res.rttMs)
        } finally {
            mockSocket1.close()
            mockSocket2.close()
            serverThread.join(1000)
        }
    }
}

