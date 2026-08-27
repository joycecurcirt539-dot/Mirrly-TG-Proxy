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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class PingEngineTest {

    @Test
    fun testInitialSampleCalculatesSrttAndRttvar() {
        val engine = PingEngine(targetProvider = { "example.com" })
        engine.recordProbe(PingProbeResult(rawRttMs = 100L, success = true))

        val snapshot = engine.currentSnapshot
        assertEquals(100L, snapshot.rawPingMs)
        assertEquals(100L, snapshot.smoothedPingMs)
        assertEquals(50L, snapshot.jitterMs)
        assertEquals(100, snapshot.successRatePercent)
        assertEquals(0, snapshot.consecutiveFailures)
    }

    @Test
    fun testEwmaSmoothingFiltersSingleSpike() {
        val engine = PingEngine(targetProvider = { "example.com" })
        // Base latency: 40 ms
        engine.recordProbe(PingProbeResult(rawRttMs = 40L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 40L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 40L, success = true))

        assertEquals(40L, engine.smoothedPingMs)

        // Single isolated spike (e.g. cellular LTE retransmission) 500 ms
        engine.recordProbe(PingProbeResult(rawRttMs = 500L, success = true))

        // Dynamic EWMA with route-shift adaptation (relDelta > 0.5 -> alpha=0.50):
        // 0.50 * 40 + 0.50 * 500 = 20 + 250 = 270 ms (raw is 500ms, smoothed is filtered to 270ms)
        assertEquals(500L, engine.currentSnapshot.rawPingMs)
        assertEquals(270L, engine.smoothedPingMs)
        assertTrue(engine.jitterMs > 50L, "Jitter should increase after spike")
    }

    @Test
    fun testConnectionQualityClassification() {
        val engine = PingEngine(targetProvider = { "example.com" })

        // Excellent: low latency & low jitter
        engine.recordProbe(PingProbeResult(rawRttMs = 40L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 42L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 38L, success = true))
        assertEquals(ConnectionQuality.EXCELLENT, engine.quality)

        // Good: 120ms
        val engineGood = PingEngine(targetProvider = { "example.com" })
        for (i in 0..5) {
            engineGood.recordProbe(PingProbeResult(rawRttMs = 120L, success = true))
        }
        assertEquals(ConnectionQuality.GOOD, engineGood.quality)

        // Moderate: 250ms
        val engineModerate = PingEngine(targetProvider = { "example.com" })
        for (i in 0..5) {
            engineModerate.recordProbe(PingProbeResult(rawRttMs = 250L, success = true))
        }
        assertEquals(ConnectionQuality.MODERATE, engineModerate.quality)
    }

    @Test
    fun testConsecutiveFailuresTriggersSelfHealing() {
        val selfHealingTriggered = AtomicBoolean(false)
        val engine = PingEngine(
            targetProvider = { "example.com" },
            onSelfHealingRequired = { selfHealingTriggered.set(true) }
        )

        // 1st failure
        engine.recordProbe(PingProbeResult(rawRttMs = -1L, success = false, failureType = FailureType.CONNECT_TIMEOUT))
        assertEquals(1, engine.currentSnapshot.consecutiveFailures)
        assertEquals(FailureType.CONNECT_TIMEOUT, engine.currentSnapshot.lastFailureType)
        assertTrue(!selfHealingTriggered.get())

        // 2nd failure
        engine.recordProbe(PingProbeResult(rawRttMs = -1L, success = false, failureType = FailureType.CONNECT_TIMEOUT))
        assertEquals(2, engine.currentSnapshot.consecutiveFailures)
        assertTrue(!selfHealingTriggered.get())

        // 3rd failure -> self healing triggered!
        engine.recordProbe(PingProbeResult(rawRttMs = -1L, success = false, failureType = FailureType.CONNECT_TIMEOUT))
        assertEquals(3, engine.currentSnapshot.consecutiveFailures)
        assertEquals(ConnectionQuality.OFFLINE, engine.quality)
        assertEquals(-1L, engine.smoothedPingMs)
        assertTrue(selfHealingTriggered.get(), "Self-healing should be triggered on 3rd failure")

        // 4th failure in same outage should NOT trigger callback again
        selfHealingTriggered.set(false)
        engine.recordProbe(PingProbeResult(rawRttMs = -1L, success = false, failureType = FailureType.CONNECT_TIMEOUT))
        assertTrue(!selfHealingTriggered.get(), "Self-healing should not re-trigger repeatedly during ongoing outage")

        // Recovery probe
        engine.recordProbe(PingProbeResult(rawRttMs = 50L, success = true))
        assertEquals(0, engine.currentSnapshot.consecutiveFailures)
        assertEquals(FailureType.NONE, engine.currentSnapshot.lastFailureType)
        assertEquals(50L, engine.smoothedPingMs)
    }

    @Test
    fun testMinRttTrackingAndBufferbloatCalculation() {
        val engine = PingEngine(targetProvider = { "example.com" })

        // Initial base probe: 30 ms
        engine.recordProbe(PingProbeResult(rawRttMs = 30L, success = true))
        assertEquals(30L, engine.currentSnapshot.minRttMs)
        assertEquals(0L, engine.currentSnapshot.bufferbloatMs)
        assertEquals("A+ (Идеально)", engine.currentSnapshot.bufferbloatGrade)

        // Consecutive slightly higher probes: 35ms, 40ms
        engine.recordProbe(PingProbeResult(rawRttMs = 35L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 40L, success = true))

        // minRttMs should remain 30ms
        assertEquals(30L, engine.currentSnapshot.minRttMs)
        assertTrue(engine.currentSnapshot.bufferbloatMs >= 0L)
        assertTrue(engine.currentSnapshot.bufferbloatGrade.startsWith("A"))

        // Heavy queue latency under load: 120ms
        engine.recordProbe(PingProbeResult(rawRttMs = 120L, success = true))
        assertEquals(30L, engine.currentSnapshot.minRttMs)
        assertTrue(engine.currentSnapshot.bufferbloatMs > 20L, "Bufferbloat delta should reflect queue delay")
    }

    @Test
    fun testAdaptiveAlphaScaling() {
        val engine = PingEngine(targetProvider = { "example.com" })

        // Initial probe
        engine.recordProbe(PingProbeResult(rawRttMs = 50L, success = true))

        // Stable subsequent probes -> rttvar settles and alpha drops to 0.125 for heavy noise filtering
        engine.recordProbe(PingProbeResult(rawRttMs = 50L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 50L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 50L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 51L, success = true))
        assertEquals(0.125, engine.currentSnapshot.currentAlpha, 0.001)

        // Sudden massive network shift / handover (+300% jump) -> alpha should adapt to 0.50 for instant convergence
        engine.recordProbe(PingProbeResult(rawRttMs = 250L, success = true))
        assertEquals(0.50, engine.currentSnapshot.currentAlpha, 0.001)
    }

    @Test
    fun testPredictiveDegradationTrendTrigger() {
        val engine = PingEngine(targetProvider = { "example.com" })
        val degradationDetected = AtomicBoolean(false)
        engine.onPredictiveDegradation = { _, currentRtt, minRtt ->
            if (currentRtt >= minRtt * 2) {
                degradationDetected.set(true)
            }
        }

        // Base stable latency: 40 ms
        engine.recordProbe(PingProbeResult(rawRttMs = 40L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 40L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 40L, success = true))
        assertEquals(40L, engine.currentSnapshot.minRttMs)
        assertFalse(engine.currentSnapshot.isDegradingTrend)

        // Monotonic escalating latency (40 -> 120 -> 250 -> 450)
        engine.recordProbe(PingProbeResult(rawRttMs = 120L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 250L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 450L, success = true))

        assertTrue(engine.currentSnapshot.isDegradingTrend, "Predictive degradation trend should be detected")
        assertTrue(degradationDetected.get(), "onPredictiveDegradation callback should be invoked")
    }

    @Test
    fun testSparklineHistoryCollection() {
        val engine = PingEngine(targetProvider = { "example.com" })

        // Record 5 probes
        engine.recordProbe(PingProbeResult(rawRttMs = 40L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 45L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = 50L, success = true))
        engine.recordProbe(PingProbeResult(rawRttMs = -1L, success = false, failureType = FailureType.CONNECT_TIMEOUT))
        engine.recordProbe(PingProbeResult(rawRttMs = 38L, success = true))

        val history = engine.currentSnapshot.rttHistory
        assertEquals(5, history.size)
        assertEquals(40L, history[0].rttMs)
        assertTrue(history[0].isSuccess)
        assertEquals(-1L, history[3].rttMs)
        assertFalse(history[3].isSuccess)
        assertEquals(38L, history[4].rttMs)
        assertTrue(history[4].isSuccess)

        // Reset clears history
        engine.reset()
        assertTrue(engine.currentSnapshot.rttHistory.isEmpty())
    }
}
