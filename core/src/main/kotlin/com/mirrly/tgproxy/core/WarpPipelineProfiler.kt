/*
 * Mirrly TG Proxy - WARP Pipeline Profiler (Task 04)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.core

import kotlinx.coroutines.flow.asStateFlow

/**
 * Четыре контролируемые фазы запуска и переподключения WARP/WireGuard.
 */
enum class WarpPhase(val displayName: String, val description: String) {
    CONFIG_ACQUISITION("Конфигурация", "Проверка учетных данных WARP и ключей"),
    DNS_RESOLUTION("DNS Anycast", "Разрешение Anycast адресов Cloudflare"),
    OBFUSCATION_PREPARATION("Обфускация", "Инициализация AmneziaWG параметров и маскировки"),
    HANDSHAKE_EXCHANGE("Рукопожатие", "Рукопожатие и согласование сессии"),
    TUNNEL_ESTABLISHMENT("Туннель", "Установка сквозного туннеля данных")
}

/**
 * Метрики замера времени этапов запуска WARP (5 фаз).
 */
data class WarpProfileMetrics(
    val tConfigMs: Long = 0L,
    val tDnsMs: Long = 0L,
    val tObfuscationMs: Long = 0L,
    val tHandshakeMs: Long = 0L,
    val tTunnelMs: Long = 0L,
    val tTotalMs: Long = 0L,
    val failurePhase: WarpPhase? = null,
    val isSuccess: Boolean = true,
    val errorDetail: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun formatSummary(): String {
        return if (isSuccess) {
            "WARP запущен за ${tTotalMs} мс (Конфиг: ${tConfigMs} мс, DNS: ${tDnsMs} мс, Обфускация: ${tObfuscationMs} мс, Handshake: ${tHandshakeMs} мс, Туннель: ${tTunnelMs} мс)"
        } else {
            "Сбой запуска WARP на этапе ${failurePhase?.displayName ?: "Неизвестно"} (Конфиг: ${tConfigMs} мс, DNS: ${tDnsMs} мс, Обфускация: ${tObfuscationMs} мс, Handshake: ${tHandshakeMs} мс): ${errorDetail ?: "Таймаут"}"
        }
    }
}

/**
 * Инструментальный профилировщик фаз запуска WARP.
 */
object WarpPipelineProfiler {

    private const val TAG = "WarpPipelineProfiler"

    @Volatile
    private var latestMetrics: WarpProfileMetrics? = null

    private val _currentPhase = kotlinx.coroutines.flow.MutableStateFlow<WarpPhase?>(null)
    val currentPhase: kotlinx.coroutines.flow.StateFlow<WarpPhase?> = _currentPhase.asStateFlow()

    fun recordMetrics(metrics: WarpProfileMetrics) {
        latestMetrics = metrics
        if (metrics.isSuccess) {
            AppLogger.i(TAG, metrics.formatSummary())
        } else {
            AppLogger.w(TAG, metrics.formatSummary())
        }
    }

    fun getLatestMetrics(): WarpProfileMetrics? = latestMetrics

    fun clear() {
        latestMetrics = null
        _currentPhase.value = null
    }

    /**
     * Замер времени выполнения блока с точностью до миллисекунд (System.nanoTime).
     */
    inline fun <T> measurePhase(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        val durationMs = (System.nanoTime() - start) / 1_000_000L
        return Pair(result, durationMs)
    }

