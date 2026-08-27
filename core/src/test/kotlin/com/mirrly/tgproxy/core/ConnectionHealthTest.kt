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

class ConnectionHealthTest {

    @Test
    fun testPerfectConnectionYieldsHighHealthScoreAndHDVoiceMos() {
        val report = ConnectionHealthEngine.computeHealth(
            smoothedPingMs = 35L,
            jitterMs = 3L,
            successRatePercent = 100
        )
        assertTrue(report.score >= 95, "Expected SQI score >= 95 for ideal link, got ${report.score}")
        assertTrue(report.chatScore >= 95, "Expected chat score >= 95, got ${report.chatScore}")
        assertEquals("Идеально для медиа", report.chatVerdict)
        assertTrue(report.callScore >= 90, "Expected call score >= 90, got ${report.callScore}")
        assertTrue(report.isExcellent)
        assertEquals("Идеальный канал связи", report.verdict)
        assertEquals("Отлично", report.operatorLatencyGrade)
        assertEquals("Стабилен", report.workerStatusGrade)
        assertTrue(report.mosScore >= 4.20, "Expected MOS >= 4.20 for ideal link, got ${report.mosScore}")
        assertEquals("HD Voice (Отлично)", report.mosGrade)
        assertTrue(report.isCallRecommended)
    }

    @Test
    fun testModerateLatencyAndJitterYieldsGoodMos() {
        val report = ConnectionHealthEngine.computeHealth(
            smoothedPingMs = 200L,
            jitterMs = 40L,
            successRatePercent = 100
        )
        assertTrue(report.score in 50..89, "Expected SQI score between 50 and 89, got ${report.score}")
        assertTrue(report.mosScore in 3.80..4.19, "Expected MOS between 3.80 and 4.19, got ${report.mosScore}")
        assertEquals("Хорошее качество", report.mosGrade)
        assertTrue(report.isCallRecommended)
    }

    @Test
    fun testLteJitterYieldsOperatorWarning() {
        val report = ConnectionHealthEngine.computeHealth(
            smoothedPingMs = 120L,
            jitterMs = 45L,
            successRatePercent = 100
        )
        assertTrue(report.score in 60..89, "Expected SQI score between 60 and 89 for jittery link, got ${report.score}")
        assertEquals("Высокий джиттер", report.operatorLatencyGrade)
    }

    @Test
    fun testHighPacketLossYieldsDegradationAndPoorMos() {
        val report = ConnectionHealthEngine.computeHealth(
            smoothedPingMs = 150L,
            jitterMs = 10L,
            successRatePercent = 40
        )
        assertTrue(report.score < 60, "Expected SQI score < 60 for packet loss link, got ${report.score}")
        assertTrue(report.mosScore < 3.10, "Expected degraded MOS for 60% packet loss, got ${report.mosScore}")
        assertFalse(report.isCallRecommended)
    }

    @Test
    fun testRateLimit429AppliesPenalty() {
        val normalReport = ConnectionHealthEngine.computeHealth(
            smoothedPingMs = 50L,
            jitterMs = 5L,
            successRatePercent = 100,
            lastFailureType = FailureType.NONE
        )
        val rateLimitedReport = ConnectionHealthEngine.computeHealth(
            smoothedPingMs = 50L,
            jitterMs = 5L,
            successRatePercent = 100,
            lastFailureType = FailureType.RATE_LIMITED_429
        )
        assertTrue(rateLimitedReport.score < normalReport.score, "429 Rate limited report must have lower score")
        assertEquals("Лимит 429", rateLimitedReport.workerStatusGrade)
        assertTrue(rateLimitedReport.mosScore < normalReport.mosScore)
    }

    @Test
    fun testDpiBlockedYieldsZeroVoiceAndSpecialVerdict() {
        val report = ConnectionHealthEngine.computeHealth(
            smoothedPingMs = 80L,
            jitterMs = 10L,
            successRatePercent = 100,
            lastFailureType = FailureType.DPI_BLOCKED
        )
        assertEquals("Блокировка DPI оператором", report.verdict)
        assertEquals("DPI Блок", report.workerStatusGrade)
        assertEquals(1.00, report.mosScore)
        assertFalse(report.isCallRecommended)
    }

    @Test
    fun testOfflineYieldsZeroScore() {
        val report = ConnectionHealthEngine.computeHealth(
            smoothedPingMs = -1L,
            jitterMs = 0L,
            successRatePercent = 0
        )
        assertEquals(0, report.score)
        assertEquals("Канал не активен", report.verdict)
        assertEquals(1.00, report.mosScore)
        assertEquals("Нет связи", report.mosGrade)
        assertFalse(report.isCallRecommended)
    }
}
