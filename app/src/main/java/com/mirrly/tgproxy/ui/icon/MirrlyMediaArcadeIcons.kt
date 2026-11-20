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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.ui.theme.AmoledBackground
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.AmoledSurface
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite

/**
 * Procedural Vector Icons for Jetpack Compose (/mirrly-icons standard).
 * Category: "Интерактив, мультимедиа, тактильность и аркада" (8 components).
 *
 * Standard rules:
 * 1. Outline-Only: 1.5dp stroke, rounded caps and joins (StrokeCap.Round, StrokeJoin.Round).
 * 2. Zero-Allocation (120 FPS): Cached Path instances and pre-computed Stroke in remember block.
 * 3. Exclusion of standard template Android icons: Bespoke cyberpunk & telemetry procedural geometry.
 */

// -------------------------------------------------------------------------------------------------
// 1. ic_wallpaper
// Рама видоискателя с горным изломом векторного ландшафта и парящим светилом.
// -------------------------------------------------------------------------------------------------

@Composable
fun MirrlyIconWallpaper(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    contentDescription: String? = "Обои и видоискатель"
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) {
        with(density) { strokeWidth.toPx() }
    }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    val semanticsModifier = remember(contentDescription) {
        if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    }

    Canvas(modifier = modifier.then(semanticsModifier)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val sx = w / 24f
        val sy = h / 24f

        path.reset()

        // --- Рама видоискателя (HUD Viewfinder Corner Brackets) ---
        // Верхний левый угол
        path.moveTo(2.5f * sx, 7.5f * sy)
        path.lineTo(2.5f * sx, 3.5f * sy)
        path.lineTo(7.5f * sx, 3.5f * sy)

        // Верхний правый угол
        path.moveTo(16.5f * sx, 3.5f * sy)
        path.lineTo(21.5f * sx, 3.5f * sy)
        path.lineTo(21.5f * sx, 7.5f * sy)

        // Нижний левый угол
        path.moveTo(2.5f * sx, 16.5f * sy)
        path.lineTo(2.5f * sx, 20.5f * sy)
        path.lineTo(7.5f * sx, 20.5f * sy)

        // Нижний правый угол
        path.moveTo(16.5f * sx, 20.5f * sy)
        path.lineTo(21.5f * sx, 20.5f * sy)
        path.lineTo(21.5f * sx, 16.5f * sy)

        // Оптические риски видоискателя (Центральные калибровочные метки)
        path.moveTo(12.0f * sx, 2.0f * sy)
        path.lineTo(12.0f * sx, 3.8f * sy)
        path.moveTo(12.0f * sx, 20.2f * sy)
        path.lineTo(12.0f * sx, 22.0f * sy)
        path.moveTo(2.0f * sx, 12.0f * sy)
        path.lineTo(3.8f * sx, 12.0f * sy)
        path.moveTo(20.2f * sx, 12.0f * sy)
        path.lineTo(22.0f * sx, 12.0f * sy)

        // --- Горный излом векторного ландшафта (Faceted Mountain Ridges) ---
        // Базовая линия горизонта в нижней части видоискателя
        path.moveTo(3.5f * sx, 18.5f * sy)
        path.lineTo(20.5f * sx, 18.5f * sy)

        // Первый пик (левый)
        path.moveTo(3.5f * sx, 18.5f * sy)
        path.lineTo(8.5f * sx, 11.0f * sy)
        path.lineTo(13.0f * sx, 15.5f * sy)

        // Второй пик (высокий правый акцентный)
        path.lineTo(17.5f * sx, 9.5f * sy)
        path.lineTo(20.5f * sx, 15.0f * sy)

        // Векторные фасетные грани горного излома (3D Origami Low-Poly Wireframe)
        path.moveTo(8.5f * sx, 11.0f * sy)
        path.lineTo(10.5f * sx, 18.5f * sy)

        path.moveTo(17.5f * sx, 9.5f * sy)
        path.lineTo(15.5f * sx, 18.5f * sy)

        // Лучи/корона светила
        path.moveTo(16.0f * sx, 3.5f * sy)
        path.lineTo(16.0f * sx, 4.3f * sy)
        path.moveTo(18.5f * sx, 6.0f * sy)
        path.lineTo(17.7f * sx, 6.0f * sy)
        path.moveTo(16.0f * sx, 8.5f * sy)
        path.lineTo(16.0f * sx, 7.7f * sy)
        path.moveTo(13.5f * sx, 6.0f * sy)
        path.lineTo(14.3f * sx, 6.0f * sy)

        drawPath(path = path, color = color, style = stroke)

        // Парящее светило (Outline диск)
        drawCircle(
            color = color,
            radius = 1.7f * sx,
            center = Offset(16.0f * sx, 6.0f * sy),
            style = stroke
        )
    }
}

