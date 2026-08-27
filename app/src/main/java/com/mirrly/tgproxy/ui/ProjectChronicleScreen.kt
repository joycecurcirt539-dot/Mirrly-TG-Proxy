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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import kotlin.math.cos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Procedural Genesis Quantum Matrix Background Canvas with ambient cyber-nebula glows and scrolling grid.
 */
@Composable
private fun GenesisQuantumMatrixCanvas(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "matrixAnim")

    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "matrixPhase"
    )

    val auraPulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "auraPulse"
    )

    val goldAccent = Color(0xFFFFB703)
    val emeraldAccent = Color(0xFF00E676)
    val cyanAccent = Color(0xFF00F5D4)
    val purpleAccent = Color(0xFFC084FC)

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 1. Top-Right Gold Cyber Nebula Glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(goldAccent.copy(alpha = 0.10f * auraPulse), Color.Transparent),
                center = Offset(w * 0.9f, h * 0.08f),
                radius = w * 0.75f * auraPulse
            ),
            radius = w * 0.75f * auraPulse,
            center = Offset(w * 0.9f, h * 0.08f)
        )

        // 2. Middle-Left Cyan/Emerald Nebula Glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    cyanAccent.copy(alpha = 0.07f * auraPulse),
                    emeraldAccent.copy(alpha = 0.03f),
                    Color.Transparent
                ),
                center = Offset(w * 0.08f, h * 0.42f),
                radius = w * 0.80f * auraPulse
            ),
            radius = w * 0.80f * auraPulse,
            center = Offset(w * 0.08f, h * 0.42f)
        )

        // 3. Bottom-Right Purple Nebula Glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(purpleAccent.copy(alpha = 0.08f * auraPulse), Color.Transparent),
                center = Offset(w * 0.92f, h * 0.85f),
                radius = w * 0.70f * auraPulse
            ),
            radius = w * 0.70f * auraPulse,
            center = Offset(w * 0.92f, h * 0.85f)
        )

        // 4. Subtle Animated Perspective Cyber Grid Lines
        val gridSpacing = 52.dp.toPx()
        val offsetY = (phase * gridSpacing) % gridSpacing

        var y = offsetY
        while (y < h) {
            val lineAlpha = ((1f - (y / h)).coerceIn(0.08f, 0.95f) * 0.035f)
            drawLine(
                color = Color(0xFF4A5D8A).copy(alpha = lineAlpha),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 0.8.dp.toPx()
            )
            y += gridSpacing
        }

        var x = 0f
        while (x < w) {
            drawLine(
                color = Color(0xFF4A5D8A).copy(alpha = 0.022f),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = 0.8.dp.toPx()
            )
            x += gridSpacing
        }
    }
}

/**
 * Procedural Genesis Quantum Canvas with 4 counter-rotating orbital rings, radiant star geometry & interactive shockwave burst.
 */
