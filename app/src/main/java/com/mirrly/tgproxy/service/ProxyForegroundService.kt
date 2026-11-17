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
    private var livenessWatchdogJob: Job? = null
    private var trafficPulseJob: Job? = null
    private var consecutiveLivenessFailures = 0
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

        private const val WAKELOCK_TIMEOUT_MS = 10L * 1000 // 10s переходный вейклок на время старта/хэндшейка
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

        @Volatile
        private var activeInstance: java.lang.ref.WeakReference<ProxyForegroundService>? = null

        fun refreshWakeLockDirectly() {
            activeInstance?.get()?.refreshWakeLock()
        }

        fun applyCpuAntiFreezeDirectly(enabled: Boolean) {
            activeInstance?.get()?.applyCpuAntiFreeze(enabled)
        }

        fun applyTrafficPulseDirectly(enabled: Boolean) {
            activeInstance?.get()?.applyTrafficPulse(enabled)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = java.lang.ref.WeakReference(this)
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
                        val isActuallyWifi = networkObserver?.isWifiActive() ?: false
                        if (!isActuallyWifi) {
                            val stats = server.stats
                            val totalBytes = stats.totalBytesReceived.get() + stats.totalBytesSent.get()
                            if (totalBytes > 100_000L) {
                                showToastOnMainThread(getString(R.string.toast_switched_to_mobile_with_bytes, humanBytes(totalBytes)))
                            } else {
                                showToastOnMainThread(getString(R.string.toast_switched_to_mobile))
                            }
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

        serviceScope.launch {
            MirrlyApplication.instance.prefsManager.forceMobileModeFlow.collect { forced ->
                AppLogger.i(TAG, "Force mobile mode changed: $forced. Refreshing network profile...")
                networkObserver?.refreshNetworkProfile()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = MirrlyApplication.instance

        if (intent == null) {
            if (!app.prefsManager.isProxyActive()) {
                AppLogger.w(TAG, "Service resurrected by system with null intent, but proxy was not active. Stopping self.")
                stopSelf()
                return START_NOT_STICKY
            }
            AppLogger.i(TAG, "Service resurrected by system after OS kill. Auto-restoring proxy session...")
            isStopping = false
        } else {
            when (intent.action) {
                ACTION_STOP -> {
                    app.prefsManager.setProxyActive(false)
                    stopProxyService()
                    return START_NOT_STICKY
                }
                ACTION_START -> {
                    isStopping = false
                    app.prefsManager.setProxyActive(true)
                }
            ACTION_RESTART -> {
                if (isStopping) return START_NOT_STICKY

                restartJob?.cancel()
                restartJob = serviceScope.launch {
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
    }

        batteryGuardDismissedForThreshold = false
        ProxyResurrectionManager.cancel(this)

        val protoLabel = if (app.config.isSocks5Mode) "SOCKS5" else "MTProto"
        val initialStatusText = getString(R.string.notif_title_template, protoLabel)
        val initialUplink = resolveProxyUplinkLabel()
        val notification = NotificationHelper.buildNotification(
            context = this,
            statusText = initialStatusText,
            speedText = "$initialUplink • ${getString(R.string.status_optimizing_route)}",
            statusIndicator = ProxyStatusIndicator.YELLOW
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
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


                val initialNetworkType = networkObserver?.getCurrentNetworkTypeName().orEmpty()
                server.setNetworkInterface(
                    isMobile = initialNetworkType.contains("Mobile", ignoreCase = true) ||
                        initialNetworkType.contains("Cellular", ignoreCase = true),
                    isScreenOn = isScreenOn
                )

                // 1. Поэтапная валидация, запуск сервера и прогрев пула потоков ДО открытия зеленого статуса
                var serverStarted = false
                val preflightResult = try {
                    PreflightDiagnosticsEngine.runPreflight(
                        context = this@ProxyForegroundService,
                        config = app.config,
                        prefsManager = app.prefsManager,
                        serverStarter = {
                            serverStarted = server.start(cacheDir)
                            serverStarted
                        },
                        isDegraded = false
                    )
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Pre-flight analysis exception: ${e.message}")
                    null
                }
                AppLogger.d(TAG, "Smart Connect pre-flight completed: ${preflightResult?.selectedRouteSummary}")

                // 2. Гарантия запуска локального сервера
                val started = if (serverStarted || server.isRunning) true else server.start(cacheDir)
                if (started) {
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
                    startLivenessWatchdog()
                    startTrafficPulse()
                    WorkerFailoverManager.startRecoveryWatchdogIfNeeded()
                    updateNotificationImmediately()
                    ProxyResurrectionManager.startWatchdog(this@ProxyForegroundService)
                }
            }
        } else {
            ProxyTileService.requestSync(this)
            startNotificationUpdates()
            startWakeLockRefresh()
            startLivenessWatchdog()
            startTrafficPulse()
            WorkerFailoverManager.startRecoveryWatchdogIfNeeded()
            ProxyResurrectionManager.startWatchdog(this)
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

        val tgUrl = server.getTelegramUrl()
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

    private fun acquireWakeLock(timeoutMs: Long = WAKELOCK_TIMEOUT_MS) {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MirrlyProxy::ServiceWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    if (timeoutMs > 0) {
                        it.acquire(timeoutMs)
                        AppLogger.d(TAG, "Transitional WakeLock acquired for ${timeoutMs}ms")
                    } else {
                        it.acquire()
                        AppLogger.d(TAG, "Continuous Anti-Freeze WakeLock acquired (indefinite)")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun startWakeLockRefresh() {
        val app = MirrlyApplication.instance
        wakeLockJob?.cancel()
        if (app.config.isCpuAntiFreezeEnabled) {
            // Anti-Freeze mode for aggressive OEM skins (Honor MagicOS / Xiaomi HyperOS):
            // Keeps continuous PARTIAL_WAKE_LOCK to prevent kernel cgroups freezer from suspending sockets
            acquireWakeLock(timeoutMs = -1L)
            AppLogger.i(TAG, "Continuous Anti-Freeze WakeLock active (OEM cgroups defense)")
            return
        }
        wakeLockJob = serviceScope.launch {
            delay(WAKELOCK_TIMEOUT_MS)
            releaseWakeLock()
            AppLogger.d(TAG, "Transitional startup WakeLock released for Doze Mode sleep compliance")
        }
    }

    private fun refreshWakeLock() {
        val app = MirrlyApplication.instance
        if (app.config.isCpuAntiFreezeEnabled) {
            acquireWakeLock(timeoutMs = -1L)
        } else {
            acquireWakeLock(WAKELOCK_TIMEOUT_MS)
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

    private fun applyCpuAntiFreeze(enabled: Boolean) {
        if (enabled) {
            wakeLockJob?.cancel()
            acquireWakeLock(timeoutMs = -1L)
            AppLogger.i(TAG, "Anti-Freeze WakeLock enabled by user")
        } else {
            startWakeLockRefresh()
            AppLogger.i(TAG, "Anti-Freeze WakeLock disabled by user; reverting to transitional mode")
        }
    }

    private fun startTrafficPulse() {
        trafficPulseJob?.cancel()
        val app = MirrlyApplication.instance
        if (!app.config.isTrafficPulseEnabled) return

        trafficPulseJob = serviceScope.launch {
            while (isActive) {
                delay(75_000L) // 75 seconds
                val server = app.proxyServer
                if (server.isRunning && !isStopping) {
                    val stats = server.stats
                    val totalSpeed = stats.downloadSpeedBps + stats.uploadSpeedBps
                    // Only pulse when the channel is idle (< 128 bytes/sec)
                    if (totalSpeed < 128L) {
                        try {
                            server.measurePingAsync()
                            AppLogger.d(TAG, "Keep-Alive traffic pulse transmitted to preserve CGNAT and process state")
                        } catch (e: Exception) {
                            AppLogger.d(TAG, "Traffic pulse skipped: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun stopTrafficPulse() {
        trafficPulseJob?.cancel()
        trafficPulseJob = null
    }

    private fun applyTrafficPulse(enabled: Boolean) {
        if (enabled) {
            startTrafficPulse()
        } else {
            stopTrafficPulse()
        }
    }

    private fun startLivenessWatchdog() {
        livenessWatchdogJob?.cancel()
        consecutiveLivenessFailures = 0
        livenessWatchdogJob = serviceScope.launch {
            while (isActive) {
                delay(20_000L)
                val app = MirrlyApplication.instance
                val server = app.proxyServer
                if (server.isRunning && !isStopping) {
                    // 1. Пассивный контроль: при наличии активного трафика сокет гарантированно жив
                    val hasActiveTraffic = (server.stats.downloadSpeedBps + server.stats.uploadSpeedBps) > 256L
                    if (hasActiveTraffic) {
                        consecutiveLivenessFailures = 0
                        continue
                    }

                    // 2. Безсокетовый FFI-контроль: проверка жизни Tokio runtime в нативном движке без паразитных TCP сессий
                    val isNativeAlive = com.mirrly.tgproxy.core.NativeProxy.isStarted && (com.mirrly.tgproxy.core.NativeProxy.getStats() != null)
                    if (isNativeAlive) {
                        consecutiveLivenessFailures = 0
                        continue
                    }

                    // 3. Fallback: только при сбое FFI выполняем loopback TCP-проверку
                    val alive = server.isPortResponding(timeoutMs = 1200)
                    if (alive) {
                        consecutiveLivenessFailures = 0
                    } else {
                        consecutiveLivenessFailures++
                        AppLogger.w(TAG, "Liveness probe failed ($consecutiveLivenessFailures/3) for local listener port")
                        if (consecutiveLivenessFailures >= 3) {
                            AppLogger.e(TAG, "Local listener port is not responding. Performing auto-healing restart...")
                            consecutiveLivenessFailures = 0
                            try {
                                server.restart(cacheDir)
                                updateNotificationImmediately()
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Auto-healing restart failed: ${e.message}")
                            }
                        }
                    }
                } else {
                    consecutiveLivenessFailures = 0
                }
            }
        }
    }

    private fun stopLivenessWatchdog() {
        livenessWatchdogJob?.cancel()
        livenessWatchdogJob = null
        consecutiveLivenessFailures = 0
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val app = MirrlyApplication.instance
        val isProxyActive = app.prefsManager.isProxyActive() && !isStopping
        AppLogger.w(TAG, "onTaskRemoved: proxy service active, task removed from recents (isProxyActive=$isProxyActive)")
        if (isProxyActive) {
            ProxyResurrectionManager.schedule(this)
        }
    }

    private fun startNotificationUpdates() {
        if (!isScreenOn) return
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            val app = MirrlyApplication.instance
            val server = app.proxyServer
            var secondsCounter = 0

            while (isActive && server.isRunning && isScreenOn) {
                delay(2000)
                secondsCounter += 2

                if (secondsCounter >= 10) {
                    ValueTriggerManager.addActiveSeconds(this@ProxyForegroundService, secondsCounter)
                    secondsCounter = 0
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
                MirrlyBridgeReceiver.broadcastStatus(this@ProxyForegroundService)
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
    private fun resolveProxyUplinkLabel(): String {
        val app = MirrlyApplication.instance
        val stats = app.proxyServer.stats
        val config = app.config

        return if (config.isSocks5Mode) {
            val stage = stats.activeCascadeStage
            val route = stats.activeEffectiveRoute
            if (stage.contains("Anycast", ignoreCase = true) || route.contains("Anycast", ignoreCase = true) || config.socks5UplinkMode == 1) {
                "Anycast CDN"
            } else {
                "Cloudflare Worker"
            }
        } else {
            // MTProto
            if (config.useWorkerMtproto && config.getEffectiveCfDomain().isNotBlank()) {
                "Cloudflare Worker"
            } else if (config.cfProxyEnabled) {
                "Anycast CDN"
            } else {
                "Direct"
            }
        }
    }

    private fun updateNotificationInternal(force: Boolean = false) {
        val app = MirrlyApplication.instance
        val server = app.proxyServer
        if (isStopping || !server.isRunning) return
        val stats = server.stats
        val activeConns = stats.activeConnections.get()
        val dlSpeed = humanBytes(stats.downloadSpeedBps)
        val ulSpeed = humanBytes(stats.uploadSpeedBps)
        val pingMs = server.currentPingMs
        val quality = server.connectionQuality

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

        val timerState = SleepTimerManager.timerState.value
        val timerSuffix = if (timerState.isActive) getString(R.string.notif_timer_suffix, timerState.formatRemainingTime()) else ""

        val protoLabel = if (app.config.isSocks5Mode) "SOCKS5" else "MTProto"
        val uplinkLabel = resolveProxyUplinkLabel()

        val failure = stats.lastFailureType
        val isSevereFailure = !stats.isCalibrating &&
            !stats.isLiveTrafficActive &&
            failure != com.mirrly.tgproxy.core.FailureType.NONE &&
            (quality == ConnectionQuality.OFFLINE || stats.healthScore < 20)

        val (statusIndicator, title, text) = when {
            !server.isRunning || isNetworkLost -> {
                Triple(
                    ProxyStatusIndicator.RED,
                    getString(R.string.notif_title_offline, protoLabel, getString(R.string.notif_status_no_net)),
                    getString(R.string.notif_body_no_net)
                )
            }
            isSevereFailure -> {
                val userFailureMsg = com.mirrly.tgproxy.ui.ConnectionHealthFormatter.formatFailureForUser(this@ProxyForegroundService, failure)
                Triple(
                    ProxyStatusIndicator.YELLOW,
                    getString(R.string.notif_title_template, protoLabel),
                    "$uplinkLabel • $userFailureMsg$timerSuffix"
                )
            }
            pingMs > 0 -> {
                val indicator = if (quality == ConnectionQuality.POOR || pingMs > 600L) ProxyStatusIndicator.YELLOW else ProxyStatusIndicator.GREEN
                val pingDisplay = getString(R.string.notif_ping_ms, pingMs)
                val speedDisplay = getString(R.string.notif_speed_format, dlSpeed, ulSpeed)
                Triple(
                    indicator,
                    getString(R.string.notif_title_template, protoLabel),
                    "$uplinkLabel • $pingDisplay • $speedDisplay$timerSuffix"
                )
            }
            activeConns > 0 -> {
                val speedDisplay = getString(R.string.notif_speed_format, dlSpeed, ulSpeed)
                Triple(
                    ProxyStatusIndicator.GREEN,
                    getString(R.string.notif_title_template, protoLabel),
                    "$uplinkLabel • ${getString(R.string.notif_status_tunnel_active)} • $speedDisplay$timerSuffix"
                )
            }
            else -> {
                Triple(
                    ProxyStatusIndicator.GREEN,
                    getString(R.string.notif_title_template, protoLabel),
                    "$uplinkLabel • ${getString(R.string.notif_status_waiting_traffic)}$timerSuffix"
                )
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
        ProxyResurrectionManager.cancel(this)
        MirrlyApplication.instance.prefsManager.setProxyActive(false)
        stopLivenessWatchdog()

        tuneWarpJob?.cancel()
        tuneWarpJob = null
        restartJob?.cancel()
        restartJob = null

        stopNotificationUpdates()
        lastNotifiedIndicator = null
        lastNotifiedTitle = null
        lastNotifiedText = null
        lastNotifiedTimestamp = 0L

        PreflightDiagnosticsEngine.reset()
        SleepTimerManager.cancelTimer(this)
        cancelBatteryGuardCountdown(userDismissed = false)
        batteryGuardDismissedForThreshold = false
        ProxyResurrectionManager.cancel(this)
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
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                bReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
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
        ProxyResurrectionManager.cancel(this)
            return
        }

        val isLowBattery = batteryPct in 1..config.batteryGuardThreshold
        val isPowerSaveTrigger = config.batteryGuardStopOnPowerSave && isPowerSave

        if (!isLowBattery && !isPowerSaveTrigger) {
            if (isBatteryGuardCountdownActive) {
                cancelBatteryGuardCountdown(userDismissed = false)
            }
            batteryGuardDismissedForThreshold = false
        ProxyResurrectionManager.cancel(this)
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

        // Cancel background service jobs and hardware locks
        tuneWarpJob?.cancel()
        tuneWarpJob = null
        restartJob?.cancel()
        restartJob = null
        updateJob?.cancel()
        updateJob = null
        wakeLockJob?.cancel()
        wakeLockJob = null
        stopLivenessWatchdog()
        stopTrafficPulse()
        batteryGuardCountdownJob?.cancel()
        batteryGuardCountdownJob = null
        releaseWakeLock()
        networkObserver?.stop()
        networkObserver = null

        // If onDestroy is invoked due to an intentional stopProxyService():
        // isStopping will be true. Otherwise, if destroyed by Android OS (LMK/Samsung Doze),
        // we DO NOT invoke stopProxyService() or cancel notifications, which allows
        // the system to resurrect the service cleanly via START_REDELIVER_INTENT.
        if (isStopping) {
            ProxyResurrectionManager.cancel(this)
            NotificationHelper.cancelProxyNotifications(this)
            MirrlyBridgeReceiver.broadcastStatus(this)
        } else {
            val app = MirrlyApplication.instance
            if (app.prefsManager.isProxyActive()) {
                AppLogger.w(TAG, "ProxyForegroundService destroyed by OS while active. Scheduling resurrection...")
                ProxyResurrectionManager.schedule(this)
            } else {
                AppLogger.w(TAG, "ProxyForegroundService destroyed by OS. Standing by for START_REDELIVER_INTENT resurrection.")
            }
        }

        try { serviceScope.cancel() } catch (_: Exception) {}
        if (activeInstance?.get() == this) {
            activeInstance = null
        }
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
