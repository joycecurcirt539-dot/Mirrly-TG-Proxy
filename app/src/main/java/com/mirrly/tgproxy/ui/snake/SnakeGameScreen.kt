/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * Cyber Snake Screen: 120 FPS Hardware Particle Canvas & Rayon Neuroevolution AI
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mirrly.tgproxy.ui.snake

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.mirrly.tgproxy.ui.icon.MirrlyIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.HapticProfile
import com.mirrly.tgproxy.ui.MirrlyBottomSheetHeader
import com.mirrly.tgproxy.ui.MirrlyModalBottomSheet
import com.mirrly.tgproxy.ui.theme.AmoledBackground
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin
import kotlin.random.Random

private data class SnakeRenderFrame(
    val prevBody: List<Pair<Int, Int>>,
    val currBody: List<Pair<Int, Int>>,
    val moveProgress: Float,
    val heading: SnakeDir,
    val foodX: Int,
    val foodY: Int
)

enum class SnakeSpeed(val labelKey: Int, val intervalNanos: Long) {
    NORMAL(R.string.snake_settings_speed_norm, 125_000_000L),
    FAST(R.string.snake_settings_speed_fast, 80_000_000L),
    TURBO(R.string.snake_settings_speed_turbo, 45_000_000L)
}

@Composable
fun SnakeGameScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val protoColors = LocalProtocolColors.current
    val accentColor = protoColors.primary

    val controller = remember { SnakeController(gridWidth = 20, gridHeight = 30) }
    val particlePool = remember { ParticlePool(maxParticles = 3000) }
    val acousticEngine = remember { SnakeAcousticEngine(context) }
    val leaderboard = remember { SnakeLeaderboardManager(context) }
    val inputQueue = remember { SnakeInputQueue() }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var isAppResumed by remember {
        mutableStateOf<Boolean>(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            isAppResumed = event == androidx.lifecycle.Lifecycle.Event.ON_RESUME ||
                lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var isAiMode by remember { mutableStateOf(false) }
    var wallsKill by remember { mutableStateOf(false) }
    var showLeaderboard by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var score by remember { mutableIntStateOf(0) }
    var highScore by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(controller.getState().generation) }
    var isDead by remember { mutableStateOf(false) }

    // Game state & 120 FPS continuous interpolation atomic frame
    var gameState by remember { mutableStateOf(controller.getState()) }
    var renderFrame by remember {
        val initial = controller.getState()
        mutableStateOf(
            SnakeRenderFrame(
                prevBody = initial.bodyCoords,
                currBody = initial.bodyCoords,
                moveProgress = 1f,
                heading = SnakeDir.RIGHT,
                foodX = initial.foodX,
                foodY = initial.foodY
            )
        )
    }
    var stepAccumulatorNanos by remember { mutableLongStateOf(0L) }

    // Audio & Haptic
    var soundEnabled by remember { mutableStateOf(true) }
    var hapticEnabled by remember { mutableStateOf(true) }
    val isGlobalVibrationDisabled by MirrlyApplication.instance.prefsManager.vibrationDisabledFlow.collectAsState()
    val globalHapticProfile by MirrlyApplication.instance.prefsManager.hapticProfileFlow.collectAsState()
    val isHapticGloballyMuted = HapticProfile.isVibrationMuted(isGlobalVibrationDisabled, globalHapticProfile)
    val effectiveHapticEnabled = hapticEnabled && !isHapticGloballyMuted

    LaunchedEffect(effectiveHapticEnabled) {
        acousticEngine.isHapticEnabled = effectiveHapticEnabled
    }

    // Settings Parameters (Controlled by user)
    var selectedSpeed by remember { mutableStateOf(SnakeSpeed.NORMAL) }
    var particleLimit by remember { mutableIntStateOf(3000) }

    val isOverlayActive = showSettings || showLeaderboard || isDead
    val blurRadius by animateFloatAsState(
        targetValue = if (isOverlayActive) 14f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "overlayBlur"
    )

    LaunchedEffect(particleLimit) {
        particlePool.activeLimit = particleLimit
    }

    LaunchedEffect(wallsKill) {
        controller.wallsKill = wallsKill
    }

    DisposableEffect(Unit) {
        onDispose {
            acousticEngine.release()
            particlePool.clear()
        }
    }

    // Root Container - Transparent to honor MirrlyUI Custom Wallpaper rules
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Main Game UI Layer (Blurred smoothly when bottom sheets or overlays are active)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurRadius > 0.5f) Modifier.blur(blurRadius.dp) else Modifier)
        ) {
            // 1. Top Bar: Horizontally aligned title, back button, and precision icon buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Exit / Back Button
                IconButton(
                    onClick = {
                        acousticEngine.playTurn()
                        onBack()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    MirrlyIcon(
                        id = R.drawable.ic_arrow_left,
                        contentDescription = stringResource(R.string.action_back),
                        tint = TextWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Title
                Text(
                    text = stringResource(R.string.snake_title),
                    color = TextWhite,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.weight(1f)
                )

                // Action Icons (Crown for Rank, Volume for Sound, Phone for Vibrate, Gear for Settings)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rating / Leaderboard Icon Button
                    IconButton(
                        onClick = {
                            acousticEngine.playTurn()
                            showLeaderboard = !showLeaderboard
                            if (showLeaderboard) showSettings = false
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        MirrlyIcon(
                            id = R.drawable.ic_crown,
                            contentDescription = stringResource(R.string.snake_btn_rank),
                            tint = if (showLeaderboard) Color(0xFFFFB703) else accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Sound Toggle Icon Button
                    IconButton(
                        onClick = {
                            soundEnabled = !soundEnabled
                            acousticEngine.isSoundEnabled = soundEnabled
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        MirrlyIcon(
                            id = if (soundEnabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off,
                            contentDescription = stringResource(R.string.snake_btn_snd),
                            tint = if (soundEnabled) accentColor else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Haptic Toggle Icon Button
                    IconButton(
                        enabled = !isHapticGloballyMuted,
                        onClick = {
                            hapticEnabled = !hapticEnabled
                            acousticEngine.isHapticEnabled = hapticEnabled && !isHapticGloballyMuted
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        MirrlyIcon(
                            id = if (effectiveHapticEnabled) R.drawable.ic_vibrate else R.drawable.ic_vibrate_off,
                            contentDescription = stringResource(R.string.snake_btn_hap),
                            tint = if (effectiveHapticEnabled) accentColor else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Settings Icon Button
                    IconButton(
                        onClick = {
                            acousticEngine.playTurn()
                            showSettings = !showSettings
                            if (showSettings) showLeaderboard = false
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        MirrlyIcon(
                            id = R.drawable.ic_settings,
                            contentDescription = stringResource(R.string.snake_settings_title),
                            tint = if (showSettings) accentColor else TextWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // 2. HUD Info Card: Apples, High Score, AI Generation + Interactive Mode Pills
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.dp, AmoledBorder.copy(alpha = 0.8f)), RoundedCornerShape(14.dp))
                    .padding(vertical = 8.dp, horizontal = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Metrics: Apples, High Score, AI Generation
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column {
                            Text(
                                text = stringResource(R.string.snake_apples),
                                color = TextMuted,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text("$score", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Black)
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.snake_record),
                                color = TextMuted,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text("$highScore", color = accentColor, fontSize = 15.sp, fontWeight = FontWeight.Black)
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.snake_generation),
                                color = TextMuted,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "#$generation",
                                color = Color(0xFFFFB703),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    // Interactive Control Pills: Wall Mode + Play Mode
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Wall Mode Switcher Pill (ТОР vs СТЕНЫ)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        if (wallsKill) Color(0xFFFF1744).copy(alpha = 0.9f) else accentColor.copy(alpha = 0.6f)
                                    ),
                                    RoundedCornerShape(10.dp)
                                )
                                .background(if (wallsKill) Color(0xFFFF1744).copy(alpha = 0.10f) else accentColor.copy(alpha = 0.08f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    wallsKill = !wallsKill
                                    acousticEngine.playTurn()
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (wallsKill) stringResource(R.string.snake_btn_walls_kill) else stringResource(R.string.snake_btn_torus),
                                color = if (wallsKill) Color(0xFFFF1744) else accentColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Play Mode Switcher Pill (Gamepad vs AI Brain Icon)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    BorderStroke(1.dp, if (isAiMode) accentColor else AmoledBorder.copy(alpha = 0.8f)),
                                    RoundedCornerShape(10.dp)
                                )
                                .background(if (isAiMode) accentColor.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    isAiMode = !isAiMode
                                    acousticEngine.playTurn()
                                    if (isDead) {
                                        controller.reset()
                                        isDead = false
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            MirrlyIcon(
                                id = if (isAiMode) R.drawable.ic_ai_thought else R.drawable.ic_gamepad,
                                contentDescription = if (isAiMode) "AI Mode" else "Manual Mode",
                                tint = if (isAiMode) accentColor else TextWhite,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 3. Cybernetic Game Surface (Hardware Particle Canvas)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .detectSnakeSwipe { swipedDir ->
                        if (isAiMode) {
                            isAiMode = false
                        }
                        inputQueue.offer(swipedDir, renderFrame.heading)
                        acousticEngine.playTurn()
                    },
                contentAlignment = Alignment.Center
            ) {
                val boxWidthPx = constraints.maxWidth.toFloat()
                val boxHeightPx = constraints.maxHeight.toFloat()
                val padHPx = with(LocalDensity.current) { 8.dp.toPx() }
                val padVPx = with(LocalDensity.current) { 8.dp.toPx() }
                val availW = (boxWidthPx - padHPx * 2).coerceAtLeast(100f)
                val availH = (boxHeightPx - padVPx * 2).coerceAtLeast(100f)

                // Fixed aspect ratio & fixed 20x30 cells
                val cellDim = minOf(availW / 20f, availH / 30f)
                val gridPixelWidth = cellDim * 20f
                val gridPixelHeight = cellDim * 30f
                val offsetX = (boxWidthPx - gridPixelWidth) / 2f
                val offsetY = (boxHeightPx - gridPixelHeight) / 2f
                val arenaCornerRadius = with(LocalDensity.current) { 16.dp.toPx() }
                val cachedSegCoords = remember { FloatArray(600) }

                // The 120 FPS Continuous Render & Simulation Loop (Suspended when app is backgrounded/minimized)
                LaunchedEffect(isDead, isAiMode, selectedSpeed, wallsKill, isAppResumed) {
                    if (!isAppResumed) return@LaunchedEffect
                    var previousNanos = System.nanoTime()
                    var particleTick = 0

                    var localPrevBody = renderFrame.prevBody
                    var localCurrBody = renderFrame.currBody
                    var localHeading = renderFrame.heading
                    var localFoodX = renderFrame.foodX
                    var localFoodY = renderFrame.foodY

                    while (isActive && isAppResumed) {
                        withFrameNanos { frameTimeNanos ->
                            val dtNanos = frameTimeNanos - previousNanos
                            previousNanos = frameTimeNanos
                            val dtSeconds = (dtNanos / 1_000_000_000f).coerceIn(0.001f, 0.05f)

                            // 1. Update particle physics in tight primitive loop
                            particlePool.update(dtSeconds)

                            // 2. Quantum food gravitating ambient dust
                            val foodCenterPxX = offsetX + (localFoodX + 0.5f) * cellDim
                            val foodCenterPxY = offsetY + (localFoodY + 0.5f) * cellDim
                            particlePool.spawnFoodAmbient(foodCenterPxX, foodCenterPxY)

                            // 3. Perimeter sparks
                            particleTick++
                            if (particleTick % 3 == 0) {
                                if (wallsKill) {
                                    when (Random.nextInt(4)) {
                                        0 -> particlePool.spawnWallHazardSpark(offsetX + Random.nextFloat() * gridPixelWidth, offsetY, 0f, 1f)
                                        1 -> particlePool.spawnWallHazardSpark(offsetX + Random.nextFloat() * gridPixelWidth, offsetY + gridPixelHeight, 0f, -1f)
                                        2 -> particlePool.spawnWallHazardSpark(offsetX, offsetY + Random.nextFloat() * gridPixelHeight, 1f, 0f)
                                        3 -> particlePool.spawnWallHazardSpark(offsetX + gridPixelWidth, offsetY + Random.nextFloat() * gridPixelHeight, -1f, 0f)
                                    }
                                } else if (particleTick % 6 == 0) {
                                    val px = offsetX + Random.nextFloat() * gridPixelWidth
                                    val py = offsetY + Random.nextFloat() * gridPixelHeight
                                    particlePool.spawnTorusPortalSpark(px, py)
                                }
                            }

                            // 4. Advance simulation step & continuous interpolation
                            if (!isDead) {
                                stepAccumulatorNanos += dtNanos
                                val stepIntervalNanos = if (isAiMode) {
                                    (selectedSpeed.intervalNanos * 0.75f).toLong().coerceAtLeast(35_000_000L)
                                } else {
                                    selectedSpeed.intervalNanos
                                }

                                while (stepAccumulatorNanos >= stepIntervalNanos && !isDead) {
                                    stepAccumulatorNanos -= stepIntervalNanos

                                    val nextDir = inputQueue.poll() ?: localHeading
                                    val result = controller.step(nextDir, isAiMode)
                                    val updated = controller.getState()

                                    localPrevBody = localCurrBody
                                    localCurrBody = updated.bodyCoords
                                    localFoodX = updated.foodX
                                    localFoodY = updated.foodY
                                    gameState = updated
                                    score = updated.score
                                    generation = updated.generation
                                    if (updated.score > highScore) highScore = updated.score

                                    // Determine actual heading vector from move
                                    if (localCurrBody.isNotEmpty() && localPrevBody.isNotEmpty()) {
                                        val nh = localCurrBody.first()
                                        val oh = localPrevBody.first()
                                        var hdx = nh.first - oh.first
                                        var hdy = nh.second - oh.second
                                        val gw = 20
                                        val gh = 30
                                        if (hdx > gw / 2) hdx -= gw else if (hdx < -gw / 2) hdx += gw
                                        if (hdy > gh / 2) hdy -= gh else if (hdy < -gh / 2) hdy += gh
                                        if (hdx != 0 || hdy != 0) {
                                            localHeading = when {
                                                hdx > 0 -> SnakeDir.RIGHT
                                                hdx < 0 -> SnakeDir.LEFT
                                                hdy > 0 -> SnakeDir.DOWN
                                                else -> SnakeDir.UP
                                            }
                                        }
                                    }

                                    val headCenterPxX = offsetX + (updated.headX + 0.5f) * cellDim
                                    val headCenterPxY = offsetY + (updated.headY + 0.5f) * cellDim

                                    when (result) {
                                        1 -> {
                                            acousticEngine.playEat()
                                            particlePool.spawnEatBurst(headCenterPxX, headCenterPxY, 48)
                                        }
                                        2 -> {
                                            acousticEngine.playDie()
                                            particlePool.spawnDieBurst(headCenterPxX, headCenterPxY, 64)
                                            isDead = true
                                            leaderboard.recordScore(
                                                score = updated.score,
                                                mode = if (isAiMode) "AI" else "MANUAL",
                                                generation = updated.generation,
                                                fitness = updated.bestFitness
                                            )
                                        }
                                        else -> {
                                            val tail = updated.bodyCoords.lastOrNull()
                                            if (tail != null) {
                                                val tailPxX = offsetX + (tail.first + 0.5f) * cellDim
                                                val tailPxY = offsetY + (tail.second + 0.5f) * cellDim
                                                particlePool.spawnTailDissolve(tailPxX, tailPxY)
                                            }
                                        }
                                    }
                                }

                                val progress = if (isDead) 1f else (stepAccumulatorNanos.toFloat() / stepIntervalNanos).coerceIn(0f, 1f)

                                renderFrame = SnakeRenderFrame(
                                    prevBody = localPrevBody,
                                    currBody = localCurrBody,
                                    moveProgress = progress,
                                    heading = localHeading,
                                    foodX = localFoodX,
                                    foodY = localFoodY
                                )

                                // Spawn propulsion jet at the REAR of the head
                                if (!isDead && localCurrBody.isNotEmpty()) {
                                    val currHead = localCurrBody.first()
                                    val prevHead = localPrevBody.firstOrNull() ?: currHead
                                    val gw = 20f
                                    val gh = 30f
                                    var phX = prevHead.first.toFloat()
                                    var phY = prevHead.second.toFloat()
                                    val chX = currHead.first.toFloat()
                                    val chY = currHead.second.toFloat()

                                    var hdx = chX - phX
                                    var hdy = chY - phY
                                    if (!wallsKill) {
                                        if (hdx > gw / 2f) { phX += gw; hdx -= gw } else if (hdx < -gw / 2f) { phX -= gw; hdx += gw }
                                        if (hdy > gh / 2f) { phY += gh; hdy -= gh } else if (hdy < -gh / 2f) { phY -= gh; hdy += gh }
                                    }

                                    val interpHeadX = if (wallsKill) (phX + hdx * progress) else (phX + hdx * progress).mod(gw)
                                    val interpHeadY = if (wallsKill) (phY + hdy * progress) else (phY + hdy * progress).mod(gh)
                                    val headPxX = offsetX + (interpHeadX + 0.5f) * cellDim
                                    val headPxY = offsetY + (interpHeadY + 0.5f) * cellDim

                                    val headR = cellDim * 0.40f
                                    val dirX = localHeading.dx.toFloat()
                                    val dirY = localHeading.dy.toFloat()
                                    val rearX = headPxX - dirX * headR * 0.65f
                                    val rearY = headPxY - dirY * headR * 0.65f

                                    particlePool.spawnHeadJet(rearX, rearY, dirX, dirY)
                                }
                            }
                        }
                    }
                }

                // Zero-Lag 120 FPS Hardware Canvas Drawing Batch
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val left = offsetX
                    val top = offsetY
                    val right = offsetX + gridPixelWidth
                    val bottom = offsetY + gridPixelHeight

                    // A. High-Performance Cybernetic Grid Lines (Inside rounded arena bounds)
                    val guideColor = accentColor.copy(alpha = 0.035f)
                    for (gx in 4 until 20 step 4) {
                        val gxPx = left + gx * cellDim
                        drawLine(guideColor, Offset(gxPx, top + arenaCornerRadius * 0.4f), Offset(gxPx, bottom - arenaCornerRadius * 0.4f), strokeWidth = 1f)
                    }
                    for (gy in 4 until 30 step 4) {
                        val gyPx = top + gy * cellDim
                        drawLine(guideColor, Offset(left + arenaCornerRadius * 0.4f, gyPx), Offset(right - arenaCornerRadius * 0.4f, gyPx), strokeWidth = 1f)
                    }

                    // B. Arena Perimeter Frame: Deadly Laser Barrier vs Soft Torus Portal (Both with smooth rounded corners!)
                    if (wallsKill) {
                        // High-Voltage Electrified Laser Barrier with Rounded Corners
                        val pulse = ((sin(System.currentTimeMillis() / 160.0) + 1.0) * 0.5).toFloat()
                        val hazardColor = Color(0xFFFF1744)
                        val glowColor = Color(0xFFFF5252).copy(alpha = 0.24f + 0.16f * pulse)

                        // Outer radiant laser halo with rounded corners
                        drawRoundRect(
                            color = glowColor,
                            topLeft = Offset(left - 2.5.dp.toPx(), top - 2.5.dp.toPx()),
                            size = Size(gridPixelWidth + 5.dp.toPx(), gridPixelHeight + 5.dp.toPx()),
                            cornerRadius = CornerRadius(arenaCornerRadius + 2.5.dp.toPx(), arenaCornerRadius + 2.5.dp.toPx()),
                            style = Stroke(width = 4.5.dp.toPx()),
                            blendMode = BlendMode.Plus
                        )
                        // Core crisp laser line with rounded corners
                        drawRoundRect(
                            color = hazardColor.copy(alpha = 0.88f + 0.12f * pulse),
                            topLeft = Offset(left, top),
                            size = Size(gridPixelWidth, gridPixelHeight),
                            cornerRadius = CornerRadius(arenaCornerRadius, arenaCornerRadius),
                            style = Stroke(width = 1.6.dp.toPx())
                        )
                    } else {
                        // Soft Permeable Cyber Boundary with rounded corners
                        drawRoundRect(
                            color = accentColor.copy(alpha = 0.32f),
                            topLeft = Offset(left, top),
                            size = Size(gridPixelWidth, gridPixelHeight),
                            cornerRadius = CornerRadius(arenaCornerRadius, arenaCornerRadius),
                            style = Stroke(width = 1.2.dp.toPx())
                        )
                        // Outer subtle glow
                        drawRoundRect(
                            color = accentColor.copy(alpha = 0.08f),
                            topLeft = Offset(left - 2.dp.toPx(), top - 2.dp.toPx()),
                            size = Size(gridPixelWidth + 4.dp.toPx(), gridPixelHeight + 4.dp.toPx()),
                            cornerRadius = CornerRadius(arenaCornerRadius + 2.dp.toPx(), arenaCornerRadius + 2.dp.toPx()),
                            style = Stroke(width = 3.dp.toPx()),
                            blendMode = BlendMode.Plus
                        )
                    }

                    // C. Quantum Food Singularity (Core + pulsating accretion halo + photon center)
                    val foodPxX = left + (renderFrame.foodX + 0.5f) * cellDim
                    val foodPxY = top + (renderFrame.foodY + 0.5f) * cellDim
                    val pulse = ((sin(System.currentTimeMillis() / 140.0) + 1.0) * 0.5).toFloat()
                    val baseR = cellDim * 0.38f

                    // Accretion Disk Glow
                    drawCircle(
                        color = Color(0xFFFFB703).copy(alpha = 0.32f + 0.18f * pulse),
                        radius = baseR * 1.8f,
                        center = Offset(foodPxX, foodPxY),
                        blendMode = BlendMode.Plus
                    )
                    // Event Horizon Singularity
                    drawCircle(
                        color = Color(0xFFFFB703),
                        radius = baseR * (0.85f + 0.25f * pulse),
                        center = Offset(foodPxX, foodPxY),
                        blendMode = BlendMode.Plus
                    )
                    // Pure White Photon Core
                    drawCircle(
                        color = Color.White,
                        radius = baseR * 0.32f,
                        center = Offset(foodPxX, foodPxY),
                        blendMode = BlendMode.Plus
                    )

                    // D. 120 FPS Interpolated Snake Particle Stream (Laser linked segments)
                    val frame = renderFrame
                    val bodySize = frame.currBody.size.coerceAtMost(280)
                    val gw = 20f
                    val gh = 30f

                    // 1. Pre-calculate all segment screen coordinates from atomic frame
                    for (i in 0 until bodySize) {
                        val curr = frame.currBody[i]
                        val prev = if (i < frame.prevBody.size) frame.prevBody[i] else curr

                        var pX = prev.first.toFloat()
                        var pY = prev.second.toFloat()
                        val cX = curr.first.toFloat()
                        val cY = curr.second.toFloat()

                        if (!wallsKill) {
                            if (cX - pX > gw / 2f) pX += gw else if (pX - cX > gw / 2f) pX -= gw
                            if (cY - pY > gh / 2f) pY += gh else if (pY - cY > gh / 2f) pY -= gh
                        }

                        val interpX = if (wallsKill) (pX + (cX - pX) * frame.moveProgress) else (pX + (cX - pX) * frame.moveProgress).mod(gw)
                        val interpY = if (wallsKill) (pY + (cY - pY) * frame.moveProgress) else (pY + (cY - pY) * frame.moveProgress).mod(gh)

                        cachedSegCoords[i * 2] = left + (interpX + 0.5f) * cellDim
                        cachedSegCoords[i * 2 + 1] = top + (interpY + 0.5f) * cellDim
                    }

                    // 2. Draw continuous connecting plasma beams between segments
                    for (i in 1 until bodySize) {
                        val x1 = cachedSegCoords[(i - 1) * 2]
                        val y1 = cachedSegCoords[(i - 1) * 2 + 1]
                        val x2 = cachedSegCoords[i * 2]
                        val y2 = cachedSegCoords[i * 2 + 1]
                        val dx = x2 - x1
                        val dy = y2 - y1

                        // Avoid drawing wrap-around lines across the arena
                        if (dx * dx + dy * dy < cellDim * cellDim * 3.5f) {
                            val progress = 1.0f - (i.toFloat() / bodySize.coerceAtLeast(1))
                            drawLine(
                                color = accentColor.copy(alpha = 0.40f * progress),
                                start = Offset(x1, y1),
                                end = Offset(x2, y2),
                                strokeWidth = cellDim * 0.42f * (0.5f + 0.5f * progress),
                                cap = StrokeCap.Round,
                                blendMode = BlendMode.Plus
                            )
                        }
                    }

                    // 3. Draw body segment nodes from tail down to 1 (behind the head)
                    for (i in (bodySize - 1) downTo 1) {
                        val segX = cachedSegCoords[i * 2]
                        val segY = cachedSegCoords[i * 2 + 1]
                        val progress = 1.0f - (i.toFloat() / bodySize.coerceAtLeast(1))
                        val radius = cellDim * 0.33f * (0.5f + 0.5f * progress)

                        // Outer stardust halo
                        drawCircle(
                            color = accentColor.copy(alpha = 0.18f * progress),
                            radius = radius * 1.35f,
                            center = Offset(segX, segY),
                            blendMode = BlendMode.Plus
                        )
                        // Energetic plasma core
                        drawCircle(
                            color = accentColor.copy(alpha = 0.45f + 0.55f * progress),
                            radius = radius,
                            center = Offset(segX, segY),
                            blendMode = BlendMode.Plus
                        )
                        // Center photon spark
                        drawCircle(
                            color = Color.White.copy(alpha = 0.55f * progress),
                            radius = radius * 0.32f,
                            center = Offset(segX, segY),
                            blendMode = BlendMode.Plus
                        )
                    }

                    // 4. Draw HEAD on TOP (crisp, zero-flicker, perfectly aligned cyber visor)
                    if (bodySize > 0) {
                        val headX = cachedSegCoords[0]
                        val headY = cachedSegCoords[1]
                        val headR = cellDim * 0.42f

                        // Outer radiant halo
                        drawCircle(
                            color = accentColor.copy(alpha = 0.35f),
                            radius = headR * 1.45f,
                            center = Offset(headX, headY),
                            blendMode = BlendMode.Plus
                        )
                        // Vibrant theme core
                        drawCircle(
                            color = accentColor,
                            radius = headR,
                            center = Offset(headX, headY)
                        )
                        // Directional photon visor / eye shifted toward motion (crisp white, no blowout)
                        val visorOffsetX = frame.heading.dx * (headR * 0.30f)
                        val visorOffsetY = frame.heading.dy * (headR * 0.30f)
                        drawCircle(
                            color = Color.White.copy(alpha = 0.95f),
                            radius = headR * 0.28f,
                            center = Offset(headX + visorOffsetX, headY + visorOffsetY)
                        )
                    }

                    // E. Active Particle Pool (Zero-allocation batch draw with multi-color blending)
                    particlePool.drawBatch(this, accentColor)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        // =========================================================================
        // MirrlyModalBottomSheet Overlays (Zero-Popup Doctrine, AMOLED Glass Style)
        // =========================================================================

        // 1. Settings Sheet
        if (showSettings) {
            MirrlyModalBottomSheet(
                onDismissRequest = {
                    showSettings = false
                    acousticEngine.playTurn()
                },
                containerColor = AmoledBackground.copy(alpha = 0.92f),
                scrimColor = Color.Black.copy(alpha = 0.55f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MirrlyBottomSheetHeader(
                        title = stringResource(R.string.snake_settings_title),
                        subtitle = "GEN #$generation",
                        onClose = {
                            showSettings = false
                            acousticEngine.playTurn()
                        }
                    )

                    // 1. Arena Boundary Mode (Torus vs Deadly Walls)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, AmoledBorder.copy(alpha = 0.7f)), RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.snake_settings_arena_title),
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Torus Button
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        BorderStroke(1.dp, if (!wallsKill) accentColor else AmoledBorder),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .background(if (!wallsKill) accentColor.copy(alpha = 0.08f) else Color.Transparent)
                                    .clickable {
                                        wallsKill = false
                                        acousticEngine.playTurn()
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.snake_mode_torus),
                                    color = if (!wallsKill) accentColor else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            // Deadly Walls Button
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        BorderStroke(1.dp, if (wallsKill) Color(0xFFFF1744) else AmoledBorder),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .background(if (wallsKill) Color(0xFFFF1744).copy(alpha = 0.08f) else Color.Transparent)
                                    .clickable {
                                        wallsKill = true
                                        acousticEngine.playTurn()
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.snake_mode_walls_kill),
                                    color = if (wallsKill) Color(0xFFFF1744) else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // 2. Game Speed & Particles VFX
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, AmoledBorder.copy(alpha = 0.7f)), RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Speed
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.snake_settings_speed),
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SnakeSpeed.values().forEach { speed ->
                                    val isSelected = selectedSpeed == speed
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .border(
                                                BorderStroke(1.dp, if (isSelected) accentColor else AmoledBorder),
                                                RoundedCornerShape(10.dp)
                                            )
                                            .background(if (isSelected) accentColor.copy(alpha = 0.08f) else Color.Transparent)
                                            .clickable {
                                                selectedSpeed = speed
                                                acousticEngine.playTurn()
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(speed.labelKey),
                                            color = if (isSelected) accentColor else TextMuted,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }

                        // Particles Limit
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.snake_settings_particles),
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(3000 to "3000", 1200 to "1200", 400 to "400").forEach { (limit, title) ->
                                    val isSelected = particleLimit == limit
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .border(
                                                BorderStroke(1.dp, if (isSelected) accentColor else AmoledBorder),
                                                RoundedCornerShape(10.dp)
                                            )
                                            .background(if (isSelected) accentColor.copy(alpha = 0.08f) else Color.Transparent)
                                            .clickable {
                                                particleLimit = limit
                                                acousticEngine.playTurn()
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = title,
                                            color = if (isSelected) accentColor else TextMuted,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Done Button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(BorderStroke(1.dp, accentColor), RoundedCornerShape(14.dp))
                            .background(accentColor.copy(alpha = 0.08f))
                            .clickable {
                                showSettings = false
                                acousticEngine.playTurn()
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.snake_leaderboard_close),
                            color = accentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // 2. Leaderboard Sheet
        if (showLeaderboard) {
            val topScores = remember(showLeaderboard, isDead) { leaderboard.getTopScores(limit = 7) }
            MirrlyModalBottomSheet(
                onDismissRequest = {
                    showLeaderboard = false
                    acousticEngine.playTurn()
                },
                containerColor = AmoledBackground.copy(alpha = 0.92f),
                scrimColor = Color.Black.copy(alpha = 0.55f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MirrlyBottomSheetHeader(
                        title = stringResource(R.string.snake_leaderboard_title),
                        subtitle = stringResource(R.string.snake_leaderboard_status),
                        onClose = {
                            showLeaderboard = false
                            acousticEngine.playTurn()
                        }
                    )

                    if (topScores.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.snake_leaderboard_empty),
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            topScores.forEachIndexed { index, entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            BorderStroke(
                                                1.dp,
                                                if (index == 0) Color(0xFFFFB703).copy(alpha = 0.6f) else AmoledBorder.copy(alpha = 0.5f)
                                            ),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "#${index + 1}",
                                            color = if (index == 0) Color(0xFFFFB703) else TextMuted,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = entry.playerName,
                                            color = TextWhite,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "[${entry.mode}]",
                                            color = if (entry.mode == "AI") accentColor else TextMuted,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${entry.score} ${stringResource(R.string.snake_pts)}",
                                            color = if (index == 0) Color(0xFFFFB703) else accentColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = entry.formattedDate(),
                                            color = TextMuted,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(BorderStroke(1.dp, accentColor), RoundedCornerShape(14.dp))
                            .background(accentColor.copy(alpha = 0.08f))
                            .clickable {
                                showLeaderboard = false
                                acousticEngine.playTurn()
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.snake_leaderboard_close),
                            color = accentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // 3. Game Over & Next Generation Sheet
        if (isDead) {
            MirrlyModalBottomSheet(
                onDismissRequest = {
                    scope.launch(Dispatchers.Default) {
                        controller.reset()
                        val refreshed = controller.getState()
                        withContext(Dispatchers.Main) {
                            renderFrame = SnakeRenderFrame(
                                prevBody = refreshed.bodyCoords,
                                currBody = refreshed.bodyCoords,
                                moveProgress = 1f,
                                heading = SnakeDir.RIGHT,
                                foodX = refreshed.foodX,
                                foodY = refreshed.foodY
                            )
                            gameState = refreshed
                            stepAccumulatorNanos = 0L
                            isDead = false
                        }
                    }
                },
                containerColor = AmoledBackground.copy(alpha = 0.94f),
                scrimColor = Color.Black.copy(alpha = 0.55f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MirrlyBottomSheetHeader(
                        title = stringResource(R.string.snake_game_over),
                        subtitle = if (isAiMode) {
                            stringResource(R.string.snake_game_over_ai_desc, generation)
                        } else {
                            stringResource(R.string.snake_game_over_desc, score)
                        },
                        onClose = {
                            scope.launch(Dispatchers.Default) {
                                controller.reset()
                                val refreshed = controller.getState()
                                withContext(Dispatchers.Main) {
                                    renderFrame = SnakeRenderFrame(
                                        prevBody = refreshed.bodyCoords,
                                        currBody = refreshed.bodyCoords,
                                        moveProgress = 1f,
                                        heading = SnakeDir.RIGHT,
                                        foodX = refreshed.foodX,
                                        foodY = refreshed.foodY
                                    )
                                    gameState = refreshed
                                    stepAccumulatorNanos = 0L
                                    isDead = false
                                }
                            }
                        }
                    )

                    // Results Card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, Color(0xFFFF1744).copy(alpha = 0.5f)), RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.snake_apples),
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "$score",
                                color = TextWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.snake_record),
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "$highScore",
                                color = accentColor,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.snake_generation),
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "#$generation",
                                color = Color(0xFFFFB703),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Action Button (Evolve vs Retry)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(BorderStroke(1.dp, accentColor), RoundedCornerShape(14.dp))
                            .background(accentColor.copy(alpha = 0.12f))
                            .clickable {
                                scope.launch(Dispatchers.Default) {
                                    if (isAiMode) {
                                        controller.trainGeneration()
                                        val st = controller.getState()
                                        withContext(Dispatchers.Main) {
                                            generation = st.generation
                                        }
                                    }
                                    controller.reset()
                                    val refreshed = controller.getState()
                                    withContext(Dispatchers.Main) {
                                        renderFrame = SnakeRenderFrame(
                                            prevBody = refreshed.bodyCoords,
                                            currBody = refreshed.bodyCoords,
                                            moveProgress = 1f,
                                            heading = SnakeDir.RIGHT,
                                            foodX = refreshed.foodX,
                                            foodY = refreshed.foodY
                                        )
                                        gameState = refreshed
                                        stepAccumulatorNanos = 0L
                                        isDead = false
                                    }
                                }
                                acousticEngine.playTurn()
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isAiMode) stringResource(R.string.snake_btn_evolve) else stringResource(R.string.snake_btn_retry),
                            color = accentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
