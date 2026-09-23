package com.mirrly.tgproxy.service

import com.mirrly.tgproxy.R

import android.content.Context
import android.content.SharedPreferences
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.core.ProxyMode
import com.mirrly.tgproxy.core.WorkerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PreferencesManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mirrly_tg_proxy_prefs", Context.MODE_PRIVATE)
    private val secretPrefs: SharedPreferences = context.getSharedPreferences("mirrly_secrets_prefs", Context.MODE_PRIVATE)

    private val _animationsDisabledFlow = MutableStateFlow(areAnimationsDisabled())
    val animationsDisabledFlow: StateFlow<Boolean> = _animationsDisabledFlow.asStateFlow()

    private val _isSocks5Flow = MutableStateFlow(loadConfig().isSocks5Mode)
    val isSocks5Flow: StateFlow<Boolean> = _isSocks5Flow.asStateFlow()

    private val _activeWorkerIdFlow = MutableStateFlow(getActiveWorkerId())
    val activeWorkerIdFlow: StateFlow<String> = _activeWorkerIdFlow.asStateFlow()

    private val _uplinkModeFlow = MutableStateFlow(loadConfig().uplinkMode)
    val uplinkModeFlow: StateFlow<com.mirrly.tgproxy.core.UplinkMode> = _uplinkModeFlow.asStateFlow()

    private val _vpnUplinkModeFlow = MutableStateFlow(loadConfig().vpnUplinkMode)
    val vpnUplinkModeFlow: StateFlow<com.mirrly.tgproxy.core.UplinkMode> = _vpnUplinkModeFlow.asStateFlow()

    private val _appLanguageFlow = MutableStateFlow(getAppLanguage())
    val appLanguageFlow: StateFlow<String> = _appLanguageFlow.asStateFlow()

    private val _advancedSettingsEnabledFlow = MutableStateFlow(isAdvancedSettingsEnabled())
    val advancedSettingsEnabledFlow: StateFlow<Boolean> = _advancedSettingsEnabledFlow.asStateFlow()

    private val _vpnMtuFlow = MutableStateFlow(prefs.getInt("vpn_mtu", 1420))
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
        if (key == "uplink_mode") {
            _uplinkModeFlow.value = com.mirrly.tgproxy.core.UplinkMode.WORKER
        }
        if (key == "vpn_uplink_mode") {
            val modeName = sharedPreferences.getString(key, com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE.name) ?: com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE.name
            val mode = try { com.mirrly.tgproxy.core.UplinkMode.valueOf(modeName) } catch (_: Exception) { com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE }
            _vpnUplinkModeFlow.value = if (mode == com.mirrly.tgproxy.core.UplinkMode.WORKER) com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE else mode
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
        // Режимы пула MTProto и TCP_NODELAY скрыты из UI: всегда режим AUTO по умолчанию для всех пользователей
        val speedPresetName = com.mirrly.tgproxy.core.SpeedPreset.AUTO.name
        val tcpNoDelayModeName = com.mirrly.tgproxy.core.TcpNoDelayMode.AUTO.name
        val tcpNoDelay = true
        val bufferSizeBytes = prefs.getInt("buffer_size_bytes", defaults.bufferSizeBytes)
        val happyEyeballsDelayMs = prefs.getLong("happy_eyeballs_delay_ms", defaults.happyEyeballsDelayMs)
        val ipFamilyPreferenceName = prefs.getString("ip_family_preference", defaults.ipFamilyPreferenceName) ?: defaults.ipFamilyPreferenceName
        val webSocketKeepAliveSeconds = prefs.getInt("socket_keep_alive_seconds", defaults.webSocketKeepAliveSeconds)
        val socks5Port = prefs.getInt("socks5_port", defaults.socks5Port)
        val socks5Username = prefs.getString("socks5_username", defaults.socks5Username) ?: defaults.socks5Username
        val socks5Password = secretPrefs.getString("socks5_password", null) ?: prefs.getString("socks5_password", defaults.socks5Password) ?: defaults.socks5Password
        val useDefaultWorkerSocks5 = if (!activeWorker.isDeveloperWorker) {
            false
        } else {
            prefs.getBoolean("use_default_worker_socks5", defaults.useDefaultWorkerSocks5)
        }

        // Миграция: если proxy_mode ещё не сохранён, читаем старый socks5_enabled
        val proxyModeName = if (prefs.contains("proxy_mode")) {
            prefs.getString("proxy_mode", ProxyMode.MTPROTO.name) ?: ProxyMode.MTPROTO.name
        } else {
            @Suppress("DEPRECATION")
            if (prefs.getBoolean("socks5_enabled", false)) ProxyMode.SOCKS5.name
            else ProxyMode.MTPROTO.name
        }

        val isBatteryGuardEnabled = prefs.getBoolean("is_battery_guard_enabled", defaults.isBatteryGuardEnabled)
        val batteryGuardThreshold = prefs.getInt("battery_guard_threshold", defaults.batteryGuardThreshold)
        val batteryGuardStopOnPowerSave = prefs.getBoolean("battery_guard_stop_on_powersave", defaults.batteryGuardStopOnPowerSave)
        val isAdaptiveQoSEnabled = prefs.getBoolean("is_adaptive_qos_enabled", defaults.isAdaptiveQoSEnabled)
        val savedDohProviders = prefs.getStringSet("enabled_doh_providers", null)
        val enabledDohProviderIds = savedDohProviders ?: com.mirrly.tgproxy.core.DohResolver.DEFAULT_ENABLED_PROVIDER_IDS

        val uplinkModeName = com.mirrly.tgproxy.core.UplinkMode.WORKER.name
        val vpnUplinkModeName = prefs.getString("vpn_uplink_mode", defaults.vpnUplinkModeName) ?: defaults.vpnUplinkModeName
        val vpnMtu = prefs.getInt("vpn_mtu", defaults.vpnMtu)
        val vpnBlockQuic = prefs.getBoolean("vpn_block_quic", defaults.vpnBlockQuic)
        val vpnBlockIpv6Leaks = prefs.getBoolean("vpn_block_ipv6_leaks", defaults.vpnBlockIpv6Leaks)
        val warpAccountId = prefs.getString("warp_account_id", defaults.warpAccountId) ?: defaults.warpAccountId
        val warpToken = secretPrefs.getString("warp_token", null) ?: prefs.getString("warp_token", defaults.warpToken) ?: defaults.warpToken
        val warpLicenseKey = secretPrefs.getString("warp_license_key", null) ?: prefs.getString("warp_license_key", defaults.warpLicenseKey) ?: defaults.warpLicenseKey
        val warpClientIpv4 = prefs.getString("warp_client_ipv4", defaults.warpClientIpv4) ?: defaults.warpClientIpv4
        val warpClientIpv6 = prefs.getString("warp_client_ipv6", defaults.warpClientIpv6) ?: defaults.warpClientIpv6
        val warpPeerEndpoint = prefs.getString("warp_peer_endpoint", defaults.warpPeerEndpoint) ?: defaults.warpPeerEndpoint
        val warpPeerPublicKey = prefs.getString("warp_peer_public_key", defaults.warpPeerPublicKey) ?: defaults.warpPeerPublicKey
        val warpPrivateKey = secretPrefs.getString("warp_private_key", null) ?: prefs.getString("warp_private_key", defaults.warpPrivateKey) ?: defaults.warpPrivateKey
        val warpPublicKey = prefs.getString("warp_public_key", defaults.warpPublicKey) ?: defaults.warpPublicKey
        val warpP256PrivateKey = secretPrefs.getString("warp_p256_private_key", defaults.warpP256PrivateKey) ?: defaults.warpP256PrivateKey
        val warpP256PublicKey = prefs.getString("warp_p256_public_key", defaults.warpP256PublicKey) ?: defaults.warpP256PublicKey
        val warpClientCert = prefs.getString("warp_client_cert", defaults.warpClientCert) ?: defaults.warpClientCert
        val warpMasqueAccountId = prefs.getString("warp_masque_account_id", defaults.warpMasqueAccountId) ?: defaults.warpMasqueAccountId
        val warpMasqueToken = secretPrefs.getString("warp_masque_token", null) ?: prefs.getString("warp_masque_token", defaults.warpMasqueToken) ?: defaults.warpMasqueToken
        val warpMasquePeerEndpoint = prefs.getString("warp_masque_peer_endpoint", defaults.warpMasquePeerEndpoint) ?: defaults.warpMasquePeerEndpoint
        val warpMasquePeerPublicKey = prefs.getString("warp_masque_peer_public_key", defaults.warpMasquePeerPublicKey) ?: defaults.warpMasquePeerPublicKey
        val warpMasqueClientIpv4 = prefs.getString("warp_masque_client_ipv4", defaults.warpMasqueClientIpv4) ?: defaults.warpMasqueClientIpv4
        val warpMasqueClientIpv6 = prefs.getString("warp_masque_client_ipv6", defaults.warpMasqueClientIpv6) ?: defaults.warpMasqueClientIpv6
        val isWgValid = prefs.getBoolean("is_wg_valid", defaults.isWgValid)
        val isMasqueValid = prefs.getBoolean("is_masque_valid", defaults.isMasqueValid)
        val isWarpPlus = prefs.getBoolean("is_warp_plus", defaults.isWarpPlus)
        val isWarpAccountActive = prefs.getBoolean("is_warp_account_active", defaults.isWarpAccountActive)
        val vlessUuid = secretPrefs.getString("vless_uuid", null) ?: prefs.getString("vless_uuid", defaults.vlessUuid) ?: defaults.vlessUuid
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
        val useOperaVpnForWarp = prefs.getBoolean("use_opera_vpn_for_warp", defaults.useOperaVpnForWarp)
        val operaVpnEndpoint = prefs.getString("opera_vpn_endpoint", defaults.operaVpnEndpoint) ?: defaults.operaVpnEndpoint
        val operaVpnNodeId = prefs.getString("opera_vpn_node_id", defaults.operaVpnNodeId) ?: defaults.operaVpnNodeId
        val warpWorkerDomain = prefs.getString("warp_worker_domain", defaults.warpWorkerDomain) ?: defaults.warpWorkerDomain
        val awgStrategyName = prefs.getString("awg_strategy_name", defaults.awgStrategyName) ?: defaults.awgStrategyName
        val awgCustomIni = prefs.getString("awg_custom_ini", defaults.awgCustomIni) ?: defaults.awgCustomIni

        return ProxyConfig(
            bindHost = bindHost,
            bindPort = bindPort,
            secretHex = secretHex,
            cfProxyEnabled = cfEnabled,
            customCfDomain = customDomain,
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
            useDefaultWorkerSocks5 = useDefaultWorkerSocks5,
            isBatteryGuardEnabled = isBatteryGuardEnabled,
            batteryGuardThreshold = batteryGuardThreshold,
            batteryGuardStopOnPowerSave = batteryGuardStopOnPowerSave,
            isAdaptiveQoSEnabled = isAdaptiveQoSEnabled,
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
            awgStrategyName = awgStrategyName,
            awgCustomIni = awgCustomIni
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
        secretPrefs.edit()
            .putString("secret_hex", secretToSave)
            .putString("warp_token", config.warpToken)
            .putString("warp_private_key", config.warpPrivateKey)
            .putString("warp_p256_private_key", config.warpP256PrivateKey)
            .putString("warp_masque_token", config.warpMasqueToken)
            .putString("vless_uuid", config.vlessUuid)
            .putString("socks5_password", config.socks5Password)
            .putString("warp_license_key", config.warpLicenseKey)
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
            .putLong("happy_eyeballs_delay_ms", config.happyEyeballsDelayMs)
            .putString("ip_family_preference", config.ipFamilyPreferenceName)
            .putInt("socket_keep_alive_seconds", config.webSocketKeepAliveSeconds)
            .putInt("socks5_port", config.socks5Port)
            .putString("socks5_username", config.socks5Username)
            .putBoolean("use_default_worker_socks5", config.useDefaultWorkerSocks5)
            .putBoolean("is_battery_guard_enabled", config.isBatteryGuardEnabled)
            .putInt("battery_guard_threshold", config.batteryGuardThreshold)
            .putBoolean("battery_guard_stop_on_powersave", config.batteryGuardStopOnPowerSave)
            .putBoolean("is_adaptive_qos_enabled", config.isAdaptiveQoSEnabled)
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
            .putString("awg_strategy_name", config.awgStrategyName)
            .putString("awg_custom_ini", config.awgCustomIni)
            .putString("vpn_uplink_mode", config.vpnUplinkModeName)
            .putInt("vpn_mtu", config.vpnMtu)
            .putBoolean("vpn_block_quic", config.vpnBlockQuic)
            .putBoolean("vpn_block_ipv6_leaks", config.vpnBlockIpv6Leaks)
            .apply()

        _isSocks5Flow.value = config.isSocks5Mode
        _uplinkModeFlow.value = config.uplinkMode
        _vpnUplinkModeFlow.value = config.vpnUplinkMode
    }

    fun saveWarpProfile(profile: com.mirrly.tgproxy.core.WarpProfile) {
        // commit() вместо apply() — блокирующая запись гарантирует сохранение warp_token на диск
        // до возврата из функции. Это предотвращает бесконечный цикл регистрации при убийстве процесса,
        // когда apply() не успевает завершить асинхронную запись до смерти процесса.
        secretPrefs.edit()
            .putString("warp_token", profile.token)
            .putString("warp_license_key", profile.licenseKey)
            .putString("warp_private_key", profile.privateKeyBase64)
            .putString("warp_p256_private_key", profile.p256PrivateKeyBase64)
            .putString("warp_masque_token", profile.masqueToken)
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
        val token = secretPrefs.getString("warp_token", null) ?: return null
        val licenseKey = secretPrefs.getString("warp_license_key", null) ?: prefs.getString("warp_license_key", "") ?: ""
        val clientIpv4 = prefs.getString("warp_client_ipv4", "172.16.0.2") ?: "172.16.0.2"
        val clientIpv6 = prefs.getString("warp_client_ipv6", "") ?: ""
        val peerEndpoint = prefs.getString("warp_peer_endpoint", "188.114.96.1:500") ?: "188.114.96.1:500"
        val peerPublicKey = prefs.getString("warp_peer_public_key", "") ?: ""
        val privateKey = secretPrefs.getString("warp_private_key", "") ?: ""
        val publicKey = prefs.getString("warp_public_key", "") ?: ""
        val p256PrivateKey = secretPrefs.getString("warp_p256_private_key", "") ?: ""
        val p256PublicKey = prefs.getString("warp_p256_public_key", "") ?: ""
        val clientCert = prefs.getString("warp_client_cert", "") ?: ""
        val masqueAccountId = prefs.getString("warp_masque_account_id", "") ?: ""
        val masqueToken = secretPrefs.getString("warp_masque_token", "") ?: ""
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

    fun getVlessUuid(): String {
        return secretPrefs.getString("vless_uuid", null) ?: prefs.getString("vless_uuid", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessUuid
    }

    fun setVlessUuid(uuid: String) {
        secretPrefs.edit().putString("vless_uuid", uuid).apply()
        prefs.edit().remove("vless_uuid").apply()
    }

    fun getVlessPath(): String {
        return prefs.getString("vless_path", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessPath
    }

    fun setVlessPath(path: String) {
        prefs.edit().putString("vless_path", path).apply()
    }

    fun getVlessDomain(): String {
        return prefs.getString("vless_domain", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessDomain
    }

    fun setVlessDomain(domain: String) {
        prefs.edit().putString("vless_domain", domain).apply()
    }

    fun getVlessPresetId(): String {
        return prefs.getString("vless_preset_id", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessPresetId
    }

    fun setVlessPresetId(id: String) {
        prefs.edit().putString("vless_preset_id", id).apply()
    }

    fun getVlessSecurity(): String = prefs.getString("vless_security", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessSecurity
    fun setVlessSecurity(security: String) { prefs.edit().putString("vless_security", security).apply() }

    fun getVlessPublicKey(): String = prefs.getString("vless_public_key", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessPublicKey
    fun setVlessPublicKey(pk: String) { prefs.edit().putString("vless_public_key", pk).apply() }

    fun getVlessShortId(): String = prefs.getString("vless_short_id", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessShortId
    fun setVlessShortId(sid: String) { prefs.edit().putString("vless_short_id", sid).apply() }

    fun getVlessFingerprint(): String = prefs.getString("vless_fingerprint", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessFingerprint
    fun setVlessFingerprint(fp: String) { prefs.edit().putString("vless_fingerprint", fp).apply() }

    fun getVlessSpiderX(): String = prefs.getString("vless_spider_x", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessSpiderX
    fun setVlessSpiderX(spx: String) { prefs.edit().putString("vless_spider_x", spx).apply() }

    fun getVlessTransport(): String = prefs.getString("vless_transport", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessTransport
    fun setVlessTransport(transport: String) { prefs.edit().putString("vless_transport", transport).apply() }

    fun getVlessFlow(): String = prefs.getString("vless_flow", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessFlow
    fun setVlessFlow(flow: String) { prefs.edit().putString("vless_flow", flow).apply() }

    fun getVlessHeaderType(): String = prefs.getString("vless_header_type", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessHeaderType
    fun setVlessHeaderType(headerType: String) { prefs.edit().putString("vless_header_type", headerType).apply() }

    fun getVlessServerAddress(): String = prefs.getString("vless_server_address", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessServerAddress
    fun setVlessServerAddress(addr: String) { prefs.edit().putString("vless_server_address", addr).apply() }

    fun getVlessServerPort(): Int = prefs.getInt("vless_server_port", com.mirrly.tgproxy.core.ProxyConfig().vlessServerPort)
    fun setVlessServerPort(port: Int) { prefs.edit().putInt("vless_server_port", port).apply() }

    fun getVlessTlsSni(): String = prefs.getString("vless_tls_sni", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessTlsSni
    fun setVlessTlsSni(sni: String) { prefs.edit().putString("vless_tls_sni", sni).apply() }

    fun getVlessHostHeader(): String = prefs.getString("vless_host_header", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessHostHeader
    fun setVlessHostHeader(host: String) { prefs.edit().putString("vless_host_header", host).apply() }

    fun isUseOperaVpnForVless(): Boolean {
        return prefs.getBoolean("use_opera_vpn_for_vless", false)
    }

    fun setUseOperaVpnForVless(enabled: Boolean) {
        prefs.edit().putBoolean("use_opera_vpn_for_vless", enabled).apply()
    }

    fun isUseOperaVpnForWarp(): Boolean {
        return prefs.getBoolean("use_opera_vpn_for_warp", false)
    }

    fun setUseOperaVpnForWarp(enabled: Boolean) {
        prefs.edit().putBoolean("use_opera_vpn_for_warp", enabled).apply()
    }

    fun getOperaVpnNodeId(): String {
        return prefs.getString("opera_vpn_node_id", null) ?: com.mirrly.tgproxy.core.OperaVpnRepository.getDefaultNode().id
    }

    fun setOperaVpnNodeId(nodeId: String) {
        prefs.edit().putString("opera_vpn_node_id", nodeId).apply()
    }

    fun getOperaVpnEndpoint(): String {
        return prefs.getString("opera_vpn_endpoint", null) ?: com.mirrly.tgproxy.core.OperaVpnRepository.getDefaultNode().endpoint
    }

    fun setOperaVpnEndpoint(endpoint: String) {
        prefs.edit().putString("opera_vpn_endpoint", endpoint).apply()
    }

    fun getEnabledDohProviderIds(): Set<String> {
        return prefs.getStringSet("enabled_doh_providers", null) ?: com.mirrly.tgproxy.core.DohResolver.DEFAULT_ENABLED_PROVIDER_IDS
    }

    fun setEnabledDohProviderIds(ids: Set<String>) {
        val sanitized = if (ids.isEmpty()) com.mirrly.tgproxy.core.DohResolver.DEFAULT_ENABLED_PROVIDER_IDS else ids
        prefs.edit().putStringSet("enabled_doh_providers", sanitized).apply()
        com.mirrly.tgproxy.core.DohResolver.setActiveProviders(sanitized)
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
                name = "Mirrly Primary",
                domain = "mirrly-tg-proxy-worker.brawny-singer.workers.dev",
                isDeveloperWorker = true
            ),
            WorkerProfile(
                id = "dev_alpha",
                name = "Mirrly Alpha",
                domain = "mtg-relay-5o77p2.mtg-alfaj.workers.dev",
                isDeveloperWorker = true
            ),
            WorkerProfile(
                id = "dev_beta",
                name = "Mirrly Beta",
                domain = "mtg-relay-ki2q2v.mtg-beta.workers.dev",
                isDeveloperWorker = true
            ),
            WorkerProfile(
                id = "dev_gamma",
                name = "Mirrly Gamma",
                domain = "mtg-relay-vndj4a.tammistichtqvc264.workers.dev",
                isDeveloperWorker = true
            ),
            WorkerProfile(
                id = "dev_delta",
                name = "Mirrly Delta",
                domain = "mtg-relay-xbl1ts.mtg-beta.workers.dev",
                isDeveloperWorker = true
            )
        )

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
                        name = obj.optString("name", context.getString(R.string.pref_worker_custom_default_name)),
                        domain = obj.getString("domain"),
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
        val allExisting = current + DEFAULT_DEV_WORKERS
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
        if (getActiveWorkerId() == id) {
            setActiveWorkerId("dev_default", fromUserAction = true)
        }
        if (getUserPrimaryWorkerId() == id) {
            setUserPrimaryWorkerId("dev_default")
        }
    }

    fun getUserPrimaryWorkerId(): String {
        val saved = prefs.getString("user_primary_worker_id", null)
        if (!saved.isNullOrBlank()) return saved
        val active = getActiveWorkerId()
        prefs.edit().putString("user_primary_worker_id", active).apply()
        return active
    }

    fun setUserPrimaryWorkerId(id: String) {
        prefs.edit().putString("user_primary_worker_id", id).apply()
    }

    fun getActiveWorkerId(): String {
        return prefs.getString("active_worker_id", "dev_default") ?: "dev_default"
    }

    fun setActiveWorkerId(id: String, fromUserAction: Boolean = true) {
        prefs.edit().putString("active_worker_id", id).apply()
        if (fromUserAction) {
            prefs.edit().putString("user_primary_worker_id", id).apply()
            try {
                PredictivePreWarmManager.clearHotReserve()
            } catch (_: Exception) {}
        }
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

    fun restoreUserPrimaryWorkerIfNeeded() {
        val primaryId = prefs.getString("user_primary_worker_id", null) ?: return
        val currentActive = getActiveWorkerId()
        if (currentActive != primaryId) {
            val allWorkers = getCustomWorkers() + DEFAULT_DEV_WORKERS
            val primaryWorker = allWorkers.find { it.id == primaryId }
            if (primaryWorker != null) {
                AppLogger.i("PreferencesManager", "Restoring user primary worker '${primaryWorker.name}' ($primaryId)")
                setActiveWorkerId(primaryId, fromUserAction = false)
            }
        }
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
        return prefs.getString("ip_family_preference", "DUAL_STACK") ?: "DUAL_STACK"
    }

    fun setIpFamilyPreference(pref: String) {
        prefs.edit().putString("ip_family_preference", pref).apply()
    }

    fun getSocketKeepAliveSeconds(): Int {
        return prefs.getInt("socket_keep_alive_seconds", 30)
    }

    fun setSocketKeepAliveSeconds(seconds: Int) {
        prefs.edit().putInt("socket_keep_alive_seconds", seconds.coerceIn(10, 300)).apply()
    }

    fun resetAdvancedSettingsToDefaults(config: ProxyConfig) {
        setHappyEyeballsDelayMs(200L)
        setIpFamilyPreference("DUAL_STACK")
        setSocketKeepAliveSeconds(30)
        config.speedPresetName = com.mirrly.tgproxy.core.SpeedPreset.AUTO.name
        config.applyPreset(com.mirrly.tgproxy.core.SpeedPreset.AUTO)
        config.tcpNoDelayModeName = com.mirrly.tgproxy.core.TcpNoDelayMode.AUTO.name
        config.tcpNoDelay = true
        config.bufferSizeBytes = 262144
        config.happyEyeballsDelayMs = 200L
        config.ipFamilyPreferenceName = com.mirrly.tgproxy.core.IpFamilyPreference.DUAL_STACK.name
        config.webSocketKeepAliveSeconds = 30
        config.warpUserEndpointOverride = ""
    }

    // ── Cloudflare Account & Deploy Session ───────────────────────────────────

    fun getCloudflareToken(): String? {
        return secretPrefs.getString("cf_access_token", null)?.takeIf { it.isNotBlank() }
    }

    fun getCloudflareRefreshToken(): String? {
        return secretPrefs.getString("cf_refresh_token", null)?.takeIf { it.isNotBlank() }
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
            .putString("cf_access_token", accessToken)
            .putString("cf_refresh_token", refreshToken ?: "")
            .apply()

        prefs.edit()
            .putString("cf_account_id", accountId ?: "")
            .putString("cf_account_name", accountName ?: "")
            .putString("cf_subdomain", subdomain ?: "")
            .apply()
    }

    fun updateCloudflareAccessToken(accessToken: String, refreshToken: String? = null) {
        val editor = secretPrefs.edit().putString("cf_access_token", accessToken)
        if (!refreshToken.isNullOrBlank()) {
            editor.putString("cf_refresh_token", refreshToken)
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

    fun getVpnMtu(): Int = prefs.getInt("vpn_mtu", 1420)

    fun setVpnMtu(mtu: Int) {
        prefs.edit().putInt("vpn_mtu", mtu).apply()
    }

    fun getVpnBlockQuic(): Boolean = prefs.getBoolean("vpn_block_quic", true)

    fun setVpnBlockQuic(blocked: Boolean) {
        prefs.edit().putBoolean("vpn_block_quic", blocked).apply()
    }

    fun getVpnBlockIpv6Leaks(): Boolean = prefs.getBoolean("vpn_block_ipv6_leaks", true)

    fun setVpnBlockIpv6Leaks(blocked: Boolean) {
        prefs.edit().putBoolean("vpn_block_ipv6_leaks", blocked).apply()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Redacted Diagnostic Bundle (Task N20)
    // ──────────────────────────────────────────────────────────────────────────
    /**
     * Создает диагностический отчет для службы поддержки, строго исключая
     * приватные ключи, токены, пароли и полные чувствительные URL (Task N20).
     */
    fun getRedactedDiagnosticReport(config: ProxyConfig): String = Companion.getRedactedDiagnosticReport(config)
}
