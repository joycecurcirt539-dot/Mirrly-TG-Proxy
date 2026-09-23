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
import androidx.compose.foundation.interaction.MutableInteractionSource
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

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .adaptiveContainerWidth(600.dp)
                .fillMaxHeight()
                .align(Alignment.TopCenter)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 10.dp
                )
                .adaptiveContentPadding()
        ) {
            val compact = maxHeight < 690.dp
            val sectionGap = if (compact) 5.dp else 8.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (compact) 8.dp else 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SpeedTestHeaderCard(
                    targetDomain = if (activeWorker.isDeveloperWorker && isSocks5) activeWorker.name else targetDomain,
                    edgeColo = testState.edgeColo,
                    isSocks5 = isSocks5,
                    compact = compact
                )

                Spacer(modifier = Modifier.height(sectionGap))

                SpeedTestPhaseStepper(
                    stage = testState.stage,
                    isSocks5 = isSocks5,
                    compact = compact
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    SpeedometerGauge(
                        currentSpeedMbps = if (testState.stage == SpeedTestStage.COMPLETED) {
                            testState.downloadSpeedMbps
                        } else {
                            testState.currentSpeedMbps
                        },
                        stage = testState.stage,
                        progress = testState.progress,
                        isSocks5 = isSocks5,
                        compact = compact,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (engine.isRunning) {
                                engine.cancelTest()
                            } else {
                                engine.startTest(targetDomain, socks5Port)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(sectionGap))

                SpeedMetricsGrid(
                    state = testState,
                    isSocks5 = isSocks5,
                    compact = compact
                )

                Spacer(modifier = Modifier.height(sectionGap))

                when {
                    testState.stage == SpeedTestStage.COMPLETED -> CompactSpeedTestResult(
                        state = testState,
                        isSocks5 = isSocks5,
                        compact = compact
                    )
                    testState.stage == SpeedTestStage.ERROR && testState.errorDetail != null -> SpeedTestErrorCard(
                        errorMessage = testState.errorDetail ?: stringResource(R.string.speed_test_error_conn)
                    )
                    else -> {
                        val safePoints = testState.sparklinePoints.filter { it.isFinite() && it >= 0f }
                        if (safePoints.size >= 2) {
                            SpeedWaveformCard(
                                points = safePoints,
                                peakSpeed = testState.peakSpeedMbps,
                                isSocks5 = isSocks5,
                                compact = true
                            )
                        }
                    }
                }

            }
        }

        // Top Navigation Bar
        SpeedTestTopBar(
            onBack = handleBack,
            isSocks5 = isSocks5
        )

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
    isSocks5: Boolean,
    compact: Boolean = false
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 42.dp else 48.dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val nodeY = if (compact) 10.dp.toPx() else 12.dp.toPx()
            repeat(3) { index ->
                val startX = size.width * ((index * 2 + 1) / 8f)
                val endX = size.width * ((index * 2 + 3) / 8f)
                val completed = activeStep > index + 1 || stage == SpeedTestStage.COMPLETED
                drawLine(
                    color = if (completed) accentColor.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.10f),
                    start = Offset(startX + 11.dp.toPx(), nodeY),
                    end = Offset(endX - 11.dp.toPx(), nodeY),
                    strokeWidth = 1.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            verticalAlignment = Alignment.Top
        ) {
            phases.forEach { (stepNum, stepTitle) ->
                val isCompleted = activeStep > stepNum || stage == SpeedTestStage.COMPLETED
                val isActive = activeStep == stepNum && stage != SpeedTestStage.COMPLETED

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(if (compact) 20.dp else 24.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCompleted -> accentColor
                                    isActive -> accentColor.copy(alpha = 0.18f)
                                    else -> Color.Transparent
                                }
                            )
                            .border(
                                width = if (isActive) 1.5.dp else 1.dp,
                                color = when {
                                    isCompleted || isActive -> accentColor
                                    else -> Color.White.copy(alpha = 0.16f)
                                },
                                shape = CircleShape
                            )
                    ) {
                        if (isCompleted) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_check),
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(if (compact) 11.dp else 13.dp)
                            )
                        } else {
                            Text(
                                text = "$stepNum",
                                fontSize = if (compact) 9.sp else 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isActive) accentColor else TextMuted.copy(alpha = 0.65f)
                            )
                        }
                    }

                    Text(
                        text = stepTitle,
                        fontSize = if (compact) 8.sp else 9.sp,
                        fontWeight = if (isActive || isCompleted) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            isActive -> accentColor
                            isCompleted -> TextWhite.copy(alpha = 0.88f)
                            else -> TextMuted.copy(alpha = 0.58f)
                        },
                        maxLines = 1
                    )
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
    isSocks5: Boolean,
    compact: Boolean = false
) {
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 28.dp else 32.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_diag_worker),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(if (compact) 12.dp else 14.dp)
            )
            Text(
                text = targetDomain,
                fontSize = if (compact) 9.5.sp else 10.5.sp,
                color = TextWhite.copy(alpha = 0.82f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (edgeColo != "—") ActiveGreenLed else TextMuted.copy(alpha = 0.55f))
            )
            Text(
                text = "POP $edgeColo",
                fontSize = if (compact) 8.5.sp else 9.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (edgeColo != "—") TextWhite.copy(alpha = 0.78f) else TextMuted.copy(alpha = 0.62f)
            )
        }
    }
}

