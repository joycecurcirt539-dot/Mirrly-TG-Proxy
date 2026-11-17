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

package com.mirrly.tgproxy.ui.icon

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Стандарт процедурных векторных иконок MirrlyUI (mirrly-icons).
 * Категория: «Сообщество, Зал Славы, донаты и партнерские сервисы» (15 компонентов).
 *
 * Архитектурные правила стандарта:
 * 1. Outline-Only: штрих 1.5dp, без глухих заливок, скругленные соединения.
 * 2. Zero-Allocation (120 FPS): кэшированные remember { Path() }, запрет создания объектов в цикле кадра.
 * 3. Аутентичность партнерских логотипов: переосмысление брендовых знаков в каркасном стиле MirrlyUI.
 * 4. Zero Color Hardcode: привязка к LocalProtocolColors.current.primary.
 * 5. Battery Guard: остановка анимационных таймлайнов при сворачивании приложения.
 */

// =============================================================================
// ВСПОМОГАТЕЛЬНЫЙ BATTERY GUARD ХЕЛПЕР
// =============================================================================

@Composable
private fun rememberIconAnimationProgress(isAnimated: Boolean): Float {
    if (!isAnimated) return 0f

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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!isAppResumed) return 0f

    val infiniteTransition = rememberInfiniteTransition(label = "MirrlyIconTransition")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing)
        ),
        label = "IconProgress"
    )
    return progress
}

// =============================================================================
// 1. IC_USER — КОНТУРНЫЙ СИЛУЭТ ПЛЕЧ И ШЛЕМА ПИЛОТА В СТИЛЕ КИБЕРПАНК
// =============================================================================

@Composable
fun MirrlyUserIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val helmetPath = remember { Path() }
    val visorPath = remember { Path() }
    val shouldersPath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val breathing = if (isAnimated) sin(animProgress * 2f * PI.toFloat()) * (dim * 0.015f) else 0f

        // 1. Фасетный контур шлема пилота
        helmetPath.reset()
        helmetPath.moveTo(cx, cy - 0.40f * dim + breathing)
        helmetPath.lineTo(cx + 0.16f * dim, cy - 0.34f * dim + breathing)
        helmetPath.lineTo(cx + 0.20f * dim, cy - 0.20f * dim + breathing)
        helmetPath.lineTo(cx + 0.18f * dim, cy - 0.04f * dim + breathing)
        helmetPath.lineTo(cx + 0.10f * dim, cy + 0.06f * dim + breathing)
        helmetPath.lineTo(cx - 0.10f * dim, cy + 0.06f * dim + breathing)
        helmetPath.lineTo(cx - 0.18f * dim, cy - 0.04f * dim + breathing)
        helmetPath.lineTo(cx - 0.20f * dim, cy - 0.20f * dim + breathing)
        helmetPath.lineTo(cx - 0.16f * dim, cy - 0.34f * dim + breathing)
        helmetPath.close()

        // Антенный гребень сенсора шлема
        helmetPath.moveTo(cx, cy - 0.45f * dim + breathing)
        helmetPath.lineTo(cx, cy - 0.40f * dim + breathing)
        drawPath(path = helmetPath, color = tint, style = stroke)

        // 2. Аэродинамический кибер-визор
        visorPath.reset()
        visorPath.moveTo(cx - 0.16f * dim, cy - 0.19f * dim + breathing)
        visorPath.lineTo(cx - 0.07f * dim, cy - 0.15f * dim + breathing)
        visorPath.lineTo(cx, cy - 0.16f * dim + breathing)
        visorPath.lineTo(cx + 0.07f * dim, cy - 0.15f * dim + breathing)
        visorPath.lineTo(cx + 0.16f * dim, cy - 0.19f * dim + breathing)
        visorPath.lineTo(cx + 0.14f * dim, cy - 0.11f * dim + breathing)
        visorPath.lineTo(cx, cy - 0.09f * dim + breathing)
        visorPath.lineTo(cx - 0.14f * dim, cy - 0.11f * dim + breathing)
        visorPath.close()
        drawPath(path = visorPath, color = tint, style = stroke)

        // 3. Воротник-горжет и контурные наплечники летного скафандра
        shouldersPath.reset()
        // Воротник
        shouldersPath.moveTo(cx - 0.13f * dim, cy + 0.11f * dim)
        shouldersPath.lineTo(cx, cy + 0.15f * dim)
        shouldersPath.lineTo(cx + 0.13f * dim, cy + 0.11f * dim)

        // Левое плечо
        shouldersPath.moveTo(cx - 0.13f * dim, cy + 0.15f * dim)
        shouldersPath.lineTo(cx - 0.38f * dim, cy + 0.24f * dim)
        shouldersPath.lineTo(cx - 0.42f * dim, cy + 0.42f * dim)

        // Правое плечо
        shouldersPath.moveTo(cx + 0.13f * dim, cy + 0.15f * dim)
        shouldersPath.lineTo(cx + 0.38f * dim, cy + 0.24f * dim)
        shouldersPath.lineTo(cx + 0.42f * dim, cy + 0.42f * dim)

        // Линия грудного шва брони
        shouldersPath.moveTo(cx, cy + 0.15f * dim)
        shouldersPath.lineTo(cx, cy + 0.40f * dim)

        // Ребра жесткости бронепластин
        shouldersPath.moveTo(cx - 0.18f * dim, cy + 0.28f * dim)
        shouldersPath.lineTo(cx, cy + 0.33f * dim)
        shouldersPath.lineTo(cx + 0.18f * dim, cy + 0.28f * dim)

        drawPath(path = shouldersPath, color = tint, style = stroke)
    }
}

