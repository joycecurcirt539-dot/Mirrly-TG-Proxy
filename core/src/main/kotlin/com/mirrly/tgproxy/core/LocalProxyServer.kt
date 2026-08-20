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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalProxyServer(val config: ProxyConfig = ProxyConfig()) {
    private var speedJob: Job? = null
    private var kotlinEngineJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var socks5Job: Job? = null
    private var socks5ServerSocket: ServerSocket? = null
    private var wsPool: WsPool? = null
    private val scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

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

    val uptimeSeconds: Long
        get() = if (isRunning && startTimeMs > 0L) (System.currentTimeMillis() - startTimeMs) / 1000L else 0L

    @Synchronized
    fun start(cacheDir: File? = null): Boolean {
        if (isRunning) return true
        return performStart(cacheDir)
    }

    private fun performStart(cacheDir: File?): Boolean {
        stats.resetBaseline()

        // В режиме SOCKS5 — сначала пробуем высокопроизводительный нативный SOCKS5 Rust-движок (с фолбеком на Kotlin)
        if (config.isSocks5Mode) {
            AppLogger.i("LocalProxyServer", "Настройка нативного SOCKS5 движка прокси...")
            var nativeStarted = false
            try {
                if (cacheDir != null) {
                    try {
                        NativeProxy.setCfProxyCacheDir(cacheDir.absolutePath)
                    } catch (t: Throwable) {
                        AppLogger.e("LocalProxyServer", "Не удалось установить кэш-директорию Cloudflare: ${t.message}")
                    }
                }

                NativeProxy.setPoolSize(config.poolSize.coerceIn(2, 16))

                val useCf = config.cfProxyEnabled
                val workerDomain = config.getEffectiveCfDomain()

                NativeProxy.setCfProxyConfig(
                    enabled = useCf,
                    userDomain = workerDomain
                )

                var code = NativeProxy.startSocks5Proxy(
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
                    nativeStarted = true
                    isNativeRunning = true
                    isRunning = true
                    if (startTimeMs == 0L) {
                        startTimeMs = System.currentTimeMillis()
                    }
                    AppLogger.i("LocalProxyServer", "Нативный SOCKS5 движок успешно запущен на порту ${config.socks5Port}")
                } else {
                    AppLogger.w("LocalProxyServer", "Нативный SOCKS5 движок вернул код $code, переключение на Kotlin-fallback")
                }
            } catch (t: Throwable) {
                AppLogger.w("LocalProxyServer", "Сбой запуска нативного SOCKS5 движка: ${t.message}, запуск Kotlin fallback")
            }

            if (!nativeStarted) {
                isNativeRunning = false
                if (wsPool == null) {
                    wsPool = WsPool(config.poolSize)
                }
                val ok = startSocks5Engine()
                if (!ok) return false
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
                    delay(1000)
                }
            }
            return true
        }

        // Режим MTPROTO — запускаем нативный движок (или Kotlin-fallback)
        AppLogger.i("LocalProxyServer", "Настройка нативного движка прокси...")
        var nativeStarted = false
        try {
            if (cacheDir != null) {
                try {
                    NativeProxy.setCfProxyCacheDir(cacheDir.absolutePath)
                } catch (t: Throwable) {
                    AppLogger.e("LocalProxyServer", "Не удалось установить кэш-директорию Cloudflare: ${t.message}")
                }
            }

            NativeProxy.setPoolSize(config.poolSize.coerceIn(2, 16))

            val useCf = config.cfProxyEnabled
            val workerDomain = config.getEffectiveCfDomain()

            NativeProxy.setCfProxyConfig(
                enabled = useCf,
                userDomain = workerDomain
            )

            val secret = config.rawSecret32
            var code = NativeProxy.startProxy(
                host = config.bindHost,
                port = config.bindPort,
                dcIps = "",
                secret = secret,
                verbose = if (config.verboseLogs) 1 else 0
            )

            if (code == 3) {
                AppLogger.w("LocalProxyServer", "Порт ${config.bindPort} всё ещё освобождается (code 3), повторный запуск...")
                code = NativeProxy.startProxy(
                    host = config.bindHost,
                    port = config.bindPort,
                    dcIps = "",
                    secret = secret,
                    verbose = if (config.verboseLogs) 1 else 0
                )
            }

            if (code == 0) {
                nativeStarted = true
                isNativeRunning = true
                isRunning = true
                if (startTimeMs == 0L) {
                    startTimeMs = System.currentTimeMillis()
                }
                AppLogger.i("LocalProxyServer", "Нативный прокси успешно запущен на ${config.bindHost}:${config.bindPort} (Cloudflare: $useCf)")
            } else {
                isNativeRunning = false
                AppLogger.w("LocalProxyServer", "Код ответа нативной библиотеки: $code, переключение на Kotlin-движок...")
            }
        } catch (t: Throwable) {
            isNativeRunning = false
            AppLogger.w("LocalProxyServer", "Нативный прокси недоступен (${t.message}), переключение на Kotlin TgWsBridge...")
        }

        if (!nativeStarted) {
            if (wsPool == null) {
                wsPool = WsPool(config.poolSize)
            }
            val ok = startKotlinEngine()
            if (!ok) return false
        } else {
            if (wsPool == null) {
                wsPool = WsPool(config.poolSize)
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
                delay(1000)
            }
        }

        return true
    }

    private fun startKotlinEngine(): Boolean {
        var socket: ServerSocket? = null
        var lastErr: Exception? = null
        for (attempt in 1..3) {
            try {
                val bindAddr = InetAddress.getByName(config.bindHost)
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(bindAddr, config.bindPort), 128)
                socket = s
                break
            } catch (e: Exception) {
                lastErr = e
                AppLogger.w("LocalProxyServer", "Попытка $attempt биндинга Kotlin-движка на ${config.bindPort} не удалась: ${e.message}")
            }
        }
        if (socket == null) {
            AppLogger.e("LocalProxyServer", "Не удалось запустить Kotlin-движок прокси: ${lastErr?.message}")
            return false
        }
        serverSocket = socket
        // Note: wsPool is created in start() before this method is called via the native fallback path.

        isRunning = true
        if (startTimeMs == 0L) {
            startTimeMs = System.currentTimeMillis()
        }
        AppLogger.i("LocalProxyServer", "Kotlin-движок TgWsBridge запущен на ${config.bindHost}:${config.bindPort}")

        kotlinEngineJob = scope.launch {
            while (isActive && isRunning && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch {
                        try {
                            val bridge = TgWsBridge(client, config, stats, wsPool)
                            bridge.handleConnection()
                        } catch (t: Throwable) {
                            AppLogger.w("LocalProxyServer", "Ошибка обработки MTProto-соединения: ${t.message}")
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        return true
    }

    private fun startSocks5Engine(): Boolean {
        var socket: ServerSocket? = null
        var lastErr: Exception? = null
        for (attempt in 1..3) {
            try {
                val bindAddr = InetAddress.getByName(config.bindHost)
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(bindAddr, config.activePort), 128)
                socket = s
                break
            } catch (e: Exception) {
                lastErr = e
                AppLogger.w("LocalProxyServer", "Попытка $attempt биндинга SOCKS5-движка на ${config.activePort} не удалась: ${e.message}")
            }
        }
        if (socket == null) {
            AppLogger.e("LocalProxyServer", "Не удалось запустить SOCKS5-движок на порту ${config.activePort}: ${lastErr?.message}")
            return false
        }
        socks5ServerSocket = socket

        isRunning = true
        if (startTimeMs == 0L) {
            startTimeMs = System.currentTimeMillis()
        }

        AppLogger.i("LocalProxyServer", "Kotlin-движок SOCKS5 запущен на ${config.bindHost}:${config.activePort} (Звонки и сообщения)")

        socks5Job = scope.launch {
            while (isActive && isRunning && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch {
                        try {
                            val bridge = Socks5WsBridge(client, config, stats, wsPool)
                            bridge.handleConnection()
                        } catch (t: Throwable) {
                            AppLogger.w("LocalProxyServer", "Ошибка обработки SOCKS5-соединения: ${t.message}")
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        if (!isRunning && !isNativeRunning && serverSocket == null && socks5ServerSocket == null) return
        performStop()
    }

    private fun performStop() {
        isRunning = false
        startTimeMs = 0L
        speedJob?.cancel()
        speedJob = null
        kotlinEngineJob?.cancel()
        kotlinEngineJob = null
        socks5Job?.cancel()
        socks5Job = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            socks5ServerSocket?.close()
        } catch (_: Exception) {}
        socks5ServerSocket = null

        try {
            wsPool?.clear()
        } catch (_: Exception) {}
        wsPool = null

        isNativeRunning = false
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

    /**
     * Applies a new pool size immediately via NativeProxy and WsPool without requiring a full restart.
     * Also updates config.poolSize so the value is consistent everywhere.
     */
    fun applyPoolSize(newSize: Int, cacheDir: File? = null) {
        val clamped = newSize.coerceIn(2, 16)
        config.poolSize = clamped
        AppLogger.i("LocalProxyServer", "Изменение пула сокетов → $clamped")
        wsPool?.updatePoolSize(clamped, config.isTestEnvironment)
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

    fun onNetworkRestored() {
        AppLogger.i("LocalProxyServer", "Сетевое подключение восстановлено. Сброс WsPool и фоновый прогрев WSS...")
        try {
            wsPool?.clear()
            wsPool?.warmUpPrimaryDCs(config.isTestEnvironment)
        } catch (e: Exception) {
            AppLogger.w("LocalProxyServer", "Ошибка при обновлении WsPool: ${e.message}")
        }
        if (isNativeRunning) {
            try {
                NativeProxy.resetNetworkSockets()
            } catch (_: Exception) {}
        }
    }

    fun resetWsPool() {
        try {
            wsPool?.clear()
        } catch (_: Exception) {}
        if (isNativeRunning) {
            try {
                NativeProxy.resetNetworkSockets()
            } catch (_: Exception) {}
        }
    }

    fun getPoolSocketCount(): Int = wsPool?.availableSockets ?: 0

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
