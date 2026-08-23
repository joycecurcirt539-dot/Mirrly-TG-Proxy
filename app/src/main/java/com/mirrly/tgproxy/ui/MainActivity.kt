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
import androidx.compose.ui.platform.LocalContext
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

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

                val globalProxyState = if (isProxyRunning) ProxyUiState.CONNECTED else ProxyUiState.DISCONNECTED

                val screenStack = remember { mutableStateListOf("home") }
                val currentScreen = screenStack.lastOrNull() ?: "home"
                var lastBackTime by remember { mutableLongStateOf(0L) }
                val scope = rememberCoroutineScope()

                val workerManagerOpenProgress = remember { Animatable(0f) }
                val isWorkerManager = currentScreen == "worker_manager"
                val isWorkerGuide = currentScreen == "worker_guide"

                // Synchronize workerManagerOpenProgress with current screen
                LaunchedEffect(currentScreen) {
                    if (isWorkerManager) {
                        if (workerManagerOpenProgress.value < 0.99f) {
                            workerManagerOpenProgress.animateTo(
                                1f,
                                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                            )
                        }
                    } else if (currentScreen == "home") {
                        if (workerManagerOpenProgress.value > 0.01f) {
                            workerManagerOpenProgress.animateTo(
                                0f,
                                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                            )
                        }
                    }
                }

                fun navigateTo(screen: String) {
                    if (screenStack.lastOrNull() != screen) {
                        screenStack.add(screen)
                        if (screen == "worker_manager") {
                            scope.launch {
                                workerManagerOpenProgress.animateTo(
                                    1f,
                                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        }
                    }
                }

                var isNavigatingBack by remember { mutableStateOf(false) }

                fun navigateBack() {
                    if (isNavigatingBack) return
                    if (screenStack.size > 1) {
                        isNavigatingBack = true
                        val topScreen = screenStack.removeAt(screenStack.size - 1)
                        if (topScreen == "worker_manager") {
                            scope.launch {
                                workerManagerOpenProgress.animateTo(
                                    0f,
                                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                                )
                                isNavigatingBack = false
                            }
                        } else {
                            scope.launch {
                                delay(250)
                                isNavigatingBack = false
                            }
                        }
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastBackTime < 2000) {
                            finish()
                        } else {
                            lastBackTime = now
                            Toast.makeText(this@MainActivity, "Нажмите еще раз для выхода", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                BackHandler {
                    navigateBack()
                }

                val standardBlurRadius by animateDpAsState(
                    targetValue = if (currentScreen == "home") 0.dp else 16.dp,
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                    label = "canvasBlur"
                )

                val standardBackdropAlpha by animateFloatAsState(
                    targetValue = if (currentScreen == "home") 0f else 0.48f,
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                    label = "backdropAlpha"
                )

                val wmProgress = workerManagerOpenProgress.value
                val isInteractiveWm = (currentScreen == "home" || isWorkerManager) && !isWorkerGuide

                val activeBlurRadius = if (currentScreen == "home") (wmProgress * 16f).dp else 16.dp
                val activeBackdropAlpha = if (currentScreen == "home") (wmProgress * 0.48f) else 0.48f
                val homeBlurRadius = if (currentScreen == "home") (wmProgress * 16f).dp else 16.dp

                var isUiHidden by remember { mutableStateOf(false) }
                val currentUpdateInfo by com.mirrly.tgproxy.service.UpdateManager.updateState.collectAsState()

                BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    // Global Seamless Cyber Energy Canvas with Zero-Lag GPU Hardware Blur Optimization
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                                if (activeBlurRadius > 0.5.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val radiusPx = activeBlurRadius.toPx()
                                    renderEffect = RenderEffect.createBlurEffect(
                                        radiusPx,
                                        radiusPx,
                                        Shader.TileMode.CLAMP
                                    ).asComposeRenderEffect()
                                } else {
                                    renderEffect = null
                                }
                            }
                            .then(
                                if (activeBlurRadius > 0.5.dp && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                    Modifier.blur(activeBlurRadius)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        CyberEnergyCanvas(
                            state = globalProxyState,
                            isUiHidden = isUiHidden,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Gaussian Frosted Dark Backdrop Overlay for Non-Home Tabs
                    if (activeBackdropAlpha > 0.01f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = activeBackdropAlpha))
                        )
                    }

                    val widthPx = constraints.maxWidth.toFloat()
                    val pushMs = 380
                    val navEasing = FastOutSlowInEasing

                    val isHome = currentScreen == "home"
                    val isSettings = currentScreen == "settings"
                    val isLogs = currentScreen == "logs"
                    val isHistory = currentScreen == "history"
                    val isAbout = currentScreen == "about"
                    val isLicense = currentScreen == "license"
                    val isTerms = currentScreen == "terms"
                    val isUpdate = currentScreen == "update"

                    // Animated offsets & scales for Update screen
                    val updateOffsetFraction by animateFloatAsState(
                        targetValue = if (isUpdate) 0f else 1.0f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "updateOffset"
                    )
                    val updateScale by animateFloatAsState(
                        targetValue = if (isUpdate) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "updateScale"
                    )
                    val updateAlpha by animateFloatAsState(
                        targetValue = if (isUpdate) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "updateAlpha"
                    )

                    // Animated offsets & scales for Worker Guide screen
                    val workerGuideOffsetFraction by animateFloatAsState(
                        targetValue = if (isWorkerGuide) 0f else 1.0f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "workerGuideOffset"
                    )
                    val workerGuideScale by animateFloatAsState(
                        targetValue = if (isWorkerGuide) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "workerGuideScale"
                    )
                    val workerGuideAlpha by animateFloatAsState(
                        targetValue = if (isWorkerGuide) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "workerGuideAlpha"
                    )

                    val heightPx = constraints.maxHeight.toFloat()

                    // Animated offsets & scales for Home screen
                    val homeOffsetFraction by animateFloatAsState(
                        targetValue = when {
                            isHome -> 0f
                            isSettings || isAbout || isLicense || isTerms || isUpdate || isWorkerGuide -> -0.15f
                            isLogs || isHistory -> 0.15f
                            else -> 0f
                        },
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "homeOffset"
                    )
                    val homeScale by animateFloatAsState(
                        targetValue = if (isHome) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "homeScale"
                    )
                    val homeAlpha by animateFloatAsState(
                        targetValue = if (isHome) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "homeAlpha"
                    )

                    // Animated offsets & scales for Settings screen
                    val settingsOffsetFraction by animateFloatAsState(
                        targetValue = when {
                            isSettings -> 0f
                            isAbout || isLicense || isTerms || isWorkerGuide || isWorkerManager -> -0.15f
                            else -> 1.0f
                        },
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "settingsOffset"
                    )
                    val settingsScale by animateFloatAsState(
                        targetValue = if (isSettings) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "settingsScale"
                    )
                    val settingsAlpha by animateFloatAsState(
                        targetValue = if (isSettings) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "settingsAlpha"
                    )

                    // Animated offsets & scales for Logs screen
                    val logsOffsetFraction by animateFloatAsState(
                        targetValue = if (isLogs) 0f else -1.0f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "logsOffset"
                    )
                    val logsScale by animateFloatAsState(
                        targetValue = if (isLogs) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "logsScale"
                    )
                    val logsAlpha by animateFloatAsState(
                        targetValue = if (isLogs) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "logsAlpha"
                    )

                    // Animated offsets & scales for History screen
                    val historyOffsetFraction by animateFloatAsState(
                        targetValue = if (isHistory) 0f else -1.0f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "historyOffset"
                    )
                    val historyScale by animateFloatAsState(
                        targetValue = if (isHistory) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "historyScale"
                    )
                    val historyAlpha by animateFloatAsState(
                        targetValue = if (isHistory) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "historyAlpha"
                    )

                    // Animated offsets & scales for About screen
                    val aboutOffsetFraction by animateFloatAsState(
                        targetValue = when {
                            isAbout -> 0f
                            isLicense || isTerms -> -0.15f
                            else -> 1.0f
                        },
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "aboutOffset"
                    )
                    val aboutScale by animateFloatAsState(
                        targetValue = if (isAbout) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "aboutScale"
                    )
                    val aboutAlpha by animateFloatAsState(
                        targetValue = if (isAbout) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "aboutAlpha"
                    )

                    // Animated offsets & scales for License screen
                    val licenseOffsetFraction by animateFloatAsState(
                        targetValue = when {
                            isLicense -> 0f
                            isTerms -> -0.15f
                            else -> 1.0f
                        },
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "licenseOffset"
                    )
                    val licenseScale by animateFloatAsState(
                        targetValue = if (isLicense) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "licenseScale"
                    )
                    val licenseAlpha by animateFloatAsState(
                        targetValue = if (isLicense) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "licenseAlpha"
                    )

                    // Animated offsets & scales for Terms screen
                    val termsOffsetFraction by animateFloatAsState(
                        targetValue = if (isTerms) 0f else 1.0f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "termsOffset"
                    )
                    val termsScale by animateFloatAsState(
                        targetValue = if (isTerms) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "termsScale"
                    )
                    val termsAlpha by animateFloatAsState(
                        targetValue = if (isTerms) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "termsAlpha"
                    )

                    // 1. HOME SCREEN (Pre-warmed & persistent with real-time blur)
                    val homeScaleEffective = if (isInteractiveWm) (1.0f - 0.04f * wmProgress) else homeScale
                    val homeAlphaEffective = if (isInteractiveWm) (1.0f - 0.25f * wmProgress) else homeAlpha

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = widthPx * homeOffsetFraction
                                scaleX = homeScaleEffective
                                scaleY = homeScaleEffective
                                alpha = homeAlphaEffective
                                if (homeBlurRadius > 0.5.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val radiusPx = homeBlurRadius.toPx()
                                    renderEffect = RenderEffect.createBlurEffect(
                                        radiusPx,
                                        radiusPx,
                                        Shader.TileMode.CLAMP
                                    ).asComposeRenderEffect()
                                } else {
                                    renderEffect = null
                                }
                            }
                            .then(
                                if (homeBlurRadius > 0.5.dp && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                    Modifier.blur(homeBlurRadius)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        HomeScreen(
                            onOpenSettings = { navigateTo("settings") },
                            onOpenLogs = { navigateTo("logs") },
                            onOpenHistory = { navigateTo("history") },
                            onOpenUpdate = { navigateTo("update") },
                            onOpenWorkerGuide = { navigateTo("worker_guide") },
                            onOpenWorkerManager = { navigateTo("worker_manager") },
                            onDragWorkerManager = { totalDragY ->
                                if (totalDragY > 0f) {
                                    val fraction = (totalDragY / (heightPx * 0.35f)).coerceIn(0f, 1f)
                                    scope.launch { workerManagerOpenProgress.snapTo(fraction) }
                                } else {
                                    scope.launch { workerManagerOpenProgress.snapTo(0f) }
                                }
                            },
                            onSettleWorkerManager = { totalDragY ->
                                if (totalDragY > 0f) {
                                    val fraction = (totalDragY / (heightPx * 0.35f)).coerceIn(0f, 1f)
                                    if (fraction > 0.18f) {
                                        scope.launch {
                                            workerManagerOpenProgress.animateTo(
                                                1f,
                                                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                                            )
                                            navigateTo("worker_manager")
                                        }
                                    } else {
                                        scope.launch {
                                            workerManagerOpenProgress.animateTo(
                                                0f,
                                                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                                            )
                                        }
                                    }
                                } else {
                                    scope.launch {
                                        workerManagerOpenProgress.animateTo(
                                            0f,
                                            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                                        )
                                    }
                                }
                            },
                            onUiHiddenChange = { hidden ->
                                isUiHidden = hidden
                            }
                        )
                    }

                    // 2. LOGS SCREEN (Pre-warmed & persistent)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = widthPx * logsOffsetFraction
                                scaleX = logsScale
                                scaleY = logsScale
                                alpha = logsAlpha
                            }
                    ) {
                        LogsScreen(
                            onBack = { navigateBack() },
                            onOpenSettings = { navigateTo("settings") }
                        )
                    }

                    // HISTORY SCREEN (Pre-warmed & persistent)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = widthPx * historyOffsetFraction
                                scaleX = historyScale
                                scaleY = historyScale
                                alpha = historyAlpha
                            }
                    ) {
                        HistoryScreen(
                            onBack = { navigateBack() }
                        )
                    }

                    // 3. SETTINGS SCREEN (Pre-warmed & persistent)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = widthPx * settingsOffsetFraction
                                scaleX = settingsScale
                                scaleY = settingsScale
                                alpha = settingsAlpha
                            }
                    ) {
                        SettingsScreen(
                            onBack = { navigateBack() },
                            onOpenAbout = { navigateTo("about") },
                            onOpenUpdate = { navigateTo("update") },
                            onOpenWorkerGuide = { navigateTo("worker_guide") },
                            onOpenWorkerManager = { navigateTo("worker_manager") }
                        )
                    }

                    // WORKER MANAGER SCREEN (Interactive Bottom-to-Top slide up sheet with blur when guide is open)
                    val wmOffsetYFraction = when {
                        isWorkerGuide -> -0.15f
                        else -> (1.0f - wmProgress)
                    }
                    val wmScale = when {
                        isWorkerGuide -> 0.94f
                        else -> 0.94f + 0.06f * wmProgress
                    }
                    val wmAlpha = when {
                        isWorkerGuide -> (1.0f - workerGuideOffsetFraction).coerceIn(0f, 1f)
                        else -> (wmProgress * 1.5f).coerceIn(0f, 1f)
                    }
                    val wmGuideBlurRadius = if (isWorkerGuide) 16.dp else 0.dp

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationY = heightPx * wmOffsetYFraction
                                scaleX = wmScale
                                scaleY = wmScale
                                alpha = wmAlpha
                                if (wmGuideBlurRadius > 0.5.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val radiusPx = wmGuideBlurRadius.toPx()
                                    renderEffect = RenderEffect.createBlurEffect(
                                        radiusPx,
                                        radiusPx,
                                        Shader.TileMode.CLAMP
                                    ).asComposeRenderEffect()
                                } else {
                                    renderEffect = null
                                }
                            }
                            .then(
                                if (wmGuideBlurRadius > 0.5.dp && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                    Modifier.blur(wmGuideBlurRadius)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        WorkerManagerScreen(
                            prefs = app.prefsManager,
                            onBack = { navigateBack() },
                            onOpenWorkerGuide = { navigateTo("worker_guide") }
                        )
                    }

                    // WORKER GUIDE SCREEN (Pre-warmed & persistent)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = widthPx * workerGuideOffsetFraction
                                scaleX = workerGuideScale
                                scaleY = workerGuideScale
                                alpha = workerGuideAlpha
                            }
                    ) {
                        CloudflareWorkerGuideScreen(
                            onBack = { navigateBack() }
                        )
                    }

                    // 4. ABOUT SCREEN (Pre-warmed & persistent)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = widthPx * aboutOffsetFraction
                                scaleX = aboutScale
                                scaleY = aboutScale
                                alpha = aboutAlpha
                            }
                    ) {
                        AboutScreen(
                            onBack = { navigateBack() },
                            onOpenLicense = { navigateTo("license") },
                            onOpenTerms = { navigateTo("terms") },
                            onOpenUpdate = { navigateTo("update") }
                        )
                    }

                    // 5. LICENSE SCREEN (Pre-warmed & persistent)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = widthPx * licenseOffsetFraction
                                scaleX = licenseScale
                                scaleY = licenseScale
                                alpha = licenseAlpha
                            }
                    ) {
                        LicenseScreen(
                            onBack = { navigateBack() }
                        )
                    }

                    // 6. TERMS SCREEN (Pre-warmed & persistent)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = widthPx * termsOffsetFraction
                                scaleX = termsScale
                                scaleY = termsScale
                                alpha = termsAlpha
                            }
                    ) {
                        TermsScreen(
                            onBack = { navigateBack() }
                        )
                    }

                    // 7. UPDATE SCREEN (Pre-warmed & persistent)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = widthPx * updateOffsetFraction
                                scaleX = updateScale
                                scaleY = updateScale
                                alpha = updateAlpha
                            }
                    ) {
                        UpdateScreen(
                            releaseInfo = currentUpdateInfo,
                            onBack = { navigateBack() }
                        )
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
