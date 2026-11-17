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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors

/**
 * Набор процедурных векторных иконок для системных уведомлений Android, строки состояния (Status Bar)
 * и шторки быстрых настроек (Quick Settings Tile) по стандарту MirrlyUI /mirrly-icons.
 *
 * Особенности категории «Системные уведомления и Quick Settings»:
 * - Идеальная читаемость в сверхмалом размере (18-24dp) на темном фоне системного лотка.
 * - Строго контурный стиль (Outline-Only, 1.5dp) со скругленными окончаниями (StrokeCap.Round, StrokeJoin.Round).
 * - Полное отсутствие глухих фоновых заливок и визуального шума.
 * - Гарантированный Zero-Allocation рендеринг в DrawScope (кэширование Path и Stroke, переиспользование через reset()).
 */
object MirrlyNotificationIcons {

    // =========================================================================
    // Константы эквивалентных векторных путей SVG / Android Vector Drawable (24x24)
    // =========================================================================

    /**
     * ic_stat_proxy_connected: Стилизованный щит-глайдер с центральной точкой захвата луча.
     */
    const val PATH_STAT_PROXY_CONNECTED_CONTOUR =
        "M12,2.5 L19.5,5.5 V12 C19.5,16.5 15.5,19.8 12,21.5 C8.5,19.8 4.5,16.5 4.5,12 V5.5 Z M12,6.5 V8.5 M12,14.5 V16.5"
    const val PATH_STAT_PROXY_CONNECTED_POINT =
        "M12,11.5m-1.25,0a1.25,1.25 0 1,0 2.5,0a1.25,1.25 0 1,0 -2.5,0"

    /**
     * ic_stat_proxy_error: Шестиугольный контур аварийного модуля с разорванным нижним контуром и диагональным крестом внутри.
     */
    const val PATH_STAT_PROXY_ERROR =
        "M14.5,19 H16 L20.5,11 L16,3 H8 L3.5,11 L8,19 H9.5 M8.5,7.5 L15.5,14.5 M15.5,7.5 L8.5,14.5"

    /**
     * ic_stat_proxy_warning: Равносторонний треугольный контур со скругленными вершинами и парящим вертикальным восклицательным штрихом.
     */
    const val PATH_STAT_PROXY_WARNING =
        "M10.7,4.8 C11.3,3.7 12.7,3.7 13.3,4.8 L20.7,17.8 C21.3,18.9 20.6,20 19.4,20 H4.6 C3.4,20 2.7,18.9 3.3,17.8 Z M12,8.5 V13.5 M12,16.5 h0.01"

    /**
     * ic_stat_timer: Упрощенный плоский циферблат с фиксированным положением стрелок на 3 часа для максимальной читаемости.
     */
    const val PATH_STAT_TIMER =
        "M10,2 H14 M12,2 V4.5 M12,4.5 A8.5,8.5 0 1,0 12.01,4.5 M12,8 V13 H16.5"

    /**
     * ic_stat_update: Стрелка обновления, пробивающая вверх дугу горизонта (символ выхода новой сборки).
     */
    const val PATH_STAT_UPDATE =
        "M3.5,15 C5.5,12.5 7.5,11.2 9.5,11 M14.5,11 C16.5,11.2 18.5,12.5 20.5,15 M9.5,20.5 H14.5 M12,20.5 V3.5 M7,8.5 L12,3.5 L17,8.5"

    /**
     * ic_notif_stop: Правильный контурный восьмигранник (форма стоп-знака) с контурным квадратом внутри.
     */
    const val PATH_NOTIF_STOP =
        "M8,2.5 H16 L21.5,8 V16 L16,21.5 H8 L2.5,16 V8 Z M8.5,8.5 H15.5 V15.5 H8.5 Z"

