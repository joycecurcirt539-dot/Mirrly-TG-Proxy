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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PredictivePreWarmTest {

    private fun shouldPreWarm(
        isProxyRunning: Boolean,
        isPowerSaveMode: Boolean,
        currentTimeMs: Long,
        lastWarmTimeMs: Long,
        minCooldownMs: Long = 30_000L
    ): Boolean {
        if (!isProxyRunning) return false
        if (isPowerSaveMode) return false
        return (currentTimeMs - lastWarmTimeMs) >= minCooldownMs
    }

    @Test
    fun testShouldPreWarmWhenRunningAndCooldownElapsed() {
        val now = 100_000L
        val last = 60_000L // 40s ago (> 30s)
        val result = shouldPreWarm(
            isProxyRunning = true,
            isPowerSaveMode = false,
            currentTimeMs = now,
            lastWarmTimeMs = last
        )
        assertTrue(result)
    }

    @Test
    fun testShouldNotPreWarmWhenProxyStopped() {
        val now = 100_000L
        val last = 50_000L
        val result = shouldPreWarm(
            isProxyRunning = false,
            isPowerSaveMode = false,
            currentTimeMs = now,
            lastWarmTimeMs = last
        )
        assertFalse(result)
    }

    @Test
    fun testShouldNotPreWarmWhenPowerSaveActive() {
        val now = 100_000L
        val last = 50_000L
        val result = shouldPreWarm(
            isProxyRunning = true,
            isPowerSaveMode = true,
            currentTimeMs = now,
            lastWarmTimeMs = last
        )
        assertFalse(result)
    }

    @Test
    fun testShouldNotPreWarmDuringDebounceCooldown() {
        val now = 100_000L
        val last = 85_000L // 15s ago (< 30s cooldown)
        val result = shouldPreWarm(
            isProxyRunning = true,
            isPowerSaveMode = false,
            currentTimeMs = now,
            lastWarmTimeMs = last
        )
        assertFalse(result)
    }
}
