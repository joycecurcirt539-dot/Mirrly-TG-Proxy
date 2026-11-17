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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Процедурные векторные иконки MirrlyUI для Jetpack Compose.
 * Категория: «Навигация, управление переходами и поиск».
 *
 * Стандарты /mirrly-icons:
 * 1. Каркасная геометрия (Outline-Only): базовая толщина 1.5dp, StrokeCap.Round, StrokeJoin.Round.
 *    Полный запрет на глухие заливки.
 * 2. Zero-Allocation (120 FPS Battery Guard): Path кэшируется через remember { Path() } и
 *    сбрасывается через .reset() перед отрисовкой. Никаких аллокаций внутри DrawScope.
 * 3. Динамическая палитра: привязка к LocalProtocolColors.current.primary с поддержкой tint.
 * 4. Оптическая балансировка: асимметричные элементы смещены на 1.25dp к острию.
 * 5. Полный отказ от эмодзи и типовых Material-шаблонов в пользу кибернетических метафор.
 */

private const val DEG_TO_RAD_F: Float = (Math.PI / 180.0).toFloat()
private const val COS_45_F: Float = 0.70710678f
private const val SIN_45_F: Float = 0.70710678f

private fun degCos(deg: Float): Float = cos(deg * DEG_TO_RAD_F)
private fun degSin(deg: Float): Float = sin(deg * DEG_TO_RAD_F)

// =============================================================================
// 1. ic_arrow_left — Кибернетический штанговый направляющий вектор
// =============================================================================

/**
 * Кибернетический штанговый направляющий вектор с дельтовидным оперением (ic_arrow_left).
 * Оптически сбалансирован смещением на 1.25dp в сторону острия (влево).
 */
@Composable
fun MirrlyIconArrowLeft(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val opticalShiftPx = remember(density) { with(density) { 1.25.dp.toPx() } }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cxa = w * 0.5f - opticalShiftPx
        val cy = h * 0.5f

        path.reset()

        // 1. Дельтовидный каркасный наконечник
        path.moveTo(cxa - w * 0.10f, cy - h * 0.22f)
        path.lineTo(cxa - w * 0.30f, cy)
        path.lineTo(cxa - w * 0.10f, cy + h * 0.22f)
        path.lineTo(cxa - w * 0.15f, cy + h * 0.07f)
        path.lineTo(cxa - w * 0.15f, cy - h * 0.07f)
        path.close()

        // 2. Носовой сенсорный штырь (микро-зонд)
        path.moveTo(cxa - w * 0.30f, cy)
        path.lineTo(cxa - w * 0.38f, cy)

        // 3. Центральная направляющая штанга
        path.moveTo(cxa - w * 0.15f, cy)
        path.lineTo(cxa + w * 0.32f, cy)

        // 4. Оперение хвостовой балки (стабилизирующие финлеты)
        path.moveTo(cxa + w * 0.18f, cy - h * 0.13f)
        path.lineTo(cxa + w * 0.25f, cy)
        path.lineTo(cxa + w * 0.18f, cy + h * 0.13f)

        // 5. Концевой ограничитель телеметрии
        path.moveTo(cxa + w * 0.32f, cy - h * 0.10f)
        path.lineTo(cxa + w * 0.32f, cy + h * 0.10f)

        drawPath(path = path, color = tint, style = strokeStyle)
    }
}

@Composable
fun MirrlyArrowLeftIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) = MirrlyIconArrowLeft(modifier, tint)

// =============================================================================
// 2. ic_arrow_down — Сдвоенные конические винглеты с затуханием (вниз)
// =============================================================================

/**
 * Сдвоенные конические винглеты с прогрессивным затуханием длины (ic_arrow_down).
 * Оптически сбалансирован смещением на 1.25dp вниз.
 */
