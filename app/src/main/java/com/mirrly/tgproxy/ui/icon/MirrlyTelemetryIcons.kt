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

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Процедурные векторные иконки сетевой телеметрии, метрик качества и ступеней скорости
 * дизайн-системы MirrlyUI (стандарт /mirrly-icons).
 *
 * Стандарты реализации:
 * 1. Outline-Only: штрих 1.5dp, StrokeCap.Round, StrokeJoin.Round.
 * 2. Zero-Allocation (120 FPS): пути кэшируются через remember { Path() } и очищаются через .reset().
 * 3. Отсутствие повторяющихся форм: каждая диагностическая метрика имеет строго уникальный силуэт.
 * 4. Нарастающая система скорости: визуальная шкала от минимального сопротивления до гиперзвука.
 * 5. Динамическая палитра: автоматическая адаптация к LocalProtocolColors.current.primary.
 */

// ============================================================================
// 1. ДИАГНОСТИЧЕСКИЕ МЕТРИКИ (12 ИКОНОК)
// ============================================================================

/**
 * ic_diag_antenna: мачта сотовой связи с тремя концентрическими радиоволнами вправо-вверх.
 */
@Composable
fun DiagAntennaIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Левая и правая наклонные фермы мачты
        path.moveTo(ox + 4.5f * scale, oy + 21.5f * scale)
        path.lineTo(ox + 9.0f * scale, oy + 6.5f * scale)
        path.lineTo(ox + 13.5f * scale, oy + 21.5f * scale)

        // Центральный вертикальный шпиль излучателя
        path.moveTo(ox + 9.0f * scale, oy + 6.5f * scale)
        path.lineTo(ox + 9.0f * scale, oy + 3.0f * scale)

        // Нижняя поперечина фермы
        path.moveTo(ox + 5.8f * scale, oy + 17.0f * scale)
        path.lineTo(ox + 12.2f * scale, oy + 17.0f * scale)

        // Верхняя поперечина фермы
        path.moveTo(ox + 7.2f * scale, oy + 12.0f * scale)
        path.lineTo(ox + 10.8f * scale, oy + 12.0f * scale)

        // Диагональные раскосы решётки
        path.moveTo(ox + 5.8f * scale, oy + 17.0f * scale)
        path.lineTo(ox + 10.8f * scale, oy + 12.0f * scale)
        path.moveTo(ox + 12.2f * scale, oy + 17.0f * scale)
        path.lineTo(ox + 7.2f * scale, oy + 12.0f * scale)

        // Волна 1 (внутренняя, концентрическая вправо-вверх)
        path.moveTo(ox + 11.5f * scale, oy + 2.0f * scale)
        path.cubicTo(
            ox + 13.5f * scale, oy + 4.5f * scale,
            ox + 13.5f * scale, oy + 6.0f * scale,
            ox + 12.0f * scale, oy + 7.5f * scale
        )

        // Волна 2 (средняя)
        path.moveTo(ox + 14.5f * scale, oy + 1.2f * scale)
        path.cubicTo(
            ox + 17.5f * scale, oy + 4.8f * scale,
            ox + 17.5f * scale, oy + 7.5f * scale,
            ox + 15.5f * scale, oy + 10.5f * scale
        )

        // Волна 3 (внешняя)
        path.moveTo(ox + 17.8f * scale, oy + 0.8f * scale)
        path.cubicTo(
            ox + 22.0f * scale, oy + 5.2f * scale,
            ox + 22.0f * scale, oy + 9.5f * scale,
            ox + 19.0f * scale, oy + 13.5f * scale
        )

        drawPath(path = path, color = color, style = stroke)

        // Точечный микро-эмиттер на вершине мачты
        drawCircle(
            color = color,
            radius = 0.85f * scale,
            center = Offset(ox + 9.0f * scale, oy + 3.0f * scale)
        )
    }
}

/**
 * ic_diag_bufferbloat: переполняющаяся контурная колба/стек очередей пакетов с маркером перегрузки.
 */
@Composable
fun DiagBufferbloatIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Горловина и колба (лабораторный контур буфера)
        path.moveTo(ox + 9.0f * scale, oy + 5.0f * scale)
        path.lineTo(ox + 15.0f * scale, oy + 5.0f * scale)

        path.moveTo(ox + 10.0f * scale, oy + 5.0f * scale)
        path.lineTo(ox + 10.0f * scale, oy + 9.0f * scale)
        path.lineTo(ox + 5.0f * scale, oy + 19.5f * scale)
        path.cubicTo(
            ox + 4.5f * scale, oy + 20.8f * scale,
            ox + 5.5f * scale, oy + 21.0f * scale,
            ox + 7.0f * scale, oy + 21.0f * scale
        )
        path.lineTo(ox + 17.0f * scale, oy + 21.0f * scale)
        path.cubicTo(
            ox + 18.5f * scale, oy + 21.0f * scale,
            ox + 19.5f * scale, oy + 20.8f * scale,
            ox + 19.0f * scale, oy + 19.5f * scale
        )
        path.lineTo(ox + 14.0f * scale, oy + 9.0f * scale)
        path.lineTo(ox + 14.0f * scale, oy + 5.0f * scale)

        // Заполненные горизонтальные уровни очереди пакетов
        path.moveTo(ox + 7.0f * scale, oy + 18.5f * scale)
        path.lineTo(ox + 17.0f * scale, oy + 18.5f * scale)

        path.moveTo(ox + 8.5f * scale, oy + 15.0f * scale)
        path.lineTo(ox + 15.5f * scale, oy + 15.0f * scale)

        path.moveTo(ox + 9.8f * scale, oy + 11.5f * scale)
        path.lineTo(ox + 14.2f * scale, oy + 11.5f * scale)

        // Уровень перегрузки в горловине
        path.moveTo(ox + 10.5f * scale, oy + 7.5f * scale)
        path.lineTo(ox + 13.5f * scale, oy + 7.5f * scale)

        // Брызги перелива через край горловины
        path.moveTo(ox + 9.0f * scale, oy + 5.0f * scale)
        path.cubicTo(ox + 7.5f * scale, oy + 5.2f * scale, ox + 7.0f * scale, oy + 6.5f * scale, ox + 7.2f * scale, oy + 7.2f * scale)

        path.moveTo(ox + 15.0f * scale, oy + 5.0f * scale)
        path.cubicTo(ox + 16.5f * scale, oy + 5.2f * scale, ox + 17.0f * scale, oy + 6.5f * scale, ox + 16.8f * scale, oy + 7.2f * scale)

        // Маркер перегрузки (восклицательный знак над горловиной)
        path.moveTo(ox + 12.0f * scale, oy + 0.8f * scale)
        path.lineTo(ox + 12.0f * scale, oy + 3.0f * scale)

        drawPath(path = path, color = color, style = stroke)

        // Точка маркера перегрузки
        drawCircle(
            color = color,
            radius = 0.75f * scale,
            center = Offset(ox + 12.0f * scale, oy + 4.2f * scale)
        )
    }
}

/**
 * ic_diag_cloudflare: фирменная контурная дуга кучевого облака со встроенным волновым вектором.
 */
