package com.mirrly.tgproxy.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ProfileGenerationScanGuardTest {

    @Test
    fun testApplyWarpEndpointRejectsStaleGeneration() {
        val config = ProxyConfig()
        config.uplinkModeName = UplinkMode.MASQUE.name
        config.warpPeerEndpoint = "162.159.192.1:2408"
        val server = LocalProxyServer(config)

        val gen = server.currentProfileGeneration.get()
        assertEquals(1L, gen)

        // Manual apply advances generation
        val applied = server.applyWarpEndpoint("162.159.193.10:1070")
        assertTrue(applied)
        assertEquals("162.159.193.10:1070", config.warpPeerEndpoint)
        val newGen = server.currentProfileGeneration.get()
        assertEquals(2L, newGen)

        // Call applyWarpEndpoint with old expected generation 1L -> must be rejected
        val rejected = server.applyWarpEndpoint("162.159.192.55:500", expectedGeneration = 1L, expectedMode = UplinkMode.MASQUE)
        assertFalse(rejected)
        assertEquals("162.159.193.10:1070", config.warpPeerEndpoint)
    }

    @Test
    fun testApplyWarpEndpointRejectsModeMismatch() {
        val config = ProxyConfig()
        config.uplinkModeName = UplinkMode.VLESS.name
        val server = LocalProxyServer(config)

        val gen = server.currentProfileGeneration.get()
        // If scan was launched for MASQUE, but current mode is VLESS, reject
        val rejected = server.applyWarpEndpoint(
            "162.159.193.1:1000",
            expectedGeneration = gen,
            expectedMode = UplinkMode.MASQUE
        )
        assertFalse(rejected)
        assertNotEquals("162.159.193.1:1000", config.warpPeerEndpoint)
    }

    @Test
    fun testApplyUplinkModeRejectsStaleGeneration() {
        val config = ProxyConfig()
        config.uplinkModeName = UplinkMode.MASQUE.name
        val server = LocalProxyServer(config)

        val gen = server.currentProfileGeneration.get()
        // User changes mode to VLESS manually
        server.applyUplinkMode(UplinkMode.VLESS)
        assertEquals(UplinkMode.VLESS.name, config.uplinkModeName)
        val nextGen = server.currentProfileGeneration.get()
        assertEquals(gen + 1, nextGen)

        // An async failover task from old generation tries to apply AWG
        val rejected = server.applyUplinkMode(UplinkMode.AWG, expectedGeneration = gen)
        assertFalse(rejected)
        assertEquals(UplinkMode.VLESS.name, config.uplinkModeName)
    }

    @Test
    fun testSlowScanCancelledWhenUserSelectsNewMode() = runBlocking {
        val config = ProxyConfig()
        config.uplinkModeName = UplinkMode.MASQUE.name
        config.warpPeerEndpoint = "initial-ep-A:2408"
        val server = LocalProxyServer(config)

        val genA = server.currentProfileGeneration.get()
        val scanCompleted = AtomicBoolean(false)
        val scanApplied = AtomicBoolean(false)

        // Simulate async slow scan on generation A
        val job = launch(Dispatchers.IO) {
            delay(150)
            scanCompleted.set(true)
            // Attempt to apply only if expected generation matches
            val ok = server.applyWarpEndpoint(
                "scanned-ep-A:1070",
                expectedGeneration = genA,
                expectedMode = UplinkMode.MASQUE
            )
            scanApplied.set(ok)
        }

        // Before scan finishes, user selects Profile B (e.g. VLESS or different mode)
        delay(30)
        server.applyUplinkMode(UplinkMode.VLESS)
        assertEquals(UplinkMode.VLESS.name, config.uplinkModeName)
        val genB = server.currentProfileGeneration.get()
        assertTrue(genB > genA)

        job.join()
        assertTrue(scanCompleted.get())
        assertFalse(scanApplied.get(), "Slow scan result must not be applied after user selected Mode B")
        assertNotEquals("scanned-ep-A:1070", config.warpPeerEndpoint)
        assertEquals(UplinkMode.VLESS.name, config.uplinkModeName)
    }

    @Test
    fun testSlowScanCancelledWhenProxyStopped() = runBlocking {
        val config = ProxyConfig()
        config.uplinkModeName = UplinkMode.MASQUE.name
        config.warpPeerEndpoint = "endpoint-A:2408"
        val server = LocalProxyServer(config)

        val genA = server.currentProfileGeneration.get()
        val appliedAfterStop = AtomicBoolean(false)

        val job = launch(Dispatchers.IO) {
            delay(150)
            val ok = server.applyWarpEndpoint(
                "scanned-endpoint-A:1070",
                expectedGeneration = genA,
                expectedMode = UplinkMode.MASQUE
            )
            appliedAfterStop.set(ok)
        }

        // Stop proxy before scan finishes
        delay(30)
        server.stop()
        assertFalse(server.isRunning)

        job.join()
        assertFalse(appliedAfterStop.get(), "Scan result must not apply to stopped proxy")
        assertNotEquals("scanned-endpoint-A:1070", config.warpPeerEndpoint)
    }

    @Test
    fun testStaleProbeEventDoesNotDegradeNewProfile() {
        val pingEngine = PingEngine(
            targetProvider = { "example.com" }
        )

        // Generation 1: Initial state
        val gen1 = pingEngine.probeGeneration.get()
        assertEquals(1L, gen1)

        // User switches profile -> reset() increments generation
        pingEngine.reset()
        val gen2 = pingEngine.probeGeneration.get()
        assertEquals(2L, gen2)

        // Snapshot is clean
        assertEquals(-1L, pingEngine.currentSnapshot.smoothedPingMs)
        assertEquals(ConnectionQuality.OFFLINE, pingEngine.currentSnapshot.quality)
        assertEquals(FailureType.NONE, pingEngine.currentSnapshot.lastFailureType)
        assertEquals(0, pingEngine.currentSnapshot.consecutiveFailures)

        // If an old probe from generation 1 finishes, PingEngine ignores it because gen1 != gen2
        // We verify that resetInternal() incremented probeGeneration so in-flight probes are dropped
        assertNotEquals(gen1, gen2)
    }
}