@Composable
fun MirrlyIconArrowDown(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val opticalShiftPx = remember(density) { with(density) { 1.25.dp.toPx() } }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cya = h * 0.5f + opticalShiftPx

        path.reset()

        // 1. Верхняя осевая направляющая штанга
        path.moveTo(cx, cya - h * 0.33f)
        path.lineTo(cx, cya - h * 0.14f)

        // 2. Первичный широкий конический винглет с концевыми стабилизаторами
        path.moveTo(cx - w * 0.28f, cya - h * 0.22f)
        path.lineTo(cx - w * 0.28f, cya - h * 0.16f)
        path.lineTo(cx, cya - h * 0.01f)
        path.lineTo(cx + w * 0.28f, cya - h * 0.16f)
        path.lineTo(cx + w * 0.28f, cya - h * 0.22f)

        // 3. Вторичный конический винглет (прогрессивное затухание размаха ~60%)
        path.moveTo(cx - w * 0.17f, cya + h * 0.11f)
        path.lineTo(cx, cya + h * 0.23f)
        path.lineTo(cx + w * 0.17f, cya + h * 0.11f)

        // 4. Осевой навигационный микро-зонд
        path.moveTo(cx, cya + h * 0.23f)
        path.lineTo(cx, cya + h * 0.31f)

        drawPath(path = path, color = tint, style = strokeStyle)
    }
}

@Composable
fun MirrlyArrowDownIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) = MirrlyIconArrowDown(modifier, tint)

// =============================================================================
// 3. ic_arrow_up — Сдвоенные конические винглеты с затуханием (вверх)
// =============================================================================

/**
 * Сдвоенные конические винглеты с прогрессивным затуханием длины (ic_arrow_up).
 * Оптически сбалансирован смещением на 1.25dp вверх.
 */
@Composable
fun MirrlyIconArrowUp(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val opticalShiftPx = remember(density) { with(density) { 1.25.dp.toPx() } }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cya = h * 0.5f - opticalShiftPx

        path.reset()

        // 1. Нижняя осевая направляющая штанга
        path.moveTo(cx, cya + h * 0.33f)
        path.lineTo(cx, cya + h * 0.14f)

        // 2. Первичный широкий конический винглет в основании
        path.moveTo(cx - w * 0.28f, cya + h * 0.22f)
        path.lineTo(cx - w * 0.28f, cya + h * 0.16f)
        path.lineTo(cx, cya + h * 0.01f)
        path.lineTo(cx + w * 0.28f, cya + h * 0.16f)
        path.lineTo(cx + w * 0.28f, cya + h * 0.22f)

        // 3. Ведущий конический винглет с затуханием размаха
        path.moveTo(cx - w * 0.17f, cya - h * 0.11f)
        path.lineTo(cx, cya - h * 0.23f)
        path.lineTo(cx + w * 0.17f, cya - h * 0.11f)

        // 4. Осевой навигационный микро-зонд острия
        path.moveTo(cx, cya - h * 0.23f)
        path.lineTo(cx, cya - h * 0.31f)

        drawPath(path = path, color = tint, style = strokeStyle)
    }
}

@Composable
fun MirrlyArrowUpIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) = MirrlyIconArrowUp(modifier, tint)

// =============================================================================
// 4. ic_chevron_right — Угловой сенсорный рассекатель (угол 75 градусов)
// =============================================================================

/**
 * Тонкий угловой сенсорный рассекатель с острым фасеточным изгибом в 75 градусов (ic_chevron_right).
 * Оптически сбалансирован смещением на 1.25dp вправо.
 */
@Composable
fun MirrlyIconChevronRight(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val opticalShiftPx = remember(density) { with(density) { 1.25.dp.toPx() } }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cxa = w * 0.5f + opticalShiftPx
        val cy = h * 0.5f

        path.reset()

        // 1. Внешний фасеточный контур рассекателя (угол 75 градусов при вершине)
        path.moveTo(cxa - w * 0.16f, cy - h * 0.32f)
        path.lineTo(cxa - w * 0.04f, cy - h * 0.185f)
        path.lineTo(cxa + w * 0.20f, cy)
        path.lineTo(cxa - w * 0.04f, cy + h * 0.185f)
        path.lineTo(cxa - w * 0.16f, cy + h * 0.32f)

        // 2. Концевые сенсорные финлеты
        path.moveTo(cxa - w * 0.21f, cy - h * 0.30f)
        path.lineTo(cxa - w * 0.16f, cy - h * 0.32f)
        path.moveTo(cxa - w * 0.21f, cy + h * 0.30f)
        path.lineTo(cxa - w * 0.16f, cy + h * 0.32f)

        // 3. Внутренний расщепляющий клин телеметрии
        path.moveTo(cxa - w * 0.08f, cy - h * 0.09f)
        path.lineTo(cxa + w * 0.04f, cy)
        path.lineTo(cxa - w * 0.08f, cy + h * 0.09f)

        // 4. Осевая линия стабилизации фокуса
        path.moveTo(cxa - w * 0.23f, cy)
        path.lineTo(cxa - w * 0.11f, cy)

        drawPath(path = path, color = tint, style = strokeStyle)
    }
}

