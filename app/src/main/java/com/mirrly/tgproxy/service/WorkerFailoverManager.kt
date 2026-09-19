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

import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.CircuitState
import com.mirrly.tgproxy.core.EstablishmentStage
import com.mirrly.tgproxy.core.FailureType
import com.mirrly.tgproxy.core.WorkerCircuitRecord
import com.mirrly.tgproxy.core.WorkerProfile
import com.mirrly.tgproxy.core.WorkerStatus
import com.mirrly.tgproxy.core.WorkerSwitchPolicy
import com.mirrly.tgproxy.core.PingProbeResult
import com.mirrly.tgproxy.core.PingSnapshot
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FailoverEvent(
    val fromWorkerName: String,
    val toWorkerName: String,
    val reason: FailureType,
    val timestamp: Long = System.currentTimeMillis()
)

data class FailoverState(
    val isFailoverActive: Boolean = false,
    val originalPrimaryWorkerId: String? = null,
    val activeWorkerId: String = "dev_default",
    val lastEvent: FailoverEvent? = null
)

object WorkerFailoverManager {
    const val MIN_FAILOVER_COOLDOWN_MS = 30_000L
    const val ANTI_PING_PONG_WINDOW_MS = 60_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recoveryJob: Job? = null
    private val mutex = Mutex()

    private val circuitRecords = ConcurrentHashMap<String, WorkerCircuitRecord>()
    private val switchPolicy = WorkerSwitchPolicy()
    private var lastObservationMs = Long.MIN_VALUE

    @Volatile
    var lastFailoverTimestampMs: Long = 0L
        private set

    private val _failoverState = MutableStateFlow(FailoverState())
    val failoverState: StateFlow<FailoverState> = _failoverState.asStateFlow()

    fun init() {
        val app = MirrlyApplication.instance
        val allWorkers = app.prefsManager.getDeveloperWorkers() + app.prefsManager.getCustomWorkers()
        for (w in allWorkers) {
            circuitRecords.putIfAbsent(w.id, WorkerCircuitRecord(workerId = w.id, domain = w.domain))
        }
    }

    fun startRecoveryWatchdogIfNeeded() {
        val app = MirrlyApplication.instance
        if (!app.proxyServer.isRunning || !app.config.isSocks5Mode || !app.prefsManager.isAutoFailoverEnabled()) return
        if (recoveryJob?.isActive == true) return

        val hasOpenCircuits = circuitRecords.values.any { it.state == CircuitState.OPEN || it.state == CircuitState.HALF_OPEN }
        if (!hasOpenCircuits) return

        recoveryJob = scope.launch {
            AppLogger.d("WorkerFailover", "Started adaptive Recovery Watchdog to check quarantined nodes")
            while (isActive) {
                delay(15000)

                if (!app.proxyServer.isRunning || !app.config.isSocks5Mode || !app.prefsManager.isAutoFailoverEnabled()) {
                    AppLogger.d("WorkerFailover", "Proxy service stopped or failover disabled. Stopping Watchdog.")
                    break
                }

                val openRecords = circuitRecords.values.filter { it.state == CircuitState.OPEN || it.state == CircuitState.HALF_OPEN }
                if (openRecords.isEmpty()) {
                    AppLogger.d("WorkerFailover", "All nodes in CLOSED state. Watchdog finished.")
                    break
                }

                checkAndProbeRecoveredWorkers()

                val remainingOpen = circuitRecords.values.filter { it.state == CircuitState.OPEN || it.state == CircuitState.HALF_OPEN }
                if (remainingOpen.isEmpty()) {
                    AppLogger.i("WorkerFailover", "All failed nodes successfully recovered. Watchdog finished.")
                    break
                }
            }
            recoveryJob = null
        }
    }

    fun stopRecoveryWatchdog() {
        recoveryJob?.cancel()
        recoveryJob = null
    }

    fun getCircuitRecord(workerId: String): WorkerCircuitRecord? = circuitRecords[workerId]

    fun getAllCircuitRecords(): Map<String, WorkerCircuitRecord> = circuitRecords.toMap()

    /** Preflight diagnostics may update health, but are not a continuous probe series. */
    fun handleActiveWorkerSuccess(domain: String, rttMs: Long) {
        val app = MirrlyApplication.instance
        val worker = (app.prefsManager.getDeveloperWorkers() + app.prefsManager.getCustomWorkers())
            .find { it.id == app.prefsManager.getActiveWorkerId() && it.domain.equals(domain, true) }
            ?: return
        circuitRecords.getOrPut(worker.id) {
            WorkerCircuitRecord(workerId = worker.id, domain = worker.domain)
        }.recordSuccess(rttMs)
    }