@Composable
fun DiagCloudflareIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Контур кучевого облака Cloudflare
        path.moveTo(ox + 6.5f * scale, oy + 18.0f * scale)
        path.lineTo(ox + 17.5f * scale, oy + 18.0f * scale)
        path.cubicTo(
            ox + 20.5f * scale, oy + 18.0f * scale,
            ox + 22.5f * scale, oy + 16.0f * scale,
            ox + 22.5f * scale, oy + 13.5f * scale
        )
        path.cubicTo(
            ox + 22.5f * scale, oy + 11.2f * scale,
            ox + 20.8f * scale, oy + 9.5f * scale,
            ox + 18.8f * scale, oy + 9.2f * scale
        )
        path.cubicTo(
            ox + 18.2f * scale, oy + 5.8f * scale,
            ox + 15.5f * scale, oy + 3.8f * scale,
            ox + 12.2f * scale, oy + 3.8f * scale
        )
        path.cubicTo(
            ox + 9.2f * scale, oy + 3.8f * scale,
            ox + 6.6f * scale, oy + 6.0f * scale,
            ox + 5.8f * scale, oy + 9.0f * scale
        )
        path.cubicTo(
            ox + 3.2f * scale, oy + 9.5f * scale,
            ox + 1.5f * scale, oy + 11.5f * scale,
            ox + 1.5f * scale, oy + 14.0f * scale
        )
        path.cubicTo(
            ox + 1.5f * scale, oy + 16.5f * scale,
            ox + 3.8f * scale, oy + 18.0f * scale,
            ox + 6.5f * scale, oy + 18.0f * scale
        )

        // Встроенный высокоскоростной волновой вектор, пронизывающий облако
        path.moveTo(ox + 2.0f * scale, oy + 13.5f * scale)
        path.lineTo(ox + 6.0f * scale, oy + 13.5f * scale)
        path.lineTo(ox + 8.5f * scale, oy + 10.5f * scale)
        path.lineTo(ox + 11.5f * scale, oy + 16.5f * scale)
        path.lineTo(ox + 14.5f * scale, oy + 10.5f * scale)
        path.lineTo(ox + 17.0f * scale, oy + 13.5f * scale)
        path.lineTo(ox + 22.0f * scale, oy + 13.5f * scale)

        // Наконечник вектора
        path.moveTo(ox + 19.5f * scale, oy + 11.2f * scale)
        path.lineTo(ox + 22.0f * scale, oy + 13.5f * scale)
        path.lineTo(ox + 19.5f * scale, oy + 15.8f * scale)

        drawPath(path = path, color = color, style = stroke)
    }
}

/**
 * ic_diag_delivery: контейнер пакета с оперением, точно попадающий в круговую координатную мишень.
 */
@Composable
fun DiagDeliveryIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        val tx = 15.0f
        val ty = 9.0f

        // Координатная мишень (внешняя дуга радиуса 6.2)
        path.addArc(
            oval = Rect(
                left = ox + (tx - 6.2f) * scale,
                top = oy + (ty - 6.2f) * scale,
                right = ox + (tx + 6.2f) * scale,
                bottom = oy + (ty + 6.2f) * scale
            ),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 360f
        )

        // Внутренний круг мишени
        path.addArc(
            oval = Rect(
                left = ox + (tx - 2.8f) * scale,
                top = oy + (ty - 2.8f) * scale,
                right = ox + (tx + 2.8f) * scale,
                bottom = oy + (ty + 2.8f) * scale
            ),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 360f
        )

        // Координатные засечки перекрестия
        path.moveTo(ox + tx * scale, oy + (ty - 6.2f) * scale)
        path.lineTo(ox + tx * scale, oy + (ty - 8.0f) * scale)

        path.moveTo(ox + tx * scale, oy + (ty + 6.2f) * scale)
        path.lineTo(ox + tx * scale, oy + (ty + 8.0f) * scale)

        path.moveTo(ox + (tx + 6.2f) * scale, oy + ty * scale)
        path.lineTo(ox + (tx + 8.0f) * scale, oy + ty * scale)

        // Контейнер пакета с оперением, нацеленный в ядро мишени (15, 9)
        // Нос пакета точно в центре мишени
        path.moveTo(ox + tx * scale, oy + ty * scale)
        path.lineTo(ox + 12.2f * scale, oy + 9.8f * scale)
        path.lineTo(ox + 8.0f * scale, oy + 14.0f * scale)
        path.lineTo(ox + 10.0f * scale, oy + 16.0f * scale)
        path.lineTo(ox + 14.2f * scale, oy + 11.8f * scale)
        path.close()

        // Верхнее стабилизирующее перо
        path.moveTo(ox + 9.2f * scale, oy + 12.8f * scale)
        path.lineTo(ox + 7.5f * scale, oy + 10.2f * scale)
        path.lineTo(ox + 6.5f * scale, oy + 13.2f * scale)
        path.lineTo(ox + 8.0f * scale, oy + 14.0f * scale)

        // Нижнее стабилизирующее перо
        path.moveTo(ox + 11.2f * scale, oy + 14.8f * scale)
        path.lineTo(ox + 13.8f * scale, oy + 16.5f * scale)
        path.lineTo(ox + 10.8f * scale, oy + 17.5f * scale)
        path.lineTo(ox + 10.0f * scale, oy + 16.0f * scale)

        // Траекторный шлейф полёта пакета
        path.moveTo(ox + 2.5f * scale, oy + 21.5f * scale)
        path.lineTo(ox + 6.5f * scale, oy + 17.5f * scale)

        drawPath(path = path, color = color, style = stroke)

        // Центральная точка попадания
        drawCircle(
            color = color,
            radius = 0.9f * scale,
            center = Offset(ox + tx * scale, oy + ty * scale)
        )
    }
}

/**
 * ic_diag_files: контурный лист данных с диагональным срезом угла и нисходящей стрелкой сквозной загрузки.
 */
@Composable
fun DiagFilesIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Контур листа данных с диагональным срезом угла
        path.moveTo(ox + 5.0f * scale, oy + 3.0f * scale)
        path.lineTo(ox + 14.0f * scale, oy + 3.0f * scale)
        path.lineTo(ox + 19.0f * scale, oy + 8.0f * scale)
        path.lineTo(ox + 19.0f * scale, oy + 21.0f * scale)
        path.lineTo(ox + 5.0f * scale, oy + 21.0f * scale)
        path.close()

        // Складка диагонального угла (dog-ear)
        path.moveTo(ox + 14.0f * scale, oy + 3.0f * scale)
        path.lineTo(ox + 14.0f * scale, oy + 8.0f * scale)
        path.lineTo(ox + 19.0f * scale, oy + 8.0f * scale)

        // Строки телеметрии/данных на листе
        path.moveTo(ox + 7.5f * scale, oy + 8.0f * scale)
        path.lineTo(ox + 11.5f * scale, oy + 8.0f * scale)

        path.moveTo(ox + 7.5f * scale, oy + 12.0f * scale)
        path.lineTo(ox + 10.0f * scale, oy + 12.0f * scale)

        // Нисходящая стрелка сквозной потоковой загрузки
        path.moveTo(ox + 12.0f * scale, oy + 1.2f * scale)
        path.lineTo(ox + 12.0f * scale, oy + 17.5f * scale)

        // Наконечник стрелки
        path.moveTo(ox + 9.0f * scale, oy + 14.5f * scale)
        path.lineTo(ox + 12.0f * scale, oy + 17.5f * scale)
        path.lineTo(ox + 15.0f * scale, oy + 14.5f * scale)

        // Приёмный лоток базы данных
        path.moveTo(ox + 8.5f * scale, oy + 21.0f * scale)
        path.lineTo(ox + 15.5f * scale, oy + 21.0f * scale)

        drawPath(path = path, color = color, style = stroke)
    }
}

