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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TelegramDCAffinityTest {

    private lateinit var engine: TelegramDCAffinityEngine

    @BeforeEach
    fun setUp() {
        engine = TelegramDCAffinityEngine()
    }

    @Test
    fun testDefaultInitialDistributionPrioritizesEurope() {
        val dist = engine.calculateSocketDistribution(totalPoolSize = 4)
        assertNotNull(dist.primaryDcId)
        assertEquals(2, dist.primaryDcId) // DC2 (Амстердам/Чат) дефолтный primary для EU/CIS

        val dc2Sockets = dist.socketDistribution[2] ?: 0
        val dc4Sockets = dist.socketDistribution[4] ?: 0
        assertTrue(dc2Sockets >= 2, "DC2 должен получить не менее 2 сокетов в дефолтном режиме")
        assertTrue(dc2Sockets + dc4Sockets >= 3, "Европейские DC2 и DC4 должны занимать большую часть пула")
    }

    @Test
    fun testHeavyTrafficOnDc4ShiftsBudgetToMedia() {
        // Симулируем загрузку тяжелого видео/файла на DC4 (50 МБ) при минимальном трафике чатов на DC2 (200 КБ)
        engine.recordTraffic(dcId = 4, rxBytes = 50_000_000L, txBytes = 500_000L)
        engine.recordTraffic(dcId = 2, rxBytes = 200_000L, txBytes = 50_000L)

        val dist = engine.calculateSocketDistribution(totalPoolSize = 8)
        assertEquals(4, dist.primaryDcId, "DC4 должен стать доминантным дата-центром")

        val dc4Sockets = dist.socketDistribution[4] ?: 0
        val dc2Sockets = dist.socketDistribution[2] ?: 0

        assertTrue(dc4Sockets >= 5, "DC4 должен получить не менее 5 сокетов из 8 при тяжелой медиа-загрузке (фактически: $dc4Sockets)")
        assertTrue(dc2Sockets >= 1, "DC2 должен сохранить минимум 1 сокет для текстовых чатов")
        assertEquals(8, dist.socketDistribution.values.sum(), "Сумма распределенных сокетов должна точно равняться размеру пула")
    }

    @Test
    fun testActiveConnectionsWeightForAsiaDc5() {
        // Подключение клиента из Азии к DC5
        engine.recordConnectionDelta(dcId = 5, delta = 3)
        engine.recordTraffic(dcId = 5, rxBytes = 5_000_000L, txBytes = 100_000L)

        val dist = engine.calculateSocketDistribution(totalPoolSize = 6)
        assertEquals(5, dist.primaryDcId, "DC5 должен стать primary при наличии активных соединений и трафика")

        val dc5Sockets = dist.socketDistribution[5] ?: 0
        assertTrue(dc5Sockets >= 4, "DC5 должен получить львиную долю пула при активных соединениях")
    }

    @Test
    fun testDcInfoMetadataResolution() {
        val dc1 = TelegramDCAffinityEngine.getDcInfo(1)
        assertEquals("DC 1", dc1.name)
        assertTrue(dc1.location.contains("Майами"))

        val dc2 = TelegramDCAffinityEngine.getDcInfo(2)
        assertEquals("DC 2", dc2.name)
        assertTrue(dc2.location.contains("Амстердам"))

        val dc4 = TelegramDCAffinityEngine.getDcInfo(4)
        assertEquals("DC 4", dc4.name)
        assertTrue(dc4.role.contains("Медиа"))

        val dc5 = TelegramDCAffinityEngine.getDcInfo(5)
        assertEquals("DC 5", dc5.name)
        assertTrue(dc5.location.contains("Сингапур"))
    }

    @Test
    fun testEngineReset() {
        engine.recordTraffic(4, 10_000_000L, 500_000L)
        val metricsBefore = engine.getDcMetrics()
        assertTrue(metricsBefore.any { it.dcId == 4 && it.bytesReceived > 0 })

        engine.reset()
        val metricsAfter = engine.getDcMetrics()
        assertTrue(metricsAfter.all { it.bytesReceived == 0L && it.bytesSent == 0L })
    }
}