    /** One ordered observation path for both successful and failed probes. */
    suspend fun handleActiveWorkerProbe(
        probe: PingProbeResult,
        domain: String,
        snapshot: PingSnapshot,
        networkGeneration: Long,
        observedAtMs: Long
    ) = mutex.withLock {
        val app = MirrlyApplication.instance
        if (!app.proxyServer.isRunning || !app.config.isSocks5Mode) return@withLock
        if (!app.prefsManager.isAutoFailoverEnabled()) return@withLock
        if (networkGeneration != app.proxyServer.currentProfileGeneration.get()) return@withLock
        if (observedAtMs <= lastObservationMs) return@withLock


        val activeId = app.prefsManager.getActiveWorkerId()
        val allWorkers = app.prefsManager.getDeveloperWorkers() + app.prefsManager.getCustomWorkers()
        // A late result from the previous worker must never penalize the new one.
        val activeWorker = allWorkers.find {
            it.id == activeId && it.domain.equals(domain, ignoreCase = true)
        } ?: return@withLock
        lastObservationMs = observedAtMs
        val environment = app.proxyServer.effectiveNetworkProfile
        if (probe.failureType == FailureType.NETWORK_LOST || !environment.validated || environment.suspended) {
            switchPolicy.observe(activeId, networkGeneration, false, observedAtMs)
            return@withLock
        }

        val record = circuitRecords.getOrPut(activeId) {
            WorkerCircuitRecord(workerId = activeId, domain = activeWorker.domain)
        }
        if (probe.success) record.recordSuccess(probe.rawRttMs)
        else record.recordFailure(probe.failureType, networkGeneration = networkGeneration)

        val degraded = !probe.success || snapshot.smoothedPingMs > 350L ||
            snapshot.jitterMs > 60L || snapshot.bufferbloatMs >= 120L || environment.congested
        if (!switchPolicy.observe(activeId, networkGeneration, degraded, observedAtMs)) return@withLock

        val isCustomActive = !activeWorker.isDeveloperWorker

        if (!probe.success && record.state == CircuitState.OPEN) {
            triggerFailover(activeWorker, probe.failureType, allWorkers)
        } else if (probe.success && !isCustomActive) {
            // Only measured, meaningfully faster candidates may replace a working route for developer workers.
            // Custom user workers have absolute priority and must NOT be replaced by predictive degradation.
            val better = allWorkers.filter { candidate ->
                val rec = circuitRecords[candidate.id]
                val rtt = rec?.lastProbeRttMs
                candidate.id != activeId && rec?.isAvailableForRouting == true &&
                    rtt != null && rtt > 0 && rtt < snapshot.smoothedPingMs * 0.70 &&
                    switchPolicy.canSelect(candidate.id, observedAtMs)
            }.maxByOrNull { circuitRecords[it.id]!!.computeQualityScore(!it.isDeveloperWorker) }
            if (better != null) {
                triggerFailover(activeWorker, FailureType.PREDICTIVE_DEGRADATION, listOf(better))
            }
        }
        startRecoveryWatchdogIfNeeded()
    }

    private fun triggerFailover(
        brokenWorker: WorkerProfile,
        reason: FailureType,
        allWorkers: List<WorkerProfile>
    ) {
        val app = MirrlyApplication.instance
        val now = System.nanoTime() / 1_000_000L
        val candidates = allWorkers.filter {
            it.id != brokenWorker.id && switchPolicy.canSelect(it.id, now)
        }

        // Если упал пользовательский воркер, в первую очередь ищем альтернативные пользовательские узлы
        val isBrokenCustom = !brokenWorker.isDeveloperWorker
        val customCandidates = candidates.filter { !it.isDeveloperWorker }
        val poolToSearch = if (isBrokenCustom && customCandidates.isNotEmpty()) customCandidates else candidates

        // Вычисляем рейтинг кандидатов по формуле Quality Score
        var bestWorker: WorkerProfile? = null
        var highestScore = -1.0

        val hotReserveCandidate = PredictivePreWarmManager.getNextFallbackWorker(brokenWorker.id, poolToSearch)
        if (hotReserveCandidate != null && circuitRecords[hotReserveCandidate.id]?.state != CircuitState.OPEN) {
            bestWorker = hotReserveCandidate
            AppLogger.i("WorkerFailover", "Failover: selected pre-warmed hot reserve worker '${bestWorker.name}'")
        } else {
            for (candidate in poolToSearch) {
                val rec = circuitRecords.getOrPut(candidate.id) {
                    WorkerCircuitRecord(workerId = candidate.id, domain = candidate.domain)
                }
                if (rec.state != CircuitState.OPEN) {
                    val score = rec.computeQualityScore(isCustomWorker = !candidate.isDeveloperWorker)
                    if (score > highestScore) {
                        highestScore = score
                        bestWorker = candidate
                    }
                }
            }
        }

        if (bestWorker == null) {
            bestWorker = poolToSearch.firstOrNull { circuitRecords[it.id]?.state != CircuitState.OPEN }
                ?: candidates.firstOrNull { circuitRecords[it.id]?.state != CircuitState.OPEN }
        }

        if (bestWorker != null) {
            val selected = bestWorker
            val switched = app.proxyServer.networkStabilizationGate.tryChange {
                if (app.prefsManager.getActiveWorkerId() != brokenWorker.id) return@tryChange false
                // fromUserAction = false: не перезаписываем постоянный выбор пользователя (user_primary_worker_id)
                app.prefsManager.setActiveWorkerId(selected.id, fromUserAction = false)
                switchPolicy.switched(brokenWorker.id, now)
                lastFailoverTimestampMs = System.currentTimeMillis()
                true
            }
            if (!switched) return
            app.proxyServer.adaptiveController.beginRecovery(System.nanoTime() / 1_000_000L)
            val event = FailoverEvent(
                fromWorkerName = brokenWorker.name,
                toWorkerName = bestWorker.name,
                reason = reason
            )

            val currentFailState = _failoverState.value
            val originalPrimary = currentFailState.originalPrimaryWorkerId ?: brokenWorker.id

            _failoverState.value = FailoverState(
                isFailoverActive = true,
                originalPrimaryWorkerId = originalPrimary,
                activeWorkerId = bestWorker.id,
                lastEvent = event
            )

            AppLogger.i(
                "WorkerFailover",
                "Failover: Seamless switch from '${brokenWorker.name}' to '${bestWorker.name}' (${bestWorker.domain})"
            )

        } else {
            AppLogger.e("WorkerFailover", "Failover: All available workers in pool are in quarantine!")
        }
    }

