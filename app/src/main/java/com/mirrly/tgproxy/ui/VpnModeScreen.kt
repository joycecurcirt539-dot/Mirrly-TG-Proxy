/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+  <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.ui
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import androidx.compose.runtime.withFrameNanos
import kotlin.math.cos
import kotlin.math.sin

// =============================================================================
// RotatingVpnRing — Specialized Kinetic Cyber Orbital Ring for VPN Mode
// Features: dual undulating sine-wave sweep arcs, glowing comet heads,
// counter-rotating satellite orbital nodes, and precision tick marks.
// =============================================================================
@Composable
fun RotatingVpnRing(
    vpnColors: ProtocolColors,
    state: VpnUiState = VpnUiState.CONNECTED,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isAppResumed = false
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> isAppResumed = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val isAnimationsDisabled by app.prefsManager.animationsDisabledFlow.collectAsState()
    val isRunning = state == VpnUiState.CONNECTED || state == VpnUiState.CONNECTING

    val timeState = produceState(initialValue = 0L, isAppResumed, isAnimationsDisabled, isRunning) {
        if (!isAppResumed || isAnimationsDisabled || !isRunning) return@produceState
        val startNano = System.nanoTime() - value
        while (isAppResumed && !isAnimationsDisabled && isRunning) {
            withFrameNanos { frameTimeNanos ->
                value = frameTimeNanos - startNano
            }
        }
    }

    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 5.dp.toPx() } }
    val wavePath = remember { Path() }
    val innerPath = remember { Path() }

    Canvas(modifier = modifier) {
        val t = if (!isAnimationsDisabled) timeState.value / 1_000_000_000f else 0f
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = (diameter - strokeWidthPx) / 2f

        // 1. STATIC CALIBRATION TRACK (1dp subtle AMOLED border circle)
        drawCircle(
            color = vpnColors.primary.copy(alpha = 0.14f),
            radius = outerRadius,
            center = center,
            style = Stroke(width = with(density) { 1.dp.toPx() })
        )

        // 4 Static Cyber Reticle Ticks at 0, 90, 180, 270 degrees
        val tickLen = with(density) { 4.5.dp.toPx() }
        for (deg in listOf(0f, 90f, 180f, 270f)) {
            val rad = Math.toRadians(deg.toDouble()).toFloat()
            val rStart = outerRadius - tickLen
            val rEnd = outerRadius + tickLen
            drawLine(
                color = vpnColors.primary.copy(alpha = 0.40f),
                start = Offset(center.x + rStart * cos(rad), center.y + rStart * sin(rad)),
                end = Offset(center.x + rEnd * cos(rad), center.y + rEnd * sin(rad)),
                strokeWidth = with(density) { 1.2.dp.toPx() },
                cap = StrokeCap.Round
            )
        }
        // 2. PRIMARY KINETIC ORBITAL RING (Circular sweeping arc + glowing comet head) - Only when running
        if (isRunning) {
            val outerAngle = (t * 36f) % 360f
            val sweepAngle = 240f
            val sweepRad = Math.toRadians(sweepAngle.toDouble()).toFloat()
            val steps = 72

            rotate(degrees = outerAngle, pivot = center) {
                wavePath.reset()
                for (i in 0..steps) {
                    val stepFrac = i.toFloat() / steps
                    val currentRad = stepFrac * sweepRad
                    val px = center.x + outerRadius * cos(currentRad)
                    val py = center.y + outerRadius * sin(currentRad)
                    if (i == 0) wavePath.moveTo(px, py) else wavePath.lineTo(px, py)
                }

                drawPath(
                    path = wavePath,
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            vpnColors.primary.copy(alpha = 0.20f),
                            vpnColors.primary.copy(alpha = 0.65f),
                            vpnColors.light
                        ),
                        center = center
                    ),
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                )

                // High-intensity leading comet head
                val headRad = sweepRad
                val headX = center.x + outerRadius * cos(headRad)
                val headY = center.y + outerRadius * sin(headRad)

                drawCircle(
                    color = vpnColors.primary,
                    radius = strokeWidthPx * 0.9f,
                    center = Offset(headX, headY)
                )
                drawCircle(
                    color = Color.White,
                    radius = strokeWidthPx * 0.5f,
                    center = Offset(headX, headY)
                )
            }

            // 3. COUNTER-ROTATING SECONDARY QUANTUM NODES
            val innerAngle = -(t * 24f) % 360f
            val innerRadius = outerRadius - with(density) { 11.dp.toPx() }
            val nodeCount = 3
            val nodeStep = 360f / nodeCount

            rotate(degrees = innerAngle, pivot = center) {
                for (n in 0 until nodeCount) {
                    val nDeg = n * nodeStep
                    val nRad = Math.toRadians(nDeg.toDouble()).toFloat()
                    val nx = center.x + innerRadius * cos(nRad)
                    val ny = center.y + innerRadius * sin(nRad)

                    // Node outer glow
                    drawCircle(
                        color = vpnColors.light.copy(alpha = 0.35f),
                        radius = with(density) { 3.5.dp.toPx() },
                        center = Offset(nx, ny)
                    )
                    // Node core
                    drawCircle(
                        color = Color.White,
                        radius = with(density) { 1.5.dp.toPx() },
                        center = Offset(nx, ny)
                    )
                }
            }

            // 4. TERTIARY HARMONIC SINE-WAVE INTERIOR TRACK
            val innerSweepAngle = 180f
            val innerSweepRad = Math.toRadians(innerSweepAngle.toDouble()).toFloat()
            val innerRotateAngle = (t * 50f) % 360f

            rotate(degrees = innerRotateAngle, pivot = center) {
                innerPath.reset()
                val innerSteps = 48
                for (j in 0..innerSteps) {
                    val jFrac = j.toFloat() / innerSteps
                    val jRad = jFrac * innerSweepRad
                    val jx = center.x + innerRadius * cos(jRad)
                    val jy = center.y + innerRadius * sin(jRad)
                    if (j == 0) innerPath.moveTo(jx, jy) else innerPath.lineTo(jx, jy)
                }

                drawPath(
                    path = innerPath,
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            vpnColors.primary.copy(alpha = 0.15f),
                            vpnColors.light.copy(alpha = 0.85f)
                        ),
                        center = center
                    ),
                    style = Stroke(width = strokeWidthPx * 0.65f, cap = StrokeCap.Round)
                )

                // Inner Satellite Comet Head
                val inHeadRad = innerSweepRad
                val inHeadX = center.x + innerRadius * cos(inHeadRad)
                val inHeadY = center.y + innerRadius * sin(inHeadRad)

                drawCircle(
                    color = vpnColors.primary,
                    radius = strokeWidthPx * 0.6f,
                    center = Offset(inHeadX, inHeadY)
                )
                drawCircle(
                    color = Color.White,
                    radius = strokeWidthPx * 0.3f,
                    center = Offset(inHeadX, inHeadY)
                )
            }
        }
    }
}

