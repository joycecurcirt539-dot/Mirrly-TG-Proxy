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
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.SpeedTestLiveState
import com.mirrly.tgproxy.core.SpeedTestStage
import com.mirrly.tgproxy.core.TelegramDcBenchmark
import com.mirrly.tgproxy.core.TelegramSuitabilityReport
import com.mirrly.tgproxy.ui.theme.*
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Экран тестирования пропускной способности и сетевой стабильности туннеля Mirrly TG Proxy.
 * Спроектирован строго по дизайн-системе MirrlyUI (Zero Background / Outline-Only, Zero-Popup, springPress).
 */
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
        val custom = app.config.customCfDomain.trim()
        if (custom.isNotEmpty()) custom else "kws2.pclead.co.uk"
    }

    val socks5Port = if (server.isRunning) server.config.socks5Port else 10808
    val socks5User = if (server.config.hasSocks5Auth) server.config.socks5Username else null
    val socks5Pass = if (server.config.hasSocks5Auth) server.config.socks5Password else null

    val isTesting = testState.stage == SpeedTestStage.DC_BENCHMARK ||
        testState.stage == SpeedTestStage.DOWNLOAD ||
        testState.stage == SpeedTestStage.UPLOAD ||
        testState.stage == SpeedTestStage.ANALYSIS

    val onToggleTest: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (engine.isRunning) {
            engine.cancelTest()
        } else {
            engine.startTest(
                targetDomain = targetDomain,
                socks5Port = socks5Port,
                socks5User = socks5User,
                socks5Pass = socks5Pass
            )
        }
    }

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

    val accentColor = if (isSocks5) Socks5Accent else MtprotoAccent
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .adaptiveContainerWidth(600.dp)
                .fillMaxHeight()
                .align(Alignment.TopCenter)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 52.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp
                )
                .adaptiveContentPadding()
        ) {
            val compact = maxHeight < 700.dp
            val sectionGap = if (compact) 8.dp else 12.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdges(topFadeHeight = 16.dp, bottomFadeHeight = 28.dp)
                    .verticalScroll(scrollState)
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

                Spacer(modifier = Modifier.height(sectionGap))

                // Диск спидометра с кинетическими контурными кольцами
                SpeedometerGauge(
                    currentSpeedMbps = if (testState.stage == SpeedTestStage.COMPLETED) {
                        testState.downloadSpeedMbps
                    } else {
                        testState.currentSpeedMbps
                    },
                    stage = testState.stage,
                    isSocks5 = isSocks5,
                    compact = compact,
                    onClick = onToggleTest
                )

                Spacer(modifier = Modifier.height(sectionGap))

                // Квадратная сетка базовых телеметрических показателей
                SpeedMetricsGrid(
                    state = testState,
                    isSocks5 = isSocks5,
                    compact = compact
                )

                Spacer(modifier = Modifier.height(sectionGap))

                // Контекстная панель контента в зависимости от текущего этапа
                when {
                    testState.stage == SpeedTestStage.COMPLETED -> {
                        SpeedTestSummaryReportCard(
                            report = testState.suitability,
                            state = testState,
                            isSocks5 = isSocks5,
                            compact = compact,
                            onRetry = onToggleTest
                        )
                    }
                    testState.stage == SpeedTestStage.ERROR && testState.errorDetail != null -> {
                        SpeedTestErrorCard(
                            errorMessage = testState.errorDetail ?: stringResource(R.string.speed_test_error_conn),
                            onRetry = onToggleTest
                        )
                    }
                    testState.stage == SpeedTestStage.DOWNLOAD || testState.stage == SpeedTestStage.UPLOAD -> {
                        val safePoints = testState.sparklinePoints.filter { it.isFinite() && it >= 0f }
                        if (safePoints.size >= 2) {
                            SpeedWaveformCard(
                                points = safePoints,
                                peakSpeed = testState.peakSpeedMbps,
                                isSocks5 = isSocks5,
                                compact = compact
                            )
                            Spacer(modifier = Modifier.height(sectionGap))
                        }
                        TelegramDcMatrixCard(
                            dcList = testState.dcBenchmarks,
                            activeDcId = testState.activeDcId,
                            isSocks5 = isSocks5,
                            compact = compact
                        )
                    }
                    else -> {
                        // IDLE или DC_BENCHMARK: отображение интерактивной матрицы пяти DC Telegram
                        TelegramDcMatrixCard(
                            dcList = testState.dcBenchmarks,
                            activeDcId = testState.activeDcId,
                            isSocks5 = isSocks5,
                            compact = compact
                        )
                    }
                }

                Spacer(modifier = Modifier.height(sectionGap + 4.dp))

                // Главная управляющая контурная кнопка
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            width = 1.dp,
                            color = if (isTesting) Color(0xFFFF1744).copy(alpha = 0.6f) else accentColor.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .springPress(onClick = onToggleTest)
                        .padding(vertical = if (compact) 12.dp else 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                id = when {
                                    isTesting -> R.drawable.ic_speed_eco
                                    testState.stage == SpeedTestStage.COMPLETED -> R.drawable.ic_refresh
                                    else -> R.drawable.ic_speed_turbo
                                }
                            ),
                            contentDescription = null,
                            tint = if (isTesting) Color(0xFFFF1744) else accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = when {
                                isTesting -> stringResource(R.string.speed_test_btn_stop)
                                testState.stage == SpeedTestStage.COMPLETED -> stringResource(R.string.speed_test_btn_retry)
                                else -> stringResource(R.string.speed_test_btn_start)
                            },
                            color = if (isTesting) Color(0xFFFF1744) else accentColor,
                            fontSize = if (compact) 12.sp else 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Верхняя навигационная панель
        SpeedTestTopBar(
            onBack = handleBack,
            isSocks5 = isSocks5
        )
    }
}