@Composable
fun GenesisChronicleCanvas(
    size: Dp = 100.dp,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val infiniteTransition = rememberInfiniteTransition(label = "genesisTransition")

    val outerRot by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerRot"
    )

    val midRot by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(9500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "midRot"
    )

    val innerRot by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "innerRot"
    )

    val corePulse by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "corePulse"
    )

    // Interactive Tap Burst Shockwave
    var burstTrigger by remember { mutableStateOf(0) }
    val burstProgress = remember { Animatable(0f) }

    LaunchedEffect(burstTrigger) {
        if (burstTrigger > 0) {
            burstProgress.snapTo(0f)
            burstProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(650, easing = FastOutSlowInEasing)
            )
        }
    }

    val goldAccent = Color(0xFFFFB703)
    val emeraldAccent = Color(0xFF00E676)
    val cyanAccent = Color(0xFF00F5D4)
    val purpleAccent = Color(0xFFC084FC)

    Canvas(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                burstTrigger++
                onTap?.invoke()
            }
    ) {
        val w = this.size.width
        val h = this.size.height
        val center = Offset(w / 2f, h / 2f)
        val maxRadius = (w.coerceAtMost(h) / 2f) * 0.94f

        // 1. Soft Radial Ambient Glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    goldAccent.copy(alpha = 0.35f * corePulse),
                    emeraldAccent.copy(alpha = 0.18f),
                    cyanAccent.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = center,
                radius = maxRadius * 1.30f
            ),
            radius = maxRadius * 1.30f,
            center = center
        )

        // 2. Interactive Expanding Shockwave Burst
        if (burstProgress.value > 0f && burstProgress.value < 1f) {
            val bp = burstProgress.value
            val shockRadius = maxRadius * (0.2f + (bp * 0.85f))
            val shockAlpha = (1f - bp) * 0.65f
            drawCircle(
                color = goldAccent.copy(alpha = shockAlpha),
                radius = shockRadius,
                center = center,
                style = Stroke(width = (2.5f * (1f - bp)).dp.toPx())
            )

            // Spark particles exploding
            for (k in 0 until 8) {
                val angle = Math.toRadians((k * 45f + bp * 60f).toDouble())
                val dist = shockRadius * 0.95f
                val spX = center.x + (cos(angle) * dist).toFloat()
                val spY = center.y + (sin(angle) * dist).toFloat()
                drawCircle(
                    color = if (k % 2 == 0) goldAccent else cyanAccent,
                    radius = (2.2f * (1f - bp)).dp.toPx(),
                    center = Offset(spX, spY),
                    alpha = shockAlpha
                )
            }
        }

        // 3. Ring 1: Outer Concentric Dashed Ring (Gold Pulsing)
        val r1 = maxRadius * 0.92f * corePulse
        drawCircle(
            color = goldAccent.copy(alpha = 0.35f),
            radius = r1,
            center = center,
            style = Stroke(width = 1.2.dp.toPx())
        )

        // 4. Ring 2: Orbiting Sweep Arc 1 (Gold 150 deg)
        val r2 = maxRadius * 0.75f
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(goldAccent, Color.Transparent),
                center = center
            ),
            startAngle = outerRot,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(center.x - r2, center.y - r2),
            size = Size(r2 * 2f, r2 * 2f),
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        )

        // 5. Ring 3: Counter-Orbiting Arc 2 (Emerald / Cyan 170 deg)
        val r3 = maxRadius * 0.58f
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(emeraldAccent, cyanAccent, Color.Transparent),
                center = center
            ),
            startAngle = midRot,
            sweepAngle = 170f,
            useCenter = false,
            topLeft = Offset(center.x - r3, center.y - r3),
            size = Size(r3 * 2f, r3 * 2f),
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        )

        // 6. Ring 4: Fast Core Arc 3 (Purple 120 deg)
        val r4 = maxRadius * 0.40f
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(purpleAccent, cyanAccent, Color.Transparent),
                center = center
            ),
            startAngle = innerRot,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = Offset(center.x - r4, center.y - r4),
            size = Size(r4 * 2f, r4 * 2f),
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // 7. Constellation Satellites & Interconnect Laser Rays
        for (i in 0 until 6) {
            val rad = Math.toRadians((outerRot + (i * 60f)).toDouble())
            val nodeR = maxRadius * 0.75f
            val nx = center.x + (cos(rad) * nodeR).toFloat()
            val ny = center.y + (sin(rad) * nodeR).toFloat()

            // Laser beam to center
            drawLine(
                color = goldAccent.copy(alpha = 0.22f),
                start = center,
                end = Offset(nx, ny),
                strokeWidth = 0.9.dp.toPx()
            )

            // Outer Satellite Node
            drawCircle(
                color = if (i % 2 == 0) goldAccent else emeraldAccent,
                radius = 2.6.dp.toPx(),
                center = Offset(nx, ny)
            )
        }

        // 8. Rotating Quantum Prism / Star Core Geometry
        val starPoints = 6
        val starOuterR = maxRadius * 0.24f * corePulse
        val starInnerR = maxRadius * 0.12f * corePulse
        val starPath = Path()

        for (p in 0 until starPoints * 2) {
            val pRadius = if (p % 2 == 0) starOuterR else starInnerR
            val angleRad = Math.toRadians((innerRot * 1.5f + (p * (360f / (starPoints * 2)))).toDouble())
            val px = center.x + (cos(angleRad) * pRadius).toFloat()
            val py = center.y + (sin(angleRad) * pRadius).toFloat()
            if (p == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
        }
        starPath.close()

        drawPath(
            path = starPath,
            brush = Brush.radialGradient(
                colors = listOf(Color.White, goldAccent),
                center = center,
                radius = starOuterR
            )
        )

        // 9. Central Radiant Core Sparkle
        drawCircle(
            color = Color.White,
            radius = 3.8.dp.toPx() * corePulse,
            center = center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectChronicleScreen(
    onBack: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    val goldAccent = Color(0xFFFFB703)
    val emeraldAccent = Color(0xFF00E676)
    val purpleAccent = Color(0xFFC084FC)
    val cyanAccent = Color(0xFF00B4D8)

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // ── 0. BACKGROUND QUANTUM MATRIX & PARTICLES ──
        GenesisQuantumMatrixCanvas()
        CyberParticlesOverlay(
            particleCount = 20,
            alphaMultiplier = 0.75f,
            isVisible = true
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(scrollState)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                )
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {

            // ── 1. HERO GENESIS EMBLEM & 1-MONTH MILESTONE CARD ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 0)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Transparent)
                    .border(1.dp, goldAccent.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                    .lightSweep(
                        isEnabled = true,
                        shape = RoundedCornerShape(24.dp),
                        sweepColor = goldAccent
                    )
                    .padding(22.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    GenesisChronicleCanvas(size = 92.dp)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = goldAccent.copy(alpha = 0.14f),
                            border = BorderStroke(0.8.dp, goldAccent.copy(alpha = 0.40f))
                        ) {
                            Text(
                                text = "ГЕНЕЗИС • 27.07.2026 (1 МЕСЯЦ)",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp,
                                color = goldAccent,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        Text(
                            text = "Летопись Mirrly TG Proxy",
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Black,
                            color = TextWhite,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "Ровно 1 месяц назад первая релизная сборка v1.0.0 была загружена на GitHub. История борьбы за свободную связь, эволюции сетевого транспорта и победы над блокировками.",
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Milestone Stats Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MilestonePill(
                            title = "31 ДЕНЬ",
                            subtitle = "В сети",
                            accentColor = goldAccent,
                            modifier = Modifier.weight(1f)
                        )
                        MilestonePill(
                            title = "v1.0.0 → v1.1.8",
                            subtitle = "22 версии",
                            accentColor = emeraldAccent,
                            modifier = Modifier.weight(1.3f)
                        )
                        MilestonePill(
                            title = "GPLv3",
                            subtitle = "Open Source",
                            accentColor = purpleAccent,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── 2. AUTHOR'S NARRATIVE (Исповедь создателя R1Xern) ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 1),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_heart),
                        contentDescription = null,
                        tint = emeraldAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "КАК И ПОЧЕМУ РОДИЛСЯ ПРОЕКТ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp,
                        color = emeraldAccent
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Исповедь создателя (R1Xern)",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )

                        Text(
                            text = "Меня окончательно задолбало, что власти и интернет-провайдеры постоянно блокируют и замедляют Telegram. Связь превращалась в пытку: сообщения бесконечно отправлялись, фотографии и тяжелые «кружочки» не открывались, а голосовые и видеозвонки постоянно сбрасывались.\n\nСуществующие на тот момент мобильные аналоги прокси либо быстро закрывались, либо работали крайне нестабильно. Почему? Потому что большинство авторов просто переносили серверные скрипты в телефон, не задумываясь о том, как устроен радиоканал в смартфонах. Серверные решения не умеют бесшовно переживать смену Wi-Fi ↔ LTE, страдают от задержек сборщика мусора (GC) в Android JVM, не имеют пула прогретых сокетов и отдают прямые IP дата-центров под блокировки DPI.",
                            fontSize = 13.sp,
                            lineHeight = 19.5.sp,
                            color = TextWhite.copy(alpha = 0.88f)
                        )

                        // Standout White-List Disclaimer Card
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFFFF9E00).copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, Color(0xFFFF9E00).copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_stat_proxy_warning),
                                    contentDescription = null,
                                    tint = Color(0xFFFF9E00),
                                    modifier = Modifier.size(18.dp).padding(top = 2.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "Важное техническое ограничение",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF9E00)
                                    )
                                    Text(
                                        text = "Во время действия режима «белых списков» (White Lists / тотальных шатдаунов провайдеров) обход блокировок Telegram технически не может быть гарантирован ни одним прокси-сервером в мире.",
                                        fontSize = 11.5.sp,
                                        lineHeight = 16.5.sp,
                                        color = TextWhite.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Была проделана колоссальная работа над стабильностью, транспортом сокетов и адаптацией под плохой интернет. Я полностью переписал движок на нативный Rust/Tokio, внедрил маскировку FakeTLS под TLS 1.3, гонку сокетов Happy Eyeballs и защиту от сотового джиттера.",
                            fontSize = 13.sp,
                            lineHeight = 19.5.sp,
                            color = TextWhite.copy(alpha = 0.88f)
                        )

                        // Philosophy Quote Card
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = emeraldAccent.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, emeraldAccent.copy(alpha = 0.30f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "ФИЛОСОФИЯ ПРОЕКТА MIRRLY",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp,
                                    color = emeraldAccent
                                )
                                Text(
                                    text = "«Сложнейшая инженерная магия и глубокая автоматизация сетевого стека должны оставаться под капотом, предоставляя пользователю абсолютную простоту в одно касание».",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 18.5.sp,
                                    color = TextWhite
                                )
                                Text(
                                    text = "Вам не нужно разбираться в портах, сертификатах FakeTLS, сборках NDK, маршрутизации сокетов или правке JSON-конфигов. Приложение запускается нажатием одной центральной кнопки, а вся многопоточная балансировка Rust-ядра, ступенчатая гонка пакетов Happy Eyeballs и фильтрация маршрутов выполняются в фоне незаметно и с нулевым влиянием на заряд батареи.",
                                    fontSize = 12.sp,
                                    lineHeight = 17.5.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            }

            // ── 3. MAP & EVOLUTION TIMELINE (История каждого релиза) ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 2),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_history),
                        contentDescription = null,
                        tint = purpleAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "КАРТА ЭВОЛЮЦИИ: ИСТОРИЯ КАЖДОГО РЕЛИЗА",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp,
                        color = purpleAccent
                    )
                }

                // Milestone 1: v1.0.0 Genesis
                ChronicleMilestoneCard(
                    version = "v1.0.0",
                    date = "27.07.2026",
                    title = "Генезис проекта и первый кирпич",
                    tag = "GENESIS",
                    accentColor = goldAccent,
                    story = "Первый официальный релиз на GitHub. Был запущен локальный MTProto-прокси на порту 1443 с монолитным ядром libtgwsproxy (C/JNA), предварительно прогретым пулом сокетов WsPool к DC1–DC5 и мгновенным подключением в 1 клик для 17+ клиентов Telegram. Это доказало жизнеспособность концепции прямого WSS-туннелирования без создания системного VPN.",
                    why = "Зачем было создано: дать пользователям рабочий локальный шлюз, который не требует root-прав и не ломает работу банковских приложений.",
                    modifier = Modifier.staggeredEntrance(index = 3)
                )

                // Milestone 2: v1.0.4 - v1.0.5
                ChronicleMilestoneCard(
                    version = "v1.0.4 — v1.0.5",
                    date = "Конец июля 2026",
                    title = "Защита чистоты кода и битва за скорость",
                    tag = "GPLv3 & SPEED",
                    accentColor = emeraldAccent,
                    story = "Проект полностью перешел на строгую лицензию GNU GPLv3, навсегда защитив открытый исходный код от закрытия в коммерческих форках. Внедрил режим скрытия Stealth UI, скоростные профили буферизации (Турбо 2 МБ) и сквозной TCP_NODELAY для ускорения отправки сообщений.",
                    why = "Почему так: видео и медиафайлы требовали увеличенных буферов передачи, а проект нуждался в надежном юридическом фундаменте.",
                    modifier = Modifier.staggeredEntrance(index = 4)
                )

                // Milestone 3: v1.0.6 - v1.0.8
                ChronicleMilestoneCard(
                    version = "v1.0.6 — v1.0.8",
                    date = "Начало августа 2026",
                    title = "Безопасность подписи и Cyber Glass UI",
                    tag = "SECURITY & UI",
                    accentColor = cyanAccent,
                    story = "Создан нативный C++ NDK модуль SignatureVerifier для проверки подписи APK перед установкой по принципу Fail-Closed. Появились прямые автообновления, умный таймер сна (Sleep Timer) и полный отказ от тяжелых непрозрачных рамок в пользу True Black Cyber Glass UI с системным размытием FLAG_BLUR_BEHIND.",
                    why = "Почему так: безопасность пользователей превыше всего — важно было защитить их от модифицированных зловредных клонов и избавить интерфейс от лишнего визуального мусора.",
                    modifier = Modifier.staggeredEntrance(index = 5)
                )

                // Milestone 4: v1.0.9
                ChronicleMilestoneCard(
                    version = "v1.0.9",
                    date = "Середина августа 2026",
                    title = "Прорыв в звонках: Рождение SOCKS5",
                    tag = "SOCKS5 VOIP",
                    accentColor = purpleAccent,
                    story = "MTProto безупречно справлялся с чатами и медиа, но протокол MTProto технически не поддерживал голосовые и видеозвонки Telegram VoIP. Был спроектирован и запущен асинхронный TCP-релей SOCKS5 на порту 10808 через WebSocket Cloudflare (cloudflare:sockets). Звонки Telegram заработали в чистом HD-качестве.",
                    why = "Почему так: связь должна быть полноценной. Без поддержки звонков прокси оставался половинчатым решением.",
                    modifier = Modifier.staggeredEntrance(index = 6)
                )

                // Milestone 5: v1.1.0 - v1.1.1
                ChronicleMilestoneCard(
                    version = "v1.1.0 — v1.1.1",
                    date = "Август 2026",
                    title = "Архитектурная стабилизация и ABI Splits",
                    tag = "STABILITY",
                    accentColor = Color(0xFFFF9E00),
                    story = "Устранены JNI race conditions при одновременной работе SOCKS5 и MTProto. Внедрена динамическая трехцветная индикация в шторке Android и совершен переход от единого тяжелого файла app-release.apk (7.1 МБ) к целевым архитектурным сборкам (ARM64 снизился до 4.8 МБ).",
                    why = "Почему так: избавление от лишнего веса установочного пакета и ликвидация редких взаимных блокировок сокетов.",
                    modifier = Modifier.staggeredEntrance(index = 7)
                )

                // Milestone 6: v1.1.2
                ChronicleMilestoneCard(
                    version = "v1.1.2",
                    date = "Август 2026",
                    title = "Великий переезд на Rust Tokio (mirrlyengine)",
                    tag = "RUST CORE",
                    accentColor = Color(0xFFDEA584),
                    story = "Фундаментальный поворотный момент. Старое C-ядро и JVM-оверхед уперлись в потолок масштабирования. Сетевой движок был полностью переписан на чистый Rust с асинхронным Tokio runtime (mirrlyengine). Появилась Zero-Copy передача буферов, нативный FakeTLS 1.3 (ee/dd) и полное исчезновение микрофризов Garbage Collector.",
                    why = "Почему перешел на Rust: только компилируемый машинный код Rust с неблокирующим epoll гарантирует безупречную передачу сотен мегабайт видео без нагрева и жора батареи.",
                    modifier = Modifier.staggeredEntrance(index = 8)
                )

                // Milestone 7: v1.1.3 - v1.1.3.1
                ChronicleMilestoneCard(
                    version = "v1.1.3 — v1.1.3.1",
                    date = "Август 2026",
                    title = "Менеджер воркеров и свобода Deep Links",
                    tag = "DEEP LINKS",
                    accentColor = cyanAccent,
                    story = "Внедрен интерактивный Менеджер воркеров с пулом серверов разработчика, балансировщик Anycast Happy Eyeballs (RFC 8305) с защитой от сотового джиттера (Hysteresis) и моментальный импорт персональных серверов по ссылкам mirrly://worker и https://mirrly.app/worker в 1 клик.",
                    why = "Зачем создано: дать каждому пользователю возможность развернуть свой бесплатный сервер со 100 000 суточных запросов и делиться им с близкими без ручной возни с настройками.",
                    modifier = Modifier.staggeredEntrance(index = 9)
                )

                // Milestone 8: v1.1.4
                ChronicleMilestoneCard(
                    version = "v1.1.4",
                    date = "Август 2026",
                    title = "100% Rust-ядро, Happy Eyeballs и Anti-Open-Relay",
                    tag = "RUST & SECURITY",
                    accentColor = Color(0xFFDEA584),
                    story = "Из проекта полностью удален устаревший Kotlin/JVM сетевой стек. Обработка всех протоколов (MTProto, SOCKS5, FakeTLS, WebSocket, TLS) переведена на 100% в нативное ядро mirrlyengine. Интегрирован адаптивный алгоритм Happy Eyeballs с Fast Failover, атомарная ротация сессий при смене Wi-Fi ↔ LTE, 3-позиционный селектор TCP_NODELAY (Auto/On/Off) и защита от открытого релея в скрипте воркера (Anti-Open-Relay с фильтрацией подсетей Telegram).",
                    why = "Почему так: ликвидация любых потерь пакетов на мобильных сетях, защита личных воркеров пользователей от сканирования ботами и максимальная отзывчивость сетевого конвейера.",
                    modifier = Modifier.staggeredEntrance(index = 10)
                )

                // Milestone 9: v1.1.5
                ChronicleMilestoneCard(
                    version = "v1.1.5",
                    date = "Август 2026",
                    title = "3-фазный переключатель протоколов и параллельная гонка воркеров",
                    tag = "OPTIMISTIC UI",
                    accentColor = emeraldAccent,
                    story = "Разработан 3-фазный оркестратор переключения режимов (ProtocolSwitchManager) с 0 мс задержки ввода для UI. Внедрена конкурентная гонка воркеров разработчика с параллельным опросом и мгновенным отсечением проигравших соединений, удален легаси-модуль MsgSplitter для устранения задержек видео, а шапка главного экрана разделена на двухуровневый TopBar с каскадной физикой пружин кнопки питания (120 FPS).",
                    why = "Почему так: сделать смену протоколов MTProto и SOCKS5 мгновенной и бесшовной, исключив лаги и замирания интерфейса.",
                    modifier = Modifier.staggeredEntrance(index = 11)
                )

                // Milestone 10: v1.1.6 - v1.1.6.1
                ChronicleMilestoneCard(
                    version = "v1.1.6 — v1.1.6.1",
                    date = "Август 2026",
                    title = "Потоковая сборка RFC 6455 и утилита мульти-деплоя воркеров",
                    tag = "STREAMING & CLI",
                    accentColor = cyanAccent,
                    story = "Внедрена потоковая сборка фрагментированных фреймов WebSocket (RFC 6455) до 16 МБ и асинхронная очередь записи writeQueue в воркере для гарантированной стабильности передачи тяжелых файлов. Создан автономный инструмент деплоя tools/deploy-worker/ с запуском в 1 клик на Windows (deploy.bat), поддержкой мульти-аккаунтов и генерацией ссылок mirrly://worker. В v1.1.6.1 узлы разработчика разнесены на 4 независимых аккаунта Cloudflare.",
                    why = "Зачем создано: решить проблему обрывов при скачивании больших видео и файлов, а также дать каждому пользователю самый простой способ поднять личный сервер.",
                    modifier = Modifier.staggeredEntrance(index = 12)
                )

                // Milestone 11: v1.1.7
                ChronicleMilestoneCard(
                    version = "v1.1.7",
                    date = "Август 2026",
                    title = "Anycast CDN Flowseal, Zero-Allocation холст и аппаратный Haptic",
                    tag = "FLOWSEAL CDN & GPU",
                    accentColor = purpleAccent,
                    story = "Трафик MTProto переведен на прямое туннелирование через глобальный пул Anycast CDN Flowseal (kws{dc}), что снизило задержки и разгрузило воркеры. Ступенчатая задержка гонки снижена до 25 мс, внедрено распознавание кодов HTTP 429 и 520–524 с мгновенным кулдауном. Графический холст переведен на Zero-Allocation рендеринг (120 FPS) на предвыделенных массивах FloatArray, а во все диалоги интегрировано аппаратное размытие FLAG_BLUR_BEHIND (60 px) и тактильные вибро-профили Android 12+.",
                    why = "Почему так: разделить маршруты чатов и звонков на оптимальные магистрали, разгрузить батарею и довести визуальный опыт до абсолютного идеала.",
                    modifier = Modifier.staggeredEntrance(index = 13)
                )

                // Milestone 12: v1.1.8
                ChronicleMilestoneCard(
                    version = "v1.1.8",
                    date = "27.08.2026",
                    title = "Сетевая вершина, DoH, Happy Eyeballs, аналитика квоты, Multi-APK и летопись Genesis",
                    tag = "DOH • SQI • GENESIS",
                    accentColor = emeraldAccent,
                    story = "Грандиозный юбилейный релиз к 1 месяцу проекта. Внедрен DNS-over-HTTPS (DoH Race Resolver: Cloudflare, Google, Quad9) против DNS-спуфинга, Happy Eyeballs v2 (RFC 8305) для Anycast IP, детектор DPI и Deep Failover (< 300 мс), Telegram DC-Affinity (до 75% сокетов на медиа DC4), адаптивный фильтр скорости EMA и Battery/Thermal Aware QoS. Добавлен локальный трекер квоты с графиком Безье (Worker Request Analytics), индикатор SQI с глубокой диагностикой сети, Менеджер архитектур Multi-APK Selector (ARM64, ARMv7, x86_64, x86, Universal) с чистым восстановлением и секретная интерактивная летопись Genesis с Залом Славы.",
                    why = "Почему так: превратить Mirrly TG Proxy в неуязвимый, саморегулирующийся сетевой комбайн, защищенный от любых блокировок, с максимальной прозрачностью квоты и возможностью выбора точной сборки под процессор.",
                    modifier = Modifier.staggeredEntrance(index = 14)
                )
            }

            // ── 4. FUTURE PROTOCOLS & ROADMAP (Лаборатория протоколов и планы на будущее) ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 15),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_diag_protocol),
                        contentDescription = null,
                        tint = cyanAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "ПЛАНЫ НА БУДУЩЕЕ: ЛАБОРАТОРИЯ ПРОТОКОЛОВ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp,
                        color = cyanAccent
                    )
                }

                // 4-Protocol Standard Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggeredEntrance(index = 16)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Архитектурный стандарт: Строго 4 протокола",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = "В Mirrly TG Proxy будет реализовано строго 4 выверенных протокола транспорта трафика без раздувания кодовой базы и без утяжеления приложения: два уже внедренных стандарта (MTProto и SOCKS5) и два протокола следующего поколения (WEB и HTTP).",
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = TextWhite.copy(alpha = 0.85f)
                        )
                    }
                }

                // 1. WEB Protocol Card
                FutureProtocolCard(
                    title = "Протокол WEB (WebTransport / WSS Tunnel)",
                    tag = "В РАЗРАБОТКЕ",
                    accentColor = cyanAccent,
                    advantages = "Преимущества перед MTProto и SOCKS5:\n• В отличие от бинарных хендшейков MTProto (dd/ee) и TCP-релея SOCKS5, протокол WEB полностью инкапсулирует полезную нагрузку в нативные HTTP/2 и HTTP/3 (WebTransport) веб-потоки.\n• Для DPI-систем операторов и межсетевых экранов этот трафик на 100% выглядит как обычный просмотр защищенных сайтов с TLS 1.3 ALPN (h2/h3), полностью исключая подозрительные сигнатуры и блокировки портов.",
                    realismWorkerPercent = 0.85f,
                    realismWorkerText = "Нативная поддержка V8 Fetch & WebSocket API в глобальной сети Cloudflare Edge сокетов",
                    realismCdnPercent = 0.60f,
                    realismCdnText = "Требует кастомных шлюзов трансляции WebTransport в бинарный поток DC Telegram",
                    modifier = Modifier.staggeredEntrance(index = 17)
                )

                // 2. HTTP Protocol Card
                FutureProtocolCard(
                    title = "Протокол HTTP (HTTP CONNECT Tunnel)",
                    tag = "В ПЛАНАХ",
                    accentColor = goldAccent,
                    advantages = "Преимущества перед MTProto и SOCKS5:\n• HTTP CONNECT — это универсальный мировой стандарт интернет-проксирования с прямой поддержкой во всех официальных и сторонних сборках Telegram.\n• В сравнении с MTProto не требует генерации и синхронизации 32-байтовых hex-ключей секретов.\n• В сравнении с SOCKS5 обеспечивает более быстрое первичное рукопожатие на медленном мобильном интернете за счет отсутствия многоступенчатой фазы аутентификации (минимизация Round-Trip Time).",
                    realismWorkerPercent = 0.90f,
                    realismWorkerText = "Прямой проброс HTTP CONNECT через Cloudflare Workers connect() API сокетов",
                    realismCdnPercent = 0.75f,
                    realismCdnText = "Стандартная Anycast маршрутизация HTTP TLS-туннелей",
                    modifier = Modifier.staggeredEntrance(index = 18)
                )

                // 3. Stability & Maintenance Creed Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.03f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggeredEntrance(index = 19)
                ) {
                    Text(
                        text = "Поддержка приложения, улучшение стабильности, постоянные обновления и связь с комьюнити.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 17.sp,
                        color = emeraldAccent,
                        modifier = Modifier.padding(14.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // ── 5. MANIFEST & CREED ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 20),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_shield),
                        contentDescription = null,
                        tint = goldAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "МАНИФЕСТ ЧИСТОТЫ И БУДУЩЕЕ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp,
                        color = goldAccent
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggeredEntrance(index = 21)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, goldAccent.copy(alpha = 0.40f), RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Мои нерушимые обещания",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )

                        Text(
                            text = "• Mirrly TG Proxy навсегда останется на 100% бесплатным, с открытым исходным кодом, без встроенной рекламы, спонсорских каналов и платных подписок.\n• Осенью 2026 года планируется масштабирование проекта на другие регионы с сетевой цензурой и подготовка к официальному релизу в Google Play Store.\n• Спасибо каждому пользователю, тестировщику и контрибьютору, кто был со мной этот первый месяц. Это только начало!",
                            fontSize = 12.5.sp,
                            lineHeight = 18.5.sp,
                            color = TextWhite.copy(alpha = 0.90f)
                        )
                    }
                }
            }

            // ── 5. FOOTER COPYRIGHT ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 22)
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Mirrly TG Proxy • 1 месяц на страже стабильности",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = goldAccent
                )
                Text(
                    text = "27.07.2026 — 27.08.2026 • Mirrly Dev",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Frosted Glass Top App Bar
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
                        text = "Летопись проекта",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onBack()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_left),
                            contentDescription = "Назад",
                            tint = TextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }

        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 44,
            alphaMultiplier = 0.70f
        )
    }
}