/**
 * ic_diag_formula: математический символ суммы Sigma (Σ), интегрированный с дельта-символом параметров туннеля.
 */
@Composable
fun DiagFormulaIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Символ математической суммы Sigma (Σ)
        path.moveTo(ox + 3.5f * scale, oy + 6.8f * scale)
        path.lineTo(ox + 3.5f * scale, oy + 4.5f * scale)
        path.lineTo(ox + 12.5f * scale, oy + 4.5f * scale)
        path.lineTo(ox + 7.0f * scale, oy + 12.0f * scale)
        path.lineTo(ox + 12.5f * scale, oy + 19.5f * scale)
        path.lineTo(ox + 3.5f * scale, oy + 19.5f * scale)
        path.lineTo(ox + 3.5f * scale, oy + 17.2f * scale)

        // Интегрированный дельта-символ вариации параметров туннеля (Δ)
        path.moveTo(ox + 17.5f * scale, oy + 7.0f * scale)
        path.lineTo(ox + 13.5f * scale, oy + 15.0f * scale)
        path.lineTo(ox + 21.5f * scale, oy + 15.0f * scale)
        path.close()

        // Индексный параметр времени/туннеля (субскрипт 't' под Дельтой)
        path.moveTo(ox + 16.5f * scale, oy + 17.5f * scale)
        path.lineTo(ox + 18.5f * scale, oy + 17.5f * scale)
        path.moveTo(ox + 17.5f * scale, oy + 17.5f * scale)
        path.lineTo(ox + 17.5f * scale, oy + 20.5f * scale)
        path.lineTo(ox + 19.0f * scale, oy + 20.5f * scale)

        // Связующий вектор взаимосвязи параметров
        path.moveTo(ox + 10.5f * scale, oy + 12.0f * scale)
        path.lineTo(ox + 13.0f * scale, oy + 12.0f * scale)

        drawPath(path = path, color = color, style = stroke)
    }
}

/**
 * ic_diag_jitter: синусоида с хаотичным разбросом фазы и пилообразными всплесками амплитуды.
 */
@Composable
fun DiagJitterIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Опорная волна слева, переходящая в пилообразный фазовый разброс джиттера
        path.moveTo(ox + 1.5f * scale, oy + 12.0f * scale)

        // Стабильный полупериод
        path.cubicTo(
            ox + 3.0f * scale, oy + 12.0f * scale,
            ox + 4.0f * scale, oy + 8.0f * scale,
            ox + 5.5f * scale, oy + 8.0f * scale
        )
        path.cubicTo(
            ox + 7.0f * scale, oy + 8.0f * scale,
            ox + 8.0f * scale, oy + 16.0f * scale,
            ox + 9.5f * scale, oy + 16.0f * scale
        )

        // Резкие пилообразные всплески аномалий джиттера
        path.lineTo(ox + 12.5f * scale, oy + 3.0f * scale)   // Пиковый всплеск амплитуды вверх
        path.lineTo(ox + 14.8f * scale, oy + 21.0f * scale)  // Глубокий провал вниз
        path.lineTo(ox + 17.2f * scale, oy + 6.0f * scale)   // Вторичный всплеск
        path.lineTo(ox + 18.8f * scale, oy + 15.5f * scale)  // Хаотичная фазовая задержка
        path.lineTo(ox + 20.2f * scale, oy + 9.5f * scale)   // Микро-осцилляция
        path.lineTo(ox + 22.5f * scale, oy + 12.0f * scale)  // Возврат к оси

        // Верхняя координатная засечка допустимого коридора задержки
        path.moveTo(ox + 11.0f * scale, oy + 1.8f * scale)
        path.lineTo(ox + 14.0f * scale, oy + 1.8f * scale)
        path.moveTo(ox + 12.5f * scale, oy + 1.8f * scale)
        path.lineTo(ox + 12.5f * scale, oy + 3.0f * scale)

        // Нижняя координатная засечка допустимого коридора
        path.moveTo(ox + 13.3f * scale, oy + 22.2f * scale)
        path.lineTo(ox + 16.3f * scale, oy + 22.2f * scale)
        path.moveTo(ox + 14.8f * scale, oy + 21.0f * scale)
        path.lineTo(ox + 14.8f * scale, oy + 22.2f * scale)

        drawPath(path = path, color = color, style = stroke)
    }
}

/**
 * ic_diag_media: пленочный векторный фрейм с вписанным треугольником воспроизведения потока.
 */
@Composable
fun DiagMediaIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Внешний контур пленочного фрейма
        path.moveTo(ox + 5.0f * scale, oy + 4.5f * scale)
        path.lineTo(ox + 19.0f * scale, oy + 4.5f * scale)
        path.cubicTo(ox + 21.0f * scale, oy + 4.5f * scale, ox + 21.5f * scale, oy + 5.0f * scale, ox + 21.5f * scale, oy + 7.0f * scale)
        path.lineTo(ox + 21.5f * scale, oy + 17.0f * scale)
        path.cubicTo(ox + 21.5f * scale, oy + 19.0f * scale, ox + 21.0f * scale, oy + 19.5f * scale, ox + 19.0f * scale, oy + 19.5f * scale)
        path.lineTo(ox + 5.0f * scale, oy + 19.5f * scale)
        path.cubicTo(ox + 3.0f * scale, oy + 19.5f * scale, ox + 2.5f * scale, oy + 19.0f * scale, ox + 2.5f * scale, oy + 17.0f * scale)
        path.lineTo(ox + 2.5f * scale, oy + 7.0f * scale)
        path.cubicTo(ox + 2.5f * scale, oy + 5.0f * scale, ox + 3.0f * scale, oy + 4.5f * scale, ox + 5.0f * scale, oy + 4.5f * scale)
        path.close()

        // Разделительные дорожки перфорации
        path.moveTo(ox + 6.8f * scale, oy + 4.5f * scale)
        path.lineTo(ox + 6.8f * scale, oy + 19.5f * scale)

        path.moveTo(ox + 17.2f * scale, oy + 4.5f * scale)
        path.lineTo(ox + 17.2f * scale, oy + 19.5f * scale)

        // Левые перфорационные отверстия пленки
        path.moveTo(ox + 3.8f * scale, oy + 7.0f * scale)
        path.lineTo(ox + 5.5f * scale, oy + 7.0f * scale)

        path.moveTo(ox + 3.8f * scale, oy + 12.0f * scale)
        path.lineTo(ox + 5.5f * scale, oy + 12.0f * scale)

        path.moveTo(ox + 3.8f * scale, oy + 17.0f * scale)
        path.lineTo(ox + 5.5f * scale, oy + 17.0f * scale)

        // Правые перфорационные отверстия пленки
        path.moveTo(ox + 18.5f * scale, oy + 7.0f * scale)
        path.lineTo(ox + 20.2f * scale, oy + 7.0f * scale)

        path.moveTo(ox + 18.5f * scale, oy + 12.0f * scale)
        path.lineTo(ox + 20.2f * scale, oy + 12.0f * scale)

        path.moveTo(ox + 18.5f * scale, oy + 17.0f * scale)
        path.lineTo(ox + 20.2f * scale, oy + 17.0f * scale)

        // Вписанный треугольник воспроизведения медиапотока (Play)
        path.moveTo(ox + 10.5f * scale, oy + 8.5f * scale)
        path.lineTo(ox + 15.0f * scale, oy + 12.0f * scale)
        path.lineTo(ox + 10.5f * scale, oy + 15.5f * scale)
        path.close()

        drawPath(path = path, color = color, style = stroke)
    }
}

