package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class PortConflictCoreTest {

    @Test
    fun parseProcNetTcpListenersExtractsOnlyListeningSockets() {
        val sampleProcNet = """
              sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 0100007F:2A88 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10245        0 12345 1 00000000 100 0 0 10 0
               1: 00000000:05A3 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10310        0 12346 1 00000000 100 0 0 10 0
               2: 0100007F:2A38 0100007F:D431 01 00000000:00000000 00:00000000 00000000 10999        0 12347 1 00000000 100 0 0 10 0
               3: 00000000000000000000000001000000:1ECE 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10400 0 12348
               4: 0100007F:1F90 00000000:0000 06 00000000:00000000 00:00000000 00000000 10555        0 12349
        """.trimIndent()

        val parsed = PortConflictCore.parseProcNetTcpListeners(sampleProcNet)

        assertEquals(setOf(10245), parsed[10888]) // 0x2A88
        assertEquals(setOf(10310), parsed[1443])  // 0x05A3
        assertEquals(setOf(10400), parsed[7886])  // 0x1ECE (IPv6 listen)
        assertFalse(parsed.containsKey(10808))    // 0x2A38 is 01 (ESTABLISHED), not 0A (LISTEN)
        assertFalse(parsed.containsKey(8080))     // 0x1F90 is 06 (TIME_WAIT), not 0A (LISTEN)
    }

    @Test
    fun buildPortCheckTargetsDeduplicatesAndRespectsRunningAndExcludedPorts() {
        val config = ProxyConfig(
            bindHost = "127.0.0.1",
            bindPort = 1443,
            socks5Port = 10888,
            proxyModeName = ProxyMode.SOCKS5.name
        )

        val idleTargets = PortConflictCore.buildPortCheckTargets(config, runningMode = null)
        assertEquals(listOf(10888, 1443), idleTargets.map { it.port })
        assertTrue(idleTargets.first { it.port == 10888 }.isActiveModePort)
        assertFalse(idleTargets.first { it.port == 1443 }.isActiveModePort)

        val runningSocksTargets = PortConflictCore.buildPortCheckTargets(config, runningMode = ProxyMode.SOCKS5)
        assertEquals(listOf(1443), runningSocksTargets.map { it.port })

        val samePortConfig = config.copy(bindPort = 10888, socks5Port = 10888)
        val merged = PortConflictCore.buildPortCheckTargets(samePortConfig, runningMode = null)
        assertEquals(1, merged.size)
        assertEquals("SOCKS5 · MTProto", merged.first().protocolLabel)
        assertFalse(merged.first().protocolLabel.contains("("))

        val withExcludedOAuth = PortConflictCore.buildPortCheckTargets(
            config = config.copy(socks5Port = 8976),
            runningMode = null,
            excludedSelfPorts = setOf(8976)
        )
        assertEquals(listOf(1443), withExcludedOAuth.map { it.port })
    }

    @Test
    fun probePortOccupancyDetectsSocks5HttpAndFreePorts() {
        // 1. Mock SOCKS5 listener
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { socksServer ->
            val t = thread(isDaemon = true) {
                runCatching {
                    socksServer.accept().use { client ->
                        val buf = ByteArray(16)
                        val n = client.getInputStream().read(buf)
                        if (n >= 3 && buf[0] == 0x05.toByte()) {
                            client.getOutputStream().write(byteArrayOf(0x05, 0x00))
                            client.getOutputStream().flush()
                        }
                    }
                }
            }
            val result = PortConflictCore.probePortOccupancy("127.0.0.1", socksServer.localPort, probeProtocol = true)
            t.join(1000)
            assertTrue(result.isOccupied)
            assertEquals(PortListenerProtocol.SOCKS5, result.listenerProtocol)
        }

        // 2. Mock HTTP proxy listener
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { httpServer ->
            val t = thread(isDaemon = true) {
                repeat(2) {
                    runCatching {
                        httpServer.accept().use { client ->
                            val buf = ByteArray(64)
                            val n = client.getInputStream().read(buf)
                            if (n > 0) {
                                client.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                                client.getOutputStream().flush()
                            }
                        }
                    }
                }
            }
            val result = PortConflictCore.probePortOccupancy("127.0.0.1", httpServer.localPort, probeProtocol = true)
            t.join(1000)
            assertTrue(result.isOccupied)
            assertEquals(PortListenerProtocol.HTTP_PROXY, result.listenerProtocol)
        }

        // 3. Free ephemeral port
        val freePort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val freeResult = PortConflictCore.probePortOccupancy("127.0.0.1", freePort, probeProtocol = true)
        assertFalse(freeResult.isOccupied)
    }

    @Test
    fun findNearestFreePortSkipsOccupiedAndExcludedPorts() {
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val basePort = occupied.localPort
            val skippedPort = basePort + 1
            val chosen = PortConflictCore.findNearestFreePort(
                host = "127.0.0.1",
                preferredPort = basePort,
                excludedPorts = setOf(skippedPort)
            )
            assertNotNull(chosen)
            assertNotEquals(basePort, chosen)
            assertNotEquals(skippedPort, chosen)
        }
    }

    @Test
    fun proxyCandidateMatcherExcludesSelfAndTelegramMessengers() {
        val self = "com.mirrly.tgproxy"
        assertTrue(PortConflictCore.isLikelyProxyPackageOrLabel("com.v2ray.ang", "v2rayNG", self))
        assertTrue(PortConflictCore.isLikelyProxyPackageOrLabel("com.example.tool", "My Socks Proxy", self))
        assertFalse(PortConflictCore.isLikelyProxyPackageOrLabel(self, "Mirrly TG Proxy", self))
        assertFalse(PortConflictCore.isLikelyProxyPackageOrLabel("org.telegram.messenger", "Telegram", self))
        assertFalse(PortConflictCore.isLikelyProxyPackageOrLabel("com.mirrly.gram", "MirrlyGram", self))
        assertFalse(PortConflictCore.isLikelyProxyPackageOrLabel("com.android.chrome", "Chrome", self))
    }
}
