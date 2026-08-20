package com.mirrly.tgproxy.service

import android.content.Context
import android.content.SharedPreferences
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.core.ProxyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mirrly_tg_proxy_prefs", Context.MODE_PRIVATE)

    private val _animationsDisabledFlow = MutableStateFlow(areAnimationsDisabled())
    val animationsDisabledFlow: StateFlow<Boolean> = _animationsDisabledFlow.asStateFlow()

    private val _isSocks5Flow = MutableStateFlow(loadConfig().isSocks5Mode)
    val isSocks5Flow: StateFlow<Boolean> = _isSocks5Flow.asStateFlow()

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "disable_animations_particles") {
            _animationsDisabledFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key == "proxy_mode" || key == "socks5_enabled") {
            _isSocks5Flow.value = loadConfig().isSocks5Mode
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    fun loadConfig(): ProxyConfig {
        // Use ProxyConfig() as single source of truth for all defaults
        val defaults = ProxyConfig()
        val bindHost = prefs.getString("bind_host", defaults.bindHost) ?: defaults.bindHost
        val bindPort = prefs.getInt("bind_port", defaults.bindPort)
        val savedSecret = prefs.getString("secret_hex", null)
        val secretHex = if (savedSecret.isNullOrEmpty()) {
            val generatedSecret = ProxyConfig.generateRandomSecret()
            prefs.edit().putString("secret_hex", generatedSecret).apply()
            generatedSecret
        } else {
            savedSecret
        }
        val cfEnabled = prefs.getBoolean("cf_proxy_enabled", defaults.cfProxyEnabled)
        val customDomain = ProxyConfig.sanitizeDomain(prefs.getString("custom_cf_domain", defaults.customCfDomain) ?: defaults.customCfDomain)
        val poolSize = prefs.getInt("pool_size", defaults.poolSize)
        val autostart = prefs.getBoolean("autostart_on_boot", defaults.autostartOnBoot)
        val speedPresetName = prefs.getString("speed_preset", defaults.speedPresetName) ?: defaults.speedPresetName
        val tcpNoDelay = prefs.getBoolean("tcp_nodelay", defaults.tcpNoDelay)
        val bufferSizeBytes = prefs.getInt("buffer_size_bytes", defaults.bufferSizeBytes)
        val socks5Port = prefs.getInt("socks5_port", defaults.socks5Port)

        // Миграция: если proxy_mode ещё не сохранён, читаем старый socks5_enabled
        val proxyModeName = if (prefs.contains("proxy_mode")) {
            prefs.getString("proxy_mode", ProxyMode.MTPROTO.name) ?: ProxyMode.MTPROTO.name
        } else {
            // Старые установки: если был socks5_enabled=true → переводим в SOCKS5 режим
            @Suppress("DEPRECATION")
            if (prefs.getBoolean("socks5_enabled", false)) ProxyMode.SOCKS5.name
            else ProxyMode.MTPROTO.name
        }

        return ProxyConfig(
            bindHost = bindHost,
            bindPort = bindPort,
            secretHex = secretHex,
            cfProxyEnabled = cfEnabled,
            customCfDomain = customDomain,
            poolSize = poolSize,
            autostartOnBoot = autostart,
            speedPresetName = speedPresetName,
            tcpNoDelay = tcpNoDelay,
            bufferSizeBytes = bufferSizeBytes,
            socks5Port = socks5Port,
            proxyModeName = proxyModeName
        )
    }

    fun saveConfig(config: ProxyConfig) {
        val sanitizedDomain = ProxyConfig.sanitizeDomain(config.customCfDomain)
        config.customCfDomain = sanitizedDomain
        prefs.edit()
            .putString("bind_host", config.bindHost)
            .putInt("bind_port", config.bindPort)
            .putString("secret_hex", config.secretHex)
            .putBoolean("cf_proxy_enabled", config.cfProxyEnabled)
            .putString("custom_cf_domain", sanitizedDomain)
            .putInt("pool_size", config.poolSize)
            .putBoolean("autostart_on_boot", config.autostartOnBoot)
            .putString("speed_preset", config.speedPresetName)
            .putBoolean("tcp_nodelay", config.tcpNoDelay)
            .putInt("buffer_size_bytes", config.bufferSizeBytes)
            .putInt("socks5_port", config.socks5Port)
            .putString("proxy_mode", config.proxyModeName)
            .apply()
        _isSocks5Flow.value = config.isSocks5Mode
    }

    fun isAutoReconnectEnabled(): Boolean {
        return prefs.getBoolean("auto_reconnect_on_network_change", true)
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_reconnect_on_network_change", enabled).apply()
    }

    fun incrementLaunchCount(): Int {
        val current = prefs.getInt("launch_count", 0) + 1
        prefs.edit().putInt("launch_count", current).apply()
        return current
    }

    fun getLaunchCount(): Int {
        return prefs.getInt("launch_count", 0)
    }

    fun isGithubStarDismissed(): Boolean {
        return prefs.getBoolean("github_star_dismissed", false)
    }

    fun setGithubStarDismissed(dismissed: Boolean = true) {
        prefs.edit().putBoolean("github_star_dismissed", dismissed).apply()
    }

    fun areAnimationsDisabled(): Boolean {
        return prefs.getBoolean("disable_animations_particles", false)
    }

    fun setAnimationsDisabled(disabled: Boolean) {
        prefs.edit().putBoolean("disable_animations_particles", disabled).apply()
        _animationsDisabledFlow.value = disabled
    }
}
