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
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.DnsScope
import com.mirrly.tgproxy.core.DohResolver
import com.mirrly.tgproxy.core.NativeProxy
import com.mirrly.tgproxy.core.PingEngine
import com.mirrly.tgproxy.core.PingProbeResult
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.core.UplinkMode
import com.mirrly.tgproxy.core.WarpEndpointCandidate
import com.mirrly.tgproxy.core.WarpEndpointScanner
import com.mirrly.tgproxy.core.WarpEndpointStage
import com.mirrly.tgproxy.core.WarpProbeProtocol
import com.mirrly.tgproxy.core.WarpPipelineProfiler
import com.mirrly.tgproxy.core.WorkerProfile
import com.mirrly.tgproxy.core.WorkerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Этапы фазы предстартовой диагностики (Pre-flight Phase).
 */
enum class PreflightStage {
    IDLE,
    OPTIMIZING_ROUTE,
    VALIDATING_DNS,
    PROBING_CANDIDATES,
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
 * Перед стартом службы берет быструю паузу (2-3 секунды) на:
 * 1. Валидацию DNS (системный / DoH).
 * 2. Протокольно-зависимый выбор оптимального узла с минимальным RTT (Telegram DC, Cloudflare Worker или WARP Anycast).
 * 3. Формирование горячего резерва проверенных альтернатив для бесшовного failover.
 */
object PreflightDiagnosticsEngine {

    private const val TAG = "PreflightDiagnostics"
    const val NORMAL_MAX_TIMEOUT_MS = 2800L
    const val DEGRADED_MAX_TIMEOUT_MS = 3800L

    private val _preflightStage = MutableStateFlow(PreflightStage.IDLE)
    val preflightStage: StateFlow<PreflightStage> = _preflightStage.asStateFlow()

    private val _preflightStatusMessage = MutableStateFlow("")
    val preflightStatusMessage: StateFlow<String> = _preflightStatusMessage.asStateFlow()

    fun reset() {
        _preflightStage.value = PreflightStage.IDLE
        _preflightStatusMessage.value = ""
    }

    /**
     * Выполняет экспресс-анализ сети и маршрутов перед запуском сокетов прокси.
     * Ограничен жестким тайм-аутом (до 3 сек в нормальной сети, до 4 сек при деградации).
     */
    suspend fun runPreflight(
        context: Context,
        config: ProxyConfig,
        prefsManager: PreferencesManager,
        isDegraded: Boolean = false
    ): PreflightResult = withContext(Dispatchers.IO) {
        val deadlineMs = if (isDegraded) DEGRADED_MAX_TIMEOUT_MS else NORMAL_MAX_TIMEOUT_MS
        _preflightStage.value = PreflightStage.OPTIMIZING_ROUTE
        _preflightStatusMessage.value = context.getString(R.string.status_optimizing_route)

        AppLogger.i(TAG, "Starting Smart Connect Pre-flight analysis (isSocks5=${config.isSocks5Mode}, deadline=${deadlineMs}ms)...")

        val result = withTimeoutOrNull(deadlineMs) {
            // Восстанавливаем пользовательский воркер, если он был временно смещен при failover
            prefsManager.restoreUserPrimaryWorkerIfNeeded()

            // 0. Проверка горячего резерва (если маршрут уже проверен недавно)
            if (PredictivePreWarmManager.hasFreshHotReserve(config.isSocks5Mode)) {
                val reserve = PredictivePreWarmManager.getHotReserve()
                val activeWorker = prefsManager.getActiveWorker()
                // Если выбран пользовательский воркер, проверяем совпадение с кэшем горячего резерва
                if (!activeWorker.isDeveloperWorker && reserve.bestWorkerId != activeWorker.id) {
                    AppLogger.d(TAG, "Hot reserve bestWorker (${reserve.bestWorkerId}) does not match active custom worker '${activeWorker.name}'. Bypassing cached reserve.")
                } else {
                    AppLogger.i(TAG, "Using fresh Hot Reserve route (validated ${System.currentTimeMillis() - reserve.validatedAtTimestampMs}ms ago)")
                    applyHotReserveToConfig(config, prefsManager, reserve)
                    return@withTimeoutOrNull PreflightResult(
                        isSuccess = true,
                        selectedRouteSummary = context.getString(R.string.preflight_hot_reserve_active),
                        isFromHotReserve = true
                    )
                }
            }

            // 1. Валидация DNS-резолвинга
            _preflightStage.value = PreflightStage.VALIDATING_DNS
            _preflightStatusMessage.value = context.getString(R.string.status_validating_dns)
            val isDnsHealthy = validateDns()

            // 2. Протокольно-зависимый выбор оптимального узла
            _preflightStage.value = PreflightStage.PROBING_CANDIDATES
            _preflightStatusMessage.value = context.getString(R.string.status_selecting_optimal_node)

            if (!config.isSocks5Mode) {
                runMtprotoPreflight(config, isDnsHealthy)
            } else {
                runSocks5Preflight(context, config, prefsManager, isDnsHealthy)
            }
        }

        val finalResult = result ?: run {
            AppLogger.w(TAG, "Pre-flight analysis exceeded deadline of ${deadlineMs}ms. Using default route.")
            PreflightResult(
                isSuccess = false,
                selectedRouteSummary = context.getString(R.string.preflight_timeout),
                rttMs = -1L
            )
        }

        _preflightStage.value = if (finalResult.isSuccess) PreflightStage.COMPLETED else PreflightStage.FAILED
        _preflightStatusMessage.value = ""
        AppLogger.i(TAG, "Pre-flight completed: ${finalResult.selectedRouteSummary} (success=${finalResult.isSuccess})")
        finalResult
    }

