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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.DcMetrics
import com.mirrly.tgproxy.core.MtprotoAnalyticsSnapshot
import com.mirrly.tgproxy.core.TelegramDCAffinityEngine
import com.mirrly.tgproxy.core.TelegramDcInfo
import com.mirrly.tgproxy.service.AnalyticsPeriod
import com.mirrly.tgproxy.service.ChartDataPoint
import com.mirrly.tgproxy.service.PeriodAnalyticsSummary
import com.mirrly.tgproxy.service.WorkerRequestTracker
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs

enum class AnalyticsTab(val label: String) {
    SOCKS5("SOCKS5 (Cloudflare)"),
    MTPROTO("MTProto (DC & CDN)")
}

@Composable
fun WorkerAnalyticsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val isSocks5 by app.prefsManager.isSocks5Flow.collectAsState()

    val activeProtoColor = if (isSocks5) Color(0xFFB388FF) else Color(0xFF00FF87)
    val secondaryProtoColor = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00F5D4)
    val activeWorker = remember(app.prefsManager.getActiveWorkerId()) { app.prefsManager.getActiveWorker() }

    var selectedTab by remember { mutableStateOf(if (isSocks5) AnalyticsTab.SOCKS5 else AnalyticsTab.MTPROTO) }
    var selectedPeriod by remember { mutableStateOf(AnalyticsPeriod.HOUR_24) }
    val updateTick by WorkerRequestTracker.trackerUpdateEvent.collectAsState()

    var summary by remember(selectedPeriod, updateTick) {
        mutableStateOf(WorkerRequestTracker.getAnalytics(selectedPeriod))
    }

    var mtprotoSnapshot by remember {
        mutableStateOf(
            app.proxyServer.stats.dcAffinityEngine.getMtprotoSnapshot(
                totalPoolSize = app.proxyServer.config.mtprotoStandbyPerActiveSlot,
                totalWsConnections = app.proxyServer.stats.totalWsConnections.get()
            )
        )
    }

    LaunchedEffect(selectedPeriod) {
        while (isActive) {
            summary = WorkerRequestTracker.getAnalytics(selectedPeriod)
            mtprotoSnapshot = app.proxyServer.stats.dcAffinityEngine.getMtprotoSnapshot(
                totalPoolSize = app.proxyServer.config.mtprotoStandbyPerActiveSlot,
                totalWsConnections = app.proxyServer.stats.totalWsConnections.get()
            )
            delay(1000)
        }
    }

    LaunchedEffect(isSocks5) {
        selectedTab = if (isSocks5) AnalyticsTab.SOCKS5 else AnalyticsTab.MTPROTO
    }

    var infoKey by remember { mutableStateOf<String?>(null) }

    val infoData = remember {
        mapOf(
            "cf_limits" to Pair(R.string.wa_info_cf_limits_title, R.string.wa_info_cf_limits_desc),
            "zero_cost" to Pair(R.string.wa_info_zero_cost_title, R.string.wa_info_zero_cost_desc),
            "wss_traffic" to Pair(R.string.wa_info_wss_traffic_title, R.string.wa_info_wss_traffic_desc),
            "probes" to Pair(R.string.wa_info_probes_title, R.string.wa_info_probes_desc),
            "burn_rate" to Pair(R.string.wa_info_burn_rate_title, R.string.wa_info_burn_rate_desc),
            "reset_timer" to Pair(R.string.wa_info_reset_timer_title, R.string.wa_info_reset_timer_desc),
            "mtproto_dc_traffic" to Pair(R.string.wa_info_mtproto_dc_traffic_title, R.string.wa_info_mtproto_dc_traffic_desc),
            "mtproto_cdn" to Pair(R.string.wa_info_mtproto_cdn_title, R.string.wa_info_mtproto_cdn_desc),
            "mtproto_affinity" to Pair(R.string.wa_info_mtproto_affinity_title, R.string.wa_info_mtproto_affinity_desc),
            "mtproto_dialects" to Pair(R.string.wa_info_mtproto_dialects_title, R.string.wa_info_mtproto_dialects_desc),
            "mtproto_multiplexing" to Pair(R.string.wa_info_mtproto_multiplexing_title, R.string.wa_info_mtproto_multiplexing_desc),
            "mtproto_entropy" to Pair(R.string.wa_info_mtproto_entropy_title, R.string.wa_info_mtproto_entropy_desc)
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
                .fadingEdges(topFadeHeight = 20.dp, bottomFadeHeight = 36.dp)
                .verticalScroll(rememberScrollState())
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 58.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
                )
                .adaptiveContentPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── TAB SELECTOR (SOCKS5 VS MTPROTO) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 0)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AnalyticsTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    val tabColor = if (tab == AnalyticsTab.SOCKS5) Color(0xFF818CF8) else Color(0xFF00FF87)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isSelected) tabColor.copy(alpha = 0.16f) else Color.Transparent)
                            .border(
                                width = if (isSelected) 0.8.dp else 0.dp,
                                color = if (isSelected) tabColor.copy(alpha = 0.5f) else Color.Transparent,
                                shape = RoundedCornerShape(9.dp)
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTab = tab
                            }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.label,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                            color = if (isSelected) tabColor else TextMuted
                        )
                    }
                }
            }

            if (selectedTab == AnalyticsTab.SOCKS5) {
                // ── SOCKS5 / CLOUDFLARE ANALYTICS ──

                // ── 1. HERO CARD: QUOTA AND TOTAL REQUESTS ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggeredEntrance(index = 1)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Transparent)
                        .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.wa_quota_title),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.1.sp,
                                    color = TextMuted
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.Bottom) {
                                    RollingNumberText(
                                        text = "${summary.totalRequests}",
                                        color = TextWhite,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 24.sp
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.wa_unit_requests),
                                        color = activeProtoColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(activeProtoColor.copy(alpha = 0.12f))
                                    .border(0.6.dp, activeProtoColor.copy(alpha = 0.40f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.wa_quota_percent_limit, String.format(Locale.US, "%.1f", summary.dailyQuotaPercentage)),
                                    color = activeProtoColor,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Linear Quota Progress Bar
                        val animatedProgress by animateFloatAsState(
                            targetValue = (summary.dailyQuotaPercentage / 100f).coerceIn(0.005f, 1f),
                            animationSpec = tween(800, easing = FastOutSlowInEasing),
                            label = "quotaProgress"
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(animatedProgress)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    activeProtoColor,
                                                    activeProtoColor.copy(alpha = 0.75f)
                                                )
                                            )
                                        )
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.wa_stat_0_req),
                                    fontSize = 9.5.sp,
                                    color = TextMuted
                                )
                                Text(
                                    text = stringResource(R.string.wa_quota_limit_day),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextMuted
                                )
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

                        // Active Worker info & Zero Cost hint
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(activeProtoColor)
                                )
                                Text(
                                    text = if (activeWorker.isDeveloperWorker) activeWorker.name else "${activeWorker.name} (${activeWorker.domain})",
                                    fontSize = 11.sp,
                                    color = TextWhite,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            InfoButton { infoKey = "cf_limits" }
                        }
                    }
                }

                // ── 2. PERIOD SELECTOR CHIPS ──
                Column(
                    modifier = Modifier.staggeredEntrance(index = 2),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.wa_period_title),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = TextMuted
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalFadingEdges(startFadeWidth = 10.dp, endFadeWidth = 20.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AnalyticsPeriod.values().forEach { period ->
                                val isSelected = selectedPeriod == period
                                val chipBg by animateColorAsState(
                                    targetValue = if (isSelected) activeProtoColor.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.03f),
                                    label = "chipBg"
                                )
                                val chipBorder by animateColorAsState(
                                    targetValue = if (isSelected) activeProtoColor.copy(alpha = 0.60f) else Color.White.copy(alpha = 0.08f),
                                    label = "chipBorder"
                                )
                                val chipText by animateColorAsState(
                                    targetValue = if (isSelected) TextWhite else TextMuted,
                                    label = "chipText"
                                )

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(chipBg)
                                        .border(0.75.dp, chipBorder, RoundedCornerShape(8.dp))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            selectedPeriod = period
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = stringResource(period.labelRes),
                                        color = chipText,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 3. INTERACTIVE BEZIER TIMELINE CHART ──
                Column(
                    modifier = Modifier.staggeredEntrance(index = 3),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.wa_timeline_title),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = TextMuted
                        )
                        InfoButton { infoKey = "zero_cost" }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Transparent)
                            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                            .padding(12.dp)
                    ) {
                        RequestTimelineChart(
                            points = summary.chartPoints,
                            primaryColor = activeProtoColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(145.dp)
                        )
                    }
                }

                // ── 4. DETAILED BREAKDOWN METRICS GRID ──
                Column(
                    modifier = Modifier.staggeredEntrance(index = 4),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.wa_details_title),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = TextMuted
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Transparent)
                            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    ) {
                        Column {
                            DiagnosticMetricRow(
                                iconRes = R.drawable.ic_diag_protocol,
                                iconColor = activeProtoColor,
                                title = stringResource(R.string.wa_stat_wss_title),
                                value = stringResource(R.string.wa_stat_wss_sessions, summary.wssRequests),
                                badgeText = stringResource(R.string.wa_badge_wss_ratio),
                                badgeColor = activeProtoColor,
                                onInfoClick = { infoKey = "wss_traffic" }
                            )

                            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

                            DiagnosticMetricRow(
                                iconRes = R.drawable.ic_diag_rtt,
                                iconColor = secondaryProtoColor,
                                title = stringResource(R.string.wa_stat_probes_title),
                                value = stringResource(R.string.wa_stat_probes_checks, summary.probeRequests),
                                badgeText = stringResource(R.string.wa_badge_failover),
                                badgeColor = secondaryProtoColor,
                                onInfoClick = { infoKey = "probes" }
                            )

                            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

                            DiagnosticMetricRow(
                                iconRes = R.drawable.ic_diag_jitter,
                                iconColor = activeProtoColor,
                                title = stringResource(R.string.wa_stat_burn_rate_title),
                                value = stringResource(R.string.wa_stat_burn_rate_val, String.format(Locale.US, "%.1f", summary.burnRatePerHour)),
                                badgeText = stringResource(R.string.wa_badge_burn_rate),
                                badgeColor = activeProtoColor,
                                onInfoClick = { infoKey = "burn_rate" }
                            )

                            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

                            DiagnosticMetricRow(
                                iconRes = R.drawable.ic_diag_delivery,
                                iconColor = secondaryProtoColor,
                                title = stringResource(R.string.wa_stat_reset_title),
                                value = stringResource(R.string.wa_stat_reset_val, summary.hoursUntilReset, summary.minutesUntilReset),
                                badgeText = "00:00 UTC",
                                badgeColor = secondaryProtoColor,
                                onInfoClick = { infoKey = "reset_timer" }
                            )
                        }
                    }
                }
            } else {
                // ── MTPROTO / TELEGRAM DC & CDN ANALYTICS ──

                MtprotoDcTrafficDistributionCard(
                    snapshot = mtprotoSnapshot,
                    primaryColor = activeProtoColor,
                    secondaryColor = secondaryProtoColor,
                    onInfoClick = { infoKey = "mtproto_dc_traffic" }
                )

                MtprotoCdnFlowSealCard(
                    snapshot = mtprotoSnapshot,
                    primaryColor = activeProtoColor,
                    secondaryColor = secondaryProtoColor,
                    onInfoClick = { infoKey = "mtproto_cdn" }
                )

                MtprotoDcAffinityMatrixCard(
                    snapshot = mtprotoSnapshot,
                    primaryColor = activeProtoColor,
                    secondaryColor = secondaryProtoColor,
                    onInfoClick = { infoKey = "mtproto_affinity" }
                )

                MtprotoPoolEntropyAndDecayCard(
                    snapshot = mtprotoSnapshot,
                    primaryColor = activeProtoColor,
                    secondaryColor = secondaryProtoColor,
                    onInfoClick = { infoKey = "mtproto_entropy" }
                )

                MtprotoTransportDialectsSplitCard(
                    snapshot = mtprotoSnapshot,
                    primaryColor = activeProtoColor,
                    secondaryColor = secondaryProtoColor,
                    onInfoClick = { infoKey = "mtproto_dialects" }
                )

                MtprotoWssMultiplexingCard(
                    snapshot = mtprotoSnapshot,
                    primaryColor = activeProtoColor,
                    secondaryColor = secondaryProtoColor,
                    onInfoClick = { infoKey = "mtproto_multiplexing" }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }

        // Top Bar
        AnalyticsTopBar(
            isSocks5 = isSocks5,
            onBack = onBack
        )

        // Floating Cyber Particles Overlay
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 10,
            alphaMultiplier = 0.40f
        )

        // Info Dialog
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

