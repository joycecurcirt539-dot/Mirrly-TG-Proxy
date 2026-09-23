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
import com.mirrly.tgproxy.core.LocalProxyServer
import com.mirrly.tgproxy.core.WorkerProfile
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

    var proxyRefreshTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(selectedPeriod) {
        while (isActive) {
            summary = WorkerRequestTracker.getAnalytics(selectedPeriod)
            proxyRefreshTick = System.currentTimeMillis()
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
            "mtproto_traffic" to Pair(R.string.wa_info_mtproto_traffic_title, R.string.wa_info_mtproto_traffic_desc),
            "mtproto_pool" to Pair(R.string.wa_info_mtproto_pool_title, R.string.wa_info_mtproto_pool_desc),
            "mtproto_tunnel" to Pair(R.string.wa_info_mtproto_tunnel_title, R.string.wa_info_mtproto_tunnel_desc)
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
                // ── MTPROTO / TELEGRAM PROXY ANALYTICS ──
                MtprotoTrafficHeroCard(
                    proxyServer = app.proxyServer,
                    primaryColor = activeProtoColor,
                    secondaryColor = secondaryProtoColor,
                    onInfoClick = { infoKey = "mtproto_traffic" }
                )

                MtprotoConnectionPoolCard(
                    proxyServer = app.proxyServer,
                    primaryColor = activeProtoColor,
                    secondaryColor = secondaryProtoColor,
                    onInfoClick = { infoKey = "mtproto_pool" }
                )

                MtprotoTunnelParamsCard(
                    proxyServer = app.proxyServer,
                    activeWorker = activeWorker,
                    primaryColor = activeProtoColor,
                    secondaryColor = secondaryProtoColor,
                    onInfoClick = { infoKey = "mtproto_tunnel" }
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
fun MtprotoTrafficHeroCard(
    proxyServer: LocalProxyServer,
    primaryColor: Color = Color(0xFF00FF87),
    secondaryColor: Color = Color(0xFF00F5D4),
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunning = proxyServer.isRunning
    val rxBytes = if (isRunning) proxyServer.stats.totalBytesReceived.get() else 0L
    val txBytes = if (isRunning) proxyServer.stats.totalBytesSent.get() else 0L
    val rxSpeed = if (isRunning) proxyServer.stats.downloadSpeedBps else 0L
    val txSpeed = if (isRunning) proxyServer.stats.uploadSpeedBps else 0L

    Column(
        modifier = modifier.staggeredEntrance(index = 1),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.wa_mtproto_traffic_title),
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = TextMuted
            )
            InfoButton { onInfoClick() }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Transparent)
                .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(primaryColor.copy(alpha = 0.06f))
                            .border(0.75.dp, primaryColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.wa_mtproto_rx_title),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                            Text(
                                text = formatTrafficBytes(rxBytes),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Black,
                                color = primaryColor
                            )
                            Text(
                                text = stringResource(R.string.wa_mtproto_speed_rx) + ": " + formatSpeedText(rxSpeed),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextWhite.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(secondaryColor.copy(alpha = 0.06f))
                            .border(0.75.dp, secondaryColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.wa_mtproto_tx_title),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                            Text(
                                text = formatTrafficBytes(txBytes),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Black,
                                color = secondaryColor
                            )
                            Text(
                                text = stringResource(R.string.wa_mtproto_speed_tx) + ": " + formatSpeedText(txSpeed),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextWhite.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MtprotoConnectionPoolCard(
    proxyServer: LocalProxyServer,
    primaryColor: Color = Color(0xFF00FF87),
    secondaryColor: Color = Color(0xFF00F5D4),
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunning = proxyServer.isRunning
    val activeConns = if (isRunning) proxyServer.stats.activeConnections.get() else 0
    val totalWs = if (isRunning) proxyServer.stats.totalWsConnections.get() else 0
    val standbySlots = if (isRunning) proxyServer.config.mtprotoStandbyPerActiveSlot else 1

    Column(
        modifier = modifier.staggeredEntrance(index = 2),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.wa_mtproto_pool_card_title),
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = TextMuted
            )
            InfoButton { onInfoClick() }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Transparent)
                .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
        ) {
            Column {
                DiagnosticMetricRow(
                    iconRes = R.drawable.ic_diag_antenna,
                    iconColor = primaryColor,
                    title = stringResource(R.string.wa_mtproto_active_clients),
                    value = "$activeConns сокетов",
                    badgeText = if (isRunning && activeConns > 0) "Активен" else "Ожидание",
                    badgeColor = if (isRunning && activeConns > 0) primaryColor else TextMuted,
                    onInfoClick = onInfoClick
                )

                Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

                DiagnosticMetricRow(
                    iconRes = R.drawable.ic_diag_protocol,
                    iconColor = secondaryColor,
                    title = stringResource(R.string.wa_mtproto_active_tunnels),
                    value = "$totalWs сессий",
                    badgeText = "WSS Anycast",
                    badgeColor = secondaryColor,
                    onInfoClick = onInfoClick
                )

                Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

                DiagnosticMetricRow(
                    iconRes = R.drawable.ic_diag_jitter,
                    iconColor = primaryColor,
                    title = stringResource(R.string.wa_mtproto_pool_slots),
                    value = "$standbySlots слота",
                    badgeText = stringResource(R.string.wa_mtproto_pool_ready_status),
                    badgeColor = primaryColor,
                    onInfoClick = onInfoClick
                )
            }
        }
    }
}