@Composable
fun IcUser(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyUserIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 2. IC_HEART — ПОЛИГОНАЛЬНОЕ КОНТУРНОЕ СЕРДЦЕ С ГРАНЕНЫМИ ШТРИХАМИ
// =============================================================================

@Composable
fun MirrlyHeartIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val outerPath = remember { Path() }
    val facetsPath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val pulseScale = if (isAnimated) 1.0f + sin(animProgress * 4f * PI.toFloat()) * 0.04f else 1.0f

        val d = dim * pulseScale

        // Вершины полигонального контура (кристаллические грани)
        val topCleftY = cy - 0.24f * d
        val leftPeakX = cx - 0.24f * d
        val leftPeakY = cy - 0.38f * d
        val leftShoulderX = cx - 0.42f * d
        val leftShoulderY = cy - 0.22f * d
        val leftMidX = cx - 0.42f * d
        val leftMidY = cy + 0.02f * d
        val leftLowerX = cx - 0.26f * d
        val leftLowerY = cy + 0.24f * d
        val bottomApexY = cy + 0.42f * d

        val rightPeakX = cx + 0.24f * d
        val rightPeakY = cy - 0.38f * d
        val rightShoulderX = cx + 0.42f * d
        val rightShoulderY = cy - 0.22f * d
        val rightMidX = cx + 0.42f * d
        val rightMidY = cy + 0.02f * d
        val rightLowerX = cx + 0.26f * d
        val rightLowerY = cy + 0.24f * d

        val coreY = cy - 0.02f * d

        // Внешний замкнутый многоугольник
        outerPath.reset()
        outerPath.moveTo(cx, topCleftY)
        outerPath.lineTo(leftPeakX, leftPeakY)
        outerPath.lineTo(leftShoulderX, leftShoulderY)
        outerPath.lineTo(leftMidX, leftMidY)
        outerPath.lineTo(leftLowerX, leftLowerY)
        outerPath.lineTo(cx, bottomApexY)
        outerPath.lineTo(rightLowerX, rightLowerY)
        outerPath.lineTo(rightMidX, rightMidY)
        outerPath.lineTo(rightShoulderX, rightShoulderY)
        outerPath.lineTo(rightPeakX, rightPeakY)
        outerPath.close()
        drawPath(path = outerPath, color = tint, style = stroke)

        // Внутренние лучи жесткости и ребра кристалла
        facetsPath.reset()
        // Центральный вертикальный киль
        facetsPath.moveTo(cx, topCleftY)
        facetsPath.lineTo(cx, coreY)
        facetsPath.lineTo(cx, bottomApexY)

        // Левые радиальные ребра из ядра
        facetsPath.moveTo(cx, coreY)
        facetsPath.lineTo(leftPeakX, leftPeakY)
        facetsPath.moveTo(cx, coreY)
        facetsPath.lineTo(leftShoulderX, leftShoulderY)
        facetsPath.moveTo(cx, coreY)
        facetsPath.lineTo(leftMidX, leftMidY)
        facetsPath.moveTo(cx, coreY)
        facetsPath.lineTo(leftLowerX, leftLowerY)

        // Правые радиальные ребра из ядра
        facetsPath.moveTo(cx, coreY)
        facetsPath.lineTo(rightPeakX, rightPeakY)
        facetsPath.moveTo(cx, coreY)
        facetsPath.lineTo(rightShoulderX, rightShoulderY)
        facetsPath.moveTo(cx, coreY)
        facetsPath.lineTo(rightMidX, rightMidY)
        facetsPath.moveTo(cx, coreY)
        facetsPath.lineTo(rightLowerX, rightLowerY)

        drawPath(path = facetsPath, color = tint, style = stroke)
    }
}

@Composable
fun IcHeart(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyHeartIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 3. IC_STAR — ГРАНЕНАЯ 5-КОНЕЧНАЯ ЗВЕЗДА С ВНУТРЕННИМИ ОСЕВЫМИ ЛУЧАМИ ЖЕСТКОСТИ
// =============================================================================

@Composable
fun MirrlyStarIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val starOutlinePath = remember { Path() }
    val axialRaysPath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val rotationAngle = if (isAnimated) animProgress * 2f * PI.toFloat() * 0.2f else 0f

        val outerRadius = dim * 0.42f
        val innerRadius = outerRadius * 0.44f

        starOutlinePath.reset()
        axialRaysPath.reset()

        val baseAngle = -PI.toFloat() / 2f + rotationAngle
        val step = PI.toFloat() / 5f

        // Построение 5 вершин и 5 впадин
        for (i in 0 until 10) {
            val angle = baseAngle + i * step
            val r = if (i % 2 == 0) outerRadius else innerRadius
            val px = cx + cos(angle) * r
            val py = cy + sin(angle) * r

            if (i == 0) {
                starOutlinePath.moveTo(px, py)
            } else {
                starOutlinePath.lineTo(px, py)
            }

            // Осевые лучи жесткости из центра к каждой вершине и каждой впадине
            axialRaysPath.moveTo(cx, cy)
            axialRaysPath.lineTo(px, py)
        }
        starOutlinePath.close()

        drawPath(path = starOutlinePath, color = tint, style = stroke)
        drawPath(path = axialRaysPath, color = tint, style = stroke)
    }
}

@Composable
fun IcStar(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyStarIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 4. IC_DONATE — ПАРЯЩИЙ ГРАНЕНЫЙ КРИСТАЛЛ НАД КОНТУРНОЙ ЛАДОНЬЮ ПРИЕМА ЭНЕРГИИ
// =============================================================================

@Composable
fun MirrlyDonateIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val crystalPath = remember { Path() }
    val palmPath = remember { Path() }
    val energyBeamsPath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val levitationY = if (isAnimated) sin(animProgress * 2f * PI.toFloat()) * (dim * 0.035f) else 0f

        // 1. Парящий граненый октаэдр (кристалл энергии)
        val topY = cy - 0.42f * dim + levitationY
        val botY = cy - 0.08f * dim + levitationY
        val eqFarLeftX = cx - 0.18f * dim
        val eqFarLeftY = cy - 0.25f * dim + levitationY
        val eqMidLeftX = cx - 0.06f * dim
        val eqMidLeftY = cy - 0.21f * dim + levitationY
        val eqMidRightX = cx + 0.06f * dim
        val eqMidRightY = cy - 0.21f * dim + levitationY
        val eqFarRightX = cx + 0.18f * dim
        val eqFarRightY = cy - 0.25f * dim + levitationY

        crystalPath.reset()
        // Верхний купол кристаллической пирамиды
        crystalPath.moveTo(cx, topY)
        crystalPath.lineTo(eqFarLeftX, eqFarLeftY)
        crystalPath.lineTo(eqMidLeftX, eqMidLeftY)
        crystalPath.lineTo(cx, topY)
        crystalPath.lineTo(eqMidRightX, eqMidRightY)
        crystalPath.lineTo(eqFarRightX, eqFarRightY)
        crystalPath.lineTo(cx, topY)

        // Нижний купол пирамиды
        crystalPath.moveTo(eqFarLeftX, eqFarLeftY)
        crystalPath.lineTo(cx, botY)
        crystalPath.lineTo(eqMidLeftX, eqMidLeftY)
        crystalPath.moveTo(cx, botY)
        crystalPath.lineTo(eqMidRightX, eqMidRightY)
        crystalPath.moveTo(cx, botY)
        crystalPath.lineTo(eqFarRightX, eqFarRightY)

        // Экваториальный пояс граней
        crystalPath.moveTo(eqFarLeftX, eqFarLeftY)
        crystalPath.lineTo(eqMidLeftX, eqMidLeftY)
        crystalPath.lineTo(eqMidRightX, eqMidRightY)
        crystalPath.lineTo(eqFarRightX, eqFarRightY)

        drawPath(path = crystalPath, color = tint, style = stroke)

        // 2. Контурная ладонь приема энергии (кибернетическая открытая длань)
        palmPath.reset()
        palmPath.moveTo(cx - 0.40f * dim, cy + 0.36f * dim)
        palmPath.lineTo(cx - 0.14f * dim, cy + 0.42f * dim)
        palmPath.lineTo(cx + 0.18f * dim, cy + 0.38f * dim)
        palmPath.lineTo(cx + 0.42f * dim, cy + 0.16f * dim)
        // Внутренний сгиб чаши ладони
        palmPath.lineTo(cx + 0.38f * dim, cy + 0.14f * dim)
        palmPath.lineTo(cx + 0.14f * dim, cy + 0.24f * dim)
        // Большой палец
        palmPath.lineTo(cx - 0.10f * dim, cy + 0.08f * dim)
        palmPath.lineTo(cx - 0.16f * dim, cy + 0.14f * dim)
        palmPath.lineTo(cx - 0.12f * dim, cy + 0.24f * dim)
        palmPath.lineTo(cx - 0.40f * dim, cy + 0.30f * dim)
        palmPath.close()

        drawPath(path = palmPath, color = tint, style = stroke)

        // 3. Восходящие квантовые микро-лучи передачи заряда
        energyBeamsPath.reset()
        energyBeamsPath.moveTo(cx - 0.08f * dim, cy + 0.14f * dim)
        energyBeamsPath.lineTo(cx - 0.08f * dim, cy + 0.01f * dim + levitationY)

        energyBeamsPath.moveTo(cx, cy + 0.12f * dim)
        energyBeamsPath.lineTo(cx, cy - 0.02f * dim + levitationY)

        energyBeamsPath.moveTo(cx + 0.08f * dim, cy + 0.14f * dim)
        energyBeamsPath.lineTo(cx + 0.08f * dim, cy + 0.01f * dim + levitationY)

        drawPath(path = energyBeamsPath, color = tint, style = stroke)
    }
}

