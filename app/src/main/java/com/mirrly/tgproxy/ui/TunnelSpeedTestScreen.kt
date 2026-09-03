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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.SpeedTestLiveState
import com.mirrly.tgproxy.core.SpeedTestStage
import com.mirrly.tgproxy.ui.theme.*
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
                .verticalScroll(rememberScrollState())
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                )
                .adaptiveContentPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── TOP TARGET BADGE & INFO ──
            SpeedTestHeaderCard(
                targetDomain = if (activeWorker.isDeveloperWorker && isSocks5) activeWorker.name else targetDomain,
                edgeColo = testState.edgeColo,
                isSocks5 = isSocks5
            )

            // ── HERO SPEEDOMETER GAUGE ──
            SpeedometerGauge(
                currentSpeedMbps = testState.currentSpeedMbps,
                stage = testState.stage,
                progress = testState.progress,
                isSocks5 = isSocks5
            )

            // ── 4-TILE LIVE METRICS GRID ──
            SpeedMetricsGrid(
                state = testState,
                isSocks5 = isSocks5
            )

            // ── REAL-TIME TRANSFER WAVEFORM ──
            if (testState.sparklinePoints.isNotEmpty() || testState.stage == SpeedTestStage.COMPLETED) {
                SpeedWaveformCard(
                    points = testState.sparklinePoints,
                    peakSpeed = testState.peakSpeedMbps,
                    isSocks5 = isSocks5
                )
            }

            // ── TELEGRAM SERVICE SUITABILITY MATRIX ──
            if (testState.stage == SpeedTestStage.COMPLETED) {
                TelegramSuitabilityCard(
                    report = testState.suitability,
                    grade = testState.qualityGrade,
                    isSocks5 = isSocks5
                )
            }

            // ── START / STOP / RETEST ACTION BUTTON ──
            SpeedTestActionButton(
                stage = testState.stage,
                isSocks5 = isSocks5,
                onStart = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    engine.startTest(targetDomain)
                },
                onStop = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    engine.cancelTest()
                }
            )
        }

        // Top Navigation Bar
        SpeedTestTopBar(
            onBack = onBack,
            isSocks5 = isSocks5
        )

        // Floating Cyber Particles
        CyberParticlesOverlay(modifier = Modifier.fillMaxSize())
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
                    contentDescription = "Назад",
                    tint = TextWhite,
                    modifier = Modifier.size(20.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "ТЕСТ СКОРОСТИ ТУННЕЛЯ",
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
                        text = "Узел туннелирования",
                        fontSize = 11.sp,
                        color = TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = targetDomain,
                        fontSize = 13.5.sp,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
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

    // Speedometer range 0 .. 120 Mbps (normalized)
    val maxGaugeSpeed = 100.0
    val targetFraction = (currentSpeedMbps / maxGaugeSpeed).coerceIn(0.0, 1.0).toFloat()

    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "gaugeFraction"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(240.dp)
        ) {
            Canvas(modifier = Modifier.size(220.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width / 2f - 18.dp.toPx()

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

                // Active speed glow arc
                val activeSweep = 270f * animatedFraction
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
                    val innerR = radius - 14.dp.toPx()
                    val outerR = radius - 7.dp.toPx()

                    val startX = center.x + (innerR * cos(angleRad)).toFloat()
                    val startY = center.y + (innerR * sin(angleRad)).toFloat()
                    val endX = center.x + (outerR * cos(angleRad)).toFloat()
                    val endY = center.y + (outerR * sin(angleRad)).toFloat()

                    val isHighlighted = (i.toFloat() / (tickCount - 1)) <= animatedFraction
                    drawLine(
                        color = if (isHighlighted) primaryAccent else Color(0xFF333D55),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = if (i % 2 == 0) 2.5.dp.toPx() else 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Needle indicator
                val needleAngle = 135f + (270f * animatedFraction)
                val needleRad = Math.toRadians(needleAngle.toDouble())
                val needleLen = radius - 20.dp.toPx()
                val needleX = center.x + (needleLen * cos(needleRad)).toFloat()
                val needleY = center.y + (needleLen * sin(needleRad)).toFloat()

                drawLine(
                    color = Color.White,
                    start = center,
                    end = Offset(needleX, needleY),
                    strokeWidth = 3.dp.toPx(),
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

            // Digital Central Speed Readout
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 40.dp)
            ) {
                Text(
                    text = String.format(java.util.Locale.US, "%.1f", currentSpeedMbps),
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                    color = TextWhite
                )
                Text(
                    text = "Мбит / с",
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
                if (stage == SpeedTestStage.PING || stage == SpeedTestStage.DOWNLOAD || stage == SpeedTestStage.UPLOAD) {
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
            title = "ПИНГ",
            value = if (state.pingMs > 0) "${state.pingMs} мс" else "—",
            subValue = if (state.minPingMs > 0) "Мин: ${state.minPingMs} мс" else "Задержка",
            accentColor = Color(0xFF38BDF8)
        )

        // Tile 2: Jitter
        MetricTile(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_diag_jitter,
            title = "ДЖИТТЕР",
            value = if (state.pingMs > 0) "±${state.jitterMs} мс" else "—",
            subValue = if (state.jitterMs <= 15) "Стабильно" else "Вариация",
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
            title = "ВХОДЯЩАЯ",
            value = if (state.downloadSpeedMbps > 0) "${String.format(java.util.Locale.US, "%.1f", state.downloadSpeedMbps)} Мбит/с" else "—",
            subValue = if (state.downloadedBytes > 0) "${state.downloadedBytes / (1024 * 1024)} МБ получено" else "Download",
            accentColor = accentColor
        )

        // Tile 4: Upload
        MetricTile(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_arrow_up,
            title = "ИСХОДЯЩАЯ",
            value = if (state.uploadSpeedMbps > 0) "${String.format(java.util.Locale.US, "%.1f", state.uploadSpeedMbps)} Мбит/с" else "—",
            subValue = if (state.uploadedBytes > 0) "${state.uploadedBytes / (1024 * 1024)} МБ отправлено" else "Upload",
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
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Black,
                color = TextWhite
            )

            Text(
                text = subValue,
                fontSize = 10.5.sp,
                color = TextMuted
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
                    text = "ДИНАМИКА СКОРОСТИ В РЕАЛЬНОМ ВРЕМЕНИ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = TextMuted
                )

                Text(
                    text = "Пик: ${String.format(java.util.Locale.US, "%.1f", peakSpeed)} Мбит/с",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                if (points.size < 2) return@Canvas

                val maxVal = max(10f, (points.maxOrNull() ?: 10f) * 1.15f)
                val widthPerPoint = size.width / (points.size - 1).toFloat()

                val linePath = Path()
                val fillPath = Path()

                points.forEachIndexed { i, p ->
                    val x = i.toFloat() * widthPerPoint
                    val y = size.height - (p / maxVal) * size.height

                    if (i == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, size.height)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }

                fillPath.lineTo((points.size - 1).toFloat() * widthPerPoint, size.height)
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
                        text = "ОЦЕНКА КАЧЕСТВА КАНАЛА",
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
                fontSize = 12.5.sp,
                lineHeight = 17.sp
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E293B)))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SuitabilityRow(label = "Текстовые чаты и стикеры", verdict = report.chatsVerdict, accent = ActiveGreenLed)
                SuitabilityRow(label = "Голосовые и видеозвонки", verdict = report.voiceVerdict, accent = Color(0xFF38BDF8))
                SuitabilityRow(label = "Фотографии и аудио", verdict = report.mediaVerdict, accent = ActiveGreenLed)
                SuitabilityRow(label = "Тяжелые файлы и видео", verdict = report.videoVerdict, accent = Color(0xFFFFB703))
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
private fun SpeedTestActionButton(
    stage: SpeedTestStage,
    isSocks5: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val isTesting = stage == SpeedTestStage.PING || stage == SpeedTestStage.DOWNLOAD || stage == SpeedTestStage.UPLOAD
    val accentColor = if (isSocks5) Color(0xFFB388FF) else ActiveGreenLed
    val btnText = when (stage) {
        SpeedTestStage.IDLE -> "ЗАПУСТИТЬ ТЕСТ СКОРОСТИ"
        SpeedTestStage.COMPLETED, SpeedTestStage.CANCELLED, SpeedTestStage.ERROR -> "ПОВТОРИТЬ ТЕСТ"
        else -> "ОСТАНОВИТЬ ТЕСТ"
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
