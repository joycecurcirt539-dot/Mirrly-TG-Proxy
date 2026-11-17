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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Процедурные векторные иконки MirrlyUI для Jetpack Compose.
 * Категория: «Сетевой стек, VPN-туннели и системные демоны» (8 компонентов).
 *
 * ТРЕБОВАНИЯ СТАНДАРТА /mirrly-icons:
 * 1. Контурный стиль Outline-Only: штрих 1.5dp (для power допускается 1.8dp), скругленные сочленения.
 *    Полный запрет на глухие заливки.
 * 2. Zero-Allocation (120 FPS Battery Guard): кэширование путей в remember { Path() },
 *    сброс через .reset() только при изменении габаритов Canvas.
 * 3. Динамическая адаптация: привязка к LocalProtocolColors.current.primary и light
 *    с поддержкой внешнего tint.
 * 4. Разграничение инженерных метафор без типовых Material-шаблонов.
 */
enum class MirrlySystemNetIconType {
    VPN,
    GLOBE,
    SPLIT_TUNNEL,
    POWER,
    TIMER,
    WATCHDOG_CLOCK,
    CPU_ANTIFREEZE,
    OEM_STARTUP,
    AUTO_SELECT,
    GLOBAL_ANYCAST
}

/**
 * Унифицированный диспетчер процедурных векторных иконок MirrlyUI.
 */
@Composable
fun MirrlySystemNetIcon(
    type: MirrlySystemNetIconType,
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    secondaryTint: Color = Color.Unspecified,
    strokeWidth: Dp? = null,
    alpha: Float = 1.0f
) {
    when (type) {
        MirrlySystemNetIconType.VPN -> MirrlyVpnIcon(
            modifier = modifier,
            tint = tint,
            secondaryTint = secondaryTint,
            strokeWidth = strokeWidth ?: 1.5.dp,
            alpha = alpha
        )
        MirrlySystemNetIconType.GLOBE -> MirrlyGlobeIcon(
            modifier = modifier,
            tint = tint,
            secondaryTint = secondaryTint,
            strokeWidth = strokeWidth ?: 1.5.dp,
            alpha = alpha
        )
        MirrlySystemNetIconType.SPLIT_TUNNEL -> MirrlySplitTunnelIcon(
            modifier = modifier,
            tint = tint,
            secondaryTint = secondaryTint,
            strokeWidth = strokeWidth ?: 1.5.dp,
            alpha = alpha
        )
        MirrlySystemNetIconType.POWER -> MirrlyPowerIcon(
            modifier = modifier,
            tint = tint,
            secondaryTint = secondaryTint,
            strokeWidth = strokeWidth ?: 1.8.dp,
            alpha = alpha
        )
        MirrlySystemNetIconType.TIMER -> MirrlyTimerIcon(
            modifier = modifier,
            tint = tint,
            secondaryTint = secondaryTint,
            strokeWidth = strokeWidth ?: 1.5.dp,
            alpha = alpha
        )
        MirrlySystemNetIconType.WATCHDOG_CLOCK -> MirrlyWatchdogClockIcon(
            modifier = modifier,
            tint = tint,
            secondaryTint = secondaryTint,
            strokeWidth = strokeWidth ?: 1.5.dp,
            alpha = alpha
        )
        MirrlySystemNetIconType.CPU_ANTIFREEZE -> MirrlyCpuAntifreezeIcon(
            modifier = modifier,
            tint = tint,
            secondaryTint = secondaryTint,
            strokeWidth = strokeWidth ?: 1.5.dp,
            alpha = alpha
        )
        MirrlySystemNetIconType.OEM_STARTUP -> MirrlyOemStartupIcon(
            modifier = modifier,
            tint = tint,
            secondaryTint = secondaryTint,
            strokeWidth = strokeWidth ?: 1.5.dp,
            alpha = alpha
        )
        MirrlySystemNetIconType.AUTO_SELECT -> MirrlyAutoSelectIcon(
            modifier = modifier,
            tint = tint,
            secondaryTint = secondaryTint,
            strokeWidth = strokeWidth ?: 1.5.dp,
            alpha = alpha
        )
        MirrlySystemNetIconType.GLOBAL_ANYCAST -> MirrlyGlobalAnycastIcon(
            modifier = modifier,
            tint = tint,
            secondaryTint = secondaryTint,
            strokeWidth = strokeWidth ?: 1.5.dp,
            alpha = alpha
        )
    }
}

// ================================================================================================
// 1. ic_vpn — Аппаратный шлюз-модуль с защитным ключом-чипом по центру
// ================================================================================================

/**
 * Процедурный вектор ic_vpn.
 *
 * Инженерная метафора: Контурный аппаратный сетевой шлюз-модуль с терминальными шинами
 * и центральным кремниевым крипто-чипом со встроенным рисунком ключа и боковыми контактами.
 * Не банальный навесной замок!
 *
 * Стандарт: Outline-Only (штрих 1.5dp, скругленные сочленения), Zero-Allocation (120 FPS).
 */