@Composable
private fun SpeedometerGauge(
    currentSpeedMbps: Double,
    stage: SpeedTestStage,
    progress: Float,
    isSocks5: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val primaryAccent = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed
    val secondaryAccent = if (isSocks5) Color(0xFF7C4DFF) else Color(0xFF00E5FF)
    val hotAccent = if (isSocks5) Color(0xFFFF4FD8) else Color(0xFF00E5FF)
    val isTesting = stage == SpeedTestStage.PING ||
        stage == SpeedTestStage.DOWNLOAD ||
        stage == SpeedTestStage.UPLOAD ||
        stage == SpeedTestStage.ANALYSIS

    val safeSpeed = if (currentSpeedMbps.isFinite() && currentSpeedMbps >= 0.0) currentSpeedMbps else 0.0
    val maxGaugeSpeed = 120.0
    val targetFraction = (safeSpeed / maxGaugeSpeed).coerceIn(0.0, 1.0).toFloat()

    val animatedFraction by animateFloatAsState(
        targetValue = if (targetFraction.isFinite()) targetFraction else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "gaugeFraction"
    )

    val motion = rememberInfiniteTransition(label = "speedCoreMotion")
    val orbitRotation by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isTesting) 4300 else 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "speedOrbitRotation"
    )
    val counterRotation by motion.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isTesting) 6100 else 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "speedCounterRotation"
    )
    val breathe by motion.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speedCoreBreathe"
    )
    val energyLevel = if (isTesting) 1f else 0.55f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 7.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(if (compact) 218.dp else 270.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = size.minDimension * 0.445f
                val progressRadius = size.minDimension * 0.375f
                val innerRadius = size.minDimension * 0.292f
                val safeAnimFrac = if (animatedFraction.isFinite()) animatedFraction.coerceIn(0f, 1f) else 0f
                val activeSweep = (270f * safeAnimFrac).coerceIn(0f, 270f)

                // Soft energy atmosphere with no opaque dial background.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryAccent.copy(alpha = 0.12f * breathe * energyLevel),
                            secondaryAccent.copy(alpha = 0.035f * energyLevel),
                            Color.Transparent
                        ),
                        center = center,
                        radius = outerRadius * 1.12f
                    ),
                    radius = outerRadius * 1.12f,
                    center = center
                )

                // Free-floating orbital ribbons inherited from the main power button.
                drawArc(
                    color = primaryAccent.copy(alpha = 0.30f * breathe * energyLevel),
                    startAngle = orbitRotation - 26f,
                    sweepAngle = 78f,
                    useCenter = false,
                    topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                    size = Size(outerRadius * 2f, outerRadius * 2f),
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = hotAccent.copy(alpha = 0.22f * energyLevel),
                    startAngle = counterRotation + 106f,
                    sweepAngle = 48f,
                    useCenter = false,
                    topLeft = Offset(center.x - (outerRadius - 7.dp.toPx()), center.y - (outerRadius - 7.dp.toPx())),
                    size = Size((outerRadius - 7.dp.toPx()) * 2f, (outerRadius - 7.dp.toPx()) * 2f),
                    style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
                )

                // Spark constellation moving around the gauge.
                repeat(18) { index ->
                    val baseAngle = index * 20f + orbitRotation * if (index % 2 == 0) 0.18f else -0.11f
                    val angleRad = Math.toRadians(baseAngle.toDouble())
                    val radius = outerRadius + ((index % 3) - 1) * 3.dp.toPx()
                    val sparkle = (0.22f + 0.42f * ((sin(angleRad + orbitRotation / 90f) + 1.0) / 2.0).toFloat()) * energyLevel
                    val point = Offset(
                        center.x + cos(angleRad).toFloat() * radius,
                        center.y + sin(angleRad).toFloat() * radius
                    )
                    drawCircle(
                        color = if (index % 4 == 0) hotAccent.copy(alpha = sparkle) else primaryAccent.copy(alpha = sparkle),
                        radius = if (index % 6 == 0) 1.7.dp.toPx() else 0.85.dp.toPx(),
                        center = point
                    )
                }

                // Minimal segmented scale: inactive marks almost disappear into the background.
                val segmentCount = 30
                repeat(segmentCount) { index ->
                    val segmentFraction = index.toFloat() / (segmentCount - 1)
                    val segmentStart = 135f + segmentFraction * 270f
                    val isLit = segmentFraction <= safeAnimFrac
                    val segmentColor = when {
                        !isLit -> Color.White.copy(alpha = 0.055f)
                        segmentFraction > 0.82f -> Color(0xFFFFC857)
                        segmentFraction > 0.56f -> hotAccent
                        else -> primaryAccent
                    }
                    drawArc(
                        color = segmentColor.copy(alpha = if (isLit) 0.90f else 0.055f),
                        startAngle = segmentStart,
                        sweepAngle = 5.6f,
                        useCenter = false,
                        topLeft = Offset(center.x - progressRadius, center.y - progressRadius),
                        size = Size(progressRadius * 2f, progressRadius * 2f),
                        style = Stroke(
                            width = if (isLit) 4.8.dp.toPx() else 2.1.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    )
                }

                // Thin continuous energy line underneath the luminous segments.
                if (activeSweep > 0.4f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(secondaryAccent, primaryAccent, hotAccent, Color(0xFFFFC857)),
                            center = center
                        ),
                        startAngle = 135f,
                        sweepAngle = activeSweep,
                        useCenter = false,
                        topLeft = Offset(center.x - (progressRadius - 7.dp.toPx()), center.y - (progressRadius - 7.dp.toPx())),
                        size = Size((progressRadius - 7.dp.toPx()) * 2f, (progressRadius - 7.dp.toPx()) * 2f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Breathing nucleus and rotating inner scan arc.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryAccent.copy(alpha = 0.10f * breathe),
                            Color(0xFF0A0D15).copy(alpha = 0.22f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = innerRadius
                    ),
                    radius = innerRadius,
                    center = center
                )
                drawCircle(
                    color = primaryAccent.copy(alpha = 0.16f + 0.12f * breathe),
                    radius = innerRadius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(Color.Transparent, primaryAccent.copy(alpha = 0.9f), Color.Transparent),
                        center = center
                    ),
                    startAngle = counterRotation,
                    sweepAngle = 96f,
                    useCenter = false,
                    topLeft = Offset(center.x - innerRadius, center.y - innerRadius),
                    size = Size(innerRadius * 2f, innerRadius * 2f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                // Bright progress head instead of a mechanical needle.
                val headAngle = Math.toRadians((135f + activeSweep).toDouble())
                val head = Offset(
                    center.x + cos(headAngle).toFloat() * progressRadius,
                    center.y + sin(headAngle).toFloat() * progressRadius
                )
                if (activeSweep > 0.4f) {
                    drawCircle(primaryAccent.copy(alpha = 0.12f * breathe), 9.dp.toPx(), head)
                    drawCircle(hotAccent.copy(alpha = 0.45f * breathe), 4.2.dp.toPx(), head)
                    drawCircle(Color.White.copy(alpha = 0.95f), 1.7.dp.toPx(), head)
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = if (compact) 2.dp else 3.dp)
            ) {
                RollingNumberText(
                    text = String.format(Locale.US, "%.1f", safeSpeed),
                    fontSize = if (compact) 29.sp else 34.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                    color = TextWhite
                )
                Text(
                    text = stringResource(R.string.speed_test_mbps_unit),
                    fontSize = if (compact) 9.5.sp else 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryAccent.copy(alpha = 0.92f),
                    letterSpacing = 1.6.sp
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (isTesting) 6.dp else 4.dp)
                    .clip(CircleShape)
                    .background(primaryAccent.copy(alpha = if (isTesting) breathe else 0.55f))
            )
            Text(
                text = stage.title.uppercase(),
                fontSize = if (compact) 8.5.sp else 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMuted.copy(alpha = 0.82f),
                letterSpacing = 0.7.sp
            )
            if (!isTesting) {
                Text(
                    text = "• НАЖМИТЕ НА СПИДОМЕТР",
                    fontSize = if (compact) 7.5.sp else 8.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = primaryAccent.copy(alpha = 0.72f),
                    letterSpacing = 0.45.sp
                )
            }
        }
    }
}

