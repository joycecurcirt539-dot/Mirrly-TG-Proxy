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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.SpeedTestLiveState
import com.mirrly.tgproxy.core.SpeedTestStage
import com.mirrly.tgproxy.service.SpeedTestHistoryManager
import com.mirrly.tgproxy.service.SpeedTestRecord
import com.mirrly.tgproxy.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelSpeedTestScreen(
    onBack: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val server = app.proxyServer
    val engine = server.speedTestEngine

    val isSocks5 by app.prefsManager.isSocks5Flow.collectAsState()
    val activeWorker = remember(app.prefsManager.getActiveWorkerId()) { app.prefsManager.getActiveWorker() }
    val testState by engine.liveState.collectAsState()
    val historyRecords by SpeedTestHistoryManager.historyFlow.collectAsState()

    var selectedTab by remember { mutableIntStateOf(1) } // 0 = Speed Test (in dev), 1 = History
    var showInDevDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    val targetDomain = if (isSocks5) {
        val custom = app.config.customCfDomain.trim()
        if (custom.isNotEmpty()) custom else activeWorker.domain
    } else {
        "kws2.pclead.co.uk"
    }

    val socks5Port = if (isSocks5 && server.isRunning) server.config.socks5Port else null

    val handleBack: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (engine.isRunning) {
            engine.cancelTest()
        }
        onBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            if (engine.isRunning) {
                engine.cancelTest()
            }
        }
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
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
                )
                .adaptiveContentPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── SEGMENTED TAB SWITCHER ──
            SpeedTestSegmentedTabs(
                selectedTab = selectedTab,
                historyCount = historyRecords.size,
                isSocks5 = isSocks5,
                onTabSelected = { newTab ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (newTab == 0) {
                        showInDevDialog = true
                    } else {
                        selectedTab = newTab
                    }
                }
            )

            // ── TAB CONTENT ──
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { width -> width / 4 } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width / 4 } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width / 4 } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width / 4 } + fadeOut()
                    }
                },
                label = "speedTabAnimation"
            ) { currentTab ->
                if (currentTab == 0) {
                    // TAB 0: LIVE TEST VIEW
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 1. Target Node Info Bar
                        SpeedTestHeaderCard(
                            targetDomain = if (activeWorker.isDeveloperWorker && isSocks5) activeWorker.name else targetDomain,
                            edgeColo = testState.edgeColo,
                            isSocks5 = isSocks5
                        )

                        // 2. Sequential Phase Stepper (Ping -> DL -> UL -> Score)
                        SpeedTestPhaseStepper(
                            stage = testState.stage,
                            isSocks5 = isSocks5
                        )

                        // 3. Hero Speedometer Gauge
                        SpeedometerGauge(
                            currentSpeedMbps = testState.currentSpeedMbps,
                            stage = testState.stage,
                            progress = testState.progress,
                            isSocks5 = isSocks5
                        )

                        // 4. 4-Tile Live Metrics Grid
                        SpeedMetricsGrid(
                            state = testState,
                            isSocks5 = isSocks5
                        )

                        // 5. Live Waveform / Sparkline Chart
                        val safePoints = testState.sparklinePoints.filter { it.isFinite() && it >= 0f }
                        if (safePoints.size >= 2 || testState.stage == SpeedTestStage.COMPLETED) {
                            SpeedWaveformCard(
                                points = safePoints,
                                peakSpeed = testState.peakSpeedMbps,
                                isSocks5 = isSocks5
                            )
                        }

                        // 6. Telegram Service Suitability Report (on completion)
                        if (testState.stage == SpeedTestStage.COMPLETED) {
                            TelegramSuitabilityCard(
                                report = testState.suitability,
                                grade = testState.qualityGrade,
                                isSocks5 = isSocks5
                            )
                        }

                        // 7. Error Notice if test encountered failure
                        if (testState.stage == SpeedTestStage.ERROR && testState.errorDetail != null) {
                            SpeedTestErrorCard(
                                errorMessage = testState.errorDetail ?: stringResource(R.string.speed_test_error_conn)
                            )
                        }

                        // 8. Start / Stop / Retest Action Button
                        SpeedTestActionButton(
                            stage = testState.stage,
                            isSocks5 = isSocks5,
                            onStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                engine.startTest(targetDomain, socks5Port)
                            },
                            onStop = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                engine.cancelTest()
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                    }
                } else {
                    // TAB 1: HISTORY VIEW
                    SpeedTestHistoryView(
                        historyRecords = historyRecords,
                        isSocks5 = isSocks5,
                        onClearAll = { showClearHistoryDialog = true },
                        onDeleteItem = { id ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            SpeedTestHistoryManager.deleteRecord(id)
                        },
                        onStartTestClick = {
                            selectedTab = 0
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            engine.startTest(targetDomain, socks5Port)
                        }
                    )
                }
            }
        }

        // Top Navigation Bar
        SpeedTestTopBar(
            onBack = handleBack,
            isSocks5 = isSocks5
        )

        // Floating Cyber Particles
        CyberParticlesOverlay(modifier = Modifier.fillMaxSize())

        // Clear History Confirmation Dialog
        if (showClearHistoryDialog) {
            ClearSpeedHistoryDialog(
                onDismiss = { showClearHistoryDialog = false },
                onConfirm = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    SpeedTestHistoryManager.clearHistory()
                    showClearHistoryDialog = false
                }
            )
        }

        if (showInDevDialog) {
            SpeedTestInDevDialog(
                onDismiss = { showInDevDialog = false }
            )
        }
    }
}

