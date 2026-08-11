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
    private val scope = CoroutineScope(Dispatchers.IO)

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
    var isTransitioning: Boolean = false
        private set

    val uptimeSeconds: Long
        get() = if (isRunning && startTimeMs > 0L) (System.currentTimeMillis() - startTimeMs) / 1000L else 0L

    @Synchronized
    fun start(cacheDir: File? = null): Boolean {
        if (isRunning || isTransitioning) return isRunning
        isTransitioning = true
        try {
            return performStart(cacheDir)
        } finally {
            isTransitioning = false
        }
    }

    private fun performStart(cacheDir: File?): Boolean {
        stats.resetBaseline()

        // В режиме SOCKS5 — запускаем только SOCKS5-движок (прозрачный TCP relay)
        if (config.isSocks5Mode) {
            isNativeRunning = false
            if (wsPool == null) {
                wsPool = WsPool(config.poolSize)
            }
            val ok = startSocks5Engine()
            if (!ok) return false

            speedJob = scope.launch {
                while (isActive && isRunning) {
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

            // Если для MTProto задан Cloudflare Worker (кастомный или дефолтный), запускаем Kotlin TgWsBridge с WSS TCP туннелированием
            if (workerDomain.isNotEmpty()) {
                AppLogger.i("LocalProxyServer", "Для MTProto активен Cloudflare Worker ($workerDomain). Запуск Kotlin TgWsBridge...")
                isNativeRunning = false
                if (wsPool == null) {
                    wsPool = WsPool(config.poolSize)
                }
                return startKotlinEngine()
            }

            NativeProxy.setCfProxyConfig(
                enabled = useCf,
                priority = useCf,
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

            // Если сокет ещё освобождается стеком ОС (code 3 = EADDRINUSE) -> ждём 350мс и пробуем повторно
            if (code == 3) {
                AppLogger.w("LocalProxyServer", "Порт ${config.bindPort} всё ещё освобождается (code 3), повторная попытка через 350мс...")
                try { Thread.sleep(350) } catch (_: Exception) {}
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
                try { Thread.sleep(250) } catch (_: Exception) {}
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
                        val bridge = TgWsBridge(client, config, stats, wsPool)
                        bridge.handleConnection()
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
                try { Thread.sleep(250) } catch (_: Exception) {}
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
                        val bridge = Socks5WsBridge(client, config, stats, wsPool)
                        bridge.handleConnection()
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
        if (!isRunning) return
        isTransitioning = true
        try {
            performStop()
        } finally {
            isTransitioning = false
        }
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

        if (isNativeRunning) {
            isNativeRunning = false
            try {
                NativeProxy.stopProxy()
                AppLogger.i("LocalProxyServer", "Движок прокси успешно остановлен")
            } catch (t: Throwable) {
                AppLogger.e("LocalProxyServer", "Ошибка остановки нативного движка: ${t.message}")
            }
        }

        // Кроткая пауза 250 мс для полного освобождения порта сокета стеком ОС
        try { Thread.sleep(250) } catch (_: Exception) {}
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

    fun measurePingAsync(dcId: Int = 2) {
        scope.launch {
            val dcIp = TgConstants.DC_DEFAULT_IPS[dcId] ?: "149.154.167.51"
            val start = System.currentTimeMillis()
            val ping = try {
                Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(dcIp, 443), 2000)
                    System.currentTimeMillis() - start
                }
            } catch (_: Exception) {
                -1L
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
    }

    fun resetWsPool() {
        try {
            wsPool?.clear()
        } catch (_: Exception) {}
    }

    fun getTelegramProxyUrl(): String {
        val cleanSecret = config.rawSecret32
        val secretWithPrefix = when {
            config.secretHex.startsWith("ee") || config.secretHex.startsWith("dd") -> config.secretHex
            else -> "ee$cleanSecret"
        }
        return "tg://proxy?server=${config.bindHost}&port=${config.bindPort}&secret=$secretWithPrefix"
    }

    fun getTelegramSocks5Url(): String {
        // Include empty user/pass for compatibility with all Telegram versions (NO AUTH mode)
        return "tg://socks?server=${config.bindHost}&port=${config.activePort}&user=&pass="
    }
}