@Composable
fun MirrlyVpnIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    secondaryTint: Color = Color.Unspecified,
    strokeWidth: Dp = 1.5.dp,
    alpha: Float = 1.0f
) {
    val proto = LocalProtocolColors.current
    val effectiveTint = (if (tint != Color.Unspecified) tint else proto.primary).copy(alpha = alpha)
    val effectiveSecondary = (if (secondaryTint != Color.Unspecified) secondaryTint else proto.light).copy(alpha = alpha)

    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val solidStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val chassisPath = remember { Path() }
    val keyChipPath = remember { Path() }
    var lastW by remember { mutableFloatStateOf(-1f) }
    var lastH by remember { mutableFloatStateOf(-1f) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            val sx = w / 24f
            val sy = h / 24f
            val cx = w / 2f
            val cy = h / 2f

            chassisPath.reset()
            // Контурный корпус аппаратного шлюза с фасеточными углами и нижним килем
            chassisPath.moveTo(cx - 7.0f * sx, cy - 6.5f * sy)
            chassisPath.lineTo(cx - 4.0f * sx, cy - 9.2f * sy)
            chassisPath.lineTo(cx + 4.0f * sx, cy - 9.2f * sy)
            chassisPath.lineTo(cx + 7.0f * sx, cy - 6.5f * sy)
            chassisPath.lineTo(cx + 7.0f * sx, cy + 1.5f * sy)
            chassisPath.lineTo(cx, cy + 9.5f * sy)
            chassisPath.lineTo(cx - 7.0f * sx, cy + 1.5f * sy)
            chassisPath.close()

            // Верхние терминальные интерфейсные шины шлюза
            chassisPath.moveTo(cx - 2.5f * sx, cy - 9.2f * sy)
            chassisPath.lineTo(cx - 2.5f * sx, cy - 11.2f * sy)
            chassisPath.moveTo(cx + 2.5f * sx, cy - 9.2f * sy)
            chassisPath.lineTo(cx + 2.5f * sx, cy - 11.2f * sy)

            keyChipPath.reset()
            // Центральный кремниевый крипто-чип (корпус микросхемы)
            val chipRadius = 1.0f * sx
            keyChipPath.addRoundRect(
                RoundRect(
                    left = cx - 3.4f * sx,
                    top = cy - 3.5f * sy,
                    right = cx + 3.4f * sx,
                    bottom = cy + 3.5f * sy,
                    cornerRadius = CornerRadius(chipRadius, chipRadius)
                )
            )

            // Контурный рисунок ключа шифрования на кристалле чипа
            keyChipPath.moveTo(cx, cy - 2.2f * sy)
            keyChipPath.lineTo(cx, cy + 2.5f * sy)
            keyChipPath.addOval(
                Rect(
                    left = cx - 1.1f * sx,
                    top = cy - 2.2f * sy - 1.1f * sy,
                    right = cx + 1.1f * sx,
                    bottom = cy - 2.2f * sy + 1.1f * sy
                )
            )
            keyChipPath.moveTo(cx, cy + 0.8f * sy)
            keyChipPath.lineTo(cx + 1.8f * sx, cy + 0.8f * sy)
            keyChipPath.moveTo(cx, cy + 2.0f * sy)
            keyChipPath.lineTo(cx + 1.4f * sx, cy + 2.0f * sy)

            // Боковые контактные проводники, соединяющие чип с шиной шлюза
            keyChipPath.moveTo(cx - 3.4f * sx, cy - 1.5f * sy)
            keyChipPath.lineTo(cx - 5.5f * sx, cy - 1.5f * sy)
            keyChipPath.moveTo(cx - 3.4f * sx, cy + 1.5f * sy)
            keyChipPath.lineTo(cx - 5.5f * sx, cy + 1.5f * sy)

            keyChipPath.moveTo(cx + 3.4f * sx, cy - 1.5f * sy)
            keyChipPath.lineTo(cx + 5.5f * sx, cy - 1.5f * sy)
            keyChipPath.moveTo(cx + 3.4f * sx, cy + 1.5f * sy)
            keyChipPath.lineTo(cx + 5.5f * sx, cy + 1.5f * sy)
        }

        drawPath(path = chassisPath, color = effectiveTint, style = solidStroke)
        drawPath(path = keyChipPath, color = effectiveSecondary, style = solidStroke)
    }
}

@Composable
fun MirrlyIconVpn(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    secondaryTint: Color = LocalProtocolColors.current.light
) = MirrlyVpnIcon(modifier = modifier, tint = tint, secondaryTint = secondaryTint)

// ================================================================================================
// 2. ic_globe — Векторная проекция геоида со штриховыми меридианами
// ================================================================================================

/**
 * Процедурный вектор ic_globe.
 *
 * Инженерная метафора: Векторная проекция геоида глобальной сети со сплошным контуром сферы
 * и штриховыми экваториальными и полярными меридианами (каркасный стиль без глухой заливки материков).
 *
 * Стандарт: Outline-Only (штрих 1.5dp), Zero-Allocation (120 FPS).
 */
@Composable
fun MirrlyGlobeIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    secondaryTint: Color = Color.Unspecified,
    strokeWidth: Dp = 1.5.dp,
    alpha: Float = 1.0f
) {
    val proto = LocalProtocolColors.current
    val effectiveTint = (if (tint != Color.Unspecified) tint else proto.primary).copy(alpha = alpha)
    val effectiveSecondary = (if (secondaryTint != Color.Unspecified) secondaryTint else proto.light).copy(alpha = alpha * 0.85f)

    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val solidStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val dashedStroke = remember(strokeWidthPx, density) {
        val dashPx = with(density) { 2.4.dp.toPx() }
        val gapPx = with(density) { 1.8.dp.toPx() }
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx), 0f)
        )
    }

    val spherePath = remember { Path() }
    val meridiansPath = remember { Path() }
    var lastW by remember { mutableFloatStateOf(-1f) }
    var lastH by remember { mutableFloatStateOf(-1f) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            val sx = w / 24f
            val sy = h / 24f
            val cx = w / 2f
            val cy = h / 2f
            val r = 9.2f * min(sx, sy)

            spherePath.reset()
            // Внешний контур экваториального сечения геоида
            spherePath.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
            // Маркеры полярной оси вращения
            spherePath.moveTo(cx, cy - r)
            spherePath.lineTo(cx, cy - r - 1.4f * sy)
            spherePath.moveTo(cx, cy + r)
            spherePath.lineTo(cx, cy + r + 1.4f * sy)

            meridiansPath.reset()
            // Экваториальная параллель (штриховая линия)
            meridiansPath.moveTo(cx - r, cy)
            meridiansPath.lineTo(cx + r, cy)

            // Северная широтная параллель
            meridiansPath.moveTo(cx - 7.6f * sx, cy - 4.8f * sy)
            meridiansPath.quadraticBezierTo(cx, cy - 3.4f * sy, cx + 7.6f * sx, cy - 4.8f * sy)

            // Южная широтная параллель
            meridiansPath.moveTo(cx - 7.6f * sx, cy + 4.8f * sy)
            meridiansPath.quadraticBezierTo(cx, cy + 3.4f * sy, cx + 7.6f * sx, cy + 4.8f * sy)

            // Центральный вертикальный нулевой меридиан
            meridiansPath.moveTo(cx, cy - r)
            meridiansPath.lineTo(cx, cy + r)

            // Левый эллиптический полярный меридиан
            meridiansPath.moveTo(cx, cy - r)
            meridiansPath.cubicTo(cx - 7.2f * sx, cy - 5.5f * sy, cx - 7.2f * sx, cy + 5.5f * sy, cx, cy + r)

            // Правый эллиптический полярный меридиан
            meridiansPath.moveTo(cx, cy - r)
            meridiansPath.cubicTo(cx + 7.2f * sx, cy - 5.5f * sy, cx + 7.2f * sx, cy + 5.5f * sy, cx, cy + r)
        }

        drawPath(path = spherePath, color = effectiveTint, style = solidStroke)
        drawPath(path = meridiansPath, color = effectiveSecondary, style = dashedStroke)
    }
}