    /**
     * ic_qs_proxy: Монументальный векторный самолет-глайдер с орбитальным кольцом (главный значок шторки быстрых настроек).
     */
    const val PATH_QS_PROXY =
        "M12,2.5 L14,7.5 L20.5,17.5 L13.5,15 L12,14 L10.5,15 L3.5,17.5 L10,7.5 Z M12,2.5 V14 M2.5,11.5 C2.5,9.3 6.7,7.5 12,7.5 C17.3,7.5 21.5,9.3 21.5,11.5 C21.5,13.7 17.3,15.5 12,15.5 C6.7,15.5 2.5,13.7 2.5,11.5 Z"
}

/**
 * 1. ic_stat_proxy_connected: Стилизованный щит-глайдер с центральной точкой захвата луча.
 * Силуэт: аэродинамический щит с плечевыми наплывами, вертикальными направляющими лучами
 * и центральным квантовым узлом фокусировки.
 */
@Composable
fun MirrlyStatProxyConnectedIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f

        path.reset()
        // Внешний контур щита-глайдера
        path.moveTo(12f * sx, 2.5f * sy)
        path.lineTo(19.5f * sx, 5.5f * sy)
        path.lineTo(19.5f * sx, 12f * sy)
        path.cubicTo(
            19.5f * sx, 16.5f * sy,
            15.5f * sx, 19.8f * sy,
            12f * sx, 21.5f * sy
        )
        path.cubicTo(
            8.5f * sx, 19.8f * sy,
            4.5f * sx, 16.5f * sy,
            4.5f * sx, 12f * sy
        )
        path.lineTo(4.5f * sx, 5.5f * sy)
        path.close()

        // Вертикальные направляющие луча захвата
        path.moveTo(12f * sx, 6.5f * sy)
        path.lineTo(12f * sx, 8.5f * sy)
        path.moveTo(12f * sx, 14.5f * sy)
        path.lineTo(12f * sx, 16.5f * sy)

        drawPath(path = path, color = tint, style = stroke)

        // Центральная микро-точка захвата луча
        drawCircle(
            color = tint,
            radius = 1.25f * sx,
            center = Offset(12f * sx, 11.5f * sy)
        )
    }
}

/**
 * 2. ic_stat_proxy_error: Шестиугольный контур аварийного модуля с разорванным нижним контуром
 * и внутренним диагональным крестом.
 * Силуэт: четко читаемый разрыв бронекорпуса модуля снизу и резкий аварийный крест в центре.
 */
@Composable
fun MirrlyStatProxyErrorIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f

        path.reset()
        // Разорванный шестигранный контур модуля (пролом днища между X=9.5 и X=14.5)
        path.moveTo(14.5f * sx, 19f * sy)
        path.lineTo(16f * sx, 19f * sy)
        path.lineTo(20.5f * sx, 11f * sy)
        path.lineTo(16f * sx, 3f * sy)
        path.lineTo(8f * sx, 3f * sy)
        path.lineTo(3.5f * sx, 11f * sy)
        path.lineTo(8f * sx, 19f * sy)
        path.lineTo(9.5f * sx, 19f * sy)

        // Диагональный аварийный крест
        path.moveTo(8.5f * sx, 7.5f * sy)
        path.lineTo(15.5f * sx, 14.5f * sy)
        path.moveTo(15.5f * sx, 7.5f * sy)
        path.lineTo(8.5f * sx, 14.5f * sy)

        drawPath(path = path, color = tint, style = stroke)
    }
}

/**
 * 3. ic_stat_proxy_warning: Равносторонний треугольный контур со скругленными вершинами
 * и парящим вертикальным восклицательным штрихом.
 * Силуэт: идеальный оптически сбалансированный контур предупреждения с радиусными вершинами.
 */