@Composable
fun MirrlyChevronRightIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) = MirrlyIconChevronRight(modifier, tint)

// =============================================================================
// 5. ic_close — Перекрестные демпферные лучи с радиальным смещением
// =============================================================================

/**
 * Перекрестные демпферные лучи с легким радиальным смещением точек пересечения (ic_close).
 * Сохраняет 100% прозрачность сквозь центральное перекрестие без глухого пересечения.
 */
@Composable
fun MirrlyIconClose(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f

        val rIn = w * 0.10f
        val rOut = w * 0.31f
        val dHalf = w * 0.065f
        val apHalf = w * 0.025f

        path.reset()

        // 1. Четыре радиально смещенных луча с открытой апертурой в центре
        // Луч 1: Top-Left
        path.moveTo(cx - rOut * COS_45_F, cy - rOut * SIN_45_F)
        path.lineTo(cx - rIn * COS_45_F, cy - rIn * SIN_45_F)

        // Луч 2: Bottom-Right
        path.moveTo(cx + rIn * COS_45_F, cy + rIn * SIN_45_F)
        path.lineTo(cx + rOut * COS_45_F, cy + rOut * SIN_45_F)

        // Луч 3: Top-Right
        path.moveTo(cx + rOut * COS_45_F, cy - rOut * SIN_45_F)
        path.lineTo(cx + rIn * COS_45_F, cy - rIn * SIN_45_F)

        // Луч 4: Bottom-Left
        path.moveTo(cx - rIn * COS_45_F, cy + rIn * SIN_45_F)
        path.lineTo(cx - rOut * COS_45_F, cy + rOut * SIN_45_F)

        // 2. Поперечные демпферные планки на внешних концах лучей
        val p1X = cx - rOut * COS_45_F
        val p1Y = cy - rOut * SIN_45_F
        path.moveTo(p1X - dHalf * SIN_45_F, p1Y + dHalf * COS_45_F)
        path.lineTo(p1X + dHalf * SIN_45_F, p1Y - dHalf * COS_45_F)

        val p2X = cx + rOut * COS_45_F
        val p2Y = cy + rOut * SIN_45_F
        path.moveTo(p2X - dHalf * SIN_45_F, p2Y + dHalf * COS_45_F)
        path.lineTo(p2X + dHalf * SIN_45_F, p2Y - dHalf * COS_45_F)

        val p3X = cx + rOut * COS_45_F
        val p3Y = cy - rOut * SIN_45_F
        path.moveTo(p3X - dHalf * COS_45_F, p3Y - dHalf * SIN_45_F)
        path.lineTo(p3X + dHalf * COS_45_F, p3Y + dHalf * SIN_45_F)

        val p4X = cx - rOut * COS_45_F
        val p4Y = cy + rOut * SIN_45_F
        path.moveTo(p4X - dHalf * COS_45_F, p4Y - dHalf * SIN_45_F)
        path.lineTo(p4X + dHalf * COS_45_F, p4Y + dHalf * SIN_45_F)

        // 3. Фасеточные стопоры центральной апертуры
        path.moveTo(cx - rIn * COS_45_F - apHalf * SIN_45_F, cy - rIn * SIN_45_F + apHalf * COS_45_F)
        path.lineTo(cx - rIn * COS_45_F + apHalf * SIN_45_F, cy - rIn * SIN_45_F - apHalf * COS_45_F)

        path.moveTo(cx + rIn * COS_45_F - apHalf * SIN_45_F, cy + rIn * SIN_45_F + apHalf * COS_45_F)
        path.lineTo(cx + rIn * COS_45_F + apHalf * SIN_45_F, cy + rIn * SIN_45_F - apHalf * COS_45_F)

        path.moveTo(cx + rIn * COS_45_F - apHalf * COS_45_F, cy - rIn * SIN_45_F - apHalf * SIN_45_F)
        path.lineTo(cx + rIn * COS_45_F + apHalf * COS_45_F, cy - rIn * SIN_45_F + apHalf * SIN_45_F)

        path.moveTo(cx - rIn * COS_45_F - apHalf * COS_45_F, cy + rIn * SIN_45_F - apHalf * SIN_45_F)
        path.lineTo(cx - rIn * COS_45_F + apHalf * COS_45_F, cy + rIn * SIN_45_F + apHalf * SIN_45_F)

        drawPath(path = path, color = tint, style = strokeStyle)
    }
}