@Composable
private fun SpeedMetricsGrid(
    state: SpeedTestLiveState,
    isSocks5: Boolean,
    compact: Boolean = false
) {
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 66.dp else 74.dp)
            .clip(RoundedCornerShape(15.dp))
            .border(1.dp, accentColor.copy(alpha = 0.20f), RoundedCornerShape(15.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompactMetricColumn(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_diag_rtt,
            title = stringResource(R.string.speed_test_stat_ping),
            value = if (state.pingMs > 0) "${state.pingMs}" else "—",
            unit = "мс",
            accentColor = Color(0xFF38BDF8),
            compact = compact
        )
        MetricDivider(compact)
        CompactMetricColumn(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_diag_jitter,
            title = stringResource(R.string.speed_test_stat_jitter),
            value = if (state.pingMs > 0) "±${state.jitterMs}" else "—",
            unit = "мс",
            accentColor = Color(0xFFFF4FD8),
            compact = compact
        )
        MetricDivider(compact)
        CompactMetricColumn(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_arrow_down,
            title = stringResource(R.string.speed_test_stat_download),
            value = if (state.downloadSpeedMbps > 0) String.format(Locale.US, "%.1f", state.downloadSpeedMbps) else "—",
            unit = stringResource(R.string.speed_test_mbps_unit),
            accentColor = accentColor,
            compact = compact
        )
        MetricDivider(compact)
        CompactMetricColumn(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_arrow_up,
            title = stringResource(R.string.speed_test_stat_upload),
            value = if (state.uploadSpeedMbps > 0) String.format(Locale.US, "%.1f", state.uploadSpeedMbps) else "—",
            unit = stringResource(R.string.speed_test_mbps_unit),
            accentColor = Color(0xFFFFC857),
            compact = compact
        )
    }
}

