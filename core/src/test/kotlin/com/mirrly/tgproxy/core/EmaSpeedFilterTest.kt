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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EmaSpeedFilterTest {

    private lateinit var filter: EmaSpeedFilter

    @BeforeEach
    fun setUp() {
        filter = EmaSpeedFilter(defaultAlpha = 0.30, cutoffBps = 1024.0)
    }

    @Test
    fun testInitialSampleSetsBaseline() {
        val s1 = filter.update(1_000_000L) // 1 MB/s
        assertEquals(1_000_000L, s1)
        assertEquals(1_000_000L, filter.currentSmoothedSpeed)
    }

    @Test
    fun testSmoothsBurstTrafficSpike() {
        filter.update(1_000_000L)
        filter.update(1_000_000L)

        // Всплеск до 5 МБ/с (raw = 5_000_000)
        // При raw > 2.5x EMA: alpha = 0.65 -> 0.35 * 1_000_000 + 0.65 * 5_000_000 = 350_000 + 3_250_000 = 3_600_000
        val sSpike = filter.update(5_000_000L)
        assertEquals(3_600_000L, sSpike)
        assertTrue(sSpike < 5_000_000L, "Сглаженная скорость должна быть ниже сырого пика всплеска")

        // Следующий замер возвращается к 1 МБ/с: alpha = 0.30 -> 0.70 * 3_600_000 + 0.30 * 1_000_000 = 2_520_000 + 300_000 = 2_820_000
        val sDown = filter.update(1_000_000L)
        assertEquals(2_820_000L, sDown)
    }

    @Test
    fun testZeroCutoffAvoidsLingeringGhostSpeed() {
        filter.update(100_000L) // 100 KB/s

        // При отсутствии трафика скорость должна быстро затухать и отсекаться до 0
        filter.update(0L) // 50_000
        filter.update(0L) // 25_000
        filter.update(0L) // 12_500
        filter.update(0L) // 6_250
        filter.update(0L) // 3_125
        filter.update(0L) // 1_562.5
        val finalSpeed = filter.update(0L) // 781.25 < cutoff 1024 -> 0L

        assertEquals(0L, finalSpeed, "Остаточная скорость должна быть отсечена строго до 0L")
        assertEquals(0L, filter.currentSmoothedSpeed)
    }

    @Test
    fun testFilterReset() {
        filter.update(5_000_000L)
        assertEquals(5_000_000L, filter.currentSmoothedSpeed)

        filter.reset()
        assertEquals(0L, filter.currentSmoothedSpeed)

        val newSample = filter.update(200_000L)
        assertEquals(200_000L, newSample, "После сброса первый замер должен стать новой базой")
    }

    @Test
    fun testProxyStatsRawAndSmoothedSeparation() {
        val stats = ProxyStats()
        stats.updateRawBytes(0L, 0L)
        Thread.sleep(350)
        stats.updateSpeed() // Initializes lastBytesRecv = 0L

        // Симулируем приход 1 МБ данных
        stats.updateRawBytes(1_048_576L, 0L)
        Thread.sleep(350)
        stats.updateSpeed() // Computes speed delta

        assertTrue(stats.rawDownloadSpeedBps > 0L)
        assertTrue(stats.downloadSpeedBps > 0L)
        assertTrue(stats.peakDownloadSpeedBps >= stats.rawDownloadSpeedBps)
    }
}