/**
 * Interactive Canvas chart with smooth Bezier interpolation and touch scrubber.
 */
@Composable
private fun RequestTimelineChart(
    points: List<ChartDataPoint>,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val bgFillColor = remember(primaryColor) { primaryColor.copy(alpha = 0.22f) }
    val path = remember { Path() }
    val areaPath = remember { Path() }

    Box(
        modifier = modifier
            .pointerInput(points) {
                detectTapGestures(
                    onPress = { offset ->
                        val idx = findClosestPointIndex(offset.x, size.width.toFloat(), points.size)
                        if (idx != selectedIndex) {
                            selectedIndex = idx
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
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
                        if (idx != selectedIndex && idx in points.indices) {
                            selectedIndex = idx
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val bottomPadding = 24.dp.toPx()
            val chartHeight = height - bottomPadding

            if (points.isEmpty()) return@Canvas

            val maxVal = points.maxOfOrNull { it.totalCount }?.coerceAtLeast(10) ?: 10
            val minVal = 0

            // 1. Grid lines (3 horizontal reference lines)
            val gridColor = Color(0xFF1E2333).copy(alpha = 0.7f)
            for (g in 0..2) {
                val y = chartHeight * (g / 2f)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // 2. Compute point coordinates
            val coords = points.mapIndexed { i, p ->
                val x = if (points.size > 1) {
                    (i.toFloat() / (points.size - 1)) * width
                } else {
                    width / 2f
                }
                val fraction = (p.totalCount.toFloat() / maxVal).coerceIn(0f, 1f)
                val y = chartHeight - (fraction * (chartHeight - 12.dp.toPx())) - 6.dp.toPx()
                Offset(x, y)
            }

            // 3. Draw Smooth Cubic Bezier Line & Area
            if (coords.isNotEmpty()) {
                path.reset()
                areaPath.reset()

                path.moveTo(coords[0].x, coords[0].y)
                areaPath.moveTo(coords[0].x, chartHeight)
                areaPath.lineTo(coords[0].x, coords[0].y)

                for (i in 0 until coords.size - 1) {
                    val p0 = coords[i]
                    val p1 = coords[i + 1]
                    val cx = (p0.x + p1.x) / 2f

                    path.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                    areaPath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                }

                areaPath.lineTo(coords.last().x, chartHeight)
                areaPath.close()

                // Draw Gradient Area Fill
                drawPath(
                    path = areaPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            bgFillColor,
                            primaryColor.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = chartHeight
                    )
                )

                // Draw Neon Stroke Line
                drawPath(
                    path = path,
                    color = primaryColor,
                    style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Draw Point Dots
                coords.forEachIndexed { idx, pt ->
                    val isHovered = selectedIndex == idx
                    drawCircle(
                        color = if (isHovered) Color.White else primaryColor,
                        radius = if (isHovered) 5.5.dp.toPx() else 3.dp.toPx(),
                        center = pt
                    )
                    if (isHovered) {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.45f),
                            radius = 9.dp.toPx(),
                            center = pt
                        )
                    }
                }
            }

            // 4. Draw Selected Scrubber Vertical Guideline
            selectedIndex?.let { idx ->
                if (idx in coords.indices) {
                    val selCoord = coords[idx]
                    drawLine(
                        color = Color.White.copy(alpha = 0.40f),
                        start = Offset(selCoord.x, 0f),
                        end = Offset(selCoord.x, chartHeight),
                        strokeWidth = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                    )
                }
            }
        }

        // Floating Tooltip when scrubbed
        selectedIndex?.let { idx ->
            if (idx in points.indices) {
                val pt = points[idx]
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF090D16).copy(alpha = 0.94f))
                        .border(1.dp, primaryColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.wa_chart_tooltip, pt.timeLabel, pt.totalCount, pt.wssCount, pt.probeCount),
                        color = TextWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

private fun findClosestPointIndex(touchX: Float, totalWidth: Float, pointCount: Int): Int {
    if (pointCount <= 1 || totalWidth <= 0f) return 0
    val fraction = (touchX / totalWidth).coerceIn(0f, 1f)
    return (fraction * (pointCount - 1)).toInt().coerceIn(0, pointCount - 1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalyticsTopBar(
    isSocks5: Boolean,
    onBack: () -> Unit
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
                    text = stringResource(R.string.wa_title),
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
                        text = stringResource(R.string.wa_chart_100k_day),
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
private fun formatTrafficBytes(bytes: Long): String {
    if (bytes <= 0) return stringResource(R.string.wa_traffic_b, 0)
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> stringResource(R.string.wa_traffic_gb, String.format(Locale.US, "%.2f", gb))
        mb >= 1.0 -> stringResource(R.string.wa_traffic_mb, String.format(Locale.US, "%.1f", mb))
        kb >= 1.0 -> stringResource(R.string.wa_traffic_kb, String.format(Locale.US, "%.1f", kb))
        else -> stringResource(R.string.wa_traffic_b, bytes)
    }
}

@Composable
fun DcTrafficDonutChart(
    metrics: List<DcMetrics>,
    totalBytes: Long,
    selectedDcId: Int?,
    onSelectDc: (Int?) -> Unit,
    primaryColor: Color = Color(0xFF00FF87),
    secondaryColor: Color = Color(0xFF00F5D4),
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val dcColors = remember(primaryColor, secondaryColor) {
        mapOf(
            2 to primaryColor,                      // DC2 Main chats
            4 to secondaryColor,                    // DC4 Media
            100 to Color(0xFF00B4D8),               // CDN FlowSeal (Deep Teal)
            1 to Color.White.copy(alpha = 0.85f),  // DC1 Miami
            3 to Color.White.copy(alpha = 0.60f),  // DC3 Miami
            5 to Color.White.copy(alpha = 0.40f)   // DC5 Singapore
        )
    }

    val displayMetrics = if (metrics.any { it.totalBytes > 0 }) {
        metrics.filter { it.totalBytes > 0 }
    } else {
        listOf(
            DcMetrics(2, TelegramDCAffinityEngine.getDcInfo(2), 6500, 1000, 2, System.currentTimeMillis(), 5.0, 2),
            DcMetrics(4, TelegramDCAffinityEngine.getDcInfo(4), 2500, 500, 1, System.currentTimeMillis(), 3.0, 1),
            DcMetrics(100, TelegramDCAffinityEngine.getDcInfo(100), 1000, 0, 1, System.currentTimeMillis(), 2.0, 1)
        )
    }

    val displayTotal = displayMetrics.sumOf { it.totalBytes }.coerceAtLeast(1L).toFloat()

    val slices = remember(displayMetrics, displayTotal) {
        var currentAngle = -90f
        val gap = if (displayMetrics.size > 1) 3.5f else 0f
        val totalGaps = gap * displayMetrics.size
        val availableDegrees = 360f - totalGaps

        displayMetrics.map { m ->
            val fraction = (m.totalBytes.toFloat() / displayTotal).coerceIn(0.02f, 1f)
            val sweep = fraction * availableDegrees
            val start = currentAngle
            currentAngle += sweep + gap
            Triple(m, start, sweep)
        }
    }

    val selectedMetric = displayMetrics.find { it.dcId == selectedDcId }

    Box(
        modifier = modifier
            .size(155.dp)
            .pointerInput(slices, selectedDcId) {
                detectTapGestures { offset ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val dx = offset.x - center.x
                    val dy = offset.y - center.y
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    val minRadius = (size.width / 2f) - 34.dp.toPx()
                    val maxRadius = (size.width / 2f) + 8.dp.toPx()

                    if (dist in minRadius..maxRadius) {
                        var angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        if (angle < -90f) angle += 360f

                        val tapped = slices.find { (_, start, sweep) ->
                            angle in start..(start + sweep)
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (tapped != null) {
                            if (selectedDcId == tapped.first.dcId) {
                                onSelectDc(null)
                            } else {
                                onSelectDc(tapped.first.dcId)
                            }
                        } else {
                            onSelectDc(null)
                        }
                    } else if (dist < minRadius) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectDc(null)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 13.dp.toPx()
            val selectedStrokeWidth = 18.dp.toPx()
            val chartRadius = (size.minDimension - selectedStrokeWidth) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            slices.forEach { (metric, startAngle, sweepAngle) ->
                val baseColor = dcColors[metric.dcId] ?: primaryColor
                val isSelected = selectedDcId == metric.dcId
                val isAnySelected = selectedDcId != null

                val sliceColor = if (isSelected) {
                    baseColor
                } else if (isAnySelected) {
                    baseColor.copy(alpha = 0.28f)
                } else {
                    baseColor
                }

                val currentStroke = if (isSelected) selectedStrokeWidth else strokeWidth

                drawArc(
                    color = sliceColor,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = Offset(center.x - chartRadius, center.y - chartRadius),
                    size = androidx.compose.ui.geometry.Size(chartRadius * 2, chartRadius * 2),
                    style = Stroke(width = currentStroke, cap = StrokeCap.Round)
                )
            }
        }

        // Center Content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(12.dp)
        ) {
            if (selectedMetric != null) {
                val col = dcColors[selectedMetric.dcId] ?: primaryColor
                Text(
                    text = selectedMetric.info.name,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black,
                    color = col,
                    maxLines = 1
                )
                Text(
                    text = formatTrafficBytes(selectedMetric.totalBytes),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Black,
                    color = TextWhite
                )
                val pct = (selectedMetric.totalBytes.toFloat() / displayTotal) * 100f
                Text(
                    text = "${String.format(Locale.US, "%.1f", pct)}%",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = col
                )
            } else {
                Text(
                    text = stringResource(R.string.wa_dc_all),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = TextMuted
                )
                Text(
                    text = formatTrafficBytes(totalBytes),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Black,
                    color = TextWhite
                )
                Text(
                    text = "100%",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )
            }
        }
    }
}

@Composable
fun SelectedDcInspectorBanner(
    metric: DcMetrics,
    totalBytes: Long,
    onClose: () -> Unit,
    primaryColor: Color = Color(0xFF00FF87),
    secondaryColor: Color = Color(0xFF00F5D4),
    modifier: Modifier = Modifier
) {
    val dcColors = remember(primaryColor, secondaryColor) {
        mapOf(
            2 to primaryColor,
            4 to secondaryColor,
            100 to Color(0xFF00B4D8),
            1 to Color.White.copy(alpha = 0.85f),
            3 to Color.White.copy(alpha = 0.60f),
            5 to Color.White.copy(alpha = 0.40f)
        )
    }
    val col = dcColors[metric.dcId] ?: primaryColor
    val pct = if (totalBytes > 0) (metric.totalBytes.toFloat() / totalBytes.toFloat()) * 100f else 0f
    val rxPct = if (metric.totalBytes > 0) (metric.bytesReceived.toFloat() / metric.totalBytes.toFloat()) * 100f else 80f
    val txPct = (100f - rxPct).coerceAtLeast(0f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(0.75.dp, col.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(col)
                    )
                    Text(
                        text = "${metric.info.name} • ${metric.info.location}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        color = TextWhite
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(col.copy(alpha = 0.12f))
                        .border(0.6.dp, col.copy(alpha = 0.4f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.wa_dc_pct_total, String.format(Locale.US, "%.1f", pct)),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = col
                    )
                }
            }

            Text(
                text = stringResource(R.string.wa_dc_role_node, metric.info.role, metric.info.defaultIp),
                fontSize = 10.sp,
                color = TextMuted
            )

            // RX / TX Visual Breakdown
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(rxPct.coerceAtLeast(1f))
                            .background(col)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(txPct.coerceAtLeast(1f))
                            .background(secondaryColor)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.wa_dc_rx, formatTrafficBytes(metric.bytesReceived), String.format(Locale.US, "%.0f", rxPct)),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = col
                    )
                    Text(
                        text = stringResource(R.string.wa_dc_tx, formatTrafficBytes(metric.bytesSent), String.format(Locale.US, "%.0f", txPct)),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = secondaryColor
                    )
                }
            }

            // Sockets & Activity Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.wa_dc_sockets_weight, metric.allocatedSockets, String.format(Locale.US, "%.2f", metric.weight)),
                    fontSize = 9.5.sp,
                    color = TextMuted
                )
                Text(
                    text = stringResource(R.string.wa_dc_deselect),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    modifier = Modifier
                        .clickable { onClose() }
                        .padding(2.dp)
                )
            }
        }
    }
}