@Composable
fun MirrlyCloseIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) = MirrlyIconClose(modifier, tint)

// =============================================================================
// 6. ic_expand — Угловые сенсорные скобы видоискателя (диагонали 45 градусов)
// =============================================================================

/**
 * Угловые сенсорные скобы видоискателя HUD, ориентированные по диагоналям 45 градусов (ic_expand).
 */
@Composable
fun MirrlyIconExpand(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f

        val rOut = w * 0.33f
        val rIn = w * 0.16f

        path.reset()

        // 1. Четыре угловые скобы оптического видоискателя
        // Top-Left
        path.moveTo(cx - rIn, cy - rOut)
        path.lineTo(cx - rOut, cy - rOut)
        path.lineTo(cx - rOut, cy - rIn)

        // Top-Right
        path.moveTo(cx + rIn, cy - rOut)
        path.lineTo(cx + rOut, cy - rOut)
        path.lineTo(cx + rOut, cy - rIn)

        // Bottom-Left
        path.moveTo(cx - rIn, cy + rOut)
        path.lineTo(cx - rOut, cy + rOut)
        path.lineTo(cx - rOut, cy + rIn)

        // Bottom-Right
        path.moveTo(cx + rIn, cy + rOut)
        path.lineTo(cx + rOut, cy + rOut)
        path.lineTo(cx + rOut, cy + rIn)

        // 2. Диагональные маркеры расширения (направлены наружу под 45 градусов)
        path.moveTo(cx - w * 0.12f, cy - h * 0.12f)
        path.lineTo(cx - w * 0.24f, cy - h * 0.24f)

        path.moveTo(cx + w * 0.12f, cy - h * 0.12f)
        path.lineTo(cx + w * 0.24f, cy - h * 0.24f)

        path.moveTo(cx - w * 0.12f, cy + h * 0.12f)
        path.lineTo(cx - w * 0.24f, cy + h * 0.24f)

        path.moveTo(cx + w * 0.12f, cy + h * 0.12f)
        path.lineTo(cx + w * 0.24f, cy + h * 0.24f)

        // 3. Центральное микро-перекрестие захвата цели
        path.moveTo(cx - w * 0.035f, cy)
        path.lineTo(cx + w * 0.035f, cy)
        path.moveTo(cx, cy - h * 0.035f)
        path.lineTo(cx, cy + h * 0.035f)

        drawPath(path = path, color = tint, style = strokeStyle)
    }
}

@Composable
fun MirrlyExpandIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) = MirrlyIconExpand(modifier, tint)

// =============================================================================
// 7. ic_accordion_expand — Горизонтальная шина с микро-стрелкой раскрытия
// =============================================================================

/**
 * Горизонтальный шинный сегмент с динамической микро-стрелкой раскрытия вниз (ic_accordion_expand).
 */
@Composable
fun MirrlyIconAccordionExpand(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f

        val yBus = cy - h * 0.16f
        val xLeft = cx - w * 0.32f
        val xRight = cx + w * 0.32f

        path.reset()

        // 1. Горизонтальная шина данных
        path.moveTo(xLeft, yBus)
        path.lineTo(xRight, yBus)

        // 2. Концевые ограничительные клеммы шины
        path.moveTo(xLeft, yBus - h * 0.08f)
        path.lineTo(xLeft, yBus + h * 0.08f)
        path.moveTo(xRight, yBus - h * 0.08f)
        path.lineTo(xRight, yBus + h * 0.08f)

        // 3. Шток микро-стрелки трансформации
        path.moveTo(cx, yBus + h * 0.04f)
        path.lineTo(cx, cy + h * 0.25f)

        // 4. Дельтовидный наконечник раскрытия (направлен вниз)
        path.moveTo(cx - w * 0.17f, cy + h * 0.11f)
        path.lineTo(cx, cy + h * 0.25f)
        path.lineTo(cx + w * 0.17f, cy + h * 0.11f)

        // 5. Нижняя направляющая телеметрическая база
        path.moveTo(cx - w * 0.11f, cy + h * 0.33f)
        path.lineTo(cx + w * 0.11f, cy + h * 0.33f)

        drawPath(path = path, color = tint, style = strokeStyle)
    }
}