/**
 * ic_diag_protocol: двоичный байтовый фрейм [01] в контурной капсуле заголовка пакета.
 */
@Composable
fun DiagProtocolIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Внешняя контурная капсула заголовка пакета
        path.moveTo(ox + 7.0f * scale, oy + 4.5f * scale)
        path.lineTo(ox + 17.0f * scale, oy + 4.5f * scale)
        path.cubicTo(
            ox + 21.0f * scale, oy + 4.5f * scale,
            ox + 21.5f * scale, oy + 8.0f * scale,
            ox + 21.5f * scale, oy + 12.0f * scale
        )
        path.cubicTo(
            ox + 21.5f * scale, oy + 16.0f * scale,
            ox + 21.0f * scale, oy + 19.5f * scale,
            ox + 17.0f * scale, oy + 19.5f * scale
        )
        path.lineTo(ox + 7.0f * scale, oy + 19.5f * scale)
        path.cubicTo(
            ox + 3.0f * scale, oy + 19.5f * scale,
            ox + 2.5f * scale, oy + 16.0f * scale,
            ox + 2.5f * scale, oy + 12.0f * scale
        )
        path.cubicTo(
            ox + 2.5f * scale, oy + 8.0f * scale,
            ox + 3.0f * scale, oy + 4.5f * scale,
            ox + 7.0f * scale, oy + 4.5f * scale
        )
        path.close()

        // Левая скобка заголовка пакета '['
        path.moveTo(ox + 6.2f * scale, oy + 8.0f * scale)
        path.lineTo(ox + 4.8f * scale, oy + 8.0f * scale)
        path.lineTo(ox + 4.8f * scale, oy + 16.0f * scale)
        path.lineTo(ox + 6.2f * scale, oy + 16.0f * scale)

        // Правая скобка заголовка пакета ']'
        path.moveTo(ox + 17.8f * scale, oy + 8.0f * scale)
        path.lineTo(ox + 19.2f * scale, oy + 8.0f * scale)
        path.lineTo(ox + 19.2f * scale, oy + 16.0f * scale)
        path.lineTo(ox + 17.8f * scale, oy + 16.0f * scale)

        // Бит '0' слева
        path.moveTo(ox + 9.5f * scale, oy + 8.2f * scale)
        path.cubicTo(ox + 7.6f * scale, oy + 8.2f * scale, ox + 7.6f * scale, oy + 10.0f * scale, ox + 7.6f * scale, oy + 12.0f * scale)
        path.cubicTo(ox + 7.6f * scale, oy + 14.0f * scale, ox + 7.6f * scale, oy + 15.8f * scale, ox + 9.5f * scale, oy + 15.8f * scale)
        path.cubicTo(ox + 11.4f * scale, oy + 15.8f * scale, ox + 11.4f * scale, oy + 14.0f * scale, ox + 11.4f * scale, oy + 12.0f * scale)
        path.cubicTo(ox + 11.4f * scale, oy + 10.0f * scale, ox + 11.4f * scale, oy + 8.2f * scale, ox + 9.5f * scale, oy + 8.2f * scale)
        path.close()

        // Бит '1' справа
        path.moveTo(ox + 13.0f * scale, oy + 10.0f * scale)
        path.lineTo(ox + 14.8f * scale, oy + 8.2f * scale)
        path.lineTo(ox + 14.8f * scale, oy + 15.8f * scale)

        path.moveTo(ox + 13.5f * scale, oy + 15.8f * scale)
        path.lineTo(ox + 16.2f * scale, oy + 15.8f * scale)

        drawPath(path = path, color = color, style = stroke)
    }
}

/**
 * ic_diag_rtt: замкнутая кольцевая траектория с двумя встречными стрелками кругового обхода.
 */
@Composable
fun DiagRttIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Верхняя половина кольцевой орбиты (прямой ход, по часовой стрелке)
        path.moveTo(ox + 3.8f * scale, oy + 12.0f * scale)
        path.cubicTo(
            ox + 3.8f * scale, oy + 7.5f * scale,
            ox + 7.5f * scale, oy + 3.8f * scale,
            ox + 12.0f * scale, oy + 3.8f * scale
        )
        path.cubicTo(
            ox + 16.5f * scale, oy + 3.8f * scale,
            ox + 20.2f * scale, oy + 7.5f * scale,
            ox + 20.2f * scale, oy + 12.0f * scale
        )

        // Стрелка 1 (правая, направленная вниз по часовой стрелке)
        path.moveTo(ox + 17.5f * scale, oy + 9.8f * scale)
        path.lineTo(ox + 20.2f * scale, oy + 12.0f * scale)
        path.lineTo(ox + 22.0f * scale, oy + 9.2f * scale)

        // Нижняя половина кольцевой орбиты (обратный ход, против часовой стрелки)
        path.moveTo(ox + 20.2f * scale, oy + 12.0f * scale)
        path.cubicTo(
            ox + 20.2f * scale, oy + 16.5f * scale,
            ox + 16.5f * scale, oy + 20.2f * scale,
            ox + 12.0f * scale, oy + 20.2f * scale
        )
        path.cubicTo(
            ox + 7.5f * scale, oy + 20.2f * scale,
            ox + 3.8f * scale, oy + 16.5f * scale,
            ox + 3.8f * scale, oy + 12.0f * scale
        )

        // Стрелка 2 (левая, направленная вверх для замыкания полного Round-Trip цикла)
        path.moveTo(ox + 6.5f * scale, oy + 14.2f * scale)
        path.lineTo(ox + 3.8f * scale, oy + 12.0f * scale)
        path.lineTo(ox + 2.0f * scale, oy + 14.8f * scale)

        // Центральный хронометрический циферблат замера пинга
        path.moveTo(ox + 12.0f * scale, oy + 12.0f * scale)
        path.lineTo(ox + 12.0f * scale, oy + 8.5f * scale)

        path.moveTo(ox + 12.0f * scale, oy + 12.0f * scale)
        path.lineTo(ox + 14.5f * scale, oy + 14.5f * scale)

        drawPath(path = path, color = color, style = stroke)

        // Центральная ось замера времени
        drawCircle(
            color = color,
            radius = 1.0f * scale,
            center = Offset(ox + 12.0f * scale, oy + 12.0f * scale)
        )
    }
}