@Composable
fun MirrlyIconGlobe(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    secondaryTint: Color = LocalProtocolColors.current.light
) = MirrlyGlobeIcon(modifier = modifier, tint = tint, secondaryTint = secondaryTint)

// ================================================================================================
// 3. ic_split_tunnel — Магистральный оптоволоконный канал раздельного туннелирования
// ================================================================================================

/**
 * Процедурный вектор ic_split_tunnel.
 *
 * Инженерная метафора: Магистральный оптоволоконный канал трафика, раздваивающийся на защищенный
 * контурный рукав (туннелируемый зашифрованный маршрут) и открытый пунктирный обходной маршрут (bypass).
 *
 * Стандарт: Outline-Only (штрих 1.5dp), Zero-Allocation (120 FPS).
 */
@Composable
fun MirrlySplitTunnelIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    secondaryTint: Color = Color.Unspecified,
    strokeWidth: Dp = 1.5.dp,
    alpha: Float = 1.0f
) {
    val proto = LocalProtocolColors.current
    val effectiveTint = (if (tint != Color.Unspecified) tint else proto.primary).copy(alpha = alpha)
    val effectiveSecondary = (if (secondaryTint != Color.Unspecified) secondaryTint else proto.light).copy(alpha = alpha * 0.75f)

    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val solidStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val dashedStroke = remember(strokeWidthPx, density) {
        val dashPx = with(density) { 2.6.dp.toPx() }
        val gapPx = with(density) { 2.0.dp.toPx() }
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx), 0f)
        )
    }

    val protectedPath = remember { Path() }
    val bypassPath = remember { Path() }
    var lastW by remember { mutableFloatStateOf(-1f) }
    var lastH by remember { mutableFloatStateOf(-1f) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            val sx = w / 24f
            val sy = h / 24f
            val cx = w / 2f

            protectedPath.reset()
            // Базовый оптоволоконный излучатель-узел магистрали
            val nodeRadius = 1.2f * sx
            protectedPath.addOval(
                Rect(
                    left = cx - nodeRadius,
                    top = 20.5f * sy - nodeRadius,
                    right = cx + nodeRadius,
                    bottom = 20.5f * sy + nodeRadius
                )
            )

            // Магистральный канал трафика, поднимающийся к развилке
            protectedPath.moveTo(cx, 19.3f * sy)
            protectedPath.lineTo(cx, 13.0f * sy)

            // Левый рукав: защищенный туннель (сплошная линия со стрелкой-шевроном)
            protectedPath.cubicTo(cx, 9.2f * sy, cx - 5.5f * sx, 10.0f * sy, cx - 5.5f * sx, 5.5f * sy)
            protectedPath.lineTo(cx - 5.5f * sx, 3.2f * sy)
            // Стрелка защищенного рукава
            protectedPath.moveTo(cx - 8.0f * sx, 5.6f * sy)
            protectedPath.lineTo(cx - 5.5f * sx, 3.2f * sy)
            protectedPath.lineTo(cx - 3.0f * sx, 5.6f * sy)
            // Кольцо шифрования защищенного рукава
            protectedPath.moveTo(cx - 7.0f * sx, 8.5f * sy)
            protectedPath.lineTo(cx - 4.0f * sx, 8.5f * sy)

            bypassPath.reset()
            // Правый рукав: открытый обходной маршрут (пунктирный канал)
            bypassPath.moveTo(cx, 13.0f * sy)
            bypassPath.cubicTo(cx, 9.2f * sy, cx + 5.5f * sx, 10.0f * sy, cx + 5.5f * sx, 5.5f * sy)
            bypassPath.lineTo(cx + 5.5f * sx, 3.2f * sy)
            // Стрелка открытого маршрута
            bypassPath.moveTo(cx + 3.0f * sx, 5.6f * sy)
            bypassPath.lineTo(cx + 5.5f * sx, 3.2f * sy)
            bypassPath.lineTo(cx + 8.0f * sx, 5.6f * sy)
        }

        drawPath(path = protectedPath, color = effectiveTint, style = solidStroke)
        drawPath(path = bypassPath, color = effectiveSecondary, style = dashedStroke)
    }
}

@Composable
fun MirrlyIconSplitTunnel(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    secondaryTint: Color = LocalProtocolColors.current.light
) = MirrlySplitTunnelIcon(modifier = modifier, tint = tint, secondaryTint = secondaryTint)

// ================================================================================================
// 4. ic_power — Разомкнутая снизу окружность с выступающим импульсным штырем
// ================================================================================================

/**
 * Процедурный вектор ic_power.
 *
 * Инженерная метафора: Разомкнутая снизу силовая окружность с вертикальным импульсным штырем,
 * выходящим за пределы контура ровно на 2dp (символ высоковольтного тумблера управления питанием демона).
 *
 * Стандарт: Outline-Only (допускается штрих 1.8dp, скругленный торец), Zero-Allocation (120 FPS).
 */
