/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui.icon

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors

/**
 * Единый диспетчер процедурных векторных иконок MirrlyUI (/mirrly-icons standard).
 * Направляет любой вызов R.drawable.ic_* на процедурный расчет в Jetpack Compose Canvas,
 * гарантируя Zero-Allocation (120 FPS), каркасный стиль Outline-Only (1.5dp) и адаптивную палитру.
 */
@Composable
fun MirrlyIcon(
    @DrawableRes id: Int,
    modifier: Modifier = Modifier.size(24.dp),
    contentDescription: String? = null,
    tint: Color = LocalProtocolColors.current.primary
) {
    when (id) {
        // --- Раздел 1. Навигация и списки ---
        R.drawable.ic_arrow_left -> MirrlyArrowLeftIcon(modifier = modifier, tint = tint)
        R.drawable.ic_arrow_down -> MirrlyArrowDownIcon(modifier = modifier, tint = tint)
        R.drawable.ic_arrow_up -> MirrlyArrowUpIcon(modifier = modifier, tint = tint)
        R.drawable.ic_chevron_right -> MirrlyChevronRightIcon(modifier = modifier, tint = tint)
        R.drawable.ic_close -> MirrlyCloseIcon(modifier = modifier, tint = tint)
        R.drawable.ic_expand -> MirrlyExpandIcon(modifier = modifier, tint = tint)
        R.drawable.ic_accordion_collapse -> MirrlyAccordionCollapseIcon(modifier = modifier, tint = tint)
        R.drawable.ic_accordion_expand -> MirrlyAccordionExpandIcon(modifier = modifier, tint = tint)
        R.drawable.ic_search -> MirrlySearchIcon(modifier = modifier, tint = tint)
        R.drawable.ic_refresh -> MirrlyRefreshIcon(modifier = modifier, tint = tint)
        R.drawable.ic_settings -> MirrlySettingsIcon(modifier = modifier, tint = tint)

        // --- Раздел 2. Действия и буфер обмена ---
        R.drawable.ic_copy -> MirrlyCopyIcon(modifier = modifier, tint = tint)
        R.drawable.ic_export -> MirrlyExportIcon(modifier = modifier, tint = tint)
        R.drawable.ic_trash -> MirrlyTrashIcon(modifier = modifier, tint = tint)
        R.drawable.ic_send -> MirrlySendIcon(modifier = modifier, tint = tint)
        R.drawable.ic_check -> MirrlyCheckIcon(modifier = modifier, tint = tint)
        R.drawable.ic_check_circle -> MirrlyCheckCircleIcon(modifier = modifier, tint = tint)
        R.drawable.ic_eye -> MirrlyEyeIcon(modifier = modifier, tint = tint)
        R.drawable.ic_eye_slash -> MirrlyEyeSlashIcon(modifier = modifier, tint = tint)
        R.drawable.ic_flash_on -> MirrlyFlashOnIcon(modifier = modifier, tint = tint)
        R.drawable.ic_flash_off -> MirrlyFlashOffIcon(modifier = modifier, tint = tint)

        // --- Раздел 3. Сеть и системный стек ---
        R.drawable.ic_vpn -> MirrlyVpnIcon(modifier = modifier, tint = tint)
        R.drawable.ic_globe -> MirrlyGlobeIcon(modifier = modifier, tint = tint)
        R.drawable.ic_split_tunnel -> MirrlySplitTunnelIcon(modifier = modifier, tint = tint)
        R.drawable.ic_power -> MirrlyPowerIcon(modifier = modifier, tint = tint)
        R.drawable.ic_timer -> MirrlyTimerIcon(modifier = modifier, tint = tint)
        R.drawable.ic_watchdog_clock -> MirrlyWatchdogClockIcon(modifier = modifier, tint = tint)
        R.drawable.ic_cpu_antifreeze -> MirrlyCpuAntifreezeIcon(modifier = modifier, tint = tint)
        R.drawable.ic_oem_startup -> MirrlyOemStartupIcon(modifier = modifier, tint = tint)
        R.drawable.ic_auto_select -> MirrlyAutoSelectIcon(modifier = modifier, tint = tint)
        R.drawable.ic_global_anycast -> MirrlyGlobalAnycastIcon(modifier = modifier, tint = tint)

        // --- Раздел 4. Телеметрия и ступени скорости ---
        R.drawable.ic_diag_antenna -> DiagAntennaIcon(modifier = modifier, color = tint)
        R.drawable.ic_diag_bufferbloat -> DiagBufferbloatIcon(modifier = modifier, color = tint)
        R.drawable.ic_diag_cloudflare -> DiagCloudflareIcon(modifier = modifier, color = tint)
        R.drawable.ic_diag_delivery -> DiagDeliveryIcon(modifier = modifier, color = tint)
        R.drawable.ic_diag_files -> DiagFilesIcon(modifier = modifier, color = tint)
        R.drawable.ic_diag_formula -> DiagFormulaIcon(modifier = modifier, color = tint)
        R.drawable.ic_diag_jitter -> DiagJitterIcon(modifier = modifier, color = tint)
        R.drawable.ic_diag_media -> DiagMediaIcon(modifier = modifier, color = tint)
        R.drawable.ic_diag_protocol -> DiagProtocolIcon(modifier = modifier, color = tint)
        R.drawable.ic_diag_rtt -> DiagRttIcon(modifier = modifier, color = tint)
        R.drawable.ic_diag_voip -> DiagVoipIcon(modifier = modifier, color = tint)
        R.drawable.ic_diag_worker -> DiagWorkerIcon(modifier = modifier, color = tint)
        R.drawable.ic_speed_auto -> SpeedAutoIcon(modifier = modifier, color = tint)
        R.drawable.ic_speed_eco -> SpeedEcoIcon(modifier = modifier, color = tint)
        R.drawable.ic_speed_balanced -> SpeedBalancedIcon(modifier = modifier, color = tint)
        R.drawable.ic_speed_turbo -> SpeedTurboIcon(modifier = modifier, color = tint)
        R.drawable.ic_speed_ultra -> SpeedUltraIcon(modifier = modifier, color = tint)

        // --- Раздел 5. Уведомления и шторка QS ---
        R.drawable.ic_stat_proxy_connected -> MirrlyStatProxyConnectedIcon(modifier = modifier, tint = tint)
        R.drawable.ic_stat_proxy_error -> MirrlyStatProxyErrorIcon(modifier = modifier, tint = tint)
        R.drawable.ic_stat_proxy_warning -> MirrlyStatProxyWarningIcon(modifier = modifier, tint = tint)
        R.drawable.ic_stat_timer -> MirrlyStatTimerIcon(modifier = modifier, tint = tint)
        R.drawable.ic_stat_update -> MirrlyStatUpdateIcon(modifier = modifier, tint = tint)
        R.drawable.ic_notif_stop -> MirrlyNotifStopIcon(modifier = modifier, tint = tint)

        // --- Раздел 6. Мультимедиа и аркада ---
        R.drawable.ic_wallpaper -> MirrlyIconWallpaper(modifier = modifier, color = tint)
        R.drawable.ic_traffic_pulse -> MirrlyIconTrafficPulse(modifier = modifier, color = tint)
        R.drawable.ic_volume_up -> MirrlyIconVolumeUp(modifier = modifier, color = tint)
        R.drawable.ic_volume_off -> MirrlyIconVolumeOff(modifier = modifier, color = tint)
        R.drawable.ic_vibrate -> MirrlyIconVibrate(modifier = modifier, color = tint)
        R.drawable.ic_vibrate_off -> MirrlyIconVibrateOff(modifier = modifier, color = tint)
        R.drawable.ic_gamepad -> MirrlyIconGamepad(modifier = modifier, color = tint)
        R.drawable.ic_crown -> MirrlyIconCrown(modifier = modifier, color = tint)
        R.drawable.ic_video -> MirrlyVideoIcon(modifier = modifier, tint = tint)

        // --- Раздел 7. ИИ и документация ---
        R.drawable.ic_ai_chat -> MirrlyAiChatIcon(modifier = modifier, tint = tint)
        R.drawable.ic_ai_thought -> MirrlyAiThoughtIcon(modifier = modifier, tint = tint)
        R.drawable.ic_chat_plus -> MirrlyChatPlusIcon(modifier = modifier, tint = tint)
        R.drawable.ic_bug -> MirrlyBugIcon(modifier = modifier, tint = tint)
        R.drawable.ic_info_circle -> MirrlyInfoCircleIcon(modifier = modifier, tint = tint)
        R.drawable.ic_question_mark -> MirrlyQuestionMarkIcon(modifier = modifier, tint = tint)
        R.drawable.ic_license -> MirrlyLicenseIcon(modifier = modifier, tint = tint)
        R.drawable.ic_logs -> MirrlyLogsIcon(modifier = modifier, tint = tint)
        R.drawable.ic_history -> MirrlyHistoryIcon(modifier = modifier, tint = tint)

        // --- Раздел 8. Сообщество, Зал Славы и донаты ---
        R.drawable.ic_user -> MirrlyUserIcon(modifier = modifier, tint = tint)
        R.drawable.ic_heart -> MirrlyHeartIcon(modifier = modifier, tint = tint)
        R.drawable.ic_star -> MirrlyStarIcon(modifier = modifier, tint = tint)
        R.drawable.ic_shield, R.drawable.ic_cyber_aegis -> MirrlyShieldIcon(modifier = modifier, tint = tint)
        R.drawable.ic_hall_of_fame -> MirrlyHallOfFameIcon(modifier = modifier, tint = tint)
        R.drawable.ic_fame_grid -> MirrlyFameGridIcon(modifier = modifier, tint = tint)
        R.drawable.ic_volunteer_badge -> MirrlyVolunteerBadgeIcon(modifier = modifier, tint = tint)
        R.drawable.ic_fingerprint_badge -> MirrlyFingerprintBadgeIcon(modifier = modifier, tint = tint)
        R.drawable.ic_mirrly_logo -> MirrlyCommunityCatPlaneIcon(modifier = modifier, tint = tint)

        else -> {
            // Резервный вызов для оригинальных векторных и растровых ассетов (ic_donate, Telegram, GitHub, CloudTips, Yandex Tips, DaLink, Cat Plane, avatar_developer)
            Icon(
                painter = painterResource(id = id),
                contentDescription = contentDescription,
                tint = tint,
                modifier = modifier
            )
        }
    }
}