@Composable
fun IcDonate(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyDonateIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 5. IC_SHIELD — ПЯТИУГОЛЬНЫЙ ГЕРАЛЬДИЧЕСКИЙ КИБЕР-ЩИТ С ЦЕНТРАЛЬНОЙ ГРАНЬЮ
// =============================================================================

@Composable
fun MirrlyShieldIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val shieldPath = remember { Path() }
    val ribsPath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val pulseGlow = if (isAnimated) sin(animProgress * 2f * PI.toFloat()) * (dim * 0.01f) else 0f

        // 1. Внешний пятиугольный геральдический щит
        val crestY = cy - 0.42f * dim + pulseGlow
        val topLX = cx - 0.36f * dim
        val topLY = cy - 0.34f * dim + pulseGlow
        val midLX = cx - 0.36f * dim
        val midLY = cy + 0.04f * dim
        val tipY = cy + 0.44f * dim
        val midRX = cx + 0.36f * dim
        val midRY = cy + 0.04f * dim
        val topRX = cx + 0.36f * dim
        val topRY = cy - 0.34f * dim + pulseGlow

        shieldPath.reset()
        shieldPath.moveTo(cx, crestY)
        shieldPath.lineTo(topLX, topLY)
        shieldPath.lineTo(midLX, midLY)
        shieldPath.lineTo(cx, tipY)
        shieldPath.lineTo(midRX, midRY)
        shieldPath.lineTo(topRX, topRY)
        shieldPath.close()

        drawPath(path = shieldPath, color = tint, style = stroke)

        // 2. Центральная грань жесткости (киль) и поперечные шевроны
        ribsPath.reset()
        // Осевой киль от гребня до нижнего острия
        ribsPath.moveTo(cx, crestY)
        ribsPath.lineTo(cx, tipY)

        // Поперечный шеврон жесткости
        ribsPath.moveTo(midLX, midLY)
        ribsPath.lineTo(cx, cy + 0.02f * dim)
        ribsPath.lineTo(midRX, midRY)

        // Верхний диагональный распор
        ribsPath.moveTo(topLX, topLY)
        ribsPath.lineTo(cx, cy - 0.18f * dim)
        ribsPath.lineTo(topRX, topRY)

        // Внутренний субрельеф
        ribsPath.moveTo(cx, cy - 0.18f * dim)
        ribsPath.lineTo(cx, cy + 0.02f * dim)

        drawPath(path = ribsPath, color = tint, style = stroke)
    }
}

@Composable
fun IcShield(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyShieldIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 6. IC_HALL_OF_FAME — ТРИУМФАЛЬНЫЙ ОБЕЛИСК С ИОННЫМ ЛУЧОМ В ЗЕНИТ
// =============================================================================

@Composable
fun MirrlyHallOfFameIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val obeliskPath = remember { Path() }
    val beamPath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val beamPulse = if (isAnimated) (animProgress * 3f) % 1f else 0f

        // 1. Пьедестал и тело обелиска
        obeliskPath.reset()
        // Нижняя плита фундамента
        obeliskPath.moveTo(cx - 0.40f * dim, cy + 0.42f * dim)
        obeliskPath.lineTo(cx + 0.40f * dim, cy + 0.42f * dim)

        // Вторая ступенька цоколя
        obeliskPath.moveTo(cx - 0.30f * dim, cy + 0.34f * dim)
        obeliskPath.lineTo(cx + 0.30f * dim, cy + 0.34f * dim)
        obeliskPath.lineTo(cx + 0.30f * dim, cy + 0.42f * dim)
        obeliskPath.moveTo(cx - 0.30f * dim, cy + 0.34f * dim)
        obeliskPath.lineTo(cx - 0.30f * dim, cy + 0.42f * dim)

        // Тело монолитной колонны (коническое сужение кверху)
        obeliskPath.moveTo(cx - 0.20f * dim, cy + 0.34f * dim)
        obeliskPath.lineTo(cx - 0.12f * dim, cy - 0.12f * dim)
        obeliskPath.lineTo(cx + 0.12f * dim, cy - 0.12f * dim)
        obeliskPath.lineTo(cx + 0.20f * dim, cy + 0.34f * dim)

        // Центральная осевая грань обелиска
        obeliskPath.moveTo(cx, cy + 0.34f * dim)
        obeliskPath.lineTo(cx, cy - 0.15f * dim)

        // Пирамидион (острая пирамидальная вершина обелиска)
        obeliskPath.moveTo(cx - 0.12f * dim, cy - 0.12f * dim)
        obeliskPath.lineTo(cx, cy - 0.24f * dim)
        obeliskPath.lineTo(cx + 0.12f * dim, cy - 0.12f * dim)
        obeliskPath.moveTo(cx, cy - 0.15f * dim)
        obeliskPath.lineTo(cx, cy - 0.24f * dim)

        drawPath(path = obeliskPath, color = tint, style = stroke)

        // 2. Ионный луч в зенит и расширяющиеся кольца энергии
        beamPath.reset()
        // Вертикальный когерентный лазер
        beamPath.moveTo(cx, cy - 0.24f * dim)
        beamPath.lineTo(cx, cy - 0.46f * dim)

        // Энергетические дуги/шевроны восходящего волнового фронта
        val arc1Y = cy - 0.32f * dim - beamPulse * 0.04f * dim
        beamPath.moveTo(cx - 0.10f * dim, arc1Y + 0.03f * dim)
        beamPath.lineTo(cx, arc1Y)
        beamPath.lineTo(cx + 0.10f * dim, arc1Y + 0.03f * dim)

        val arc2Y = cy - 0.40f * dim - beamPulse * 0.04f * dim
        beamPath.moveTo(cx - 0.18f * dim, arc2Y + 0.03f * dim)
        beamPath.lineTo(cx, arc2Y)
        beamPath.lineTo(cx + 0.18f * dim, arc2Y + 0.03f * dim)

        drawPath(path = beamPath, color = tint, style = stroke)
        drawCircle(color = tint, radius = strokeWidthPx * 0.8f, center = Offset(cx, cy - 0.46f * dim))
    }
}

