package com.mirrly.tgproxy.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mirrly.tgproxy.ui.theme.ActiveGreenLed
import com.mirrly.tgproxy.ui.theme.rememberAnimatedProtocolColors
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private data class MicroParticle(
    val relX: Float,        // Relative start X (0..1)
    val relY: Float,        // Relative start Y (0..1)
    val sizeDp: Float,      // Size in dp
    val speedY: Float,      // Base speed multiplier
    val driftXAmp: Float,   // Horizontal drift amplitude
    val freqX: Float,       // Frequency of X drift
    val phase: Float,       // Random phase offset
    val baseAlpha: Float    // Base particle alpha
)

private data class PreparedParticle(
    val relX: Float,
    val relY: Float,
    val sizePx: Float,
    val sizeDp: Float,
    val speedY: Float,
    val driftXPx: Float,
    val freqX: Float,
    val phase: Float,
    val baseAlpha: Float
)

private class CanvasMetrics(
    val dp3Px: Float,
    val p120: Float,
    val p135: Float,
    val p175: Float,
    val p25: Float,
    val p110: Float,
    val p55: Float,
    val p150: Float,
    val p50: Float,
    val p195: Float,
    val p30: Float,
    val p130: Float,
    val p40: Float,
    val p45: Float,
    val p165: Float,
    val p22: Float,
    val p95: Float,
    val p125: Float,
    val p35: Float,
    val p185: Float,
    val p28: Float,
    val speedYConn: Float,
    val speedYDisconn: Float
)

