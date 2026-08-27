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
import org.junit.jupiter.api.Test

class AdaptiveHeartbeatTest {

    @Test
    fun testWifiHeartbeatIntervalWhenScreenOn() {
        val interval = AdaptiveHeartbeatEngine.calculateInterval(
            isMobile = false,
            isScreenOn = true,
            smoothedPingMs = 35L,
            jitterMs = 5L
        )
        assertEquals(45_000L, interval, "Wi-Fi with screen on should use 45s heartbeat")
    }

    @Test
    fun testWifiHeartbeatIntervalWhenScreenOff() {
        val interval = AdaptiveHeartbeatEngine.calculateInterval(
            isMobile = false,
            isScreenOn = false,
            smoothedPingMs = 35L,
            jitterMs = 5L
        )
        assertEquals(60_000L, interval, "Wi-Fi with screen off should use 60s heartbeat to save battery")
    }

    @Test
    fun testCellularHeartbeatIntervalNormal() {
        val interval = AdaptiveHeartbeatEngine.calculateInterval(
            isMobile = true,
            isScreenOn = true,
            smoothedPingMs = 80L,
            jitterMs = 15L
        )
        assertEquals(20_000L, interval, "Normal cellular LTE/5G should use 20s heartbeat against carrier CGNAT")
    }

    @Test
    fun testCellularHeartbeatIntervalDegradedHighPing() {
        val interval = AdaptiveHeartbeatEngine.calculateInterval(
            isMobile = true,
            isScreenOn = true,
            smoothedPingMs = 350L, // High latency
            jitterMs = 10L
        )
        assertEquals(15_000L, interval, "Cellular with high latency should use 15s heartbeat")
    }

    @Test
    fun testCellularHeartbeatIntervalDegradedHighJitter() {
        val interval = AdaptiveHeartbeatEngine.calculateInterval(
            isMobile = true,
            isScreenOn = true,
            smoothedPingMs = 100L,
            jitterMs = 80L // High jitter
        )
        assertEquals(15_000L, interval, "Cellular with high jitter should use 15s heartbeat")
    }
}
