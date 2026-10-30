package com.mirrly.tgproxy.service

import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.core.ProxyMode
import com.mirrly.tgproxy.core.WarpKeystoreEncryption
import com.mirrly.tgproxy.core.WorkerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class PreferencesManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mirrly_tg_proxy_prefs", Context.MODE_PRIVATE)
    private val secretPrefs: SharedPreferences = context.getSharedPreferences("mirrly_secrets_prefs", Context.MODE_PRIVATE)

    private val _animationsDisabledFlow = MutableStateFlow(areAnimationsDisabled())
    val animationsDisabledFlow: StateFlow<Boolean> = _animationsDisabledFlow.asStateFlow()

    private val _isEchEnabledFlow = MutableStateFlow(isEchEnabled())
    val isEchEnabledFlow: StateFlow<Boolean> = _isEchEnabledFlow.asStateFlow()

    private val _isWsRandomizationEnabledFlow = MutableStateFlow(isWsRandomizationEnabled())
    val isWsRandomizationEnabledFlow: StateFlow<Boolean> = _isWsRandomizationEnabledFlow.asStateFlow()

    private val _isTlsRecordPaddingEnabledFlow = MutableStateFlow(isTlsRecordPaddingEnabled())
    val isTlsRecordPaddingEnabledFlow: StateFlow<Boolean> = _isTlsRecordPaddingEnabledFlow.asStateFlow()

    private val _isTlsClientHelloSplitEnabledFlow = MutableStateFlow(isTlsClientHelloSplitEnabled())
    val isTlsClientHelloSplitEnabledFlow: StateFlow<Boolean> = _isTlsClientHelloSplitEnabledFlow.asStateFlow()

    private val _useWorkerMtprotoFlow = MutableStateFlow(prefs.getBoolean("use_worker_mtproto", false))
    val useWorkerMtprotoFlow: StateFlow<Boolean> = _useWorkerMtprotoFlow.asStateFlow()

    private val _isSocks5Flow = MutableStateFlow(loadConfig().isSocks5Mode)
    val isSocks5Flow: StateFlow<Boolean> = _isSocks5Flow.asStateFlow()

    private val _socks5UplinkModeFlow = MutableStateFlow(loadConfig().socks5UplinkMode)
    val socks5UplinkModeFlow: StateFlow<Int> = _socks5UplinkModeFlow.asStateFlow()

    private val _proxyModeFlow = MutableStateFlow(loadConfig().proxyMode)
    val proxyModeFlow: StateFlow<ProxyMode> = _proxyModeFlow.asStateFlow()

    private val _activeWorkerIdFlow = MutableStateFlow(getActiveWorkerId())
    val activeWorkerIdFlow: StateFlow<String> = _activeWorkerIdFlow.asStateFlow()

    private val _activeWorkerIdMtprotoFlow = MutableStateFlow(getActiveWorkerId(ProxyMode.MTPROTO))
    val activeWorkerIdMtprotoFlow: StateFlow<String> = _activeWorkerIdMtprotoFlow.asStateFlow()

    private val _activeWorkerIdSocks5Flow = MutableStateFlow(getActiveWorkerId(ProxyMode.SOCKS5))
    val activeWorkerIdSocks5Flow: StateFlow<String> = _activeWorkerIdSocks5Flow.asStateFlow()

    private val _uplinkModeFlow = MutableStateFlow(loadConfig().uplinkMode)
    val uplinkModeFlow: StateFlow<com.mirrly.tgproxy.core.UplinkMode> = _uplinkModeFlow.asStateFlow()

    private val _vpnUplinkModeFlow = MutableStateFlow(loadConfig().vpnUplinkMode)
    val vpnUplinkModeFlow: StateFlow<com.mirrly.tgproxy.core.UplinkMode> = _vpnUplinkModeFlow.asStateFlow()

    private val _appLanguageFlow = MutableStateFlow(getAppLanguage())
    val appLanguageFlow: StateFlow<String> = _appLanguageFlow.asStateFlow()

    private val _advancedSettingsEnabledFlow = MutableStateFlow(isAdvancedSettingsEnabled())
    val advancedSettingsEnabledFlow: StateFlow<Boolean> = _advancedSettingsEnabledFlow.asStateFlow()

    private val _vpnMtuFlow = MutableStateFlow(1280)
    val vpnMtuFlow: StateFlow<Int> = _vpnMtuFlow.asStateFlow()

    private val _vpnBlockQuicFlow = MutableStateFlow(prefs.getBoolean("vpn_block_quic", true))
    val vpnBlockQuicFlow: StateFlow<Boolean> = _vpnBlockQuicFlow.asStateFlow()

    private val _vpnBlockIpv6LeaksFlow = MutableStateFlow(prefs.getBoolean("vpn_block_ipv6_leaks", true))
    val vpnBlockIpv6LeaksFlow: StateFlow<Boolean> = _vpnBlockIpv6LeaksFlow.asStateFlow()

    private val _vpnSplitTunnelEnabledFlow = MutableStateFlow(prefs.getBoolean("vpn_split_tunnel_enabled", false))
    val vpnSplitTunnelEnabledFlow: StateFlow<Boolean> = _vpnSplitTunnelEnabledFlow.asStateFlow()

    private val _vpnSplitTunnelAllowlistFlow = MutableStateFlow(prefs.getBoolean("vpn_split_tunnel_allowlist", false))
    val vpnSplitTunnelAllowlistFlow: StateFlow<Boolean> = _vpnSplitTunnelAllowlistFlow.asStateFlow()

    private val _vpnSplitTunnelPackagesFlow = MutableStateFlow(prefs.getStringSet("vpn_split_tunnel_packages", emptySet()) ?: emptySet())
    val vpnSplitTunnelPackagesFlow: StateFlow<Set<String>> = _vpnSplitTunnelPackagesFlow.asStateFlow()

    private val _vpnDnsModeFlow = MutableStateFlow(
        runCatching {
            val raw = prefs.getString("vpn_dns_mode", com.mirrly.tgproxy.core.VpnDnsMode.COMSS_DNS.name) ?: com.mirrly.tgproxy.core.VpnDnsMode.COMSS_DNS.name
            if (raw == "XBOX_DNS") com.mirrly.tgproxy.core.VpnDnsMode.COMSS_DNS
            else com.mirrly.tgproxy.core.VpnDnsMode.valueOf(raw)
        }.getOrDefault(com.mirrly.tgproxy.core.VpnDnsMode.COMSS_DNS)
    )
    val vpnDnsModeFlow: StateFlow<com.mirrly.tgproxy.core.VpnDnsMode> = _vpnDnsModeFlow.asStateFlow()

    private val _vpnCustomDnsIpsFlow = MutableStateFlow(prefs.getString("vpn_custom_dns_ips", "") ?: "")
    val vpnCustomDnsIpsFlow: StateFlow<String> = _vpnCustomDnsIpsFlow.asStateFlow()

    private val _vpnAutoProfileEnabledFlow = MutableStateFlow(prefs.getBoolean("vpn_auto_profile_enabled", true))
    val vpnAutoProfileEnabledFlow: StateFlow<Boolean> = _vpnAutoProfileEnabledFlow.asStateFlow()

    // ── AI Chat & Neural Engine Settings Flows ──
    private val _aiProviderFlow = MutableStateFlow(getAiProvider())
    val aiProviderFlow: StateFlow<String> = _aiProviderFlow.asStateFlow()

    private val _aiEndpointFlow = MutableStateFlow(getAiEndpoint())
    val aiEndpointFlow: StateFlow<String> = _aiEndpointFlow.asStateFlow()

    private val _aiModelFlow = MutableStateFlow(getAiModel())
    val aiModelFlow: StateFlow<String> = _aiModelFlow.asStateFlow()

    private val _aiApiKeyFlow = MutableStateFlow(getAiApiKey())
    val aiApiKeyFlow: StateFlow<String> = _aiApiKeyFlow.asStateFlow()

    private val _aiReasoningEnabledFlow = MutableStateFlow(isAiReasoningEnabled())
    val aiReasoningEnabledFlow: StateFlow<Boolean> = _aiReasoningEnabledFlow.asStateFlow()

    private val _aiStreamingEnabledFlow = MutableStateFlow(isAiStreamingEnabled())
    val aiStreamingEnabledFlow: StateFlow<Boolean> = _aiStreamingEnabledFlow.asStateFlow()

    private val _aiOnlyResponseFlow = MutableStateFlow(isAiOnlyResponse())
    val aiOnlyResponseFlow: StateFlow<Boolean> = _aiOnlyResponseFlow.asStateFlow()

    private val _aiQuoteResponseFlow = MutableStateFlow(isAiQuoteResponse())
    val aiQuoteResponseFlow: StateFlow<Boolean> = _aiQuoteResponseFlow.asStateFlow()

    private val _aiTemperatureFlow = MutableStateFlow(getAiTemperature())
    val aiTemperatureFlow: StateFlow<Float> = _aiTemperatureFlow.asStateFlow()

    private val _forceMobileModeFlow = MutableStateFlow(isForceMobileModeEnabled())
    val forceMobileModeFlow: StateFlow<Boolean> = _forceMobileModeFlow.asStateFlow()

    private val _dialThrottlingFlow = MutableStateFlow(isDialThrottlingEnabled())
    val dialThrottlingFlow: StateFlow<Boolean> = _dialThrottlingFlow.asStateFlow()

    // ── Log Journal & Overhead Reduction Settings ──
    private val _isLogsEnabledFlow = MutableStateFlow(prefs.getBoolean("logs_enabled", true))
    val isLogsEnabledFlow: StateFlow<Boolean> = _isLogsEnabledFlow.asStateFlow()

    private val _isLogcatReaderAllowedFlow = MutableStateFlow(prefs.getBoolean("logs_logcat_reader_allowed", true))
    val isLogcatReaderAllowedFlow: StateFlow<Boolean> = _isLogcatReaderAllowedFlow.asStateFlow()

    private val _isLogLevelDebugFlow = MutableStateFlow(prefs.getBoolean("logs_level_debug", false))
    val isLogLevelDebugFlow: StateFlow<Boolean> = _isLogLevelDebugFlow.asStateFlow()

    private val _isLogLevelInfoFlow = MutableStateFlow(prefs.getBoolean("logs_level_info", true))
    val isLogLevelInfoFlow: StateFlow<Boolean> = _isLogLevelInfoFlow.asStateFlow()

    private val _isLogLevelWarnFlow = MutableStateFlow(prefs.getBoolean("logs_level_warn", true))
    val isLogLevelWarnFlow: StateFlow<Boolean> = _isLogLevelWarnFlow.asStateFlow()

    private val _isLogLevelErrorFlow = MutableStateFlow(prefs.getBoolean("logs_level_error", true))
    val isLogLevelErrorFlow: StateFlow<Boolean> = _isLogLevelErrorFlow.asStateFlow()

    private val _disabledLogCategoriesFlow = MutableStateFlow(
        prefs.getStringSet("logs_disabled_categories", emptySet()) ?: emptySet()
    )
    val disabledLogCategoriesFlow: StateFlow<Set<String>> = _disabledLogCategoriesFlow.asStateFlow()

    private val _disabledLogTagsFlow = MutableStateFlow(
        prefs.getStringSet("logs_disabled_tags", emptySet()) ?: emptySet()
    )
    val disabledLogTagsFlow: StateFlow<Set<String>> = _disabledLogTagsFlow.asStateFlow()

    // ── Custom Wallpaper Engine ─────────────────────────────────────────────
    /** Absolute path of the sandboxed wallpaper file, or null for the default AMOLED background. */
    private val _customBackgroundUriFlow = MutableStateFlow(resolveStoredWallpaperPath())
    val customBackgroundUriFlow: StateFlow<String?> = _customBackgroundUriFlow.asStateFlow()

    /** Bumped on every successful import: the file name is constant, so image caches key on this. */
    private val _customBackgroundRevisionFlow = MutableStateFlow(prefs.getLong(KEY_WALLPAPER_REVISION, 0L))
    val customBackgroundRevisionFlow: StateFlow<Long> = _customBackgroundRevisionFlow.asStateFlow()

    /** Media type: [WALLPAPER_TYPE_IMAGE] or [WALLPAPER_TYPE_VIDEO]. */
    private val _customBackgroundTypeFlow = MutableStateFlow(
        prefs.getString(KEY_WALLPAPER_TYPE, WALLPAPER_TYPE_IMAGE) ?: WALLPAPER_TYPE_IMAGE
    )
    val customBackgroundTypeFlow: StateFlow<String> = _customBackgroundTypeFlow.asStateFlow()

    /** Video power saver toggle: caps decoding frame rate and auto-pauses in Android battery saver. */
    private val _customBackgroundVideoPowerSaveFlow = MutableStateFlow(
        prefs.getBoolean(KEY_WALLPAPER_VIDEO_POWER_SAVE, true)
    )
    val customBackgroundVideoPowerSaveFlow: StateFlow<Boolean> = _customBackgroundVideoPowerSaveFlow.asStateFlow()

    /** Cached video metadata (resolution, duration, size) for the active video wallpaper. */
    private val _customBackgroundVideoMetadataFlow = MutableStateFlow<WallpaperProcessor.VideoMetadata?>(
        resolveStoredVideoMetadata()
    )
    val customBackgroundVideoMetadataFlow: StateFlow<WallpaperProcessor.VideoMetadata?> = _customBackgroundVideoMetadataFlow.asStateFlow()

    /** Blur radius in dp, range [WALLPAPER_BLUR_MIN]..[WALLPAPER_BLUR_MAX]. */
    private val _customBackgroundBlurFlow = MutableStateFlow(
        prefs.getFloat(KEY_WALLPAPER_BLUR, WALLPAPER_BLUR_DEFAULT).coerceIn(WALLPAPER_BLUR_MIN, WALLPAPER_BLUR_MAX)
    )
    val customBackgroundBlurFlow: StateFlow<Float> = _customBackgroundBlurFlow.asStateFlow()

    /** Black overlay alpha, range [WALLPAPER_DIM_MIN]..[WALLPAPER_DIM_MAX]; keeps text contrast. */
    private val _customBackgroundDimFlow = MutableStateFlow(
        prefs.getFloat(KEY_WALLPAPER_DIM, WALLPAPER_DIM_DEFAULT).coerceIn(WALLPAPER_DIM_MIN, WALLPAPER_DIM_MAX)
    )
    val customBackgroundDimFlow: StateFlow<Float> = _customBackgroundDimFlow.asStateFlow()

    /** Whether the particle layer is rendered on top of the custom wallpaper. */
    private val _customBackgroundParticlesFlow = MutableStateFlow(prefs.getBoolean(KEY_WALLPAPER_PARTICLES, true))
    val customBackgroundParticlesFlow: StateFlow<Boolean> = _customBackgroundParticlesFlow.asStateFlow()

    /** Active preset ID if the current background corresponds to a preset (or null if ad-hoc custom file). */
    private val _customBackgroundActivePresetIdFlow = MutableStateFlow<String?>(
        prefs.getString(KEY_WALLPAPER_ACTIVE_PRESET_ID, null)
    )
    val customBackgroundActivePresetIdFlow: StateFlow<String?> = _customBackgroundActivePresetIdFlow.asStateFlow()

    /** User-saved custom presets list. */
    private val _customBackgroundUserPresetsFlow = MutableStateFlow<List<WallpaperPreset>>(emptyList())
    val customBackgroundUserPresetsFlow: StateFlow<List<WallpaperPreset>> = _customBackgroundUserPresetsFlow.asStateFlow()

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "disable_animations_particles") {
            _animationsDisabledFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key == KEY_ECH_ENABLED) {
            _isEchEnabledFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key == KEY_WS_RANDOMIZATION_ENABLED) {
            _isWsRandomizationEnabledFlow.value = sharedPreferences.getBoolean(key, true)
        }
        if (key == KEY_TLS_RECORD_PADDING_ENABLED) {
            _isTlsRecordPaddingEnabledFlow.value = sharedPreferences.getBoolean(key, true)
        }
        if (key == KEY_TLS_CLIENT_HELLO_SPLIT_ENABLED) {
            _isTlsClientHelloSplitEnabledFlow.value = sharedPreferences.getBoolean(key, true)
        }
        if (key == KEY_SOCKS5_UPLINK_MODE) {
            val mode = sharedPreferences.getInt(key, 0)
            _socks5UplinkModeFlow.value = mode
            try {
                MirrlyApplication.instance.config.socks5UplinkMode = mode
            } catch (_: Throwable) {}
            com.mirrly.tgproxy.core.NativeProxy.setSocks5UplinkMode(mode)
        }
        if (key == "proxy_mode" || key == "socks5_enabled") {
            val modeName = sharedPreferences.getString("proxy_mode", ProxyMode.MTPROTO.name)
            val isSocks5 = modeName == ProxyMode.SOCKS5.name || sharedPreferences.getBoolean("socks5_enabled", false)
            _isSocks5Flow.value = isSocks5
            _proxyModeFlow.value = try { ProxyMode.valueOf(modeName ?: ProxyMode.MTPROTO.name) } catch (_: Exception) { ProxyMode.MTPROTO }
        }
        if (key == "active_worker_id") {
            _activeWorkerIdFlow.value = sharedPreferences.getString(key, "") ?: ""
        }
        if (key == KEY_ACTIVE_WORKER_ID_MTPROTO) {
            _activeWorkerIdMtprotoFlow.value = sharedPreferences.getString(key, "") ?: ""
        }
        if (key == KEY_ACTIVE_WORKER_ID_SOCKS5) {
            _activeWorkerIdSocks5Flow.value = sharedPreferences.getString(key, "") ?: ""
        }
        if (key == "uplink_mode") {
            _uplinkModeFlow.value = com.mirrly.tgproxy.core.UplinkMode.WORKER
        }
        if (key == "vpn_uplink_mode") {
            val modeName = sharedPreferences.getString(key, com.mirrly.tgproxy.core.UplinkMode.AWG.name) ?: com.mirrly.tgproxy.core.UplinkMode.AWG.name
            val mode = try { com.mirrly.tgproxy.core.UplinkMode.valueOf(modeName) } catch (_: Exception) { com.mirrly.tgproxy.core.UplinkMode.AWG }
            _vpnUplinkModeFlow.value = if (mode == com.mirrly.tgproxy.core.UplinkMode.WORKER) com.mirrly.tgproxy.core.UplinkMode.AWG else mode
        }
        if (key == "app_language") {
            _appLanguageFlow.value = sharedPreferences.getString(key, "system") ?: "system"
        }
        if (key == "advanced_settings_enabled") {
            _advancedSettingsEnabledFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key == "vpn_mtu") {
            _vpnMtuFlow.value = sharedPreferences.getInt(key, 1420)
        }
        if (key == "vpn_block_quic") {
            _vpnBlockQuicFlow.value = sharedPreferences.getBoolean(key, true)
        }
        if (key == "vpn_block_ipv6_leaks") {
            _vpnBlockIpv6LeaksFlow.value = sharedPreferences.getBoolean(key, true)
        }
        if (key == "vpn_split_tunnel_enabled") {
            _vpnSplitTunnelEnabledFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key == "vpn_split_tunnel_allowlist") {
            _vpnSplitTunnelAllowlistFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key == "vpn_split_tunnel_packages") {
            _vpnSplitTunnelPackagesFlow.value = sharedPreferences.getStringSet(key, emptySet()) ?: emptySet()
        }
        if (key == "vpn_dns_mode") {
            val modeName = sharedPreferences.getString(key, com.mirrly.tgproxy.core.VpnDnsMode.COMSS_DNS.name) ?: com.mirrly.tgproxy.core.VpnDnsMode.COMSS_DNS.name
            _vpnDnsModeFlow.value = if (modeName == "XBOX_DNS") com.mirrly.tgproxy.core.VpnDnsMode.COMSS_DNS
            else runCatching { com.mirrly.tgproxy.core.VpnDnsMode.valueOf(modeName) }.getOrDefault(com.mirrly.tgproxy.core.VpnDnsMode.COMSS_DNS)
        }
        if (key == "vpn_custom_dns_ips") {
            _vpnCustomDnsIpsFlow.value = sharedPreferences.getString(key, "") ?: ""
        }
        if (key == "vpn_auto_profile_enabled") {
            _vpnAutoProfileEnabledFlow.value = sharedPreferences.getBoolean(key, true)
        }
        if (key == "force_mobile_mode_enabled") {
            _forceMobileModeFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key == "dial_throttling_enabled") {
            _dialThrottlingFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key?.startsWith("logs_") == true) {
            _isLogsEnabledFlow.value = sharedPreferences.getBoolean("logs_enabled", true)
            _isLogcatReaderAllowedFlow.value = sharedPreferences.getBoolean("logs_logcat_reader_allowed", true)
            _isLogLevelDebugFlow.value = sharedPreferences.getBoolean("logs_level_debug", false)
            _isLogLevelInfoFlow.value = sharedPreferences.getBoolean("logs_level_info", true)
            _isLogLevelWarnFlow.value = sharedPreferences.getBoolean("logs_level_warn", true)
            _isLogLevelErrorFlow.value = sharedPreferences.getBoolean("logs_level_error", true)
            _disabledLogCategoriesFlow.value = sharedPreferences.getStringSet("logs_disabled_categories", emptySet()) ?: emptySet()
            _disabledLogTagsFlow.value = sharedPreferences.getStringSet("logs_disabled_tags", emptySet()) ?: emptySet()
            syncLogSettingsToAppLogger()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        _customBackgroundUserPresetsFlow.value = getUserPresets()
        ensureDefaultWallpaperInitialized()
    }

    fun loadConfig(): ProxyConfig {
        // Use ProxyConfig() as single source of truth for all defaults
        val defaults = ProxyConfig()
        val bindHost = prefs.getString("bind_host", defaults.bindHost) ?: defaults.bindHost
        val bindPort = prefs.getInt("bind_port", defaults.bindPort)
        val savedSecretRaw = secretPrefs.getString("secret_hex", null) ?: prefs.getString("secret_hex", null)
        val savedSecret = savedSecretRaw?.let { WarpKeystoreEncryption.decrypt(it) }
        val secretHex = if (savedSecret.isNullOrBlank() || savedSecret == "dd00000000000000000000000000000000") {
            val generatedSecret = ProxyConfig.generateRandomSecret()
            secretPrefs.edit().putString("secret_hex", WarpKeystoreEncryption.encrypt(generatedSecret)).commit()
            if (prefs.contains("secret_hex")) {
                prefs.edit().remove("secret_hex").commit()
            }
            generatedSecret
        } else {
            secretPrefs.edit().putString("secret_hex", WarpKeystoreEncryption.encrypt(savedSecret)).commit()
            if (prefs.contains("secret_hex")) {
                prefs.edit().remove("secret_hex").commit()
            }
            savedSecret
        }
        // Миграция: если proxy_mode ещё не сохранён, читаем старый socks5_enabled
        val proxyModeName = if (prefs.contains("proxy_mode")) {
            prefs.getString("proxy_mode", ProxyMode.MTPROTO.name) ?: ProxyMode.MTPROTO.name
        } else {
            @Suppress("DEPRECATION")
            if (prefs.getBoolean("socks5_enabled", false)) ProxyMode.SOCKS5.name
            else ProxyMode.MTPROTO.name
        }
        val cfEnabled = prefs.getBoolean("cf_proxy_enabled", defaults.cfProxyEnabled)
        val currentMode = try { ProxyMode.valueOf(proxyModeName) } catch (_: Exception) { ProxyMode.MTPROTO }
        val activeWorker = getActiveWorker(currentMode)
        val savedCustomDomain = prefs.getString("custom_cf_domain", null)
        val customDomain = if (!activeWorker.isDeveloperWorker) {
            activeWorker.domain
        } else if (!savedCustomDomain.isNullOrBlank()) {
            ProxyConfig.sanitizeDomain(savedCustomDomain)
        } else {
            activeWorker.domain
        }

        val poolSize = if (prefs.contains("mtproto_standby_per_active_slot")) {
            prefs.getInt("mtproto_standby_per_active_slot", defaults.mtprotoStandbyPerActiveSlot)
        } else {
            prefs.getInt("pool_size", defaults.poolSize)
        }
        val autostart = prefs.getBoolean("autostart_on_boot", defaults.autostartOnBoot)
        val speedPresetName = com.mirrly.tgproxy.core.SpeedPreset.AUTO.name
        val savedTcpNoDelayMode = prefs.getString("tcp_nodelay_mode", null)
        val nagleEnabled = when (savedTcpNoDelayMode?.uppercase()) {
            "DISABLED", "ON" -> false
            else -> true
        }
        val tcpNoDelay = !nagleEnabled
        val tcpNoDelayModeName = if (nagleEnabled) com.mirrly.tgproxy.core.NagleMode.ENABLED.name else com.mirrly.tgproxy.core.NagleMode.DISABLED.name
        val savedBufferSizeBytes = prefs.getInt("buffer_size_bytes", defaults.bufferSizeBytes)
        val bufferMigrationDone = prefs.getBoolean("socket_buffer_autotune_migrated_v3", false)
        val bufferSizeBytes = if (!bufferMigrationDone && (savedBufferSizeBytes == 262144 || savedBufferSizeBytes == 131072) &&
            speedPresetName == com.mirrly.tgproxy.core.SpeedPreset.AUTO.name
        ) 0 else savedBufferSizeBytes
        if (!bufferMigrationDone) {
            prefs.edit()
                .putInt("buffer_size_bytes", bufferSizeBytes)
                .putBoolean("socket_buffer_autotune_migrated_v3", true)
                .apply()
        }
        val happyEyeballsDelayMs = prefs.getLong("happy_eyeballs_delay_ms", defaults.happyEyeballsDelayMs)
        val ipFamilyPreferenceName = prefs.getString("ip_family_preference", defaults.ipFamilyPreferenceName) ?: defaults.ipFamilyPreferenceName
        val webSocketKeepAliveSeconds = prefs.getInt("socket_keep_alive_seconds", defaults.webSocketKeepAliveSeconds)
        val socks5Port = prefs.getInt("socks5_port", defaults.socks5Port)
        val socks5Username = prefs.getString("socks5_username", defaults.socks5Username) ?: defaults.socks5Username
        val socks5PasswordRaw = secretPrefs.getString("socks5_password", null) ?: prefs.getString("socks5_password", defaults.socks5Password) ?: defaults.socks5Password
        val socks5Password = WarpKeystoreEncryption.decrypt(socks5PasswordRaw)
        val useDefaultWorkerSocks5 = prefs.getBoolean("use_default_worker_socks5", defaults.useDefaultWorkerSocks5)
        val socks5OptimisticResponse = prefs.getBoolean("socks5_optimistic_response", defaults.socks5OptimisticResponse)
        val socks5UplinkMode = prefs.getInt(KEY_SOCKS5_UPLINK_MODE, defaults.socks5UplinkMode)
        val targetTelegramPort = prefs.getInt("target_telegram_port", defaults.targetTelegramPort)
        val useWorkerMtproto = prefs.getBoolean("use_worker_mtproto", defaults.useWorkerMtproto)

        val isTlsRecordPaddingEnabled = prefs.getBoolean(KEY_TLS_RECORD_PADDING_ENABLED, defaults.isTlsRecordPaddingEnabled)
        val isTlsClientHelloSplitEnabled = prefs.getBoolean(KEY_TLS_CLIENT_HELLO_SPLIT_ENABLED, defaults.isTlsClientHelloSplitEnabled)
        val qosMigrationDone = prefs.getBoolean("qos_default_disabled_migrated_v2", false)
        val isBatteryGuardEnabled = if (!qosMigrationDone) {
            false
        } else {
            prefs.getBoolean("is_battery_guard_enabled", defaults.isBatteryGuardEnabled)
        }
        val batteryGuardThreshold = prefs.getInt("battery_guard_threshold", defaults.batteryGuardThreshold)
        val batteryGuardStopOnPowerSave = if (!qosMigrationDone) {
            false
        } else {
            prefs.getBoolean("battery_guard_stop_on_powersave", defaults.batteryGuardStopOnPowerSave)
        }
        val isAdaptiveQoSEnabled = if (!qosMigrationDone) {
            false
        } else {
            prefs.getBoolean("is_adaptive_qos_enabled", defaults.isAdaptiveQoSEnabled)
        }
        val isDialThrottlingEnabled = prefs.getBoolean("dial_throttling_enabled", defaults.isDialThrottlingEnabled)
        if (!qosMigrationDone) {
            prefs.edit()
                .putBoolean("is_battery_guard_enabled", isBatteryGuardEnabled)
                .putBoolean("battery_guard_stop_on_powersave", batteryGuardStopOnPowerSave)
                .putBoolean("is_adaptive_qos_enabled", isAdaptiveQoSEnabled)
                .putBoolean("qos_default_disabled_migrated_v2", true)
                .apply()
        }
        val enabledDohProviderIds = emptySet<String>()

        val uplinkModeName = com.mirrly.tgproxy.core.UplinkMode.WORKER.name
        var vpnUplinkModeName = prefs.getString("vpn_uplink_mode", defaults.vpnUplinkModeName) ?: defaults.vpnUplinkModeName
        if (vpnUplinkModeName != com.mirrly.tgproxy.core.UplinkMode.MASQUE.name && vpnUplinkModeName != com.mirrly.tgproxy.core.UplinkMode.AWG.name) {
            vpnUplinkModeName = com.mirrly.tgproxy.core.UplinkMode.AWG.name
        }
        val vpnMtu = prefs.getInt("vpn_mtu", defaults.vpnMtu)
        val vpnBlockQuic = prefs.getBoolean("vpn_block_quic", defaults.vpnBlockQuic)
        val vpnBlockIpv6Leaks = prefs.getBoolean("vpn_block_ipv6_leaks", defaults.vpnBlockIpv6Leaks)
        val vpnDnsModeName = prefs.getString("vpn_dns_mode", defaults.vpnDnsModeName) ?: defaults.vpnDnsModeName
        val vpnCustomDnsIps = prefs.getString("vpn_custom_dns_ips", defaults.vpnCustomDnsIps) ?: defaults.vpnCustomDnsIps
        val warpAccountId = prefs.getString("warp_account_id", defaults.warpAccountId) ?: defaults.warpAccountId
        val warpTokenRaw = secretPrefs.getString("warp_token", null) ?: prefs.getString("warp_token", defaults.warpToken) ?: defaults.warpToken
        val warpToken = WarpKeystoreEncryption.decrypt(warpTokenRaw)
        val warpLicenseKeyRaw = secretPrefs.getString("warp_license_key", null) ?: prefs.getString("warp_license_key", defaults.warpLicenseKey) ?: defaults.warpLicenseKey
        val warpLicenseKey = WarpKeystoreEncryption.decrypt(warpLicenseKeyRaw)
        val warpClientIpv4 = prefs.getString("warp_client_ipv4", defaults.warpClientIpv4) ?: defaults.warpClientIpv4
        val warpClientIpv6 = prefs.getString("warp_client_ipv6", defaults.warpClientIpv6) ?: defaults.warpClientIpv6
        val warpPeerEndpoint = prefs.getString("warp_peer_endpoint", defaults.warpPeerEndpoint) ?: defaults.warpPeerEndpoint
        val warpPeerPublicKey = prefs.getString("warp_peer_public_key", defaults.warpPeerPublicKey) ?: defaults.warpPeerPublicKey
        val warpPrivateKeyRaw = secretPrefs.getString("warp_private_key", null) ?: prefs.getString("warp_private_key", defaults.warpPrivateKey) ?: defaults.warpPrivateKey
        val warpPrivateKey = WarpKeystoreEncryption.decrypt(warpPrivateKeyRaw)
        val warpPublicKey = prefs.getString("warp_public_key", defaults.warpPublicKey) ?: defaults.warpPublicKey
        val warpP256PrivateKeyRaw = secretPrefs.getString("warp_p256_private_key", defaults.warpP256PrivateKey) ?: defaults.warpP256PrivateKey
        val warpP256PrivateKey = WarpKeystoreEncryption.decrypt(warpP256PrivateKeyRaw)
        val warpP256PublicKey = prefs.getString("warp_p256_public_key", defaults.warpP256PublicKey) ?: defaults.warpP256PublicKey
        val warpClientCert = prefs.getString("warp_client_cert", defaults.warpClientCert) ?: defaults.warpClientCert
        val warpMasqueAccountId = prefs.getString("warp_masque_account_id", defaults.warpMasqueAccountId) ?: defaults.warpMasqueAccountId
        val warpMasqueTokenRaw = secretPrefs.getString("warp_masque_token", null) ?: prefs.getString("warp_masque_token", defaults.warpMasqueToken) ?: defaults.warpMasqueToken
        val warpMasqueToken = WarpKeystoreEncryption.decrypt(warpMasqueTokenRaw)
        val warpMasquePeerEndpoint = prefs.getString("warp_masque_peer_endpoint", defaults.warpMasquePeerEndpoint) ?: defaults.warpMasquePeerEndpoint
        val warpMasquePeerPublicKey = prefs.getString("warp_masque_peer_public_key", defaults.warpMasquePeerPublicKey) ?: defaults.warpMasquePeerPublicKey
        val warpMasqueClientIpv4 = prefs.getString("warp_masque_client_ipv4", defaults.warpMasqueClientIpv4) ?: defaults.warpMasqueClientIpv4
        val warpMasqueClientIpv6 = prefs.getString("warp_masque_client_ipv6", defaults.warpMasqueClientIpv6) ?: defaults.warpMasqueClientIpv6
        val isWgValid = prefs.getBoolean("is_wg_valid", defaults.isWgValid)
        val isMasqueValid = prefs.getBoolean("is_masque_valid", defaults.isMasqueValid)
        val warpMasqueSni = prefs.getString("warp_masque_sni", defaults.warpMasqueSni)?.takeIf { it.isNotBlank() } ?: defaults.warpMasqueSni
        val isWarpPlus = prefs.getBoolean("is_warp_plus", defaults.isWarpPlus)
        val isWarpAccountActive = prefs.getBoolean("is_warp_account_active", defaults.isWarpAccountActive)
        val vlessUuidRaw = secretPrefs.getString("vless_uuid", null) ?: prefs.getString("vless_uuid", defaults.vlessUuid) ?: defaults.vlessUuid
        val vlessUuid = WarpKeystoreEncryption.decrypt(vlessUuidRaw)
        val vlessPath = prefs.getString("vless_path", defaults.vlessPath) ?: defaults.vlessPath
        val vlessDomain = prefs.getString("vless_domain", defaults.vlessDomain) ?: defaults.vlessDomain
        val vlessPresetId = prefs.getString("vless_preset_id", defaults.vlessPresetId) ?: defaults.vlessPresetId
        val vlessSecurity = prefs.getString("vless_security", defaults.vlessSecurity) ?: defaults.vlessSecurity
        val vlessPublicKey = prefs.getString("vless_public_key", defaults.vlessPublicKey) ?: defaults.vlessPublicKey
        val vlessShortId = prefs.getString("vless_short_id", defaults.vlessShortId) ?: defaults.vlessShortId
        val vlessFingerprint = prefs.getString("vless_fingerprint", defaults.vlessFingerprint) ?: defaults.vlessFingerprint
        val vlessSpiderX = prefs.getString("vless_spider_x", defaults.vlessSpiderX) ?: defaults.vlessSpiderX
        val vlessTransport = prefs.getString("vless_transport", defaults.vlessTransport) ?: defaults.vlessTransport
        val vlessFlow = prefs.getString("vless_flow", defaults.vlessFlow) ?: defaults.vlessFlow
        val vlessHeaderType = prefs.getString("vless_header_type", defaults.vlessHeaderType) ?: defaults.vlessHeaderType
        val vlessServerAddress = prefs.getString("vless_server_address", defaults.vlessServerAddress) ?: defaults.vlessServerAddress
        val vlessServerPort = prefs.getInt("vless_server_port", defaults.vlessServerPort)
        val vlessTlsSni = prefs.getString("vless_tls_sni", defaults.vlessTlsSni) ?: defaults.vlessTlsSni
        val vlessHostHeader = prefs.getString("vless_host_header", defaults.vlessHostHeader) ?: defaults.vlessHostHeader
        val useOperaVpnForVless = prefs.getBoolean("use_opera_vpn_for_vless", defaults.useOperaVpnForVless)
        val useOperaVpnForWarp = false
        val operaVpnEndpoint = prefs.getString("opera_vpn_endpoint", defaults.operaVpnEndpoint) ?: defaults.operaVpnEndpoint
        val operaVpnNodeId = prefs.getString("opera_vpn_node_id", defaults.operaVpnNodeId) ?: defaults.operaVpnNodeId
        val warpWorkerDomain = prefs.getString("warp_worker_domain", defaults.warpWorkerDomain) ?: defaults.warpWorkerDomain
        val isRegisterWarpViaWorker = prefs.getBoolean("is_register_warp_via_worker", defaults.isRegisterWarpViaWorker)
        val awgStrategyName = prefs.getString("awg_strategy_name", defaults.awgStrategyName) ?: defaults.awgStrategyName
        val awgCustomIni = prefs.getString("awg_custom_ini", defaults.awgCustomIni) ?: defaults.awgCustomIni

        return ProxyConfig(
            bindHost = bindHost,
            bindPort = bindPort,
            secretHex = secretHex,
            cfProxyEnabled = cfEnabled,
            customCfDomain = customDomain,
            isTlsRecordPaddingEnabled = isTlsRecordPaddingEnabled,
            isTlsClientHelloSplitEnabled = isTlsClientHelloSplitEnabled,
            mtprotoStandbyPerActiveSlotValue = poolSize,
            autostartOnBoot = autostart,
            speedPresetName = speedPresetName,
            tcpNoDelayModeName = tcpNoDelayModeName,
            tcpNoDelay = tcpNoDelay,
            bufferSizeBytes = bufferSizeBytes,
            happyEyeballsDelayMs = happyEyeballsDelayMs,
            ipFamilyPreferenceName = ipFamilyPreferenceName,
            webSocketKeepAliveSeconds = webSocketKeepAliveSeconds,
            socks5Port = socks5Port,
            socks5Username = socks5Username,
            socks5Password = socks5Password,
            socks5OptimisticResponse = socks5OptimisticResponse,
            socks5UplinkMode = socks5UplinkMode,
            targetTelegramPort = targetTelegramPort,
            useDefaultWorkerSocks5 = useDefaultWorkerSocks5,
            useWorkerMtproto = useWorkerMtproto,
            isBatteryGuardEnabled = isBatteryGuardEnabled,
            batteryGuardThreshold = batteryGuardThreshold,
            batteryGuardStopOnPowerSave = batteryGuardStopOnPowerSave,
            isAdaptiveQoSEnabled = isAdaptiveQoSEnabled,
            isDialThrottlingEnabled = isDialThrottlingEnabled,
            proxyModeName = proxyModeName,
            enabledDohProviderIds = enabledDohProviderIds,
            uplinkModeName = uplinkModeName,
            vpnUplinkModeName = vpnUplinkModeName,
            vpnMtu = vpnMtu,
            vpnBlockQuic = vpnBlockQuic,
            vpnBlockIpv6Leaks = vpnBlockIpv6Leaks,
            warpAccountId = warpAccountId,
            warpToken = warpToken,
            warpLicenseKey = warpLicenseKey,
            warpClientIpv4 = warpClientIpv4,
            warpClientIpv6 = warpClientIpv6,
            warpPeerEndpoint = warpPeerEndpoint,
            warpPeerPublicKey = warpPeerPublicKey,
            warpPrivateKey = warpPrivateKey,
            warpPublicKey = warpPublicKey,
            warpP256PrivateKey = warpP256PrivateKey,
            warpP256PublicKey = warpP256PublicKey,
            warpClientCert = warpClientCert,
            isWarpPlus = isWarpPlus,
            isWarpAccountActive = isWarpAccountActive,
            warpMasqueAccountId = warpMasqueAccountId,
            warpMasqueToken = warpMasqueToken,
            warpMasquePeerEndpoint = warpMasquePeerEndpoint,
            warpMasquePeerPublicKey = warpMasquePeerPublicKey,
            warpMasqueClientIpv4 = warpMasqueClientIpv4,
            warpMasqueClientIpv6 = warpMasqueClientIpv6,
            isWgValid = isWgValid,
            isMasqueValid = isMasqueValid,
            warpMasqueSni = warpMasqueSni,
            vlessUuid = vlessUuid,
            vlessPath = vlessPath,
            vlessDomain = vlessDomain,
            vlessPresetId = vlessPresetId,
            vlessSecurity = vlessSecurity,
            vlessPublicKey = vlessPublicKey,
            vlessShortId = vlessShortId,
            vlessFingerprint = vlessFingerprint,
            vlessSpiderX = vlessSpiderX,
            vlessTransport = vlessTransport,
            vlessFlow = vlessFlow,
            vlessHeaderType = vlessHeaderType,
            vlessServerAddress = vlessServerAddress,
            vlessServerPort = vlessServerPort,
            vlessTlsSni = vlessTlsSni,
            vlessHostHeader = vlessHostHeader,
            useOperaVpnForVless = useOperaVpnForVless,
            useOperaVpnForWarp = useOperaVpnForWarp,
            operaVpnEndpoint = operaVpnEndpoint,
            operaVpnNodeId = operaVpnNodeId,
            warpWorkerDomain = warpWorkerDomain,
            isRegisterWarpViaWorker = isRegisterWarpViaWorker,
            awgStrategyName = awgStrategyName,
            awgCustomIni = awgCustomIni,
            vpnDnsModeName = vpnDnsModeName,
            vpnCustomDnsIps = vpnCustomDnsIps
        )
    }

    fun saveConfig(config: ProxyConfig) {
        val sanitizedDomain = ProxyConfig.sanitizeDomain(config.customCfDomain)
        val relayWorker = getActiveWorker()
        val domainToSave = if (sanitizedDomain.isNotEmpty()) {
            sanitizedDomain
        } else {
            relayWorker.domain
        }
        config.customCfDomain = domainToSave

        val secretToSave = if (config.secretHex.isNotBlank() && config.secretHex != "dd00000000000000000000000000000000") {
            config.secretHex
        } else {
            val existingRaw = secretPrefs.getString("secret_hex", null) ?: prefs.getString("secret_hex", null)
            val existing = existingRaw?.let { WarpKeystoreEncryption.decrypt(it) }
            if (!existing.isNullOrBlank() && existing != "dd00000000000000000000000000000000") {
                existing
            } else {
                val newSec = ProxyConfig.generateRandomSecret()
                newSec
            }
        }
        config.secretHex = secretToSave
        secretPrefs.edit()
            .putString("secret_hex", WarpKeystoreEncryption.encrypt(secretToSave))
            .putString("warp_token", WarpKeystoreEncryption.encrypt(config.warpToken))
            .putString("warp_private_key", WarpKeystoreEncryption.encrypt(config.warpPrivateKey))
            .putString("warp_p256_private_key", WarpKeystoreEncryption.encrypt(config.warpP256PrivateKey))
            .putString("warp_masque_token", WarpKeystoreEncryption.encrypt(config.warpMasqueToken))
            .putString("vless_uuid", WarpKeystoreEncryption.encrypt(config.vlessUuid))
            .putString("socks5_password", WarpKeystoreEncryption.encrypt(config.socks5Password))
            .putString("warp_license_key", WarpKeystoreEncryption.encrypt(config.warpLicenseKey))
            .apply()

        prefs.edit()
            .remove("secret_hex")
            .remove("warp_token")
            .remove("warp_private_key")
            .remove("warp_p256_private_key")
            .remove("warp_masque_token")
            .remove("vless_uuid")
            .remove("socks5_password")
            .remove("warp_license_key")
            .putString("bind_host", config.bindHost)
            .putInt("bind_port", config.bindPort)
            .putBoolean("cf_proxy_enabled", config.cfProxyEnabled)
            .putString("custom_cf_domain", domainToSave)
            .putInt("pool_size", config.poolSize)
            .putInt("mtproto_standby_per_active_slot", config.mtprotoStandbyPerActiveSlot)
            .putBoolean("autostart_on_boot", config.autostartOnBoot)
            .putString("speed_preset", config.speedPresetName)
            .putString("tcp_nodelay_mode", config.tcpNoDelayModeName)
            .putBoolean("tcp_nodelay", config.tcpNoDelay)
            .putInt("buffer_size_bytes", config.bufferSizeBytes)
            .putBoolean(KEY_SOCKET_BUFFER_AUTOTUNE_MIGRATED, true)
            .putLong("happy_eyeballs_delay_ms", config.happyEyeballsDelayMs)
            .putString("ip_family_preference", config.ipFamilyPreferenceName)
            .putInt("socket_keep_alive_seconds", config.webSocketKeepAliveSeconds)
            .putInt("socks5_port", config.socks5Port)
            .putString("socks5_username", config.socks5Username)
            .putBoolean("socks5_optimistic_response", config.socks5OptimisticResponse)
            .putInt(KEY_SOCKS5_UPLINK_MODE, config.socks5UplinkMode)
            .putInt("target_telegram_port", config.targetTelegramPort)
            .putBoolean("use_default_worker_socks5", config.useDefaultWorkerSocks5)
            .putBoolean("use_worker_mtproto", config.useWorkerMtproto)
            .putBoolean("is_battery_guard_enabled", config.isBatteryGuardEnabled)
            .putInt("battery_guard_threshold", config.batteryGuardThreshold)
            .putBoolean("battery_guard_stop_on_powersave", config.batteryGuardStopOnPowerSave)
            .putBoolean("is_adaptive_qos_enabled", config.isAdaptiveQoSEnabled)
            .putBoolean("dial_throttling_enabled", config.isDialThrottlingEnabled)
            .putString("proxy_mode", config.proxyModeName)
            .putStringSet("enabled_doh_providers", config.enabledDohProviderIds)
            .putString("uplink_mode", config.uplinkModeName)
            .putString("warp_account_id", config.warpAccountId)
            .putString("warp_client_ipv4", config.warpClientIpv4)
            .putString("warp_client_ipv6", config.warpClientIpv6)
            .putString("warp_peer_endpoint", config.warpPeerEndpoint)
            .putString("warp_peer_public_key", config.warpPeerPublicKey)
            .putString("warp_public_key", config.warpPublicKey)
            .putString("warp_p256_public_key", config.warpP256PublicKey)
            .putString("warp_client_cert", config.warpClientCert)
            .putString("warp_masque_account_id", config.warpMasqueAccountId)
            .putString("warp_masque_peer_endpoint", config.warpMasquePeerEndpoint)
            .putString("warp_masque_peer_public_key", config.warpMasquePeerPublicKey)
            .putString("warp_masque_client_ipv4", config.warpMasqueClientIpv4)
            .putString("warp_masque_client_ipv6", config.warpMasqueClientIpv6)
            .putString("warp_masque_sni", config.warpMasqueSni)
            .putBoolean("is_wg_valid", config.isWgValid)
            .putBoolean("is_masque_valid", config.isMasqueValid)
            .putBoolean("is_warp_plus", config.isWarpPlus)
            .putBoolean("is_warp_account_active", config.isWarpAccountActive)
            .putString("vless_path", config.vlessPath)
            .putString("vless_domain", config.vlessDomain)
            .putString("vless_preset_id", config.vlessPresetId)
            .putString("vless_security", config.vlessSecurity)
            .putString("vless_public_key", config.vlessPublicKey)
            .putString("vless_short_id", config.vlessShortId)
            .putString("vless_fingerprint", config.vlessFingerprint)
            .putString("vless_spider_x", config.vlessSpiderX)
            .putString("vless_transport", config.vlessTransport)
            .putString("vless_flow", config.vlessFlow)
            .putString("vless_header_type", config.vlessHeaderType)
            .putString("vless_server_address", config.vlessServerAddress)
            .putInt("vless_server_port", config.vlessServerPort)
            .putString("vless_tls_sni", config.vlessTlsSni)
            .putString("vless_host_header", config.vlessHostHeader)
            .putBoolean("use_opera_vpn_for_vless", config.useOperaVpnForVless)
            .putBoolean("use_opera_vpn_for_warp", config.useOperaVpnForWarp)
            .putString("opera_vpn_endpoint", config.operaVpnEndpoint)
            .putString("opera_vpn_node_id", config.operaVpnNodeId)
            .putString("warp_worker_domain", config.warpWorkerDomain)
            .putBoolean("is_register_warp_via_worker", config.isRegisterWarpViaWorker)
            .putString("awg_strategy_name", config.awgStrategyName)
            .putString("awg_custom_ini", config.awgCustomIni)
            .putString("vpn_uplink_mode", config.vpnUplinkModeName)
            .putInt("vpn_mtu", config.vpnMtu)
            .putBoolean("vpn_block_quic", config.vpnBlockQuic)
            .putBoolean("vpn_block_ipv6_leaks", config.vpnBlockIpv6Leaks)
            .putString("vpn_dns_mode", config.vpnDnsModeName)
            .putString("vpn_custom_dns_ips", config.vpnCustomDnsIps)
            .apply()

        _isSocks5Flow.value = config.isSocks5Mode
        _proxyModeFlow.value = config.proxyMode
        _uplinkModeFlow.value = config.uplinkMode
        _vpnUplinkModeFlow.value = config.vpnUplinkMode
    }

    fun getProxyMode(): ProxyMode {
        val modeName = prefs.getString("proxy_mode", ProxyMode.MTPROTO.name)
        return try {
            ProxyMode.valueOf(modeName ?: ProxyMode.MTPROTO.name)
        } catch (_: Exception) {
            ProxyMode.MTPROTO
        }
    }

    fun setProxyMode(mode: ProxyMode) {
        prefs.edit().putString("proxy_mode", mode.name).apply()
        _proxyModeFlow.value = mode
        _isSocks5Flow.value = mode == ProxyMode.SOCKS5
    }

    fun setSocks5OptimisticResponse(enabled: Boolean) {
        prefs.edit().putBoolean("socks5_optimistic_response", enabled).apply()
        com.mirrly.tgproxy.core.NativeProxy.setSocks5Optimistic(enabled)
    }

    fun isSocks5OptimisticResponse(): Boolean {
        return prefs.getBoolean("socks5_optimistic_response", true)
    }

    fun setSocks5UplinkMode(mode: Int) {
        prefs.edit().putInt(KEY_SOCKS5_UPLINK_MODE, mode).apply()
        _socks5UplinkModeFlow.value = mode
        try {
            MirrlyApplication.instance.config.socks5UplinkMode = mode
        } catch (_: Throwable) {}
        com.mirrly.tgproxy.core.NativeProxy.setSocks5UplinkMode(mode)
    }

    fun getSocks5UplinkMode(): Int {
        return prefs.getInt(KEY_SOCKS5_UPLINK_MODE, 0)
    }

    fun setTargetTelegramPort(port: Int) {
        prefs.edit().putInt("target_telegram_port", port).apply()
    }

    fun getTargetTelegramPort(): Int {
        return prefs.getInt("target_telegram_port", 443)
    }

    fun saveWarpProfile(profile: com.mirrly.tgproxy.core.WarpProfile) {
        // commit() вместо apply() — блокирующая запись гарантирует сохранение warp_token на диск
        // до возврата из функции. Это предотвращает бесконечный цикл регистрации при убийстве процесса,
        // когда apply() не успевает завершить асинхронную запись до смерти процесса.
        secretPrefs.edit()
            .putString("warp_token", WarpKeystoreEncryption.encrypt(profile.token))
            .putString("warp_license_key", WarpKeystoreEncryption.encrypt(profile.licenseKey))
            .putString("warp_private_key", WarpKeystoreEncryption.encrypt(profile.privateKeyBase64))
            .putString("warp_p256_private_key", WarpKeystoreEncryption.encrypt(profile.p256PrivateKeyBase64))
            .putString("warp_masque_token", WarpKeystoreEncryption.encrypt(profile.masqueToken))
            .commit()

        prefs.edit()
            .remove("warp_license_key")
            .putString("warp_account_id", profile.accountId)
            .putString("warp_client_ipv4", profile.clientIpv4)
            .putString("warp_client_ipv6", profile.clientIpv6)
            .putString("warp_peer_endpoint", profile.peerEndpoint)
            .putString("warp_peer_public_key", profile.peerPublicKey)
            .putString("warp_public_key", profile.publicKeyBase64)
            .putString("warp_p256_public_key", profile.p256PublicKeyBase64)
            .putString("warp_client_cert", profile.clientCertBase64)
            .putString("warp_masque_account_id", profile.masqueAccountId)
            .putString("warp_masque_peer_endpoint", profile.masquePeerEndpoint)
            .putString("warp_masque_peer_public_key", profile.masquePeerPublicKey)
            .putString("warp_masque_client_ipv4", profile.masqueClientIpv4)
            .putString("warp_masque_client_ipv6", profile.masqueClientIpv6)
            .putBoolean("is_wg_valid", profile.isWgValid)
            .putBoolean("is_masque_valid", profile.isMasqueValid)
            .putBoolean("is_warp_plus", profile.isWarpPlus)
            .putBoolean("is_warp_account_active", profile.isWarpEnabled)
            .putBoolean("is_registered", profile.isRegistered)
            .putBoolean("is_activated", profile.isActivated)
            .putBoolean("credentials_valid", profile.credentialsValid)
            .putBoolean("data_plane_ready", profile.dataPlaneReady)
            .putString("entitlement", profile.entitlement.name)
            .putBoolean("needs_verification", profile.needsVerification)
            .putBoolean("is_offline_cached", profile.isOfflineCached)
            .putString("warp_api_endpoint", profile.apiEndpoint)
            .putString("warp_user_endpoint_override", profile.userEndpointOverride ?: "")
            .putString("warp_masque_api_endpoint", profile.masqueApiEndpoint)
            .putString("warp_masque_user_override", profile.masqueUserOverride ?: "")
            .putString("warp_measured_wg_endpoint", profile.measuredWgEndpoint ?: "")
            .putString("warp_measured_masque_endpoint", profile.measuredMasqueEndpoint ?: "")
            .apply()
    }

    fun getWarpProfile(): com.mirrly.tgproxy.core.WarpProfile? {
        val accountId = prefs.getString("warp_account_id", null) ?: return null
        if (accountId.isBlank()) return null
        val tokenRaw = secretPrefs.getString("warp_token", null) ?: return null
        val token = WarpKeystoreEncryption.decrypt(tokenRaw)
        val licenseKeyRaw = secretPrefs.getString("warp_license_key", null) ?: prefs.getString("warp_license_key", "") ?: ""
        val licenseKey = WarpKeystoreEncryption.decrypt(licenseKeyRaw)
        val clientIpv4 = prefs.getString("warp_client_ipv4", "172.16.0.2") ?: "172.16.0.2"
        val clientIpv6 = prefs.getString("warp_client_ipv6", "") ?: ""
        val peerEndpoint = prefs.getString("warp_peer_endpoint", "188.114.96.1:500") ?: "188.114.96.1:500"
        val peerPublicKey = prefs.getString("warp_peer_public_key", "") ?: ""
        val privateKeyRaw = secretPrefs.getString("warp_private_key", "") ?: ""
        val privateKey = WarpKeystoreEncryption.decrypt(privateKeyRaw)
        val publicKey = prefs.getString("warp_public_key", "") ?: ""
        val p256PrivateKeyRaw = secretPrefs.getString("warp_p256_private_key", "") ?: ""
        val p256PrivateKey = WarpKeystoreEncryption.decrypt(p256PrivateKeyRaw)
        val p256PublicKey = prefs.getString("warp_p256_public_key", "") ?: ""
        val clientCert = prefs.getString("warp_client_cert", "") ?: ""
        val masqueAccountId = prefs.getString("warp_masque_account_id", "") ?: ""
        val masqueTokenRaw = secretPrefs.getString("warp_masque_token", "") ?: ""
        val masqueToken = WarpKeystoreEncryption.decrypt(masqueTokenRaw)
        val masquePeerEndpoint = prefs.getString("warp_masque_peer_endpoint", "") ?: ""
        val masquePeerPublicKey = prefs.getString("warp_masque_peer_public_key", "") ?: ""
        val masqueClientIpv4 = prefs.getString("warp_masque_client_ipv4", "") ?: ""
        val masqueClientIpv6 = prefs.getString("warp_masque_client_ipv6", "") ?: ""
        val isWgValid = prefs.getBoolean("is_wg_valid", accountId.isNotBlank() && accountId != "mirrly-warp-bootstrap-id")
        val isMasqueValid = prefs.getBoolean("is_masque_valid", p256PrivateKey.isNotBlank())
        val isWarpPlus = prefs.getBoolean("is_warp_plus", false)
        val isWarpAccountActive = prefs.getBoolean("is_warp_account_active", false)
        val isReg = prefs.getBoolean("is_registered", true)
        val isAct = prefs.getBoolean("is_activated", isWarpAccountActive)
        val credsValid = prefs.getBoolean("credentials_valid", isWgValid || isMasqueValid)
        val dpReady = prefs.getBoolean("data_plane_ready", isAct && (isWgValid || isMasqueValid))
        val entName = prefs.getString("entitlement", "") ?: ""
        val entitlement = try {
            if (entName.isNotBlank()) com.mirrly.tgproxy.core.WarpEntitlement.valueOf(entName) else if (isWarpPlus) com.mirrly.tgproxy.core.WarpEntitlement.PLUS else com.mirrly.tgproxy.core.WarpEntitlement.FREE
        } catch (_: Exception) {
            if (isWarpPlus) com.mirrly.tgproxy.core.WarpEntitlement.PLUS else com.mirrly.tgproxy.core.WarpEntitlement.FREE
        }
        val needsVer = prefs.getBoolean("needs_verification", false)
        val isOffline = prefs.getBoolean("is_offline_cached", false)
        val apiEndpoint = prefs.getString("warp_api_endpoint", "") ?: ""
        val userEndpointOverride = prefs.getString("warp_user_endpoint_override", "").takeIf { !it.isNullOrBlank() }
        val masqueApiEndpoint = prefs.getString("warp_masque_api_endpoint", "") ?: ""
        val masqueUserOverride = prefs.getString("warp_masque_user_override", "").takeIf { !it.isNullOrBlank() }
        val measuredWgEndpoint = prefs.getString("warp_measured_wg_endpoint", "").takeIf { !it.isNullOrBlank() }
        val measuredMasqueEndpoint = prefs.getString("warp_measured_masque_endpoint", "").takeIf { !it.isNullOrBlank() }

        return com.mirrly.tgproxy.core.WarpProfile(
            accountId = accountId,
            token = token,
            licenseKey = licenseKey,
            clientIpv4 = clientIpv4,
            clientIpv6 = clientIpv6,
            peerEndpoint = peerEndpoint,
            peerPublicKey = peerPublicKey,
            privateKeyBase64 = privateKey,
            publicKeyBase64 = publicKey,
            p256PrivateKeyBase64 = p256PrivateKey,
            p256PublicKeyBase64 = p256PublicKey,
            clientCertBase64 = clientCert,
            isWarpPlus = isWarpPlus,
            isWarpEnabled = isWarpAccountActive,
            masqueAccountId = masqueAccountId,
            masqueToken = masqueToken,
            masquePeerEndpoint = masquePeerEndpoint,
            masquePeerPublicKey = masquePeerPublicKey,
            masqueClientIpv4 = masqueClientIpv4,
            masqueClientIpv6 = masqueClientIpv6,
            isRegistered = isReg,
            isActivated = isAct,
            credentialsValid = credsValid,
            dataPlaneReady = dpReady,
            entitlement = entitlement,
            needsVerification = needsVer,
            isOfflineCached = isOffline,
            isWgValid = isWgValid,
            isMasqueValid = isMasqueValid,
            apiEndpoint = apiEndpoint,
            userEndpointOverride = userEndpointOverride,
            masqueApiEndpoint = masqueApiEndpoint,
            masqueUserOverride = masqueUserOverride,
            measuredWgEndpoint = measuredWgEndpoint,
            measuredMasqueEndpoint = measuredMasqueEndpoint
        )
    }

    fun setUplinkMode(mode: com.mirrly.tgproxy.core.UplinkMode) {
        prefs.edit().putString("uplink_mode", com.mirrly.tgproxy.core.UplinkMode.WORKER.name).apply()
        _uplinkModeFlow.value = com.mirrly.tgproxy.core.UplinkMode.WORKER
    }

    fun getUplinkMode(): com.mirrly.tgproxy.core.UplinkMode = com.mirrly.tgproxy.core.UplinkMode.WORKER

    fun setVpnUplinkMode(mode: com.mirrly.tgproxy.core.UplinkMode) {
        prefs.edit().putString("vpn_uplink_mode", mode.name).apply()
        _vpnUplinkModeFlow.value = mode
    }

    fun getVpnUplinkMode(): com.mirrly.tgproxy.core.UplinkMode = _vpnUplinkModeFlow.value

    fun setVpnDnsMode(mode: com.mirrly.tgproxy.core.VpnDnsMode) {
        prefs.edit().putString("vpn_dns_mode", mode.name).apply()
        _vpnDnsModeFlow.value = mode
    }

    fun getVpnDnsMode(): com.mirrly.tgproxy.core.VpnDnsMode = _vpnDnsModeFlow.value

    fun setVpnCustomDnsIps(ips: String) {
        val trimmed = ips.trim()
        prefs.edit().putString("vpn_custom_dns_ips", trimmed).apply()
        _vpnCustomDnsIpsFlow.value = trimmed
    }

    fun getVpnCustomDnsIps(): String = _vpnCustomDnsIpsFlow.value

    fun isWarpRegisterViaWorker(): Boolean {
        return prefs.getBoolean("is_register_warp_via_worker", true)
    }

    fun setWarpRegisterViaWorker(enabled: Boolean) {
        prefs.edit().putBoolean("is_register_warp_via_worker", enabled).apply()
        com.mirrly.tgproxy.core.WarpAccountManager.isRegisterViaWorker = enabled
    }

    fun getEnabledDohProviderIds(): Set<String> = emptySet()

    fun setEnabledDohProviderIds(ids: Set<String>) {
        // No-op: DoH is removed from MTProto and SOCKS5 pipelines
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

    // ── Custom Wallpaper Engine ─────────────────────────────────────────────

    private val wallpaperMutex = kotlinx.coroutines.sync.Mutex()

    private fun wallpaperFile(): File = context.filesDir.resolve(WALLPAPER_FILE_NAME)
    private fun wallpaperVideoFile(): File = context.filesDir.resolve(WALLPAPER_VIDEO_FILE_NAME)
    private fun wallpaperVideoPreviewFile(): File = context.filesDir.resolve(WALLPAPER_VIDEO_PREVIEW_NAME)

    fun getWallpaperPreviewFile(): File? {
        val type = _customBackgroundTypeFlow.value
        return if (type == WALLPAPER_TYPE_VIDEO) {
            val f = wallpaperVideoPreviewFile()
            if (f.isFile && f.length() > 0L) f else null
        } else {
            val f = wallpaperFile()
            if (f.isFile && f.length() > 0L) f else null
        }
    }

    private fun resolveStoredWallpaperPath(): String? {
        if (!prefs.getBoolean(KEY_WALLPAPER_ENABLED, false)) return null
        val type = prefs.getString(KEY_WALLPAPER_TYPE, WALLPAPER_TYPE_IMAGE) ?: WALLPAPER_TYPE_IMAGE
        val file = if (type == WALLPAPER_TYPE_VIDEO) wallpaperVideoFile() else wallpaperFile()
        return if (file.isFile && file.length() > 0L) file.absolutePath else null
    }

    private fun resolveStoredVideoMetadata(): WallpaperProcessor.VideoMetadata? {
        if (!prefs.getBoolean(KEY_WALLPAPER_ENABLED, false)) return null
        val type = prefs.getString(KEY_WALLPAPER_TYPE, WALLPAPER_TYPE_IMAGE)
        if (type != WALLPAPER_TYPE_VIDEO) return null
        val videoFile = wallpaperVideoFile()
        if (!videoFile.isFile || videoFile.length() == 0L) return null
        val width = prefs.getInt(KEY_WALLPAPER_VIDEO_WIDTH, 1080)
        val height = prefs.getInt(KEY_WALLPAPER_VIDEO_HEIGHT, 1920)
        val duration = prefs.getInt(KEY_WALLPAPER_VIDEO_DURATION, 1)
        val size = prefs.getLong(KEY_WALLPAPER_VIDEO_SIZE, videoFile.length())
        return WallpaperProcessor.VideoMetadata(
            width = width,
            height = height,
            durationSec = duration,
            fileSizeBytes = size,
            mimeType = "video/mp4"
        )
    }

    /**
     * Copies the picked image into `filesDir/custom_wallpaper.jpg` (never stores the raw
     * `content://` URI: its read grant expires after reboot). Decoding is subsampled and
     * EXIF-normalized on [Dispatchers.IO]; the previous wallpaper stays active until the new
     * file is fully written, then the swap is a single atomic rename.
     */
    suspend fun setCustomWallpaper(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        wallpaperMutex.lock()
        val tmp = context.filesDir.resolve("$WALLPAPER_FILE_NAME.tmp")
        try {
            val (w, h) = WallpaperProcessor.importToFile(context.contentResolver, uri, tmp)
            val target = wallpaperFile()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            // Remove previous video files if any
            try { wallpaperVideoFile().delete() } catch (_: Throwable) {}
            try { wallpaperVideoPreviewFile().delete() } catch (_: Throwable) {}

            val revision = System.currentTimeMillis()
            prefs.edit()
                .putBoolean(KEY_WALLPAPER_ENABLED, true)
                .putLong(KEY_WALLPAPER_REVISION, revision)
                .putString(KEY_WALLPAPER_TYPE, WALLPAPER_TYPE_IMAGE)
                .remove(KEY_WALLPAPER_ACTIVE_PRESET_ID)
                .apply()
            _customBackgroundActivePresetIdFlow.value = null
            _customBackgroundRevisionFlow.value = revision
            _customBackgroundTypeFlow.value = WALLPAPER_TYPE_IMAGE
            _customBackgroundVideoMetadataFlow.value = null
            _customBackgroundUriFlow.value = target.absolutePath
            AppLogger.i("Wallpaper", "Custom wallpaper stored: ${w}x$h, ${target.length() / 1024} KB")
            Result.success(Unit)
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Throwable) {
            tmp.delete()
            AppLogger.e("Wallpaper", "Wallpaper import failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            wallpaperMutex.unlock()
        }
    }

    /**
     * Copies the picked video into `filesDir/custom_wallpaper.mp4` and extracts its first-frame
     * preview into `filesDir/custom_wallpaper_video_preview.jpg`.
     */
    suspend fun setCustomVideoWallpaper(uri: Uri): Result<WallpaperProcessor.VideoMetadata> = withContext(Dispatchers.IO) {
        wallpaperMutex.lock()
        val tmpVideo = context.filesDir.resolve("$WALLPAPER_VIDEO_FILE_NAME.tmp")
        val tmpPreview = context.filesDir.resolve("$WALLPAPER_VIDEO_PREVIEW_NAME.tmp")
        try {
            val meta = WallpaperProcessor.importVideoToFile(context.contentResolver, uri, tmpVideo, tmpPreview)
            val targetVideo = wallpaperVideoFile()
            val targetPreview = wallpaperVideoPreviewFile()
            if (!tmpVideo.renameTo(targetVideo)) {
                tmpVideo.copyTo(targetVideo, overwrite = true)
                tmpVideo.delete()
            }
            if (tmpPreview.exists()) {
                if (!tmpPreview.renameTo(targetPreview)) {
                    tmpPreview.copyTo(targetPreview, overwrite = true)
                    tmpPreview.delete()
                }
            }
            // Remove previous image file if any
            try { wallpaperFile().delete() } catch (_: Throwable) {}

            val revision = System.currentTimeMillis()
            prefs.edit()
                .putBoolean(KEY_WALLPAPER_ENABLED, true)
                .putLong(KEY_WALLPAPER_REVISION, revision)
                .putString(KEY_WALLPAPER_TYPE, WALLPAPER_TYPE_VIDEO)
                .remove(KEY_WALLPAPER_ACTIVE_PRESET_ID)
                .putInt(KEY_WALLPAPER_VIDEO_WIDTH, meta.width)
                .putInt(KEY_WALLPAPER_VIDEO_HEIGHT, meta.height)
                .putInt(KEY_WALLPAPER_VIDEO_DURATION, meta.durationSec)
                .putLong(KEY_WALLPAPER_VIDEO_SIZE, meta.fileSizeBytes)
                .apply()
            _customBackgroundActivePresetIdFlow.value = null
            _customBackgroundRevisionFlow.value = revision
            _customBackgroundTypeFlow.value = WALLPAPER_TYPE_VIDEO
            _customBackgroundVideoMetadataFlow.value = meta
            _customBackgroundUriFlow.value = targetVideo.absolutePath
            AppLogger.i("Wallpaper", "Custom video wallpaper stored: ${meta.width}x${meta.height}, ${meta.durationSec}s, ${meta.fileSizeBytes / 1024} KB")
            Result.success(meta)
        } catch (e: CancellationException) {
            tmpVideo.delete()
            tmpPreview.delete()
            throw e
        } catch (e: Throwable) {
            tmpVideo.delete()
            tmpPreview.delete()
            AppLogger.e("Wallpaper", "Video wallpaper import failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            wallpaperMutex.unlock()
        }
    }

    /**
     * Universal wallpaper import: auto-detects whether [uri] is a video or image.
     * If the initial guess fails, gracefully tries the alternate format before returning an error.
     */
    suspend fun setCustomMediaWallpaper(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val looksLikeVideo = WallpaperProcessor.isVideoUri(context.contentResolver, uri)
        if (looksLikeVideo) {
            val videoResult = setCustomVideoWallpaper(uri)
            if (videoResult.isSuccess) {
                return@withContext Result.success(WALLPAPER_TYPE_VIDEO)
            }
            // Fallback: try image in case video probe was a false positive
            val imgResult = setCustomWallpaper(uri)
            if (imgResult.isSuccess) {
                return@withContext Result.success(WALLPAPER_TYPE_IMAGE)
            }
            Result.failure(videoResult.exceptionOrNull() ?: IOException("Failed to load video or image"))
        } else {
            val imgResult = setCustomWallpaper(uri)
            if (imgResult.isSuccess) {
                return@withContext Result.success(WALLPAPER_TYPE_IMAGE)
            }
            // Fallback: try video in case image decoder rejected a valid video stream
            val videoResult = setCustomVideoWallpaper(uri)
            if (videoResult.isSuccess) {
                return@withContext Result.success(WALLPAPER_TYPE_VIDEO)
            }
            Result.failure(imgResult.exceptionOrNull() ?: IOException("Failed to load image or video"))
        }
    }

    /** Deletes the sandboxed files and returns to the AMOLED background (orbs come back). */
    fun clearCustomWallpaper() {
        try {
            wallpaperFile().delete()
            wallpaperVideoFile().delete()
            wallpaperVideoPreviewFile().delete()
        } catch (e: Exception) {
            AppLogger.w("Wallpaper", "Wallpaper delete failed: ${e.message}")
        }
        prefs.edit()
            .putBoolean(KEY_WALLPAPER_ENABLED, false)
            .remove(KEY_WALLPAPER_REVISION)
            .remove(KEY_WALLPAPER_TYPE)
            .remove(KEY_WALLPAPER_ACTIVE_PRESET_ID)
            .apply()
        _customBackgroundActivePresetIdFlow.value = null
        _customBackgroundTypeFlow.value = WALLPAPER_TYPE_IMAGE
        _customBackgroundVideoMetadataFlow.value = null
        _customBackgroundUriFlow.value = null
    }

    /** @param persist false while a slider is dragged (live preview), true on release. */
    fun setWallpaperBlur(radiusDp: Float, persist: Boolean = true) {
        val value = radiusDp.coerceIn(WALLPAPER_BLUR_MIN, WALLPAPER_BLUR_MAX)
        _customBackgroundBlurFlow.value = value
        if (persist) prefs.edit().putFloat(KEY_WALLPAPER_BLUR, value).apply()
    }

    /** @param persist false while a slider is dragged (live preview), true on release. */
    fun setWallpaperDim(dimFactor: Float, persist: Boolean = true) {
        val value = dimFactor.coerceIn(WALLPAPER_DIM_MIN, WALLPAPER_DIM_MAX)
        _customBackgroundDimFlow.value = value
        if (persist) prefs.edit().putFloat(KEY_WALLPAPER_DIM, value).apply()
    }

    fun setWallpaperParticlesEnabled(enabled: Boolean) {
        _customBackgroundParticlesFlow.value = enabled
        prefs.edit().putBoolean(KEY_WALLPAPER_PARTICLES, enabled).apply()
    }

    fun setWallpaperVideoPowerSave(enabled: Boolean) {
        _customBackgroundVideoPowerSaveFlow.value = enabled
        prefs.edit().putBoolean(KEY_WALLPAPER_VIDEO_POWER_SAVE, enabled).apply()
    }

    // ── Wallpaper Presets Engine ─────────────────────────────────────────────

    fun ensureDefaultWallpaperInitialized() {
        val alreadyInitialized = prefs.getBoolean(KEY_WALLPAPER_DEFAULT_SPACE_TOILET_INIT, false)
        if (!alreadyInitialized) {
            try {
                val targetVideo = wallpaperVideoFile()
                val targetPreview = wallpaperVideoPreviewFile()
                val assetVideoName = "wallpapers/space_toilet.mp4"
                val assetPreviewName = "wallpapers/space_toilet_preview.jpg"

                context.assets.open(assetVideoName).use { input ->
                    FileOutputStream(targetVideo).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                context.assets.open(assetPreviewName).use { input ->
                    FileOutputStream(targetPreview).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }

                val revision = System.currentTimeMillis()
                prefs.edit()
                    .putBoolean(KEY_WALLPAPER_DEFAULT_SPACE_TOILET_INIT, true)
                    .putBoolean(KEY_WALLPAPER_ENABLED, true)
                    .putLong(KEY_WALLPAPER_REVISION, revision)
                    .putString(KEY_WALLPAPER_TYPE, WALLPAPER_TYPE_VIDEO)
                    .putString(KEY_WALLPAPER_ACTIVE_PRESET_ID, WallpaperPreset.PRESET_ID_SPACE_TOILET)
                    .putFloat(KEY_WALLPAPER_BLUR, 0f)
                    .putFloat(KEY_WALLPAPER_DIM, 0.50f)
                    .putInt(KEY_WALLPAPER_VIDEO_WIDTH, 1080)
                    .putInt(KEY_WALLPAPER_VIDEO_HEIGHT, 1920)
                    .putInt(KEY_WALLPAPER_VIDEO_DURATION, 10)
                    .putLong(KEY_WALLPAPER_VIDEO_SIZE, targetVideo.length())
                    .apply()

                _customBackgroundRevisionFlow.value = revision
                _customBackgroundTypeFlow.value = WALLPAPER_TYPE_VIDEO
                _customBackgroundUriFlow.value = targetVideo.absolutePath
                _customBackgroundActivePresetIdFlow.value = WallpaperPreset.PRESET_ID_SPACE_TOILET
                _customBackgroundBlurFlow.value = 0f
                _customBackgroundDimFlow.value = 0.50f
                _customBackgroundVideoMetadataFlow.value = resolveStoredVideoMetadata()

                AppLogger.i("Wallpaper", "Default Space Toilet wallpaper initialized successfully.")
            } catch (e: Throwable) {
                AppLogger.w("Wallpaper", "Failed to initialize default space toilet wallpaper: ${e.message}")
            }
        }
    }

    suspend fun applyPreset(preset: WallpaperPreset): Result<Unit> = withContext(Dispatchers.IO) {
        wallpaperMutex.lock()
        try {
            val revision = System.currentTimeMillis()
            if (preset.isBuiltIn) {
                if (preset.type == WALLPAPER_TYPE_VIDEO) {
                    val targetVideo = wallpaperVideoFile()
                    val targetPreview = wallpaperVideoPreviewFile()
                    val assetVideo = preset.assetPath ?: "wallpapers/space_toilet.mp4"

                    context.assets.open(assetVideo).use { input ->
                        FileOutputStream(targetVideo).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    try {
                        context.assets.open("wallpapers/space_toilet_preview.jpg").use { input ->
                            FileOutputStream(targetPreview).use { output ->
                                input.copyTo(output)
                                output.fd.sync()
                            }
                        }
                    } catch (_: Throwable) {}

                    try { wallpaperFile().delete() } catch (_: Throwable) {}

                    prefs.edit()
                        .putBoolean(KEY_WALLPAPER_ENABLED, true)
                        .putLong(KEY_WALLPAPER_REVISION, revision)
                        .putString(KEY_WALLPAPER_TYPE, WALLPAPER_TYPE_VIDEO)
                        .putString(KEY_WALLPAPER_ACTIVE_PRESET_ID, preset.id)
                        .putFloat(KEY_WALLPAPER_BLUR, preset.blurDp)
                        .putFloat(KEY_WALLPAPER_DIM, preset.dimFactor)
                        .putInt(KEY_WALLPAPER_VIDEO_WIDTH, 1080)
                        .putInt(KEY_WALLPAPER_VIDEO_HEIGHT, 1920)
                        .putInt(KEY_WALLPAPER_VIDEO_DURATION, 10)
                        .putLong(KEY_WALLPAPER_VIDEO_SIZE, targetVideo.length())
                        .apply()

                    _customBackgroundRevisionFlow.value = revision
                    _customBackgroundTypeFlow.value = WALLPAPER_TYPE_VIDEO
                    _customBackgroundActivePresetIdFlow.value = preset.id
                    _customBackgroundBlurFlow.value = preset.blurDp
                    _customBackgroundDimFlow.value = preset.dimFactor
                    _customBackgroundUriFlow.value = targetVideo.absolutePath
                    _customBackgroundVideoMetadataFlow.value = resolveStoredVideoMetadata()
                } else {
                    val targetImage = wallpaperFile()
                    val assetImage = preset.assetPath ?: return@withContext Result.failure(IOException("Missing asset image"))
                    context.assets.open(assetImage).use { input ->
                        FileOutputStream(targetImage).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    try { wallpaperVideoFile().delete() } catch (_: Throwable) {}
                    try { wallpaperVideoPreviewFile().delete() } catch (_: Throwable) {}

                    prefs.edit()
                        .putBoolean(KEY_WALLPAPER_ENABLED, true)
                        .putLong(KEY_WALLPAPER_REVISION, revision)
                        .putString(KEY_WALLPAPER_TYPE, WALLPAPER_TYPE_IMAGE)
                        .putString(KEY_WALLPAPER_ACTIVE_PRESET_ID, preset.id)
                        .putFloat(KEY_WALLPAPER_BLUR, preset.blurDp)
                        .putFloat(KEY_WALLPAPER_DIM, preset.dimFactor)
                        .remove(KEY_WALLPAPER_VIDEO_WIDTH)
                        .remove(KEY_WALLPAPER_VIDEO_HEIGHT)
                        .remove(KEY_WALLPAPER_VIDEO_DURATION)
                        .remove(KEY_WALLPAPER_VIDEO_SIZE)
                        .apply()

                    _customBackgroundRevisionFlow.value = revision
                    _customBackgroundTypeFlow.value = WALLPAPER_TYPE_IMAGE
                    _customBackgroundActivePresetIdFlow.value = preset.id
                    _customBackgroundBlurFlow.value = preset.blurDp
                    _customBackgroundDimFlow.value = preset.dimFactor
                    _customBackgroundUriFlow.value = targetImage.absolutePath
                    _customBackgroundVideoMetadataFlow.value = null
                }
            } else {
                val srcFile = preset.filePath?.let { File(it) }
                if (srcFile == null || !srcFile.exists()) {
                    return@withContext Result.failure(IOException("Preset file not found"))
                }
                if (preset.type == WALLPAPER_TYPE_VIDEO) {
                    val targetVideo = wallpaperVideoFile()
                    val targetPreview = wallpaperVideoPreviewFile()
                    srcFile.copyTo(targetVideo, overwrite = true)
                    preset.previewFilePath?.let { File(it) }?.takeIf { it.exists() }?.copyTo(targetPreview, overwrite = true)
                    try { wallpaperFile().delete() } catch (_: Throwable) {}

                    prefs.edit()
                        .putBoolean(KEY_WALLPAPER_ENABLED, true)
                        .putLong(KEY_WALLPAPER_REVISION, revision)
                        .putString(KEY_WALLPAPER_TYPE, WALLPAPER_TYPE_VIDEO)
                        .putString(KEY_WALLPAPER_ACTIVE_PRESET_ID, preset.id)
                        .putFloat(KEY_WALLPAPER_BLUR, preset.blurDp)
                        .putFloat(KEY_WALLPAPER_DIM, preset.dimFactor)
                        .apply()

                    _customBackgroundRevisionFlow.value = revision
                    _customBackgroundTypeFlow.value = WALLPAPER_TYPE_VIDEO
                    _customBackgroundActivePresetIdFlow.value = preset.id
                    _customBackgroundBlurFlow.value = preset.blurDp
                    _customBackgroundDimFlow.value = preset.dimFactor
                    _customBackgroundUriFlow.value = targetVideo.absolutePath
                    _customBackgroundVideoMetadataFlow.value = resolveStoredVideoMetadata()
                } else {
                    val targetImage = wallpaperFile()
                    srcFile.copyTo(targetImage, overwrite = true)
                    try { wallpaperVideoFile().delete() } catch (_: Throwable) {}
                    try { wallpaperVideoPreviewFile().delete() } catch (_: Throwable) {}

                    prefs.edit()
                        .putBoolean(KEY_WALLPAPER_ENABLED, true)
                        .putLong(KEY_WALLPAPER_REVISION, revision)
                        .putString(KEY_WALLPAPER_TYPE, WALLPAPER_TYPE_IMAGE)
                        .putString(KEY_WALLPAPER_ACTIVE_PRESET_ID, preset.id)
                        .putFloat(KEY_WALLPAPER_BLUR, preset.blurDp)
                        .putFloat(KEY_WALLPAPER_DIM, preset.dimFactor)
                        .apply()

                    _customBackgroundRevisionFlow.value = revision
                    _customBackgroundTypeFlow.value = WALLPAPER_TYPE_IMAGE
                    _customBackgroundActivePresetIdFlow.value = preset.id
                    _customBackgroundBlurFlow.value = preset.blurDp
                    _customBackgroundDimFlow.value = preset.dimFactor
                    _customBackgroundUriFlow.value = targetImage.absolutePath
                    _customBackgroundVideoMetadataFlow.value = null
                }
            }
            Result.success(Unit)
        } catch (e: Throwable) {
            AppLogger.e("Wallpaper", "Apply preset failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            wallpaperMutex.unlock()
        }
    }

    suspend fun saveCurrentAsUserPreset(title: String): Result<WallpaperPreset> = withContext(Dispatchers.IO) {
        val currentType = _customBackgroundTypeFlow.value
        val isVideo = currentType == WALLPAPER_TYPE_VIDEO
        val srcFile = if (isVideo) wallpaperVideoFile() else wallpaperFile()
        if (!srcFile.exists() || srcFile.length() == 0L) {
            return@withContext Result.failure(IOException("No active wallpaper to save as preset"))
        }

        val presetsDir = context.filesDir.resolve("wallpaper_presets").apply { mkdirs() }
        val presetId = "custom_${System.currentTimeMillis()}"
        val ext = if (isVideo) "mp4" else "jpg"
        val savedFile = presetsDir.resolve("$presetId.$ext")
        srcFile.copyTo(savedFile, overwrite = true)

        var savedPreviewFile: File? = null
        if (isVideo) {
            val previewSrc = wallpaperVideoPreviewFile()
            if (previewSrc.exists()) {
                val pf = presetsDir.resolve("${presetId}_preview.jpg")
                previewSrc.copyTo(pf, overwrite = true)
                savedPreviewFile = pf
            }
        }

        val preset = WallpaperPreset(
            id = presetId,
            title = title.trim().ifEmpty { "Мой пресет" },
            type = currentType,
            filePath = savedFile.absolutePath,
            previewFilePath = savedPreviewFile?.absolutePath ?: savedFile.absolutePath,
            blurDp = _customBackgroundBlurFlow.value,
            dimFactor = _customBackgroundDimFlow.value,
            isBuiltIn = false,
            createdAt = System.currentTimeMillis()
        )

        val list = getUserPresets().toMutableList()
        list.add(0, preset)
        saveUserPresetsList(list)
        _customBackgroundUserPresetsFlow.value = list
        _customBackgroundActivePresetIdFlow.value = presetId
        prefs.edit().putString(KEY_WALLPAPER_ACTIVE_PRESET_ID, presetId).apply()

        Result.success(preset)
    }

    fun deleteUserPreset(presetId: String) {
        val list = getUserPresets().toMutableList()
        val removed = list.find { it.id == presetId }
        if (removed != null) {
            list.remove(removed)
            saveUserPresetsList(list)
            _customBackgroundUserPresetsFlow.value = list
            removed.filePath?.let { File(it).delete() }
            removed.previewFilePath?.let { File(it).delete() }
            if (_customBackgroundActivePresetIdFlow.value == presetId) {
                _customBackgroundActivePresetIdFlow.value = null
                prefs.edit().remove(KEY_WALLPAPER_ACTIVE_PRESET_ID).apply()
            }
        }
    }

    fun getUserPresets(): List<WallpaperPreset> {
        val jsonStr = prefs.getString(KEY_WALLPAPER_USER_PRESETS, null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val result = mutableListOf<WallpaperPreset>()
            for (i in 0 until array.length()) {
                result.add(WallpaperPreset.fromJson(array.getJSONObject(i)))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveUserPresetsList(list: List<WallpaperPreset>) {
        val array = JSONArray()
        for (item in list) {
            array.put(item.toJson())
        }
        prefs.edit().putString(KEY_WALLPAPER_USER_PRESETS, array.toString()).apply()
    }

    // ── Worker Profiles Management ──────────────────────────────────────────

    companion object {
        const val KEY_ECH_ENABLED = "ech_enabled"
        const val KEY_WS_RANDOMIZATION_ENABLED = "ws_randomization_enabled"
        const val KEY_TLS_RECORD_PADDING_ENABLED = "tls_record_padding_enabled"
        const val KEY_TLS_CLIENT_HELLO_SPLIT_ENABLED = "tls_client_hello_split_enabled"
        const val KEY_ACTIVE_WORKER_ID_SOCKS5 = "active_worker_id_socks5"
        const val KEY_ACTIVE_WORKER_ID_MTPROTO = "active_worker_id_mtproto"
        const val KEY_USER_PRIMARY_WORKER_ID_SOCKS5 = "user_primary_worker_id_socks5"
        const val KEY_USER_PRIMARY_WORKER_ID_MTPROTO = "user_primary_worker_id_mtproto"
        const val KEY_SOCKS5_UPLINK_MODE = "socks5_uplink_mode"
        private const val KEY_SOCKET_BUFFER_AUTOTUNE_MIGRATED = "socket_buffer_autotune_migrated_v1"

        // Custom Wallpaper Engine
        const val WALLPAPER_FILE_NAME = "custom_wallpaper.jpg"
        const val WALLPAPER_VIDEO_FILE_NAME = "custom_wallpaper.mp4"
        const val WALLPAPER_VIDEO_PREVIEW_NAME = "custom_wallpaper_video_preview.jpg"
        const val WALLPAPER_BLUR_MIN = 0f
        const val WALLPAPER_BLUR_MAX = 25f
        const val WALLPAPER_BLUR_DEFAULT = 12f
        const val WALLPAPER_DIM_MIN = 0.10f
        const val WALLPAPER_DIM_MAX = 0.85f
        const val WALLPAPER_DIM_DEFAULT = 0.45f
        const val WALLPAPER_TYPE_IMAGE = "image"
        const val WALLPAPER_TYPE_VIDEO = "video"
        const val KEY_WALLPAPER_ACTIVE_PRESET_ID = "custom_wallpaper_active_preset_id"
        const val KEY_WALLPAPER_USER_PRESETS = "custom_wallpaper_user_presets"
        private const val KEY_WALLPAPER_DEFAULT_SPACE_TOILET_INIT = "wallpaper_default_space_toilet_v2"
        private const val KEY_WALLPAPER_ENABLED = "custom_wallpaper_enabled"
        private const val KEY_WALLPAPER_REVISION = "custom_wallpaper_revision"
        private const val KEY_WALLPAPER_TYPE = "custom_wallpaper_type"
        private const val KEY_WALLPAPER_BLUR = "custom_wallpaper_blur_dp"
        private const val KEY_WALLPAPER_DIM = "custom_wallpaper_dim"
        private const val KEY_WALLPAPER_PARTICLES = "custom_wallpaper_particles"
        private const val KEY_WALLPAPER_VIDEO_POWER_SAVE = "custom_wallpaper_video_power_save"
        private const val KEY_WALLPAPER_VIDEO_WIDTH = "custom_wallpaper_video_width"
        private const val KEY_WALLPAPER_VIDEO_HEIGHT = "custom_wallpaper_video_height"
        private const val KEY_WALLPAPER_VIDEO_DURATION = "custom_wallpaper_video_duration"
        private const val KEY_WALLPAPER_VIDEO_SIZE = "custom_wallpaper_video_size"

        /**
         * Создает диагностический отчет для службы поддержки, строго исключая
         * приватные ключи, токены, пароли и полные чувствительные URL (Task N20).
         */
        fun getRedactedDiagnosticReport(config: ProxyConfig): String {
            val report = JSONObject()
            report.put("schema_version", 2)
            report.put("timestamp_utc_ms", System.currentTimeMillis())
            report.put("uplink_mode", config.uplinkModeName)
            report.put("proxy_mode", config.proxyModeName)
            report.put("bind_port", config.bindPort)
            report.put("socks5_port", config.socks5Port)
            report.put("socks5_username", config.socks5Username)
            report.put("socks5_has_auth", config.hasSocks5Auth)
            report.put("socks5_password_redacted", if (config.socks5Password.isNotBlank()) "***REDACTED***" else "EMPTY")

            // WARP Diagnostics (без секретных ключей и полного токена)
            report.put("warp_account_id_redacted", if (config.warpAccountId.length > 8) "${config.warpAccountId.take(4)}...${config.warpAccountId.takeLast(4)}" else "***")
            report.put("warp_has_token", config.warpToken.isNotBlank())
            report.put("warp_has_private_key", config.warpPrivateKey.isNotBlank())
            report.put("warp_private_key_redacted", if (config.warpPrivateKey.isNotBlank()) "***REDACTED_KEY***" else "EMPTY")
            report.put("warp_license_key_redacted", if (config.warpLicenseKey.length >= 8) "${config.warpLicenseKey.take(4)}...${config.warpLicenseKey.takeLast(4)}" else "NONE")
            report.put("warp_client_ipv4", config.warpClientIpv4)
            report.put("warp_client_ipv6", config.warpClientIpv6)
            report.put("warp_peer_endpoint", config.warpPeerEndpoint)
            report.put("is_warp_plus", config.isWarpPlus)

            // VLESS Diagnostics (без полного UUID)
            report.put("vless_domain", config.vlessDomain)
            report.put("vless_transport", config.vlessTransport)
            report.put("vless_security", config.vlessSecurity)
            report.put("vless_uuid_redacted", if (config.vlessUuid.length >= 8) "${config.vlessUuid.take(4)}...${config.vlessUuid.takeLast(4)}" else "EMPTY")

            // VPN Subsystem
            report.put("vpn_is_running", MirrlyVpnService.isRunning)
            val vpnStatus = MirrlyVpnService.vpnStatus.value
            report.put("vpn_internal_state", vpnStatus.internalState.name)
            report.put("vpn_failure_reason", vpnStatus.failureReason.name)
            report.put("vpn_generation", vpnStatus.generation)
            report.put("vpn_bytes_in", vpnStatus.bytesIn)
            report.put("vpn_bytes_out", vpnStatus.bytesOut)
            report.put("vpn_active_tcp_flows", vpnStatus.activeTcpFlows)
            report.put("vpn_active_udp_sessions", vpnStatus.activeUdpSessions)

            return report.toString(2)
        }
    }

    fun getDeveloperWorkers(): List<WorkerProfile> = emptyList()

    fun getCustomWorkers(): List<WorkerProfile> {
        val jsonStr = prefs.getString("custom_workers_json", null) ?: return emptyList()
        val result = mutableListOf<WorkerProfile>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val domain = obj.getString("domain")
                val name = obj.optString("name", context.getString(R.string.pref_worker_custom_default_name))
                result.add(
                    WorkerProfile(
                        id = obj.getString("id"),
                        name = name,
                        domain = domain,
                        isDeveloperWorker = false,
                        isCloudflarePersonal = obj.optBoolean("is_cf_personal", false),
                        scriptVersion = obj.optInt("script_version", 0)
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
            obj.put("is_cf_personal", w.isCloudflarePersonal)
            obj.put("script_version", w.scriptVersion)
            array.put(obj)
        }
        prefs.edit().putString("custom_workers_json", array.toString()).apply()
    }

    fun addCustomWorker(
        name: String,
        domain: String,
        isCloudflarePersonal: Boolean = false,
        scriptVersion: Int = 0
    ): Result<WorkerProfile> {
        val formRes = com.mirrly.tgproxy.core.WorkerDomainNormalizer.normalizeForm(name, domain)
        val cleanDomain = formRes.normalizedDomain
        if (cleanDomain.isBlank()) {
            return Result.failure(IllegalArgumentException(context.getString(R.string.pref_worker_err_invalid_domain)))
        }

        val current = getCustomWorkers().toMutableList()
        val allExisting = current
        if (allExisting.any { it.domain.equals(cleanDomain, ignoreCase = true) }) {
            return Result.failure(IllegalStateException(context.getString(R.string.pref_worker_err_already_exists)))
        }

        val cleanName = formRes.normalizedName.ifBlank { context.getString(R.string.pref_worker_custom_indexed_name, current.size + 1) }
        val newWorker = WorkerProfile(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            domain = cleanDomain,
            isDeveloperWorker = false,
            isCloudflarePersonal = isCloudflarePersonal,
            scriptVersion = scriptVersion
        )
        current.add(newWorker)
        saveCustomWorkers(current)
        return Result.success(newWorker)
    }

    fun updateCustomWorkerScriptVersion(domain: String, scriptVersion: Int) {
        val current = getCustomWorkers()
        val updated = current.map {
            if (it.domain.equals(domain, ignoreCase = true)) {
                it.copy(scriptVersion = scriptVersion, isCloudflarePersonal = true)
            } else it
        }
        saveCustomWorkers(updated)
    }

    fun deleteCustomWorker(id: String) {
        val current = getCustomWorkers().filter { it.id != id }
        saveCustomWorkers(current)
        val nextWorker = current.firstOrNull()
        if (getActiveWorkerId(ProxyMode.MTPROTO) == id) {
            setActiveWorkerId(nextWorker?.id ?: "", mode = ProxyMode.MTPROTO, fromUserAction = true)
        }
        if (getActiveWorkerId(ProxyMode.SOCKS5) == id) {
            setActiveWorkerId(nextWorker?.id ?: "", mode = ProxyMode.SOCKS5, fromUserAction = true)
        }
        if (getUserPrimaryWorkerId(ProxyMode.MTPROTO) == id) {
            setUserPrimaryWorkerId(nextWorker?.id ?: "", mode = ProxyMode.MTPROTO)
        }
        if (getUserPrimaryWorkerId(ProxyMode.SOCKS5) == id) {
            setUserPrimaryWorkerId(nextWorker?.id ?: "", mode = ProxyMode.SOCKS5)
        }
    }

    fun getEffectiveProxyMode(): ProxyMode {
        val modeName = prefs.getString("proxy_mode", ProxyMode.MTPROTO.name) ?: ProxyMode.MTPROTO.name
        return try {
            ProxyMode.valueOf(modeName)
        } catch (_: Exception) {
            ProxyMode.MTPROTO
        }
    }

    fun getUserPrimaryWorkerId(): String = getUserPrimaryWorkerId(getEffectiveProxyMode())

    fun getUserPrimaryWorkerId(mode: ProxyMode): String {
        val primaryKey = when (mode) {
            ProxyMode.MTPROTO -> KEY_USER_PRIMARY_WORKER_ID_MTPROTO
            ProxyMode.SOCKS5 -> KEY_USER_PRIMARY_WORKER_ID_SOCKS5
        }
        val saved = prefs.getString(primaryKey, null)
            ?: prefs.getString("user_primary_worker_id", null)
        if (!saved.isNullOrBlank()) return saved
        val active = getActiveWorkerId(mode)
        prefs.edit().putString(primaryKey, active).apply()
        return active
    }

    fun setUserPrimaryWorkerId(id: String) = setUserPrimaryWorkerId(id, getEffectiveProxyMode())

    fun setUserPrimaryWorkerId(id: String, mode: ProxyMode) {
        val primaryKey = when (mode) {
            ProxyMode.MTPROTO -> KEY_USER_PRIMARY_WORKER_ID_MTPROTO
            ProxyMode.SOCKS5 -> KEY_USER_PRIMARY_WORKER_ID_SOCKS5
        }
        prefs.edit()
            .putString(primaryKey, id)
            .putString("user_primary_worker_id", id)
            .apply()
    }

    fun getActiveWorkerId(): String = getActiveWorkerId(getEffectiveProxyMode())

    fun getActiveWorkerId(mode: ProxyMode): String {
        val key = when (mode) {
            ProxyMode.MTPROTO -> KEY_ACTIVE_WORKER_ID_MTPROTO
            ProxyMode.SOCKS5 -> KEY_ACTIVE_WORKER_ID_SOCKS5
        }
        val saved = prefs.getString(key, null)
            ?: prefs.getString("active_worker_id", "") ?: ""
        val custom = getCustomWorkers()
        if (saved.isNotEmpty() && custom.any { it.id == saved }) {
            return saved
        }
        return custom.firstOrNull()?.id ?: ""
    }

    fun setActiveWorkerId(id: String, fromUserAction: Boolean = true) {
        setActiveWorkerId(id, getEffectiveProxyMode(), fromUserAction)
    }

    fun setActiveWorkerId(id: String, mode: ProxyMode, fromUserAction: Boolean = true) {
        val key = when (mode) {
            ProxyMode.MTPROTO -> KEY_ACTIVE_WORKER_ID_MTPROTO
            ProxyMode.SOCKS5 -> KEY_ACTIVE_WORKER_ID_SOCKS5
        }
        val primaryKey = when (mode) {
            ProxyMode.MTPROTO -> KEY_USER_PRIMARY_WORKER_ID_MTPROTO
            ProxyMode.SOCKS5 -> KEY_USER_PRIMARY_WORKER_ID_SOCKS5
        }
        prefs.edit()
            .putString(key, id)
            .putString("active_worker_id", id)
            .apply()
        if (fromUserAction) {
            prefs.edit()
                .putString(primaryKey, id)
                .putString("user_primary_worker_id", id)
                .apply()
            try {
                PredictivePreWarmManager.clearHotReserve()
            } catch (_: Exception) {}
        }
        if (mode == ProxyMode.MTPROTO) {
            _activeWorkerIdMtprotoFlow.value = id
        } else {
            _activeWorkerIdSocks5Flow.value = id
        }
        _activeWorkerIdFlow.value = id
        val worker = getActiveWorker(mode = mode, activeId = id)

        try {
            val app = com.mirrly.tgproxy.MirrlyApplication.instance
            val runningMode = app.config.proxyMode
            if (runningMode == mode) {
                app.config.customCfDomain = worker.domain
                app.config.useDefaultWorkerSocks5 = worker.isDeveloperWorker
                saveConfig(app.config)
                val shouldHotSwap = app.proxyServer.isRunning && (
                    app.config.isSocks5Mode || (runningMode == ProxyMode.MTPROTO && app.config.useWorkerMtproto)
                )
                if (shouldHotSwap) {
                    app.proxyServer.onWorkerChanged(worker.domain)
                }
            } else {
                val currentConfig = loadConfig()
                if (currentConfig.proxyMode == mode) {
                    currentConfig.customCfDomain = worker.domain
                    currentConfig.useDefaultWorkerSocks5 = worker.isDeveloperWorker
                    saveConfig(currentConfig)
                }
            }
        } catch (_: Exception) {
            val config = loadConfig()
            if (config.proxyMode == mode) {
                config.customCfDomain = worker.domain
                config.useDefaultWorkerSocks5 = worker.isDeveloperWorker
                saveConfig(config)
            }
        }

        try {
            WorkerFailoverManager.getCircuitRecord(worker.id)?.reset()
        } catch (_: Exception) {}
    }

    fun restoreUserPrimaryWorkerIfNeeded() {
        restoreUserPrimaryWorkerIfNeeded(getEffectiveProxyMode())
    }

    fun restoreUserPrimaryWorkerIfNeeded(mode: ProxyMode) {
        val primaryId = getUserPrimaryWorkerId(mode)
        val currentActive = getActiveWorkerId(mode)
        if (currentActive != primaryId) {
            val allRelayWorkers = getCustomWorkers()
            val primaryWorker = allRelayWorkers.find { it.id == primaryId }
            if (primaryWorker != null) {
                AppLogger.i("PreferencesManager", "Restoring user primary worker '${primaryWorker.name}' ($primaryId) for $mode")
                setActiveWorkerId(primaryId, mode = mode, fromUserAction = false)
            }
        }
    }

    fun getActiveWorker(): WorkerProfile = getActiveWorker(getEffectiveProxyMode())

    fun getActiveWorker(mode: ProxyMode): WorkerProfile = getActiveWorker(mode, getActiveWorkerId(mode))

    fun getActiveWorker(activeId: String): WorkerProfile = getActiveWorker(getEffectiveProxyMode(), activeId)

    fun getActiveWorker(mode: ProxyMode, activeId: String): WorkerProfile {
        val relayWorkers = getCustomWorkers()
        return relayWorkers.find { it.id == activeId }
            ?: relayWorkers.firstOrNull()
            ?: WorkerProfile(
                id = "no_worker",
                name = "Нет активного узла",
                domain = "",
                isDeveloperWorker = false
            )
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

    // ── Auto-Stop on Start Settings ─────────────────────────────────────────

    fun isAutoStopOnStartEnabled(): Boolean {
        return prefs.getBoolean("auto_stop_on_start_enabled", false)
    }

    fun setAutoStopOnStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_stop_on_start_enabled", enabled).apply()
    }

    fun getAutoStopMinutes(): Int {
        return prefs.getInt("auto_stop_minutes", 5)
    }

    fun setAutoStopMinutes(minutes: Int) {
        prefs.edit().putInt("auto_stop_minutes", minutes.coerceIn(1, 360)).apply()
    }

    // ── Proxy Schedule Settings ─────────────────────────────────────────────

    fun loadScheduleConfig(): ScheduleConfig {
        val isEnabled = prefs.getBoolean("schedule_enabled", false)
        val startHour = prefs.getInt("schedule_start_hour", 8)
        val startMinute = prefs.getInt("schedule_start_minute", 0)
        val stopHour = prefs.getInt("schedule_stop_hour", 23)
        val stopMinute = prefs.getInt("schedule_stop_minute", 0)
        val daysModeStr = prefs.getString("schedule_days_mode", ScheduleDaysMode.EVERY_DAY.name)
        val daysMode = ScheduleDaysMode.fromName(daysModeStr)
        val customDaysSet = prefs.getStringSet("schedule_custom_days", null)
        val customDays = customDaysSet?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(
            java.util.Calendar.MONDAY,
            java.util.Calendar.TUESDAY,
            java.util.Calendar.WEDNESDAY,
            java.util.Calendar.THURSDAY,
            java.util.Calendar.FRIDAY,
            java.util.Calendar.SATURDAY,
            java.util.Calendar.SUNDAY
        )

        return ScheduleConfig(
            isEnabled = isEnabled,
            startHour = startHour,
            startMinute = startMinute,
            stopHour = stopHour,
            stopMinute = stopMinute,
            daysMode = daysMode,
            customDays = customDays
        )
    }

    fun saveScheduleConfig(config: ScheduleConfig) {
        prefs.edit()
            .putBoolean("schedule_enabled", config.isEnabled)
            .putInt("schedule_start_hour", config.startHour)
            .putInt("schedule_start_minute", config.startMinute)
            .putInt("schedule_stop_hour", config.stopHour)
            .putInt("schedule_stop_minute", config.stopMinute)
            .putString("schedule_days_mode", config.daysMode.name)
            .putStringSet("schedule_custom_days", config.customDays.map { it.toString() }.toSet())
            .apply()
    }

    // ── Onboarding & Language Settings ───────────────────────────────────────

    fun hasSeenOnboarding(): Boolean {
        return prefs.getBoolean("has_seen_onboarding", false)
    }

    fun setHasSeenOnboarding(seen: Boolean = true) {
        prefs.edit().putBoolean("has_seen_onboarding", seen).apply()
    }

    fun hasSeenVpnOnboarding(): Boolean {
        return prefs.getBoolean("has_seen_vpn_onboarding", false)
    }

    fun setHasSeenVpnOnboarding(seen: Boolean = true) {
        prefs.edit().putBoolean("has_seen_vpn_onboarding", seen).apply()
    }

    fun getAppLanguage(): String {
        return prefs.getString("app_language", "system") ?: "system"
    }

    fun setAppLanguage(langCode: String) {
        prefs.edit().putString("app_language", langCode).apply()
        _appLanguageFlow.value = langCode
        // MainActivity observes appLanguageFlow and supplies a localized Compose context.
        // Mutating Resources here during a click can invalidate the active composition and
        // leave the first-run onboarding controls unresponsive on Android 14.
        java.util.Locale.setDefault(com.mirrly.tgproxy.util.LocaleHelper.getTargetLocale(langCode))
    }

    // ── Advanced / Expert Mode Settings ──────────────────────────────────────

    fun isAdvancedSettingsEnabled(): Boolean {
        return prefs.getBoolean("advanced_settings_enabled", false)
    }

    fun setAdvancedSettingsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("advanced_settings_enabled", enabled).apply()
        _advancedSettingsEnabledFlow.value = enabled
    }

    fun getHappyEyeballsDelayMs(): Long {
        return prefs.getLong("happy_eyeballs_delay_ms", 200L)
    }

    fun setHappyEyeballsDelayMs(delayMs: Long) {
        prefs.edit().putLong("happy_eyeballs_delay_ms", delayMs.coerceIn(50L, 1000L)).apply()
    }

    fun getIpFamilyPreference(): String {
        return prefs.getString("ip_family_preference", "IPV4_ONLY") ?: "IPV4_ONLY"
    }

    fun setIpFamilyPreference(pref: String) {
        prefs.edit().putString("ip_family_preference", pref).apply()
    }

    fun getSocketKeepAliveSeconds(): Int {
        return prefs.getInt("socket_keep_alive_seconds", 60)
    }

    fun setSocketKeepAliveSeconds(seconds: Int) {
        prefs.edit().putInt("socket_keep_alive_seconds", seconds.coerceIn(10, 300)).apply()
    }

    fun resetAdvancedSettingsToDefaults(config: ProxyConfig) {
        setHappyEyeballsDelayMs(200L)
        setIpFamilyPreference("IPV4_ONLY")
        setSocketKeepAliveSeconds(60)
        config.speedPresetName = com.mirrly.tgproxy.core.SpeedPreset.AUTO.name
        config.applyPreset(com.mirrly.tgproxy.core.SpeedPreset.AUTO)
        config.tcpNoDelayModeName = com.mirrly.tgproxy.core.NagleMode.ENABLED.name
        config.tcpNoDelay = false
        config.bufferSizeBytes = 0
        config.happyEyeballsDelayMs = 200L
        config.ipFamilyPreferenceName = com.mirrly.tgproxy.core.IpFamilyPreference.IPV4_ONLY.name
        config.webSocketKeepAliveSeconds = 60
        config.warpUserEndpointOverride = ""
    }

    // ── Cloudflare Account & Deploy Session ───────────────────────────────────

    fun getCloudflareToken(): String? {
        val raw = secretPrefs.getString("cf_access_token", null)?.takeIf { it.isNotBlank() } ?: return null
        return WarpKeystoreEncryption.decrypt(raw).takeIf { it.isNotBlank() }
    }

    fun getCloudflareRefreshToken(): String? {
        val raw = secretPrefs.getString("cf_refresh_token", null)?.takeIf { it.isNotBlank() } ?: return null
        return WarpKeystoreEncryption.decrypt(raw).takeIf { it.isNotBlank() }
    }

    fun isCloudflareAuthorized(): Boolean {
        return !getCloudflareToken().isNullOrBlank()
    }

    fun getCloudflareAccountId(): String? {
        return prefs.getString("cf_account_id", null)?.takeIf { it.isNotBlank() }
    }

    fun getCloudflareAccountName(): String? {
        return prefs.getString("cf_account_name", null)?.takeIf { it.isNotBlank() }
    }

    fun getCloudflareSubdomain(): String? {
        return prefs.getString("cf_subdomain", null)?.takeIf { it.isNotBlank() }
    }

    fun setCloudflareSubdomain(subdomain: String) {
        prefs.edit().putString("cf_subdomain", subdomain).apply()
    }

    fun saveCloudflareSession(
        accessToken: String,
        refreshToken: String?,
        accountId: String?,
        accountName: String?,
        subdomain: String?
    ) {
        secretPrefs.edit()
            .putString("cf_access_token", WarpKeystoreEncryption.encrypt(accessToken))
            .putString("cf_refresh_token", WarpKeystoreEncryption.encrypt(refreshToken ?: ""))
            .apply()

        prefs.edit()
            .putString("cf_account_id", accountId ?: "")
            .putString("cf_account_name", accountName ?: "")
            .putString("cf_subdomain", subdomain ?: "")
            .apply()
    }

    fun updateCloudflareAccessToken(accessToken: String, refreshToken: String? = null) {
        val editor = secretPrefs.edit().putString("cf_access_token", WarpKeystoreEncryption.encrypt(accessToken))
        if (!refreshToken.isNullOrBlank()) {
            editor.putString("cf_refresh_token", WarpKeystoreEncryption.encrypt(refreshToken))
        }
        editor.apply()
    }

    fun clearCloudflareSession() {
        secretPrefs.edit()
            .remove("cf_access_token")
            .remove("cf_refresh_token")
            .apply()

        prefs.edit()
            .remove("cf_account_id")
            .remove("cf_account_name")
            .remove("cf_subdomain")
            .apply()
    }

    /**
     * Checks if a worker profile belongs to the currently authorized Cloudflare account.
     * Manual third-party/stranger workers or developer pool workers are NOT owned by the user.
     */
    fun isWorkerOwnedByCurrentUser(worker: WorkerProfile): Boolean {
        if (worker.isDeveloperWorker) return false
        if (!isCloudflareAuthorized()) return false
        if (worker.isCloudflarePersonal) return true
        val subdomain = getCloudflareSubdomain()
        if (!subdomain.isNullOrBlank() && worker.domain.contains(".$subdomain.workers.dev", ignoreCase = true)) {
            return true
        }
        return false
    }

    fun isSocks5OnboardingCompleted(): Boolean {
        return prefs.getBoolean("socks5_onboarding_completed", false)
    }

    fun setSocks5OnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean("socks5_onboarding_completed", completed).apply()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // VPN Mode & Split Tunnel Settings (Tasks N12, N19)
    // ──────────────────────────────────────────────────────────────────────────
    fun isVpnModeEnabled(): Boolean = prefs.getBoolean("vpn_mode_enabled", false)

    fun setVpnModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("vpn_mode_enabled", enabled).apply()
    }

    fun isVpnSplitTunnelEnabled(): Boolean = prefs.getBoolean("vpn_split_tunnel_enabled", false)

    fun setVpnSplitTunnelEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("vpn_split_tunnel_enabled", enabled).apply()
    }

    fun isVpnSplitTunnelAllowlist(): Boolean = prefs.getBoolean("vpn_split_tunnel_allowlist", false)

    fun setVpnSplitTunnelAllowlist(isAllowlist: Boolean) {
        prefs.edit().putBoolean("vpn_split_tunnel_allowlist", isAllowlist).apply()
    }

    fun getVpnSplitTunnelPackages(): Set<String> = prefs.getStringSet("vpn_split_tunnel_packages", emptySet()) ?: emptySet()

    fun setVpnSplitTunnelPackages(packages: Set<String>) {
        prefs.edit().putStringSet("vpn_split_tunnel_packages", packages).apply()
    }

    fun getVpnMtu(): Int = 1280

    fun setVpnMtu(mtu: Int) {
        // Fixed: MTU is permanently 1280 for stability
        prefs.edit().putInt("vpn_mtu", 1280).apply()
    }

    fun getVpnBlockQuic(): Boolean = prefs.getBoolean("vpn_block_quic", true)

    fun setVpnBlockQuic(blocked: Boolean) {
        prefs.edit().putBoolean("vpn_block_quic", blocked).apply()
    }

    fun getVpnBlockIpv6Leaks(): Boolean = prefs.getBoolean("vpn_block_ipv6_leaks", true)

    fun setVpnBlockIpv6Leaks(blocked: Boolean) {
        prefs.edit().putBoolean("vpn_block_ipv6_leaks", blocked).apply()
    }

    fun isVpnAutoProfileEnabled(): Boolean = prefs.getBoolean("vpn_auto_profile_enabled", true)

    fun setVpnAutoProfileEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("vpn_auto_profile_enabled", enabled).apply()
        _vpnAutoProfileEnabledFlow.value = enabled
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Redacted Diagnostic Bundle (Task N20)
    // ──────────────────────────────────────────────────────────────────────────
    /**
     * Создает диагностический отчет для службы поддержки, строго исключая
     * приватные ключи, токены, пароли и полные чувствительные URL (Task N20).
     */
    fun getRedactedDiagnosticReport(config: ProxyConfig): String = Companion.getRedactedDiagnosticReport(config)


    fun isEchEnabled(): Boolean = prefs.getBoolean(KEY_ECH_ENABLED, false)

    fun setEchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ECH_ENABLED, enabled).apply()
        _isEchEnabledFlow.value = enabled
    }

    fun isWsRandomizationEnabled(): Boolean = prefs.getBoolean(KEY_WS_RANDOMIZATION_ENABLED, true)

    fun setWsRandomizationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WS_RANDOMIZATION_ENABLED, enabled).apply()
        _isWsRandomizationEnabledFlow.value = enabled
    }

    fun getVlessUuid(): String {
        return prefs.getString("vless_uuid", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessUuid
    }

    fun setVlessUuid(uuid: String) {
        prefs.edit().putString("vless_uuid", uuid).apply()
    }

    fun isTlsRecordPaddingEnabled(): Boolean = prefs.getBoolean(KEY_TLS_RECORD_PADDING_ENABLED, true)

    fun setTlsRecordPaddingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TLS_RECORD_PADDING_ENABLED, enabled).apply()
        _isTlsRecordPaddingEnabledFlow.value = enabled
        try {
            val app = context.applicationContext as? com.mirrly.tgproxy.MirrlyApplication
            app?.config?.isTlsRecordPaddingEnabled = enabled
            app?.proxyServer?.applyTlsRecordPaddingEnabled(enabled)
        } catch (_: Exception) {}
    }

    fun isTlsClientHelloSplitEnabled(): Boolean = prefs.getBoolean(KEY_TLS_CLIENT_HELLO_SPLIT_ENABLED, true)

    fun setTlsClientHelloSplitEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TLS_CLIENT_HELLO_SPLIT_ENABLED, enabled).apply()
        _isTlsClientHelloSplitEnabledFlow.value = enabled
        try {
            val app = context.applicationContext as? com.mirrly.tgproxy.MirrlyApplication
            app?.config?.isTlsClientHelloSplitEnabled = enabled
            app?.proxyServer?.applyTlsClientHelloSplitEnabled(enabled)
        } catch (_: Exception) {}
    }

    fun isUseWorkerMtproto(): Boolean = prefs.getBoolean("use_worker_mtproto", false)

    fun setUseWorkerMtproto(enabled: Boolean) {
        prefs.edit().putBoolean("use_worker_mtproto", enabled).apply()
        _useWorkerMtprotoFlow.value = enabled
        try {
            val app = context.applicationContext as? com.mirrly.tgproxy.MirrlyApplication
            app?.config?.useWorkerMtproto = enabled
            app?.proxyServer?.applyUseWorkerMtproto(enabled)
        } catch (_: Exception) {}
    }

    // ──────────────────────────────────────────────────────────────────────────
    // External Donation Warning Skip Preference
    // ──────────────────────────────────────────────────────────────────────────
    fun isSkipDonateWarning(): Boolean = prefs.getBoolean("skip_donate_warning", false)

    fun setSkipDonateWarning(skip: Boolean) {
        prefs.edit().putBoolean("skip_donate_warning", skip).apply()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Mirrly AI Chat & Neural Engine Settings
    // ──────────────────────────────────────────────────────────────────────────
    fun getAiProvider(): String = prefs.getString("ai_provider", "Gemini") ?: "Gemini"
    fun setAiProvider(provider: String) {
        prefs.edit().putString("ai_provider", provider).apply()
        _aiProviderFlow.value = provider
    }

    fun getAiEndpoint(): String = prefs.getString("ai_endpoint", "https://generativelanguage.googleapis.com/v1beta") ?: "https://generativelanguage.googleapis.com/v1beta"
    fun setAiEndpoint(endpoint: String) {
        prefs.edit().putString("ai_endpoint", endpoint).apply()
        _aiEndpointFlow.value = endpoint
    }

    fun getAiModel(): String {
        val model = prefs.getString("ai_model", "gemini-3.6-flash") ?: "gemini-3.6-flash"
        return if (model.startsWith("gemini-2.0") || model == "gemini-3.8-flash" || model.isEmpty()) {
            prefs.edit().putString("ai_model", "gemini-3.6-flash").apply()
            "gemini-3.6-flash"
        } else {
            model
        }
    }
    fun setAiModel(model: String) {
        prefs.edit().putString("ai_model", model).apply()
        try {
            _aiModelFlow.value = model
        } catch (_: Exception) {}
    }

    fun getAiApiKey(): String {
        val encrypted = secretPrefs.getString("ai_api_key", null) ?: prefs.getString("ai_api_key", null)
        return if (!encrypted.isNullOrBlank()) {
            try {
                WarpKeystoreEncryption.decrypt(encrypted)
            } catch (_: Exception) {
                encrypted
            }
        } else {
            ""
        }
    }

    fun setAiApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        val toStore = if (trimmed.isNotEmpty()) {
            try {
                WarpKeystoreEncryption.encrypt(trimmed)
            } catch (_: Exception) {
                trimmed
            }
        } else {
            ""
        }
        secretPrefs.edit().putString("ai_api_key", toStore).apply()
        _aiApiKeyFlow.value = trimmed
    }

    fun isAiReasoningEnabled(): Boolean = prefs.getBoolean("ai_reasoning_enabled", true)
    fun setAiReasoningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("ai_reasoning_enabled", enabled).apply()
        _aiReasoningEnabledFlow.value = enabled
    }

    fun isAiStreamingEnabled(): Boolean = prefs.getBoolean("ai_streaming_enabled", true)
    fun setAiStreamingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("ai_streaming_enabled", enabled).apply()
        _aiStreamingEnabledFlow.value = enabled
    }

    fun isAiOnlyResponse(): Boolean = prefs.getBoolean("ai_only_response", false)
    fun setAiOnlyResponse(onlyResponse: Boolean) {
        prefs.edit().putBoolean("ai_only_response", onlyResponse).apply()
        _aiOnlyResponseFlow.value = onlyResponse
    }

    fun isAiQuoteResponse(): Boolean = prefs.getBoolean("ai_quote_response", true)
    fun setAiQuoteResponse(quoteResponse: Boolean) {
        prefs.edit().putBoolean("ai_quote_response", quoteResponse).apply()
        _aiQuoteResponseFlow.value = quoteResponse
    }

    fun getAiTemperature(): Float = prefs.getFloat("ai_temperature", 0.7f)
    fun setAiTemperature(temperature: Float) {
        val clamped = temperature.coerceIn(0.0f, 2.0f)
        prefs.edit().putFloat("ai_temperature", clamped).apply()
        _aiTemperatureFlow.value = clamped
    }

    fun getAiSystemPromptMode(): String = prefs.getString("ai_system_prompt_mode", "DIAGNOSTIC") ?: "DIAGNOSTIC"
    fun setAiSystemPromptMode(mode: String) {
        prefs.edit().putString("ai_system_prompt_mode", mode).apply()
    }

    fun getAiCustomSystemPrompt(): String = prefs.getString("ai_custom_system_prompt", "") ?: ""
    fun setAiCustomSystemPrompt(prompt: String) {
        prefs.edit().putString("ai_custom_system_prompt", prompt).apply()
    }

    fun isAiUseSocks5(): Boolean = prefs.getBoolean("ai_use_socks5", false)
    fun setAiUseSocks5(use: Boolean) {
        prefs.edit().putBoolean("ai_use_socks5", use).apply()
    }

    fun isProxyActive(): Boolean = prefs.getBoolean("proxy_service_active", false)
    fun setProxyActive(active: Boolean) {
        prefs.edit().putBoolean("proxy_service_active", active).apply()
    }

    fun isForceMobileModeEnabled(): Boolean = prefs.getBoolean("force_mobile_mode_enabled", false)
    fun setForceMobileModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("force_mobile_mode_enabled", enabled).apply()
        _forceMobileModeFlow.value = enabled
    }

    fun isDialThrottlingEnabled(): Boolean = prefs.getBoolean("dial_throttling_enabled", false)
    fun setDialThrottlingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("dial_throttling_enabled", enabled).apply()
        _dialThrottlingFlow.value = enabled
        com.mirrly.tgproxy.core.NativeProxy.setDialThrottlingEnabled(enabled)
    }

    // ── Log Settings & Load Optimization ────────────────────────────────────
    fun isLogsEnabled(): Boolean = prefs.getBoolean("logs_enabled", true)
    fun isLogcatReaderAllowed(): Boolean = prefs.getBoolean("logs_logcat_reader_allowed", true)

    fun syncLogSettingsToAppLogger() {
        val loggingEnabled = isLogsEnabled()
        val logcatAllowed = isLogcatReaderAllowed()
        val levels = mutableSetOf<com.mirrly.tgproxy.core.LogLevel>()
        if (prefs.getBoolean("logs_level_debug", false)) levels.add(com.mirrly.tgproxy.core.LogLevel.DEBUG)
        if (prefs.getBoolean("logs_level_info", true)) levels.add(com.mirrly.tgproxy.core.LogLevel.INFO)
        if (prefs.getBoolean("logs_level_warn", true)) levels.add(com.mirrly.tgproxy.core.LogLevel.WARN)
        if (prefs.getBoolean("logs_level_error", true)) levels.add(com.mirrly.tgproxy.core.LogLevel.ERROR)
        val disabledCats = prefs.getStringSet("logs_disabled_categories", emptySet()) ?: emptySet()
        val disabledTgs = prefs.getStringSet("logs_disabled_tags", emptySet()) ?: emptySet()

        com.mirrly.tgproxy.core.AppLogger.configure(
            loggingEnabled = loggingEnabled,
            logcatReaderAllowed = logcatAllowed,
            levels = levels,
            disabledCats = disabledCats,
            disabledTgs = disabledTgs
        )
    }

    fun setLogsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("logs_enabled", enabled).apply()
        _isLogsEnabledFlow.value = enabled
        syncLogSettingsToAppLogger()
    }

    fun setLogcatReaderAllowed(allowed: Boolean) {
        prefs.edit().putBoolean("logs_logcat_reader_allowed", allowed).apply()
        _isLogcatReaderAllowedFlow.value = allowed
        syncLogSettingsToAppLogger()
    }

    fun setLogLevelEnabled(level: com.mirrly.tgproxy.core.LogLevel, enabled: Boolean) {
        val key = when (level) {
            com.mirrly.tgproxy.core.LogLevel.DEBUG -> "logs_level_debug"
            com.mirrly.tgproxy.core.LogLevel.INFO -> "logs_level_info"
            com.mirrly.tgproxy.core.LogLevel.WARN -> "logs_level_warn"
            com.mirrly.tgproxy.core.LogLevel.ERROR -> "logs_level_error"
        }
        prefs.edit().putBoolean(key, enabled).apply()
        when (level) {
            com.mirrly.tgproxy.core.LogLevel.DEBUG -> _isLogLevelDebugFlow.value = enabled
            com.mirrly.tgproxy.core.LogLevel.INFO -> _isLogLevelInfoFlow.value = enabled
            com.mirrly.tgproxy.core.LogLevel.WARN -> _isLogLevelWarnFlow.value = enabled
            com.mirrly.tgproxy.core.LogLevel.ERROR -> _isLogLevelErrorFlow.value = enabled
        }
        syncLogSettingsToAppLogger()
    }

    fun setLogCategoryEnabled(categoryKey: String, enabled: Boolean) {
        val current = prefs.getStringSet("logs_disabled_categories", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) {
            current.remove(categoryKey)
        } else {
            current.add(categoryKey)
        }
        prefs.edit().putStringSet("logs_disabled_categories", current).apply()
        _disabledLogCategoriesFlow.value = current.toSet()
        syncLogSettingsToAppLogger()
    }

    fun setLogTagEnabled(tag: String, enabled: Boolean) {
        val cleanTag = tag.trim()
        val current = prefs.getStringSet("logs_disabled_tags", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) {
            current.removeAll { it.equals(cleanTag, ignoreCase = true) }
        } else {
            current.add(cleanTag)
        }
        prefs.edit().putStringSet("logs_disabled_tags", current).apply()
        _disabledLogTagsFlow.value = current.toSet()
        syncLogSettingsToAppLogger()
    }

    fun resetLogSettings() {
        prefs.edit()
            .putBoolean("logs_enabled", true)
            .putBoolean("logs_logcat_reader_allowed", true)
            .putBoolean("logs_level_debug", false)
            .putBoolean("logs_level_info", true)
            .putBoolean("logs_level_warn", true)
            .putBoolean("logs_level_error", true)
            .putStringSet("logs_disabled_categories", emptySet())
            .putStringSet("logs_disabled_tags", emptySet())
            .apply()

        _isLogsEnabledFlow.value = true
        _isLogcatReaderAllowedFlow.value = true
        _isLogLevelDebugFlow.value = false
        _isLogLevelInfoFlow.value = true
        _isLogLevelWarnFlow.value = true
        _isLogLevelErrorFlow.value = true
        _disabledLogCategoriesFlow.value = emptySet()
        _disabledLogTagsFlow.value = emptySet()
        syncLogSettingsToAppLogger()
    }
}