// -------------------------------------------------------------------------------------------------
// 2. ic_traffic_pulse
// Осциллограмма кардиограммы/квантового импульса с двумя горизонтальными направляющими шинами.
// -------------------------------------------------------------------------------------------------

@Composable
fun MirrlyIconTrafficPulse(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    contentDescription: String? = "Пульс трафика и квантовая осциллограмма"
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) {
        with(density) { strokeWidth.toPx() }
    }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    val semanticsModifier = remember(contentDescription) {
        if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    }

    Canvas(modifier = modifier.then(semanticsModifier)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val sx = w / 24f
        val sy = h / 24f

        path.reset()

        // --- Две горизонтальные направляющие шины (Dual Quantum Rail Buses) ---
        // Верхняя шина (максимальный порог квантового импульса)
        path.moveTo(2.5f * sx, 5.5f * sy)
        path.lineTo(21.5f * sx, 5.5f * sy)
        // Терминальные упоры верхней шины
        path.moveTo(2.5f * sx, 4.3f * sy)
        path.lineTo(2.5f * sx, 6.7f * sy)
        path.moveTo(21.5f * sx, 4.3f * sy)
        path.lineTo(21.5f * sx, 6.7f * sy)

        // Нижняя шина (базовый порог отрицательного импульса)
        path.moveTo(2.5f * sx, 18.5f * sy)
        path.lineTo(21.5f * sx, 18.5f * sy)
        // Терминальные упоры нижней шины
        path.moveTo(2.5f * sx, 17.3f * sy)
        path.lineTo(2.5f * sx, 19.7f * sy)
        path.moveTo(21.5f * sx, 17.3f * sy)
        path.lineTo(21.5f * sx, 19.7f * sy)

        // --- Осциллограмма кардиограммы / квантового импульса ---
        path.moveTo(2.5f * sx, 12.0f * sy)
        path.lineTo(6.5f * sx, 12.0f * sy) // Базовая изоэлектрическая линия
        path.lineTo(8.5f * sx, 13.5f * sy) // Предвозбуждение (Q-зубец)
        path.lineTo(11.0f * sx, 5.5f * sy) // R-пик (касается верхней шины)
        path.lineTo(13.5f * sx, 18.5f * sy) // S-спад (касается нижней шины)
        path.lineTo(15.5f * sx, 9.5f * sy) // Т-волна восстановления
        path.lineTo(17.5f * sx, 12.0f * sy) // Выход в равновесие
        path.lineTo(21.5f * sx, 12.0f * sy) // Хвостовая шина

        drawPath(path = path, color = color, style = stroke)

        // Квантовые фотонные узлы на экстремумах
        drawCircle(
            color = color,
            radius = strokeWidthPx * 1.05f,
            center = Offset(11.0f * sx, 5.5f * sy)
        )
        drawCircle(
            color = color,
            radius = strokeWidthPx * 1.05f,
            center = Offset(13.5f * sx, 18.5f * sy)
        )
    }
}

// -------------------------------------------------------------------------------------------------
// 3. ic_volume_up
// Диффузор динамика с двумя концентрическими сферическими акустическими фронтами.
// -------------------------------------------------------------------------------------------------

