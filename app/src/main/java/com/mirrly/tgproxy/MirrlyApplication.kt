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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        prefsManager = PreferencesManager(this)
        com.mirrly.tgproxy.util.LocaleHelper.applyLocale(this, prefsManager.getAppLanguage())
        com.mirrly.tgproxy.service.SessionHistoryManager.init(this)
        com.mirrly.tgproxy.service.SpeedTestHistoryManager.init(this)
        com.mirrly.tgproxy.service.WorkerRequestTracker.init(this)
        config = prefsManager.loadConfig()
        proxyServer = LocalProxyServer(config)
        proxyServer.speedTestEngine.onTestCompleted = { completedState ->
            val isSocks = config.isSocks5Mode
            com.mirrly.tgproxy.service.SpeedTestHistoryManager.addRecord(
                com.mirrly.tgproxy.service.SpeedTestRecord(
                    downloadSpeedMbps = completedState.downloadSpeedMbps,
                    uploadSpeedMbps = completedState.uploadSpeedMbps,
                    pingMs = completedState.pingMs,
                    jitterMs = completedState.jitterMs,
                    edgeColo = completedState.edgeColo,
                    targetDomain = completedState.targetDomain,
                    protocol = if (isSocks) "SOCKS5" else "MTProto",
                    qualityGrade = completedState.qualityGrade,
                    overallScore = completedState.suitability.overallScore,
                    durationMs = completedState.durationMs
                )
            )
        }
        proxyServer.stats.externalByteProvider = {
            val uid = android.os.Process.myUid()
            val rx = android.net.TrafficStats.getUidRxBytes(uid)
            val tx = android.net.TrafficStats.getUidTxBytes(uid)
            Pair(
                if (rx != android.net.TrafficStats.UNSUPPORTED.toLong() && rx > 0) rx else 0L,
                if (tx != android.net.TrafficStats.UNSUPPORTED.toLong() && tx > 0) tx else 0L
            )
        }
        proxyServer.stats.onTotalWsConnectionsChanged = { wsTotal ->
            com.mirrly.tgproxy.service.WorkerRequestTracker.syncNativeConnectionsTotal(wsTotal)
        }
        UpdateManager.onAppInit(this)
        UpdateManager.scheduleDaytimeCheck(this)
        com.mirrly.tgproxy.service.WorkerFailoverManager.init()
        com.mirrly.tgproxy.service.ScheduleManager.syncSchedule(this)

        proxyServer.pingEngine.onProbeCompleted = { probe, target ->
            val snapshot = proxyServer.pingEngine.currentSnapshot
            val generation = proxyServer.currentProfileGeneration.get()
            val observedAtMs = System.nanoTime() / 1_000_000L
            if (proxyServer.config.isSocks5Mode) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    com.mirrly.tgproxy.service.WorkerFailoverManager.handleActiveWorkerProbe(
                        probe, target, snapshot, generation, observedAtMs
                    )
                }
            }
        }
    }

    fun saveConfig() {
        prefsManager.saveConfig(config)
    }

    companion object {
        lateinit var instance: MirrlyApplication
            private set
    }
}
