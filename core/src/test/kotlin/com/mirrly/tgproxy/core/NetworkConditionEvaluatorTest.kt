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

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkConditionEvaluatorTest {
    private val readyWifi = NetworkProfile(
        generation = 7L,
        validated = true,
        suspended = false,
        transport = NetworkTransport.WIFI,
        wifi = true,
        estimatedDownKbps = 100_000,
        estimatedUpKbps = 20_000
    ).normalized()

    private fun snapshot(
        atMs: Long,
        throughputBps: Long = 0L,
        pingMs: Long = 80L,
        minRttMs: Long = 60L,
        jitterMs: Long = 10L,
        successRate: Int = 100,
        profile: NetworkProfile = readyWifi,
        mode: TcpNoDelayMode = TcpNoDelayMode.AUTO,
        qos: QoSThrottleLevel = QoSThrottleLevel.NONE
    ) = AdaptivePolicySnapshot(
        observedAtMs = atMs,
        networkProfile = profile,
        throughputBps = throughputBps,
        smoothedPingMs = pingMs,
        minRttMs = minRttMs,
        jitterMs = jitterMs,
        successRatePercent = successRate,
        qosThrottleLevel = qos,
        tcpNoDelayMode = mode
    )

    @Test
    fun `the same snapshot returns the same effective decision`() {
        val controller = AdaptiveNetworkPolicyController(initialPoolSize = 2)
        val sample = snapshot(atMs = 1_000L)

        val first = controller.evaluate(sample)
        val duplicate = controller.evaluate(sample)

        assertEquals(first, duplicate)
        assertEquals(1L, duplicate.revision)
        assertTrue(duplicate.recommendedTcpNoDelay)
        assertEquals(AdaptiveTrafficClass.INTERACTIVE_CHAT, duplicate.trafficClass)
    }

    @Test
    fun `repeated unavailable snapshots do not create policy revisions`() {
        val controller = AdaptiveNetworkPolicyController(initialPoolSize = 4)
        val unavailable = NetworkProfile()

        val first = controller.evaluate(snapshot(atMs = 0L, profile = unavailable))
        for (second in 1L..30L) {
            controller.evaluate(snapshot(atMs = second * 1_000L, profile = unavailable))
        }

        assertEquals(1L, first.revision)
        assertEquals(first.revision, controller.currentDecision.revision)
        assertEquals(AdaptiveNetworkState.UNAVAILABLE, controller.currentDecision.networkState)
        assertFalse(controller.currentDecision.recommendedTcpNoDelay)
    }

    @Test
    fun `one throughput spike cannot switch interactive traffic to media`() {
        val controller = AdaptiveNetworkPolicyController(initialPoolSize = 4)
        val initial = controller.evaluate(snapshot(atMs = 0L, throughputBps = 0L))

        val spike = controller.evaluate(snapshot(atMs = 1_000L, throughputBps = 8_000_000L))

        assertEquals(AdaptiveTrafficClass.INTERACTIVE_CHAT, spike.trafficClass)
        assertTrue(spike.recommendedTcpNoDelay)
        assertEquals(initial.recommendedPoolSize, spike.recommendedPoolSize)
    }

    @Test
    fun `sustained media is confirmed and applied only after dwell and cooldown`() {
        val controller = AdaptiveNetworkPolicyController(initialPoolSize = 4)
        val initial = controller.evaluate(snapshot(atMs = 0L))
        assertTrue(initial.recommendedTcpNoDelay)

        for (second in 1L..29L) {
            val decision = controller.evaluate(
                snapshot(atMs = second * 1_000L, throughputBps = 8_000_000L)
            )
            assertTrue(decision.recommendedTcpNoDelay, "cooldown must hold through second $second")
        }

        val media = controller.evaluate(snapshot(atMs = 30_000L, throughputBps = 8_000_000L))
        assertEquals(AdaptiveTrafficClass.SUSTAINED_MEDIA, media.trafficClass)
        assertFalse(media.recommendedTcpNoDelay)
        assertTrue(media.recommendedPoolSize >= 3)
        assertEquals(2L, media.revision)
    }

    @Test
    fun `transient rtt spike under 5 seconds does not trigger degraded`() {
        val controller = AdaptiveNetworkPolicyController(initialPoolSize = 2)
        controller.evaluate(snapshot(atMs = 0L, pingMs = 80L, minRttMs = 60L))
        controller.evaluate(snapshot(atMs = 30_000L, pingMs = 80L, minRttMs = 60L))

        // Всплеск RTT до 450 мс на 4 секунды (меньше 7 секунд окна наблюдения)
        for (second in 31L..34L) {
            controller.evaluate(snapshot(atMs = second * 1_000L, pingMs = 450L, minRttMs = 60L))
        }
        // Возврат к нормальному RTT
        val recovered = controller.evaluate(snapshot(atMs = 35_000L, pingMs = 80L, minRttMs = 60L))

        assertEquals(AdaptiveNetworkState.NORMAL, recovered.networkState)
        assertTrue(recovered.recommendedTcpNoDelay)
        assertEquals(1L, recovered.revision, "Кратковременный всплеск не должен вызывать переключения состояния")
    }

    @Test
    fun `sustained rtt degradation over 7 seconds triggers degraded`() {
        val controller = AdaptiveNetworkPolicyController(initialPoolSize = 2)
        controller.evaluate(snapshot(atMs = 0L, pingMs = 80L, minRttMs = 60L))
        controller.evaluate(snapshot(atMs = 30_000L, pingMs = 80L, minRttMs = 60L))

        // Устойчивая деградация: RTT 400 мс на протяжении 8 секунд (>= 7с dwell time и 5 семплов)
        for (second in 31L..38L) {
            controller.evaluate(snapshot(atMs = second * 1_000L, pingMs = 400L, minRttMs = 60L))
        }

        val degraded = controller.currentDecision
        assertEquals(AdaptiveNetworkState.DEGRADED, degraded.networkState)
        assertFalse(degraded.recommendedTcpNoDelay)
        assertEquals(2L, degraded.revision)
    }

    @Test
    fun `rtt hysteresis and candidate dwell prevent boundary flapping`() {
        val controller = AdaptiveNetworkPolicyController(initialPoolSize = 2)
        controller.evaluate(snapshot(atMs = 0L, pingMs = 140L, minRttMs = 120L))

        for (second in 1L..30L) {
            val ping = if (second % 2L == 0L) 355L else 345L
            controller.evaluate(snapshot(atMs = second * 1_000L, pingMs = ping, minRttMs = 160L))
        }
        val congested = controller.currentDecision
        assertEquals(AdaptiveNetworkState.DEGRADED, congested.networkState)
        assertFalse(congested.recommendedTcpNoDelay)
        assertEquals(2L, congested.revision, "enter threshold may cause one transition, never flapping")

        for (second in 31L..60L) {
            val ping = if (second % 2L == 0L) 355L else 195L
            controller.evaluate(snapshot(atMs = second * 1_000L, pingMs = ping, minRttMs = 120L))
        }
        assertEquals(2L, controller.currentDecision.revision)
        assertEquals(AdaptiveNetworkState.DEGRADED, controller.currentDecision.networkState)
    }

    @Test
    fun `congestion exits only at the lower recovery threshold`() {
        val controller = AdaptiveNetworkPolicyController(initialPoolSize = 2)
        controller.evaluate(snapshot(atMs = 0L, pingMs = 80L, minRttMs = 60L))
        for (second in 1L..30L) {
            controller.evaluate(snapshot(atMs = second * 1_000L, pingMs = 380L, minRttMs = 160L))
        }
        assertEquals(AdaptiveNetworkState.DEGRADED, controller.currentDecision.networkState)

        // RTT снизился до 210 мс (выше порога выхода 200 мс)
        for (second in 31L..60L) {
            controller.evaluate(snapshot(atMs = second * 1_000L, pingMs = 210L, minRttMs = 120L))
        }
        assertEquals(AdaptiveNetworkState.DEGRADED, controller.currentDecision.networkState)

        // RTT снизился до 180 мс (ниже 200 мс) и кулдаун 30с истёк -> вход в RECOVERING
        for (second in 61L..75L) {
            controller.evaluate(snapshot(atMs = second * 1_000L, pingMs = 180L, minRttMs = 120L))
        }
        assertEquals(AdaptiveNetworkState.RECOVERING, controller.currentDecision.networkState)
    }

    @Test
    fun `explicit tcp mode bypasses adaptive cooldown`() {
        val controller = AdaptiveNetworkPolicyController(initialPoolSize = 2)
        controller.evaluate(snapshot(atMs = 0L))

        val off = controller.evaluate(snapshot(atMs = 1_000L, mode = TcpNoDelayMode.OFF), force = true)
        val on = controller.evaluate(snapshot(atMs = 1_001L, mode = TcpNoDelayMode.ON), force = true)

        assertFalse(off.recommendedTcpNoDelay)
        assertTrue(on.recommendedTcpNoDelay)
        assertEquals(3L, on.revision)
    }

    @Test
    fun `thermal qos clamp is part of the same effective decision`() {
        val controller = AdaptiveNetworkPolicyController(initialPoolSize = 4)
        val severe = controller.evaluate(
            snapshot(
                atMs = 0L,
                throughputBps = 8_000_000L,
                qos = QoSThrottleLevel.SEVERE
            ),
            force = true
        )

        assertEquals(1, severe.recommendedPoolSize)
        assertEquals(AdaptiveNetworkPolicyController.BUFFER_SIZE_ECO, severe.recommendedBufferSizeBytes)
    }

    @Test
    fun `quality classifier remains diagnostic and deterministic`() {
        assertEquals(ConnectionQuality.EXCELLENT, NetworkQualityClassifier.evaluate(50L, 15L, 0, 100))
        assertEquals(ConnectionQuality.GOOD, NetworkQualityClassifier.evaluate(150L, 45L, 0, 90))
        assertEquals(ConnectionQuality.MODERATE, NetworkQualityClassifier.evaluate(300L, 45L, 0, 70))
        assertEquals(ConnectionQuality.POOR, NetworkQualityClassifier.evaluate(450L, 120L, 0, 50))
        assertEquals(ConnectionQuality.OFFLINE, NetworkQualityClassifier.evaluate(50L, 10L, 3, 100))
    }

    @Test
    fun `service and ui consume the core decision instead of evaluating policy`() {
        val serviceSource = File(
            "..",
            "app/src/main/java/com/mirrly/tgproxy/service/ProxyForegroundService.kt"
        ).canonicalFile.readText()
        val uiSource = File(
            "..",
            "app/src/main/java/com/mirrly/tgproxy/ui/SettingsScreen.kt"
        ).canonicalFile.readText()
        val duplicateEvaluator = File(
            "..",
            "app/src/main/java/com/mirrly/tgproxy/service/NetworkConditionEvaluator.kt"
        ).canonicalFile

        assertFalse(duplicateEvaluator.exists())
        assertFalse(serviceSource.contains("NetworkConditionEvaluator.evaluate"))
        assertFalse(uiSource.contains("NetworkConditionEvaluator.evaluate"))
        assertTrue(uiSource.contains("server.adaptiveNetworkDecision.collectAsState()"))
        assertTrue(uiSource.contains("server.setTcpNoDelayMode(mode)"))
    }
}