@Composable
private fun MilestonePill(
    title: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accentColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.38f)),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .lightSweep(
                isEnabled = true,
                shape = RoundedCornerShape(14.dp),
                sweepColor = accentColor
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = title,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                color = accentColor,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ChronicleMilestoneCard(
    version: String,
    date: String,
    title: String,
    tag: String,
    accentColor: Color,
    story: String,
    why: String,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val infiniteTransition = rememberInfiniteTransition(label = "cardBeacon")

    val beaconPulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "beaconPulse"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, accentColor.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
            .lightSweep(
                isEnabled = true,
                shape = RoundedCornerShape(18.dp),
                sweepColor = accentColor
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header Row: Version + Tag + Date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pulsing LED Beacon Dot
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = beaconPulse))
                    )

                    Text(
                        text = version,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = accentColor
                    )

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = accentColor.copy(alpha = 0.15f),
                        border = BorderStroke(0.8.dp, accentColor.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = tag,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Black,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = date,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMuted
                )
            }

            Text(
                text = title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite
            )

            Text(
                text = story,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = TextWhite.copy(alpha = 0.88f)
            )

            // Why section
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.03f),
                border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.10f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = why,
                    fontSize = 11.sp,
                    lineHeight = 15.5.sp,
                    color = TextMuted,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun FutureProtocolCard(
    title: String,
    tag: String,
    accentColor: Color,
    advantages: String,
    realismWorkerPercent: Float,
    realismWorkerText: String,
    realismCdnPercent: Float,
    realismCdnText: String,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val progressAnim = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progressAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(1200, easing = FastOutSlowInEasing)
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, accentColor.copy(alpha = 0.38f), RoundedCornerShape(18.dp))
            .lightSweep(
                isEnabled = true,
                shape = RoundedCornerShape(18.dp),
                sweepColor = accentColor
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header Row: Title + Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = accentColor.copy(alpha = 0.15f),
                    border = BorderStroke(0.8.dp, accentColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = tag,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = advantages,
                fontSize = 12.sp,
                lineHeight = 17.5.sp,
                color = TextWhite.copy(alpha = 0.88f)
            )

            // Animated Realism Progress Bars Section
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.03f),
                border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.10f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "ОЦЕНКА РЕАЛИЗУЕМОСТИ АРХИТЕКТУРЫ",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp,
                        color = accentColor
                    )

                    // Track 1: Cloudflare Worker
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Cloudflare Worker Edge",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Text(
                                text = "${(realismWorkerPercent * 100).toInt()}%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = accentColor
                            )
                        }

                        // Progress Bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF141824))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(realismWorkerPercent * progressAnim.value)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(accentColor.copy(alpha = 0.6f), accentColor)
                                        )
                                    )
                            )
                        }

                        Text(
                            text = realismWorkerText,
                            fontSize = 10.5.sp,
                            lineHeight = 14.5.sp,
                            color = TextMuted
                        )
                    }

                    // Track 2: CDN Flowseal
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "CDN Flowseal Anycast",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Text(
                                text = "${(realismCdnPercent * 100).toInt()}%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = TextMuted
                            )
                        }

                        // Progress Bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF141824))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(realismCdnPercent * progressAnim.value)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFF7A889B).copy(alpha = 0.5f), Color(0xFF9EADBF))
                                        )
                                    )
                            )
                        }

                        Text(
                            text = realismCdnText,
                            fontSize = 10.5.sp,
                            lineHeight = 14.5.sp,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}


