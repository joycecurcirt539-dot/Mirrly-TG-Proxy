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
}
