/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+  <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.ui.theme.ProtocolColors
import com.mirrly.tgproxy.ui.theme.VpnUiState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// =============================================================================
// Cloudflare Official Brand Spline Geometry (1:1 Exact Vector)
// Main body (left): Fuel Orange (#F6821F)
// Right wing (right): Fuel Gold (#FBAD41)
// =============================================================================

private const val CLOUD_MAIN_BODY_SVG =
    "M 16.5088 16.8447 c 0.1475 -0.5068 0.0908 -0.9707 -0.1553 -1.3154 c -0.2246 -0.3164 -0.6045 -0.499 -1.0615 -0.5205 l -8.6592 -0.1123 a 0.1559 0.1559 0 0 1 -0.1333 -0.0713 c -0.0283 -0.042 -0.0351 -0.0986 -0.021 -0.1553 c 0.0278 -0.084 0.1123 -0.1484 0.2036 -0.1562 l 8.7359 -0.1123 c 1.0351 -0.0489 2.1601 -0.8868 2.5537 -1.9136 l 0.499 -1.3013 c 0.0215 -0.0561 0.0293 -0.1128 0.0147 -0.168 c -0.5625 -2.5463 -2.835 -4.4453 -5.5499 -4.4453 c -2.5039 0 -4.6284 1.6177 -5.3876 3.8614 c -0.4927 -0.3658 -1.1187 -0.5625 -1.794 -0.499 c -1.2026 0.119 -2.1665 1.083 -2.2861 2.2856 c -0.0283 0.31 -0.0069 0.6128 0.0635 0.894 C 1.5683 13.171 0 14.7754 0 16.752 c 0 0.1748 0.0142 0.3515 0.0352 0.5273 c 0.0141 0.083 0.0844 0.1475 0.1689 0.1475 h 15.9814 c 0.0909 0 0.1758 -0.0645 0.2032 -0.1553 l 0.12 -0.4268 z"

private const val CLOUD_RIGHT_WING_SVG =
    "M 19.2656 11.2813 c -0.0771 0 -0.1611 0 -0.2383 0.0112 c -0.0566 0 -0.1054 0.0415 -0.127 0.0976 l -0.3378 1.1744 c -0.1475 0.5068 -0.0918 0.9707 0.1543 1.3164 c 0.2256 0.3164 0.6055 0.498 1.0625 0.5195 l 1.8437 0.1133 c 0.0557 0 0.1055 0.0263 0.1329 0.0703 c 0.0283 0.043 0.0351 0.1074 0.0214 0.1562 c -0.0283 0.084 -0.1132 0.1485 -0.204 0.1553 l -1.921 0.1123 c -1.041 0.0488 -2.1582 0.8867 -2.5527 1.914 l -0.1406 0.3585 c -0.0283 0.0713 0.0215 0.1416 0.0986 0.1416 h 6.5977 c 0.0771 0 0.1474 -0.0489 0.169 -0.126 c 0.1122 -0.4082 0.1757 -0.837 0.1757 -1.2803 c 0 -2.6025 -2.125 -4.727 -4.7344 -4.727 z"

// Official Brand Colors
private val CF_FUEL_ORANGE = Color(0xFFF6821F)
private val CF_FUEL_GOLD = Color(0xFFFBAD41)

// =============================================================================
// Kinetic Energy Particle Descriptor
// =============================================================================
private class WarpEnergyParticle(
    val id: Int,
    val initialAngleRad: Float,
    val speedFactor: Float,
    val baseRadiusFraction: Float,
    val sizeDp: Float
)

