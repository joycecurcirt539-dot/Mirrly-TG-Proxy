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
import com.mirrly.tgproxy.ui.theme.ThemeAccentMode
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

import androidx.annotation.StringRes

/**
 * Стратегии автоматического выбора встроенных VPN-серверов.
 */
enum class VpnAutoSelectStrategy(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val title: String = "",
    val subtitle: String = ""
) {
    TEL_FIRST("TEL_FIRST", R.string.vpn_auto_strat_tel_title, R.string.vpn_auto_strat_tel_sub),
    LOWEST_PING("LOWEST_PING", R.string.vpn_auto_strat_ping_title, R.string.vpn_auto_strat_ping_sub),
    ANYCAST_FIRST("ANYCAST_FIRST", R.string.vpn_auto_strat_anycast_title, R.string.vpn_auto_strat_anycast_sub),
    CUSTOM_ORDER("CUSTOM_ORDER", R.string.vpn_auto_strat_custom_title, R.string.vpn_auto_strat_custom_sub)
}

/**
 * Категории серверов для пользовательской расстановки приоритетов автовыбора.
 */
enum class VpnAutoCategory(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val title: String = "",
    val description: String = ""
) {
    TEL("TEL", R.string.vpn_auto_cat_tel_title, R.string.vpn_auto_cat_tel_desc),
    ANYCAST("ANYCAST", R.string.vpn_auto_cat_anycast_title, R.string.vpn_auto_cat_anycast_desc),
    DEDICATED("DEDICATED", R.string.vpn_auto_cat_dedicated_title, R.string.vpn_auto_cat_dedicated_desc)
}

class PreferencesManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mirrly_tg_proxy_prefs", Context.MODE_PRIVATE)
    private val secretPrefs: SharedPreferences = context.getSharedPreferences("mirrly_secrets_prefs", Context.MODE_PRIVATE)

    private val _animationsDisabledFlow = MutableStateFlow(areAnimationsDisabled())
    val animationsDisabledFlow: StateFlow<Boolean> = _animationsDisabledFlow.asStateFlow()

    private val _isEchEnabledFlow = MutableStateFlow(isEchEnabled())
    val isEchEnabledFlow: StateFlow<Boolean> = _isEchEnabledFlow.asStateFlow()

    private val _isWsRandomizationEnabledFlow = MutableStateFlow(isWsRandomizationEnabled())
    val isWsRandomizationEnabledFlow: StateFlow<Boolean> = _isWsRandomizationEnabledFlow.asStateFlow()

    private val _isTlsEnabledFlow = MutableStateFlow(isTlsEnabled())
    val isTlsEnabledFlow: StateFlow<Boolean> = _isTlsEnabledFlow.asStateFlow()

    private val _vpnTlsEnabledFlow = MutableStateFlow(isVpnTlsEnabled())
    val vpnTlsEnabledFlow: StateFlow<Boolean> = _vpnTlsEnabledFlow.asStateFlow()

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

    private val _isWorkerSwipeEnabledFlow = MutableStateFlow(
        prefs.getBoolean("worker_swipe_enabled", !prefs.getBoolean("worker_swipe_blocked", true))
    )
    val isWorkerSwipeEnabledFlow: StateFlow<Boolean> = _isWorkerSwipeEnabledFlow.asStateFlow()

    private val _isWorkerSwipeBlockedFlow = MutableStateFlow(!_isWorkerSwipeEnabledFlow.value)
    val isWorkerSwipeBlockedFlow: StateFlow<Boolean> = _isWorkerSwipeBlockedFlow.asStateFlow()

    private val _isHomeModeSwipeEnabledFlow = MutableStateFlow(prefs.getBoolean("home_mode_swipe_enabled", true))
    val isHomeModeSwipeEnabledFlow: StateFlow<Boolean> = _isHomeModeSwipeEnabledFlow.asStateFlow()

    private val _uplinkModeFlow = MutableStateFlow(loadConfig().uplinkMode)
    val uplinkModeFlow: StateFlow<com.mirrly.tgproxy.core.UplinkMode> = _uplinkModeFlow.asStateFlow()

    private val _vpnUplinkModeFlow = MutableStateFlow(loadConfig().vpnUplinkMode)
    val vpnUplinkModeFlow: StateFlow<com.mirrly.tgproxy.core.UplinkMode> = _vpnUplinkModeFlow.asStateFlow()

    private val _isAlarmClockWatchdogEnabledFlow = MutableStateFlow(loadConfig().isAlarmClockWatchdogEnabled)
    val isAlarmClockWatchdogEnabledFlow: StateFlow<Boolean> = _isAlarmClockWatchdogEnabledFlow.asStateFlow()

    private val _isCpuAntiFreezeEnabledFlow = MutableStateFlow(loadConfig().isCpuAntiFreezeEnabled)
    val isCpuAntiFreezeEnabledFlow: StateFlow<Boolean> = _isCpuAntiFreezeEnabledFlow.asStateFlow()

    private val _isTrafficPulseEnabledFlow = MutableStateFlow(loadConfig().isTrafficPulseEnabled)
    val isTrafficPulseEnabledFlow: StateFlow<Boolean> = _isTrafficPulseEnabledFlow.asStateFlow()

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

    private val _vpnSelectedBuiltinServerIdFlow = MutableStateFlow(prefs.getString("vpn_selected_builtin_server_id", "builtin_tel_fi") ?: "builtin_tel_fi")
    val vpnSelectedBuiltinServerIdFlow: StateFlow<String> = _vpnSelectedBuiltinServerIdFlow.asStateFlow()

    private val _vpnUseCustomProfileFlow = MutableStateFlow(prefs.getBoolean("vpn_use_custom_profile", false))
    val vpnUseCustomProfileFlow: StateFlow<Boolean> = _vpnUseCustomProfileFlow.asStateFlow()

    // ── VPN Server Auto-Select Priority Settings Flows ──
    private val _vpnAutoSelectStrategyFlow = MutableStateFlow(getVpnAutoSelectStrategy())
    val vpnAutoSelectStrategyFlow: StateFlow<VpnAutoSelectStrategy> = _vpnAutoSelectStrategyFlow.asStateFlow()

    private val _vpnAutoSelectCategoryOrderFlow = MutableStateFlow(getVpnAutoSelectCategoryOrder())
    val vpnAutoSelectCategoryOrderFlow: StateFlow<List<String>> = _vpnAutoSelectCategoryOrderFlow.asStateFlow()

    private val _vpnAutoSelectDisabledServersFlow = MutableStateFlow(getVpnAutoSelectDisabledServers())
    val vpnAutoSelectDisabledServersFlow: StateFlow<Set<String>> = _vpnAutoSelectDisabledServersFlow.asStateFlow()

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

    private val _aiPauseBackgroundParticlesFlow = MutableStateFlow(isAiPauseBackgroundParticles())
    val aiPauseBackgroundParticlesFlow: StateFlow<Boolean> = _aiPauseBackgroundParticlesFlow.asStateFlow()

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

    /** Media type: [WALLPAPER_TYPE_IMAGE], [WALLPAPER_TYPE_VIDEO], or [WALLPAPER_TYPE_SHADER]. */
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

    /** Particle dynamics behavior style: [ParticleStyle.BOKEH_DUST], [ParticleStyle.BITSTREAM_MATRIX], [ParticleStyle.STARFIELD_WARP]. */
    private val _wallpaperParticlesStyleFlow = MutableStateFlow(
        prefs.getString(KEY_WALLPAPER_PARTICLES_STYLE, ParticleStyle.BOKEH_DUST.name) ?: ParticleStyle.BOKEH_DUST.name
    )
    val wallpaperParticlesStyleFlow: StateFlow<String> = _wallpaperParticlesStyleFlow.asStateFlow()

    /** Particle count / density in range [WALLPAPER_PARTICLES_COUNT_MIN]..[WALLPAPER_PARTICLES_COUNT_MAX]. */
    private val _wallpaperParticlesCountFlow = MutableStateFlow(
        prefs.getInt(KEY_WALLPAPER_PARTICLES_COUNT, WALLPAPER_PARTICLES_COUNT_DEFAULT).coerceIn(WALLPAPER_PARTICLES_COUNT_MIN, WALLPAPER_PARTICLES_COUNT_MAX)
    )
    val wallpaperParticlesCountFlow: StateFlow<Int> = _wallpaperParticlesCountFlow.asStateFlow()

    /** Particle movement speed multiplier in range [WALLPAPER_PARTICLES_SPEED_MIN]..[WALLPAPER_PARTICLES_SPEED_MAX]. */
    private val _wallpaperParticlesSpeedFlow = MutableStateFlow(
        prefs.getFloat(KEY_WALLPAPER_PARTICLES_SPEED, WALLPAPER_PARTICLES_SPEED_DEFAULT).coerceIn(WALLPAPER_PARTICLES_SPEED_MIN, WALLPAPER_PARTICLES_SPEED_MAX)
    )
    val wallpaperParticlesSpeedFlow: StateFlow<Float> = _wallpaperParticlesSpeedFlow.asStateFlow()

    /** Hardware gyroscopic 3D parallax toggle. */
    private val _wallpaperParallaxEnabledFlow = MutableStateFlow(
        prefs.getBoolean(KEY_WALLPAPER_PARALLAX_ENABLED, false)
    )
    val wallpaperParallaxEnabledFlow: StateFlow<Boolean> = _wallpaperParallaxEnabledFlow.asStateFlow()

    /** Hardware gyroscopic 3D parallax depth in range [WALLPAPER_PARALLAX_DEPTH_MIN]..[WALLPAPER_PARALLAX_DEPTH_MAX]. */
    private val _wallpaperParallaxDepthFlow = MutableStateFlow(
        prefs.getFloat(KEY_WALLPAPER_PARALLAX_DEPTH, WALLPAPER_PARALLAX_DEPTH_DEFAULT).coerceIn(WALLPAPER_PARALLAX_DEPTH_MIN, WALLPAPER_PARALLAX_DEPTH_MAX)
    )
    val wallpaperParallaxDepthFlow: StateFlow<Float> = _wallpaperParallaxDepthFlow.asStateFlow()

    /** Selected AGSL procedural GPU shader preset: [AgslShaderPreset.PERSPECTIVE_GRID], etc. */
    private val _wallpaperShaderPresetFlow = MutableStateFlow(
        AgslShaderPreset.fromString(prefs.getString(KEY_WALLPAPER_SHADER_PRESET, AgslShaderPreset.PERSPECTIVE_GRID.id))
    )
    val wallpaperShaderPresetFlow: StateFlow<AgslShaderPreset> = _wallpaperShaderPresetFlow.asStateFlow()

    /** Animation playback speed multiplier for AGSL procedural shaders [WALLPAPER_SHADER_SPEED_MIN]..[WALLPAPER_SHADER_SPEED_MAX]. */
    private val _wallpaperShaderSpeedFlow = MutableStateFlow(
        prefs.getFloat(KEY_WALLPAPER_SHADER_SPEED, WALLPAPER_SHADER_SPEED_DEFAULT)
            .coerceIn(WALLPAPER_SHADER_SPEED_MIN, WALLPAPER_SHADER_SPEED_MAX)
    )
    val wallpaperShaderSpeedFlow: StateFlow<Float> = _wallpaperShaderSpeedFlow.asStateFlow()

    /** Geometric corner style for MirrlyUI cards: [CardCornerStyle.ROUNDED], [CardCornerStyle.SHARP], [CardCornerStyle.CYBER_CHISEL]. */
    private val _cardCornerStyleFlow = MutableStateFlow(
        CardCornerStyle.fromString(prefs.getString(KEY_CARD_CORNER_STYLE, CardCornerStyle.ROUNDED.id))
    )
    val cardCornerStyleFlow: StateFlow<CardCornerStyle> = _cardCornerStyleFlow.asStateFlow()

    /** Corner radius in dp when [CardCornerStyle.ROUNDED] is active in range [CARD_CORNER_RADIUS_DP_MIN]..[CARD_CORNER_RADIUS_DP_MAX]. */
    private val _cardCornerRadiusDpFlow = MutableStateFlow(
        prefs.getFloat(KEY_CARD_CORNER_RADIUS_DP, CARD_CORNER_RADIUS_DP_DEFAULT).coerceIn(CARD_CORNER_RADIUS_DP_MIN, CARD_CORNER_RADIUS_DP_MAX)
    )
    val cardCornerRadiusDpFlow: StateFlow<Float> = _cardCornerRadiusDpFlow.asStateFlow()

    /** Tactile hardware haptic vibration profile: [HapticProfile.MECHANICAL_CLICK], etc. */
    private val _hapticProfileFlow = MutableStateFlow(
        HapticProfile.fromString(prefs.getString(KEY_HAPTIC_PROFILE, HapticProfile.MECHANICAL_CLICK.id))
    )
    val hapticProfileFlow: StateFlow<HapticProfile> = _hapticProfileFlow.asStateFlow()

    /** Master toggle that disables all haptic feedback and vibration across the app. */
    private val _vibrationDisabledFlow = MutableStateFlow(
        prefs.getBoolean(KEY_VIBRATION_DISABLED, false)
    )
    val vibrationDisabledFlow: StateFlow<Boolean> = _vibrationDisabledFlow.asStateFlow()

    /** Central orbital rings visibility on the home screen. */
    private val _homeRingsEnabledFlow = MutableStateFlow(
        prefs.getBoolean(KEY_HOME_RINGS_ENABLED, true)
    )
    val homeRingsEnabledFlow: StateFlow<Boolean> = _homeRingsEnabledFlow.asStateFlow()

    /** Liquid Glass hardware lens refraction & chromatic dispersion effects for community cards. */
    private val _liquidGlassEnabledFlow = MutableStateFlow(
        prefs.getBoolean(KEY_LIQUID_GLASS_ENABLED, true)
    )
    val liquidGlassEnabledFlow: StateFlow<Boolean> = _liquidGlassEnabledFlow.asStateFlow()

    /** Master UI scale multiplier for all cards, panels, and layouts in range [UI_SCALE_MIN]..[UI_SCALE_MAX]. */
    private val _uiScaleFlow = MutableStateFlow(
        prefs.getFloat(KEY_UI_SCALE, UI_SCALE_DEFAULT).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
    )
    val uiScaleFlow: StateFlow<Float> = _uiScaleFlow.asStateFlow()

    /** Typography text scale multiplier in range [TEXT_SCALE_MIN]..[TEXT_SCALE_MAX]. */
    private val _textScaleFlow = MutableStateFlow(
        prefs.getFloat(KEY_TEXT_SCALE, TEXT_SCALE_DEFAULT).coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
    )
    val textScaleFlow: StateFlow<Float> = _textScaleFlow.asStateFlow()

    /** Central orbital rings and background energy spheres scale in range [ORBITAL_SPHERES_SCALE_MIN]..[ORBITAL_SPHERES_SCALE_MAX]. */
    private val _orbitalSpheresScaleFlow = MutableStateFlow(
        prefs.getFloat(KEY_ORBITAL_SPHERES_SCALE, ORBITAL_SPHERES_SCALE_DEFAULT).coerceIn(ORBITAL_SPHERES_SCALE_MIN, ORBITAL_SPHERES_SCALE_MAX)
    )
    val orbitalSpheresScaleFlow: StateFlow<Float> = _orbitalSpheresScaleFlow.asStateFlow()

    /** Micro-particle size scale in range [WALLPAPER_PARTICLES_SIZE_MIN]..[WALLPAPER_PARTICLES_SIZE_MAX]. */
    private val _wallpaperParticlesSizeFlow = MutableStateFlow(
        prefs.getFloat(KEY_WALLPAPER_PARTICLES_SIZE, WALLPAPER_PARTICLES_SIZE_DEFAULT).coerceIn(WALLPAPER_PARTICLES_SIZE_MIN, WALLPAPER_PARTICLES_SIZE_MAX)
    )
    val wallpaperParticlesSizeFlow: StateFlow<Float> = _wallpaperParticlesSizeFlow.asStateFlow()

    /** Active preset ID if the current background corresponds to a preset (or null if ad-hoc custom file). */
    private val _customBackgroundActivePresetIdFlow = MutableStateFlow<String?>(
        prefs.getString(KEY_WALLPAPER_ACTIVE_PRESET_ID, null)
    )
    val customBackgroundActivePresetIdFlow: StateFlow<String?> = _customBackgroundActivePresetIdFlow.asStateFlow()

    /** User-saved custom presets list. */
    private val _customBackgroundUserPresetsFlow = MutableStateFlow<List<WallpaperPreset>>(emptyList())
    val customBackgroundUserPresetsFlow: StateFlow<List<WallpaperPreset>> = _customBackgroundUserPresetsFlow.asStateFlow()

    /** Auto-saved custom preset waiting for the one-time black-default migration notice, or null. */
    private val _pendingMigratedWallpaperPresetFlow = MutableStateFlow<WallpaperPreset?>(null)
    val pendingMigratedWallpaperPresetFlow: StateFlow<WallpaperPreset?> = _pendingMigratedWallpaperPresetFlow.asStateFlow()

    // ── Theme Accent & Customization Flows ──
    private val _themeAccentModeFlow = MutableStateFlow(getThemeAccentMode())
    val themeAccentModeFlow: StateFlow<ThemeAccentMode> = _themeAccentModeFlow.asStateFlow()

    private val _isCustomThemeEnabledFlow = MutableStateFlow(getThemeAccentMode() != ThemeAccentMode.PROTOCOL_DEFAULT)
    val isCustomThemeEnabledFlow: StateFlow<Boolean> = _isCustomThemeEnabledFlow.asStateFlow()

    private val _themePresetIdFlow = MutableStateFlow(getThemePresetId())
    val themePresetIdFlow: StateFlow<String> = _themePresetIdFlow.asStateFlow()

    private val _themeCustomColorHexFlow = MutableStateFlow(getThemeCustomColorHex())
    val themeCustomColorHexFlow: StateFlow<String> = _themeCustomColorHexFlow.asStateFlow()

    private val _themeBrightnessFlow = MutableStateFlow(getThemeBrightness())
    val themeBrightnessFlow: StateFlow<Float> = _themeBrightnessFlow.asStateFlow()

    private val _themeSaturationFlow = MutableStateFlow(getThemeSaturation())
    val themeSaturationFlow: StateFlow<Float> = _themeSaturationFlow.asStateFlow()

    private val _themeAlphaFlow = MutableStateFlow(getThemeAlpha())
    val themeAlphaFlow: StateFlow<Float> = _themeAlphaFlow.asStateFlow()

    private val _appIconIdFlow = MutableStateFlow(getAppIconId())
    val appIconIdFlow: StateFlow<String> = _appIconIdFlow.asStateFlow()

    // ── Central Button Icon & Custom Code Flows ──
    private val _centralButtonIconMtprotoFlow = MutableStateFlow(getCentralButtonIcon("mtproto"))
    val centralButtonIconMtprotoFlow: StateFlow<String> = _centralButtonIconMtprotoFlow.asStateFlow()

    private val _centralButtonIconSocks5Flow = MutableStateFlow(getCentralButtonIcon("socks5"))
    val centralButtonIconSocks5Flow: StateFlow<String> = _centralButtonIconSocks5Flow.asStateFlow()

    private val _centralButtonIconVpnFlow = MutableStateFlow(getCentralButtonIcon("vpn"))
    val centralButtonIconVpnFlow: StateFlow<String> = _centralButtonIconVpnFlow.asStateFlow()

    private val _customIconCodeMtprotoFlow = MutableStateFlow(getCustomIconCode("mtproto"))
    val customIconCodeMtprotoFlow: StateFlow<String> = _customIconCodeMtprotoFlow.asStateFlow()

    private val _customIconCodeSocks5Flow = MutableStateFlow(getCustomIconCode("socks5"))
    val customIconCodeSocks5Flow: StateFlow<String> = _customIconCodeSocks5Flow.asStateFlow()

    private val _customIconCodeVpnFlow = MutableStateFlow(getCustomIconCode("vpn"))
    val customIconCodeVpnFlow: StateFlow<String> = _customIconCodeVpnFlow.asStateFlow()

    private val _centralButtonScaleFlow = MutableStateFlow(getCentralButtonScale())
    val centralButtonScaleFlow: StateFlow<Float> = _centralButtonScaleFlow.asStateFlow()

    private val _centralButtonStrokeWidthFlow = MutableStateFlow(getCentralButtonStrokeWidth())
    val centralButtonStrokeWidthFlow: StateFlow<Float> = _centralButtonStrokeWidthFlow.asStateFlow()

    private val _centralButtonGlowIntensityFlow = MutableStateFlow(getCentralButtonGlowIntensity())
    val centralButtonGlowIntensityFlow: StateFlow<Float> = _centralButtonGlowIntensityFlow.asStateFlow()

    private val _aiChatDraftFlow = MutableStateFlow(getAiChatDraft())
    val aiChatDraftFlow: StateFlow<String> = _aiChatDraftFlow.asStateFlow()

    // ── Cloudflare Session Reactive Flows ──
    private val _isCloudflareAuthorizedFlow = MutableStateFlow(isCloudflareAuthorized())
    val isCloudflareAuthorizedFlow: StateFlow<Boolean> = _isCloudflareAuthorizedFlow.asStateFlow()

    private val _cloudflareAccountIdFlow = MutableStateFlow(getCloudflareAccountId().orEmpty())
    val cloudflareAccountIdFlow: StateFlow<String> = _cloudflareAccountIdFlow.asStateFlow()

    private val _cloudflareAccountNameFlow = MutableStateFlow(getCloudflareAccountName().orEmpty())
    val cloudflareAccountNameFlow: StateFlow<String> = _cloudflareAccountNameFlow.asStateFlow()

    private val _cloudflareSubdomainFlow = MutableStateFlow(getCloudflareSubdomain().orEmpty())
    val cloudflareSubdomainFlow: StateFlow<String> = _cloudflareSubdomainFlow.asStateFlow()

    fun updateCloudflareSessionFlows() {
        _isCloudflareAuthorizedFlow.value = isCloudflareAuthorized()
        _cloudflareAccountIdFlow.value = getCloudflareAccountId().orEmpty()
        _cloudflareAccountNameFlow.value = getCloudflareAccountName().orEmpty()
        _cloudflareSubdomainFlow.value = getCloudflareSubdomain().orEmpty()
    }

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == KEY_THEME_ACCENT_MODE) {
            val mode = getThemeAccentMode()
            _themeAccentModeFlow.value = mode
            _isCustomThemeEnabledFlow.value = (mode != ThemeAccentMode.PROTOCOL_DEFAULT)
        }
        if (key == KEY_THEME_PRESET_ID) {
            _themePresetIdFlow.value = getThemePresetId()
        }
        if (key == KEY_THEME_CUSTOM_COLOR_HEX) {
            _themeCustomColorHexFlow.value = getThemeCustomColorHex()
        }
        if (key == KEY_THEME_BRIGHTNESS) {
            _themeBrightnessFlow.value = getThemeBrightness()
        }
        if (key == KEY_THEME_SATURATION) {
            _themeSaturationFlow.value = getThemeSaturation()
        }
        if (key == KEY_THEME_ALPHA) {
            _themeAlphaFlow.value = getThemeAlpha()
        }
        if (key == KEY_APP_ICON_ID) {
            _appIconIdFlow.value = getAppIconId()
        }
        if (key == KEY_CENTRAL_BUTTON_ICON_MTPROTO) {
            _centralButtonIconMtprotoFlow.value = getCentralButtonIcon("mtproto")
        }
        if (key == KEY_CENTRAL_BUTTON_ICON_SOCKS5) {
            _centralButtonIconSocks5Flow.value = getCentralButtonIcon("socks5")
        }
        if (key == KEY_CENTRAL_BUTTON_ICON_VPN) {
            _centralButtonIconVpnFlow.value = getCentralButtonIcon("vpn")
        }
        if (key == KEY_CUSTOM_ICON_CODE_MTPROTO) {
            _customIconCodeMtprotoFlow.value = getCustomIconCode("mtproto")
        }
        if (key == KEY_CUSTOM_ICON_CODE_SOCKS5) {
            _customIconCodeSocks5Flow.value = getCustomIconCode("socks5")
        }
        if (key == KEY_CUSTOM_ICON_CODE_VPN) {
            _customIconCodeVpnFlow.value = getCustomIconCode("vpn")
        }
        if (key == KEY_CENTRAL_BUTTON_SCALE) {
            _centralButtonScaleFlow.value = getCentralButtonScale()
        }
        if (key == KEY_CENTRAL_BUTTON_STROKE_WIDTH) {
            _centralButtonStrokeWidthFlow.value = getCentralButtonStrokeWidth()
        }
        if (key == KEY_CENTRAL_BUTTON_GLOW_INTENSITY) {
            _centralButtonGlowIntensityFlow.value = getCentralButtonGlowIntensity()
        }
        if (key == KEY_AI_CHAT_DRAFT) {
            _aiChatDraftFlow.value = getAiChatDraft()
        }
        if (key == "disable_animations_particles") {
            _animationsDisabledFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key == KEY_HAPTIC_PROFILE) {
            _hapticProfileFlow.value = HapticProfile.fromString(
                sharedPreferences.getString(key, HapticProfile.MECHANICAL_CLICK.id)
            )
        }
        if (key == KEY_VIBRATION_DISABLED) {
            _vibrationDisabledFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key == KEY_WALLPAPER_PARTICLES_STYLE) {
            _wallpaperParticlesStyleFlow.value = sharedPreferences.getString(key, ParticleStyle.BOKEH_DUST.name) ?: ParticleStyle.BOKEH_DUST.name
        }
        if (key == KEY_WALLPAPER_PARTICLES_COUNT) {
            _wallpaperParticlesCountFlow.value = sharedPreferences.getInt(key, WALLPAPER_PARTICLES_COUNT_DEFAULT).coerceIn(WALLPAPER_PARTICLES_COUNT_MIN, WALLPAPER_PARTICLES_COUNT_MAX)
        }
        if (key == KEY_WALLPAPER_PARTICLES_SPEED) {
            _wallpaperParticlesSpeedFlow.value = sharedPreferences.getFloat(key, WALLPAPER_PARTICLES_SPEED_DEFAULT).coerceIn(WALLPAPER_PARTICLES_SPEED_MIN, WALLPAPER_PARTICLES_SPEED_MAX)
        }
        if (key == KEY_WALLPAPER_PARALLAX_ENABLED) {
            _wallpaperParallaxEnabledFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key == KEY_HOME_RINGS_ENABLED) {
            _homeRingsEnabledFlow.value = sharedPreferences.getBoolean(key, true)
        }
        if (key == KEY_LIQUID_GLASS_ENABLED) {
            _liquidGlassEnabledFlow.value = sharedPreferences.getBoolean(key, true)
        }
        if (key == KEY_UI_SCALE) {
            _uiScaleFlow.value = sharedPreferences.getFloat(key, UI_SCALE_DEFAULT).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
        }
        if (key == KEY_TEXT_SCALE) {
            _textScaleFlow.value = sharedPreferences.getFloat(key, TEXT_SCALE_DEFAULT).coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
        }
        if (key == KEY_ORBITAL_SPHERES_SCALE) {
            _orbitalSpheresScaleFlow.value = sharedPreferences.getFloat(key, ORBITAL_SPHERES_SCALE_DEFAULT).coerceIn(ORBITAL_SPHERES_SCALE_MIN, ORBITAL_SPHERES_SCALE_MAX)
        }
        if (key == KEY_WALLPAPER_PARTICLES_SIZE) {
            _wallpaperParticlesSizeFlow.value = sharedPreferences.getFloat(key, WALLPAPER_PARTICLES_SIZE_DEFAULT).coerceIn(WALLPAPER_PARTICLES_SIZE_MIN, WALLPAPER_PARTICLES_SIZE_MAX)
        }
        if (key == KEY_ECH_ENABLED) {
            _isEchEnabledFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key == KEY_WS_RANDOMIZATION_ENABLED) {
            _isWsRandomizationEnabledFlow.value = sharedPreferences.getBoolean(key, true)
        }
        if (key == KEY_TLS_ENABLED) {
            _isTlsEnabledFlow.value = sharedPreferences.getBoolean(
                key,
                com.mirrly.tgproxy.util.LocaleHelper.isPersianLanguage(getAppLanguage())
            )
        }
        if (key == KEY_VPN_TLS_ENABLED) {
            _vpnTlsEnabledFlow.value = sharedPreferences.getBoolean(key, true)
        }
        if (key == KEY_TLS_RECORD_PADDING_ENABLED) {
            _isTlsRecordPaddingEnabledFlow.value = sharedPreferences.getBoolean(key, true)
        }
        if (key == KEY_TLS_CLIENT_HELLO_SPLIT_ENABLED) {
            _isTlsClientHelloSplitEnabledFlow.value = sharedPreferences.getBoolean(key, true)
        }
        if (key == KEY_SOCKS5_UPLINK_MODE) {
            val mode = sharedPreferences.getInt(key, 1)
            _socks5UplinkModeFlow.value = mode
            try {
                MirrlyApplication.instance.config.socks5UplinkMode = mode
                MirrlyApplication.instance.proxyServer.applySocks5UplinkMode(mode)
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
        if (key == "vpn_selected_builtin_server_id") {
            _vpnSelectedBuiltinServerIdFlow.value = sharedPreferences.getString(key, "builtin_tel_fi") ?: "builtin_tel_fi"
        }
        if (key == "vpn_use_custom_profile") {
            _vpnUseCustomProfileFlow.value = sharedPreferences.getBoolean(key, false)
        }
        if (key == "vpn_auto_select_strategy") {
            _vpnAutoSelectStrategyFlow.value = getVpnAutoSelectStrategy()
        }
        if (key == "vpn_auto_select_category_order") {
            _vpnAutoSelectCategoryOrderFlow.value = getVpnAutoSelectCategoryOrder()
        }
        if (key == "vpn_auto_select_disabled_servers") {
            _vpnAutoSelectDisabledServersFlow.value = getVpnAutoSelectDisabledServers()
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
        if (key == "cf_account_id" || key == "cf_account_name" || key == "cf_subdomain") {
            updateCloudflareSessionFlows()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        _customBackgroundUserPresetsFlow.value = getUserPresets()
        ensureDefaultWallpaperInitialized()
        _pendingMigratedWallpaperPresetFlow.value = resolvePendingMigratedWallpaperPreset()
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
        val bufferMigrationDone = prefs.getBoolean("socket_buffer_autotune_migrated_v4", false)
        val bufferSizeBytes = if (!bufferMigrationDone) {
            if (speedPresetName == com.mirrly.tgproxy.core.SpeedPreset.AUTO.name ||
                savedBufferSizeBytes == 262144 || savedBufferSizeBytes == 131072
            ) {
                0
            } else {
                savedBufferSizeBytes
            }
        } else {
            savedBufferSizeBytes
        }
        if (!bufferMigrationDone) {
            prefs.edit()
                .putInt("buffer_size_bytes", bufferSizeBytes)
                .putBoolean("socket_buffer_autotune_migrated_v4", true)
                .apply()
        }
        val happyEyeballsDelayMs = prefs.getLong("happy_eyeballs_delay_ms", defaults.happyEyeballsDelayMs)
        val ipFamilyPreferenceName = prefs.getString("ip_family_preference", defaults.ipFamilyPreferenceName) ?: defaults.ipFamilyPreferenceName
        val webSocketKeepAliveSeconds = prefs.getInt("socket_keep_alive_seconds", defaults.webSocketKeepAliveSeconds)
        var socks5Port = prefs.getInt("socks5_port", defaults.socks5Port)
        val socks5PortMigrationDone = prefs.getBoolean("socks5_port_migrated_10888", false)
        if (!socks5PortMigrationDone) {
            if (socks5Port == 10808) {
                socks5Port = defaults.socks5Port
                prefs.edit().putInt("socks5_port", socks5Port).putBoolean("socks5_port_migrated_10888", true).apply()
            } else {
                prefs.edit().putBoolean("socks5_port_migrated_10888", true).apply()
            }
        }
        val socks5Username = prefs.getString("socks5_username", defaults.socks5Username) ?: defaults.socks5Username
        val socks5PasswordRaw = secretPrefs.getString("socks5_password", null) ?: prefs.getString("socks5_password", defaults.socks5Password) ?: defaults.socks5Password
        val socks5Password = WarpKeystoreEncryption.decrypt(socks5PasswordRaw)
        val useDefaultWorkerSocks5 = prefs.getBoolean("use_default_worker_socks5", defaults.useDefaultWorkerSocks5)
        val socks5OptimisticResponse = prefs.getBoolean("socks5_optimistic_response", defaults.socks5OptimisticResponse)
        val hasValidCustomWorker = getCustomWorkers().any { ProxyConfig.sanitizeDomain(it.domain).isNotBlank() }
        val socks5UplinkStartup = Socks5UplinkPolicy.resolveStartupState(
            storedUplinkMode = prefs.getInt(KEY_SOCKS5_UPLINK_MODE, defaults.socks5UplinkMode),
            v2MigrationDone = prefs.getBoolean(KEY_SOCKS5_UPLINK_FLOWSEAL_MIGRATED_V2, false),
            hasValidCustomWorker = hasValidCustomWorker
        )
        val socks5UplinkMode = socks5UplinkStartup.effectiveUplinkMode
        if (socks5UplinkStartup.shouldPersistMode || socks5UplinkStartup.markV2Migrated) {
            prefs.edit().apply {
                if (socks5UplinkStartup.shouldPersistMode) {
                    putInt(KEY_SOCKS5_UPLINK_MODE, socks5UplinkMode)
                }
                if (socks5UplinkStartup.markV2Migrated) {
                    putBoolean("socks5_uplink_default_flowseal_migrated_v1", true)
                    putBoolean(KEY_SOCKS5_UPLINK_FLOWSEAL_MIGRATED_V2, true)
                }
                apply()
            }
        }
        val targetTelegramPort = prefs.getInt("target_telegram_port", defaults.targetTelegramPort)
        val useWorkerMtproto = prefs.getBoolean("use_worker_mtproto", defaults.useWorkerMtproto)

        val currentAppLanguage = getAppLanguage()
        val tlsStartupState = resolveTlsStartupState(
            langCode = currentAppLanguage,
            systemLanguage = com.mirrly.tgproxy.util.LocaleHelper.getSystemLocale().language,
            hasStoredTlsValue = prefs.contains(KEY_TLS_ENABLED),
            storedTlsValue = prefs.getBoolean(KEY_TLS_ENABLED, false),
            v1MigrationDone = prefs.getBoolean("tls_default_disabled_migrated_v1", false),
            faMigrationDone = prefs.getBoolean(KEY_TLS_FA_DEFAULT_MIGRATED, false),
            hasUserOverride = prefs.getBoolean(KEY_TLS_USER_OVERRIDE, false)
        )
        val isTlsEnabled = tlsStartupState.isTlsEnabled
        if (tlsStartupState.shouldPersistTls ||
            tlsStartupState.markUserOverride ||
            tlsStartupState.markV1Migrated ||
            tlsStartupState.markFaDefaultMigrated
        ) {
            prefs.edit().apply {
                if (tlsStartupState.shouldPersistTls) {
                    putBoolean(KEY_TLS_ENABLED, isTlsEnabled)
                }
                if (tlsStartupState.markUserOverride) {
                    putBoolean(KEY_TLS_USER_OVERRIDE, true)
                }
                if (tlsStartupState.markV1Migrated) {
                    putBoolean("tls_default_disabled_migrated_v1", true)
                }
                if (tlsStartupState.markFaDefaultMigrated) {
                    putBoolean(KEY_TLS_FA_DEFAULT_MIGRATED, true)
                }
                apply()
            }
            _isTlsEnabledFlow.value = isTlsEnabled
        }
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
        val isAlarmClockWatchdogEnabled = prefs.getBoolean("is_alarm_clock_watchdog_enabled", defaults.isAlarmClockWatchdogEnabled)
        val isCpuAntiFreezeEnabled = prefs.getBoolean("is_cpu_anti_freeze_enabled", defaults.isCpuAntiFreezeEnabled)
        val isTrafficPulseEnabled = prefs.getBoolean("is_traffic_pulse_enabled", defaults.isTrafficPulseEnabled)
        if (!qosMigrationDone) {
            prefs.edit()
                .putBoolean("is_battery_guard_enabled", isBatteryGuardEnabled)
                .putBoolean("battery_guard_stop_on_powersave", batteryGuardStopOnPowerSave)
                .putBoolean("is_adaptive_qos_enabled", isAdaptiveQoSEnabled)
                .putBoolean("qos_default_disabled_migrated_v2", true)
                .apply()
        }

        val uplinkModeName = com.mirrly.tgproxy.core.UplinkMode.WORKER.name
        var vpnUplinkModeName = prefs.getString("vpn_uplink_mode", defaults.vpnUplinkModeName) ?: defaults.vpnUplinkModeName
        if (vpnUplinkModeName != com.mirrly.tgproxy.core.UplinkMode.AWG.name) {
            vpnUplinkModeName = com.mirrly.tgproxy.core.UplinkMode.AWG.name
        }
        val vpnMtu = prefs.getInt("vpn_mtu", defaults.vpnMtu)
        val vpnBlockQuic = prefs.getBoolean("vpn_block_quic", defaults.vpnBlockQuic)
        val vpnBlockIpv6Leaks = prefs.getBoolean("vpn_block_ipv6_leaks", defaults.vpnBlockIpv6Leaks)
        val isVpnTlsEnabled = prefs.getBoolean(KEY_VPN_TLS_ENABLED, defaults.isVpnTlsEnabled)
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
        val isWgValid = prefs.getBoolean("is_wg_valid", defaults.isWgValid)
        val warpClientId = prefs.getString("warp_client_id", defaults.warpClientId) ?: defaults.warpClientId
        val isWarpPlus = prefs.getBoolean("is_warp_plus", defaults.isWarpPlus)
        val isWarpAccountActive = prefs.getBoolean("is_warp_account_active", defaults.isWarpAccountActive)
        val awgStrategyName = prefs.getString("awg_strategy_name", defaults.awgStrategyName) ?: defaults.awgStrategyName
        val awgCustomIni = prefs.getString("awg_custom_ini", defaults.awgCustomIni) ?: defaults.awgCustomIni
        val warpMeasuredWgEndpoint = prefs.getString("warp_measured_wg_endpoint", defaults.warpMeasuredWgEndpoint) ?: defaults.warpMeasuredWgEndpoint

        return ProxyConfig(
            bindHost = bindHost,
            bindPort = bindPort,
            secretHex = secretHex,
            cfProxyEnabled = cfEnabled,
            customCfDomain = customDomain,
            isTlsEnabled = isTlsEnabled,
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
            isAlarmClockWatchdogEnabled = isAlarmClockWatchdogEnabled,
            isCpuAntiFreezeEnabled = isCpuAntiFreezeEnabled,
            isTrafficPulseEnabled = isTrafficPulseEnabled,
            proxyModeName = proxyModeName,
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
            isWgValid = isWgValid,
            warpClientId = warpClientId,
            warpMeasuredWgEndpoint = warpMeasuredWgEndpoint,
            awgStrategyName = awgStrategyName,
            awgCustomIni = awgCustomIni,
            vpnDnsModeName = vpnDnsModeName,
            vpnCustomDnsIps = vpnCustomDnsIps,
            isVpnTlsEnabled = isVpnTlsEnabled
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
            .putString("socks5_password", WarpKeystoreEncryption.encrypt(config.socks5Password))
            .putString("warp_license_key", WarpKeystoreEncryption.encrypt(config.warpLicenseKey))
            .apply()

        prefs.edit()
            .remove("secret_hex")
            .remove("warp_token")
            .remove("warp_private_key")
            .remove("warp_p256_private_key")
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
            .putBoolean(KEY_TLS_ENABLED, config.isTlsEnabled)
            .putBoolean("is_adaptive_qos_enabled", config.isAdaptiveQoSEnabled)
            .putBoolean("dial_throttling_enabled", config.isDialThrottlingEnabled)
            .putBoolean("is_alarm_clock_watchdog_enabled", config.isAlarmClockWatchdogEnabled)
            .putBoolean("is_cpu_anti_freeze_enabled", config.isCpuAntiFreezeEnabled)
            .putBoolean("is_traffic_pulse_enabled", config.isTrafficPulseEnabled)
            .putString("proxy_mode", config.proxyModeName)
            .putString("uplink_mode", config.uplinkModeName)
            .putString("warp_account_id", config.warpAccountId)
            .putString("warp_client_ipv4", config.warpClientIpv4)
            .putString("warp_client_ipv6", config.warpClientIpv6)
            .putString("warp_peer_endpoint", config.warpPeerEndpoint)
            .putString("warp_peer_public_key", config.warpPeerPublicKey)
            .putString("warp_public_key", config.warpPublicKey)
            .putString("warp_p256_public_key", config.warpP256PublicKey)
            .putString("warp_client_cert", config.warpClientCert)
            .putString("warp_client_id", config.warpClientId)
            .putString("warp_measured_wg_endpoint", config.warpMeasuredWgEndpoint)
            .putBoolean("is_wg_valid", config.isWgValid)
            .putBoolean("is_warp_plus", config.isWarpPlus)
            .putBoolean("is_warp_account_active", config.isWarpAccountActive)
            .putString("awg_strategy_name", config.awgStrategyName)
            .putString("awg_custom_ini", config.awgCustomIni)
            .putString("vpn_uplink_mode", config.vpnUplinkModeName)
            .putInt("vpn_mtu", config.vpnMtu)
            .putBoolean("vpn_block_quic", config.vpnBlockQuic)
            .putBoolean("vpn_block_ipv6_leaks", config.vpnBlockIpv6Leaks)
            .putBoolean(KEY_VPN_TLS_ENABLED, config.isVpnTlsEnabled)
            .putString("vpn_dns_mode", config.vpnDnsModeName)
            .putString("vpn_custom_dns_ips", config.vpnCustomDnsIps)
            .apply()

        _isSocks5Flow.value = config.isSocks5Mode
        _proxyModeFlow.value = config.proxyMode
        _uplinkModeFlow.value = config.uplinkMode
        _vpnUplinkModeFlow.value = config.vpnUplinkMode
        _vpnTlsEnabledFlow.value = config.isVpnTlsEnabled
        _isAlarmClockWatchdogEnabledFlow.value = config.isAlarmClockWatchdogEnabled
        _isCpuAntiFreezeEnabledFlow.value = config.isCpuAntiFreezeEnabled
        _isTrafficPulseEnabledFlow.value = config.isTrafficPulseEnabled
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
            MirrlyApplication.instance.proxyServer.applySocks5UplinkMode(mode)
        } catch (_: Throwable) {}
        com.mirrly.tgproxy.core.NativeProxy.setSocks5UplinkMode(mode)
    }

    fun getSocks5UplinkMode(): Int {
        return prefs.getInt(KEY_SOCKS5_UPLINK_MODE, 1)
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
            .putString("warp_client_id", profile.clientId)
            .putBoolean("is_wg_valid", profile.isWgValid)
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
            .putString("warp_measured_wg_endpoint", profile.measuredWgEndpoint ?: "")
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
        val isWgValid = prefs.getBoolean("is_wg_valid", accountId.isNotBlank() && accountId != "mirrly-warp-bootstrap-id")
        val isWarpPlus = prefs.getBoolean("is_warp_plus", false)
        val isWarpAccountActive = prefs.getBoolean("is_warp_account_active", false)
        val isReg = prefs.getBoolean("is_registered", true)
        val isAct = prefs.getBoolean("is_activated", isWarpAccountActive)
        val entName = prefs.getString("entitlement", "") ?: ""
        val entitlement = try {
            if (entName.isNotBlank()) com.mirrly.tgproxy.core.WarpEntitlement.valueOf(entName) else if (isWarpPlus) com.mirrly.tgproxy.core.WarpEntitlement.PLUS else com.mirrly.tgproxy.core.WarpEntitlement.FREE
        } catch (_: Exception) {
            if (isWarpPlus) com.mirrly.tgproxy.core.WarpEntitlement.PLUS else com.mirrly.tgproxy.core.WarpEntitlement.FREE
        }
        val needsVer = prefs.getBoolean("needs_verification", false)
        val isOffline = prefs.getBoolean("is_offline_cached", false)
        val credsValid = prefs.getBoolean("credentials_valid", true)
        val dpReady = prefs.getBoolean("data_plane_ready", true)
        val apiEndpoint = prefs.getString("warp_api_endpoint", "") ?: ""
        val userEndpointOverride = prefs.getString("warp_user_endpoint_override", "").takeIf { !it.isNullOrBlank() }
        val measuredWgEndpoint = prefs.getString("warp_measured_wg_endpoint", "").takeIf { !it.isNullOrBlank() }
        val clientId = prefs.getString("warp_client_id", "") ?: ""

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
            isRegistered = isReg,
            isActivated = isAct,
            credentialsValid = credsValid,
            dataPlaneReady = dpReady,
            entitlement = entitlement,
            needsVerification = needsVer,
            isOfflineCached = isOffline,
            isWgValid = isWgValid,
            apiEndpoint = apiEndpoint,
            userEndpointOverride = userEndpointOverride,
            measuredWgEndpoint = measuredWgEndpoint,
            clientId = clientId
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
        if (type == WALLPAPER_TYPE_SHADER) return null
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
        if (type == WALLPAPER_TYPE_SHADER) {
            val preset = prefs.getString(KEY_WALLPAPER_SHADER_PRESET, AgslShaderPreset.PERSPECTIVE_GRID.id)
                ?: AgslShaderPreset.PERSPECTIVE_GRID.id
            return "shader://$preset"
        }
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
            .putString(KEY_WALLPAPER_ACTIVE_PRESET_ID, WallpaperPreset.PRESET_ID_DEFAULT_AMOLED)
            .apply()
        _customBackgroundActivePresetIdFlow.value = WallpaperPreset.PRESET_ID_DEFAULT_AMOLED
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

    fun getWallpaperParticlesStyle(): ParticleStyle {
        return ParticleStyle.fromString(_wallpaperParticlesStyleFlow.value)
    }

    fun setWallpaperParticlesStyle(style: ParticleStyle) {
        _wallpaperParticlesStyleFlow.value = style.name
        prefs.edit().putString(KEY_WALLPAPER_PARTICLES_STYLE, style.name).apply()
    }

    fun setWallpaperParticlesCount(count: Int, persist: Boolean = true) {
        val clamped = count.coerceIn(WALLPAPER_PARTICLES_COUNT_MIN, WALLPAPER_PARTICLES_COUNT_MAX)
        _wallpaperParticlesCountFlow.value = clamped
        if (persist) prefs.edit().putInt(KEY_WALLPAPER_PARTICLES_COUNT, clamped).apply()
    }

    fun setWallpaperParticlesSpeed(speed: Float, persist: Boolean = true) {
        val clamped = speed.coerceIn(WALLPAPER_PARTICLES_SPEED_MIN, WALLPAPER_PARTICLES_SPEED_MAX)
        _wallpaperParticlesSpeedFlow.value = clamped
        if (persist) prefs.edit().putFloat(KEY_WALLPAPER_PARTICLES_SPEED, clamped).apply()
    }

    fun setWallpaperParallaxEnabled(enabled: Boolean) {
        _wallpaperParallaxEnabledFlow.value = enabled
        prefs.edit().putBoolean(KEY_WALLPAPER_PARALLAX_ENABLED, enabled).apply()
    }

    fun isHomeRingsEnabled(): Boolean = _homeRingsEnabledFlow.value

    fun setHomeRingsEnabled(enabled: Boolean) {
        _homeRingsEnabledFlow.value = enabled
        prefs.edit().putBoolean(KEY_HOME_RINGS_ENABLED, enabled).apply()
    }

    fun isLiquidGlassEnabled(): Boolean = _liquidGlassEnabledFlow.value

    fun setLiquidGlassEnabled(enabled: Boolean) {
        _liquidGlassEnabledFlow.value = enabled
        prefs.edit().putBoolean(KEY_LIQUID_GLASS_ENABLED, enabled).apply()
    }

    fun getUiScale(): Float = _uiScaleFlow.value

    fun setUiScale(scale: Float, persist: Boolean = true) {
        val clamped = scale.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
        _uiScaleFlow.value = clamped
        if (persist) prefs.edit().putFloat(KEY_UI_SCALE, clamped).apply()
    }

    fun getTextScale(): Float = _textScaleFlow.value

    fun setTextScale(scale: Float, persist: Boolean = true) {
        val clamped = scale.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
        _textScaleFlow.value = clamped
        if (persist) prefs.edit().putFloat(KEY_TEXT_SCALE, clamped).apply()
    }

    fun getOrbitalSpheresScale(): Float = _orbitalSpheresScaleFlow.value

    fun setOrbitalSpheresScale(scale: Float, persist: Boolean = true) {
        val clamped = scale.coerceIn(ORBITAL_SPHERES_SCALE_MIN, ORBITAL_SPHERES_SCALE_MAX)
        _orbitalSpheresScaleFlow.value = clamped
        if (persist) prefs.edit().putFloat(KEY_ORBITAL_SPHERES_SCALE, clamped).apply()
    }

    fun getWallpaperParticlesSize(): Float = _wallpaperParticlesSizeFlow.value

    fun setWallpaperParticlesSize(size: Float, persist: Boolean = true) {
        val clamped = size.coerceIn(WALLPAPER_PARTICLES_SIZE_MIN, WALLPAPER_PARTICLES_SIZE_MAX)
        _wallpaperParticlesSizeFlow.value = clamped
        if (persist) prefs.edit().putFloat(KEY_WALLPAPER_PARTICLES_SIZE, clamped).apply()
    }

    fun resetAllUiDimensions() {
        _uiScaleFlow.value = UI_SCALE_DEFAULT
        _textScaleFlow.value = TEXT_SCALE_DEFAULT
        _orbitalSpheresScaleFlow.value = ORBITAL_SPHERES_SCALE_DEFAULT
        _wallpaperParticlesSizeFlow.value = WALLPAPER_PARTICLES_SIZE_DEFAULT
        prefs.edit()
            .putFloat(KEY_UI_SCALE, UI_SCALE_DEFAULT)
            .putFloat(KEY_TEXT_SCALE, TEXT_SCALE_DEFAULT)
            .putFloat(KEY_ORBITAL_SPHERES_SCALE, ORBITAL_SPHERES_SCALE_DEFAULT)
            .putFloat(KEY_WALLPAPER_PARTICLES_SIZE, WALLPAPER_PARTICLES_SIZE_DEFAULT)
            .apply()
    }

    // ── Parallax Depth Slider ────────────────────────────────────────────────

    fun getWallpaperParallaxDepth(): Float = _wallpaperParallaxDepthFlow.value
    fun getParallaxDepth(): Float = getWallpaperParallaxDepth()

    fun setWallpaperParallaxDepth(depth: Float, persist: Boolean = true) {
        val clamped = depth.coerceIn(WALLPAPER_PARALLAX_DEPTH_MIN, WALLPAPER_PARALLAX_DEPTH_MAX)
        _wallpaperParallaxDepthFlow.value = clamped
        if (persist) prefs.edit().putFloat(KEY_WALLPAPER_PARALLAX_DEPTH, clamped).apply()
    }
    fun setParallaxDepth(depth: Float, persist: Boolean = true) = setWallpaperParallaxDepth(depth, persist)

    // ── AGSL Procedural Shaders (Android 13+) ────────────────────────────────

    fun getWallpaperShaderPreset(): AgslShaderPreset = _wallpaperShaderPresetFlow.value

    fun setWallpaperShaderPreset(preset: AgslShaderPreset) {
        _wallpaperShaderPresetFlow.value = preset
        prefs.edit().putString(KEY_WALLPAPER_SHADER_PRESET, preset.id).apply()
    }

    fun activateShaderWallpaper(preset: AgslShaderPreset) {
        val revision = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_WALLPAPER_ENABLED, true)
            .putLong(KEY_WALLPAPER_REVISION, revision)
            .putString(KEY_WALLPAPER_TYPE, WALLPAPER_TYPE_SHADER)
            .putString(KEY_WALLPAPER_SHADER_PRESET, preset.id)
            .remove(KEY_WALLPAPER_ACTIVE_PRESET_ID)
            .apply()
        _customBackgroundActivePresetIdFlow.value = null
        _customBackgroundRevisionFlow.value = revision
        _customBackgroundTypeFlow.value = WALLPAPER_TYPE_SHADER
        _wallpaperShaderPresetFlow.value = preset
        _customBackgroundVideoMetadataFlow.value = null
        _customBackgroundUriFlow.value = "shader://${preset.id}"
    }

    fun getWallpaperShaderSpeed(): Float = _wallpaperShaderSpeedFlow.value

    fun setWallpaperShaderSpeed(speed: Float, persist: Boolean = true) {
        val clamped = speed.coerceIn(WALLPAPER_SHADER_SPEED_MIN, WALLPAPER_SHADER_SPEED_MAX)
        _wallpaperShaderSpeedFlow.value = clamped
        if (persist) {
            prefs.edit().putFloat(KEY_WALLPAPER_SHADER_SPEED, clamped).apply()
        }
    }

    // ── Card Corner Geometry & Cyber Chisel ──────────────────────────────────

    fun getCardCornerStyle(): CardCornerStyle = _cardCornerStyleFlow.value

    fun setCardCornerStyle(style: CardCornerStyle) {
        _cardCornerStyleFlow.value = style
        prefs.edit().putString(KEY_CARD_CORNER_STYLE, style.id).apply()
    }

    fun getCardCornerRadiusDp(): Float = _cardCornerRadiusDpFlow.value

    fun setCardCornerRadiusDp(radius: Float, persist: Boolean = true) {
        val clamped = radius.coerceIn(CARD_CORNER_RADIUS_DP_MIN, CARD_CORNER_RADIUS_DP_MAX)
        _cardCornerRadiusDpFlow.value = clamped
        if (persist) prefs.edit().putFloat(KEY_CARD_CORNER_RADIUS_DP, clamped).apply()
    }

    // ── Haptic Profiles ──────────────────────────────────────────────────────

    fun getHapticProfile(): HapticProfile = _hapticProfileFlow.value

    fun setHapticProfile(profile: HapticProfile) {
        _hapticProfileFlow.value = profile
        prefs.edit().putString(KEY_HAPTIC_PROFILE, profile.id).apply()
    }

    fun isVibrationDisabled(): Boolean = _vibrationDisabledFlow.value

    fun setVibrationDisabled(disabled: Boolean) {
        _vibrationDisabledFlow.value = disabled
        prefs.edit().putBoolean(KEY_VIBRATION_DISABLED, disabled).apply()
    }

    // ── Wallpaper Presets Engine ─────────────────────────────────────────────

    fun ensureDefaultWallpaperInitialized() {
        val alreadyMigrated = prefs.getBoolean(KEY_WALLPAPER_BLACK_DEFAULT_MIGRATION, false)
        val wallpaperEnabled = prefs.getBoolean(KEY_WALLPAPER_ENABLED, false)
        val wallpaperType = prefs.getString(KEY_WALLPAPER_TYPE, null)
        val activePresetId = prefs.getString(KEY_WALLPAPER_ACTIVE_PRESET_ID, null)
        val activeMediaFile = when (wallpaperType) {
            WALLPAPER_TYPE_VIDEO -> wallpaperVideoFile()
            WALLPAPER_TYPE_IMAGE -> wallpaperFile()
            else -> null
        }
        val hasValidMediaFile = activeMediaFile != null && activeMediaFile.isFile && activeMediaFile.length() > 0L
        val fileLength = if (hasValidMediaFile) activeMediaFile!!.length() else 0L
        val matchesLegacyBuiltinSize = hasValidMediaFile &&
            WallpaperDefaultMigrationPlanner.isLegacyBuiltinAssetSize(wallpaperType, fileLength)

        val currentUserPresets = getUserPresets()
        val matchingExistingPreset = if (activePresetId != null) {
            currentUserPresets.firstOrNull { preset ->
                preset.id == activePresetId &&
                    preset.filePath?.let { path -> File(path).let { it.isFile && it.length() > 0L } } == true
            }
        } else {
            null
        }

        val plan = WallpaperDefaultMigrationPlanner.plan(
            WallpaperMigrationInput(
                alreadyMigrated = alreadyMigrated,
                wallpaperEnabled = wallpaperEnabled,
                wallpaperType = wallpaperType,
                activePresetId = activePresetId,
                hasValidMediaFile = hasValidMediaFile,
                matchesBuiltinAssetSize = matchesLegacyBuiltinSize,
                matchingExistingUserPresetId = matchingExistingPreset?.id
            )
        )

        when (plan.action) {
            WallpaperMigrationAction.NOOP_ALREADY_MIGRATED -> return
            WallpaperMigrationAction.RESET_SILENT_TO_BLACK -> {
                clearCustomWallpaper()
                prefs.edit()
                    .putBoolean(KEY_WALLPAPER_BLACK_DEFAULT_MIGRATION, true)
                    .remove(KEY_PENDING_CUSTOM_WALLPAPER_NOTICE_PRESET_ID)
                    .apply()
                _pendingMigratedWallpaperPresetFlow.value = null
            }
            WallpaperMigrationAction.PRESERVE_CUSTOM_AND_RESET_TO_BLACK -> {
                val preservedPresetId = plan.reuseExistingPresetId ?: saveActiveCustomWallpaperAsMigratedPreset(
                    wallpaperType = wallpaperType!!,
                    srcFile = activeMediaFile!!,
                    existingPresets = currentUserPresets
                )
                clearCustomWallpaper()
                if (preservedPresetId != null) {
                    prefs.edit()
                        .putBoolean(KEY_WALLPAPER_BLACK_DEFAULT_MIGRATION, true)
                        .putString(KEY_PENDING_CUSTOM_WALLPAPER_NOTICE_PRESET_ID, preservedPresetId)
                        .apply()
                    _pendingMigratedWallpaperPresetFlow.value = resolvePendingMigratedWallpaperPreset()
                    AppLogger.i("Wallpaper", "Migrated active wallpaper to black AMOLED; custom background saved as preset $preservedPresetId")
                } else {
                    prefs.edit()
                        .putBoolean(KEY_WALLPAPER_BLACK_DEFAULT_MIGRATION, true)
                        .remove(KEY_PENDING_CUSTOM_WALLPAPER_NOTICE_PRESET_ID)
                        .apply()
                    _pendingMigratedWallpaperPresetFlow.value = null
                }
            }
        }
    }

    private fun saveActiveCustomWallpaperAsMigratedPreset(
        wallpaperType: String,
        srcFile: File,
        existingPresets: List<WallpaperPreset>
    ): String? {
        return try {
            val isVideo = wallpaperType == WALLPAPER_TYPE_VIDEO
            val presetsDir = context.filesDir.resolve("wallpaper_presets").apply { mkdirs() }
            val presetId = "custom_migrated_${System.currentTimeMillis()}"
            val ext = if (isVideo) "mp4" else "jpg"
            val savedFile = presetsDir.resolve("$presetId.$ext")
            if (!srcFile.renameTo(savedFile)) {
                srcFile.copyTo(savedFile, overwrite = true)
            }

            var savedPreviewFile: File? = null
            if (isVideo) {
                val previewSrc = wallpaperVideoPreviewFile()
                if (previewSrc.isFile && previewSrc.length() > 0L) {
                    val pf = presetsDir.resolve("${presetId}_preview.jpg")
                    if (!previewSrc.renameTo(pf)) {
                        previewSrc.copyTo(pf, overwrite = true)
                    }
                    savedPreviewFile = pf
                }
            }

            val localizedTitle = runCatching {
                com.mirrly.tgproxy.util.LocaleHelper.wrapContext(context, getAppLanguage())
                    .getString(R.string.wallpaper_migrated_preset_name)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Личный фон"

            val preset = WallpaperPreset(
                id = presetId,
                title = localizedTitle,
                type = wallpaperType,
                filePath = savedFile.absolutePath,
                previewFilePath = savedPreviewFile?.absolutePath ?: savedFile.absolutePath,
                blurDp = _customBackgroundBlurFlow.value,
                dimFactor = _customBackgroundDimFlow.value,
                isBuiltIn = false,
                createdAt = System.currentTimeMillis()
            )

            val updatedList = existingPresets.toMutableList().apply { add(0, preset) }
            saveUserPresetsList(updatedList)
            _customBackgroundUserPresetsFlow.value = updatedList
            presetId
        } catch (e: Throwable) {
            AppLogger.e("Wallpaper", "Failed to auto-save custom wallpaper during migration: ${e.message}", e)
            null
        }
    }

    private fun resolvePendingMigratedWallpaperPreset(): WallpaperPreset? {
        val pendingId = prefs.getString(KEY_PENDING_CUSTOM_WALLPAPER_NOTICE_PRESET_ID, null) ?: return null
        val preset = getUserPresets().firstOrNull { it.id == pendingId }
        if (preset == null) {
            prefs.edit().remove(KEY_PENDING_CUSTOM_WALLPAPER_NOTICE_PRESET_ID).apply()
            return null
        }
        val fileExists = preset.filePath?.let { File(it).let { f -> f.isFile && f.length() > 0L } } == true
        if (!fileExists) {
            prefs.edit().remove(KEY_PENDING_CUSTOM_WALLPAPER_NOTICE_PRESET_ID).apply()
            return null
        }
        return preset
    }

    fun dismissPendingMigratedWallpaperNotice() {
        prefs.edit().remove(KEY_PENDING_CUSTOM_WALLPAPER_NOTICE_PRESET_ID).apply()
        _pendingMigratedWallpaperPresetFlow.value = null
    }

    suspend fun applyPreset(preset: WallpaperPreset): Result<Unit> = withContext(Dispatchers.IO) {
        wallpaperMutex.lock()
        try {
            if (preset.id == WallpaperPreset.PRESET_ID_DEFAULT_AMOLED) {
                clearCustomWallpaper()
                prefs.edit().putString(KEY_WALLPAPER_ACTIVE_PRESET_ID, WallpaperPreset.PRESET_ID_DEFAULT_AMOLED).apply()
                _customBackgroundActivePresetIdFlow.value = WallpaperPreset.PRESET_ID_DEFAULT_AMOLED
                return@withContext Result.success(Unit)
            }
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

    // ── Theme Accent Management ──

    fun getThemeAccentMode(): ThemeAccentMode {
        val raw = prefs.getString(KEY_THEME_ACCENT_MODE, ThemeAccentMode.PROTOCOL_DEFAULT.storageKey)
        return ThemeAccentMode.fromStorageKey(raw)
    }

    fun setThemeAccentMode(mode: ThemeAccentMode) {
        prefs.edit().putString(KEY_THEME_ACCENT_MODE, mode.storageKey).apply()
        _themeAccentModeFlow.value = mode
        _isCustomThemeEnabledFlow.value = (mode != ThemeAccentMode.PROTOCOL_DEFAULT)
    }

    fun getThemePresetId(): String {
        return prefs.getString(KEY_THEME_PRESET_ID, "CYBER_RUBY") ?: "CYBER_RUBY"
    }

    fun setThemePresetId(presetId: String) {
        prefs.edit().putString(KEY_THEME_PRESET_ID, presetId).apply()
        _themePresetIdFlow.value = presetId
    }

    fun getThemeCustomColorHex(): String {
        return prefs.getString(KEY_THEME_CUSTOM_COLOR_HEX, "#00F5D4") ?: "#00F5D4"
    }

    fun setThemeCustomColorHex(hex: String) {
        val clean = if (hex.startsWith("#")) hex.uppercase() else "#${hex.uppercase()}"
        prefs.edit().putString(KEY_THEME_CUSTOM_COLOR_HEX, clean).apply()
        _themeCustomColorHexFlow.value = clean
    }

    fun getThemeBrightness(): Float {
        return prefs.getFloat(KEY_THEME_BRIGHTNESS, 1.0f).coerceIn(0.2f, 1.5f)
    }

    fun setThemeBrightness(value: Float) {
        val clamped = value.coerceIn(0.2f, 1.5f)
        prefs.edit().putFloat(KEY_THEME_BRIGHTNESS, clamped).apply()
        _themeBrightnessFlow.value = clamped
    }

    fun getThemeSaturation(): Float {
        return prefs.getFloat(KEY_THEME_SATURATION, 1.0f).coerceIn(0.0f, 1.5f)
    }

    fun setThemeSaturation(value: Float) {
        val clamped = value.coerceIn(0.0f, 1.5f)
        prefs.edit().putFloat(KEY_THEME_SATURATION, clamped).apply()
        _themeSaturationFlow.value = clamped
    }

    fun getThemeAlpha(): Float {
        return prefs.getFloat(KEY_THEME_ALPHA, 1.0f).coerceIn(0.2f, 1.0f)
    }

    fun setThemeAlpha(value: Float) {
        val clamped = value.coerceIn(0.2f, 1.0f)
        prefs.edit().putFloat(KEY_THEME_ALPHA, clamped).apply()
        _themeAlphaFlow.value = clamped
    }

    fun resetThemeAdjustments() {
        prefs.edit()
            .putFloat(KEY_THEME_BRIGHTNESS, 1.0f)
            .putFloat(KEY_THEME_SATURATION, 1.0f)
            .putFloat(KEY_THEME_ALPHA, 1.0f)
            .apply()
        _themeBrightnessFlow.value = 1.0f
        _themeSaturationFlow.value = 1.0f
        _themeAlphaFlow.value = 1.0f
    }

    // ── App Launcher Icon Customization ──

    fun getAppIconId(): String {
        val raw = prefs.getString(KEY_APP_ICON_ID, AppIconPreset.DEFAULT.id)
        return AppIconPreset.fromStorageKey(raw).id
    }

    fun setAppIconId(iconId: String) {
        val normalized = AppIconPreset.fromStorageKey(iconId).id
        // Flush synchronously so backup/startup state stays intact if launcher restarts the task.
        prefs.edit().putString(KEY_APP_ICON_ID, normalized).commit()
        _appIconIdFlow.value = normalized
    }

    // ── Central Buttons Icon Customization ──

    fun getCentralButtonIcon(protocol: String): String {
        return when (protocol.lowercase()) {
            "mtproto" -> prefs.getString(KEY_CENTRAL_BUTTON_ICON_MTPROTO, "default") ?: "default"
            "socks5" -> prefs.getString(KEY_CENTRAL_BUTTON_ICON_SOCKS5, "default") ?: "default"
            "vpn" -> prefs.getString(KEY_CENTRAL_BUTTON_ICON_VPN, "default") ?: "default"
            else -> "default"
        }
    }

    fun setCentralButtonIcon(protocol: String, iconId: String) {
        val key = when (protocol.lowercase()) {
            "mtproto" -> KEY_CENTRAL_BUTTON_ICON_MTPROTO
            "socks5" -> KEY_CENTRAL_BUTTON_ICON_SOCKS5
            "vpn" -> KEY_CENTRAL_BUTTON_ICON_VPN
            else -> return
        }
        prefs.edit().putString(key, iconId).apply()
        when (protocol.lowercase()) {
            "mtproto" -> _centralButtonIconMtprotoFlow.value = iconId
            "socks5" -> _centralButtonIconSocks5Flow.value = iconId
            "vpn" -> _centralButtonIconVpnFlow.value = iconId
        }
    }

    fun getCustomIconCode(protocol: String): String {
        val key = when (protocol.lowercase()) {
            "mtproto" -> KEY_CUSTOM_ICON_CODE_MTPROTO
            "socks5" -> KEY_CUSTOM_ICON_CODE_SOCKS5
            "vpn" -> KEY_CUSTOM_ICON_CODE_VPN
            else -> return ""
        }
        return prefs.getString(key, "") ?: ""
    }

    fun setCustomIconCode(protocol: String, code: String) {
        val key = when (protocol.lowercase()) {
            "mtproto" -> KEY_CUSTOM_ICON_CODE_MTPROTO
            "socks5" -> KEY_CUSTOM_ICON_CODE_SOCKS5
            "vpn" -> KEY_CUSTOM_ICON_CODE_VPN
            else -> return
        }
        prefs.edit().putString(key, code).apply()
        when (protocol.lowercase()) {
            "mtproto" -> _customIconCodeMtprotoFlow.value = code
            "socks5" -> _customIconCodeSocks5Flow.value = code
            "vpn" -> _customIconCodeVpnFlow.value = code
        }
    }

    fun getAiChatDraft(): String {
        return prefs.getString(KEY_AI_CHAT_DRAFT, "") ?: ""
    }

    fun setAiChatDraft(text: String) {
        prefs.edit().putString(KEY_AI_CHAT_DRAFT, text).apply()
        _aiChatDraftFlow.value = text
    }

    fun getCentralButtonScale(): Float {
        return prefs.getFloat(KEY_CENTRAL_BUTTON_SCALE, CENTRAL_BUTTON_SCALE_DEFAULT).coerceIn(0.70f, 1.30f)
    }

    fun setCentralButtonScale(scale: Float, persist: Boolean = true) {
        val clamped = scale.coerceIn(0.70f, 1.30f)
        _centralButtonScaleFlow.value = clamped
        if (persist) {
            prefs.edit().putFloat(KEY_CENTRAL_BUTTON_SCALE, clamped).apply()
        }
    }

    fun getCentralButtonStrokeWidth(): Float {
        return prefs.getFloat(KEY_CENTRAL_BUTTON_STROKE_WIDTH, CENTRAL_BUTTON_STROKE_WIDTH_DEFAULT).coerceIn(1.0f, 3.0f)
    }

    fun setCentralButtonStrokeWidth(stroke: Float, persist: Boolean = true) {
        val clamped = stroke.coerceIn(1.0f, 3.0f)
        _centralButtonStrokeWidthFlow.value = clamped
        if (persist) {
            prefs.edit().putFloat(KEY_CENTRAL_BUTTON_STROKE_WIDTH, clamped).apply()
        }
    }

    fun getCentralButtonGlowIntensity(): Float {
        return prefs.getFloat(KEY_CENTRAL_BUTTON_GLOW_INTENSITY, CENTRAL_BUTTON_GLOW_INTENSITY_DEFAULT).coerceIn(0.0f, 2.0f)
    }

    fun setCentralButtonGlowIntensity(glow: Float, persist: Boolean = true) {
        val clamped = glow.coerceIn(0.0f, 2.0f)
        _centralButtonGlowIntensityFlow.value = clamped
        if (persist) {
            prefs.edit().putFloat(KEY_CENTRAL_BUTTON_GLOW_INTENSITY, clamped).apply()
        }
    }

    // ── Worker Profiles Management ──────────────────────────────────────────

    companion object {
        const val KEY_THEME_ACCENT_MODE = "theme_accent_mode"
        const val KEY_THEME_PRESET_ID = "theme_preset_id"
        const val KEY_THEME_CUSTOM_COLOR_HEX = "theme_custom_color_hex"
        const val KEY_THEME_BRIGHTNESS = "theme_brightness"
        const val KEY_THEME_SATURATION = "theme_saturation"
        const val KEY_THEME_ALPHA = "theme_alpha"
        const val KEY_APP_ICON_ID = "selected_app_icon_id"

        const val KEY_CENTRAL_BUTTON_ICON_MTPROTO = "central_button_icon_mtproto"
        const val KEY_CENTRAL_BUTTON_ICON_SOCKS5 = "central_button_icon_socks5"
        const val KEY_CENTRAL_BUTTON_ICON_VPN = "central_button_icon_vpn"
        const val KEY_CUSTOM_ICON_CODE_MTPROTO = "custom_icon_code_mtproto"
        const val KEY_CUSTOM_ICON_CODE_SOCKS5 = "custom_icon_code_socks5"
        const val KEY_CUSTOM_ICON_CODE_VPN = "custom_icon_code_vpn"
        const val KEY_CENTRAL_BUTTON_SCALE = "central_button_scale"
        const val KEY_CENTRAL_BUTTON_STROKE_WIDTH = "central_button_stroke_width"
        const val KEY_CENTRAL_BUTTON_GLOW_INTENSITY = "central_button_glow_intensity"
        const val CENTRAL_BUTTON_SCALE_DEFAULT = 1.0f
        const val CENTRAL_BUTTON_STROKE_WIDTH_DEFAULT = 1.5f
        const val CENTRAL_BUTTON_GLOW_INTENSITY_DEFAULT = 1.0f
        const val KEY_AI_CHAT_DRAFT = "ai_chat_draft"

        const val KEY_TLS_ENABLED = "tls_enabled"
        const val KEY_VPN_TLS_ENABLED = "vpn_tls_enabled"
        private const val KEY_TLS_USER_OVERRIDE = "tls_user_override"
        private const val KEY_TLS_FA_DEFAULT_MIGRATED = "tls_fa_default_enabled_migrated_v1"
        const val KEY_ECH_ENABLED = "ech_enabled"
        const val KEY_WS_RANDOMIZATION_ENABLED = "ws_randomization_enabled"
        const val KEY_TLS_RECORD_PADDING_ENABLED = "tls_record_padding_enabled"
        const val KEY_TLS_CLIENT_HELLO_SPLIT_ENABLED = "tls_client_hello_split_enabled"
        const val KEY_ACTIVE_WORKER_ID_SOCKS5 = "active_worker_id_socks5"
        const val KEY_ACTIVE_WORKER_ID_MTPROTO = "active_worker_id_mtproto"
        const val KEY_USER_PRIMARY_WORKER_ID_SOCKS5 = "user_primary_worker_id_socks5"
        const val KEY_USER_PRIMARY_WORKER_ID_MTPROTO = "user_primary_worker_id_mtproto"
        const val KEY_SOCKS5_UPLINK_MODE = "socks5_uplink_mode"
        private const val KEY_SOCKS5_UPLINK_FLOWSEAL_MIGRATED_V2 = "socks5_uplink_default_flowseal_migrated_v2"
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
        const val WALLPAPER_TYPE_SHADER = "shader"
        const val KEY_WALLPAPER_SHADER_PRESET = "custom_wallpaper_shader_preset"
        const val KEY_WALLPAPER_SHADER_SPEED = "custom_wallpaper_shader_speed"
        const val WALLPAPER_SHADER_SPEED_MIN = 0.20f
        const val WALLPAPER_SHADER_SPEED_MAX = 2.50f
        const val WALLPAPER_SHADER_SPEED_DEFAULT = 1.00f
        const val KEY_WALLPAPER_ACTIVE_PRESET_ID = "custom_wallpaper_active_preset_id"
        const val KEY_WALLPAPER_USER_PRESETS = "custom_wallpaper_user_presets"
        private const val KEY_WALLPAPER_BLACK_DEFAULT_MIGRATION = "wallpaper_black_default_migration_v5"
        private const val KEY_PENDING_CUSTOM_WALLPAPER_NOTICE_PRESET_ID = "wallpaper_pending_migration_notice_preset_id"
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

        // Particle Dynamics & Parallax Engine Keys
        const val KEY_WALLPAPER_PARTICLES_STYLE = "wallpaper_particles_style"
        const val KEY_WALLPAPER_PARTICLES_COUNT = "wallpaper_particles_count"
        const val KEY_WALLPAPER_PARTICLES_SPEED = "wallpaper_particles_speed"
        const val KEY_WALLPAPER_PARALLAX_ENABLED = "wallpaper_parallax_enabled"
        const val KEY_WALLPAPER_PARALLAX_DEPTH = "wallpaper_parallax_depth"
        const val WALLPAPER_PARALLAX_DEPTH_MIN = 0.20f
        const val WALLPAPER_PARALLAX_DEPTH_MAX = 2.50f
        const val WALLPAPER_PARALLAX_DEPTH_DEFAULT = 1.00f
        const val KEY_HOME_RINGS_ENABLED = "home_rings_enabled"
        const val KEY_LIQUID_GLASS_ENABLED = "liquid_glass_enabled"

        // Card Corner Geometry Keys
        const val KEY_CARD_CORNER_STYLE = "card_corner_style"
        const val KEY_CARD_CORNER_RADIUS_DP = "card_corner_radius_dp"
        const val CARD_CORNER_RADIUS_DP_MIN = 8.0f
        const val CARD_CORNER_RADIUS_DP_MAX = 24.0f
        const val CARD_CORNER_RADIUS_DP_DEFAULT = 16.0f

        // Haptic Profile Keys
        const val KEY_HAPTIC_PROFILE = "haptic_profile"
        const val KEY_VIBRATION_DISABLED = "disable_all_vibration"

        // UI Scaling & Dimensions Keys
        const val KEY_UI_SCALE = "ui_scale"
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_ORBITAL_SPHERES_SCALE = "orbital_spheres_scale"
        const val KEY_WALLPAPER_PARTICLES_SIZE = "wallpaper_particles_size"

        const val UI_SCALE_MIN = 0.80f
        const val UI_SCALE_MAX = 1.25f
        const val UI_SCALE_DEFAULT = 1.00f

        const val TEXT_SCALE_MIN = 0.80f
        const val TEXT_SCALE_MAX = 1.30f
        const val TEXT_SCALE_DEFAULT = 1.00f

        const val ORBITAL_SPHERES_SCALE_MIN = 0.70f
        const val ORBITAL_SPHERES_SCALE_MAX = 1.30f
        const val ORBITAL_SPHERES_SCALE_DEFAULT = 1.00f

        const val WALLPAPER_PARTICLES_SIZE_MIN = 0.50f
        const val WALLPAPER_PARTICLES_SIZE_MAX = 2.50f
        const val WALLPAPER_PARTICLES_SIZE_DEFAULT = 1.00f

        const val WALLPAPER_PARTICLES_COUNT_MIN = 8
        const val WALLPAPER_PARTICLES_COUNT_MAX = 100
        const val WALLPAPER_PARTICLES_COUNT_DEFAULT = 20

        const val WALLPAPER_PARTICLES_SPEED_MIN = 0.5f
        const val WALLPAPER_PARTICLES_SPEED_MAX = 2.0f
        const val WALLPAPER_PARTICLES_SPEED_DEFAULT = 1.0f

        /**
         * Создает диагностический отчет для службы поддержки, строго исключая
         * приватные ключи, токены, пароли и полные чувствительные URL (Task N20).
         */
        fun getRedactedDiagnosticReport(config: ProxyConfig): String {
            val report = JSONObject()
            report.put("schema_version", 2)
            report.put("timestamp_utc_ms", System.currentTimeMillis())
            report.put("tls_enabled", config.isTlsEnabled)
            report.put("vpn_tls_enabled", config.isVpnTlsEnabled)
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

        internal data class TlsStartupState(
            val isTlsEnabled: Boolean,
            val shouldPersistTls: Boolean,
            val markUserOverride: Boolean,
            val markV1Migrated: Boolean,
            val markFaDefaultMigrated: Boolean
        )

        internal data class TlsLanguageTransition(
            val shouldUpdateTls: Boolean,
            val newTlsEnabled: Boolean,
            val clearUserOverride: Boolean
        )

        /**
         * Resolves initial `isTlsEnabled` across fresh installs, legacy migrations, and explicit user overrides.
         * Persian (`fa`) defaults to TLS 1.2/1.3 enabled; other languages default to disabled.
         */
        internal fun resolveTlsStartupState(
            langCode: String,
            systemLanguage: String,
            hasStoredTlsValue: Boolean,
            storedTlsValue: Boolean,
            v1MigrationDone: Boolean,
            faMigrationDone: Boolean,
            hasUserOverride: Boolean
        ): TlsStartupState {
            val defaultForLang = com.mirrly.tgproxy.util.LocaleHelper.isPersianLanguage(
                langCode = langCode,
                systemLanguage = systemLanguage
            )
            if (!v1MigrationDone) {
                return TlsStartupState(
                    isTlsEnabled = defaultForLang,
                    shouldPersistTls = true,
                    markUserOverride = false,
                    markV1Migrated = true,
                    markFaDefaultMigrated = true
                )
            }
            if (!faMigrationDone) {
                if (defaultForLang) {
                    val enableForFa = if (!hasUserOverride && (!hasStoredTlsValue || !storedTlsValue)) true else storedTlsValue
                    return TlsStartupState(
                        isTlsEnabled = enableForFa,
                        shouldPersistTls = !hasStoredTlsValue || enableForFa != storedTlsValue,
                        markUserOverride = false,
                        markV1Migrated = false,
                        markFaDefaultMigrated = true
                    )
                }
                val preserveManualEnable = !hasUserOverride && hasStoredTlsValue && storedTlsValue
                return TlsStartupState(
                    isTlsEnabled = if (hasStoredTlsValue) storedTlsValue else false,
                    shouldPersistTls = false,
                    markUserOverride = preserveManualEnable,
                    markV1Migrated = false,
                    markFaDefaultMigrated = true
                )
            }
            return TlsStartupState(
                isTlsEnabled = if (hasStoredTlsValue) storedTlsValue else defaultForLang,
                shouldPersistTls = false,
                markUserOverride = false,
                markV1Migrated = false,
                markFaDefaultMigrated = false
            )
        }

        /**
         * Computes whether switching app language should auto-update TLS 1.2/1.3 without clobbering
         * an explicit manual override within the active language family.
         */
        internal fun resolveTlsOnLanguageChange(
            previousLangCode: String,
            newLangCode: String,
            systemLanguage: String,
            currentTlsEnabled: Boolean,
            hasUserOverride: Boolean
        ): TlsLanguageTransition {
            val wasPersian = com.mirrly.tgproxy.util.LocaleHelper.isPersianLanguage(
                langCode = previousLangCode,
                systemLanguage = systemLanguage
            )
            val isNowPersian = com.mirrly.tgproxy.util.LocaleHelper.isPersianLanguage(
                langCode = newLangCode,
                systemLanguage = systemLanguage
            )
            if (wasPersian == isNowPersian) {
                return TlsLanguageTransition(
                    shouldUpdateTls = false,
                    newTlsEnabled = currentTlsEnabled,
                    clearUserOverride = false
                )
            }
            if (isNowPersian) {
                return TlsLanguageTransition(
                    shouldUpdateTls = !currentTlsEnabled,
                    newTlsEnabled = true,
                    clearUserOverride = true
                )
            }
            return if (!hasUserOverride) {
                TlsLanguageTransition(
                    shouldUpdateTls = currentTlsEnabled,
                    newTlsEnabled = false,
                    clearUserOverride = false
                )
            } else {
                TlsLanguageTransition(
                    shouldUpdateTls = false,
                    newTlsEnabled = currentTlsEnabled,
                    clearUserOverride = false
                )
            }
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
        val prevActiveMtprotoId = prefs.getString(KEY_ACTIVE_WORKER_ID_MTPROTO, null)
            ?: prefs.getString("active_worker_id", "") ?: ""
        val prevActiveSocks5Id = prefs.getString(KEY_ACTIVE_WORKER_ID_SOCKS5, null)
            ?: prefs.getString("active_worker_id", "") ?: ""
        val prevPrimaryMtprotoId = getUserPrimaryWorkerId(ProxyMode.MTPROTO)
        val prevPrimarySocks5Id = getUserPrimaryWorkerId(ProxyMode.SOCKS5)

        val current = getCustomWorkers().filter { it.id != id }
        saveCustomWorkers(current)
        val nextWorker = current.firstOrNull()
        val hasValidRemainingWorker = current.any { ProxyConfig.sanitizeDomain(it.domain).isNotBlank() }

        if (prevActiveMtprotoId == id) {
            setActiveWorkerId(nextWorker?.id ?: "", mode = ProxyMode.MTPROTO, fromUserAction = false)
        }
        if (prevActiveSocks5Id == id) {
            setActiveWorkerId(nextWorker?.id ?: "", mode = ProxyMode.SOCKS5, fromUserAction = false)
        }
        if (prevPrimaryMtprotoId == id) {
            setUserPrimaryWorkerId(nextWorker?.id ?: "", mode = ProxyMode.MTPROTO)
        }
        if (prevPrimarySocks5Id == id) {
            setUserPrimaryWorkerId(nextWorker?.id ?: "", mode = ProxyMode.SOCKS5)
        }
        if (!hasValidRemainingWorker) {
            setSocks5UplinkMode(ProxyConfig.SOCKS5_UPLINK_FLOWSEAL)
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
        if (fromUserAction && mode == ProxyMode.SOCKS5 && worker.domain.isNotBlank()) {
            setSocks5UplinkMode(0)
            try {
                NotificationHelper.cancelSocks5FlowsealFallbackNotification(context)
            } catch (_: Throwable) {}
        }

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

    fun isWorkerSwipeEnabled(): Boolean = prefs.getBoolean(
        "worker_swipe_enabled",
        !prefs.getBoolean("worker_swipe_blocked", true)
    )

    fun setWorkerSwipeEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("worker_swipe_enabled", enabled)
            .putBoolean("worker_swipe_blocked", !enabled)
            .apply()
        _isWorkerSwipeEnabledFlow.value = enabled
        _isWorkerSwipeBlockedFlow.value = !enabled
    }

    fun isWorkerSwipeBlocked(): Boolean = !isWorkerSwipeEnabled()

    fun setWorkerSwipeBlocked(blocked: Boolean) {
        setWorkerSwipeEnabled(!blocked)
    }

    fun isHomeModeSwipeEnabled(): Boolean = prefs.getBoolean("home_mode_swipe_enabled", true)

    fun setHomeModeSwipeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("home_mode_swipe_enabled", enabled).apply()
        _isHomeModeSwipeEnabledFlow.value = enabled
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

    fun hasConfirmedOemAutostart(): Boolean {
        return prefs.getBoolean("has_confirmed_oem_autostart", false)
    }

    fun setHasConfirmedOemAutostart(confirmed: Boolean = true) {
        prefs.edit().putBoolean("has_confirmed_oem_autostart", confirmed).apply()
    }

    fun hasCompletedBackgroundOnboarding(): Boolean {
        return prefs.getBoolean("has_completed_background_onboarding_v2", false)
    }

    fun setHasCompletedBackgroundOnboarding(completed: Boolean = true) {
        prefs.edit().putBoolean("has_completed_background_onboarding_v2", completed).apply()
    }

    fun hasSeenVpnOnboarding(): Boolean {
        return prefs.getBoolean("has_seen_vpn_onboarding", false)
    }

    fun setHasSeenVpnOnboarding(seen: Boolean = true) {
        prefs.edit().putBoolean("has_seen_vpn_onboarding", seen).apply()
    }

    fun hasSeenAiOnboarding(): Boolean {
        return prefs.getBoolean("has_seen_ai_onboarding", false)
    }

    fun setHasSeenAiOnboarding(seen: Boolean = true) {
        prefs.edit().putBoolean("has_seen_ai_onboarding", seen).apply()
    }

    fun getAppLanguage(): String {
        return prefs.getString("app_language", "system") ?: "system"
    }

    fun setAppLanguage(langCode: String) {
        val previousLang = getAppLanguage()
        val systemLanguage = com.mirrly.tgproxy.util.LocaleHelper.getSystemLocale().language
        val transition = resolveTlsOnLanguageChange(
            previousLangCode = previousLang,
            newLangCode = langCode,
            systemLanguage = systemLanguage,
            currentTlsEnabled = isTlsEnabled(),
            hasUserOverride = prefs.getBoolean(KEY_TLS_USER_OVERRIDE, false)
        )
        prefs.edit().apply {
            putString("app_language", langCode)
            putBoolean(KEY_TLS_FA_DEFAULT_MIGRATED, true)
            if (transition.clearUserOverride) {
                remove(KEY_TLS_USER_OVERRIDE)
            }
            apply()
        }
        _appLanguageFlow.value = langCode
        // MainActivity observes appLanguageFlow and supplies a localized Compose context.
        // Mutating Resources here during a click can invalidate the active composition and
        // leave the first-run onboarding controls unresponsive on Android 14.
        java.util.Locale.setDefault(com.mirrly.tgproxy.util.LocaleHelper.getTargetLocale(langCode))
        if (transition.shouldUpdateTls) {
            setTlsEnabled(transition.newTlsEnabled, isUserOverride = false)
        }
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
        prefs.edit().remove(KEY_TLS_USER_OVERRIDE).apply()
        setTlsEnabled(
            com.mirrly.tgproxy.util.LocaleHelper.isPersianLanguage(getAppLanguage()),
            isUserOverride = false
        )
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
        updateCloudflareSessionFlows()
    }

    fun setCloudflareToken(token: String) {
        if (token.isBlank()) {
            secretPrefs.edit().remove("cf_access_token").apply()
        } else {
            secretPrefs.edit().putString("cf_access_token", WarpKeystoreEncryption.encrypt(token)).apply()
        }
        updateCloudflareSessionFlows()
    }

    fun setCloudflareAccountId(accountId: String) {
        if (accountId.isBlank()) {
            prefs.edit().remove("cf_account_id").apply()
        } else {
            prefs.edit().putString("cf_account_id", accountId).apply()
        }
        updateCloudflareSessionFlows()
    }

    fun setCloudflareAuthorized(authorized: Boolean) {
        if (!authorized) {
            clearCloudflareSession()
        }
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

        updateCloudflareSessionFlows()
    }

    fun updateCloudflareAccessToken(accessToken: String, refreshToken: String? = null) {
        val editor = secretPrefs.edit().putString("cf_access_token", WarpKeystoreEncryption.encrypt(accessToken))
        if (!refreshToken.isNullOrBlank()) {
            editor.putString("cf_refresh_token", WarpKeystoreEncryption.encrypt(refreshToken))
        }
        editor.apply()
        updateCloudflareSessionFlows()
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

        updateCloudflareSessionFlows()

        if (getCustomWorkers().none { ProxyConfig.sanitizeDomain(it.domain).isNotBlank() }) {
            setSocks5UplinkMode(ProxyConfig.SOCKS5_UPLINK_FLOWSEAL)
        }
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

    fun getSelectedBuiltinServerId(): String =
        prefs.getString("vpn_selected_builtin_server_id", "builtin_tel_fi") ?: "builtin_tel_fi"

    fun setSelectedBuiltinServerId(id: String) {
        prefs.edit().putString("vpn_selected_builtin_server_id", id).apply()
        _vpnSelectedBuiltinServerIdFlow.value = id
    }

    fun isVpnUseCustomProfile(): Boolean = prefs.getBoolean("vpn_use_custom_profile", false)

    fun setVpnUseCustomProfile(useCustom: Boolean) {
        prefs.edit().putBoolean("vpn_use_custom_profile", useCustom).apply()
        _vpnUseCustomProfileFlow.value = useCustom
    }

    // ── Настройки приоритетов и стратегии автовыбора VPN-серверов ──
    fun getVpnAutoSelectStrategy(): VpnAutoSelectStrategy {
        val raw = prefs.getString("vpn_auto_select_strategy", VpnAutoSelectStrategy.TEL_FIRST.id)
        return VpnAutoSelectStrategy.values().find { it.id == raw } ?: VpnAutoSelectStrategy.TEL_FIRST
    }

    fun setVpnAutoSelectStrategy(strategy: VpnAutoSelectStrategy) {
        prefs.edit().putString("vpn_auto_select_strategy", strategy.id).apply()
        _vpnAutoSelectStrategyFlow.value = strategy
    }

    fun getVpnAutoSelectCategoryOrder(): List<String> {
        val raw = prefs.getString("vpn_auto_select_category_order", "TEL,ANYCAST,DEDICATED") ?: "TEL,ANYCAST,DEDICATED"
        val list = raw.split(",").filter { it.isNotBlank() }
        val allCats = listOf("TEL", "ANYCAST", "DEDICATED")
        val result = list.filter { it in allCats }.toMutableList()
        allCats.forEach { if (it !in result) result.add(it) }
        return result
    }

    fun setVpnAutoSelectCategoryOrder(order: List<String>) {
        val raw = order.joinToString(",")
        prefs.edit().putString("vpn_auto_select_category_order", raw).apply()
        _vpnAutoSelectCategoryOrderFlow.value = order
    }

    fun moveAutoSelectCategoryUp(categoryId: String) {
        val current = getVpnAutoSelectCategoryOrder().toMutableList()
        val index = current.indexOf(categoryId)
        if (index > 0) {
            val item = current.removeAt(index)
            current.add(index - 1, item)
            setVpnAutoSelectCategoryOrder(current)
        }
    }

    fun moveAutoSelectCategoryDown(categoryId: String) {
        val current = getVpnAutoSelectCategoryOrder().toMutableList()
        val index = current.indexOf(categoryId)
        if (index in 0 until current.size - 1) {
            val item = current.removeAt(index)
            current.add(index + 1, item)
            setVpnAutoSelectCategoryOrder(current)
        }
    }

    fun getVpnAutoSelectDisabledServers(): Set<String> {
        return prefs.getStringSet("vpn_auto_select_disabled_servers", emptySet()) ?: emptySet()
    }

    fun setVpnAutoSelectDisabledServers(disabled: Set<String>) {
        prefs.edit().putStringSet("vpn_auto_select_disabled_servers", disabled).apply()
        _vpnAutoSelectDisabledServersFlow.value = disabled
    }

    fun toggleVpnAutoSelectServer(serverId: String, enabled: Boolean) {
        val current = getVpnAutoSelectDisabledServers().toMutableSet()
        if (enabled) {
            current.remove(serverId)
        } else {
            current.add(serverId)
        }
        setVpnAutoSelectDisabledServers(current)
    }

    fun resetVpnAutoSelectSettings() {
        setVpnAutoSelectStrategy(VpnAutoSelectStrategy.TEL_FIRST)
        setVpnAutoSelectCategoryOrder(listOf("TEL", "ANYCAST", "DEDICATED"))
        setVpnAutoSelectDisabledServers(emptySet())
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

    fun isTlsEnabled(): Boolean = prefs.getBoolean(
        KEY_TLS_ENABLED,
        com.mirrly.tgproxy.util.LocaleHelper.isPersianLanguage(getAppLanguage())
    )

    fun setTlsEnabled(enabled: Boolean, isUserOverride: Boolean = true) {
        prefs.edit().apply {
            putBoolean(KEY_TLS_ENABLED, enabled)
            if (isUserOverride) {
                putBoolean(KEY_TLS_USER_OVERRIDE, true)
            }
            apply()
        }
        _isTlsEnabledFlow.value = enabled
        try {
            val app = context.applicationContext as? com.mirrly.tgproxy.MirrlyApplication
            app?.config?.isTlsEnabled = enabled
            app?.proxyServer?.applyTlsEnabled(enabled)
        } catch (_: Exception) {}
    }

    fun isVpnTlsEnabled(): Boolean = prefs.getBoolean(KEY_VPN_TLS_ENABLED, true)

    fun setVpnTlsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VPN_TLS_ENABLED, enabled).apply()
        _vpnTlsEnabledFlow.value = enabled
        try {
            val app = context.applicationContext as? com.mirrly.tgproxy.MirrlyApplication
            app?.config?.isVpnTlsEnabled = enabled
        } catch (_: Exception) {}
        if (MirrlyVpnService.isRunning || com.mirrly.tgproxy.core.NativeProxy.isVpnStarted) {
            com.mirrly.tgproxy.core.NativeProxy.setVpnTlsEnabled(enabled)
        }
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
        val provider = getAiProvider()
        val defaultModel = when (provider) {
            "Cloudflare" -> "@cf/meta/llama-3.3-70b-instruct"
            "OpenRouter" -> "deepseek/deepseek-r1"
            else -> "gemini-3.6-flash"
        }
        val model = prefs.getString("ai_model", defaultModel) ?: defaultModel
        if (provider == "Gemini" && (model.startsWith("@cf/") || model.contains("/") || model.startsWith("gemini-2.0") || model == "gemini-3.8-flash" || model.isEmpty())) {
            prefs.edit().putString("ai_model", "gemini-3.6-flash").apply()
            return "gemini-3.6-flash"
        }
        if (provider == "Cloudflare" && (!model.startsWith("@cf/") || model.isEmpty())) {
            prefs.edit().putString("ai_model", "@cf/meta/llama-3.3-70b-instruct").apply()
            return "@cf/meta/llama-3.3-70b-instruct"
        }
        if (provider == "OpenRouter" && (model.startsWith("@cf/") || !model.contains("/") || model.isEmpty())) {
            prefs.edit().putString("ai_model", "deepseek/deepseek-r1").apply()
            return "deepseek/deepseek-r1"
        }
        return model
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

    fun isAiPauseBackgroundParticles(): Boolean = prefs.getBoolean("ai_pause_background_particles", true)
    fun setAiPauseBackgroundParticles(pause: Boolean) {
        prefs.edit().putBoolean("ai_pause_background_particles", pause).apply()
        _aiPauseBackgroundParticlesFlow.value = pause
    }

    fun isAiUseSmartDns(): Boolean = prefs.getBoolean("ai_use_smart_dns", true)
    fun setAiUseSmartDns(use: Boolean) {
        prefs.edit().putBoolean("ai_use_smart_dns", use).apply()
    }

    fun getAiSmartDnsProvider(): String = prefs.getString("ai_smart_dns_provider", "geohide") ?: "geohide"
    fun setAiSmartDnsProvider(provider: String) {
        prefs.edit().putString("ai_smart_dns_provider", provider).apply()
    }

    @Deprecated("Superseded by isAiUseSmartDns")
    fun isAiUseSocks5(): Boolean = isAiUseSmartDns()
    @Deprecated("Superseded by setAiUseSmartDns")
    fun setAiUseSocks5(use: Boolean) {
        setAiUseSmartDns(use)
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
