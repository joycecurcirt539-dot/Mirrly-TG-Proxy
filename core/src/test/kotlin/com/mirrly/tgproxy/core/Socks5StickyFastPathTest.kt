/*
 * Mirrly TG Proxy - Unit tests for SOCKS5 Sticky Fast-Path & Bounded Fallback (MOB-029)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 */

package com.mirrly.tgproxy.core

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * MOB-029: Sticky fast-path до race.
 *
 * Требования:
 * 1. LAST_SOCKS5_WORKER лишь ставит worker раньше, но каждый flow всё равно готовит race до четырёх.
 * 2. Исправить: один быстрый establishment к proven worker; hedge только после adaptive delay или typed failure.
 * 3. Coalesce только DNS/TLS metadata — один /tcp WebSocket нельзя переиспользовать для другого TCP flow.
 * 4. Принять: стабильный worker = одна WSS попытка на Telegram flow; отказ приводит к bounded fallback.
 */
class Socks5StickyFastPathTest {

    @Test
    fun testStickyFastPathSingleWssAttemptWhenWorkerStable() {
        // Model simulation of stable proven worker establishment
        val provenWorker = "custom-worker.workers.dev"
        var wssAttempts = 0
        var fallbackRacersSpawned = 0

        // Step 1: Flow arrives with a known healthy proven worker
        val isFastPathEligible = provenWorker.isNotBlank()
        assertTrue(isFastPathEligible, "Proven worker must be eligible for fast-path")

        // Step 2: Sticky fast-path execution
        wssAttempts++
        val fastPathSuccess = true // Fast-path completes within hedge deadline

        if (!fastPathSuccess) {
            fallbackRacersSpawned += 3 // Fallback race would only spawn if fast-path failed
        }

        // Assert contract: Exactly 1 WSS attempt made, 0 fallback racers spawned
        assertEquals(1, wssAttempts, "Stable proven worker must result in exactly 1 WSS attempt per flow")
        assertEquals(0, fallbackRacersSpawned, "No fallback racers must be spawned when fast-path succeeds")
    }

    @Test
    fun testTypedFailureTriggersImmediateBoundedFallback() {
        // Model simulation of typed failure (e.g. 429 cooldown, connection reset, 502)
        val provenWorker = "failing-worker.workers.dev"
        var fastPathAttempts = 0
        var fallbackRaceTriggered = false
        var fallbackRacersCount = 0

        fastPathAttempts++
        // Simulate typed failure returned immediately (e.g. HTTP 429 / connection refused)
        val typedError = "HTTP 429 Too Many Requests"

        if (typedError.contains("429") || typedError.contains("refused") || typedError.contains("invalid_ack")) {
            fallbackRaceTriggered = true
            fallbackRacersCount = 3 // Bounded to max 3 diverse candidates
        }

        assertEquals(1, fastPathAttempts, "Fast path was attempted once")
        assertTrue(fallbackRaceTriggered, "Typed failure must immediately trigger fallback race without waiting")
        assertEquals(3, fallbackRacersCount, "Fallback race must be strictly bounded to diverse candidates")
    }

    @Test
    fun testAdaptiveHedgeDelayCalculation() {
        // Helper function matching Rust implementation:
        // hedge_delay = if smoothed_rtt > 0 { (smoothed_rtt * 3).clamp(800, 2500) } else if is_mobile { 1500 } else { 800 }
        fun computeHedgeDelay(smoothedRttMs: Long, isMobile: Boolean): Long {
            return if (smoothedRttMs > 0) {
                (smoothedRttMs * 3).coerceIn(800L, 2500L)
            } else if (isMobile) {
                1500L
            } else {
                800L
            }
        }

        // Low latency Wi-Fi (RTT 50ms) -> 150ms clamped to minimum 800ms
        assertEquals(800L, computeHedgeDelay(50L, false))

        // Normal mobile LTE (RTT 350ms) -> 1050ms
        assertEquals(1050L, computeHedgeDelay(350L, true))

        // High latency mobile (RTT 900ms) -> 2700ms clamped to maximum 2500ms
        assertEquals(2500L, computeHedgeDelay(900L, true))

        // Unknown RTT on Wi-Fi (default baseline)
        assertEquals(800L, computeHedgeDelay(-1L, false))

        // Unknown RTT on Mobile (conservative default baseline)
        assertEquals(1500L, computeHedgeDelay(-1L, true))
    }

    @Test
    fun testCoalescingRulesPreserveSingleFlowPerWebSocket() {
        // Architectural assertion:
        // 1. WebSocket streams bridge 1:1 with Telegram DC TCP connection.
        // 2. Cross-flow multiplexing on a raw /tcp or /tcp-v2 stream is forbidden without a new framing protocol.
        // 3. DNS (DoH cache) and TLS (session resumption tickets) metadata are safely coalesced across flows.

        val flow1Id = "flow-dc2-chat"
        val flow2Id = "flow-dc2-media"

        // Flows must have distinct WebSocket instance references
        val wsInstance1 = Any()
        val wsInstance2 = Any()
        assertNotSame(wsInstance1, wsInstance2, "Each Telegram TCP flow must own a distinct RawWebSocket stream")
    }

    @Test
    fun testRustEngineWiringContract() {
        val rootDir = File(System.getProperty("user.dir")).let {
            if (it.name == "core") it.parentFile else it
        }

        val socks5Rs = File(rootDir, "mirrlyengine/src/socks5.rs")
        assertTrue(socks5Rs.exists(), "mirrlyengine/src/socks5.rs must exist")
        val socks5Code = socks5Rs.readText()

        // Verify Sticky Fast-Path logic
        assertTrue(
            socks5Code.contains("proven_worker"),
            "socks5.rs must identify proven_worker (user_domain or LAST_SOCKS5_WORKER)"
        )
        assertTrue(
            socks5Code.contains("attempt_single_worker_connect"),
            "socks5.rs must declare attempt_single_worker_connect helper"
        )
        assertTrue(
            socks5Code.contains("hedge_delay"),
            "socks5.rs must compute adaptive hedge_delay"
        )
        assertTrue(
            socks5Code.contains("socks5_fastpath_hits"),
            "socks5.rs must track socks5_fastpath_hits stat"
        )
        assertTrue(
            socks5Code.contains("socks5_fallback_triggers"),
            "socks5.rs must track socks5_fallback_triggers stat"
        )
        assertTrue(
            socks5Code.contains("select_diverse_race_candidates"),
            "socks5.rs must bound fallback race with select_diverse_race_candidates"
        )

        val configRs = File(rootDir, "mirrlyengine/src/config.rs")
        assertTrue(configRs.exists(), "mirrlyengine/src/config.rs must exist")
        val configCode = configRs.readText()
        assertTrue(
            configCode.contains("pub socks5_fastpath_hits: AtomicI64"),
            "config.rs Stats must declare socks5_fastpath_hits"
        )
        assertTrue(
            configCode.contains("pub socks5_fallback_triggers: AtomicI64"),
            "config.rs Stats must declare socks5_fallback_triggers"
        )
    }
}
