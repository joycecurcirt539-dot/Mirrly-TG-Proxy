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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.BuiltinVpnServers
import com.mirrly.tgproxy.core.NativeProxy
import com.mirrly.tgproxy.core.VpnSocketProtector
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
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Промышленная системная служба VpnService (Tasks N01–N20).
 * Объединяет L3/L4 userspace стек TUN, защиту внешних сокетов от петель,
 * привязку к физической сети, системный DNS и идемпотентный жизненный цикл.
 */
class MirrlyVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Выделенный исполнитель потоков с увеличенным размером стека 10 МБ (0xA00000 = 10_485_760L байт).
     * Реализован строго по эталону Cloudflare WARP (com.cloudflare.increased_stack_size.*)
     * для исключения StackOverflowError при глубокой рекурсии Tokio, криптографии Kyber-768/ML-KEM и UniFFI.
     */
    private val increasedStackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "cf-warp-increased-stack", 10_485_760L) // 10 МБ
    }
    private val vpnDispatcher = increasedStackExecutor.asCoroutineDispatcher()
    private val isResetting = AtomicBoolean(false)
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
    @Volatile
    private var isScreenOn: Boolean = true
    private val lifecycleLock = Any()

    companion object {
        private const val TAG = "MirrlyVPN"
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
            try {
                VpnTileService.requestSync(MirrlyApplication.instance)
            } catch (_: Throwable) {}
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
                Log.w(TAG, "VPN [Protect]: protect(fd=$fd) исключение: ${e.message}")
                AppLogger.w(TAG, "VPN [Protect]: protect(fd=$fd) исключение: ${e.message}")
                false
            }
            Log.i(TAG, "VPN [Protect]: protect_socket_fd(fd=$fd) -> $ok")
            AppLogger.i(TAG, "VPN [Protect]: protect_socket_fd(fd=$fd) -> $ok")
            ok
        }
        registerSleepWakeReceiver()
        AppLogger.i(TAG, "Служба MirrlyVpnService инициализирована с защитой сокетов и приемником сна/пробуждения")
    }

    /**
     * Регистрация приемника событий сна и пробуждения устройства (Cloudflare DeviceSleepWakeReceiver Model).
     * Отслеживает SCREEN_ON, SCREEN_OFF и выход из Doze-режима (ACTION_DEVICE_IDLE_MODE_CHANGED).
     * При пробуждении перезапрашивает физическую сеть и сбрасывает устаревшие NAT-сессии WireGuard/AWG.
     */
    private fun registerSleepWakeReceiver() {
        if (sleepWakeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        AppLogger.d(TAG, "DeviceSleepWakeReceiver: экран выключен, устройство переходит в энергосбережение")
                    }
                    Intent.ACTION_SCREEN_ON, PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        isScreenOn = true
                        val actionName = intent.action ?: "WAKEUP"
                        AppLogger.i(TAG, "DeviceSleepWakeReceiver: событие пробуждения ($actionName), запуск восстановления туннеля...")
                        if (isRunning) {
                            NativeProxy.onScreenWakeup()
                        }
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
                                    NativeProxy.notifyVpnReconnect()
                                } catch (e: Exception) {
                                    AppLogger.w(TAG, "DeviceSleepWakeReceiver: сбой сброса VPN сокетов: ${e.message}")
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
        managedResetTunnel()
    }

    /**
     * Конечный автомат перезапуска туннеля (Cloudflare managedResetTunnel Model).
     * Обеспечивает защиту от дребезга и повторного входа (AtomicBoolean isResetting),
     * выполняет деактивацию нативного ядра и добавляет защитную паузу 5000 мс перед
     * повторным подключением к Anycast-узлам для предотвращения спама пакетами.
     */
    private fun managedResetTunnel() {
        if (!_vpnStatus.value.isRunning && tunHolder == null) {
            AppLogger.d(TAG, "managedResetTunnel: VPN не запущен, пропуск")
            return
        }
        if (!isResetting.compareAndSet(false, true)) {
            AppLogger.w(TAG, "managedResetTunnel: перезапуск уже выполняется (isResetting=true), повторный запрос отклонен")
            return
        }

        AppLogger.i(TAG, "Перезапуск VPN-туннеля (ACTION_RESTART / managedResetTunnel)...")
        vpnGeneration.incrementAndGet()
        startJob?.cancel()
        startJob = null
        monitorJob?.cancel()
        monitorJob = null
        updateState(VpnInternalState.PREPARING)

        stopJob?.cancel()
        stopJob = serviceScope.launch(Dispatchers.IO + NonCancellable) {
            try {
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

                // Быстрая пауза 300 мс: даёт ОС время закрыть сетевые сокеты и освободить ресурсы
                AppLogger.i(TAG, "managedResetTunnel: сброс туннеля и подготовка новой сессии...")
                delay(300L)

                // Очищаем статус и счетчики старой сессии
                _vpnStatus.value = _vpnStatus.value.copy(
                    bytesIn = 0L,
                    bytesOut = 0L,
                    activeTcpFlows = 0,
                    activeUdpSessions = 0,
                    uptimeMs = 0L
                )

                isResetting.set(false)

                withContext(Dispatchers.Main) {
                    startVpn()
                }
            } finally {
                isResetting.set(false)
            }
        }
    }


    private fun startVpn() {
        ProxyResurrectionManager.cancel(this)
        if (tunHolder != null && !tunHolder!!.isClosed()) {
            AppLogger.d(TAG, "VPN уже поднят и активен, повторный запуск пропущен")
            updateState(VpnInternalState.RUNNING)
            return
        }

        startTimeMs = System.currentTimeMillis()
        _vpnStatus.value = _vpnStatus.value.copy(
            bytesIn = 0L,
            bytesOut = 0L,
            activeTcpFlows = 0,
            activeUdpSessions = 0,
            uptimeMs = 0L
        )

        val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val signatureStatus = com.mirrly.tgproxy.util.SignatureVerifier.verify(applicationContext)
        if (!isDebug && (packageName != "com.mirrly.tgproxy" ||
                signatureStatus == com.mirrly.tgproxy.util.SignatureStatus.UNOFFICIAL_MODIFIED)) {
            updateState(VpnInternalState.FAILED, VpnFailureReason.ESTABLISH_FAILED)
            stopSelf()
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
                Log.w(TAG, "VPN [Protect]: protect(fd=$fd) исключение: ${e.message}")
                AppLogger.w(TAG, "VPN [Protect]: protect(fd=$fd) исключение: ${e.message}")
                false
            }
            Log.i(TAG, "VPN [Protect]: protect_socket_fd(fd=$fd) -> $ok")
            AppLogger.i(TAG, "VPN [Protect]: protect_socket_fd(fd=$fd) -> $ok")
            ok
        }

        val previousStop = stopJob
        startJob = serviceScope.launch(vpnDispatcher) {
            try {
                previousStop?.join()
                if (!isActive || vpnGeneration.get() != gen) return@launch
                val app = MirrlyApplication.instance
                val config = app.config
                config.isVpnTlsEnabled = app.prefsManager.isVpnTlsEnabled()
                NativeProxy.setVpnTlsEnabled(config.isVpnTlsEnabled)

                // 1. Инициализация параметров Cloudflare WARP (WireGuard AWG)
                // Автовыбор: если включен ID_AUTO и в кэше менеджера еще нет замеров пинга,
                // выполняем быстрый параллельный замер Anycast/EU узлов для выбора наилучшего
                if (app.prefsManager.getSelectedBuiltinServerId() == BuiltinVpnServers.ID_AUTO &&
                    !app.prefsManager.isVpnUseCustomProfile() &&
                    app.warpProfileManager.getAllServerPings().isEmpty()
                ) {
                    try {
                        val candidates = listOf(
                            BuiltinVpnServers.SERVER_BUILTIN_DEFAULT,
                            BuiltinVpnServers.SERVER_BUILTIN_NL,
                            BuiltinVpnServers.SERVER_BUILTIN_DE,
                            BuiltinVpnServers.SERVER_BUILTIN_FI
                        )
                        val endpointsCsv = candidates.joinToString(",") { it.endpoint }
                        val probeJson = NativeProxy.probeWarpEndpoints(endpointsCsv, protocol = 0, timeoutMs = 700)
                        val arr = org.json.JSONArray(probeJson)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val ep = obj.optString("endpoint", "")
                            val rtt = obj.optLong("rtt_ms", -1L)
                            if (ep.isNotBlank() && rtt > 0L) {
                                candidates.find { it.endpoint == ep }?.let {
                                    app.warpProfileManager.recordServerPing(it.id, rtt)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Если профиль не был предварительно сгенерирован (новое устройство или свежая установка),
                // гарантированно создаем и активируем базовый профиль AmneziaWG (Cloudflare Model).
                val activeSavedProfile = app.warpProfileManager.getEffectiveActiveProfile()
                    ?: app.warpProfileManager.ensureDefaultProfile()

                val effectiveProfile = activeSavedProfile

                val isEffectiveBuiltin = effectiveProfile.origin == WarpProfileOrigin.BUILTIN
                val isAuto = isEffectiveBuiltin && app.prefsManager.getSelectedBuiltinServerId() == BuiltinVpnServers.ID_AUTO && !app.prefsManager.isVpnUseCustomProfile()

                var rawCleanName = effectiveProfile.name.trim()
                rawCleanName = rawCleanName.replace(Regex("""\s*\((?:AmneziaWG|AWG|Clash|WireGuard)\)""", RegexOption.IGNORE_CASE), "").trim()
                rawCleanName = rawCleanName.replace(Regex("""^Авто\s*\((.*)\)$"""), "$1").trim()

                val cleanProfileName = if (isAuto) {
                    "Автовыбор — $rawCleanName"
                } else {
                    rawCleanName
                }

                AppLogger.i(TAG, "VPN: активирован профиль пользователя '$cleanProfileName' (${effectiveProfile.format.title})")
                val parsedResult = com.mirrly.tgproxy.core.WarpProfileParser.parse(effectiveProfile.content)
                if (parsedResult is com.mirrly.tgproxy.core.WarpImportResult.Success) {
                    val userWarpProfile = parsedResult.profile
                    config.applyWarpProfile(userWarpProfile, overrideDns = !isEffectiveBuiltin)
                } else {
                    AppLogger.w(TAG, "VPN: не удалось распарсить конфигурацию профиля '${effectiveProfile.name}'")
                }

                if (isEffectiveBuiltin) {
                    // Для встроенных серверов AmneziaWG применяем DNS-серверы, настроенные пользователем
                    // (Cloudflare, Comss.one SmartDNS, Google, Quad9 или пользовательский DNS)
                    config.vpnDnsModeName = app.prefsManager.getVpnDnsMode().name
                    config.vpnCustomDnsIps = app.prefsManager.getVpnCustomDnsIps()
                    AppLogger.i(TAG, "VPN [DNS]: Для встроенного сервера применен DNS-режим '${config.vpnDnsMode.name}' (${config.getEffectiveVpnDnsIpv4().joinToString(", ")})")
                }

                val ext = effectiveProfile.format.extension.lowercase()
                val isYamlProfile = effectiveProfile.format == com.mirrly.tgproxy.core.ImportedProfileType.CLASH_META_YAML || ext == "yaml"
                val isCustomAwgProfileActive = effectiveProfile.content.isNotBlank() && ext == "conf"

                if (isCustomAwgProfileActive) {
                    var rawIni = effectiveProfile.content
                    if (isEffectiveBuiltin) {
                        val dnsList = config.getEffectiveVpnDnsIpv4()
                        if (dnsList.isNotEmpty()) {
                            rawIni = com.mirrly.tgproxy.core.ProxyConfig.replaceDnsInAwgIni(rawIni, dnsList)
                        }
                    }
                    config.awgCustomIni = rawIni
                    config.awgStrategyName = com.mirrly.tgproxy.core.AwgObfuscationStrategy.CUSTOM.name
                }

                if (effectiveProfile.endpoint.isNotBlank()) {
                    config.warpPeerEndpoint = effectiveProfile.endpoint
                    if (config.warpMeasuredWgEndpoint.isBlank()) {
                        config.warpMeasuredWgEndpoint = effectiveProfile.endpoint
                    }
                }

                config.vpnUplinkMode = app.prefsManager.getVpnUplinkMode()
                app.prefsManager.saveConfig(config)

                // 2. Передаем целевой режим VPN в нативное ядро ПОСЛЕ синхронизации конфигурации с профилем
                NativeProxy.setVpnUplinkMode(config.vpnUplinkMode)
                AppLogger.i(TAG, "VPN: выбран режим аплинка в нативном ядре: ${config.vpnUplinkMode.displayName}")

                if (!isActive || vpnGeneration.get() != gen) return@launch

                // 3. Подготовка параметров Anycast и обфускации для передачи в нативный движок

                // Анти-блокировка для РФ: раздельный выбор чистых Anycast эндпоинтов ДО поднятия TUN без искажения портов
                val currentAwgEp = when {
                    isCustomAwgProfileActive && effectiveProfile.endpoint.isNotBlank() -> effectiveProfile.endpoint
                    config.warpUserEndpointOverride.isNotBlank() -> config.warpUserEndpointOverride
                    config.warpMeasuredWgEndpoint.isNotBlank() -> config.warpMeasuredWgEndpoint
                    config.warpPeerEndpoint.isNotBlank() -> config.warpPeerEndpoint
                    else -> config.effectivePeerEndpoint
                }
                val (rawHost, awgPort) = com.mirrly.tgproxy.core.ProxyConfig.parseEndpoint(currentAwgEp)
                val isAwgPortValid = awgPort in 1..65535

                val isCloudflareHost = rawHost.contains("cloudflare", ignoreCase = true) ||
                    rawHost.startsWith("162.159.") ||
                    rawHost.startsWith("188.114.")

                // Анти-блокировка для РФ: раздельный выбор чистых Anycast эндпоинтов ДО поднятия TUN
                val cleanAwgEp = when {
                    isCustomAwgProfileActive && effectiveProfile.endpoint.isNotBlank() && isAwgPortValid -> effectiveProfile.endpoint
                    config.warpUserEndpointOverride.isNotBlank() -> config.warpUserEndpointOverride
                    isCustomAwgProfileActive && !isCloudflareHost && isAwgPortValid -> {
                        // Кастомный VPS пользователя: сохраняем указанный эндпоинт и порт полностью
                        currentAwgEp
                    }
                    isAwgPortValid -> currentAwgEp
                    else -> {
                        val host = if (rawHost.isNotBlank()) rawHost else "162.159.192.1"
                        AppLogger.i(TAG, "VPN: порт $awgPort не указан или некорректен, переключение на дефолтный порт $host:4500")
                        "$host:4500"
                    }
                }

                if (!isCustomAwgProfileActive) {
                    if (config.warpPeerEndpoint.isBlank()) {
                        AppLogger.i(TAG, "VPN: применен чистый Anycast эндпоинт для AmneziaWG: $cleanAwgEp")
                        config.warpPeerEndpoint = cleanAwgEp
                        config.warpMeasuredWgEndpoint = cleanAwgEp
                        app.prefsManager.saveConfig(config)
                    } else if (config.warpMeasuredWgEndpoint != cleanAwgEp && config.warpUserEndpointOverride.isBlank()) {
                        config.warpMeasuredWgEndpoint = cleanAwgEp
                        app.prefsManager.saveConfig(config)
                    }
                }

                val activeEp = cleanAwgEp
                val epLog = "VPN [${config.vpnUplinkMode.name}]: Выбранный эндпоинт: $activeEp (профиль: '${effectiveProfile.name}', формат: ${effectiveProfile.format.title})"
                Log.i(TAG, epLog)
                AppLogger.i(TAG, epLog)

                // Передаем настройки AmneziaWG (если активен кастомный профиль — синхронизируем в нем DNS с выбранным режимом)
                val effectiveDnsString = config.getEffectiveVpnDnsIpv4().joinToString(", ")
                val awgIni = if (isYamlProfile && parsedResult is com.mirrly.tgproxy.core.WarpImportResult.Success) {
                    val up = parsedResult.profile
                    val reservedVal = if (up.reserved.isNotBlank()) up.reserved else up.clientId
                    val reservedPart = if (reservedVal.isNotBlank()) "Reserved = $reservedVal\n" else ""
                    val i1Part = if (up.awgI1.isNotBlank()) "I1 = ${up.awgI1}\n" else ""
                    val i2Part = if (up.awgI2.isNotBlank()) "I2 = ${up.awgI2}\n" else ""
                    val addr = if (up.clientIpv6.isNotBlank()) "${up.clientIpv4}/32, ${up.clientIpv6}/128" else "${up.clientIpv4}/32"
                    AppLogger.i(TAG, "VPN: генерация AmneziaWG параметров из Clash YAML профиля (Jc=${up.awgJc}, Jmin=${up.awgJmin}, Jmax=${up.awgJmax})")
                    """
                    [Interface]
                    PrivateKey = ${up.privateKeyBase64}
                    Address = $addr
                    ${reservedPart}${i1Part}${i2Part}DNS = $effectiveDnsString
                    MTU = 1280
                    Jc = ${up.awgJc}
                    Jmin = ${up.awgJmin}
                    Jmax = ${up.awgJmax}
                    S1 = ${up.awgS1}
                    S2 = ${up.awgS2}
                    H1 = ${up.awgH1}
                    H2 = ${up.awgH2}
                    H3 = ${up.awgH3}
                    H4 = ${up.awgH4}
                    [Peer]
                    PublicKey = ${up.peerPublicKey.ifBlank { "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=" }}
                    AllowedIPs = 0.0.0.0/0, ::/0
                    Endpoint = $cleanAwgEp
                    PersistentKeepalive = 25
                    """.trimIndent()
                } else if (isCustomAwgProfileActive) {
                    AppLogger.i(TAG, "VPN: применен активный профиль '${effectiveProfile.name}' (${effectiveProfile.format.title})")
                    var baseIni = effectiveProfile.content
                    if (baseIni.contains(Regex("""(?i)^DNS\s*=.*$""", RegexOption.MULTILINE))) {
                        baseIni.replace(Regex("""(?i)^DNS\s*=.*$""", RegexOption.MULTILINE), "DNS = $effectiveDnsString")
                    } else {
                        baseIni
                    }
                } else {
                    config.getAmneziaWgConfig(cleanEndpoint = cleanAwgEp)
                }
                val awgLoaded = NativeProxy.setAwgConfig(awgIni)
                if (config.vpnUplinkMode == com.mirrly.tgproxy.core.UplinkMode.AWG && !awgLoaded) {
                    AppLogger.e(TAG, "VPN: нативный движок отклонил конфигурацию AmneziaWG! Остановка запуска.")
                    updateState(VpnInternalState.FAILED, VpnFailureReason.PROFILE_INVALID)
                    stopVpn()
                    return@launch
                }

                
                if (!isActive || vpnGeneration.get() != gen) {
                    AppLogger.w(TAG, "Старт VPN прерван новым поколением жизненного цикла перед созданием TUN")
                    return@launch
                }

                updateState(VpnInternalState.CONNECTING)

                // 2. Создание и запуск VpnNetworkBroker СТРОГО ДО создания TUN и передачи FD в Rust (Cloudflare Life-Cycle Order):
                // Привязываем физическую сеть (Wi-Fi/Cellular) к VpnService через setUnderlyingNetworks
                val broker = VpnNetworkBroker(
                    context = this@MirrlyVpnService,
                    vpnService = this@MirrlyVpnService
                )
                networkBroker = broker
                broker.start()
                val physicalNetwork = broker.findActivePhysicalNetwork()
                AppLogger.i(TAG, "VPN [LifeCycle]: Сетевой брокер запущен, физическая сеть: $physicalNetwork. setUnderlyingNetworks установлен до TUN.")

                // 3. Поднятие TUN интерфейса через VpnTunManager.establish (Tasks N03, N08, N09, N12, N13, N18)
                val splitConfig = VpnTunManager.SplitTunnelConfig(
                    isEnabled = app.prefsManager.isVpnSplitTunnelEnabled(),
                    isAllowlist = app.prefsManager.isVpnSplitTunnelAllowlist(),
                    packageNames = app.prefsManager.getVpnSplitTunnelPackages()
                )

                val effectiveDnsList = config.getEffectiveVpnDnsIpv4()
                val profileMtu = Regex("""(?i)^MTU\s*=\s*(\d+)""", RegexOption.MULTILINE)
                    .find(awgIni)?.groupValues?.get(1)?.toIntOrNull()

                val tunResult = VpnTunManager.establish(
                    vpnService = this@MirrlyVpnService,
                    config = config,
                    sessionName = "Mirrly VPN (${config.vpnUplinkMode.displayName})",
                    splitTunnel = splitConfig,
                    customDnsServers = effectiveDnsList,
                    explicitMtu = profileMtu
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
                        try {
                            broker.stop()
                        } catch (_: Exception) {}
                        networkBroker = null
                        return@launch
                    }

                    tunResult.onSuccess { holder ->
                        tunHolder = holder

                        // 4. Синхронизация состояния и передача файлового дескриптора в нативный L3 VPN движок mirrlyvpn (Rust)
                        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                        val physCaps = physicalNetwork?.let { cm?.getNetworkCapabilities(it) }
                        val isMobilePhysical = physCaps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true ||
                            app.prefsManager.isForceMobileModeEnabled()
                        val syncProfileJson = app.proxyServer.effectiveNetworkProfile
                            .withGeneration(gen)
                            .toJsonString()
                        NativeProxy.syncVpnRuntimeState(
                            tlsEnabled = config.isVpnTlsEnabled,
                            networkGeneration = gen,
                            networkProfileJson = syncProfileJson,
                            isMobile = isMobilePhysical,
                            qosLevel = app.proxyServer.qosEngine.currentThrottleLevel,
                            socketBufferBytes = config.bufferSizeBytes
                        )
                        val vpnCode = NativeProxy.startVpn(holder.fd, verbose = false)
                        if (vpnCode != 0 || !isActive || vpnGeneration.get() != gen) {
                            AppLogger.e(TAG, "Сбой старта или отмена нативного L3 VPN движка (code=$vpnCode, genMatch=${vpnGeneration.get() == gen})")
                            try {
                                NativeProxy.stopVpn()
                            } catch (_: Exception) {}
                            try {
                                broker.stop()
                            } catch (_: Exception) {}
                            networkBroker = null
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

                        // 5. Привязка сетевого брокера к коллбэкам миграции физической сети
                        broker.onNetworkMigrated = { net, nGen ->
                            AppLogger.i(TAG, "Миграция физической сети на $net (поколение: $nGen)")
                            try {
                                NativeProxy.notifyVpnReconnect()
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Ошибка сброса сетевых сокетов при миграции: ${e.message}")
                            }
                        }
                        startedFdSuccess = true
                    }.onFailure { err ->
                        try {
                            broker.stop()
                        } catch (_: Exception) {}
                        networkBroker = null
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
                    profileName = cleanProfileName,
                    uplinkMode = config.vpnUplinkMode.name
                )

                // Ожидание и верификация фактического хэндшейка из нативного ядра
                val verifyStartTime = System.currentTimeMillis()
                var isTunnelReady = false
                var handshakeLogged = false
                var firstPacketLogged = false
                var tenSecDiagLogged = false

                while (isActive && vpnGeneration.get() == gen && (System.currentTimeMillis() - verifyStartTime) < 30000L) {
                    delay(100L)
                    val elapsed = System.currentTimeMillis() - verifyStartTime
                    val vpnJsonStr = NativeProxy.getVpnStatusJson()
                    val json = try { if (vpnJsonStr != null) JSONObject(vpnJsonStr) else null } catch (_: Exception) { null }
                    val isNativeRunning = json?.optBoolean("is_running", true) ?: true
                    val isEstablished = json?.optBoolean("is_established", false) ?: false
                    val lastError = json?.optString("last_error", "") ?: ""
                    val bytesDown = json?.optLong("bytes_down", 0L) ?: 0L

                    if (isEstablished && !handshakeLogged) {
                        handshakeLogged = true
                        val msg = when (config.vpnUplinkMode) {
                            
                            com.mirrly.tgproxy.core.UplinkMode.AWG ->
                                "VPN [AWG]: Хэндшейк WireGuard/AWG успешно подтвержден (is_established=true) за $elapsed ms"
                            else ->
                                "VPN [${config.vpnUplinkMode.name}]: Сессия туннеля установлена (is_established=true) за $elapsed ms"
                        }
                        Log.i(TAG, msg)
                        AppLogger.i(TAG, msg)
                    }

                    if (bytesDown > 0 && !firstPacketLogged) {
                        firstPacketLogged = true
                        val msg = when (config.vpnUplinkMode) {
                            
                            com.mirrly.tgproxy.core.UplinkMode.AWG ->
                                "VPN [AWG]: Первый входящий IP-пакет WireGuard принят за $elapsed ms (bytes_down=$bytesDown)"
                            else ->
                                "VPN [${config.vpnUplinkMode.name}]: Первый входящий пакет получен за $elapsed ms (bytes_down=$bytesDown)"
                        }
                        Log.i(TAG, msg)
                        AppLogger.i(TAG, msg)
                    }

                    if (!tenSecDiagLogged && elapsed >= 10000L && !isEstablished && bytesDown == 0L) {
                        tenSecDiagLogged = true
                        val initSent = json?.optLong("awg_init_sent", 0L) ?: 0L
                        val respRecv = json?.optLong("awg_resp_received", 0L) ?: 0L
                        val cbOpen = json?.optBoolean("circuit_breaker_open", false) ?: false
                        val cbFailures = json?.optInt("circuit_breaker_failures", 0) ?: 0
                        val awgEp = json?.optString("awg_endpoint", cleanAwgEp) ?: cleanAwgEp
                        val diagMsg = "VPN [AWG Diagnostic]: Хэндшейк WireGuard/AWG не проходит за 10 секунд! " +
                            "Эндпоинт: $awgEp, init_sent=$initSent, resp_received=$respRecv, bytes_down=$bytesDown, " +
                            "circuit_breaker_open=$cbOpen, cb_failures=$cbFailures, last_error='$lastError'. " +
                            "Возможна блокировка UDP-портов провайдером или ТСПУ."
                        Log.w(TAG, diagMsg)
                        AppLogger.w(TAG, diagMsg)
                    }

                    if (!isNativeRunning || lastError.isNotBlank()) {
                        val errMsg = "VPN: ошибка нативного туннеля при установлении соединения: $lastError"
                        Log.e(TAG, errMsg)
                        AppLogger.e(TAG, errMsg)
                        updateState(VpnInternalState.FAILED, VpnFailureReason.ESTABLISH_FAILED)
                        stopVpn()
                        return@launch
                    }

                    if (isEstablished || bytesDown > 0) {
                        isTunnelReady = true
                        val readyMsg = when (config.vpnUplinkMode) {
                            
                            com.mirrly.tgproxy.core.UplinkMode.AWG ->
                                "VPN [AWG]: Нативный хэндшейк WireGuard/AWG подтвержден ядром Rust за $elapsed ms"
                            else ->
                                "VPN [${config.vpnUplinkMode.name}]: Нативный хэндшейк подтвержден ядром Rust за $elapsed ms"
                        }
                        Log.i(TAG, readyMsg)
                        AppLogger.i(TAG, readyMsg)
                        break
                    }
                }

                if (!isTunnelReady || !isActive || vpnGeneration.get() != gen) {
                    if (vpnGeneration.get() == gen) {
                        val (timeoutLog, failReasonDesc) = when (config.vpnUplinkMode) {
                            com.mirrly.tgproxy.core.UplinkMode.AWG -> Pair(
                                "VPN: таймаут верификации туннеля (сервер не ответил на Noise Handshake). Рекомендуется сменить порт или эндпоинт.",
                                "Таймаут WireGuard: проверьте доступность сети"
                            )
                            else -> Pair(
                                "VPN: таймаут верификации туннеля (сервер не ответил на сетевой хэндшейк).",
                                "Таймаут сетевого хэндшейка"
                            )
                        }
                        AppLogger.e(TAG, timeoutLog)
                        updateState(
                            VpnInternalState.FAILED,
                            reason = VpnFailureReason.ESTABLISH_FAILED,
                            profileName = failReasonDesc
                        )
                        stopVpn()
                    }
                    return@launch
                }

                updateState(
                    internalState = VpnInternalState.RUNNING,
                    profileName = cleanProfileName,
                    uplinkMode = config.vpnUplinkMode.name
                )

                startMetricsMonitor()
                ProxyResurrectionManager.startWatchdog(this@MirrlyVpnService)
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
            var lastNotifText = ""
            var lastNotifiedAtMs = 0L

            while (isActive && isRunning) {
                // Посекундный тик: 1000 мс при включенном экране для плавного счетчика времени,
                // 10000 мс в фоне для сбережения батареи
                val pollInterval = if (isScreenOn) 1000L else 10000L
                delay(pollInterval)
                if (!isActive || !isRunning) break

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

                // IPC Throttler: Dispatch NotificationManager Binder IPC only when screen is on,
                // text changed, and at least 2 seconds have elapsed to avoid SystemUI layout churn.
                val nowMs = System.currentTimeMillis()
                if (isScreenOn && (nowMs - lastNotifiedAtMs >= 2000L)) {
                    val notifText = "↓ ${formatBytes(bytesIn)} • ↑ ${formatBytes(bytesOut)}"
                    if (notifText != lastNotifText) {
                        lastNotifText = notifText
                        lastNotifiedAtMs = nowMs
                        val notif = buildVpnNotification(notifText)
                        try {
                            nm.notify(NotificationHelper.VPN_NOTIFICATION_ID, notif)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun stopVpn() {
        ProxyResurrectionManager.cancel(this)
        isResetting.set(false)
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

            // Очищаем счетчики и метрики завершенной сессии
            _vpnStatus.value = _vpnStatus.value.copy(
                bytesIn = 0L,
                bytesOut = 0L,
                activeTcpFlows = 0,
                activeUdpSessions = 0,
                uptimeMs = 0L
            )

            // updateState доступен из любого потока (StateFlow thread-safe).
            // Не используем withContext(Dispatchers.Main) чтобы не упасть в CancellationException
            // если serviceScope уже отменён в onDestroy.
            updateState(VpnInternalState.IDLE)
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Ошибка stopForeground: ${e.message}")
            }
            try {
                stopSelf()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Ошибка stopSelf: ${e.message}")
            }
            AppLogger.i(TAG, "MirrlyVpnService успешно и идемпотентно остановлена")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val app = MirrlyApplication.instance
        val isVpnActive = app.prefsManager.isVpnModeEnabled() && isRunning
        AppLogger.w(TAG, "onTaskRemoved: VPN task removed from recents (isVpnActive=$isVpnActive)")
        if (isVpnActive) {
            ProxyResurrectionManager.schedule(this)
        }
    }

    override fun onRevoke() {
        AppLogger.w(TAG, "Системное разрешение VpnService отозвано системой/пользователем (Task N02)")
        updateState(VpnInternalState.FAILED, VpnFailureReason.REVOKED)
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "MirrlyVpnService: onDestroy вызван, выполняется финализация ресурсов")
        unregisterSleepWakeReceiver()
        // Отменяем стартовые и мониторинговые корутины.
        // stopJob уже работает с NonCancellable — повторный stopVpn() создаёт гонку
        // и может зависнуть в STOPPING без перехода в IDLE.
        startJob?.cancel()
        startJob = null
        monitorJob?.cancel()
        monitorJob = null
        isResetting.set(false)
        serviceScope.cancel()
        try {
            vpnDispatcher.close()
        } catch (_: Exception) {}
        try {
            increasedStackExecutor.shutdown()
        } catch (_: Exception) {}
        val app = MirrlyApplication.instance
        if (app.prefsManager.isVpnModeEnabled() && _vpnStatus.value.isRunning) {
            AppLogger.w(TAG, "MirrlyVpnService destroyed by OS while active in preferences. Scheduling resurrection...")
            ProxyResurrectionManager.schedule(this)
        }
        // Гарантируем переход в IDLE для UI вне зависимости от незавершённых корутин
        updateState(VpnInternalState.IDLE)
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
        val profileTitle = current.activeProfileName.trim()
        val title = when {
            profileTitle.startsWith("Mirrly VPN • ") -> profileTitle
            profileTitle.isNotBlank() -> "Mirrly VPN • $profileTitle"
            else -> getString(R.string.vpn_tunnel_title)
        }

        val isVibrationDisabled = try {
            MirrlyApplication.instance.prefsManager.isVibrationDisabled()
        } catch (_: Throwable) {
            false
        }

        val builder = NotificationCompat.Builder(this, NotificationHelper.VPN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(title)
            .setContentText(statusText)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_disconnect),
                stopPendingIntent
            )
        if (isVibrationDisabled) {
            builder.setSilent(true).setVibrate(longArrayOf(0L))
        }
        return builder.build()
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
