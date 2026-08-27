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

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ConnectException
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Оценка качества соединения на основе задержки, джиттера и процента успешных проб.
 */
enum class ConnectionQuality(val label: String, val level: Int) {
    EXCELLENT("Идеальное", 4),
    GOOD("Хорошее", 3),
    MODERATE("Умеренное", 2),
    POOR("Слабое", 1),
    OFFLINE("Нет связи", 0)
}

/**
 * Классификация сетевых сбоев для точной диагностики и самовосстановления.
 */
enum class FailureType(val description: String) {
    NONE("Норма"),
    DNS_FAILURE("Ошибка DNS / DoH резолвинга"),
    CONNECT_TIMEOUT("Таймаут подключения (> 2500 мс)"),
    HOST_UNREACHABLE("Сервер или воркер недоступен"),
    RATE_LIMITED_429("Превышен лимит запросов Cloudflare (429)"),
    DPI_BLOCKED("Блокировка DPI (сброс TCP/TLS)"),
    TLS_HANDSHAKE_FAILED("Сбой TLS рукопожатия / подмена сертификата"),
    NETWORK_LOST("Сетевой интерфейс отключен"),
    PREDICTIVE_DEGRADATION("Предиктивная деградация (рост RTT и Bufferbloat)"),
    UNKNOWN_ERROR("Сетевой сбой")
}

/**
 * Результат одиночной пробы задержки.
 */
data class PingProbeResult(
    val rawRttMs: Long,
    val success: Boolean,
    val failureType: FailureType = FailureType.NONE,
    val errorDetail: String? = null
)

/**
 * Точка временного ряда для живого графика задержки (Live Latency Sparkline).
 */
data class PingHistoryPoint(
    val timestampMs: Long,
    val rttMs: Long,
    val isSuccess: Boolean
)

/**
 * Неизменяемый снимок текущих телеметрических метрик задержки.
 */
data class PingSnapshot(
    val rawPingMs: Long = -1L,
    val smoothedPingMs: Long = -1L,
    val jitterMs: Long = 0L,
    val minPingMs: Long = -1L,
    val maxPingMs: Long = -1L,
    val minRttMs: Long = -1L,
    val bufferbloatMs: Long = 0L,
    val bufferbloatGrade: String = "A+ (Идеально)",
    val currentAlpha: Double = 0.25,
    val isDegradingTrend: Boolean = false,
    val successRatePercent: Int = 100,
    val quality: ConnectionQuality = ConnectionQuality.OFFLINE,
    val consecutiveFailures: Int = 0,
    val lastFailureType: FailureType = FailureType.NONE,
    val lastProbeTimestamp: Long = 0L,
    val rttHistory: List<PingHistoryPoint> = emptyList(),
    val healthReport: ConnectionHealthReport = ConnectionHealthReport(
        score = 100,
        chatScore = 100,
        chatVerdict = "Идеально для медиа",
        callScore = 100,
        mosScore = 4.50,
        mosGrade = "HD Voice (Отлично)",
        isCallRecommended = true,
        verdict = "Канал в норме",
        detail = "Ожидание первых данных",
        operatorLatencyGrade = "Отлично",
        workerStatusGrade = "Стабилен",
        packetReliabilityGrade = "100%",
        pingMs = -1L,
        jitterMs = 0L,
        successRate = 100,
        isExcellent = true
    )
)

/**
 * Интеллектуальный движок замера задержки и выявления сбоев.
 * Использует алгоритм адаптивного экспоненциального скользящего среднего (Adaptive EWMA RTT & Jitter),
 * отслеживает физический Min-RTT (BBR), вычисляет индекс Bufferbloat и динамически масштабирует фильтр.
 */