/**
 * ic_diag_voip: стилизованная акустическая диафрагма гарнитуры с исходящими звуковыми волнами.
 */
@Composable
fun DiagVoipIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Дужка оголовья гарнитуры
        path.moveTo(ox + 5.0f * scale, oy + 8.5f * scale)
        path.cubicTo(
            ox + 5.0f * scale, oy + 4.5f * scale,
            ox + 8.5f * scale, oy + 3.2f * scale,
            ox + 13.0f * scale, oy + 3.2f * scale
        )

        // Корпус акустического излучателя / наушника
        path.moveTo(ox + 4.2f * scale, oy + 8.5f * scale)
        path.lineTo(ox + 4.2f * scale, oy + 15.5f * scale)

        // Акустический конус диафрагмы
        path.moveTo(ox + 4.2f * scale, oy + 9.2f * scale)
        path.lineTo(ox + 7.5f * scale, oy + 10.8f * scale)
        path.lineTo(ox + 7.5f * scale, oy + 13.2f * scale)
        path.lineTo(ox + 4.2f * scale, oy + 14.8f * scale)

        // Выносная штанга микрофона
        path.moveTo(ox + 4.2f * scale, oy + 15.5f * scale)
        path.cubicTo(
            ox + 4.2f * scale, oy + 18.5f * scale,
            ox + 6.0f * scale, oy + 20.5f * scale,
            ox + 8.8f * scale, oy + 20.5f * scale
        )
        // Капсюль микрофона
        path.lineTo(ox + 10.5f * scale, oy + 20.5f * scale)

        // Волна 1 (ближняя звуковая волна)
        path.moveTo(ox + 10.5f * scale, oy + 9.5f * scale)
        path.cubicTo(ox + 12.0f * scale, oy + 10.8f * scale, ox + 12.0f * scale, oy + 13.2f * scale, ox + 10.5f * scale, oy + 14.5f * scale)

        // Волна 2 (средняя звуковая волна)
        path.moveTo(ox + 14.2f * scale, oy + 7.5f * scale)
        path.cubicTo(ox + 16.5f * scale, oy + 9.5f * scale, ox + 16.5f * scale, oy + 14.5f * scale, ox + 14.2f * scale, oy + 16.5f * scale)

        // Волна 3 (внешняя широкополосная звуковая волна)
        path.moveTo(ox + 18.0f * scale, oy + 5.5f * scale)
        path.cubicTo(ox + 21.2f * scale, oy + 8.5f * scale, ox + 21.2f * scale, oy + 15.5f * scale, ox + 18.0f * scale, oy + 18.5f * scale)

        drawPath(path = path, color = color, style = stroke)
    }
}

/**
 * ic_diag_worker: кремниевый V8-модуль с орбитальным электроном-воркером.
 */
@Composable
fun DiagWorkerIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Фасетированный кремниевый корпус V8-чипа (октагональная геометрия)
        path.moveTo(ox + 7.5f * scale, oy + 5.5f * scale)
        path.lineTo(ox + 16.5f * scale, oy + 5.5f * scale)
        path.lineTo(ox + 18.5f * scale, oy + 7.5f * scale)
        path.lineTo(ox + 18.5f * scale, oy + 16.5f * scale)
        path.lineTo(ox + 16.5f * scale, oy + 18.5f * scale)
        path.lineTo(ox + 7.5f * scale, oy + 18.5f * scale)
        path.lineTo(ox + 5.5f * scale, oy + 16.5f * scale)
        path.lineTo(ox + 5.5f * scale, oy + 7.5f * scale)
        path.close()

        // Внешние пины контактов модуля
        // Верхние
        path.moveTo(ox + 9.5f * scale, oy + 3.0f * scale)
        path.lineTo(ox + 9.5f * scale, oy + 5.5f * scale)
        path.moveTo(ox + 14.5f * scale, oy + 3.0f * scale)
        path.lineTo(ox + 14.5f * scale, oy + 5.5f * scale)

        // Нижние
        path.moveTo(ox + 9.5f * scale, oy + 18.5f * scale)
        path.lineTo(ox + 9.5f * scale, oy + 21.0f * scale)
        path.moveTo(ox + 14.5f * scale, oy + 18.5f * scale)
        path.lineTo(ox + 14.5f * scale, oy + 21.0f * scale)

        // Левые
        path.moveTo(ox + 3.0f * scale, oy + 9.5f * scale)
        path.lineTo(ox + 5.5f * scale, oy + 9.5f * scale)
        path.moveTo(ox + 3.0f * scale, oy + 14.5f * scale)
        path.lineTo(ox + 5.5f * scale, oy + 14.5f * scale)

        // Правые
        path.moveTo(ox + 18.5f * scale, oy + 9.5f * scale)
        path.lineTo(ox + 21.0f * scale, oy + 9.5f * scale)
        path.moveTo(ox + 18.5f * scale, oy + 14.5f * scale)
        path.lineTo(ox + 21.0f * scale, oy + 14.5f * scale)

        // Внутренняя маркировка V8
        // Литера 'V'
        path.moveTo(ox + 8.2f * scale, oy + 9.5f * scale)
        path.lineTo(ox + 10.5f * scale, oy + 14.5f * scale)
        path.lineTo(ox + 12.5f * scale, oy + 9.5f * scale)

        // Цифра '8'
        // Верхняя петля
        path.addArc(
            oval = Rect(
                left = ox + 13.5f * scale,
                top = oy + 9.2f * scale,
                right = ox + 16.5f * scale,
                bottom = oy + 12.0f * scale
            ),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 360f
        )
        // Нижняя петля
        path.addArc(
            oval = Rect(
                left = ox + 13.2f * scale,
                top = oy + 11.8f * scale,
                right = ox + 16.8f * scale,
                bottom = oy + 15.0f * scale
            ),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 360f
        )

        // Наклонная квантовая орбита электрона-воркера вокруг ядра модуля
        val cx = 12.0f
        val cy = 12.0f
        val rx = 9.8f
        val ry = 4.2f
        val rotRad = -0.5236f // -30 градусов

        val cosR = cos(rotRad)
        val sinR = sin(rotRad)

        val steps = 32
        for (i in 0..steps) {
            val theta = (i.toFloat() / steps) * 2f * PI.toFloat()
            val ex = rx * cos(theta)
            val ey = ry * sin(theta)
            val px = cx + ex * cosR - ey * sinR
            val py = cy + ex * sinR + ey * cosR

            if (i == 0) {
                path.moveTo(ox + px * scale, oy + py * scale)
            } else {
                path.lineTo(ox + px * scale, oy + py * scale)
            }
        }

        drawPath(path = path, color = color, style = stroke)

        // Орбитальный электрон-воркер на квантовой траектории
        val electronTheta = 0.85f
        val ex0 = rx * cos(electronTheta)
        val ey0 = ry * sin(electronTheta)
        val elX = cx + ex0 * cosR - ey0 * sinR
        val elY = cy + ex0 * sinR + ey0 * cosR

        drawCircle(
            color = color,
            radius = 1.1f * scale,
            center = Offset(ox + elX * scale, oy + elY * scale)
        )
    }
}

