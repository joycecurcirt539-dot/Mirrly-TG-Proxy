/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class NodeHealthProberTest {

    @BeforeEach
    @AfterEach
    fun resetState() {
        NodeHealthProber.clearCache()
    }

    @Test
    fun testNodeProbeStatusProperties() {
        assertTrue(NodeProbeStatus.AVAILABLE.isSuccess)
        assertTrue(NodeProbeStatus.WARNING.isSuccess)
        assertFalse(NodeProbeStatus.DPI_BLOCKED.isSuccess)
        assertFalse(NodeProbeStatus.TIMEOUT.isSuccess)
        assertFalse(NodeProbeStatus.DNS_FAILED.isSuccess)
        assertFalse(NodeProbeStatus.RATE_LIMITED.isSuccess)
        assertFalse(NodeProbeStatus.ERROR.isSuccess)
        assertFalse(NodeProbeStatus.IDLE.isSuccess)
        assertFalse(NodeProbeStatus.CHECKING.isSuccess)
    }

    @Test
    fun testCacheOperations() {
        assertNull(NodeHealthProber.getCachedResult("node-1"))
        NodeHealthProber.clearCache()
        assertNull(NodeHealthProber.getCachedResult("node-1"))
    }

    @Test
    fun testProbeViaLocalSocksNoAuthSuccess() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val serverDone = AtomicBoolean(false)

        val serverThread = Thread {
            try {
                serverSocket.soTimeout = 3000
                val client = serverSocket.accept()
                val inp = client.getInputStream()
                val out = client.getOutputStream()

                // 1. SOCKS5 Auth request: [0x05, 0x01, 0x00]
                val authReq = ByteArray(3)
                inp.read(authReq)
                assertEquals(0x05.toByte(), authReq[0])
                out.write(byteArrayOf(0x05, 0x00)) // No auth accepted
                out.flush()

                // 2. CONNECT request
                val connectHeader = ByteArray(4)
                inp.read(connectHeader)
                assertEquals(0x05.toByte(), connectHeader[0]) // VER
                assertEquals(0x01.toByte(), connectHeader[1]) // CMD CONNECT
                val atyp = connectHeader[3]
                if (atyp == 0x01.toByte()) {
                    val ipBytes = ByteArray(4)
                    inp.read(ipBytes)
                } else if (atyp == 0x03.toByte()) {
                    val domainLen = inp.read()
                    val domainBytes = ByteArray(domainLen)
                    inp.read(domainBytes)
                }
                val portBytes = ByteArray(2)
                inp.read(portBytes)

                // Response: Success
                out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x01, 0xBB.toByte()))
                out.flush()
                serverDone.set(true)
                client.close()
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }
        serverThread.start()

        val target = HealthTargetNode(
            id = "test-node",
            name = "Test Telegram DC",
            domain = "149.154.167.50",
            port = 443,
            type = HealthTargetType.WORKER
        )

        val metric = NodeHealthProber.probeViaLocalSocks(target, port, null)

        serverThread.join(2000)
        assertTrue(serverDone.get(), "Mock SOCKS5 server must complete exchange")
        assertEquals(NodeProbeStatus.AVAILABLE, metric.status)
        assertNotNull(metric.latencyMs)
        assertEquals("Туннель активен", metric.detail)
    }

    @Test
    fun testProbeViaLocalSocksWithAuthSuccess() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val serverDone = AtomicBoolean(false)

        val serverThread = Thread {
            try {
                serverSocket.soTimeout = 3000
                val client = serverSocket.accept()
                val inp = client.getInputStream()
                val out = client.getOutputStream()

                // 1. Auth method negotiation
                val methodReq = ByteArray(3)
                inp.read(methodReq)
                assertEquals(0x05.toByte(), methodReq[0])
                out.write(byteArrayOf(0x05, 0x02)) // Username/password required
                out.flush()

                // 2. Sub-negotiation
                val ver = inp.read()
                assertEquals(1, ver)
                val uLen = inp.read()
                val uBytes = ByteArray(uLen)
                inp.read(uBytes)
                val pLen = inp.read()
                val pBytes = ByteArray(pLen)
                inp.read(pBytes)

                val user = String(uBytes, StandardCharsets.UTF_8)
                val pass = String(pBytes, StandardCharsets.UTF_8)
                assertEquals("mirrlyUser", user)
                assertEquals("secretPass", pass)

                out.write(byteArrayOf(0x01, 0x00)) // Auth success
                out.flush()

                // 3. CONNECT request
                val connectBuf = ByteArray(1024)
                val n = inp.read(connectBuf)
                assertTrue(n > 0)
                out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x01, 0xBB.toByte()))
                out.flush()
                serverDone.set(true)
                client.close()
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }
        serverThread.start()

        val target = HealthTargetNode(
            id = "test-auth-node",
            name = "Test Auth Node",
            domain = "149.154.167.51",
            port = 443,
            type = HealthTargetType.WORKER
        )

        val metric = NodeHealthProber.probeViaLocalSocks(target, port, Pair("mirrlyUser", "secretPass"))

        serverThread.join(2000)
        assertTrue(serverDone.get(), "Authenticated mock SOCKS5 server must complete exchange")
        assertEquals(NodeProbeStatus.AVAILABLE, metric.status)
        assertEquals("Туннель активен", metric.detail)
    }

    @Test
    fun testProbeViaLocalSocksAuthFailure() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val serverThread = Thread {
            try {
                serverSocket.soTimeout = 3000
                val client = serverSocket.accept()
                val inp = client.getInputStream()
                val out = client.getOutputStream()

                inp.read(ByteArray(3))
                out.write(byteArrayOf(0x05, 0x02))
                out.flush()

                inp.read(ByteArray(100))
                out.write(byteArrayOf(0x01, 0x01)) // Auth failed
                out.flush()
                client.close()
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }
        serverThread.start()

        val target = HealthTargetNode(
            id = "test-fail-node",
            name = "Test Node",
            domain = "149.154.167.51",
            port = 443,
            type = HealthTargetType.WORKER
        )

        val metric = NodeHealthProber.probeViaLocalSocks(target, port, Pair("badUser", "badPass"))
        serverThread.join(2000)
        assertEquals(NodeProbeStatus.ERROR, metric.status)
        assertTrue(metric.detail.contains("SOCKS5 Auth error"))
    }

    @Test
    fun testProbeViaLocalSocksClosedPort() {
        val freeSocket = ServerSocket(0)
        val closedPort = freeSocket.localPort
        freeSocket.close()

        val target = HealthTargetNode(
            id = "test-closed",
            name = "Closed Port Test",
            domain = "149.154.167.51",
            port = 443,
            type = HealthTargetType.WORKER
        )

        val metric = NodeHealthProber.probeViaLocalSocks(target, closedPort, null)
        assertEquals(NodeProbeStatus.TIMEOUT, metric.status)
    }

    @Test
    fun testProbeDirectDnsFailure() {
        val target = HealthTargetNode(
            id = "test-bad-dns",
            name = "Bad DNS Node",
            domain = "invalid-test-domain-not-exist-xyz999.invalid",
            port = 443,
            type = HealthTargetType.WORKER
        )

        val metric = NodeHealthProber.probeDirect(target)
        assertEquals(NodeProbeStatus.DNS_FAILED, metric.status)
        assertTrue(metric.detail.contains("Ошибка DNS") || metric.detail.contains("Не найден"))
    }

    @Test
    fun testProbeViaLocalSocksClosedConnectionBeforeReplyFails() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val thread = Thread {
            try {
                val client = serverSocket.accept()
                val inp = client.getInputStream()
                // Read auth request [0x05, 0x01, 0x00]
                val req = ByteArray(3)
                inp.read(req)
                // Instantly close connection without sending reply
                client.close()
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }
        thread.isDaemon = true
        thread.start()

        val metric = NodeHealthProber.probeViaLocalSocks("149.154.167.50", 443, port, null, 1000)
        thread.join(2000)

        assertEquals(NodeProbeStatus.ERROR, metric.status, "Closed connection before auth reply must fail with ERROR")
        assertFalse(metric.isReady, "Closed connection before reply must not be Ready")
    }

    @Test
    fun testProbeViaLocalSocksOneByteReplyFails() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val thread = Thread {
            try {
                val client = serverSocket.accept()
                val inp = client.getInputStream()
                val out = client.getOutputStream()

                // Read auth request and send valid auth OK [0x05, 0x00]
                val authReq = ByteArray(3)
                inp.read(authReq)
                out.write(byteArrayOf(0x05, 0x00))
                out.flush()

                // Read connect request [0x05, 0x01, ...]
                val connReq = ByteArray(10)
                inp.read(connReq)

                // Send ONLY 1 byte then close connection (partial malformed response)
                out.write(byteArrayOf(0x05))
                out.flush()
                client.close()
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }
        thread.isDaemon = true
        thread.start()

        val metric = NodeHealthProber.probeViaLocalSocks("149.154.167.50", 443, port, null, 1000)
        thread.join(2000)

        assertEquals(NodeProbeStatus.ERROR, metric.status, "1-byte reply must fail with ERROR")
        assertFalse(metric.isReady, "1-byte reply must not be Ready")
    }

    @Test
    fun testProbeViaLocalSocksServerSilentDropDataNotReady() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val thread = Thread {
            try {
                val client = serverSocket.accept()
                val inp = client.getInputStream()
                val out = client.getOutputStream()

                // 1. Auth OK
                inp.read(ByteArray(3))
                out.write(byteArrayOf(0x05, 0x00))
                out.flush()

                // 2. Connect OK
                inp.read(ByteArray(10))
                out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x01, 0xBB.toByte()))
                out.flush()

                // 3. Read client's E2E probe payload, but SILENTLY close without sending response data
                val probePayload = ByteArray(100)
                val n = inp.read(probePayload)
                assertTrue(n > 0, "Client must have sent probe payload")
                // Close connection (simulates upstream silent packet drop/TSPU drop)
                client.close()
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }
        thread.isDaemon = true
        thread.start()

        val metric = NodeHealthProber.probeViaLocalSocksE2E(
            host = "149.154.167.50",
            port = 443,
            socksPort = port,
            auth = null,
            timeoutMs = 1000,
            e2ePayload = "MIRRLY_PROBE_E2E".toByteArray(StandardCharsets.US_ASCII)
        )
        thread.join(2000)

        assertEquals(NodeProbeStatus.ERROR, metric.status, "Silent data loss must fail with ERROR")
        assertFalse(metric.isReady, "Server accepting CONNECT but losing payload data must NOT be Ready")
        assertEquals(0, metric.rxBytes, "Must have received 0 bytes of useful RX data")
    }

    @Test
    fun testProbeViaLocalSocksControlledEchoThroughUplinkReady() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val thread = Thread {
            try {
                val client = serverSocket.accept()
                val inp = client.getInputStream()
                val out = client.getOutputStream()

                // 1. Auth OK
                inp.read(ByteArray(3))
                out.write(byteArrayOf(0x05, 0x00))
                out.flush()

                // 2. Connect OK
                inp.read(ByteArray(10))
                out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x01, 0xBB.toByte()))
                out.flush()

                // 3. Read client's E2E probe payload and echo back response
                val probePayload = ByteArray(100)
                val n = inp.read(probePayload)
                assertTrue(n > 0, "Client must have sent probe payload")
                val response = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nHELLO".toByteArray(StandardCharsets.US_ASCII)
                out.write(response)
                out.flush()
                client.close()
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }
        thread.isDaemon = true
        thread.start()

        val metric = NodeHealthProber.probeViaLocalSocksE2E(
            host = "149.154.167.50",
            port = 443,
            socksPort = port,
            auth = null,
            timeoutMs = 1000,
            e2ePayload = "GET / HTTP/1.1\r\nHost: 149.154.167.50\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        )
        thread.join(2000)

        assertEquals(NodeProbeStatus.AVAILABLE, metric.status, "Successful E2E data transmission must be AVAILABLE")
        assertTrue(metric.isReady, "Successful E2E data transmission must be Ready")
        assertNotNull(metric.latencyMs)
        assertTrue(metric.latencyMs!! >= 0)
        assertTrue(metric.rxBytes > 0, "Must have recorded useful RX bytes")
        assertTrue(metric.detail.contains("E2E туннель активен"))
    }

    @Test
    fun testNodeProbeStatusUnsupportedStageProperties() {
        assertEquals("Не поддерживается сетью", NodeProbeStatus.UNSUPPORTED_STAGE.label)
        assertFalse(NodeProbeStatus.UNSUPPORTED_STAGE.isSuccess)
    }

    @Test
    fun testProbeViaLocalSocksIpv6Atyp4() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val receivedAtyp = java.util.concurrent.atomic.AtomicInteger(-1)
        val receivedIpBytes = java.util.concurrent.atomic.AtomicReference<ByteArray>(null)

        val thread = Thread {
            try {
                val client = serverSocket.accept()
                val inp = client.getInputStream()
                val out = client.getOutputStream()

                // 1. Auth method NO AUTH
                inp.read(ByteArray(3))
                out.write(byteArrayOf(0x05, 0x00))
                out.flush()

                // 2. Connect request: read header [VER, CMD, RSV, ATYP]
                val header = ByteArray(4)
                inp.read(header)
                val atyp = header[3].toInt() and 0xFF
                receivedAtyp.set(atyp)

                if (atyp == 0x04) { // IPv6: 16 bytes
                    val ipBytes = ByteArray(16)
                    inp.read(ipBytes)
                    receivedIpBytes.set(ipBytes)
                }
                val portBytes = ByteArray(2)
                inp.read(portBytes)

                // Reply: Success
                out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x01, 0xBB.toByte()))
                out.flush()
                client.close()
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }
        thread.isDaemon = true
        thread.start()

        val metric = NodeHealthProber.probeViaLocalSocks(
            host = "2001:b28:f23d:f001::a",
            port = 443,
            socksPort = port,
            auth = null,
            timeoutMs = 1500
        )
        thread.join(2000)

        assertEquals(0x04, receivedAtyp.get(), "SOCKS5 must encode IPv6 destination with ATYP 0x04")
        assertNotNull(receivedIpBytes.get())
        assertEquals(16, receivedIpBytes.get()?.size)
        assertEquals(NodeProbeStatus.AVAILABLE, metric.status)
    }

    @Test
    fun testProbeViaLocalSocksRepNetworkUnreachableYieldsUnsupportedStage() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val thread = Thread {
            try {
                val client = serverSocket.accept()
                val inp = client.getInputStream()
                val out = client.getOutputStream()

                // 1. Auth method NO AUTH
                inp.read(ByteArray(3))
                out.write(byteArrayOf(0x05, 0x00))
                out.flush()

                // 2. Connect request
                inp.read(ByteArray(10))

                // Reply: REP 0x03 (Network unreachable)
                out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()
                client.close()
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }
        thread.isDaemon = true
        thread.start()

        val metric = NodeHealthProber.probeViaLocalSocks(
            host = "192.0.2.1",
            port = 443,
            socksPort = port,
            auth = null,
            timeoutMs = 1500
        )
        thread.join(2000)

        assertEquals(NodeProbeStatus.UNSUPPORTED_STAGE, metric.status, "REP 0x03 must be mapped to UNSUPPORTED_STAGE")
        assertFalse(metric.status.isSuccess)
    }
}