class PingEngine(
    private val targetProvider: () -> String,
    private val trafficThroughputProvider: () -> Long = { 0L },
    private val onSelfHealingRequired: (() -> Unit)? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var workerJob: Job? = null
    private val mutex = Mutex()

    // Динамический адаптивный EWMA фильтр задержки
    private var srtt: Double = -1.0
    private var rttvar: Double = 0.0
    private var currentAlpha: Double = 0.25

    private var rawPingMs: Long = -1L
    private var minPingMs: Long = -1L
    private var maxPingMs: Long = -1L

    // Скользящее окно Min-RTT (60 секунд) для детекции физической задержки без очередей
    private val minRttWindow = ArrayDeque<Pair<Long, Long>>()

    // Окно последних 5 замеров RTT для регрессионного анализа тренда деградации
    private val recentRttSamples = ArrayDeque<Long>(5)

    // Скользящее окно истории замеров RTT (последние 60 секунд / 60 проб) для графика Sparkline
    private val rttHistoryWindow = ArrayDeque<PingHistoryPoint>(60)

    private var consecutiveFailures: Int = 0
    private var lastFailureType: FailureType = FailureType.NONE
    private var lastProbeTimestamp: Long = 0L

    // Окно успешности последних 10 проб
    private val historyWindow = ArrayDeque<Boolean>(10)
    private var healingTriggeredForCurrentOutage = false

    @Volatile
    var onProbeCompleted: ((PingProbeResult, targetDomain: String) -> Unit)? = null

    @Volatile
    var onPredictiveDegradation: ((targetDomain: String, currentRtt: Long, minRtt: Long) -> Unit)? = null

    @Volatile
    var currentSnapshot: PingSnapshot = PingSnapshot()
        private set

    val smoothedPingMs: Long
        get() = currentSnapshot.smoothedPingMs

    val jitterMs: Long
        get() = currentSnapshot.jitterMs

    val quality: ConnectionQuality
        get() = currentSnapshot.quality

    val target: String
        get() = targetProvider()

    fun start() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            while (isActive) {
                val probe = executeProbe()
                recordProbe(probe)

                val delayMs = calculateNextProbeDelay(probe.success)
                delay(delayMs)
            }
        }
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
        resetInternal()
    }

    fun reset() {
        resetInternal()
    }

    private fun resetInternal() {
        srtt = -1.0
        rttvar = 0.0
        currentAlpha = 0.25
        rawPingMs = -1L
        minPingMs = -1L
        maxPingMs = -1L
        consecutiveFailures = 0
        lastFailureType = FailureType.NONE
        lastProbeTimestamp = 0L
        historyWindow.clear()
        minRttWindow.clear()
        recentRttSamples.clear()
        rttHistoryWindow.clear()
        healingTriggeredForCurrentOutage = false
        currentSnapshot = PingSnapshot()
    }

    suspend fun triggerSingleProbe(): PingSnapshot = mutex.withLock {
        val probe = executeProbe()
        recordProbe(probe)
        currentSnapshot
    }

    private suspend fun executeProbe(): PingProbeResult {
        val target = targetProvider().trim()
        if (target.isBlank()) {
            return PingProbeResult(
                rawRttMs = -1L,
                success = false,
                failureType = FailureType.DNS_FAILURE,
                errorDetail = "Пустой адрес целевого сервера"
            )
        }

        return try {
            val hostClean = target
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("wss://")
                .substringBefore(":")
                .substringBefore("/")

            val addrs = DohResolver.resolve(hostClean)
            if (addrs.isEmpty()) {
                return PingProbeResult(
                    rawRttMs = -1L,
                    success = false,
                    failureType = FailureType.DNS_FAILURE,
                    errorDetail = "Не удалось разрешить IP-адрес для $hostClean (DoH / DNS Failure)"
                )
            }

            val raceResult = HappyEyeballsEngine.raceConnect(
                addresses = addrs,
                port = 443,
                attemptDelayMs = 200L,
                timeoutMs = 2500L
            )

            if (raceResult != null) {
                PingProbeResult(
                    rawRttMs = raceResult.handshakeRttMs.coerceAtLeast(1L),
                    success = true,
                    failureType = FailureType.NONE
                )
            } else {
                PingProbeResult(
                    rawRttMs = -1L,
                    success = false,
                    failureType = FailureType.CONNECT_TIMEOUT,
                    errorDetail = "Таймаут подключения (Happy Eyeballs опрос ${addrs.size} IP не удался)"
                )
            }
        } catch (e: Exception) {
            val fType = DpiAnomalyDetector.classifyException(e)
            PingProbeResult(-1L, false, fType, e.message)
        }
    }

    @Synchronized
    fun recordProbe(probe: PingProbeResult) {
        val now = System.currentTimeMillis()
        lastProbeTimestamp = now

        // Обновление скользящего окна успешности
        if (historyWindow.size >= 10) {
            historyWindow.removeFirst()
        }
        historyWindow.addLast(probe.success)

        val successCount = historyWindow.count { it }
        val successRate = if (historyWindow.isNotEmpty()) (successCount * 100) / historyWindow.size else 100

        if (probe.success && probe.rawRttMs > 0L) {
            rawPingMs = probe.rawRttMs
            consecutiveFailures = 0
            lastFailureType = FailureType.NONE
            healingTriggeredForCurrentOutage = false

            // Сохранение последних замеров для трендового анализа
            recentRttSamples.addLast(probe.rawRttMs)
            if (recentRttSamples.size > 5) {
                recentRttSamples.removeFirst()
            }

            // 1. Обновление скользящего окна Min-RTT (60 секунд)
            minRttWindow.addLast(Pair(now, probe.rawRttMs))
            while (minRttWindow.isNotEmpty() && ((now - minRttWindow.first().first) > 60_000L)) {
                minRttWindow.removeFirst()
            }

            // 2. Расчет динамического адаптивного EWMA SRTT и Jitter
            val sample = probe.rawRttMs.toDouble()
            if (srtt < 0.0) {
                srtt = sample
                rttvar = sample / 2.0
                minPingMs = probe.rawRttMs
                maxPingMs = probe.rawRttMs
                currentAlpha = 0.25
            } else {
                val delta = abs(srtt - sample)
                val relDelta = delta / kotlin.math.max(10.0, srtt)

                // Динамическая адаптация alpha:
                // - Стабильный канал: alpha = 0.125 (шумоподавление на Wi-Fi/LTE)
                // - Умеренная вариация: alpha = 0.25
                // - Резкий скачок / сдвиг маршрута (relDelta > 0.50): alpha = 0.50 (быстрая сходимость)
                val (alpha, beta) = when {
                    relDelta > 0.50 -> Pair(0.50, 0.35)
                    relDelta <= 0.15 && rttvar <= 15.0 -> Pair(0.125, 0.25)
                    else -> Pair(0.25, 0.25)
                }
                currentAlpha = alpha

                srtt = (1.0 - alpha) * srtt + alpha * sample
                rttvar = (1.0 - beta) * rttvar + beta * delta

                if (probe.rawRttMs < minPingMs || minPingMs < 0L) minPingMs = probe.rawRttMs
                if (probe.rawRttMs > maxPingMs) maxPingMs = probe.rawRttMs
            }
        } else {
            consecutiveFailures++
            lastFailureType = probe.failureType

            // При 3 последовательных сбоях инициируем самовосстановление
            if (consecutiveFailures >= 3 && !healingTriggeredForCurrentOutage) {
                healingTriggeredForCurrentOutage = true
                AppLogger.w(
                    "PingEngine",
                    "Зафиксировано 3 сбоя подряд (${probe.failureType.description}: ${probe.errorDetail ?: "Таймаут"}). Запуск самовосстановления..."
                )
                onSelfHealingRequired?.invoke()
            }
        }

        val smoothed = if (consecutiveFailures >= 3) -1L else srtt.roundToLong().coerceAtLeast(-1L)
        val jitter = if (consecutiveFailures >= 3) 0L else rttvar.roundToLong().coerceAtLeast(0L)
        val quality = evaluateQuality(smoothed, jitter, consecutiveFailures, successRate)

        // 3. Вычисление физического Min-RTT и Bufferbloat
        val calculatedMinRtt = if (consecutiveFailures >= 3 || minRttWindow.isEmpty()) {
            -1L
        } else {
            minRttWindow.minOfOrNull { it.second } ?: rawPingMs
        }

        val bufferbloatMs = if (smoothed > 0L && calculatedMinRtt > 0L) {
            maxOf(0L, smoothed - calculatedMinRtt)
        } else {
            0L
        }

        val bloatGrade = when {
            smoothed <= 0L || calculatedMinRtt <= 0L -> "—"
            bufferbloatMs <= 10L -> "A+ (Идеально)"
            bufferbloatMs <= 30L -> "A (Хорошо)"
            bufferbloatMs <= 75L -> "B (Умеренно)"
            bufferbloatMs <= 150L -> "C (Повышено)"
            else -> "D (Критично)"
        }

        // 4. Предиктивная детекция тренда деградации узла
        val isDegrading = if (consecutiveFailures < 3) {
            detectDegradationTrend(smoothed, calculatedMinRtt, jitter)
        } else {
            false
        }

        if (isDegrading) {
            AppLogger.w(
                "PingEngine",
                "Обнаружен предиктивный тренд деградации узла (SRTT: ${smoothed}мс, Min-RTT: ${calculatedMinRtt}мс, Jitter: ${jitter}мс)"
            )
            try {
                val target = targetProvider().trim()
                onPredictiveDegradation?.invoke(target, smoothed, calculatedMinRtt)
            } catch (_: Exception) {}
        }

        // Сохранение точки в историю для живого Sparkline-графика задержки
        rttHistoryWindow.addLast(
            PingHistoryPoint(
                timestampMs = now,
                rttMs = if (probe.success) probe.rawRttMs else -1L,
                isSuccess = probe.success
            )
        )
        while (rttHistoryWindow.isNotEmpty() && ((now - rttHistoryWindow.first().timestampMs > 60_000L) || rttHistoryWindow.size > 60)) {
            rttHistoryWindow.removeFirst()
        }

        val healthReport = ConnectionHealthEngine.computeHealth(
            smoothedPingMs = smoothed,
            jitterMs = jitter,
            successRatePercent = successRate,
            lastFailureType = lastFailureType
        )

        currentSnapshot = PingSnapshot(
            rawPingMs = if (consecutiveFailures >= 3) -1L else rawPingMs,
            smoothedPingMs = smoothed,
            jitterMs = jitter,
            minPingMs = minPingMs,
            maxPingMs = maxPingMs,
            minRttMs = calculatedMinRtt,
            bufferbloatMs = bufferbloatMs,
            bufferbloatGrade = bloatGrade,
            currentAlpha = currentAlpha,
            isDegradingTrend = isDegrading,
            successRatePercent = successRate,
            quality = quality,
            consecutiveFailures = consecutiveFailures,
            lastFailureType = lastFailureType,
            lastProbeTimestamp = lastProbeTimestamp,
            rttHistory = rttHistoryWindow.toList(),
            healthReport = healthReport
        )

        try {
            val target = targetProvider().trim()
            onProbeCompleted?.invoke(probe, target)
        } catch (_: Exception) {}
    }

    private fun detectDegradationTrend(smoothedRtt: Long, minRtt: Long, jitter: Long): Boolean {
        if (recentRttSamples.size < 4 || minRtt <= 0L || smoothedRtt < 220L) return false

        val samples = recentRttSamples.toList()
        val n = samples.size

        // 1. Монотонный эскалационный рост задержки (3 последних замера подряд строго растут)
        val isMonotonicRise = samples[n - 1] > samples[n - 2] &&
                samples[n - 2] > samples[n - 3] &&
                samples[n - 1] >= (minRtt * 2.2).toLong()

        // 2. Линейный наклон регрессии тренда OLS (Slope >= 30.0 мс/шаг)
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0
        for (i in 0 until n) {
            val x = i.toDouble()
            val y = samples[i].toDouble()
            sumX += x
            sumY += y
            sumXY += x * y
            sumXX += x * x
        }
        val slope = (n * sumXY - sumX * sumY) / (n * sumXX - sumX * sumX)
        val isSteepSlope = slope >= 30.0 && smoothedRtt >= (minRtt * 2.0).toLong()

        // 3. Критический Bufferbloat
        val isCriticalBloat = (smoothedRtt - minRtt) >= 250L && jitter >= 45L

        return (isMonotonicRise || isSteepSlope || isCriticalBloat) && smoothedRtt >= 250L
    }

    private fun evaluateQuality(
        smoothedPing: Long,
        jitter: Long,
        failures: Int,
        successRate: Int
    ): ConnectionQuality {
        return NetworkConditionEvaluator.evaluateConnectionQuality(
            smoothedPingMs = smoothedPing,
            jitterMs = jitter,
            consecutiveFailures = failures,
            successRatePercent = successRate
        )
    }

    /**
     * Динамический интервал между пробами:
     * - При активном трафике не нагружаем радиомодем (15 сек).
     * - В покое — каждые 5 сек.
     * - При сбоях — адаптивный backoff для быстрой диагностики (1.5с -> 3с -> 6с).
     */
    private fun calculateNextProbeDelay(lastSuccess: Boolean): Long {
        if (!lastSuccess) {
            return when (consecutiveFailures) {
                1 -> 1500L
                2 -> 3000L
                else -> 6000L
            }
        }
        val throughput = trafficThroughputProvider()
        return if (throughput > 102_400L) { // > 100 KB/s
            15000L
        } else {
            5000L
        }
    }

    companion object {
        const val MIN_RTT_WINDOW_MS = 60_000L // 60-секундное скользящее окно Min-RTT
    }
}
