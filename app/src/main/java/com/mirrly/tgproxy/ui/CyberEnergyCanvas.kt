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

@Composable
fun CyberEnergyCanvas(
    state: ProxyUiState,
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

    // ── GYROSCOPE / ACCELEROMETER TILT INTEGRATION (PAUSED IN BACKGROUND) ─
    var rawTiltX by remember { mutableFloatStateOf(0f) }
    var rawTiltY by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context, isAppResumed) {
        if (!isAppResumed) return@DisposableEffect onDispose {}

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
        sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    // ── ANIMATION FRAME TIMER (PAUSED IN BACKGROUND FOR 0% BATTERY DRAIN) ──
    val timeState = produceState(initialValue = 0L, isAppResumed) {
        if (!isAppResumed) return@produceState
        val startNano = System.nanoTime() - value
        while (isAppResumed) {
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

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val t = timeState.value / 1_000_000_000f

        val dp3Px = with(density) { 3.dp.toPx() }
        val tiltX = -rawTiltX * dp3Px
        val tiltY = (rawTiltY - 4.5f) * dp3Px

        // Balanced float speed for glowing spheres
        val timeScale = 0.75f + animatedEnergy * 0.50f

        // Pre-calculated layout pixel metrics to eliminate runtime Density recalculation
        val p120 = with(density) { 120.dp.toPx() }
        val p135 = with(density) { 135.dp.toPx() }
        val p175 = with(density) { 175.dp.toPx() }
        val p25 = with(density) { 25.dp.toPx() }

        val p110 = with(density) { 110.dp.toPx() }
        val p55 = with(density) { 55.dp.toPx() }
        val p150 = with(density) { 150.dp.toPx() }
        val p50 = with(density) { 50.dp.toPx() }
        val p195 = with(density) { 195.dp.toPx() }
        val p30 = with(density) { 30.dp.toPx() }

        val p130 = with(density) { 130.dp.toPx() }
        val p40 = with(density) { 40.dp.toPx() }
        val p45 = with(density) { 45.dp.toPx() }
        val p165 = with(density) { 165.dp.toPx() }
        val p22 = with(density) { 22.dp.toPx() }

        val p95 = with(density) { 95.dp.toPx() }
        val p125 = with(density) { 125.dp.toPx() }
        val p35 = with(density) { 35.dp.toPx() }
        val p185 = with(density) { 185.dp.toPx() }
        val p28 = with(density) { 28.dp.toPx() }

        // ── 1. GLOWING SPHERES (CYBER ORBS WITH SMOOTH GYRO PARALLAX) ───────

        // Orb 1: Upper-Right Organic Flow
        val orb1X = width * 0.65f + sin(t * 0.14f * timeScale) * p120 + cos(t * 0.22f * timeScale + 1.4f) * p45 - tiltX * 0.5f
        val orb1Y = height * 0.22f + cos(t * 0.11f * timeScale + 0.7f) * p135 + sin(t * 0.18f * timeScale) * p40 - tiltY * 0.5f
        val orb1Radius = p175 + sin(t * 0.15f) * p25

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    orb1CenterColor,
                    orb1CenterColor.copy(alpha = orb1CenterColor.alpha * 0.4f),
                    Color.Transparent
                ),
                center = Offset(orb1X, orb1Y),
                radius = orb1Radius
            ),
            center = Offset(orb1X, orb1Y),
            radius = orb1Radius
        )

        // Orb 2: Center-Left Organic Flow
        val orb2X = width * 0.25f + cos(t * 0.12f * timeScale + 2.1f) * p110 + sin(t * 0.19f * timeScale) * p55 - tiltX * 0.7f
        val orb2Y = height * 0.52f + sin(t * 0.15f * timeScale + 1.1f) * p150 + cos(t * 0.24f * timeScale + 0.3f) * p50 - tiltY * 0.7f
        val orb2Radius = p195 + cos(t * 0.13f + 1.5f) * p30

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    orb2CenterColor,
                    orb2CenterColor.copy(alpha = orb2CenterColor.alpha * 0.35f),
                    Color.Transparent
                ),
                center = Offset(orb2X, orb2Y),
                radius = orb2Radius
            ),
            center = Offset(orb2X, orb2Y),
            radius = orb2Radius
        )

        // Orb 3: Lower-Right Organic Flow
        val orb3X = width * 0.75f + sin(t * 0.10f * timeScale + 4.2f) * p130 + cos(t * 0.18f * timeScale + 2.5f) * p40 - tiltX * 0.4f
        val orb3Y = height * 0.78f + cos(t * 0.17f * timeScale + 2.8f) * p110 + sin(t * 0.25f * timeScale + 0.9f) * p45 - tiltY * 0.4f
        val orb3Radius = p165 + sin(t * 0.14f + 2.5f) * p22

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    orb3CenterColor,
                    orb3CenterColor.copy(alpha = orb3CenterColor.alpha * 0.3f),
                    Color.Transparent
                ),
                center = Offset(orb3X, orb3Y),
                radius = orb3Radius
            ),
            center = Offset(orb3X, orb3Y),
            radius = orb3Radius
        )

        // Orb 4: Deep Tech Accent Floating Mid-Top
        val orb4X = width * 0.45f + cos(t * 0.09f * timeScale + 0.8f) * p95 + sin(t * 0.16f * timeScale + 3.1f) * p50 - tiltX * 0.6f
        val orb4Y = height * 0.35f + sin(t * 0.13f * timeScale + 3.5f) * p125 + cos(t * 0.21f * timeScale) * p35 - tiltY * 0.6f
        val orb4Radius = p185 + sin(t * 0.11f + 0.5f) * p28

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    orb4CenterColor,
                    orb4CenterColor.copy(alpha = orb4CenterColor.alpha * 0.35f),
                    Color.Transparent
                ),
                center = Offset(orb4X, orb4Y),
                radius = orb4Radius
            ),
            center = Offset(orb4X, orb4Y),
            radius = orb4Radius
        )

        // ── 2. MICRO-PARTICLES WITH CLEAN SILKY SMOOTH GYRO TILT ─────────────
        val baseSpeedY = if (isConnected) -with(density) { 30.dp.toPx() } else with(density) { 8.dp.toPx() }

        for (p in particles) {
            val pSizePx = with(density) { p.sizeDp.dp.toPx() }
            val driftXPx = with(density) { p.driftXAmp.dp.toPx() }

            // Vertical position with smooth tilt shift
            val rawY = p.relY * height + t * baseSpeedY * p.speedY + tiltY * p.speedY * 1.2f
            val py = ((rawY % height) + height) % height

            // Horizontal position with smooth tilt shift
            val rawX = p.relX * width + sin(t * p.freqX + p.phase) * driftXPx + tiltX * p.speedY * 1.2f
            val px = ((rawX % width) + width) % width

            // Subtle twinkle alpha
            val flicker = 0.75f + 0.25f * sin(t * 2.8f + p.phase)
            val finalAlpha = (p.baseAlpha * flicker * (0.5f + 0.5f * animatedEnergy)).coerceIn(0.05f, 0.95f)

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