@Composable
fun MirrlyStatProxyWarningIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f

        path.reset()
        // Равносторонний треугольник с мягкими скругленными вершинами
        path.moveTo(10.7f * sx, 4.8f * sy)
        path.cubicTo(
            11.3f * sx, 3.7f * sy,
            12.7f * sx, 3.7f * sy,
            13.3f * sx, 4.8f * sy
        )
        path.lineTo(20.7f * sx, 17.8f * sy)
        path.cubicTo(
            21.3f * sx, 18.9f * sy,
            20.6f * sx, 20f * sy,
            19.4f * sx, 20f * sy
        )
        path.lineTo(4.6f * sx, 20f * sy)
        path.cubicTo(
            3.4f * sx, 20f * sy,
            2.7f * sx, 18.9f * sy,
            3.3f * sx, 17.8f * sy
        )
        path.close()

        // Парящий вертикальный штрих восклицания
        path.moveTo(12f * sx, 8.5f * sy)
        path.lineTo(12f * sx, 13.5f * sy)

        drawPath(path = path, color = tint, style = stroke)

        // Парящая точка восклицания
        drawCircle(
            color = tint,
            radius = 0.85f * sx,
            center = Offset(12f * sx, 16.5f * sy)
        )
    }
}

/**
 * 4. ic_stat_timer: Упрощенный плоский циферблат с фиксированным положением стрелок на 3 часа.
 * Силуэт: верхняя кнопка-актуатор, идеальное кольцо безеля и стрелки 3:00 (вертикально вверх и горизонтально вправо).
 */
@Composable
fun MirrlyStatTimerIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f

        path.reset()
        // Верхняя кнопка таймера (crown)
        path.moveTo(10f * sx, 2f * sy)
        path.lineTo(14f * sx, 2f * sy)
        path.moveTo(12f * sx, 2f * sy)
        path.lineTo(12f * sx, 4.5f * sy)

        // Фиксированные стрелки на 3:00 (минутная на 12, часовая на 3)
        path.moveTo(12f * sx, 8f * sy)
        path.lineTo(12f * sx, 13f * sy)
        path.lineTo(16.5f * sx, 13f * sy)

        drawPath(path = path, color = tint, style = stroke)

        // Круглый циферблат
        drawCircle(
            color = tint,
            radius = 8.5f * sx,
            center = Offset(12f * sx, 13f * sy),
            style = stroke
        )
    }
}

/**
 * 5. ic_stat_update: Стрелка обновления, пробивающая вверх дугу горизонта.
 * Силуэт: мощная вертикальная стрела со стартовой платформы, пробивающая планетарную дугу горизонта вверх.
 */
@Composable
fun MirrlyStatUpdateIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f

        path.reset()
        // Левый сегмент пробиваемой дуги горизонта
        path.moveTo(3.5f * sx, 15f * sy)
        path.cubicTo(
            5.5f * sx, 12.5f * sy,
            7.5f * sx, 11.2f * sy,
            9.5f * sx, 11f * sy
        )

        // Правый сегмент пробиваемой дуги горизонта
        path.moveTo(14.5f * sx, 11f * sy)
        path.cubicTo(
            16.5f * sx, 11.2f * sy,
            18.5f * sx, 12.5f * sy,
            20.5f * sx, 15f * sy
        )

        // Стартовая опорная площадка
        path.moveTo(9.5f * sx, 20.5f * sy)
        path.lineTo(14.5f * sx, 20.5f * sy)

        // Восходящий стержень стрелы обновления
        path.moveTo(12f * sx, 20.5f * sy)
        path.lineTo(12f * sx, 3.5f * sy)

        // Острие стрелы (шеврон), устремленное в зенит
        path.moveTo(7f * sx, 8.5f * sy)
        path.lineTo(12f * sx, 3.5f * sy)
        path.lineTo(17f * sx, 8.5f * sy)

        drawPath(path = path, color = tint, style = stroke)
    }
}

/**
 * 6. ic_notif_stop: Правильный контурный восьмигранник (форма стоп-знака) с контурным квадратом внутри.
 * Силуэт: классический восьмиугольный габарит с внутренним стоп-ядром, строгая контурная геометрия.
 */
