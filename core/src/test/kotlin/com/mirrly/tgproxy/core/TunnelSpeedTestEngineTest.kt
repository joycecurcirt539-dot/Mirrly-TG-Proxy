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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TunnelSpeedTestEngineTest {

    @Test
    fun testInitialSpeedTestStateIsIdle() {
        val engine = TunnelSpeedTestEngine()
        val state = engine.liveState.value

        assertEquals(SpeedTestStage.IDLE, state.stage)
        assertEquals(0f, state.progress)
        assertEquals(0.0, state.currentSpeedMbps)
        assertEquals(-1L, state.pingMs)
        assertEquals("—", state.edgeColo)
        assertFalse(engine.isRunning)
    }

    @Test
    fun testCancelTestResetsStateGracefully() {
        val engine = TunnelSpeedTestEngine()
        engine.cancelTest()

        val state = engine.liveState.value
        assertEquals(SpeedTestStage.IDLE, state.stage)
        assertFalse(engine.isRunning)
    }

    @Test
    fun testTelegramSuitabilityReportDefaults() {
        val report = TelegramSuitabilityReport(
            chatsVerdict = "Мгновенно (< 50 мс)",
            voiceVerdict = "HD Voice (Opus 48 kHz)",
            mediaVerdict = "Быстро (~0.5 сек / фото)",
            videoVerdict = "4K UHD / 60 FPS",
            overallScore = 100,
            summary = "Идеальный канал"
        )

        assertEquals("Мгновенно (< 50 мс)", report.chatsVerdict)
        assertEquals("HD Voice (Opus 48 kHz)", report.voiceVerdict)
        assertEquals(100, report.overallScore)
        assertTrue(report.summary.isNotBlank())
    }

    @Test
    fun testSpeedTestLiveStateImmutability() {
        val original = SpeedTestLiveState(
            stage = SpeedTestStage.DOWNLOAD,
            currentSpeedMbps = 64.2,
            pingMs = 32L,
            edgeColo = "DME"
        )

        val updated = original.copy(
            stage = SpeedTestStage.UPLOAD,
            currentSpeedMbps = 30.5
        )

        assertEquals(SpeedTestStage.DOWNLOAD, original.stage)
        assertEquals(64.2, original.currentSpeedMbps)
        assertEquals(SpeedTestStage.UPLOAD, updated.stage)
        assertEquals(30.5, updated.currentSpeedMbps)
        assertEquals("DME", updated.edgeColo)
    }

    @Test
    fun testAllSequentialStagesExist() {
        val stages = SpeedTestStage.values().toList()
        assertTrue(stages.contains(SpeedTestStage.IDLE))
        assertTrue(stages.contains(SpeedTestStage.PING))
        assertTrue(stages.contains(SpeedTestStage.DOWNLOAD))
        assertTrue(stages.contains(SpeedTestStage.UPLOAD))
        assertTrue(stages.contains(SpeedTestStage.ANALYSIS))
        assertTrue(stages.contains(SpeedTestStage.COMPLETED))
        assertTrue(stages.contains(SpeedTestStage.CANCELLED))
        assertTrue(stages.contains(SpeedTestStage.ERROR))
    }

    @Test
    fun testOnTestCompletedCallbackCanBeRegistered() {
        val engine = TunnelSpeedTestEngine()
        var completedCalled = false
        engine.onTestCompleted = {
            completedCalled = true
        }
        assertNotNull(engine.onTestCompleted)
        assertFalse(completedCalled)
    }

    @Test
    fun testTelegramDcBenchmarksList() {
        val dcs = defaultTelegramDcs()
        assertEquals(5, dcs.size)

        val dc1 = dcs.find { it.dcId == 1 }
        val dc2 = dcs.find { it.dcId == 2 }
        val dc3 = dcs.find { it.dcId == 3 }
        val dc4 = dcs.find { it.dcId == 4 }
        val dc5 = dcs.find { it.dcId == 5 }

        assertNotNull(dc1)
        assertNotNull(dc2)
        assertNotNull(dc3)
        assertNotNull(dc4)
        assertNotNull(dc5)

        assertEquals("149.154.175.50", dc1?.ip)
        assertEquals("149.154.167.51", dc2?.ip)
        assertEquals("149.154.175.100", dc3?.ip)
        assertEquals("149.154.167.91", dc4?.ip)
        assertEquals("91.108.56.130", dc5?.ip)

        assertTrue(dc2?.isKeyDc == true)
        assertTrue(dc4?.isKeyDc == true)
        assertFalse(dc1?.isKeyDc == true)
        assertFalse(dc3?.isKeyDc == true)
        assertFalse(dc5?.isKeyDc == true)
    }

    @Test
    fun testWeightedScoringQualityGrade() {
        val engine = TunnelSpeedTestEngine()

        // Requirement 1.4: Connection with >50 Mbps and latency <= 120 ms must get "Отлично"
        val score = engine.calculateOverallScore(
            dlMbps = 65.0,
            ulMbps = 40.0,
            pingMs = 110L,
            jitterMs = 8L,
            dc2RttMs = 110L,
            dc4RttMs = 115L
        )
        val grade = engine.calculateQualityGrade(score, dlMbps = 65.0, pingMs = 110L)
        assertEquals("Отлично", grade)

        // Ultra high speed (120 Mbps) with 95 ms ping must also be "Отлично"
        val gradeHigh = engine.calculateQualityGrade(90, dlMbps = 120.0, pingMs = 95L)
        assertEquals("Отлично", gradeHigh)
    }
}

