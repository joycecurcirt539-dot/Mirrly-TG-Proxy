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
import com.mirrly.tgproxy.core.NetworkStabilizationGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.net.DatagramSocket
import java.net.Socket
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
    private val stabilizationGate = NetworkStabilizationGate(cooldownMs = 400L)

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
            handleNetworkEvent(network, isAvailable = true)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (hasInternet && isValidated) {
                handleNetworkEvent(network, isAvailable = true)
            }
        }

        override fun onLost(network: Network) {
            if (network == currentNetwork) {
                handleNetworkEvent(network, isAvailable = false)
            }
        }
    }

    fun start() {
        if (isRegistered.compareAndSet(false, true)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    connectivityManager.registerDefaultNetworkCallback(networkCallback)
                } else {
                    val request = NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    connectivityManager.registerNetworkCallback(request, networkCallback)
                }
                val active = connectivityManager.activeNetwork
                if (active != null) {
                    currentNetwork = active
                    isConnected = true
                    applyUnderlyingNetworks(active)
                }
                AppLogger.i(TAG, "Сетевой брокер запущен, текущая сеть: $active")
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

    private fun applyUnderlyingNetworks(network: Network?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (network != null) {
                    vpnService.setUnderlyingNetworks(arrayOf(network))
                } else {
                    vpnService.setUnderlyingNetworks(emptyArray())
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Сбой вызова setUnderlyingNetworks: ${e.message}")
        }
    }

    /**
     * Привязка внешнего сокета к физической сети (Task N05).
     */
    fun bindSocket(socket: Socket): Boolean {
        val net = currentNetwork ?: return false
        return try {
            net.bindSocket(socket)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Не удалось привязать сокет к физической сети: ${e.message}")
            false
        }
    }

    /**
     * Привязка датаграмм-сокета к физической сети (Task N05).
     */
    fun bindSocket(socket: DatagramSocket): Boolean {
        val net = currentNetwork ?: return false
        return try {
            net.bindSocket(socket)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Не удалось привязать DatagramSocket к физической сети: ${e.message}")
            false
        }
    }

    /**
     * Привязка файлового дескриптора к физической сети (Task N05).
     */
    fun bindSocket(fd: FileDescriptor): Boolean {
        val net = currentNetwork ?: return false
        return try {
            net.bindSocket(fd)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Не удалось привязать FileDescriptor к физической сети: ${e.message}")
            false
        }
    }
}