// ============================================================================
// 2. НАРАСТАЮЩАЯ СИСТЕМА ИКОНОК СКОРОСТИ (5 ИКОНОК)
// ============================================================================

/**
 * ic_speed_auto: круговой спидометр с литерой "A" и адаптивной стрелкой.
 */
@Composable
fun SpeedAutoIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        val cx = 12.0f
        val cy = 13.0f
        val r = 8.5f

        // Шкала спидометра (дуга от 145 до 395 градусов, размах 250°)
        path.addArc(
            oval = Rect(
                left = ox + (cx - r) * scale,
                top = oy + (cy - r) * scale,
                right = ox + (cx + r) * scale,
                bottom = oy + (cy + r) * scale
            ),
            startAngleDegrees = 145f,
            sweepAngleDegrees = 250f
        )

        // Калибровочные риски скоростной шкалы
        val tickAngles = floatArrayOf(155f, 210f, 270f, 330f, 385f)
        for (deg in tickAngles) {
            val rad = deg * PI.toFloat() / 180f
            val x1 = cx + (r - 0.2f) * cos(rad)
            val y1 = cy + (r - 0.2f) * sin(rad)
            val x2 = cx + (r - 2.0f) * cos(rad)
            val y2 = cy + (r - 2.0f) * sin(rad)

            path.moveTo(ox + x1 * scale, oy + y1 * scale)
            path.lineTo(ox + x2 * scale, oy + y2 * scale)
        }

        // Адаптивная динамическая стрелка прибора (нацелена в зону круизной авто-скорости ~315°)
        val needleRad = 315f * PI.toFloat() / 180f
        val tipX = cx + 6.8f * cos(needleRad)
        val tipY = cy + 6.8f * sin(needleRad)

        path.moveTo(ox + cx * scale, oy + cy * scale)
        path.lineTo(ox + tipX * scale, oy + tipY * scale)

        // Наконечник стрелки
        path.moveTo(ox + (tipX - 1.8f) * scale, oy + tipY * scale)
        path.lineTo(ox + tipX * scale, oy + tipY * scale)
        path.lineTo(ox + tipX * scale, oy + (tipY + 1.8f) * scale)

        // Символ автоматического режима "A" в основании прибора
        path.moveTo(ox + 12.0f * scale, oy + 15.0f * scale)
        path.lineTo(ox + 9.5f * scale, oy + 21.0f * scale)

        path.moveTo(ox + 12.0f * scale, oy + 15.0f * scale)
        path.lineTo(ox + 14.5f * scale, oy + 21.0f * scale)

        path.moveTo(ox + 10.5f * scale, oy + 18.8f * scale)
        path.lineTo(ox + 13.5f * scale, oy + 18.8f * scale)

        drawPath(path = path, color = color, style = stroke)

        // Центральная ступица стрелки
        drawCircle(
            color = color,
            radius = 1.4f * scale,
            center = Offset(ox + cx * scale, oy + cy * scale)
        )
    }
}

/**
 * ic_speed_eco: одиночное аэродинамическое перо/лепесток с минимальным сопротивлением.
 */
@Composable
fun SpeedEcoIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Очин / стержень пера у основания
        path.moveTo(ox + 3.0f * scale, oy + 21.0f * scale)
        path.lineTo(ox + 6.0f * scale, oy + 18.0f * scale)

        // Центральный стержень (рахис) с аэродинамическим изгибом к вершине (21, 3)
        path.moveTo(ox + 6.0f * scale, oy + 18.0f * scale)
        path.cubicTo(
            ox + 10.0f * scale, oy + 14.5f * scale,
            ox + 15.0f * scale, oy + 9.5f * scale,
            ox + 21.0f * scale, oy + 3.0f * scale
        )

        // Верхнее ламинарное опахало пера
        path.moveTo(ox + 6.0f * scale, oy + 18.0f * scale)
        path.cubicTo(
            ox + 5.5f * scale, oy + 12.5f * scale,
            ox + 9.5f * scale, oy + 6.5f * scale,
            ox + 15.0f * scale, oy + 4.8f * scale
        )
        path.cubicTo(
            ox + 17.5f * scale, oy + 4.0f * scale,
            ox + 19.5f * scale, oy + 3.2f * scale,
            ox + 21.0f * scale, oy + 3.0f * scale
        )

        // Нижнее ламинарное опахало пера
        path.moveTo(ox + 6.0f * scale, oy + 18.0f * scale)
        path.cubicTo(
            ox + 11.5f * scale, oy + 18.5f * scale,
            ox + 16.5f * scale, oy + 14.5f * scale,
            ox + 18.5f * scale, oy + 9.0f * scale
        )
        path.cubicTo(
            ox + 19.5f * scale, oy + 6.5f * scale,
            ox + 20.5f * scale, oy + 4.5f * scale,
            ox + 21.0f * scale, oy + 3.0f * scale
        )

        // Ламинарные микро-прорези минимального воздушного сопротивления
        path.moveTo(ox + 11.0f * scale, oy + 9.5f * scale)
        path.lineTo(ox + 13.5f * scale, oy + 7.5f * scale)

        path.moveTo(ox + 14.5f * scale, oy + 7.0f * scale)
        path.lineTo(ox + 17.0f * scale, oy + 5.0f * scale)

        // Спутный след ламинарного обтекания
        path.moveTo(ox + 1.8f * scale, oy + 22.2f * scale)
        path.lineTo(ox + 3.2f * scale, oy + 20.8f * scale)

        drawPath(path = path, color = color, style = stroke)
    }
}

/**
 * ic_speed_balanced: сдвоенный стабилизатор курсовой устойчивости с симметричными крыльями.
 */
@Composable
fun SpeedBalancedIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Центральный осевой киль курсовой стабилизации
        path.moveTo(ox + 12.0f * scale, oy + 3.0f * scale)
        path.lineTo(ox + 12.0f * scale, oy + 21.0f * scale)

        // Симметричное левое крыло обратной/стреловидной стреловидности
        path.moveTo(ox + 12.0f * scale, oy + 9.0f * scale)
        path.lineTo(ox + 2.5f * scale, oy + 17.0f * scale)
        path.lineTo(ox + 4.5f * scale, oy + 18.0f * scale)
        path.lineTo(ox + 10.0f * scale, oy + 16.0f * scale)

        // Симметричное правое крыло
        path.moveTo(ox + 12.0f * scale, oy + 9.0f * scale)
        path.lineTo(ox + 21.5f * scale, oy + 17.0f * scale)
        path.lineTo(ox + 19.5f * scale, oy + 18.0f * scale)
        path.lineTo(ox + 14.0f * scale, oy + 16.0f * scale)

        // Левый вертикальный стабилизатор курса (canted fin)
        path.moveTo(ox + 8.5f * scale, oy + 16.5f * scale)
        path.lineTo(ox + 7.0f * scale, oy + 5.0f * scale)
        path.lineTo(ox + 9.5f * scale, oy + 16.5f * scale)

        // Правый вертикальный стабилизатор курса
        path.moveTo(ox + 15.5f * scale, oy + 16.5f * scale)
        path.lineTo(ox + 17.0f * scale, oy + 5.0f * scale)
        path.lineTo(ox + 14.5f * scale, oy + 16.5f * scale)

        // Горизонтальная балка триммера баланса
        path.moveTo(ox + 7.0f * scale, oy + 16.5f * scale)
        path.lineTo(ox + 17.0f * scale, oy + 16.5f * scale)

        drawPath(path = path, color = color, style = stroke)

        // Носовой датчик курсового угла
        drawCircle(
            color = color,
            radius = 0.85f * scale,
            center = Offset(ox + 12.0f * scale, oy + 3.0f * scale)
        )
    }
}

