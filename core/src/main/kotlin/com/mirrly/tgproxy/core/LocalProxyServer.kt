package com.mirrly.tgproxy.core

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LocalProxyServer(val config: ProxyConfig = ProxyConfig()) {
    private var speedJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    val stats = ProxyStats()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var startTimeMs: Long = 0L
        private set

    val uptimeSeconds: Long
        get() = if (isRunning && startTimeMs > 0L) (System.currentTimeMillis() - startTimeMs) / 1000L else 0L

    @Synchronized
    fun start(cacheDir: File? = null): Boolean {
        if (isRunning) return true

        AppLogger.i("LocalProxyServer", "Настройка нативного движка прокси...")
        try {
            if (cacheDir != null) {
                try {
                    NativeProxy.setCfProxyCacheDir(cacheDir.absolutePath)
                } catch (e: Exception) {
                    AppLogger.e("LocalProxyServer", "Не удалось установить кэш-директорию Cloudflare: ${e.message}")
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
                isRunning = true
                if (startTimeMs == 0L) {
                    startTimeMs = System.currentTimeMillis()
                }
                AppLogger.i("LocalProxyServer", "Нативный прокси успешно запущен на ${config.bindHost}:${config.bindPort} (Cloudflare: $useCf)")

                speedJob = scope.launch {
                    while (isActive && isRunning) {
                        try {
                            val nativeStats = NativeProxy.getStats()
                            if (!nativeStats.isNullOrEmpty()) {
                                stats.parseNativeStats(nativeStats)
                            }
                        } catch (_: Exception) {}
                        stats.updateSpeed()
                        delay(1000)
                    }
                }
                return true
            } else {
                AppLogger.e("LocalProxyServer", "Ошибка запуска нативного прокси, код ошибки: $code")
                return false
            }
        } catch (e: Exception) {
            AppLogger.e("LocalProxyServer", "Ошибка вызова нативной библиотеки NativeProxy: ${e.message}")
            return false
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        startTimeMs = 0L
        try {
            NativeProxy.stopProxy()
            AppLogger.i("LocalProxyServer", "Нативный движок прокси успешно остановлен")
        } catch (e: Exception) {
            AppLogger.e("LocalProxyServer", "Ошибка остановки нативного движка прокси: ${e.message}")
        }
        speedJob?.cancel()
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

    fun getTelegramProxyUrl(): String {
        val nativeSecret = try { NativeProxy.getSecretWithPrefix() } catch (_: Exception) { null }
        val secretWithPrefix = if (!nativeSecret.isNullOrEmpty()) {
            nativeSecret
        } else {
            val cleanSecret = config.rawSecret32
            "dd$cleanSecret"
        }
        return "tg://proxy?server=${config.bindHost}&port=${config.bindPort}&secret=$secretWithPrefix"
    }
}