@Composable
fun MirrlyPowerIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    secondaryTint: Color = Color.Unspecified,
    strokeWidth: Dp = 1.8.dp,
    alpha: Float = 1.0f
) {
    val proto = LocalProtocolColors.current
    val effectiveTint = (if (tint != Color.Unspecified) tint else proto.primary).copy(alpha = alpha)
    val effectiveSecondary = (if (secondaryTint != Color.Unspecified) secondaryTint else proto.light).copy(alpha = alpha)

    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val powerStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val arcPath = remember { Path() }
    val pinPath = remember { Path() }
    var lastW by remember { mutableFloatStateOf(-1f) }
    var lastH by remember { mutableFloatStateOf(-1f) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            val sx = w / 24f
            val sy = h / 24f
            val cx = w / 2f
            val cy = 11.2f * sy
            val r = 7.6f * min(sx, sy)

            arcPath.reset()
            // Разомкнутая снизу окружность: угол разрыва внизу вокруг 90° (от 55° до 125°).
            // Дуга стартует с 125° и разворачивается по часовой стрелке через верх до 55° (290° развертки).
            arcPath.arcTo(
                rect = Rect(cx - r, cy - r, cx + r, cy + r),
                startAngleDegrees = 125f,
                sweepAngleDegrees = 290f,
                forceMoveTo = true
            )

            pinPath.reset()
            // Вертикальный импульсный штырь, выступающий за пределы окружности на 2dp
            val protrusionPx = 2.0f * sy
            val pinTopY = cy - r - protrusionPx
            pinPath.moveTo(cx, pinTopY)
            pinPath.lineTo(cx, cy + 2.4f * sy)

            // Горизонтальный микро-контакт в основании импульсного штыря
            pinPath.moveTo(cx - 1.2f * sx, cy + 2.4f * sy)
            pinPath.lineTo(cx + 1.2f * sx, cy + 2.4f * sy)
        }

        drawPath(path = arcPath, color = effectiveTint, style = powerStroke)
        drawPath(path = pinPath, color = effectiveSecondary, style = powerStroke)
    }
}

@Composable
fun MirrlyIconPower(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    secondaryTint: Color = LocalProtocolColors.current.light
) = MirrlyPowerIcon(modifier = modifier, tint = tint, secondaryTint = secondaryTint)

// ================================================================================================
// 5. ic_timer — Прецизионный хронометр с верхней кнопкой пуска
// ================================================================================================

/**
 * Процедурный вектор ic_timer.
 *
 * Инженерная метафора: Высокоточный хронометр с контурным корпусом, верхней кнопкой пуска и одной
 * минутной стрелкой, строго зафиксированной на 12 часов.
 * Отличие от watchdog_clock: классический измеритель интервалов таймаута без шестеренчатого венца.
 *
 * Стандарт: Outline-Only (штрих 1.5dp, скругленные сочленения), Zero-Allocation (120 FPS).
 */
@Composable
fun MirrlyTimerIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    secondaryTint: Color = Color.Unspecified,
    strokeWidth: Dp = 1.5.dp,
    alpha: Float = 1.0f
) {
    val proto = LocalProtocolColors.current
    val effectiveTint = (if (tint != Color.Unspecified) tint else proto.primary).copy(alpha = alpha)
    val effectiveSecondary = (if (secondaryTint != Color.Unspecified) secondaryTint else proto.light).copy(alpha = alpha)

    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val solidStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val housingPath = remember { Path() }
    val handPath = remember { Path() }
    var lastW by remember { mutableFloatStateOf(-1f) }
    var lastH by remember { mutableFloatStateOf(-1f) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            val sx = w / 24f
            val sy = h / 24f
            val cx = w / 2f
            val cy = 13.2f * sy
            val r = 7.6f * min(sx, sy)

            housingPath.reset()
            // Контурный корпус циферблата хронометра
            housingPath.addOval(Rect(cx - r, cy - r, cx + r, cy + r))

            // Верхняя кнопка пуска хронометра (шток и нажимная пластина)
            housingPath.moveTo(cx, cy - r)
            housingPath.lineTo(cx, 3.8f * sy)
            housingPath.moveTo(cx - 2.8f * sx, 3.2f * sy)
            housingPath.lineTo(cx + 2.8f * sx, 3.2f * sy)

            // Кардинальные метки циферблата на 3, 6 и 9 часов
            housingPath.moveTo(cx + r - 1.4f * sx, cy)
            housingPath.lineTo(cx + r, cy)
            housingPath.moveTo(cx, cy + r - 1.4f * sy)
            housingPath.lineTo(cx, cy + r)
            housingPath.moveTo(cx - r, cy)
            housingPath.lineTo(cx - r + 1.4f * sx, cy)

            handPath.reset()
            // Центральная ось вращения (арбор)
            val pivotR = 1.0f * sx
            handPath.addOval(Rect(cx - pivotR, cy - pivotR, cx + pivotR, cy + pivotR))

            // Единственная минутная стрелка, указывающая на 12 часов
            handPath.moveTo(cx, cy)
            handPath.lineTo(cx, cy - 5.5f * sy)
        }

        drawPath(path = housingPath, color = effectiveTint, style = solidStroke)
        drawPath(path = handPath, color = effectiveSecondary, style = solidStroke)
    }
}

@Composable
fun MirrlyIconTimer(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    secondaryTint: Color = LocalProtocolColors.current.light
) = MirrlyTimerIcon(modifier = modifier, tint = tint, secondaryTint = secondaryTint)

// ================================================================================================
// 6. ic_watchdog_clock — Циферблат с венцом шестерни и молнией пульса перезапуска
// ================================================================================================

