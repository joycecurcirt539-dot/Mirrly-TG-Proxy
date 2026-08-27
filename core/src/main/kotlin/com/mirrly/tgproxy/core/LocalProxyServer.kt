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

class LocalProxyServer(val config: ProxyConfig = ProxyConfig()) {
    private var speedJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val stats = ProxyStats()

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
            val cfDomain = config.getEffectiveCfDomain()
            if (cfDomain.isNotBlank()) cfDomain else TgConstants.decodeCfDomain("virkgj.com")
        },
        trafficThroughputProvider = { stats.downloadSpeedBps + stats.uploadSpeedBps },
        onSelfHealingRequired = {
            AppLogger.w("LocalProxyServer", "Watchdog: Выполняется превентивный сброс сокетов из-за серии сетевых сбоев...")
            if (isNativeRunning) {
                try {
                    NativeProxy.resetNetworkSockets()
                } catch (_: Exception) {}
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
        onThrottleLevelChanged = {
            if (config.isAutoSpeedPreset && isNativeRunning) {
                updateAutoTuning()
            }
        }
    )

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
            } catch (t: Throwable) {
                AppLogger.e("LocalProxyServer", "Не удалось установить кэш-директорию Cloudflare: ${t.message}")
            }
        }

        NativeProxy.setPoolSize(config.poolSize.coerceIn(2, 16))
        
        val initialNoDelay = when (config.tcpNoDelayMode) {
            TcpNoDelayMode.ON -> true
            TcpNoDelayMode.OFF -> false
            TcpNoDelayMode.AUTO -> config.tcpNoDelay
        }
        currentEffectiveTcpNoDelay = initialNoDelay
        NativeProxy.setTcpNoDelay(initialNoDelay)

        val useCf = config.cfProxyEnabled
        val workerDomain = if (config.isSocks5Mode) {
            config.getEffectiveCfDomain()
        } else {
            // MTProto маршрутизируется исключительно через глобальный Anycast CDN Flowseal (kws{dc}.{domain}/apiws).
            // Пользовательские воркеры и воркеры разработчика намеренно НЕ применяются к MTProto,
            // так как прямое туннелирование MTProto через воркеры нарушает сетевой стек CDN и ломает подключение.
            ""
        }

        NativeProxy.setCfProxyConfig(
            enabled = useCf,
            userDomain = workerDomain
        )

        var code: Int

        if (config.isSocks5Mode) {
            AppLogger.i("LocalProxyServer", "Запуск нативного SOCKS5 движка на порту ${config.socks5Port}...")
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

        speedJob = scope.launch {
            while (isActive && isRunning) {
                if (isNativeRunning) {
                    try {
                        val nativeStats = NativeProxy.getStats()
                        if (!nativeStats.isNullOrEmpty()) {
                            stats.parseNativeStats(nativeStats)
                        }
                    } catch (_: Exception) {}
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
        val effectiveDomain = if (config.isSocks5Mode) {
            config.getEffectiveCfDomain()
        } else {
            // MTProto маршрутизируется исключительно через глобальный Anycast CDN Flowseal (kws{dc}.{domain}/apiws).
            // Пользовательские воркеры и воркеры разработчика намеренно НЕ применяются к MTProto.
            ""
        }
        AppLogger.i("LocalProxyServer", "Обновление конфигурации воркера → '$effectiveDomain'")
        if (isNativeRunning) {
            try {
                NativeProxy.setCfProxyConfig(
                    enabled = config.cfProxyEnabled,
                    userDomain = effectiveDomain
                )
                NativeProxy.resetNetworkSockets()
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Ошибка применения нового воркера в NativeProxy: ${t.message}")
            }
        }
        measurePingAsync()
    }

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
        // Include empty user/pass for compatibility with all Telegram versions (NO AUTH mode)
        return "tg://socks?server=${config.bindHost}&port=${config.activePort}&user=&pass="
    }
}