@Composable
fun IcHallOfFame(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyHallOfFameIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 7. IC_FAME_GRID — МАТРИЦА 3X3 ИЗ ПЕРЕКРЕСТНЫХ МИКРО-РАМОК
// =============================================================================

@Composable
fun MirrlyFameGridIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val busPath = remember { Path() }
    val centerNodePath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val cellSize = dim * 0.19f
        val cellRadius = CornerRadius(dim * 0.04f)
        val offsets = floatArrayOf(-0.25f * dim, 0f, 0.25f * dim)

        // 1. Отрисовка 9 контурных микро-рамок
        for (r in 0..2) {
            for (c in 0..2) {
                val x = cx + offsets[c] - cellSize * 0.5f
                val y = cy + offsets[r] - cellSize * 0.5f
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(x, y),
                    size = Size(cellSize, cellSize),
                    cornerRadius = cellRadius,
                    style = stroke
                )
            }
        }

        // 2. Межмодульные соединительные микро-шины (перекрестные связи)
        busPath.reset()
        // Горизонтальные соединители между ячейками
        for (r in 0..2) {
            val y = cy + offsets[r]
            // Между колонками 0 и 1
            busPath.moveTo(cx + offsets[0] + cellSize * 0.5f, y)
            busPath.lineTo(cx + offsets[1] - cellSize * 0.5f, y)
            // Между колонками 1 и 2
            busPath.moveTo(cx + offsets[1] + cellSize * 0.5f, y)
            busPath.lineTo(cx + offsets[2] - cellSize * 0.5f, y)
        }

        // Вертикальные соединители между рядами
        for (c in 0..2) {
            val x = cx + offsets[c]
            // Между рядами 0 и 1
            busPath.moveTo(x, cy + offsets[0] + cellSize * 0.5f)
            busPath.lineTo(x, cy + offsets[1] - cellSize * 0.5f)
            // Между рядами 1 и 2
            busPath.moveTo(x, cy + offsets[1] + cellSize * 0.5f)
            busPath.lineTo(x, cy + offsets[2] - cellSize * 0.5f)
        }
        drawPath(path = busPath, color = tint, style = stroke)

        // 3. Центральный слот Зала Славы: квантовый маркер лидера
        val centerSize = if (isAnimated) (0.05f + sin(animProgress * 4f * PI.toFloat()) * 0.015f) * dim else 0.05f * dim
        centerNodePath.reset()
        centerNodePath.moveTo(cx, cy - centerSize)
        centerNodePath.lineTo(cx + centerSize, cy)
        centerNodePath.lineTo(cx, cy + centerSize)
        centerNodePath.lineTo(cx - centerSize, cy)
        centerNodePath.close()
        drawPath(path = centerNodePath, color = tint, style = stroke)
    }
}

@Composable
fun IcFameGrid(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyFameGridIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 8. IC_VOLUNTEER_BADGE — РОМБОВИДНЫЙ ОРДЕН С КРЫЛЬЯМИ ГЛАЙДЕРА
// =============================================================================

@Composable
fun MirrlyVolunteerBadgeIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val diamondPath = remember { Path() }
    val wingsPath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val wingFlutter = if (isAnimated) sin(animProgress * 2f * PI.toFloat()) * (dim * 0.018f) else 0f

        // 1. Центральный ромбовидный орден волонтера
        diamondPath.reset()
        // Внешний ромб
        diamondPath.moveTo(cx, cy - 0.36f * dim)
        diamondPath.lineTo(cx + 0.18f * dim, cy)
        diamondPath.lineTo(cx, cy + 0.36f * dim)
        diamondPath.lineTo(cx - 0.18f * dim, cy)
        diamondPath.close()

        // Внутренний ромб
        diamondPath.moveTo(cx, cy - 0.20f * dim)
        diamondPath.lineTo(cx + 0.10f * dim, cy)
        diamondPath.lineTo(cx, cy + 0.20f * dim)
        diamondPath.lineTo(cx - 0.10f * dim, cy)
        diamondPath.close()

        drawPath(path = diamondPath, color = tint, style = stroke)
        drawCircle(color = tint, radius = strokeWidthPx * 0.8f, center = Offset(cx, cy))

        // 2. Стреловидные крылья глайдера по бокам ордена
        wingsPath.reset()
        // Левое крыло глайдера
        wingsPath.moveTo(cx - 0.14f * dim, cy - 0.14f * dim)
        wingsPath.lineTo(cx - 0.46f * dim, cy - 0.06f * dim + wingFlutter)
        wingsPath.lineTo(cx - 0.44f * dim, cy + 0.10f * dim + wingFlutter)
        wingsPath.lineTo(cx - 0.14f * dim, cy + 0.16f * dim)
        // Ребро жесткости консоли
        wingsPath.moveTo(cx - 0.14f * dim, cy)
        wingsPath.lineTo(cx - 0.46f * dim, cy - 0.06f * dim + wingFlutter)

        // Правое крыло глайдера
        wingsPath.moveTo(cx + 0.14f * dim, cy - 0.14f * dim)
        wingsPath.lineTo(cx + 0.46f * dim, cy - 0.06f * dim + wingFlutter)
        wingsPath.lineTo(cx + 0.44f * dim, cy + 0.10f * dim + wingFlutter)
        wingsPath.lineTo(cx + 0.14f * dim, cy + 0.16f * dim)
        // Ребро жесткости консоли
        wingsPath.moveTo(cx + 0.14f * dim, cy)
        wingsPath.lineTo(cx + 0.46f * dim, cy - 0.06f * dim + wingFlutter)

        drawPath(path = wingsPath, color = tint, style = stroke)
    }
}