@Composable
fun MirrlyNotifStopIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f

        path.reset()
        // Внешний контурный восьмигранник стоп-знака
        path.moveTo(8f * sx, 2.5f * sy)
        path.lineTo(16f * sx, 2.5f * sy)
        path.lineTo(21.5f * sx, 8f * sy)
        path.lineTo(21.5f * sx, 16f * sy)
        path.lineTo(16f * sx, 21.5f * sy)
        path.lineTo(8f * sx, 21.5f * sy)
        path.lineTo(2.5f * sx, 16f * sy)
        path.lineTo(2.5f * sx, 8f * sy)
        path.close()

        // Внутренний контурный квадрат
        path.moveTo(8.5f * sx, 8.5f * sy)
        path.lineTo(15.5f * sx, 8.5f * sy)
        path.lineTo(15.5f * sx, 15.5f * sy)
        path.lineTo(8.5f * sx, 15.5f * sy)
        path.close()

        drawPath(path = path, color = tint, style = stroke)
    }
}

/**
 * 7. ic_qs_proxy: Монументальный векторный самолет-глайдер с орбитальным кольцом.
 * Силуэт: главный значок шторки быстрых настроек Android. Сверхзвуковой дельтовидный глайдер Mirrly,
 * центральный продольный киль и охватывающее эллиптическое кольцо Anycast-орбиты.
 */
@Composable
fun MirrlyQsProxyIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f

        path.reset()
        // Корпус дельтовидного кибер-глайдера
        path.moveTo(12f * sx, 2.5f * sy)
        path.lineTo(14f * sx, 7.5f * sy)
        path.lineTo(20.5f * sx, 17.5f * sy)
        path.lineTo(13.5f * sx, 15f * sy)
        path.lineTo(12f * sx, 14f * sy)
        path.lineTo(10.5f * sx, 15f * sy)
        path.lineTo(3.5f * sx, 17.5f * sy)
        path.lineTo(10f * sx, 7.5f * sy)
        path.close()

        // Продольный дорсальный киль
        path.moveTo(12f * sx, 2.5f * sy)
        path.lineTo(12f * sx, 14f * sy)

        // Опоясывающее орбитальное квантовое кольцо
        path.moveTo(2.5f * sx, 11.5f * sy)
        path.cubicTo(
            2.5f * sx, 9.3f * sy,
            6.7f * sx, 7.5f * sy,
            12f * sx, 7.5f * sy
        )
        path.cubicTo(
            17.3f * sx, 7.5f * sy,
            21.5f * sx, 9.3f * sy,
            21.5f * sx, 11.5f * sy
        )
        path.cubicTo(
            21.5f * sx, 13.7f * sy,
            17.3f * sx, 15.5f * sy,
            12f * sx, 15.5f * sy
        )
        path.cubicTo(
            6.7f * sx, 15.5f * sy,
            2.5f * sx, 13.7f * sy,
            2.5f * sx, 11.5f * sy
        )
        path.close()

        drawPath(path = path, color = tint, style = stroke)
    }
}

/**
 * Тип системной иконки уведомлений Mirrly для универсального компонента.
 */
enum class MirrlyNotificationIconType {
    PROXY_CONNECTED,
    PROXY_ERROR,
    PROXY_WARNING,
    TIMER,
    UPDATE,
    NOTIF_STOP,
    QS_PROXY
}

/**
 * Единый универсальный фасад отрисовки любой иконки из набора системных уведомлений.
 */
@Composable
fun MirrlyNotificationIcon(
    type: MirrlyNotificationIconType,
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    when (type) {
        MirrlyNotificationIconType.PROXY_CONNECTED -> MirrlyStatProxyConnectedIcon(modifier, tint, strokeWidth)
        MirrlyNotificationIconType.PROXY_ERROR -> MirrlyStatProxyErrorIcon(modifier, tint, strokeWidth)
        MirrlyNotificationIconType.PROXY_WARNING -> MirrlyStatProxyWarningIcon(modifier, tint, strokeWidth)
        MirrlyNotificationIconType.TIMER -> MirrlyStatTimerIcon(modifier, tint, strokeWidth)
        MirrlyNotificationIconType.UPDATE -> MirrlyStatUpdateIcon(modifier, tint, strokeWidth)
        MirrlyNotificationIconType.NOTIF_STOP -> MirrlyNotifStopIcon(modifier, tint, strokeWidth)
        MirrlyNotificationIconType.QS_PROXY -> MirrlyQsProxyIcon(modifier, tint, strokeWidth)
    }
}
