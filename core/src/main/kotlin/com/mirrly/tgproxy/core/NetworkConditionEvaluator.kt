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

/**
 * Комплексное решение по адаптивной настройке сетевого стека прокси-сервера.
 */
data class NetworkEvaluationDecision(
    val recommendedPoolSize: Int,
    val recommendedBufferSizeBytes: Int,
    val recommendedTcpNoDelay: Boolean,
    val connectionQuality: ConnectionQuality,
    val isInteractiveVoipActive: Boolean,
    val isBufferbloatMitigationActive: Boolean,
    val summary: String
)

/**
 * Единый централизованный анализатор сетевых условий и регулятор параметров сетевого тракта.
 * Выполняет сквозную гармонизацию порогов задержки, джиттера, скорости передачи,
 * предотвращает перегрузку буферов (Bufferbloat Mitigation) и управляет алгоритмом Нагла (TCP_NODELAY).
 */
object NetworkConditionEvaluator {

    // Пороги пропускной способности (Throughput Tiers)
    const val SPEED_ULTRA_BPS = 6_291_456L     // >= 6 МБ/с -> 16 сокетов
    const val SPEED_TURBO_BPS = 1_572_864L     // >= 1.5 МБ/с -> 8 сокетов
    const val SPEED_BALANCED_BPS = 153_600L    // >= 150 КБ/с -> 4 сокета
    const val SPEED_INTERACTIVE_MAX_BPS = 512_000L // < 500 КБ/с -> Интерактивный приоритет

    // Размеры кольцевых буферов
    const val BUFFER_SIZE_ULTRA = 2_097_152    // 2 МБ
    const val BUFFER_SIZE_TURBO = 1_048_576    // 1 МБ
    const val BUFFER_SIZE_BALANCED = 262_144   // 256 КБ
    const val BUFFER_SIZE_ECO = 131_072        // 128 КБ

    // Критические пороги деградации тракта
    const val LATENCY_CONGESTION_MS = 500L     // Порог тяжелой задержки
    const val JITTER_CONGESTION_MS = 100L      // Порог тяжелого джиттера
    const val BUFFERBLOAT_CONGESTION_MS = 150L // Порог тяжелого раздувания очередей

    /**
     * Вычисляет оптимальный профиль сетевого стека.
     */
    fun evaluate(
        throughputBps: Long,
        smoothedPingMs: Long,
        minRttMs: Long,
        jitterMs: Long,
        successRatePercent: Int,
        consecutiveFailures: Int = 0,
        mosScore: Double = 4.50,
        isCallRecommended: Boolean = true,
        qosThrottleLevel: QoSThrottleLevel = QoSThrottleLevel.NONE,
        isAutoSpeedPreset: Boolean = true,
        baseTcpNoDelay: Boolean = true
    ): NetworkEvaluationDecision {
        val quality = evaluateConnectionQuality(
            smoothedPingMs = smoothedPingMs,
            jitterMs = jitterMs,
            consecutiveFailures = consecutiveFailures,
            successRatePercent = successRatePercent
        )

        val bufferbloatMs = if (smoothedPingMs > 0L && minRttMs > 0L) {
            maxOf(0L, smoothedPingMs - minRttMs)
        } else {
            0L
        }

        // 1. Детекция необходимости защиты от Bufferbloat и коллапса очередей
        val isNetworkSeverelyDegraded = smoothedPingMs < 0L ||
                smoothedPingMs >= LATENCY_CONGESTION_MS ||
                jitterMs >= JITTER_CONGESTION_MS ||
                bufferbloatMs >= BUFFERBLOAT_CONGESTION_MS ||
                quality == ConnectionQuality.OFFLINE ||
                quality == ConnectionQuality.POOR

        // 2. Определение базового пула сокетов по скорости
        val rawPool = when {
            isNetworkSeverelyDegraded -> 2
            throughputBps >= SPEED_ULTRA_BPS -> 16
            throughputBps >= SPEED_TURBO_BPS -> 8
            throughputBps >= SPEED_BALANCED_BPS -> 4
            else -> 2
        }

        // 3. Применение ограничений термотроттлинга QoS (Battery / Thermal)
        val targetPool = rawPool.coerceAtMost(qosThrottleLevel.maxPoolSize).coerceIn(2, 16)

        // 4. Определение размера буфера
        val rawBuffer = when (targetPool) {
            16 -> BUFFER_SIZE_ULTRA
            8 -> BUFFER_SIZE_TURBO
            4 -> BUFFER_SIZE_BALANCED
            else -> BUFFER_SIZE_ECO
        }
        val targetBuffer = rawBuffer.coerceAtMost(qosThrottleLevel.maxBufferSizeBytes)

        // 5. Интеллектуальные правила TCP_NODELAY
        // Интерактивный VoIP трафик (Opus) и диалоговые сообщения требуют TCP_NODELAY = true
        val isVoipPriority = isCallRecommended && mosScore >= 3.80
        val isLowThroughputInteractive = throughputBps < SPEED_INTERACTIVE_MAX_BPS

        val effectiveTcpNoDelay = if (isAutoSpeedPreset) {
            if (isVoipPriority || isLowThroughputInteractive || isNetworkSeverelyDegraded) {
                true // Приоритет интерактивности и низкой задержки
            } else {
                baseTcpNoDelay
            }
        } else {
            baseTcpNoDelay
        }

        val summary = when {
            isNetworkSeverelyDegraded -> "Защита от заторов (Eco / $targetPool сокетов)"
            targetPool >= 16 -> "Ultra режим (16 сокетов, 2 МБ буфер)"
            targetPool >= 8 -> "Turbo режим (8 сокетов, 1 МБ буфер)"
            targetPool >= 4 -> "Balanced режим (4 сокета, 256 КБ буфер)"
            else -> "Eco режим (2 сокета, 128 КБ буфер)"
        }

        return NetworkEvaluationDecision(
            recommendedPoolSize = targetPool,
            recommendedBufferSizeBytes = targetBuffer,
            recommendedTcpNoDelay = effectiveTcpNoDelay,
            connectionQuality = quality,
            isInteractiveVoipActive = isVoipPriority,
            isBufferbloatMitigationActive = isNetworkSeverelyDegraded,
            summary = summary
        )
    }

    /**
     * Единая стандартизированная классификация общего качества канала связи.
     */
    fun evaluateConnectionQuality(
        smoothedPingMs: Long,
        jitterMs: Long,
        consecutiveFailures: Int,
        successRatePercent: Int
    ): ConnectionQuality {
        if (consecutiveFailures >= 3 || smoothedPingMs <= 0L || successRatePercent < 40) {
            return ConnectionQuality.OFFLINE
        }
        return when {
            smoothedPingMs <= 80L && jitterMs <= 25L && successRatePercent >= 90 -> ConnectionQuality.EXCELLENT
            smoothedPingMs <= 180L && jitterMs <= 60L && successRatePercent >= 80 -> ConnectionQuality.GOOD
            smoothedPingMs <= 350L && successRatePercent >= 50 -> ConnectionQuality.MODERATE
            else -> ConnectionQuality.POOR
        }
    }
}
