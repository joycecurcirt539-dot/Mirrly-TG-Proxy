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

    @Volatile
    var currentPingMs: Long = -1L
        private set

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
        val workerDomain = config.getEffectiveCfDomain()

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

            if (code == 3) {
                AppLogger.w("LocalProxyServer", "Порт SOCKS5 ${config.socks5Port} всё ещё освобождается (code 3), повторный запуск...")
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

            if (code == 3) {
                AppLogger.w("LocalProxyServer", "Порт ${config.bindPort} всё ещё освобождается (code 3), повторный запуск...")
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
                if (System.currentTimeMillis() % 5000 < 1000) {
                    measurePingAsync()
                }
                if (config.isAutoSpeedPreset && isNativeRunning) {
                    updateAutoTuning()
                }
                delay(1000)
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
        val ping = currentPingMs

        // Determine candidate pool size based on traffic throughput and network latency
        val candidatePool = when {
            ping > 600L -> 2 // High latency / unstable network -> Eco
            totalSpeed >= 6_291_456L -> 16 // > 6 MB/s -> Ultra
            totalSpeed >= 1_572_864L -> 8  // 1.5 MB/s .. 6 MB/s -> Turbo
            totalSpeed >= 153_600L -> 4    // 150 KB/s .. 1.5 MB/s -> Balanced
            else -> 2                      // Low traffic / idle -> Eco
        }

        val currentPool = config.poolSize

        if (candidatePool > currentPool) {
            autoConsecutiveHighTicks++
            autoConsecutiveLowTicks = 0
            if (autoConsecutiveHighTicks >= 2) {
                autoConsecutiveHighTicks = 0
                applyAutoPoolSize(candidatePool)
            }
        } else if (candidatePool < currentPool) {
            autoConsecutiveLowTicks++
            autoConsecutiveHighTicks = 0
            if (autoConsecutiveLowTicks >= 5) {
                autoConsecutiveLowTicks = 0
                applyAutoPoolSize(candidatePool)
            }
        } else {
            autoConsecutiveHighTicks = 0
            autoConsecutiveLowTicks = 0
        }
    }

    private fun applyAutoPoolSize(newSize: Int) {
        val clamped = newSize.coerceIn(2, 16)
        if (clamped == config.poolSize) return
        config.poolSize = clamped
        config.bufferSizeBytes = when (clamped) {
            2 -> 131072
            4 -> 262144
            8 -> 1048576
            16 -> 2097152
            else -> 262144
        }
        AppLogger.i("LocalProxyServer", "Авто-адаптация пула сокетов: $clamped сокетов (буфер: ${config.bufferSizeBytes / 1024} КБ)")
        if (isNativeRunning) {
            try {
                NativeProxy.setPoolSize(clamped)
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Автоматический setPoolSize($clamped) не удался: ${t.message}")
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
        scope.launch {
            val cfDomain = config.getEffectiveCfDomain()
            val pingTarget = if (cfDomain.isNotBlank()) cfDomain else TgConstants.decodeCfDomain("virkgj.com")
            val ping = run {
                val start = System.currentTimeMillis()
                try {
                    Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(pingTarget, 443), 2500)
                        System.currentTimeMillis() - start
                    }
                } catch (_: Exception) {
                    -1L
                }
            }
            currentPingMs = ping
        }
    }

    fun onWorkerChanged(newWorkerDomain: String) {
        config.customCfDomain = newWorkerDomain
        val effectiveDomain = config.getEffectiveCfDomain()
        AppLogger.i("LocalProxyServer", "Смена активного воркера → $effectiveDomain")
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
        AppLogger.i("LocalProxyServer", "Сетевое подключение восстановлено. Сброс сокетов...")
        if (isNativeRunning) {
            try {
                NativeProxy.resetNetworkSockets()
            } catch (_: Exception) {}
        }
        measurePingAsync()
    }

    fun resetWsPool() {
        if (isNativeRunning) {
            try {
                NativeProxy.resetNetworkSockets()
            } catch (_: Exception) {}
        }
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