@Composable
fun MtprotoDcTrafficDistributionCard(
    snapshot: MtprotoAnalyticsSnapshot,
    onInfoClick: () -> Unit,
    primaryColor: Color = Color(0xFF00FF87),
    secondaryColor: Color = Color(0xFF00F5D4),
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var selectedDcId by remember { mutableStateOf<Int?>(null) }

    val dcColors = remember(primaryColor, secondaryColor) {
        mapOf(
            2 to primaryColor,                      // DC2 Chats
            4 to secondaryColor,                    // DC4 Media
            100 to Color(0xFF00B4D8),               // CDN FlowSeal
            1 to Color.White.copy(alpha = 0.85f),  // DC1 Miami
            3 to Color.White.copy(alpha = 0.60f),  // DC3 Miami
            5 to Color.White.copy(alpha = 0.40f)   // DC5 Singapore
        )
    }

    val dcGeoBadges = mapOf(
        2 to "AMS",
        4 to "AMS",
        100 to "CDN",
        1 to "MIA",
        3 to "MIA",
        5 to "SIN"
    )

    val selectedMetric = snapshot.dcMetrics.find { it.dcId == selectedDcId }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .staggeredEntrance(index = 1)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.wa_dc_traffic_map_title),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.1.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        RollingNumberText(
                            text = formatTrafficBytes(snapshot.totalBytes),
                            color = TextWhite,
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.wa_dc_total_label),
                            color = primaryColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }

                InfoButton(onClick = onInfoClick)
            }

            // Radial Donut Chart
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                DcTrafficDonutChart(
                    metrics = snapshot.dcMetrics,
                    totalBytes = snapshot.totalBytes,
                    selectedDcId = selectedDcId,
                    onSelectDc = { selectedDcId = it },
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor
                )
            }

            // Selected DC Detailed Inspector Banner
            if (selectedMetric != null) {
                SelectedDcInspectorBanner(
                    metric = selectedMetric,
                    totalBytes = snapshot.totalBytes,
                    onClose = { selectedDcId = null },
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor
                )
            }

            // Regional Quick Summary Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val total = snapshot.totalBytes.coerceAtLeast(1L).toFloat()
                val europeBytes = snapshot.dcMetrics.filter { it.dcId == 2 || it.dcId == 4 }.sumOf { it.totalBytes }
                val cdnBytes = snapshot.dcMetrics.find { it.dcId == 100 }?.totalBytes ?: snapshot.cdnBytes
                val americasBytes = snapshot.dcMetrics.filter { it.dcId == 1 || it.dcId == 3 }.sumOf { it.totalBytes }
                val asiaBytes = snapshot.dcMetrics.find { it.dcId == 5 }?.totalBytes ?: 0L

                val europePct = if (snapshot.totalBytes > 0) (europeBytes.toFloat() / total) * 100f else 65f
                val cdnPct = if (snapshot.totalBytes > 0) (cdnBytes.toFloat() / total) * 100f else 25f
                val americasPct = if (snapshot.totalBytes > 0) (americasBytes.toFloat() / total) * 100f else 8f
                val asiaPct = if (snapshot.totalBytes > 0) (asiaBytes.toFloat() / total) * 100f else 2f

                RegionalChip(title = stringResource(R.string.wa_region_europe), pct = europePct, color = primaryColor, modifier = Modifier.weight(1f))
                RegionalChip(title = "CDN", pct = cdnPct, color = Color(0xFF00B4D8), modifier = Modifier.weight(1f))
                RegionalChip(title = stringResource(R.string.wa_region_americas), pct = americasPct, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
                RegionalChip(title = stringResource(R.string.wa_region_asia), pct = asiaPct, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.weight(1f))
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // All DCs Interactive List
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.wa_dc_clusters_title),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = TextMuted
                )

                snapshot.dcMetrics.forEach { m ->
                    val isSelected = selectedDcId == m.dcId
                    val col = dcColors[m.dcId] ?: primaryColor
                    val geoBadge = dcGeoBadges[m.dcId] ?: "DC"
                    val pct = if (snapshot.totalBytes > 0) {
                        (m.totalBytes.toFloat() / snapshot.totalBytes.toFloat()) * 100f
                    } else if (m.dcId == 2) 65f else if (m.dcId == 4) 25f else if (m.dcId == 100) 10f else 0f

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) col.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.02f))
                            .border(
                                width = if (isSelected) 0.8.dp else 0.6.dp,
                                color = if (isSelected) col.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.06f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedDcId = if (selectedDcId == m.dcId) null else m.dcId
                            }
                            .padding(8.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(col.copy(alpha = 0.15f))
                                            .border(0.6.dp, col.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = geoBadge,
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Black,
                                            color = col
                                        )
                                    }

                                    Column {
                                        Text(
                                            text = m.info.name,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextWhite
                                        )
                                        Text(
                                            text = m.info.role,
                                            fontSize = 9.5.sp,
                                            color = TextMuted
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = formatTrafficBytes(m.totalBytes),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", pct)}%",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = col
                                    )
                                }
                            }

                            // Subnet & Sockets mini info
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "IP: ${m.info.defaultIp}",
                                    fontSize = 9.sp,
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = stringResource(R.string.wa_dc_sockets_weight, m.allocatedSockets, String.format(Locale.US, "%.2f", m.weight)),
                                    fontSize = 9.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionalChip(
    title: String,
    pct: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = "${String.format(Locale.US, "%.0f", pct)}%",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = color
            )
        }
    }
}