@Composable
fun IcVolunteerBadge(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyVolunteerBadgeIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 9. IC_FINGERPRINT_BADGE — ТРИ ДУГИ ДАКТИЛОСКОПИЧЕСКОГО СЕНСОРА С РАЗРЫВАМИ
// =============================================================================

@Composable
fun MirrlyFingerprintBadgeIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val framePath = remember { Path() }
    val ridgesPath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val scanPulse = if (isAnimated) sin(animProgress * 2f * PI.toFloat()) * (dim * 0.015f) else 0f

        // 1. Угловые скобки оптического сенсора
        framePath.reset()
        // Top-Left
        framePath.moveTo(cx - 0.38f * dim, cy - 0.20f * dim)
        framePath.lineTo(cx - 0.38f * dim, cy - 0.38f * dim)
        framePath.lineTo(cx - 0.20f * dim, cy - 0.38f * dim)

        // Top-Right
        framePath.moveTo(cx + 0.20f * dim, cy - 0.38f * dim)
        framePath.lineTo(cx + 0.38f * dim, cy - 0.38f * dim)
        framePath.lineTo(cx + 0.38f * dim, cy - 0.20f * dim)

        // Bottom-Left
        framePath.moveTo(cx - 0.38f * dim, cy + 0.20f * dim)
        framePath.lineTo(cx - 0.38f * dim, cy + 0.38f * dim)
        framePath.lineTo(cx - 0.20f * dim, cy + 0.38f * dim)

        // Bottom-Right
        framePath.moveTo(cx + 0.20f * dim, cy + 0.38f * dim)
        framePath.lineTo(cx + 0.38f * dim, cy + 0.38f * dim)
        framePath.lineTo(cx + 0.38f * dim, cy + 0.20f * dim)

        drawPath(path = framePath, color = tint, style = stroke)

        // 2. Дактилоскопические папиллярные гребни с волновыми разрывами
        ridgesPath.reset()
        // Внутреннее ядро-петля
        ridgesPath.moveTo(cx - 0.06f * dim, cy + 0.16f * dim)
        ridgesPath.cubicTo(
            cx - 0.06f * dim, cy - 0.02f * dim,
            cx + 0.06f * dim, cy - 0.02f * dim,
            cx + 0.06f * dim, cy + 0.16f * dim
        )

        // Дуга 1 (Внутренняя концентрическая, с разрывом справа)
        ridgesPath.moveTo(cx - 0.13f * dim, cy + 0.22f * dim)
        ridgesPath.cubicTo(
            cx - 0.13f * dim, cy - 0.12f * dim + scanPulse,
            cx - 0.02f * dim, cy - 0.14f * dim + scanPulse,
            cx, cy - 0.14f * dim + scanPulse
        )
        // Правый сегмент после разрыва
        ridgesPath.moveTo(cx + 0.06f * dim, cy - 0.12f * dim + scanPulse)
        ridgesPath.cubicTo(
            cx + 0.13f * dim, cy - 0.06f * dim,
            cx + 0.13f * dim, cy + 0.10f * dim,
            cx + 0.13f * dim, cy + 0.22f * dim
        )

        // Дуга 2 (Средняя концентрическая, с верхним разрывом)
        ridgesPath.moveTo(cx - 0.22f * dim, cy + 0.12f * dim)
        ridgesPath.lineTo(cx - 0.22f * dim, cy + 0.28f * dim)
        // Верхняя петля средней дуги
        ridgesPath.moveTo(cx - 0.22f * dim, cy + 0.04f * dim)
        ridgesPath.cubicTo(
            cx - 0.22f * dim, cy - 0.24f * dim,
            cx + 0.22f * dim, cy - 0.24f * dim,
            cx + 0.22f * dim, cy - 0.02f * dim
        )
        ridgesPath.moveTo(cx + 0.22f * dim, cy + 0.08f * dim)
        ridgesPath.lineTo(cx + 0.22f * dim, cy + 0.28f * dim)

        // Дуга 3 (Внешняя концентрическая, с боковым волновым разрывом)
        ridgesPath.moveTo(cx - 0.28f * dim, cy + 0.30f * dim)
        ridgesPath.cubicTo(
            cx - 0.32f * dim, cy - 0.10f * dim - scanPulse,
            cx - 0.20f * dim, cy - 0.34f * dim - scanPulse,
            cx, cy - 0.34f * dim - scanPulse
        )
        ridgesPath.cubicTo(
            cx + 0.20f * dim, cy - 0.34f * dim - scanPulse,
            cx + 0.32f * dim, cy - 0.10f * dim - scanPulse,
            cx + 0.28f * dim, cy + 0.30f * dim
        )

        drawPath(path = ridgesPath, color = tint, style = stroke)
        drawCircle(color = tint, radius = strokeWidthPx * 0.75f, center = Offset(cx, cy + 0.06f * dim))
    }
}

@Composable
fun IcFingerprintBadge(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyFingerprintBadgeIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 10. IC_TELEGRAM — ТОНКИЙ КОНТУРНЫЙ САМОЛЕТИК СО СКЛАДКОЙ ПРАВОГО КРЫЛА
// =============================================================================

@Composable
fun MirrlyTelegramIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val mainPlanePath = remember { Path() }
    val wingFoldPath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f + (dim * 0.02f) // Оптическая балансировка центра массы
        val cy = size.height * 0.5f

        val hoverY = if (isAnimated) sin(animProgress * 2f * PI.toFloat()) * (dim * 0.025f) else 0f

        val noseX = cx + 0.40f * dim
        val noseY = cy - 0.38f * dim + hoverY
        val leftTipX = cx - 0.42f * dim
        val leftTipY = cy - 0.02f * dim + hoverY
        val keelBottomX = cx - 0.10f * dim
        val keelBottomY = cy + 0.38f * dim + hoverY
        val innerFoldX = cx + 0.04f * dim
        val innerFoldY = cy + 0.14f * dim + hoverY
        val rightTipX = cx + 0.30f * dim
        val rightTipY = cy - 0.08f * dim + hoverY

        // 1. Основное левое крыло и силуэт полета
        mainPlanePath.reset()
        mainPlanePath.moveTo(noseX, noseY)
        mainPlanePath.lineTo(leftTipX, leftTipY)
        mainPlanePath.lineTo(innerFoldX, innerFoldY)
        mainPlanePath.close()

        // 2. Киль фюзеляжа (подбрюшье бумажного самолета)
        mainPlanePath.moveTo(innerFoldX, innerFoldY)
        mainPlanePath.lineTo(keelBottomX, keelBottomY)
        mainPlanePath.lineTo(cx + 0.12f * dim, cy + 0.10f * dim + hoverY)

        drawPath(path = mainPlanePath, color = tint, style = stroke)

        // 3. Выраженная складка правого крыла (3D-рельеф изгиба листа бумаги)
        wingFoldPath.reset()
        wingFoldPath.moveTo(noseX, noseY)
        wingFoldPath.lineTo(rightTipX, rightTipY)
        wingFoldPath.lineTo(innerFoldX, innerFoldY)
        wingFoldPath.close()

        drawPath(path = wingFoldPath, color = tint, style = stroke)
    }
}