@Composable
private fun SpeedTestSegmentedTabs(
    selectedTab: Int,
    historyCount: Int,
    isSocks5: Boolean,
    onTabSelected: (Int) -> Unit
) {
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.03f),
        border = BorderStroke(1.dp, Color(0xFF1E2333))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Tab 0: Measure
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selectedTab == 0) accentColor.copy(alpha = 0.16f) else Color.Transparent)
                    .border(
                        1.dp,
                        if (selectedTab == 0) accentColor.copy(alpha = 0.45f) else Color.Transparent,
                        RoundedCornerShape(11.dp)
                    )
                    .clickable { onTabSelected(0) },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_speed_turbo),
                        contentDescription = null,
                        tint = if (selectedTab == 0) accentColor else TextMuted,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = stringResource(R.string.speed_test_tab_test),
                        fontSize = 10.5.sp,
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 0.6.sp,
                        color = if (selectedTab == 0) TextWhite else TextMuted
                    )
                }
            }

            // Tab 1: History
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selectedTab == 1) accentColor.copy(alpha = 0.16f) else Color.Transparent)
                    .border(
                        1.dp,
                        if (selectedTab == 1) accentColor.copy(alpha = 0.45f) else Color.Transparent,
                        RoundedCornerShape(11.dp)
                    )
                    .clickable { onTabSelected(1) },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_history),
                        contentDescription = null,
                        tint = if (selectedTab == 1) accentColor else TextMuted,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = stringResource(R.string.speed_test_tab_history),
                        fontSize = 11.5.sp,
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 0.8.sp,
                        color = if (selectedTab == 1) TextWhite else TextMuted
                    )
                    if (historyCount > 0) {
                        Surface(
                            shape = CircleShape,
                            color = if (selectedTab == 1) accentColor else Color(0xFF1E293B),
                            modifier = Modifier.padding(start = 2.dp)
                        ) {
                            Text(
                                text = "$historyCount",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Black,
                                color = if (selectedTab == 1) Color.Black else TextWhite,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedTestPhaseStepper(
    stage: SpeedTestStage,
    isSocks5: Boolean
) {
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed

    val phases = listOf(
        Pair(1, stringResource(R.string.speed_test_phase_ping)),
        Pair(2, stringResource(R.string.speed_test_phase_download)),
        Pair(3, stringResource(R.string.speed_test_phase_upload)),
        Pair(4, stringResource(R.string.speed_test_phase_summary))
    )

    val activeStep = when (stage) {
        SpeedTestStage.IDLE -> 0
        SpeedTestStage.PING -> 1
        SpeedTestStage.DOWNLOAD -> 2
        SpeedTestStage.UPLOAD -> 3
        SpeedTestStage.ANALYSIS, SpeedTestStage.COMPLETED -> 4
        SpeedTestStage.CANCELLED, SpeedTestStage.ERROR -> 0
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.025f),
        border = BorderStroke(1.dp, Color(0xFF1E2333))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            phases.forEachIndexed { index, (stepNum, stepTitle) ->
                val isCompleted = activeStep > stepNum || stage == SpeedTestStage.COMPLETED
                val isActive = activeStep == stepNum && stage != SpeedTestStage.COMPLETED

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isCompleted -> accentColor
                                        isActive -> accentColor.copy(alpha = 0.20f)
                                        else -> Color(0xFF161B26)
                                    }
                                )
                                .border(
                                    1.dp,
                                    when {
                                        isCompleted -> accentColor
                                        isActive -> accentColor
                                        else -> Color(0xFF263045)
                                    },
                                    CircleShape
                                )
                        ) {
                            if (isCompleted) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(13.dp)
                                )
                            } else {
                                Text(
                                    text = "$stepNum",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isActive) accentColor else TextMuted
                                )
                            }
                        }

                        Text(
                            text = stepTitle,
                            fontSize = 10.sp,
                            fontWeight = if (isActive || isCompleted) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                isActive -> accentColor
                                isCompleted -> TextWhite
                                else -> TextMuted
                            }
                        )
                    }

                    if (index < phases.size - 1) {
                        val isLineDone = activeStep > stepNum
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(2.dp)
                                .padding(horizontal = 8.dp)
                                .background(
                                    if (isLineDone) accentColor.copy(alpha = 0.8f) else Color(0xFF1E2333)
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedTestTopBar(
    onBack: () -> Unit,
    isSocks5: Boolean
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onBack()
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.action_back),
                    tint = TextWhite,
                    modifier = Modifier.size(20.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.speed_test_header_title),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    color = TextWhite
                )

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isSocks5) Color(0xFF7C4DFF).copy(alpha = 0.20f) else ActiveGreenLed.copy(alpha = 0.15f),
                    border = BorderStroke(0.8.dp, if (isSocks5) Color(0xFFB388FF).copy(alpha = 0.5f) else ActiveGreenLed.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = if (isSocks5) "SOCKS5" else "MTPROTO",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSocks5) Color(0xFFC084FC) else ActiveGreenLed,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun SpeedTestHeaderCard(
    targetDomain: String,
    edgeColo: String,
    isSocks5: Boolean
) {
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.12f))
                        .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_diag_worker),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column {
                    Text(
                        text = stringResource(R.string.speed_test_tunnel_node),
                        fontSize = 11.sp,
                        color = TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = targetDomain,
                        fontSize = 13.5.sp,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0F172A),
                border = BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (edgeColo != "—") ActiveGreenLed else TextMuted)
                    )
                    Text(
                        text = "POP: $edgeColo",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (edgeColo != "—") TextWhite else TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedometerGauge(
    currentSpeedMbps: Double,
    stage: SpeedTestStage,
    progress: Float,
    isSocks5: Boolean
) {
    val primaryAccent = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed
    val secondaryAccent = if (isSocks5) Color(0xFF7C4DFF) else Color(0xFF00E5FF)

    // Safe clamped speed (Guards against NaN or Infinity)
    val safeSpeed = if (currentSpeedMbps.isFinite() && currentSpeedMbps >= 0.0) currentSpeedMbps else 0.0
    val maxGaugeSpeed = 120.0
    val targetFraction = (safeSpeed / maxGaugeSpeed).coerceIn(0.0, 1.0).toFloat()

    val animatedFraction by animateFloatAsState(
        targetValue = if (targetFraction.isFinite()) targetFraction else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "gaugeFraction"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(230.dp)
        ) {
            Canvas(modifier = Modifier.size(210.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width / 2f - 16.dp.toPx()

                // Background track arc (135 deg to 405 deg = 270 deg sweep)
                drawArc(
                    color = Color(0xFF1E2333).copy(alpha = 0.6f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                )

                // Active speed glow arc (Clamped)
                val safeAnimFrac = if (animatedFraction.isFinite()) animatedFraction.coerceIn(0f, 1f) else 0f
                val activeSweep = (270f * safeAnimFrac).coerceIn(0f, 270f)

                if (activeSweep > 0.5f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0.0f to secondaryAccent,
                            0.5f to primaryAccent,
                            1.0f to Color(0xFFFFB703),
                            center = center
                        ),
                        startAngle = 135f,
                        sweepAngle = activeSweep,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Dial tick marks
                val tickCount = 11
                for (i in 0 until tickCount) {
                    val angleDeg = 135f + (270f * i / (tickCount - 1))
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val innerR = radius - 13.dp.toPx()
                    val outerR = radius - 6.dp.toPx()

                    val startX = center.x + (innerR * cos(angleRad)).toFloat()
                    val startY = center.y + (innerR * sin(angleRad)).toFloat()
                    val endX = center.x + (outerR * cos(angleRad)).toFloat()
                    val endY = center.y + (outerR * sin(angleRad)).toFloat()

                    val isHighlighted = (i.toFloat() / (tickCount - 1)) <= safeAnimFrac
                    drawLine(
                        color = if (isHighlighted) primaryAccent else Color(0xFF2B3548),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = if (i % 2 == 0) 2.2.dp.toPx() else 1.2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Needle indicator (Clamped)
                val needleAngle = 135f + activeSweep
                val needleRad = Math.toRadians(needleAngle.toDouble())
                val needleLen = radius - 18.dp.toPx()
                val needleX = center.x + (needleLen * cos(needleRad)).toFloat()
                val needleY = center.y + (needleLen * sin(needleRad)).toFloat()

                drawLine(
                    color = Color.White,
                    start = center,
                    end = Offset(needleX, needleY),
                    strokeWidth = 2.8.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Needle center hub
                drawCircle(
                    color = primaryAccent,
                    radius = 7.dp.toPx(),
                    center = center
                )
                drawCircle(
                    color = Color(0xFF0F121C),
                    radius = 3.5.dp.toPx(),
                    center = center
                )
            }

            // Central Digital Speed Readout
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 40.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f", safeSpeed),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                    color = TextWhite
                )
                Text(
                    text = stringResource(R.string.speed_test_mbps_unit),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryAccent,
                    letterSpacing = 1.sp
                )
            }
        }

        // Status description pill below gauge
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = primaryAccent.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.35f))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                if (stage == SpeedTestStage.PING || stage == SpeedTestStage.DOWNLOAD || stage == SpeedTestStage.UPLOAD || stage == SpeedTestStage.ANALYSIS) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = primaryAccent
                    )
                }
                Text(
                    text = stage.title.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}

@Composable
private fun SpeedMetricsGrid(
    state: SpeedTestLiveState,
    isSocks5: Boolean
) {
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Tile 1: Ping
        MetricTile(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_diag_rtt,
            title = stringResource(R.string.speed_test_stat_ping),
            value = if (state.pingMs > 0) stringResource(R.string.speed_test_stat_ping_ms, state.pingMs) else "—",
            subValue = if (state.minPingMs > 0) stringResource(R.string.speed_test_stat_min_ping, state.minPingMs) else stringResource(R.string.speed_test_stat_rtt_delay),
            accentColor = Color(0xFF38BDF8)
        )

        // Tile 2: Jitter
        MetricTile(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_diag_jitter,
            title = stringResource(R.string.speed_test_stat_jitter),
            value = if (state.pingMs > 0) stringResource(R.string.speed_test_stat_jitter_val, state.jitterMs) else "—",
            subValue = if (state.jitterMs <= 15) stringResource(R.string.speed_test_stat_high_stability) else stringResource(R.string.speed_test_stat_jitter_variation),
            accentColor = Color(0xFFB388FF)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Tile 3: Download
        MetricTile(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_arrow_down,
            title = stringResource(R.string.speed_test_stat_download),
            value = if (state.downloadSpeedMbps > 0) stringResource(R.string.speed_test_mbps_compact, String.format(Locale.US, "%.1f", state.downloadSpeedMbps)) else "—",
            subValue = if (state.downloadedBytes > 0) stringResource(R.string.speed_test_stat_downloaded_mb, state.downloadedBytes / (1024 * 1024)) else stringResource(R.string.speed_test_stat_download_channel),
            accentColor = accentColor
        )

        // Tile 4: Upload
        MetricTile(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_arrow_up,
            title = stringResource(R.string.speed_test_stat_upload),
            value = if (state.uploadSpeedMbps > 0) stringResource(R.string.speed_test_mbps_compact, String.format(Locale.US, "%.1f", state.uploadSpeedMbps)) else "—",
            subValue = if (state.uploadedBytes > 0) stringResource(R.string.speed_test_stat_uploaded_mb, state.uploadedBytes / (1024 * 1024)) else stringResource(R.string.speed_test_stat_upload_channel),
            accentColor = Color(0xFFFFB703)
        )
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    iconRes: Int,
    title: String,
    value: String,
    subValue: String,
    accentColor: Color
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = title,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
            }

            Text(
                text = value,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                color = TextWhite
            )

            Text(
                text = subValue,
                fontSize = 10.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SpeedWaveformCard(
    points: List<Float>,
    peakSpeed: Double,
    isSocks5: Boolean
) {
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed
    val safePoints = points.filter { it.isFinite() && it >= 0f }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.speed_test_chart_header),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                    color = TextMuted
                )

                val safePeak = if (peakSpeed.isFinite() && peakSpeed >= 0) peakSpeed else 0.0
                Text(
                    text = stringResource(R.string.speed_test_chart_peak, String.format(Locale.US, "%.1f", safePeak)),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                if (safePoints.size < 2) return@Canvas

                val maxVal = max(5f, (safePoints.maxOrNull() ?: 5f) * 1.15f)
                val widthPerPoint = (size.width / (safePoints.size - 1).toFloat()).coerceAtLeast(0.1f)

                val linePath = Path()
                val fillPath = Path()

                safePoints.forEachIndexed { i, p ->
                    val x = (i.toFloat() * widthPerPoint).coerceIn(0f, size.width)
                    val y = (size.height - (p / maxVal) * size.height).coerceIn(0f, size.height)

                    if (i == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, size.height)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }

                fillPath.lineTo(size.width, size.height)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(accentColor.copy(alpha = 0.25f), Color.Transparent),
                        startY = 0f,
                        endY = size.height
                    )
                )

                drawPath(
                    path = linePath,
                    color = accentColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}

@Composable
private fun TelegramSuitabilityCard(
    report: com.mirrly.tgproxy.core.TelegramSuitabilityReport,
    grade: String,
    isSocks5: Boolean
) {
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.7f))
            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_stat_proxy_connected),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.speed_test_report_header),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.1.sp,
                        color = TextWhite
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = accentColor.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = grade,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = report.summary,
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E293B)))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SuitabilityRow(label = stringResource(R.string.speed_test_suit_chats), verdict = report.chatsVerdict, accent = ActiveGreenLed)
                SuitabilityRow(label = stringResource(R.string.speed_test_suit_voice), verdict = report.voiceVerdict, accent = Color(0xFF38BDF8))
                SuitabilityRow(label = stringResource(R.string.speed_test_suit_media), verdict = report.mediaVerdict, accent = ActiveGreenLed)
                SuitabilityRow(label = stringResource(R.string.speed_test_suit_video), verdict = report.videoVerdict, accent = Color(0xFFFFB703))
            }
        }
    }
}