@Composable
fun MtprotoCdnFlowSealCard(
    snapshot: MtprotoAnalyticsSnapshot,
    onInfoClick: () -> Unit,
    primaryColor: Color = Color(0xFF00FF87),
    secondaryColor: Color = Color(0xFF00F5D4),
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var selectedNodeIndex by remember { mutableStateOf<Int?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .staggeredEntrance(index = 2)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(primaryColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_diag_files),
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.wa_cdn_title),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.1.sp,
                            color = TextMuted
                        )
                        Text(
                            text = stringResource(R.string.wa_cdn_subtitle),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }
                }

                InfoButton(onClick = onInfoClick)
            }

            // Hero Cache Hit Split
            val cdnPct = snapshot.cdnPercentage.coerceIn(0f, 100f)
            val dc4Pct = (100f - cdnPct).coerceAtLeast(0f)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.wa_cdn_cache_efficiency),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "${String.format(Locale.US, "%.1f", cdnPct)}%",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                color = primaryColor
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.wa_cache_hit_rate),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMuted,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(primaryColor.copy(alpha = 0.12f))
                            .border(0.6.dp, primaryColor.copy(alpha = 0.4f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wa_cdn_dc4_offload),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor
                        )
                    }
                }

                // Dual progress bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(cdnPct.coerceAtLeast(2f))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(primaryColor, secondaryColor)
                                )
                            )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(dc4Pct.coerceAtLeast(2f))
                            .background(Color.White.copy(alpha = 0.15f))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "CDN FlowSeal: ${formatTrafficBytes(snapshot.cdnBytes)}",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor
                    )
                    Text(
                        text = "DC4: ${formatTrafficBytes(snapshot.savedDc4Bytes)}",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = secondaryColor
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // 4-Card Performance Grid
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CdnMetricBox(
                        title = stringResource(R.string.wa_cdn_dc4_saved_title),
                        value = formatTrafficBytes(snapshot.savedDc4Bytes),
                        subtext = stringResource(R.string.wa_cdn_amsterdam_traffic),
                        color = secondaryColor,
                        modifier = Modifier.weight(1f)
                    )
                    CdnMetricBox(
                        title = stringResource(R.string.wa_cdn_media_accel_title),
                        value = stringResource(R.string.wa_cdn_speed_boost),
                        subtext = stringResource(R.string.wa_cdn_edge_delivery),
                        color = primaryColor,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CdnMetricBox(
                        title = stringResource(R.string.wa_cdn_edge_cluster_title),
                        value = stringResource(R.string.wa_cdn_nodes_count),
                        subtext = stringResource(R.string.wa_cdn_flowseal_network),
                        color = primaryColor,
                        modifier = Modifier.weight(1f)
                    )
                    CdnMetricBox(
                        title = stringResource(R.string.wa_cdn_quota_saving_title),
                        value = "Zero-Cost WSS",
                        subtext = stringResource(R.string.wa_cdn_quota_subtext),
                        color = secondaryColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // Interactive 20-Node Edge Grid (4 rows of 5 nodes)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.wa_cdn_topology_title),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = TextMuted
                    )
                    Text(
                        text = stringResource(R.string.wa_100_online),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                }

                // Grid 5x4
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0..3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (col in 0..4) {
                                val nodeIdx = row * 5 + col + 1
                                val isSelected = selectedNodeIndex == nodeIdx
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isSelected) primaryColor.copy(alpha = 0.20f)
                                            else Color.White.copy(alpha = 0.03f)
                                        )
                                        .border(
                                            width = if (isSelected) 0.8.dp else 0.6.dp,
                                            color = if (isSelected) primaryColor.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.07f),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            selectedNodeIndex = if (selectedNodeIndex == nodeIdx) null else nodeIdx
                                        }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(4.5.dp)
                                                .clip(CircleShape)
                                                .background(if (isSelected) primaryColor else primaryColor.copy(alpha = 0.7f))
                                        )
                                        Text(
                                            text = "#${String.format(Locale.US, "%02d", nodeIdx)}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) primaryColor else TextWhite
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Micro Inspector banner for selected node
                if (selectedNodeIndex != null) {
                    val idx = selectedNodeIndex!!
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(0.7.dp, primaryColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.wa_cdn_node_item, String.format(Locale.US, "%02d", idx)),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor
                                )
                                Text(
                                    text = stringResource(R.string.wa_cdn_node_item_details),
                                    fontSize = 9.sp,
                                    color = TextMuted
                                )
                            }
                            Text(
                                text = stringResource(R.string.wa_close),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                modifier = Modifier
                                    .clickable { selectedNodeIndex = null }
                                    .padding(2.dp)
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // Content Cache Breakdown
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.wa_cdn_categories_title),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = TextMuted
                )

                CdnContentRow(
                    title = stringResource(R.string.wa_cdn_cat_video_title),
                    shareText = stringResource(R.string.wa_cdn_cat_video_share),
                    pct = 0.65f,
                    color = primaryColor
                )
                CdnContentRow(
                    title = stringResource(R.string.wa_cdn_cat_audio_title),
                    shareText = stringResource(R.string.wa_cdn_cat_audio_share),
                    pct = 0.20f,
                    color = secondaryColor
                )
                CdnContentRow(
                    title = stringResource(R.string.wa_cdn_cat_docs_title),
                    shareText = stringResource(R.string.wa_cdn_cat_docs_share),
                    pct = 0.15f,
                    color = Color.White.copy(alpha = 0.60f)
                )
            }
        }
    }
}

