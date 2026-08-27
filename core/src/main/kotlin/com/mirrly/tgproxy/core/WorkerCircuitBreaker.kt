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

enum class CircuitState(val label: String) {
    CLOSED("В норме"),
    OPEN("Изолирован (Карантин)"),
    HALF_OPEN("Пробный опрос")
}

data class WorkerCircuitRecord(
    val workerId: String,
    val domain: String,
    var state: CircuitState = CircuitState.CLOSED,
    var consecutiveFailures: Int = 0,
    var lastFailureTimestamp: Long = 0L,
    var cooldownUntilTimestamp: Long = 0L,
    var lastFailureReason: FailureType = FailureType.NONE,
    var lastProbeRttMs: Long? = null,
    var jitterMs: Long = 0L,
    var successCount: Int = 10,
    var totalProbes: Int = 10
) {
    val isAvailableForRouting: Boolean
        get() = state == CircuitState.CLOSED || state == CircuitState.HALF_OPEN

    val remainingCooldownSeconds: Long
        get() {
            val rem = (cooldownUntilTimestamp - System.currentTimeMillis()) / 1000L
            return rem.coerceAtLeast(0L)
        }

    fun computeQualityScore(isCustomWorker: Boolean = false): Double {
        if (state == CircuitState.OPEN) return -1.0

        val rtt = (lastProbeRttMs ?: 150L).coerceAtLeast(10L).toDouble()
        val jitterFactor = 1.0 + (jitterMs.coerceAtLeast(0L).toDouble() / 100.0)
        val successRate = if (totalProbes > 0) (successCount.toDouble() / totalProbes.toDouble()).coerceIn(0.1, 1.0) else 0.8

        var score = (successRate * 1000.0) / (rtt * jitterFactor)

        // Предпочтение кастомным воркерам пользователя при равном качестве
        if (isCustomWorker) {
            score *= 1.10
        }

        // Временный штраф воркерам на испытательном сроке
        if (state == CircuitState.HALF_OPEN) {
            score *= 0.85
        }

        return score
    }

    fun recordFailure(failureType: FailureType, customCooldownMs: Long? = null) {
        consecutiveFailures++
        lastFailureReason = failureType
        lastFailureTimestamp = System.currentTimeMillis()
        totalProbes++

        val actualCooldown = customCooldownMs ?: when (failureType) {
            FailureType.DPI_BLOCKED -> 600_000L          // 10 минут при DPI-блокировке
            FailureType.TLS_HANDSHAKE_FAILED -> 600_000L // 10 минут при разрыве/подмене TLS
            FailureType.RATE_LIMITED_429 -> 300_000L     // 5 минут при 429
            FailureType.PREDICTIVE_DEGRADATION -> 45_000L // 45 секунд мягкого карантина
            FailureType.CONNECT_TIMEOUT -> 60_000L       // 1 минута
            FailureType.HOST_UNREACHABLE -> 60_000L      // 1 минута
            else -> 45_000L                              // 45 секунд
        }

        val isInstantTrip = failureType == FailureType.DPI_BLOCKED ||
                failureType == FailureType.TLS_HANDSHAKE_FAILED ||
                failureType == FailureType.RATE_LIMITED_429 ||
                failureType == FailureType.PREDICTIVE_DEGRADATION

        if (consecutiveFailures >= 2 || isInstantTrip) {
            state = CircuitState.OPEN
            cooldownUntilTimestamp = System.currentTimeMillis() + actualCooldown
        }
    }

    fun recordSuccess(rttMs: Long) {
        consecutiveFailures = 0
        lastFailureReason = FailureType.NONE
        state = CircuitState.CLOSED
        cooldownUntilTimestamp = 0L
        lastProbeRttMs = rttMs
        totalProbes++
        successCount++
    }

    fun checkCooldownExpiration(): Boolean {
        if (state == CircuitState.OPEN && System.currentTimeMillis() >= cooldownUntilTimestamp) {
            state = CircuitState.HALF_OPEN
            return true
        }
        return false
    }

    fun reset() {
        consecutiveFailures = 0
        lastFailureReason = FailureType.NONE
        state = CircuitState.CLOSED
        cooldownUntilTimestamp = 0L
    }
}
