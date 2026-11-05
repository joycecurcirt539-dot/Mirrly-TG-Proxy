/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui.icon

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.AnimatedWarpCloud
import com.mirrly.tgproxy.ui.AnimatedWarpGlider
import com.mirrly.tgproxy.ui.ProxyUiState
import com.mirrly.tgproxy.ui.theme.VpnUiState
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import com.mirrly.tgproxy.ui.theme.ProtocolColors
import com.mirrly.tgproxy.ui.theme.rememberAnimatedProtocolColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Преобразует состояние прокси в состояние VPN для универсального маппинга.
 */
fun ProxyUiState.toVpnUiState(): VpnUiState = when (this) {
    ProxyUiState.CONNECTED -> VpnUiState.CONNECTED
    ProxyUiState.CONNECTING -> VpnUiState.CONNECTING
    ProxyUiState.DISCONNECTING -> VpnUiState.DISCONNECTING
    ProxyUiState.DISCONNECTED -> VpnUiState.DISCONNECTED
}

/**
 * Единый универсальный компонент центральной кнопки режимов Mirrly TG Proxy.
 * Гарантирует 100% визуальный паритет между HomeScreen и экраном предпросмотра в Настройках.
 */
@Composable
fun CentralButtonView(
    protocol: String,
    presetId: String,
    customCode: String,
    state: ProxyUiState,
    isSocks5: Boolean = protocol.equals("socks5", ignoreCase = true),
    customColors: ProtocolColors? = null,
    scaleFactor: Float? = null,
    strokeWidthDp: Float? = null,
    glowIntensity: Float? = null,
    modifier: Modifier = Modifier
) {
    val app = MirrlyApplication.instance
    val prefScale by app.prefsManager.centralButtonScaleFlow.collectAsState()
    val prefStroke by app.prefsManager.centralButtonStrokeWidthFlow.collectAsState()
    val prefGlow by app.prefsManager.centralButtonGlowIntensityFlow.collectAsState()

    val effScale = scaleFactor ?: prefScale
    val effStroke = strokeWidthDp ?: prefStroke
    val effGlow = glowIntensity ?: prefGlow

    val isVpn = protocol.equals("vpn", ignoreCase = true)

    val scaledModifier = modifier.graphicsLayer {
        scaleX = effScale
        scaleY = effScale
    }

    when {
        isVpn -> {
            if (presetId == "custom" && customCode.isNotBlank()) {
                AnimatedDynamicVectorButton(
                    pathData = customCode,
                    state = state,
                    protocolColors = customColors,
                    strokeWidthDp = effStroke,
                    glowIntensity = effGlow,
                    modifier = scaledModifier
                )
            } else {
                val vpnPalette = customColors ?: rememberAnimatedProtocolColors(isVpn = true)
                AnimatedWarpCloud(
                    vpnColors = vpnPalette,
                    state = state.toVpnUiState(),
                    strokeWidthDp = effStroke,
                    modifier = scaledModifier
                )
            }
        }
        presetId == "warp_glider" -> {
            AnimatedWarpGlider(
                state = state,
                isSocks5 = isSocks5,
                customColors = customColors,
                strokeWidthDp = effStroke,
                modifier = scaledModifier
            )
        }
        presetId == "custom" && customCode.isNotBlank() -> {
            AnimatedDynamicVectorButton(
                pathData = customCode,
                state = state,
                protocolColors = customColors,
                strokeWidthDp = effStroke,
                glowIntensity = effGlow,
                modifier = scaledModifier
            )
        }
        else -> {
            // По умолчанию (default) для MTProto и SOCKS5: официальный маскот Mirrly (котик + самолётик)
            AnimatedMirrlyLogo(
                state = state,
                protocolColors = customColors,
                strokeWidthDp = effStroke,
                glowIntensity = effGlow,
                modifier = scaledModifier
            )
        }
    }
}

private data class MascotParticle(
    val relX: Float,
    val speed: Float,
    val radiusDp: Float,
    val phase: Float,
    val alpha: Float
)

