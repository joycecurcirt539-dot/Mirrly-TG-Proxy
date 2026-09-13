package com.mirrly.tgproxy.service

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.ConnectionQuality
import com.mirrly.tgproxy.core.SpeedPreset
import kotlinx.coroutines.CancellationException
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
    private var thermalListener: Any? = null
    private var batteryReceiver: BroadcastReceiver? = null

    private var batteryGuardCountdownJob: Job? = null
    @Volatile
    private var isBatteryGuardCountdownActive = false
    @Volatile
    private var batteryGuardDismissedForThreshold = false

    @Volatile
    private var isReconnectingNetwork = false

    @Volatile
    private var isScreenOn = true

    // Защита от параллельного запуска авторегистрации WARP при повторных onStartCommand
    // (возникает из-за START_REDELIVER_INTENT при убийстве сервиса во время регистрации)
    @Volatile
    private var warpRegistrationInProgress = false

    companion object {
        const val ACTION_START = "com.mirrly.tgproxy.START"
        const val ACTION_STOP = "com.mirrly.tgproxy.STOP"
        const val ACTION_RESTART = "com.mirrly.tgproxy.RESTART"
        const val ACTION_CYCLE_PRESET = "com.mirrly.tgproxy.CYCLE_PRESET"
        const val ACTION_COPY_LINK = "com.mirrly.tgproxy.COPY_LINK"
        const val ACTION_EXTEND_TIMER = "com.mirrly.tgproxy.EXTEND_TIMER"
        const val ACTION_CANCEL_TIMER = "com.mirrly.tgproxy.CANCEL_TIMER"
        const val ACTION_CANCEL_BATTERY_GUARD = "com.mirrly.tgproxy.CANCEL_BATTERY_GUARD"

        private const val WAKELOCK_TIMEOUT_MS = 30L * 60 * 1000
        private const val WAKELOCK_REFRESH_MS = 25L * 60 * 1000
        private const val TAG = "ProxyForegroundService"

        fun restartIfRunning(context: Context) {
            val app = MirrlyApplication.instance
            app.saveConfig()
            if (app.proxyServer.isRunning) {
                val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                    action = ACTION_RESTART
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Не удалось перезапустить службу прокси: ${e.message}")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isStopping = false
        NotificationHelper.createNotificationChannel(this)
        NotificationHelper.cancelProxyNotifications(this)
        PredictivePreWarmManager.start(this)
        initBatteryAndThermalMonitoring()

        networkObserver = NetworkChangeObserver(this) { newType, oldType ->
            val app = MirrlyApplication.instance

            if (newType == "DISCONNECTED") {
                AppLogger.i(TAG, "Связь с сетью потеряна (DISCONNECTED). Перевод в спящий режим ожидания сети...")
                app.proxyServer.setNetworkDormancy(true)
                WorkerFailoverManager.stopRecoveryWatchdog()
                return@NetworkChangeObserver
            }

            if (app.proxyServer.isRunning) {
                val isMobile = newType.contains("Mobile", ignoreCase = true) || newType.contains("Cellular", ignoreCase = true)
                app.proxyServer.setNetworkInterface(isMobile, isScreenOn = isScreenOn)

                // Выход из спящего режима, сброс DoH и мгновенный прогрев сокетов
                app.proxyServer.setNetworkDormancy(false)

                if (app.config.tcpNoDelayMode == com.mirrly.tgproxy.core.TcpNoDelayMode.AUTO) {
                    val eval = NetworkConditionEvaluator.evaluate(
                        context = this@ProxyForegroundService,
                        capabilities = networkObserver?.getCurrentCapabilities(),
                        currentPingMs = app.proxyServer.currentPingMs,
                        currentThroughputBps = 0L
                    )
                    app.proxyServer.applyTcpNoDelay(eval.isInstantSendRecommended)
                }

                if (oldType == "Wi-Fi" && (newType.contains("Mobile") || newType.contains("Cellular"))) {
                    val stats = app.proxyServer.stats
                    val totalBytes = stats.totalBytesReceived.get() + stats.totalBytesSent.get()
                    if (totalBytes > 100_000L) {
                        showToastOnMainThread("Переключено на мобильную сеть. Прокси активен (${humanBytes(totalBytes)} за сессию)")
                    } else {
                        showToastOnMainThread("Переключено на мобильную сеть. Прокси активен")
                    }
                }

                if (app.config.isMasqueUplink || app.config.isHybridUplink) {
                    serviceScope.launch(Dispatchers.IO) {
                        checkAndTuneWarpEndpoint()
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
            ACTION_CANCEL_BATTERY_GUARD -> {
                cancelBatteryGuardCountdown(userDismissed = true)
                showToastOnMainThread("Автоотключение отменено. Прокси продолжает работу")
                return START_REDELIVER_INTENT
            }
        }

        batteryGuardDismissedForThreshold = false

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
                if (app.config.isSocks5Mode && !app.config.hasSocks5Auth) {
                    val (u, p) = com.mirrly.tgproxy.core.ProxyConfig.generateRandomSocks5Credentials()
                    app.config.socks5Username = u
                    app.config.socks5Password = p
                    app.prefsManager.saveConfig(app.config)
                    com.mirrly.tgproxy.core.NativeProxy.setSocks5Auth(u, p)
                }
                val needsWarp = app.config.isMasqueUplink || app.config.isHybridUplink || app.config.isAwgUplink || app.config.isWarpCascadeUplink
                val hasInvalidWarpCredentials = app.config.warpToken.isBlank() ||
                    app.config.warpToken == "mirrly-bootstrap-token" ||
                    app.config.warpPrivateKey == com.mirrly.tgproxy.core.WarpAccountManager.BOOTSTRAP_PROFILE.privateKeyBase64
                if (needsWarp && hasInvalidWarpCredentials && !warpRegistrationInProgress) {
                    warpRegistrationInProgress = true
                    AppLogger.i(TAG, "WARP активен, профиль не обнаружен или содержит заглушку. Регистрация живого аккаунта...")
                    try {
                        val regResult = com.mirrly.tgproxy.core.WarpAccountManager.registerAndActivate(
                            fallbackToBootstrap = false
                        )
                        regResult.onSuccess { profile ->
                            app.prefsManager.saveWarpProfile(profile)
                            app.config.applyWarpProfile(profile)
                            app.prefsManager.saveConfig(app.config)
                            AppLogger.i(TAG, "WARP профиль успешно создан: ${profile.getSummary()}")
                        }.onFailure { err ->
                            AppLogger.w(TAG, "Авторегистрация WARP не удалась: ${err.message}")
                        }
                    } finally {
                        warpRegistrationInProgress = false
                    }
                }
                val started = server.start(cacheDir)
                if (started) {
                    SessionHistoryManager.onSessionStarted(
                        presetName = getPresetShortName(app.config.speedPreset),
                        proxyMode = app.config.proxyMode.name
                    )
                    WorkerRequestTracker.onSessionStarted()
                    DonationManager.recordSuccessfulConnection(this@ProxyForegroundService)

                    if (app.config.isMasqueUplink || app.config.isHybridUplink) {
                        serviceScope.launch(Dispatchers.IO) {
                            checkAndTuneWarpEndpoint()
                        }
                    } else if (app.config.isVlessUplink) {
                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                com.mirrly.tgproxy.core.VlessPresetsRepository.fetchFreshPublicPresets(socks5Port = app.config.socks5Port)
                            } catch (_: Exception) {}
                        }
                    }

                    if (app.prefsManager.isAutoStopOnStartEnabled() && !SleepTimerManager.timerState.value.isActive) {
                        val autoStopMin = app.prefsManager.getAutoStopMinutes()
                        AppLogger.i(TAG, "Автоотключение при запуске активно: запуск таймера на $autoStopMin мин")
                        SleepTimerManager.startTimer(this@ProxyForegroundService, autoStopMin)
                    }
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    ProxyTileService.requestSync(this@ProxyForegroundService)
                    startNotificationUpdates()
                    startWakeLockRefresh()
                    WorkerFailoverManager.startRecoveryWatchdogIfNeeded()
                }
            }
        } else {
            ProxyTileService.requestSync(this)
            startNotificationUpdates()
            startWakeLockRefresh()
            WorkerFailoverManager.startRecoveryWatchdogIfNeeded()
        }
        return START_REDELIVER_INTENT
    }

    private fun cycleSpeedPreset() {
        val app = MirrlyApplication.instance
        val currentPreset = app.config.speedPreset
        val nextPreset = when (currentPreset) {
            SpeedPreset.BALANCED -> SpeedPreset.TURBO
            SpeedPreset.TURBO -> SpeedPreset.ULTRA
            SpeedPreset.ULTRA -> SpeedPreset.AUTO
            SpeedPreset.AUTO -> SpeedPreset.ECO
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
            server.getTelegramProxyUrl()
        }
        val label = if (config.isSocks5Mode) "tg://socks" else "tg://proxy"

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Telegram Proxy Link", tgUrl)
            clipboard.setPrimaryClip(clip)
            showToastOnMainThread("Ссылка $label скопирована!")
        } catch (_: Exception) {
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
            SpeedPreset.ULTRA -> "Ультра"
            SpeedPreset.TURBO -> "Турбо"
            SpeedPreset.BALANCED -> "Баланс"
            SpeedPreset.ECO -> "Эко"
            SpeedPreset.AUTO -> "Авто"
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
        if (!isScreenOn) return
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            val app = MirrlyApplication.instance
            val server = app.proxyServer
            var secondsCounter = 0
            var pingCounter = 0

            server.measurePingAsync()

            while (isActive && server.isRunning && isScreenOn) {
                delay(2000)
                secondsCounter += 2
                pingCounter += 2

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

                if (app.config.tcpNoDelayMode == com.mirrly.tgproxy.core.TcpNoDelayMode.AUTO) {
                    val eval = NetworkConditionEvaluator.evaluate(
                        context = this@ProxyForegroundService,
                        capabilities = networkObserver?.getCurrentCapabilities(),
                        currentPingMs = server.currentPingMs,
                        currentThroughputBps = stats.downloadSpeedBps + stats.uploadSpeedBps
                    )
                    server.applyTcpNoDelay(eval.isInstantSendRecommended)
                }

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

    private fun stopNotificationUpdates() {
        updateJob?.cancel()
        updateJob = null
    }

    private fun updateNotificationImmediately() {
        serviceScope.launch {
            updateNotificationInternal(force = true)
        }
    }

    @Volatile
    private var lastNotifiedIndicator: ProxyStatusIndicator? = null
    @Volatile
    private var lastNotifiedTitle: String? = null
    @Volatile
    private var lastNotifiedText: String? = null
    @Volatile
    private var lastNotifiedTimestamp: Long = 0L
    @Volatile
    private var lastNotifiedCascadeStageCode: Int = 0

    private fun updateNotificationInternal(force: Boolean = false) {
        val app = MirrlyApplication.instance
        val server = app.proxyServer
        if (isStopping || !server.isRunning) return
        val stats = server.stats

        // Мягкие уведомления при каскадном фоллбэке (Failover)
        val stageCode = stats.activeCascadeStageCode
        if (app.config.isSocks5Mode && stageCode > 0 && stageCode != lastNotifiedCascadeStageCode) {
            val previousStage = lastNotifiedCascadeStageCode
            lastNotifiedCascadeStageCode = stageCode

            if (app.config.isMasqueUplink) {
                if (stageCode == 2) {
                    NotificationHelper.showFailoverNotification(
                        context = this@ProxyForegroundService,
                        title = "Mirrly TG Proxy",
                        message = "WARP MASQUE недоступен у вашего оператора, активирован AmneziaWG"
                    )
                }
            } else if (app.config.isAwgUplink) {
                if (stageCode == 1) {
                    NotificationHelper.showFailoverNotification(
                        context = this@ProxyForegroundService,
                        title = "Mirrly TG Proxy",
                        message = "WARP AmneziaWG недоступен у вашего оператора, активирован MASQUE"
                    )
                }
            }
        }

        val activeConns = stats.activeConnections.get()
        val dlSpeed = humanBytes(stats.downloadSpeedBps)
        val ulSpeed = humanBytes(stats.uploadSpeedBps)
        val pingMs = server.currentPingMs
        val jitterMs = server.jitterMs
        val quality = server.connectionQuality
        val pingDisplay = if (pingMs > 0) {
            if (jitterMs > 0) "${pingMs}мс (±${jitterMs}мс)" else "${pingMs}мс"
        } else {
            ""
        }

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

        val effectiveDomain = if (app.config.isSocks5Mode) app.config.getEffectiveCfDomain() else ""
        val isWorker = app.config.isSocks5Mode && app.config.cfProxyEnabled && effectiveDomain.isNotBlank()
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
            app.config.isSocks5Mode -> {
                val currentStage = stats.activeCascadeStageCode
                val uplinkLabel = when {
                    app.config.isMasqueUplink && currentStage == 2 -> "AmneziaWG (Фоллбэк)"
                    app.config.isAwgUplink && currentStage == 1 -> "MASQUE (Фоллбэк)"
                    currentStage == 1 -> "WARP MASQUE"
                    currentStage == 2 -> "WARP AmneziaWG"
                    app.config.isLivenessProbeEnabled && stats.activeCascadeStage.isNotBlank() -> stats.activeCascadeStage
                    app.config.isVlessUplink -> "VLESS"
                    app.config.isMasqueUplink -> "WARP MASQUE"
                    app.config.isAwgUplink -> "WARP AmneziaWG"
                    isWorker -> "Worker"
                    else -> "SOCKS5"
                }
                if (pingMs > 0) {
                    val indicator = if (quality == ConnectionQuality.POOR || pingMs > 600L) ProxyStatusIndicator.YELLOW else ProxyStatusIndicator.GREEN
                    Triple(
                        indicator,
                        "Mirrly TG Proxy [$protoLabel] • Активен",
                        "$uplinkLabel: $pingDisplay | ↓ $dlSpeed/с  ↑ $ulSpeed/с | $netName$timerSuffix"
                    )
                } else if (activeConns > 0) {
                    Triple(
                        ProxyStatusIndicator.GREEN,
                        "Mirrly TG Proxy [$protoLabel] • Активен",
                        "$uplinkLabel: Туннель активен | ↓ $dlSpeed/с  ↑ $ulSpeed/с | $netName$timerSuffix"
                    )
                } else {
                    Triple(
                        ProxyStatusIndicator.YELLOW,
                        "Mirrly TG Proxy [$protoLabel] • $uplinkLabel подключается",
                        "Проверка шлюза... | ↓ $dlSpeed/с  ↑ $ulSpeed/с | $netName$timerSuffix"
                    )
                }
            }
            else -> {
                if (pingMs > 0) {
                    val indicator = if (quality == ConnectionQuality.POOR || pingMs > 600L) ProxyStatusIndicator.YELLOW else ProxyStatusIndicator.GREEN
                    Triple(
                        indicator,
                        "Mirrly TG Proxy [$protoLabel] • Активен",
                        "CDN: $pingDisplay | ↓ $dlSpeed/с  ↑ $ulSpeed/с | $netName$timerSuffix"
                    )
                } else {
                    Triple(
                        ProxyStatusIndicator.GREEN,
                        "Mirrly TG Proxy [$protoLabel] • Активен",
                        "CDN: Туннель активен | ↓ $dlSpeed/с  ↑ $ulSpeed/с | $netName$timerSuffix"
                    )
                }
            }
        }

        val now = System.currentTimeMillis()
        val hasChanged = statusIndicator != lastNotifiedIndicator || title != lastNotifiedTitle || text != lastNotifiedText
        val timeSinceLastNotify = now - lastNotifiedTimestamp

        // Пропускаем отправку в NotificationManager, если данные не изменились и прошло менее 4 секунд
        if (!force && !hasChanged && timeSinceLastNotify < 4000L) {
            return
        }

        lastNotifiedIndicator = statusIndicator
        lastNotifiedTitle = title
        lastNotifiedText = text
        lastNotifiedTimestamp = now

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

        stopNotificationUpdates()
        lastNotifiedIndicator = null
        lastNotifiedTitle = null
        lastNotifiedText = null
        lastNotifiedTimestamp = 0L
        lastNotifiedCascadeStageCode = 0

        SleepTimerManager.cancelTimer(this)
        cancelBatteryGuardCountdown(userDismissed = false)
        batteryGuardDismissedForThreshold = false
        WorkerFailoverManager.stopRecoveryWatchdog()
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

        serviceScope.launch {
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

                withContext(Dispatchers.Main) {
                    ProxyTileService.requestSync(this@ProxyForegroundService)
                    NotificationHelper.cancelProxyNotifications(this@ProxyForegroundService)
                    stopSelf()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка при остановке службы: ${e.message}")
                withContext(Dispatchers.Main) {
                    NotificationHelper.cancelProxyNotifications(this@ProxyForegroundService)
                    stopSelf()
                }
            }
        }
    }

    private fun initBatteryAndThermalMonitoring() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager?.isInteractive ?: true
        } else {
            @Suppress("DEPRECATION")
            powerManager?.isScreenOn ?: true
        }

        // 1. Android 10+ Thermal Status Listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                AppLogger.d(TAG, "Thermal статус изменился: $status")
                updateDeviceQoSState(status)
            }
            try {
                powerManager.addThermalStatusListener(listener)
                thermalListener = listener
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Не удалось зарегистрировать OnThermalStatusChangedListener: ${t.message}")
            }
        }

        // 2. Battery, Screen & Power Save Broadcast Receiver
        val bReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        val isMobile = networkObserver?.getCurrentNetworkTypeName()?.let {
                            it.contains("Mobile", ignoreCase = true) || it.contains("Cellular", ignoreCase = true)
                        } ?: false
                        MirrlyApplication.instance.proxyServer.setNetworkInterface(isMobile, isScreenOn = false)
                        stopNotificationUpdates()
                        AppLogger.d(TAG, "Экран выключен: пауза фонового обновления уведомлений")
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn = true
                        val isMobile = networkObserver?.getCurrentNetworkTypeName()?.let {
                            it.contains("Mobile", ignoreCase = true) || it.contains("Cellular", ignoreCase = true)
                        } ?: false
                        MirrlyApplication.instance.proxyServer.setNetworkInterface(isMobile, isScreenOn = true)
                        AppLogger.d(TAG, "Экран включен: возобновление обновления уведомлений")
                        updateNotificationImmediately()
                        startNotificationUpdates()
                    }
                    else -> {
                        updateDeviceQoSState()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try {
            registerReceiver(bReceiver, filter)
            batteryReceiver = bReceiver
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Не удалось зарегистрировать battery/screen receiver: ${t.message}")
        }

        updateDeviceQoSState()
    }

    private fun updateDeviceQoSState(thermalStatusOverride: Int? = null) {
        try {
            val app = MirrlyApplication.instance
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isPowerSave = powerManager?.isPowerSaveMode == true
            val thermal = thermalStatusOverride ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
                powerManager.currentThermalStatus
            } else {
                0
            }

            val (batteryPct, isCharging) = getBatteryInfo()

            app.proxyServer.qosEngine.updateState(
                batteryPercent = batteryPct,
                isCharging = isCharging,
                isPowerSaveMode = isPowerSave,
                thermalStatus = thermal
            )

            checkBatteryGuard(
                batteryPct = batteryPct,
                isCharging = isCharging,
                isPowerSave = isPowerSave
            )
        } catch (_: Exception) {}
    }

    private fun checkBatteryGuard(batteryPct: Int, isCharging: Boolean, isPowerSave: Boolean) {
        val app = MirrlyApplication.instance
        val config = app.config

        if (!config.isBatteryGuardEnabled || !app.proxyServer.isRunning) {
            if (isBatteryGuardCountdownActive) {
                cancelBatteryGuardCountdown(userDismissed = false)
            }
            return
        }

        if (isCharging) {
            if (isBatteryGuardCountdownActive) {
                AppLogger.i(TAG, "Подключено зарядное устройство: обратный отсчет защиты аккумулятора отменен")
                cancelBatteryGuardCountdown(userDismissed = false)
            }
            batteryGuardDismissedForThreshold = false
            return
        }

        val isLowBattery = batteryPct in 1..config.batteryGuardThreshold
        val isPowerSaveTrigger = config.batteryGuardStopOnPowerSave && isPowerSave

        if (!isLowBattery && !isPowerSaveTrigger) {
            if (isBatteryGuardCountdownActive) {
                cancelBatteryGuardCountdown(userDismissed = false)
            }
            batteryGuardDismissedForThreshold = false
            return
        }

        if (batteryGuardDismissedForThreshold) {
            return
        }

        if (isBatteryGuardCountdownActive) {
            return
        }

        val reason = if (isLowBattery) {
            "Заряд батареи упал до $batteryPct% (порог: ${config.batteryGuardThreshold}%)"
        } else {
            "Активирован системный режим энергосбережения Android"
        }

        startBatteryGuardCountdown(reason = reason, initialBatteryPct = batteryPct)
    }

    private fun startBatteryGuardCountdown(reason: String, initialBatteryPct: Int) {
        if (isBatteryGuardCountdownActive) return
        isBatteryGuardCountdownActive = true

        val totalSeconds = 300 // 5 минут
        val targetTimeMs = System.currentTimeMillis() + totalSeconds * 1000L

        AppLogger.w(TAG, "Защита аккумулятора: $reason. Запуск 5-минутного таймера предупреждения перед остановкой.")

        NotificationHelper.showBatteryGuardWarningNotification(
            context = this,
            targetTimeMs = targetTimeMs,
            remainingSeconds = totalSeconds,
            reason = reason,
            batteryPct = initialBatteryPct
        )

        batteryGuardCountdownJob?.cancel()
        batteryGuardCountdownJob = serviceScope.launch(Dispatchers.Default) {
            var remaining = totalSeconds
            try {
                while (remaining > 0 && isActive) {
                    delay(1000)
                    remaining--

                    val app = MirrlyApplication.instance
                    if (!app.config.isBatteryGuardEnabled || !app.proxyServer.isRunning) {
                        withContext(Dispatchers.Main) {
                            cancelBatteryGuardCountdown(userDismissed = false)
                        }
                        return@launch
                    }

                    val (currentPct, isCharging) = getBatteryInfo()
                    if (isCharging) {
                        AppLogger.i(TAG, "Зарядное устройство подключено: отмена обратного отсчета защиты аккумулятора")
                        withContext(Dispatchers.Main) {
                            cancelBatteryGuardCountdown(userDismissed = false)
                        }
                        return@launch
                    }

                    if (remaining % 60 == 0 || remaining == 30 || remaining == 10) {
                        withContext(Dispatchers.Main) {
                            if (isBatteryGuardCountdownActive) {
                                NotificationHelper.showBatteryGuardWarningNotification(
                                    context = this@ProxyForegroundService,
                                    targetTimeMs = targetTimeMs,
                                    remainingSeconds = remaining,
                                    reason = reason,
                                    batteryPct = if (currentPct > 0) currentPct else initialBatteryPct
                                )
                            }
                        }
                    }
                }

                if (isActive && isBatteryGuardCountdownActive) {
                    AppLogger.w(TAG, "5-минутный таймер защиты аккумулятора истек. Остановка прокси.")
                    withContext(Dispatchers.Main) {
                        isBatteryGuardCountdownActive = false
                        NotificationHelper.cancelBatteryGuardNotification(this@ProxyForegroundService)
                        stopProxyWithReason("Защита аккумулятора: $reason")
                        NotificationHelper.showBatteryGuardStoppedNotification(
                            context = this@ProxyForegroundService,
                            reason = reason
                        )
                    }
                }
            } catch (_: CancellationException) {
                // Таймер отменен
            }
        }
    }

    private fun cancelBatteryGuardCountdown(userDismissed: Boolean) {
        batteryGuardCountdownJob?.cancel()
        batteryGuardCountdownJob = null
        isBatteryGuardCountdownActive = false
        if (userDismissed) {
            batteryGuardDismissedForThreshold = true
            AppLogger.i(TAG, "Пользователь отменил автоотключение прокси по защите батареи")
        }
        NotificationHelper.cancelBatteryGuardNotification(this)
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        return try {
            val bIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 100
            val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
            val pct = if (scale > 0) (level * 100) / scale else level
            val status = bIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            Pair(pct, isCharging)
        } catch (_: Exception) {
            Pair(100, false)
        }
    }

    private suspend fun checkAndTuneWarpEndpoint() {
        val app = MirrlyApplication.instance
        val currentEp = app.config.warpPeerEndpoint
        val (_, port) = com.mirrly.tgproxy.core.WarpEndpointScanner.parseEndpoint(currentEp)

        // Порт 2408 и пустые порты известны 100% блокировками ТСПУ в РФ
        val isSuspectPort = port == 2408 || port <= 0
        if (!isSuspectPort) {
            val probe = com.mirrly.tgproxy.core.WarpEndpointScanner.probeEndpoint(currentEp, timeoutMs = 650, useFragmentation = true)
            if (probe.isAlive) {
                AppLogger.d(TAG, "Текущий Anycast-эндпоинт WARP активен: $currentEp (RTT=${probe.rttMs}мс)")
                return
            }
        }

        AppLogger.i(TAG, "Эндпоинт $currentEp недоступен (блокировка ТСПУ). Запуск автоподбора живых Anycast-портов...")
        try {
            val best = com.mirrly.tgproxy.core.WarpEndpointScanner.findBestEndpoint(useFragmentation = true, maxCandidatesToProbe = 18)
            if (best != null && best.endpoint != currentEp) {
                AppLogger.i(TAG, "Автоматически подобран рабочий Anycast-порт WARP: ${best.endpoint} (${best.rttMs}мс)")
                app.config.warpPeerEndpoint = best.endpoint
                app.prefsManager.saveConfig(app.config)
                app.proxyServer.applyWarpEndpoint(best.endpoint)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка автоматического подбора портов WARP: ${e.message}")
        }
    }

    private fun stopProxyWithReason(reason: String) {
        serviceScope.launch(Dispatchers.Main) {
            try {
                Toast.makeText(applicationContext, reason, Toast.LENGTH_LONG).show()
                val stopIntent = Intent(applicationContext, ProxyForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                startService(stopIntent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка автоматической остановки прокси: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            try {
                (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.removeThermalStatusListener(
                    thermalListener as PowerManager.OnThermalStatusChangedListener
                )
            } catch (_: Exception) {}
        }
        batteryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        PredictivePreWarmManager.stop(this)
        stopProxyService()
        NotificationHelper.cancelProxyNotifications(this)
        try { serviceScope.cancel() } catch (_: Exception) {}
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