// =============================================================================
// AnimatedVpnShield — High-Fidelity Kinetic Cyber Aegis Shield
// Features: aerodynamic banking, levitation drift, micro-rumble, defensive pulse
// shockwaves, twin plasma thrusters, 3D specular facets, gyroscopic quantum lock core,
// continuous photon spine data streams, and apex diffraction beacon.
// =============================================================================
@Composable
fun AnimatedVpnShield(
    vpnColors: ProtocolColors,
    state: VpnUiState = VpnUiState.CONNECTED,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isAppResumed = false
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> isAppResumed = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val isAnimationsDisabled by app.prefsManager.animationsDisabledFlow.collectAsState()

    val timeState = produceState(initialValue = 0L, isAppResumed, isAnimationsDisabled) {
        if (!isAppResumed || isAnimationsDisabled) return@produceState
        val startNano = System.nanoTime() - value
        while (isAppResumed && !isAnimationsDisabled) {
            withFrameNanos { frameTimeNanos ->
                value = frameTimeNanos - startNano
            }
        }
    }

    val targetJetColor = when (state) {
        VpnUiState.CONNECTING -> vpnColors.light
        VpnUiState.CONNECTED -> vpnColors.primary
        VpnUiState.DISCONNECTING -> vpnColors.primary.copy(alpha = 0.65f)
        VpnUiState.DISCONNECTED -> vpnColors.primary
    }
    val animatedJetColor by animateColorAsState(
        targetValue = targetJetColor,
        animationSpec = tween(550, easing = FastOutSlowInEasing),
        label = "vpnShieldColor"
    )

    val targetThrust = when (state) {
        VpnUiState.CONNECTING -> 0.95f
        VpnUiState.CONNECTED -> 1.0f
        VpnUiState.DISCONNECTING -> 0.35f
        VpnUiState.DISCONNECTED -> 0.0f
    }
    val animatedThrust by animateFloatAsState(
        targetValue = targetThrust,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "vpnShieldThrust"
    )

    val targetLiftDp = when (state) {
        VpnUiState.CONNECTING -> (-4).dp
        VpnUiState.CONNECTED -> (-2.5).dp
        VpnUiState.DISCONNECTING -> (-1).dp
        VpnUiState.DISCONNECTED -> 0.dp
    }
    val animatedLiftDp by animateDpAsState(
        targetValue = targetLiftDp,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow),
        label = "vpnShieldLift"
    )

    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 2.dp.toPx() } }
    val haloStrokePx = remember(density) { with(density) { 4.5.dp.toPx() } }
    val spineStrokePx = remember(density) { with(density) { 2.2.dp.toPx() } }
    val dotRadiusPx = remember(density) { with(density) { 2.2.dp.toPx() } }
    val liftPx = with(density) { animatedLiftDp.toPx() }

    val shieldContourPath = remember { Path() }
    val leftWingPath = remember { Path() }
    val rightWingPath = remember { Path() }
    val flameLeftPath = remember { Path() }
    val flameRightPath = remember { Path() }

    Canvas(modifier = modifier) {
        val t = if (!isAnimationsDisabled) timeState.value / 1_000_000_000f else 0f
        val diameter = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f + liftPx

        // Flight & levitation dynamics
        val floatAmp = diameter * 0.024f
        val floatFrequency = 2.0f
        val driftY = sin(t * floatFrequency) * floatAmp
        val rumbleY = if (state == VpnUiState.CONNECTING) sin(t * 36f) * (diameter * 0.008f) else 0f
        val effectiveCy = cy + driftY + rumbleY

        val rollAngle = sin(t * 1.6f) * 2.5f

        val shieldH = diameter * 0.54f
        val shieldW = diameter * 0.48f

        // Geometry key vertices for high-tech Aegis stealth contour
        val apex = Offset(cx, effectiveCy - shieldH * 0.46f)
        val crownLeft = Offset(cx - shieldW * 0.22f, effectiveCy - shieldH * 0.40f)
        val crownRight = Offset(cx + shieldW * 0.22f, effectiveCy - shieldH * 0.40f)
        val leftShoulder = Offset(cx - shieldW * 0.48f, effectiveCy - shieldH * 0.18f)
        val rightShoulder = Offset(cx + shieldW * 0.48f, effectiveCy - shieldH * 0.18f)
        val leftWaist = Offset(cx - shieldW * 0.44f, effectiveCy + shieldH * 0.10f)
        val rightWaist = Offset(cx + shieldW * 0.44f, effectiveCy + shieldH * 0.10f)
        val bottomTip = Offset(cx, effectiveCy + shieldH * 0.48f)
        val centerCore = Offset(cx, effectiveCy + shieldH * 0.02f)

        rotate(degrees = rollAngle, pivot = Offset(cx, effectiveCy)) {
            // ── 1. DUAL PLASMA AFTERBURNER THRUSTER PLUMES ──
            if (animatedThrust > 0.02f) {
                val flameFlicker = 0.88f + 0.24f * sin(t * 24f)
                val flameLen = shieldH * 0.22f * flameFlicker * animatedThrust
                val jetBaseY = effectiveCy + shieldH * 0.32f
                val jetOffsetW = shieldW * 0.18f

                // Left thruster plume
                flameLeftPath.reset()
                flameLeftPath.moveTo(cx - jetOffsetW - 4.dp.toPx(), jetBaseY)
                flameLeftPath.lineTo(cx - jetOffsetW, jetBaseY + flameLen)
                flameLeftPath.lineTo(cx - jetOffsetW + 4.dp.toPx(), jetBaseY)
                flameLeftPath.close()

                drawPath(
                    path = flameLeftPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = animatedThrust * 0.95f),
                            vpnColors.light.copy(alpha = animatedThrust * 0.80f),
                            animatedJetColor.copy(alpha = animatedThrust * 0.40f),
                            Color.Transparent
                        ),
                        startY = jetBaseY,
                        endY = jetBaseY + flameLen
                    )
                )

                // Right thruster plume
                flameRightPath.reset()
                flameRightPath.moveTo(cx + jetOffsetW - 4.dp.toPx(), jetBaseY)
                flameRightPath.lineTo(cx + jetOffsetW, jetBaseY + flameLen)
                flameRightPath.lineTo(cx + jetOffsetW + 4.dp.toPx(), jetBaseY)
                flameRightPath.close()

                drawPath(
                    path = flameRightPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = animatedThrust * 0.95f),
                            vpnColors.light.copy(alpha = animatedThrust * 0.80f),
                            animatedJetColor.copy(alpha = animatedThrust * 0.40f),
                            Color.Transparent
                        ),
                        startY = jetBaseY,
                        endY = jetBaseY + flameLen
                    )
                )
            }

            // ── 3. LEFT SHIELD WING (Ambient Specular Facet) ──
            leftWingPath.reset()
            leftWingPath.moveTo(apex.x, apex.y)
            leftWingPath.lineTo(crownLeft.x, crownLeft.y)
            leftWingPath.lineTo(leftShoulder.x, leftShoulder.y)
            leftWingPath.lineTo(leftWaist.x, leftWaist.y)
            leftWingPath.lineTo(bottomTip.x, bottomTip.y)
            leftWingPath.lineTo(centerCore.x, centerCore.y)
            leftWingPath.close()

            drawPath(
                path = leftWingPath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        vpnColors.primary.copy(alpha = 0.24f),
                        vpnColors.primary.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    start = apex,
                    end = leftWaist
                )
            )

            // ── 4. RIGHT SHIELD WING (High-Gloss Specular Facet) ──
            rightWingPath.reset()
            rightWingPath.moveTo(apex.x, apex.y)
            rightWingPath.lineTo(crownRight.x, crownRight.y)
            rightWingPath.lineTo(rightShoulder.x, rightShoulder.y)
            rightWingPath.lineTo(rightWaist.x, rightWaist.y)
            rightWingPath.lineTo(bottomTip.x, bottomTip.y)
            rightWingPath.lineTo(centerCore.x, centerCore.y)
            rightWingPath.close()

            drawPath(
                path = rightWingPath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        vpnColors.light.copy(alpha = 0.38f),
                        vpnColors.primary.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    start = apex,
                    end = rightWaist
                )
            )

            // ── 5. INNER GEOMETRIC LATTICE TRACES ──
            val latticeAlpha = 0.35f + 0.15f * sin(t * 3f)
            drawLine(
                color = animatedJetColor.copy(alpha = latticeAlpha),
                start = centerCore,
                end = crownLeft,
                strokeWidth = with(density) { 0.8.dp.toPx() },
                cap = StrokeCap.Round
            )
            drawLine(
                color = animatedJetColor.copy(alpha = latticeAlpha),
                start = centerCore,
                end = crownRight,
                strokeWidth = with(density) { 0.8.dp.toPx() },
                cap = StrokeCap.Round
            )
            drawLine(
                color = animatedJetColor.copy(alpha = latticeAlpha),
                start = centerCore,
                end = leftWaist,
                strokeWidth = with(density) { 0.8.dp.toPx() },
                cap = StrokeCap.Round
            )
            drawLine(
                color = animatedJetColor.copy(alpha = latticeAlpha),
                start = centerCore,
                end = rightWaist,
                strokeWidth = with(density) { 0.8.dp.toPx() },
                cap = StrokeCap.Round
            )

            // ── 6. DUAL-PASS SHIELD OUTLINE (Outer Halo Glow + Inner Sharp Rim) ──
            shieldContourPath.reset()
            shieldContourPath.moveTo(apex.x, apex.y)
            shieldContourPath.lineTo(crownLeft.x, crownLeft.y)
            shieldContourPath.lineTo(leftShoulder.x, leftShoulder.y)
            shieldContourPath.lineTo(leftWaist.x, leftWaist.y)
            shieldContourPath.lineTo(bottomTip.x, bottomTip.y)
            shieldContourPath.lineTo(rightWaist.x, rightWaist.y)
            shieldContourPath.lineTo(rightShoulder.x, rightShoulder.y)
            shieldContourPath.lineTo(crownRight.x, crownRight.y)
            shieldContourPath.close()

            // Pass A: Outer soft neon halo glow
            drawPath(
                path = shieldContourPath,
                color = animatedJetColor.copy(alpha = 0.28f),
                style = Stroke(width = haloStrokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            // Pass B: Inner crisp neon contour
            drawPath(
                path = shieldContourPath,
                color = animatedJetColor,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // ── 7. CENTRAL GYROSCOPIC CYBER LOCK NODE ──
            val lockW = shieldW * 0.26f
            val lockH = shieldH * 0.22f
            val lockLeft = cx - lockW / 2f
            val lockTop = effectiveCy - lockH * 0.20f

            // Padlock shackle arc
            val shackleR = lockW * 0.32f
            val shackleCenterY = lockTop - with(density) { 1.5.dp.toPx() }
            drawArc(
                color = vpnColors.light,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - shackleR, shackleCenterY - shackleR),
                size = Size(shackleR * 2f, shackleR * 2f),
                style = Stroke(width = strokeWidthPx * 0.95f, cap = StrokeCap.Round)
            )

            // Padlock chassis
            drawRoundRect(
                color = animatedJetColor.copy(alpha = 0.20f),
                topLeft = Offset(lockLeft, lockTop),
                size = Size(lockW, lockH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(with(density) { 5.dp.toPx() })
            )
            drawRoundRect(
                color = animatedJetColor,
                topLeft = Offset(lockLeft, lockTop),
                size = Size(lockW, lockH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(with(density) { 5.dp.toPx() }),
                style = Stroke(width = strokeWidthPx * 0.90f)
            )

            // Core illuminated keyhole
            val keyholeCy = lockTop + lockH * 0.42f
            drawCircle(
                color = Color.White,
                radius = dotRadiusPx * 0.9f,
                center = Offset(cx, keyholeCy)
            )
            drawLine(
                color = Color.White,
                start = Offset(cx, keyholeCy),
                end = Offset(cx, keyholeCy + lockH * 0.26f),
                strokeWidth = strokeWidthPx * 0.8f,
                cap = StrokeCap.Round
            )

            // Gyroscopic Quantum Orbital Satellites around lock node
            val orbRadiusX = lockW * 0.72f
            val orbRadiusY = lockH * 0.48f
            val orbAngle = t * 3.8f
            val sat1X = cx + cos(orbAngle) * orbRadiusX
            val sat1Y = keyholeCy + sin(orbAngle) * orbRadiusY
            val sat2X = cx - cos(orbAngle) * orbRadiusX
            val sat2Y = keyholeCy - sin(orbAngle) * orbRadiusY

            // Orbital trace ring
            drawOval(
                color = vpnColors.primary.copy(alpha = 0.20f),
                topLeft = Offset(cx - orbRadiusX, keyholeCy - orbRadiusY),
                size = Size(orbRadiusX * 2f, orbRadiusY * 2f),
                style = Stroke(width = with(density) { 0.8.dp.toPx() })
            )

            // Satellite 1
            drawCircle(
                color = Color.White,
                radius = dotRadiusPx * 0.75f,
                center = Offset(sat1X, sat1Y)
            )
            drawCircle(
                color = vpnColors.light.copy(alpha = 0.5f),
                radius = dotRadiusPx * 1.5f,
                center = Offset(sat1X, sat1Y)
            )

            // Satellite 2
            drawCircle(
                color = Color.White,
                radius = dotRadiusPx * 0.75f,
                center = Offset(sat2X, sat2Y)
            )
            drawCircle(
                color = vpnColors.light.copy(alpha = 0.5f),
                radius = dotRadiusPx * 1.5f,
                center = Offset(sat2X, sat2Y)
            )

            // ── 8. CENTRAL ENERGY SPINE & RUNNING DATA PACKETS ──
            drawLine(
                color = animatedJetColor.copy(alpha = 0.85f),
                start = apex,
                end = Offset(cx, shackleCenterY - shackleR),
                strokeWidth = spineStrokePx,
                cap = StrokeCap.Round
            )
            drawLine(
                color = animatedJetColor.copy(alpha = 0.65f),
                start = Offset(cx, lockTop + lockH),
                end = bottomTip,
                strokeWidth = strokeWidthPx * 0.85f,
                cap = StrokeCap.Round
            )

            // 2 Continuous flowing photon data packets traveling down the spine (only when active)
            if (animatedThrust > 0.05f) {
                for (pIndex in 0..1) {
                    val packetPhase = (t * 2.4f + pIndex * 0.5f) % 1.0f
                    val packetY = apex.y + (bottomTip.y - apex.y) * packetPhase

                    drawCircle(
                        color = Color.White,
                        radius = dotRadiusPx,
                        center = Offset(cx, packetY)
                    )
                    drawCircle(
                        color = vpnColors.light.copy(alpha = 0.65f),
                        radius = dotRadiusPx * 2.4f,
                        center = Offset(cx, packetY)
                    )
                }
            }

            // ── 9. APEX DIFFRACTION STAR BEACON ──
            val beaconAlpha = 0.85f + 0.15f * sin(t * 6f)
            val flareLen = with(density) { 5.dp.toPx() }
            // Horizontal flare
            drawLine(
                color = Color.White.copy(alpha = beaconAlpha),
                start = Offset(apex.x - flareLen, apex.y),
                end = Offset(apex.x + flareLen, apex.y),
                strokeWidth = with(density) { 0.9.dp.toPx() },
                cap = StrokeCap.Round
            )
            // Vertical flare
            drawLine(
                color = Color.White.copy(alpha = beaconAlpha),
                start = Offset(apex.x, apex.y - flareLen),
                end = Offset(apex.x, apex.y + flareLen),
                strokeWidth = with(density) { 0.9.dp.toPx() },
                cap = StrokeCap.Round
            )
            // Star center
            drawCircle(
                color = Color.White,
                radius = dotRadiusPx * 0.95f,
                center = apex
            )
            drawCircle(
                color = vpnColors.primary.copy(alpha = 0.55f),
                radius = dotRadiusPx * 2.2f,
                center = apex
            )
        }
    }
}

// =============================================================================
// VpnInfoWidget — Transparent AMOLED Information Row
// Absolutely ZERO background (color = Color.Transparent). Clean 1dp AmoledBorder.
// =============================================================================
@Composable
fun VpnInfoWidget(
    isCompact: Boolean,
    vpnState: VpnUiState,
    vpnColors: ProtocolColors,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = vpnState == VpnUiState.CONNECTED
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (isActive) vpnColors.primary.copy(alpha = 0.25f) else AmoledBorder),
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (isCompact) 8.dp else 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VpnInfoCell(
                    label = stringResource(R.string.vpn_stat_servers),
                    value = if (isActive) "Anycast" else "—",
                    isActive = isActive,
                    isCompact = isCompact,
                    modifier = Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, null) { onTap() }
                )

                // Central VPN cyber orb
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .border(
                            1.dp,
                            if (isActive) vpnColors.primary.copy(alpha = 0.40f) else AmoledBorder,
                            CircleShape
                        )
                        .clickable(remember { MutableInteractionSource() }, null) { onTap() }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_vpn),
                        contentDescription = "VPN",
                        tint = if (isActive) vpnColors.primary else TextMuted.copy(alpha = 0.45f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                VpnInfoCell(
                    label = stringResource(R.string.vpn_stat_protocol),
                    value = "WARP",
                    isActive = isActive,
                    isCompact = isCompact,
                    modifier = Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, null) { onTap() }
                )
            }

            Spacer(modifier = Modifier.height(if (isCompact) 5.dp else 7.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(AmoledBorder.copy(alpha = 0.5f))
            )
            Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 6.dp))

            // Notice bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(remember { MutableInteractionSource() }, null) { onTap() }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isActive) vpnColors.primary else vpnColors.primary.copy(alpha = 0.65f))
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = if (isActive) stringResource(R.string.status_vpn_system_connected_desc) else stringResource(R.string.vpn_screen_in_dev_hint),
                    color = if (isActive) TextWhite.copy(alpha = 0.85f) else TextMuted.copy(alpha = 0.65f),
                    fontSize = if (isCompact) 9.5.sp else 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun VpnInfoCell(
    label: String,
    value: String,
    isActive: Boolean,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = if (isActive) TextWhite else TextMuted,
            fontWeight = FontWeight.Bold,
            fontSize = if (isCompact) 14.sp else 16.sp
        )
    }
}