private data class MascotScaledPaths(
    val planePath: Path,
    val wingShadowPath: Path,
    val catPath: Path,
    val eyesPath: Path
)

private const val MIRRLY_MASCOT_CAT_PATH = "M385.5 0L395.5 1L403 7.5L410 20.5L416 42.5L418 60.5L419 61.5L419 68.5L420 69.5L420 78.5L431 97.5L435 110.5Q433.9 117.5 436.5 121Q441.7 115.7 452.5 116Q466.2 118.3 473 127.5L477 132.5L477 135L198.5 242L197 240.5L197 225.5L199 217.5L206.5 206Q212.8 199.8 225 199.5Q214.9 189.6 209 175.5L205 160.5L204 137.5L205 136.5L206 121.5L210 104.5L209 103.5L207 76.5L206 75.5L206 45.5L209 33.5L215.5 24Q219.6 20.1 228.5 21Q240.8 23.7 249.5 30L272.5 48Q284.9 40.4 301.5 37Q306.3 38.3 307.5 36L337.5 36Q339 38.5 344.5 37L370.5 9Q376.5 3 385.5 0ZM367 100L360 103Q352 108 349 118Q347 131 353 137Q358 144 369 146Q381 146 388 140Q393 135 395 126L394 116L390 109Q384 99 367 100ZM266 112L257 116Q249 121 247 131Q245 145 252 152Q257 158 268 160Q278 160 284 156Q292 151 294 139Q294 128 290 123Q286 116 279 113Q274 111 266 112ZM328 144L324 148Q322 145 318 146Q315 147 316 151Q319 156 327 156L332 147Q331 144 328 144Z"

private const val MIRRLY_MASCOT_PLANE_PATH = "M604.5 87Q623.4 86.6 631 97.5Q637.6 105.9 637 121.5L626 169.5L624 183.5L622 188.5L619 207.5L617 212.5L615 226.5L613 231.5L611 245.5L609 250.5L607 264.5L605 269.5L603 283.5L601 288.5L599 302.5L597 307.5L595 321.5L593 326.5L591 340.5L589 345.5L587 359.5L585 364.5L583 378.5L581 383.5L578 402.5L576 407.5L574 421.5L572 426.5L570 440.5L568 445.5L566 459.5L564 464.5L539 587.5Q535.6 604.6 524.5 614L516.5 618L508.5 619Q507 616.5 501.5 618L490.5 614L350.5 512L342.5 507L337.5 507L252.5 583L246.5 584L241 579.5L240 574.5L241 573.5L241 562.5L242 561.5L246 502.5L247 501.5L251 442.5L252 438.5L260.5 430L432.5 276L523 191.5L525 185.5Q521.9 181.1 512.5 183L169.5 397L163.5 398L29.5 354L26.5 354Q13.8 349.4 5 342L1 334.5L1 329.5Q3.7 320.2 11.5 316L17.5 312L185.5 247L222.5 234L575.5 96L597.5 88Q603 89.5 604.5 87Z"

private const val MIRRLY_MASCOT_WING_SHADOW_PATH = "M604.5 87 L246.5 501.5 L432.5 276 Z M246.5 501.5 L252.5 583 L350.5 512 Z"

private const val MIRRLY_MASCOT_EYES_PATH = "M367 100L360 103Q352 108 349 118Q347 131 353 137Q358 144 369 146Q381 146 388 140Q393 135 395 126L394 116L390 109Q384 99 367 100ZM266 112L257 116Q249 121 247 131Q245 145 252 152Q257 158 268 160Q278 160 284 156Q292 151 294 139Q294 128 290 123Q286 116 279 113Q274 111 266 112Z"

/**
 * Кинетический рендерер фирменного маскота Mirrly (котик над бумажным самолётиком Telegram).
 * Поддерживает раздельное динамическое окрашивание, микро-частицы протокола, кинетическую левитацию
 * и четкое визуальное различие между выключенным и включенным состояниями.
 */