@Composable
private fun SuitabilityRow(label: String, verdict: String, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = TextWhite.copy(alpha = 0.85f))
        Text(text = verdict, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = accent)
    }
}

@Composable
private fun SpeedTestErrorCard(
    errorMessage: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFEF4444).copy(alpha = 0.10f),
        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_stat_proxy_error),
                contentDescription = null,
                tint = Color(0xFFEF4444),
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = stringResource(R.string.speed_test_failed),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEF4444)
                )
                Text(
                    text = errorMessage,
                    fontSize = 11.sp,
                    color = TextWhite.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun SpeedTestActionButton(
    stage: SpeedTestStage,
    isSocks5: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val isTesting = stage == SpeedTestStage.PING || stage == SpeedTestStage.DOWNLOAD || stage == SpeedTestStage.UPLOAD || stage == SpeedTestStage.ANALYSIS
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed
    val btnText = when (stage) {
        SpeedTestStage.IDLE -> stringResource(R.string.speed_test_btn_start)
        SpeedTestStage.COMPLETED, SpeedTestStage.CANCELLED, SpeedTestStage.ERROR -> stringResource(R.string.speed_test_btn_retry)
        else -> stringResource(R.string.speed_test_btn_stop)
    }

    Surface(
        onClick = if (isTesting) onStop else onStart,
        shape = RoundedCornerShape(16.dp),
        color = if (isTesting) Color(0xFFEF4444).copy(alpha = 0.15f) else accentColor.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, if (isTesting) Color(0xFFEF4444) else accentColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .springPress()
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = if (isTesting) R.drawable.ic_notif_stop else R.drawable.ic_refresh),
                contentDescription = null,
                tint = if (isTesting) Color(0xFFEF4444) else accentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = btnText,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
                color = if (isTesting) Color(0xFFEF4444) else TextWhite
            )
        }
    }
}