@Composable
fun MirrlyAccordionExpandIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) = MirrlyIconAccordionExpand(modifier, tint)

// =============================================================================
// 8. ic_accordion_collapse — Горизонтальная шина с микро-стрелкой схлопывания
// =============================================================================

/**
 * Горизонтальный шинный сегмент с динамической микро-стрелкой схлопывания вверх (ic_accordion_collapse).
 */
@Composable
fun MirrlyIconAccordionCollapse(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f

        val yBus = cy + h * 0.16f
        val xLeft = cx - w * 0.32f
        val xRight = cx + w * 0.32f

        path.reset()

        // 1. Горизонтальная шина данных
        path.moveTo(xLeft, yBus)
        path.lineTo(xRight, yBus)

        // 2. Концевые ограничительные клеммы шины
        path.moveTo(xLeft, yBus - h * 0.08f)
        path.lineTo(xLeft, yBus + h * 0.08f)
        path.moveTo(xRight, yBus - h * 0.08f)
        path.lineTo(xRight, yBus + h * 0.08f)

        // 3. Шток микро-стрелки трансформации
        path.moveTo(cx, yBus - h * 0.04f)
        path.lineTo(cx, cy - h * 0.25f)

        // 4. Дельтовидный наконечник схлопывания (направлен вверх)
        path.moveTo(cx - w * 0.17f, cy - h * 0.11f)
        path.lineTo(cx, cy - h * 0.25f)
        path.lineTo(cx + w * 0.17f, cy - h * 0.11f)

        // 5. Верхняя направляющая телеметрическая база
        path.moveTo(cx - w * 0.11f, cy - h * 0.33f)
        path.lineTo(cx + w * 0.11f, cy - h * 0.33f)

        drawPath(path = path, color = tint, style = strokeStyle)
    }
}

@Composable
fun MirrlyAccordionCollapseIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) = MirrlyIconAccordionCollapse(modifier, tint)

// =============================================================================
// 9. ic_search — Коллиматорный оптический прицел-сканер
// =============================================================================

/**
 * Коллиматорный оптический прицел с разорванной окружностью, перекрестьем и диагональной
 * рукоятью-сканером (ic_search).
 */
@Composable
fun MirrlyIconSearch(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f

        // Оптический центр линзы прицела сбалансирован относительно рукояти
        val rcx = cx - w * 0.07f
        val rcy = cy - h * 0.07f
        val r = w * 0.25f

        // 1. Разорванная окружность коллиматора (4 квадрантные дуги по 54 градуса)
        val arcTopLeft = Offset(rcx - r, rcy - r)
        val arcSize = Size(r * 2f, r * 2f)

        drawArc(color = tint, startAngle = 18f, sweepAngle = 54f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = strokeStyle)
        drawArc(color = tint, startAngle = 108f, sweepAngle = 54f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = strokeStyle)
        drawArc(color = tint, startAngle = 198f, sweepAngle = 54f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = strokeStyle)
        drawArc(color = tint, startAngle = 288f, sweepAngle = 54f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = strokeStyle)

        path.reset()

        // 2. Входные перекрестные риски из разрывов окружности
        path.moveTo(rcx, rcy - r - h * 0.06f)
        path.lineTo(rcx, rcy - r + h * 0.035f)

        path.moveTo(rcx - r - w * 0.06f, rcy)
        path.lineTo(rcx - r + w * 0.035f, rcy)

        path.moveTo(rcx, rcy + r - h * 0.035f)
        path.lineTo(rcx, rcy + r + h * 0.06f)

        path.moveTo(rcx + r - w * 0.035f, rcy)
        path.lineTo(rcx + r + w * 0.06f, rcy)

        // 3. Центральное полое микро-перекрестие оптического захвата
        path.moveTo(rcx - w * 0.04f, rcy)
        path.lineTo(rcx - w * 0.015f, rcy)
        path.moveTo(rcx + w * 0.015f, rcy)
        path.lineTo(rcx + w * 0.04f, rcy)

        path.moveTo(rcx, rcy - h * 0.04f)
        path.lineTo(rcx, rcy - h * 0.015f)
        path.moveTo(rcx, rcy + h * 0.015f)
        path.lineTo(rcx, rcy + h * 0.04f)

        // 4. Диагональная рукоять-сканер (под углом 45 градусов)
        val x1 = rcx + r * COS_45_F
        val y1 = rcy + r * SIN_45_F
        val x2 = cx + w * 0.35f
        val y2 = cy + h * 0.35f

        path.moveTo(x1, y1)
        path.lineTo(x2, y2)

        // 5. Сенсорная муфта телеметрии на рукояти
        val mx = x1 + (x2 - x1) * 0.55f
        val my = y1 + (y2 - y1) * 0.55f
        val dW = w * 0.04f
        path.moveTo(mx - dW * COS_45_F, my + dW * SIN_45_F)
        path.lineTo(mx + dW * COS_45_F, my - dW * SIN_45_F)

        // 6. Концевая защитная насадка зонда
        path.moveTo(x2 - dW * 0.75f * COS_45_F, y2 + dW * 0.75f * SIN_45_F)
        path.lineTo(x2 + dW * 0.75f * COS_45_F, y2 - dW * 0.75f * SIN_45_F)

        drawPath(path = path, color = tint, style = strokeStyle)
    }
}

