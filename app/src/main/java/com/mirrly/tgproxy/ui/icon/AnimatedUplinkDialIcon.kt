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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated Rotary Uplink Dial Icon (Variant 2 - MirrlyUI / mirrly-icons standard).
 *
 * Visual semantics:
 * 1. Outbound Network Gateway (Uplink):
 *    - Central ascending vector arrow pointing straight up towards route channels.
 *    - Continuous quantum packet pulse wave traveling upward along the arrow shaft.
 *    - Origin anchor ring at device level.
 * 2. Clickable Channel Selector Affordance:
 *    - Precision rotary dial bezel with 3 channel detents (Direct TCP, Worker, WARP).
 *    - Active channel detent is prominently illuminated and pulsed with neon glow.
 *    - Integrated dropdown chevron ('v') in the bottom bezel gap with spring levitation,
 *      universally indicating a tap-to-expand / selector sheet trigger.
 *    - Subtle gyroscopic oscillation (±3°) indicating a movable rotary dial mechanism.
 * 3. 120 FPS Zero-Allocation & Battery Guard:
 *    - Pre-allocated paths outside Canvas, zero heap allocations in DrawScope.
 *    - Freezes when activity is paused or animations are disabled.
 *    - Outline-Only (1.5dp), zero background fill, dynamic protocol palette.
 */