/**
 * ic_speed_turbo: реактивная турбина с тремя закрученными лопатками крыльчатки.
 */
@Composable
fun SpeedTurboIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        val cx = 12.0f
        val cy = 12.0f
        val outerR = 9.5f
        val hubR = 2.5f

        // Внешнее кольцо гондолы турбины
        path.addArc(
            oval = Rect(
                left = ox + (cx - outerR) * scale,
                top = oy + (cy - outerR) * scale,
                right = ox + (cx + outerR) * scale,
                bottom = oy + (cy + outerR) * scale
            ),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 360f
        )

        // Центральный обтекатель / ступица турбины
        path.addArc(
            oval = Rect(
                left = ox + (cx - hubR) * scale,
                top = oy + (cy - hubR) * scale,
                right = ox + (cx + hubR) * scale,
                bottom = oy + (cy + hubR) * scale
            ),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 360f
        )

        // 3 закрученные лопатки крыльчатки (центробежный турбокомпрессор под 120°)
        // Лопатка 1 (Северная)
        path.moveTo(ox + 12.0f * scale, oy + (cy - hubR) * scale)
        path.cubicTo(
            ox + 14.5f * scale, oy + 7.5f * scale,
            ox + 16.0f * scale, oy + 4.8f * scale,
            ox + 12.0f * scale, oy + (cy - outerR) * scale
        )
        path.cubicTo(
            ox + 9.8f * scale, oy + 5.2f * scale,
            ox + 10.2f * scale, oy + 8.0f * scale,
            ox + 10.8f * scale, oy + 9.8f * scale
        )

        // Лопатка 2 (Юго-Восточная, 120°)
        path.moveTo(ox + 14.2f * scale, oy + 13.2f * scale)
        path.cubicTo(
            ox + 16.5f * scale, oy + 14.8f * scale,
            ox + 19.5f * scale, oy + 16.5f * scale,
            ox + 20.2f * scale, oy + 17.0f * scale
        )
        path.cubicTo(
            ox + 17.5f * scale, oy + 18.0f * scale,
            ox + 14.5f * scale, oy + 15.5f * scale,
            ox + 13.5f * scale, oy + 14.2f * scale
        )

        // Лопатка 3 (Юго-Западная, 240°)
        path.moveTo(ox + 9.8f * scale, oy + 13.2f * scale)
        path.cubicTo(
            ox + 7.5f * scale, oy + 14.8f * scale,
            ox + 4.5f * scale, oy + 16.5f * scale,
            ox + 3.8f * scale, oy + 17.0f * scale
        )
        path.cubicTo(
            ox + 6.5f * scale, oy + 18.0f * scale,
            ox + 9.5f * scale, oy + 15.5f * scale,
            ox + 10.5f * scale, oy + 14.2f * scale
        )

        // Динамические тангенциальные насечки ускорения воздушного потока на обечайке
        path.moveTo(ox + 15.0f * scale, oy + 2.8f * scale)
        path.lineTo(ox + 17.0f * scale, oy + 4.2f * scale)

        path.moveTo(ox + 21.2f * scale, oy + 13.5f * scale)
        path.lineTo(ox + 20.8f * scale, oy + 16.0f * scale)

        path.moveTo(ox + 3.2f * scale, oy + 8.5f * scale)
        path.lineTo(ox + 2.8f * scale, oy + 11.0f * scale)

        drawPath(path = path, color = color, style = stroke)

        // Центральная микро-точка ступицы
        drawCircle(
            color = color,
            radius = 0.9f * scale,
            center = Offset(ox + cx * scale, oy + cy * scale)
        )
    }
}

/**
 * ic_speed_ultra: тройное гиперзвуковое сопло с расходящимися волнами Маха.
 */
@Composable
fun SpeedUltraIcon(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(width = with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 24f
        val ox = (size.width - 24f * scale) / 2f
        val oy = (size.height - 24f * scale) / 2f

        path.reset()

        // Верхний силовой коллектор подачи гиперзвукового топлива
        path.moveTo(ox + 3.5f * scale, oy + 3.5f * scale)
        path.lineTo(ox + 20.5f * scale, oy + 3.5f * scale)

        // Сопло 1 (Левое)
        path.moveTo(ox + 5.2f * scale, oy + 3.5f * scale)
        path.lineTo(ox + 5.5f * scale, oy + 5.5f * scale)
        path.lineTo(ox + 4.5f * scale, oy + 8.5f * scale)
        path.lineTo(ox + 7.8f * scale, oy + 8.5f * scale)
        path.lineTo(ox + 6.8f * scale, oy + 5.5f * scale)
        path.lineTo(ox + 7.2f * scale, oy + 3.5f * scale)

        // Сопло 2 (Центральное маршевое, форсированное)
        path.moveTo(ox + 10.2f * scale, oy + 3.5f * scale)
        path.lineTo(ox + 10.8f * scale, oy + 5.5f * scale)
        path.lineTo(ox + 9.2f * scale, oy + 9.0f * scale)
        path.lineTo(ox + 14.8f * scale, oy + 9.0f * scale)
        path.lineTo(ox + 13.2f * scale, oy + 5.5f * scale)
        path.lineTo(ox + 13.8f * scale, oy + 3.5f * scale)

        // Сопло 3 (Правое)
        path.moveTo(ox + 16.8f * scale, oy + 3.5f * scale)
        path.lineTo(ox + 17.2f * scale, oy + 5.5f * scale)
        path.lineTo(ox + 16.2f * scale, oy + 8.5f * scale)
        path.lineTo(ox + 19.5f * scale, oy + 8.5f * scale)
        path.lineTo(ox + 18.5f * scale, oy + 5.5f * scale)
        path.lineTo(ox + 18.8f * scale, oy + 3.5f * scale)

        // Волна Маха 1: скачок уплотнения (ударный ромб / Mach shock diamond)
        path.moveTo(ox + 12.0f * scale, oy + 10.0f * scale)
        path.lineTo(ox + 10.5f * scale, oy + 11.8f * scale)
        path.lineTo(ox + 12.0f * scale, oy + 13.6f * scale)
        path.lineTo(ox + 13.5f * scale, oy + 11.8f * scale)
        path.close()

        // Волна Маха 2: промежуточный гиперзвуковой фронт (угол конуса Маха)
        path.moveTo(ox + 6.0f * scale, oy + 17.5f * scale)
        path.lineTo(ox + 12.0f * scale, oy + 13.6f * scale)
        path.lineTo(ox + 18.0f * scale, oy + 17.5f * scale)

        // Волна Маха 3: расходящаяся внешняя волна сверхзвукового прорыва
        path.moveTo(ox + 2.5f * scale, oy + 21.5f * scale)
        path.lineTo(ox + 12.0f * scale, oy + 17.0f * scale)
        path.lineTo(ox + 21.5f * scale, oy + 21.5f * scale)

        drawPath(path = path, color = color, style = stroke)
    }
}

