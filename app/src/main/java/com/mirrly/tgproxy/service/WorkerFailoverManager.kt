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
import com.mirrly.tgproxy.core.FailureType
import com.mirrly.tgproxy.core.WorkerCircuitRecord
import com.mirrly.tgproxy.core.WorkerProfile
import com.mirrly.tgproxy.core.WorkerStatus
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recoveryJob: Job? = null
    private val mutex = Mutex()

    private val circuitRecords = ConcurrentHashMap<String, WorkerCircuitRecord>()

    private val _failoverState = MutableStateFlow(FailoverState())
    val failoverState: StateFlow<FailoverState> = _failoverState.asStateFlow()

    fun init() {
        val app = MirrlyApplication.instance
        val allWorkers = app.prefsManager.getDeveloperWorkers() + app.prefsManager.getCustomWorkers()
        for (w in allWorkers) {
            circuitRecords.putIfAbsent(w.id, WorkerCircuitRecord(workerId = w.id, domain = w.domain))
        }

        startRecoveryWatchdog()
    }

    private fun startRecoveryWatchdog() {
        if (recoveryJob?.isActive == true) return
        recoveryJob = scope.launch {
            while (isActive) {
                delay(15000) // Фоновый опрос каждые 15 сек
                checkAndProbeRecoveredWorkers()
            }
        }
    }

    fun getCircuitRecord(workerId: String): WorkerCircuitRecord? = circuitRecords[workerId]

    fun getAllCircuitRecords(): Map<String, WorkerCircuitRecord> = circuitRecords.toMap()

    /**
     * Обработка сетевого сбоя от PingEngine на активном воркере.
     */
    suspend fun handleActiveWorkerFailure(failureType: FailureType, domain: String) = mutex.withLock {
        val app = MirrlyApplication.instance
        if (!app.prefsManager.isAutoFailoverEnabled()) return@withLock

        val activeId = app.prefsManager.getActiveWorkerId()
        val allWorkers = app.prefsManager.getDeveloperWorkers() + app.prefsManager.getCustomWorkers()
        val activeWorker = allWorkers.find { it.id == activeId || it.domain.equals(domain, ignoreCase = true) } ?: return@withLock

        val record = circuitRecords.getOrPut(activeWorker.id) {
            WorkerCircuitRecord(workerId = activeWorker.id, domain = activeWorker.domain)
        }

        record.recordFailure(failureType)

        if (record.state == CircuitState.OPEN) {
            val isDpi = com.mirrly.tgproxy.core.DpiAnomalyDetector.isDpiOrCensorship(failureType)
            val logLabel = if (isDpi) "Instant Deep Failover (DPI/Лимит)" else "Circuit Breaker"
            AppLogger.w(
                "WorkerFailover",
                "$logLabel сработал для '${activeWorker.name}' (${failureType.description}). Мгновенный переход на резервный узел..."
            )
            triggerFailover(activeWorker, failureType, allWorkers)
        }
    }

    /**
     * Обработка успешного замера задержки на активном воркере.
     */
    fun handleActiveWorkerSuccess(domain: String, rttMs: Long) {
        val app = MirrlyApplication.instance
        val activeId = app.prefsManager.getActiveWorkerId()
        val record = circuitRecords.getOrPut(activeId) {
            WorkerCircuitRecord(workerId = activeId, domain = domain)
        }
        record.recordSuccess(rttMs)
    }

    /**
     * Предиктивная обработка деградации активного воркера (рост тренда задержки и Bufferbloat).
     * Выполняет упреждающее переключение на здоровый резервный узел без ожидания жесткого сбоя связи.
     */
    suspend fun handleActiveWorkerDegradation(domain: String, currentRtt: Long, minRtt: Long) = mutex.withLock {
        val app = MirrlyApplication.instance
        if (!app.prefsManager.isAutoFailoverEnabled()) return@withLock

        val activeId = app.prefsManager.getActiveWorkerId()
        val allWorkers = app.prefsManager.getDeveloperWorkers() + app.prefsManager.getCustomWorkers()
        val activeWorker = allWorkers.find { it.id == activeId || it.domain.equals(domain, ignoreCase = true) } ?: return@withLock

        // Ищем в пуле доступного кандидата с существенно лучшим качеством (RTT < 0.70 * currentRtt)
        val candidates = allWorkers.filter { it.id != activeWorker.id }
        var bestAlternative: WorkerProfile? = null
        var highestScore = -1.0

        for (candidate in candidates) {
            val rec = circuitRecords[candidate.id]
            if (rec != null && rec.isAvailableForRouting) {
                val candidateRtt = rec.lastProbeRttMs ?: 120L
                if (candidateRtt < (currentRtt * 0.70).toLong()) {
                    val score = rec.computeQualityScore(isCustomWorker = !candidate.isDeveloperWorker)
                    if (score > highestScore) {
                        highestScore = score
                        bestAlternative = candidate
                    }
                }
            }
        }

        if (bestAlternative != null) {
            AppLogger.w(
                "WorkerFailover",
                "Предиктивный триггер: Зафиксирована деградация узла '${activeWorker.name}' (RTT: ${currentRtt}мс, Min-RTT: ${minRtt}мс). Упреждающий переход на '${bestAlternative.name}'..."
            )
            val record = circuitRecords.getOrPut(activeWorker.id) {
                WorkerCircuitRecord(workerId = activeWorker.id, domain = activeWorker.domain)
            }
            record.recordFailure(FailureType.PREDICTIVE_DEGRADATION, customCooldownMs = 45_000L)
            triggerFailover(activeWorker, FailureType.PREDICTIVE_DEGRADATION, allWorkers)
        }
    }

    private fun triggerFailover(
        brokenWorker: WorkerProfile,
        reason: FailureType,
        allWorkers: List<WorkerProfile>
    ) {
        val app = MirrlyApplication.instance
        val candidates = allWorkers.filter { it.id != brokenWorker.id }

        // Вычисляем рейтинг кандидатов по формуле Quality Score
        var bestWorker: WorkerProfile? = null
        var highestScore = -1.0

        for (candidate in candidates) {
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

        if (bestWorker == null) {
            bestWorker = candidates.firstOrNull { circuitRecords[it.id]?.state != CircuitState.OPEN }
        }

        if (bestWorker != null) {
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
                "Failover: Бесшовное переключение с '${brokenWorker.name}' на '${bestWorker.name}' (${bestWorker.domain})"
            )

            // Переключаем активный домен без перезапуска сокетов Telegram
            app.prefsManager.setActiveWorkerId(bestWorker.id)
        } else {
            AppLogger.e("WorkerFailover", "Failover: Все доступные воркеры в пуле находятся в состоянии карантина!")
        }
    }

    private suspend fun checkAndProbeRecoveredWorkers() {
        val app = MirrlyApplication.instance
        val allWorkers = app.prefsManager.getDeveloperWorkers() + app.prefsManager.getCustomWorkers()

        for (worker in allWorkers) {
            val record = circuitRecords.getOrPut(worker.id) {
                WorkerCircuitRecord(workerId = worker.id, domain = worker.domain)
            }

            if (record.checkCooldownExpiration()) {
                val res = WorkerPingTester.pingWorker(worker.domain)
                if (res.first == WorkerStatus.ONLINE && res.second != null) {
                    record.recordSuccess(res.second!!)
                    AppLogger.i("WorkerFailover", "Воркер '${worker.name}' успешно восстановился (RTT: ${res.second}мс). Выведен из карантина.")
                } else {
                    record.recordFailure(if (res.first == WorkerStatus.RATE_LIMITED_429) FailureType.RATE_LIMITED_429 else FailureType.CONNECT_TIMEOUT)
                }
            }
        }
    }
}
