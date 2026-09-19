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

package com.mirrly.tgproxy.service

import com.mirrly.tgproxy.core.WorkerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreflightDiagnosticsTest {

    @Before
    fun setUp() {
        PreflightDiagnosticsEngine.reset()
        PredictivePreWarmManager.clearHotReserve()
    }

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(PreflightStage.IDLE, PreflightDiagnosticsEngine.preflightStage.value)
        assertEquals("", PreflightDiagnosticsEngine.preflightStatusMessage.value)
    }

    @Test
    fun testHotReserveFreshness() {
        val now = System.currentTimeMillis()
        val freshRoutes = HotReserveRoutes(
            validatedAtTimestampMs = now - 50_000L, // 50s ago
            bestWorkerId = "w1",
            bestMtprotoDc = "149.154.167.51:443"
        )
        assertTrue(freshRoutes.isFresh(now, ttlMs = 300_000L))

        val expiredRoutes = HotReserveRoutes(
            validatedAtTimestampMs = now - 350_000L, // 350s ago (> 300s)
            bestWorkerId = "w1"
        )
        assertFalse(expiredRoutes.isFresh(now, ttlMs = 300_000L))

        val emptyRoutes = HotReserveRoutes(validatedAtTimestampMs = 0L)
        assertFalse(emptyRoutes.isFresh(now, ttlMs = 300_000L))
    }

    @Test
    fun testPredictivePreWarmManagerHotReserveUpdateAndQuery() {
        assertFalse(PredictivePreWarmManager.hasFreshHotReserve(isSocks5 = true))
        assertFalse(PredictivePreWarmManager.hasFreshHotReserve(isSocks5 = false))

        val now = System.currentTimeMillis()
        val routes = HotReserveRoutes(
            validatedAtTimestampMs = now,
            bestWorkerId = "dev_default",
            fallbackWorkerIds = listOf("dev_backup", "custom_1"),
            bestMtprotoDc = "149.154.167.51:443",
            fallbackMtprotoDcs = listOf("149.154.167.91:443"),
            bestWarpEndpoint = "188.114.96.1:8443",
            isDnsHealthy = true
        )
        PredictivePreWarmManager.updateHotReserve(routes)

        assertTrue(PredictivePreWarmManager.hasFreshHotReserve(isSocks5 = true))
        assertTrue(PredictivePreWarmManager.hasFreshHotReserve(isSocks5 = false))

        val retrieved = PredictivePreWarmManager.getHotReserve()
        assertEquals("dev_default", retrieved.bestWorkerId)
        assertEquals("149.154.167.51:443", retrieved.bestMtprotoDc)
        assertEquals("188.114.96.1:8443", retrieved.bestWarpEndpoint)
        assertEquals(2, retrieved.fallbackWorkerIds.size)

        PredictivePreWarmManager.clearHotReserve()
        assertFalse(PredictivePreWarmManager.hasFreshHotReserve(isSocks5 = true))
    }

    @Test
    fun testGetNextFallbackWorkerSelectsNextValidWorker() {
        val w1 = WorkerProfile(id = "w1", name = "Worker 1", domain = "w1.example.com", isDeveloperWorker = true)
        val w2 = WorkerProfile(id = "w2", name = "Worker 2", domain = "w2.example.com", isDeveloperWorker = true)
        val w3 = WorkerProfile(id = "w3", name = "Worker 3", domain = "w3.example.com", isDeveloperWorker = false)
        val allWorkers = listOf(w1, w2, w3)

        val routes = HotReserveRoutes(
            validatedAtTimestampMs = System.currentTimeMillis(),
            bestWorkerId = "w1",
            fallbackWorkerIds = listOf("w2", "w3")
        )
        PredictivePreWarmManager.updateHotReserve(routes)

        // When currentActiveId is w1, next should be w2
        val next = PredictivePreWarmManager.getNextFallbackWorker("w1", allWorkers)
        assertNotNull(next)
        assertEquals("w2", next?.id)

        // When currentActiveId is w2, next should be w3
        val nextAfterW2 = PredictivePreWarmManager.getNextFallbackWorker("w2", allWorkers)
        assertNotNull(nextAfterW2)
        assertEquals("w3", nextAfterW2?.id)

        // When neither fallback matches
        val noNext = PredictivePreWarmManager.getNextFallbackWorker("w3", listOf(w1, w2))
        assertEquals("w2", noNext?.id)
    }

    @Test
    fun testPreflightResultDataClass() {
        val res = PreflightResult(
            isSuccess = true,
            selectedRouteSummary = "Telegram DC: 149.154.167.51:443 (42ms)",
            rttMs = 42L,
            isFromHotReserve = false
        )
        assertTrue(res.isSuccess)
        assertEquals(42L, res.rttMs)
        assertFalse(res.isFromHotReserve)
    }

    @Test
    fun testCustomWorkerIsNotDeveloperWorker() {
        val custom = WorkerProfile(id = "cust-1", name = "My Personal CF", domain = "custom.workers.dev", isDeveloperWorker = false)
        val dev = WorkerProfile(id = "dev_default", name = "Mirrly Primary", domain = "mirrly.workers.dev", isDeveloperWorker = true)
        assertFalse(custom.isDeveloperWorker)
        assertTrue(dev.isDeveloperWorker)
    }

    @Test
    fun testHotReserveWorkerFallbackPreservesRouteTopology() {
        val custom = WorkerProfile(id = "cust-1", name = "My Personal CF", domain = "custom.workers.dev", isDeveloperWorker = false)
        val dev1 = WorkerProfile(id = "dev_default", name = "Mirrly Primary", domain = "mirrly.workers.dev", isDeveloperWorker = true)
        val dev2 = WorkerProfile(id = "dev_alpha", name = "Mirrly Alpha", domain = "alpha.workers.dev", isDeveloperWorker = true)

        val routes = HotReserveRoutes(
            validatedAtTimestampMs = System.currentTimeMillis(),
            bestWorkerId = custom.id,
            fallbackWorkerIds = listOf(dev1.id, dev2.id)
        )
        PredictivePreWarmManager.updateHotReserve(routes)

        assertEquals("cust-1", PredictivePreWarmManager.getHotReserve().bestWorkerId)
        val fallback = PredictivePreWarmManager.getNextFallbackWorker("cust-1", listOf(custom, dev1, dev2))
        assertEquals("dev_default", fallback?.id)
    }
}
