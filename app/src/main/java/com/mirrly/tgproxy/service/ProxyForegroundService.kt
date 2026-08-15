package com.mirrly.tgproxy.service

import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.SpeedPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProxyForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var updateJob: Job? = null
    private var wakeLockJob: Job? = null
    private var networkObserver: NetworkChangeObserver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var isReconnectingNetwork = false

    companion object {
        const val ACTION_START = "com.mirrly.tgproxy.START"
        const val ACTION_STOP = "com.mirrly.tgproxy.STOP"
        const val ACTION_RESTART = "com.mirrly.tgproxy.RESTART"
        const val ACTION_CYCLE_PRESET = "com.mirrly.tgproxy.CYCLE_PRESET"
        const val ACTION_COPY_LINK = "com.mirrly.tgproxy.COPY_LINK"
        const val ACTION_EXTEND_TIMER = "com.mirrly.tgproxy.EXTEND_TIMER"
        const val ACTION_CANCEL_TIMER = "com.mirrly.tgproxy.CANCEL_TIMER"

        private const val WAKELOCK_TIMEOUT_MS = 30L * 60 * 1000
        private const val WAKELOCK_REFRESH_MS = 25L * 60 * 1000
        private const val TAG = "ProxyForegroundService"
    }

    override fun onCreate() {
        super.onCreate()
        isStopping = false
        NotificationHelper.createNotificationChannel(this)
        NotificationHelper.cancelProxyNotifications(this)

        networkObserver = NetworkChangeObserver(this) { newType, oldType ->
            val app = MirrlyApplication.instance

            if (newType == "DISCONNECTED") {
                AppLogger.i(TAG, "Связь с сетью потеряна (DISCONNECTED). Сброс сокетов...")
                app.proxyServer.resetWsPool()
                return@NetworkChangeObserver
            }

            if (app.proxyServer.isRunning) {
                // 1. Мгновенный сброс протухших сокетов и упреждающий прогрев WSS
                app.proxyServer.onNetworkRestored()

                if (oldType == "Wi-Fi" && (newType.contains("Mobile") || newType.contains("Cellular"))) {
                    val stats = app.proxyServer.stats
                    val totalBytes = stats.totalBytesReceived.get() + stats.totalBytesSent.get()
                    if (totalBytes > 100_000L) {
                        showToastOnMainThread("Переключено на мобильную сеть. Прокси активен (${humanBytes(totalBytes)} за сессию)")
                    } else {
                        showToastOnMainThread("Переключено на мобильную сеть. Прокси активен")
                    }
                }

                if (app.prefsManager.isAutoReconnectEnabled()) {
                    isReconnectingNetwork = true
                    serviceScope.launch {
                        try {
                            delay(500)
                            if (app.proxyServer.isRunning) {
                                app.proxyServer.stop()
                                delay(350)
                                val started = app.proxyServer.start(cacheDir)
                                if (started) {
                                    app.proxyServer.onNetworkRestored()
                                    withContext(Dispatchers.Main) {
                                        startNotificationUpdates()
                                        startWakeLockRefresh()
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            delay(1000)
                            isReconnectingNetwork = false
                        }
                    }
                }
            }
        }
        networkObserver?.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = MirrlyApplication.instance

        when (intent?.action) {
            ACTION_STOP -> {
                stopProxyService()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                isReconnectingNetwork = true
                serviceScope.launch {
                    try {
                        val server = app.proxyServer
                        server.stop()
                        delay(350)
                        val started = server.start(cacheDir)
                        if (started) {
                            withContext(Dispatchers.Main) {
                                ProxyTileService.requestSync(this@ProxyForegroundService)
                                startNotificationUpdates()
                                startWakeLockRefresh()
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Ошибка при перезапуске прокси: ${e.message}")
                    } finally {
                        delay(1000)
                        isReconnectingNetwork = false
                    }
                }
                return START_REDELIVER_INTENT
            }
            ACTION_CYCLE_PRESET -> {
                cycleSpeedPreset()
                return START_REDELIVER_INTENT
            }
            ACTION_COPY_LINK -> {
                copyProxyLinkToClipboard()
                return START_REDELIVER_INTENT
            }
            ACTION_EXTEND_TIMER -> {
                val extraMin = intent.getIntExtra("extra_minutes", 15)
                SleepTimerManager.extendTimer(this, extraMin)
                showToastOnMainThread("Таймер продлен на +$extraMin мин")
                updateNotificationImmediately()
                return START_REDELIVER_INTENT
            }
            ACTION_CANCEL_TIMER -> {
                SleepTimerManager.cancelTimer(this)
                showToastOnMainThread("Таймер автоотключения отменен")
                updateNotificationImmediately()
                return START_REDELIVER_INTENT
            }
        }

        val notification = NotificationHelper.buildNotification(
            context = this,
            statusText = if (app.config.isSocks5Mode) "SOCKS5 прокси активен" else "Обход Telegram активен",
            speedText = "Порт: ${app.config.activePort} | Инициализация...",
            statusIndicator = ProxyStatusIndicator.GREEN
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            AppLogger.e("ProxyForegroundService", "Не удалось запустить ForegroundService: ${e.message}")
        }


        acquireWakeLock()

        val server = app.proxyServer
        if (!server.isRunning) {
            serviceScope.launch(Dispatchers.IO) {
                val started = server.start(cacheDir)
                if (started) {
                    SessionHistoryManager.onSessionStarted(
                        presetName = getPresetShortName(app.config.speedPreset),
                        proxyMode = app.config.proxyMode.name
                    )
                    DonationManager.recordSuccessfulConnection(this@ProxyForegroundService)
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    ProxyTileService.requestSync(this@ProxyForegroundService)
                    startNotificationUpdates()
                    startWakeLockRefresh()
                }
            }
        } else {
            ProxyTileService.requestSync(this)
            startNotificationUpdates()
            startWakeLockRefresh()
        }
        return START_REDELIVER_INTENT
    }

    private fun cycleSpeedPreset() {
        val app = MirrlyApplication.instance
        val currentPreset = app.config.speedPreset
        val nextPreset = when (currentPreset) {
            SpeedPreset.BALANCED -> SpeedPreset.TURBO
            SpeedPreset.TURBO -> SpeedPreset.ECO
            SpeedPreset.ECO -> SpeedPreset.BALANCED
        }

        app.config.applyPreset(nextPreset)
        app.proxyServer.applyPoolSize(nextPreset.defaultPoolSize)
        app.prefsManager.saveConfig(app.config)

        showToastOnMainThread("Режим скорости: ${getPresetShortName(nextPreset)}")
        updateNotificationImmediately()
    }

    private fun copyProxyLinkToClipboard() {
        val app = MirrlyApplication.instance
        val config = app.config
        val server = app.proxyServer

        val tgUrl = if (config.isSocks5Mode) {
            server.getTelegramSocks5Url()
        } else {
            "tg://proxy?server=${config.bindHost}&port=${config.bindPort}&secret=${config.secretHex}"
        }
        val label = if (config.isSocks5Mode) "tg://socks" else "tg://proxy"

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Telegram Proxy Link", tgUrl)
            clipboard.setPrimaryClip(clip)
            showToastOnMainThread("Ссылка $label скопирована!")
        } catch (e: Exception) {
            showToastOnMainThread("Ошибка копирования ссылки")
        }
    }

    private fun showToastOnMainThread(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getPresetShortName(preset: SpeedPreset): String {
        return when (preset) {
            SpeedPreset.TURBO -> "Турбо"
            SpeedPreset.BALANCED -> "Баланс"
            SpeedPreset.ECO -> "Эко"
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MirrlyProxy::ServiceWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(WAKELOCK_TIMEOUT_MS)
                }
                AppLogger.i(TAG, "WakeLock acquired for 30 minutes")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun startWakeLockRefresh() {
        wakeLockJob?.cancel()
        wakeLockJob = serviceScope.launch {
            while (isActive) {
                delay(WAKELOCK_REFRESH_MS)
                try {
                    releaseWakeLock()
                    acquireWakeLock()
                    AppLogger.i(TAG, "WakeLock refreshed")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to refresh WakeLock: ${e.message}")
                }
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to release WakeLock: ${e.message}")
        }
        wakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AppLogger.w(TAG, "onTaskRemoved: proxy service active, task removed from recents")
    }

    private fun startNotificationUpdates() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            val server = MirrlyApplication.instance.proxyServer
            var secondsCounter = 0
            var pingCounter = 0

            server.measurePingAsync()

            while (isActive && server.isRunning) {
                delay(1000)
                secondsCounter++
                pingCounter++

                if (secondsCounter >= 10) {
                    ValueTriggerManager.addActiveSeconds(this@ProxyForegroundService, secondsCounter)
                    secondsCounter = 0
                }

                if (pingCounter >= 8) {
                    server.measurePingAsync()
                    pingCounter = 0
                }

                val stats = server.stats
                stats.updateSpeed()

                val activeConns = stats.activeConnections.get()

                SessionHistoryManager.onSessionUpdate(
                    bytesReceived = stats.totalBytesReceived.get(),
                    bytesSent = stats.totalBytesSent.get(),
                    peakSpeedBps = maxOf(stats.peakDownloadSpeedBps, stats.peakUploadSpeedBps),
                    activeConnections = activeConns
                )

                updateNotificationInternal()
            }
        }
    }

    private fun updateNotificationImmediately() {
        serviceScope.launch {
            updateNotificationInternal()
        }
    }

    private fun updateNotificationInternal() {
        val app = MirrlyApplication.instance
        val server = app.proxyServer
        if (isStopping || !server.isRunning) return
        val stats = server.stats

        val activeConns = stats.activeConnections.get()
        val dlSpeed = humanBytes(stats.downloadSpeedBps)
        val ulSpeed = humanBytes(stats.uploadSpeedBps)
        val pingMs = server.currentPingMs

        val netTypeName = networkObserver?.getCurrentNetworkTypeName() ?: "UNKNOWN"
        val isNetworkLost = netTypeName == "DISCONNECTED"

        val netName = when (netTypeName) {
            "Wi-Fi" -> "Wi-Fi"
            "Mobile LTE/5G", "Cellular" -> "Мобильная сеть"
            "Ethernet" -> "Ethernet"
            "DISCONNECTED" -> "Нет сети"
            else -> "Сеть активна"
        }

        val timerState = SleepTimerManager.timerState.value
        val timerSuffix = if (timerState.isActive) " | Таймер: ${timerState.formatRemainingTime()}" else ""

        val isCf = app.config.cfProxyEnabled && app.config.getEffectiveCfDomain().isNotBlank()
        val protoLabel = if (app.config.isSocks5Mode) "SOCKS5" else "MTProto"

        val (statusIndicator, title, text) = when {
            !server.isRunning || isNetworkLost -> {
                Triple(
                    ProxyStatusIndicator.RED,
                    "Mirrly TG Proxy [$protoLabel] • Нет сети",
                    "Сеть: Отключена | ↓ 0 Б/с  ↑ 0 Б/с | Нет сети"
                )
            }
            isReconnectingNetwork -> {
                Triple(
                    ProxyStatusIndicator.YELLOW,
                    "Mirrly TG Proxy [$protoLabel] • Переподключение...",
                    "Восстановление связи... | $netName"
                )
            }
            isCf -> {
                if (pingMs > 0) {
                    val indicator = if (pingMs > 1200L) ProxyStatusIndicator.YELLOW else ProxyStatusIndicator.GREEN
                    Triple(
                        indicator,
                        "Mirrly TG Proxy [$protoLabel] • Активен",
                        "Cloudflare: ${pingMs}мс | ↓ $dlSpeed/с  ↑ $ulSpeed/с | $netName$timerSuffix"
                    )
                } else if (activeConns > 0) {
                    Triple(
                        ProxyStatusIndicator.GREEN,
                        "Mirrly TG Proxy [$protoLabel] • Активен",
                        "Туннель активен | ↓ $dlSpeed/с  ↑ $ulSpeed/с | $netName$timerSuffix"
                    )
                } else {
                    Triple(
                        ProxyStatusIndicator.YELLOW,
                        "Mirrly TG Proxy [$protoLabel] • Cloudflare недоступен",
                        "Проверка шлюза... | ↓ $dlSpeed/с  ↑ $ulSpeed/с | $netName$timerSuffix"
                    )
                }
            }
            else -> {
                if (pingMs > 0) {
                    val indicator = if (pingMs > 1200L) ProxyStatusIndicator.YELLOW else ProxyStatusIndicator.GREEN
                    Triple(
                        indicator,
                        "Mirrly TG Proxy [$protoLabel] • Активен",
                        "Пинг: ${pingMs}мс | ↓ $dlSpeed/с  ↑ $ulSpeed/с | $netName$timerSuffix"
                    )
                } else {
                    Triple(
                        ProxyStatusIndicator.GREEN,
                        "Mirrly TG Proxy [$protoLabel] • Активен",
                        "↓ $dlSpeed/с  ↑ $ulSpeed/с | $netName$timerSuffix"
                    )
                }
            }
        }

        val updatedNotification = NotificationHelper.buildNotification(
            context = this@ProxyForegroundService,
            statusText = title,
            speedText = text,
            statusIndicator = statusIndicator
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.notify(NotificationHelper.NOTIFICATION_ID, updatedNotification)
        } catch (_: Exception) {}
    }


    @Volatile
    private var isStopping = false

    private fun stopProxyService() {
        if (isStopping) return
        isStopping = true

        updateJob?.cancel()
        updateJob = null

        SleepTimerManager.cancelTimer(this)
        networkObserver?.stop()
        networkObserver = null
        wakeLockJob?.cancel()
        wakeLockJob = null
        releaseWakeLock()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        NotificationHelper.cancelProxyNotifications(this)

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val server = MirrlyApplication.instance.proxyServer

                if (server.isRunning) {
                    val stats = server.stats
                    val peakSpeedBps = maxOf(stats.peakDownloadSpeedBps, stats.peakUploadSpeedBps)
                    val activeConns = stats.activeConnections.get()

                    SessionHistoryManager.onSessionEnded(
                        bytesReceived = stats.totalBytesReceived.get(),
                        bytesSent = stats.totalBytesSent.get(),
                        peakSpeedBps = peakSpeedBps,
                        maxConnections = activeConns
                    )
                }

                server.stop()

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    ProxyTileService.requestSync(this@ProxyForegroundService)
                    NotificationHelper.cancelProxyNotifications(this@ProxyForegroundService)
                    stopSelf()
                    try { serviceScope.cancel() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка при остановке службы: ${e.message}")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    NotificationHelper.cancelProxyNotifications(this@ProxyForegroundService)
                    stopSelf()
                }
            }
        }
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds < 60) return "$seconds сек"
        val minutes = seconds / 60
        val remainingSec = seconds % 60
        if (minutes < 60) {
            return if (remainingSec > 0) "${minutes}мин ${remainingSec}сек" else "${minutes}мин"
        }
        val hours = minutes / 60
        val remainingMin = minutes % 60
        return if (remainingMin > 0) "${hours}ч ${remainingMin}мин" else "${hours}ч"
    }

    override fun onDestroy() {
        stopProxyService()
        NotificationHelper.cancelProxyNotifications(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

fun humanBytes(bytes: Long): String {
    var n = bytes.toDouble()
    for (unit in arrayOf("B", "KB", "MB", "GB")) {
        if (Math.abs(n) < 1024) {
            return String.format("%.1f %s", n, unit)
        }
        n /= 1024.0
    }
    return String.format("%.1f TB", n)
}
