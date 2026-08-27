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

class NetworkConditionEvaluatorTest {

    @Test
    fun testThroughputTierEscalation() {
        // 1. Eco Tier (< 150 KB/s)
        val eco = NetworkConditionEvaluator.evaluate(
            throughputBps = 80_000L,
            smoothedPingMs = 50L,
            minRttMs = 45L,
            jitterMs = 5L,
            successRatePercent = 100
        )
        assertEquals(2, eco.recommendedPoolSize)
        assertEquals(NetworkConditionEvaluator.BUFFER_SIZE_ECO, eco.recommendedBufferSizeBytes)
        assertTrue(eco.recommendedTcpNoDelay)
        assertEquals(ConnectionQuality.EXCELLENT, eco.connectionQuality)

        // 2. Balanced Tier (>= 150 KB/s)
        val balanced = NetworkConditionEvaluator.evaluate(
            throughputBps = 300_000L,
            smoothedPingMs = 60L,
            minRttMs = 50L,
            jitterMs = 8L,
            successRatePercent = 100
        )
        assertEquals(4, balanced.recommendedPoolSize)
        assertEquals(NetworkConditionEvaluator.BUFFER_SIZE_BALANCED, balanced.recommendedBufferSizeBytes)

        // 3. Turbo Tier (>= 1.5 MB/s)
        val turbo = NetworkConditionEvaluator.evaluate(
            throughputBps = 2_000_000L,
            smoothedPingMs = 70L,
            minRttMs = 55L,
            jitterMs = 10L,
            successRatePercent = 100
        )
        assertEquals(8, turbo.recommendedPoolSize)
        assertEquals(NetworkConditionEvaluator.BUFFER_SIZE_TURBO, turbo.recommendedBufferSizeBytes)

        // 4. Ultra Tier (>= 6 MB/s)
        val ultra = NetworkConditionEvaluator.evaluate(
            throughputBps = 8_000_000L,
            smoothedPingMs = 70L,
            minRttMs = 55L,
            jitterMs = 10L,
            successRatePercent = 100
        )
        assertEquals(16, ultra.recommendedPoolSize)
        assertEquals(NetworkConditionEvaluator.BUFFER_SIZE_ULTRA, ultra.recommendedBufferSizeBytes)
    }

    @Test
    fun testBufferbloatAndHighLatencyTriggersEcoFallback() {
        // High traffic 8 MB/s, but severe Bufferbloat (RTT 250ms vs Min 40ms -> Delta 210ms >= 150ms)
        val bloated = NetworkConditionEvaluator.evaluate(
            throughputBps = 8_000_000L,
            smoothedPingMs = 250L,
            minRttMs = 40L,
            jitterMs = 30L,
            successRatePercent = 100
        )
        assertEquals(2, bloated.recommendedPoolSize, "Bufferbloat congestion must fallback pool size to 2")
        assertEquals(NetworkConditionEvaluator.BUFFER_SIZE_ECO, bloated.recommendedBufferSizeBytes)
        assertTrue(bloated.isBufferbloatMitigationActive)

        // Severe latency (600ms >= 500ms threshold)
        val highLatency = NetworkConditionEvaluator.evaluate(
            throughputBps = 8_000_000L,
            smoothedPingMs = 600L,
            minRttMs = 550L,
            jitterMs = 40L,
            successRatePercent = 100
        )
        assertEquals(2, highLatency.recommendedPoolSize)
        assertTrue(highLatency.isBufferbloatMitigationActive)
    }