@Composable
private fun SpeedTestHistoryView(
    historyRecords: List<SpeedTestRecord>,
    isSocks5: Boolean,
    onClearAll: () -> Unit,
    onDeleteItem: (String) -> Unit,
    onStartTestClick: () -> Unit
) {
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header Row: Count and Clear Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.speed_test_total_records, historyRecords.size),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = TextMuted
            )

            if (historyRecords.isNotEmpty()) {
                Surface(
                    onClick = onClearAll,
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFEF4444).copy(alpha = 0.12f),
                    border = BorderStroke(0.8.dp, Color(0xFFEF4444).copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_trash),
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = stringResource(R.string.logs_action_clear),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444)
                        )
                    }
                }
            }
        }

        if (historyRecords.isEmpty()) {
            // Empty State
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(1.dp, Color(0xFF1E2333), CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_history),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Text(
                        text = stringResource(R.string.speed_test_empty_history_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )

                    Text(
                        text = stringResource(R.string.speed_test_empty_history_desc),
                        fontSize = 12.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        onClick = onStartTestClick,
                        shape = RoundedCornerShape(12.dp),
                        color = accentColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, accentColor)
                    ) {
                        Text(
                            text = stringResource(R.string.speed_test_btn_first_test),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        } else {
            // History List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(historyRecords, key = { it.id }) { record ->
                    SpeedHistoryRecordCard(
                        record = record,
                        isSocks5 = isSocks5,
                        onDelete = { onDeleteItem(record.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedHistoryRecordCard(
    record: SpeedTestRecord,
    isSocks5: Boolean,
    onDelete: () -> Unit
) {
    val dateStr = remember(record.timestampMs) {
        val sdf = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault())
        sdf.format(Date(record.timestampMs))
    }

    val isRecordSocks = record.protocol.equals("SOCKS5", ignoreCase = true)
    val recordAccent = if (isRecordSocks) Color(0xFFB388FF) else ActiveGreenLed

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header: Date, Protocol Chip, POP Chip, Delete Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = dateStr,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )

                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = if (isRecordSocks) Color(0xFF7C4DFF).copy(alpha = 0.20f) else ActiveGreenLed.copy(alpha = 0.15f),
                        border = BorderStroke(0.6.dp, if (isRecordSocks) Color(0xFFB388FF).copy(alpha = 0.5f) else ActiveGreenLed.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = record.protocol,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRecordSocks) Color(0xFFC084FC) else ActiveGreenLed,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }

                    if (record.edgeColo.isNotBlank() && record.edgeColo != "—") {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = Color(0xFF0F172A),
                            border = BorderStroke(0.6.dp, Color(0xFF1E293B))
                        ) {
                            Text(
                                text = record.edgeColo,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_trash),
                        contentDescription = stringResource(R.string.history_btn_delete),
                        tint = TextMuted.copy(alpha = 0.6f),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // Metrics row: DL | UL | PING | JITTER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Download
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_down),
                            contentDescription = null,
                            tint = recordAccent,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = stringResource(R.string.speed_test_in_short),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted
                        )
                    }
                    Text(
                        text = stringResource(R.string.speed_test_mbps_compact, String.format(Locale.US, "%.1f", record.downloadSpeedMbps)),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        color = TextWhite
                    )
                }

                // Upload
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_up),
                            contentDescription = null,
                            tint = Color(0xFFFFB703),
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = stringResource(R.string.speed_test_out_short),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted
                        )
                    }
                    Text(
                        text = stringResource(R.string.speed_test_mbps_compact, String.format(Locale.US, "%.1f", record.uploadSpeedMbps)),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        color = TextWhite
                    )
                }

                // Ping / Jitter
                Column {
                    Text(
                        text = stringResource(R.string.speed_test_rtt_jitter),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted
                    )
                    Text(
                        text = stringResource(R.string.speed_test_rtt_jitter_val, record.pingMs, record.jitterMs),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }

                // Grade Pill
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = recordAccent.copy(alpha = 0.12f),
                    border = BorderStroke(0.8.dp, recordAccent.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = record.qualityGrade,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = recordAccent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ClearSpeedHistoryDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.70f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .adaptiveContainerWidth(420.dp)
                    .padding(24.dp)
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF0F172A),
                border = BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                                .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_trash),
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Text(
                            text = stringResource(R.string.speed_test_clear_dialog_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }

                    Text(
                        text = stringResource(R.string.speed_test_clear_dialog_desc),
                        fontSize = 12.5.sp,
                        color = TextMuted,
                        lineHeight = 17.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.05f),
                            border = BorderStroke(1.dp, Color(0xFF1E293B))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.action_cancel),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextWhite
                                )
                            }
                        }

                        Surface(
                            onClick = onConfirm,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.20f),
                            border = BorderStroke(1.dp, Color(0xFFEF4444))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.speed_test_btn_delete_all),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEF4444)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

