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
import java.net.InetSocketAddress
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

    private val _warmPoolStatus = MutableStateFlow<WarmPoolStatus?>(null)
    val warmPoolStatus: StateFlow<WarmPoolStatus?> = _warmPoolStatus.asStateFlow()

    private val _adaptiveNetworkDecision = MutableStateFlow(adaptiveController.currentDecision)
    val adaptiveNetworkDecision: StateFlow<NetworkEvaluationDecision> = _adaptiveNetworkDecision.asStateFlow()

    fun setNagleMode(mode: NagleMode) {
        config.nagleMode = mode
        val effective = (mode == NagleMode.DISABLED)
        config.tcpNoDelay = effective
        currentEffectiveTcpNoDelay = effective
        if (isNativeRunning) {
            try {
                NativeProxy.setTcpNoDelay(effective)
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Failed to set TCP_NODELAY: ${t.message}")
            }
        }
    }

    @Deprecated("Use setNagleMode instead")
    @Suppress("DEPRECATION")
    fun setTcpNoDelayMode(mode: TcpNoDelayMode) {
        val nagle = if (mode == TcpNoDelayMode.ON) NagleMode.DISABLED else NagleMode.ENABLED
        setNagleMode(nagle)
    }

    private val _isRunningFlow = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

    @Volatile
    var isRunning: Boolean = false
        private set(value) {
            field = value
            _isRunningFlow.value = value
        }

    @Volatile
    var isNativeRunning: Boolean = false
        private set

    @Volatile
    var startTimeMs: Long = 0L
        private set

    val pingEngine = PingEngine(
        targetProvider = {
            if (config.isSocks5Mode) {
                config.getEffectiveCfDomain()
            } else {
                if (config.useWorkerMtproto && config.getEffectiveCfDomain().isNotBlank()) {
                    config.getEffectiveCfDomain()
                } else {
                    TgConstants.getWsDomains(2).firstOrNull() ?: ("kws2." + TgConstants.decodeCfDomain("virkgj.com"))
                }
            }
        },
        isSocks5Provider = { config.isSocks5Mode || (config.useWorkerMtproto && config.getEffectiveCfDomain().isNotBlank()) },
        onSelfHealingRequired = { failureType ->
            AppLogger.w("LocalProxyServer", "Watchdog: Зафиксирован сетевой сбой $failureType")
            when (failureType) {
                FailureType.DNS_FAILURE -> {
                    // DNS failure handler: DoH removed
                }
                FailureType.CONNECT_TIMEOUT -> {
                    // Мягкая обработка таймаута соединения без сброса рабочих потоков
                }
                else -> {}
            }
        },
        onSnapshotUpdated = { snapshot ->
            if (snapshot.smoothedPingMs > 0) {
                val nextSli = effectiveNetworkProfile.transportSli.copy(
                    smoothedRttMs = snapshot.smoothedPingMs,
                    jitterMs = snapshot.jitterMs
                )
                updateTransportSli(nextSli)
            }
        }
    )

    val adaptiveHeartbeatEngine = AdaptiveHeartbeatEngine(
        statsProvider = { stats },
        onHeartbeatTick = {
            // Адаптивный NAT Keep-Alive: поддержание трансляции без холостых Happy Eyeballs сокетов
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
    var isVpnServer: Boolean = false
        private set

    @Volatile
    var currentEffectiveTcpNoDelay: Boolean = true
        private set

    val currentProfileGeneration = java.util.concurrent.atomic.AtomicLong(1L)

    val uptimeSeconds: Long
        get() = if (isRunning && startTimeMs > 0L) (System.currentTimeMillis() - startTimeMs) / 1000L else 0L

    @Synchronized
    fun start(cacheDir: File? = null): Boolean {
        if (isRunning) return true
        isVpnServer = false
        // Автономные режимы SOCKS5 и MTProto строго используют Cloudflare Worker (WSS)
        config.uplinkModeName = UplinkMode.WORKER.name
        return performStart(cacheDir)
    }

    @Synchronized
    fun startForVpn(baseConfig: ProxyConfig, cacheDir: File? = null): Boolean {
        isVpnServer = true
        if (isRunning) {
            // Если уже запущен в SOCKS5 с нужным режимом туннеля VPN — не перезапускаем
            if (config.isSocks5Mode && config.vpnUplinkMode == baseConfig.vpnUplinkMode) {
                AppLogger.i("LocalProxyServer", "SOCKS5 сервер уже запущен для VPN с режимом ${config.vpnUplinkMode}")
                return true
            }
            AppLogger.i("LocalProxyServer", "Перезапуск SOCKS5 сервера для VPN с режимом ${baseConfig.vpnUplinkMode}...")
            stop()
        }


        // Обновляем параметры текущего экземпляра config под требования VPN
        config.proxyModeName = ProxyMode.SOCKS5.name
        config.vpnUplinkMode = baseConfig.vpnUplinkMode
        config.vpnMtu = baseConfig.vpnMtu
        config.vpnBlockQuic = baseConfig.vpnBlockQuic
        config.vpnBlockIpv6Leaks = baseConfig.vpnBlockIpv6Leaks
        config.socks5Port = baseConfig.socks5Port
        config.socks5Username = baseConfig.socks5Username
        config.socks5Password = baseConfig.socks5Password
        config.customCfDomain = baseConfig.customCfDomain
        config.vlessDomain = baseConfig.vlessDomain
        config.vlessUuid = baseConfig.vlessUuid
        config.vlessPath = baseConfig.vlessPath
        config.useOperaVpnForVless = baseConfig.useOperaVpnForVless
        config.useOperaVpnForWarp = baseConfig.useOperaVpnForWarp
        config.operaVpnNodeId = baseConfig.operaVpnNodeId
        config.awgStrategyName = baseConfig.awgStrategyName
        config.awgCustomIni = baseConfig.awgCustomIni

        return performStart(cacheDir)
    }

    private fun performStart(cacheDir: File?): Boolean {
        stats.resetBaseline()

        // Engineering settings are part of the connection contract: commit them
        // before any protocol creates a native socket.
        applyAdvancedNetworkSettings(
            config.happyEyeballsDelayMs,
            config.ipFamilyPreference,
            config.webSocketKeepAliveSeconds
        )

        if (cacheDir != null) {
            try {
                NativeProxy.setCfProxyCacheDir(cacheDir.absolutePath)
            } catch (t: Throwable) {
                AppLogger.e("LocalProxyServer", "Не удалось установить кэш-директорию Cloudflare: ${t.message}")
            }
        }

        NativeProxy.setPoolSize(config.poolSize.coerceIn(2, 16))
        NativeProxy.setMtprotoStandbyPerActiveSlot(config.mtprotoStandbyPerActiveSlot)
        
        val initialNoDelay = (config.nagleMode == NagleMode.DISABLED)
        currentEffectiveTcpNoDelay = initialNoDelay
        NativeProxy.setTcpNoDelay(initialNoDelay)


        NativeProxy.setSocks5Auth(
            username = config.socks5Username,
            password = config.socks5Password
        )
        NativeProxy.setTlsRecordPaddingEnabled(config.isTlsRecordPaddingEnabled)
        NativeProxy.setTlsClientHelloSplitEnabled(config.isTlsClientHelloSplitEnabled)
        NativeProxy.setDialThrottlingEnabled(config.isDialThrottlingEnabled)

        if (isVpnServer) {
            // Для системного VPN режима использование Cloudflare воркеров категорически отключено
            NativeProxy.setCfProxyConfig(
                enabled = false,
                userDomain = ""
            )
            NativeProxy.setUplinkMode(config.vpnUplinkMode)
            if (config.isVpnAnyWarpUplink) {
                val awgIni = config.awgCustomIni.trim().ifEmpty {
                    config.getAmneziaWgConfig(cleanEndpoint = config.effectivePeerEndpoint)
                }
                NativeProxy.setAwgConfig(awgIni)
            }
        } else {
            // Для автономных режимов прокси (MTProto / SOCKS5):
            // MTProto: если включен useWorkerMtproto — строго через Cloudflare Worker WSS пользователя.
            // Если выключен — через распределенную сеть Anycast SDN Flowseal (CFProxy).
            // SOCKS5: строго Cloudflare Worker WSS.
            val useCf = config.cfProxyEnabled
            val workerDomain = if (config.isSocks5Mode) {
                config.getEffectiveCfDomain()
            } else {
                if (config.useWorkerMtproto) config.getEffectiveCfDomain() else ""
            }

            NativeProxy.setCfProxyConfig(
                enabled = useCf,
                userDomain = workerDomain
            )
            NativeProxy.setUplinkMode(UplinkMode.WORKER)
        }

        var code: Int

        if (config.isSocks5Mode) {
            val workerDomain = config.getEffectiveCfDomain()
            AppLogger.i("LocalProxyServer", "Запуск нативного SOCKS5 движка на порту ${config.socks5Port} (worker=${workerDomain.ifBlank { "direct" }}, auth=${config.hasSocks5Auth}, optimistic=${config.socks5OptimisticResponse})...")
            NativeProxy.setSocks5Optimistic(config.socks5OptimisticResponse)
            var code = -1
            val maxAttempts = 4
            for (attempt in 1..maxAttempts) {
                code = NativeProxy.startSocks5Proxy(
                    host = config.bindHost,
                    port = config.socks5Port,
                    verbose = if (config.verboseLogs) 1 else 0
                )
                if (code == 0) break
                AppLogger.w("LocalProxyServer", "Порт ${config.socks5Port} освобождается (код $code, попытка $attempt/$maxAttempts), ожидание...")
                if (code == -1) {
                    NativeProxy.stopProxy()
                }
                Thread.sleep(attempt * 120L)
            }

            if (code == 0) {
                isNativeRunning = true
                isRunning = true
                if (startTimeMs == 0L) {
                    startTimeMs = System.currentTimeMillis()
                }
                stats.activeCascadeStage = if (workerDomain.isNotBlank()) "Cloudflare Worker WSS" else "Direct TCP"
                stats.activeEffectiveRoute = workerDomain.ifBlank { "Direct TCP" }
                AppLogger.i("LocalProxyServer", "Нативный SOCKS5 движок успешно запущен на ${config.bindHost}:${config.socks5Port}")
            } else {
                AppLogger.e("LocalProxyServer", "Ошибка запуска нативного SOCKS5 движка после $maxAttempts попыток: код $code")
                isNativeRunning = false
                isRunning = false
                return false
            }
        } else {
            AppLogger.i("LocalProxyServer", "Запуск нативного MTProto движка на ${config.bindHost}:${config.bindPort}...")
            val secret = config.rawSecret32
            val defaultDcIps = "1:149.154.175.50,2:149.154.167.51,3:149.154.175.100,4:149.154.167.91,5:91.108.56.130,203:91.105.192.100"
            var code = -1
            val maxAttempts = 4
            for (attempt in 1..maxAttempts) {
                code = NativeProxy.startProxy(
                    host = config.bindHost,
                    port = config.bindPort,
                    dcIps = defaultDcIps,
                    secret = secret,
                    verbose = if (config.verboseLogs) 1 else 0
                )
                if (code == 0) break
                AppLogger.w("LocalProxyServer", "Порт ${config.bindPort} освобождается (код $code, попытка $attempt/$maxAttempts), ожидание...")
                if (code == -1) {
                    NativeProxy.stopProxy()
                }
                Thread.sleep(attempt * 120L)
            }

            if (code == 0) {
                isNativeRunning = true
                isRunning = true
                if (startTimeMs == 0L) {
                    startTimeMs = System.currentTimeMillis()
                }
                AppLogger.i("LocalProxyServer", "Нативный MTProto движок успешно запущен на ${config.bindHost}:${config.bindPort}")
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

                    try {
                        val warm = NativeProxy.getWarmPoolStatus()
                        _warmPoolStatus.value = warm
                    } catch (_: Exception) {}

                    val isMob = effectiveNetworkProfile.cellular || effectiveNetworkProfile.metered
                    _transportPoolStatus.value = config.getTransportPoolStatus(
                        isMobile = isMob,
                        isRunning = isRunning,
                        activeConnections = stats.activeConnections.get()
                    )
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

                if (config.isAutoSpeedPreset && isNativeRunning) {
                    updateAutoTuning()
                }
                delay(1000)
            }
        }

        pingEngine.start()
        adaptiveHeartbeatEngine.start()

        activeLivenessProbe.isEnabled = false
        activeLivenessProbe.timeoutMs = config.livenessProbeTimeoutMs
        activeLivenessProbe.failoverThreshold = config.livenessProbeFailoverThreshold
        if (isVpnServer) {
            val initialStage = when {
                config.isVpnMasqueUplink -> {
                    val (ip, _) = WarpEndpointScanner.parseEndpoint(config.warpPeerEndpoint)
                    if (ip.contains(":")) CascadeStage.STAGE_0_IPV6_WARP
                    else CascadeStage.STAGE_1_SCANNED_WARP
                }
                else -> CascadeStage.STAGE_1_SCANNED_WARP
            }
            activeLivenessProbe.setInitialStage(initialStage)
            stats.activeCascadeStage = initialStage.title
        } else {
            stats.activeCascadeStage = when (config.proxyMode) {
                ProxyMode.SOCKS5 -> "Cloudflare Worker WSS"
                ProxyMode.MTPROTO -> if (config.useWorkerMtproto && config.getEffectiveCfDomain().isNotBlank()) "Cloudflare Worker" else "Flowseal Anycast CDN"
            }
            stats.activeEffectiveRoute = when (config.proxyMode) {
                ProxyMode.SOCKS5 -> config.getEffectiveCfDomain().ifBlank { "Cloudflare Worker" }
                ProxyMode.MTPROTO -> if (config.useWorkerMtproto && config.getEffectiveCfDomain().isNotBlank()) config.getEffectiveCfDomain() else "Flowseal Anycast CDN"
            }
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
        _warmPoolStatus.value = null
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
        val targetBuffer = if (config.isSocks5Mode) config.bufferSizeBytes else
            decision.recommendedBufferSizeBytes.coerceAtMost(qosEngine.maxAllowedBufferSizeBytes)
        if (clampedPool == config.poolSize && config.bufferSizeBytes == targetBuffer) return

        config.poolSize = clampedPool
        config.bufferSizeBytes = targetBuffer
        val bufferStatus = if (config.bufferSizeBytes == 0) "авто ОС" else "${config.bufferSizeBytes / 1024} КБ"

        AppLogger.i(
            "LocalProxyServer",
            "Авто-адаптация стека: ${decision.summary} (буфер: $bufferStatus, TCP_NODELAY: ${decision.recommendedTcpNoDelay}, QoS: ${qosEngine.currentThrottleLevel.name})"
        )

        if (isNativeRunning) {
            try {
                NativeProxy.setPoolSize(clampedPool)
                NativeProxy.setMtprotoStandbyPerActiveSlot(clampedPool.coerceIn(1, 4))
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
                NativeProxy.setMtprotoStandbyPerActiveSlot(clamped.coerceIn(1, 4))
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
        pingEngine.reset()
        updateWorkerConfig()
    }

    fun updateWorkerConfig() {
        if (isVpnServer) {
            AppLogger.i("LocalProxyServer", "В режиме VPN использование Cloudflare воркеров отключено.")
            return
        }
        val effectiveDomain = config.getEffectiveCfDomain()
        val workerEnabled = if (config.isSocks5Mode) {
            config.cfProxyEnabled && effectiveDomain.isNotBlank()
        } else {
            config.cfProxyEnabled
        }
        val domainToPass = if (config.isSocks5Mode) {
            effectiveDomain
        } else {
            if (config.useWorkerMtproto) effectiveDomain else ""
        }
        AppLogger.i("LocalProxyServer", "Обновление конфигурации воркера → '$domainToPass' (enabled=$workerEnabled, mtprotoWorker=${config.useWorkerMtproto})")
        if (isNativeRunning) {
            try {
                NativeProxy.setCfProxyConfig(
                    enabled = workerEnabled,
                    userDomain = domainToPass
                )
                NativeProxy.setTlsRecordPaddingEnabled(config.isTlsRecordPaddingEnabled)
                NativeProxy.setTlsClientHelloSplitEnabled(config.isTlsClientHelloSplitEnabled)
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Ошибка применения нового воркера в NativeProxy: ${t.message}")
            }
        }
        stats.activeCascadeStage = when (config.proxyMode) {
            ProxyMode.SOCKS5 -> "Cloudflare Worker WSS"
            ProxyMode.MTPROTO -> if (config.useWorkerMtproto && domainToPass.isNotBlank()) "Cloudflare Worker" else "Flowseal Anycast CDN"
        }
        stats.activeEffectiveRoute = when (config.proxyMode) {
            ProxyMode.SOCKS5 -> effectiveDomain.ifBlank { "Cloudflare Worker" }
            ProxyMode.MTPROTO -> if (config.useWorkerMtproto && domainToPass.isNotBlank()) domainToPass else "Flowseal Anycast CDN"
        }
        measurePingAsync()
    }

    fun applyUseWorkerMtproto(enabled: Boolean) {
        config.useWorkerMtproto = enabled
        updateWorkerConfig()
    }

    fun applyEchEnabled(enabled: Boolean) {
        AppLogger.i("LocalProxyServer", "Смена режима ECH → $enabled")
        if (isNativeRunning) {
            try {
                NativeProxy.setEchEnabled(enabled)
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "NativeProxy.setEchEnabled($enabled) не удался: ${t.message}")
            }
        }
    }

    fun applyWsRandomizationEnabled(enabled: Boolean) {
        NativeProxy.setWsRandomizationEnabled(enabled)
    }

    fun applyTlsRecordPaddingEnabled(enabled: Boolean) {
        config.isTlsRecordPaddingEnabled = enabled
        AppLogger.i("LocalProxyServer", "Смена режима TLS Record Padding → $enabled")
        if (isNativeRunning) {
            try {
                NativeProxy.setTlsRecordPaddingEnabled(enabled)
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "NativeProxy.setTlsRecordPaddingEnabled($enabled) не удался: ${t.message}")
            }
        }
    }

    fun applyTlsClientHelloSplitEnabled(enabled: Boolean) {
        config.isTlsClientHelloSplitEnabled = enabled
        AppLogger.i("LocalProxyServer", "Смена режима TLS ClientHello Split → $enabled")
        if (isNativeRunning) {
            try {
                NativeProxy.setTlsClientHelloSplitEnabled(enabled)
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "NativeProxy.setTlsClientHelloSplitEnabled($enabled) не удался: ${t.message}")
            }
        }
    }

    /**
     * DoH удален из контура MTProto и SOCKS5.
     */
    fun applyDohConfig(providerIds: Set<String>) {
        config.enabledDohProviderIds = emptySet()
    }

    /**
     * Динамически применяет выбранный режим аплинка (Worker WSS / WARP MASQUE / Hybrid).
     */
    fun applyBufferSizeBytes(newSize: Int) {
        val clamped = if (newSize <= 0) {
            0
        } else {
            newSize.coerceIn(32 * 1024, 1024 * 1024)
        }
        config.bufferSizeBytes = clamped
        if (isNativeRunning) {
            try {
                NativeProxy.setSocketBufferSizes(config.bufferSizeBytes, config.bufferSizeBytes)
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "setSocketBufferSizes failed: ${t.message}")
            }
        }
    }

    /** Applies expert transport policy to every native protocol (MTProto, SOCKS5,
     * Worker WSS, and VPN uplinks) without recreating the Android service. */
    fun applyAdvancedNetworkSettings(
        happyEyeballsDelayMs: Long = config.happyEyeballsDelayMs,
        ipFamilyPreference: IpFamilyPreference = config.ipFamilyPreference,
        webSocketKeepAliveSeconds: Int = config.webSocketKeepAliveSeconds
    ) {
        config.happyEyeballsDelayMs = happyEyeballsDelayMs.coerceIn(100L, 1000L)
        config.ipFamilyPreference = ipFamilyPreference
        config.webSocketKeepAliveSeconds = webSocketKeepAliveSeconds.coerceIn(15, 120)
        HappyEyeballsEngine.setIpFamilyPreference(config.ipFamilyPreference)
        HappyEyeballsEngine.setAttemptDelayMs(config.happyEyeballsDelayMs)
        effectiveNetworkProfile = effectiveNetworkProfile.copy(
            happyEyeballsDelayMs = config.happyEyeballsDelayMs,
            ipFamilyPreference = config.ipFamilyPreference.name,
            webSocketKeepAliveSeconds = config.webSocketKeepAliveSeconds
        ).normalized()
        try {
            // This is deliberately valid before start(): native configuration
            // must be committed before the selected protocol opens its first socket.
            NativeProxy.setNetworkProfileJson(effectiveNetworkProfile.toJsonString())
            NativeProxy.setSocketBufferSizes(config.bufferSizeBytes, config.bufferSizeBytes)
        } catch (t: Throwable) {
            AppLogger.w("LocalProxyServer", "Failed to apply expert network settings: ${t.message}")
        }
    }

    fun applyMtprotoStandbyPerActiveSlot(slots: Int) {
        val clamped = slots.coerceIn(1, 4)
        config.mtprotoStandbyPerActiveSlot = clamped
        if (isNativeRunning) {
            try {
                NativeProxy.setMtprotoStandbyPerActiveSlot(clamped)
                NativeProxy.setPoolSize(clamped)
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "applyMtprotoStandbyPerActiveSlot($clamped) failed: ${t.message}")
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
        if (isNativeRunning) {
            try { NativeProxy.setNetworkProfileJson(effectiveNetworkProfile.toJsonString()) }
            catch (t: Throwable) { AppLogger.w("LocalProxyServer", "Failed to update native network environment: ${t.message}") }
        }
    }

    fun updateTransportSli(sli: TransportSli) {
        effectiveNetworkProfile = effectiveNetworkProfile.withTransportSli(sli)
        if (isNativeRunning) {
            try { NativeProxy.setNetworkProfileJson(effectiveNetworkProfile.toJsonString()) }
            catch (t: Throwable) { AppLogger.w("LocalProxyServer", "Failed to update native transport SLI: ${t.message}") }
        }
    }

    fun updateScreenPowerMode(screenOn: Boolean, powerSaveMode: Boolean) {
        effectiveNetworkProfile = effectiveNetworkProfile.withRuntime(screenOn, powerSaveMode)
        pingEngine.isScreenOn = screenOn
        adaptiveHeartbeatEngine.isScreenOn = screenOn
        activeLivenessProbe.isScreenOn = screenOn
        if (isNativeRunning) {
            try {
                NativeProxy.setNetworkProfileJson(effectiveNetworkProfile.toJsonString())
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Failed to update native network profile: ${t.message}")
            }
            if (screenOn) {
                try {
                    NativeProxy.onScreenWakeup()
                } catch (t: Throwable) {
                    AppLogger.w("LocalProxyServer", "Failed to notify native onScreenWakeup: ${t.message}")
                }
            }
        }
    }

    fun nextGeneration(): Long {
        val gen = currentProfileGeneration.incrementAndGet()
        effectiveNetworkProfile = effectiveNetworkProfile.withGeneration(gen)
        if (isNativeRunning) {
            try {
                NativeProxy.setNetworkProfileJson(effectiveNetworkProfile.toJsonString())
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Failed to update native network generation: ${t.message}")
            }
        }
        return gen
    }

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
        val gen = nextGeneration()
        setNetworkInterface(isMobile, isScreenOn)
        resumeNetworkMonitoring()
        try {
            HappyEyeballsEngine.setNetworkGeneration(gen)
            HappyEyeballsEngine.clearRating()
        } catch (_: Throwable) {}
        try {
            pingEngine.invalidateInFlight()
            measurePingAsync()
        } catch (_: Throwable) {}
    }

    /**
     * Обработка событий приостановки/возобновления сети.
     */
    fun handleNetworkSuspended() {
        if (isNativeRunning) {
            try {
                NativeProxy.suspendNetworkSockets()
                AppLogger.i("LocalProxyServer", "Сеть приостановлена (SUSPENDED): сокеты переведены в спящий режим")
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Ошибка приостановки нативных сокетов: ${t.message}")
            }
        }
    }

    fun handleNetworkResumed(isMobile: Boolean = false, isScreenOn: Boolean = true) {
        setNetworkInterface(isMobile, isScreenOn)
        resumeNetworkMonitoring()
        measurePingAsync()
    }

    /** A validated network is usable again; preserve established bridges. */
    fun resumeNetworkMonitoring() {
        // The caller schedules the probe after updating the network generation.
        // Leaving dormancy through the legacy setter would reset all connections.
        pingEngine.setDormant(false, probeImmediately = false)
        activeLivenessProbe.isDormant = false
    }

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
        if (expectedMode != null && config.uplinkMode != expectedMode && config.vpnUplinkMode != expectedMode) {
            AppLogger.w("LocalProxyServer", "Rejected warp endpoint change: mode ${config.uplinkMode} does not match expected $expectedMode")
            return false
        }
        if (expectedGeneration != null && !isRunning && !isNativeRunning) {
            return false
        }
        config.warpPeerEndpoint = endpoint
        config.warpMasquePeerEndpoint = endpoint
        config.warpMeasuredWgEndpoint = endpoint
        if (expectedGeneration == null) {
            currentProfileGeneration.incrementAndGet()
        }
        if (isNativeRunning && isVpnServer && config.isVpnAnyWarpUplink) {
            try {
                val awgIni = config.awgCustomIni.trim().ifEmpty {
                    config.getAmneziaWgConfig(cleanEndpoint = endpoint)
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
     * Динамически применяет выбранный режим аплинка VPN к нативному ядру.
     */
    fun applyVpnUplinkMode(mode: UplinkMode, expectedGeneration: Long? = null): Boolean {
        if (!isVpnServer) return false
        if (expectedGeneration != null && currentProfileGeneration.get() != expectedGeneration) {
            AppLogger.w("LocalProxyServer", "Rejected VPN uplink mode change: generation $expectedGeneration is stale (current: ${currentProfileGeneration.get()})")
            return false
        }
        config.vpnUplinkMode = mode
        currentProfileGeneration.incrementAndGet()
        AppLogger.i("LocalProxyServer", "Применение режима аплинка VPN: ${mode.displayName} (gen=${currentProfileGeneration.get()})")
        applyVpnUplinkModeInternal(mode)
        return true
    }

    private fun applyVpnUplinkModeInternal(mode: UplinkMode) {
        if (isNativeRunning) {
            try {
                NativeProxy.setUplinkMode(mode)
                if (config.isVpnAnyWarpUplink) {
                    val awgIni = config.awgCustomIni.trim().ifEmpty {
                        config.getAmneziaWgConfig(cleanEndpoint = config.warpPeerEndpoint)
                    }
                    NativeProxy.setAwgConfig(awgIni)
                }
                NativeProxy.resetNetworkSockets()
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Сбой применения режима аплинка VPN в NativeProxy: ${t.message}")
            }
        }
    }

    /**
     * Динамически применяет режим аплинка (с проверкой поколения профиля).
     */
    fun applyUplinkMode(mode: UplinkMode, expectedGeneration: Long? = null): Boolean {
        if (expectedGeneration != null && currentProfileGeneration.get() != expectedGeneration) {
            AppLogger.w("LocalProxyServer", "Rejected uplink mode change: generation $expectedGeneration is stale (current: ${currentProfileGeneration.get()})")
            return false
        }
        config.uplinkMode = mode
        if (isVpnServer) {
            config.vpnUplinkMode = mode
        }
        currentProfileGeneration.incrementAndGet()
        if (isVpnServer) {
            applyVpnUplinkModeInternal(mode)
        }
        return true
    }

    fun getWarpStatus(): String? = NativeProxy.getWarpStatus()

    fun onNetworkRestored() {
        AppLogger.i("LocalProxyServer", "Сетевое подключение восстановлено. Сброс сокетов, Happy Eyeballs рейтинга и PingEngine...")
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
        pingEngine.isScreenOn = isScreenOn
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
     * Предиктивный упреждающий прогрев отключен в пользу прямого соединения по требованию (0 мс холостых сокетов).
     */
    fun predictivePreWarm(reason: String = "ACTION_USER_PRESENT") {
        // Safe no-op: speculative socket warming removed to conserve network and battery
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

    fun getTelegramUrl(): String = when (config.proxyMode) {
        ProxyMode.SOCKS5 -> getTelegramSocks5Url()
        ProxyMode.MTPROTO -> getTelegramProxyUrl()
    }

    private fun handleCascadeTransition(oldStage: CascadeStage, newStage: CascadeStage, reason: String) {
        if (!isVpnServer) {
            AppLogger.w("LocalProxyServer", "Каскадный переход проигнорирован: сервер работает в автономном режиме прокси")
            return
        }
        stats.activeCascadeStage = newStage.title
        AppLogger.w("LocalProxyServer", "Каскадный переход аплинка VPN: [${oldStage.title}] -> [${newStage.title}]. Причина: $reason")

        when (newStage) {
            CascadeStage.STAGE_0_IPV6_WARP -> {
                val ipv6 = WarpEndpointScanner.CLEAN_IPV6_POOL.firstOrNull() ?: "2606:4700:d0::a29f:c001"
                applyWarpEndpoint("[$ipv6]:8095")
                applyVpnUplinkMode(UplinkMode.MASQUE)
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
                    applyVpnUplinkMode(UplinkMode.MASQUE)
                }
            }
            CascadeStage.STAGE_2_MASQUE_HTTP3 -> {
                applyWarpEndpoint("188.114.96.1:443")
                applyVpnUplinkMode(UplinkMode.MASQUE)
            }
        }
    }

    /**
     * Проверяет, отвечает ли локальный сокет-слушатель прокси на loopback-соединения.
     * Возвращает true, если TCP handshake успешно завершен за timeoutMs миллисекунд.
     */
    fun isPortResponding(timeoutMs: Int = 500): Boolean {
        if (!isRunning || !isNativeRunning) return false
        val port = if (config.isSocks5Mode) config.socks5Port else config.bindPort
        val host = config.bindHost.ifBlank { "127.0.0.1" }
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