@Composable
private fun CdnMetricBox(
    title: String,
    value: String,
    subtext: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                maxLines = 1
            )
            Text(
                text = value,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Black,
                color = color,
                maxLines = 1
            )
            Text(
                text = subtext,
                fontSize = 8.5.sp,
                color = TextMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CdnContentRow(
    title: String,
    shareText: String,
    pct: Float,
    color: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextWhite
            )
            Text(
                text = shareText,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.05f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(pct)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun MtprotoDcAffinityMatrixCard(
    snapshot: MtprotoAnalyticsSnapshot,
    onInfoClick: () -> Unit,
    primaryColor: Color = Color(0xFF00FF87),
    secondaryColor: Color = Color(0xFF00F5D4),
    modifier: Modifier = Modifier
) {
    val dcColors = remember(primaryColor, secondaryColor) {
        mapOf(
            2 to primaryColor,                      // DC2 Chats
            4 to secondaryColor,                    // DC4 Media
            100 to Color(0xFF00B4D8),               // CDN FlowSeal
            1 to Color.White.copy(alpha = 0.85f),  // DC1 Miami
            3 to Color.White.copy(alpha = 0.60f),  // DC3 Miami
            5 to Color.White.copy(alpha = 0.40f)   // DC5 Singapore
        )
    }

    val totalSockets = snapshot.dcMetrics.sumOf { it.allocatedSockets }.coerceAtLeast(1)
    val maxWeight = snapshot.dcMetrics.maxOfOrNull { it.weight }?.coerceAtLeast(1.0) ?: 1.0

    Box(
        modifier = modifier
            .fillMaxWidth()
            .staggeredEntrance(index = 3)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(primaryColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_speed_auto),
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.wa_pool_balancer_title),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.1.sp,
                            color = TextMuted
                        )
                        Text(
                            text = "DC-Affinity • ${snapshot.primaryDcName}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }
                }

                InfoButton(onClick = onInfoClick)
            }

            // Socket Pool Allocation Multi-Segment Bar
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.wa_pool_distribution_title),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "$totalSockets",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                color = primaryColor
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.wa_pool_sockets_active),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMuted,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(primaryColor.copy(alpha = 0.12f))
                            .border(0.6.dp, primaryColor.copy(alpha = 0.4f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wa_pool_entropy_val, String.format(Locale.US, "%.2f", snapshot.dcAffinityEntropy)),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor
                        )
                    }
                }

                // Sockets bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                ) {
                    val activeMetrics = snapshot.dcMetrics.filter { it.allocatedSockets > 0 }
                    if (activeMetrics.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f)
                                .background(primaryColor)
                        )
                    } else {
                        activeMetrics.forEach { m ->
                            val col = dcColors[m.dcId] ?: primaryColor
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(m.allocatedSockets.toFloat())
                                    .background(col)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val activeCount = snapshot.dcMetrics.count { it.allocatedSockets > 0 }
                    Text(
                        text = stringResource(R.string.wa_pool_active_dcs, activeCount),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted
                    )
                    Text(
                        text = if (snapshot.dcAffinityEntropy < 0.6) stringResource(R.string.wa_pool_focus, snapshot.primaryDcName) else stringResource(R.string.wa_pool_balanced_load),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // 3-Card Algorithmic Parameters Matrix
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AffinityParamBox(
                    title = stringResource(R.string.wa_pool_load_focus_title),
                    value = snapshot.primaryDcName,
                    subtext = stringResource(R.string.wa_pool_dominant_dc),
                    color = primaryColor,
                    modifier = Modifier.weight(1f)
                )
                AffinityParamBox(
                    title = stringResource(R.string.wa_pool_half_life_title),
                    value = stringResource(R.string.wa_pool_half_life_60s),
                    subtext = "exp(-Δt / 60)",
                    color = secondaryColor,
                    modifier = Modifier.weight(1f)
                )
                AffinityParamBox(
                    title = stringResource(R.string.wa_pool_energy_save_title),
                    value = stringResource(R.string.wa_pool_sleep_mode),
                    subtext = stringResource(R.string.wa_pool_auto_sleep),
                    color = TextWhite.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // All DCs Socket Allocations Grid
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.wa_pool_dc_capacity_title),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = TextMuted
                )

                snapshot.dcMetrics.forEach { m ->
                    val col = dcColors[m.dcId] ?: primaryColor
                    val isAllocated = m.allocatedSockets > 0
                    val weightProgress = (m.weight / maxWeight).toFloat().coerceIn(0.05f, 1f)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isAllocated) col.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.02f))
                            .border(
                                width = if (isAllocated) 0.8.dp else 0.6.dp,
                                color = if (isAllocated) col.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.06f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(8.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(col)
                                    )
                                    Column {
                                        Text(
                                            text = m.info.name,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextWhite
                                        )
                                        Text(
                                            text = m.info.role,
                                            fontSize = 9.sp,
                                            color = TextMuted
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(if (isAllocated) col.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isAllocated) stringResource(R.string.wa_pool_sockets_count, m.allocatedSockets) else stringResource(R.string.wa_pool_standby_zero),
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isAllocated) col else TextMuted
                                    )
                                }
                            }

                            // Activity Weight Bar
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.wa_pool_weight_val, String.format(Locale.US, "%.2f", m.weight)),
                                        fontSize = 9.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = if (isAllocated) stringResource(R.string.wa_pool_hot) else stringResource(R.string.wa_pool_standby),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isAllocated) col else TextMuted
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.5.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(Color.White.copy(alpha = 0.05f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(weightProgress)
                                            .clip(RoundedCornerShape(1.5.dp))
                                            .background(if (isAllocated) col else TextMuted.copy(alpha = 0.3f))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // Behavioral Rules Info Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringResource(R.string.wa_pool_principles_title),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp,
                        color = primaryColor
                    )
                    Text(
                        text = stringResource(R.string.wa_pool_principles_text),
                        fontSize = 9.5.sp,
                        color = TextMuted,
                        lineHeight = 13.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AffinityParamBox(
    title: String,
    value: String,
    subtext: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                maxLines = 1
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = color,
                maxLines = 1
            )
            Text(
                text = subtext,
                fontSize = 8.5.sp,
                color = TextMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
fun MtprotoTransportDialectsSplitCard(
    snapshot: MtprotoAnalyticsSnapshot,
    onInfoClick: () -> Unit,
    primaryColor: Color = Color(0xFF00FF87),
    secondaryColor: Color = Color(0xFF00F5D4),
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var selectedDialect by remember { mutableStateOf<String?>(null) }

    val totalPackets = snapshot.totalPackets.coerceAtLeast(1L).toFloat()
    val interPct = (snapshot.intermediatePackets.toFloat() / totalPackets * 100f).coerceIn(0f, 100f)
    val paddedPct = (snapshot.paddedPackets.toFloat() / totalPackets * 100f).coerceIn(0f, 100f)
    val abridgedPct = (snapshot.abridgedPackets.toFloat() / totalPackets * 100f).coerceIn(0f, 100f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .staggeredEntrance(index = 5)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(primaryColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_shield),
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.wa_proto_split_title),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.1.sp,
                            color = TextMuted
                        )
                        Text(
                            text = stringResource(R.string.wa_proto_split_sub),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }
                }

                InfoButton(onClick = onInfoClick)
            }

            // Dialect Share Segmented Bar
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.wa_proto_distribution_title),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "${snapshot.totalPackets}",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                color = TextWhite
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.wa_unit_packets),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMuted,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(secondaryColor.copy(alpha = 0.12f))
                            .border(0.6.dp, secondaryColor.copy(alpha = 0.4f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wa_dpi_resistant),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = secondaryColor
                        )
                    }
                }

                // 3-Segment Dialect Progress Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(interPct.coerceAtLeast(1f))
                            .background(primaryColor)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(paddedPct.coerceAtLeast(1f))
                            .background(secondaryColor)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(abridgedPct.coerceAtLeast(1f))
                            .background(Color.White.copy(alpha = 0.60f))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Interm: ${String.format(Locale.US, "%.1f", interPct)}%",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor
                    )
                    Text(
                        text = "Padded: ${String.format(Locale.US, "%.1f", paddedPct)}%",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = secondaryColor
                    )
                    Text(
                        text = "Abridged: ${String.format(Locale.US, "%.1f", abridgedPct)}%",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.60f)
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // 3-Card Dialect Summary Matrix
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DialectSummaryBox(
                    title = stringResource(R.string.wa_proto_intermediate_title),
                    value = "${snapshot.intermediatePackets}",
                    subtext = stringResource(R.string.wa_proto_0xee_sub),
                    color = primaryColor,
                    modifier = Modifier.weight(1f)
                )
                DialectSummaryBox(
                    title = stringResource(R.string.wa_proto_padded_title),
                    value = "${snapshot.paddedPackets}",
                    subtext = stringResource(R.string.wa_proto_0xdd_sub),
                    color = secondaryColor,
                    modifier = Modifier.weight(1f)
                )
                DialectSummaryBox(
                    title = stringResource(R.string.wa_proto_abridged_title),
                    value = "${snapshot.abridgedPackets}",
                    subtext = stringResource(R.string.wa_proto_0xef_sub),
                    color = Color.White.copy(alpha = 0.70f),
                    modifier = Modifier.weight(1f)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // Interactive Dialect Deep Inspector Rows
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.wa_proto_spec_title),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = TextMuted
                )

                // Intermediate
                DialectDeepRow(
                    name = stringResource(R.string.wa_proto_intermediate_name),
                    hexHeader = "0xee ee ee ee",
                    packets = snapshot.intermediatePackets,
                    pct = interPct,
                    frameStructure = "[4B Length LE] [MTProto Payload]",
                    description = stringResource(R.string.wa_proto_intermediate_desc),
                    color = primaryColor,
                    isSelected = selectedDialect == "intermediate",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selectedDialect = if (selectedDialect == "intermediate") null else "intermediate"
                    }
                )

                // Padded Intermediate
                DialectDeepRow(
                    name = stringResource(R.string.wa_proto_padded_name),
                    hexHeader = "0xdd dd dd dd",
                    packets = snapshot.paddedPackets,
                    pct = paddedPct,
                    frameStructure = "[4B Length LE] [Payload] [0-15B Pad]",
                    description = stringResource(R.string.wa_proto_padded_desc),
                    color = secondaryColor,
                    isSelected = selectedDialect == "padded",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selectedDialect = if (selectedDialect == "padded") null else "padded"
                    }
                )

                // Abridged
                DialectDeepRow(
                    name = stringResource(R.string.wa_proto_abridged_name),
                    hexHeader = "0xef",
                    packets = snapshot.abridgedPackets,
                    pct = abridgedPct,
                    frameStructure = "[1B Length (<127)] [MTProto Payload]",
                    description = stringResource(R.string.wa_proto_abridged_desc),
                    color = Color.White.copy(alpha = 0.75f),
                    isSelected = selectedDialect == "abridged",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selectedDialect = if (selectedDialect == "abridged") null else "abridged"
                    }
                )
            }
        }
    }
}

