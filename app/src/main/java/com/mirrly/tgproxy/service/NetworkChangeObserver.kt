package com.mirrly.tgproxy.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

class NetworkChangeObserver(
    private val context: Context,
    private val onNetworkChanged: (newType: String, oldType: String) -> Unit
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

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
                // При вызове registerDefaultNetworkCallback колбэк onAvailable
                // вызывается исключительно для актуальной дефолтной сети системы.
                currentDefaultNetwork = network
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                currentDefaultNetwork = network
                currentCapabilities = caps

                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val newType = if (hasInternet) {
                    extractNetworkTypeName(caps)
                } else {
                    "DISCONNECTED"
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

            override fun onLost(network: Network) {
                // Обрабатываем потерю сети только если потеряна именно текущая дефолтная сеть
                if (network == currentDefaultNetwork) {
                    val oldType = lastReportedType
                    currentDefaultNetwork = null
                    currentCapabilities = null
                    currentNetworkType = "DISCONNECTED"
                    lastReportedNetwork = null
                    lastReportedType = "DISCONNECTED"
                    onNetworkChanged("DISCONNECTED", oldType)
                }
            }

            override fun onUnavailable() {
                if (currentNetworkType != "DISCONNECTED") {
                    val oldType = lastReportedType
                    currentDefaultNetwork = null
                    currentCapabilities = null
                    currentNetworkType = "DISCONNECTED"
                    lastReportedNetwork = null
                    lastReportedType = "DISCONNECTED"
                    onNetworkChanged("DISCONNECTED", oldType)
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