/**
 * Процедурный вектор ic_watchdog_clock.
 *
 * Инженерная метафора: Циферблат системного сторожевого таймера (Watchdog) с наружным зубчатым венцом
 * механической шестерни и контурной молнией пульса по центру (символ аппаратного воскрешения службы).
 *
 * Стандарт: Outline-Only (штрих 1.5dp, скругленные сочленения), Zero-Allocation (120 FPS).
 */
@Composable
fun MirrlyWatchdogClockIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    secondaryTint: Color = Color.Unspecified,
    strokeWidth: Dp = 1.5.dp,
    alpha: Float = 1.0f
) {
    val proto = LocalProtocolColors.current
    val effectiveTint = (if (tint != Color.Unspecified) tint else proto.primary).copy(alpha = alpha)
    val effectiveSecondary = (if (secondaryTint != Color.Unspecified) secondaryTint else proto.light).copy(alpha = alpha)

    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val solidStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val gearPath = remember { Path() }
    val pulseLightningPath = remember { Path() }
    var lastW by remember { mutableFloatStateOf(-1f) }
    var lastH by remember { mutableFloatStateOf(-1f) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            val sx = w / 24f
            val sy = h / 24f
            val cx = w / 2f
            val cy = h / 2f
            val baseScale = min(sx, sy)

            val rRoot = 7.5f * baseScale
            val rTip = 9.8f * baseScale
            val teethCount = 8
            val angleStep = (2.0 * PI / teethCount).toFloat()

            gearPath.reset()
            // Процедурный расчет наружного зубчатого венца шестерни Watchdog
            for (i in 0 until teethCount) {
                val a0 = i * angleStep
                val a1 = a0 + angleStep * 0.28f
                val a2 = a0 + angleStep * 0.44f
                val a3 = a0 + angleStep * 0.84f

                val x0 = cx + rTip * cos(a0)
                val y0 = cy + rTip * sin(a0)
                val x1 = cx + rTip * cos(a1)
                val y1 = cy + rTip * sin(a1)
                val x2 = cx + rRoot * cos(a2)
                val y2 = cy + rRoot * sin(a2)
                val x3 = cx + rRoot * cos(a3)
                val y3 = cy + rRoot * sin(a3)

                if (i == 0) {
                    gearPath.moveTo(x0, y0)
                } else {
                    gearPath.lineTo(x0, y0)
                }
                gearPath.lineTo(x1, y1)
                gearPath.lineTo(x2, y2)
                gearPath.lineTo(x3, y3)
            }
            gearPath.close()

            // Внутреннее циферблатное кольцо таймера
            val dialR = 5.8f * baseScale
            gearPath.addOval(Rect(cx - dialR, cy - dialR, cx + dialR, cy + dialR))

            pulseLightningPath.reset()
            // Контурная молния импульса аппаратного перезапуска (Watchdog Pulse)
            pulseLightningPath.moveTo(cx + 1.4f * sx, cy - 4.4f * sy)
            pulseLightningPath.lineTo(cx - 1.4f * sx, cy - 0.2f * sy)
            pulseLightningPath.lineTo(cx + 1.2f * sx, cy - 0.2f * sy)
            pulseLightningPath.lineTo(cx - 1.6f * sx, cy + 4.6f * sy)
        }

        drawPath(path = gearPath, color = effectiveTint, style = solidStroke)
        drawPath(path = pulseLightningPath, color = effectiveSecondary, style = solidStroke)
    }
}

@Composable
fun MirrlyIconWatchdogClock(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    secondaryTint: Color = LocalProtocolColors.current.light
) = MirrlyWatchdogClockIcon(modifier = modifier, tint = tint, secondaryTint = secondaryTint)

// ================================================================================================
// 7. ic_cpu_antifreeze — Процессорный чип с криогенным кристаллом без троттлинга
// ================================================================================================

/**
 * Процедурный вектор ic_cpu_antifreeze.
 *
 * Инженерная метафора: Процессорный чип (квадратный корпус с 12 боковыми выводами шин), внутри
 * которого расположен контурный кристалл охлаждения без термического троттлинга.
 *
 * Стандарт: Outline-Only (штрих 1.5dp, скругленные сочленения), Zero-Allocation (120 FPS).
 */
