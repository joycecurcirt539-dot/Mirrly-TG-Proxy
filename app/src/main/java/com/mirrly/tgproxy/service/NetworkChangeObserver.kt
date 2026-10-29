package com.mirrly.tgproxy.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.mirrly.tgproxy.core.NetworkEnvironment
import com.mirrly.tgproxy.core.NetworkTransport
import java.util.concurrent.atomic.AtomicLong

/**
 * MOB-012: NetworkChangeObserver с явным state machine и generation-stamp.
 *
 * ВАЖНО: onNetworkChanged НИКОГДА не вызывается под stateLock.
 * Все решения принимаются внутри synchronized, результат накапливается в локальных переменных,
 * callback вызывается ПОСЛЕ выхода из synchronized — исключая deadlock с JNI и ANR.
 *
 * State transitions:
 *   UNAVAILABLE → AVAILABLE_UNVALIDATED  (onAvailable — тихо, без уведомления)
 *   AVAILABLE_UNVALIDATED → VALIDATED_STABLE  (onCapabilitiesChanged + VALIDATED + NOT_SUSPENDED)
 *   VALIDATED_STABLE → SUSPENDED  (onCapabilitiesChanged: потеря NOT_SUSPENDED, без ложного offline)
 *   VALIDATED_STABLE / SUSPENDED → UNAVAILABLE  (onLost — debounced disconnect)
 *
 * Принципы:
 * - Уведомление onNetworkChanged вызывается ТОЛЬКО при переходе в VALIDATED_STABLE или UNAVAILABLE.
 * - LinkProperties/capabilities берутся исключительно из колбэков, не из синхронных вызовов.
 * - generationCounter гарантирует, что устаревший pending disconnect не переопишет новое состояние.
 * - Wi-Fi↔LTE handover, кратковременный suspend и captive portal не вызывают double/triple reset.
 */