// =============================================================================
// AnimatedWarpCloud — 1:1 Official Cloudflare Animated Dynamic Component
// =============================================================================
@Composable
fun AnimatedWarpCloud(
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

    val app = MirrlyApplication.instance
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

    // Dynamic states and transitions
    val targetCloudScale = when (state) {
        VpnUiState.DISCONNECTED -> 0.94f // Clean, relaxed, passive
        VpnUiState.CONNECTING -> 0.88f   // Subtle tension compression before breakthrough
        VpnUiState.CONNECTED -> 1.00f    // Authoritative full scale
        VpnUiState.DISCONNECTING -> 0.94f
    }
    val animatedCloudScale by animateFloatAsState(
        targetValue = targetCloudScale,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "warpCloudScale"
    )

    val targetBodyAlpha = when (state) {
        VpnUiState.DISCONNECTED -> 0f    // Zero fill in stopped mode
        VpnUiState.CONNECTING -> 0.78f   // Internal energy accumulation
        VpnUiState.CONNECTED -> 1.00f    // Fully opaque solid 1:1 Cloudflare mass
        VpnUiState.DISCONNECTING -> 0.40f
    }
    val animatedBodyAlpha by animateFloatAsState(
        targetValue = targetBodyAlpha,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "warpBodyAlpha"
    )

    val targetVerticalLiftDp = when (state) {
        VpnUiState.DISCONNECTED -> 0.dp
        VpnUiState.CONNECTING -> (-3.0).dp
        VpnUiState.CONNECTED -> (-1.5).dp
        VpnUiState.DISCONNECTING -> (-0.5).dp
    }
    val animatedVerticalLiftDp by animateDpAsState(
        targetValue = targetVerticalLiftDp,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
        label = "warpVerticalLift"
    )

    // Breakthrough shockwave trigger when entering CONNECTED
    val shockwaveAnim = remember { Animatable(0f) }
    var previousState by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        if ((previousState == VpnUiState.CONNECTING || previousState == VpnUiState.DISCONNECTED) && state == VpnUiState.CONNECTED) {
            shockwaveAnim.snapTo(0f)
            shockwaveAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
            )
        } else if (state != VpnUiState.CONNECTED) {
            shockwaveAnim.snapTo(0f)
        }
        previousState = state
    }

    // Pre-allocated parsed 1:1 official paths
    val (rawMainPath, rawRightPath) = remember {
        val main = PathParser.createPathFromPathData(CLOUD_MAIN_BODY_SVG).asComposePath()
        val right = PathParser.createPathFromPathData(CLOUD_RIGHT_WING_SVG).asComposePath()
        Pair(main, right)
    }

    // Exact geometric bounds for mathematical sub-pixel centering
    val cloudBounds = remember(rawMainPath, rawRightPath) {
        val b1 = rawMainPath.getBounds()
        val b2 = rawRightPath.getBounds()
        Rect(
            left = minOf(b1.left, b2.left),
            top = minOf(b1.top, b2.top),
            right = maxOf(b1.right, b2.right),
            bottom = maxOf(b1.bottom, b2.bottom)
        )
    }

    // Kinetic Particle system (22 particles with diverse orbits and inward trajectories)
    val particles = remember {
        List(22) { idx ->
            WarpEnergyParticle(
                id = idx,
                initialAngleRad = (idx * (2f * PI / 22f)).toFloat(),
                speedFactor = 0.75f + (idx % 5) * 0.22f,
                baseRadiusFraction = 0.65f + (idx % 4) * 0.12f,
                sizeDp = 1.6f + (idx % 3) * 0.7f
            )
        }
    }

    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 1.6.dp.toPx() } }
    val glowStrokeWidthPx = remember(density) { with(density) { 5.5.dp.toPx() } }
    val liftPx = with(density) { animatedVerticalLiftDp.toPx() }

    // Pre-allocated brushes to prevent per-frame GC allocations during 60/120Hz render
    val mainBodyBrush = remember(cloudBounds) {
        Brush.verticalGradient(
            colors = listOf(Color(0xFFFA8926), Color(0xFFF37517)),
            startY = cloudBounds.top,
            endY = cloudBounds.bottom
        )
    }
    val rightWingBrush = remember(cloudBounds) {
        Brush.verticalGradient(
            colors = listOf(Color(0xFFFCB54C), Color(0xFFFA9F30)),
            startY = cloudBounds.top,
            endY = cloudBounds.bottom
        )
    }
    val sheenColors = remember {
        listOf(
            Color.White.copy(alpha = 0f),
            Color.White.copy(alpha = 0.20f),
            Color.White.copy(alpha = 0f)
        )
    }

    Canvas(modifier = modifier) {
        val t = if (!isAnimationsDisabled) timeState.value / 1_000_000_000f else 0f
        val diameter = size.minDimension
        val cx = size.width / 2f
        val baseCy = size.height / 2f + liftPx

        // Idle gentle breathing vs. noble levitation
        val idleBreathY = if (state == VpnUiState.DISCONNECTED) sin(t * 1.5f) * (diameter * 0.012f) else 0f
        val levitationY = if (state == VpnUiState.CONNECTED) sin(t * 2.0f) * (diameter * 0.012f) else 0f

        // Connecting dynamics: micro-rumble + struggle surges
        val rumbleX: Float
        val rumbleY: Float
        val struggleScalePulse: Float
        val plasmaFlicker: Float

        if (state == VpnUiState.CONNECTING) {
            val strainTension = 1.0f
            rumbleX = sin(t * 54f) * (diameter * 0.015f) * strainTension
            rumbleY = cos(t * 46f) * (diameter * 0.011f) * strainTension

            val strugglePeriod = 1.5f
            val cycleProgress = (t % strugglePeriod) / strugglePeriod
            val surge = if (cycleProgress < 0.60f) {
                sin(cycleProgress / 0.60f * PI.toFloat()) * 0.12f
            } else {
                -sin((cycleProgress - 0.60f) / 0.40f * PI.toFloat()) * 0.04f
            }
            struggleScalePulse = surge
            plasmaFlicker = (0.55f + 0.45f * sin(t * 32f) * sin(t * 22f)).coerceIn(0.2f, 1.0f)
        } else {
            rumbleX = 0f
            rumbleY = 0f
            struggleScalePulse = 0f
            plasmaFlicker = 1.0f
        }

        val effectiveCx = cx + rumbleX
        val effectiveCy = baseCy + idleBreathY + levitationY + rumbleY

        // Optimal scale factor mapping 1:1 Cloudflare into bounds (width ~ 72% of circle diameter)
        val targetWidth = diameter * 0.72f
        val baseScaleFactor = targetWidth / cloudBounds.width
        val effectiveScale = (animatedCloudScale + struggleScalePulse) * baseScaleFactor

        // 0. Breakthrough Shockwave Expansion
        val shockProgress = shockwaveAnim.value
        if (shockProgress > 0f && shockProgress < 1f) {
            val shockRadius = (diameter * 0.22f) + (diameter * 0.35f) * shockProgress
            val shockAlpha = ((1f - shockProgress) * 0.75f).coerceIn(0f, 1f)
            drawCircle(
                color = CF_FUEL_GOLD.copy(alpha = shockAlpha),
                radius = shockRadius,
                center = Offset(effectiveCx, effectiveCy),
                style = Stroke(width = strokeWidthPx * (1f - shockProgress * 0.5f) * 1.5f)
            )
            drawCircle(
                color = CF_FUEL_ORANGE.copy(alpha = shockAlpha * 0.45f),
                radius = shockRadius * 0.82f,
                center = Offset(effectiveCx, effectiveCy),
                style = Stroke(width = strokeWidthPx * 0.8f)
            )
        }

        // 1. Kinetic Particles (Connecting inward / Connected orbital / Idle vapor)
        drawKineticParticles(
            particles = particles,
            t = t,
            state = state,
            cx = effectiveCx,
            cy = effectiveCy,
            diameter = diameter,
            vpnColors = vpnColors
        )

        // 2. Transformation Scope centered with mathematical exactness on cloud bounds
        translate(left = effectiveCx, top = effectiveCy) {
            scale(scaleX = effectiveScale, scaleY = effectiveScale, pivot = Offset.Zero) {
                translate(left = -cloudBounds.center.x, top = -cloudBounds.center.y) {

                    // A. Ambient Core Corona Glow (Connected & Connecting)
                    if (state == VpnUiState.CONNECTED || state == VpnUiState.CONNECTING) {
                        val coronaAlpha = if (state == VpnUiState.CONNECTED) {
                            (0.24f + 0.08f * sin(t * 2.2f)) * animatedBodyAlpha
                        } else {
                            0.20f * plasmaFlicker * animatedBodyAlpha
                        }
                        val coronaStroke = Stroke(
                            width = glowStrokeWidthPx / effectiveScale,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                        drawPath(path = rawMainPath, color = CF_FUEL_ORANGE.copy(alpha = coronaAlpha), style = coronaStroke)
                        drawPath(path = rawRightPath, color = CF_FUEL_GOLD.copy(alpha = coronaAlpha), style = coronaStroke)
                    }

                    // B. Cloud Interior Fill
                    when (state) {
                        VpnUiState.DISCONNECTED -> {
                            // STOPPED STATE: STRICTLY WITHOUT FILL (TRANSPARENT)
                            // NO fill drawn as explicitly requested!
                        }

                        VpnUiState.CONNECTING -> {
                            // Warm energy accumulating inside
                            val connectingAlpha = (animatedBodyAlpha * (0.65f + 0.35f * plasmaFlicker)).coerceIn(0.2f, 1f)
                            drawPath(path = rawMainPath, color = CF_FUEL_ORANGE.copy(alpha = connectingAlpha * 0.85f))
                            drawPath(path = rawRightPath, color = CF_FUEL_GOLD.copy(alpha = connectingAlpha * 0.85f))
                        }

                        VpnUiState.CONNECTED, VpnUiState.DISCONNECTING -> {
                            // 1:1 AUTHENTIC CLOUDFLARE PALETTE (NO HARSH GRADIENTS)
                            drawPath(path = rawMainPath, brush = mainBodyBrush)
                            drawPath(path = rawRightPath, brush = rightWingBrush)
                        }
                    }

                    // C. Precision Laser Contours / Outlines
                    when (state) {
                        VpnUiState.DISCONNECTED -> {
                            // Elegant crisp outline without fill
                            val inactiveStroke = Stroke(
                                width = 1.4.dp.toPx() / effectiveScale,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                            val inactiveBorderColor = Color(0x66FFFFFF)
                            drawPath(path = rawMainPath, color = inactiveBorderColor, style = inactiveStroke)
                            drawPath(path = rawRightPath, color = inactiveBorderColor, style = inactiveStroke)
                        }

                        VpnUiState.CONNECTING -> {
                            // High-voltage active border
                            val connectingStroke = Stroke(
                                width = 1.8.dp.toPx() / effectiveScale,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                            drawPath(path = rawMainPath, color = CF_FUEL_ORANGE, style = connectingStroke)
                            drawPath(path = rawRightPath, color = CF_FUEL_GOLD, style = connectingStroke)
                        }

                        VpnUiState.CONNECTED, VpnUiState.DISCONNECTING -> {
                            // Crisp subtle rim highlight
                            val rimStroke = Stroke(
                                width = 1.2.dp.toPx() / effectiveScale,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                            drawPath(path = rawMainPath, color = Color(0xFFFFA24A).copy(alpha = 0.65f), style = rimStroke)
                            drawPath(path = rawRightPath, color = Color(0xFFFFD48A).copy(alpha = 0.70f), style = rimStroke)
                        }
                    }

                    // D. Soft Kinetic Shimmer Sheen (Animated specular wave crossing the cloud)
                    if (state == VpnUiState.CONNECTED) {
                        val sweepPeriod = 3.6f
                        val sweepProgress = ((t % sweepPeriod) / sweepPeriod)
                        val sheenX = cloudBounds.left - 4f + (cloudBounds.width + 8f) * sweepProgress
                        val sheenBrush = Brush.linearGradient(
                            colors = sheenColors,
                            start = Offset(sheenX - 2.5f, cloudBounds.top),
                            end = Offset(sheenX + 2.5f, cloudBounds.bottom)
                        )
                        drawPath(path = rawMainPath, brush = sheenBrush)
                        drawPath(path = rawRightPath, brush = sheenBrush)
                    }
                }
            }
        }
    }
}

// =============================================================================
// Helper: Kinetic Particle Inflow (Connecting) and Orbital Satellites (Connected)
// =============================================================================
private fun DrawScope.drawKineticParticles(
    particles: List<WarpEnergyParticle>,
    t: Float,
    state: VpnUiState,
    cx: Float,
    cy: Float,
    diameter: Float,
    vpnColors: ProtocolColors
) {
    val outerRingRadius = diameter * 0.46f

    when (state) {
        VpnUiState.CONNECTING -> {
            // DRAMATIC INWARD ENERGY GATHERING:
            // Particles rush from the outer ring INWARD toward the cloud core!
            for (p in particles) {
                val cycleTime = 1.20f / p.speedFactor
                val rawProgress = ((t + p.id * 0.16f) % cycleTime) / cycleTime
                val inwardProgress = 1.0f - rawProgress // 1.0 (outer) -> 0.0 (core)

                val spiralAngle = p.initialAngleRad + rawProgress * 1.8f
                val currentRadius = outerRingRadius * inwardProgress * p.baseRadiusFraction

                val px = cx + cos(spiralAngle) * currentRadius
                val py = cy + sin(spiralAngle) * currentRadius

                val intensity = (1.0f - inwardProgress * 0.4f).coerceIn(0.25f, 1.0f)
                val pColor = if (p.id % 2 == 0) CF_FUEL_GOLD else CF_FUEL_ORANGE

                // Particle nucleus
                drawCircle(
                    color = pColor.copy(alpha = intensity),
                    radius = (p.sizeDp * 1.25f),
                    center = Offset(px, py)
                )

                // Trailing photon streak pointing along trajectory
                val tailLength = (14f * (1f - inwardProgress)).coerceAtLeast(3f)
                val tailAngle = spiralAngle + PI.toFloat() - 0.2f
                val tailX = px + cos(tailAngle) * tailLength
                val tailY = py + sin(tailAngle) * tailLength

                drawLine(
                    color = pColor.copy(alpha = intensity * 0.5f),
                    start = Offset(px, py),
                    end = Offset(tailX, tailY),
                    strokeWidth = 1.6f,
                    cap = StrokeCap.Round
                )
            }
        }

        VpnUiState.CONNECTED -> {
            // STABILIZED QUANTUM SATELLITES:
            // High-precision circular orbits rotating gracefully around the cloud
            val activeCount = 8
            for (i in 0 until activeCount) {
                val p = particles[i]
                val orbitRadius = diameter * (0.42f + (i % 3) * 0.04f)
                val orbitAngle = p.initialAngleRad + t * (0.65f + (i % 2) * 0.35f)

                val px = cx + cos(orbitAngle) * orbitRadius
                val py = cy + sin(orbitAngle) * orbitRadius

                val satColor = if (i % 2 == 0) CF_FUEL_GOLD else CF_FUEL_ORANGE

                // Outer quantum glow
                drawCircle(
                    color = satColor.copy(alpha = 0.35f),
                    radius = p.sizeDp * 2.2f,
                    center = Offset(px, py)
                )
                // Crisp photon core
                drawCircle(
                    color = Color.White.copy(alpha = 0.95f),
                    radius = p.sizeDp * 0.85f,
                    center = Offset(px, py)
                )
            }
        }

        VpnUiState.DISCONNECTED -> {
            // IDLE LAZY VAPOR:
            // 4 faint vapor particles slowly floating upward and fading
            for (i in 0 until 4) {
                val p = particles[i]
                val vaporCycle = 4.2f
                val progress = ((t * 0.38f + i * 1.2f) % vaporCycle) / vaporCycle
                val vaporY = cy + (diameter * 0.12f) - progress * (diameter * 0.30f)
                val vaporX = cx + sin(progress * 2f * PI.toFloat() + i) * (diameter * 0.16f)
                val vaporAlpha = (sin(progress * PI.toFloat()) * 0.20f).coerceAtLeast(0f)

                drawCircle(
                    color = Color(0x3590A4AE).copy(alpha = vaporAlpha),
                    radius = p.sizeDp * 2.0f,
                    center = Offset(vaporX, vaporY)
                )
            }
        }

        VpnUiState.DISCONNECTING -> {
            // Fading residual sparks
            for (i in 0 until 6) {
                val p = particles[i]
                val rad = outerRingRadius * (0.5f + (i % 2) * 0.2f)
                val angle = p.initialAngleRad + t * 0.3f
                drawCircle(
                    color = Color(0x60F6821F),
                    radius = p.sizeDp,
                    center = Offset(cx + cos(angle) * rad, cy + sin(angle) * rad)
                )
            }
        }
    }
}
