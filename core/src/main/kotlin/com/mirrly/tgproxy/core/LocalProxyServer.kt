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

import java.io.File
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalProxyServer(val config: ProxyConfig = ProxyConfig()) {
    private var speedJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val stats = ProxyStats()

    val networkStabilizationGate = NetworkStabilizationGate()
    val adaptiveController = AdaptiveNetworkPolicyController(stabilizationGate = networkStabilizationGate)

    private val _transportPoolStatus = MutableStateFlow(
        config.getTransportPoolStatus(isMobile = false, isRunning = false, activeConnections = 0)
    )
    val transportPoolStatus: StateFlow<TransportPoolStatus> = _transportPoolStatus.asStateFlow()

    private val _adaptiveNetworkDecision = MutableStateFlow(adaptiveController.currentDecision)
    val adaptiveNetworkDecision: StateFlow<NetworkEvaluationDecision> = _adaptiveNetworkDecision.asStateFlow()

    fun setTcpNoDelayMode(mode: TcpNoDelayMode) {
        config.tcpNoDelayModeName = mode.name
        val effective = when (mode) {
            TcpNoDelayMode.ON -> true
            TcpNoDelayMode.OFF -> false
            TcpNoDelayMode.AUTO -> adaptiveNetworkDecision.value.recommendedTcpNoDelay
        }
        config.tcpNoDelay = effective
        if (isNativeRunning) {
            try {
                NativeProxy.setTcpNoDelay(effective)
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Failed to set TCP_NODELAY: ${t.message}")
            }
        }
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isNativeRunning: Boolean = false
        private set

    @Volatile
    var startTimeMs: Long = 0L
        private set

    val pingEngine = PingEngine(
        targetProvider = {
            if (config.isSocks5Mode) {
                val cfDomain = config.getEffectiveCfDomain()
                if (cfDomain.isNotBlank()) cfDomain else TgConstants.DEFAULT_SOCKS5_DEV_WORKER
            } else {
                TgConstants.getWsDomains(2).firstOrNull() ?: ("kws2." + TgConstants.decodeCfDomain("virkgj.com"))
            }
        },
        onSelfHealingRequired = { failureType ->
            AppLogger.w("LocalProxyServer", "Watchdog: Зафиксирован сетевой сбой $failureType")
            when (failureType) {
                FailureType.DNS_FAILURE -> {
                    DohResolver.clearCache()
                }
                FailureType.CONNECT_TIMEOUT -> {
                    // Мягкая обработка таймаута соединения без сброса рабочих потоков
                }
                else -> {}
            }
        }
    )

    val adaptiveHeartbeatEngine = AdaptiveHeartbeatEngine(
        statsProvider = { stats },
        onHeartbeatTick = {
            measurePingAsync()
        }
    )

    val qosEngine = BatteryThermalQoSEngine(
        isEnabled = config.isAdaptiveQoSEnabled,
        onThrottleLevelChanged = { newLevel ->
            if (isNativeRunning) {
                try {
                    NativeProxy.setBatteryQoSLevel(newLevel)
                } catch (t: Throwable) {
                    AppLogger.w("LocalProxyServer", "setBatteryQoSLevel($newLevel) не удался: ${t.message}")
                }
            }
            if (config.isAutoSpeedPreset && isNativeRunning) {
                updateAutoTuning()
            }
        }
    )

    val speedTestEngine = TunnelSpeedTestEngine()

    val activeLivenessProbe = ActiveLivenessProbe(
        isSocks5ModeProvider = { config.isSocks5Mode },
        socks5PortProvider = { config.socks5Port },
        socks5AuthProvider = { Pair(config.socks5Username, config.socks5Password) },
        trafficThroughputProvider = { stats.downloadSpeedBps }, // Only count verified incoming RX throughput, not outgoing TX
        onCascadeTriggered = { oldStage, newStage, reason ->
            handleCascadeTransition(oldStage, newStage, reason)
        }
    ).apply {
        onProbeCompleted = { res ->
            stats.lastActiveProbeRttMs = res.rttMs
            stats.isProbeAlive = res.isAlive
            stats.activeCascadeStage = currentStage.title
        }
    }

    val currentPingMs: Long
        get() = pingEngine.smoothedPingMs

    val smoothedPingMs: Long
        get() = pingEngine.smoothedPingMs

    val jitterMs: Long
        get() = pingEngine.jitterMs

    val connectionQuality: ConnectionQuality
        get() = pingEngine.quality

    @Volatile
    var currentEffectiveTcpNoDelay: Boolean = true
        private set

    val currentProfileGeneration = java.util.concurrent.atomic.AtomicLong(1L)

    val uptimeSeconds: Long
        get() = if (isRunning && startTimeMs > 0L) (System.currentTimeMillis() - startTimeMs) / 1000L else 0L

    @Synchronized
    fun start(cacheDir: File? = null): Boolean {
        if (isRunning) return true
        return performStart(cacheDir)
    }

    private fun performStart(cacheDir: File?): Boolean {
        stats.resetBaseline()

        if (cacheDir != null) {
            try {
                NativeProxy.setCfProxyCacheDir(cacheDir.absolutePath)
                VlessPresetsRepository.loadDynamicPool(cacheDir)
            } catch (t: Throwable) {
                AppLogger.e("LocalProxyServer", "Не удалось установить кэш-директорию Cloudflare/VLESS: ${t.message}")
            }
        }
        VlessPresetsRepository.syncFallbackPoolToNative()

        NativeProxy.setPoolSize(config.poolSize.coerceIn(2, 16))
        
        val initialNoDelay = when (config.tcpNoDelayMode) {
            TcpNoDelayMode.ON -> true
            TcpNoDelayMode.OFF -> false
            TcpNoDelayMode.AUTO -> config.tcpNoDelay
        }
        currentEffectiveTcpNoDelay = initialNoDelay
        NativeProxy.setTcpNoDelay(initialNoDelay)

        val useCf = if (config.isAnyWarpUplink) false else config.cfProxyEnabled
        val workerDomain = if (config.isSocks5Mode) {
            if (config.isAnyWarpUplink) {
                // В режиме WARP использование любых воркеров (пользовательских или дефолтных) категорически запрещено
                ""
            } else if (config.isVlessUplink) {
                config.getEffectiveVlessDomain()
            } else {
                config.getEffectiveCfDomain()
            }
        } else {
            // MTProto маршрутизируется исключительно через глобальный Anycast CDN Flowseal (kws{dc}.{domain}/apiws).
            // Пользовательские воркеры и воркеры разработчика намеренно НЕ применяются к MTProto,
            // так как прямое туннелирование MTProto через воркеры нарушает сетевой стек CDN и ломает подключение.
            ""
        }

        if (config.isSocks5Mode && !config.hasSocks5Auth) {
            val (u, p) = ProxyConfig.generateRandomSocks5Credentials()
            config.socks5Username = u
            config.socks5Password = p
        }

        if (config.isWarpCascadeUplink) {
            // В режиме каскада настраиваем домен воркера для аварийного Level 3 failover
            NativeProxy.setCfProxyConfig(
                enabled = true,
                userDomain = config.getEffectiveCfDomain()
            )
        } else if (config.isAnyWarpUplink) {
            NativeProxy.setCfProxyConfig(
                enabled = false,
                userDomain = ""
            )
        } else {
            NativeProxy.setCfProxyConfig(
                enabled = useCf,
                userDomain = workerDomain
            )
        }
        NativeProxy.setSocks5Auth(
            username = config.socks5Username,
            password = config.socks5Password
        )
        DohResolver.setActiveProviders(config.enabledDohProviderIds)
        NativeProxy.setDohEndpoints(DohResolver.getActiveEndpointsCsv())
        NativeProxy.setUplinkMode(config.uplinkMode)
        NativeProxy.setVlessExtendedConfig(
            uuid = config.vlessUuid,
            path = config.vlessPath,
            domain = config.getEffectiveVlessDomain(),
            serverAddress = config.getEffectiveVlessServerAddress(),
            serverPort = config.getEffectiveVlessServerPort(),
            tlsSni = config.getEffectiveVlessSni(),
            hostHeader = config.getEffectiveVlessHost(),
            transport = config.vlessTransport,
            security = config.vlessSecurity,
            publicKey = config.vlessPublicKey,
            shortId = config.vlessShortId,
            fingerprint = config.vlessFingerprint,
            spiderX = config.vlessSpiderX,
            flow = config.vlessFlow,
            headerType = config.vlessHeaderType
        )
        NativeProxy.setOperaVpnConfig(
            vlessEnabled = config.useOperaVpnForVless,
            warpEnabled = config.useOperaVpnForWarp,
            endpoint = config.getEffectiveOperaEndpoint()
        )
        // Для всех WARP-режимов настраиваем AmneziaWG (AWG)
        if (config.isAnyWarpUplink) {
            val awgIni = config.awgCustomIni.trim().ifEmpty {
                config.getAmneziaWgConfig(cleanEndpoint = config.warpPeerEndpoint)
            }
            NativeProxy.setAwgConfig(awgIni)
        }

        var code: Int

        if (config.isSocks5Mode) {
            AppLogger.i("LocalProxyServer", "Запуск нативного SOCKS5 движка на порту ${config.socks5Port} (auth=${config.hasSocks5Auth})...")
            code = NativeProxy.startSocks5Proxy(
                host = config.bindHost,
                port = config.socks5Port,
                verbose = if (config.verboseLogs) 1 else 0
            )

            if (code == -3 || code == 3) {
                AppLogger.w("LocalProxyServer", "Порт SOCKS5 ${config.socks5Port} освобождается (код $code), повторный запуск через 150мс...")
                Thread.sleep(150)
                code = NativeProxy.startSocks5Proxy(
                    host = config.bindHost,
                    port = config.socks5Port,
                    verbose = if (config.verboseLogs) 1 else 0
                )
            }

            if (code == 0) {
                isNativeRunning = true
                isRunning = true
                if (startTimeMs == 0L) {
                    startTimeMs = System.currentTimeMillis()
                }
                AppLogger.i("LocalProxyServer", "Нативный SOCKS5 движок успешно запущен на порту ${config.socks5Port}")
            } else {
                isNativeRunning = false
                isRunning = false
                AppLogger.e("LocalProxyServer", "Не удалось запустить нативный SOCKS5 движок (код: $code)")
                return false
            }
        } else {
            AppLogger.i("LocalProxyServer", "Запуск нативного MTProto движка на ${config.bindHost}:${config.bindPort}...")
            val secret = config.rawSecret32
            val defaultDcIps = "1:149.154.175.50,2:149.154.167.51,3:149.154.175.100,4:149.154.167.91,5:91.108.56.130,203:91.105.192.100"
            code = NativeProxy.startProxy(
                host = config.bindHost,
                port = config.bindPort,
                dcIps = defaultDcIps,
                secret = secret,
                verbose = if (config.verboseLogs) 1 else 0
            )

            if (code == -3 || code == 3) {
                AppLogger.w("LocalProxyServer", "Порт ${config.bindPort} освобождается (код $code), повторный запуск через 150мс...")
                Thread.sleep(150)
                code = NativeProxy.startProxy(
                    host = config.bindHost,
                    port = config.bindPort,
                    dcIps = defaultDcIps,
                    secret = secret,
                    verbose = if (config.verboseLogs) 1 else 0
                )
            }

            if (code == 0) {
                isNativeRunning = true
                isRunning = true
                if (startTimeMs == 0L) {
                    startTimeMs = System.currentTimeMillis()
                }
                AppLogger.i("LocalProxyServer", "Нативный MTProto движок успешно запущен на ${config.bindHost}:${config.bindPort} (Cloudflare: $useCf)")
            } else {
                isNativeRunning = false
                isRunning = false
                AppLogger.e("LocalProxyServer", "Не удалось запустить нативный MTProto движок (код: $code)")
                return false
            }
        }

        if (isNativeRunning) {
            NativeProxy.setBatteryQoSLevel(qosEngine.currentThrottleLevel)
        }

        speedJob = scope.launch {
            while (isActive && isRunning) {
                if (isNativeRunning) {
                    try {
                        val nativeStats = NativeProxy.getStats()
                        if (!nativeStats.isNullOrEmpty()) {
                            stats.parseNativeStats(nativeStats)
                        }
                    } catch (_: Exception) {}

                    val rawStage = NativeProxy.getActiveCascadeStage()
                    if (rawStage > 0) {
                        stats.activeCascadeStageCode = rawStage
                        stats.activeCascadeStage = when (rawStage) {
                            1 -> "WARP MASQUE"
                            2 -> "WARP AmneziaWG"
                            3 -> "Cloudflare Worker"
                            4 -> "VLESS Direct"
                            5 -> "Opera VPN"
                            6 -> "Direct TCP"
                            7 -> "VLESS + Opera Hop"
                            else -> stats.activeCascadeStage
                        }
                    }
                }
                stats.updateSpeed()
                val snapshot = pingEngine.currentSnapshot
                stats.smoothedPingMs = snapshot.smoothedPingMs
                stats.jitterMs = snapshot.jitterMs
                stats.connectionQuality = snapshot.quality
                stats.lastFailureType = snapshot.lastFailureType
                stats.healthScore = snapshot.healthReport.score
                stats.healthVerdict = snapshot.healthReport.verdict
                stats.healthDetail = snapshot.healthReport.detail
                stats.healthSuccessRate = snapshot.successRatePercent
                stats.chatScore = snapshot.healthReport.chatScore
                stats.chatVerdict = snapshot.healthReport.chatVerdict
                stats.callScore = snapshot.healthReport.callScore
                stats.mosScore = snapshot.healthReport.mosScore
                stats.mosGrade = snapshot.healthReport.mosGrade
                stats.isCallRecommended = snapshot.healthReport.isCallRecommended
                stats.minRttMs = snapshot.minRttMs
                stats.bufferbloatMs = snapshot.bufferbloatMs
                stats.bufferbloatGrade = snapshot.bufferbloatGrade
                stats.currentAlpha = snapshot.currentAlpha
                stats.rttHistory = snapshot.rttHistory
                val dcDist = stats.dcAffinityEngine.calculateSocketDistribution(config.poolSize)
                stats.dcAffinitySummary = dcDist.summary

                if (config.isAutoSpeedPreset && isNativeRunning) {
                    updateAutoTuning()
                }
                delay(1000)
            }
        }

        pingEngine.start()
        adaptiveHeartbeatEngine.start()

        activeLivenessProbe.isEnabled = config.isLivenessProbeEnabled
        activeLivenessProbe.timeoutMs = config.livenessProbeTimeoutMs
        activeLivenessProbe.failoverThreshold = config.livenessProbeFailoverThreshold
        if (config.isSocks5Mode && config.isLivenessProbeEnabled) {
            val initialStage = when {
                config.isVlessUplink -> CascadeStage.STAGE_3_VLESS_PRESET
                config.isMasqueUplink -> {
                    val (ip, _) = WarpEndpointScanner.parseEndpoint(config.warpPeerEndpoint)
                    if (ip.contains(":")) CascadeStage.STAGE_0_IPV6_WARP
                    else CascadeStage.STAGE_1_SCANNED_WARP
                }
                else -> CascadeStage.STAGE_1_SCANNED_WARP
            }
            activeLivenessProbe.setInitialStage(initialStage)
            stats.activeCascadeStage = initialStage.title
            activeLivenessProbe.start()
        }
        return true
    }

    @Synchronized
    fun stop() {
        if (!isRunning && !isNativeRunning) return
        performStop()
    }

    private fun performStop() {
        isRunning = false
        startTimeMs = 0L
        speedJob?.cancel()
        speedJob = null
        pingEngine.stop()
        adaptiveHeartbeatEngine.stop()
        activeLivenessProbe.stop()
        isNativeRunning = false
        autoConsecutiveHighTicks = 0
        autoConsecutiveLowTicks = 0

        try {
            NativeProxy.stopProxy()
            AppLogger.i("LocalProxyServer", "Движок прокси успешно остановлен")
        } catch (t: Throwable) {
            AppLogger.e("LocalProxyServer", "Ошибка остановки нативного движка: ${t.message}")
        }
    }

    @Synchronized
    fun restart(cacheDir: File? = null): Boolean {
        val wasRunning = isRunning
        val savedStart = startTimeMs
        if (wasRunning) {
            stop()
        }
        val ok = start(cacheDir)
        if (wasRunning && ok && savedStart > 0L) {
            startTimeMs = savedStart
        }
        return ok
    }

    private var autoConsecutiveHighTicks = 0
    private var autoConsecutiveLowTicks = 0

    private fun updateAutoTuning() {
        val totalSpeed = stats.downloadSpeedBps + stats.uploadSpeedBps
        val snapshot = pingEngine.currentSnapshot

        val decision = NetworkConditionEvaluator.evaluate(
            throughputBps = totalSpeed,
            smoothedPingMs = snapshot.smoothedPingMs,
            minRttMs = snapshot.minRttMs,
            jitterMs = snapshot.jitterMs,
            successRatePercent = snapshot.successRatePercent,
            consecutiveFailures = snapshot.consecutiveFailures,
            mosScore = snapshot.healthReport.mosScore,
            isCallRecommended = snapshot.healthReport.isCallRecommended,
            qosThrottleLevel = qosEngine.currentThrottleLevel,
            isAutoSpeedPreset = config.isAutoSpeedPreset,
            baseTcpNoDelay = config.tcpNoDelay
        )

        // Синхронизируем TCP_NODELAY при изменении
        if (config.isAutoSpeedPreset) {
            applyTcpNoDelay(decision.recommendedTcpNoDelay)
        }

        val candidatePool = decision.recommendedPoolSize
        val currentPool = config.poolSize

        if (candidatePool > currentPool) {
            autoConsecutiveHighTicks++
            autoConsecutiveLowTicks = 0
            if (autoConsecutiveHighTicks >= 2) {
                autoConsecutiveHighTicks = 0
                applyAutoDecision(decision)
            }
        } else if (candidatePool < currentPool) {
            autoConsecutiveLowTicks++
            autoConsecutiveHighTicks = 0
            if (autoConsecutiveLowTicks >= 5) {
                autoConsecutiveLowTicks = 0
                applyAutoDecision(decision)
            }
        } else {
            autoConsecutiveHighTicks = 0
            autoConsecutiveLowTicks = 0
        }
    }

    private fun applyAutoDecision(decision: NetworkEvaluationDecision) {
        val clampedPool = decision.recommendedPoolSize.coerceIn(2, 16).coerceAtMost(qosEngine.maxAllowedPoolSize)
        if (clampedPool == config.poolSize && config.bufferSizeBytes == decision.recommendedBufferSizeBytes) return

        config.poolSize = clampedPool
        config.bufferSizeBytes = decision.recommendedBufferSizeBytes.coerceAtMost(qosEngine.maxAllowedBufferSizeBytes)

        AppLogger.i(
            "LocalProxyServer",
            "Авто-адаптация стека: ${decision.summary} (буфер: ${config.bufferSizeBytes / 1024} КБ, TCP_NODELAY: ${decision.recommendedTcpNoDelay}, QoS: ${qosEngine.currentThrottleLevel.name})"
        )

        if (isNativeRunning) {
            try {
                NativeProxy.setPoolSize(clampedPool)
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Автоматический setPoolSize($clampedPool) не удался: ${t.message}")
            }
        }
    }

    /**
     * Applies a new pool size immediately via NativeProxy without requiring a full restart.
     * Also updates config.poolSize so the value is consistent everywhere.
     */
    fun applyPoolSize(newSize: Int, cacheDir: File? = null) {
        val clamped = newSize.coerceIn(2, 16)
        config.poolSize = clamped
        AppLogger.i("LocalProxyServer", "Изменение пула сокетов → $clamped")
        if (isNativeRunning) {
            try {
                NativeProxy.setPoolSize(clamped)
                AppLogger.i("LocalProxyServer", "Пул сокетов обновлён динамически: $clamped")
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "setPoolSize() не удался (${t.message}), перезапуск прокси...")
                if (isRunning) restart(cacheDir)
            }
        }
    }

    /**
     * Динамически применяет флаг TCP_NODELAY в нативном движке.
     */
    fun applyTcpNoDelay(enabled: Boolean) {
        if (currentEffectiveTcpNoDelay != enabled) {
            currentEffectiveTcpNoDelay = enabled
            config.tcpNoDelay = enabled
            AppLogger.i("LocalProxyServer", "Смена режима TCP_NODELAY → $enabled")
            if (isNativeRunning) {
                try {
                    NativeProxy.setTcpNoDelay(enabled)
                } catch (t: Throwable) {
                    AppLogger.w("LocalProxyServer", "NativeProxy.setTcpNoDelay($enabled) не удался: ${t.message}")
                }
            }
        }
    }

    /**
     * Динамически включает или отключает троттлинг энергосбережения и адаптивного QoS.
     */
    fun setAdaptiveQoSEnabled(enabled: Boolean) {
        config.isAdaptiveQoSEnabled = enabled
        qosEngine.setEnabled(enabled)
        AppLogger.i("LocalProxyServer", "Смена режима адаптивного QoS/троттлинга → $enabled")
        if (config.isAutoSpeedPreset && isNativeRunning) {
            updateAutoTuning()
        }
    }

    fun measurePingAsync(dcId: Int = 2) {
        scope.launch(Dispatchers.IO) {
            pingEngine.triggerSingleProbe()
        }
    }

    fun onWorkerChanged(newWorkerDomain: String) {
        config.customCfDomain = newWorkerDomain
        DohResolver.clearCache()
        pingEngine.reset()
        updateWorkerConfig()
    }

    fun updateWorkerConfig() {
        if (config.isAnyWarpUplink) {
            AppLogger.i("LocalProxyServer", "В режиме WARP использование воркеров отключено.")
            return
        }
        val effectiveDomain = if (config.isVlessUplink) {
            config.getEffectiveVlessDomain()
        } else {
            config.getEffectiveCfDomain()
        }
        AppLogger.i("LocalProxyServer", "Обновление конфигурации воркера → '$effectiveDomain'")
        if (isNativeRunning) {
            try {
                NativeProxy.setCfProxyConfig(
                    enabled = config.cfProxyEnabled,
                    userDomain = effectiveDomain
                )
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Ошибка применения нового воркера в NativeProxy: ${t.message}")
            }
        }
        measurePingAsync()
    }

    /**
     * Динамически применяет выбранный набор DoH-провайдеров без перезапуска службы.
     */
    fun applyDohConfig(providerIds: Set<String>) {
        config.enabledDohProviderIds = providerIds
        DohResolver.setActiveProviders(providerIds)
        DohResolver.clearCache()
        val endpointsCsv = DohResolver.getActiveEndpointsCsv()
        AppLogger.i("LocalProxyServer", "Обновлены DoH провайдеры (${providerIds.size} активных) → $endpointsCsv")
        if (isNativeRunning) {
            try {
                NativeProxy.setDohEndpoints(endpointsCsv)
                NativeProxy.resetNetworkSockets()
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Сбой применения DoH провайдеров в NativeProxy: ${t.message}")
            }
        }
    }

    /**
     * Динамически применяет выбранный режим аплинка (Worker WSS / WARP MASQUE / Hybrid).
     */
    fun applyBufferSizeBytes(newSize: Int) {
        val clamped = if (newSize <= 0) {
            0
        } else {
            newSize.coerceIn(32 * 1024, 2 * 1024 * 1024)
        }
        config.bufferSizeBytes = clamped
        if (isNativeRunning && clamped > 0) {
            try {
                NativeProxy.setSocketBufferSizes(config.bufferSizeBytes, config.bufferSizeBytes)
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "setSocketBufferSizes failed: ${t.message}")
            }
        }
    }

    fun applyMtprotoStandbyPerActiveSlot(slots: Int) {
        val clamped = slots.coerceIn(1, 4)
        config.mtprotoStandbyPerActiveSlot = clamped
        if (isNativeRunning) {
            try {
                NativeProxy.setPoolSize(clamped)
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "setPoolSize($clamped) failed: ${t.message}")
            }
        }
    }

    fun emergencyKillAllSockets(reason: String = "manual") {
        AppLogger.w("LocalProxyServer", "Emergency killing all active network sockets (reason: $reason)")
        if (isNativeRunning) {
            try {
                NativeProxy.emergencyKillAllSockets(reason)
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Failed to reset network sockets: ${t.message}")
            }
        }
    }

    /**
     * Эффективный профиль сетевого окружения.
     */
    var effectiveNetworkProfile: NetworkProfile = NetworkProfile()
        private set

    fun updateNetworkEnvironment(
        environment: NetworkEnvironment = NetworkEnvironment(),
        screenOn: Boolean = true,
        powerSaveMode: Boolean = false,
        env: NetworkEnvironment = environment
    ) {
        val target = if (environment != NetworkEnvironment()) environment else env
        effectiveNetworkProfile = effectiveNetworkProfile
            .withEnvironment(target)
            .withRuntime(screenOn, powerSaveMode)
    }

    fun updateScreenPowerMode(screenOn: Boolean, powerSaveMode: Boolean) {
        effectiveNetworkProfile = effectiveNetworkProfile.withRuntime(screenOn, powerSaveMode)
    }

    fun nextGeneration(): Long = currentProfileGeneration.incrementAndGet()

    fun handleNetworkChanged(
        newType: String = "",
        oldType: String = "",
        isMobile: Boolean = false,
        isScreenOn: Boolean = true,
        newNet: String = newType,
        oldNet: String = oldType
    ) {
        val target = if (newType.isNotEmpty()) newType else newNet
        if (target == "DISCONNECTED") return
        nextGeneration()
        setNetworkInterface(isMobile, isScreenOn)
    }

    /**
     * Обработка событий приостановки/возобновления сети.
     */
    fun handleNetworkSuspended() {}
    fun handleNetworkResumed(isMobile: Boolean = false, isScreenOn: Boolean = true) {}

    fun getEffectiveMtprotoStandby(isMobile: Boolean = false): Int = config.getEffectiveMtprotoStandby(isMobile)
    fun getGlobalEstablishmentBudget(isMobile: Boolean = false): Int = config.getGlobalEstablishmentBudget(isMobile)
    fun getTransportPoolStatus(isMobile: Boolean = false): TransportPoolStatus = config.getTransportPoolStatus(isMobile, isRunning, stats.activeConnections.get())

    fun applyWarpEndpoint(
        endpoint: String,
        expectedGeneration: Long? = null,
        expectedMode: UplinkMode? = null
    ): Boolean {
        if (expectedGeneration != null && currentProfileGeneration.get() != expectedGeneration) {
            AppLogger.w("LocalProxyServer", "Rejected warp endpoint change: generation $expectedGeneration is stale (current: ${currentProfileGeneration.get()})")
            return false
        }
        if (expectedMode != null && config.uplinkMode != expectedMode) {
            AppLogger.w("LocalProxyServer", "Rejected warp endpoint change: mode ${config.uplinkMode} does not match expected $expectedMode")
            return false
        }
        if (expectedGeneration != null && !isRunning && !isNativeRunning) {
            return false
        }
        config.warpPeerEndpoint = endpoint
        config.warpMasquePeerEndpoint = endpoint
        if (expectedGeneration == null) {
            currentProfileGeneration.incrementAndGet()
        }
        if (isNativeRunning && (config.isMasqueUplink || config.isHybridUplink || config.isAwgUplink || config.isWarpCascadeUplink)) {
            try {
                val awgIni = config.awgCustomIni.trim().ifEmpty {
                    config.getAmneziaWgConfig(cleanEndpoint = config.warpPeerEndpoint)
                }
                NativeProxy.setAwgConfig(awgIni)
                NativeProxy.resetNetworkSockets()
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Failed to update WARP endpoint: ${t.message}")
            }
        }
        return true
    }

    /**
     * Динамически применяет выбранный режим аплинка (Worker WSS / WARP MASQUE / Hybrid).
     */
    fun applyUplinkMode(mode: UplinkMode, expectedGeneration: Long? = null): Boolean {
        if (expectedGeneration != null && currentProfileGeneration.get() != expectedGeneration) {
            AppLogger.w("LocalProxyServer", "Rejected uplink mode change: generation $expectedGeneration is stale (current: ${currentProfileGeneration.get()})")
            return false
        }
        config.uplinkModeName = mode.name
        currentProfileGeneration.incrementAndGet()
        AppLogger.i("LocalProxyServer", "Применение режима аплинка: ${mode.displayName} (gen=${currentProfileGeneration.get()})")
        val syncStage = when (mode) {
            UplinkMode.VLESS -> CascadeStage.STAGE_3_VLESS_PRESET
            UplinkMode.MASQUE -> {
                val (ip, _) = WarpEndpointScanner.parseEndpoint(config.warpPeerEndpoint)
                if (ip.contains(":")) CascadeStage.STAGE_0_IPV6_WARP
                else CascadeStage.STAGE_1_SCANNED_WARP
            }
            UplinkMode.AWG, UplinkMode.WARP_CASCADE -> CascadeStage.STAGE_1_SCANNED_WARP
            else -> CascadeStage.STAGE_1_SCANNED_WARP
        }
        activeLivenessProbe.setInitialStage(syncStage)
        stats.activeCascadeStage = syncStage.title
        if (isNativeRunning) {
            try {
                NativeProxy.setUplinkMode(mode)
                if (mode == UplinkMode.VLESS) {
                    NativeProxy.setCfProxyConfig(
                        enabled = config.cfProxyEnabled,
                        userDomain = config.getEffectiveVlessDomain()
                    )
                    NativeProxy.setVlessExtendedConfig(
                        uuid = config.vlessUuid,
                        path = config.vlessPath,
                        domain = config.getEffectiveVlessDomain(),
                        serverAddress = config.getEffectiveVlessServerAddress(),
                        serverPort = config.getEffectiveVlessServerPort(),
                        tlsSni = config.getEffectiveVlessSni(),
                        hostHeader = config.getEffectiveVlessHost(),
                        transport = config.vlessTransport,
                        security = config.vlessSecurity,
                        publicKey = config.vlessPublicKey,
                        shortId = config.vlessShortId,
                        fingerprint = config.vlessFingerprint,
                        spiderX = config.vlessSpiderX,
                        flow = config.vlessFlow,
                        headerType = config.vlessHeaderType
                    )
                } else if (mode == UplinkMode.WORKER) {
                    NativeProxy.setCfProxyConfig(
                        enabled = config.cfProxyEnabled,
                        userDomain = config.getEffectiveCfDomain()
                    )
                } else if (mode == UplinkMode.WARP_CASCADE) {
                    // WARP_CASCADE: активируем конфигурацию воркера для аварийного Level 3 failover
                    NativeProxy.setCfProxyConfig(
                        enabled = true,
                        userDomain = config.getEffectiveCfDomain()
                    )
                } else {
                    // Режимы WARP (MASQUE, AWG, HYBRID):
                    // Воркеры разработчика и пользовательские воркеры полностью отключены
                    NativeProxy.setCfProxyConfig(
                        enabled = false,
                        userDomain = ""
                    )
                }
                if (mode == UplinkMode.MASQUE || mode == UplinkMode.HYBRID || mode == UplinkMode.AWG || mode == UplinkMode.WARP_CASCADE) {
                    val awgIni = config.awgCustomIni.trim().ifEmpty {
                        config.getAmneziaWgConfig(cleanEndpoint = config.warpPeerEndpoint)
                    }
                    NativeProxy.setAwgConfig(awgIni)
                }
                NativeProxy.resetNetworkSockets()
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Сбой применения режима аплинка в NativeProxy: ${t.message}")
            }
        }
        return true
    }

    /**
     * Применяет полный пресет VLESS (включая Reality/Vision/Transport) к конфигурации и нативному ядру.
     */
    fun applyVlessPreset(preset: VlessPreset) {
        VlessPresetsRepository.applyPreset(preset, config)
        applyVlessConfig(
            uuid = preset.uuid,
            path = preset.path,
            domain = preset.domain,
            serverAddress = preset.serverAddress,
            serverPort = preset.effectiveServerPort,
            tlsSni = preset.tlsSni,
            hostHeader = preset.hostHeader,
            transport = preset.transport,
            security = preset.security,
            publicKey = preset.publicKey,
            shortId = preset.shortId,
            fingerprint = preset.fingerprint,
            spiderX = preset.spiderX,
            flow = preset.flow,
            headerType = preset.headerType
        )
    }

    /**
     * Динамически применяет конфигурацию VLESS (UUID, путь, домен, Clean IP, SNI и Extended Security/Transport).
     */
    fun applyVlessConfig(
        uuid: String,
        path: String,
        domain: String = "",
        serverAddress: String = "",
        serverPort: Int = 443,
        tlsSni: String = "",
        hostHeader: String = "",
        transport: String = "",
        security: String = "",
        publicKey: String = "",
        shortId: String = "",
        fingerprint: String = "",
        spiderX: String = "",
        flow: String = "",
        headerType: String = ""
    ) {
        config.vlessUuid = uuid
        config.vlessPath = path
        if (domain.isNotBlank()) {
            config.vlessDomain = domain
        }
        if (serverAddress.isNotBlank()) {
            config.vlessServerAddress = serverAddress
        }
        if (serverPort > 0) {
            config.vlessServerPort = serverPort
        }
        if (tlsSni.isNotBlank()) {
            config.vlessTlsSni = tlsSni
        }
        if (hostHeader.isNotBlank()) {
            config.vlessHostHeader = hostHeader
        }
        if (transport.isNotBlank()) {
            config.vlessTransport = transport
        }
        if (security.isNotBlank()) {
            config.vlessSecurity = security
        }
        if (publicKey.isNotBlank()) {
            config.vlessPublicKey = publicKey
        }
        if (shortId.isNotBlank()) {
            config.vlessShortId = shortId
        }
        if (fingerprint.isNotBlank()) {
            config.vlessFingerprint = fingerprint
        }
        if (spiderX.isNotBlank()) {
            config.vlessSpiderX = spiderX
        }
        if (flow.isNotBlank()) {
            config.vlessFlow = flow
        }
        if (headerType.isNotBlank()) {
            config.vlessHeaderType = headerType
        }
        val effectiveDomain = if (domain.isNotBlank()) domain else config.getEffectiveVlessDomain()
        val effectiveServerAddr = config.getEffectiveVlessServerAddress()
        val effectivePort = config.getEffectiveVlessServerPort()
        val effectiveSni = config.getEffectiveVlessSni()
        val effectiveHost = config.getEffectiveVlessHost()
        AppLogger.i("LocalProxyServer", "Применение конфигурации VLESS: uuid=$uuid, path=$path, server=$effectiveServerAddr:$effectivePort, sni=$effectiveSni, host=$effectiveHost, transport=${config.vlessTransport}, sec=${config.vlessSecurity}")
        if (isNativeRunning) {
            try {
                NativeProxy.setVlessExtendedConfig(
                    uuid = uuid,
                    path = path,
                    domain = effectiveDomain,
                    serverAddress = effectiveServerAddr,
                    serverPort = effectivePort,
                    tlsSni = effectiveSni,
                    hostHeader = effectiveHost,
                    transport = config.vlessTransport,
                    security = config.vlessSecurity,
                    publicKey = config.vlessPublicKey,
                    shortId = config.vlessShortId,
                    fingerprint = config.vlessFingerprint,
                    spiderX = config.vlessSpiderX,
                    flow = config.vlessFlow,
                    headerType = config.vlessHeaderType
                )
                NativeProxy.resetNetworkSockets()
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Сбой применения VLESS конфигурации в NativeProxy: ${t.message}")
            }
        }
    }


    /**
     * Динамически применяет конфигурацию Opera VPN прокси для VLESS и WARP.
     */
    fun applyOperaVpnConfig() {
        AppLogger.i("LocalProxyServer", "Применение конфигурации Opera VPN: vless=${config.useOperaVpnForVless}, warp=${config.useOperaVpnForWarp}, ep=${config.getEffectiveOperaEndpoint()}")
        if (isNativeRunning) {
            try {
                NativeProxy.setOperaVpnConfig(
                    vlessEnabled = config.useOperaVpnForVless,
                    warpEnabled = config.useOperaVpnForWarp,
                    endpoint = config.getEffectiveOperaEndpoint()
                )
                NativeProxy.resetNetworkSockets()
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Сбой применения Opera VPN конфигурации в NativeProxy: ${t.message}")
            }
        }
    }

    fun getWarpStatus(): String? = NativeProxy.getWarpStatus()

    fun getVlessStatus(): String? = NativeProxy.getVlessStatus()

    fun onNetworkRestored() {
        AppLogger.i("LocalProxyServer", "Сетевое подключение восстановлено. Сброс сокетов, DoH-кэша, Happy Eyeballs рейтинга и PingEngine...")
        DohResolver.clearCache()
        HappyEyeballsEngine.clearRating()
        stats.dcAffinityEngine.reset()
        pingEngine.reset()
        if (isNativeRunning) {
            try {
                NativeProxy.resetNetworkSockets()
            } catch (_: Exception) {}
        }
        measurePingAsync()
    }

    fun setNetworkInterface(isMobile: Boolean, isScreenOn: Boolean = true) {
        adaptiveHeartbeatEngine.isMobileNetwork = isMobile
        adaptiveHeartbeatEngine.isScreenOn = isScreenOn
        activeLivenessProbe.isScreenOn = isScreenOn
    }

    fun setNetworkDormancy(isDormant: Boolean) {
        pingEngine.setDormant(isDormant)
        activeLivenessProbe.isDormant = isDormant
        if (isDormant) {
            currentProfileGeneration.incrementAndGet()
            AppLogger.i("LocalProxyServer", "Вход в спящий режим ожидания сети (Deep Dormancy / Offline). Сброс сокетов и пауза фоновых опросов.")
            stats.connectionQuality = ConnectionQuality.OFFLINE
            stats.lastFailureType = FailureType.NETWORK_LOST
            stats.healthVerdict = "Ожидание сети"
            stats.healthDetail = "Связь с интернетом отсутствует. Ожидание подключения..."
            stats.healthScore = 0
            if (isNativeRunning) {
                try {
                    NativeProxy.resetNetworkSockets()
                } catch (_: Exception) {}
            }
        } else {
            AppLogger.i("LocalProxyServer", "Выход из спящего режима ожидания сети (Network Restored).")
            onNetworkRestored()
        }
    }

    fun resetWsPool() {
        if (isNativeRunning) {
            try {
                NativeProxy.resetNetworkSockets()
            } catch (_: Exception) {}
        }
    }

    /**
     * Предиктивный упреждающий прогрев пула WsPool и обновление сессий при пробуждении устройства.
     */
    fun predictivePreWarm(reason: String = "ACTION_USER_PRESENT") {
        if (!isRunning || !isNativeRunning) return
        AppLogger.i("LocalProxyServer", "Предиктивный прогрев WsPool ($reason)...")
        try {
            NativeProxy.resetNetworkSockets()
        } catch (_: Exception) {}
        measurePingAsync()
    }

    fun getPoolSocketCount(): Int = stats.activeConnections.get()

    fun getTelegramProxyUrl(): String {
        val cleanSecret = config.rawSecret32
        val secretWithPrefix = when {
            config.secretHex.startsWith("ee") || config.secretHex.startsWith("dd") -> config.secretHex
            else -> "dd$cleanSecret"
        }
        return "tg://proxy?server=${config.bindHost}&port=${config.bindPort}&secret=$secretWithPrefix"
    }

    fun getTelegramSocks5Url(): String {
        val userParam = try {
            java.net.URLEncoder.encode(config.socks5Username, "UTF-8")
        } catch (_: Exception) {
            config.socks5Username
        }
        val passParam = try {
            java.net.URLEncoder.encode(config.socks5Password, "UTF-8")
        } catch (_: Exception) {
            config.socks5Password
        }
        return "tg://socks?server=${config.bindHost}&port=${config.activePort}&user=$userParam&pass=$passParam"
    }

    private fun handleCascadeTransition(oldStage: CascadeStage, newStage: CascadeStage, reason: String) {
        stats.activeCascadeStage = newStage.title
        AppLogger.w("LocalProxyServer", "Каскадный переход аплинка: [${oldStage.title}] -> [${newStage.title}]. Причина: $reason")

        when (newStage) {
            CascadeStage.STAGE_0_IPV6_WARP -> {
                val ipv6 = WarpEndpointScanner.CLEAN_IPV6_POOL.firstOrNull() ?: "2606:4700:d0::a29f:c001"
                applyWarpEndpoint("[$ipv6]:8095")
                applyUplinkMode(UplinkMode.MASQUE)
            }
            CascadeStage.STAGE_1_SCANNED_WARP -> {
                scope.launch(Dispatchers.IO) {
                    val best = WarpEndpointScanner.findBestEndpoint(
                        useFragmentation = true,
                        maxCandidatesToProbe = 16,
                        timeoutMs = 600
                    )
                    if (best != null) {
                        applyWarpEndpoint(best.endpoint)
                    } else {
                        applyWarpEndpoint("188.114.96.1:8095")
                    }
                    applyUplinkMode(UplinkMode.MASQUE)
                }
            }
            CascadeStage.STAGE_2_MASQUE_HTTP3 -> {
                applyWarpEndpoint("188.114.96.1:443")
                applyUplinkMode(UplinkMode.MASQUE)
            }
            CascadeStage.STAGE_3_VLESS_PRESET -> {
                val preset = VlessPresetsRepository.getDefaultPreset()
                applyVlessPreset(preset)
                applyUplinkMode(UplinkMode.VLESS)
            }
        }
    }

    /**
     * Динамически включает/выключает быструю контрольную пробу туннеля.
     */
    fun applyLivenessProbeConfig(enabled: Boolean, timeoutMs: Int = 800, threshold: Int = 2) {
        config.isLivenessProbeEnabled = enabled
        config.livenessProbeTimeoutMs = timeoutMs
        config.livenessProbeFailoverThreshold = threshold
        activeLivenessProbe.isEnabled = enabled
        activeLivenessProbe.timeoutMs = timeoutMs
        activeLivenessProbe.failoverThreshold = threshold
        if (enabled && isRunning && config.isSocks5Mode) {
            activeLivenessProbe.start()
        } else if (!enabled) {
            activeLivenessProbe.stop()
        }
    }
}
