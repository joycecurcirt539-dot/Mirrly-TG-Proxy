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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyResurrectionTest {

    @Test
    fun testActionConstants() {
        assertEquals("com.mirrly.tgproxy.ACTION_RESURRECT_PROXY", ProxyResurrectionManager.ACTION_RESURRECT_PROXY)
        assertEquals("com.mirrly.tgproxy.ACTION_WATCHDOG_HEARTBEAT", ProxyResurrectionManager.ACTION_WATCHDOG_HEARTBEAT)
    }

    @Test
    fun testWatchdogIntervalContract() {
        // Гарантируем, что интервал сторожевого пульса равен 15 минутам (900,000 мс)
        // для согласования с минимальным окном Doze / WorkManager
        assertEquals(15L * 60 * 1000L, ProxyResurrectionManager.WATCHDOG_INTERVAL_MS)
        assertTrue(ProxyResurrectionManager.WATCHDOG_INTERVAL_MS >= 15L * 60 * 1000L)
    }

    @Test
    fun testProxyResurrectionReceiverInstantiation() {
        val receiver = ProxyResurrectionReceiver()
        assertNotNull(receiver)
    }

    @Test
    fun testWorkerClassReflect() {
        val workerClass = ProxyResurrectionWorker::class.java
        assertNotNull(workerClass)
        assertEquals("com.mirrly.tgproxy.service.ProxyResurrectionWorker", workerClass.name)
    }
}
