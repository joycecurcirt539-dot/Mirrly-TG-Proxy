package com.mirrly.tgproxy.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

import android.os.Handler
import android.os.Looper

class NetworkChangeObserver(
    private val context: Context,
    private val onNetworkChanged: (newType: String, oldType: String) -> Unit
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingDisconnectRunnable: Runnable? = null
    private val stateLock = Any()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var currentDefaultNetwork: Network? = null

    @Volatile
    private var currentNetworkType: String = "DISCONNECTED"

    @Volatile
    private var lastReportedNetwork: Network? = null

    @Volatile
    private var lastReportedType: String = "DISCONNECTED"

    @Volatile
    private var currentCapabilities: NetworkCapabilities? = null

    private fun cancelPendingDisconnect() {
        synchronized(stateLock) {
            pendingDisconnectRunnable?.let {
                mainHandler.removeCallbacks(it)
                pendingDisconnectRunnable = null
            }
        }
    }

    private fun schedulePendingDisconnect(oldType: String) {
        synchronized(stateLock) {
            cancelPendingDisconnect()
            val runnable = Runnable {
                synchronized(stateLock) {
                    pendingDisconnectRunnable = null
                    currentDefaultNetwork = null
                    currentCapabilities = null
                    currentNetworkType = "DISCONNECTED"
                    lastReportedNetwork = null
                    lastReportedType = "DISCONNECTED"
                    onNetworkChanged("DISCONNECTED", oldType)
                }
            }
            pendingDisconnectRunnable = runnable
            mainHandler.postDelayed(runnable, 500L)
        }
    }

    fun start() {
        val cm = connectivityManager ?: return

        // Инициализируем базовое начальное состояние до прихода первых асинхронных колбэков
        try {
            val activeNet = cm.activeNetwork
            if (activeNet != null) {
                val caps = cm.getNetworkCapabilities(activeNet)
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    currentDefaultNetwork = activeNet
                    currentCapabilities = caps
                    currentNetworkType = extractNetworkTypeName(caps)
                    lastReportedNetwork = activeNet
                    lastReportedType = currentNetworkType
                }
            }
        } catch (_: Exception) {}

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // При появлении новой сети отменяем отложенный дисконнект от предыдущей сети
                cancelPendingDisconnect()
                currentDefaultNetwork = network
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (hasInternet) {
                    cancelPendingDisconnect()
                }

                synchronized(stateLock) {
                    currentDefaultNetwork = network
                    currentCapabilities = caps

                    val newType = if (hasInternet) {
                        extractNetworkTypeName(caps)
                    } else {
                        "DISCONNECTED"
                    }

                    if (newType == "DISCONNECTED") {
                        schedulePendingDisconnect(lastReportedType)
                        return
                    }

                    val oldType = lastReportedType
                    val isNetworkChanged = (lastReportedNetwork != network)

                    if (newType != oldType || isNetworkChanged) {
                        currentNetworkType = newType
                        lastReportedType = newType
                        lastReportedNetwork = network
                        onNetworkChanged(newType, oldType)
                    } else {
                        currentNetworkType = newType
                    }
                }
            }

            override fun onLost(network: Network) {
                // Предотвращаем Double Reset (Wi-Fi <-> LTE): дебаунсим дисконнект на 500 мс.
                // Если сотовая сеть поднимется в течение 500 мс, дисконнект отменяется.
                if (network == currentDefaultNetwork) {
                    schedulePendingDisconnect(lastReportedType)
                }
            }

            override fun onUnavailable() {
                if (currentNetworkType != "DISCONNECTED") {
                    schedulePendingDisconnect(lastReportedType)
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
        cancelPendingDisconnect()
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
        currentDefaultNetwork = null
        currentCapabilities = null
        currentNetworkType = "DISCONNECTED"
        lastReportedNetwork = null
        lastReportedType = "DISCONNECTED"
    }

    fun getCurrentNetworkTypeName(): String = currentNetworkType

    fun getCurrentCapabilities(): NetworkCapabilities? = currentCapabilities

    private fun extractNetworkTypeName(caps: NetworkCapabilities): String {
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile LTE/5G"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Active Network"
        }
    }
}
