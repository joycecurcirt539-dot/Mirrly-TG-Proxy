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

                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(220, easing = LinearOutSlowInEasing)) + scaleIn(initialScale = 0.97f, animationSpec = tween(220, easing = LinearOutSlowInEasing)))
                                .togetherWith(fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)) + scaleOut(targetScale = 1.02f, animationSpec = tween(180)))
                        },
                        label = "ScreenTransition",
                        modifier = Modifier.fillMaxSize().background(Color.Black)
                    ) { screen ->
                        when (screen) {
                            "home" -> HomeScreen(
                                onOpenSettings = {
                                    previousScreen = "home"
                                    currentScreen = "settings"
                                },
                                onOpenLogs = {
                                    currentScreen = "logs"
                                }
                            )
                            "settings" -> SettingsScreen(
                                onBack = { currentScreen = previousScreen }
                            )
                            "logs" -> LogsScreen(
                                onBack = { currentScreen = "home" },
                                onOpenSettings = {
                                    previousScreen = "logs"
                                    currentScreen = "settings"
                                }
                            )
                        }
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