@Composable
fun MirrlyCpuAntifreezeIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    secondaryTint: Color = Color.Unspecified,
    strokeWidth: Dp = 1.5.dp,
    alpha: Float = 1.0f
) {
    val proto = LocalProtocolColors.current
    val effectiveTint = (if (tint != Color.Unspecified) tint else proto.primary).copy(alpha = alpha)
    val effectiveSecondary = (if (secondaryTint != Color.Unspecified) secondaryTint else proto.light).copy(alpha = alpha)

    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val solidStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val packagePath = remember { Path() }
    val crystalPath = remember { Path() }
    var lastW by remember { mutableFloatStateOf(-1f) }
    var lastH by remember { mutableFloatStateOf(-1f) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            val sx = w / 24f
            val sy = h / 24f
            val cx = w / 2f
            val cy = h / 2f

            packagePath.reset()
            // Корпус процессора со скругленными углами
            val cornerR = 2.0f * sx
            packagePath.addRoundRect(
                RoundRect(
                    left = cx - 6.0f * sx,
                    top = cy - 6.0f * sy,
                    right = cx + 6.0f * sx,
                    bottom = cy + 6.0f * sy,
                    cornerRadius = CornerRadius(cornerR, cornerR)
                )
            )

            // Верхние выводы шины (ножки)
            packagePath.moveTo(cx - 3.0f * sx, cy - 6.0f * sy)
            packagePath.lineTo(cx - 3.0f * sx, cy - 9.8f * sy)
            packagePath.moveTo(cx, cy - 6.0f * sy)
            packagePath.lineTo(cx, cy - 9.8f * sy)
            packagePath.moveTo(cx + 3.0f * sx, cy - 6.0f * sy)
            packagePath.lineTo(cx + 3.0f * sx, cy - 9.8f * sy)

            // Нижние выводы шины
            packagePath.moveTo(cx - 3.0f * sx, cy + 6.0f * sy)
            packagePath.lineTo(cx - 3.0f * sx, cy + 9.8f * sy)
            packagePath.moveTo(cx, cy + 6.0f * sy)
            packagePath.lineTo(cx, cy + 9.8f * sy)
            packagePath.moveTo(cx + 3.0f * sx, cy + 6.0f * sy)
            packagePath.lineTo(cx + 3.0f * sx, cy + 9.8f * sy)

            // Левые выводы шины
            packagePath.moveTo(cx - 6.0f * sx, cy - 3.0f * sy)
            packagePath.lineTo(cx - 9.8f * sx, cy - 3.0f * sy)
            packagePath.moveTo(cx - 6.0f * sx, cy)
            packagePath.lineTo(cx - 9.8f * sx, cy)
            packagePath.moveTo(cx - 6.0f * sx, cy + 3.0f * sy)
            packagePath.lineTo(cx - 9.8f * sx, cy + 3.0f * sy)

            // Правые выводы шины
            packagePath.moveTo(cx + 6.0f * sx, cy - 3.0f * sy)
            packagePath.lineTo(cx + 9.8f * sx, cy - 3.0f * sy)
            packagePath.moveTo(cx + 6.0f * sx, cy)
            packagePath.lineTo(cx + 9.8f * sx, cy)
            packagePath.moveTo(cx + 6.0f * sx, cy + 3.0f * sy)
            packagePath.lineTo(cx + 9.8f * sx, cy + 3.0f * sy)

            crystalPath.reset()
            // Фасеточный кристалл кремния без термического троттлинга
            crystalPath.moveTo(cx, cy - 3.4f * sy)
            crystalPath.lineTo(cx + 3.4f * sx, cy)
            crystalPath.lineTo(cx, cy + 3.4f * sy)
            crystalPath.lineTo(cx - 3.4f * sx, cy)
            crystalPath.close()

            // Внутренние кристаллические грани теплоотвода (антифриз-решетка)
            crystalPath.moveTo(cx, cy - 3.4f * sy)
            crystalPath.lineTo(cx, cy + 3.4f * sy)
            crystalPath.moveTo(cx - 3.4f * sx, cy)
            crystalPath.lineTo(cx + 3.4f * sx, cy)
            crystalPath.moveTo(cx - 1.4f * sx, cy - 1.4f * sy)
            crystalPath.lineTo(cx + 1.4f * sx, cy + 1.4f * sy)
            crystalPath.moveTo(cx + 1.4f * sx, cy - 1.4f * sy)
            crystalPath.lineTo(cx - 1.4f * sx, cy + 1.4f * sy)
        }

        drawPath(path = packagePath, color = effectiveTint, style = solidStroke)
        drawPath(path = crystalPath, color = effectiveSecondary, style = solidStroke)
    }
}

@Composable
fun MirrlyIconCpuAntifreeze(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    secondaryTint: Color = LocalProtocolColors.current.light
) = MirrlyCpuAntifreezeIcon(modifier = modifier, tint = tint, secondaryTint = secondaryTint)

// ================================================================================================
// 8. ic_oem_startup — Контурная ракета-стабилизатор преодоления барьера ОС
// ================================================================================================

/**
 * Процедурный вектор ic_oem_startup.
 *
 * Инженерная метафора: Контурная ракета-стабилизатор, пробивающая защитный энергетический барьер
 * операционной системы (символ преодоления агрессивных OEM-киллеров фоновых служб).
 *
 * Стандарт: Outline-Only (штрих 1.5dp, скругленные сочленения), Zero-Allocation (120 FPS).
 */
@Composable
fun MirrlyOemStartupIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    secondaryTint: Color = Color.Unspecified,
    strokeWidth: Dp = 1.5.dp,
    alpha: Float = 1.0f
) {
    val proto = LocalProtocolColors.current
    val effectiveTint = (if (tint != Color.Unspecified) tint else proto.primary).copy(alpha = alpha)
    val effectiveSecondary = (if (secondaryTint != Color.Unspecified) secondaryTint else proto.light).copy(alpha = alpha)

    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val solidStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val dashedStroke = remember(strokeWidthPx, density) {
        val dashPx = with(density) { 2.4.dp.toPx() }
        val gapPx = with(density) { 1.8.dp.toPx() }
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx), 0f)
        )
    }

    val rocketPath = remember { Path() }
    val barrierPath = remember { Path() }
    var lastW by remember { mutableFloatStateOf(-1f) }
    var lastH by remember { mutableFloatStateOf(-1f) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            val sx = w / 24f
            val sy = h / 24f
            val cx = w / 2f

            barrierPath.reset()
            // Защитный энергетический барьер ОС с пробоем по центру (штриховой дуговой контур)
            barrierPath.moveTo(2.5f * sx, 13.5f * sy)
            barrierPath.quadraticBezierTo(5.2f * sx, 10.8f * sy, 8.2f * sx, 10.5f * sy)

            barrierPath.moveTo(15.8f * sx, 10.5f * sy)
            barrierPath.quadraticBezierTo(18.8f * sx, 10.8f * sy, 21.5f * sx, 13.5f * sy)

            // Засечки фронта пробоя барьера
            barrierPath.moveTo(8.2f * sx, 10.5f * sy)
            barrierPath.lineTo(7.2f * sx, 9.0f * sy)
            barrierPath.moveTo(15.8f * sx, 10.5f * sy)
            barrierPath.lineTo(16.8f * sx, 9.0f * sy)

            rocketPath.reset()
            // Аэродинамический фюзеляж ракеты-стабилизатора
            rocketPath.moveTo(cx, 2.0f * sy)
            rocketPath.cubicTo(cx - 1.4f * sx, 5.0f * sy, cx - 2.8f * sx, 9.5f * sy, cx - 3.0f * sx, 14.2f * sy)
            rocketPath.lineTo(cx + 3.0f * sx, 14.2f * sy)
            rocketPath.cubicTo(cx + 2.8f * sx, 9.5f * sy, cx + 1.4f * sx, 5.0f * sy, cx, 2.0f * sy)
            rocketPath.close()

            // Стреловидные дельтовидные стабилизаторы (крылья)
            rocketPath.moveTo(cx - 3.0f * sx, 11.2f * sy)
            rocketPath.lineTo(cx - 6.5f * sx, 15.5f * sy)
            rocketPath.lineTo(cx - 3.0f * sx, 14.5f * sy)

            rocketPath.moveTo(cx + 3.0f * sx, 11.2f * sy)
            rocketPath.lineTo(cx + 6.5f * sx, 15.5f * sy)
            rocketPath.lineTo(cx + 3.0f * sx, 14.5f * sy)

            // Сверхзвуковой факел сопла двигателя (Mach Diamond)
            rocketPath.moveTo(cx - 1.6f * sx, 14.8f * sy)
            rocketPath.lineTo(cx, 21.8f * sy)
            rocketPath.lineTo(cx + 1.6f * sx, 14.8f * sy)

            // Контурный навигационный иллюминатор
            val portR = 1.1f * sx
            rocketPath.addOval(Rect(cx - portR, 7.5f * sy - portR, cx + portR, 7.5f * sy + portR))
        }

        drawPath(path = barrierPath, color = effectiveSecondary, style = dashedStroke)
        drawPath(path = rocketPath, color = effectiveTint, style = solidStroke)
    }
}

