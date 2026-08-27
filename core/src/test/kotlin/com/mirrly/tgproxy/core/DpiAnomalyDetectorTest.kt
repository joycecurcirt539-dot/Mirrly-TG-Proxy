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
import org.junit.jupiter.api.Test
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class DpiAnomalyDetectorTest {

    @Test
    fun testClassifyTcpResetAsDpiBlocked() {
        val e1 = SocketException("recvfrom failed: ECONNRESET (Connection reset by peer)")
        assertEquals(FailureType.DPI_BLOCKED, DpiAnomalyDetector.classifyException(e1))

        val e2 = SocketException("Software caused connection abort")
        assertEquals(FailureType.DPI_BLOCKED, DpiAnomalyDetector.classifyException(e2))

        val e3 = SocketException("Broken pipe")
        assertEquals(FailureType.DPI_BLOCKED, DpiAnomalyDetector.classifyException(e3))

        assertTrue(DpiAnomalyDetector.isDpiOrCensorship(FailureType.DPI_BLOCKED))
    }

    @Test
    fun testClassifyTlsHandshakeAbortAsTlsBlocked() {
        val e1 = SSLHandshakeException("bad_record_mac")
        assertEquals(FailureType.TLS_HANDSHAKE_FAILED, DpiAnomalyDetector.classifyException(e1))

        val e2 = SSLPeerUnverifiedException("Hostname example.workers.dev not verified")
        assertEquals(FailureType.TLS_HANDSHAKE_FAILED, DpiAnomalyDetector.classifyException(e2))

        val e3 = CertificateException("Trust anchor for certification path not found.")
        assertEquals(FailureType.TLS_HANDSHAKE_FAILED, DpiAnomalyDetector.classifyException(e3))

        assertTrue(DpiAnomalyDetector.isDpiOrCensorship(FailureType.TLS_HANDSHAKE_FAILED))
    }

    @Test
    fun testClassifyStandardNetworkExceptions() {
        val dnsEx = UnknownHostException("Unable to resolve host \"test.com\": No address associated with hostname")
        assertEquals(FailureType.DNS_FAILURE, DpiAnomalyDetector.classifyException(dnsEx))

        val timeoutEx = SocketTimeoutException("failed to connect to /104.21.5.8 within 2500ms")
        assertEquals(FailureType.CONNECT_TIMEOUT, DpiAnomalyDetector.classifyException(timeoutEx))

        val rateLimitEx = Exception("HTTP 429 Too Many Requests")
        assertEquals(FailureType.RATE_LIMITED_429, DpiAnomalyDetector.classifyException(rateLimitEx))
    }

    @Test
    fun testInstantCircuitBreakerOpenOnDpiBlocked() {
        val record = WorkerCircuitRecord(
            workerId = "test_worker_id",
            domain = "test-worker.workers.dev",
            state = CircuitState.CLOSED,
            consecutiveFailures = 0
        )

        // Single 1st DPI failure MUST immediately trip circuit breaker to OPEN
        record.recordFailure(FailureType.DPI_BLOCKED)

        assertEquals(CircuitState.OPEN, record.state)
        assertEquals(1, record.consecutiveFailures)
        assertEquals(FailureType.DPI_BLOCKED, record.lastFailureReason)

        // Quarantine cooldown must be ~10 minutes (>= 580s)
        assertTrue(record.remainingCooldownSeconds >= 580L, "DPI cooldown must be approximately 10 minutes")
    }

    @Test
    fun testNormalTimeoutRequiresTwoFailuresForCircuitBreaker() {
        val record = WorkerCircuitRecord(
            workerId = "test_worker_id",
            domain = "test-worker.workers.dev",
            state = CircuitState.CLOSED,
            consecutiveFailures = 0
        )

        // 1st standard timeout failure -> STILL CLOSED
        record.recordFailure(FailureType.CONNECT_TIMEOUT)
        assertEquals(CircuitState.CLOSED, record.state)
        assertEquals(1, record.consecutiveFailures)

        // 2nd timeout failure -> TRIPPED TO OPEN
        record.recordFailure(FailureType.CONNECT_TIMEOUT)
        assertEquals(CircuitState.OPEN, record.state)
        assertEquals(2, record.consecutiveFailures)
    }

    @Test
    fun testConnectionHealthEngineDpiPenalties() {
        val reportDpi = ConnectionHealthEngine.computeHealth(
            smoothedPingMs = 50L,
            jitterMs = 10L,
            successRatePercent = 90,
            lastFailureType = FailureType.DPI_BLOCKED
        )

        assertEquals("Блокировка DPI оператором", reportDpi.verdict)
        assertEquals("DPI Блок", reportDpi.workerStatusGrade)
        assertTrue(reportDpi.score <= 65, "Score should have heavy penalty for DPI blockage")
    }
}
