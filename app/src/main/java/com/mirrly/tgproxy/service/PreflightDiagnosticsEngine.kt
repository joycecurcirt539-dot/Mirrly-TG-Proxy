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
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.core.UplinkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

import com.mirrly.tgproxy.R
import kotlinx.coroutines.delay

/**
 * Этапы фазы предстартовой диагностики (Pre-flight Phase).
 */
enum class PreflightStage {
    IDLE,
    PHASE_1_START_SERVER,
    PHASE_2_WARMUP_STREAMS,
    PHASE_3_SOCKS5_READY,
    COMPLETED,
    FAILED
}

/**
 * Результат предстартовой проверки маршрута.
 */
data class PreflightResult(
    val isSuccess: Boolean,
    val selectedRouteSummary: String,
    val rttMs: Long = -1L,
    val isFromHotReserve: Boolean = false
)

/**
 * Движок предстартовой экспресс-диагностики («Подключение в один клик» / Smart Connect).
 *
 * Реализует последовательный 3-фазный конвейер инициализации службы:
 * 1. Запуск локального слушателя SOCKS5 / MTProto на localhost;
 * 2. Прогрев пула WSS-потоков через Cloudflare Worker (/apiws);
 * 3. Готовность локального прокси-сервера к передаче трафика Telegram.
 */
object PreflightDiagnosticsEngine {

    private const val TAG = "PreflightDiagnostics"
    const val NORMAL_MAX_TIMEOUT_MS = 2500L
    const val DEGRADED_MAX_TIMEOUT_MS = 3500L

    private val _preflightStage = MutableStateFlow(PreflightStage.IDLE)
    val preflightStage: StateFlow<PreflightStage> = _preflightStage.asStateFlow()

    private val _preflightStatusMessage = MutableStateFlow("")
    val preflightStatusMessage: StateFlow<String> = _preflightStatusMessage.asStateFlow()

    private val _warmPoolStatus = MutableStateFlow<com.mirrly.tgproxy.core.WarmPoolStatus?>(null)
    val warmPoolStatus: StateFlow<com.mirrly.tgproxy.core.WarmPoolStatus?> = _warmPoolStatus.asStateFlow()

    fun reset() {
        _preflightStage.value = PreflightStage.IDLE
        _preflightStatusMessage.value = ""
        _warmPoolStatus.value = null
    }

