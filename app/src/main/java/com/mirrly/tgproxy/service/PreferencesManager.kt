package com.mirrly.tgproxy.service

import android.content.Context
import android.content.SharedPreferences
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.core.ProxyMode
import com.mirrly.tgproxy.core.WorkerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mirrly_tg_proxy_prefs", Context.MODE_PRIVATE)
    private val secretPrefs: SharedPreferences = context.getSharedPreferences("mirrly_secrets_prefs", Context.MODE_PRIVATE)

    private val _animationsDisabledFlow = MutableStateFlow(areAnimationsDisabled())
    val animationsDisabledFlow: StateFlow<Boolean> = _animationsDisabledFlow.asStateFlow()

    private val _isSocks5Flow = MutableStateFlow(loadConfig().isSocks5Mode)
    val isSocks5Flow: StateFlow<Boolean> = _isSocks5Flow.asStateFlow()

    private val _activeWorkerIdFlow = MutableStateFlow(getActiveWorkerId())
    val activeWorkerIdFlow: StateFlow<String> = _activeWorkerIdFlow.asStateFlow()

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "disable_animations_particles") {
            _animationsDisabledFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key == "proxy_mode" || key == "socks5_enabled") {
            val modeName = sharedPreferences.getString("proxy_mode", ProxyMode.MTPROTO.name)
            val isSocks5 = modeName == ProxyMode.SOCKS5.name || sharedPreferences.getBoolean("socks5_enabled", false)
            _isSocks5Flow.value = isSocks5
        }
        if (key == "active_worker_id") {
            _activeWorkerIdFlow.value = sharedPreferences.getString(key, "dev_default") ?: "dev_default"
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
        val savedSecret = secretPrefs.getString("secret_hex", null) ?: prefs.getString("secret_hex", null)
        val secretHex = if (savedSecret.isNullOrBlank() || savedSecret == "dd00000000000000000000000000000000") {
            val generatedSecret = ProxyConfig.generateRandomSecret()
            secretPrefs.edit().putString("secret_hex", generatedSecret).commit()
            if (prefs.contains("secret_hex")) {
                prefs.edit().remove("secret_hex").commit()
            }
            generatedSecret
        } else {
            if (!secretPrefs.contains("secret_hex")) {
                secretPrefs.edit().putString("secret_hex", savedSecret).commit()
            }
            if (prefs.contains("secret_hex")) {
                prefs.edit().remove("secret_hex").commit()
            }
            savedSecret
        }
        val cfEnabled = prefs.getBoolean("cf_proxy_enabled", defaults.cfProxyEnabled)
        val activeWorker = getActiveWorker()
        val savedCustomDomain = prefs.getString("custom_cf_domain", null)
        val customDomain = if (!savedCustomDomain.isNullOrBlank()) {
            ProxyConfig.sanitizeDomain(savedCustomDomain)
        } else {
            activeWorker.domain
        }
        val poolSize = prefs.getInt("pool_size", defaults.poolSize)
        val autostart = prefs.getBoolean("autostart_on_boot", defaults.autostartOnBoot)
        val speedPresetName = prefs.getString("speed_preset", defaults.speedPresetName) ?: defaults.speedPresetName
        val tcpNoDelayModeName = if (prefs.contains("tcp_nodelay_mode")) {
            prefs.getString("tcp_nodelay_mode", defaults.tcpNoDelayModeName) ?: defaults.tcpNoDelayModeName
        } else if (prefs.contains("tcp_nodelay")) {
            if (prefs.getBoolean("tcp_nodelay", true)) com.mirrly.tgproxy.core.TcpNoDelayMode.AUTO.name
            else com.mirrly.tgproxy.core.TcpNoDelayMode.OFF.name
        } else {
            defaults.tcpNoDelayModeName
        }
        val tcpNoDelay = prefs.getBoolean("tcp_nodelay", defaults.tcpNoDelay)
        val bufferSizeBytes = prefs.getInt("buffer_size_bytes", defaults.bufferSizeBytes)
        val socks5Port = prefs.getInt("socks5_port", defaults.socks5Port)
        val useDefaultWorkerSocks5 = prefs.getBoolean("use_default_worker_socks5", defaults.useDefaultWorkerSocks5)

        // Миграция: если proxy_mode ещё не сохранён, читаем старый socks5_enabled
        val proxyModeName = if (prefs.contains("proxy_mode")) {
            prefs.getString("proxy_mode", ProxyMode.MTPROTO.name) ?: ProxyMode.MTPROTO.name
        } else {
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
            tcpNoDelayModeName = tcpNoDelayModeName,
            tcpNoDelay = tcpNoDelay,
            bufferSizeBytes = bufferSizeBytes,
            socks5Port = socks5Port,
            useDefaultWorkerSocks5 = useDefaultWorkerSocks5,
            proxyModeName = proxyModeName
        )
    }

    fun saveConfig(config: ProxyConfig) {
        val sanitizedDomain = ProxyConfig.sanitizeDomain(config.customCfDomain)
        val domainToSave = if (sanitizedDomain.isNotEmpty()) sanitizedDomain else getActiveWorker().domain
        config.customCfDomain = domainToSave

        val secretToSave = if (config.secretHex.isNotBlank() && config.secretHex != "dd00000000000000000000000000000000") {
            config.secretHex
        } else {
            val existing = secretPrefs.getString("secret_hex", null) ?: prefs.getString("secret_hex", null)
            if (!existing.isNullOrBlank() && existing != "dd00000000000000000000000000000000") {
                existing
            } else {
                val newSec = ProxyConfig.generateRandomSecret()
                newSec
            }
        }
        config.secretHex = secretToSave
        secretPrefs.edit().putString("secret_hex", secretToSave).apply()

        prefs.edit()
            .remove("secret_hex")
            .putString("bind_host", config.bindHost)
            .putInt("bind_port", config.bindPort)
            .putBoolean("cf_proxy_enabled", config.cfProxyEnabled)
            .putString("custom_cf_domain", domainToSave)
            .putInt("pool_size", config.poolSize)
            .putBoolean("autostart_on_boot", config.autostartOnBoot)
            .putString("speed_preset", config.speedPresetName)
            .putString("tcp_nodelay_mode", config.tcpNoDelayModeName)
            .putBoolean("tcp_nodelay", config.tcpNoDelay)
            .putInt("buffer_size_bytes", config.bufferSizeBytes)
            .putInt("socks5_port", config.socks5Port)
            .putBoolean("use_default_worker_socks5", config.useDefaultWorkerSocks5)
            .putString("proxy_mode", config.proxyModeName)
            .apply()
        _isSocks5Flow.value = config.isSocks5Mode
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

    fun isAutoFailoverEnabled(): Boolean {
        return prefs.getBoolean("auto_failover_enabled", true)
    }

    fun setAutoFailoverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_failover_enabled", enabled).apply()
    }

    // ── Worker Profiles Management ──────────────────────────────────────────

    companion object {
        val DEFAULT_DEV_WORKERS = listOf(
            WorkerProfile(
                id = "dev_default",
                name = "Mirrly Основной",
                domain = "mirrly-tg-proxy-worker.brawny-singer.workers.dev",
                isDeveloperWorker = true
            ),
            WorkerProfile(
                id = "dev_alpha",
                name = "Mirrly Альфа",
                domain = "mtg-relay-5o77p2.mtg-alfaj.workers.dev",
                isDeveloperWorker = true
            ),
            WorkerProfile(
                id = "dev_beta",
                name = "Mirrly Бета",
                domain = "mtg-relay-ki2q2v.mtg-beta.workers.dev",
                isDeveloperWorker = true
            ),
            WorkerProfile(
                id = "dev_gamma",
                name = "Mirrly Гамма",
                domain = "mtg-relay-vndj4a.tammistichtqvc264.workers.dev",
                isDeveloperWorker = true
            ),
            WorkerProfile(
                id = "dev_delta",
                name = "Mirrly Дельта",
                domain = "mtg-relay-xbl1ts.mtg-beta.workers.dev",
                isDeveloperWorker = true
            )
        )
    }

    fun getDeveloperWorkers(): List<WorkerProfile> {
        return DEFAULT_DEV_WORKERS
    }

    fun getCustomWorkers(): List<WorkerProfile> {
        val jsonStr = prefs.getString("custom_workers_json", null) ?: return emptyList()
        val result = mutableListOf<WorkerProfile>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    WorkerProfile(
                        id = obj.getString("id"),
                        name = obj.optString("name", "Личный воркер"),
                        domain = obj.getString("domain"),
                        isDeveloperWorker = false
                    )
                )
            }
        } catch (_: Exception) {}
        return result
    }

    fun saveCustomWorkers(workers: List<WorkerProfile>) {
        val array = JSONArray()
        for (w in workers) {
            val obj = JSONObject()
            obj.put("id", w.id)
            obj.put("name", w.name)
            obj.put("domain", w.domain)
            array.put(obj)
        }
        prefs.edit().putString("custom_workers_json", array.toString()).apply()
    }

    fun addCustomWorker(name: String, domain: String): Result<WorkerProfile> {
        val formRes = com.mirrly.tgproxy.core.WorkerDomainNormalizer.normalizeForm(name, domain)
        val cleanDomain = formRes.normalizedDomain
        if (cleanDomain.isBlank()) {
            return Result.failure(IllegalArgumentException("Укажите корректный домен воркера (например: my-proxy.username.workers.dev)"))
        }

        val current = getCustomWorkers().toMutableList()
        val allExisting = current + DEFAULT_DEV_WORKERS
        if (allExisting.any { it.domain.equals(cleanDomain, ignoreCase = true) }) {
            return Result.failure(IllegalStateException("Этот воркер уже добавлен в ваш список"))
        }

        val cleanName = formRes.normalizedName.ifBlank { "Личный воркер #${current.size + 1}" }
        val newWorker = WorkerProfile(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            domain = cleanDomain,
            isDeveloperWorker = false
        )
        current.add(newWorker)
        saveCustomWorkers(current)
        return Result.success(newWorker)
    }

    fun deleteCustomWorker(id: String) {
        val current = getCustomWorkers().filter { it.id != id }
        saveCustomWorkers(current)
        if (getActiveWorkerId() == id) {
            setActiveWorkerId("dev_default")
        }
    }

    fun getActiveWorkerId(): String {
        return prefs.getString("active_worker_id", "dev_default") ?: "dev_default"
    }

    fun setActiveWorkerId(id: String) {
        prefs.edit().putString("active_worker_id", id).apply()
        _activeWorkerIdFlow.value = id
        val worker = getActiveWorker(id)

        try {
            val app = com.mirrly.tgproxy.MirrlyApplication.instance
            app.config.customCfDomain = worker.domain
            app.config.useDefaultWorkerSocks5 = worker.isDeveloperWorker
            saveConfig(app.config)
            if (app.proxyServer.isRunning && app.config.isSocks5Mode) {
                app.proxyServer.onWorkerChanged(worker.domain)
            }
        } catch (_: Exception) {
            val config = loadConfig()
            config.customCfDomain = worker.domain
            config.useDefaultWorkerSocks5 = worker.isDeveloperWorker
            saveConfig(config)
        }

        try {
            WorkerFailoverManager.getCircuitRecord(worker.id)?.reset()
        } catch (_: Exception) {}
    }

    fun getActiveWorker(activeId: String = getActiveWorkerId()): WorkerProfile {
        return (getCustomWorkers() + DEFAULT_DEV_WORKERS).find { it.id == activeId }
            ?: DEFAULT_DEV_WORKERS.first()
    }

    fun getIgnoredUpdateVersion(): String? {
        return prefs.getString("ignored_update_version", null)
    }

    fun setIgnoredUpdateVersion(version: String) {
        val clean = com.mirrly.tgproxy.core.UpdateChecker.cleanVersionString(version)
        prefs.edit().putString("ignored_update_version", clean).apply()
    }

    fun clearIgnoredUpdateVersion() {
        prefs.edit().remove("ignored_update_version").apply()
    }

    fun isUpdateVersionIgnored(version: String): Boolean {
        val ignored = getIgnoredUpdateVersion() ?: return false
        val cleanIgnored = com.mirrly.tgproxy.core.UpdateChecker.cleanVersionString(ignored)
        val cleanVersion = com.mirrly.tgproxy.core.UpdateChecker.cleanVersionString(version)
        return cleanIgnored.isNotBlank() && cleanIgnored == cleanVersion
    }
}