@Composable
fun MirrlyIconOemStartup(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    secondaryTint: Color = LocalProtocolColors.current.light
) = MirrlyOemStartupIcon(modifier = modifier, tint = tint, secondaryTint = secondaryTint)

// ================================================================================================
// 9. ic_auto_select — Векторный локатор автовыбора серверов (Кибер-цикл & Квантовый ретикул)
// ================================================================================================

/**
 * Процедурный вектор ic_auto_select (/mirrly-icons standard).
 * Инженерная метафора: Два сопряженных аэродинамических вектора обхода (телеметрия циклического сканирования)
 * с направляющими микро-стрелками, охватывающие центральный квантовый ретикул автонаведения с кардинальными рисками.
 *
 * Стандарт: Outline-Only (штрих 1.5dp), Zero-Allocation (120 FPS Battery Guard), адаптивная палитра.
 */
@Composable
fun MirrlyAutoSelectIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    secondaryTint: Color = Color.Unspecified,
    strokeWidth: Dp = 1.5.dp,
    alpha: Float = 1.0f
) {
    val proto = LocalProtocolColors.current
    val effectiveTint = (if (tint != Color.Unspecified) tint else proto.primary).copy(alpha = alpha)
    val effectiveSecondary = (if (secondaryTint != Color.Unspecified) secondaryTint else proto.light).copy(alpha = alpha * 0.85f)

    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val solidStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val topArcPath = remember { Path() }
    val bottomArcPath = remember { Path() }
    val reticlePath = remember { Path() }
    var lastW by remember { mutableFloatStateOf(-1f) }
    var lastH by remember { mutableFloatStateOf(-1f) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            val sx = w / 24f
            val sy = h / 24f
            val cx = w / 2f
            val cy = h / 2f

            topArcPath.reset()
            // Верхняя дуга циклического автосканирования со стрелкой
            topArcPath.moveTo(cx - 7.5f * sx, cy - 0.5f * sy)
            topArcPath.cubicTo(cx - 7.5f * sx, cy - 6.5f * sy, cx + 1.0f * sx, cy - 7.5f * sy, cx + 7.0f * sx, cy - 1.5f * sy)
            topArcPath.lineTo(cx + 7.0f * sx, cy - 5.5f * sy)
            topArcPath.moveTo(cx + 7.0f * sx, cy - 1.5f * sy)
            topArcPath.lineTo(cx + 3.0f * sx, cy - 1.5f * sy)

            bottomArcPath.reset()
            // Нижняя дуга циклического автосканирования со стрелкой
            bottomArcPath.moveTo(cx + 7.5f * sx, cy + 0.5f * sy)
            bottomArcPath.cubicTo(cx + 7.5f * sx, cy + 6.5f * sy, cx - 1.0f * sx, cy + 7.5f * sy, cx - 7.0f * sx, cy + 1.5f * sy)
            bottomArcPath.lineTo(cx - 7.0f * sx, cy + 5.5f * sy)
            bottomArcPath.moveTo(cx - 7.0f * sx, cy + 1.5f * sy)
            bottomArcPath.lineTo(cx - 3.0f * sx, cy + 1.5f * sy)

            reticlePath.reset()
            // Кардинальные риски целеуказателя (Север, Юг, Запад, Восток)
            reticlePath.moveTo(cx, cy - 4.5f * sy)
            reticlePath.lineTo(cx, cy - 2.0f * sy)
            reticlePath.moveTo(cx, cy + 2.0f * sy)
            reticlePath.lineTo(cx, cy + 4.5f * sy)
            reticlePath.moveTo(cx - 4.5f * sx, cy)
            reticlePath.lineTo(cx - 2.0f * sx, cy)
            reticlePath.moveTo(cx + 2.0f * sx, cy)
            reticlePath.lineTo(cx + 4.5f * sx, cy)

            // Центральное квантовое ядро фокуса
            val coreR = 0.9f * min(sx, sy)
            reticlePath.addOval(Rect(cx - coreR, cy - coreR, cx + coreR, cy + coreR))
        }

        drawPath(path = topArcPath, color = effectiveTint, style = solidStroke)
        drawPath(path = bottomArcPath, color = effectiveTint, style = solidStroke)
        drawPath(path = reticlePath, color = effectiveSecondary, style = solidStroke)
    }
}

/**
 * Процедурный бейдж автовыбора серверов MirrlyUI (20x13 dp) для карточек списков.
 * Полностью заменяет текстовые заглушки в соответствии со стандартом /mirrly-icons.
 */
