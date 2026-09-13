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

    private val _uplinkModeFlow = MutableStateFlow(loadConfig().uplinkMode)
    val uplinkModeFlow: StateFlow<com.mirrly.tgproxy.core.UplinkMode> = _uplinkModeFlow.asStateFlow()

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
            val modeName = sharedPreferences.getString(key, com.mirrly.tgproxy.core.UplinkMode.WORKER.name) ?: com.mirrly.tgproxy.core.UplinkMode.WORKER.name
            _uplinkModeFlow.value = try { com.mirrly.tgproxy.core.UplinkMode.valueOf(modeName) } catch (_: Exception) { com.mirrly.tgproxy.core.UplinkMode.WORKER }
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
        val socks5Username = prefs.getString("socks5_username", defaults.socks5Username) ?: defaults.socks5Username
        val socks5Password = prefs.getString("socks5_password", defaults.socks5Password) ?: defaults.socks5Password
        val useDefaultWorkerSocks5 = prefs.getBoolean("use_default_worker_socks5", defaults.useDefaultWorkerSocks5)

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

        val uplinkModeName = prefs.getString("uplink_mode", defaults.uplinkModeName) ?: defaults.uplinkModeName
        val warpAccountId = prefs.getString("warp_account_id", defaults.warpAccountId) ?: defaults.warpAccountId
        val warpToken = secretPrefs.getString("warp_token", null) ?: prefs.getString("warp_token", defaults.warpToken) ?: defaults.warpToken
        val warpLicenseKey = prefs.getString("warp_license_key", defaults.warpLicenseKey) ?: defaults.warpLicenseKey
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
        val vlessUuid = prefs.getString("vless_uuid", defaults.vlessUuid) ?: defaults.vlessUuid
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
        val isLivenessProbeEnabled = prefs.getBoolean("liveness_probe_enabled", defaults.isLivenessProbeEnabled)
        val livenessProbeTimeoutMs = prefs.getInt("liveness_probe_timeout_ms", defaults.livenessProbeTimeoutMs)
        val livenessProbeFailoverThreshold = prefs.getInt("liveness_probe_failover_threshold", defaults.livenessProbeFailoverThreshold)
        val useOperaVpnForVless = prefs.getBoolean("use_opera_vpn_for_vless", defaults.useOperaVpnForVless)
        val useOperaVpnForWarp = prefs.getBoolean("use_opera_vpn_for_warp", defaults.useOperaVpnForWarp)
        val operaVpnEndpoint = prefs.getString("opera_vpn_endpoint", defaults.operaVpnEndpoint) ?: defaults.operaVpnEndpoint
        val operaVpnNodeId = prefs.getString("opera_vpn_node_id", defaults.operaVpnNodeId) ?: defaults.operaVpnNodeId
        val warpWorkerDomain = prefs.getString("warp_worker_domain", defaults.warpWorkerDomain) ?: defaults.warpWorkerDomain

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
            isLivenessProbeEnabled = isLivenessProbeEnabled,
            livenessProbeTimeoutMs = livenessProbeTimeoutMs,
            livenessProbeFailoverThreshold = livenessProbeFailoverThreshold,
            useOperaVpnForVless = useOperaVpnForVless,
            useOperaVpnForWarp = useOperaVpnForWarp,
            operaVpnEndpoint = operaVpnEndpoint,
            operaVpnNodeId = operaVpnNodeId,
            warpWorkerDomain = warpWorkerDomain
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
            .apply()

        prefs.edit()
            .remove("secret_hex")
            .remove("warp_token")
            .remove("warp_private_key")
            .remove("warp_p256_private_key")
            .remove("warp_masque_token")
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
            .putString("socks5_username", config.socks5Username)
            .putString("socks5_password", config.socks5Password)
            .putBoolean("use_default_worker_socks5", config.useDefaultWorkerSocks5)
            .putBoolean("is_battery_guard_enabled", config.isBatteryGuardEnabled)
            .putInt("battery_guard_threshold", config.batteryGuardThreshold)
            .putBoolean("battery_guard_stop_on_powersave", config.batteryGuardStopOnPowerSave)
            .putBoolean("is_adaptive_qos_enabled", config.isAdaptiveQoSEnabled)
            .putString("proxy_mode", config.proxyModeName)
            .putStringSet("enabled_doh_providers", config.enabledDohProviderIds)
            .putString("uplink_mode", config.uplinkModeName)
            .putString("warp_account_id", config.warpAccountId)
            .putString("warp_license_key", config.warpLicenseKey)
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
            .putString("vless_uuid", config.vlessUuid)
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
            .putBoolean("liveness_probe_enabled", config.isLivenessProbeEnabled)
            .putInt("liveness_probe_timeout_ms", config.livenessProbeTimeoutMs)
            .putInt("liveness_probe_failover_threshold", config.livenessProbeFailoverThreshold)
            .putBoolean("use_opera_vpn_for_vless", config.useOperaVpnForVless)
            .putBoolean("use_opera_vpn_for_warp", config.useOperaVpnForWarp)
            .putString("opera_vpn_endpoint", config.operaVpnEndpoint)
            .putString("opera_vpn_node_id", config.operaVpnNodeId)
            .putString("warp_worker_domain", config.warpWorkerDomain)
            .apply()

        _isSocks5Flow.value = config.isSocks5Mode
        _uplinkModeFlow.value = config.uplinkMode
    }

    fun saveWarpProfile(profile: com.mirrly.tgproxy.core.WarpProfile) {
        // commit() вместо apply() — блокирующая запись гарантирует сохранение warp_token на диск
        // до возврата из функции. Это предотвращает бесконечный цикл регистрации при убийстве процесса,
        // когда apply() не успевает завершить асинхронную запись до смерти процесса.
        secretPrefs.edit()
            .putString("warp_token", profile.token)
            .putString("warp_private_key", profile.privateKeyBase64)
            .putString("warp_p256_private_key", profile.p256PrivateKeyBase64)
            .putString("warp_masque_token", profile.masqueToken)
            .commit()

        prefs.edit()
            .putString("warp_account_id", profile.accountId)
            .putString("warp_license_key", profile.licenseKey)
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
        val licenseKey = prefs.getString("warp_license_key", "") ?: ""
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
        prefs.edit().putString("uplink_mode", mode.name).apply()
        _uplinkModeFlow.value = mode
    }

    fun getVlessUuid(): String {
        return prefs.getString("vless_uuid", null) ?: com.mirrly.tgproxy.core.ProxyConfig().vlessUuid
    }

    fun setVlessUuid(uuid: String) {
        prefs.edit().putString("vless_uuid", uuid).apply()
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

    fun isLivenessProbeEnabled(): Boolean {
        return prefs.getBoolean("liveness_probe_enabled", true)
    }

    fun setLivenessProbeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("liveness_probe_enabled", enabled).apply()
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
}