@Composable
fun MirrlyIconVolumeUp(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    contentDescription: String? = "Звук включен"
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) {
        with(density) { strokeWidth.toPx() }
    }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    val semanticsModifier = remember(contentDescription) {
        if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    }

    Canvas(modifier = modifier.then(semanticsModifier)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val sx = w / 24f
        val sy = h / 24f

        path.reset()

        // --- Диффузор динамика (Кибернетический звуковой излучатель) ---
        // Магнитный блок драйвера
        path.moveTo(2.5f * sx, 9.5f * sy)
        path.lineTo(5.5f * sx, 9.5f * sy)
        path.lineTo(5.5f * sx, 14.5f * sy)
        path.lineTo(2.5f * sx, 14.5f * sy)
        path.close()

        // Рупорный раструб диффузора
        path.moveTo(5.5f * sx, 9.5f * sy)
        path.lineTo(11.0f * sx, 5.2f * sy)
        path.lineTo(11.0f * sx, 18.8f * sy)
        path.lineTo(5.5f * sx, 14.5f * sy)
        path.close()

        // Внутреннее ребро апертуры раструба
        path.moveTo(11.0f * sx, 5.2f * sy)
        path.quadraticBezierTo(10.2f * sx, 12.0f * sy, 11.0f * sx, 18.8f * sy)

        // --- Два концентрических сферических акустических фронта ---
        // Первый фронт (ближний акустический радиус)
        path.moveTo(13.8f * sx, 8.2f * sy)
        path.quadraticBezierTo(16.0f * sx, 12.0f * sy, 13.8f * sx, 15.8f * sy)

        // Второй фронт (дальний акустический радиус)
        path.moveTo(17.5f * sx, 5.5f * sy)
        path.quadraticBezierTo(21.2f * sx, 12.0f * sy, 17.5f * sx, 18.5f * sy)

        drawPath(path = path, color = color, style = stroke)
    }
}

// -------------------------------------------------------------------------------------------------
// 4. ic_volume_off
// Диффузор динамика, перечеркнутый изящным контурным разделителем под углом 45 градусов.
// -------------------------------------------------------------------------------------------------

@Composable
fun MirrlyIconVolumeOff(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    contentDescription: String? = "Звук выключен"
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) {
        with(density) { strokeWidth.toPx() }
    }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    val semanticsModifier = remember(contentDescription) {
        if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    }

    Canvas(modifier = modifier.then(semanticsModifier)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val sx = w / 24f
        val sy = h / 24f

        path.reset()

        // --- Диффузор динамика ---
        // Магнитный блок драйвера
        path.moveTo(2.5f * sx, 9.5f * sy)
        path.lineTo(5.5f * sx, 9.5f * sy)
        path.lineTo(5.5f * sx, 14.5f * sy)
        path.lineTo(2.5f * sx, 14.5f * sy)
        path.close()

        // Рупорный раструб диффузора
        path.moveTo(5.5f * sx, 9.5f * sy)
        path.lineTo(11.0f * sx, 5.2f * sy)
        path.lineTo(11.0f * sx, 18.8f * sy)
        path.lineTo(5.5f * sx, 14.5f * sy)
        path.close()

        // Внутреннее ребро апертуры
        path.moveTo(11.0f * sx, 5.2f * sy)
        path.quadraticBezierTo(10.2f * sx, 12.0f * sy, 11.0f * sx, 18.8f * sy)

        // Изящный контурный разделитель под углом строго 45 градусов
        path.moveTo(3.5f * sx, 3.5f * sy)
        path.lineTo(20.5f * sx, 20.5f * sy)

        drawPath(path = path, color = color, style = stroke)
    }
}

// -------------------------------------------------------------------------------------------------
// 5. ic_vibrate
// Вертикальный контур смартфона, окруженный с двух сторон тройными скобками упругих волн отдачи.
// -------------------------------------------------------------------------------------------------

