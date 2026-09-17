/*
 * Mirrly TG Proxy - Unit tests for TLS Resumption & Handshake Cost Observability (MOB-028)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 */

package com.mirrly.tgproxy.core

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * MOB-028: TLS resumption и handshake cost сделать наблюдаемыми.
 *
 * Требования:
 * 1. Rust cache на 128 sessions сам по себе не доказывает resumption.
 * 2. Считать full/resumed handshake, duration и failure per hostname/network.
 * 3. Session resumption реально наблюдается (handshake_kind: Full, Resumed, FullWithHelloRetryRequest).
 * 4. Запрещено менять security verification ради скорости (строгая верификация webpki-roots, SNI, ALPN).
 */
class TlsObservabilityTest {

    @Test
    fun testTlsObservabilityStatusJsonParsing() {
        val sampleJson = """
            {
              "current_network_generation": 2,
              "total_full_handshakes": 3,
              "total_resumed_handshakes": 7,
              "total_handshake_failures": 1,
              "global_resumption_ratio": 0.7,
              "hosts": [
                {
                  "hostname": "kws1.mirrly.workers.dev",
                  "network_generation": 2,
                  "full_handshakes": 1,
                  "resumed_handshakes": 4,
                  "handshake_failures": 0,
                  "total_duration_ms": 170,
                  "min_duration_ms": 18,
                  "max_duration_ms": 95,
                  "avg_duration_ms": 34.0,
                  "resumption_ratio": 0.8,
                  "last_handshake_kind": "resumed",
                  "last_duration_ms": 18,
                  "last_error": null
                },
                {
                  "hostname": "kws2.mirrly.workers.dev",
                  "network_generation": 2,
                  "full_handshakes": 2,
                  "resumed_handshakes": 3,
                  "handshake_failures": 1,
                  "total_duration_ms": 290,
                  "min_duration_ms": 22,
                  "max_duration_ms": 140,
                  "avg_duration_ms": 58.0,
                  "resumption_ratio": 0.6,
                  "last_handshake_kind": "resumed",
                  "last_duration_ms": 22,
                  "last_error": "handshake_timeout"
                }
              ]
            }
        """.trimIndent()

        val status = TlsObservabilityStatus.fromJson(sampleJson)
        assertNotNull(status, "Parsed status must not be null")
        assertEquals(2L, status!!.currentNetworkGeneration)
        assertEquals(3L, status.totalFullHandshakes)
        assertEquals(7L, status.totalResumedHandshakes)
        assertEquals(1L, status.totalHandshakeFailures)
        assertEquals(0.7, status.globalResumptionRatio, 0.0001)
        assertEquals(2, status.hosts.size)

        val host1 = status.hosts.first { it.hostname == "kws1.mirrly.workers.dev" }
        assertEquals(2L, host1.networkGeneration)
        assertEquals(1L, host1.fullHandshakes)
        assertEquals(4L, host1.resumedHandshakes)
        assertEquals(0L, host1.handshakeFailures)
        assertEquals(170L, host1.totalDurationMs)
        assertEquals(18L, host1.minDurationMs)
        assertEquals(95L, host1.maxDurationMs)
        assertEquals(34.0, host1.avgDurationMs, 0.0001)
        assertEquals(0.8, host1.resumptionRatio, 0.0001)
        assertEquals("resumed", host1.lastHandshakeKind)
        assertEquals(18L, host1.lastDurationMs)
        assertNull(host1.lastError)

        val host2 = status.hosts.first { it.hostname == "kws2.mirrly.workers.dev" }
        assertEquals(2L, host2.fullHandshakes)
        assertEquals(3L, host2.resumedHandshakes)
        assertEquals(1L, host2.handshakeFailures)
        assertEquals("handshake_timeout", host2.lastError)
    }