@Composable
fun CyberEnergyCanvas(
    state: ProxyUiState,
    isSocks5: Boolean = com.mirrly.tgproxy.MirrlyApplication.instance.prefsManager.isSocks5Flow.collectAsState().value,
    externalTouchPoint: Offset? = null,
    isUiHidden: Boolean = false,
    isConstellationPaused: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── BATTERY LIFECYCLE GUARD: MONITOR APP FOREGROUND/BACKGROUND STATE ──
    var isAppResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    isAppResumed = false
                }
                Lifecycle.Event.ON_RESUME,
                Lifecycle.Event.ON_START -> {
                    isAppResumed = true
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ── PERFORMANCE GUARD: CHECK IF USER DISABLED ANIMATIONS ──
    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val isAnimationsDisabled by app.prefsManager.animationsDisabledFlow.collectAsState()

    // ── ANIMATION FRAME TIMER (PAUSED IN BACKGROUND OR IF ANIMATIONS DISABLED) ──
    val shouldAnimate = isAppResumed && !isAnimationsDisabled
    val timeState = produceState(initialValue = 0L, shouldAnimate) {
        if (!shouldAnimate) return@produceState
        val startNano = System.nanoTime() - value
        while (shouldAnimate) {
            withFrameNanos { frameTimeNanos ->
                value = frameTimeNanos - startNano
            }
        }
    }

    // Dynamic state animation parameters
    val isConnected = state == ProxyUiState.CONNECTED || state == ProxyUiState.CONNECTING

    // Transition progress (0f when disconnected, 1f when connected/connecting)
    val energyTarget = if (isConnected) 1.0f else 0.0f
    val animatedEnergy by animateFloatAsState(
        targetValue = energyTarget,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "energyTransition"
    )

    var lastState by remember { mutableStateOf(state) }
    val sparkBurst = remember { Animatable(0f) }
    val whiteBloom = remember { Animatable(0f) }

    LaunchedEffect(state) {
        val wasActive = lastState == ProxyUiState.CONNECTED || lastState == ProxyUiState.CONNECTING
        val isNowActive = state == ProxyUiState.CONNECTED || state == ProxyUiState.CONNECTING
        lastState = state

        if (wasActive && !isNowActive) {
            // ── TURN OFF SCENARIO: Smooth gentle flare-down on particles (no jarring flashes) ──
            sparkBurst.snapTo(0f)
            sparkBurst.animateTo(
                targetValue = 0.65f,
                animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)
            )
            sparkBurst.animateTo(
                targetValue = 0.0f,
                animationSpec = tween(durationMillis = 420, easing = FastOutLinearInEasing)
            )
        } else if (!wasActive && isNowActive) {
            // ── TURN ON SCENARIO: Sudden bright white bloom -> smooth fade to violet/emerald ──
            whiteBloom.snapTo(1.0f)
            whiteBloom.animateTo(
                targetValue = 0.0f,
                animationSpec = tween(durationMillis = 750, easing = LinearOutSlowInEasing)
            )
        }
    }

    // Dynamic Protocol Colors (MTProto = Emerald/Cyan, SOCKS5 = Violet/Purple)
    val protoColors = rememberAnimatedProtocolColors(isSocks5 = isSocks5)

    // Animated colors for glowing spheres across all 3 states:
    // 1. Disconnected: Barely visible (еле заметные), subtle dark graphite
    // 2. Connected MTProto: Vivid Emerald / Cyan
    // 3. Connected SOCKS5: Vivid Violet / Purple
    val targetOrb1 = if (isConnected) protoColors.orb1.copy(alpha = 0.28f) else Color(0xFF222B38).copy(alpha = 0.16f)
    val targetOrb2 = if (isConnected) protoColors.orb2.copy(alpha = 0.24f) else Color(0xFF1E2634).copy(alpha = 0.14f)
    val targetOrb3 = if (isConnected) protoColors.orb3.copy(alpha = 0.20f) else Color(0xFF18202C).copy(alpha = 0.11f)
    val targetOrb4 = if (isConnected) protoColors.orb4.copy(alpha = 0.22f) else Color(0xFF202836).copy(alpha = 0.13f)

    val orb1CenterColor by animateColorAsState(targetOrb1, tween(750, easing = FastOutSlowInEasing), label = "orb1Center")
    val orb2CenterColor by animateColorAsState(targetOrb2, tween(750, easing = FastOutSlowInEasing), label = "orb2Center")
    val orb3CenterColor by animateColorAsState(targetOrb3, tween(750, easing = FastOutSlowInEasing), label = "orb3Center")
    val orb4CenterColor by animateColorAsState(targetOrb4, tween(750, easing = FastOutSlowInEasing), label = "orb4Center")

    // Target resting particle color across all 3 states:
    // 1. Disconnected: Delicate starry white dots
    // 2. Connected MTProto: Pure Emerald dots
    // 3. Connected SOCKS5: Pure Violet dots
    val targetParticleColor = if (isConnected) protoColors.primary else Color(0xFFF1F5F9)
    val particleColor by animateColorAsState(
        targetValue = targetParticleColor,
        animationSpec = tween(750, easing = FastOutSlowInEasing),
        label = "particleColor"
    )

    // Dynamic focus boost when UI is hidden
    val focusTarget = if (isUiHidden) 1.35f else 1.0f
    val animatedFocusBoost by animateFloatAsState(
        targetValue = focusTarget,
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "focusBoost"
    )

    // Touch interaction physics state
    val scope = rememberCoroutineScope()
    var localTouchPoint by remember { mutableStateOf<Offset?>(null) }
    val effectiveTouchPoint = externalTouchPoint ?: localTouchPoint
    val touchIntensity = remember { Animatable(0f) }

    LaunchedEffect(effectiveTouchPoint) {
        if (effectiveTouchPoint != null) {
            touchIntensity.animateTo(1f, tween(120, easing = LinearOutSlowInEasing))
        } else {
            touchIntensity.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
        }
    }

    // Generate 50 fine micro-particles with deterministic seed (delicate, non-intrusive size ~0.75dp .. 2.1dp)
    val particles = remember {
        val list = mutableListOf<MicroParticle>()
        val random = java.util.Random(2026)
        for (i in 0 until 50) {
            list.add(
                MicroParticle(
                    relX = random.nextFloat(),
                    relY = random.nextFloat(),
                    sizeDp = 0.80f + random.nextFloat() * 1.30f,
                    speedY = 0.35f + random.nextFloat() * 0.65f,
                    driftXAmp = 10f + random.nextFloat() * 20f,
                    freqX = 0.35f + random.nextFloat() * 0.75f,
                    phase = random.nextFloat() * 6.283f,
                    baseAlpha = 0.35f + random.nextFloat() * 0.45f
                )
            )
        }
        list
    }

    // Precalculate particle physical pixel metrics once per density change
    val preparedParticles = remember(density, particles) {
        particles.map { p ->
            PreparedParticle(
                relX = p.relX,
                relY = p.relY,
                sizePx = with(density) { p.sizeDp.dp.toPx() },
                sizeDp = p.sizeDp,
                speedY = p.speedY,
                driftXPx = with(density) { p.driftXAmp.dp.toPx() },
                freqX = p.freqX,
                phase = p.phase,
                baseAlpha = p.baseAlpha
            )
        }
    }

    // Precalculated reusable FloatArray to hold (px, py, alpha) for zero-allocation constellation rendering
    val particleCoords = remember { FloatArray(50 * 3) }

    // Precalculate layout pixel metrics once per density change (Spheres reduced by 25%)
    val metrics = remember(density) {
        with(density) {
            CanvasMetrics(
                dp3Px = 3.dp.toPx(),
                p120 = 90.dp.toPx(),
                p135 = 101.dp.toPx(),
                p175 = 131.dp.toPx(),
                p25 = 19.dp.toPx(),
                p110 = 82.dp.toPx(),
                p55 = 41.dp.toPx(),
                p150 = 112.dp.toPx(),
                p50 = 37.dp.toPx(),
                p195 = 146.dp.toPx(),
                p30 = 22.5f.dp.toPx(),
                p130 = 97.dp.toPx(),
                p40 = 30.dp.toPx(),
                p45 = 34.dp.toPx(),
                p165 = 124.dp.toPx(),
                p22 = 16.5f.dp.toPx(),
                p95 = 71.dp.toPx(),
                p125 = 94.dp.toPx(),
                p35 = 26.dp.toPx(),
                p185 = 139.dp.toPx(),
                p28 = 21.dp.toPx(),
                speedYConn = -22.dp.toPx(),
                speedYDisconn = -6.dp.toPx()
            )
        }
    }

    val maxConnDistPx = remember(density) { with(density) { 56.dp.toPx() } }
    val maxConnDistSq = remember(maxConnDistPx) { maxConnDistPx * maxConnDistPx }
    val touchRadiusPx = remember(density) { with(density) { 150.dp.toPx() } }
    val connStrokeWidth = remember(density) { with(density) { 0.85.dp.toPx() } }

    // Reusable paint and gradient buffers to eliminate heap allocations in onDraw
    val orbPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
        }
    }
    val bloomPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
        }
    }
    val orbColorBuffer = remember { IntArray(3) }
    val orbStopBuffer = remember { floatArrayOf(0f, 0.45f, 1f) }
    val bloomColorBuffer = remember { IntArray(3) }
    val bloomStopBuffer = remember { floatArrayOf(0f, 0.45f, 1f) }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull()
                    if (change != null) {
                        localTouchPoint = if (change.pressed) change.position else null
                    }
                }
            }
        }
    ) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        if (isAnimationsDisabled) {
            drawRect(color = Color.Black)
            return@Canvas
        }

        val t = timeState.value / 1_000_000_000f
        val tiltX = 0f
        val tiltY = 0f

        // Balanced float speed for glowing spheres
        val timeScale = 0.70f + animatedEnergy * 0.45f

        // ── 0. WHITE BLOOM & 1. GLOWING SPHERES (ZERO HEAP ALLOCATION ON NATIVE CANVAS) ──
        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas

            // 0. White Bloom Energy Surge on Connect
            if (whiteBloom.value > 0.01f && isConnected) {
                val bloomAlpha = (whiteBloom.value * 0.32f).coerceIn(0f, 1f)
                val bloomRadius = (width.coerceAtLeast(height) * 0.45f) * (0.80f + 0.20f * (1f - whiteBloom.value))
                val bloomC1 = Color.White.copy(alpha = bloomAlpha * 0.75f).toArgb()
                val bloomC2 = protoColors.light.copy(alpha = bloomAlpha * 0.40f).toArgb()
                bloomColorBuffer[0] = bloomC1
                bloomColorBuffer[1] = bloomC2
                bloomColorBuffer[2] = 0x00000000

                bloomPaint.shader = android.graphics.RadialGradient(
                    width / 2f,
                    height / 2f,
                    bloomRadius.coerceAtLeast(1f),
                    bloomColorBuffer,
                    bloomStopBuffer,
                    android.graphics.Shader.TileMode.CLAMP
                )
                nativeCanvas.drawCircle(width / 2f, height / 2f, bloomRadius, bloomPaint)
            }

            // Orb 1: Upper-Right Organic Flow
            val orb1X = width * 0.65f + sin(t * 0.14f * timeScale) * metrics.p120 + cos(t * 0.22f * timeScale + 1.4f) * metrics.p45 - tiltX * 0.5f
            val orb1Y = height * 0.22f + cos(t * 0.11f * timeScale + 0.7f) * metrics.p135 + sin(t * 0.18f * timeScale) * metrics.p40 - tiltY * 0.5f
            val orb1Radius = metrics.p175 + sin(t * 0.15f) * metrics.p25
            val o1c = orb1CenterColor.toArgb()
            val o1Alpha = (o1c ushr 24) and 0xFF
            val o1Mid = ((o1Alpha * 0.40f).toInt() shl 24) or (o1c and 0x00FFFFFF)
            orbColorBuffer[0] = o1c
            orbColorBuffer[1] = o1Mid
            orbColorBuffer[2] = 0x00000000
            orbPaint.shader = android.graphics.RadialGradient(
                orb1X, orb1Y, orb1Radius.coerceAtLeast(1f),
                orbColorBuffer,
                orbStopBuffer,
                android.graphics.Shader.TileMode.CLAMP
            )
            nativeCanvas.drawCircle(orb1X, orb1Y, orb1Radius, orbPaint)

            // Orb 2: Center-Left Organic Flow
            val orb2X = width * 0.25f + cos(t * 0.12f * timeScale + 2.1f) * metrics.p110 + sin(t * 0.19f * timeScale) * metrics.p55 - tiltX * 0.7f
            val orb2Y = height * 0.52f + sin(t * 0.15f * timeScale + 1.1f) * metrics.p150 + cos(t * 0.24f * timeScale + 0.3f) * metrics.p50 - tiltY * 0.7f
            val orb2Radius = metrics.p195 + cos(t * 0.13f + 1.5f) * metrics.p30
            val o2c = orb2CenterColor.toArgb()
            val o2Alpha = (o2c ushr 24) and 0xFF
            val o2Mid = ((o2Alpha * 0.35f).toInt() shl 24) or (o2c and 0x00FFFFFF)
            orbColorBuffer[0] = o2c
            orbColorBuffer[1] = o2Mid
            orbColorBuffer[2] = 0x00000000
            orbPaint.shader = android.graphics.RadialGradient(
                orb2X, orb2Y, orb2Radius.coerceAtLeast(1f),
                orbColorBuffer,
                orbStopBuffer,
                android.graphics.Shader.TileMode.CLAMP
            )
            nativeCanvas.drawCircle(orb2X, orb2Y, orb2Radius, orbPaint)

            // Orb 3: Lower-Right Organic Flow
            val orb3X = width * 0.75f + sin(t * 0.10f * timeScale + 4.2f) * metrics.p130 + cos(t * 0.18f * timeScale + 2.5f) * metrics.p40 - tiltX * 0.4f
            val orb3Y = height * 0.78f + cos(t * 0.17f * timeScale + 2.8f) * metrics.p110 + sin(t * 0.25f * timeScale + 0.9f) * metrics.p45 - tiltY * 0.4f
            val orb3Radius = metrics.p165 + sin(t * 0.14f + 2.5f) * metrics.p22
            val o3c = orb3CenterColor.toArgb()
            val o3Alpha = (o3c ushr 24) and 0xFF
            val o3Mid = ((o3Alpha * 0.30f).toInt() shl 24) or (o3c and 0x00FFFFFF)
            orbColorBuffer[0] = o3c
            orbColorBuffer[1] = o3Mid
            orbColorBuffer[2] = 0x00000000
            orbPaint.shader = android.graphics.RadialGradient(
                orb3X, orb3Y, orb3Radius.coerceAtLeast(1f),
                orbColorBuffer,
                orbStopBuffer,
                android.graphics.Shader.TileMode.CLAMP
            )
            nativeCanvas.drawCircle(orb3X, orb3Y, orb3Radius, orbPaint)

            // Orb 4: Deep Tech Accent Floating Mid-Top
            val orb4X = width * 0.45f + cos(t * 0.09f * timeScale + 0.8f) * metrics.p95 + sin(t * 0.16f * timeScale + 3.1f) * metrics.p50 - tiltX * 0.6f
            val orb4Y = height * 0.35f + sin(t * 0.13f * timeScale + 3.5f) * metrics.p125 + cos(t * 0.21f * timeScale) * metrics.p35 - tiltY * 0.6f
            val orb4Radius = metrics.p185 + sin(t * 0.11f + 0.5f) * metrics.p28
            val o4c = orb4CenterColor.toArgb()
            val o4Alpha = (o4c ushr 24) and 0xFF
            val o4Mid = ((o4Alpha * 0.35f).toInt() shl 24) or (o4c and 0x00FFFFFF)
            orbColorBuffer[0] = o4c
            orbColorBuffer[1] = o4Mid
            orbColorBuffer[2] = 0x00000000
            orbPaint.shader = android.graphics.RadialGradient(
                orb4X, orb4Y, orb4Radius.coerceAtLeast(1f),
                orbColorBuffer,
                orbStopBuffer,
                android.graphics.Shader.TileMode.CLAMP
            )
            nativeCanvas.drawCircle(orb4X, orb4Y, orb4Radius, orbPaint)
        }

        // ── 2. UNIFIED MICRO-PARTICLES & TOUCH INTERACTION ─────────────────
        val baseSpeedY = if (isConnected) metrics.speedYConn else metrics.speedYDisconn
        val currentTouch = effectiveTouchPoint
        val touchWeight = touchIntensity.value

        // Pass 1: Compute particle coordinates with touch deflection physics
        for (i in preparedParticles.indices) {
            val p = preparedParticles[i]
            val driftXPx = p.driftXPx

            // Continuous vertical position with smooth tilt shift
            val rawY = p.relY * height + t * baseSpeedY * p.speedY + tiltY * p.speedY * 1.2f
            var py = ((rawY % height) + height) % height

            // Continuous horizontal position with smooth tilt shift
            val rawX = p.relX * width + sin(t * p.freqX + p.phase) * driftXPx + tiltX * p.speedY * 1.2f
            var px = ((rawX % width) + width) % width

            // Touch magnetic deflection
            if (currentTouch != null && touchWeight > 0.01f) {
                val dx = px - currentTouch.x
                val dy = py - currentTouch.y
                val distSq = dx * dx + dy * dy
                if (distSq < touchRadiusPx * touchRadiusPx && distSq > 0.1f) {
                    val dist = kotlin.math.sqrt(distSq)
                    val force = (1f - dist / touchRadiusPx) * touchWeight * (45f * p.speedY)
                    px += (dx / dist) * force
                    py += (dy / dist) * force
                }
            }

            // Twinkle alpha: crisp and visible in all 3 states
            val flicker = 0.82f + 0.18f * sin(t * 2.2f + p.phase)
            val stateAlphaMultiplier = if (isConnected) (0.65f + 0.35f * animatedEnergy) else 0.75f
            val normalAlpha = (p.baseAlpha * flicker * stateAlphaMultiplier * animatedFocusBoost).coerceIn(0.20f, 0.90f)

            particleCoords[i * 3] = px
            particleCoords[i * 3 + 1] = py
            particleCoords[i * 3 + 2] = normalAlpha
        }

        // Pass 2: Constellation Neural Mesh (Skipped when secondary tab / background blurred to save O(N^2) CPU overhead)
        if (!isConstellationPaused) {
            val particleCount = preparedParticles.size
            for (i in 0 until particleCount) {
                val p1x = particleCoords[i * 3]
                val p1y = particleCoords[i * 3 + 1]
                val p1Alpha = particleCoords[i * 3 + 2]
                if (p1Alpha < 0.10f) continue

                for (j in (i + 1) until particleCount) {
                    val p2x = particleCoords[j * 3]
                    val p2y = particleCoords[j * 3 + 1]
                    val p2Alpha = particleCoords[j * 3 + 2]
                    if (p2Alpha < 0.10f) continue

                    val dx = p1x - p2x
                    val dy = p1y - p2y
                    val distSq = dx * dx + dy * dy

                    if (distSq < maxConnDistSq) {
                        val dist = kotlin.math.sqrt(distSq)
                        val connAlpha = (1f - dist / maxConnDistPx) * 0.32f * kotlin.math.min(p1Alpha, p2Alpha)
                        if (connAlpha > 0.012f) {
                            drawLine(
                                color = particleColor.copy(alpha = connAlpha),
                                start = Offset(p1x, p1y),
                                end = Offset(p2x, p2y),
                                strokeWidth = connStrokeWidth
                            )
                        }
                    }
                }
            }
        }

        // Pass 3: Draw Core Micro-Particles, Comet Trails & Glow Auroras (Zero Allocation)
        for (i in preparedParticles.indices) {
            val p = preparedParticles[i]
            val px = particleCoords[i * 3]
            val py = particleCoords[i * 3 + 1]
            val alpha = particleCoords[i * 3 + 2]
            val pSizePx = p.sizePx

            // Comet trailing speed-tail for larger particles with tapered width and decaying alpha
            if (p.sizeDp > 1.25f) {
                val trailDeltaY = (baseSpeedY * 0.40f * p.speedY).coerceIn(-28f, -8f)
                val tailAlphaBase = alpha * (if (isConnected) 0.40f else 0.22f)
                val midY = py - trailDeltaY * 0.45f
                val endY = py - trailDeltaY

                drawLine(
                    color = particleColor.copy(alpha = tailAlphaBase * 0.85f),
                    start = Offset(px, py),
                    end = Offset(px, midY),
                    strokeWidth = pSizePx * 0.70f
                )
                drawLine(
                    color = particleColor.copy(alpha = tailAlphaBase * 0.25f),
                    start = Offset(px, midY),
                    end = Offset(px, endY),
                    strokeWidth = pSizePx * 0.45f
                )
            }

            // Core micro-particle dot
            drawCircle(
                color = particleColor.copy(alpha = alpha),
                radius = pSizePx / 2f,
                center = Offset(px, py)
            )

            // Soft micro-glow halo for active protocol particles
            if (isConnected && p.sizeDp > 1.20f) {
                val glowRadius = pSizePx * 1.45f
                val glowAlpha = alpha * 0.22f * animatedEnergy
                drawCircle(
                    color = particleColor.copy(alpha = glowAlpha),
                    radius = glowRadius,
                    center = Offset(px, py)
                )
            }
        }
    }
}

