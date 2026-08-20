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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.mirrly.tgproxy.ui.theme.MirrlyTheme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import android.graphics.RenderEffect
import android.graphics.Shader

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
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

                fun navigateTo(screen: String) {
                    if (screenStack.lastOrNull() != screen) {
                        screenStack.add(screen)
                    }
                }

                fun navigateBack() {
                    if (screenStack.size > 1) {
                        screenStack.removeAt(screenStack.size - 1)
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

                val blurRadius by animateDpAsState(
                    targetValue = if (currentScreen == "home") 0.dp else 12.dp,
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                    label = "canvasBlur"
                )

                val backdropAlpha by animateFloatAsState(
                    targetValue = if (currentScreen == "home") 0f else 0.45f,
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                    label = "backdropAlpha"
                )

                var isUiHidden by remember { mutableStateOf(false) }
                val currentUpdateInfo by com.mirrly.tgproxy.service.UpdateManager.updateState.collectAsState()

                BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    // Global Seamless Cyber Energy Canvas with Zero-Lag GPU Hardware Blur Optimization
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                                if (blurRadius > 0.5.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val radiusPx = blurRadius.toPx()
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
                                if (blurRadius > 0.5.dp && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                    Modifier.blur(blurRadius)
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
                    if (backdropAlpha > 0.01f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = backdropAlpha))
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
                    val isWorkerGuide = currentScreen == "worker_guide"
                    val isWorkerManager = currentScreen == "worker_manager"

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

                    // Animated offsets & scales for Worker Manager screen (Top-to-bottom slide dropdown)
                    val workerManagerOffsetYFraction by animateFloatAsState(
                        targetValue = when {
                            isWorkerManager -> 0f
                            isWorkerGuide -> -0.15f
                            else -> -1.0f
                        },
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "workerManagerOffsetY"
                    )
                    val workerManagerScale by animateFloatAsState(
                        targetValue = if (isWorkerManager) 1.0f else 0.94f,
                        animationSpec = tween(pushMs, easing = navEasing),
                        label = "workerManagerScale"
                    )
                    val workerManagerAlpha by animateFloatAsState(
                        targetValue = if (isWorkerManager) 1.0f else 0.0f,
                        animationSpec = tween(220),
                        label = "workerManagerAlpha"
                    )

                    // Animated offsets & scales for Home screen
                    val homeOffsetFraction by animateFloatAsState(
                        targetValue = when {
                            isHome -> 0f
                            isSettings || isAbout || isLicense || isTerms || isUpdate || isWorkerGuide || isWorkerManager -> -0.15f
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

                    // 1. HOME SCREEN (Pre-warmed & persistent)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = widthPx * homeOffsetFraction
                                scaleX = homeScale
                                scaleY = homeScale
                                alpha = homeAlpha
                            }
                    ) {
                        HomeScreen(
                            onOpenSettings = { navigateTo("settings") },
                            onOpenLogs = { navigateTo("logs") },
                            onOpenHistory = { navigateTo("history") },
                            onOpenUpdate = { navigateTo("update") },
                            onOpenWorkerGuide = { navigateTo("worker_guide") },
                            onOpenWorkerManager = { navigateTo("worker_manager") },
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

                    // WORKER MANAGER SCREEN (Top-to-bottom slide dropdown)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationY = heightPx * workerManagerOffsetYFraction
                                scaleX = workerManagerScale
                                scaleY = workerManagerScale
                                alpha = workerManagerAlpha
                            }
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
                                        Toast.makeText(applicationContext, "Воркер «${added.name}» импортирован и активирован ⚡", Toast.LENGTH_LONG).show()
                                    },
                                    onFailure = { err ->
                                        // If already exists, activate it
                                        val existing = app.prefsManager.getCustomWorkers().find { it.domain.equals(domain.trim(), ignoreCase = true) }
                                        if (existing != null) {
                                            app.prefsManager.setActiveWorkerId(existing.id)
                                            pendingImportWorker = null
                                            Toast.makeText(applicationContext, "Воркер уже был в списке и теперь активирован ⚡", Toast.LENGTH_LONG).show()
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

@Composable
fun ImportWorkerDialog(
    name: String,
    domain: String,
    onDismiss: () -> Unit,
    onImport: (name: String, domain: String) -> Unit
) {
    var editName by remember { mutableStateOf(name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "📥 Импорт Cloudflare Worker",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Вы открыли ссылку для подключения Cloudflare Worker. Хотите добавить этот узел в список и сразу активировать?",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("Название воркера (не обязательно)") },
                    placeholder = { Text("например: От друга (опционально)", color = Color.White.copy(alpha = 0.35f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E676),
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedLabelColor = Color(0xFF00E676),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E293B).copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "ДОМЕН УЗЛА",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF),
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = domain,
                            fontSize = 13.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = Color.White
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(editName, domain) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E676),
                    contentColor = Color(0xFF0A0E1A)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Импортировать ⚡", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = Color.White.copy(alpha = 0.7f))
            }
        }
    )
}