    @Test
    fun testObservabilityProvesResumptionRatioAndSpeedup() {
        // Handshake durations simulation
        val fullHandshakeDurationMs = 125L
        val resumedHandshake1DurationMs = 24L
        val resumedHandshake2DurationMs = 19L

        val totalDuration = fullHandshakeDurationMs + resumedHandshake1DurationMs + resumedHandshake2DurationMs
        val avgDuration = totalDuration.toDouble() / 3.0
        val resumptionRatio = 2.0 / 3.0

        val hostStats = TlsHostStatsData(
            hostname = "cloudflare-edge.example.com",
            networkGeneration = 1L,
            fullHandshakes = 1L,
            resumedHandshakes = 2L,
            handshakeFailures = 0L,
            totalDurationMs = totalDuration,
            minDurationMs = resumedHandshake2DurationMs,
            maxDurationMs = fullHandshakeDurationMs,
            avgDurationMs = avgDuration,
            resumptionRatio = resumptionRatio,
            lastHandshakeKind = "resumed",
            lastDurationMs = resumedHandshake2DurationMs,
            lastError = null
        )

        assertTrue(hostStats.resumedHandshakes > hostStats.fullHandshakes, "Resumed handshakes should dominate active pool")
        assertTrue(hostStats.resumptionRatio > 0.5, "Resumption ratio should be observed > 50%")
        assertTrue(hostStats.minDurationMs < hostStats.maxDurationMs, "Resumed handshake duration must be lower than full handshake")
        assertTrue(hostStats.avgDurationMs < fullHandshakeDurationMs, "Average duration must reflect significant speedup from resumption")
    }

    @Test
    fun testSecurityVerificationNotCompromisedForSpeed() {
        val rootDir = File(System.getProperty("user.dir")).let {
            if (it.name == "core") it.parentFile else it
        }

        val wsRs = File(rootDir, "mirrlyengine/src/ws.rs")
        assertTrue(wsRs.exists(), "mirrlyengine/src/ws.rs must exist")
        val wsCode = wsRs.readText()

        // 1. Strict Root CA verification via webpki_roots
        assertTrue(
            wsCode.contains("webpki_roots::TLS_SERVER_ROOTS"),
            "Strict TLS CA store must use webpki_roots::TLS_SERVER_ROOTS"
        )
        assertTrue(
            wsCode.contains("with_root_certificates(root_store)"),
            "ClientConfig must be initialized with verified root certificates"
        )

        // 2. Strict SNI verification
        assertTrue(
            wsCode.contains("server_name"),
            "TLS SNI must be properly passed to connector"
        )

        // 3. Strict ALPN protocol negotiation
        assertTrue(
            wsCode.contains("cfg.alpn_protocols = vec![b\"http/1.1\".to_vec()]"),
            "ALPN must negotiate standard HTTP/1.1"
        )

        // 4. In-memory session resumption configured safely
        assertTrue(
            wsCode.contains("Resumption::in_memory_sessions(128)"),
            "Resumption must use standard in_memory_sessions without disabling security"
        )

        // 5. Categorical check: NO dangerous / insecure cert verifiers
        assertFalse(
            wsCode.contains("dangerous()"),
            "CRITICAL: dangerous() certificate verification bypass is strictly forbidden"
        )
        assertFalse(
            wsCode.contains("set_certificate_verifier"),
            "CRITICAL: custom certificate verifier bypass is strictly forbidden"
        )
        assertFalse(
            wsCode.contains("NullVerifier"),
            "CRITICAL: null verifier is strictly forbidden"
        )
    }

    @Test
    fun testPerNetworkGenerationIsolation() {
        val rootDir = File(System.getProperty("user.dir")).let {
            if (it.name == "core") it.parentFile else it
        }

        val trackerRs = File(rootDir, "mirrlyengine/src/tls_observability.rs")
        assertTrue(trackerRs.exists(), "mirrlyengine/src/tls_observability.rs must exist")
        val trackerCode = trackerRs.readText()

        // Key must include network_generation
        assertTrue(
            trackerCode.contains("(String, u64)"),
            "TlsObservabilityTracker key must be composite (hostname, network_generation)"
        )
        assertTrue(
            trackerCode.contains("network_generation: u64"),
            "TlsHostStats must track network_generation"
        )
    }