    private suspend fun validateDns(): Boolean = try {
        withTimeoutOrNull(800L) {
            val addrs = DohResolver.resolve("cloudflare.com", DnsScope.BOOTSTRAP)
            addrs.isNotEmpty()
        } ?: false
    } catch (e: Exception) {
        AppLogger.d(TAG, "Pre-flight DNS probe exception: ${e.message}")
        false
    }

    private suspend fun runMtprotoPreflight(
        config: ProxyConfig,
        isDnsHealthy: Boolean
    ): PreflightResult {
        // Flowseal Anycast CDN узлы (kws2/kws4) и официальные шлюзы Telegram Web
        val flowsealDomains = com.mirrly.tgproxy.core.TgConstants.getWsDomains(2)
            .filter { it.startsWith("kws") }
            .take(2)
            .map { "$it:443" }

        val candidates = (flowsealDomains + listOf(
            "kws2.web.telegram.org:443",
            "kws4.web.telegram.org:443"
        )).distinct()

        val probeResults: List<Pair<String, PingProbeResult>> = PingEngine.parallelProbeCandidates(
            targets = candidates,
            timeoutMs = 600L,
            maxConcurrency = 4
        )

        val responsiveDcs: List<Pair<String, PingProbeResult>> = probeResults
            .filter { it.second.success && it.second.rawRttMs > 0 }
            .sortedBy { it.second.rawRttMs }

        val bestDc = responsiveDcs.firstOrNull()?.first ?: (candidates.firstOrNull() ?: "kws2.web.telegram.org:443")
        val bestRtt = responsiveDcs.firstOrNull()?.second?.rawRttMs ?: -1L
        val fallbackDcs = responsiveDcs.map { it.first }.filter { it != bestDc }

        // The Rust balancer accepts a full kws{dc}.domain:port candidate and
        // normalizes it to the Flowseal base domain. Pin only a route that was
        // actually reachable; the normal CDN race remains the fallback.
        val promoted = if (bestRtt > 0) {
            NativeProxy.promoteMtprotoAnycastDomain(dcId = 2, domain = bestDc)
        } else {
            false
        }
        AppLogger.i(
            TAG,
            "MTProto Anycast preflight winner: $bestDc (${if (bestRtt > 0) "${bestRtt}ms" else "unverified"}), applied=$promoted"
        )

        PredictivePreWarmManager.updateHotReserve(
            HotReserveRoutes(
                validatedAtTimestampMs = System.currentTimeMillis(),
                bestMtprotoDc = bestDc,
                fallbackMtprotoDcs = fallbackDcs,
                isDnsHealthy = isDnsHealthy
            )
        )

        val isSuccess = responsiveDcs.isNotEmpty() || isDnsHealthy
        return PreflightResult(
            isSuccess = isSuccess,
            selectedRouteSummary = "Flowseal Anycast: $bestDc (${if (bestRtt > 0) "${bestRtt}ms" else "OK"})",
            rttMs = bestRtt
        )
    }

