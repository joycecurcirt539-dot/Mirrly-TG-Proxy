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
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.ConnectionQuality
import com.mirrly.tgproxy.core.ProxyDisplayLabels
import com.mirrly.tgproxy.core.ProxyNotificationPolicy
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
    private var restartJob: Job? = null
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
    private var isScreenOn = true
    @Volatile
    private var isPowerSaveMode = false

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
                    AppLogger.e(TAG, "Failed to restart proxy service: ${e.message}")
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

        networkObserver = NetworkChangeObserver(
            context = this,
            onNetworkChanged = networkChanged@{ newType, oldType, isInitial ->
                val app = MirrlyApplication.instance
                val server = app.proxyServer

                if (newType == "DISCONNECTED") {
                    AppLogger.i(TAG, "Network connection lost (DISCONNECTED). Entering dormant network standby mode...")
                    if (server.isRunning) {
                        server.setNetworkDormancy(true)
                    }
                    WorkerFailoverManager.stopRecoveryWatchdog()
                    return@networkChanged
                }

                if (server.isRunning) {
                    val isMobile = newType.contains("Mobile", ignoreCase = true) ||
                        newType.contains("Cellular", ignoreCase = true)

                    if (isInitial) {
                        // Initial default-network delivery only configures the
                        // already-started engine. It is not a handover and must
                        // not advance generation or reset live sockets.
                        server.setNetworkInterface(isMobile, isScreenOn = isScreenOn)
                        server.resumeNetworkMonitoring()
                    } else {
                        server.handleNetworkChanged(
                            newType = newType,
                            oldType = oldType,
                            isMobile = isMobile,
                            isScreenOn = isScreenOn
                        )
                    }

                    WorkerFailoverManager.startRecoveryWatchdogIfNeeded()

                    if (oldType == "Wi-Fi" && (newType.contains("Mobile") || newType.contains("Cellular"))) {
                        val stats = server.stats
                        val totalBytes = stats.totalBytesReceived.get() + stats.totalBytesSent.get()
                        if (totalBytes > 100_000L) {
                            showToastOnMainThread(getString(R.string.toast_switched_to_mobile_with_bytes, humanBytes(totalBytes)))
                        } else {
                            showToastOnMainThread(getString(R.string.toast_switched_to_mobile))
                        }
                    }


                }
            },
            onNetworkSuspended = {
                val server = MirrlyApplication.instance.proxyServer
                if (server.isRunning) {
                    server.handleNetworkSuspended()
                }
            },
            onNetworkResumed = { networkType ->
                val server = MirrlyApplication.instance.proxyServer
                if (server.isRunning) {
                    val isMobile = networkType.contains("Mobile", ignoreCase = true) ||
                        networkType.contains("Cellular", ignoreCase = true)
                    server.handleNetworkResumed(isMobile, isScreenOn = isScreenOn)
                    WorkerFailoverManager.startRecoveryWatchdogIfNeeded()
                }
            },
            onNetworkProfileChanged = { environment ->
                MirrlyApplication.instance.proxyServer.updateNetworkEnvironment(
                    environment = environment,
                    screenOn = isScreenOn,
                    powerSaveMode = isPowerSaveMode
                )
            }
        )
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
                if (isStopping) return START_NOT_STICKY
                restartJob?.cancel()
                restartJob = serviceScope.launch {
                    try {
                        val server = app.proxyServer
                        server.stop()
                        delay(350)
                        val started = startServerWithProfiling(server, cacheDir, needsWarp = false)
                        if (started) {
                            withContext(Dispatchers.Main) {
                                ProxyTileService.requestSync(this@ProxyForegroundService)
                                startNotificationUpdates()
                                startWakeLockRefresh()
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error while restarting proxy: ${e.message}")
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
                showToastOnMainThread(getString(R.string.toast_timer_extended, extraMin))
                updateNotificationImmediately()
                return START_REDELIVER_INTENT
            }
            ACTION_CANCEL_TIMER -> {
                SleepTimerManager.cancelTimer(this)
                showToastOnMainThread(getString(R.string.toast_timer_cancelled))
                updateNotificationImmediately()
                return START_REDELIVER_INTENT
            }
            ACTION_CANCEL_BATTERY_GUARD -> {
                cancelBatteryGuardCountdown(userDismissed = true)
                showToastOnMainThread(getString(R.string.toast_battery_guard_cancelled))
                return START_REDELIVER_INTENT
            }
        }

        batteryGuardDismissedForThreshold = false

        val notification = NotificationHelper.buildNotification(
            context = this,
            statusText = if (app.config.isSocks5Mode) getString(R.string.notif_service_socks5_active) else getString(R.string.notif_service_tg_active),
            speedText = getString(R.string.status_optimizing_route),
            statusIndicator = ProxyStatusIndicator.YELLOW
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
            AppLogger.e("ProxyForegroundService", "Failed to start ForegroundService: ${e.message}")
        }


        acquireWakeLock()

        val server = app.proxyServer
        if (!server.isRunning) {
            serviceScope.launch(Dispatchers.IO) {
                app.prefsManager.restoreUserPrimaryWorkerIfNeeded()
                if (app.config.isSocks5Mode && !app.config.hasSocks5Auth) {
                    val (u, p) = com.mirrly.tgproxy.core.ProxyConfig.generateRandomSocks5Credentials()
                    app.config.socks5Username = u
                    app.config.socks5Password = p
                    app.prefsManager.saveConfig(app.config)
                    com.mirrly.tgproxy.core.NativeProxy.setSocks5Auth(u, p)
                }

                val initialNetworkType = networkObserver?.getCurrentNetworkTypeName().orEmpty()
                server.setNetworkInterface(
                    isMobile = initialNetworkType.contains("Mobile", ignoreCase = true) ||
                        initialNetworkType.contains("Cellular", ignoreCase = true),
                    isScreenOn = isScreenOn
                )

                // 1. Поэтапная валидация и выбор наилучшего маршрута (Preflight Phase) ДО открытия зеленого статуса
                val preflightResult = try {
                    PreflightDiagnosticsEngine.runPreflight(
                        context = this@ProxyForegroundService,
                        config = app.config,
                        prefsManager = app.prefsManager,
                        isDegraded = false
                    )
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Pre-flight analysis exception: ${e.message}")
                    null
                }
                AppLogger.i(TAG, "Smart Connect pre-flight completed: ${preflightResult?.selectedRouteSummary}")

                // 2. Запуск локального сервера с УЖЕ отобранным и проверенным узлом
                val started = startServerWithProfiling(server, cacheDir, needsWarp = false)
                if (started) {
                    // 3. Предварительный прогрев (Pre-Warm) туннеля для мгновенного отклика в Telegram
                    if (!app.config.isSocks5Mode) {
                        try {
                            com.mirrly.tgproxy.core.NativeProxy.warmupWsPool()
                        } catch (_: Exception) {}
                    } else {
                        val targetDomain = app.config.getEffectiveCfDomain()
                        if (targetDomain.isNotBlank()) {
                            try {
                                com.mirrly.tgproxy.core.DohResolver.resolve(targetDomain)
                            } catch (_: Exception) {}
                        }
                    }

                    SessionHistoryManager.onSessionStarted(
                        presetName = getPresetShortName(app.config.speedPreset),
                        proxyMode = app.config.proxyMode.name
                    )
                    WorkerRequestTracker.onSessionStarted()
                    DonationManager.recordSuccessfulConnection(this@ProxyForegroundService)

                    if (app.prefsManager.isAutoStopOnStartEnabled() && !SleepTimerManager.timerState.value.isActive) {
                        val autoStopMin = app.prefsManager.getAutoStopMinutes()
                        AppLogger.i(TAG, "Auto-stop on launch enabled: starting timer for $autoStopMin min")
                        SleepTimerManager.startTimer(this@ProxyForegroundService, autoStopMin)
                    }
                }

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    ProxyTileService.requestSync(this@ProxyForegroundService)
                    startNotificationUpdates()
                    startWakeLockRefresh()
                    WorkerFailoverManager.startRecoveryWatchdogIfNeeded()
                    updateNotificationImmediately()
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
        app.proxyServer.applyMtprotoStandbyPerActiveSlot(nextPreset.defaultMtprotoStandbyPerActiveSlot)
        app.prefsManager.saveConfig(app.config)

        showToastOnMainThread(getString(R.string.toast_speed_preset_changed, getPresetShortName(nextPreset)))
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
            showToastOnMainThread(getString(R.string.toast_link_copied, label))
        } catch (_: Exception) {
            showToastOnMainThread(getString(R.string.toast_link_copy_failed))
        }
    }

    private fun showToastOnMainThread(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getPresetShortName(preset: SpeedPreset): String {
        return when (preset) {
            SpeedPreset.ULTRA -> getString(R.string.preset_ultra)
            SpeedPreset.TURBO -> getString(R.string.preset_turbo)
            SpeedPreset.BALANCED -> getString(R.string.preset_balanced)
            SpeedPreset.ECO -> getString(R.string.preset_eco)
            SpeedPreset.AUTO -> getString(R.string.preset_auto)
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

            if (app.config.isWarpCascadeUplink) {
                if (ProxyNotificationPolicy.isConfirmedFailover(previousStage, stageCode, configuredPrimaryStage = 1)) {
                    NotificationHelper.showFailoverNotification(
                        context = this@ProxyForegroundService,
                        title = getString(R.string.app_name),
                        message = getString(R.string.notif_failover_masque_to_awg)
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
            if (jitterMs > 0) getString(R.string.notif_ping_jitter_ms, pingMs, jitterMs) else getString(R.string.notif_ping_ms, pingMs)
        } else {
            ""
        }

        val netTypeName = networkObserver?.getCurrentNetworkTypeName() ?: "UNKNOWN"
        val pingSnapshot = server.pingEngine.currentSnapshot
        val totalBytes = stats.totalBytesReceived.get() + stats.totalBytesSent.get()
        val isNetworkLost = ProxyNotificationPolicy.isConfirmedOffline(
            serverRunning = server.isRunning,
            observerDisconnected = netTypeName == "DISCONNECTED",
            totalBytes = totalBytes,
            lastActivityTimestamp = stats.lastActivityTimestamp.get(),
            lastProbeTimestamp = pingSnapshot.lastProbeTimestamp,
            probeSucceeded = pingSnapshot.smoothedPingMs > 0L && pingSnapshot.consecutiveFailures == 0
        )

        val netName = when (netTypeName) {
            "Wi-Fi" -> "Wi-Fi"
            "Mobile LTE/5G", "Cellular" -> getString(R.string.notif_net_cellular)
            "Ethernet" -> "Ethernet"
            "DISCONNECTED" -> if (isNetworkLost) getString(R.string.notif_net_none) else getString(R.string.notif_net_active)
            else -> getString(R.string.notif_net_active)
        }

        val timerState = SleepTimerManager.timerState.value
        val timerSuffix = if (timerState.isActive) getString(R.string.notif_timer_suffix, timerState.formatRemainingTime()) else ""

        val effectiveDomain = if (app.config.isSocks5Mode) app.config.getEffectiveCfDomain() else ""
        val isWorker = app.config.isSocks5Mode && app.config.cfProxyEnabled && effectiveDomain.isNotBlank()
        val protoLabel = if (app.config.isSocks5Mode) "SOCKS5" else "MTProto"

        val (statusIndicator, title, text) = when {
            !server.isRunning || isNetworkLost -> {
                Triple(
                    ProxyStatusIndicator.RED,
                    getString(R.string.notif_title_template, protoLabel, getString(R.string.notif_status_no_net)),
                    getString(R.string.notif_body_no_net)
                )
            }
            app.config.isSocks5Mode -> {
                val currentStage = stats.activeCascadeStageCode
                val uplinkLabel = when {
                    stats.activeEffectiveRoute.isNotBlank() -> stats.activeEffectiveRoute
                    stats.activeCascadeStage.isNotBlank() -> stats.activeCascadeStage
                    else -> "Cloudflare Worker WSS"
                }
                val isCloudflareWorkerRoute = ProxyDisplayLabels.isCloudflareWorkerRoute(
                    effectiveRoute = stats.activeEffectiveRoute,
                    operator = stats.activeOperator,
                    configuredWorker = app.config.uplinkMode == com.mirrly.tgproxy.core.UplinkMode.WORKER
                )
                val trustWarning = if (!stats.isTrustBoundaryMaintained && !isCloudflareWorkerRoute) getString(R.string.notif_trust_public_relay) else ""
                val detailedLabel = if (stats.activeOperator.isNotBlank() && stats.activeEffectiveRoute.isNotBlank()) {
                    "$uplinkLabel (${stats.activeOperator})$trustWarning"
                } else {
                    "$uplinkLabel$trustWarning"
                }
                val fullLabel = ProxyDisplayLabels.notificationRouteLabel(
                    effectiveRoute = stats.activeEffectiveRoute,
                    operator = stats.activeOperator,
                    fallbackLabel = detailedLabel,
                    configuredWorker = app.config.uplinkMode == com.mirrly.tgproxy.core.UplinkMode.WORKER
                )
                val trustDegraded = !stats.isTrustBoundaryMaintained && !isCloudflareWorkerRoute
                val statusTitleText = if (trustDegraded) getString(R.string.notif_status_public_relay) else getString(R.string.notif_status_active)

                val failure = stats.lastFailureType
                if (failure != com.mirrly.tgproxy.core.FailureType.NONE && (quality == ConnectionQuality.OFFLINE || stats.healthScore < 20)) {
                    val userFailureMsg = com.mirrly.tgproxy.ui.ConnectionHealthFormatter.formatFailureForUser(this@ProxyForegroundService, failure)
                    Triple(
                        ProxyStatusIndicator.YELLOW,
                        getString(R.string.notif_title_template, protoLabel, getString(R.string.notif_status_active)),
                        getString(R.string.notif_body_format, fullLabel, userFailureMsg, dlSpeed, ulSpeed, netName, timerSuffix)
                    )
                } else if (pingMs > 0) {
                    val indicator = if (quality == ConnectionQuality.POOR || pingMs > 600L || trustDegraded) ProxyStatusIndicator.YELLOW else ProxyStatusIndicator.GREEN
                    Triple(
                        indicator,
                        getString(R.string.notif_title_template, protoLabel, statusTitleText),
                        getString(R.string.notif_body_format, fullLabel, pingDisplay, dlSpeed, ulSpeed, netName, timerSuffix)
                    )
                } else if (activeConns > 0) {
                    val indicator = if (trustDegraded) ProxyStatusIndicator.YELLOW else ProxyStatusIndicator.GREEN
                    Triple(
                        indicator,
                        getString(R.string.notif_title_template, protoLabel, statusTitleText),
                        getString(R.string.notif_body_format, fullLabel, getString(R.string.notif_status_tunnel_active), dlSpeed, ulSpeed, netName, timerSuffix)
                    )
                } else {
                    Triple(
                        if (trustDegraded) ProxyStatusIndicator.YELLOW else ProxyStatusIndicator.GREEN,
                        getString(R.string.notif_title_template, protoLabel, statusTitleText),
                        getString(R.string.notif_body_format, fullLabel, getString(R.string.notif_status_waiting_traffic), dlSpeed, ulSpeed, netName, timerSuffix)
                    )
                }
            }
            else -> {
                if (pingMs > 0) {
                    val indicator = if (quality == ConnectionQuality.POOR || pingMs > 600L) ProxyStatusIndicator.YELLOW else ProxyStatusIndicator.GREEN
                    Triple(
                        indicator,
                        getString(R.string.notif_title_template, protoLabel, getString(R.string.notif_status_active)),
                        getString(R.string.notif_body_format, "CDN", pingDisplay, dlSpeed, ulSpeed, netName, timerSuffix)
                    )
                } else {
                    Triple(
                        ProxyStatusIndicator.GREEN,
                        getString(R.string.notif_title_template, protoLabel, getString(R.string.notif_status_active)),
                        getString(R.string.notif_body_format, "CDN", getString(R.string.notif_status_tunnel_active), dlSpeed, ulSpeed, netName, timerSuffix)
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

    private var tuneWarpJob: Job? = null

    private fun stopProxyService() {
        if (isStopping) return
        isStopping = true

        tuneWarpJob?.cancel()
        tuneWarpJob = null
        restartJob?.cancel()
        restartJob = null

        stopNotificationUpdates()
        lastNotifiedIndicator = null
        lastNotifiedTitle = null
        lastNotifiedText = null
        lastNotifiedTimestamp = 0L
        lastNotifiedCascadeStageCode = 0

        PreflightDiagnosticsEngine.reset()
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
                WorkerFailoverManager.onProxyStopped()
                MirrlyApplication.instance.prefsManager.restoreUserPrimaryWorkerIfNeeded()

                withContext(Dispatchers.Main) {
                    ProxyTileService.requestSync(this@ProxyForegroundService)
                    NotificationHelper.cancelProxyNotifications(this@ProxyForegroundService)
                    stopSelf()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error while stopping service: ${e.message}")
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
                AppLogger.d(TAG, "Thermal status changed: $status")
                updateDeviceQoSState(status)
            }
            try {
                powerManager.addThermalStatusListener(listener)
                thermalListener = listener
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Failed to register OnThermalStatusChangedListener: ${t.message}")
            }
        }

        // 2. Battery, Screen & Power Save Broadcast Receiver
        val bReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        MirrlyApplication.instance.proxyServer.updateScreenPowerMode(
                            screenOn = false,
                            powerSaveMode = isPowerSaveMode
                        )
                        stopNotificationUpdates()
                        AppLogger.d(TAG, "Screen off: pausing background notification updates")
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn = true
                        MirrlyApplication.instance.proxyServer.updateScreenPowerMode(
                            screenOn = true,
                            powerSaveMode = isPowerSaveMode
                        )
                        AppLogger.d(TAG, "Screen on: resuming notification updates")
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
            AppLogger.w(TAG, "Failed to register battery/screen receiver: ${t.message}")
        }

        updateDeviceQoSState()
    }

    private fun updateDeviceQoSState(thermalStatusOverride: Int? = null) {
        try {
            val app = MirrlyApplication.instance
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isPowerSave = powerManager?.isPowerSaveMode == true
            isPowerSaveMode = isPowerSave
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
            app.proxyServer.updateScreenPowerMode(
                screenOn = isScreenOn,
                powerSaveMode = isPowerSave
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
                AppLogger.i(TAG, "Charger connected: battery guard countdown cancelled")
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
            getString(R.string.battery_guard_reason_threshold, batteryPct, config.batteryGuardThreshold)
        } else {
            getString(R.string.battery_guard_reason_powersave)
        }

        startBatteryGuardCountdown(reason = reason, initialBatteryPct = batteryPct)
    }

    private fun startBatteryGuardCountdown(reason: String, initialBatteryPct: Int) {
        if (isBatteryGuardCountdownActive) return
        isBatteryGuardCountdownActive = true

        val totalSeconds = 300 // 5 минут
        val targetTimeMs = System.currentTimeMillis() + totalSeconds * 1000L

        AppLogger.w(TAG, "Battery guard: $reason. Starting 5-minute warning timer before shutdown.")

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
                        AppLogger.i(TAG, "Charger connected: battery guard countdown cancelled")
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
                    AppLogger.w(TAG, "5-minute battery guard timer expired. Stopping proxy.")
                    withContext(Dispatchers.Main) {
                        isBatteryGuardCountdownActive = false
                        NotificationHelper.cancelBatteryGuardNotification(this@ProxyForegroundService)
                        stopProxyWithReason(getString(R.string.battery_guard_stop_reason, reason))
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
            AppLogger.i(TAG, "User cancelled battery guard proxy auto-shutdown")
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
        val targetGen = app.proxyServer.currentProfileGeneration.get()
        val targetMode = app.config.uplinkMode
        val currentEp = app.config.warpPeerEndpoint
        val (_, port) = com.mirrly.tgproxy.core.WarpEndpointScanner.parseEndpoint(currentEp)

        if (!app.proxyServer.isRunning || !app.config.isAnyWarpUplink) {
            return
        }

        // Порт 2408 и пустые порты известны 100% блокировками ТСПУ в РФ
        val isSuspectPort = port == 2408 || port <= 0
        if (!isSuspectPort) {
            val probe = com.mirrly.tgproxy.core.WarpEndpointScanner.probeEndpoint(currentEp, timeoutMs = 650, useFragmentation = true)
            if (probe.isAlive) {
                AppLogger.d(TAG, "Current WARP Anycast endpoint is active: $currentEp (RTT=${probe.rttMs}ms)")
                return
            }
        }

        if (app.proxyServer.currentProfileGeneration.get() != targetGen || !app.proxyServer.isRunning || app.config.uplinkMode != targetMode) {
            return
        }

        AppLogger.i(TAG, "Endpoint $currentEp unavailable (DPI blocking). Starting Anycast port auto-tuning (gen=$targetGen)...")
        try {
            val best = com.mirrly.tgproxy.core.WarpEndpointScanner.findBestEndpoint(useFragmentation = true, maxCandidatesToProbe = 18)
            if (app.proxyServer.currentProfileGeneration.get() != targetGen || !app.proxyServer.isRunning || app.config.uplinkMode != targetMode) {
                AppLogger.w(TAG, "WARP Anycast port auto-tuning ignored: profile state changed during scan")
                return
            }
            if (best != null && best.endpoint != currentEp) {
                AppLogger.i(TAG, "Selected working WARP Anycast port: ${best.endpoint} (${best.rttMs}ms)")
                app.config.warpPeerEndpoint = best.endpoint
                app.config.warpMasquePeerEndpoint = best.endpoint
                app.config.warpMeasuredWgEndpoint = best.endpoint
                app.prefsManager.saveConfig(app.config)
                app.proxyServer.applyWarpEndpoint(best.endpoint, expectedGeneration = targetGen, expectedMode = targetMode)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to auto-tune WARP ports: ${e.message}")
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
                AppLogger.e(TAG, "Failed to automatically stop proxy: ${e.message}")
            }
        }
    }

    private suspend fun startServerWithProfiling(
        server: com.mirrly.tgproxy.core.LocalProxyServer,
        cacheDir: java.io.File,
        needsWarp: Boolean
    ): Boolean {
        if (!needsWarp) {
            return server.start(cacheDir)
        }

        // Если предстартовая диагностика уже проверила пайплайн менее 10 секунд назад, не дублируем сетевые пробы
        val cached = com.mirrly.tgproxy.core.WarpPipelineProfiler.getLatestMetrics()
        if (cached != null && (System.currentTimeMillis() - cached.timestampMs) < 10_000L && cached.isSuccess) {
            val pStart = System.nanoTime()
            val s = server.start(cacheDir)
            val tTunnelMs = (System.nanoTime() - pStart) / 1_000_000L
            val updated = cached.copy(
                tTunnelMs = tTunnelMs,
                tTotalMs = cached.tConfigMs + cached.tDnsMs + cached.tObfuscationMs + cached.tHandshakeMs + tTunnelMs,
                isSuccess = s,
                failurePhase = if (!s) com.mirrly.tgproxy.core.WarpPhase.TUNNEL_ESTABLISHMENT else null,
                timestampMs = System.currentTimeMillis()
            )
            com.mirrly.tgproxy.core.WarpPipelineProfiler.recordMetrics(updated)
            return s
        }

        val app = MirrlyApplication.instance
        var failurePhase: com.mirrly.tgproxy.core.WarpPhase? = null
        var isSuccess = true
        var errorDetail: String? = null

        // Phase 1: CONFIG_ACQUISITION
        val p1Start = System.nanoTime()
        try {
            val privKey = app.config.warpPrivateKey
            val token = app.config.warpToken
            if (privKey.isBlank() || token.isBlank()) {
                AppLogger.d(TAG, "WARP Profiler: Config key/token empty")
            }
        } catch (e: Exception) {
            failurePhase = com.mirrly.tgproxy.core.WarpPhase.CONFIG_ACQUISITION
            isSuccess = false
            errorDetail = e.message
        }
        val tConfigMs = (System.nanoTime() - p1Start) / 1_000_000L

        // Phase 2: DNS_RESOLUTION
        val p2Start = System.nanoTime()
        try {
            com.mirrly.tgproxy.core.DohResolver.resolve("engage.cloudflareclient.com", com.mirrly.tgproxy.core.DnsScope.BOOTSTRAP)
        } catch (e: Exception) {
            if (isSuccess) {
                failurePhase = com.mirrly.tgproxy.core.WarpPhase.DNS_RESOLUTION
                errorDetail = e.message
            }
        }
        val tDnsMs = (System.nanoTime() - p2Start) / 1_000_000L

        // Phase 3: OBFUSCATION_PREPARATION
        val p3Start = System.nanoTime()
        try {
            val awgIni = app.config.getAmneziaWgConfig(cleanEndpoint = app.config.warpPeerEndpoint)
            if (awgIni.isBlank()) {
                failurePhase = com.mirrly.tgproxy.core.WarpPhase.OBFUSCATION_PREPARATION
                errorDetail = "Empty obfuscation config"
            }
        } catch (e: Exception) {
            if (isSuccess) {
                failurePhase = com.mirrly.tgproxy.core.WarpPhase.OBFUSCATION_PREPARATION
                errorDetail = e.message
            }
        }
        val tObfuscationMs = (System.nanoTime() - p3Start) / 1_000_000L

        // Phase 4: HANDSHAKE_EXCHANGE
        val p4Start = System.nanoTime()
        try {
            val ep = app.config.warpPeerEndpoint
            val probeProto = if (app.config.isAwgUplink) com.mirrly.tgproxy.core.WarpProbeProtocol.WIREGUARD else com.mirrly.tgproxy.core.WarpProbeProtocol.MASQUE_QUIC
            val probe = com.mirrly.tgproxy.core.WarpEndpointScanner.probeEndpoint(
                endpoint = ep,
                timeoutMs = 400,
                protocol = probeProto,
                useFragmentation = true
            )
            if (!probe.isAlive && isSuccess) {
                failurePhase = com.mirrly.tgproxy.core.WarpPhase.HANDSHAKE_EXCHANGE
                errorDetail = "Endpoint probe unconfirmed"
            }
        } catch (e: Exception) {
            if (isSuccess) {
                failurePhase = com.mirrly.tgproxy.core.WarpPhase.HANDSHAKE_EXCHANGE
                errorDetail = e.message
            }
        }
        val tHandshakeMs = (System.nanoTime() - p4Start) / 1_000_000L

        // Phase 5: TUNNEL_ESTABLISHMENT
        val p5Start = System.nanoTime()
        val s = server.start(cacheDir)
        val tTunnelMs = (System.nanoTime() - p5Start) / 1_000_000L
        if (!s) {
            failurePhase = com.mirrly.tgproxy.core.WarpPhase.TUNNEL_ESTABLISHMENT
            isSuccess = false
            errorDetail = "Native tunnel start returned false"
        }
        val tTotalMs = tConfigMs + tDnsMs + tObfuscationMs + tHandshakeMs + tTunnelMs

        val metrics = com.mirrly.tgproxy.core.WarpProfileMetrics(
            tConfigMs = tConfigMs,
            tDnsMs = tDnsMs,
            tObfuscationMs = tObfuscationMs,
            tHandshakeMs = tHandshakeMs,
            tTunnelMs = tTunnelMs,
            tTotalMs = tTotalMs,
            failurePhase = failurePhase,
            isSuccess = isSuccess && s,
            errorDetail = errorDetail
        )
        com.mirrly.tgproxy.core.WarpPipelineProfiler.recordMetrics(metrics)
        return s
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