@Composable
fun AnimatedMirrlyLogo(
    state: ProxyUiState,
    protocolColors: ProtocolColors? = null,
    strokeWidthDp: Float = 1.5f,
    glowIntensity: Float = 1.0f,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isAppResumed = false
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> isAppResumed = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val app = MirrlyApplication.instance
    val isAnimationsDisabled by app.prefsManager.animationsDisabledFlow.collectAsState()

    val timeState = produceState(initialValue = 0L, isAppResumed, isAnimationsDisabled) {
        if (!isAppResumed || isAnimationsDisabled) return@produceState
        val startNano = System.nanoTime() - value
        while (isAppResumed && !isAnimationsDisabled) {
            withFrameNanos { frameTimeNanos ->
                value = frameTimeNanos - startNano
            }
        }
    }

    val defaultColors = LocalProtocolColors.current
    val protoColors = protocolColors ?: defaultColors
    val isActive = state == ProxyUiState.CONNECTED || state == ProxyUiState.CONNECTING

    val targetLiftDp = when (state) {
        ProxyUiState.CONNECTING -> (-4.0).dp
        ProxyUiState.CONNECTED -> (-2.5).dp
        ProxyUiState.DISCONNECTING -> (-0.5).dp
        ProxyUiState.DISCONNECTED -> 0.0.dp
    }

    val animatedLiftDp by animateDpAsState(
        targetValue = targetLiftDp,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
        label = "mirrlyLogoLift"
    )

    // Цвета самолётика
    val targetPlaneFill = when (state) {
        ProxyUiState.CONNECTED -> protoColors.primary
        ProxyUiState.CONNECTING -> protoColors.light
        ProxyUiState.DISCONNECTING -> protoColors.primary.copy(alpha = 0.50f)
        ProxyUiState.DISCONNECTED -> Color(0xFF222733)
    }
    val planeFillColor by animateColorAsState(targetPlaneFill, tween(400), label = "planeFill")

    val targetPlaneStroke = when (state) {
        ProxyUiState.CONNECTED -> protoColors.light
        ProxyUiState.CONNECTING -> Color.White
        ProxyUiState.DISCONNECTING -> protoColors.light.copy(alpha = 0.60f)
        ProxyUiState.DISCONNECTED -> Color(0xFF3E4758)
    }
    val planeStrokeColor by animateColorAsState(targetPlaneStroke, tween(400), label = "planeStroke")

    val targetWingShadow = when (state) {
        ProxyUiState.CONNECTED -> protoColors.primary.copy(alpha = 0.65f)
        ProxyUiState.CONNECTING -> protoColors.light.copy(alpha = 0.70f)
        ProxyUiState.DISCONNECTING -> protoColors.primary.copy(alpha = 0.35f)
        ProxyUiState.DISCONNECTED -> Color(0xFF191D26)
    }
    val wingShadowColor by animateColorAsState(targetWingShadow, tween(400), label = "wingShadow")

    // Цвета котика
    val targetCatFill = when (state) {
        ProxyUiState.CONNECTED -> Color(0xFF0C0E14)
        ProxyUiState.CONNECTING -> Color(0xFF0E1016)
        ProxyUiState.DISCONNECTING -> Color(0xFF12151D)
        ProxyUiState.DISCONNECTED -> Color(0xFF141720)
    }
    val catFillColor by animateColorAsState(targetCatFill, tween(400), label = "catFill")

    val targetCatStroke = when (state) {
        ProxyUiState.CONNECTED -> protoColors.primary.copy(alpha = 0.60f)
        ProxyUiState.CONNECTING -> protoColors.light.copy(alpha = 0.75f)
        ProxyUiState.DISCONNECTING -> protoColors.primary.copy(alpha = 0.30f)
        ProxyUiState.DISCONNECTED -> Color(0xFF2C3242)
    }
    val catStrokeColor by animateColorAsState(targetCatStroke, tween(400), label = "catStroke")

    val targetEyes = when (state) {
        ProxyUiState.CONNECTED -> Color.White
        ProxyUiState.CONNECTING -> Color.White.copy(alpha = 0.90f)
        ProxyUiState.DISCONNECTING -> Color(0xFF7A889B)
        ProxyUiState.DISCONNECTED -> Color(0xFF4A5568)
    }
    val eyesColor by animateColorAsState(targetEyes, tween(400), label = "eyesColor")

    val mascotParticles = remember {
        listOf(
            MascotParticle(relX = -0.32f, speed = 0.55f, radiusDp = 2.0f, phase = 0.12f, alpha = 0.70f),
            MascotParticle(relX = -0.22f, speed = 0.75f, radiusDp = 2.5f, phase = 0.48f, alpha = 0.85f),
            MascotParticle(relX = -0.12f, speed = 0.45f, radiusDp = 1.8f, phase = 0.82f, alpha = 0.60f),
            MascotParticle(relX = -0.02f, speed = 0.85f, radiusDp = 2.8f, phase = 0.25f, alpha = 0.90f),
            MascotParticle(relX = 0.08f,  speed = 0.60f, radiusDp = 2.2f, phase = 0.63f, alpha = 0.75f),
            MascotParticle(relX = 0.18f,  speed = 0.80f, radiusDp = 2.4f, phase = 0.34f, alpha = 0.85f),
            MascotParticle(relX = 0.28f,  speed = 0.50f, radiusDp = 1.8f, phase = 0.91f, alpha = 0.65f),
            MascotParticle(relX = 0.36f,  speed = 0.70f, radiusDp = 2.0f, phase = 0.15f, alpha = 0.70f),
            MascotParticle(relX = -0.26f, speed = 0.90f, radiusDp = 1.6f, phase = 0.71f, alpha = 0.60f),
            MascotParticle(relX = -0.08f, speed = 0.65f, radiusDp = 2.2f, phase = 0.05f, alpha = 0.80f),
            MascotParticle(relX = 0.02f,  speed = 0.95f, radiusDp = 2.6f, phase = 0.52f, alpha = 0.90f),
            MascotParticle(relX = 0.14f,  speed = 0.58f, radiusDp = 1.9f, phase = 0.78f, alpha = 0.65f),
            MascotParticle(relX = 0.24f,  speed = 0.82f, radiusDp = 2.3f, phase = 0.22f, alpha = 0.80f),
            MascotParticle(relX = -0.16f, speed = 0.72f, radiusDp = 2.1f, phase = 0.39f, alpha = 0.75f)
        )
    }

    val basePlane = remember { PathParser.createPathFromPathData(MIRRLY_MASCOT_PLANE_PATH) }
    val baseWing = remember { PathParser.createPathFromPathData(MIRRLY_MASCOT_WING_SHADOW_PATH) }
    val baseCat = remember { PathParser.createPathFromPathData(MIRRLY_MASCOT_CAT_PATH) }
    val baseEyes = remember { PathParser.createPathFromPathData(MIRRLY_MASCOT_EYES_PATH) }

    val density = LocalDensity.current
    val liftPx = with(density) { animatedLiftDp.toPx() }
    val strokeWidthPx = remember(density, strokeWidthDp) { with(density) { strokeWidthDp.dp.toPx() } }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val t = if (!isAnimationsDisabled) timeState.value / 1_000_000_000f else 0f

        Canvas(modifier = Modifier.fillMaxSize()) {
            val diameter = size.minDimension

            // Кинетическая левитация только в активном состоянии
            val floatAmp = if (isActive) diameter * 0.022f else 0f
            val floatFreq = 2.0f
            val driftY = if (isActive) sin(t * floatFreq) * floatAmp else 0f
            val rumbleY = if (state == ProxyUiState.CONNECTING) sin(t * 36f) * (diameter * 0.007f) else 0f
            val rollDeg = if (isActive) sin(t * 1.4f) * 2.0f else 0f

            val effectiveCenter = Offset(size.width / 2f, size.height / 2f + liftPx + driftY + rumbleY)

            // Поток энергетических микро-частиц протокола при активном соединении
            if (isActive && !isAnimationsDisabled) {
                for (p in mascotParticles) {
                    val prog = (t * p.speed + p.phase) % 1.0f
                    val pY = effectiveCenter.y + diameter * 0.32f - prog * (diameter * 0.65f)
                    val pX = effectiveCenter.x + diameter * p.relX + sin(t * 2.8f + p.phase * 6.28f) * (diameter * 0.024f)
                    val pAlpha = (sin(prog * Math.PI.toFloat()) * p.alpha).coerceIn(0f, 1f)
                    val pRadius = p.radiusDp.dp.toPx()

                    drawCircle(
                        color = protoColors.primary.copy(alpha = (pAlpha * 0.85f).coerceIn(0f, 1f)),
                        radius = pRadius,
                        center = Offset(pX, pY)
                    )
                    drawCircle(
                        color = protoColors.light.copy(alpha = pAlpha),
                        radius = pRadius * 0.55f,
                        center = Offset(pX, pY)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = (pAlpha * 0.95f).coerceIn(0f, 1f)),
                        radius = pRadius * 0.28f,
                        center = Offset(pX, pY)
                    )
                }
            }

            // Масштабирование путей композиции маскота под геометрию Canvas
            val srcBounds = RectF(1f, 0f, 638f, 619f)
            val paddingPx = diameter * 0.08f
            val availW = size.width - 2 * paddingPx
            val availH = size.height - 2 * paddingPx
            val scale = (availW / srcBounds.width()).coerceAtMost(availH / srcBounds.height())
            val dx = (size.width - srcBounds.width() * scale) / 2f - srcBounds.left * scale
            val dy = (size.height - srcBounds.height() * scale) / 2f - srcBounds.top * scale + liftPx + driftY + rumbleY

            val transformMatrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(dx, dy)
            }

            val planePath = android.graphics.Path(basePlane).apply { transform(transformMatrix) }.asComposePath()
            val wingPath = android.graphics.Path(baseWing).apply { transform(transformMatrix) }.asComposePath()
            val catPath = android.graphics.Path(baseCat).apply { transform(transformMatrix) }.asComposePath()
            val eyesPath = android.graphics.Path(baseEyes).apply { transform(transformMatrix) }.asComposePath()

            rotate(degrees = rollDeg, pivot = effectiveCenter) {
                // 1. Самолётик: заливка основного тела
                drawPath(path = planePath, color = planeFillColor)

                // 2. Самолётик: теневая грань складки крыла
                drawPath(path = wingPath, color = wingShadowColor)

                // 3. Неоновый контурный ореол вокруг самолётика при свечении
                if (isActive && glowIntensity > 0f) {
                    drawPath(
                        path = planePath,
                        color = planeStrokeColor.copy(alpha = (0.24f * glowIntensity).coerceIn(0f, 0.8f)),
                        style = Stroke(
                            width = strokeWidthPx * (2.2f + glowIntensity * 0.8f),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // 4. Самолётик: четкий контур
                drawPath(
                    path = planePath,
                    color = planeStrokeColor,
                    style = Stroke(
                        width = strokeWidthPx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // 5. Кот: заливка силуэта
                drawPath(path = catPath, color = catFillColor)

                // 6. Кот: глаза
                drawPath(path = eyesPath, color = eyesColor)
                if (isActive) {
                    drawPath(
                        path = eyesPath,
                        color = Color.White.copy(alpha = 0.50f),
                        style = Stroke(
                            width = strokeWidthPx * 1.4f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // 7. Кот: контур
                drawPath(
                    path = catPath,
                    color = catStrokeColor,
                    style = Stroke(
                        width = strokeWidthPx * 0.85f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

/**
 * Кинетический рендерер кастомных и альтернативных векторных сплайнов (SVG / pathData).
 * Обладает аэродинамической левитацией, неоновым свечением и пружинной физикой MirrlyUI.
 */
@Composable
fun AnimatedDynamicVectorButton(
    pathData: String,
    state: ProxyUiState,
    protocolColors: ProtocolColors? = null,
    strokeWidthDp: Float = 2.0f,
    glowIntensity: Float = 1.0f,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isAppResumed = false
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> isAppResumed = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val app = MirrlyApplication.instance
    val isAnimationsDisabled by app.prefsManager.animationsDisabledFlow.collectAsState()

    val timeState = produceState(initialValue = 0L, isAppResumed, isAnimationsDisabled) {
        if (!isAppResumed || isAnimationsDisabled) return@produceState
        val startNano = System.nanoTime() - value
        while (isAppResumed && !isAnimationsDisabled) {
            withFrameNanos { frameTimeNanos ->
                value = frameTimeNanos - startNano
            }
        }
    }

    val defaultColors = LocalProtocolColors.current
    val protoColors = protocolColors ?: defaultColors

    val targetStrokeColor = when (state) {
        ProxyUiState.CONNECTING -> protoColors.light
        ProxyUiState.CONNECTED -> protoColors.primary
        ProxyUiState.DISCONNECTING -> protoColors.primary.copy(alpha = 0.60f)
        ProxyUiState.DISCONNECTED -> protoColors.primary.copy(alpha = 0.80f)
    }

    val animatedColor by animateColorAsState(
        targetValue = targetStrokeColor,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "dynamicVectorColor"
    )

    val targetLiftDp = when (state) {
        ProxyUiState.CONNECTING -> (-3.5).dp
        ProxyUiState.CONNECTED -> (-2.0).dp
        ProxyUiState.DISCONNECTING -> (-0.5).dp
        ProxyUiState.DISCONNECTED -> (-1.0).dp
    }

    val animatedLiftDp by animateDpAsState(
        targetValue = targetLiftDp,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
        label = "dynamicVectorLift"
    )

    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidthDp) { with(density) { strokeWidthDp.dp.toPx() } }
    val liftPx = with(density) { animatedLiftDp.toPx() }

    Canvas(modifier = modifier) {
        val t = if (!isAnimationsDisabled) timeState.value / 1_000_000_000f else 0f
        val diameter = size.minDimension

        // Аэродинамическая левитация
        val isActive = state == ProxyUiState.CONNECTED || state == ProxyUiState.CONNECTING
        val floatAmp = if (isActive) diameter * 0.022f else diameter * 0.012f
        val floatFreq = if (isActive) 2.0f else 1.2f
        val driftY = sin(t * floatFreq) * floatAmp

        // Микровибрация при подключении
        val rumbleY = if (state == ProxyUiState.CONNECTING) sin(t * 36f) * (diameter * 0.007f) else 0f
        val rollAngle = if (state == ProxyUiState.CONNECTED) sin(t * 1.4f) * 2.0f else sin(t * 1.0f) * 1.0f

        val effectiveCenter = Offset(size.width / 2f, size.height / 2f + liftPx + driftY + rumbleY)

        val path = SvgPathDataParser.createScaledComposePath(
            rawInput = pathData,
            targetSize = size,
            paddingPx = diameter * 0.12f
        ) ?: return@Canvas

        rotate(degrees = rollAngle, pivot = effectiveCenter) {
            // Неоновый ореол при активном соединении с настраиваемой интенсивностью
            if (isActive && glowIntensity > 0f) {
                drawPath(
                    path = path,
                    color = animatedColor.copy(alpha = (0.24f * glowIntensity).coerceIn(0f, 0.8f)),
                    style = Stroke(
                        width = strokeWidthPx * (2.0f + glowIntensity * 0.8f),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // Основной четкий контур
            drawPath(
                path = path,
                color = animatedColor,
                style = Stroke(
                    width = strokeWidthPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}