    @Test
    fun testTcpNoDelayRulesForVoipAndInteractive() {
        // 1. VoIP Priority: MOS = 4.20 -> TCP_NODELAY always true
        val voip = NetworkConditionEvaluator.evaluate(
            throughputBps = 1_000_000L,
            smoothedPingMs = 50L,
            minRttMs = 45L,
            jitterMs = 5L,
            successRatePercent = 100,
            mosScore = 4.20,
            isCallRecommended = true,
            isAutoSpeedPreset = true,
            baseTcpNoDelay = false
        )
        assertTrue(voip.recommendedTcpNoDelay, "VoIP calls must have TCP_NODELAY = true for minimal latency")
        assertTrue(voip.isInteractiveVoipActive)

        // 2. Interactive Low Throughput: throughput < 500 KB/s -> TCP_NODELAY always true
        val interactive = NetworkConditionEvaluator.evaluate(
            throughputBps = 100_000L,
            smoothedPingMs = 50L,
            minRttMs = 45L,
            jitterMs = 5L,
            successRatePercent = 100,
            mosScore = 2.0,
            isCallRecommended = false,
            isAutoSpeedPreset = true,
            baseTcpNoDelay = false
        )
        assertTrue(interactive.recommendedTcpNoDelay, "Interactive small packets require TCP_NODELAY = true")

        // 3. Bulk Download without VoIP -> uses baseTcpNoDelay
        val bulk = NetworkConditionEvaluator.evaluate(
            throughputBps = 8_000_000L,
            smoothedPingMs = 50L,
            minRttMs = 45L,
            jitterMs = 5L,
            successRatePercent = 100,
            mosScore = 2.0,
            isCallRecommended = false,
            isAutoSpeedPreset = true,
            baseTcpNoDelay = false
        )
        assertFalse(bulk.recommendedTcpNoDelay, "Bulk download with baseTcpNoDelay=false should respect config")
    }

    @Test
    fun testQoSThermalThrottlingClamping() {
        // Throughput warrants Ultra (16 sockets), but Severe Thermal QoS clamps to 2 sockets (Eco)
        val severe = NetworkConditionEvaluator.evaluate(
            throughputBps = 8_000_000L,
            smoothedPingMs = 40L,
            minRttMs = 35L,
            jitterMs = 5L,
            successRatePercent = 100,
            qosThrottleLevel = QoSThrottleLevel.SEVERE
        )
        assertEquals(2, severe.recommendedPoolSize)
        assertEquals(NetworkConditionEvaluator.BUFFER_SIZE_ECO, severe.recommendedBufferSizeBytes)

        // Throughput warrants Ultra (16 sockets), but Moderate Thermal QoS clamps to 4 sockets (Balanced)
        val moderate = NetworkConditionEvaluator.evaluate(
            throughputBps = 8_000_000L,
            smoothedPingMs = 40L,
            minRttMs = 35L,
            jitterMs = 5L,
            successRatePercent = 100,
            qosThrottleLevel = QoSThrottleLevel.MODERATE
        )
        assertEquals(4, moderate.recommendedPoolSize)
        assertEquals(NetworkConditionEvaluator.BUFFER_SIZE_BALANCED, moderate.recommendedBufferSizeBytes)
    }

    @Test
    fun testConnectionQualityClassification() {
        // EXCELLENT
        assertEquals(
            ConnectionQuality.EXCELLENT,
            NetworkConditionEvaluator.evaluateConnectionQuality(smoothedPingMs = 50L, jitterMs = 15L, consecutiveFailures = 0, successRatePercent = 100)
        )

        // GOOD
        assertEquals(
            ConnectionQuality.GOOD,
            NetworkConditionEvaluator.evaluateConnectionQuality(smoothedPingMs = 150L, jitterMs = 45L, consecutiveFailures = 0, successRatePercent = 90)
        )

        // MODERATE
        assertEquals(
            ConnectionQuality.MODERATE,
            NetworkConditionEvaluator.evaluateConnectionQuality(smoothedPingMs = 300L, jitterMs = 45L, consecutiveFailures = 0, successRatePercent = 70)
        )

        // POOR
        assertEquals(
            ConnectionQuality.POOR,
            NetworkConditionEvaluator.evaluateConnectionQuality(smoothedPingMs = 450L, jitterMs = 120L, consecutiveFailures = 0, successRatePercent = 50)
        )

        // OFFLINE on 3 failures
        assertEquals(
            ConnectionQuality.OFFLINE,
            NetworkConditionEvaluator.evaluateConnectionQuality(smoothedPingMs = 50L, jitterMs = 10L, consecutiveFailures = 3, successRatePercent = 100)
        )
    }
}
