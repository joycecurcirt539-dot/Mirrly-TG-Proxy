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

import android.content.Context
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.ConflictTrigger
import com.mirrly.tgproxy.core.PortConflictReport
import com.mirrly.tgproxy.core.ProxyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

object PortConflictMonitor {

    private const val TAG = "PortConflictMonitor"

    private val scanMutex = Mutex()
    private val isForeground = AtomicBoolean(false)
    private val startupScanAttempted = AtomicBoolean(false)

    private val _conflictReport = MutableStateFlow<PortConflictReport?>(null)
    val conflictReport: StateFlow<PortConflictReport?> = _conflictReport.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isResolvedInSheet = MutableStateFlow(false)
    val isResolvedInSheet: StateFlow<Boolean> = _isResolvedInSheet.asStateFlow()

    private val _portOverrideRevision = MutableStateFlow(0)
    val portOverrideRevision: StateFlow<Int> = _portOverrideRevision.asStateFlow()

    fun setForegroundState(foreground: Boolean) {
        isForeground.set(foreground)
    }

    fun isAppInForeground(): Boolean = isForeground.get()

    suspend fun checkOnAppStartup(context: Context, force: Boolean = false) {
        if (!force && !startupScanAttempted.compareAndSet(false, true)) {
            return
        }
        val app = MirrlyApplication.instance
        if (app.proxyServer.isRunning) {
            return
        }

        val report = runLockedScan(
            context = context.applicationContext,
            trigger = ConflictTrigger.APP_STARTUP,
            failedMode = null,
            failedPort = null,
            nativeDetail = ""
        )
        if (report.hasConflict) {
            _isResolvedInSheet.value = false
            _conflictReport.value = report
        }
    }

    suspend fun reportRuntimeConflict(
        context: Context,
        failedMode: ProxyMode,
        failedHost: String,
        failedPort: Int,
        nativeDetail: String
    ): PortConflictReport {
        val report = runLockedScan(
            context = context.applicationContext,
            trigger = ConflictTrigger.START_ERROR,
            failedMode = failedMode,
            failedPort = failedPort,
            nativeDetail = nativeDetail
        )
        _isResolvedInSheet.value = !report.hasConflict
        _conflictReport.value = report
        return report
    }

    suspend fun recheck(context: Context): PortConflictReport {
        val prev = _conflictReport.value
        val trigger = prev?.trigger ?: ConflictTrigger.APP_STARTUP
        val report = runLockedScan(
            context = context.applicationContext,
            trigger = trigger,
            failedMode = prev?.failedMode,
            failedPort = prev?.failedPort,
            nativeDetail = prev?.nativeErrorDetail.orEmpty()
        )
        if (report.hasConflict) {
            _isResolvedInSheet.value = false
            _conflictReport.value = report
        } else {
            _isResolvedInSheet.value = true
            _conflictReport.value = report
        }
        return report
    }

    suspend fun switchConflictingPortsToFree(context: Context): List<Pair<String, Int>> =
        withContext(Dispatchers.IO) {
            val app = MirrlyApplication.instance
            val current = _conflictReport.value ?: return@withContext emptyList()
            val switched = mutableListOf<Pair<String, Int>>()

            for (portInfo in current.occupiedPorts) {
                val occupiedPort = portInfo.port
                val matchedSocks5 = app.config.socks5Port == occupiedPort
                val matchedMtproto = app.config.bindPort == occupiedPort

                if (matchedSocks5) {
                    val freePort = PortConflictDetector.findFreePortForTarget(app.config, occupiedPort)
                    if (freePort != null) {
                        app.config.socks5Port = freePort
                        switched += "SOCKS5" to freePort
                    }
                }
                if (matchedMtproto) {
                    val freePort = PortConflictDetector.findFreePortForTarget(app.config, occupiedPort)
                    if (freePort != null) {
                        app.config.bindPort = freePort
                        switched += "MTProto" to freePort
                    }
                }
            }

            if (switched.isNotEmpty()) {
                app.saveConfig()
                _portOverrideRevision.value = _portOverrideRevision.value + 1
                AppLogger.i(TAG, "Автоматически подобраны свободные порты: $switched")
                recheck(context)
            }
            switched
        }

    fun dismiss() {
        _conflictReport.value = null
        _isResolvedInSheet.value = false
    }

    private suspend fun runLockedScan(
        context: Context,
        trigger: ConflictTrigger,
        failedMode: ProxyMode?,
        failedPort: Int?,
        nativeDetail: String
    ): PortConflictReport = scanMutex.withLock {
        _isScanning.value = true
        try {
            val app = MirrlyApplication.instance
            val runningMode = if (app.proxyServer.isRunning) app.config.proxyMode else null
            PortConflictDetector.scan(
                context = context,
                config = app.config,
                runningMode = runningMode,
                trigger = trigger,
                failedMode = failedMode,
                failedPort = failedPort,
                nativeErrorDetail = nativeDetail
            )
        } finally {
            _isScanning.value = false
        }
    }
}
