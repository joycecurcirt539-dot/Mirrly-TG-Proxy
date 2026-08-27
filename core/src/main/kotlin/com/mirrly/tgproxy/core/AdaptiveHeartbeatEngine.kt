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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Интеллектуальный движок адаптивного Keep-Alive (Adaptive NAT Heartbeat).
 * Защищает мобильные сокеты от разрыва агрессивным CGNAT оператора (LTE/5G)
 * и экономит заряд батареи на стабильных сетях Wi-Fi.
 */
class AdaptiveHeartbeatEngine(
    private val statsProvider: () -> ProxyStats,
    private val onHeartbeatTick: () -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    @Volatile
    var isMobileNetwork: Boolean = false

    @Volatile
    var isScreenOn: Boolean = true

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                delay(5000L) // Проверка каждые 5 сек

                val intervalMs = calculateTargetIntervalMs()
                val stats = statsProvider()
                val idleMs = System.currentTimeMillis() - stats.lastActivityTimestamp.get()

                if (idleMs >= intervalMs) {
                    AppLogger.d(
                        "AdaptiveHeartbeat",
                        "NAT пульс отправлен (Сеть: ${if (isMobileNetwork) "Cellular" else "Wi-Fi"}, лимит: ${intervalMs / 1000}с, простой: ${idleMs / 1000}с)"
                    )
                    stats.lastActivityTimestamp.set(System.currentTimeMillis())
                    try {
                        onHeartbeatTick()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    fun calculateTargetIntervalMs(): Long {
        val stats = statsProvider()
        return calculateInterval(
            isMobile = isMobileNetwork,
            isScreenOn = isScreenOn,
            smoothedPingMs = stats.smoothedPingMs,
            jitterMs = stats.jitterMs
        )
    }

    companion object {
        fun calculateInterval(
            isMobile: Boolean,
            isScreenOn: Boolean,
            smoothedPingMs: Long,
            jitterMs: Long
        ): Long {
            return when {
                !isMobile -> {
                    // Wi-Fi: длительный интервал для экономии батареи
                    if (!isScreenOn) 60_000L else 45_000L
                }
                smoothedPingMs > 300L || jitterMs > 60L -> {
                    // Нестабильная мобильная сеть (высокий пинг/джиттер)
                    15_000L
                }
                else -> {
                    // Обычная мобильная сеть (LTE / 5G / 3G)
                    20_000L
                }
            }
        }
    }
}