/**
 * Верхняя строка экрана с кнопкой назад и бейджем активного протокола.
 */
@Composable
private fun SpeedTestTopBar(
    onBack: () -> Unit,
    isSocks5: Boolean
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = if (isSocks5) Socks5Accent else MtprotoAccent

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 2.dp),
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
                modifier = Modifier
                    .size(40.dp)
                    .springPress(onClick = onBack)
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
                    letterSpacing = 1.1.sp,
                    color = TextWhite
                )

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = if (isSocks5) "SOCKS5" else "MTPROTO",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.size(40.dp))
        }
    }
}

/**
 * Карточка с информацией о домене туннелирования и Anycast POP узле Cloudflare.
 */
@Composable
private fun SpeedTestHeaderCard(
    targetDomain: String,
    edgeColo: String,
    isSocks5: Boolean,
    compact: Boolean = false
) {
    val accentColor = if (isSocks5) Socks5Accent else MtprotoAccent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_diag_worker),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(if (compact) 13.dp else 15.dp)
            )
            Column {
                Text(
                    text = stringResource(R.string.speed_test_tunnel_node),
                    fontSize = 8.sp,
                    color = TextMuted,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = targetDomain,
                    fontSize = if (compact) 10.sp else 11.sp,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, AmoledBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (edgeColo != "—") accentColor else TextMuted.copy(alpha = 0.5f))
            )
            Text(
                text = "POP $edgeColo",
                fontSize = if (compact) 9.sp else 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (edgeColo != "—") TextWhite else TextMuted
            )
        }
    }
}

/**
 * 4-этапный индикатор фаз замера (DC Бенчмарк -> Входящая -> Исходящая -> Сводка).
 */
