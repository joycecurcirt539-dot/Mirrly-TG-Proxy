package com.mirrly.tgproxy.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Deep True Pure Black AMOLED Android Palette
val AmoledBackground = Color(0xFF000000)
val AmoledSurfaceLow = Color(0xFF070709)
val AmoledSurface = Color(0xFF0D0D12)
val AmoledSurfaceHigh = Color(0xFF14141B)
val AmoledBorder = Color(0xFF1F1F2B)

val ActiveGreenLed = Color(0xFF00F5D4) // Neon Cyan / Emerald Accent
val ActiveGreenGlow = Color(0x3D00F5D4) // Neon Cyan Glow
val InactiveGrayLed = Color(0xFF3A4256)
val TextWhite = Color(0xFFFFFFFF)
val TextMuted = Color(0xFF7E8B9B)
val MinimalAccent = Color(0xFF00F5D4)

private val AmoledDarkColorScheme = darkColorScheme(
    primary = TextWhite,
    secondary = TextMuted,
    tertiary = ActiveGreenLed,
    background = AmoledBackground,
    surface = AmoledBackground,
    surfaceContainerLow = AmoledSurfaceLow,
    surfaceContainer = AmoledSurface,
    surfaceContainerHigh = AmoledSurfaceHigh,
    outline = AmoledBorder,
    outlineVariant = AmoledBorder,
    onPrimary = Color.Black,
    onBackground = TextWhite,
    onSurface = TextWhite,
    onSurfaceVariant = TextMuted
)

@Composable
fun MirrlyTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.BLACK
            window.navigationBarColor = android.graphics.Color.BLACK
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = AmoledDarkColorScheme,
        content = content
    )
}