    private suspend fun runSocks5Preflight(
        context: Context,
        config: ProxyConfig,
        prefsManager: PreferencesManager,
        isDnsHealthy: Boolean
    ): PreflightResult {
        val uplinkMode = config.uplinkMode
        return when (uplinkMode) {
            UplinkMode.WORKER -> probeAndSelectOptimalWorker(context, prefsManager, isDnsHealthy)
            UplinkMode.MASQUE, UplinkMode.WARP_CASCADE, UplinkMode.AWG -> probeAndSelectOptimalWarp(config, prefsManager, isDnsHealthy)
            UplinkMode.HYBRID -> {
                // В гибридном режиме выбираем лучший Anycast WARP и валидируем активный Worker
                val warpRes = probeAndSelectOptimalWarp(config, prefsManager, isDnsHealthy)
                val workerRes = probeAndSelectOptimalWorker(context, prefsManager, isDnsHealthy)
                PreflightResult(
                    isSuccess = warpRes.isSuccess || workerRes.isSuccess,
                    selectedRouteSummary = "Hybrid: ${warpRes.selectedRouteSummary} + ${workerRes.selectedRouteSummary}",
                    rttMs = if (workerRes.rttMs > 0) workerRes.rttMs else warpRes.rttMs
                )
            }
            UplinkMode.VLESS -> {
                PreflightResult(
                    isSuccess = true,
                    selectedRouteSummary = "VLESS over WSS",
                    rttMs = -1L
                )
            }
        }
    }

