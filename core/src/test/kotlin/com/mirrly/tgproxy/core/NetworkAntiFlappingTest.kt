package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NetworkAntiFlappingTest {
    private val ready = NetworkProfile(
        validated = true, suspended = false, transport = NetworkTransport.CELLULAR, cellular = true
    )

    private fun sample(at: Long, rtt: Long = 80, probe: Long = at, profile: NetworkProfile = ready) =
        AdaptivePolicySnapshot(at, profile, 0, rtt, rtt, 0, 100, probeId = probe)

    @Test
    fun `shared cooldown includes socket changes and endpoint changes`() {
        var now = 0L
        val gate = NetworkStabilizationGate(clock = { now })
        val controller = AdaptiveNetworkPolicyController(stabilizationGate = gate)
        controller.evaluate(sample(now))
        now = 29_999
        assertFalse(gate.tryChange { true })
        now = 30_000
        assertTrue(gate.tryChange { true })
        controller.beginRecovery(now)
        for (second in 31L..59L) {
            now = second * 1000
            val decision = controller.evaluate(sample(now, 450))
            assertTrue(decision.recommendedTcpNoDelay)
            assertEquals(350L, decision.recommendedHappyEyeballsDelayMs)
        }
        now = 60_000
        val degraded = controller.evaluate(sample(now, 450))
        assertEquals(AdaptiveNetworkState.DEGRADED, degraded.networkState)
        assertFalse(degraded.recommendedTcpNoDelay)
        assertEquals(700L, degraded.recommendedHappyEyeballsDelayMs)
        assertFalse(gate.tryChange { true }, "a socket policy commit also blocks failover")
    }

    @Test
    fun `no available candidate does not consume cooldown`() {
        val gate = NetworkStabilizationGate(clock = { 0L })
        assertFalse(gate.tryChange { false })
        assertTrue(gate.tryChange { true })
        assertFalse(gate.tryChange { true })
    }

    @Test
    fun `single failed probe cannot mature through repeated polling`() {
        val controller = AdaptiveNetworkPolicyController()
        controller.evaluate(sample(0))
        for (second in 31L..70L) {
            assertEquals(AdaptiveNetworkState.NORMAL,
                controller.evaluate(sample(second * 1000, 450, probe = 1)).networkState)
        }
        assertEquals(AdaptiveNetworkState.NORMAL, controller.evaluate(sample(71_000)).networkState)
    }

    @Test
    fun `late bad probe starts a new window after a long measurement gap`() {
        val controller = AdaptiveNetworkPolicyController()
        controller.evaluate(sample(0))
        for (second in 31L..70L) controller.evaluate(sample(second * 1000, 450, probe = 1))
        assertEquals(AdaptiveNetworkState.NORMAL, controller.evaluate(sample(71_000, 450, probe = 2)).networkState)
        for (second in 72L..78L) controller.evaluate(sample(second * 1000, 450))
        assertEquals(AdaptiveNetworkState.DEGRADED, controller.currentDecision.networkState)
    }

    @Test
    fun `startup does not treat a single high RTT as confirmed degradation`() {
        val controller = AdaptiveNetworkPolicyController()
        assertEquals(AdaptiveNetworkState.NORMAL, controller.evaluate(sample(0, 450)).networkState)
        assertTrue(controller.currentDecision.recommendedTcpNoDelay)
    }

    @Test
    fun `suspend flapping does not toggle committed socket options`() {
        val controller = AdaptiveNetworkPolicyController()
        val initial = controller.evaluate(sample(0))
        for (second in 1L..29L) {
            val profile = if (second % 2 == 1L) ready.copy(suspended = true) else ready
            val decision = controller.evaluate(sample(second * 1000, profile = profile))
            assertEquals(initial.recommendedTcpNoDelay, decision.recommendedTcpNoDelay)
            assertEquals(initial.recommendedBufferSizeBytes, decision.recommendedBufferSizeBytes)
            assertEquals(initial.recommendedHappyEyeballsDelayMs, decision.recommendedHappyEyeballsDelayMs)
        }
    }

    @Test
    fun `failure window requires continuous fresh observations for seven seconds`() {
        val policy = WorkerSwitchPolicy()
        assertFalse(policy.observe("a", 1, true, 0))
        assertFalse(policy.observe("a", 1, true, 4_000))
        assertFalse(policy.observe("a", 1, false, 5_000))
        assertFalse(policy.observe("a", 1, true, 6_000))
        assertFalse(policy.observe("a", 1, true, 6_000))
        assertFalse(policy.observe("a", 1, true, 12_999))
        assertTrue(policy.observe("a", 1, true, 13_000))
    }

    @Test
    fun `network changes and long gaps restart failure confirmation`() {
        val policy = WorkerSwitchPolicy()
        policy.observe("a", 1, true, 0)
        assertFalse(policy.observe("a", 2, true, 8_000))
        assertFalse(policy.observe("a", 2, true, 30_000))
        assertTrue(policy.observe("a", 2, true, 37_000))
        assertFalse(policy.observe("b", 2, true, 38_000))
    }

    @Test
    fun `two-worker pool cannot bypass the sixty second return ban`() {
        val policy = WorkerSwitchPolicy()
        policy.switched("a", 0)
        assertFalse(policy.canSelect("a", 30_000))
        assertFalse(policy.canSelect("a", 59_999))
        assertTrue(policy.canSelect("a", 60_000))
        assertTrue(policy.canSelect("b", 30_000))
    }

    @Test
    fun `Android environment survives runtime updates without generation thrashing`() {
        val server = LocalProxyServer()
        val generation = server.currentProfileGeneration.get()
        repeat(60) { index ->
            server.updateNetworkEnvironment(NetworkEnvironment(
                validated = true, transport = NetworkTransport.CELLULAR,
                congested = index % 2 == 0, estimatedDownKbps = index * 1000
            ), screenOn = true, powerSaveMode = false)
            server.updateScreenPowerMode(screenOn = false, powerSaveMode = true)
            assertEquals(generation, server.currentProfileGeneration.get())
            assertEquals(index % 2 == 0, server.effectiveNetworkProfile.congested)
            assertTrue(server.effectiveNetworkProfile.validated)
            assertEquals(NetworkPowerMode.POWER_SAVE, server.effectiveNetworkProfile.powerMode)
        }
    }
}