@Composable
private fun SpeedTestPhaseStepper(
    stage: SpeedTestStage,
    isSocks5: Boolean,
    compact: Boolean = false
) {
    val accentColor = if (isSocks5) Socks5Accent else MtprotoAccent

    val phases = listOf(
        Pair(1, stringResource(R.string.speed_test_phase_dc)),
        Pair(2, stringResource(R.string.speed_test_phase_download)),
        Pair(3, stringResource(R.string.speed_test_phase_upload)),
        Pair(4, stringResource(R.string.speed_test_phase_summary))
    )

    val activeStep = when (stage) {
        SpeedTestStage.IDLE -> 0
        SpeedTestStage.DC_BENCHMARK, SpeedTestStage.PING -> 1
        SpeedTestStage.DOWNLOAD -> 2
        SpeedTestStage.UPLOAD -> 3
        SpeedTestStage.ANALYSIS, SpeedTestStage.COMPLETED -> 4
        SpeedTestStage.CANCELLED, SpeedTestStage.ERROR -> 0
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 44.dp else 50.dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val nodeY = if (compact) 10.dp.toPx() else 12.dp.toPx()
            repeat(3) { index ->
                val startX = size.width * ((index * 2 + 1) / 8f)
                val endX = size.width * ((index * 2 + 3) / 8f)
                val completed = activeStep > index + 1 || stage == SpeedTestStage.COMPLETED
                drawLine(
                    color = if (completed) accentColor.copy(alpha = 0.75f) else AmoledBorder,
                    start = Offset(startX + 12.dp.toPx(), nodeY),
                    end = Offset(endX - 12.dp.toPx(), nodeY),
                    strokeWidth = 1.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top
        ) {
            phases.forEach { (stepNum, stepTitle) ->
                val isCompleted = activeStep > stepNum || stage == SpeedTestStage.COMPLETED
                val isActive = activeStep == stepNum && stage != SpeedTestStage.COMPLETED

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(if (compact) 20.dp else 24.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCompleted -> accentColor
                                    isActive -> accentColor.copy(alpha = 0.16f)
                                    else -> Color.Transparent
                                }
                            )
                            .border(
                                width = if (isActive) 1.5.dp else 1.dp,
                                color = when {
                                    isCompleted || isActive -> accentColor
                                    else -> AmoledBorder
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
                                color = if (isActive) accentColor else TextMuted
                            )
                        }
                    }

                    Text(
                        text = stepTitle,
                        fontSize = if (compact) 8.sp else 9.sp,
                        fontWeight = if (isActive || isCompleted) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            isActive -> accentColor
                            isCompleted -> TextWhite
                            else -> TextMuted
                        },
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Минималистичный кибернетический спидометр со шкалой прогресса и кинетическими дугами.
 */
@Composable
private fun SpeedometerGauge(
    currentSpeedMbps: Double,
    stage: SpeedTestStage,
    isSocks5: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val primaryAccent = if (isSocks5) Socks5Accent else MtprotoAccent
    val secondaryAccent = if (isSocks5) Color(0xFF7C4DFF) else Color(0xFF00FF87)
    val hotAccent = if (isSocks5) Color(0xFFFF4FD8) else Color(0xFF00E5FF)

    val isTesting = stage == SpeedTestStage.DC_BENCHMARK ||
        stage == SpeedTestStage.DOWNLOAD ||
        stage == SpeedTestStage.UPLOAD ||
        stage == SpeedTestStage.ANALYSIS

    val safeSpeed = if (currentSpeedMbps.isFinite() && currentSpeedMbps >= 0.0) currentSpeedMbps else 0.0
    val maxGaugeSpeed = 150.0
    val targetFraction = (safeSpeed / maxGaugeSpeed).coerceIn(0.0, 1.0).toFloat()

    val animatedFraction by animateFloatAsState(
        targetValue = if (targetFraction.isFinite()) targetFraction else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow),
        label = "gaugeFraction"
    )

    val motion = rememberInfiniteTransition(label = "gaugeMotion")
    val orbitRotation by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isTesting) 4500 else 10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gaugeOrbit"
    )
    val breathe by motion.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gaugeBreathe"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(if (compact) 210.dp else 256.dp)
                .clip(CircleShape)
                .springPress(onClick = onClick)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = size.minDimension * 0.44f
                val progressRadius = size.minDimension * 0.37f
                val innerRadius = size.minDimension * 0.285f
                val safeAnimFrac = animatedFraction.coerceIn(0f, 1f)
                val activeSweep = (270f * safeAnimFrac).coerceIn(0f, 270f)

                // 1. Мягкая световая атмосфера (Zero Background: без глухого черного оверлея)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryAccent.copy(alpha = 0.10f * breathe),
                            secondaryAccent.copy(alpha = 0.03f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = outerRadius * 1.15f
                    ),
                    radius = outerRadius * 1.15f,
                    center = center
                )

                // 2. Внешняя орбитальная контурная дуга
                drawArc(
                    color = primaryAccent.copy(alpha = 0.32f * breathe),
                    startAngle = orbitRotation - 20f,
                    sweepAngle = 75f,
                    useCenter = false,
                    topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                    size = Size(outerRadius * 2f, outerRadius * 2f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                // 3. Сегментированная шкала спидометра (30 делений)
                val segmentCount = 30
                repeat(segmentCount) { index ->
                    val segmentFraction = index.toFloat() / (segmentCount - 1)
                    val segmentStart = 135f + segmentFraction * 270f
                    val isLit = segmentFraction <= safeAnimFrac
                    val segmentColor = when {
                        !isLit -> Color.White.copy(alpha = 0.06f)
                        segmentFraction > 0.82f -> Color(0xFFFFC857)
                        segmentFraction > 0.55f -> hotAccent
                        else -> primaryAccent
                    }
                    drawArc(
                        color = segmentColor.copy(alpha = if (isLit) 0.92f else 0.06f),
                        startAngle = segmentStart,
                        sweepAngle = 5.5f,
                        useCenter = false,
                        topLeft = Offset(center.x - progressRadius, center.y - progressRadius),
                        size = Size(progressRadius * 2f, progressRadius * 2f),
                        style = Stroke(
                            width = if (isLit) 4.5.dp.toPx() else 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    )
                }

                // 4. Тонкая светящаяся полоса под активными сегментами
                if (activeSweep > 0.5f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(secondaryAccent, primaryAccent, hotAccent, Color(0xFFFFC857)),
                            center = center
                        ),
                        startAngle = 135f,
                        sweepAngle = activeSweep,
                        useCenter = false,
                        topLeft = Offset(center.x - (progressRadius - 6.dp.toPx()), center.y - (progressRadius - 6.dp.toPx())),
                        size = Size((progressRadius - 6.dp.toPx()) * 2f, (progressRadius - 6.dp.toPx()) * 2f),
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // 5. Внутренний контурный обод ядра
                drawCircle(
                    color = primaryAccent.copy(alpha = 0.16f),
                    radius = innerRadius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )

                // 6. Светящаяся точка-наконечник
                val headAngle = Math.toRadians((135f + activeSweep).toDouble())
                val head = Offset(
                    center.x + cos(headAngle).toFloat() * progressRadius,
                    center.y + sin(headAngle).toFloat() * progressRadius
                )
                if (activeSweep > 0.5f) {
                    drawCircle(primaryAccent.copy(alpha = 0.20f * breathe), 8.dp.toPx(), head)
                    drawCircle(hotAccent.copy(alpha = 0.55f * breathe), 3.8.dp.toPx(), head)
                    drawCircle(Color.White, 1.6.dp.toPx(), head)
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                RollingNumberText(
                    text = String.format(Locale.US, "%.1f", safeSpeed),
                    fontSize = if (compact) 28.sp else 34.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                    color = TextWhite
                )
                Text(
                    text = stringResource(R.string.speed_test_mbps_unit),
                    fontSize = if (compact) 9.5.sp else 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryAccent,
                    letterSpacing = 1.4.sp
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                color = TextMuted,
                letterSpacing = 0.6.sp
            )
            if (!isTesting) {
                Text(
                    text = "· " + stringResource(R.string.speed_test_gauge_tap_start),
                    fontSize = if (compact) 7.5.sp else 8.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = primaryAccent.copy(alpha = 0.75f),
                    letterSpacing = 0.4.sp
                )
            }
        }
    }
}

/**
 * Интерактивная матрица пяти официальных дата-центров Telegram (DC 1..5).
 * Последовательно отображает замер задержки через туннель с выделением ключевых DC2 и DC4.
 */
@Composable
private fun TelegramDcMatrixCard(
    dcList: List<TelegramDcBenchmark>,
    activeDcId: Int,
    isSocks5: Boolean,
    compact: Boolean = false
) {
    val accentColor = if (isSocks5) Socks5Accent else MtprotoAccent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, AmoledBorder, RoundedCornerShape(16.dp))
            .padding(if (compact) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.speed_test_dc_matrix_title),
                    fontSize = if (compact) 9.5.sp else 10.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                    color = TextWhite
                )
                Text(
                    text = stringResource(R.string.speed_test_dc_matrix_subtitle),
                    fontSize = if (compact) 8.sp else 9.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            dcList.forEach { dc ->
                TelegramDcRowItem(
                    dc = dc,
                    isActive = dc.dcId == activeDcId || dc.inProgress,
                    accentColor = accentColor,
                    compact = compact
                )
            }
        }
    }
}

