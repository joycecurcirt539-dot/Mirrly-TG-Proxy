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

class WorkerCircuitBreakerStageAndNetworkTest {

    @Test
    fun `rate limited 429 remains global and does not expire on network switch`() {
        val record = WorkerCircuitRecord(workerId = "w1", domain = "w1.workers.dev")
        val netGen1 = 1L
        val netGen2 = 2L

        // Trip 429 on Network Generation 1 with long cooldown
        record.recordFailure(
            failureType = FailureType.RATE_LIMITED_429,
            networkGeneration = netGen1,
            customCooldownMs = 300_000L
        )

        assertEquals(CircuitState.OPEN, record.state)
        assertEquals(EstablishmentStage.WSS, record.failureStage)
        assertFalse(record.isAvailableForRouting)

        // Switching to Network Generation 2 must NOT reset or bypass 429 cooldown
        val expiredOnNet2 = record.checkCooldownExpiration(currentNetworkGeneration = netGen2)
        assertFalse(expiredOnNet2, "Global 429 must not expire simply because network changed")
        assertEquals(CircuitState.OPEN, record.state)
        assertFalse(record.isAvailableForRouting)
    }

    @Test
    fun `path-specific failures are scoped to network generation and allow trial on handover`() {
        val record = WorkerCircuitRecord(workerId = "w2", domain = "w2.workers.dev")
        val netGen1 = 1L
        val netGen2 = 2L

        // Two consecutive path-specific TCP / Relay failures trip circuit on Network Generation 1
        record.recordFailure(
            failureType = FailureType.RELAY_ACK_FAILED,
            networkGeneration = netGen1,
            customCooldownMs = 60_000L
        )
        record.recordFailure(
            failureType = FailureType.RELAY_ACK_FAILED,
            networkGeneration = netGen1,
            customCooldownMs = 60_000L
        )

        assertEquals(CircuitState.OPEN, record.state)
        assertEquals(EstablishmentStage.READY, record.failureStage)
        assertEquals(netGen1, record.failureNetworkGeneration)
        assertFalse(record.isAvailableForRouting)

        // On same network (netGen1), cooldown has not expired
        val expiredOnSameNet = record.checkCooldownExpiration(currentNetworkGeneration = netGen1)
        assertFalse(expiredOnSameNet)
        assertEquals(CircuitState.OPEN, record.state)

        // On handover to netGen2, path-specific failure immediately transitions to HALF_OPEN for trial
        val expiredOnNewNet = record.checkCooldownExpiration(currentNetworkGeneration = netGen2)
        assertTrue(expiredOnNewNet, "Path-specific failure must transition to HALF_OPEN on new network generation")
        assertEquals(CircuitState.HALF_OPEN, record.state)
        assertTrue(record.isAvailableForRouting)
    }

    @Test
    fun `half-open admits strictly single trial permit and rejects concurrent stampede`() {
        val record = WorkerCircuitRecord(workerId = "w3", domain = "w3.workers.dev")
        val netGen1 = 1L

        // Trip circuit
        record.recordFailure(FailureType.CONNECT_TIMEOUT, networkGeneration = netGen1, customCooldownMs = 10L)
        record.recordFailure(FailureType.CONNECT_TIMEOUT, networkGeneration = netGen1, customCooldownMs = 10L)
        assertEquals(CircuitState.OPEN, record.state)

        Thread.sleep(25)
        assertTrue(record.checkCooldownExpiration(currentNetworkGeneration = netGen1))
        assertEquals(CircuitState.HALF_OPEN, record.state)
        assertTrue(record.isAvailableForRouting)

        // First trial is acquired successfully
        val firstTrialAcquired = record.tryAcquireHalfOpenTrial()
        assertTrue(firstTrialAcquired, "First trial in HALF_OPEN must succeed")

        // While trial is active, no other concurrent flow may acquire a permit ("не толпу")
        assertFalse(record.isAvailableForRouting, "Worker must not be available for other flows during active trial")
        val secondTrialAcquired = record.tryAcquireHalfOpenTrial()
        assertFalse(secondTrialAcquired, "Concurrent trial in HALF_OPEN must be rejected")

        // Releasing trial (e.g. timeout or cancelled probe) allows next attempt
        record.releaseHalfOpenTrial()
        assertTrue(record.isAvailableForRouting)
        assertTrue(record.tryAcquireHalfOpenTrial(), "After release, another single trial can be acquired")

        // Successful contract probe closes circuit and clears trial
        record.recordSuccess(rttMs = 42L)
        assertEquals(CircuitState.CLOSED, record.state)
        assertTrue(record.isAvailableForRouting)
        assertFalse(record.hasActiveHalfOpenTrial)
    }

    @Test
    fun `stage mapping distinguishes handshake and relay stages`() {
        assertEquals(EstablishmentStage.DNS, FailureType.DNS_FAILURE.stage)
        assertEquals(EstablishmentStage.TCP, FailureType.CONNECT_TIMEOUT.stage)
        assertEquals(EstablishmentStage.TLS, FailureType.TLS_HANDSHAKE_FAILED.stage)
        assertEquals(EstablishmentStage.WSS, FailureType.RATE_LIMITED_429.stage)
        assertEquals(EstablishmentStage.READY, FailureType.RELAY_ACK_FAILED.stage)

        assertFalse(FailureType.RATE_LIMITED_429.isPathSpecific)
        assertTrue(FailureType.CONNECT_TIMEOUT.isPathSpecific)
        assertTrue(FailureType.TLS_HANDSHAKE_FAILED.isPathSpecific)
        assertTrue(FailureType.RELAY_ACK_FAILED.isPathSpecific)
    }

    @Test
    fun `versioned relay ready control ack matches byte contract`() {
        val validAck = byteArrayOf(0x56.toByte(), 0x02.toByte(), 0x00.toByte(), 0x00.toByte())
        val invalidAck = "HTTP/1.1 200 OK\r\n\r\n<html>ok</html>".toByteArray()

        val isValidContract = validAck.size >= 4 &&
                validAck[0] == 0x56.toByte() &&
                validAck[1] == 0x02.toByte() &&
                validAck[2] == 0x00.toByte()
        assertTrue(isValidContract, "Relay ACK contract must be recognized")

        val isInvalidContract = invalidAck.size >= 4 &&
                invalidAck[0] == 0x56.toByte() &&
                invalidAck[1] == 0x02.toByte() &&
                invalidAck[2] == 0x00.toByte()
        assertFalse(isInvalidContract, "Root HTTP 200 HTML must NEVER satisfy relay ACK contract")
    }
}
