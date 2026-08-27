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

class WorkerFailoverTest {

    @Test
    fun testCircuitBreakerTransitionsToOpenOn429() {
        val record = WorkerCircuitRecord(workerId = "w1", domain = "worker1.example.com")
        assertEquals(CircuitState.CLOSED, record.state)
        assertTrue(record.isAvailableForRouting)

        // Single 429 immediately breaks circuit (5-min cooldown)
        record.recordFailure(FailureType.RATE_LIMITED_429)
        assertEquals(CircuitState.OPEN, record.state)
        assertFalse(record.isAvailableForRouting)
        assertTrue(record.remainingCooldownSeconds > 250L)
    }

    @Test
    fun testCircuitBreakerTransitionsToOpenOnConsecutiveTimeouts() {
        val record = WorkerCircuitRecord(workerId = "w1", domain = "worker1.example.com")

        // 1st failure: still closed (warning)
        record.recordFailure(FailureType.CONNECT_TIMEOUT)
        assertEquals(CircuitState.CLOSED, record.state)
        assertEquals(1, record.consecutiveFailures)

        // 2nd failure: transitions to OPEN
        record.recordFailure(FailureType.CONNECT_TIMEOUT)
        assertEquals(CircuitState.OPEN, record.state)
        assertEquals(2, record.consecutiveFailures)
        assertFalse(record.isAvailableForRouting)
    }

    @Test
    fun testCooldownExpirationTransitionsToHalfOpen() {
        val record = WorkerCircuitRecord(workerId = "w1", domain = "worker1.example.com")
        record.recordFailure(FailureType.RATE_LIMITED_429, customCooldownMs = 10L)
        assertEquals(CircuitState.OPEN, record.state)

        // Wait for 20ms cooldown expiration
        Thread.sleep(25)

        val expired = record.checkCooldownExpiration()
        assertTrue(expired)
        assertEquals(CircuitState.HALF_OPEN, record.state)
        assertTrue(record.isAvailableForRouting)

        // On success in HALF_OPEN -> returns to CLOSED
        record.recordSuccess(45L)
        assertEquals(CircuitState.CLOSED, record.state)
        assertEquals(0, record.consecutiveFailures)
    }

    @Test
    fun testQualityScoreRanking() {
        val fastWorker = WorkerCircuitRecord(
            workerId = "w_fast",
            domain = "fast.example.com",
            state = CircuitState.CLOSED,
            lastProbeRttMs = 35L,
            jitterMs = 5L,
            successCount = 10,
            totalProbes = 10
        )

        val slowWorker = WorkerCircuitRecord(
            workerId = "w_slow",
            domain = "slow.example.com",
            state = CircuitState.CLOSED,
            lastProbeRttMs = 300L,
            jitterMs = 60L,
            successCount = 8,
            totalProbes = 10
        )

        val brokenWorker = WorkerCircuitRecord(
            workerId = "w_broken",
            domain = "broken.example.com",
            state = CircuitState.OPEN
        )

        val fastScore = fastWorker.computeQualityScore()
        val slowScore = slowWorker.computeQualityScore()
        val brokenScore = brokenWorker.computeQualityScore()

        assertTrue(fastScore > slowScore, "Fast worker ($fastScore) should have higher score than slow worker ($slowScore)")
        assertEquals(-1.0, brokenScore, "Broken worker in OPEN state should have score -1.0")
    }

    @Test
    fun testPredictiveDegradationTransitionsToOpenWith45sCooldown() {
        val record = WorkerCircuitRecord(workerId = "w_degrading", domain = "degrading.example.com")
        assertEquals(CircuitState.CLOSED, record.state)

        record.recordFailure(FailureType.PREDICTIVE_DEGRADATION)
        assertEquals(CircuitState.OPEN, record.state)
        assertFalse(record.isAvailableForRouting)
        assertTrue(record.remainingCooldownSeconds in 40L..46L, "Predictive degradation should have ~45s soft cooldown")
    }
}
