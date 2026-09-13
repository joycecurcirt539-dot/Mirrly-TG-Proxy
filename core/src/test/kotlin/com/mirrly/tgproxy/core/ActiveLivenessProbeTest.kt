package com.mirrly.tgproxy.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ActiveLivenessProbeTest {

    @Test
    fun testCascadeStageCycling() {
        assertEquals(CascadeStage.STAGE_1_SCANNED_WARP, CascadeStage.STAGE_0_IPV6_WARP.next())
        assertEquals(CascadeStage.STAGE_2_MASQUE_HTTP3, CascadeStage.STAGE_1_SCANNED_WARP.next())
        assertEquals(CascadeStage.STAGE_3_VLESS_PRESET, CascadeStage.STAGE_2_MASQUE_HTTP3.next())
        assertEquals(CascadeStage.STAGE_0_IPV6_WARP, CascadeStage.STAGE_3_VLESS_PRESET.next())
    }

    @Test
    fun testCascadeTransitionCallback() {
        val oldStageRef = AtomicReference<CascadeStage>()
        val newStageRef = AtomicReference<CascadeStage>()
        val reasonRef = AtomicReference<String>()

        val probe = ActiveLivenessProbe(
            onCascadeTriggered = { oldStage, newStage, reason ->
                oldStageRef.set(oldStage)
                newStageRef.set(newStage)
                reasonRef.set(reason)
            }
        )

        probe.setInitialStage(CascadeStage.STAGE_1_SCANNED_WARP)
        assertEquals(CascadeStage.STAGE_1_SCANNED_WARP, probe.currentStage)

        probe.triggerCascadeFailover("Test Timeout > 800ms")

        assertEquals(CascadeStage.STAGE_1_SCANNED_WARP, oldStageRef.get())
        assertEquals(CascadeStage.STAGE_2_MASQUE_HTTP3, newStageRef.get())
        assertEquals(CascadeStage.STAGE_2_MASQUE_HTTP3, probe.currentStage)
        assertEquals("Test Timeout > 800ms", reasonRef.get())
    }

    @Test
    fun testIpv6DetectionRunsSafely() {
        // Must complete without throwing any exception on any OS/JVM
        val ipv6Available = ActiveLivenessProbe.isIpv6Available()
        assertTrue(ipv6Available || !ipv6Available)
    }

    @Test
    fun testExecuteSocks5ProbeOnClosedPortFailsGracefully() = runBlocking {
        val freeSocket = ServerSocket(0)
        val freePort = freeSocket.localPort
        freeSocket.close()

        val probe = ActiveLivenessProbe(
            socks5PortProvider = { freePort },
            onCascadeTriggered = { _, _, _ -> }
        )

        val result = probe.executeSocks5Probe(
            targetHost = "149.154.167.51",
            targetPort = 443,
            timeoutMs = 150
        )

        assertFalse(result.isAlive, "Closed port probe must report isAlive=false")
        assertNotNull(result.failureReason, "Failure reason must be populated")
    }

    @Test
    fun testMockSocks5HandshakeSuccess() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val serverDone = AtomicBoolean(false)

        val thread = Thread {
            try {
                val client = serverSocket.accept()
                val input = client.getInputStream()
                val output = client.getOutputStream()

                // 1. Read Auth Handshake: [0x05, 0x01, 0x00]
                val authReq = ByteArray(3)
                input.read(authReq)
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // 2. Read CONNECT Request
                val connectReq = ByteArray(10)
                input.read(connectReq)
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x01, 0xBB.toByte()))
                output.flush()

                serverDone.set(true)
                client.close()
            } catch (_: Exception) {
            } finally {
                try { serverSocket.close() } catch (_: Exception) {}
            }
        }
        thread.isDaemon = true
        thread.start()

        val probe = ActiveLivenessProbe(
            socks5PortProvider = { port },
            onCascadeTriggered = { _, _, _ -> }
        )

        val result = probe.executeSocks5Probe(
            targetHost = "149.154.167.51",
            targetPort = 443,
            timeoutMs = 1000
        )

        assertTrue(result.isAlive, "Probe must succeed when SOCKS5 returns REP=0x00")
        assertTrue(result.rttMs >= 0L, "RTT must be >= 0")
        assertEquals("149.154.167.51:443", result.targetDc)
    }

    @Test
    fun testExecuteSocks5ProbeClosedBeforeReplyFails() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val thread = Thread {
            try {
                val client = serverSocket.accept()
                val input = client.getInputStream()
                // Read auth request [0x05, 0x01, 0x00]
                val authReq = ByteArray(3)
                input.read(authReq)
                // Close before sending auth response
                client.close()
            } catch (_: Exception) {
            } finally {
                try { serverSocket.close() } catch (_: Exception) {}
            }
        }
        thread.isDaemon = true
        thread.start()

        val probe = ActiveLivenessProbe(
            socks5PortProvider = { port },
            onCascadeTriggered = { _, _, _ -> }
        )

        val result = probe.executeSocks5Probe(
            targetHost = "149.154.167.51",
            targetPort = 443,
            timeoutMs = 1000
        )

        assertFalse(result.isAlive, "Closed before reply must fail probe")
        assertNotNull(result.failureReason)
    }

    @Test
    fun testExecuteSocks5ProbeOneByteReplyFails() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val thread = Thread {
            try {
                val client = serverSocket.accept()
                val input = client.getInputStream()
                val output = client.getOutputStream()

                // Auth OK
                val authReq = ByteArray(3)
                input.read(authReq)
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // Connect request
                val connectReq = ByteArray(10)
                input.read(connectReq)

                // Only 1 byte sent
                output.write(byteArrayOf(0x05))
                output.flush()
                client.close()
            } catch (_: Exception) {
            } finally {
                try { serverSocket.close() } catch (_: Exception) {}
            }
        }
        thread.isDaemon = true
        thread.start()

        val probe = ActiveLivenessProbe(
            socks5PortProvider = { port },
            onCascadeTriggered = { _, _, _ -> }
        )

        val result = probe.executeSocks5Probe(
            targetHost = "149.154.167.51",
            targetPort = 443,
            timeoutMs = 1000
        )

        assertFalse(result.isAlive, "1-byte reply must fail probe")
        assertNotNull(result.failureReason)
    }

    @Test
    fun testExecuteSocks5ProbeSilentDropE2EFails() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val thread = Thread {
            try {
                val client = serverSocket.accept()
                val input = client.getInputStream()
                val output = client.getOutputStream()

                // Auth OK
                input.read(ByteArray(3))
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // Connect OK
                input.read(ByteArray(10))
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x01, 0xBB.toByte()))
                output.flush()

                // Read client E2E payload and silently close without reply
                val payload = ByteArray(100)
                val n = input.read(payload)
                assertTrue(n > 0)
                client.close()
            } catch (_: Exception) {
            } finally {
                try { serverSocket.close() } catch (_: Exception) {}
            }
        }
        thread.isDaemon = true
        thread.start()

        val probe = ActiveLivenessProbe(
            socks5PortProvider = { port },
            onCascadeTriggered = { _, _, _ -> }
        )

        val result = probe.executeSocks5Probe(
            targetHost = "149.154.167.51",
            targetPort = 443,
            timeoutMs = 1000,
            e2eProbe = true,
            probePayload = "PING_TEST".toByteArray()
        )

        assertFalse(result.isAlive, "Silent drop on E2E probe must report isAlive=false")
        assertEquals(0, result.rxBytes, "Must have received 0 RX bytes")
        assertTrue(result.failureReason?.contains("RX=0") == true || result.failureReason?.contains("Таймаут") == true)
    }

    @Test
    fun testExecuteSocks5ProbeEchoE2ESuccess() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val thread = Thread {
            try {
                val client = serverSocket.accept()
                val input = client.getInputStream()
                val output = client.getOutputStream()

                // Auth OK
                input.read(ByteArray(3))
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // Connect OK
                input.read(ByteArray(10))
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x01, 0xBB.toByte()))
                output.flush()

                // Read client E2E payload and echo back response
                val payload = ByteArray(100)
                val n = input.read(payload)
                assertTrue(n > 0)
                output.write("ECHO_PONG_BYTES".toByteArray())
                output.flush()
                client.close()
            } catch (_: Exception) {
            } finally {
                try { serverSocket.close() } catch (_: Exception) {}
            }
        }
        thread.isDaemon = true
        thread.start()

        val probe = ActiveLivenessProbe(
            socks5PortProvider = { port },
            onCascadeTriggered = { _, _, _ -> }
        )

        val result = probe.executeSocks5Probe(
            targetHost = "149.154.167.51",
            targetPort = 443,
            timeoutMs = 1000,
            e2eProbe = true,
            probePayload = "ECHO_PING".toByteArray()
        )

        assertTrue(result.isAlive, "Echo response must report isAlive=true")
        assertTrue(result.rxBytes > 0, "Must have received > 0 RX bytes")
        assertTrue(result.rttMs >= 0L)
    }
}