/**
 * Строка элемента дата-центра в матрице DC Telegram.
 */
@Composable
private fun TelegramDcRowItem(
    dc: TelegramDcBenchmark,
    isActive: Boolean,
    accentColor: Color,
    compact: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dcPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dcPulseAlpha"
    )

    val itemBorderColor = when {
        isActive -> accentColor
        dc.isKeyDc && dc.isSuccess -> accentColor.copy(alpha = 0.45f)
        dc.isSuccess -> Color.White.copy(alpha = 0.12f)
        else -> AmoledBorder
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, itemBorderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // DC ID pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(
                    width = 1.dp,
                    color = if (dc.isKeyDc) accentColor.copy(alpha = 0.7f) else AmoledBorder,
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = dc.name,
                fontSize = if (compact) 8.5.sp else 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (dc.isKeyDc) accentColor else TextWhite
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Role & Location
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = dc.role,
                    fontSize = if (compact) 9.5.sp else 10.5.sp,
                    fontWeight = if (dc.isKeyDc) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (dc.isKeyDc) TextWhite else TextWhite.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (dc.isKeyDc) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(0.8.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.speed_test_dc_key_badge),
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                }
            }
            Text(
                text = "${dc.location} · ${dc.ip}",
                fontSize = if (compact) 8.sp else 8.5.sp,
                color = TextMuted,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Status indicator & RTT
        when {
            dc.inProgress || isActive -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = pulseAlpha))
                    )
                    Text(
                        text = stringResource(R.string.speed_test_dc_status_measuring),
                        fontSize = if (compact) 8.5.sp else 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor
                    )
                }
            }
            dc.isSuccess && dc.rttMs > 0 -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${dc.rttMs} мс",
                        fontSize = if (compact) 10.sp else 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (dc.isKeyDc) accentColor else TextWhite
                    )
                    if (dc.jitterMs > 0) {
                        Text(
                            text = "±${dc.jitterMs}",
                            fontSize = 8.5.sp,
                            color = TextMuted
                        )
                    }
                }
            }
            dc.rttMs < 0 && dc.errorDetail != null -> {
                Text(
                    text = stringResource(R.string.speed_test_dc_status_unreachable),
                    fontSize = if (compact) 8.5.sp else 9.5.sp,
                    color = Color(0xFFFF1744).copy(alpha = 0.8f),
                    fontWeight = FontWeight.Medium
                )
            }
            else -> {
                Text(
                    text = "—",
                    fontSize = 11.sp,
                    color = TextMuted.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * Сетка из 4 ключевых телеметрических показателей канала связи.
 */
@Composable
private fun SpeedMetricsGrid(
    state: SpeedTestLiveState,
    isSocks5: Boolean,
    compact: Boolean = false
) {
    val accentColor = if (isSocks5) Socks5Accent else MtprotoAccent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 64.dp else 72.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp)),
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
private fun MetricDivider(compact: Boolean) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(if (compact) 30.dp else 38.dp)
            .background(AmoledBorder)
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
                color = TextMuted,
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
            color = accentColor.copy(alpha = 0.85f),
            fontSize = if (compact) 7.sp else 8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/**
 * График динамики скорости в реальном времени со спарклайном.
 */
@Composable
private fun SpeedWaveformCard(
    points: List<Float>,
    peakSpeed: Double,
    isSocks5: Boolean,
    compact: Boolean = false
) {
    val accentColor = if (isSocks5) Socks5Accent else MtprotoAccent
    val safePoints = points.filter { it.isFinite() && it >= 0f }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, AmoledBorder, RoundedCornerShape(16.dp))
            .padding(if (compact) 10.dp else 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.speed_test_chart_header),
                    fontSize = if (compact) 9.sp else 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                    color = TextMuted,
                    maxLines = 1
                )

                val safePeak = if (peakSpeed.isFinite() && peakSpeed >= 0) peakSpeed else 0.0
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = Color.Transparent,
                    border = BorderStroke(0.8.dp, accentColor.copy(alpha = 0.5f))
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
                    .height(if (compact) 42.dp else 54.dp)
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
                        colors = listOf(accentColor.copy(alpha = 0.20f), Color.Transparent),
                        startY = 0f,
                        endY = size.height
                    )
                )

                drawPath(
                    path = linePath,
                    color = accentColor,
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}

