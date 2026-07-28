package com.mirrly.tgproxy.service

import android.content.Context
import android.content.SharedPreferences
import com.mirrly.tgproxy.core.ProxyConfig

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mirrly_tg_proxy_prefs", Context.MODE_PRIVATE)

    fun loadConfig(): ProxyConfig {
        // Use ProxyConfig() as single source of truth for all defaults
        val defaults = ProxyConfig()
        val bindHost = prefs.getString("bind_host", defaults.bindHost) ?: defaults.bindHost
        val bindPort = prefs.getInt("bind_port", defaults.bindPort)
        val secretHex = prefs.getString("secret_hex", "")?.ifEmpty { ProxyConfig.generateRandomSecret() } ?: ProxyConfig.generateRandomSecret()
        val cfEnabled = prefs.getBoolean("cf_proxy_enabled", defaults.cfProxyEnabled)
        val customDomain = prefs.getString("custom_cf_domain", defaults.customCfDomain) ?: defaults.customCfDomain
        val poolSize = prefs.getInt("pool_size", defaults.poolSize)
        val autostart = prefs.getBoolean("autostart_on_boot", defaults.autostartOnBoot)
        val fallbackTcp = prefs.getBoolean("fallback_direct_tcp", defaults.fallbackDirectTcp)

        return ProxyConfig(
            bindHost = bindHost,
            bindPort = bindPort,
            secretHex = secretHex,
            cfProxyEnabled = cfEnabled,
            customCfDomain = customDomain,
            poolSize = poolSize,
            autostartOnBoot = autostart,
            fallbackDirectTcp = fallbackTcp
        )
    }

    fun saveConfig(config: ProxyConfig) {
        prefs.edit()
            .putString("bind_host", config.bindHost)
            .putInt("bind_port", config.bindPort)
            .putString("secret_hex", config.secretHex)
            .putBoolean("cf_proxy_enabled", config.cfProxyEnabled)
            .putString("custom_cf_domain", config.customCfDomain)
            .putInt("pool_size", config.poolSize)
            .putBoolean("autostart_on_boot", config.autostartOnBoot)
            .putBoolean("fallback_direct_tcp", config.fallbackDirectTcp)
            .apply()
    }

    fun isAutoReconnectEnabled(): Boolean {
        return prefs.getBoolean("auto_reconnect_on_network_change", true)
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_reconnect_on_network_change", enabled).apply()
    }
}
