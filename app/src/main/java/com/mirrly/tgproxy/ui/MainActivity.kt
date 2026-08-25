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

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.graphics.RenderEffect
import android.graphics.Shader
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.ui.theme.MirrlyTheme
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.springPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * High-performance RenderEffect blur cache to prevent GPU shader pipeline churn.
 */
private class RenderEffectBlurCache(private val density: Float) {
    private var lastQuantizedPx = -1
    private var cachedEffect: androidx.compose.ui.graphics.RenderEffect? = null
    var fullBlurEffect: androidx.compose.ui.graphics.RenderEffect? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val fullPx = 16f * density
                fullBlurEffect = RenderEffect.createBlurEffect(
                    fullPx,
                    fullPx,
                    Shader.TileMode.CLAMP
                ).asComposeRenderEffect()
            } catch (_: Throwable) {}
        }
    }

    fun getBlurEffect(progress: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || progress <= 0.02f) return null
        if (progress >= 0.98f) return fullBlurEffect

        val radiusPx = progress * 16f * density
        val quantizedPx = (radiusPx / 2f).roundToInt() * 2
        if (quantizedPx <= 1) return null
        if (quantizedPx == lastQuantizedPx && cachedEffect != null) {
            return cachedEffect
        }
        lastQuantizedPx = quantizedPx
        return try {
            val effect = RenderEffect.createBlurEffect(
                quantizedPx.toFloat(),
                quantizedPx.toFloat(),
                Shader.TileMode.CLAMP
            ).asComposeRenderEffect()
            cachedEffect = effect
            effect
        } catch (_: Throwable) {
            null
        }
    }
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock screen orientation to Portrait only
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Enable Edge-to-Edge edge transparent status bar drawing
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Force hardware acceleration at window level to eliminate OpenGLRenderer swap errors
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        optimizeForHighRefreshRate()

        setContent {
            MirrlyTheme {
                val app = MirrlyApplication.instance
                val server = app.proxyServer
                var isProxyRunning by remember { mutableStateOf(server.isRunning) }

                val signatureStatus = remember {
                    com.mirrly.tgproxy.util.SignatureVerifier.verify(applicationContext)
                }
                var showUnofficialDialog by remember {
                    mutableStateOf(signatureStatus == com.mirrly.tgproxy.util.SignatureStatus.UNOFFICIAL_MODIFIED)
                }

                val shouldShowStarDialog = remember {
                    com.mirrly.tgproxy.service.LaunchCountManager.onAppLaunched(applicationContext)
                    com.mirrly.tgproxy.service.LaunchCountManager.shouldShowStarDialog(applicationContext)
                }
                var showGithubStarDialog by remember { mutableStateOf(shouldShowStarDialog && !showUnofficialDialog) }

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        com.mirrly.tgproxy.service.UpdateManager.checkForUpdates(applicationContext, notifyIfFound = true)
                    }
                }

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        while (isActive) {
                            val running = server.isRunning
                            withContext(Dispatchers.Main) {
                                isProxyRunning = running
                            }
                            delay(500)
                        }
                    }
                }

                val switchPhase by com.mirrly.tgproxy.service.ProtocolSwitchManager.switchPhase.collectAsState()

                val globalProxyState = when (switchPhase) {
                    com.mirrly.tgproxy.service.SwitchPhase.DISCONNECTING -> ProxyUiState.DISCONNECTING
                    com.mirrly.tgproxy.service.SwitchPhase.PAUSE_DARK -> ProxyUiState.DISCONNECTED
                    com.mirrly.tgproxy.service.SwitchPhase.RECONNECTING -> ProxyUiState.CONNECTING
                    com.mirrly.tgproxy.service.SwitchPhase.IDLE -> if (isProxyRunning || server.isRunning) ProxyUiState.CONNECTED else ProxyUiState.DISCONNECTED
                }

                val screenStack = remember { mutableStateListOf("home") }
                val currentScreen = screenStack.lastOrNull() ?: "home"
                var lastBackTime by remember { mutableLongStateOf(0L) }
                val scope = rememberCoroutineScope()

                var workerManagerSection by remember { mutableStateOf(ManagerSection.WORKERS) }
                val workerManagerOpenProgress = remember { Animatable(0f) }
                val isWorkerManager = currentScreen == "worker_manager"

                var isWmVisible by remember { mutableStateOf(false) }

                // Synchronize workerManagerOpenProgress with current screen
                LaunchedEffect(currentScreen) {
                    if (isWorkerManager) {
                        isWmVisible = true
                        if (workerManagerOpenProgress.value < 0.99f) {
                            workerManagerOpenProgress.animateTo(
                                1f,
                                spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                            )
                        }
                    } else if (currentScreen == "home") {
                        if (workerManagerOpenProgress.value > 0.01f) {
                            workerManagerOpenProgress.animateTo(
                                0f,
                                spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                            )
                        }
                        isWmVisible = false
                    } else {
                        isWmVisible = false
                    }
                }

                // Stable navigation action callbacks
                val navigateTo: (String) -> Unit = remember {
                    { screen ->
                        if (screen == "worker_guide") {
                            workerManagerSection = ManagerSection.GUIDE
                            if (screenStack.lastOrNull() != "worker_manager") {
                                screenStack.add("worker_manager")
                            }
                            isWmVisible = true
                            scope.launch {
                                workerManagerOpenProgress.animateTo(
                                    1f,
                                    spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        } else if (screen == "worker_manager") {
                            workerManagerSection = ManagerSection.WORKERS
                            if (screenStack.lastOrNull() != "worker_manager") {
                                screenStack.add("worker_manager")
                            }
                            isWmVisible = true
                            scope.launch {
                                workerManagerOpenProgress.animateTo(
                                    1f,
                                    spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        } else {
                            if (screenStack.lastOrNull() != screen) {
                                screenStack.add(screen)
                            }
                        }
                    }
                }

                var lastNavigateBackTime by remember { mutableLongStateOf(0L) }

                val navigateBack: () -> Unit = remember {
                    {
                        val now = System.currentTimeMillis()
                        if (now - lastNavigateBackTime >= 240) {
                            lastNavigateBackTime = now

                            if (screenStack.size > 1) {
                                val topScreen = screenStack.removeAt(screenStack.size - 1)
                                if (topScreen == "worker_manager") {
                                    scope.launch {
                                        workerManagerOpenProgress.animateTo(
                                            0f,
                                            spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                                        )
                                        if (screenStack.lastOrNull() != "worker_manager") {
                                            isWmVisible = false
                                        }
                                    }
                                }
                            } else if (workerManagerOpenProgress.value > 0.01f) {
                                screenStack.removeAll { it == "worker_manager" }
                                scope.launch {
                                    workerManagerOpenProgress.animateTo(
                                        0f,
                                        spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                                    )
                                    isWmVisible = false
                                }
                            } else {
                                if (now - lastBackTime < 2000) {
                                    finish()
                                } else {
                                    lastBackTime = now
                                    Toast.makeText(this@MainActivity, "Нажмите еще раз для выхода", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }

                BackHandler {
                    navigateBack()
                }

                var isUiHidden by remember { mutableStateOf(false) }
                val currentUpdateInfo by com.mirrly.tgproxy.service.UpdateManager.updateState.collectAsState()
                var globalTouchPoint by remember { mutableStateOf<Offset?>(null) }

                // Stable callbacks for child screens (Enables Smart Recomposition Skipping)
                val onOpenSettings = remember { { navigateTo("settings") } }
                val onOpenLogs = remember { { navigateTo("logs") } }
                val onOpenHistory = remember { { navigateTo("history") } }
                val onOpenUpdate = remember { { navigateTo("update") } }
                val onOpenWorkerGuide = remember { { navigateTo("worker_guide") } }
                val onOpenWorkerManager = remember { { navigateTo("worker_manager") } }
                val onOpenAbout = remember { { navigateTo("about") } }
                val onOpenLicense = remember { { navigateTo("license") } }
                val onOpenTerms = remember { { navigateTo("terms") } }
                val onNavigateBack = remember { { navigateBack() } }
                val onUiHiddenChange: (Boolean) -> Unit = remember { { hidden -> isUiHidden = hidden } }

                val density = LocalDensity.current.density
                val blurCache = remember(density) { RenderEffectBlurCache(density) }

                // Active screens tracking for smooth transition without recomposition storms
                var activeScreens by remember { mutableStateOf(setOf("home")) }

                LaunchedEffect(currentScreen, screenStack.toList()) {
                    val newSet = activeScreens.toMutableSet()
                    newSet.addAll(screenStack)
                    newSet.add(currentScreen)
                    activeScreens = newSet

                    // Keep screens alive for transition duration (420ms) then prune inactive ones
                    delay(420)

                    val targetSet = screenStack.toSet() + currentScreen + "home"
                    activeScreens = targetSet
                }

                val standardBackdropAlpha = animateFloatAsState(
                    targetValue = if (currentScreen == "home") 0f else 0.48f,
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                    label = "backdropAlpha"
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull()
                                    if (change != null) {
                                        globalTouchPoint = if (change.pressed) change.position else null
                                    }
                                }
                            }
                        }
                ) {
                    val widthPx = constraints.maxWidth.toFloat()
                    val heightPx = constraints.maxHeight.toFloat()
                    val pushMs = 380
                    val navEasing = FastOutSlowInEasing

                    val onDragWorkerManager: (Float) -> Unit = remember(heightPx) {
                        { totalDragY ->
                            if (totalDragY > 0f) {
                                isWmVisible = true
                                val fraction = (totalDragY / (heightPx * 0.28f)).coerceIn(0f, 1f)
                                scope.launch { workerManagerOpenProgress.snapTo(fraction) }
                            } else {
                                scope.launch { workerManagerOpenProgress.snapTo(0f) }
                            }
                        }
                    }

                    val onSettleWorkerManager: (Float) -> Unit = remember(heightPx) {
                        { totalDragY ->
                            if (totalDragY > 0f) {
                                val fraction = (totalDragY / (heightPx * 0.28f)).coerceIn(0f, 1f)
                                if (fraction > 0.08f) {
                                    workerManagerSection = ManagerSection.WORKERS
                                    if (screenStack.lastOrNull() != "worker_manager") {
                                        screenStack.add("worker_manager")
                                    }
                                    isWmVisible = true
                                    scope.launch {
                                        workerManagerOpenProgress.animateTo(
                                            1f,
                                            spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                                        )
                                    }
                                } else {
                                    if (screenStack.lastOrNull() == "worker_manager") {
                                        screenStack.removeAt(screenStack.size - 1)
                                    }
                                    scope.launch {
                                        workerManagerOpenProgress.animateTo(
                                            0f,
                                            spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                                        )
                                        if (screenStack.lastOrNull() != "worker_manager") {
                                            isWmVisible = false
                                        }
                                    }
                                }
                            } else {
                                if (screenStack.lastOrNull() == "worker_manager") {
                                    screenStack.removeAt(screenStack.size - 1)
                                }
                                scope.launch {
                                    workerManagerOpenProgress.animateTo(
                                        0f,
                                        spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                                    )
                                    if (screenStack.lastOrNull() != "worker_manager") {
                                        isWmVisible = false
                                    }
                                }
                            }
                        }
                    }

                    val isHome = currentScreen == "home"
                    val isSettings = currentScreen == "settings"
                    val isLogs = currentScreen == "logs"
                    val isHistory = currentScreen == "history"
                    val isAbout = currentScreen == "about"
                    val isLicense = currentScreen == "license"
                    val isTerms = currentScreen == "terms"
                    val isUpdate = currentScreen == "update"

                    // Animated States for All Screens
                    val updateOffsetFraction = animateFloatAsState(
                        targetValue = if (isUpdate) 0f else 1.0f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "updateOffset"
                    )
                    val updateScale = animateFloatAsState(
                        targetValue = if (isUpdate) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "updateScale"
                    )
                    val updateAlpha = animateFloatAsState(
                        targetValue = if (isUpdate) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "updateAlpha"
                    )

                    val homeOffsetFraction = animateFloatAsState(
                        targetValue = when {
                            isHome -> 0f
                            isSettings || isAbout || isLicense || isTerms || isUpdate -> -0.15f
                            isLogs || isHistory -> 0.15f
                            else -> 0f
                        },
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "homeOffset"
                    )
                    val homeScale = animateFloatAsState(
                        targetValue = if (isHome) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "homeScale"
                    )
                    val homeAlpha = animateFloatAsState(
                        targetValue = if (isHome) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "homeAlpha"
                    )

                    val settingsOffsetFraction = animateFloatAsState(
                        targetValue = when {
                            isSettings -> 0f
                            isAbout || isLicense || isTerms || isWorkerManager -> -0.15f
                            else -> 1.0f
                        },
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "settingsOffset"
                    )
                    val settingsScale = animateFloatAsState(
                        targetValue = if (isSettings) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "settingsScale"
                    )
                    val settingsAlpha = animateFloatAsState(
                        targetValue = if (isSettings) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "settingsAlpha"
                    )

                    val logsOffsetFraction = animateFloatAsState(
                        targetValue = if (isLogs) 0f else -1.0f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "logsOffset"
                    )
                    val logsScale = animateFloatAsState(
                        targetValue = if (isLogs) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "logsScale"
                    )
                    val logsAlpha = animateFloatAsState(
                        targetValue = if (isLogs) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "logsAlpha"
                    )

                    val historyOffsetFraction = animateFloatAsState(
                        targetValue = if (isHistory) 0f else -1.0f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "historyOffset"
                    )
                    val historyScale = animateFloatAsState(
                        targetValue = if (isHistory) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "historyScale"
                    )
                    val historyAlpha = animateFloatAsState(
                        targetValue = if (isHistory) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "historyAlpha"
                    )

                    val aboutOffsetFraction = animateFloatAsState(
                        targetValue = when {
                            isAbout -> 0f
                            isLicense || isTerms -> -0.15f
                            else -> 1.0f
                        },
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "aboutOffset"
                    )
                    val aboutScale = animateFloatAsState(
                        targetValue = if (isAbout) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "aboutScale"
                    )
                    val aboutAlpha = animateFloatAsState(
                        targetValue = if (isAbout) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "aboutAlpha"
                    )

                    val licenseOffsetFraction = animateFloatAsState(
                        targetValue = when {
                            isLicense -> 0f
                            isTerms -> -0.15f
                            else -> 1.0f
                        },
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "licenseOffset"
                    )
                    val licenseScale = animateFloatAsState(
                        targetValue = if (isLicense) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "licenseScale"
                    )
                    val licenseAlpha = animateFloatAsState(
                        targetValue = if (isLicense) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "licenseAlpha"
                    )

                    val termsOffsetFraction = animateFloatAsState(
                        targetValue = if (isTerms) 0f else 1.0f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "termsOffset"
                    )
                    val termsScale = animateFloatAsState(
                        targetValue = if (isTerms) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "termsScale"
                    )
                    val termsAlpha = animateFloatAsState(
                        targetValue = if (isTerms) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "termsAlpha"
                    )

                    // ── BACKGROUND CANVAS (Overdraw-Optimized: rendered only when visible) ──
                    val isCanvasVisible = activeScreens.contains("home") || isWmVisible
                    if (isCanvasVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val wmP = workerManagerOpenProgress.value
                                    val isNonHome = currentScreen != "home" && currentScreen != "worker_manager"
                                    val effProgress = if (isNonHome) 1f else wmP
                                    val blurEffect = blurCache.getBlurEffect(effProgress)

                                    compositingStrategy = if (blurEffect != null) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                                    renderEffect = blurEffect
                                }
                        ) {
                            val isSocks5 by app.prefsManager.isSocks5Flow.collectAsState()
                            CyberEnergyCanvas(
                                state = globalProxyState,
                                isSocks5 = isSocks5,
                                externalTouchPoint = globalTouchPoint,
                                isUiHidden = isUiHidden,
                                isConstellationPaused = (currentScreen != "home"),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // ── GAUSSIAN FROSTED DARK BACKDROP OVERLAY FOR NON-HOME TABS ──
                    val isBackdropVisible = currentScreen != "home" || isWmVisible || activeScreens.any { it != "home" }
                    if (isBackdropVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val wmP = workerManagerOpenProgress.value
                                    val isHomeTab = currentScreen == "home"
                                    val alphaVal = if (isHomeTab) {
                                        wmP * 0.48f
                                    } else if (currentScreen == "worker_manager") {
                                        wmP.coerceIn(0f, 1f) * 0.48f
                                    } else {
                                        standardBackdropAlpha.value
                                    }
                                    alpha = alphaVal
                                }
                                .background(Color.Black)
                        )
                    }

                    // ── 1. HOME SCREEN (Layered & Hardware Blur Cached) ──
                    if (activeScreens.contains("home")) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val wmP = workerManagerOpenProgress.value
                                    val isInteractiveWm = (currentScreen == "home" || currentScreen == "worker_manager")
                                    val baseScale = homeScale.value
                                    val baseAlpha = homeAlpha.value
                                    val effectiveScale = if (isInteractiveWm) (1.0f - 0.04f * wmP) else baseScale
                                    val effectiveAlpha = if (isInteractiveWm) (1.0f - 0.25f * wmP) else baseAlpha

                                    translationX = widthPx * homeOffsetFraction.value
                                    scaleX = effectiveScale
                                    scaleY = effectiveScale
                                    alpha = effectiveAlpha

                                    val blurEffect = blurCache.getBlurEffect(if (isInteractiveWm) wmP else if (currentScreen != "home") 1f else 0f)
                                    renderEffect = blurEffect
                                }
                        ) {
                            HomeScreen(
                                onOpenSettings = onOpenSettings,
                                onOpenLogs = onOpenLogs,
                                onOpenHistory = onOpenHistory,
                                onOpenUpdate = onOpenUpdate,
                                onOpenWorkerGuide = onOpenWorkerGuide,
                                onOpenWorkerManager = onOpenWorkerManager,
                                onDragWorkerManager = onDragWorkerManager,
                                onSettleWorkerManager = onSettleWorkerManager,
                                onUiHiddenChange = onUiHiddenChange,
                                isInteractive = (currentScreen == "home")
                            )
                        }
                    }

                    // ── 2. LOGS SCREEN ──
                    if (activeScreens.contains("logs")) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = widthPx * logsOffsetFraction.value
                                    scaleX = logsScale.value
                                    scaleY = logsScale.value
                                    alpha = logsAlpha.value
                                }
                        ) {
                            LogsScreen(
                                onBack = onNavigateBack,
                                onOpenSettings = onOpenSettings
                            )
                        }
                    }

                    // ── 3. HISTORY SCREEN ──
                    if (activeScreens.contains("history")) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = widthPx * historyOffsetFraction.value
                                    scaleX = historyScale.value
                                    scaleY = historyScale.value
                                    alpha = historyAlpha.value
                                }
                        ) {
                            HistoryScreen(
                                onBack = onNavigateBack
                            )
                        }
                    }

                    // ── 4. SETTINGS SCREEN ──
                    if (activeScreens.contains("settings")) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = widthPx * settingsOffsetFraction.value
                                    scaleX = settingsScale.value
                                    scaleY = settingsScale.value
                                    alpha = settingsAlpha.value
                                }
                        ) {
                            SettingsScreen(
                                onBack = onNavigateBack,
                                onOpenAbout = onOpenAbout,
                                onOpenUpdate = onOpenUpdate,
                                onOpenWorkerGuide = onOpenWorkerGuide,
                                onOpenWorkerManager = onOpenWorkerManager
                            )
                        }
                    }

                    // ── 5. WORKER MANAGER SCREEN (Top-to-Bottom Interactive Sheet) ──
                    if (isWmVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val wmP = workerManagerOpenProgress.value
                                    translationY = heightPx * (-(1.0f - wmP))
                                    val wmScale = 0.96f + 0.04f * wmP
                                    scaleX = wmScale
                                    scaleY = wmScale
                                    alpha = wmP.coerceIn(0f, 1f)
                                }
                        ) {
                            WorkerManagerScreen(
                                prefs = app.prefsManager,
                                onBack = onNavigateBack,
                                initialSection = workerManagerSection
                            )
                        }
                    }

                    // ── 6. ABOUT SCREEN ──
                    if (activeScreens.contains("about")) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = widthPx * aboutOffsetFraction.value
                                    scaleX = aboutScale.value
                                    scaleY = aboutScale.value
                                    alpha = aboutAlpha.value
                                }
                        ) {
                            AboutScreen(
                                onBack = onNavigateBack,
                                onOpenLicense = onOpenLicense,
                                onOpenTerms = onOpenTerms,
                                onOpenUpdate = onOpenUpdate
                            )
                        }
                    }

                    // ── 7. LICENSE SCREEN ──
                    if (activeScreens.contains("license")) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = widthPx * licenseOffsetFraction.value
                                    scaleX = licenseScale.value
                                    scaleY = licenseScale.value
                                    alpha = licenseAlpha.value
                                }
                        ) {
                            LicenseScreen(
                                onBack = onNavigateBack
                            )
                        }
                    }

                    // ── 8. TERMS SCREEN ──
                    if (activeScreens.contains("terms")) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = widthPx * termsOffsetFraction.value
                                    scaleX = termsScale.value
                                    scaleY = termsScale.value
                                    alpha = termsAlpha.value
                                }
                        ) {
                            TermsScreen(
                                onBack = onNavigateBack
                            )
                        }
                    }

                    // ── 9. UPDATE SCREEN ──
                    if (activeScreens.contains("update")) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = widthPx * updateOffsetFraction.value
                                    scaleX = updateScale.value
                                    scaleY = updateScale.value
                                    alpha = updateAlpha.value
                                }
                        ) {
                            UpdateScreen(
                                releaseInfo = currentUpdateInfo,
                                onBack = onNavigateBack
                            )
                        }
                    }

                    if (showUnofficialDialog) {
                        UnofficialBuildDialog(
                            onDismiss = {
                                showUnofficialDialog = false
                            }
                        )
                    } else if (showGithubStarDialog) {
                        GithubStarDialog(
                            onDismiss = {
                                showGithubStarDialog = false
                            },
                            onStarClicked = {
                                com.mirrly.tgproxy.service.LaunchCountManager.setStarDismissed(applicationContext, true)
                                showGithubStarDialog = false
                            },
                            onNeverShowAgain = {
                                com.mirrly.tgproxy.service.LaunchCountManager.setStarDismissed(applicationContext, true)
                                showGithubStarDialog = false
                            }
                        )
                    }

                    // Deep Link Import Worker Dialog
                    var pendingImportWorker by remember { mutableStateOf(extractWorkerDeepLink(intent)) }

                    DisposableEffect(Unit) {
                        onDeepLinkReceived = { deepLinkWorker ->
                            pendingImportWorker = deepLinkWorker
                        }
                        onDispose {
                            onDeepLinkReceived = null
                        }
                    }

                    pendingImportWorker?.let { (importName, importDomain) ->
                        ImportWorkerDialog(
                            name = importName,
                            domain = importDomain,
                            onDismiss = { pendingImportWorker = null },
                            onImport = { name, domain ->
                                val res = app.prefsManager.addCustomWorker(name, domain)
                                res.fold(
                                    onSuccess = { added ->
                                        app.prefsManager.setActiveWorkerId(added.id)
                                        pendingImportWorker = null
                                        Toast.makeText(applicationContext, "Воркер «${added.name}» импортирован и активирован", Toast.LENGTH_LONG).show()
                                    },
                                    onFailure = { err ->
                                        // If already exists, activate it
                                        val existing = app.prefsManager.getCustomWorkers().find { it.domain.equals(domain.trim(), ignoreCase = true) }
                                        if (existing != null) {
                                            app.prefsManager.setActiveWorkerId(existing.id)
                                            pendingImportWorker = null
                                            Toast.makeText(applicationContext, "Воркер уже был в списке и теперь активирован", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(applicationContext, err.message ?: "Ошибка импорта", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    private var onDeepLinkReceived: ((Pair<String, String>) -> Unit)? = null

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractWorkerDeepLink(intent)?.let {
            onDeepLinkReceived?.invoke(it)
        }
    }

    private fun extractWorkerDeepLink(intent: android.content.Intent?): Pair<String, String>? {
        val data = intent?.data ?: return null
        val scheme = data.scheme?.lowercase() ?: ""
        val host = data.host?.lowercase() ?: ""
        val path = data.path ?: ""

        val isMirrlyScheme = scheme == "mirrly" && (host == "worker" || path.contains("worker"))
        val isWebScheme = (scheme == "https" || scheme == "http") &&
                (host == "mirrly.app" || host == "www.mirrly.app" || host == "mirrly.me" || host == "www.mirrly.me")

        if (isMirrlyScheme || isWebScheme) {
            val rawDomain = data.getQueryParameter("domain")
                ?: data.getQueryParameter("d")
                ?: data.getQueryParameter("host")
                ?: data.getQueryParameter("worker")
                ?: data.getQueryParameter("url")
                ?: data.lastPathSegment?.takeIf { it != "worker" && it.isNotEmpty() }
                ?: ""
            val rawName = data.getQueryParameter("name")
                ?: data.getQueryParameter("n")
                ?: data.getQueryParameter("title")
                ?: ""
            val cleanDomain = com.mirrly.tgproxy.core.ProxyConfig.sanitizeDomain(rawDomain)
            if (cleanDomain.isNotBlank()) {
                return Pair(rawName.trim(), cleanDomain)
            }
        }
        return null
    }

    private fun optimizeForHighRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val modes = display?.supportedModes ?: emptyArray()
                val maxMode = modes.maxByOrNull { it.refreshRate }
                if (maxMode != null && maxMode.refreshRate > 60f) {
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = maxMode.modeId
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val modes = window.windowManager.defaultDisplay.supportedModes ?: emptyArray()
                val maxMode = modes.maxByOrNull { it.refreshRate }
                if (maxMode != null && maxMode.refreshRate > 60f) {
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = maxMode.modeId
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