/**
 * Большой финальный сводный отчет (Summary Report Card).
 * Презентабельный блок с общим баллом 0..100, сводкой RTT ключевых DC и оценкой сценариев Telegram.
 */
@Composable
private fun SpeedTestSummaryReportCard(
    report: TelegramSuitabilityReport,
    state: SpeedTestLiveState,
    isSocks5: Boolean,
    compact: Boolean,
    onRetry: () -> Unit
) {
    val accentColor = if (isSocks5) Socks5Accent else MtprotoAccent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .padding(if (compact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
    ) {
        // Шапка отчета: Общий балл качества туннеля в стильном контурном кольце
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 60.dp else 68.dp)
                    .clip(CircleShape)
                    .border(2.dp, accentColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${report.overallScore}",
                        color = TextWhite,
                        fontSize = if (compact) 20.sp else 23.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = stringResource(R.string.speed_test_summary_score_label),
                        color = accentColor,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = state.qualityGrade,
                        fontSize = if (compact) 14.sp else 16.sp,
                        fontWeight = FontWeight.Black,
                        color = accentColor
                    )
                }
                Text(
                    text = report.summary,
                    fontSize = if (compact) 10.5.sp else 11.5.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    lineHeight = if (compact) 14.sp else 16.sp
                )
            }
        }

        // Блок 1: Сводка RTT по ключевым узлам: Telegram DC2 (чаты), DC4 (медиа), Cloudflare Edge
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.speed_test_key_nodes_title),
                fontSize = if (compact) 8.5.sp else 9.5.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp,
                color = TextMuted
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                KeyNodeRttPill(
                    modifier = Modifier.weight(1f),
                    title = "DC2 · ЧАТЫ",
                    rttMs = report.dc2RttMs,
                    accentColor = accentColor,
                    compact = compact
                )
                KeyNodeRttPill(
                    modifier = Modifier.weight(1f),
                    title = "DC4 · МЕДИА",
                    rttMs = report.dc4RttMs,
                    accentColor = accentColor,
                    compact = compact
                )
                KeyNodeRttPill(
                    modifier = Modifier.weight(1f),
                    title = "EDGE · ${report.edgeColo}",
                    rttMs = report.edgeRttMs,
                    accentColor = Color(0xFF38BDF8),
                    compact = compact
                )
            }
        }

        // Блок 2: Готовность к профильным сценариям Telegram
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.speed_test_readiness_title),
                fontSize = if (compact) 8.5.sp else 9.5.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp,
                color = TextMuted
            )

            SuitabilityRow(
                iconRes = R.drawable.ic_diag_delivery,
                scenarioTitle = stringResource(R.string.speed_test_suit_chats),
                verdict = report.chatsVerdict,
                accentColor = accentColor,
                compact = compact
            )
            SuitabilityRow(
                iconRes = R.drawable.ic_diag_voip,
                scenarioTitle = stringResource(R.string.speed_test_suit_voice),
                verdict = report.voiceVerdict,
                accentColor = Color(0xFF38BDF8),
                compact = compact
            )
            SuitabilityRow(
                iconRes = R.drawable.ic_diag_media,
                scenarioTitle = stringResource(R.string.speed_test_suit_media),
                verdict = report.mediaVerdict,
                accentColor = Color(0xFFFFC857),
                compact = compact
            )
            SuitabilityRow(
                iconRes = R.drawable.ic_diag_files,
                scenarioTitle = stringResource(R.string.speed_test_suit_video),
                verdict = report.videoVerdict,
                accentColor = Color(0xFFFF4FD8),
                compact = compact
            )
        }
    }
}