/**
 * Легковесный оверлей микро-частиц и созвездий для отображения поверх других экранов и диалогов.
 * Не перехватывает клики и жесты, автоматически адаптирует цвета под активный протокол (MTProto/SOCKS5).
 * Автоматически замораживает такты анимации (timeState), когда оверлей скрыт (alphaMultiplier <= 0f или isVisible = false).
 */
@Composable
fun CyberParticlesOverlay(
    modifier: Modifier = Modifier,
    particleCount: Int = 16,
    alphaMultiplier: Float = 0.85f,
    isVisible: Boolean = true,
    isPaused: Boolean = false
) {
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val isAnimationsDisabled by app.prefsManager.animationsDisabledFlow.collectAsState()
    val isSocks5 by app.prefsManager.isSocks5Flow.collectAsState()
    val isConnected = app.proxyServer.isRunning

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

    val shouldAnimate = isAppResumed && !isAnimationsDisabled && isVisible && !isPaused && alphaMultiplier > 0.001f

    val timeState = produceState(initialValue = 0L, shouldAnimate) {
        if (!shouldAnimate) return@produceState
        val startNano = System.nanoTime() - value
        while (shouldAnimate) {
            withFrameNanos { frameTimeNanos ->
                value = frameTimeNanos - startNano
            }
        }
    }

    val protoColors = rememberAnimatedProtocolColors(isSocks5 = isSocks5)
    val targetParticleColor = if (isConnected) protoColors.primary else Color(0xFFF1F5F9)
    val particleColor by animateColorAsState(
        targetValue = targetParticleColor,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "overlayParticleColor"
    )

    val particles = remember(particleCount) {
        val list = mutableListOf<MicroParticle>()
        val random = java.util.Random(4096)
        for (i in 0 until particleCount) {
            list.add(
                MicroParticle(
                    relX = random.nextFloat(),
                    relY = random.nextFloat(),
                    sizeDp = 0.75f + random.nextFloat() * 1.35f,
                    speedY = 0.25f + random.nextFloat() * 0.55f,
                    driftXAmp = 8f + random.nextFloat() * 18f,
                    freqX = 0.30f + random.nextFloat() * 0.65f,
                    phase = random.nextFloat() * 6.283f,
                    baseAlpha = 0.30f + random.nextFloat() * 0.45f
                )
            )
        }
        list
    }

    val preparedParticles = remember(density, particles) {
        particles.map { p ->
            PreparedParticle(
                relX = p.relX,
                relY = p.relY,
                sizePx = with(density) { p.sizeDp.dp.toPx() },
                sizeDp = p.sizeDp,
                speedY = p.speedY,
                driftXPx = with(density) { p.driftXAmp.dp.toPx() },
                freqX = p.freqX,
                phase = p.phase,
                baseAlpha = p.baseAlpha
            )
        }
    }

    val particleCoords = remember(particleCount) { FloatArray(particleCount * 3) }
    val maxConnDistPx = remember(density) { with(density) { 50.dp.toPx() } }
    val maxConnDistSq = remember(maxConnDistPx) { maxConnDistPx * maxConnDistPx }
    val connStrokeWidth = remember(density) { with(density) { 0.75.dp.toPx() } }
    val baseSpeedY = remember(density, isConnected) {
        with(density) { if (isConnected) -18.dp.toPx() else -5.dp.toPx() }
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f || !shouldAnimate) return@Canvas

        val t = timeState.value / 1_000_000_000f

        // Pass 1: Compute particle coordinates
        for (i in preparedParticles.indices) {
            val p = preparedParticles[i]
            val rawY = p.relY * height + t * baseSpeedY * p.speedY
            val py = ((rawY % height) + height) % height
            val rawX = p.relX * width + sin(t * p.freqX + p.phase) * p.driftXPx
            val px = ((rawX % width) + width) % width

            val flicker = 0.82f + 0.18f * sin(t * 2.0f + p.phase)
            val normalAlpha = (p.baseAlpha * flicker * alphaMultiplier).coerceIn(0.15f, 0.90f)

            particleCoords[i * 3] = px
            particleCoords[i * 3 + 1] = py
            particleCoords[i * 3 + 2] = normalAlpha
        }

        // Pass 2: Constellation mesh
        val n = preparedParticles.size
        for (i in 0 until n) {
            val p1x = particleCoords[i * 3]
            val p1y = particleCoords[i * 3 + 1]
            val p1Alpha = particleCoords[i * 3 + 2]
            if (p1Alpha < 0.10f) continue

            for (j in (i + 1) until n) {
                val p2x = particleCoords[j * 3]
                val p2y = particleCoords[j * 3 + 1]
                val p2Alpha = particleCoords[j * 3 + 2]
                if (p2Alpha < 0.10f) continue

                val dx = p1x - p2x
                val dy = p1y - p2y
                val distSq = dx * dx + dy * dy

                if (distSq < maxConnDistSq) {
                    val dist = kotlin.math.sqrt(distSq)
                    val connAlpha = (1f - dist / maxConnDistPx) * 0.28f * kotlin.math.min(p1Alpha, p2Alpha)
                    if (connAlpha > 0.01f) {
                        drawLine(
                            color = particleColor.copy(alpha = connAlpha),
                            start = Offset(p1x, p1y),
                            end = Offset(p2x, p2y),
                            strokeWidth = connStrokeWidth
                        )
                    }
                }
            }
        }

        // Pass 3: Core dots & subtle glow (Zero Allocation)
        for (i in preparedParticles.indices) {
            val p = preparedParticles[i]
            val px = particleCoords[i * 3]
            val py = particleCoords[i * 3 + 1]
            val alpha = particleCoords[i * 3 + 2]
            val pSizePx = p.sizePx

            // Comet trail with tapered segments
            if (p.sizeDp > 1.25f) {
                val trailDeltaY = (baseSpeedY * 0.35f * p.speedY).coerceIn(-22f, -6f)
                val tailAlphaBase = alpha * (if (isConnected) 0.35f else 0.20f)
                val midY = py - trailDeltaY * 0.45f
                val endY = py - trailDeltaY

                drawLine(
                    color = particleColor.copy(alpha = tailAlphaBase * 0.85f),
                    start = Offset(px, py),
                    end = Offset(px, midY),
                    strokeWidth = pSizePx * 0.65f
                )
                drawLine(
                    color = particleColor.copy(alpha = tailAlphaBase * 0.30f),
                    start = Offset(px, midY),
                    end = Offset(px, endY),
                    strokeWidth = pSizePx * 0.45f
                )
            }

            // Core micro-particle
            drawCircle(
                color = particleColor.copy(alpha = alpha),
                radius = pSizePx / 2f,
                center = Offset(px, py)
            )

            // Micro-glow
            if (isConnected && p.sizeDp > 1.20f) {
                drawCircle(
                    color = particleColor.copy(alpha = alpha * 0.20f),
                    radius = pSizePx * 1.35f,
                    center = Offset(px, py)
                )
            }
        }
    }
}

