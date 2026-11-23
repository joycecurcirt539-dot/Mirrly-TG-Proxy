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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.lightSweep
import com.mirrly.tgproxy.ui.theme.springPress
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private val ChampionshipGold = Color(0xFFFFD166)
private val WarmAmberGold = Color(0xFFFFB703)
private val StadiumCrimsonFlare = Color(0xFFFF4D8D)

/**
 * Snapshot-backed motion state read inside the central mascot's `graphicsLayer` block
 * so the 120 FPS jump and 360-degree spin cause zero Compose recompositions.
 */
@Stable
class RonMascotMotionState {
    var translationYPx by mutableFloatStateOf(0f)
    var scaleX by mutableFloatStateOf(1f)
    var scaleY by mutableFloatStateOf(1f)
    var rotationYDeg by mutableFloatStateOf(0f)
    var rotationZDeg by mutableFloatStateOf(0f)

    fun reset() {
        translationYPx = 0f
        scaleX = 1f
        scaleY = 1f
        rotationYDeg = 0f
        rotationZDeg = 0f
    }
}

/**
 * Compact MirrlyUI outline button labeled "ron" placed alongside the protocol switcher.
 * Deliberately triggers no vibration and no sound on tap.
 */
@Composable
fun RonCelebrationButton(
    isCelebrating: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    onOriginPositioned: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor by animateColorAsState(
        targetValue = if (isCelebrating) ChampionshipGold else accentColor.copy(alpha = 0.45f),
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "ronBtnBorder"
    )
    val labelColor by animateColorAsState(
        targetValue = if (isCelebrating) ChampionshipGold else TextWhite.copy(alpha = 0.88f),
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "ronBtnText"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(31.dp)
            .widthIn(min = 46.dp)
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                val sz = coords.size
                onOriginPositioned(Offset(pos.x + sz.width * 0.5f, pos.y + sz.height * 0.5f))
            }
            .clip(shape)
            .background(if (isCelebrating) ChampionshipGold.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                width = if (isCelebrating) 1.3.dp else 1.dp,
                color = borderColor,
                shape = shape
            )
            .lightSweep(
                isEnabled = isCelebrating,
                shape = shape,
                borderWidth = 1.3.dp,
                sweepColor = ChampionshipGold
            )
            .springPress(
                onClick = onClick,
                pressScale = 0.92f
            )
            .padding(horizontal = 11.dp)
    ) {
        Text(
            text = "ron",
            color = labelColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.35.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Zero-allocation flat FloatArray particle pool for 120 FPS celebration bursts.
 *
 * Stride = 11 floats per particle:
 * [0: x, 1: y, 2: vx, 3: vy, 4: life, 5: maxLife, 6: size, 7: shapeKind, 8: angleDeg, 9: angularVelDeg, 10: colorIndex]
 */
internal class RonParticlePool(private val maxParticles: Int = 340) {
    private val data = FloatArray(maxParticles * STRIDE)
    var activeCount: Int = 0
        private set

    fun clear() {
        activeCount = 0
    }

    fun spawn(
        x: Float,
        y: Float,
        vx: Float,
        vy: Float,
        maxLife: Float,
        size: Float,
        shapeKind: Float,
        angleDeg: Float,
        angularVelDeg: Float,
        colorIndex: Float
    ) {
        if (activeCount >= maxParticles) return
        val idx = activeCount * STRIDE
        data[idx + 0] = x
        data[idx + 1] = y
        data[idx + 2] = vx
        data[idx + 3] = vy
        data[idx + 4] = maxLife
        data[idx + 5] = maxLife
        data[idx + 6] = size
        data[idx + 7] = shapeKind
        data[idx + 8] = angleDeg
        data[idx + 9] = angularVelDeg
        data[idx + 10] = colorIndex
        activeCount++
    }

    fun update(dt: Float, gravityPx: Float) {
        val clampedDt = dt.coerceIn(0.001f, 0.05f)
        val dragFactor = (1f - 2.0f * clampedDt).coerceIn(0.75f, 0.995f)
        var i = 0
        while (i < activeCount) {
            val idx = i * STRIDE
            val remaining = data[idx + 4] - clampedDt
            if (remaining <= 0f) {
                activeCount--
                if (i < activeCount) {
                    System.arraycopy(data, activeCount * STRIDE, data, idx, STRIDE)
                }
                continue
            }
            data[idx + 4] = remaining
            data[idx + 0] += data[idx + 2] * clampedDt
            data[idx + 1] += data[idx + 3] * clampedDt
            data[idx + 2] *= dragFactor
            data[idx + 3] = data[idx + 3] * dragFactor + gravityPx * clampedDt
            data[idx + 8] += data[idx + 9] * clampedDt
            i++
        }
    }

    fun draw(
        scope: DrawScope,
        primaryColor: Color,
        lightColor: Color,
        starPath: Path,
        diamondPath: Path
    ) {
        for (i in 0 until activeCount) {
            val idx = i * STRIDE
            val x = data[idx + 0]
            val y = data[idx + 1]
            val vx = data[idx + 2]
            val vy = data[idx + 3]
            val lifeFrac = (data[idx + 4] / data[idx + 5]).coerceIn(0f, 1f)
            val baseSize = data[idx + 6]
            val shapeKind = data[idx + 7].toInt()
            val angleDeg = data[idx + 8]
            val colorIndex = data[idx + 10].toInt()

            val baseColor = when (colorIndex) {
                0 -> primaryColor
                1 -> lightColor
                2 -> ChampionshipGold
                3 -> Color.White
                else -> StadiumCrimsonFlare
            }
            val alpha = (lifeFrac * lifeFrac * 1.15f).coerceIn(0f, 1f)
            val center = Offset(x, y)

            when (shapeKind) {
                SHAPE_ORB -> {
                    val r = baseSize * (0.45f + 0.55f * lifeFrac)
                    scope.drawCircle(
                        color = baseColor.copy(alpha = alpha * 0.28f),
                        radius = r * 2.2f,
                        center = center,
                        blendMode = BlendMode.Plus
                    )
                    scope.drawCircle(
                        color = baseColor.copy(alpha = alpha * 0.92f),
                        radius = r,
                        center = center,
                        blendMode = BlendMode.Plus
                    )
                }

                SHAPE_STAR -> {
                    val r = baseSize * (0.5f + 0.5f * lifeFrac)
                    val innerR = r * 0.32f
                    starPath.reset()
                    starPath.moveTo(x, y - r)
                    starPath.lineTo(x + innerR, y - innerR)
                    starPath.lineTo(x + r, y)
                    starPath.lineTo(x + innerR, y + innerR)
                    starPath.lineTo(x, y + r)
                    starPath.lineTo(x - innerR, y + innerR)
                    starPath.lineTo(x - r, y)
                    starPath.lineTo(x - innerR, y - innerR)
                    starPath.close()

                    scope.rotate(degrees = angleDeg, pivot = center) {
                        scope.drawPath(
                            path = starPath,
                            color = baseColor.copy(alpha = alpha * 0.88f),
                            blendMode = BlendMode.Plus
                        )
                    }
                }

                SHAPE_DIAMOND -> {
                    val w = baseSize * 0.9f
                    val tumble = cos(angleDeg * 0.045f)
                    val h = (baseSize * 1.35f * tumble).let { if (kotlin.math.abs(it) < 1.2f) 1.2f else it }
                    diamondPath.reset()
                    diamondPath.moveTo(x, y - h)
                    diamondPath.lineTo(x + w, y)
                    diamondPath.lineTo(x, y + h)
                    diamondPath.lineTo(x - w, y)
                    diamondPath.close()

                    scope.rotate(degrees = angleDeg, pivot = center) {
                        scope.drawPath(
                            path = diamondPath,
                            color = baseColor.copy(alpha = alpha * 0.85f)
                        )
                    }
                }

                else -> {
                    val tailScale = 0.028f * lifeFrac
                    scope.drawLine(
                        color = baseColor.copy(alpha = alpha * 0.95f),
                        start = center,
                        end = Offset(x - vx * tailScale, y - vy * tailScale),
                        strokeWidth = baseSize * 0.55f,
                        cap = StrokeCap.Round,
                        blendMode = BlendMode.Plus
                    )
                }
            }
        }
    }

    companion object {
        private const val STRIDE = 11
        const val SHAPE_ORB = 0
        const val SHAPE_STAR = 1
        const val SHAPE_DIAMOND = 2
        const val SHAPE_STREAK = 3
    }
}

/**
 * Full-screen non-blocking "Siiiiiiiiii" celebration overlay synchronized with the central mascot leap.
 * Zero popups, zero sound, zero vibration, zero draw-loop allocations, and Lifecycle battery guard.
 */
@Composable
fun RonCelebrationOverlay(
    triggerNonce: Int,
    ronOriginInRoot: Offset?,
    mascotCenterInRoot: Offset?,
    mascotSizePx: Float,
    topBarBottomInRoot: Float,
    mascotMotion: RonMascotMotionState,
    onCelebrationActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (triggerNonce <= 0) return

    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> isAppResumed = false
                Lifecycle.Event.ON_RESUME,
                Lifecycle.Event.ON_START -> isAppResumed = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mascotMotion.reset()
        }
    }

    val app = MirrlyApplication.instance
    val isAnimationsDisabled by app.prefsManager.animationsDisabledFlow.collectAsState()
    val protoColors = LocalProtocolColors.current
    val density = LocalDensity.current

    var rootTopLeft by remember { mutableStateOf(Offset.Zero) }
    var elapsedSec by remember { mutableFloatStateOf(0f) }
    var isRunning by remember { mutableStateOf(false) }

    val pool = remember { RonParticlePool(maxParticles = 340) }
    val starPath = remember { Path() }
    val diamondPath = remember { Path() }
    val boltPath = remember { Path() }
    val crownPath = remember { Path() }

    val jumpHeightPx = remember(density) { with(density) { 46.dp.toPx() } }
    val crouchOffsetPx = remember(density) { with(density) { 7.dp.toPx() } }
    val gravityPx = remember(density) { with(density) { 520.dp.toPx() } }
    val minSparkSpeedPx = remember(density) { with(density) { 180.dp.toPx() } }
    val maxSparkSpeedPx = remember(density) { with(density) { 640.dp.toPx() } }
    val fountainSpeedPx = remember(density) { with(density) { 560.dp.toPx() } }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                rootTopLeft = coords.positionInRoot()
            }
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        val targetCenter = remember(mascotCenterInRoot, rootTopLeft, widthPx, heightPx) {
            if (mascotCenterInRoot != null) {
                mascotCenterInRoot - rootTopLeft
            } else {
                Offset(widthPx * 0.5f, heightPx * 0.45f)
            }
        }
        val originPoint = remember(ronOriginInRoot, rootTopLeft, widthPx, heightPx) {
            if (ronOriginInRoot != null) {
                ronOriginInRoot - rootTopLeft
            } else {
                Offset(widthPx * 0.86f, heightPx * 0.14f)
            }
        }

        // Static fallback when system/app animations are disabled
        LaunchedEffect(triggerNonce, isAnimationsDisabled) {
            if (isAnimationsDisabled) {
                pool.clear()
                mascotMotion.reset()
                isRunning = true
                onCelebrationActiveChange(true)
                elapsedSec = IMPACT_TIME_SEC + 0.15f
                delay(1400L)
                isRunning = false
                onCelebrationActiveChange(false)
            }
        }

        // Full 120 FPS choreography loop
        LaunchedEffect(triggerNonce, isAppResumed, isAnimationsDisabled) {
            if (isAnimationsDisabled || !isAppResumed) {
                if (!isAppResumed) {
                    mascotMotion.reset()
                }
                return@LaunchedEffect
            }

            isRunning = true
            onCelebrationActiveChange(true)
            elapsedSec = 0f

            var impactSpawned = false
            var flankSpawned = false
            var lastTrailSpawnSec = -1f
            var lastNano = System.nanoTime()

            try {
                while (isAppResumed && !isAnimationsDisabled) {
                    var frameDone = false
                    withFrameNanos { frameNano ->
                        val dt = ((frameNano - lastNano) / 1_000_000_000f).coerceIn(0.001f, 0.04f)
                        lastNano = frameNano
                        elapsedSec += dt

                        // Update central mascot leap & 360-degree spin without recomposition
                        updateMascotMotion(
                            t = elapsedSec,
                            jumpHeightPx = jumpHeightPx,
                            crouchOffsetPx = crouchOffsetPx,
                            motion = mascotMotion
                        )

                        // Phase 1: Bolt trail from "ron" button down to mascot
                        if (elapsedSec <= WINDUP_END_SEC && elapsedSec - lastTrailSpawnSec >= 0.016f) {
                            lastTrailSpawnSec = elapsedSec
                            val p = (elapsedSec / WINDUP_END_SEC).coerceIn(0f, 1f)
                            val ctrl = Offset(
                                x = (originPoint.x + targetCenter.x) * 0.5f + widthPx * 0.12f,
                                y = (originPoint.y + targetCenter.y) * 0.5f - heightPx * 0.05f
                            )
                            val bx = quadBezier(originPoint.x, ctrl.x, targetCenter.x, p)
                            val by = quadBezier(originPoint.y, ctrl.y, targetCenter.y, p)
                            repeat(3) {
                                val jitterAngle = Random.nextFloat() * 2f * PI.toFloat()
                                val jitterSpeed = minSparkSpeedPx * (0.15f + Random.nextFloat() * 0.35f)
                                pool.spawn(
                                    x = bx,
                                    y = by,
                                    vx = cos(jitterAngle) * jitterSpeed,
                                    vy = sin(jitterAngle) * jitterSpeed,
                                    maxLife = 0.35f + Random.nextFloat() * 0.25f,
                                    size = with(density) { (2.5f + Random.nextFloat() * 2.5f).dp.toPx() },
                                    shapeKind = if (Random.nextBoolean()) RonParticlePool.SHAPE_ORB.toFloat() else RonParticlePool.SHAPE_STAR.toFloat(),
                                    angleDeg = Random.nextFloat() * 360f,
                                    angularVelDeg = (Random.nextFloat() - 0.5f) * 320f,
                                    colorIndex = if (Random.nextFloat() < 0.6f) 2f else 0f
                                )
                            }
                        }

                        // Phase 2: Spiral ascent sparks during the 360 spin
                        if (elapsedSec in WINDUP_END_SEC..IMPACT_TIME_SEC && elapsedSec - lastTrailSpawnSec >= 0.02f) {
                            lastTrailSpawnSec = elapsedSec
                            val leapP = ((elapsedSec - WINDUP_END_SEC) / (IMPACT_TIME_SEC - WINDUP_END_SEC)).coerceIn(0f, 1f)
                            val spinRad = leapP * 4f * PI.toFloat()
                            val orbitR = (mascotSizePx * 0.35f).coerceAtLeast(with(density) { 48.dp.toPx() })
                            val sx = targetCenter.x + cos(spinRad) * orbitR
                            val sy = targetCenter.y + mascotMotion.translationYPx + sin(spinRad) * orbitR * 0.35f
                            pool.spawn(
                                x = sx,
                                y = sy,
                                vx = cos(spinRad + 1.4f) * minSparkSpeedPx * 0.45f,
                                vy = with(density) { 40.dp.toPx() },
                                maxLife = 0.40f + Random.nextFloat() * 0.25f,
                                size = with(density) { (3.0f + Random.nextFloat() * 2.5f).dp.toPx() },
                                shapeKind = RonParticlePool.SHAPE_STAR.toFloat(),
                                angleDeg = Random.nextFloat() * 360f,
                                angularVelDeg = 280f,
                                colorIndex = if (Random.nextBoolean()) 2f else 1f
                            )
                        }

                        // Phase 3: Landing impact burst ("Siiiiiiiiii")
                        if (!impactSpawned && elapsedSec >= IMPACT_TIME_SEC) {
                            impactSpawned = true
                            spawnImpactBurst(
                                pool = pool,
                                center = targetCenter,
                                minSpeedPx = minSparkSpeedPx,
                                maxSpeedPx = maxSparkSpeedPx,
                                dpToPx = density.density
                            )
                        }

                        // Phase 4: Dual stadium flank fountains
                        if (!flankSpawned && elapsedSec >= FLANK_BURST_SEC) {
                            flankSpawned = true
                            val leftOrigin = Offset(widthPx * 0.16f, targetCenter.y + heightPx * 0.14f)
                            val rightOrigin = Offset(widthPx * 0.84f, targetCenter.y + heightPx * 0.14f)
                            spawnFlankFountain(pool, leftOrigin, aimRight = true, fountainSpeedPx, density.density)
                            spawnFlankFountain(pool, rightOrigin, aimRight = false, fountainSpeedPx, density.density)
                        }

                        pool.update(dt, gravityPx)

                        if (elapsedSec >= TOTAL_DURATION_SEC && pool.activeCount == 0) {
                            frameDone = true
                        }
                    }
                    if (frameDone) break
                }
            } finally {
                mascotMotion.reset()
                pool.clear()
                isRunning = false
                onCelebrationActiveChange(false)
            }
        }

        if (!isRunning) return@BoxWithConstraints

        val strokeThinPx = remember(density) { with(density) { 1.4.dp.toPx() } }
        val strokeRingPx = remember(density) { with(density) { 2.0.dp.toPx() } }
        val boltStrokePx = remember(density) { with(density) { 2.6.dp.toPx() } }
        val maxRingRadius = remember(widthPx, heightPx) { maxOf(widthPx, heightPx) * 0.62f }

        val bannerFade = when {
            elapsedSec < IMPACT_TIME_SEC -> 0f
            elapsedSec < IMPACT_TIME_SEC + 0.12f -> (elapsedSec - IMPACT_TIME_SEC) / 0.12f
            elapsedSec > TOTAL_DURATION_SEC - 0.50f -> ((TOTAL_DURATION_SEC - elapsedSec) / 0.50f).coerceIn(0f, 1f)
            else -> 1f
        }

        val bannerPunchProgress = ((elapsedSec - IMPACT_TIME_SEC) / 0.28f).coerceIn(0f, 1f)
        val bannerScale = if (elapsedSec < IMPACT_TIME_SEC) {
            0.35f
        } else {
            // Fast spring-like overshoot 0.4 -> 1.15 -> 1.0
            val overshoot = sin(bannerPunchProgress * PI.toFloat()) * (1f - bannerPunchProgress) * 0.34f
            0.42f + 0.58f * FastOutSlowInEasing.transform(bannerPunchProgress) + overshoot
        }
        val bannerTiltDeg = if (elapsedSec < IMPACT_TIME_SEC) {
            -6f
        } else {
            -6f * (1f - FastOutSlowInEasing.transform(bannerPunchProgress))
        }
        val bannerLetterSpacingSp = if (elapsedSec < IMPACT_TIME_SEC) {
            0.4f
        } else {
            1.0f + 2.8f * FastOutSlowInEasing.transform(bannerPunchProgress)
        }

        // Compute non-overlapping placement for the "Siiiiiiiiii" banner above the mascot
        val localTopBarBottom = (topBarBottomInRoot - rootTopLeft.y).coerceAtLeast(0f)
        val mascotHalfSize = if (mascotSizePx > 0f) mascotSizePx * 0.5f else with(density) { 88.dp.toPx() }
        val mascotTopY = targetCenter.y - mascotHalfSize
        val upperSlotRoom = mascotTopY - localTopBarBottom
        val gapMargin = with(density) { 36.dp.toPx() }
        val minUpperBannerY = localTopBarBottom + with(density) { 42.dp.toPx() }
        val bannerCenterY = if (upperSlotRoom >= with(density) { 76.dp.toPx() }) {
            ((localTopBarBottom + mascotTopY) * 0.54f).coerceAtLeast(minUpperBannerY)
        } else {
            (targetCenter.y + mascotHalfSize + gapMargin).coerceAtMost(heightPx * 0.78f)
        }

        val cachedTextBrush = remember(protoColors.primary, protoColors.light) {
            Brush.horizontalGradient(
                colors = listOf(
                    Color.White,
                    ChampionshipGold,
                    protoColors.light,
                    Color.White
                )
            )
        }

        // 1. Hardware 120 FPS VFX Canvas (Shockwaves, Rays, Bolt, Particles)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = elapsedSec

            // Bolt from "ron" button into mascot
            if (t in 0f..WINDUP_END_SEC + 0.08f) {
                val p = (t / WINDUP_END_SEC).coerceIn(0f, 1f)
                val boltAlpha = if (t <= WINDUP_END_SEC) 0.95f else (1f - (t - WINDUP_END_SEC) / 0.08f).coerceIn(0f, 1f)
                val ctrl = Offset(
                    x = (originPoint.x + targetCenter.x) * 0.5f + widthPx * 0.12f,
                    y = (originPoint.y + targetCenter.y) * 0.5f - heightPx * 0.05f
                )
                boltPath.reset()
                boltPath.moveTo(originPoint.x, originPoint.y)
                val steps = 16
                val maxStep = (steps * p).toInt().coerceAtLeast(1)
                for (s in 1..maxStep) {
                    val f = s.toFloat() / steps
                    boltPath.lineTo(
                        quadBezier(originPoint.x, ctrl.x, targetCenter.x, f),
                        quadBezier(originPoint.y, ctrl.y, targetCenter.y, f)
                    )
                }
                drawPath(
                    path = boltPath,
                    color = ChampionshipGold.copy(alpha = boltAlpha * 0.35f),
                    style = Stroke(width = boltStrokePx * 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    blendMode = BlendMode.Plus
                )
                drawPath(
                    path = boltPath,
                    color = Color.White.copy(alpha = boltAlpha * 0.95f),
                    style = Stroke(width = boltStrokePx * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    blendMode = BlendMode.Plus
                )
            }

            // 3 Staggered Expanding Shockwave Rings on Impact
            drawShockwaveRing(
                t = t,
                startSec = IMPACT_TIME_SEC,
                durationSec = 0.72f,
                center = targetCenter,
                maxRadius = maxRingRadius,
                strokePx = strokeRingPx,
                color = ChampionshipGold
            )
            drawShockwaveRing(
                t = t,
                startSec = IMPACT_TIME_SEC + 0.09f,
                durationSec = 0.78f,
                center = targetCenter,
                maxRadius = maxRingRadius * 0.92f,
                strokePx = strokeThinPx,
                color = protoColors.primary
            )
            drawShockwaveRing(
                t = t,
                startSec = IMPACT_TIME_SEC + 0.18f,
                durationSec = 0.84f,
                center = targetCenter,
                maxRadius = maxRingRadius * 0.82f,
                strokePx = strokeThinPx,
                color = Color.White
            )

            // 12 Radial Stadium Beams fanning out smoothly on impact
            if (t >= IMPACT_TIME_SEC && t <= IMPACT_TIME_SEC + 1.05f) {
                val beamP = ((t - IMPACT_TIME_SEC) / 1.05f).coerceIn(0f, 1f)
                val beamAlpha = (sin(beamP * PI.toFloat()) * (1f - beamP * 0.35f) * 0.42f).coerceIn(0f, 0.45f)
                val innerR = mascotHalfSize * 0.55f
                val outerR = mascotHalfSize * (1.25f + 1.55f * beamP)
                val spinAngle = beamP * 28f
                val beamCount = 12
                for (b in 0 until beamCount) {
                    val rad = Math.toRadians((b * (360f / beamCount) + spinAngle).toDouble()).toFloat()
                    val cosA = cos(rad)
                    val sinA = sin(rad)
                    val rayColor = if (b % 2 == 0) ChampionshipGold else protoColors.primary
                    drawLine(
                        color = rayColor.copy(alpha = beamAlpha),
                        start = Offset(targetCenter.x + cosA * innerR, targetCenter.y + sinA * innerR),
                        end = Offset(targetCenter.x + cosA * outerR, targetCenter.y + sinA * outerR),
                        strokeWidth = strokeThinPx * (1.5f - 0.8f * beamP),
                        cap = StrokeCap.Round,
                        blendMode = BlendMode.Plus
                    )
                }
            }

            // Central additive flash halo behind "Siiiiiiiiii"
            if (bannerFade > 0.01f) {
                val haloCenter = Offset(widthPx * 0.5f, bannerCenterY)
                drawCircle(
                    color = ChampionshipGold.copy(alpha = 0.14f * bannerFade),
                    radius = widthPx * 0.24f * bannerScale.coerceAtMost(1.15f),
                    center = haloCenter,
                    blendMode = BlendMode.Plus
                )
                drawCircle(
                    color = protoColors.primary.copy(alpha = 0.11f * bannerFade),
                    radius = widthPx * 0.32f * bannerScale.coerceAtMost(1.15f),
                    center = haloCenter,
                    blendMode = BlendMode.Plus
                )

                // Procedural outline crown glyph hovering cleanly above "Siiiiiiiiii"
                val crownCenterY = bannerCenterY - with(density) { 27.dp.toPx() } * bannerScale
                val crownW = with(density) { 15.dp.toPx() } * bannerScale
                val crownH = with(density) { 10.dp.toPx() } * bannerScale
                val cx = widthPx * 0.5f
                crownPath.reset()
                crownPath.moveTo(cx - crownW, crownCenterY + crownH * 0.55f)
                crownPath.lineTo(cx - crownW * 1.15f, crownCenterY - crownH * 0.55f)
                crownPath.lineTo(cx - crownW * 0.42f, crownCenterY + crownH * 0.05f)
                crownPath.lineTo(cx, crownCenterY - crownH * 0.90f)
                crownPath.lineTo(cx + crownW * 0.42f, crownCenterY + crownH * 0.05f)
                crownPath.lineTo(cx + crownW * 1.15f, crownCenterY - crownH * 0.55f)
                crownPath.lineTo(cx + crownW, crownCenterY + crownH * 0.55f)
                crownPath.close()

                rotate(degrees = bannerTiltDeg, pivot = Offset(cx, crownCenterY)) {
                    drawPath(
                        path = crownPath,
                        color = ChampionshipGold.copy(alpha = 0.95f * bannerFade),
                        style = Stroke(width = strokeThinPx * 1.15f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            // Active Zero-Allocation Particle Pool
            pool.draw(
                scope = this,
                primaryColor = protoColors.primary,
                lightColor = protoColors.light,
                starPath = starPath,
                diamondPath = diamondPath
            )
        }

        // 2. Kinetic "Siiiiiiiiii" Typography Banner
        if (bannerFade > 0.01f) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset {
                            IntOffset(
                                x = 0,
                                y = (bannerCenterY - with(density) { 22.dp.toPx() }).roundToInt()
                            )
                        }
                        .graphicsLayer {
                            alpha = bannerFade
                            scaleX = bannerScale
                            scaleY = bannerScale
                            rotationZ = bannerTiltDeg
                        }
                ) {
                    Text(
                        text = "Siiiiiiiiii",
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        style = TextStyle(
                            brush = cachedTextBrush,
                            fontSize = 31.sp,
                            fontWeight = FontWeight.Black,
                            fontStyle = FontStyle.Italic,
                            letterSpacing = bannerLetterSpacingSp.sp,
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.85f),
                                offset = Offset(0f, 4f),
                                blurRadius = 14f
                            )
                        )
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawShockwaveRing(
    t: Float,
    startSec: Float,
    durationSec: Float,
    center: Offset,
    maxRadius: Float,
    strokePx: Float,
    color: Color
) {
    if (t < startSec || t > startSec + durationSec) return
    val p = ((t - startSec) / durationSec).coerceIn(0f, 1f)
    val eased = FastOutSlowInEasing.transform(p)
    val radius = (maxRadius * (0.08f + 0.92f * eased)).coerceAtLeast(1f)
    val alpha = ((1f - p) * (1f - p) * 0.88f).coerceIn(0f, 1f)
    drawCircle(
        color = color.copy(alpha = alpha),
        radius = radius,
        center = center,
        style = Stroke(width = (strokePx * (1.25f - 0.85f * p)).coerceAtLeast(0.8f)),
        blendMode = BlendMode.Plus
    )
}

private fun updateMascotMotion(
    t: Float,
    jumpHeightPx: Float,
    crouchOffsetPx: Float,
    motion: RonMascotMotionState
) {
    when {
        t < WINDUP_END_SEC -> {
            val p = (t / WINDUP_END_SEC).coerceIn(0f, 1f)
            val s = sin(p * PI.toFloat() * 0.5f)
            motion.translationYPx = crouchOffsetPx * s
            motion.scaleX = 1f + 0.08f * s
            motion.scaleY = 1f - 0.11f * s
            motion.rotationYDeg = 0f
            motion.rotationZDeg = 0f
        }

        t < IMPACT_TIME_SEC -> {
            val p = ((t - WINDUP_END_SEC) / (IMPACT_TIME_SEC - WINDUP_END_SEC)).coerceIn(0f, 1f)
            val arc = sin(p * PI.toFloat())
            val spinEase = FastOutSlowInEasing.transform(p)
            motion.translationYPx = -jumpHeightPx * arc
            motion.scaleX = 1f + 0.16f * arc
            motion.scaleY = 1f + 0.16f * arc
            motion.rotationYDeg = 360f * spinEase
            motion.rotationZDeg = -7f * arc
        }

        t < SETTLE_END_SEC -> {
            val p = ((t - IMPACT_TIME_SEC) / (SETTLE_END_SEC - IMPACT_TIME_SEC)).coerceIn(0f, 1f)
            val impactWave = sin(p * PI.toFloat()) * (1f - p)
            motion.translationYPx = crouchOffsetPx * 0.65f * impactWave
            motion.scaleX = 1f + 0.14f * impactWave
            motion.scaleY = 1f - 0.10f * impactWave
            motion.rotationYDeg = 0f
            motion.rotationZDeg = 0f
        }

        else -> {
            motion.reset()
        }
    }
}

private fun spawnImpactBurst(
    pool: RonParticlePool,
    center: Offset,
    minSpeedPx: Float,
    maxSpeedPx: Float,
    dpToPx: Float
) {
    val count = 165
    for (i in 0 until count) {
        val baseAngle = (i.toFloat() / count) * 2f * PI.toFloat()
        val jitter = (Random.nextFloat() - 0.5f) * 0.22f
        val angle = baseAngle + jitter
        val speed = minSpeedPx + Random.nextFloat() * (maxSpeedPx - minSpeedPx)
        val kindRoll = Random.nextFloat()
        val shapeKind = when {
            kindRoll < 0.34f -> RonParticlePool.SHAPE_ORB
            kindRoll < 0.64f -> RonParticlePool.SHAPE_STAR
            kindRoll < 0.84f -> RonParticlePool.SHAPE_STREAK
            else -> RonParticlePool.SHAPE_DIAMOND
        }
        val colorRoll = Random.nextFloat()
        val colorIndex = when {
            colorRoll < 0.34f -> 2f // Championship gold
            colorRoll < 0.60f -> 0f // Protocol primary
            colorRoll < 0.78f -> 1f // Protocol light
            colorRoll < 0.92f -> 3f // Photon white
            else -> 4f              // RANALDU crimson flare
        }
        val sizeDp = when (shapeKind) {
            RonParticlePool.SHAPE_STAR -> 4.5f + Random.nextFloat() * 4.5f
            RonParticlePool.SHAPE_DIAMOND -> 3.0f + Random.nextFloat() * 3.0f
            else -> 2.4f + Random.nextFloat() * 3.2f
        }
        pool.spawn(
            x = center.x,
            y = center.y,
            vx = cos(angle) * speed,
            vy = sin(angle) * speed - minSpeedPx * 0.25f,
            maxLife = 0.85f + Random.nextFloat() * 1.10f,
            size = sizeDp * dpToPx,
            shapeKind = shapeKind.toFloat(),
            angleDeg = Random.nextFloat() * 360f,
            angularVelDeg = (Random.nextFloat() - 0.5f) * 420f,
            colorIndex = colorIndex
        )
    }
}

private fun spawnFlankFountain(
    pool: RonParticlePool,
    origin: Offset,
    aimRight: Boolean,
    baseSpeedPx: Float,
    dpToPx: Float
) {
    val count = 42
    val centerAngleDeg = if (aimRight) -64f else -116f
    for (i in 0 until count) {
        val angleDeg = centerAngleDeg + (Random.nextFloat() - 0.5f) * 34f
        val angleRad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val speed = baseSpeedPx * (0.72f + Random.nextFloat() * 0.58f)
        val kindRoll = Random.nextFloat()
        val shapeKind = when {
            kindRoll < 0.40f -> RonParticlePool.SHAPE_STAR
            kindRoll < 0.75f -> RonParticlePool.SHAPE_DIAMOND
            else -> RonParticlePool.SHAPE_ORB
        }
        val colorIndex = when (i % 4) {
            0 -> 2f
            1 -> 0f
            2 -> 3f
            else -> 1f
        }
        pool.spawn(
            x = origin.x,
            y = origin.y,
            vx = cos(angleRad) * speed,
            vy = sin(angleRad) * speed,
            maxLife = 1.05f + Random.nextFloat() * 0.85f,
            size = (3.4f + Random.nextFloat() * 3.6f) * dpToPx,
            shapeKind = shapeKind.toFloat(),
            angleDeg = Random.nextFloat() * 360f,
            angularVelDeg = (Random.nextFloat() - 0.5f) * 380f,
            colorIndex = colorIndex
        )
    }
}

private fun quadBezier(p0: Float, p1: Float, p2: Float, t: Float): Float {
    val inv = 1f - t
    return inv * inv * p0 + 2f * inv * t * p1 + t * t * p2
}

private const val WINDUP_END_SEC = 0.14f
private const val IMPACT_TIME_SEC = 0.44f
private const val SETTLE_END_SEC = 0.68f
private const val FLANK_BURST_SEC = 0.76f
private const val TOTAL_DURATION_SEC = 2.85f
