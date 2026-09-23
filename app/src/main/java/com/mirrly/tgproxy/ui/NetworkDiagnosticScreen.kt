/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.mirrly.tgproxy.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.FailureType
import com.mirrly.tgproxy.core.PingHistoryPoint
import com.mirrly.tgproxy.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDiagnosticScreen(
    onBack: () -> Unit,
    onOpenAnalytics: (() -> Unit)? = null,
    onOpenSpeedTest: (() -> Unit)? = null,
    onOpenDiagnosticReport: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val app = MirrlyApplication.instance
    val server = app.proxyServer

    val isSocks5 by app.prefsManager.isSocks5Flow.collectAsState()
    val activeWorker = remember(app.prefsManager.getActiveWorkerId()) { app.prefsManager.getActiveWorker() }

    val isProxyActive = server.isRunning

    var pingMs by remember { mutableLongStateOf(server.currentPingMs) }
    var jitterMs by remember { mutableLongStateOf(if (server.isRunning) server.stats.jitterMs else 0L) }
    var healthScore by remember { mutableIntStateOf(if (server.isRunning) server.stats.healthScore else 0) }
    var chatScore by remember { mutableIntStateOf(if (server.isRunning) server.stats.chatScore else 0) }
    val stoppedText = stringResource(R.string.nd_proxy_stopped)
    val initialDetail = stringResource(R.string.nd_initial_health_detail)
    val noConnText = stringResource(R.string.nd_no_connection)
    var chatVerdict by remember { mutableStateOf(if (server.isRunning) ConnectionHealthFormatter.formatChatVerdict(context, server.stats.chatVerdict) else stoppedText) }
    var callScore by remember { mutableIntStateOf(if (server.isRunning) server.stats.callScore else 0) }
    var verdict by remember { mutableStateOf(if (server.isRunning) ConnectionHealthFormatter.formatVerdict(context, server.stats.healthVerdict) else stoppedText) }
    var verdictDetail by remember { mutableStateOf(if (server.isRunning) ConnectionHealthFormatter.formatDetail(context, server.stats.healthDetail) else initialDetail) }
    var successRate by remember { mutableIntStateOf(if (server.isRunning) server.stats.healthSuccessRate else 100) }
    var mosScore by remember { mutableDoubleStateOf(if (server.isRunning) server.stats.mosScore else 1.0) }
    var mosGrade by remember { mutableStateOf(if (server.isRunning) ConnectionHealthFormatter.formatMosGrade(context, server.stats.mosGrade) else noConnText) }
    var isCallRecommended by remember { mutableStateOf(if (server.isRunning) server.stats.isCallRecommended else false) }
    var minRttMs by remember { mutableLongStateOf(if (server.isRunning) server.stats.minRttMs else -1L) }
    var bufferbloatMs by remember { mutableLongStateOf(if (server.isRunning) server.stats.bufferbloatMs else 0L) }
    var bufferbloatGrade by remember { mutableStateOf(if (server.isRunning) server.stats.bufferbloatGrade else "—") }
    var currentAlpha by remember { mutableDoubleStateOf(if (server.isRunning) server.stats.currentAlpha else 0.25) }
    var rttHistory by remember { mutableStateOf<List<PingHistoryPoint>>(if (server.isRunning) server.stats.rttHistory else emptyList()) }
    var poolSize by remember { mutableIntStateOf(if (server.isRunning) server.config.mtprotoStandbyPerActiveSlot else 1) }
    var lastFailureType by remember { mutableStateOf(if (server.isRunning) server.stats.lastFailureType else FailureType.NONE) }

    LaunchedEffect(isProxyActive) {
        while (isActive) {
            if (server.isRunning) {
                pingMs = server.currentPingMs
                jitterMs = server.stats.jitterMs
                healthScore = server.stats.healthScore
                chatScore = server.stats.chatScore
                chatVerdict = ConnectionHealthFormatter.formatChatVerdict(context, server.stats.chatVerdict)
                callScore = server.stats.callScore
                verdict = ConnectionHealthFormatter.formatVerdict(context, server.stats.healthVerdict)
                verdictDetail = ConnectionHealthFormatter.formatDetail(context, server.stats.healthDetail)
                successRate = server.stats.healthSuccessRate
                mosScore = server.stats.mosScore
                mosGrade = ConnectionHealthFormatter.formatMosGrade(context, server.stats.mosGrade)
                isCallRecommended = server.stats.isCallRecommended
                minRttMs = server.stats.minRttMs
                bufferbloatMs = server.stats.bufferbloatMs
                bufferbloatGrade = server.stats.bufferbloatGrade
                currentAlpha = server.stats.currentAlpha
                rttHistory = server.stats.rttHistory
                poolSize = server.config.mtprotoStandbyPerActiveSlot
                lastFailureType = server.stats.lastFailureType
            } else {
                healthScore = 0
                chatScore = 0
                chatVerdict = stoppedText
                callScore = 0
                verdict = stoppedText
                verdictDetail = initialDetail
                mosScore = 1.0
                mosGrade = stoppedText
                isCallRecommended = false
                minRttMs = -1L
                bufferbloatMs = 0L
                bufferbloatGrade = "—"
                currentAlpha = 0.25
                rttHistory = emptyList()
                poolSize = 1
                lastFailureType = FailureType.NONE
            }
            kotlinx.coroutines.delay(500)
        }
    }

    var infoKey by remember { mutableStateOf<String?>(null) }

    val infoData = remember {
        mapOf(
            "sqi" to Pair(R.string.nd_info_sqi_title, R.string.nd_info_sqi_desc),
            "chats_sqi" to Pair(R.string.nd_info_chats_sqi_title, R.string.nd_info_chats_sqi_desc),
            "latency_sparkline" to Pair(R.string.nd_info_latency_sparkline_title, R.string.nd_info_latency_sparkline_desc),
            "readiness_grid" to Pair(R.string.nd_info_readiness_grid_title, R.string.nd_info_readiness_grid_desc),
            "readiness_text" to Pair(R.string.nd_info_readiness_text_title, R.string.nd_info_readiness_text_desc),
            "readiness_media" to Pair(R.string.nd_info_readiness_media_title, R.string.nd_info_readiness_media_desc),
            "readiness_files" to Pair(R.string.nd_info_readiness_files_title, R.string.nd_info_readiness_files_desc),
            "readiness_calls" to Pair(R.string.nd_info_readiness_calls_title, R.string.nd_info_readiness_calls_desc),
            "network_path" to Pair(R.string.nd_info_network_path_title, R.string.nd_info_network_path_desc),
            "rtt" to Pair(R.string.nd_info_rtt_title, R.string.nd_info_rtt_desc),
            "jitter" to Pair(R.string.nd_info_jitter_title, R.string.nd_info_jitter_desc),
            "delivery" to Pair(R.string.nd_info_delivery_title, R.string.nd_info_delivery_desc),
            "localization" to Pair(R.string.nd_info_localization_title, R.string.nd_info_localization_desc),
            "bottleneck_radar" to Pair(R.string.nd_info_bottleneck_radar_title, R.string.nd_info_bottleneck_radar_desc),
            "smart_insights" to Pair(R.string.nd_info_smart_insights_title, R.string.nd_info_smart_insights_desc),
            "hop_device" to Pair(R.string.nd_info_hop_device_title, R.string.nd_info_hop_device_desc),
            "hop_isp" to Pair(R.string.nd_info_hop_isp_title, R.string.nd_info_hop_isp_desc),
            "hop_tg_dc" to Pair(R.string.nd_info_hop_tg_dc_title, R.string.nd_info_hop_tg_dc_desc),
            "last_mile" to Pair(R.string.nd_info_last_mile_title, R.string.nd_info_last_mile_desc),
            "cf_edge" to Pair(R.string.nd_info_cf_edge_title, R.string.nd_info_cf_edge_desc),
            "config" to Pair(R.string.nd_info_config_title, R.string.nd_info_config_desc),
            "active_worker" to Pair(R.string.nd_info_active_worker_title, R.string.nd_info_active_worker_desc),
            "protocol_mode" to Pair(R.string.nd_info_protocol_mode_title, R.string.nd_info_protocol_mode_desc),
            "mos" to Pair(R.string.nd_info_mos_title, R.string.nd_info_mos_desc),
            "mtproto_pool" to Pair(R.string.nd_info_mtproto_pool_title, R.string.nd_info_mtproto_pool_desc),
            "bufferbloat" to Pair(R.string.nd_info_bufferbloat_title, R.string.nd_info_bufferbloat_desc),
            "math_model" to Pair(R.string.nd_info_math_model_title, R.string.nd_info_math_model_desc),
            "cf_quota" to Pair(R.string.nd_info_cf_quota_title, R.string.nd_info_cf_quota_desc)
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .adaptiveContainerWidth(600.dp)
                .fillMaxHeight()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(rememberScrollState())
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
                )
                .adaptiveContentPadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── HERO: DUAL QUALITY GAUGES (CHATS VS CALLS) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 0),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. LEFT HERO CARD: CHATS & MEDIA
                val chatColor = when {
                    chatScore >= 90 -> Color(0xFF00FF87)
                    chatScore >= 75 -> Color(0xFF00E676)
                    chatScore >= 50 -> Color(0xFFFFB703)
                    else -> Color(0xFFFF0055)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            infoKey = "chats_sqi"
                        }
                        .padding(14.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.nd_chats_media_title),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.1.sp,
                                color = TextMuted
                            )
                            InfoButton { infoKey = "chats_sqi" }
                        }

                        LiquidWaveQualityCircle(
                            score = chatScore,
                            isProxyActive = isProxyActive,
                            isSocks5 = isSocks5,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                infoKey = "chats_sqi"
                            },
                            modifier = Modifier.size(64.dp)
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (isProxyActive) "$chatScore%" else "—",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = TextWhite
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isProxyActive) chatVerdict else stringResource(R.string.nd_stopped),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = chatColor,
                                maxLines = 1
                            )
                        }
                    }
                }

                // 2. RIGHT HERO CARD: CALLS & VIDEO (SOCKS5) OR THREAD POOL (MTPROTO)
                val mosColor = when {
                    mosScore >= 4.20 -> Color(0xFF00FF87)
                    mosScore >= 3.80 -> Color(0xFF38BDF8)
                    mosScore >= 3.10 -> Color(0xFFB388FF)
                    mosScore >= 2.40 -> Color(0xFFFFB703)
                    else -> Color(0xFFFF0055)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            infoKey = if (isSocks5) "mos" else "mtproto_pool"
                        }
                        .padding(14.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isSocks5) stringResource(R.string.nd_calls_video_title) else stringResource(R.string.nd_hero_mtproto_pool_title),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.1.sp,
                                color = TextMuted
                            )
                            InfoButton { infoKey = if (isSocks5) "mos" else "mtproto_pool" }
                        }

                        if (isSocks5) {
                            LiquidWaveQualityCircle(
                                score = callScore,
                                isProxyActive = isProxyActive,
                                isSocks5 = true,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    infoKey = "mos"
                                },
                                modifier = Modifier.size(64.dp)
                            )

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isProxyActive) String.format(java.util.Locale.US, "%.2f MOS", mosScore) else "—",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    color = TextWhite
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isProxyActive) mosGrade else stringResource(R.string.nd_stopped),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = mosColor,
                                    maxLines = 1
                                )
                            }
                        } else {
                            val poolScore = if (isProxyActive) (poolSize * 25).coerceIn(25, 100) else 0
                            val poolColor = if (isProxyActive) Color(0xFF00FF87) else TextMuted
                            LiquidWaveQualityCircle(
                                score = poolScore,
                                isProxyActive = isProxyActive,
                                isSocks5 = false,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    infoKey = "mtproto_pool"
                                },
                                modifier = Modifier.size(64.dp)
                            )

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isProxyActive) stringResource(R.string.nd_hero_mtproto_slots_val, poolSize) else "—",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = TextWhite
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isProxyActive) stringResource(R.string.nd_hero_mtproto_slots_desc) else stringResource(R.string.nd_stopped),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = poolColor,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // ── TUNNEL SPEED TEST PROMO CARD ──
            if (onOpenSpeedTest != null) {
                val speedTestAccent = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.03f))
                        .border(1.dp, speedTestAccent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenSpeedTest()
                        }
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(speedTestAccent.copy(alpha = 0.15f))
                                    .border(1.dp, speedTestAccent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_refresh),
                                    contentDescription = null,
                                    tint = speedTestAccent,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            Column {
                                Text(
                                    text = stringResource(R.string.nd_speedtest_title),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                Text(
                                    text = stringResource(R.string.nd_speedtest_desc),
                                    fontSize = 11.5.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = speedTestAccent.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, speedTestAccent.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = stringResource(R.string.nd_speedtest_action_run),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = speedTestAccent,
                                letterSpacing = 0.8.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            // ── SMART NETWORK INSIGHTS (RECOMMENDATION BANNER) ──
            SmartNetworkInsightsCard(
                pingMs = pingMs,
                jitterMs = jitterMs,
                bufferbloatMs = bufferbloatMs,
                successRate = successRate,
                healthScore = healthScore,
                chatScore = chatScore,
                mosScore = mosScore,
                lastFailureType = lastFailureType,
                isProxyActive = isProxyActive,
                isSocks5 = isSocks5,
                onInfoClick = { infoKey = "smart_insights" }
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // ── LIVE LATENCY SPARKLINE GRAPH (30-60 SEC) ──
            LiveLatencySparklineCard(
                rttHistory = rttHistory,
                currentPingMs = pingMs,
                isProxyActive = isProxyActive,
                isSocks5 = isSocks5,
                onInfoClick = { infoKey = "latency_sparkline" }
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // ── TELEGRAM SERVICES READINESS GRID ──
            ContentReadinessGrid(
                pingMs = pingMs,
                jitterMs = jitterMs,
                successRate = successRate,
                bufferbloatMs = bufferbloatMs,
                mosScore = mosScore,
                poolSize = poolSize,
                isProxyActive = isProxyActive,
                isSocks5 = isSocks5,
                onItemClick = { key -> infoKey = key },
                onInfoClick = { infoKey = "readiness_grid" }
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // ── SECTION 1: NETWORK PATH & LATENCY ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 3),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.nd_network_path_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.3.sp,
                        color = TextMuted
                    )
                    InfoButton { infoKey = "network_path" }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                ) {
                    Column {
                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_rtt,
                            iconColor = Color(0xFF38BDF8),
                            title = stringResource(R.string.nd_rtt_title),
                            value = if (pingMs > 0) stringResource(R.string.unit_ms_val, pingMs) else "—",
                            badgeText = if (pingMs in 1..100) stringResource(R.string.nd_badge_excellent) else if (pingMs <= 200) stringResource(R.string.nd_badge_normal) else stringResource(R.string.nd_badge_elevated),
                            badgeColor = if (pingMs in 1..100) Color(0xFF00FF87) else if (pingMs <= 200) Color(0xFF00E676) else Color(0xFFFFB703),
                            onInfoClick = { infoKey = "rtt" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_jitter,
                            iconColor = Color(0xFFB388FF),
                            title = stringResource(R.string.nd_jitter_title),
                            value = stringResource(R.string.unit_plus_minus_ms_val, jitterMs),
                            badgeText = if (jitterMs <= 15) stringResource(R.string.nd_badge_stable) else if (jitterMs <= 35) stringResource(R.string.nd_badge_moderate) else stringResource(R.string.nd_badge_unstable),
                            badgeColor = if (jitterMs <= 15) Color(0xFF00FF87) else if (jitterMs <= 35) Color(0xFF00E676) else Color(0xFFFFB703),
                            onInfoClick = { infoKey = "jitter" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_delivery,
                            iconColor = Color(0xFF00FF87),
                            title = stringResource(R.string.nd_delivery_title),
                            value = "$successRate%",
                            badgeText = if (successRate >= 95) stringResource(R.string.nd_badge_100_norm) else if (successRate >= 75) stringResource(R.string.nd_badge_packet_loss) else stringResource(R.string.nd_badge_failures),
                            badgeColor = if (successRate >= 95) Color(0xFF00FF87) else if (successRate >= 75) Color(0xFFFFB703) else Color(0xFFFF0055),
                            onInfoClick = { infoKey = "delivery" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        val mosBadgeColor = when {
                            mosScore >= 4.20 -> Color(0xFF00FF87)
                            mosScore >= 3.80 -> Color(0xFF00E676)
                            mosScore >= 3.10 -> Color(0xFFFFB703)
                            else -> Color(0xFFFF0055)
                        }

                        if (isSocks5) {
                            DiagnosticMetricRow(
                                iconRes = R.drawable.ic_diag_voip,
                                iconColor = Color(0xFF38BDF8),
                                title = stringResource(R.string.nd_calls_quality_title),
                                value = if (isProxyActive && pingMs > 0) "${String.format(java.util.Locale.US, "%.2f", mosScore)} / 4.50 (${mosGrade})" else "—",
                                badgeText = if (isProxyActive && pingMs > 0) (if (isCallRecommended) stringResource(R.string.nd_badge_hd_voice) else stringResource(R.string.nd_badge_noise)) else stringResource(R.string.nd_badge_waiting),
                                badgeColor = if (isProxyActive && pingMs > 0) mosBadgeColor else Color(0xFF38BDF8),
                                onInfoClick = { infoKey = "mos" }
                            )
                        } else {
                            DiagnosticMetricRow(
                                iconRes = R.drawable.ic_diag_voip,
                                iconColor = TextMuted,
                                title = stringResource(R.string.nd_calls_quality_title),
                                value = stringResource(R.string.nd_calls_unsupported_mtproto),
                                badgeText = stringResource(R.string.nd_badge_socks5_only),
                                badgeColor = Color(0xFF818CF8),
                                onInfoClick = { infoKey = "mos" }
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // ── SECTION 2: BOTTLENECK LOCALIZATION (HOP-BY-HOP RADAR) ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 4),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HopByHopBottleneckRadar(
                    pingMs = pingMs,
                    jitterMs = jitterMs,
                    bufferbloatMs = bufferbloatMs,
                    successRate = successRate,
                    lastFailureType = lastFailureType,
                    isProxyActive = isProxyActive,
                    isSocks5 = isSocks5,
                    onInfoClick = { infoKey = "bottleneck_radar" },
                    onHopClick = { key -> infoKey = key }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                ) {
                    Column {
                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_antenna,
                            iconColor = Color(0xFF00E676),
                            title = stringResource(R.string.nd_isp_wifi_title),
                            value = if (jitterMs <= 25) stringResource(R.string.nd_radio_stable) else stringResource(R.string.nd_radio_noise),
                            badgeText = if (jitterMs <= 25) stringResource(R.string.nd_badge_norm) else stringResource(R.string.nd_badge_attention),
                            badgeColor = if (jitterMs <= 25) Color(0xFF00FF87) else Color(0xFFFFB703),
                            onInfoClick = { infoKey = "last_mile" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_cloudflare,
                            iconColor = Color(0xFF38BDF8),
                            title = stringResource(R.string.nd_cf_segment_title),
                            value = if (pingMs > 0) stringResource(R.string.nd_wss_tunnel_active) else stringResource(R.string.nd_waiting_probe),
                            badgeText = if (pingMs in 1..250) stringResource(R.string.nd_badge_available) else if (pingMs > 250) stringResource(R.string.nd_badge_lag) else stringResource(R.string.nd_badge_fail),
                            badgeColor = if (pingMs in 1..250) Color(0xFF00FF87) else Color(0xFFFFB703),
                            onInfoClick = { infoKey = "cf_edge" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        val bloatBadgeColor = when {
                            bufferbloatGrade.startsWith("A+") -> Color(0xFF00FF87)
                            bufferbloatGrade.startsWith("A") -> Color(0xFF00E676)
                            bufferbloatGrade.startsWith("B") -> Color(0xFF38BDF8)
                            bufferbloatGrade.startsWith("C") -> Color(0xFFFFB703)
                            else -> Color(0xFFFF0055)
                        }

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_bufferbloat,
                            iconColor = Color(0xFFFFB703),
                            title = stringResource(R.string.nd_bufferbloat_title),
                            value = if (isProxyActive && minRttMs > 0) stringResource(R.string.nd_bufferbloat_val, bufferbloatMs, minRttMs, String.format(java.util.Locale.US, "%.2f", currentAlpha)) else "—",
                            badgeText = if (isProxyActive && minRttMs > 0) bufferbloatGrade else stringResource(R.string.nd_badge_waiting),
                            badgeColor = if (isProxyActive && minRttMs > 0) bloatBadgeColor else Color(0xFFFFB703),
                            onInfoClick = { infoKey = "bufferbloat" }
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // ── SECTION 3: TUNNEL CONFIGURATION ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 3),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.nd_config_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.3.sp,
                        color = TextMuted
                    )
                    InfoButton { infoKey = "config" }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                ) {
                    Column {
                        // In developer worker: do NOT print internal CF worker domain!
                        val workerDisplayValue = if (activeWorker.isDeveloperWorker) {
                            activeWorker.name
                        } else {
                            "${activeWorker.name} (${activeWorker.domain})"
                        }

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_worker,
                            iconColor = Color(0xFFB388FF),
                            title = stringResource(R.string.nd_active_worker_title),
                            value = workerDisplayValue,
                            badgeText = if (activeWorker.isDeveloperWorker) stringResource(R.string.nd_badge_builtin) else stringResource(R.string.nd_badge_custom),
                            badgeColor = Color(0xFF38BDF8),
                            onInfoClick = { infoKey = "active_worker" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_protocol,
                            iconColor = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87),
                            title = stringResource(R.string.nd_protocol_port_title),
                            value = if (isSocks5) "SOCKS5 TCP Relay (:10808)" else "MTProto TLS Relay (:1080)",
                            badgeText = "WSS TLS 1.3",
                            badgeColor = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87),
                            onInfoClick = { infoKey = "protocol_mode" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_formula,
                            iconColor = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87),
                            title = stringResource(R.string.nd_cf_quota_title),
                            value = stringResource(R.string.nd_cf_quota_val),
                            badgeText = stringResource(R.string.nd_chart_100k_day),
                            badgeColor = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87),
                            onInfoClick = {
                                if (onOpenAnalytics != null) {
                                    onOpenAnalytics()
                                } else {
                                    infoKey = "cf_quota"
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }

        // Top Bar
        NetworkDiagnosticTopBar(
            isSocks5 = isSocks5,
            onBack = onBack,
            onOpenDiagnosticReport = onOpenDiagnosticReport
        )

        // Floating Cyber Particles Overlay
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 10,
            alphaMultiplier = 0.40f
        )

        // Detailed Explanation Modal Dialog
        infoKey?.let { key ->
            val info = infoData[key]
            if (info != null) {
                InfoDialog(
                    title = stringResource(info.first),
                    body = stringResource(info.second),
                    onDismiss = { infoKey = null }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkDiagnosticTopBar(
    isSocks5: Boolean,
    onBack: () -> Unit,
    onOpenDiagnosticReport: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.98f),
                        Color.Black.copy(alpha = 0.94f),
                        Color.Black.copy(alpha = 0.72f),
                        Color.Black.copy(alpha = 0.00f)
                    )
                )
            )
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.nd_screen_title),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1
                )
            },
            navigationIcon = {
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBack()
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_left),
                        contentDescription = stringResource(R.string.action_back),
                        tint = TextWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            },
            actions = {
                if (onOpenDiagnosticReport != null) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenDiagnosticReport()
                        },
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_bug),
                            contentDescription = stringResource(R.string.diagnostic_report_title),
                            tint = TextWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSocks5) Color(0xFF818CF8).copy(alpha = 0.12f) else Color(0xFF00FF87).copy(alpha = 0.12f))
                        .border(
                            0.8.dp,
                            if (isSocks5) Color(0xFF818CF8).copy(alpha = 0.45f) else Color(0xFF00FF87).copy(alpha = 0.45f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isSocks5) "SOCKS5" else "MTProto",
                        color = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}

@Composable
fun DiagnosticMetricRow(
    iconRes: Int,
    iconColor: Color,
    title: String,
    value: String,
    badgeText: String,
    badgeColor: Color,
    onInfoClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onInfoClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconColor.copy(alpha = 0.12f))
                    .border(1.dp, iconColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Text(
                    text = value,
                    fontSize = 11.5.sp,
                    color = TextMuted
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor.copy(alpha = 0.12f))
                    .border(0.8.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = badgeText,
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            InfoButton { onInfoClick() }
        }
    }
}

@Composable
fun LiveLatencySparklineCard(
    rttHistory: List<PingHistoryPoint>,
    currentPingMs: Long,
    isProxyActive: Boolean,
    isSocks5: Boolean,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val primaryColor = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87)
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val validPoints = remember(rttHistory) {
        rttHistory.filter { it.rttMs > 0L }
    }

    val minRtt = remember(validPoints) { validPoints.minOfOrNull { it.rttMs } ?: 0L }
    val maxRtt = remember(validPoints) { validPoints.maxOfOrNull { it.rttMs } ?: 0L }
    val avgRtt = remember(validPoints) {
        if (validPoints.isNotEmpty()) validPoints.map { it.rttMs }.average().toLong() else 0L
    }

    Column(
        modifier = modifier.staggeredEntrance(index = 1),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.nd_sparkline_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
                InfoButton(onClick = onInfoClick)
            }

            if (isProxyActive && validPoints.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(primaryColor.copy(alpha = 0.12f))
                        .border(0.8.dp, primaryColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.nd_live_ping, if (currentPingMs > 0) "$currentPingMs ms" else "—"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Transparent)
                .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Top Summary Metrics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SparklineStatPill(
                        label = stringResource(R.string.nd_label_min),
                        value = if (isProxyActive && minRtt > 0) stringResource(R.string.unit_ms_val, minRtt) else "—",
                        color = Color(0xFF00FF87)
                    )
                    SparklineStatPill(
                        label = stringResource(R.string.nd_label_avg),
                        value = if (isProxyActive && avgRtt > 0) stringResource(R.string.unit_ms_val, avgRtt) else "—",
                        color = Color(0xFF38BDF8)
                    )
                    SparklineStatPill(
                        label = stringResource(R.string.nd_label_max),
                        value = if (isProxyActive && maxRtt > 0) stringResource(R.string.unit_ms_val, maxRtt) else "—",
                        color = if (maxRtt > 200) Color(0xFFFF0055) else if (maxRtt > 120) Color(0xFFFFB703) else Color(0xFF00FF87)
                    )
                    SparklineStatPill(
                        label = stringResource(R.string.nd_label_samples),
                        value = if (isProxyActive) "${rttHistory.size}" else "0",
                        color = TextMuted
                    )
                }

                // Interactive Canvas Chart
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                ) {
                    if (!isProxyActive || rttHistory.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (!isProxyActive) stringResource(R.string.nd_proxy_stopped) else stringResource(R.string.nd_collecting_first_probes),
                                fontSize = 12.sp,
                                color = TextMuted,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        val points = rttHistory
                        val path = remember { Path() }
                        val fillPath = remember { Path() }

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(points) {
                                    detectTapGestures(
                                        onPress = { offset ->
                                            val idx = findClosestPointIndex(offset.x, size.width.toFloat(), points.size)
                                            selectedIndex = idx
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            tryAwaitRelease()
                                            selectedIndex = null
                                        }
                                    )
                                }
                                .pointerInput(points) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val idx = findClosestPointIndex(offset.x, size.width.toFloat(), points.size)
                                            selectedIndex = idx
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        },
                                        onDragEnd = { selectedIndex = null },
                                        onDragCancel = { selectedIndex = null },
                                        onDrag = { change, _ ->
                                            val idx = findClosestPointIndex(change.position.x, size.width.toFloat(), points.size)
                                            if (idx != selectedIndex) {
                                                selectedIndex = idx
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    )
                                }
                        ) {
                            val w = size.width
                            val h = size.height
                            val n = points.size

                            val maxVal = maxOf(100L, (points.maxOfOrNull { it.rttMs } ?: 100L) + 20L).toFloat()
                            val minVal = 0f

                            // 1. Guidelines (50ms, 100ms)
                            val guide50Y = h * (1f - (50f / maxVal).coerceIn(0f, 1f))
                            drawLine(
                                color = Color(0xFF1E2333),
                                start = Offset(0f, guide50Y),
                                end = Offset(w, guide50Y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                            )

                            val guide100Y = h * (1f - (100f / maxVal).coerceIn(0f, 1f))
                            drawLine(
                                color = Color(0xFF1E2333),
                                start = Offset(0f, guide100Y),
                                end = Offset(w, guide100Y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                            )

                            // 2. Compute Coordinates
                            val coords = points.mapIndexed { idx, pt ->
                                val x = if (n > 1) (idx.toFloat() / (n - 1)) * w else w / 2f
                                val rtt = if (pt.rttMs > 0) pt.rttMs.toFloat() else maxVal
                                val y = (h * (1f - ((rtt - minVal) / (maxVal - minVal)))).coerceIn(4f, h - 4f)
                                Offset(x, y)
                            }

                            // 3. Draw Spline/Line Path
                            path.reset()
                            fillPath.reset()

                            if (coords.isNotEmpty()) {
                                path.moveTo(coords[0].x, coords[0].y)
                                fillPath.moveTo(coords[0].x, h)
                                fillPath.lineTo(coords[0].x, coords[0].y)

                                for (i in 0 until coords.size - 1) {
                                    val p0 = coords[i]
                                    val p1 = coords[i + 1]
                                    val midX = (p0.x + p1.x) / 2f
                                    path.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                                    fillPath.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                                }

                                fillPath.lineTo(coords.last().x, h)
                                fillPath.close()

                                // Gradient fill under curve
                                drawPath(
                                    path = fillPath,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            primaryColor.copy(alpha = 0.25f),
                                            primaryColor.copy(alpha = 0.02f),
                                            Color.Transparent
                                        ),
                                        startY = 0f,
                                        endY = h
                                    )
                                )

                                // Stroke line
                                drawPath(
                                    path = path,
                                    color = primaryColor,
                                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )

                                // Draw Dots
                                coords.forEachIndexed { idx, pt ->
                                    val pointData = points[idx]
                                    val isHovered = selectedIndex == idx
                                    val dotColor = when {
                                        !pointData.isSuccess || pointData.rttMs <= 0 -> Color(0xFFFF0055)
                                        pointData.rttMs <= 80 -> Color(0xFF00FF87)
                                        pointData.rttMs <= 180 -> Color(0xFF38BDF8)
                                        else -> Color(0xFFFFB703)
                                    }

                                    if (isHovered) {
                                        drawCircle(
                                            color = dotColor.copy(alpha = 0.40f),
                                            radius = 7.dp.toPx(),
                                            center = pt
                                        )
                                        drawCircle(
                                            color = Color.White,
                                            radius = 4.dp.toPx(),
                                            center = pt
                                        )
                                    } else if (n <= 25 || idx == n - 1 || idx % 2 == 0) {
                                        drawCircle(
                                            color = dotColor,
                                            radius = 2.5.dp.toPx(),
                                            center = pt
                                        )
                                    }
                                }
                            }

                            // 4. Guideline for Scrubber
                            selectedIndex?.let { idx ->
                                if (idx in coords.indices) {
                                    val sel = coords[idx]
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.40f),
                                        start = Offset(sel.x, 0f),
                                        end = Offset(sel.x, h),
                                        strokeWidth = 1.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                                    )
                                }
                            }
                        }

                        // Scrubber Tooltip
                        selectedIndex?.let { idx ->
                            if (idx in points.indices) {
                                val pt = points[idx]
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF090D16).copy(alpha = 0.94f))
                                        .border(1.dp, primaryColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (pt.isSuccess) stringResource(R.string.nd_sample_success, idx + 1, pt.rttMs) else stringResource(R.string.nd_sample_fail, idx + 1),
                                        color = if (pt.isSuccess) TextWhite else Color(0xFFFF0055),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SparklineStatPill(
    label: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private fun findClosestPointIndex(touchX: Float, totalWidth: Float, pointCount: Int): Int {
    if (pointCount <= 1 || totalWidth <= 0f) return 0
    val fraction = (touchX / totalWidth).coerceIn(0f, 1f)
    return (fraction * (pointCount - 1)).toInt().coerceIn(0, pointCount - 1)
}

@Composable
fun ContentReadinessGrid(
    pingMs: Long,
    jitterMs: Long,
    successRate: Int,
    bufferbloatMs: Long,
    mosScore: Double,
    poolSize: Int,
    isProxyActive: Boolean,
    isSocks5: Boolean,
    onItemClick: (String) -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 1. Text chats
    val textStatus = when {
        !isProxyActive -> stringResource(R.string.nd_proxy_stopped)
        pingMs in 1..80 -> stringResource(R.string.nd_ready_instant)
        pingMs in 81..180 -> stringResource(R.string.nd_ready_fast_norm)
        pingMs > 180 -> stringResource(R.string.nd_ready_delayed)
        else -> stringResource(R.string.nd_badge_waiting)
    }
    val textBadge = when {
        !isProxyActive -> stringResource(R.string.nd_stopped)
        pingMs in 1..80 -> stringResource(R.string.nd_ready_zero_delay)
        pingMs in 81..180 -> stringResource(R.string.nd_badge_norm)
        else -> stringResource(R.string.nd_badge_lag)
    }
    val textBadgeColor = when {
        !isProxyActive -> TextMuted
        pingMs in 1..80 -> Color(0xFF00FF87)
        pingMs in 81..180 -> Color(0xFF38BDF8)
        else -> Color(0xFFFFB703)
    }

    // 2. Photos and media
    val mediaStatus = when {
        !isProxyActive -> stringResource(R.string.nd_proxy_stopped)
        successRate >= 95 && jitterMs <= 25 -> stringResource(R.string.nd_ready_high_speed)
        successRate >= 75 -> stringResource(R.string.nd_ready_stable_download)
        else -> stringResource(R.string.nd_badge_packet_loss)
    }
    val mediaBadge = when {
        !isProxyActive -> stringResource(R.string.nd_stopped)
        successRate >= 95 -> stringResource(R.string.nd_ready_lossless)
        successRate >= 75 -> stringResource(R.string.nd_ready_buffering)
        else -> stringResource(R.string.nd_badge_failures)
    }
    val mediaBadgeColor = when {
        !isProxyActive -> TextMuted
        successRate >= 95 -> Color(0xFF00FF87)
        successRate >= 75 -> Color(0xFFFFB703)
        else -> Color(0xFFFF0055)
    }

    // 3. Heavy files and 4K (SOCKS5: direct TCP stream; MTProto: standby pool)
    val filesStatus = when {
        !isProxyActive -> stringResource(R.string.nd_proxy_stopped)
        isSocks5 -> if (bufferbloatMs <= 25) stringResource(R.string.nd_ready_socks5_direct_stream) else stringResource(R.string.nd_ready_standard_stream)
        bufferbloatMs <= 25 && poolSize >= 4 -> stringResource(R.string.nd_ready_turbo_stream, poolSize)
        bufferbloatMs <= 75 -> stringResource(R.string.nd_ready_standard_stream)
        else -> stringResource(R.string.nd_ready_queue_limit)
    }
    val filesBadge = when {
        !isProxyActive -> stringResource(R.string.nd_stopped)
        isSocks5 -> if (bufferbloatMs <= 25) stringResource(R.string.nd_ready_socks5_buffer) else "Balanced"
        bufferbloatMs <= 25 -> stringResource(R.string.nd_ready_2mb_buffer)
        bufferbloatMs <= 75 -> "Balanced"
        else -> "Bufferbloat"
    }
    val filesBadgeColor = when {
        !isProxyActive -> TextMuted
        bufferbloatMs <= 25 -> Color(0xFF818CF8)
        bufferbloatMs <= 75 -> Color(0xFF38BDF8)
        else -> Color(0xFFFFB703)
    }

    // 4. Calls and video (SOCKS5 only; MTProto does not support VoIP)
    val callsStatus = when {
        !isProxyActive -> stringResource(R.string.nd_proxy_stopped)
        !isSocks5 -> stringResource(R.string.nd_calls_unsupported_mtproto)
        mosScore >= 4.20 -> "HD Voice (Opus 48k)"
        mosScore >= 3.80 -> stringResource(R.string.nd_ready_good_clarity)
        mosScore >= 3.10 -> stringResource(R.string.nd_ready_acceptable_audio)
        else -> stringResource(R.string.nd_ready_not_recommended)
    }
    val callsBadge = when {
        !isProxyActive -> stringResource(R.string.nd_stopped)
        !isSocks5 -> stringResource(R.string.nd_badge_socks5_only)
        mosScore >= 4.20 -> "HD 1080p"
        mosScore >= 3.80 -> "HD 720p"
        mosScore >= 3.10 -> stringResource(R.string.nd_ready_sd_call)
        else -> stringResource(R.string.nd_badge_noise)
    }
    val callsBadgeColor = when {
        !isProxyActive -> TextMuted
        !isSocks5 -> Color(0xFF818CF8)
        mosScore >= 4.20 -> Color(0xFF00FF87)
        mosScore >= 3.80 -> Color(0xFF38BDF8)
        mosScore >= 3.10 -> Color(0xFFFFB703)
        else -> Color(0xFFFF0055)
    }

    Column(
        modifier = modifier.staggeredEntrance(index = 2),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.nd_services_readiness_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
                InfoButton(onClick = onInfoClick)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ContentReadinessItem(
                    iconRes = R.drawable.ic_send,
                    iconColor = Color(0xFF38BDF8),
                    title = stringResource(R.string.nd_ready_chats_title),
                    statusText = textStatus,
                    badgeText = textBadge,
                    badgeColor = textBadgeColor,
                    onClick = { onItemClick("readiness_text") },
                    modifier = Modifier.weight(1f)
                )

                ContentReadinessItem(
                    iconRes = R.drawable.ic_diag_media,
                    iconColor = Color(0xFF00FF87),
                    title = stringResource(R.string.nd_ready_media_title),
                    statusText = mediaStatus,
                    badgeText = mediaBadge,
                    badgeColor = mediaBadgeColor,
                    onClick = { onItemClick("readiness_media") },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ContentReadinessItem(
                    iconRes = R.drawable.ic_diag_files,
                    iconColor = Color(0xFFB388FF),
                    title = stringResource(R.string.nd_ready_files_title),
                    statusText = filesStatus,
                    badgeText = filesBadge,
                    badgeColor = filesBadgeColor,
                    onClick = { onItemClick("readiness_files") },
                    modifier = Modifier.weight(1f)
                )

                ContentReadinessItem(
                    iconRes = R.drawable.ic_diag_voip,
                    iconColor = Color(0xFF818CF8),
                    title = stringResource(R.string.nd_ready_calls_title),
                    statusText = callsStatus,
                    badgeText = callsBadge,
                    badgeColor = callsBadgeColor,
                    onClick = { onItemClick("readiness_calls") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ContentReadinessItem(
    iconRes: Int,
    iconColor: Color,
    title: String,
    statusText: String,
    badgeText: String,
    badgeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(16.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconColor.copy(alpha = 0.12f))
                        .border(0.8.dp, iconColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .border(0.6.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = statusText,
                    fontSize = 10.5.sp,
                    color = TextMuted,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun HopByHopBottleneckRadar(
    pingMs: Long,
    jitterMs: Long,
    bufferbloatMs: Long,
    successRate: Int,
    lastFailureType: FailureType,
    isProxyActive: Boolean,
    isSocks5: Boolean,
    onInfoClick: () -> Unit,
    onHopClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87)

    // Hop States: 0 = Off, 1 = Ok, 2 = Warn, 3 = Error
    val hop1State = if (isProxyActive) 1 else 0

    val hop2State = when {
        !isProxyActive -> 0
        jitterMs > 45L || bufferbloatMs >= 150L -> 2
        jitterMs > 25L || bufferbloatMs >= 75L -> 2
        else -> 1
    }

    val hop3State = when {
        !isProxyActive -> 0
        lastFailureType == FailureType.DPI_BLOCKED -> 3
        successRate < 75 -> 2
        else -> 1
    }

    val hop4State = when {
        !isProxyActive -> 0
        lastFailureType == FailureType.TLS_HANDSHAKE_FAILED || lastFailureType == FailureType.RATE_LIMITED_429 -> 3
        pingMs > 250L -> 2
        else -> 1
    }

    val hop5State = when {
        !isProxyActive -> 0
        lastFailureType != FailureType.NONE -> 2
        else -> 1
    }

    val (bottleneckTitle, bottleneckColor) = when {
        !isProxyActive -> Pair(stringResource(R.string.nd_proxy_stopped), TextMuted)
        lastFailureType == FailureType.DPI_BLOCKED -> Pair(stringResource(R.string.nd_bn_dpi), Color(0xFFFF0055))
        lastFailureType == FailureType.TLS_HANDSHAKE_FAILED -> Pair(stringResource(R.string.nd_bn_tls), Color(0xFFFF0055))
        lastFailureType == FailureType.RATE_LIMITED_429 -> Pair(stringResource(R.string.nd_bn_429), Color(0xFFFF0055))
        jitterMs > 40L -> Pair(stringResource(R.string.nd_bn_radio, jitterMs), Color(0xFFFFB703))
        bufferbloatMs >= 100L -> Pair(stringResource(R.string.nd_bn_bufferbloat, bufferbloatMs), Color(0xFFFFB703))
        pingMs > 250L -> Pair(stringResource(R.string.nd_bn_rtt, pingMs), Color(0xFFFFB703))
        successRate < 85 -> Pair(stringResource(R.string.nd_bn_loss), Color(0xFFFFB703))
        else -> Pair(stringResource(R.string.nd_bn_clean), Color(0xFF00FF87))
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.nd_radar_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
                InfoButton(onClick = onInfoClick)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bottleneckColor.copy(alpha = 0.12f))
                    .border(0.8.dp, bottleneckColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (isProxyActive) (if (bottleneckColor == Color(0xFF00FF87)) stringResource(R.string.nd_radar_route_ok) else stringResource(R.string.nd_radar_diagnose)) else stringResource(R.string.nd_stopped),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = bottleneckColor
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Transparent)
                .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 1. Horizontal 5-Hop Chain
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    HopNode(
                        label = stringResource(R.string.nd_hop_client),
                        subLabel = if (isSocks5) ":10808" else ":1080",
                        state = hop1State,
                        primaryColor = primaryColor,
                        onClick = { onHopClick("hop_device") }
                    )
                    HopLink(isActive = isProxyActive, isAlert = hop2State >= 2)
                    HopNode(
                        label = stringResource(R.string.nd_hop_tower_wifi),
                        subLabel = if (isProxyActive && jitterMs > 0) "±${jitterMs}ms" else stringResource(R.string.nd_hop_radio),
                        state = hop2State,
                        primaryColor = primaryColor,
                        onClick = { onHopClick("last_mile") }
                    )
                    HopLink(isActive = isProxyActive, isAlert = hop3State >= 2)
                    HopNode(
                        label = stringResource(R.string.nd_hop_operator),
                        subLabel = if (hop3State == 3) "DPI" else "ISP",
                        state = hop3State,
                        primaryColor = primaryColor,
                        onClick = { onHopClick("hop_isp") }
                    )
                    HopLink(isActive = isProxyActive, isAlert = hop4State >= 2)
                    HopNode(
                        label = "Cloudflare",
                        subLabel = if (isProxyActive && pingMs > 0) "${pingMs}ms" else "WSS",
                        state = hop4State,
                        primaryColor = primaryColor,
                        onClick = { onHopClick("cf_edge") }
                    )
                    HopLink(isActive = isProxyActive, isAlert = hop5State >= 2)
                    HopNode(
                        label = "Telegram",
                        subLabel = if (isSocks5) "DC + VoIP" else "DC 1–5",
                        state = hop5State,
                        primaryColor = primaryColor,
                        onClick = { onHopClick("hop_tg_dc") }
                    )
                }

                // 2. Bottleneck Verdict Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(bottleneckColor.copy(alpha = 0.08f))
                        .border(0.8.dp, bottleneckColor.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(bottleneckColor)
                        )
                        Text(
                            text = bottleneckTitle,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isProxyActive) TextWhite else TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HopNode(
    label: String,
    subLabel: String,
    state: Int,
    primaryColor: Color,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val nodeColor = when (state) {
        1 -> Color(0xFF00FF87)
        2 -> Color(0xFFFFB703)
        3 -> Color(0xFFFF0055)
        else -> TextMuted.copy(alpha = 0.35f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(nodeColor.copy(alpha = 0.12f))
                .border(1.dp, nodeColor.copy(alpha = 0.5f), CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(nodeColor)
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            color = TextWhite,
            maxLines = 1
        )
        Text(
            text = subLabel,
            fontSize = 8.sp,
            color = nodeColor,
            maxLines = 1
        )
    }
}

@Composable
private fun HopLink(
    isActive: Boolean,
    isAlert: Boolean
) {
    val lineColor = when {
        !isActive -> Color(0xFF1E2333)
        isAlert -> Color(0xFFFFB703).copy(alpha = 0.5f)
        else -> Color(0xFF00FF87).copy(alpha = 0.35f)
    }
    Box(
        modifier = Modifier
            .width(10.dp)
            .height(1.dp)
            .background(lineColor)
    )
}

@Composable
fun SmartNetworkInsightsCard(
    pingMs: Long,
    jitterMs: Long,
    bufferbloatMs: Long,
    successRate: Int,
    healthScore: Int,
    chatScore: Int,
    mosScore: Double,
    lastFailureType: FailureType,
    isProxyActive: Boolean,
    isSocks5: Boolean,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val (badgeText, insightText, accentColor) = when {
        !isProxyActive -> Triple(
            stringResource(R.string.nd_insight_waiting_title),
            stringResource(R.string.nd_insight_waiting_desc),
            TextMuted
        )
        lastFailureType == FailureType.RATE_LIMITED_429 -> Triple(
            stringResource(R.string.nd_insight_quota_title),
            stringResource(R.string.nd_insight_quota_desc),
            Color(0xFFFF0055)
        )
        lastFailureType == FailureType.DPI_BLOCKED -> Triple(
            stringResource(R.string.nd_insight_dpi_title),
            stringResource(R.string.nd_insight_dpi_desc),
            Color(0xFFFF0055)
        )
        jitterMs > 35L -> Triple(
            stringResource(R.string.nd_insight_jitter_title),
            if (isSocks5) stringResource(R.string.nd_insight_jitter_desc, jitterMs)
            else stringResource(R.string.nd_insight_jitter_desc, jitterMs)
                .replace("Для стабильных голосовых и видеозвонков без прерываний", "Для быстрой и стабильной передачи медиа"),
            Color(0xFFFFB703)
        )
        bufferbloatMs >= 100L -> Triple(
            stringResource(R.string.nd_insight_bufferbloat_title),
            stringResource(R.string.nd_insight_bufferbloat_desc, bufferbloatMs),
            Color(0xFFFFB703)
        )
        pingMs > 250L -> Triple(
            stringResource(R.string.nd_insight_rtt_title),
            stringResource(R.string.nd_insight_rtt_desc, pingMs),
            Color(0xFFFFB703)
        )
        successRate < 85 -> Triple(
            stringResource(R.string.nd_insight_loss_title),
            stringResource(R.string.nd_insight_loss_desc, 100 - successRate),
            Color(0xFFFF0055)
        )
        isSocks5 && mosScore < 3.80 -> Triple(
            stringResource(R.string.nd_insight_calls_title),
            stringResource(R.string.nd_insight_calls_desc, String.format(java.util.Locale.US, "%.2f", mosScore)),
            Color(0xFFFFB703)
        )
        else -> Triple(
            stringResource(R.string.nd_insight_ideal_title),
            if (isSocks5) stringResource(R.string.nd_insight_ideal_desc, healthScore)
            else stringResource(R.string.nd_insight_ideal_desc, healthScore).replace(" и кристально чистым звонкам", ""),
            Color(0xFF00FF87)
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accentColor.copy(alpha = 0.07f))
            .border(1.dp, accentColor.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onInfoClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.15f))
                    .border(0.8.dp, accentColor.copy(alpha = 0.40f), RoundedCornerShape(10.dp))
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_speed_auto),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.nd_insight_title),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = accentColor
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(accentColor.copy(alpha = 0.12f))
                            .border(0.6.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = accentColor,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = insightText,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = if (isProxyActive) TextWhite else TextMuted,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}


