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

import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Надежный поиск Dialog Window с обходом дерева ViewParent.
 */
fun findDialogWindow(view: View): Window? {
    var current: ViewParent? = view.parent
    while (current != null) {
        if (current is DialogWindowProvider) {
            return current.window
        }
        current = current.parent
    }
    return null
}

/**
 * Хук для настройки системного размытия окна диалога (FLAG_BLUR_BEHIND)
 * с автоматическим переключением на темный акриловый фон на устройствах
 * без поддержки размытия (Android < 12, MIUI/HyperOS с выключенным blur, режим экономии энергии).
 */
@Composable
fun rememberDialogBlurState(
    blurRadiusPx: Int = 60
): Boolean {
    val view = LocalView.current
    var isBlurActive by remember { mutableStateOf(false) }

    DisposableEffect(view) {
        val window = findDialogWindow(view)
        if (window != null) {
            try {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val wm = view.context.getSystemService(WindowManager::class.java)
                    val isCrossWindowBlur = wm?.isCrossWindowBlurEnabled == true

                    if (isCrossWindowBlur) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        window.attributes = window.attributes.apply {
                            blurBehindRadius = blurRadiusPx
                        }
                        window.setDimAmount(0.35f)
                        isBlurActive = true
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        window.setDimAmount(0.65f)
                        isBlurActive = false
                    }

                    val blurListener = java.util.function.Consumer<Boolean> { enabled ->
                        if (enabled) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                            window.attributes = window.attributes.apply {
                                blurBehindRadius = blurRadiusPx
                            }
                            window.setDimAmount(0.35f)
                            isBlurActive = true
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                            window.setDimAmount(0.65f)
                            isBlurActive = false
                        }
                    }
                    wm?.addCrossWindowBlurEnabledListener(blurListener)

                    onDispose {
                        try {
                            wm?.removeCrossWindowBlurEnabledListener(blurListener)
                        } catch (_: Exception) {}
                    }
                } else {
                    window.setDimAmount(0.70f)
                    isBlurActive = false
                    onDispose {}
                }
            } catch (_: Exception) {
                onDispose {}
            }
        } else {
            onDispose {}
        }
    }

    return isBlurActive
}

/**
 * Унифицированный полноэкранный контейнер диалога с адаптивным фоном и парящими частицами:
 * - При поддержке WindowManager Blur: легкий затемняющий слой + системное оптическое размытие.
 * - Без аппаратного Blur (Android 8..11, недорогие GPU, Xiaomi/Samsung без поддержки): глубокий полупрозрачный акриловый слой (88% opacity).
 * - Поверх текста и всех элементов диалога парит деликатный слой интерактивных микро-частиц (CyberParticlesOverlay).
 */
@Composable
fun DialogBackdropBox(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    blurRadiusPx: Int = 60,
    showParticles: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val isBlurActive = rememberDialogBlurState(blurRadiusPx = blurRadiusPx)
    val scrimInteractionSource = remember { MutableInteractionSource() }
    val scrimColor = if (isBlurActive) {
        Color.Black.copy(alpha = 0.35f)
    } else {
        Color(0xFF060A13).copy(alpha = 0.88f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrimColor)
            .clickable(
                interactionSource = scrimInteractionSource,
                indication = null
            ) { onDismiss() }
            .then(modifier)
    ) {
        content()

        if (showParticles) {
            CyberParticlesOverlay(
                modifier = Modifier.fillMaxSize(),
                particleCount = 32,
                alphaMultiplier = 0.80f
            )
        }
    }
}