@Composable
fun AutoSelectBadgeIcon(
    modifier: Modifier = Modifier.size(width = 20.dp, height = 13.dp),
    accentColor: Color = LocalProtocolColors.current.primary
) {
    val roundedShape = remember { RoundedCornerShape(3.dp) }
    Box(
        modifier = modifier
            .clip(roundedShape)
            .background(accentColor.copy(alpha = 0.12f))
            .border(0.8.dp, accentColor.copy(alpha = 0.55f), roundedShape)
            .padding(1.5.dp),
        contentAlignment = Alignment.Center
    ) {
        MirrlyAutoSelectIcon(
            modifier = Modifier.fillMaxSize(),
            tint = accentColor,
            secondaryTint = accentColor.copy(alpha = 0.75f),
            strokeWidth = 1.0.dp
        )
    }
}

// ================================================================================================
// 10. ic_global_anycast — Векторная сфера Anycast-маршрутизации (Глобальный меш & POP-маяки)
// ================================================================================================

/**
 * Процедурный вектор ic_global_anycast (/mirrly-icons standard).
 * Инженерная метафора: Векторная сфера Anycast-топологии (BGP Multi-POP Ingestion).
 * Контур экватора, 3D эллиптический меридиан, периферийные Anycast-лучи ингресса
 * и центральный активный POP-узел Cloudflare с квантовым маркером.
 *
 * Стандарт: Outline-Only (штрих 1.5dp), Zero-Allocation (120 FPS Battery Guard), адаптивная палитра.
 */
@Composable
fun MirrlyGlobalAnycastIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    secondaryTint: Color = Color.Unspecified,
    strokeWidth: Dp = 1.5.dp,
    alpha: Float = 1.0f
) {
    val proto = LocalProtocolColors.current
    val effectiveTint = (if (tint != Color.Unspecified) tint else proto.primary).copy(alpha = alpha)
    val effectiveSecondary = (if (secondaryTint != Color.Unspecified) secondaryTint else proto.light).copy(alpha = alpha * 0.85f)

    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val solidStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val thinStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val globePath = remember { Path() }
    val meshRaysPath = remember { Path() }
    val beaconsPath = remember { Path() }
    var lastW by remember { mutableFloatStateOf(-1f) }
    var lastH by remember { mutableFloatStateOf(-1f) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            val sx = w / 24f
            val sy = h / 24f
            val cx = w / 2f
            val cy = h / 2f
            val r = 7.5f * min(sx, sy)

            globePath.reset()
            // 1. Внешний контур сферы Anycast
            globePath.addOval(Rect(cx - r, cy - r, cx + r, cy + r))

            // 2. Экваториальная плоскость
            globePath.moveTo(cx - r, cy)
            globePath.lineTo(cx + r, cy)

            // 3. 3D проекция нулевого меридиана (эллипс)
            globePath.addOval(Rect(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r))

            meshRaysPath.reset()
            // 4. Anycast-лучи ингресса (векторы маршрутизации в ближайший POP)
            // Северо-западный луч
            meshRaysPath.moveTo(cx - 10.5f * sx, cy - 8.5f * sy)
            meshRaysPath.lineTo(cx - 6.2f * sx, cy - 4.5f * sy)
            meshRaysPath.moveTo(cx - 8.5f * sx, cy - 4.5f * sy)
            meshRaysPath.lineTo(cx - 6.2f * sx, cy - 4.5f * sy)
            meshRaysPath.lineTo(cx - 6.2f * sx, cy - 6.8f * sy)

            // Юго-восточный луч
            meshRaysPath.moveTo(cx + 10.5f * sx, cy + 8.5f * sy)
            meshRaysPath.lineTo(cx + 6.2f * sx, cy + 4.5f * sy)
            meshRaysPath.moveTo(cx + 8.5f * sx, cy + 4.5f * sy)
            meshRaysPath.lineTo(cx + 6.2f * sx, cy + 4.5f * sy)
            meshRaysPath.lineTo(cx + 6.2f * sx, cy + 6.8f * sy)

            beaconsPath.reset()
            // 5. Центральный активный POP edge-сервер
            val popR = 1.0f * min(sx, sy)
            beaconsPath.addOval(Rect(cx - popR, cy - popR, cx + popR, cy + popR))

            // Дополнительные периферийные Anycast-узлы меша
            val nodeR = 0.65f * min(sx, sy)
            beaconsPath.addOval(Rect(cx - 3.8f * sx - nodeR, cy - 2.5f * sy - nodeR, cx - 3.8f * sx + nodeR, cy - 2.5f * sy + nodeR))
            beaconsPath.addOval(Rect(cx + 3.8f * sx - nodeR, cy + 2.5f * sy - nodeR, cx + 3.8f * sx + nodeR, cy + 2.5f * sy + nodeR))
        }

        drawPath(path = globePath, color = effectiveTint, style = solidStroke)
        drawPath(path = meshRaysPath, color = effectiveSecondary, style = thinStroke)
        drawPath(path = beaconsPath, color = effectiveSecondary, style = solidStroke)
    }
}

/**
 * Процедурный бейдж Anycast-серверов MirrlyUI (20x13 dp) для карточек списков.
 * Заменяет устаревший статический Canvas в соответствии со стандартом /mirrly-icons.
 */
@Composable
fun GlobalAnycastBadgeIcon(
    modifier: Modifier = Modifier.size(width = 20.dp, height = 13.dp),
    accentColor: Color = LocalProtocolColors.current.primary
) {
    val roundedShape = remember { RoundedCornerShape(3.dp) }
    Box(
        modifier = modifier
            .clip(roundedShape)
            .background(accentColor.copy(alpha = 0.12f))
            .border(0.8.dp, accentColor.copy(alpha = 0.55f), roundedShape)
            .padding(1.dp),
        contentAlignment = Alignment.Center
    ) {
        MirrlyGlobalAnycastIcon(
            modifier = Modifier.fillMaxSize(),
            tint = accentColor,
            secondaryTint = accentColor.copy(alpha = 0.75f),
            strokeWidth = 1.0.dp
        )
    }
}