/**
 * Карточка задержки ключевого узла (DC2, DC4, Edge).
 */
@Composable
private fun KeyNodeRttPill(
    modifier: Modifier,
    title: String,
    rttMs: Long,
    accentColor: Color,
    compact: Boolean
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = if (compact) 6.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (rttMs > 0) "$rttMs мс" else "—",
            fontSize = if (compact) 11.5.sp else 13.sp,
            fontWeight = FontWeight.Black,
            color = if (rttMs > 0) TextWhite else TextMuted
        )
    }
}

/**
 * Строка сценария использования Telegram (Чаты, Звонки, Медиа, 4K).
 */
@Composable
private fun SuitabilityRow(
    iconRes: Int,
    scenarioTitle: String,
    verdict: String,
    accentColor: Color,
    compact: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(if (compact) 13.dp else 15.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = scenarioTitle,
            fontSize = if (compact) 9.5.sp else 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextWhite,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = verdict,
            fontSize = if (compact) 9.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            color = accentColor
        )
    }
}

/**
 * Карточка отображения ошибки с возможностью повторить замер.
 */
@Composable
private fun SpeedTestErrorCard(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0xFFFF1744).copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_stat_proxy_error),
                contentDescription = null,
                tint = Color(0xFFFF1744),
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.speed_test_failed),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF1744)
                )
                Text(
                    text = errorMessage,
                    fontSize = 11.sp,
                    color = TextWhite.copy(alpha = 0.85f)
                )
            }
            IconButton(
                onClick = onRetry,
                modifier = Modifier
                    .size(36.dp)
                    .springPress(onClick = onRetry)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_refresh),
                    contentDescription = null,
                    tint = TextWhite,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
