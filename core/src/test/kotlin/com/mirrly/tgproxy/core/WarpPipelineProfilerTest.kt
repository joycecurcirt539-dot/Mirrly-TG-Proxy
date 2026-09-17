/*
 * Mirrly TG Proxy - WARP Pipeline Profiler Test (Task 04)
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
import org.junit.jupiter.api.Test

class WarpPipelineProfilerTest {

    @AfterEach
    fun tearDown() {
        WarpPipelineProfiler.clear()
    }

    @Test
    fun testRecordAndRetrieveMetrics() {
        assertNull(WarpPipelineProfiler.getLatestMetrics())

        val metrics = WarpProfileMetrics(
            tConfigMs = 15L,
            tDnsMs = 120L,
            tHandshakeMs = 450L,
            tTunnelMs = 210L,
            tTotalMs = 795L,
            isSuccess = true
        )

        WarpPipelineProfiler.recordMetrics(metrics)

        val retrieved = WarpPipelineProfiler.getLatestMetrics()
        assertNotNull(retrieved)
        assertEquals(15L, retrieved?.tConfigMs)
        assertEquals(120L, retrieved?.tDnsMs)
        assertEquals(450L, retrieved?.tHandshakeMs)
        assertEquals(210L, retrieved?.tTunnelMs)
        assertEquals(795L, retrieved?.tTotalMs)
        assertTrue(retrieved?.isSuccess == true)
    }

    @Test
    fun testFormatSummarySuccess() {
        val metrics = WarpProfileMetrics(
            tConfigMs = 12L,
            tDnsMs = 140L,
            tObfuscationMs = 5L,
            tHandshakeMs = 980L,
            tTunnelMs = 288L,
            tTotalMs = 1425L,
            isSuccess = true
        )

        val summary = metrics.formatSummary()
        assertTrue(summary.contains("WARP запущен за 1425 мс"))
        assertTrue(summary.contains("Конфиг: 12 мс"))
        assertTrue(summary.contains("DNS: 140 мс"))
        assertTrue(summary.contains("Обфускация: 5 мс"))
        assertTrue(summary.contains("Handshake: 980 мс"))
        assertTrue(summary.contains("Туннель: 288 мс"))
    }

    @Test
    fun testFormatSummaryFailure() {
        val metrics = WarpProfileMetrics(
            tConfigMs = 10L,
            tDnsMs = 50L,
            tHandshakeMs = 1200L,
            tTunnelMs = 0L,
            tTotalMs = 1260L,
            failurePhase = WarpPhase.HANDSHAKE_EXCHANGE,
            isSuccess = false,
            errorDetail = "Таймаут рукопожатия WireGuard"
        )

        val summary = metrics.formatSummary()
        assertTrue(summary.contains("Сбой запуска WARP на этапе Рукопожатие"))
        assertTrue(summary.contains("Таймаут рукопожатия WireGuard"))
    }

    @Test
    fun testMeasurePhaseDuration() {
        val (res, durationMs) = WarpPipelineProfiler.measurePhase {
            Thread.sleep(50)
            42
        }

        assertEquals(42, res)
        assertTrue(durationMs >= 40L, "Measured duration should be at least ~40ms, got $durationMs")
    }

    @Test
    fun testClearMetrics() {
        val metrics = WarpProfileMetrics(tTotalMs = 100L, isSuccess = true)
        WarpPipelineProfiler.recordMetrics(metrics)
        assertNotNull(WarpPipelineProfiler.getLatestMetrics())

        WarpPipelineProfiler.clear()
        assertNull(WarpPipelineProfiler.getLatestMetrics())
    }

    @Test
    fun testProfileWarpPipelineInvalidConfigFailsEarly() = runBlocking {
        val emptyConfig = ProxyConfig(
            warpPrivateKey = "",
            warpToken = ""
        )

        val metrics = WarpPipelineProfiler.profileWarpPipeline(emptyConfig, "162.159.192.1:2408")

        assertFalse(metrics.isSuccess)
        assertEquals(WarpPhase.CONFIG_ACQUISITION, metrics.failurePhase)
        assertNotNull(metrics.errorDetail)
        assertEquals(metrics, WarpPipelineProfiler.getLatestMetrics())
    }
}