@Composable
fun IcTelegram(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyTelegramIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 11. IC_GITHUB — СИЛУЭТ OCTOCAT, ПЕРЕРИСОВАННЫЙ ЗАМКНУТЫМ КОНТУРОМ 1.5DP
// =============================================================================

@Composable
fun MirrlyGithubIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val octocatOutline = remember { Path() }
    val faceFeatures = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val eyeBlink = if (isAnimated) {
            val p = (animProgress * 5f) % 1f
            if (p > 0.88f) 0.35f else 1.0f
        } else 1.0f

        // 1. Замкнутый каркасный контур маскота Octocat (голова, ушки и щупальца)
        octocatOutline.reset()
        octocatOutline.moveTo(cx, cy - 0.22f * dim) // Выемка между ушами
        octocatOutline.lineTo(cx - 0.26f * dim, cy - 0.38f * dim) // Левое ухо острие
        octocatOutline.lineTo(cx - 0.34f * dim, cy - 0.20f * dim) // Левое ухо основание
        // Левая щека
        octocatOutline.cubicTo(
            cx - 0.45f * dim, cy - 0.10f * dim,
            cx - 0.45f * dim, cy + 0.12f * dim,
            cx - 0.32f * dim, cy + 0.22f * dim
        )
        // Левые изогнутые щупальца
        octocatOutline.cubicTo(
            cx - 0.38f * dim, cy + 0.34f * dim,
            cx - 0.22f * dim, cy + 0.42f * dim,
            cx - 0.10f * dim, cy + 0.38f * dim
        )
        octocatOutline.lineTo(cx, cy + 0.32f * dim) // Центральная вырезка основания
        // Правые изогнутые щупальца
        octocatOutline.lineTo(cx + 0.10f * dim, cy + 0.38f * dim)
        octocatOutline.cubicTo(
            cx + 0.22f * dim, cy + 0.42f * dim,
            cx + 0.38f * dim, cy + 0.34f * dim,
            cx + 0.32f * dim, cy + 0.22f * dim
        )
        // Правая щека
        octocatOutline.cubicTo(
            cx + 0.45f * dim, cy + 0.12f * dim,
            cx + 0.45f * dim, cy - 0.10f * dim,
            cx + 0.34f * dim, cy - 0.20f * dim
        )
        octocatOutline.lineTo(cx + 0.26f * dim, cy - 0.38f * dim) // Правое ухо острие
        octocatOutline.close()

        drawPath(path = octocatOutline, color = tint, style = stroke)

        // 2. Черты лица: круглые контурные глаза, носик и усы
        faceFeatures.reset()
        // Глаза (контурные кольца без глухой заливки)
        val eyeRadius = 0.055f * dim
        val eyeH = eyeRadius * eyeBlink
        val leftEyeX = cx - 0.14f * dim
        val rightEyeX = cx + 0.14f * dim
        val eyeY = cy - 0.01f * dim

        // Левый глаз
        faceFeatures.addOval(androidx.compose.ui.geometry.Rect(leftEyeX - eyeRadius, eyeY - eyeH, leftEyeX + eyeRadius, eyeY + eyeH))
        // Правый глаз
        faceFeatures.addOval(androidx.compose.ui.geometry.Rect(rightEyeX - eyeRadius, eyeY - eyeH, rightEyeX + eyeRadius, eyeY + eyeH))

        // Носик-шеврон
        faceFeatures.moveTo(cx - 0.035f * dim, cy + 0.08f * dim)
        faceFeatures.lineTo(cx, cy + 0.11f * dim)
        faceFeatures.lineTo(cx + 0.035f * dim, cy + 0.08f * dim)

        // Контурные усики
        faceFeatures.moveTo(cx - 0.22f * dim, cy + 0.06f * dim)
        faceFeatures.lineTo(cx - 0.34f * dim, cy + 0.04f * dim)
        faceFeatures.moveTo(cx - 0.22f * dim, cy + 0.10f * dim)
        faceFeatures.lineTo(cx - 0.33f * dim, cy + 0.11f * dim)

        faceFeatures.moveTo(cx + 0.22f * dim, cy + 0.06f * dim)
        faceFeatures.lineTo(cx + 0.34f * dim, cy + 0.04f * dim)
        faceFeatures.moveTo(cx + 0.22f * dim, cy + 0.10f * dim)
        faceFeatures.lineTo(cx + 0.33f * dim, cy + 0.11f * dim)

        drawPath(path = faceFeatures, color = tint, style = stroke)
    }
}

@Composable
fun IcGithub(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyGithubIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 12. IC_CLOUDTIPS — КОНТУРНОЕ ОБЛАКО С ДВУМЯ РАСХОДЯЩИМИСЯ ЛУЧАМИ ПОДДЕРЖКИ
// =============================================================================

@Composable
fun MirrlyCloudtipsIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val cloudPath = remember { Path() }
    val raysPath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val rayPulse = if (isAnimated) sin(animProgress * 3f * PI.toFloat()) * (dim * 0.02f) else 0f

        // 1. Контурный купол облака (чистый Outline-Only)
        cloudPath.reset()
        // Нижняя полка облака
        cloudPath.moveTo(cx - 0.28f * dim, cy + 0.06f * dim)
        cloudPath.lineTo(cx + 0.28f * dim, cy + 0.06f * dim)
        // Правая полусфера
        cloudPath.cubicTo(
            cx + 0.42f * dim, cy + 0.06f * dim,
            cx + 0.44f * dim, cy - 0.12f * dim,
            cx + 0.32f * dim, cy - 0.18f * dim
        )
        // Верхний кучевой купол
        cloudPath.cubicTo(
            cx + 0.30f * dim, cy - 0.36f * dim,
            cx - 0.04f * dim, cy - 0.38f * dim,
            cx - 0.10f * dim, cy - 0.26f * dim
        )
        // Левая полусфера
        cloudPath.cubicTo(
            cx - 0.24f * dim, cy - 0.26f * dim,
            cx - 0.42f * dim, cy - 0.12f * dim,
            cx - 0.28f * dim, cy + 0.06f * dim
        )
        cloudPath.close()

        drawPath(path = cloudPath, color = tint, style = stroke)

        // 2. Два расходящихся луча поддержки (стилизация капсул CloudTips под 1.5dp каркас)
        raysPath.reset()
        // Левый диагональный луч поддержки
        raysPath.moveTo(cx - 0.10f * dim, cy + 0.06f * dim)
        raysPath.lineTo(cx - 0.28f * dim - rayPulse, cy + 0.40f * dim)
        raysPath.lineTo(cx - 0.20f * dim - rayPulse, cy + 0.43f * dim)
        raysPath.lineTo(cx - 0.02f * dim, cy + 0.06f * dim)

        // Правый диагональный луч поддержки
        raysPath.moveTo(cx + 0.02f * dim, cy + 0.06f * dim)
        raysPath.lineTo(cx + 0.20f * dim + rayPulse, cy + 0.43f * dim)
        raysPath.lineTo(cx + 0.28f * dim + rayPulse, cy + 0.40f * dim)
        raysPath.lineTo(cx + 0.10f * dim, cy + 0.06f * dim)

        drawPath(path = raysPath, color = tint, style = stroke)

        // Контурное микро-сердечко внутри облака (импульс благодарности)
        drawCircle(color = tint, radius = strokeWidthPx * 0.8f, center = Offset(cx, cy - 0.10f * dim))
    }
}