@Composable
fun MirrlyIconVibrate(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    contentDescription: String? = "Вибрация и тактильный отклик"
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) {
        with(density) { strokeWidth.toPx() }
    }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    val semanticsModifier = remember(contentDescription) {
        if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    }

    Canvas(modifier = modifier.then(semanticsModifier)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val sx = w / 24f
        val sy = h / 24f

        path.reset()

        // --- Вертикальный контур смартфона ---
        // Корпус: x от 8.5 до 15.5, y от 4.5 до 19.5, радиус скругления углов 1.8
        path.moveTo(10.3f * sx, 4.5f * sy)
        path.lineTo(13.7f * sx, 4.5f * sy)
        path.quadraticBezierTo(15.5f * sx, 4.5f * sy, 15.5f * sx, 6.3f * sy)
        path.lineTo(15.5f * sx, 17.7f * sy)
        path.quadraticBezierTo(15.5f * sx, 19.5f * sy, 13.7f * sx, 19.5f * sy)
        path.lineTo(10.3f * sx, 19.5f * sy)
        path.quadraticBezierTo(8.5f * sx, 19.5f * sy, 8.5f * sx, 17.7f * sy)
        path.lineTo(8.5f * sx, 6.3f * sy)
        path.quadraticBezierTo(8.5f * sx, 4.5f * sy, 10.3f * sx, 4.5f * sy)
        path.close()

        // Разговорный динамик в верхней части
        path.moveTo(11.0f * sx, 6.5f * sy)
        path.lineTo(13.0f * sx, 6.5f * sy)

        // Навигационный маркер жестов в нижней части
        path.moveTo(11.0f * sx, 17.5f * sy)
        path.lineTo(13.0f * sx, 17.5f * sy)

        // --- Тройные скобки упругих волн отдачи (Слева) ---
        // Внутренняя скобка 1
        path.moveTo(6.5f * sx, 9.5f * sy)
        path.lineTo(5.2f * sx, 12.0f * sy)
        path.lineTo(6.5f * sx, 14.5f * sy)

        // Средняя скобка 2
        path.moveTo(4.5f * sx, 8.2f * sy)
        path.lineTo(3.0f * sx, 12.0f * sy)
        path.lineTo(4.5f * sx, 15.8f * sy)

        // Внешняя скобка 3
        path.moveTo(2.5f * sx, 7.0f * sy)
        path.lineTo(0.9f * sx, 12.0f * sy)
        path.lineTo(2.5f * sx, 17.0f * sy)

        // --- Тройные скобки упругих волн отдачи (Справа) ---
        // Внутренняя скобка 1
        path.moveTo(17.5f * sx, 9.5f * sy)
        path.lineTo(18.8f * sx, 12.0f * sy)
        path.lineTo(17.5f * sx, 14.5f * sy)

        // Средняя скобка 2
        path.moveTo(19.5f * sx, 8.2f * sy)
        path.lineTo(21.0f * sx, 12.0f * sy)
        path.lineTo(19.5f * sx, 15.8f * sy)

        // Внешняя скобка 3
        path.moveTo(21.5f * sx, 7.0f * sy)
        path.lineTo(23.1f * sx, 12.0f * sy)
        path.lineTo(21.5f * sx, 17.0f * sy)

        drawPath(path = path, color = color, style = stroke)
    }
}

// -------------------------------------------------------------------------------------------------
// 6. ic_vibrate_off
// Контур смартфона с диагональным штрихом покоя.
// -------------------------------------------------------------------------------------------------

