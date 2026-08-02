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
    private var wsPool: WsPool? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    val stats = ProxyStats()

    @Volatile
    var isRunning: Boolean = false
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

        stats.resetBaseline()
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
                priority = useCf,
                userDomain = workerDomain
            )

            val secret = config.rawSecret32
            val code = NativeProxy.startProxy(
                host = config.bindHost,
                port = config.bindPort,
                dcIps = "",
                secret = secret,
                verbose = if (config.verboseLogs) 1 else 0
            )

            if (code == 0) {
                nativeStarted = true
                isRunning = true
                if (startTimeMs == 0L) {
                    startTimeMs = System.currentTimeMillis()
                }
                AppLogger.i("LocalProxyServer", "Нативный прокси успешно запущен на ${config.bindHost}:${config.bindPort} (Cloudflare: $useCf)")
            } else {
                AppLogger.w("LocalProxyServer", "Код ответа нативной библиотеки: $code, переключение на Kotlin-движок...")
            }
        } catch (t: Throwable) {
            AppLogger.w("LocalProxyServer", "Нативный прокси недоступен (${t.message}), переключение на Kotlin TgWsBridge...")
        }

        if (!nativeStarted) {
            val ok = startKotlinEngine()
            if (!ok) return false
        }

        speedJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    val nativeStats = NativeProxy.getStats()
                    if (!nativeStats.isNullOrEmpty()) {
                        stats.parseNativeStats(nativeStats)
                    }
                } catch (_: Exception) {}
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
        return try {
            val bindAddr = InetAddress.getByName(config.bindHost)
            val socket = ServerSocket(config.bindPort, 128, bindAddr)
            socket.reuseAddress = true
            serverSocket = socket
            wsPool = WsPool(config.poolSize)

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
            true
        } catch (e: Exception) {
            AppLogger.e("LocalProxyServer", "Не удалось запустить Kotlin-движок прокси: ${e.message}")
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        startTimeMs = 0L
        speedJob?.cancel()
        speedJob = null
        kotlinEngineJob?.cancel()
        kotlinEngineJob = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            wsPool?.clear()
        } catch (_: Exception) {}
        wsPool = null

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
     * Applies a new pool size immediately via NativeProxy without requiring a full restart.
     * Also updates config.poolSize so the value is consistent everywhere.
     */
    fun applyPoolSize(newSize: Int, cacheDir: File? = null) {
        val clamped = newSize.coerceIn(2, 16)
        config.poolSize = clamped
        AppLogger.i("LocalProxyServer", "Изменение пула сокетов → $clamped")
        try {
            NativeProxy.setPoolSize(clamped)
            AppLogger.i("LocalProxyServer", "Пул сокетов обновлён динамически: $clamped")
        } catch (t: Throwable) {
            AppLogger.w("LocalProxyServer", "setPoolSize() не удался (${t.message}), перезапуск прокси...")
            if (isRunning) restart(cacheDir)
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
            if (ping >= 0) {
                currentPingMs = ping
            }
        }
    }

    fun getTelegramProxyUrl(): String {
        val nativeSecret = try { NativeProxy.getSecretWithPrefix() } catch (_: Throwable) { null }
        val secretWithPrefix = if (!nativeSecret.isNullOrEmpty()) {
            nativeSecret
        } else {
            val cleanSecret = config.rawSecret32
            "dd$cleanSecret"
        }
        return "tg://proxy?server=${config.bindHost}&port=${config.bindPort}&secret=$secretWithPrefix"
    }
}