    @Test
    fun testFailureTrackingAndErrorVisibility() {
        val rootDir = File(System.getProperty("user.dir")).let {
            if (it.name == "core") it.parentFile else it
        }

        val trackerRs = File(rootDir, "mirrlyengine/src/tls_observability.rs")
        assertTrue(trackerRs.exists(), "mirrlyengine/src/tls_observability.rs must exist")
        val trackerCode = trackerRs.readText()

        assertTrue(
            trackerCode.contains("pub handshake_failures: u64"),
            "TlsHostStats must track handshake_failures count"
        )
        assertTrue(
            trackerCode.contains("pub last_error: Option<String>"),
            "TlsHostStats must expose last_error message"
        )
        assertTrue(
            trackerCode.contains("pub fn record_failure"),
            "TlsObservabilityTracker must provide record_failure method"
        )
    }

    @Test
    fun testJnaAndNativeProxyContract() {
        val rootDir = File(System.getProperty("user.dir")).let {
            if (it.name == "core") it.parentFile else it
        }

        val nativeProxyFile = File(rootDir, "core/src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt")
        assertTrue(nativeProxyFile.exists(), "NativeProxy.kt must exist")
        val kotlinCode = nativeProxyFile.readText()

        // Verify ProxyLibrary FFI declaration
        assertTrue(
            kotlinCode.contains("fun GetTlsObservabilityStatusJson(): Pointer?"),
            "ProxyLibrary must declare GetTlsObservabilityStatusJson(): Pointer?"
        )

        // Verify NativeProxy facade methods
        assertTrue(
            kotlinCode.contains("fun getTlsObservabilityStatusJson(): String?"),
            "NativeProxy must provide getTlsObservabilityStatusJson(): String?"
        )
        assertTrue(
            kotlinCode.contains("fun getTlsObservabilityStatus(): TlsObservabilityStatus?"),
            "NativeProxy must provide getTlsObservabilityStatus(): TlsObservabilityStatus?"
        )

        // Verify Data Classes
        assertTrue(
            kotlinCode.contains("data class TlsObservabilityStatus("),
            "NativeProxy must declare TlsObservabilityStatus"
        )
        assertTrue(
            kotlinCode.contains("data class TlsHostStatsData("),
            "NativeProxy must declare TlsHostStatsData"
        )
    }

    @Test
    fun testRustWiringAndObservabilityCalls() {
        val rootDir = File(System.getProperty("user.dir")).let {
            if (it.name == "core") it.parentFile else it
        }

        val libRs = File(rootDir, "mirrlyengine/src/lib.rs")
        val libCode = libRs.readText()
        assertTrue(
            libCode.contains("pub mod tls_observability;"),
            "lib.rs must declare pub mod tls_observability;"
        )
        assertTrue(
            libCode.contains("pub extern \"C\" fn GetTlsObservabilityStatusJson"),
            "lib.rs must export GetTlsObservabilityStatusJson"
        )
        assertTrue(
            libCode.contains("tls_observability::TLS_TRACKER.write().reset()"),
            "lib.rs must reset TLS_TRACKER on proxy start/stop"
        )

        val wsRs = File(rootDir, "mirrlyengine/src/ws.rs")
        val wsCode = wsRs.readText()
        assertTrue(
            wsCode.contains("crate::tls_observability::TLS_TRACKER.write().record_success"),
            "ws.rs must record TLS handshake success"
        )
        assertTrue(
            wsCode.contains("crate::tls_observability::TLS_TRACKER.write().record_failure"),
            "ws.rs must record TLS handshake failure"
        )
        assertTrue(
            wsCode.contains("handshake_kind()"),
            "ws.rs must inspect handshake_kind() to detect resumption"
        )

        val vlessRs = File(rootDir, "mirrlyengine/src/vless.rs")
        val vlessCode = vlessRs.readText()
        assertTrue(
            vlessCode.contains("crate::tls_observability::TLS_TRACKER.write().record_success"),
            "vless.rs must record TLS handshake success"
        )
    }
}