@Composable
fun MtprotoWssMultiplexingCard(
    snapshot: MtprotoAnalyticsSnapshot,
    onInfoClick: () -> Unit,
    primaryColor: Color = Color(0xFF00FF87),
    secondaryColor: Color = Color(0xFF00F5D4),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .staggeredEntrance(index = 6)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(primaryColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_diag_protocol),
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.wa_mux_title),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.1.sp,
                            color = TextMuted
                        )
                        Text(
                            text = stringResource(R.string.wa_mux_sub),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }
                }

                InfoButton(onClick = onInfoClick)
            }

            // Hero Ratio Display
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.wa_mux_ratio_title),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "1 WSS : ${String.format(Locale.US, "%.0f", snapshot.multiplexingRatio)}",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                color = primaryColor
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.wa_unit_packets),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMuted,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(primaryColor.copy(alpha = 0.12f))
                            .border(0.6.dp, primaryColor.copy(alpha = 0.4f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wa_zero_cost_badge),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // 4-Card Grid
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TransportMetricBox(
                        title = stringResource(R.string.wa_mux_packets_processed_title),
                        value = "${snapshot.totalPackets}",
                        subtext = stringResource(R.string.wa_mux_mtproto_transport),
                        color = primaryColor,
                        modifier = Modifier.weight(1f)
                    )
                    TransportMetricBox(
                        title = stringResource(R.string.wa_mux_dpi_protection_title),
                        value = "Padded 0xdd",
                        subtext = stringResource(R.string.wa_mux_padding_random),
                        color = secondaryColor,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TransportMetricBox(
                        title = stringResource(R.string.wa_mux_tunnel_mode_title),
                        value = "WSS TLS 1.3",
                        subtext = "Cloudflare Worker",
                        color = primaryColor,
                        modifier = Modifier.weight(1f)
                    )
                    TransportMetricBox(
                        title = stringResource(R.string.wa_cdn_quota_saving_title),
                        value = stringResource(R.string.wa_zero_cost_val),
                        subtext = stringResource(R.string.wa_mux_wss_per_session),
                        color = secondaryColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // Architecture Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringResource(R.string.wa_mux_arch_title),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp,
                        color = primaryColor
                    )
                    Text(
                        text = stringResource(R.string.wa_mux_arch_text),
                        fontSize = 9.5.sp,
                        color = TextMuted,
                        lineHeight = 13.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportMetricBox(
    title: String,
    value: String,
    subtext: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                maxLines = 1
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = color,
                maxLines = 1
            )
            Text(
                text = subtext,
                fontSize = 8.5.sp,
                color = TextMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DialectSummaryBox(
    title: String,
    value: String,
    subtext: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                maxLines = 1
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = color,
                maxLines = 1
            )
            Text(
                text = subtext,
                fontSize = 8.5.sp,
                color = TextMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DialectDeepRow(
    name: String,
    hexHeader: String,
    packets: Long,
    pct: Float,
    frameStructure: String,
    description: String,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) color.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.02f))
            .border(
                width = if (isSelected) 0.8.dp else 0.6.dp,
                color = if (isSelected) color.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(color.copy(alpha = 0.15f))
                            .border(0.6.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = hexHeader,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = color
                        )
                    }
                    Text(
                        text = name,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.wa_packets_val, packets),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        text = "${String.format(Locale.US, "%.1f", pct)}%",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = color
                    )
                }
            }

            Text(
                text = description,
                fontSize = 9.5.sp,
                color = TextMuted,
                lineHeight = 13.sp
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = stringResource(R.string.wa_frame_structure_val, frameStructure),
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = color
                    )
                }
            }
        }
    }
}