@Composable
fun IcCloudtips(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyCloudtipsIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 13. IC_YANDEX_TIPS — КОНТУРНЫЕ СПИРАЛЕВИДНЫЕ СКОБКИ С ДИНАМИЧЕСКИМ ВРАЩЕНИЕМ
// =============================================================================

@Composable
fun MirrlyYandexTipsIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val bracketsPath = remember { Path() }
    val spiralPath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val spiralRotation = if (isAnimated) animProgress * 2f * PI.toFloat() else 0f

        // 1. Четыре контурные обрамляющие скобки (фирменный визор Yandex Чаевые)
        bracketsPath.reset()
        // Top-Left
        bracketsPath.moveTo(cx - 0.38f * dim, cy - 0.18f * dim)
        bracketsPath.lineTo(cx - 0.38f * dim, cy - 0.38f * dim)
        bracketsPath.lineTo(cx - 0.18f * dim, cy - 0.38f * dim)

        // Top-Right
        bracketsPath.moveTo(cx + 0.18f * dim, cy - 0.38f * dim)
        bracketsPath.lineTo(cx + 0.38f * dim, cy - 0.38f * dim)
        bracketsPath.lineTo(cx + 0.38f * dim, cy - 0.18f * dim)

        // Bottom-Right
        bracketsPath.moveTo(cx + 0.38f * dim, cy + 0.18f * dim)
        bracketsPath.lineTo(cx + 0.38f * dim, cy + 0.38f * dim)
        bracketsPath.lineTo(cx + 0.18f * dim, cy + 0.38f * dim)

        // Bottom-Left
        bracketsPath.moveTo(cx - 0.18f * dim, cy + 0.38f * dim)
        bracketsPath.lineTo(cx - 0.38f * dim, cy + 0.38f * dim)
        bracketsPath.lineTo(cx - 0.38f * dim, cy + 0.18f * dim)

        drawPath(path = bracketsPath, color = tint, style = stroke)

        // 2. Архимедова контурная спираль с плавным расширением витков
        spiralPath.reset()
        val totalTurns = 2.2f * PI.toFloat()
        val steps = 36
        val rMin = dim * 0.04f
        val rMax = dim * 0.25f

        for (i in 0..steps) {
            val fraction = i.toFloat() / steps.toFloat()
            val theta = fraction * totalTurns + spiralRotation
            val r = rMin + (rMax - rMin) * fraction
            val px = cx + cos(theta) * r
            val py = cy + sin(theta) * r

            if (i == 0) {
                spiralPath.moveTo(px, py)
            } else {
                spiralPath.lineTo(px, py)
            }
        }
        drawPath(path = spiralPath, color = tint, style = stroke)
        drawCircle(color = tint, radius = strokeWidthPx * 0.8f, center = Offset(cx, cy))
    }
}

@Composable
fun IcYandexTips(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyYandexTipsIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 14. IC_DALINK — КОНТУРНОЕ ЗВЕНО ЦЕПИ В ДИАЛОГОВОМ БАЛЛОНЕ ТРАНЗАКЦИИ
// =============================================================================

@Composable
fun MirrlyDalinkIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val bubblePath = remember { Path() }
    val chainPath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val pulse = if (isAnimated) sin(animProgress * 2f * PI.toFloat()) * (dim * 0.015f) else 0f

        // 1. Диалоговый баллон транзакции / D-знак
        bubblePath.reset()
        // Верхний край
        bubblePath.moveTo(cx - 0.28f * dim, cy - 0.40f * dim + pulse)
        bubblePath.lineTo(cx + 0.20f * dim, cy - 0.40f * dim + pulse)
        // Правая дуга в стиле буквы D
        bubblePath.cubicTo(
            cx + 0.44f * dim, cy - 0.40f * dim + pulse,
            cx + 0.44f * dim, cy + 0.24f * dim,
            cx + 0.20f * dim, cy + 0.24f * dim
        )
        // Нижняя полка
        bubblePath.lineTo(cx - 0.12f * dim, cy + 0.24f * dim)
        // Указатель/хвостик диалога
        bubblePath.lineTo(cx - 0.36f * dim, cy + 0.42f * dim)
        bubblePath.lineTo(cx - 0.28f * dim, cy + 0.24f * dim)
        // Левый вертикальный край
        bubblePath.lineTo(cx - 0.28f * dim, cy - 0.40f * dim + pulse)
        bubblePath.close()

        drawPath(path = bubblePath, color = tint, style = stroke)

        // 2. Сцепленные контурные звенья цепи транзакции (Chain Link)
        chainPath.reset()
        // Верхнее правое звено
        chainPath.moveTo(cx - 0.02f * dim, cy - 0.24f * dim + pulse)
        chainPath.lineTo(cx + 0.18f * dim, cy - 0.04f * dim + pulse)
        chainPath.lineTo(cx + 0.11f * dim, cy + 0.03f * dim + pulse)
        chainPath.lineTo(cx - 0.09f * dim, cy - 0.17f * dim + pulse)
        chainPath.close()

        // Нижнее левое звено
        chainPath.moveTo(cx - 0.16f * dim, cy - 0.10f * dim)
        chainPath.lineTo(cx + 0.04f * dim, cy + 0.10f * dim)
        chainPath.lineTo(cx - 0.03f * dim, cy + 0.17f * dim)
        chainPath.lineTo(cx - 0.23f * dim, cy - 0.03f * dim)
        chainPath.close()

        drawPath(path = chainPath, color = tint, style = stroke)
    }
}