@Composable
fun MtprotoTunnelParamsCard(
    proxyServer: LocalProxyServer,
    activeWorker: WorkerProfile,
    primaryColor: Color = Color(0xFF00FF87),
    secondaryColor: Color = Color(0xFF00F5D4),
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunning = proxyServer.isRunning
    val pingMs = if (isRunning) proxyServer.currentPingMs else 0L
    val effectiveDomain = proxyServer.config.getEffectiveCfDomain()
    val sni = when {
        effectiveDomain.isNotBlank() -> effectiveDomain
        activeWorker.domain.isNotBlank() -> activeWorker.domain
        else -> "cloudflare.com"
    }

    Column(
        modifier = modifier.staggeredEntrance(index = 3),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.wa_mtproto_tunnel_card_title),
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = TextMuted
            )
            InfoButton { onInfoClick() }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Transparent)
                .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
        ) {
            Column {
                DiagnosticMetricRow(
                    iconRes = R.drawable.ic_diag_worker,
                    iconColor = primaryColor,
                    title = stringResource(R.string.wa_mtproto_tunnel_port),
                    value = "127.0.0.1:1080",
                    badgeText = "FakeTLS Relay",
                    badgeColor = primaryColor,
                    onInfoClick = onInfoClick
                )

                Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

                DiagnosticMetricRow(
                    iconRes = R.drawable.ic_diag_cloudflare,
                    iconColor = secondaryColor,
                    title = stringResource(R.string.wa_mtproto_tunnel_sni),
                    value = sni,
                    badgeText = "TLS 1.3",
                    badgeColor = secondaryColor,
                    onInfoClick = onInfoClick
                )

                Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.05f)))

                DiagnosticMetricRow(
                    iconRes = R.drawable.ic_diag_rtt,
                    iconColor = primaryColor,
                    title = stringResource(R.string.wa_mtproto_tunnel_rtt),
                    value = if (isRunning && pingMs > 0) "$pingMs мс" else "—",
                    badgeText = if (isRunning && pingMs in 1..250) "Отлично" else if (isRunning && pingMs > 250) "Задержка" else "Ожидание",
                    badgeColor = if (isRunning && pingMs in 1..250) primaryColor else TextMuted,
                    onInfoClick = onInfoClick
                )
            }
        }
    }
}

private fun formatSpeedText(bps: Long): String {
    if (bps <= 0) return "0 Б/с"
    val kbps = bps / 1024.0
    val mbps = kbps / 1024.0
    return when {
        mbps >= 1.0 -> String.format(Locale.US, "%.1f МБ/с", mbps)
        kbps >= 1.0 -> String.format(Locale.US, "%.1f КБ/с", kbps)
        else -> "$bps Б/с"
    }
}