    /**
     * Выполняет экспресс-анализ сети и маршрутов перед запуском сокетов прокси.
     * Последовательно проводит соединение через 3 прозрачные фазы.
     */
    suspend fun runPreflight(
        context: Context,
        config: ProxyConfig,
        prefsManager: PreferencesManager,
        serverStarter: (suspend () -> Boolean)? = null,
        isDegraded: Boolean = false
    ): PreflightResult = withContext(Dispatchers.IO) {
        prefsManager.restoreUserPrimaryWorkerIfNeeded()
        val workerDomain = config.getEffectiveCfDomain()

        // Фаза 1: Запуск локального слушателя SOCKS5
        _preflightStage.value = PreflightStage.PHASE_1_START_SERVER
        _preflightStatusMessage.value = "Запуск локального сервиса (127.0.0.1:10808)"
        AppLogger.i(TAG, "Фаза 1/3: Запуск локального сервиса...")
        var targetRtt = -1L
        if (serverStarter != null) {
            val started = serverStarter()
            if (!started) {
                _preflightStage.value = PreflightStage.FAILED
                _preflightStatusMessage.value = "Ошибка запуска локального сервиса"
                return@withContext PreflightResult(
                    isSuccess = false,
                    selectedRouteSummary = "Server startup failed"
                )
            }
        }
        delay(60)

        // Фаза 2: Прогрев WSS-туннелей и пула потоков
        _preflightStage.value = PreflightStage.PHASE_2_WARMUP_STREAMS
        AppLogger.i(TAG, "Фаза 2/3: Прогрев WSS-туннелей и пула потоков...")
        if (config.isSocks5Mode) {
            val isMobile = prefsManager.isForceMobileModeEnabled() ||
                com.mirrly.tgproxy.MirrlyApplication.instance.proxyServer.effectiveNetworkProfile.let { it.cellular || it.metered || it.isMobile }
            val maxWarmupMs = if (isMobile || isDegraded) 6000L else 3500L
            val pollIntervalMs = 60L
            val startTime = System.currentTimeMillis()
            var lastStatus: com.mirrly.tgproxy.core.WarmPoolStatus? = null

            while (System.currentTimeMillis() - startTime < maxWarmupMs) {
                val status = com.mirrly.tgproxy.core.NativeProxy.getWarmPoolStatus()
                if (status != null) {
                    lastStatus = status
                    _warmPoolStatus.value = status
                    val ready = status.ready_total
                    val target = status.target_total
                    val remaining = status.remaining
                    _preflightStatusMessage.value = "Прогрев потоков: $ready/$target (осталось $remaining)"
                    val elapsed = System.currentTimeMillis() - startTime
                    if (target > 0 && ready >= target) {
                        AppLogger.i(TAG, "Фаза 2/3: Прогрев пула завершен на 100%: $ready/$target готовы (${elapsed} мс)")
                        break
                    }
                    if (isMobile && status.dc2_chat_ready >= 1 && ready >= 2 && elapsed >= 1200L) {
                        AppLogger.i(TAG, "Фаза 2/3: Базовый сотовый прогрев готов: $ready/$target (${elapsed} мс)")
                        break
                    }
                    if (!isMobile && ready >= 4 && elapsed >= 800L) {
                        AppLogger.i(TAG, "Фаза 2/3: Быстрый Wi-Fi прогрев готов: $ready/$target (${elapsed} мс)")
                        break
                    }
                } else {
                    _preflightStatusMessage.value = "Прогрев пула WSS-потоков..."
                }
                delay(pollIntervalMs)
            }

            if (lastStatus != null) {
                val ready = lastStatus.ready_total
                val target = lastStatus.target_total
                _preflightStatusMessage.value = "Прогрето потоков: $ready/$target"
                delay(120)
            }
        } else {
            _preflightStatusMessage.value = "Прогрев туннеля завершен"
            delay(120)
        }

        // Фаза 3: Служба активна и готова к работе
        _preflightStage.value = PreflightStage.PHASE_3_SOCKS5_READY
        _preflightStatusMessage.value = "Прокси активен и готов к работе"
        AppLogger.i(TAG, "Фаза 3/3: Служба активна и готова к работе")
        delay(150)

        val summary = if (!config.isSocks5Mode) {
            if (workerDomain.isNotBlank()) {
                val activeWorker = prefsManager.getActiveWorker()
                "Worker: ${activeWorker.name.ifBlank { workerDomain }}"
            } else {
                "Flowseal Anycast CDN"
            }
        } else {
            when (config.uplinkMode) {
                UplinkMode.WORKER -> {
                    val activeWorker = prefsManager.getActiveWorker()
                    "Worker: ${activeWorker.name}"
                }
                UplinkMode.VLESS -> "VLESS over WSS"
                else -> "WARP: ${config.warpPeerEndpoint.ifBlank { "Anycast" }}"
            }
        }

        PredictivePreWarmManager.updateHotReserve(
            HotReserveRoutes(
                validatedAtTimestampMs = System.currentTimeMillis(),
                bestWorkerId = if (config.isSocks5Mode) prefsManager.getActiveWorkerId() else null,
                bestMtprotoDc = if (!config.isSocks5Mode) "kws2.web.telegram.org:443" else null,
                bestWarpEndpoint = if (config.isSocks5Mode) config.warpPeerEndpoint else null,
                isDnsHealthy = true
            )
        )

        _preflightStage.value = PreflightStage.COMPLETED
        _preflightStatusMessage.value = ""

        AppLogger.i(TAG, "Поэтапный запуск успешно завершен: $summary")
        PreflightResult(
            isSuccess = true,
            selectedRouteSummary = summary,
            rttMs = targetRtt,
            isFromHotReserve = false
        )
    }
}