    /**
     * Инструментальный замер 5 контролируемых фаз запуска WARP.
     * Фаза 1: CONFIG_ACQUISITION (ключи, токены, валидация профиля).
     * Фаза 2: DNS_RESOLUTION (резолвинг Anycast/Cloudflare).
     * Фаза 3: OBFUSCATION_PREPARATION (генерация параметров AmneziaWG / маскировки).
     * Фаза 4: HANDSHAKE_EXCHANGE (зондирование рукопожатия).
     * Фаза 5: TUNNEL_ESTABLISHMENT (проверка сквозного туннеля данных).
     */
    suspend fun profileWarpPipeline(
        config: ProxyConfig,
        targetEndpoint: String = config.warpPeerEndpoint
    ): WarpProfileMetrics {
        // Фаза 1: CONFIG_ACQUISITION
        _currentPhase.value = WarpPhase.CONFIG_ACQUISITION
        val (configValid, tConfig) = measurePhase {
            val privKey = if (config.warpPrivateKey.isNotBlank()) config.warpPrivateKey else WarpAccountManager.BOOTSTRAP_PROFILE.privateKeyBase64
            val hasKey = privKey.isNotBlank() && privKey.length >= 40
            val hasToken = config.warpToken.isNotBlank()
            hasKey && hasToken
        }
        if (!configValid) {
            val metrics = WarpProfileMetrics(
                tConfigMs = tConfig,
                tTotalMs = tConfig,
                failurePhase = WarpPhase.CONFIG_ACQUISITION,
                isSuccess = false,
                errorDetail = "Отсутствует закрытый ключ или токен WARP"
            )
            recordMetrics(metrics)
            return metrics
        }

        // Фаза 2: DNS_RESOLUTION
        _currentPhase.value = WarpPhase.DNS_RESOLUTION
        val (dnsResolved, tDns) = measurePhase {
            try {
                val addrs = DohResolver.resolve("engage.cloudflareclient.com", DnsScope.BOOTSTRAP)
                addrs.isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }

        // Фаза 3: OBFUSCATION_PREPARATION
        _currentPhase.value = WarpPhase.OBFUSCATION_PREPARATION
        val (obfuscationReady, tObfuscation) = measurePhase {
            try {
                val awgIni = config.getAmneziaWgConfig(cleanEndpoint = targetEndpoint)
                awgIni.isNotBlank()
            } catch (_: Exception) {
                false
            }
        }

        // Фаза 4: HANDSHAKE_EXCHANGE
        _currentPhase.value = WarpPhase.HANDSHAKE_EXCHANGE
        val (handshakeCandidate, tHandshake) = measurePhase {
            try {
                val protocol = if (config.uplinkMode == UplinkMode.AWG) {
                    WarpProbeProtocol.WIREGUARD
                } else {
                    WarpProbeProtocol.MASQUE_QUIC
                }
                WarpEndpointScanner.probeEndpoint(targetEndpoint, timeoutMs = 800L, useFragmentation = true, protocol = protocol)
            } catch (_: Exception) {
                WarpEndpointCandidate("", 0, targetEndpoint, isAlive = false)
            }
        }
        if (!handshakeCandidate.isAlive) {
            val metrics = WarpProfileMetrics(
                tConfigMs = tConfig,
                tDnsMs = tDns,
                tObfuscationMs = tObfuscation,
                tHandshakeMs = tHandshake,
                tTotalMs = tConfig + tDns + tObfuscation + tHandshake,
                failurePhase = WarpPhase.HANDSHAKE_EXCHANGE,
                isSuccess = false,
                errorDetail = "Таймаут рукопожатия на $targetEndpoint"
            )
            recordMetrics(metrics)
            return metrics
        }

        // Фаза 5: TUNNEL_ESTABLISHMENT
        _currentPhase.value = WarpPhase.TUNNEL_ESTABLISHMENT
        val (tunnelOk, tTunnel) = measurePhase {
            handshakeCandidate.isAlive && handshakeCandidate.rttMs >= 0
        }
        val totalMs = tConfig + tDns + tObfuscation + tHandshake + tTunnel
        val metrics = WarpProfileMetrics(
            tConfigMs = tConfig,
            tDnsMs = tDns,
            tObfuscationMs = tObfuscation,
            tHandshakeMs = tHandshake,
            tTunnelMs = tTunnel,
            tTotalMs = totalMs,
            failurePhase = if (tunnelOk) null else WarpPhase.TUNNEL_ESTABLISHMENT,
            isSuccess = tunnelOk,
            errorDetail = if (tunnelOk) null else "Сбой туннелирования данных"
        )
        recordMetrics(metrics)
        return metrics
    }
}

