package com.mirrly.tgproxy.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mirrly.tgproxy.ui.theme.ActiveGreenLed
import kotlin.math.cos
import kotlin.math.sin

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

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
    isUiHidden: Boolean = false,
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

    // ── GYROSCOPE / ACCELEROMETER TILT INTEGRATION (PAUSED IN BACKGROUND OR IF ANIMATIONS DISABLED) ─
    var rawTiltX by remember { mutableFloatStateOf(0f) }
    var rawTiltY by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context, isAppResumed, isAnimationsDisabled) {
        if (!isAppResumed || isAnimationsDisabled) return@DisposableEffect onDispose {}

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null && event.values.size >= 2) {
                    val ax = event.values[0]
                    val ay = event.values[1]

                    // Smooth low-pass filter for device tilt (-10..10)
                    rawTiltX = rawTiltX * 0.92f + ax * 0.08f
                    rawTiltY = rawTiltY * 0.92f + ay * 0.08f
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    // ── ANIMATION FRAME TIMER (PAUSED IN BACKGROUND OR IF ANIMATIONS DISABLED) ──
    val timeState = produceState(initialValue = 0L, isAppResumed, isAnimationsDisabled) {
        if (!isAppResumed || isAnimationsDisabled) return@produceState
        val startNano = System.nanoTime() - value
        while (isAppResumed && !isAnimationsDisabled) {
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
        animationSpec = tween(durationMillis = 1000, easing = LinearOutSlowInEasing),
        label = "energyTransition"
    )

    // Animated colors for glowing spheres
    val orb1CenterColor by animateColorAsState(
        targetValue = if (isConnected) Color(0xFF00F5D4).copy(alpha = 0.30f) else Color(0xFF1E2838).copy(alpha = 0.34f),
        animationSpec = tween(800), label = "orb1Center"
    )
    val orb2CenterColor by animateColorAsState(
        targetValue = if (isConnected) Color(0xFF00FF87).copy(alpha = 0.26f) else Color(0xFF141C26).copy(alpha = 0.28f),
        animationSpec = tween(800), label = "orb2Center"
    )
    val orb3CenterColor by animateColorAsState(
        targetValue = if (isConnected) Color(0xFF00E676).copy(alpha = 0.22f) else Color(0xFF0F1620).copy(alpha = 0.24f),
        animationSpec = tween(800), label = "orb3Center"
    )
    val orb4CenterColor by animateColorAsState(
        targetValue = if (isConnected) Color(0xFF00B4D8).copy(alpha = 0.20f) else Color(0xFF161E2C).copy(alpha = 0.26f),
        animationSpec = tween(800), label = "orb4Center"
    )

    val particleColor by animateColorAsState(
        targetValue = if (isConnected) ActiveGreenLed else Color(0xFF4A5568),
        animationSpec = tween(800), label = "particleColor"
    )

    // Dynamic focus boost when UI is hidden
    val focusTarget = if (isUiHidden) 1.35f else 1.0f
    val animatedFocusBoost by animateFloatAsState(
        targetValue = focusTarget,
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "focusBoost"
    )

    // Generate 45 fixed micro-particles with deterministic seed
    val particles = remember {
        val list = mutableListOf<MicroParticle>()
        val random = java.util.Random(2026)
        for (i in 0 until 45) {
            list.add(
                MicroParticle(
                    relX = random.nextFloat(),
                    relY = random.nextFloat(),
                    sizeDp = 1.2f + random.nextFloat() * 2.8f,
                    speedY = 0.35f + random.nextFloat() * 0.75f,
                    driftXAmp = 14f + random.nextFloat() * 32f,
                    freqX = 0.4f + random.nextFloat() * 0.9f,
                    phase = random.nextFloat() * 6.283f,
                    baseAlpha = 0.25f + random.nextFloat() * 0.55f
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

    // Precalculate layout pixel metrics once per density change
    val metrics = remember(density) {
        with(density) {
            CanvasMetrics(
                dp3Px = 3.dp.toPx(),
                p120 = 120.dp.toPx(),
                p135 = 135.dp.toPx(),
                p175 = 175.dp.toPx(),
                p25 = 25.dp.toPx(),
                p110 = 110.dp.toPx(),
                p55 = 55.dp.toPx(),
                p150 = 150.dp.toPx(),
                p50 = 50.dp.toPx(),
                p195 = 195.dp.toPx(),
                p30 = 30.dp.toPx(),
                p130 = 130.dp.toPx(),
                p40 = 40.dp.toPx(),
                p45 = 45.dp.toPx(),
                p165 = 165.dp.toPx(),
                p22 = 22.dp.toPx(),
                p95 = 95.dp.toPx(),
                p125 = 125.dp.toPx(),
                p35 = 35.dp.toPx(),
                p185 = 185.dp.toPx(),
                p28 = 28.dp.toPx(),
                speedYConn = -30.dp.toPx(),
                speedYDisconn = 8.dp.toPx()
            )
        }
    }

    // Cache Orb gradient color lists to prevent per-frame List allocation
    val orb1Colors = remember(orb1CenterColor) {
        listOf(
            orb1CenterColor,
            orb1CenterColor.copy(alpha = orb1CenterColor.alpha * 0.4f),
            Color.Transparent
        )
    }
    val orb2Colors = remember(orb2CenterColor) {
        listOf(
            orb2CenterColor,
            orb2CenterColor.copy(alpha = orb2CenterColor.alpha * 0.35f),
            Color.Transparent
        )
    }
    val orb3Colors = remember(orb3CenterColor) {
        listOf(
            orb3CenterColor,
            orb3CenterColor.copy(alpha = orb3CenterColor.alpha * 0.3f),
            Color.Transparent
        )
    }
    val orb4Colors = remember(orb4CenterColor) {
        listOf(
            orb4CenterColor,
            orb4CenterColor.copy(alpha = orb4CenterColor.alpha * 0.35f),
            Color.Transparent
        )
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        if (isAnimationsDisabled) {
            drawRect(color = Color.Black)
            return@Canvas
        }

        val t = timeState.value / 1_000_000_000f

        val tiltX = -rawTiltX * metrics.dp3Px
        val tiltY = (rawTiltY - 4.5f) * metrics.dp3Px

        // Balanced float speed for glowing spheres
        val timeScale = 0.75f + animatedEnergy * 0.50f

        // ── 1. GLOWING SPHERES (CYBER ORBS WITH SMOOTH GYRO PARALLAX) ───────

        // Orb 1: Upper-Right Organic Flow
        val orb1X = width * 0.65f + sin(t * 0.14f * timeScale) * metrics.p120 + cos(t * 0.22f * timeScale + 1.4f) * metrics.p45 - tiltX * 0.5f
        val orb1Y = height * 0.22f + cos(t * 0.11f * timeScale + 0.7f) * metrics.p135 + sin(t * 0.18f * timeScale) * metrics.p40 - tiltY * 0.5f
        val orb1Radius = metrics.p175 + sin(t * 0.15f) * metrics.p25

        drawCircle(
            brush = Brush.radialGradient(
                colors = orb1Colors,
                center = Offset(orb1X, orb1Y),
                radius = orb1Radius
            ),
            center = Offset(orb1X, orb1Y),
            radius = orb1Radius
        )

        // Orb 2: Center-Left Organic Flow
        val orb2X = width * 0.25f + cos(t * 0.12f * timeScale + 2.1f) * metrics.p110 + sin(t * 0.19f * timeScale) * metrics.p55 - tiltX * 0.7f
        val orb2Y = height * 0.52f + sin(t * 0.15f * timeScale + 1.1f) * metrics.p150 + cos(t * 0.24f * timeScale + 0.3f) * metrics.p50 - tiltY * 0.7f
        val orb2Radius = metrics.p195 + cos(t * 0.13f + 1.5f) * metrics.p30

        drawCircle(
            brush = Brush.radialGradient(
                colors = orb2Colors,
                center = Offset(orb2X, orb2Y),
                radius = orb2Radius
            ),
            center = Offset(orb2X, orb2Y),
            radius = orb2Radius
        )

        // Orb 3: Lower-Right Organic Flow
        val orb3X = width * 0.75f + sin(t * 0.10f * timeScale + 4.2f) * metrics.p130 + cos(t * 0.18f * timeScale + 2.5f) * metrics.p40 - tiltX * 0.4f
        val orb3Y = height * 0.78f + cos(t * 0.17f * timeScale + 2.8f) * metrics.p110 + sin(t * 0.25f * timeScale + 0.9f) * metrics.p45 - tiltY * 0.4f
        val orb3Radius = metrics.p165 + sin(t * 0.14f + 2.5f) * metrics.p22

        drawCircle(
            brush = Brush.radialGradient(
                colors = orb3Colors,
                center = Offset(orb3X, orb3Y),
                radius = orb3Radius
            ),
            center = Offset(orb3X, orb3Y),
            radius = orb3Radius
        )

        // Orb 4: Deep Tech Accent Floating Mid-Top
        val orb4X = width * 0.45f + cos(t * 0.09f * timeScale + 0.8f) * metrics.p95 + sin(t * 0.16f * timeScale + 3.1f) * metrics.p50 - tiltX * 0.6f
        val orb4Y = height * 0.35f + sin(t * 0.13f * timeScale + 3.5f) * metrics.p125 + cos(t * 0.21f * timeScale) * metrics.p35 - tiltY * 0.6f
        val orb4Radius = metrics.p185 + sin(t * 0.11f + 0.5f) * metrics.p28

        drawCircle(
            brush = Brush.radialGradient(
                colors = orb4Colors,
                center = Offset(orb4X, orb4Y),
                radius = orb4Radius
            ),
            center = Offset(orb4X, orb4Y),
            radius = orb4Radius
        )

        // ── 2. MICRO-PARTICLES WITH CLEAN SILKY SMOOTH GYRO TILT ─────────────
        val baseSpeedY = if (isConnected) metrics.speedYConn else metrics.speedYDisconn

        for (p in preparedParticles) {
            val pSizePx = p.sizePx
            val driftXPx = p.driftXPx

            // Vertical position with smooth tilt shift
            val rawY = p.relY * height + t * baseSpeedY * p.speedY + tiltY * p.speedY * 1.2f
            val py = ((rawY % height) + height) % height

            // Horizontal position with smooth tilt shift
            val rawX = p.relX * width + sin(t * p.freqX + p.phase) * driftXPx + tiltX * p.speedY * 1.2f
            val px = ((rawX % width) + width) % width

            // Subtle twinkle alpha
            val flicker = 0.75f + 0.25f * sin(t * 2.8f + p.phase)
            val finalAlpha = (p.baseAlpha * flicker * (0.5f + 0.5f * animatedEnergy) * animatedFocusBoost).coerceIn(0.05f, 0.98f)

            // Core particle dot
            drawCircle(
                color = particleColor.copy(alpha = finalAlpha),
                radius = pSizePx / 2f,
                center = Offset(px, py)
            )

            // Soft energy glow around active particles
            if (animatedEnergy > 0.1f && p.sizeDp > 2.0f) {
                drawCircle(
                    color = particleColor.copy(alpha = finalAlpha * 0.35f * animatedEnergy),
                    radius = pSizePx * 1.9f,
                    center = Offset(px, py)
                )
            }
        }
    }
}