    private suspend fun probeAndSelectOptimalWorker(
        context: Context,
        prefsManager: PreferencesManager,
        isDnsHealthy: Boolean
    ): PreflightResult {
        val allWorkers = prefsManager.getDeveloperWorkers() + prefsManager.getCustomWorkers()
        if (allWorkers.isEmpty()) {
            return PreflightResult(false, context.getString(R.string.preflight_no_workers), -1L)
        }

        val activeId = prefsManager.getActiveWorkerId()
        val activeWorker = prefsManager.getActiveWorker(activeId)
        val isCustomActive = !activeWorker.isDeveloperWorker

        if (isCustomActive) {
            // Пользовательский воркер имеет абсолютный приоритет!
            // Он НИКОГДА не заменяется на узлы разработчика (Primary, Alpha, Beta, Gamma, Delta).
            val (status, rtt) = try {
                WorkerPingTester.probeWorkerRelayContract(activeWorker.domain, timeoutMs = 1500L)
            } catch (_: Exception) {
                Pair(WorkerStatus.ERROR_UNREACHABLE, null)
            }
            val isOnline = status == WorkerStatus.ONLINE && (rtt ?: -1L) > 0
            val measuredRtt = if (isOnline) (rtt ?: -1L) else -1L

            if (measuredRtt > 0) {
                WorkerFailoverManager.handleActiveWorkerSuccess(activeWorker.domain, measuredRtt)
            }

            val fallbackIds = allWorkers.filter { it.id != activeWorker.id }.map { it.id }
            PredictivePreWarmManager.updateHotReserve(
                HotReserveRoutes(
                    validatedAtTimestampMs = System.currentTimeMillis(),
                    bestWorkerId = activeWorker.id,
                    fallbackWorkerIds = fallbackIds,
                    isDnsHealthy = isDnsHealthy
                )
            )

            AppLogger.i(TAG, "Pre-flight custom worker retained: '${activeWorker.name}' (${measuredRtt}ms, online=$isOnline)")
            return PreflightResult(
                isSuccess = true,
                selectedRouteSummary = "Worker: ${activeWorker.name} (${if (measuredRtt > 0) "${measuredRtt}ms" else "Custom"})",
                rttMs = measuredRtt
            )
        }

        val devWorkers = prefsManager.getDeveloperWorkers()
        // Опрашиваем до 5 лучших кандидатов разработчика параллельно (активный узел в приоритете)
        val candidatesToProbe = devWorkers
            .sortedByDescending { it.id == activeId }
            .take(5)

        val probeResults = coroutineScope {
            candidatesToProbe.map { worker ->
                async(Dispatchers.IO) {
                    val (status, rtt) = WorkerPingTester.probeWorkerRelayContract(worker.domain, timeoutMs = 1200L)
                    Triple(worker, status, rtt)
                }
            }.awaitAll()
        }

        val onlineWorkers = probeResults
            .filter { it.second == WorkerStatus.ONLINE && (it.third ?: -1L) > 0 }
            .sortedBy { it.third }

        val (chosenWorker, chosenRtt) = if (onlineWorkers.isNotEmpty()) {
            // Если активный девелоперский воркер работает стабильно (RTT < 350мс), сохраняем его
            val activeCandidate = onlineWorkers.find { it.first.id == activeId }
            if (activeCandidate != null && (activeCandidate.third ?: Long.MAX_VALUE) < 350L) {
                activeCandidate.first to (activeCandidate.third ?: -1L)
            } else {
                val best = onlineWorkers.first()
                best.first to (best.third ?: -1L)
            }
        } else {
            (devWorkers.find { it.id == activeId } ?: devWorkers.first()) to -1L
        }

        if (chosenWorker.id != activeId) {
            AppLogger.i(TAG, "Smart Connect: Selected optimal dev worker '${chosenWorker.name}' (${chosenRtt}ms) instead of '$activeId'")
            prefsManager.setActiveWorkerId(chosenWorker.id, fromUserAction = false)
        }
        if (chosenRtt > 0) {
            WorkerFailoverManager.handleActiveWorkerSuccess(chosenWorker.domain, chosenRtt)
        }

        val fallbackIds = onlineWorkers.map { it.first.id }.filter { it != chosenWorker.id }
        PredictivePreWarmManager.updateHotReserve(
            HotReserveRoutes(
                validatedAtTimestampMs = System.currentTimeMillis(),
                bestWorkerId = chosenWorker.id,
                fallbackWorkerIds = fallbackIds,
                isDnsHealthy = isDnsHealthy
            )
        )

        return PreflightResult(
            isSuccess = onlineWorkers.isNotEmpty(),
            selectedRouteSummary = "Worker: ${chosenWorker.name} (${chosenRtt}ms)",
            rttMs = chosenRtt
        )
    }