@Composable
fun MirrlyIconVibrateOff(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    contentDescription: String? = "Вибрация отключена"
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) {
        with(density) { strokeWidth.toPx() }
    }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    val semanticsModifier = remember(contentDescription) {
        if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    }

    Canvas(modifier = modifier.then(semanticsModifier)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val sx = w / 24f
        val sy = h / 24f

        path.reset()

        // --- Контур смартфона ---
        path.moveTo(10.3f * sx, 4.5f * sy)
        path.lineTo(13.7f * sx, 4.5f * sy)
        path.quadraticBezierTo(15.5f * sx, 4.5f * sy, 15.5f * sx, 6.3f * sy)
        path.lineTo(15.5f * sx, 17.7f * sy)
        path.quadraticBezierTo(15.5f * sx, 19.5f * sy, 13.7f * sx, 19.5f * sy)
        path.lineTo(10.3f * sx, 19.5f * sy)
        path.quadraticBezierTo(8.5f * sx, 19.5f * sy, 8.5f * sx, 17.7f * sy)
        path.lineTo(8.5f * sx, 6.3f * sy)
        path.quadraticBezierTo(8.5f * sx, 4.5f * sy, 10.3f * sx, 4.5f * sy)
        path.close()

        // Разговорный динамик
        path.moveTo(11.0f * sx, 6.5f * sy)
        path.lineTo(13.0f * sx, 6.5f * sy)

        // Навигационный маркер жестов
        path.moveTo(11.0f * sx, 17.5f * sy)
        path.lineTo(13.0f * sx, 17.5f * sy)

        // Диагональный штрих покоя под углом 45 градусов
        path.moveTo(3.5f * sx, 3.5f * sy)
        path.lineTo(20.5f * sx, 20.5f * sy)

        drawPath(path = path, color = color, style = stroke)
    }
}

// -------------------------------------------------------------------------------------------------
// 7. ic_gamepad
// Стилизованный кибернетический джойстик с фасетными D-pad кнопками и контурными курками.
// -------------------------------------------------------------------------------------------------

@Composable
fun MirrlyIconGamepad(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    contentDescription: String? = "Игровой контроллер и аркада"
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) {
        with(density) { strokeWidth.toPx() }
    }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    val semanticsModifier = remember(contentDescription) {
        if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    }

    Canvas(modifier = modifier.then(semanticsModifier)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val sx = w / 24f
        val sy = h / 24f

        path.reset()

        // --- Стилизованный корпус кибернетического джойстика ---
        // Верхний мост корпуса
        path.moveTo(8.0f * sx, 9.0f * sy)
        path.lineTo(16.0f * sx, 9.0f * sy)

        // Правое плечо
        path.lineTo(19.5f * sx, 9.8f * sy)
        path.quadraticBezierTo(21.8f * sx, 11.0f * sy, 21.8f * sx, 13.5f * sy)

        // Правый эргономичный рог рукояти (внешний и нижний контур)
        path.lineTo(20.2f * sx, 18.0f * sy)
        path.quadraticBezierTo(18.8f * sx, 19.5f * sy, 17.2f * sx, 18.2f * sy)

        // Внутренний скат правой рукояти
        path.lineTo(15.2f * sx, 15.0f * sy)

        // Центральная нижняя арка (перемычка хвата)
        path.quadraticBezierTo(12.0f * sx, 16.5f * sy, 8.8f * sx, 15.0f * sy)

        // Внутренний скат левой рукояти
        path.lineTo(6.8f * sx, 18.2f * sy)

        // Левый эргономичный рог рукояти
        path.quadraticBezierTo(5.2f * sx, 19.5f * sy, 3.8f * sx, 18.0f * sy)
        path.lineTo(2.2f * sx, 13.5f * sy)

        // Левое плечо
        path.quadraticBezierTo(2.2f * sx, 11.0f * sy, 4.5f * sx, 9.8f * sy)
        path.lineTo(8.0f * sx, 9.0f * sy)
        path.close()

        // --- Контурные курки (Shoulder Bumpers / Triggers L1/R1) ---
        // Левый курок
        path.moveTo(4.8f * sx, 6.8f * sy)
        path.lineTo(8.5f * sx, 6.8f * sy)

        // Правый курок
        path.moveTo(15.5f * sx, 6.8f * sy)
        path.lineTo(19.2f * sx, 6.8f * sy)

        // --- Фасетные D-pad кнопки (Крестовина слева) ---
        // Горизонтальная направляющая
        path.moveTo(5.2f * sx, 13.2f * sy)
        path.lineTo(9.2f * sx, 13.2f * sy)
        // Вертикальная направляющая
        path.moveTo(7.2f * sx, 11.2f * sy)
        path.lineTo(7.2f * sx, 15.2f * sy)
        // Фасетные контурные засечки на концах D-pad
        path.moveTo(5.2f * sx, 12.4f * sy)
        path.lineTo(5.2f * sx, 14.0f * sy)
        path.moveTo(9.2f * sx, 12.4f * sy)
        path.lineTo(9.2f * sx, 14.0f * sy)
        path.moveTo(6.4f * sx, 11.2f * sy)
        path.lineTo(8.0f * sx, 11.2f * sy)
        path.moveTo(6.4f * sx, 15.2f * sy)
        path.lineTo(8.0f * sx, 15.2f * sy)

        // Центральные сервисные индикаторы телеметрии (Select / Start)
        path.moveTo(10.8f * sx, 11.8f * sy)
        path.lineTo(11.6f * sx, 11.8f * sy)
        path.moveTo(12.4f * sx, 11.8f * sy)
        path.lineTo(13.2f * sx, 11.8f * sy)

        drawPath(path = path, color = color, style = stroke)

        // --- Кнопки действий (Ромбовидный блок кнопок справа) ---
        val btnRadius = strokeWidthPx * 0.75f
        // Верхняя кнопка (X/Y)
        drawCircle(color = color, radius = btnRadius, center = Offset(16.8f * sx, 11.4f * sy))
        // Нижняя кнопка (A/B)
        drawCircle(color = color, radius = btnRadius, center = Offset(16.8f * sx, 15.0f * sy))
        // Левая кнопка
        drawCircle(color = color, radius = btnRadius, center = Offset(15.0f * sx, 13.2f * sy))
        // Правая кнопка
        drawCircle(color = color, radius = btnRadius, center = Offset(18.6f * sx, 13.2f * sy))
    }
}

