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

package com.mirrly.tgproxy.ui.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Window Width Category according to Material Design 3 guidelines.
 */
enum class WindowWidthClass {
    COMPACT,   // Phones in portrait (< 600dp)
    MEDIUM,    // Small tablets, foldables (600dp - 839dp)
    EXPANDED   // Tablets, desktop / large displays (>= 840dp)
}

/**
 * Adaptive metrics computed from user display parameters and system font scaling.
 */
@Immutable
data class AdaptiveMetrics(
    val screenWidthDp: Dp,
    val screenHeightDp: Dp,
    val density: Float,
    val fontScale: Float,
    val scaleFactor: Float,
    val windowWidthClass: WindowWidthClass,
    val isCompact: Boolean,
    val isTabletOrLandscape: Boolean,
    val horizontalPadding: Dp,
    val cardCornerRadius: Dp
)

val LocalAdaptiveMetrics = compositionLocalOf {
    AdaptiveMetrics(
        screenWidthDp = 390.dp,
        screenHeightDp = 844.dp,
        density = 2.75f,
        fontScale = 1.0f,
        scaleFactor = 1.0f,
        windowWidthClass = WindowWidthClass.COMPACT,
        isCompact = false,
        isTabletOrLandscape = false,
        horizontalPadding = 18.dp,
        cardCornerRadius = 24.dp
    )
}

/**
 * Provides responsive metrics to the Composable tree.
 */
@Composable
fun ProvideAdaptiveMetrics(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val densityObj = LocalDensity.current

    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val density = densityObj.density
    val fontScale = densityObj.fontScale

    val windowWidthClass = when {
        screenWidth < 600.dp -> WindowWidthClass.COMPACT
        screenWidth < 840.dp -> WindowWidthClass.MEDIUM
        else -> WindowWidthClass.EXPANDED
    }

    // Base reference width: 390dp (Standard modern smartphone)
    // Scale factor smoothly clamped between 0.86f (compact devices e.g. 320-340dp) and 1.20f (larger displays)
    val rawScale = (screenWidth.value / 390f)
    val scaleFactor = rawScale.coerceIn(0.86f, 1.20f)
    val isCompact = screenWidth < 360.dp
    val isTabletOrLandscape = screenWidth >= 600.dp

    val horizontalPadding = when {
        isCompact -> 14.dp
        isTabletOrLandscape -> 24.dp
        else -> 18.dp
    }

    val cardCornerRadius = when {
        isCompact -> 20.dp
        else -> 24.dp
    }

    val metrics = remember(screenWidth, screenHeight, density, fontScale, scaleFactor, windowWidthClass) {
        AdaptiveMetrics(
            screenWidthDp = screenWidth,
            screenHeightDp = screenHeight,
            density = density,
            fontScale = fontScale,
            scaleFactor = scaleFactor,
            windowWidthClass = windowWidthClass,
            isCompact = isCompact,
            isTabletOrLandscape = isTabletOrLandscape,
            horizontalPadding = horizontalPadding,
            cardCornerRadius = cardCornerRadius
        )
    }

    CompositionLocalProvider(LocalAdaptiveMetrics provides metrics) {
        content()
    }
}

/**
 * Modifier to apply responsive horizontal padding depending on screen width.
 */
@Composable
fun Modifier.adaptiveContentPadding(
    vertical: Dp = 0.dp
): Modifier {
    val metrics = LocalAdaptiveMetrics.current
    return this.padding(horizontal = metrics.horizontalPadding, vertical = vertical)
}

/**
 * Container modifier that centers and limits content width on tablets and landscape screens.
 */
@Composable
fun Modifier.adaptiveContainerWidth(
    maxWidth: Dp = 640.dp
): Modifier {
    val metrics = LocalAdaptiveMetrics.current
    return if (metrics.isTabletOrLandscape) {
        this
            .fillMaxWidth()
            .widthIn(max = maxWidth)
    } else {
        this.fillMaxWidth()
    }
}

/**
 * Scales DP values gracefully based on user's effective screen width.
 */
@Composable
fun Dp.adaptive(): Dp {
    val metrics = LocalAdaptiveMetrics.current
    return (this.value * metrics.scaleFactor).dp
}

/**
 * Scales font sizes gracefully, taking into account font scale and narrow screens.
 * Clamps large expansion to prevent breaking compact UI elements (chips, badges).
 */
@Composable
fun TextUnit.adaptive(maxFontScaleFactor: Float = 1.35f): TextUnit {
    val metrics = LocalAdaptiveMetrics.current
    val effectiveScale = if (metrics.fontScale > maxFontScaleFactor) {
        maxFontScaleFactor / metrics.fontScale
    } else {
        1.0f
    }
    val widthAdjust = if (metrics.isCompact) 0.92f else 1.0f
    return (this.value * effectiveScale * widthAdjust).sp
}

/**
 * Helper to compute an adaptive SP value directly from float.
 */
@Composable
fun adaptiveSp(baseSp: Float, maxFontScaleFactor: Float = 1.35f): TextUnit {
    return baseSp.sp.adaptive(maxFontScaleFactor)
}
