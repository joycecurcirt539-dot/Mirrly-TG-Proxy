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
import org.junit.jupiter.api.Test

class ProxyStatsTest {

    @Test
    fun testParseNativeStatsExtractsWsAndCfConnections() {
        val stats = ProxyStats()
        var reportedWs = -1L
        stats.onTotalWsConnectionsChanged = { reportedWs = it }

        val rawStats = "total=42 active=3 ws=35 cf=35 bad=0 err=0 pool=8/16 up=2.4MB down=18.5MB"
        stats.parseNativeStats(rawStats)

        assertEquals(3, stats.activeConnections.get())
        assertEquals(35L, stats.totalWsConnections.get())
        assertEquals(35L, reportedWs)
    }

    @Test
    fun testParseNativeStatsMonotonicallyIncreases() {
        val stats = ProxyStats()
        var updateCount = 0
        stats.onTotalWsConnectionsChanged = { updateCount++ }

        stats.parseNativeStats("active=2 ws=10")
        assertEquals(10L, stats.totalWsConnections.get())
        assertEquals(1, updateCount)

        // Lower or equal value must not trigger update or decrease
        stats.parseNativeStats("active=2 ws=8")
        assertEquals(10L, stats.totalWsConnections.get())
        assertEquals(1, updateCount)

        stats.parseNativeStats("active=4 ws=15")
        assertEquals(15L, stats.totalWsConnections.get())
        assertEquals(2, updateCount)
    }
}
