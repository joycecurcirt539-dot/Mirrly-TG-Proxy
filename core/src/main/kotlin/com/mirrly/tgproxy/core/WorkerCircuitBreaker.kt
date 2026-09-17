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
    var failureStage: EstablishmentStage = EstablishmentStage.READY,
    var failureNetworkGeneration: Long = 0L,
    var hasActiveHalfOpenTrial: Boolean = false,
    var lastProbeRttMs: Long? = null,
    var jitterMs: Long = 0L,
    var successCount: Int = 10,
    var totalProbes: Int = 10
) {
    /**
     * Проверяет доступность узла для маршрутизации.
     * В состоянии CLOSED узел доступен всем потокам.
     * В состоянии OPEN узел изолирован.
     * В состоянии HALF_OPEN узел допускает РОВНО ОДНУ попытку (half-open trial),
     * предотвращая наплыв (thundering herd / толпу).
     */
    val isAvailableForRouting: Boolean
        get() = state == CircuitState.CLOSED || (state == CircuitState.HALF_OPEN && !hasActiveHalfOpenTrial)

    /**
     * Атомарно запрашивает разрешение на пробную попытку в состоянии HALF_OPEN.
     * Возвращает true только первому потоку/пробе. Все последующие получают false.
     */
    @Synchronized
    fun tryAcquireHalfOpenTrial(): Boolean {
        if (state == CircuitState.HALF_OPEN && !hasActiveHalfOpenTrial) {
            hasActiveHalfOpenTrial = true
            return true
        }
        return false
    }

    @Synchronized
    fun releaseHalfOpenTrial() {
        hasActiveHalfOpenTrial = false
    }

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

    @Synchronized
    fun recordFailure(
        failureType: FailureType,
        networkGeneration: Long = 0L,
        customCooldownMs: Long? = null
    ) {
        consecutiveFailures++
        lastFailureReason = failureType
        lastFailureTimestamp = System.currentTimeMillis()
        totalProbes++
        hasActiveHalfOpenTrial = false

        // Фиксация стадии и поколения сети сбоя
        failureStage = failureType.stage
        failureNetworkGeneration = networkGeneration

        val actualCooldown = customCooldownMs ?: when (failureType) {
            FailureType.DPI_BLOCKED, FailureType.CLOUDFLARE_EDGE_BLOCKED -> 600_000L // 10 минут при DPI-блокировке
            FailureType.TLS_HANDSHAKE_FAILED -> 600_000L // 10 минут при разрыве/подмене TLS
            FailureType.RATE_LIMITED_429, FailureType.WORKER_QUOTA_EXCEEDED -> 300_000L // 5 минут при 429
            FailureType.RELAY_ACK_FAILED, FailureType.SOCKS5_AUTH_REJECTED -> 60_000L // 1 минута при сбое relay ACK или отказе auth
            FailureType.WARP_HANDSHAKE_TIMEOUT -> 120_000L // 2 минуты при таймауте WARP
            FailureType.PREDICTIVE_DEGRADATION -> 45_000L // 45 секунд мягкого карантина
            FailureType.CONNECT_TIMEOUT -> 60_000L       // 1 минута
            FailureType.HOST_UNREACHABLE -> 60_000L      // 1 минута
            else -> 45_000L                              // 45 секунд
        }

        val isInstantTrip = failureType == FailureType.DPI_BLOCKED ||
                failureType == FailureType.CLOUDFLARE_EDGE_BLOCKED ||
                failureType == FailureType.TLS_HANDSHAKE_FAILED ||
                failureType == FailureType.RATE_LIMITED_429 ||
                failureType == FailureType.WORKER_QUOTA_EXCEEDED ||
                failureType == FailureType.RELAY_ACK_FAILED ||
                failureType == FailureType.SOCKS5_AUTH_REJECTED ||
                failureType == FailureType.WARP_HANDSHAKE_TIMEOUT ||
                failureType == FailureType.PREDICTIVE_DEGRADATION

        if (consecutiveFailures >= 2 || isInstantTrip) {
            state = CircuitState.OPEN
            cooldownUntilTimestamp = System.currentTimeMillis() + actualCooldown
        }
    }

    @Synchronized
    fun recordSuccess(rttMs: Long) {
        consecutiveFailures = 0
        lastFailureReason = FailureType.NONE
        failureStage = EstablishmentStage.READY
        failureNetworkGeneration = 0L
        state = CircuitState.CLOSED
        cooldownUntilTimestamp = 0L
        hasActiveHalfOpenTrial = false
        lastProbeRttMs = rttMs
        totalProbes++
        successCount++
    }

    /**
     * Проверяет истечение карантина с учетом поколения сети.
     * 429 глобален по endpoint — сохраняется независимо от смены сети.
     * Path-specific сбои (TCP/TLS/DPI/DNS/Relay ACK) привязаны к сети (per network):
     * при смене сети узел сразу становится HALF_OPEN для одиночной пробной попытки.
     */
    @Synchronized
    fun checkCooldownExpiration(currentNetworkGeneration: Long = 0L): Boolean {
        if (state == CircuitState.OPEN) {
            val isRateLimit = lastFailureReason == FailureType.RATE_LIMITED_429
            val networkChanged = currentNetworkGeneration > 0L &&
                    failureNetworkGeneration > 0L &&
                    currentNetworkGeneration != failureNetworkGeneration

            // Если сбой был path-specific (TCP/TLS/etc), а сеть сменилась — узел получает шанс на новом маршруте
            if (!isRateLimit && networkChanged) {
                state = CircuitState.HALF_OPEN
                hasActiveHalfOpenTrial = false
                cooldownUntilTimestamp = 0L
                consecutiveFailures = 0
                return true
            }

            // Стандартное истечение таймаута
            if (System.currentTimeMillis() >= cooldownUntilTimestamp) {
                state = CircuitState.HALF_OPEN
                hasActiveHalfOpenTrial = false
                return true
            }
        }
        return false
    }

    @Synchronized
    fun reset() {
        consecutiveFailures = 0
        lastFailureReason = FailureType.NONE
        failureStage = EstablishmentStage.READY
        failureNetworkGeneration = 0L
        state = CircuitState.CLOSED
        cooldownUntilTimestamp = 0L
        hasActiveHalfOpenTrial = false
    }
}
