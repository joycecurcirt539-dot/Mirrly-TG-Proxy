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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
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

                var currentScreen by remember { mutableStateOf("home") }
                var previousScreen by remember { mutableStateOf("home") }
                var lastBackTime by remember { mutableLongStateOf(0L) }

                BackHandler {
                    when (currentScreen) {
                        "license" -> {
                            currentScreen = "about"
                        }
                        "about" -> {
                            currentScreen = "settings"
                        }
                        "settings" -> {
                            currentScreen = previousScreen
                        }
                        "logs" -> {
                            currentScreen = "home"
                        }
                        "home" -> {
                            val now = System.currentTimeMillis()
                            if (now - lastBackTime < 2000) {
                                finish()
                            } else {
                                lastBackTime = now
                                Toast.makeText(this@MainActivity, "Нажмите еще раз для выхода", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
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
                    val isAbout = currentScreen == "about"
                    val isLicense = currentScreen == "license"
                    val isTerms = currentScreen == "terms"

                    // Animated offsets & scales for Home screen
                    val homeOffsetFraction by animateFloatAsState(
                        targetValue = when {
                            isHome -> 0f
                            isSettings || isAbout || isLicense || isTerms -> -0.15f
                            isLogs -> 0.15f
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
                            isAbout || isLicense || isTerms -> -0.15f
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
                            onOpenSettings = {
                                previousScreen = "home"
                                currentScreen = "settings"
                            },
                            onOpenLogs = {
                                currentScreen = "logs"
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
                            onBack = { currentScreen = "home" },
                            onOpenSettings = {
                                previousScreen = "logs"
                                currentScreen = "settings"
                            }
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
                            onBack = { currentScreen = previousScreen },
                            onOpenAbout = { currentScreen = "about" }
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
                            onBack = { currentScreen = "settings" },
                            onOpenLicense = { currentScreen = "license" },
                            onOpenTerms = { currentScreen = "terms" }
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
                            onBack = { currentScreen = "about" }
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
                            onBack = { currentScreen = "about" }
                        )
                    }
                }
            }
        }
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