@Composable
fun ShannonEntropyRadialGauge(
    entropy: Double,
    maxEntropy: Double = 1.79,
    primaryColor: Color = Color(0xFF00FF87),
    secondaryColor: Color = Color(0xFF00F5D4),
    modifier: Modifier = Modifier
) {
    val normalized = (entropy / maxEntropy).coerceIn(0.0, 1.0).toFloat()
    val animatedNormalized by animateFloatAsState(
        targetValue = normalized,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "entropyGauge"
    )

    val gaugeColor = when {
        entropy < 0.60 -> primaryColor
        entropy <= 1.30 -> secondaryColor
        else -> TextWhite
    }

    Box(
        modifier = modifier
            .size(width = 120.dp, height = 70.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 9.dp.toPx()
            val radius = (size.width - strokeWidth) / 2f
            val center = Offset(size.width / 2f, size.height - 3.dp.toPx())

            // Background Track Arc
            drawArc(
                color = Color.White.copy(alpha = 0.05f),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active Colored Arc in Brand Gradient
            val sweep = (animatedNormalized * 180f).coerceAtLeast(4f)
            drawArc(
                brush = Brush.sweepGradient(
                    0.5f to primaryColor.copy(alpha = 0.35f),
                    0.75f to primaryColor,
                    1.0f to secondaryColor,
                    center = center
                ),
                startAngle = 180f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        // Center Value
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            Text(
                text = "H = ${String.format(Locale.US, "%.2f", entropy)}",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Black,
                color = gaugeColor
            )
            Text(
                text = stringResource(R.string.wa_unit_nat),
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted
            )
        }
    }
}

@Composable
fun DecayCurveTimelineChart(
    dcMetrics: List<DcMetrics>,
    selectedDcId: Int?,
    onSelectDc: (Int) -> Unit,
    primaryColor: Color = Color(0xFF00FF87),
    secondaryColor: Color = Color(0xFF00F5D4),
    modifier: Modifier = Modifier
) {
    val dcColors = remember(primaryColor, secondaryColor) {
        mapOf(
            2 to primaryColor,
            4 to secondaryColor,
            100 to Color(0xFF00B4D8),
            1 to Color.White.copy(alpha = 0.85f),
            3 to Color.White.copy(alpha = 0.60f),
            5 to Color.White.copy(alpha = 0.40f)
        )
    }

    val now = System.currentTimeMillis()
    val maxT = 180f // 180 seconds

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.wa_decay_curve_title),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                    color = primaryColor
                )
                Text(
                    text = stringResource(R.string.wa_decay_half_life),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(75.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val bottomPadding = 12.dp.toPx()
                    val chartH = h - bottomPadding

                    // 1. Threshold line T1/2 = 50%
                    val halfLifeY = chartH * 0.50f
                    drawLine(
                        color = Color.White.copy(alpha = 0.08f),
                        start = Offset(0f, halfLifeY),
                        end = Offset(w, halfLifeY),
                        strokeWidth = 0.8.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
                    )

                    // 2. Exponential Decay Curve Path
                    val curvePath = Path()
                    val steps = 60
                    val points = (0..steps).map { step ->
                        val t = (step.toFloat() / steps.toFloat()) * maxT
                        val decay = kotlin.math.exp(-t / 60.0).toFloat()
                        val x = (t / maxT) * w
                        val y = chartH * (1f - decay)
                        Offset(x, y)
                    }

                    curvePath.moveTo(points.first().x, points.first().y)
                    for (i in 0 until points.size - 1) {
                        val p0 = points[i]
                        val p1 = points[i + 1]
                        val cx = (p0.x + p1.x) / 2f
                        curvePath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                    }

                    drawPath(
                        path = curvePath,
                        brush = Brush.horizontalGradient(
                            listOf(primaryColor, secondaryColor, Color.White.copy(alpha = 0.40f))
                        ),
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // 3. DC Points on Decay Curve
                    dcMetrics.forEach { m ->
                        val idleSec = if (m.lastActivityTimestamp > 0L) {
                            ((now - m.lastActivityTimestamp) / 1000f).coerceIn(0f, maxT)
                        } else {
                            maxT
                        }
                        val decay = kotlin.math.exp(-idleSec / 60.0).toFloat()
                        val x = (idleSec / maxT) * w
                        val y = chartH * (1f - decay)
                        val col = dcColors[m.dcId] ?: primaryColor
                        val isSelected = selectedDcId == m.dcId

                        // Glow halo
                        drawCircle(
                            color = col.copy(alpha = if (isSelected) 0.6f else 0.25f),
                            radius = if (isSelected) 6.5.dp.toPx() else 4.dp.toPx(),
                            center = Offset(x, y)
                        )
                        // Core dot
                        drawCircle(
                            color = col,
                            radius = if (isSelected) 3.5.dp.toPx() else 2.5.dp.toPx(),
                            center = Offset(x, y)
                        )
                    }
                }

                // Time axis labels
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = stringResource(R.string.wa_decay_pt_0), fontSize = 8.sp, color = TextMuted)
                    Text(text = stringResource(R.string.wa_decay_pt_60), fontSize = 8.sp, color = primaryColor)
                    Text(text = stringResource(R.string.wa_decay_pt_120), fontSize = 8.sp, color = TextMuted)
                    Text(text = stringResource(R.string.wa_decay_pt_180), fontSize = 8.sp, color = Color.White.copy(alpha = 0.50f))
                }
            }

            // DC Quick Selection Chips on the Curve
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                dcMetrics.forEach { m ->
                    val col = dcColors[m.dcId] ?: primaryColor
                    val isSelected = selectedDcId == m.dcId
                    val idleSec = if (m.lastActivityTimestamp > 0L) {
                        ((now - m.lastActivityTimestamp) / 1000f).coerceIn(0f, maxT)
                    } else {
                        maxT
                    }
                    val decayFactor = kotlin.math.exp(-idleSec / 60.0).toFloat()

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) col.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.03f))
                            .border(0.6.dp, if (isSelected) col else Color.White.copy(alpha = 0.07f), RoundedCornerShape(6.dp))
                            .clickable { onSelectDc(m.dcId) }
                            .padding(vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${if (m.dcId == 100) "CDN" else "DC${m.dcId}"}\n${String.format(Locale.US, "%.0f", decayFactor * 100f)}%",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) col else TextMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 9.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MtprotoPoolEntropyAndDecayCard(
    snapshot: MtprotoAnalyticsSnapshot,
    onInfoClick: () -> Unit,
    primaryColor: Color = Color(0xFF00FF87),
    secondaryColor: Color = Color(0xFF00F5D4),
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var selectedDcId by remember { mutableStateOf<Int?>(null) }

    val entropy = snapshot.dcAffinityEntropy
    val maxEntropy = 1.79 // ln(6 DC nodes)
    val pielouEvenness = ((entropy / maxEntropy) * 100.0).coerceIn(0.0, 100.0)

    val (modeTitle, modeColor, modeDesc) = when {
        entropy < 0.60 -> Triple(
            stringResource(R.string.wa_entropy_mode_single_title),
            primaryColor,
            stringResource(R.string.wa_entropy_mode_single_desc)
        )
        entropy <= 1.30 -> Triple(
            stringResource(R.string.wa_entropy_mode_dual_title),
            secondaryColor,
            stringResource(R.string.wa_entropy_mode_dual_desc)
        )
        else -> Triple(
            stringResource(R.string.wa_entropy_mode_multi_title),
            TextWhite,
            stringResource(R.string.wa_entropy_mode_multi_desc)
        )
    }

    val dcColors = remember(primaryColor, secondaryColor) {
        mapOf(
            2 to primaryColor,                      // DC2 Chats
            4 to secondaryColor,                    // DC4 Media
            100 to Color(0xFF00B4D8),               // CDN FlowSeal
            1 to Color.White.copy(alpha = 0.85f),  // DC1 Miami
            3 to Color.White.copy(alpha = 0.60f),  // DC3 Miami
            5 to Color.White.copy(alpha = 0.40f)   // DC5 Singapore
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .staggeredEntrance(index = 4)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(primaryColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_diag_formula),
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.wa_entropy_decay_title),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.1.sp,
                            color = TextMuted
                        )
                        Text(
                            text = stringResource(R.string.wa_shannon_model_sub),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }
                }

                InfoButton(onClick = onInfoClick)
            }

            // Radial Gauge & Mode Banner
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShannonEntropyRadialGauge(
                    entropy = entropy,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(modeColor.copy(alpha = 0.12f))
                            .border(0.6.dp, modeColor.copy(alpha = 0.4f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.5.dp)
                    ) {
                        Text(
                            text = modeTitle,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = modeColor
                        )
                    }
                    Text(
                        text = modeDesc,
                        fontSize = 9.5.sp,
                        color = TextMuted,
                        lineHeight = 13.sp
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // 3-Card Math Metric Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MathParamBox(
                    title = stringResource(R.string.wa_entropy_h_title),
                    value = stringResource(R.string.wa_entropy_val, String.format(Locale.US, "%.2f", entropy)),
                    subtext = "H = -∑ p·ln(p)",
                    color = modeColor,
                    modifier = Modifier.weight(1f)
                )
                MathParamBox(
                    title = stringResource(R.string.wa_uniformity_title),
                    value = "${String.format(Locale.US, "%.0f", pielouEvenness)}%",
                    subtext = stringResource(R.string.wa_pielou_evenness),
                    color = secondaryColor,
                    modifier = Modifier.weight(1f)
                )
                MathParamBox(
                    title = stringResource(R.string.wa_half_life_title),
                    value = stringResource(R.string.wa_half_life_val),
                    subtext = "exp(-Δt / 60)",
                    color = primaryColor,
                    modifier = Modifier.weight(1f)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // Real-Time Decay Curve Timeline Chart
            DecayCurveTimelineChart(
                dcMetrics = snapshot.dcMetrics,
                selectedDcId = selectedDcId,
                onSelectDc = { dcId ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedDcId = if (selectedDcId == dcId) null else dcId
                },
                primaryColor = primaryColor,
                secondaryColor = secondaryColor
            )

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // Decay Life Radar per DC
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.wa_decay_dynamics_title),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = TextMuted
                    )
                    Text(
                        text = "T½ = 60s",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                }

                snapshot.dcMetrics.forEach { m ->
                    val col = dcColors[m.dcId] ?: primaryColor
                    val now = System.currentTimeMillis()
                    val idleSeconds = if (m.lastActivityTimestamp > 0L) (now - m.lastActivityTimestamp) / 1000.0 else 300.0
                    val decayFactor = kotlin.math.exp(-idleSeconds / 60.0).coerceIn(0.01, 1.0)
                    val decayPct = (decayFactor * 100.0).toFloat()
                    val isSelected = selectedDcId == m.dcId

                    val statusText = when {
                        decayPct >= 70f -> stringResource(R.string.wa_decay_status_hot)
                        decayPct >= 25f -> stringResource(R.string.wa_decay_status_cooling)
                        else -> stringResource(R.string.wa_decay_status_standby)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) col.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.02f))
                            .border(
                                width = if (isSelected) 0.8.dp else 0.6.dp,
                                color = if (isSelected) col else Color.White.copy(alpha = 0.06f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedDcId = if (selectedDcId == m.dcId) null else m.dcId
                            }
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
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
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(col)
                                )
                                Text(
                                    text = "${m.info.name} • ${m.info.role}",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                            }
                            Text(
                                text = "$statusText (${String.format(Locale.US, "%.0f", decayPct)}%)",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (decayPct >= 70f) col else TextMuted
                            )
                        }

                        // Decay factor progress bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(decayPct / 100f)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(if (decayPct >= 70f) col else col.copy(alpha = 0.5f))
                            )
                        }

                        // Dynamic Inspector details when selected
                        if (isSelected) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.wa_decay_idle_val, String.format(Locale.US, "%.1f", idleSeconds)),
                                    fontSize = 9.sp,
                                    color = TextMuted
                                )
                                Text(
                                    text = stringResource(R.string.wa_decay_weight_val, String.format(Locale.US, "%.2f", m.weight)),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = col
                                )
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

            // Formula Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringResource(R.string.wa_math_engine_title),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp,
                        color = primaryColor
                    )
                    Text(
                        text = stringResource(R.string.wa_math_engine_text),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextWhite.copy(alpha = 0.85f),
                        lineHeight = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MathParamBox(
    title: String,
    value: String,
    subtext: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                maxLines = 1
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = color,
                maxLines = 1
            )
            Text(
                text = subtext,
                fontSize = 8.5.sp,
                color = TextMuted,
                maxLines = 1
            )
        }
    }
}