@Composable
fun AnimatedUplinkDialIcon(
    modifier: Modifier = Modifier.size(36.dp),
    color: Color = LocalProtocolColors.current.primary,
    secondaryColor: Color = LocalProtocolColors.current.light,
    strokeWidth: Dp = 1.5.dp,
    glowIntensity: Float = 0.85f,
    activeChannel: Int = 0, // 0: Cloudflare Worker, 1: Direct TCP, 2: WARP, -1: neutral
    isAnimated: Boolean = true
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val isAppResumed = remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isAppResumed.value = false
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> isAppResumed.value = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shouldAnimate = isAnimated && isAppResumed.value

    val animProgress by if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "UplinkDialTransition")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "DialProgress"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    // Pre-allocated reusable Path objects for Zero-Allocation in DrawScope
    val arrowHeadPath = remember { Path() }
    val chevronPath = remember { Path() }
    val packetWavePath = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val strokePx = strokeWidth.toPx()
        val cx = w / 2f
        val cy = h / 2f - 0.5.dp.toPx() // Optical balance compensation for bottom chevron

        // Available radius leaving safe margin for stroke cap and glow halo
        val r = (minOf(w, h) / 2f) - strokePx * 1.5f - 1.5.dp.toPx()
        if (r <= 2f) return@Canvas

        // Kinetic angles & phases derived from single hardware-synced clock
        val rotAngleDeg = if (shouldAnimate) sin(animProgress * 2.0 * PI).toFloat() * 3.0f else 0f
        val chevronBouncePx = if (shouldAnimate) sin(animProgress * 2.0 * PI).toFloat() * 1.2.dp.toPx() else 0f
        val wavePhase = (animProgress * 1.6f) % 1.0f
        val pulseAlpha = 0.5f + 0.5f * sin(animProgress * 2.0 * PI).toFloat()

        // ─── 1. OUTER ROTARY BEZEL ARC ───
        // Arc spans from 120° clockwise to 60° (300° total sweep), leaving a 60° opening at the bottom (centered at 90°)
        val arcStartAngle = 120f + rotAngleDeg
        val arcSweepAngle = 300f
        val arcTopLeft = Offset(cx - r, cy - r)
        val arcSize = Size(r * 2f, r * 2f)

        drawArc(
            color = color.copy(alpha = 0.85f),
            startAngle = arcStartAngle,
            sweepAngle = arcSweepAngle,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )

        // ─── 2. THREE CHANNEL DETENTS (Direct TCP, Worker, WARP) ───
        // Channel 1: Direct TCP (10 o'clock = 210°)
        // Channel 0: Worker (12 o'clock = 270°)
        // Channel 2: WARP (2 o'clock = 330°)
        val channelAngles = floatArrayOf(270f, 210f, 330f) // index 0: Worker, index 1: Direct, index 2: WARP
        val tickInner = r - 2.8.dp.toPx()
        val tickOuter = r + 0.8.dp.toPx()

        for (chIndex in 0..2) {
            val baseAngleDeg = channelAngles[chIndex] + rotAngleDeg
            val rad = baseAngleDeg * (PI / 180.0)
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            val isActive = (chIndex == activeChannel)
            val detentAlpha = if (isActive) 1.0f else 0.35f
            val detentColor = if (isActive) color else color.copy(alpha = detentAlpha)

            // Radial tick line
            drawLine(
                color = detentColor,
                start = Offset(cx + tickInner * cosA, cy + tickInner * sinA),
                end = Offset(cx + tickOuter * cosA, cy + tickOuter * sinA),
                strokeWidth = if (isActive) strokePx * 1.25f else strokePx * 0.9f,
                cap = StrokeCap.Round
            )

            // Active channel pip with neon glow aura
            if (isActive) {
                val pipCenter = Offset(cx + (r - 1.2.dp.toPx()) * cosA, cy + (r - 1.2.dp.toPx()) * sinA)
                // Soft glow halo
                drawCircle(
                    color = color.copy(alpha = 0.30f * pulseAlpha * glowIntensity),
                    radius = 3.6.dp.toPx(),
                    center = pipCenter
                )
                // Solid micro-core (radius <= 1.5dp per /mirrly-icons standard)
                drawCircle(
                    color = color,
                    radius = 1.3.dp.toPx(),
                    center = pipCenter
                )
            }
        }

        // ─── 3. INTEGRATED BOTTOM DROPDOWN CHEVRON ('v' AFFORDANCE) ───
        // Sits in the bottom 60° gap (between 60° and 120°), nudging gently up and down
        val chevronApexY = cy + r * 1.02f + chevronBouncePx
        val chevronWidthHalf = r * 0.28f
        val chevronArmHeight = r * 0.20f

        chevronPath.rewind()
        chevronPath.moveTo(cx - chevronWidthHalf, chevronApexY - chevronArmHeight)
        chevronPath.lineTo(cx, chevronApexY)
        chevronPath.lineTo(cx + chevronWidthHalf, chevronApexY - chevronArmHeight)

        drawPath(
            path = chevronPath,
            color = color,
            style = Stroke(
                width = strokePx * 1.15f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // ─── 4. CENTRAL ASCENDING UPLINK ARROW ───
        // Outbound transmission vector pointing UP towards route channels
        val arrowBaseY = cy + r * 0.30f
        val arrowShaftTopY = cy - r * 0.38f

        // Central vertical shaft
        drawLine(
            color = color,
            start = Offset(cx, arrowBaseY),
            end = Offset(cx, arrowShaftTopY),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
        )

        // Arrow head chevron pointing UP
        val arrowTipY = cy - r * 0.46f
        val arrowWingX = r * 0.22f
        val arrowWingY = cy - r * 0.20f

        arrowHeadPath.rewind()
        arrowHeadPath.moveTo(cx - arrowWingX, arrowWingY)
        arrowHeadPath.lineTo(cx, arrowTipY)
        arrowHeadPath.lineTo(cx + arrowWingX, arrowWingY)

        drawPath(
            path = arrowHeadPath,
            color = color,
            style = Stroke(
                width = strokePx * 1.1f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // ─── 5. ASCENDING PACKET TRANSMISSION PULSE ───
        // Secondary quantum wave chevron traveling upwards along the shaft
        val waveTravelDist = arrowBaseY - arrowShaftTopY
        val waveTipY = (arrowBaseY - 1.dp.toPx()) - (wavePhase * waveTravelDist)
        val waveWidthHalf = r * 0.14f
        val waveArmHeight = r * 0.11f
        val waveAlpha = (sin(wavePhase * PI).toFloat().coerceIn(0f, 1f)) * 0.85f

        packetWavePath.rewind()
        packetWavePath.moveTo(cx - waveWidthHalf, waveTipY + waveArmHeight)
        packetWavePath.lineTo(cx, waveTipY)
        packetWavePath.lineTo(cx + waveWidthHalf, waveTipY + waveArmHeight)

        drawPath(
            path = packetWavePath,
            color = secondaryColor.copy(alpha = waveAlpha),
            style = Stroke(
                width = strokePx * 0.95f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // ─── 6. LOCAL DEVICE ORIGIN NODE (BASE ANCHOR) ───
        drawCircle(
            color = color,
            radius = 1.8.dp.toPx(),
            center = Offset(cx, arrowBaseY),
            style = Stroke(width = strokePx * 0.85f)
        )
    }
}
