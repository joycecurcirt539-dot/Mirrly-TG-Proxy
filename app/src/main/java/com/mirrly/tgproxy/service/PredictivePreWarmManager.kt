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
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.WorkerProfile
import java.util.concurrent.atomic.AtomicReference

/**
 * Неизменяемый снимок горячего резерва маршрутов (Hot Reserve Route Pool).
 */
data class HotReserveRoutes(
    val validatedAtTimestampMs: Long = 0L,
    val bestMtprotoDc: String? = null,
    val fallbackMtprotoDcs: List<String> = emptyList(),
    val bestWorkerId: String? = null,
    val fallbackWorkerIds: List<String> = emptyList(),
    val bestWarpEndpoint: String? = null,
    val fallbackWarpEndpoints: List<String> = emptyList(),
    val isDnsHealthy: Boolean = true
) {
    fun isFresh(nowMs: Long = System.currentTimeMillis(), ttlMs: Long = 300_000L): Boolean =
        validatedAtTimestampMs > 0L && (nowMs - validatedAtTimestampMs) < ttlMs
}

/**
 * Менеджер горячего резерва маршрутов и оптимизации соединения.
 * Поддерживает актуальный пул валидированных маршрутов (Smart Connect / Hot Reserve).
 */
object PredictivePreWarmManager {
    private const val TAG = "PredictivePreWarm"
    const val DEBOUNCE_INTERVAL_MS = 10_000L
    const val HOT_RESERVE_TTL_MS = 300_000L

    private val hotReserve = AtomicReference(HotReserveRoutes())

    fun start(context: Context) {
        // Фоновый упреждающий прогрев отключен в пользу прямого соединения по требованию
        AppLogger.d(TAG, "Predictive screen-wake pre-warm is disabled (on-demand connection active)")
    }

    fun stop(context: Context) {
        // Safe no-op
    }

    fun shouldPreWarm(
        isProxyRunning: Boolean,
        isPowerSaveMode: Boolean,
        currentTimeMs: Long,
        lastWarmTimeMs: Long,
        minCooldownMs: Long = DEBOUNCE_INTERVAL_MS
    ): Boolean {
        if (!isProxyRunning) return false
        if (isPowerSaveMode) return false
        return (currentTimeMs - lastWarmTimeMs) >= minCooldownMs
    }

    /**
     * Сохраняет обновленный снимок валидированных маршрутов в горячий резерв.
     */
    fun updateHotReserve(routes: HotReserveRoutes) {
        hotReserve.set(routes)
        val details = when {
            !routes.bestMtprotoDc.isNullOrBlank() -> "Flowseal SDN: ${routes.bestMtprotoDc}"
            !routes.bestWorkerId.isNullOrBlank() -> "Worker: ${routes.bestWorkerId}"
            !routes.bestWarpEndpoint.isNullOrBlank() -> "WARP: ${routes.bestWarpEndpoint}"
            else -> "default"
        }
        AppLogger.d(TAG, "Hot reserve updated: $details")
    }

    /**
     * Возвращает текущий снимок горячего резерва маршрутов.
     */
    fun getHotReserve(): HotReserveRoutes = hotReserve.get()

    /**
     * Проверяет наличие актуального (не устаревшего) маршрута в горячем резерве.
     */
    fun hasFreshHotReserve(isSocks5: Boolean, ttlMs: Long = HOT_RESERVE_TTL_MS): Boolean {
        val current = hotReserve.get()
        if (!current.isFresh(ttlMs = ttlMs)) return false
        return if (isSocks5) {
            !current.bestWorkerId.isNullOrBlank() || !current.bestWarpEndpoint.isNullOrBlank()
        } else {
            !current.bestMtprotoDc.isNullOrBlank()
        }
    }

    /**
     * Извлекает следующий резервный узел из предварительно проверенного пула для бесшовного failover.
     */
    fun getNextFallbackWorker(currentActiveId: String, allWorkers: List<WorkerProfile>): WorkerProfile? {
        val reserve = hotReserve.get()
        for (fallbackId in reserve.fallbackWorkerIds) {
            if (fallbackId != currentActiveId) {
                val candidate = allWorkers.find { it.id == fallbackId }
                if (candidate != null) {
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * Сбрасывает кэш горячего резерва.
     */
    fun clearHotReserve() {
        hotReserve.set(HotReserveRoutes())
    }
}

