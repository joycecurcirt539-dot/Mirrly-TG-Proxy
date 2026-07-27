package com.mirrly.tgproxy.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

class NetworkChangeObserver(
    private val context: Context,
    private val onNetworkChanged: (networkType: String) -> Unit
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastActiveNetworkType: String = ""

    fun start() {
        if (connectivityManager == null) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val currentType = getCurrentNetworkTypeName()
                if (lastActiveNetworkType != currentType) {
                    lastActiveNetworkType = currentType
                    onNetworkChanged(currentType)
                }
            }

            override fun onLost(network: Network) {
                lastActiveNetworkType = "DISCONNECTED"
                onNetworkChanged("DISCONNECTED")
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {}
    }

    fun stop() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    fun getCurrentNetworkTypeName(): String {
        val cm = connectivityManager ?: return "UNKNOWN"
        val activeNet = cm.activeNetwork ?: return "DISCONNECTED"
        val caps = cm.getNetworkCapabilities(activeNet) ?: return "DISCONNECTED"

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile LTE/5G"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Active Network"
        }
    }
}