// ============================================================================
// 3. УНИВЕРСАЛЬНЫЙ СЕЛЕКТОР И МАППИНГ РЕСУРСОВ
// ============================================================================

/**
 * Перечисление всех 17 процедурных векторных компонентов телеметрии и скорости.
 */
enum class MirrlyTelemetryIconType {
    DIAG_ANTENNA,
    DIAG_BUFFERBLOAT,
    DIAG_CLOUDFLARE,
    DIAG_DELIVERY,
    DIAG_FILES,
    DIAG_FORMULA,
    DIAG_JITTER,
    DIAG_MEDIA,
    DIAG_PROTOCOL,
    DIAG_RTT,
    DIAG_VOIP,
    DIAG_WORKER,
    SPEED_AUTO,
    SPEED_ECO,
    SPEED_BALANCED,
    SPEED_TURBO,
    SPEED_ULTRA
}

/**
 * Единый компонент вызова векторной иконки по перечислению [MirrlyTelemetryIconType].
 */
@Composable
fun MirrlyTelemetryIcon(
    type: MirrlyTelemetryIconType,
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    when (type) {
        MirrlyTelemetryIconType.DIAG_ANTENNA -> DiagAntennaIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.DIAG_BUFFERBLOAT -> DiagBufferbloatIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.DIAG_CLOUDFLARE -> DiagCloudflareIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.DIAG_DELIVERY -> DiagDeliveryIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.DIAG_FILES -> DiagFilesIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.DIAG_FORMULA -> DiagFormulaIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.DIAG_JITTER -> DiagJitterIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.DIAG_MEDIA -> DiagMediaIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.DIAG_PROTOCOL -> DiagProtocolIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.DIAG_RTT -> DiagRttIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.DIAG_VOIP -> DiagVoipIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.DIAG_WORKER -> DiagWorkerIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.SPEED_AUTO -> SpeedAutoIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.SPEED_ECO -> SpeedEcoIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.SPEED_BALANCED -> SpeedBalancedIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.SPEED_TURBO -> SpeedTurboIcon(modifier, color, strokeWidth)
        MirrlyTelemetryIconType.SPEED_ULTRA -> SpeedUltraIcon(modifier, color, strokeWidth)
    }
}

/**
 * Умный адаптер векторных иконок: принимает идентификатор [iconRes] из `R.drawable.*`
 * и автоматически подставляет процедурный Canvas-рендерер, обеспечивая 120 FPS и
 * бесшовную замену стандартных XML drawables без изменения бизнес-логики экранов.
 */
@Composable
fun MirrlyTelemetryIcon(
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    when (iconRes) {
        R.drawable.ic_diag_antenna -> DiagAntennaIcon(modifier, color, strokeWidth)
        R.drawable.ic_diag_bufferbloat -> DiagBufferbloatIcon(modifier, color, strokeWidth)
        R.drawable.ic_diag_cloudflare -> DiagCloudflareIcon(modifier, color, strokeWidth)
        R.drawable.ic_diag_delivery -> DiagDeliveryIcon(modifier, color, strokeWidth)
        R.drawable.ic_diag_files -> DiagFilesIcon(modifier, color, strokeWidth)
        R.drawable.ic_diag_formula -> DiagFormulaIcon(modifier, color, strokeWidth)
        R.drawable.ic_diag_jitter -> DiagJitterIcon(modifier, color, strokeWidth)
        R.drawable.ic_diag_media -> DiagMediaIcon(modifier, color, strokeWidth)
        R.drawable.ic_diag_protocol -> DiagProtocolIcon(modifier, color, strokeWidth)
        R.drawable.ic_diag_rtt -> DiagRttIcon(modifier, color, strokeWidth)
        R.drawable.ic_diag_voip -> DiagVoipIcon(modifier, color, strokeWidth)
        R.drawable.ic_diag_worker -> DiagWorkerIcon(modifier, color, strokeWidth)
        R.drawable.ic_speed_auto -> SpeedAutoIcon(modifier, color, strokeWidth)
        R.drawable.ic_speed_eco -> SpeedEcoIcon(modifier, color, strokeWidth)
        R.drawable.ic_speed_balanced -> SpeedBalancedIcon(modifier, color, strokeWidth)
        R.drawable.ic_speed_turbo -> SpeedTurboIcon(modifier, color, strokeWidth)
        R.drawable.ic_speed_ultra -> SpeedUltraIcon(modifier, color, strokeWidth)
        else -> {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = modifier,
                tint = color
            )
        }
    }
}

// ============================================================================
// 4. ALIASES ДЛЯ 100% СОВМЕСТИМОСТИ С ИМЕНАМИ РЕСУРСОВ И ПАКЕТОВ
// ============================================================================

@Composable
fun IconDiagAntenna(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    DiagAntennaIcon(modifier, color, strokeWidth)

@Composable
fun IconDiagBufferbloat(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    DiagBufferbloatIcon(modifier, color, strokeWidth)

@Composable
fun IconDiagCloudflare(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    DiagCloudflareIcon(modifier, color, strokeWidth)

@Composable
fun IconDiagDelivery(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    DiagDeliveryIcon(modifier, color, strokeWidth)

@Composable
fun IconDiagFiles(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    DiagFilesIcon(modifier, color, strokeWidth)

@Composable
fun IconDiagFormula(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    DiagFormulaIcon(modifier, color, strokeWidth)

@Composable
fun IconDiagJitter(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    DiagJitterIcon(modifier, color, strokeWidth)

@Composable
fun IconDiagMedia(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    DiagMediaIcon(modifier, color, strokeWidth)

@Composable
fun IconDiagProtocol(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    DiagProtocolIcon(modifier, color, strokeWidth)

@Composable
fun IconDiagRtt(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    DiagRttIcon(modifier, color, strokeWidth)

@Composable
fun IconDiagVoip(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    DiagVoipIcon(modifier, color, strokeWidth)

@Composable
fun IconDiagWorker(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    DiagWorkerIcon(modifier, color, strokeWidth)

@Composable
fun IconSpeedAuto(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    SpeedAutoIcon(modifier, color, strokeWidth)

@Composable
fun IconSpeedEco(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    SpeedEcoIcon(modifier, color, strokeWidth)

@Composable
fun IconSpeedBalanced(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    SpeedBalancedIcon(modifier, color, strokeWidth)

@Composable
fun SpeedTurboIconAlias(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    SpeedTurboIcon(modifier, color, strokeWidth)

@Composable
fun IconSpeedTurbo(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    SpeedTurboIcon(modifier, color, strokeWidth)

@Composable
fun IconSpeedUltra(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalProtocolColors.current.primary, strokeWidth: Dp = 1.5.dp) =
    SpeedUltraIcon(modifier, color, strokeWidth)