    private suspend fun checkAndProbeRecoveredWorkers() {
        val app = MirrlyApplication.instance
        val allWorkers = app.prefsManager.getDeveloperWorkers() + app.prefsManager.getCustomWorkers()
        val currentNetGen = app.proxyServer.currentProfileGeneration.get()

        for (worker in allWorkers) {
            val record = circuitRecords.getOrPut(worker.id) {
                WorkerCircuitRecord(workerId = worker.id, domain = worker.domain)
            }

            if (record.checkCooldownExpiration(currentNetGen)) {
                // В состоянии HALF_OPEN допускается ровно ОДНА пробная попытка, предотвращая толпу
                if (!record.tryAcquireHalfOpenTrial()) {
                    continue
                }

                try {
                    // Проверка реального контракта эндпоинта воркера:
                    // Обычный root HTTP 200 (например, HTML стороннего сайта) категорически НЕ закрывает circuit /tcp-v2
                    val requireRelayContract = record.failureStage == EstablishmentStage.READY ||
                            record.failureStage == EstablishmentStage.WSS ||
                            record.lastFailureReason == FailureType.RELAY_ACK_FAILED

                    val res = WorkerPingTester.probeWorkerRelayContract(worker.domain)
                    if (res.first == WorkerStatus.ONLINE && res.second != null) {
                        record.recordSuccess(res.second!!)
                        AppLogger.i("WorkerFailover", "Worker '${worker.name}' confirmed relay contract (RTT: ${res.second}ms). Released from quarantine.")

                        // Если восстановился исходный primary воркер пользователя, переключаемся обратно!
                        val currentState = _failoverState.value
                        if (currentState.isFailoverActive && currentState.originalPrimaryWorkerId == worker.id) {
                            AppLogger.i("WorkerFailover", "Original primary worker '${worker.name}' recovered! Reverting failover.")
                            app.prefsManager.setActiveWorkerId(worker.id, fromUserAction = false)
                            _failoverState.value = FailoverState(isFailoverActive = false)
                        }
                    } else {
                        val fType = when (res.first) {
                            WorkerStatus.RATE_LIMITED_429 -> FailureType.RATE_LIMITED_429
                            else -> if (requireRelayContract) FailureType.RELAY_ACK_FAILED else FailureType.CONNECT_TIMEOUT
                        }
                        record.recordFailure(fType, networkGeneration = currentNetGen)
                    }
                } catch (_: Exception) {
                    record.releaseHalfOpenTrial()
                }
            }
        }
    }

    fun onProxyStopped() {
        stopRecoveryWatchdog()
        _failoverState.value = FailoverState(isFailoverActive = false)
    }

    /**
     * Экстренный принудительный разрыв всех текущих сессий (Emergency Kill).
     * Не вызывается при штатном failover; служит отдельной операторской или аварийной командой.
     */
    fun emergencyKillExistingFlows(reason: String = "manual_emergency_kill") {
        AppLogger.w("WorkerFailover", "Emergency kill of all active flows requested (reason=$reason)")
        val app = MirrlyApplication.instance
        app.proxyServer.emergencyKillAllSockets(reason)
    }
}