class NetworkChangeObserver(
    private val context: Context,
    private val onNetworkChanged: (newType: String, oldType: String, isInitial: Boolean) -> Unit,
    private val onNetworkSuspended: (networkType: String) -> Unit = {},
    private val onNetworkResumed: (networkType: String) -> Unit = {},
    private val onNetworkProfileChanged: (NetworkEnvironment) -> Unit = {}
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    enum class NetworkState {
        /** Нет сети или сеть окончательно потеряна. */
        UNAVAILABLE,
        /** onAvailable получен, но VALIDATED/NOT_SUSPENDED ещё не подтверждены. */
        AVAILABLE_UNVALIDATED,
        /** Сеть полностью готова: INTERNET + VALIDATED + NOT_SUSPENDED. */
        VALIDATED_STABLE,
        /** Сеть временно приостановлена (например, роуминг, мобильное радио — горячий suspend). */
        SUSPENDED
    }

    @Volatile
    private var currentState: NetworkState = NetworkState.UNAVAILABLE
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastReportedNetwork: Network? = null
    private var hasReportedUsableNetwork: Boolean = false

    // generation инкрементируется при каждом переходе в UNAVAILABLE/SUSPENDED/HANDOVER
    // для защиты от устаревших pending runnables.
    private val generationCounter = AtomicLong(0L)

    private var pendingDisconnectRunnable: Runnable? = null
    private var pendingHandoverRunnable: Runnable? = null

    companion object {
        private const val WIFI_TO_CELLULAR_HANDOVER_DELAY_MS = 2500L
        private const val DISCONNECT_GRACE_DELAY_MS = 7_000L
    }

    @Volatile
    private var currentDefaultNetwork: Network? = null

    @Volatile
    private var currentCapabilities: NetworkCapabilities? = null

    @Volatile
    private var currentLinkProperties: LinkProperties? = null

    @Volatile
    private var currentNetworkType: String = "DISCONNECTED"

    @Volatile
    private var lastReportedType: String = "DISCONNECTED"

    @Volatile
    private var lastReportedEnvironment: NetworkEnvironment? = null

    // -------------------------------------------------------------------------
    // Helpers (вызываются только внутри synchronized(stateLock))
    // -------------------------------------------------------------------------

    private fun cancelPendingDisconnect() {
        pendingDisconnectRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingDisconnectRunnable = null
            generationCounter.incrementAndGet()
        }
    }

    private fun cancelPendingHandover() {
        pendingHandoverRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingHandoverRunnable = null
            generationCounter.incrementAndGet()
        }
    }

    /**
     * Откладывает переход в UNAVAILABLE на [delayMs].
     * ВАЖНО: вызывается внутри synchronized(stateLock).
     * Runnable при срабатывании собирает notify-параметры, выходит из лока, затем вызывает callback.
     */
    private fun schedulePendingDisconnect(oldType: String, delayMs: Long = 7_000L) {
        // Repeated bad callbacks must neither shorten nor postpone the same outage window.
        if (pendingDisconnectRunnable != null) return
        val expectedGen = generationCounter.incrementAndGet()
        val runnable = Runnable {
            // Параметры для callback — вычисляем под локом, callback — вне лока
            val shouldNotify: Boolean
            synchronized(stateLock) {
                if (generationCounter.get() != expectedGen) return@Runnable
                pendingDisconnectRunnable = null
                cancelPendingHandover()
                currentState = NetworkState.UNAVAILABLE
                currentDefaultNetwork = null
                currentCapabilities = null
                currentLinkProperties = null
                currentNetworkType = "DISCONNECTED"
                lastReportedType = "DISCONNECTED"
                lastReportedNetwork = null
                lastReportedEnvironment = NetworkEnvironment.unavailable()
                shouldNotify = oldType != "DISCONNECTED"
            }
            if (shouldNotify) {
                onNetworkProfileChanged(NetworkEnvironment.unavailable())
                onNetworkChanged("DISCONNECTED", oldType, false)
            }
        }
        pendingDisconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    /** Проверяет, что сеть полностью готова (INTERNET + VALIDATED + NOT_SUSPENDED). */
    private fun hasUsableInternet(caps: NetworkCapabilities): Boolean {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !isVpn
        ) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) && !isVpn
        ) return false
        return true
    }

    /** Проверяет наличие INTERNET + VALIDATED (без NOT_SUSPENDED — для детекции Suspend). */
    private fun isValidated(caps: NetworkCapabilities): Boolean {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !isVpn
        ) return false
        return true
    }

    /** Проверяет флаг NOT_SUSPENDED (только API >= P). */
    private fun isNotSuspended(caps: NetworkCapabilities): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
    }

    fun isWifiActive(): Boolean {
        synchronized(stateLock) {
            val caps = currentCapabilities ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun isForcedMobile(): Boolean = try {
        com.mirrly.tgproxy.MirrlyApplication.instance.prefsManager.isForceMobileModeEnabled()
    } catch (_: Exception) { false }

    private fun isMobileHotspot(lp: LinkProperties?): Boolean {
        if (isForcedMobile()) return true
        val targetLp = lp ?: currentLinkProperties
        val caps = currentCapabilities

        // 1. Android automatically flags mobile hotspots / tethering as METERED
        if (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return true
        }

        if (targetLp != null) {
            val hotspotPrefixes = arrayOf(
                "192.168.43.",  // Android AOSP default
                "192.168.42.",  // USB tethering
                "192.168.44.",  // Alternative tethering
                "192.168.49.",  // Wi-Fi Direct
                "192.168.50.",  // Xiaomi / Redmi tethering
                "192.168.137.", // Windows / Xiaomi tethering
                "192.168.173.", // Xiaomi tethering
                "192.168.225.", // Jio / Spreadtrum / Mediatek tethering (Redmi 9A)
                "192.168.240.", // MediaTek custom tethering
                "192.168.241.", // MediaTek custom tethering
                "192.168.242.", // MediaTek custom tethering
                "172.20.10.",   // iOS Personal Hotspot
                "172.20.",      // Mobile hotspot class B
                "10.42.0."      // Linux network-manager hotspot
            )
            for (addr in targetLp.linkAddresses) {
                val ip = addr.address.hostAddress ?: continue
                if (hotspotPrefixes.any { ip.startsWith(it) }) {
                    return true
                }
            }
            for (route in targetLp.routes) {
                val gw = route.gateway?.hostAddress ?: continue
                if (hotspotPrefixes.any { gw.startsWith(it) }) {
                    return true
                }
            }

            // 2. Cellular operator DNS identification (MTS, MegaFon, Beeline, Tele2, Yota)
            val cellularDnsPrefixes = arrayOf(
                "217.66.",   // MTS IPv4
                "83.149.",   // MegaFon IPv4
                "217.118.",  // Beeline IPv4
                "212.1.",    // Tele2 IPv4
                "109.105."   // Yota IPv4
            )
            for (dns in targetLp.dnsServers) {
                val dnsIp = dns.hostAddress ?: continue
                if (cellularDnsPrefixes.any { dnsIp.startsWith(it) }) {
                    return true
                }
            }

            // 3. Cellular operator IPv6 prefix detection (SLAAC / RA handed out by mobile tethering)
            val cellularIpv6Prefixes = arrayOf(
                "2a02:2698:", // MTS IPv6
                "2a02:269c:", // MTS IPv6
                "2a02:2168:", // MegaFon IPv6
                "2a00:1028:", // Beeline IPv6
                "2a00:1fa"    // Tele2 IPv6
            )
            for (addr in targetLp.linkAddresses) {
                val ip = addr.address.hostAddress?.lowercase() ?: continue
                if (cellularIpv6Prefixes.any { ip.startsWith(it) }) {
                    return true
                }
            }
            for (route in targetLp.routes) {
                val gw = route.gateway?.hostAddress?.lowercase() ?: continue
                if (cellularIpv6Prefixes.any { gw.startsWith(it) }) {
                    return true
                }
            }

            // 4. Tethering and modem virtual interface signatures
            val iface = targetLp.interfaceName?.lowercase() ?: ""
            if (iface.contains("rndis") || iface.contains("p2p") || iface.contains("bt-pan") ||
                iface.contains("swlan") || iface.contains("ap0") || iface.contains("usb") ||
                iface.contains("ncm") || iface.contains("cdc")) {
                return true
            }

            // 5. Operator domain signatures in DHCP options
            val domains = targetLp.domains?.lowercase() ?: ""
            if (domains.contains("mts") || domains.contains("megafon") ||
                domains.contains("beeline") || domains.contains("tele2")) {
                return true
            }
        }
        return false
    }

    private fun extractNetworkTypeName(caps: NetworkCapabilities): String {
        val isHotspot = isMobileHotspot(currentLinkProperties)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                if (isHotspot) "Mobile Hotspot (LTE/5G)" else "Wi-Fi"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile LTE/5G"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> {
                if (isWifiActive()) {
                    if (isHotspot) "Mobile Hotspot (LTE/5G)" else "Wi-Fi"
                } else "VPN"
            }
            else -> "Active Network"
        }
    }

    private fun extractTransport(caps: NetworkCapabilities): NetworkTransport {
        val isHotspot = isMobileHotspot(currentLinkProperties)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                if (isHotspot) NetworkTransport.CELLULAR else NetworkTransport.WIFI
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
            else -> NetworkTransport.OTHER
        }
    }

    private fun buildEnvironment(
        caps: NetworkCapabilities,
        state: NetworkState
    ): NetworkEnvironment {
        val isHotspot = isMobileHotspot(currentLinkProperties)
        val transport = extractTransport(caps)
        val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) || isHotspot
        return NetworkEnvironment(
            validated = isValidated(caps),
            suspended = state == NetworkState.SUSPENDED || !isNotSuspended(caps),
            metered = isMetered,
            roaming = transport == NetworkTransport.CELLULAR &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
            congested = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED),
            transport = transport,
            estimatedDownKbps = ((caps.linkDownstreamBandwidthKbps.coerceAtLeast(0) + 2500) / 5000) * 5000,
            estimatedUpKbps = ((caps.linkUpstreamBandwidthKbps.coerceAtLeast(0) + 2500) / 5000) * 5000
        )
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun start() {
        val cm = connectivityManager ?: return

        networkCallback = object : ConnectivityManager.NetworkCallback() {

            /**
             * Сеть стала доступна — но ещё не VALIDATED.
             * Переходим в AVAILABLE_UNVALIDATED и ждём onCapabilitiesChanged с VALIDATED.
             * НЕ уведомляем onNetworkChanged здесь.
             */
            override fun onAvailable(network: Network) {
                var notifySuspended: String? = null
                synchronized(stateLock) {
                    val hadStableNetwork = lastReportedNetwork != null &&
                        currentState == NetworkState.VALIDATED_STABLE
                    currentDefaultNetwork = network
                    currentState = NetworkState.AVAILABLE_UNVALIDATED

                    // An unvalidated replacement must not cancel the old
                    // generation's drain deadline. Only a usable capabilities
                    // callback is allowed to do that.
                    if (hadStableNetwork && network != lastReportedNetwork) {
                        notifySuspended = lastReportedType
                        schedulePendingDisconnect(lastReportedType, delayMs = DISCONNECT_GRACE_DELAY_MS)
                    }
                }
                notifySuspended?.let(onNetworkSuspended)
            }

            /**
             * Capabilities изменились — основная точка принятия решений.
             *
             * КРИТИЧНО: onNetworkChanged вызывается ПОСЛЕ выхода из synchronized,
             * чтобы исключить deadlock с JNI (NativeProxy.setNetworkGeneration/resetNetworkSockets).
             */
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Переменные для callback — заполняются под локом, callback вызывается вне лока
                var notifyNew: String? = null
                var notifyOld: String? = null
                var notifyInitial = false
                var notifySuspended: String? = null
                var notifyResumed: String? = null
                var profileUpdate: NetworkEnvironment? = null

                synchronized(stateLock) {
                    // A late callback from the previous default network must not
                    // replace the candidate selected by onAvailable().
                    if (currentDefaultNetwork != null && currentDefaultNetwork != network) {
                        return@synchronized
                    }

                    val validated = isValidated(caps)
                    val notSuspended = isNotSuspended(caps)
                    val usable = hasUsableInternet(caps)

                    // Capabilities берём только для текущей или новой сети
                    if (currentDefaultNetwork == null || currentDefaultNetwork == network) {
                        currentDefaultNetwork = network
                        currentCapabilities = caps
                        if (currentLinkProperties == null) {
                            currentLinkProperties = connectivityManager?.getLinkProperties(network)
                        }
                    }

                    when {
                        // Полностью готова: VALIDATED + NOT_SUSPENDED
                        usable -> {
                            cancelPendingDisconnect()

                            val newType = extractNetworkTypeName(caps)
                            val oldState = currentState
                            val oldType = lastReportedType
                            val sameReportedNetwork = lastReportedNetwork == network

                            // Anti-flap protection: если переход с Wi-Fi/Ethernet на сотовую сеть,
                            // подтверждаем смену с задержкой 2500 мс для защиты от кратковременных просадок/роуминга Wi-Fi
                            val isDowngradeToCellular = (oldType == "Wi-Fi" || oldType == "Ethernet") &&
                                (newType == "Mobile LTE/5G" || newType.contains("Mobile") || newType.contains("Cellular"))

                            if (isDowngradeToCellular && hasReportedUsableNetwork) {
                                currentDefaultNetwork = network
                                currentCapabilities = caps
                                currentState = NetworkState.AVAILABLE_UNVALIDATED

                                if (pendingHandoverRunnable == null) {
                                    val expectedGen = generationCounter.incrementAndGet()
                                    val runnable = Runnable {
                                        var delayedNotifyNew: String? = null
                                        var delayedNotifyOld: String? = null
                                        var delayedProfileUpdate: NetworkEnvironment? = null
                                        synchronized(stateLock) {
                                            if (generationCounter.get() != expectedGen) return@Runnable
                                            pendingHandoverRunnable = null
                                            if (currentDefaultNetwork == network) {
                                                currentState = NetworkState.VALIDATED_STABLE
                                                currentNetworkType = newType
                                                lastReportedType = newType
                                                lastReportedNetwork = network
                                                delayedNotifyNew = newType
                                                delayedNotifyOld = oldType
                                                val candidateEnv = buildEnvironment(caps, currentState)
                                                lastReportedEnvironment = candidateEnv
                                                delayedProfileUpdate = candidateEnv
                                            }
                                        }
                                        delayedProfileUpdate?.let(onNetworkProfileChanged)
                                        if (delayedNotifyNew != null && delayedNotifyOld != null) {
                                            onNetworkChanged(delayedNotifyNew!!, delayedNotifyOld!!, false)
                                        }
                                    }
                                    pendingHandoverRunnable = runnable
                                    mainHandler.postDelayed(runnable, WIFI_TO_CELLULAR_HANDOVER_DELAY_MS)
                                }
                            } else {
                                // Сеть Wi-Fi восстановилась или это не downgrade:
                                // немедленно отменяем любой отложенный переход на сотовую сеть
                                cancelPendingHandover()

                                currentState = NetworkState.VALIDATED_STABLE
                                currentDefaultNetwork = network
                                currentNetworkType = newType

                                if (sameReportedNetwork &&
                                    (oldState == NetworkState.SUSPENDED ||
                                        oldState == NetworkState.AVAILABLE_UNVALIDATED)
                                ) {
                                    notifyResumed = newType
                                } else if (!sameReportedNetwork || newType != oldType) {
                                    notifyInitial = !hasReportedUsableNetwork
                                    hasReportedUsableNetwork = true
                                    lastReportedType = newType
                                    lastReportedNetwork = network
                                    // Планируем callback ПОСЛЕ выхода из synchronized
                                    notifyNew = newType
                                    notifyOld = oldType
                                }
                            }
                        }

                        // VALIDATED но SUSPENDED (мобильное радио временно на паузе)
                        validated && !notSuspended -> {
                            if (currentState != NetworkState.SUSPENDED) {
                                currentState = NetworkState.SUSPENDED
                                notifySuspended = lastReportedType
                                schedulePendingDisconnect(lastReportedType, delayMs = 7_000L)
                            }
                        }

                        // Не VALIDATED (captive portal, DHCP pending, etc.)
                        else -> {
                            if (currentState == NetworkState.VALIDATED_STABLE || currentState == NetworkState.SUSPENDED) {
                                val oldType = lastReportedType
                                currentState = NetworkState.AVAILABLE_UNVALIDATED
                                notifySuspended = oldType
                                schedulePendingDisconnect(oldType, delayMs = 7_000L)
                            }
                        }
                    }
                    val candidateEnv = buildEnvironment(caps, currentState)
                    if (candidateEnv != lastReportedEnvironment) {
                        lastReportedEnvironment = candidateEnv
                        profileUpdate = candidateEnv
                    }
                }

                // Вызываем callback СТРОГО вне synchronized — исключаем deadlock с JNI
                profileUpdate?.let(onNetworkProfileChanged)
                if (notifyNew != null && notifyOld != null) {
                    onNetworkChanged(notifyNew!!, notifyOld!!, notifyInitial)
                }
                notifySuspended?.let(onNetworkSuspended)
                notifyResumed?.let(onNetworkResumed)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                var profileUpdate: NetworkEnvironment? = null
                var typeUpdate: Pair<String, String>? = null
                synchronized(stateLock) {
                    if (network == currentDefaultNetwork) {
                        currentLinkProperties = linkProperties
                        currentCapabilities?.let { caps ->
                            val newType = extractNetworkTypeName(caps)
                            if (newType != lastReportedType && lastReportedType != "DISCONNECTED") {
                                val oldType = lastReportedType
                                lastReportedType = newType
                                currentNetworkType = newType
                                typeUpdate = oldType to newType
                            }
                            val candidateEnv = buildEnvironment(caps, currentState)
                            if (candidateEnv != lastReportedEnvironment) {
                                lastReportedEnvironment = candidateEnv
                                profileUpdate = candidateEnv
                            }
                        }
                    }
                }
                typeUpdate?.let { (oldT, newT) ->
                    onNetworkChanged(newT, oldT, false)
                }
                profileUpdate?.let(onNetworkProfileChanged)
            }

            /**
             * Сеть потеряна.
             * Debounced на 7000 мс для Wi-Fi↔LTE handover grace.
             */
            override fun onLost(network: Network) {
                synchronized(stateLock) {
                    if (currentDefaultNetwork != null && network != currentDefaultNetwork) {
                        return // Запоздалый onLost от уже неактивной сети
                    }

                    cancelPendingHandover()
                    val oldType = lastReportedType
                    if (currentState != NetworkState.UNAVAILABLE) {
                        currentState = NetworkState.AVAILABLE_UNVALIDATED
                        schedulePendingDisconnect(oldType, delayMs = DISCONNECT_GRACE_DELAY_MS)
                    }
                }
            }

            override fun onUnavailable() {
                synchronized(stateLock) {
                    cancelPendingHandover()
                    if (currentState != NetworkState.UNAVAILABLE) {
                        val oldType = lastReportedType
                        schedulePendingDisconnect(oldType, delayMs = DISCONNECT_GRACE_DELAY_MS)
                    }
                }
            }
        }

        val cb = networkCallback ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(cb)
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        // 1. Сначала отменяем регистрацию — чтобы новые колбэки не приходили
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null

        // 2. Потом отменяем pending runnables под локом
        synchronized(stateLock) {
            cancelPendingDisconnect()
            cancelPendingHandover()
            currentState = NetworkState.UNAVAILABLE
            currentDefaultNetwork = null
            currentCapabilities = null
            currentLinkProperties = null
            currentNetworkType = "DISCONNECTED"
            lastReportedType = "DISCONNECTED"
            lastReportedNetwork = null
            lastReportedEnvironment = null
            hasReportedUsableNetwork = false
        }
        // onNetworkChanged при stop() НЕ вызывается — сервис сам управляет своим состоянием
    }

    fun getCurrentNetworkTypeName(): String {
        synchronized(stateLock) {
            if (isForcedMobile()) {
                return "Mobile Hotspot (LTE/5G)"
            }
            if (pendingHandoverRunnable != null && (lastReportedType == "Wi-Fi" || lastReportedType == "Ethernet")) {
                return lastReportedType
            }
            if (currentState == NetworkState.VALIDATED_STABLE && currentNetworkType != "DISCONNECTED") {
                return currentNetworkType
            }
            return if (lastReportedType != "DISCONNECTED") lastReportedType else currentNetworkType
        }
    }

    fun getCurrentCapabilities(): NetworkCapabilities? = currentCapabilities

    fun getCurrentLinkProperties(): LinkProperties? = currentLinkProperties

    fun getCurrentState(): NetworkState = currentState

    fun refreshNetworkProfile() {
        var envToNotify: NetworkEnvironment? = null
        var newT: String? = null
        var oldT: String? = null
        synchronized(stateLock) {
            val caps = currentCapabilities ?: return
            oldT = currentNetworkType
            newT = extractNetworkTypeName(caps)
            currentNetworkType = newT!!
            lastReportedType = newT!!
            val env = buildEnvironment(caps, currentState)
            lastReportedEnvironment = env
            envToNotify = env
        }
        val env = envToNotify ?: return
        val newTypeName = newT ?: return
        val oldTypeName = oldT ?: "UNKNOWN"
        mainHandler.post {
            onNetworkProfileChanged(env)
            onNetworkChanged(newTypeName, oldTypeName, false)
        }
    }
}