// -------------------------------------------------------------------------------------------------
// 8. ic_crown
// 5-зубчатая титановая корона с акцентным центральным зубцом и контурными полигональными гранями.
// -------------------------------------------------------------------------------------------------

@Composable
fun MirrlyIconCrown(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    contentDescription: String? = "Титановая корона лидера"
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) {
        with(density) { strokeWidth.toPx() }
    }
    val stroke = remember(strokeWidthPx) {
        Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }
    val path = remember { Path() }

    val semanticsModifier = remember(contentDescription) {
        if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    }

    Canvas(modifier = modifier.then(semanticsModifier)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val sx = w / 24f
        val sy = h / 24f

        path.reset()

        // --- Внешний контур 5-зубчатой титановой короны ---
        // Начало от левого нижнего угла диадемы
        path.moveTo(4.2f * sx, 17.5f * sy)
        // Зубец 1 (крайний левый)
        path.lineTo(2.8f * sx, 7.8f * sy)
        // Впадина 1
        path.lineTo(5.5f * sx, 11.8f * sy)
        // Зубец 2 (промежуточный левый)
        path.lineTo(7.8f * sx, 6.8f * sy)
        // Впадина 2
        path.lineTo(10.2f * sx, 10.8f * sy)
        // Зубец 3 (АКЦЕНТНЫЙ ВЫСОКИЙ ЦЕНТРАЛЬНЫЙ ЗУБЕЦ)
        path.lineTo(12.0f * sx, 3.5f * sy)
        // Впадина 3
        path.lineTo(13.8f * sx, 10.8f * sy)
        // Зубец 4 (промежуточный правый)
        path.lineTo(16.2f * sx, 6.8f * sy)
        // Впадина 4
        path.lineTo(18.5f * sx, 11.8f * sy)
        // Зубец 5 (крайний правый)
        path.lineTo(21.2f * sx, 7.8f * sy)
        // Опуск к правому нижнему краю диадемы
        path.lineTo(19.8f * sx, 17.5f * sy)
        path.close()

        // --- Титановое основание (Диадема) ---
        // Нижняя базовая кромка диадемы
        path.moveTo(4.8f * sx, 20.5f * sy)
        path.lineTo(19.2f * sx, 20.5f * sy)
        // Левый фасетный скос диадемы
        path.moveTo(4.2f * sx, 17.5f * sy)
        path.lineTo(4.8f * sx, 20.5f * sy)
        // Правый фасетный скос диадемы
        path.moveTo(19.8f * sx, 17.5f * sy)
        path.lineTo(19.2f * sx, 20.5f * sy)

        // --- Контурные полигональные грани (3D Origami Titanium Facets) ---
        // Центральное титановое ребро жесткости от вершины к основанию
        path.moveTo(12.0f * sx, 3.5f * sy)
        path.lineTo(12.0f * sx, 17.5f * sy)

        // Фасетные ребра от внутренних впадин к центру основания
        path.moveTo(10.2f * sx, 10.8f * sy)
        path.lineTo(12.0f * sx, 17.5f * sy)

        path.moveTo(13.8f * sx, 10.8f * sy)
        path.lineTo(12.0f * sx, 17.5f * sy)

        // Вертикальные полигональные ребра промежуточных зубцов
        path.moveTo(7.8f * sx, 6.8f * sy)
        path.lineTo(7.8f * sx, 17.5f * sy)

        path.moveTo(16.2f * sx, 6.8f * sy)
        path.lineTo(16.2f * sx, 17.5f * sy)

        // Угловые фасеты от внешних впадин к углам диадемы
        path.moveTo(5.5f * sx, 11.8f * sy)
        path.lineTo(4.2f * sx, 17.5f * sy)

        path.moveTo(18.5f * sx, 11.8f * sy)
        path.lineTo(19.8f * sx, 17.5f * sy)

        drawPath(path = path, color = color, style = stroke)

        // Центральный титановый маркер в основании диадемы
        drawCircle(
            color = color,
            radius = strokeWidthPx * 0.95f,
            center = Offset(12.0f * sx, 19.0f * sy)
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Диспетчер и перечисление всех 8 иконок категории
// -------------------------------------------------------------------------------------------------

enum class MirrlyMediaArcadeIconType(val iconName: String) {
    WALLPAPER("ic_wallpaper"),
    TRAFFIC_PULSE("ic_traffic_pulse"),
    VOLUME_UP("ic_volume_up"),
    VOLUME_OFF("ic_volume_off"),
    VIBRATE("ic_vibrate"),
    VIBRATE_OFF("ic_vibrate_off"),
    GAMEPAD("ic_gamepad"),
    CROWN("ic_crown")
}

@Composable
fun MirrlyMediaArcadeIcon(
    type: MirrlyMediaArcadeIconType,
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    contentDescription: String? = type.iconName
) {
    when (type) {
        MirrlyMediaArcadeIconType.WALLPAPER -> MirrlyIconWallpaper(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
            contentDescription = contentDescription
        )
        MirrlyMediaArcadeIconType.TRAFFIC_PULSE -> MirrlyIconTrafficPulse(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
            contentDescription = contentDescription
        )
        MirrlyMediaArcadeIconType.VOLUME_UP -> MirrlyIconVolumeUp(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
            contentDescription = contentDescription
        )
        MirrlyMediaArcadeIconType.VOLUME_OFF -> MirrlyIconVolumeOff(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
            contentDescription = contentDescription
        )
        MirrlyMediaArcadeIconType.VIBRATE -> MirrlyIconVibrate(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
            contentDescription = contentDescription
        )
        MirrlyMediaArcadeIconType.VIBRATE_OFF -> MirrlyIconVibrateOff(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
            contentDescription = contentDescription
        )
        MirrlyMediaArcadeIconType.GAMEPAD -> MirrlyIconGamepad(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
            contentDescription = contentDescription
        )
        MirrlyMediaArcadeIconType.CROWN -> MirrlyIconCrown(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
            contentDescription = contentDescription
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Интерактивное превью всех 8 компонентов
// -------------------------------------------------------------------------------------------------

@Composable
fun MirrlyMediaArcadeIconsPreview() {
    val accentColor = Color(0xFF00F5D4) // Кибер-циан Mirrly

    Column(
        modifier = Modifier
            .background(AmoledBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "MIRRLY-ICONS: МУЛЬТИМЕДИА, ТАКТИЛЬНОСТЬ И АРКАДА",
            color = TextWhite,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        val icons = listOf(
            MirrlyMediaArcadeIconType.WALLPAPER to "ic_wallpaper (Видоискатель & Пейзаж)",
            MirrlyMediaArcadeIconType.TRAFFIC_PULSE to "ic_traffic_pulse (Квантовая осциллограмма)",
            MirrlyMediaArcadeIconType.VOLUME_UP to "ic_volume_up (Диффузор & Акустические фронты)",
            MirrlyMediaArcadeIconType.VOLUME_OFF to "ic_volume_off (Диффузор & Разделитель 45°)",
            MirrlyMediaArcadeIconType.VIBRATE to "ic_vibrate (Смартфон & Тройные волны отдачи)",
            MirrlyMediaArcadeIconType.VIBRATE_OFF to "ic_vibrate_off (Смартфон & Штрих покоя)",
            MirrlyMediaArcadeIconType.GAMEPAD to "ic_gamepad (Кибер-геймпад & D-pad & Курки)",
            MirrlyMediaArcadeIconType.CROWN to "ic_crown (5-зубчатая титановая корона)"
        )

        icons.forEach { (type, description) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AmoledSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color.Black, RoundedCornerShape(6.dp))
                        .border(1.dp, AmoledBorder.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    MirrlyMediaArcadeIcon(
                        type = type,
                        modifier = Modifier.size(24.dp),
                        color = accentColor,
                        strokeWidth = 1.5.dp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = type.iconName,
                        color = accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 9. ic_video
// Контурный видео-фрейм с треугольником воспроизведения
// -------------------------------------------------------------------------------------------------

@Composable
fun MirrlyIconVideo(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val sx = w / 24f
        val sy = h / 24f
        path.reset()
        path.addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = 3f * sx,
                top = 4f * sy,
                right = 21f * sx,
                bottom = 20f * sy,
                radiusX = 2.5f * sx,
                radiusY = 2.5f * sy
            )
        )
        path.moveTo(10f * sx, 8.5f * sy)
        path.lineTo(16f * sx, 12f * sy)
        path.lineTo(10f * sx, 15.5f * sy)
        path.close()

        drawPath(path = path, color = color, style = stroke)
    }
}

@Composable
fun MirrlyVideoIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) = MirrlyIconVideo(modifier = modifier, color = tint, strokeWidth = strokeWidth)

// -------------------------------------------------------------------------------------------------
// 10. ic_app_icon_picker
// Контурный фрейм иконки приложения со стилизованным дельта-самолётиком внутри
// -------------------------------------------------------------------------------------------------

@Composable
fun MirrlyIconAppIconPicker(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val sx = w / 24f
        val sy = h / 24f

        path.reset()
        path.addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = 3.5f * sx,
                top = 3.5f * sy,
                right = 20.5f * sx,
                bottom = 20.5f * sy,
                radiusX = 4.5f * sx,
                radiusY = 4.5f * sy
            )
        )
        // Оптически сбалансированный дельта-самолётик (смещение +0.4 к острию)
        path.moveTo(16.6f * sx, 8.2f * sy)
        path.lineTo(7.8f * sx, 11.7f * sy)
        path.lineTo(11.3f * sx, 13.1f * sy)
        path.lineTo(12.7f * sx, 16.4f * sy)
        path.close()
        path.moveTo(11.3f * sx, 13.1f * sy)
        path.lineTo(16.6f * sx, 8.2f * sy)

        drawPath(path = path, color = color, style = stroke)
    }
}

@Composable
fun MirrlyAppIconPickerIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) = MirrlyIconAppIconPicker(modifier = modifier, color = tint, strokeWidth = strokeWidth)

