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

package com.mirrly.tgproxy

import android.app.Application
import com.mirrly.tgproxy.core.LocalProxyServer
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.UpdateManager

class MirrlyApplication : Application() {

    lateinit var prefsManager: PreferencesManager
        private set

    lateinit var config: ProxyConfig
        private set

    lateinit var proxyServer: LocalProxyServer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.mirrly.tgproxy.core.AppLogger.startLogcatReader(android.os.Process.myPid())
        prefsManager = PreferencesManager(this)
        config = prefsManager.loadConfig()
        proxyServer = LocalProxyServer(config)
        proxyServer.stats.externalByteProvider = {
            val uid = android.os.Process.myUid()
            val rx = android.net.TrafficStats.getUidRxBytes(uid)
            val tx = android.net.TrafficStats.getUidTxBytes(uid)
            Pair(
                if (rx != android.net.TrafficStats.UNSUPPORTED.toLong() && rx > 0) rx else 0L,
                if (tx != android.net.TrafficStats.UNSUPPORTED.toLong() && tx > 0) tx else 0L
            )
        }
        UpdateManager.scheduleDaytimeCheck(this)
    }

    fun saveConfig() {
        prefsManager.saveConfig(config)
    }

    companion object {
        lateinit var instance: MirrlyApplication
            private set
    }
}
