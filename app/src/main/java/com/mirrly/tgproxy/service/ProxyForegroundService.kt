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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProxyForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
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
        NotificationHelper.createNotificationChannel(this)

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
                        showToastOnMainThread("⚠️ Переключено на мобильную сеть. Прокси активен (${humanBytes(totalBytes)} за сессию)")
                    } else {
                        showToastOnMainThread("⚠️ Переключено на мобильную сеть. Прокси активен")
                    }
                }

                if (app.prefsManager.isAutoReconnectEnabled()) {
                    isReconnectingNetwork = true
                    serviceScope.launch {
                        try {
                            delay(500)
                            if (app.proxyServer.isRunning) {
                                app.proxyServer.stop()
                                delay(300)
                                app.proxyServer.start(cacheDir)
                                app.proxyServer.onNetworkRestored()
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
                        delay(300)
                        server.start(cacheDir)
                    } catch (_: Exception) {
                    } finally {
                        delay(1500)
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
                showToastOnMainThread("⏳ Таймер продлен на +$extraMin мин")
                updateNotificationImmediately()
                return START_REDELIVER_INTENT
            }
            ACTION_CANCEL_TIMER -> {
                SleepTimerManager.cancelTimer(this)
                showToastOnMainThread("⏳ Таймер автоотключения отменен")
                updateNotificationImmediately()
                return START_REDELIVER_INTENT
            }
        }

        val notification = NotificationHelper.buildNotification(
            context = this,
            statusText = "Mirrly TG Proxy работает",
            speedText = "Порт: ${app.config.activePort} | Скорость: 0 Б/с",
            presetName = getPresetShortName(app.config.speedPreset)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }

        acquireWakeLock()

        val server = app.proxyServer
        if (!server.isRunning) {
            serviceScope.launch(Dispatchers.IO) {
                val started = server.start(cacheDir)
                if (started) {
                    SessionHistoryManager.onSessionStarted(getPresetShortName(app.config.speedPreset))
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
            var zeroSpeedStallSeconds = 0

            while (isActive && server.isRunning) {
                delay(1000)
                secondsCounter++
                if (secondsCounter >= 10) {
                    ValueTriggerManager.addActiveSeconds(this@ProxyForegroundService, secondsCounter)
                    secondsCounter = 0
                }

                val stats = server.stats
                stats.updateSpeed()

                val activeConns = stats.activeConnections.get()
                val dlSpeedBps = stats.downloadSpeedBps
                val ulSpeedBps = stats.uploadSpeedBps

                SessionHistoryManager.onSessionUpdate(
                    bytesReceived = stats.totalBytesReceived.get(),
                    bytesSent = stats.totalBytesSent.get(),
                    peakSpeedBps = maxOf(stats.peakDownloadSpeedBps, stats.peakUploadSpeedBps),
                    activeConnections = activeConns
                )

                if (activeConns > 0 && dlSpeedBps == 0L && ulSpeedBps == 0L) {
                    zeroSpeedStallSeconds++
                } else {
                    zeroSpeedStallSeconds = 0
                }

                updateNotificationInternal(zeroSpeedStallSeconds >= 15)
            }
        }
    }

    private fun updateNotificationImmediately() {
        serviceScope.launch {
            updateNotificationInternal(false)
        }
    }

    private fun updateNotificationInternal(isStalled: Boolean) {
        val app = MirrlyApplication.instance
        val server = app.proxyServer
        val stats = server.stats

        val activeConns = stats.activeConnections.get()
        val dlSpeed = humanBytes(stats.downloadSpeedBps)
        val ulSpeed = humanBytes(stats.uploadSpeedBps)
        val pingMs = server.currentPingMs

        val netName = when (networkObserver?.getCurrentNetworkTypeName()) {
            "Wi-Fi" -> "Wi-Fi"
            "Cellular" -> "Мобильная сеть"
            else -> "Сеть активна"
        }

        val tgStatus = when {
            pingMs > 0 -> "Telegram: Доступен (${pingMs}мс)"
            pingMs == -1L -> "Telegram: Недоступен"
            else -> "Telegram: Проверка..."
        }

        val title = when {
            isReconnectingNetwork -> "Переподключение к сети..."
            isStalled -> "Затор сети | Возможна задержка"
            else -> "Обход Telegram активен"
        }

        val timerState = SleepTimerManager.timerState.value
        val timerSuffix = if (timerState.isActive) " | ⏳ ${timerState.formatRemainingTime()}" else ""

        val text = if (isStalled) {
            "$tgStatus | ↓ 0.0 B/s | $netName$timerSuffix (Нажмите 'Перезапустить')"
        } else {
            "$tgStatus | ↓ $dlSpeed/с  ↑ $ulSpeed/с | $netName$timerSuffix"
        }

        val updatedNotification = NotificationHelper.buildNotification(
            context = this@ProxyForegroundService,
            statusText = title,
            speedText = text,
            presetName = getPresetShortName(app.config.speedPreset),
            isStalled = isStalled,
            isReconnecting = isReconnectingNetwork
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NotificationHelper.NOTIFICATION_ID, updatedNotification)
    }

    @Volatile
    private var isStopping = false

    private fun stopProxyService() {
        if (isStopping) return
        isStopping = true

        SleepTimerManager.cancelTimer(this)
        networkObserver?.stop()
        networkObserver = null
        wakeLockJob?.cancel()
        wakeLockJob = null
        releaseWakeLock()

        CoroutineScope(Dispatchers.IO).launch {
            val server = MirrlyApplication.instance.proxyServer
            val appContext = applicationContext

            if (server.isRunning) {
                val stats = server.stats
                val totalBytes = stats.totalBytesReceived.get() + stats.totalBytesSent.get()
                val durationSec = server.uptimeSeconds
                val peakSpeedBps = maxOf(stats.peakDownloadSpeedBps, stats.peakUploadSpeedBps)
                val activeConns = stats.activeConnections.get()

                SessionHistoryManager.onSessionEnded(
                    bytesReceived = stats.totalBytesReceived.get(),
                    bytesSent = stats.totalBytesSent.get(),
                    peakSpeedBps = peakSpeedBps,
                    maxConnections = activeConns
                )

                val transferredStr = humanBytes(totalBytes)
                val durationStr = formatDuration(durationSec)
                val peakSpeedStr = "${humanBytes(peakSpeedBps)}/с"

                NotificationHelper.showSessionSummaryNotification(
                    appContext,
                    transferredStr,
                    durationStr,
                    peakSpeedStr
                )

                // Auto-dismiss summary notification after 5 seconds
                CoroutineScope(Dispatchers.IO).launch {
                    delay(5000)
                    NotificationHelper.cancelSummaryNotification(appContext)
                }
            }

            server.stop()
            updateJob?.cancel()

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                ProxyTileService.requestSync(this@ProxyForegroundService)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                try { serviceScope.cancel() } catch (_: Exception) {}
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