    private suspend fun probeAndSelectOptimalWarp(
        config: ProxyConfig,
        prefsManager: PreferencesManager,
        isDnsHealthy: Boolean
    ): PreflightResult {
        val probeProto = if (config.isAwgUplink) WarpProbeProtocol.WIREGUARD else WarpProbeProtocol.MASQUE_QUIC

        // 1. Быстрый опрос sticky-профиля (если уже есть проверенный рабочий эндпоинт)
        val sticky = WarpEndpointScanner.getStickyProfile()
        if (sticky != null && sticky.isAlive) {
            val stickyCandidate = WarpEndpointScanner.probeEndpoint(
                endpoint = sticky.endpoint,
                timeoutMs = 250L,
                protocol = probeProto
            )
            if (stickyCandidate.isAlive && stickyCandidate.rttMs > 0) {
                config.warpPeerEndpoint = stickyCandidate.endpoint
                prefsManager.saveConfig(config)
                WarpEndpointScanner.setStickyProfile(stickyCandidate)
                AppLogger.i(TAG, "Smart Connect: Sticky WARP endpoint is responsive: ${stickyCandidate.endpoint} (${stickyCandidate.rttMs}ms)")
                WarpPipelineProfiler.profileWarpPipeline(config, stickyCandidate.endpoint)
                return PreflightResult(
                    isSuccess = true,
                    selectedRouteSummary = "WARP: ${stickyCandidate.endpoint} (${stickyCandidate.rttMs}ms)",
                    rttMs = stickyCandidate.rttMs,
                    isFromHotReserve = true
                )
            }
        }

        // 2. Параллельный экспресс-опрос топ-кандидатов
        val candidates = WarpEndpointScanner.fastProbeParallel(
            endpoints = WarpEndpointScanner.PRECONFIGURED_PROFILES_50.take(10),
            timeoutMs = 600L,
            maxConcurrency = 10,
            protocol = probeProto
        )

        val responsive = candidates
            .filter { it.isAlive && it.rttMs > 0 }
            .sortedWith(compareByDescending<WarpEndpointCandidate> { it.stage.priority }.thenBy { it.rttMs })

        val best = responsive.firstOrNull()
        if (best != null) {
            config.warpPeerEndpoint = best.endpoint
            prefsManager.saveConfig(config)
            WarpEndpointScanner.setStickyProfile(best)
            AppLogger.i(TAG, "Smart Connect: Selected optimal WARP endpoint '${best.endpoint}' (${best.rttMs}ms, ${best.stage.displayName})")
            WarpPipelineProfiler.profileWarpPipeline(config, best.endpoint)
        }

        val fallbackEndpoints = responsive.map { it.endpoint }.filter { it != best?.endpoint }
        PredictivePreWarmManager.updateHotReserve(
            HotReserveRoutes(
                validatedAtTimestampMs = System.currentTimeMillis(),
                bestWarpEndpoint = best?.endpoint,
                fallbackWarpEndpoints = fallbackEndpoints,
                isDnsHealthy = isDnsHealthy
            )
        )

        return PreflightResult(
            isSuccess = responsive.isNotEmpty(),
            selectedRouteSummary = "WARP: ${best?.endpoint ?: "Anycast"} (${best?.rttMs ?: -1L}ms)",
            rttMs = best?.rttMs ?: -1L
        )
    }

    private fun applyHotReserveToConfig(
        config: ProxyConfig,
        prefsManager: PreferencesManager,
        reserve: HotReserveRoutes
    ) {
        if (!config.isSocks5Mode) {
            // MTProto
            if (!reserve.bestMtprotoDc.isNullOrBlank()) {
                AppLogger.d(TAG, "Applying cached MTProto DC: ${reserve.bestMtprotoDc}")
            }
        } else {
            val uplink = config.uplinkMode
            if (uplink == UplinkMode.WORKER && !reserve.bestWorkerId.isNullOrBlank()) {
                val activeWorker = prefsManager.getActiveWorker()
                if (!activeWorker.isDeveloperWorker) {
                    AppLogger.d(TAG, "Preserving user custom worker '${activeWorker.name}' over cached hot reserve '${reserve.bestWorkerId}'")
                } else if (prefsManager.getActiveWorkerId() != reserve.bestWorkerId) {
                    prefsManager.setActiveWorkerId(reserve.bestWorkerId, fromUserAction = false)
                }
            } else if ((uplink == UplinkMode.MASQUE || uplink == UplinkMode.WARP_CASCADE || uplink == UplinkMode.AWG) && !reserve.bestWarpEndpoint.isNullOrBlank()) {
                config.warpPeerEndpoint = reserve.bestWarpEndpoint
                prefsManager.saveConfig(config)
            }
        }
    }
}