@Composable
fun MirrlySearchIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) = MirrlyIconSearch(modifier, tint)

// =============================================================================
// 10. ic_refresh — Орбитальные дуги центробежного ускорителя
// =============================================================================

private fun DrawScope.drawCentrifugalAccelerator(
    path: Path,
    strokeStyle: Stroke,
    tint: Color,
    w: Float,
    h: Float
) {
    val cx = w * 0.5f
    val cy = h * 0.5f
    val r = w * 0.28f

    // 1. Две орбитальные дуги центробежного ускорителя с разрывами (по 140 градусов)
    val arcTopLeft = Offset(cx - r, cy - r)
    val arcSize = Size(r * 2f, r * 2f)

    drawArc(color = tint, startAngle = 205f, sweepAngle = 140f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = strokeStyle)
    drawArc(color = tint, startAngle = 25f, sweepAngle = 140f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = strokeStyle)

    path.reset()

    // 2. Стреловидные факелы на концах орбит (по ходу центробежного вращения)
    val barb = w * 0.12f

    // Факел дуги 1: терминал на угле -15° (345°)
    val tipX1 = cx + r * degCos(-15f)
    val tipY1 = cy + r * degSin(-15f)
    path.moveTo(tipX1 + barb * degCos(215f), tipY1 + barb * degSin(215f))
    path.lineTo(tipX1, tipY1)
    path.lineTo(tipX1 + barb * degCos(-65f), tipY1 + barb * degSin(-65f))

    // Факел дуги 2: терминал на угле 165°
    val tipX2 = cx + r * degCos(165f)
    val tipY2 = cy + r * degSin(165f)
    path.moveTo(tipX2 + barb * degCos(35f), tipY2 + barb * degSin(35f))
    path.lineTo(tipX2, tipY2)
    path.lineTo(tipX2 + barb * degCos(115f), tipY2 + barb * degSin(115f))

    // 3. Центральная квантовая ось вращения
    path.moveTo(cx - w * 0.035f, cy)
    path.lineTo(cx + w * 0.035f, cy)
    path.moveTo(cx, cy - h * 0.035f)
    path.lineTo(cx, cy + h * 0.035f)

    drawPath(path = path, color = tint, style = strokeStyle)
}

/**
 * Две орбитальные дуги центробежного ускорителя с разрывами и стреловидными факелами (ic_refresh).
 * Поддерживает опциональную динамическую ротацию через [rotationDegrees].
 */
@Composable
fun MirrlyIconRefresh(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    rotationDegrees: Float = 0f
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        if (rotationDegrees == 0f) {
            drawCentrifugalAccelerator(
                path = path,
                strokeStyle = strokeStyle,
                tint = tint,
                w = size.width,
                h = size.height
            )
        } else {
            rotate(degrees = rotationDegrees, pivot = Offset(size.width * 0.5f, size.height * 0.5f)) {
                drawCentrifugalAccelerator(
                    path = path,
                    strokeStyle = strokeStyle,
                    tint = tint,
                    w = size.width,
                    h = size.height
                )
            }
        }
    }
}