@Composable
private fun RowScope.MetricDivider(compact: Boolean) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(if (compact) 34.dp else 42.dp)
            .background(Color.White.copy(alpha = 0.10f))
    )
}

@Composable
private fun CompactMetricColumn(
    modifier: Modifier,
    iconRes: Int,
    title: String,
    value: String,
    unit: String,
    accentColor: Color,
    compact: Boolean
) {
    Column(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(if (compact) 10.dp else 11.dp)
            )
            Text(
                text = title,
                color = TextMuted.copy(alpha = 0.76f),
                fontSize = if (compact) 7.sp else 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        RollingNumberText(
            text = value,
            color = TextWhite,
            fontSize = if (compact) 13.sp else 15.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.3).sp
        )
        Text(
            text = unit,
            color = accentColor.copy(alpha = 0.78f),
            fontSize = if (compact) 7.sp else 8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
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
    accentColor: Color,
    compact: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(16.dp))
            .padding(if (compact) 7.dp else 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(if (compact) 11.dp else 14.dp)
                )
                Text(
                    text = title,
                    fontSize = if (compact) 8.5.sp else 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
            }

            Text(
                text = value,
                fontSize = if (compact) 12.5.sp else 14.5.sp,
                fontWeight = FontWeight.Black,
                color = TextWhite
            )

            Text(
                text = subValue,
                fontSize = if (compact) 8.5.sp else 9.5.sp,
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
    isSocks5: Boolean,
    compact: Boolean = false
) {
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed
    val safePoints = points.filter { it.isFinite() && it >= 0f }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(18.dp))
            .padding(if (compact) 8.dp else 11.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.speed_test_chart_header),
                    fontSize = if (compact) 9.sp else 9.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.6.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(6.dp))

                val safePeak = if (peakSpeed.isFinite() && peakSpeed >= 0) peakSpeed else 0.0
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = accentColor.copy(alpha = 0.12f),
                    border = BorderStroke(0.8.dp, accentColor.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = stringResource(R.string.speed_test_chart_peak, String.format(Locale.US, "%.1f", safePeak)),
                        fontSize = if (compact) 8.5.sp else 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 38.dp else 50.dp)
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
private fun SpeedTestReadyStrip(
    stage: SpeedTestStage,
    isSocks5: Boolean,
    compact: Boolean
) {
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed
    val pulse = rememberInfiniteTransition(label = "readyStripPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "readyStripAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 38.dp else 42.dp)
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, accentColor.copy(alpha = 0.18f), RoundedCornerShape(13.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = pulseAlpha))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stage.title.uppercase(),
            color = TextWhite.copy(alpha = 0.82f),
            fontSize = if (compact) 9.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactSpeedTestResult(
    state: SpeedTestLiveState,
    isSocks5: Boolean,
    compact: Boolean
) {
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed
    val report = state.suitability

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 66.dp else 74.dp),
        shape = RoundedCornerShape(15.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.24f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 9.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val gradeText = state.qualityGrade.substringBefore(" (").trim()
            val gradeFontSize = when {
                gradeText.length >= 14 -> if (compact) 6.4.sp else 7.2.sp
                gradeText.length >= 10 -> if (compact) 7.0.sp else 8.0.sp
                gradeText.length >= 7  -> if (compact) 8.2.sp else 9.2.sp
                else                   -> if (compact) 10.0.sp else 11.5.sp
            }
            Box(
                modifier = Modifier
                    .size(if (compact) 46.dp else 52.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, accentColor.copy(alpha = 0.72f), CircleShape)
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = gradeText,
                    color = accentColor,
                    fontSize = gradeFontSize,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    letterSpacing = if (gradeText.length >= 10) (-0.4).sp else (-0.1).sp
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = stringResource(R.string.speed_test_report_header),
                    color = TextWhite.copy(alpha = 0.88f),
                    fontSize = if (compact) 8.5.sp else 9.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.6.sp,
                    maxLines = 1
                )
                Text(
                    text = report.summary,
                    color = TextMuted,
                    fontSize = if (compact) 9.sp else 10.5.sp,
                    lineHeight = if (compact) 11.sp else 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                painter = painterResource(id = R.drawable.ic_stat_proxy_connected),
                contentDescription = null,
                tint = accentColor.copy(alpha = 0.75f),
                modifier = Modifier.size(if (compact) 17.dp else 20.dp)
            )
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
    compact: Boolean = false,
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
        color = Color.Transparent,
        border = BorderStroke(1.2.dp, if (isTesting) Color(0xFFEF4444) else accentColor.copy(alpha = 0.82f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 44.dp else 50.dp)
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
