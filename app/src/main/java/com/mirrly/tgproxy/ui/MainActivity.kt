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
import com.mirrly.tgproxy.ui.theme.MirrlyTheme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.graphicsLayer

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                var currentScreen by remember { mutableStateOf("home") }
                var previousScreen by remember { mutableStateOf("home") }
                var lastBackTime by remember { mutableLongStateOf(0L) }

                BackHandler {
                    when (currentScreen) {
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

                BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    val widthPx = constraints.maxWidth.toFloat()
                    val pushMs = 380
                    val navEasing = FastOutSlowInEasing

                    val isHome = currentScreen == "home"
                    val isSettings = currentScreen == "settings"
                    val isLogs = currentScreen == "logs"
                    val isAbout = currentScreen == "about"

                    // Animated offsets & scales for Home screen
                    val homeOffsetFraction by animateFloatAsState(
                        targetValue = when {
                            isHome -> 0f
                            isSettings || isAbout -> -0.15f
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
                            isAbout -> -0.15f
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
                        targetValue = if (isAbout) 0f else 1.0f,
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
                            onBack = { currentScreen = "settings" }
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