@Composable
fun IcDalink(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyDalinkIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// 15. IC_COMMUNITY_CAT_PLANE — МОРДОЧКА КОТА-ШТУРМАНА В КАБИНЕ ГЛАЙДЕРА
// =============================================================================

@Composable
fun MirrlyCommunityCatPlaneIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val gliderPath = remember { Path() }
    val catPilotPath = remember { Path() }

    val animProgress = rememberIconAnimationProgress(isAnimated)

    val semModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier

    Canvas(modifier = semModifier) {
        val dim = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        val flightRoll = if (isAnimated) sin(animProgress * 2f * PI.toFloat()) * (dim * 0.02f) else 0f

        // 1. Корпус кибер-глайдера (дельтовидные крылья и кабина)
        gliderPath.reset()
        // Носовой обтекатель
        val noseX = cx
        val noseY = cy - 0.44f * dim
        // Левая консоль
        gliderPath.moveTo(noseX, noseY)
        gliderPath.lineTo(cx - 0.44f * dim, cy + 0.34f * dim + flightRoll)
        gliderPath.lineTo(cx - 0.26f * dim, cy + 0.28f * dim + flightRoll)

        // Правая консоль
        gliderPath.moveTo(noseX, noseY)
        gliderPath.lineTo(cx + 0.44f * dim, cy + 0.34f * dim - flightRoll)
        gliderPath.lineTo(cx + 0.26f * dim, cy + 0.28f * dim - flightRoll)

        // Контур стеклянного фонаря кабины (Canopy)
        gliderPath.moveTo(cx - 0.26f * dim, cy + 0.20f * dim)
        gliderPath.cubicTo(
            cx - 0.26f * dim, cy - 0.30f * dim,
            cx + 0.26f * dim, cy - 0.30f * dim,
            cx + 0.26f * dim, cy + 0.20f * dim
        )

        drawPath(path = gliderPath, color = tint, style = stroke)

        // 2. Мордочка кота-штурмана внутри кабины
        catPilotPath.reset()
        // Ушки пилота (торчат под куполом фонаря)
        // Левое ухо
        catPilotPath.moveTo(cx - 0.16f * dim, cy - 0.10f * dim)
        catPilotPath.lineTo(cx - 0.20f * dim, cy - 0.28f * dim)
        catPilotPath.lineTo(cx - 0.08f * dim, cy - 0.16f * dim)
        // Правое ухо
        catPilotPath.lineTo(cx + 0.08f * dim, cy - 0.16f * dim)
        catPilotPath.lineTo(cx + 0.20f * dim, cy - 0.28f * dim)
        catPilotPath.lineTo(cx + 0.16f * dim, cy - 0.10f * dim)

        // Авиаторские очки / визор пилота
        val goggleR = 0.06f * dim
        catPilotPath.addOval(androidx.compose.ui.geometry.Rect(cx - 0.10f * dim - goggleR, cy - 0.03f * dim - goggleR, cx - 0.10f * dim + goggleR, cy - 0.03f * dim + goggleR))
        catPilotPath.addOval(androidx.compose.ui.geometry.Rect(cx + 0.10f * dim - goggleR, cy - 0.03f * dim - goggleR, cx + 0.10f * dim + goggleR, cy - 0.03f * dim + goggleR))
        // Переносица очков
        catPilotPath.moveTo(cx - 0.04f * dim, cy - 0.03f * dim)
        catPilotPath.lineTo(cx + 0.04f * dim, cy - 0.03f * dim)

        // Носик и ротик штурмана
        catPilotPath.moveTo(cx - 0.035f * dim, cy + 0.08f * dim)
        catPilotPath.lineTo(cx, cy + 0.11f * dim)
        catPilotPath.lineTo(cx + 0.035f * dim, cy + 0.08f * dim)

        catPilotPath.moveTo(cx - 0.06f * dim, cy + 0.14f * dim)
        catPilotPath.cubicTo(cx - 0.03f * dim, cy + 0.16f * dim, cx, cy + 0.14f * dim, cx, cy + 0.11f * dim)
        catPilotPath.cubicTo(cx, cy + 0.14f * dim, cx + 0.03f * dim, cy + 0.16f * dim, cx + 0.06f * dim, cy + 0.14f * dim)

        // Усики штурмана
        catPilotPath.moveTo(cx - 0.16f * dim, cy + 0.08f * dim)
        catPilotPath.lineTo(cx - 0.30f * dim, cy + 0.06f * dim)
        catPilotPath.moveTo(cx - 0.16f * dim, cy + 0.12f * dim)
        catPilotPath.lineTo(cx - 0.28f * dim, cy + 0.15f * dim)

        catPilotPath.moveTo(cx + 0.16f * dim, cy + 0.08f * dim)
        catPilotPath.lineTo(cx + 0.30f * dim, cy + 0.06f * dim)
        catPilotPath.moveTo(cx + 0.16f * dim, cy + 0.12f * dim)
        catPilotPath.lineTo(cx + 0.28f * dim, cy + 0.15f * dim)

        // Штурвал / приборная панель внизу кабины
        catPilotPath.moveTo(cx - 0.18f * dim, cy + 0.26f * dim)
        catPilotPath.lineTo(cx + 0.18f * dim, cy + 0.26f * dim)

        drawPath(path = catPilotPath, color = tint, style = stroke)
    }
}

@Composable
fun IcCommunityCatPlane(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) = MirrlyCommunityCatPlaneIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)

// =============================================================================
// ЕДИНЫЙ УНИВЕРСАЛЬНЫЙ ДИСПЕТЧЕР И ПЕРЕЧИСЛЕНИЕ (15 КОМПОНЕНТОВ)
// =============================================================================

/**
 * Идентификаторы процедурных векторных иконок категории
 * «Сообщество, Зал Славы, донаты и партнерские сервисы».
 */
enum class MirrlyCommunityIconType {
    USER,
    HEART,
    STAR,
    DONATE,
    SHIELD,
    HALL_OF_FAME,
    FAME_GRID,
    VOLUNTEER_BADGE,
    FINGERPRINT_BADGE,
    TELEGRAM,
    GITHUB,
    CLOUDTIPS,
    YANDEX_TIPS,
    DALINK,
    COMMUNITY_CAT_PLANE
}

/**
 * Единый универсальный компонент отрисовки иконок сообщества MirrlyUI.
 */
@Composable
fun MirrlyCommunityIcon(
    type: MirrlyCommunityIconType,
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false,
    contentDescription: String? = null
) {
    when (type) {
        MirrlyCommunityIconType.USER -> MirrlyUserIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.HEART -> MirrlyHeartIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.STAR -> MirrlyStarIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.DONATE -> MirrlyDonateIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.SHIELD -> MirrlyShieldIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.HALL_OF_FAME -> MirrlyHallOfFameIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.FAME_GRID -> MirrlyFameGridIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.VOLUNTEER_BADGE -> MirrlyVolunteerBadgeIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.FINGERPRINT_BADGE -> MirrlyFingerprintBadgeIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.TELEGRAM -> MirrlyTelegramIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.GITHUB -> MirrlyGithubIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.CLOUDTIPS -> MirrlyCloudtipsIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.YANDEX_TIPS -> MirrlyYandexTipsIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.DALINK -> MirrlyDalinkIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
        MirrlyCommunityIconType.COMMUNITY_CAT_PLANE -> MirrlyCommunityCatPlaneIcon(modifier, tint, strokeWidth, isAnimated, contentDescription)
    }
}

// =============================================================================
// COMPOSE PREVIEW — ГАЛЕРЕЯ ВСЕХ 15 ИКОНОК СООБЩЕСТВА
// =============================================================================

@Composable
fun MirrlyCommunityIconsPreview() {
    val cyan = Color(0xFF00F5D4)
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Mirrly Community Icons (15 Outline-Only)", color = Color.White, fontSize = 14.sp)

        // Ряд 1: Базовые символы сообщества
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MirrlyUserIcon(modifier = Modifier.size(28.dp), tint = cyan)
            MirrlyHeartIcon(modifier = Modifier.size(28.dp), tint = cyan)
            MirrlyStarIcon(modifier = Modifier.size(28.dp), tint = cyan)
            MirrlyDonateIcon(modifier = Modifier.size(28.dp), tint = cyan)
            MirrlyShieldIcon(modifier = Modifier.size(28.dp), tint = cyan)
        }

        // Ряд 2: Зал Славы и отличительные бейджи
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MirrlyHallOfFameIcon(modifier = Modifier.size(28.dp), tint = cyan)
            MirrlyFameGridIcon(modifier = Modifier.size(28.dp), tint = cyan)
            MirrlyVolunteerBadgeIcon(modifier = Modifier.size(28.dp), tint = cyan)
            MirrlyFingerprintBadgeIcon(modifier = Modifier.size(28.dp), tint = cyan)
            MirrlyTelegramIcon(modifier = Modifier.size(28.dp), tint = cyan)
        }

        // Ряд 3: Партнерские сервисы и маскот
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MirrlyGithubIcon(modifier = Modifier.size(28.dp), tint = cyan)
            MirrlyCloudtipsIcon(modifier = Modifier.size(28.dp), tint = cyan)
            MirrlyYandexTipsIcon(modifier = Modifier.size(28.dp), tint = cyan)
            MirrlyDalinkIcon(modifier = Modifier.size(28.dp), tint = cyan)
            MirrlyCommunityCatPlaneIcon(modifier = Modifier.size(28.dp), tint = cyan)
        }
    }
}