// =============================================================================
// VpnActionDock — Transparent AMOLED action buttons
// =============================================================================
@Composable
fun VpnActionDock(
    onTapProtocol: () -> Unit,
    onTapKillSwitch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            onClick = onTapProtocol,
            modifier = Modifier.weight(1f).height(44.dp).springPress(onClick = onTapProtocol),
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_info_circle),
                    contentDescription = stringResource(R.string.vpn_stat_protocol),
                    tint = TextMuted.copy(alpha = 0.60f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.vpn_stat_protocol),
                    color = TextMuted.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        Surface(
            onClick = onTapKillSwitch,
            modifier = Modifier.weight(1f).height(44.dp).springPress(onClick = onTapKillSwitch),
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_shield),
                    contentDescription = stringResource(R.string.vpn_killswitch_title),
                    tint = TextMuted.copy(alpha = 0.60f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.vpn_killswitch_title),
                    color = TextMuted.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

// =============================================================================
// VpnInDevDialog — Frosted-glass modal dialog (DialogBackdropBox)
// Strict factual text, NO emoji, unified with system-selected VPN accent.
// =============================================================================
@Composable
fun VpnInDevDialog(
    vpnColors: ProtocolColors,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        DialogBackdropBox(onDismiss = onDismiss) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .adaptiveContainerWidth(380.dp)
                    .padding(horizontal = 24.dp)
                    .clickable(remember { MutableInteractionSource() }, null) {}
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF0C0E14),
                    border = BorderStroke(1.dp, vpnColors.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)
                    ) {
                        // Icon badge
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(vpnColors.primary.copy(alpha = 0.12f))
                                .border(1.dp, vpnColors.primary.copy(alpha = 0.35f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_vpn),
                                contentDescription = null,
                                tint = vpnColors.primary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.vpn_mode_title),
                            color = TextWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.vpn_dialog_title_dev),
                            color = vpnColors.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.vpn_dialog_desc_dev),
                            color = TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            lineHeight = 19.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder)
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_dialog_independent_hint),
                                color = TextMuted.copy(alpha = 0.70f),
                                fontSize = 11.5.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Surface(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(12.dp),
                            color = vpnColors.primary.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, vpnColors.primary.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth().height(44.dp).springPress(onClick = onDismiss)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    text = stringResource(R.string.action_understood_short),
                                    color = vpnColors.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
