/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import com.mirrly.tgproxy.core.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Брокер физической сети для координации внешних сокетов и VpnService (Tasks N05, N16).
 * Отслеживает изменения Wi-Fi/Cellular, назначает setUnderlyingNetworks,
 * инкрементирует сетевые поколения и предотвращает busy-loop при смене сети.
 */
class VpnNetworkBroker(
    private val context: Context,
    private val vpnService: VpnService,
    private val onNetworkMigrated: (network: Network?, generation: Long) -> Unit
) {
    companion object {
        private const val TAG = "VpnNetworkBroker"
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val currentNetworkGeneration = AtomicLong(1L)

    @Volatile
    var currentNetwork: Network? = null
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    private val isRegistered = AtomicBoolean(false)
    private var debounceJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            val isPhysicalValid = caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (isPhysicalValid) {
                handleNetworkEvent(network, isAvailable = true)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val notVpn = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            if (hasInternet && notVpn) {
                handleNetworkEvent(network, isAvailable = true)
            }
        }

        override fun onLost(network: Network) {
            if (network == currentNetwork) {
                val alternative = findActivePhysicalNetwork()
                if (alternative != null && alternative != network) {
                    AppLogger.i(TAG, "Физическая сеть $network потеряна, переход на альтернативную: $alternative")
                    handleNetworkEvent(alternative, isAvailable = true)
                } else {
                    handleNetworkEvent(network, isAvailable = false)
                }
            }
        }
    }

    /**
     * Поиск активной физической сети устройства (Wi-Fi, Ethernet, Сотовая связь), исключая VPN (Cloudflare эталон).
     */
    @Suppress("DEPRECATION")
    fun findActivePhysicalNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        if (active != null) {
            val caps = connectivityManager.getNetworkCapabilities(active)
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return active
            }
        }

        val allNetworks = connectivityManager.allNetworks
        var candidateCellular: Network? = null
        var candidateAnyPhysical: Network? = null
        for (net in allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(net) ?: continue
            val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            if (!notVpn) continue

            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

            if (hasInternet) {
                if (isWifi || isEthernet) {
                    return net
                }
                if (candidateCellular == null && isCellular) {
                    candidateCellular = net
                }
            } else if (candidateAnyPhysical == null && (isWifi || isCellular || isEthernet)) {
                candidateAnyPhysical = net
            }
        }
        return candidateCellular ?: candidateAnyPhysical
    }

    fun start() {
        if (isRegistered.compareAndSet(false, true)) {
            try {
                // В соответствии с эталоном Cloudflare (NetworkChangeReceiver):
                // Запрещено использовать registerDefaultNetworkCallback, так как после поднятия TUN дефолтной сетью становится сам VPN!
                val requestBuilder = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                }

                val request = requestBuilder.build()
                connectivityManager.registerNetworkCallback(request, networkCallback)

                val physical = findActivePhysicalNetwork()
                if (physical != null) {
                    currentNetwork = physical
                    isConnected = true
                    applyUnderlyingNetworks(physical)
                } else {
                    applyUnderlyingNetworks(null)
                }
                AppLogger.i(TAG, "Сетевой брокер запущен (Cloudflare Model), текущая физическая сеть: $physical")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка регистрации сетевого колбэка: ${e.message}")
            }
        }
    }

    fun stop() {
        if (isRegistered.compareAndSet(true, false)) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Ошибка отмены регистрации сетевого колбэка: ${e.message}")
            }
            debounceJob?.cancel()
            scope.cancel()
            currentNetwork = null
            isConnected = false
            applyUnderlyingNetworks(null)
            AppLogger.i(TAG, "Сетевой брокер остановлен")
        }
    }

    private fun handleNetworkEvent(network: Network, isAvailable: Boolean) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(300L) // дебаунс кратковременных переключений
            if (isAvailable) {
                currentNetwork = network
                isConnected = true
                val newGen = currentNetworkGeneration.incrementAndGet()
                applyUnderlyingNetworks(network)
                AppLogger.i(TAG, "Сеть изменилась на $network (поколение: $newGen)")
                onNetworkMigrated(network, newGen)
            } else {
                if (currentNetwork == network) {
                    currentNetwork = null
                    isConnected = false
                    val newGen = currentNetworkGeneration.incrementAndGet()
                    applyUnderlyingNetworks(null)
                    AppLogger.w(TAG, "Связь потеряна (поколение: $newGen)")
                    onNetworkMigrated(null, newGen)
                }
            }
        }
    }

    fun applyUnderlyingNetworks(network: Network?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (network != null) {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                        vpnService.setUnderlyingNetworks(arrayOf(network))
                        return
                    }
                }
                // Передача null указывает Android автоматически использовать системную физическую сеть по умолчанию.
                // Передача emptyArray() полностью блокирует маршрутизацию трафика через VPN!
                vpnService.setUnderlyingNetworks(null)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Сбой вызова setUnderlyingNetworks: ${e.message}")
        }
    }
}
