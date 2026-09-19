/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.NativeProxy
import com.mirrly.tgproxy.core.VpnSocketProtector
import com.mirrly.tgproxy.core.WarpAccountManager
import com.mirrly.tgproxy.core.WarpEndpointScanner
import com.mirrly.tgproxy.service.vpn.TunHolder
import com.mirrly.tgproxy.service.vpn.TunPacketEngine
import com.mirrly.tgproxy.service.vpn.VpnFailureReason
import com.mirrly.tgproxy.service.vpn.VpnInternalState
import com.mirrly.tgproxy.service.vpn.VpnNetworkBroker
import com.mirrly.tgproxy.service.vpn.VpnStatus
import com.mirrly.tgproxy.service.vpn.VpnTunManager
import com.mirrly.tgproxy.ui.MainActivity
import com.mirrly.tgproxy.ui.theme.VpnUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Промышленная системная служба VpnService (Tasks N01–N20).
 * Объединяет L3/L4 userspace стек TUN, защиту внешних сокетов от петель,
 * привязку к физической сети, DNS DoH резолвер и идемпотентный жизненный цикл.
 */
class MirrlyVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunHolder: TunHolder? = null
    private var packetEngine: TunPacketEngine? = null
    private var networkBroker: VpnNetworkBroker? = null
    private var monitorJob: Job? = null
    private var startTimeMs: Long = 0L

    companion object {
        private const val TAG = "MirrlyVpnService"
        const val ACTION_START = "com.mirrly.tgproxy.VPN_START"
        const val ACTION_STOP = "com.mirrly.tgproxy.VPN_STOP"

        val vpnGeneration = AtomicLong(0L)

        private val _vpnStatus = MutableStateFlow(VpnStatus())
        val vpnStatus: StateFlow<VpnStatus> = _vpnStatus.asStateFlow()

        private val _vpnState = MutableStateFlow(VpnUiState.DISCONNECTED)
        val vpnState: StateFlow<VpnUiState> = _vpnState.asStateFlow()

        val isRunning: Boolean
            get() = _vpnStatus.value.isRunning

        @Volatile
        private var wasProxyRunningBeforeVpn = false
        @Volatile
        private var previousProxyModeBeforeVpn = com.mirrly.tgproxy.core.ProxyMode.MTPROTO.name

        fun prepare(context: Context): Intent? {
            return try {
                VpnService.prepare(context)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка проверки готовности VpnService: ${e.message}")
                null
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, MirrlyVpnService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка запуска MirrlyVpnService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MirrlyVpnService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка остановки MirrlyVpnService: ${e.message}")
            }
        }

        fun restart(context: Context) {
            stop(context)
            start(context)
        }

        internal fun updateState(
            internalState: VpnInternalState,
            reason: VpnFailureReason = VpnFailureReason.NONE,
            profileName: String = "",
            uplinkMode: String = ""
        ) {
            val ui = internalState.toUiState()
            _vpnState.value = ui
            val current = _vpnStatus.value
            _vpnStatus.value = current.copy(
                internalState = internalState,
                uiState = ui,
                failureReason = reason,
                generation = vpnGeneration.get(),
                activeProfileName = if (profileName.isNotEmpty()) profileName else current.activeProfileName,
                uplinkMode = if (uplinkMode.isNotEmpty()) uplinkMode else current.uplinkMode
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
        AppLogger.i(TAG, "Служба MirrlyVpnService инициализирована")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
            else -> AppLogger.w(TAG, "Неизвестное действие: $action")
        }
        return START_NOT_STICKY
    }


    private fun startVpn() {
        if (tunHolder != null && !tunHolder!!.isClosed()) {
            AppLogger.d(TAG, "VPN уже поднят и активен, повторный запуск пропущен")
            updateState(VpnInternalState.RUNNING)
            return
        }

        val gen = vpnGeneration.incrementAndGet()
        startTimeMs = System.currentTimeMillis()
        updateState(VpnInternalState.PREPARING)

        val initialNotif = buildVpnNotification("Подготовка защищенного туннеля...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.VPN_NOTIFICATION_ID,
                initialNotif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NotificationHelper.VPN_NOTIFICATION_ID, initialNotif)
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val app = MirrlyApplication.instance
                val config = app.config

                wasProxyRunningBeforeVpn = app.proxyServer.isRunning
                previousProxyModeBeforeVpn = config.proxyModeName

                // Гарантируем наличие учетных данных SOCKS5
                if (!config.hasSocks5Auth) {
                    val (u, p) = com.mirrly.tgproxy.core.ProxyConfig.generateRandomSocks5Credentials()
                    config.socks5Username = u
                    config.socks5Password = p
                    app.prefsManager.saveConfig(config)
                }

                // Проверка готовности профиля WARP (для режимов VPN на базе WARP)
                if (config.isVpnAnyWarpUplink) {
                    val hasInvalidWarpCredentials = config.warpToken.isBlank() ||
                        config.warpToken == "mirrly-bootstrap-token" ||
                        config.warpPrivateKey == WarpAccountManager.BOOTSTRAP_PROFILE.privateKeyBase64

                    if (hasInvalidWarpCredentials) {
                        AppLogger.i(TAG, "VPN: регистрация рабочего профиля Cloudflare WARP...")
                        val regResult = WarpAccountManager.registerAndActivate(fallbackToBootstrap = false)
                        regResult.onSuccess { profile ->
                            app.prefsManager.saveWarpProfile(profile)
                            config.applyWarpProfile(profile)
                            app.prefsManager.saveConfig(config)
                            AppLogger.i(TAG, "VPN: зарегистрирован WARP профиль: ${profile.getSummary()}")
                        }.onFailure { err ->
                            AppLogger.w(TAG, "VPN: сбой регистрации WARP: ${err.message}")
                        }
                    }

                    // Анти-блокировка для РФ: гарантируем чистый, проверенный Anycast эндпоинт
                    val currentEp = config.effectivePeerEndpoint
                    val (_, port) = WarpEndpointScanner.parseEndpoint(currentEp)
                    val isSuspect = port == 2408 || port <= 0
                    val sticky = WarpEndpointScanner.getStickyProfile()

                    val cleanEp = if (sticky != null && sticky.isAlive && !sticky.endpoint.endsWith(":2408")) {
                        sticky.endpoint
                    } else if (isSuspect) {
                        AppLogger.i(TAG, "VPN: порт $port подвержен блокировкам ТСПУ. Поиск чистого Anycast эндпоинта...")
                        val best = WarpEndpointScanner.findBestEndpoint(useFragmentation = true, maxCandidatesToProbe = 12)
                        best?.endpoint ?: "188.114.96.1:8095"
                    } else {
                        val probe = WarpEndpointScanner.probeEndpoint(currentEp, timeoutMs = 450, useFragmentation = true)
                        if (!probe.isAlive) {
                            AppLogger.i(TAG, "VPN: текущий узел $currentEp недоступен (DPI drop). Поиск резервного...")
                            val best = WarpEndpointScanner.findBestEndpoint(useFragmentation = true, maxCandidatesToProbe = 12)
                            best?.endpoint ?: "188.114.96.1:8095"
                        } else {
                            currentEp
                        }
                    }

                    if (cleanEp != config.warpPeerEndpoint || isSuspect) {
                        AppLogger.i(TAG, "VPN: применен рабочий Anycast эндпоинт $cleanEp")
                        config.warpPeerEndpoint = cleanEp
                        config.warpMasquePeerEndpoint = cleanEp
                        config.warpMeasuredWgEndpoint = cleanEp
                        app.prefsManager.saveConfig(config)
                        app.proxyServer.applyWarpEndpoint(cleanEp)
                    }

                    // Передаем настройки AmneziaWG
                    val awgIni = config.getAmneziaWgConfig(cleanEndpoint = cleanEp)
                    NativeProxy.setAwgConfig(awgIni)
                }

                // Гарантируем запуск локального SOCKS5-бэкенда с конфигурацией VPN (порт 10808)
                val proxyReady = app.proxyServer.startForVpn(config, cacheDir)
                if (!proxyReady) {
                    AppLogger.e(TAG, "Не удалось поднять SOCKS5 сервер для VPN")
                    updateState(VpnInternalState.FAILED, VpnFailureReason.ESTABLISH_FAILED)
                    stopVpn()
                    return@launch
                }

                // Регистрация защиты внешних сокетов (Task N04)
                VpnSocketProtector.register(
                    onProtectSocket = { socket -> protect(socket) },
                    onProtectDatagram = { socket -> protect(socket) },
                    onProtectFd = { fd -> protect(fd) }
                )

                if (vpnGeneration.get() != gen) {
                    AppLogger.w(TAG, "Старт VPN прерван новым поколением жизненного цикла")
                    return@launch
                }

                updateState(VpnInternalState.CONNECTING)

                // Поднятие TUN интерфейса (Tasks N03, N08, N09, N12, N13, N18)
                val splitConfig = VpnTunManager.SplitTunnelConfig(
                    isEnabled = app.prefsManager.isVpnSplitTunnelEnabled(),
                    isAllowlist = app.prefsManager.isVpnSplitTunnelAllowlist(),
                    packageNames = app.prefsManager.getVpnSplitTunnelPackages()
                )

                val tunResult = VpnTunManager.establish(
                    vpnService = this@MirrlyVpnService,
                    config = config,
                    sessionName = "Mirrly VPN (${config.vpnUplinkMode.displayName})",
                    splitTunnel = splitConfig
                )

                tunResult.onSuccess { holder ->
                    tunHolder = holder

                    // Инициализация и запуск пакетного userspace конвейера (Tasks N06, N07, N10, N11, N17)
                    val engine = TunPacketEngine(
                        tunHolder = holder,
                        socks5Host = "127.0.0.1",
                        socks5Port = config.socks5Port,
                        socks5Username = config.socks5Username,
                        socks5Password = config.socks5Password,
                        networkGenerationProvider = { networkBroker?.currentNetworkGeneration?.get() ?: 1L },
                        isUplinkAliveProvider = { networkBroker?.isConnected ?: true },
                        vpnMtu = config.vpnMtu,
                        vpnBlockQuic = config.vpnBlockQuic,
                        vpnBlockIpv6Leaks = config.vpnBlockIpv6Leaks
                    )
                    packetEngine = engine
                    engine.start()

                    // Инициализация сетевого брокера физических интерфейсов (Tasks N05, N16)
                    val broker = VpnNetworkBroker(
                        context = this@MirrlyVpnService,
                        vpnService = this@MirrlyVpnService,
                        onNetworkMigrated = { net, nGen ->
                            AppLogger.i(TAG, "Миграция физической сети на $net (поколение: $nGen)")
                            try {
                                app.proxyServer.handleNetworkChanged()
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Ошибка сброса сетевых сокетов при миграции: ${e.message}")
                            }
                        }
                    )
                    networkBroker = broker
                    broker.start()

                    updateState(
                        internalState = VpnInternalState.RUNNING,
                        profileName = config.vpnUplinkMode.displayName,
                        uplinkMode = config.vpnUplinkMode.name
                    )

                    startMetricsMonitor()
                    AppLogger.i(TAG, "VPN-служба успешно перешла в состояние RUNNING")
                }.onFailure { err ->
                    AppLogger.e(TAG, "Не удалось поднять TUN: ${err.message}")
                    updateState(VpnInternalState.FAILED, VpnFailureReason.ESTABLISH_FAILED)
                    stopVpn()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Критический сбой инициализации VPN: ${e.message}", e)
                updateState(VpnInternalState.FAILED, VpnFailureReason.UNKNOWN)
                stopVpn()
            }
        }
    }

    private fun startMetricsMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val app = MirrlyApplication.instance
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            while (isActive && isRunning) {
                delay(1000L)
                val engine = packetEngine ?: break
                val bytesIn = engine.totalBytesIn.get()
                val bytesOut = engine.totalBytesOut.get()
                val tcpFlows = engine.activeTcpFlowCount.get()
                val udpSessions = engine.activeUdpSessionCount.get()
                val uptime = System.currentTimeMillis() - startTimeMs

                val cur = _vpnStatus.value
                _vpnStatus.value = cur.copy(
                    bytesIn = bytesIn,
                    bytesOut = bytesOut,
                    activeTcpFlows = tcpFlows,
                    activeUdpSessions = udpSessions,
                    uptimeMs = uptime
                )

                val notifText = "↓ ${formatBytes(bytesIn)} • ↑ ${formatBytes(bytesOut)} | Потоков: ${tcpFlows + udpSessions}"
                val notif = buildVpnNotification(notifText)
                try {
                    nm.notify(NotificationHelper.VPN_NOTIFICATION_ID, notif)
                } catch (_: Exception) {}
            }
        }
    }

    private fun stopVpn() {
        vpnGeneration.incrementAndGet()
        updateState(VpnInternalState.STOPPING)

        monitorJob?.cancel()
        monitorJob = null

        packetEngine?.stop()
        packetEngine = null

        networkBroker?.stop()
        networkBroker = null

        VpnSocketProtector.unregister()

        try {
            tunHolder?.closeSafely()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка при освобождении TUN: ${e.message}")
        }
        tunHolder = null

        updateState(VpnInternalState.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Восстановление автономного прокси, если он был запущен до VPN
        if (wasProxyRunningBeforeVpn) {
            try {
                val app = MirrlyApplication.instance
                app.config.proxyModeName = previousProxyModeBeforeVpn
                app.proxyServer.start(cacheDir)
                AppLogger.i(TAG, "Автономный прокси ($previousProxyModeBeforeVpn) успешно восстановлен после остановки VPN")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Ошибка восстановления автономного прокси: ${e.message}")
            }
        } else {
            MirrlyApplication.instance.proxyServer.stop()
        }

        stopSelf()
        AppLogger.i(TAG, "MirrlyVpnService успешно и идемпотентно остановлена")
    }

    override fun onRevoke() {
        AppLogger.w(TAG, "Системное разрешение VpnService отозвано системой/пользователем (Task N02)")
        updateState(VpnInternalState.FAILED, VpnFailureReason.REVOKED)
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
        AppLogger.i(TAG, "MirrlyVpnService уничтожена")
    }

    private fun buildVpnNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MirrlyVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val current = _vpnStatus.value
        val title = if (current.activeProfileName.isNotBlank()) {
            "Mirrly VPN • ${current.activeProfileName}"
        } else {
            getString(R.string.vpn_tunnel_title)
        }

        return NotificationCompat.Builder(this, NotificationHelper.VPN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(title)
            .setContentText(statusText)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_disconnect),
                stopPendingIntent
            )
            .build()
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