@Composable
fun MirrlyRefreshIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    rotationDegrees: Float = 0f
) = MirrlyIconRefresh(modifier, tint, rotationDegrees)

// =============================================================================
// 11. ic_settings — Кольцевой сенсорный ротор с радиальными зубьями и зрачком
// =============================================================================

private fun DrawScope.drawCyberneticSettingsRotor(
    path: Path,
    strokeStyle: Stroke,
    tint: Color,
    w: Float,
    h: Float
) {
    path.reset()
    val cx = w * 0.5f
    val cy = h * 0.5f
    val rInner = minOf(w, h) * 0.16f
    val rBody = minOf(w, h) * 0.32f
    val rOuter = minOf(w, h) * 0.44f
    val toothHalfAngle = 14f

    // Центральный сенсорный зрачок
    drawCircle(color = tint, radius = rInner, center = Offset(cx, cy), style = strokeStyle)

    // Внешний контур ротора с 6 радиальными фасетными зубьями
    for (i in 0 until 6) {
        val baseAngle = i * 60f
        val a1 = baseAngle - toothHalfAngle
        val a2 = baseAngle - toothHalfAngle * 0.55f
        val a3 = baseAngle + toothHalfAngle * 0.55f
        val a4 = baseAngle + toothHalfAngle
        val aNext = baseAngle + 60f - toothHalfAngle

        val p1 = Offset(cx + rBody * degCos(a1), cy + rBody * degSin(a1))
        val p2 = Offset(cx + rOuter * degCos(a2), cy + rOuter * degSin(a2))
        val p3 = Offset(cx + rOuter * degCos(a3), cy + rOuter * degSin(a3))
        val p4 = Offset(cx + rBody * degCos(a4), cy + rBody * degSin(a4))

        if (i == 0) {
            path.moveTo(p1.x, p1.y)
        } else {
            path.lineTo(p1.x, p1.y)
        }
        path.lineTo(p2.x, p2.y)
        path.lineTo(p3.x, p3.y)
        path.lineTo(p4.x, p4.y)

        val pNext = Offset(cx + rBody * degCos(aNext), cy + rBody * degSin(aNext))
        path.lineTo(pNext.x, pNext.y)
    }
    path.close()

    drawPath(path = path, color = tint, style = strokeStyle)
}

@Composable
fun MirrlyIconSettings(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        drawCyberneticSettingsRotor(
            path = path,
            strokeStyle = strokeStyle,
            tint = tint,
            w = size.width,
            h = size.height
        )
    }
}

@Composable
fun MirrlySettingsIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary
) = MirrlyIconSettings(modifier, tint)

// =============================================================================
// MirrlyNavIcons — Объединенное пространство имен компонентов
// =============================================================================

object MirrlyNavIcons {
    @Composable
    fun Settings(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary
    ) = MirrlyIconSettings(modifier, tint)
    @Composable
    fun ArrowLeft(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary
    ) = MirrlyIconArrowLeft(modifier, tint)

    @Composable
    fun ArrowDown(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary
    ) = MirrlyIconArrowDown(modifier, tint)

    @Composable
    fun ArrowUp(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary
    ) = MirrlyIconArrowUp(modifier, tint)

    @Composable
    fun ChevronRight(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary
    ) = MirrlyIconChevronRight(modifier, tint)

    @Composable
    fun Close(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary
    ) = MirrlyIconClose(modifier, tint)

    @Composable
    fun Expand(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary
    ) = MirrlyIconExpand(modifier, tint)

    @Composable
    fun AccordionExpand(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary
    ) = MirrlyIconAccordionExpand(modifier, tint)

    @Composable
    fun AccordionCollapse(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary
    ) = MirrlyIconAccordionCollapse(modifier, tint)

    @Composable
    fun Search(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary
    ) = MirrlyIconSearch(modifier, tint)

    @Composable
    fun Refresh(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary,
        rotationDegrees: Float = 0f
    ) = MirrlyIconRefresh(modifier, tint, rotationDegrees)
}
