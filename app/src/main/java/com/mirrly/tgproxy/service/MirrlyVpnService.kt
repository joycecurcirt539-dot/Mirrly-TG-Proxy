/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.NativeProxy
import com.mirrly.tgproxy.core.VpnSocketProtector
import com.mirrly.tgproxy.core.WarpAccountManager
import com.mirrly.tgproxy.core.WarpEndpointScanner
import com.mirrly.tgproxy.service.vpn.TunHolder
import org.json.JSONObject
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Промышленная системная служба VpnService (Tasks N01–N20).
 * Объединяет L3/L4 userspace стек TUN, защиту внешних сокетов от петель,
 * привязку к физической сети, DNS DoH резолвер и идемпотентный жизненный цикл.
 */
class MirrlyVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var tunHolder: TunHolder? = null
    @Volatile
    private var networkBroker: VpnNetworkBroker? = null
    @Volatile
    private var startJob: Job? = null
    @Volatile
    private var monitorJob: Job? = null
    @Volatile
    private var stopJob: Job? = null
    @Volatile
    private var sleepWakeReceiver: BroadcastReceiver? = null
    @Volatile
    private var sleepWakeDebounceJob: Job? = null
    @Volatile
    private var startTimeMs: Long = 0L
    private val lifecycleLock = Any()

    companion object {
        private const val TAG = "MirrlyVpnService"
        const val ACTION_START = "com.mirrly.tgproxy.VPN_START"
        const val ACTION_STOP = "com.mirrly.tgproxy.VPN_STOP"
        const val ACTION_RESTART = "com.mirrly.tgproxy.VPN_RESTART"

        val vpnGeneration = AtomicLong(0L)

        private val _vpnStatus = MutableStateFlow(VpnStatus())
        val vpnStatus: StateFlow<VpnStatus> = _vpnStatus.asStateFlow()

        private val _vpnState = MutableStateFlow(VpnUiState.DISCONNECTED)
        val vpnState: StateFlow<VpnUiState> = _vpnState.asStateFlow()

        val isRunning: Boolean
            get() = _vpnStatus.value.isRunning

        fun prepare(context: Context): Intent? {
            return try {
                VpnService.prepare(context)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка проверки готовности VpnService: ${e.message}")
                null
            }
        }

        fun start(context: Context) {
            updateState(VpnInternalState.PREPARING)
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
                updateState(VpnInternalState.IDLE)
            }
        }

        fun stop(context: Context) {
            vpnGeneration.incrementAndGet()
            updateState(VpnInternalState.STOPPING)
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
            val intent = Intent(context, MirrlyVpnService::class.java).apply {
                action = ACTION_RESTART
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка перезапуска MirrlyVpnService: ${e.message}")
            }
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
        VpnSocketProtector.register(
            onProtectSocket = { socket -> protect(socket) },
            onProtectDatagram = { socket -> protect(socket) },
            onProtectFd = { fd -> protect(fd) }
        )
        NativeProxy.setProtectCallback { fd ->
            val ok = try {
                protect(fd)
            } catch (e: Exception) {
                AppLogger.w(TAG, "protect(fd=$fd) исключение: ${e.message}")
                false
            }
            AppLogger.d(TAG, "NativeProxy: protect(fd=$fd) -> $ok")
            ok
        }
        registerSleepWakeReceiver()
        AppLogger.i(TAG, "Служба MirrlyVpnService инициализирована с защитой сокетов и приемником сна/пробуждения")
    }

    /**
     * Регистрация приемника событий сна и пробуждения устройства (Cloudflare DeviceSleepWakeReceiver Model).
     * Отслеживает SCREEN_ON, SCREEN_OFF и выход из Doze-режима (ACTION_DEVICE_IDLE_MODE_CHANGED).
     * При пробуждении перезапрашивает физическую сеть и сбрасывает устаревшие NAT-сессии WireGuard/AWG/MASQUE.
     */
    private fun registerSleepWakeReceiver() {
        if (sleepWakeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        AppLogger.d(TAG, "DeviceSleepWakeReceiver: экран выключен, устройство переходит в энергосбережение")
                    }
                    Intent.ACTION_SCREEN_ON, PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        val actionName = intent.action ?: "WAKEUP"
                        AppLogger.i(TAG, "DeviceSleepWakeReceiver: событие пробуждения ($actionName), запуск восстановления туннеля...")
                        sleepWakeDebounceJob?.cancel()
                        sleepWakeDebounceJob = serviceScope.launch {
                            delay(1500L) // 1.5s дебаунс: даем радиомодулю / Wi-Fi чипу восстановить несущую
                            if (!isActive || !isRunning || tunHolder == null) return@launch
                            val broker = networkBroker ?: return@launch
                            val physical = broker.findActivePhysicalNetwork()
                            if (physical != null) {
                                AppLogger.i(TAG, "DeviceSleepWakeReceiver: активная физическая сеть $physical, обновление underlying networks и сброс внешних сокетов")
                                broker.applyUnderlyingNetworks(physical)
                                try {
                                    NativeProxy.resetNetworkSockets()
                                } catch (e: Exception) {
                                    AppLogger.w(TAG, "DeviceSleepWakeReceiver: сбой сброса сокетов: ${e.message}")
                                }
                            } else {
                                AppLogger.w(TAG, "DeviceSleepWakeReceiver: физическая сеть пока не доступна")
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
        }
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            sleepWakeReceiver = receiver
            AppLogger.i(TAG, "DeviceSleepWakeReceiver успешно зарегистрирован (SCREEN_ON, SCREEN_OFF, IDLE_MODE)")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Не удалось зарегистрировать DeviceSleepWakeReceiver: ${e.message}")
        }
    }

    private fun unregisterSleepWakeReceiver() {
        sleepWakeDebounceJob?.cancel()
        sleepWakeDebounceJob = null
        val receiver = sleepWakeReceiver ?: return
        try {
            unregisterReceiver(receiver)
            AppLogger.i(TAG, "DeviceSleepWakeReceiver отменен")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка отмены регистрации DeviceSleepWakeReceiver: ${e.message}")
        }
        sleepWakeReceiver = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
            ACTION_RESTART -> restartVpn()
            else -> AppLogger.w(TAG, "Неизвестное действие: $action")
        }
        return START_NOT_STICKY
    }

    private fun restartVpn() {
        if (!_vpnStatus.value.isRunning && tunHolder == null) {
            AppLogger.d(TAG, "restartVpn: VPN не запущен, пропуск")
            return
        }
        AppLogger.i(TAG, "Перезапуск VPN-туннеля (ACTION_RESTART)...")
        vpnGeneration.incrementAndGet()
        startJob?.cancel()
        startJob = null
        monitorJob?.cancel()
        monitorJob = null
        updateState(VpnInternalState.PREPARING)

        stopJob?.cancel()
        stopJob = serviceScope.launch(Dispatchers.IO + NonCancellable) {
            synchronized(lifecycleLock) {
                try {
                    NativeProxy.stopVpn()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Ошибка stopVpn() при перезапуске: ${e.message}")
                }
                try {
                    NativeProxy.clearProtectCallback()
                } catch (_: Exception) {}
                try {
                    networkBroker?.stop()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Ошибка networkBroker.stop() при перезапуске: ${e.message}")
                }
                networkBroker = null
                try {
                    VpnSocketProtector.unregister()
                } catch (_: Exception) {}
                try {
                    tunHolder?.closeSafely()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Ошибка closeSafely() при перезапуске: ${e.message}")
                }
                tunHolder = null
            }
            withContext(Dispatchers.Main) {
                startVpn()
            }
        }
    }


    private fun startVpn() {
        if (tunHolder != null && !tunHolder!!.isClosed()) {
            AppLogger.d(TAG, "VPN уже поднят и активен, повторный запуск пропущен")
            updateState(VpnInternalState.RUNNING)
            return
        }

        startJob?.cancel()
        val gen = vpnGeneration.incrementAndGet()
        startTimeMs = System.currentTimeMillis()
        updateState(VpnInternalState.PREPARING)

        val initialNotif = buildVpnNotification("Подготовка защищенного туннеля...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            }
            startForeground(
                NotificationHelper.VPN_NOTIFICATION_ID,
                initialNotif,
                fgsType
            )
        } else {
            startForeground(NotificationHelper.VPN_NOTIFICATION_ID, initialNotif)
        }

        // Регистрация защиты внешних сокетов (Task N04) - СТРОГО ДО любых сетевых вызовов, проб и TUN
        VpnSocketProtector.register(
            onProtectSocket = { socket -> protect(socket) },
            onProtectDatagram = { socket -> protect(socket) },
            onProtectFd = { fd -> protect(fd) }
        )
        NativeProxy.setProtectCallback { fd ->
            val ok = try {
                protect(fd)
            } catch (e: Exception) {
                AppLogger.w(TAG, "protect(fd=$fd) исключение: ${e.message}")
                false
            }
            AppLogger.d(TAG, "NativeProxy: protect(fd=$fd) -> $ok")
            ok
        }

        val previousStop = stopJob
        startJob = serviceScope.launch(Dispatchers.IO) {
            try {
                previousStop?.join()
                if (!isActive || vpnGeneration.get() != gen) return@launch
                val app = MirrlyApplication.instance
                val config = app.config

                // 1. Инициализация параметров Cloudflare WARP (WireGuard AWG)
                // Если профиль не был предварительно сгенерирован (новое устройство или свежая установка),
                // гарантированно создаем и активируем базовый профиль AmneziaWG (Cloudflare Model).
                val activeSavedProfile = app.warpProfileManager.getEffectiveActiveProfile()
                    ?: app.warpProfileManager.ensureDefaultProfile()

                // Проверяем, зарегистрирован ли аккаунт в Cloudflare API.
                // На новых устройствах или при чистой установке, если у пользователя нет своего импортированного профиля,
                // обязательно выполняем онлайн-регистрацию в Cloudflare API для получения легитимного X25519 ключа и IPv4.
                val savedWarpProf = app.prefsManager.getWarpProfile()
                val isAccountActive = savedWarpProf != null && savedWarpProf.isUsable() && !savedWarpProf.isBootstrap() && config.isWarpAccountActive
                val needsAutoReg = !isAccountActive && !app.warpProfileManager.hasUserProfiles()

                val effectiveProfile = if (needsAutoReg) {
                    AppLogger.i(TAG, "VPN: Первичная инициализация устройства: регистрация профиля в Cloudflare Anycast API...")
                    updateState(VpnInternalState.PREPARING, profileName = "Регистрация в Cloudflare...")
                    val regResult = WarpAccountManager.registerAndActivate(fallbackToBootstrap = true)
                    if (regResult.isSuccess) {
                        val newProfile = regResult.getOrThrow()
                        app.prefsManager.saveWarpProfile(newProfile)
                        config.applyWarpProfile(newProfile)
                        app.prefsManager.saveConfig(config)
                        val updated = app.warpProfileManager.updateDefaultProfileWithRegisteredAccount(newProfile)
                        AppLogger.i(TAG, "VPN: Успешная регистрация в Cloudflare! Присвоен IPv4: ${newProfile.clientIpv4}")
                        updated
                    } else {
                        AppLogger.w(TAG, "VPN: Сбой авторегистрации (${regResult.exceptionOrNull()?.message}), использование локального профиля")
                        activeSavedProfile
                    }
                } else {
                    activeSavedProfile
                }

                AppLogger.i(TAG, "VPN: активирован профиль пользователя '${effectiveProfile.name}' (${effectiveProfile.format.title})")
                val parsedResult = com.mirrly.tgproxy.core.WarpProfileParser.parse(effectiveProfile.content)
                if (parsedResult is com.mirrly.tgproxy.core.WarpImportResult.Success) {
                    val userWarpProfile = parsedResult.profile
                    config.applyWarpProfile(userWarpProfile)
                } else {
                    AppLogger.w(TAG, "VPN: не удалось распарсить конфигурацию профиля '${effectiveProfile.name}'")
                }

                if (effectiveProfile.endpoint.isNotBlank()) {
                    config.warpPeerEndpoint = effectiveProfile.endpoint
                    config.warpMeasuredWgEndpoint = effectiveProfile.endpoint
                }
                if (effectiveProfile.format.extension == "conf") {
                    config.awgCustomIni = effectiveProfile.content
                    config.awgStrategyName = com.mirrly.tgproxy.core.AwgObfuscationStrategy.CUSTOM.name
                    config.vpnUplinkMode = com.mirrly.tgproxy.core.UplinkMode.AWG
                }
                app.prefsManager.saveConfig(config)

                // 2. Передаем целевой режим VPN в нативное ядро ПОСЛЕ синхронизации конфигурации с профилем
                NativeProxy.setVpnUplinkMode(config.vpnUplinkMode)
                AppLogger.i(TAG, "VPN: выбран режим аплинка в нативном ядре: ${config.vpnUplinkMode.displayName}")

                if (!isActive || vpnGeneration.get() != gen) return@launch

                // 3. Подготовка параметров Anycast и обфускации для передачи в нативный движок
                val isCustomAwgProfileActive = effectiveProfile.content.isNotBlank() &&
                    effectiveProfile.format.extension == "conf"

                // Анти-блокировка для РФ: раздельный выбор чистых Anycast эндпоинтов ДО поднятия TUN
                val currentAwgEp = if (isCustomAwgProfileActive && effectiveProfile.endpoint.isNotBlank()) {
                    effectiveProfile.endpoint
                } else {
                    config.effectivePeerEndpoint
                }
                val (rawHost, awgPort) = WarpEndpointScanner.parseEndpoint(currentAwgEp)
                val isAwgPortValid = awgPort in listOf(500, 4500, 1701)
                val sticky = WarpEndpointScanner.getStickyProfile()

                val isCloudflareHost = rawHost.contains("cloudflare", ignoreCase = true) ||
                    rawHost.startsWith("162.159.") ||
                    rawHost.startsWith("188.114.") ||
                    WarpEndpointScanner.CLEAN_IPV4_POOL.contains(rawHost)

                // Порт 2408 в РФ блокируется ТСПУ на большинстве провайдеров. Автоматически заменяем его на чистый WireGuard Anycast порт 1701 или 500
                val cleanAwgEp = when {
                    isCustomAwgProfileActive && !isCloudflareHost && awgPort > 0 -> {
                        // Кастомный VPS пользователя: сохраняем указанный эндпоинт и порт полностью
                        currentAwgEp
                    }
                    awgPort == 2408 || awgPort == 0 -> {
                        val host = if (rawHost.isNotBlank()) rawHost else "162.159.193.10"
                        AppLogger.i(TAG, "VPN: порт 2408 заблокирован ТСПУ в РФ, автоматическое переключение на чистый WireGuard Anycast порт $host:1701")
                        "$host:1701"
                    }
                    sticky != null && sticky.port in listOf(500, 4500, 1701) -> sticky.endpoint
                    isAwgPortValid -> currentAwgEp
                    config.warpPeerEndpoint.isNotBlank() && !config.warpPeerEndpoint.endsWith(":2408") && !config.warpPeerEndpoint.endsWith(":0") -> config.warpPeerEndpoint
                    else -> "162.159.193.10:1701"
                }

                val currentMasqueEp = if (config.warpMasquePeerEndpoint.isNotBlank()) config.warpMasquePeerEndpoint else config.warpPeerEndpoint
                val (_, masquePort) = WarpEndpointScanner.parseEndpoint(currentMasqueEp)
                val isMasquePortValid = masquePort in listOf(443, 8095, 8443, 4443)

                val cleanMasqueEp = when {
                    sticky != null && sticky.port in listOf(443, 8095, 8443, 4443) -> sticky.endpoint
                    isMasquePortValid && masquePort != 500 && masquePort != 1701 && masquePort != 4500 && masquePort != 2408 -> currentMasqueEp
                    config.warpMasquePeerEndpoint.isNotBlank() && !config.warpMasquePeerEndpoint.endsWith(":0") && !config.warpMasquePeerEndpoint.endsWith(":2408") -> config.warpMasquePeerEndpoint
                    else -> "188.114.96.1:8095"
                }

                if (!isCustomAwgProfileActive || isCloudflareHost) {
                    if (cleanAwgEp != config.warpPeerEndpoint) {
                        AppLogger.i(TAG, "VPN: применен чистый Anycast эндпоинт для AmneziaWG: $cleanAwgEp")
                        config.warpPeerEndpoint = cleanAwgEp
                        config.warpMeasuredWgEndpoint = cleanAwgEp
                        app.prefsManager.saveConfig(config)
                    }
                }
                if (cleanMasqueEp != config.warpMasquePeerEndpoint) {
                    AppLogger.i(TAG, "VPN: применен чистый Anycast эндпоинт для MASQUE: $cleanMasqueEp")
                    config.warpMasquePeerEndpoint = cleanMasqueEp
                    app.prefsManager.saveConfig(config)
                }

                // Передаем настройки AmneziaWG (если активен кастомный профиль — берем его контент напрямую)
                val awgIni = if (isCustomAwgProfileActive) {
                    AppLogger.i(TAG, "VPN: применен активный профиль '${effectiveProfile.name}' (${effectiveProfile.format.title})")
                    if (isCloudflareHost && effectiveProfile.content.contains(":2408")) {
                        AppLogger.i(TAG, "VPN: замена заблокированного порта 2408 на 1701 в конфигурации .conf")
                        effectiveProfile.content.replace(":2408", ":1701")
                    } else {
                        effectiveProfile.content
                    }
                } else {
                    config.getAmneziaWgConfig(cleanEndpoint = cleanAwgEp)
                }
                NativeProxy.setAwgConfig(awgIni)

                // Передаем настройки MASQUE с гарантированным анти-блокировочным SNI (cdnjs.cloudflare.com)
                val masqueSni = if (config.warpMasqueSni.isNotBlank() && config.warpMasqueSni != "consumer-masque-proxy.cloudflareclient.com") {
                    config.warpMasqueSni
                } else {
                    com.mirrly.tgproxy.service.sni.SniBypassManager.DEFAULT_BYPASS_SNI
                }
                if (config.warpMasqueSni != masqueSni) {
                    config.warpMasqueSni = masqueSni
                    app.prefsManager.saveConfig(config)
                }
                com.mirrly.tgproxy.service.sni.SniBypassManager.setSni(masqueSni)
                NativeProxy.setWarpFullConfig(
                    endpoint = cleanMasqueEp,
                    sni = masqueSni,
                    authToken = config.effectiveMasqueToken,
                    clientIpv4 = config.effectiveMasqueClientIpv4,
                    clientIpv6 = config.effectiveMasqueClientIpv6,
                    p256PrivateKey = config.warpP256PrivateKey,
                    clientCert = config.warpClientCert,
                    peerPublicKey = config.effectiveMasquePeerPublicKey,
                    uriTemplate = config.warpUriTemplate
                )

                if (!isActive || vpnGeneration.get() != gen) {
                    AppLogger.w(TAG, "Старт VPN прерван новым поколением жизненного цикла перед созданием TUN")
                    return@launch
                }

                updateState(VpnInternalState.CONNECTING)

                // Поднятие TUN интерфейса (Tasks N03, N08, N09, N12, N13, N18)
                val splitConfig = VpnTunManager.SplitTunnelConfig(
                    isEnabled = app.prefsManager.isVpnSplitTunnelEnabled(),
                    isAllowlist = app.prefsManager.isVpnSplitTunnelAllowlist(),
                    packageNames = app.prefsManager.getVpnSplitTunnelPackages()
                )

                val effectiveDnsList = if (effectiveProfile.dnsLine.isNotBlank()) {
                    com.mirrly.tgproxy.core.ProxyConfig.parseCustomDnsIps(effectiveProfile.dnsLine)
                } else {
                    config.getEffectiveVpnDnsIpv4()
                }

                val tunResult = VpnTunManager.establish(
                    vpnService = this@MirrlyVpnService,
                    config = config,
                    sessionName = "Mirrly VPN (${config.vpnUplinkMode.displayName})",
                    splitTunnel = splitConfig,
                    customDnsServers = effectiveDnsList
                )

                var startedFdSuccess = false
                synchronized(lifecycleLock) {
                    if (!isActive || vpnGeneration.get() != gen) {
                        AppLogger.w(TAG, "Старт VPN был отменен перед передачей TUN дескриптора")
                        tunResult.onSuccess { holder ->
                            try {
                                holder.closeSafely()
                            } catch (_: Exception) {}
                        }
                        return@launch
                    }

                    tunResult.onSuccess { holder ->
                        tunHolder = holder

                        // Запуск нативного L3 VPN движка в mirrlyengine (Rust)
                        val vpnCode = NativeProxy.startVpn(holder.fd, verbose = false)
                        if (vpnCode != 0 || !isActive || vpnGeneration.get() != gen) {
                            AppLogger.e(TAG, "Сбой старта или отмена нативного L3 VPN движка (code=$vpnCode, genMatch=${vpnGeneration.get() == gen})")
                            try {
                                NativeProxy.stopVpn()
                            } catch (_: Exception) {}
                            try {
                                holder.closeSafely()
                            } catch (_: Exception) {}
                            tunHolder = null
                            if (vpnGeneration.get() == gen) {
                                updateState(VpnInternalState.FAILED, VpnFailureReason.ESTABLISH_FAILED)
                                stopVpn()
                            }
                            return@onSuccess
                        }

                        // Инициализация сетевого брокера физических интерфейсов (Tasks N05, N16)
                        val broker = VpnNetworkBroker(
                            context = this@MirrlyVpnService,
                            vpnService = this@MirrlyVpnService,
                            onNetworkMigrated = { net, nGen ->
                                AppLogger.i(TAG, "Миграция физической сети на $net (поколение: $nGen)")
                                try {
                                    NativeProxy.resetVpnSockets()
                                } catch (e: Exception) {
                                    AppLogger.w(TAG, "Ошибка сброса сетевых сокетов при миграции: ${e.message}")
                                }
                            }
                        )
                        networkBroker = broker
                        broker.start()
                        startedFdSuccess = true
                    }.onFailure { err ->
                        if (vpnGeneration.get() == gen) {
                            AppLogger.e(TAG, "Не удалось поднять TUN: ${err.message}")
                            updateState(VpnInternalState.FAILED, VpnFailureReason.ESTABLISH_FAILED)
                            stopVpn()
                        }
                    }
                }

                if (!startedFdSuccess || !isActive || vpnGeneration.get() != gen) {
                    return@launch
                }

                // Переход в фазу верификации туннеля (Task N02: VERIFYING)
                updateState(
                    internalState = VpnInternalState.VERIFYING,
                    profileName = "${effectiveProfile.name} (${effectiveProfile.format.title})",
                    uplinkMode = config.vpnUplinkMode.name
                )

                // Ожидание и верификация фактического хэндшейка из нативного ядра
                val verifyStartTime = System.currentTimeMillis()
                var isTunnelReady = false
                while (isActive && vpnGeneration.get() == gen && (System.currentTimeMillis() - verifyStartTime) < 30000L) {
                    delay(100L)
                    val vpnJsonStr = NativeProxy.getVpnStatusJson()
                    val json = try { if (vpnJsonStr != null) JSONObject(vpnJsonStr) else null } catch (_: Exception) { null }
                    val isNativeRunning = json?.optBoolean("is_running", true) ?: true
                    val isEstablished = json?.optBoolean("is_established", false) ?: false
                    val lastError = json?.optString("last_error", "") ?: ""
                    val bytesDown = json?.optLong("bytes_down", 0L) ?: 0L

                    if (!isNativeRunning || lastError.isNotBlank()) {
                        AppLogger.e(TAG, "VPN: ошибка нативного туннеля при установлении соединения: $lastError")
                        updateState(VpnInternalState.FAILED, VpnFailureReason.ESTABLISH_FAILED)
                        stopVpn()
                        return@launch
                    }

                    if (isEstablished || bytesDown > 0) {
                        isTunnelReady = true
                        AppLogger.i(TAG, "VPN: нативный хэндшейк подтвержден ядром Rust за ${System.currentTimeMillis() - verifyStartTime} ms")
                        break
                    }
                }

                if (!isTunnelReady || !isActive || vpnGeneration.get() != gen) {
                    if (vpnGeneration.get() == gen) {
                        AppLogger.e(TAG, "VPN: таймаут верификации туннеля (сервер не ответил на Noise Handshake)")
                        updateState(VpnInternalState.FAILED, VpnFailureReason.ESTABLISH_FAILED)
                        stopVpn()
                    }
                    return@launch
                }

                updateState(
                    internalState = VpnInternalState.RUNNING,
                    profileName = "${effectiveProfile.name} (${effectiveProfile.format.title})",
                    uplinkMode = config.vpnUplinkMode.name
                )

                startMetricsMonitor()
                AppLogger.i(TAG, "Нативная L3 VPN-служба успешно прошла верификацию хэндшейка (${effectiveProfile.format.title}) и перешла в состояние RUNNING")
            } catch (e: Exception) {
                if (vpnGeneration.get() == gen) {
                    AppLogger.e(TAG, "Критический сбой инициализации VPN: ${e.message}", e)
                    updateState(VpnInternalState.FAILED, VpnFailureReason.UNKNOWN)
                    stopVpn()
                }
            }
        }
    }

    private fun startMetricsMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            while (isActive && isRunning) {
                delay(1000L)
                val vpnJsonStr = NativeProxy.getVpnStatusJson()
                val json = try { if (vpnJsonStr != null) JSONObject(vpnJsonStr) else null } catch (_: Exception) { null }
                val isNativeRunning = json?.optBoolean("is_running", true) ?: true
                val bytesIn = json?.optLong("bytes_down", 0L) ?: 0L
                val bytesOut = json?.optLong("bytes_up", 0L) ?: 0L
                val pktsIn = json?.optLong("packets_down", 0L) ?: 0L
                val pktsOut = json?.optLong("packets_up", 0L) ?: 0L
                val uptime = System.currentTimeMillis() - startTimeMs

                if (!isNativeRunning && uptime > 3000L) {
                    AppLogger.e(TAG, "Нативное ядро L3 VPN завершило работу или произошел сбой соединения (is_running=false)")
                    updateState(VpnInternalState.FAILED, VpnFailureReason.TUNNEL_LOST)
                    stopVpn()
                    break
                }

                val cur = _vpnStatus.value
                _vpnStatus.value = cur.copy(
                    bytesIn = bytesIn,
                    bytesOut = bytesOut,
                    activeTcpFlows = pktsOut.toInt(),
                    activeUdpSessions = pktsIn.toInt(),
                    uptimeMs = uptime
                )

                val notifText = "↓ ${formatBytes(bytesIn)} • ↑ ${formatBytes(bytesOut)}"
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

        startJob?.cancel()
        startJob = null

        monitorJob?.cancel()
        monitorJob = null

        stopJob?.cancel()
        stopJob = serviceScope.launch(Dispatchers.IO + NonCancellable) {
            synchronized(lifecycleLock) {
                try {
                    NativeProxy.stopVpn()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Ошибка NativeProxy.stopVpn(): ${e.message}")
                }
                try {
                    NativeProxy.clearProtectCallback()
                } catch (_: Exception) {}

                try {
                    networkBroker?.stop()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Ошибка networkBroker.stop(): ${e.message}")
                }
                networkBroker = null

                try {
                    VpnSocketProtector.unregister()
                } catch (_: Exception) {}

                try {
                    tunHolder?.closeSafely()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Ошибка при освобождении TUN: ${e.message}")
                }
                tunHolder = null
            }

            withContext(Dispatchers.Main) {
                updateState(VpnInternalState.IDLE)
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Ошибка stopForeground: ${e.message}")
                }

                stopSelf()
                AppLogger.i(TAG, "MirrlyVpnService успешно и идемпотентно остановлена")
            }
        }
    }

    override fun onRevoke() {
        AppLogger.w(TAG, "Системное разрешение VpnService отозвано системой/пользователем (Task N02)")
        updateState(VpnInternalState.FAILED, VpnFailureReason.REVOKED)
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        unregisterSleepWakeReceiver()
        stopVpn()
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
