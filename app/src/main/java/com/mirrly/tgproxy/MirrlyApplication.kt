package com.mirrly.tgproxy

import android.app.Application
import com.mirrly.tgproxy.core.LocalProxyServer
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.service.PreferencesManager

class MirrlyApplication : Application() {

    lateinit var prefsManager: PreferencesManager
        private set

    lateinit var config: ProxyConfig
        private set

    lateinit var proxyServer: LocalProxyServer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.mirrly.tgproxy.core.AppLogger.startLogcatReader(android.os.Process.myPid())
        prefsManager = PreferencesManager(this)
        config = prefsManager.loadConfig()
        proxyServer = LocalProxyServer(config)
    }

    fun saveConfig() {
        prefsManager.saveConfig(config)
    }

    companion object {
        lateinit var instance: MirrlyApplication
            private set
    }
}
