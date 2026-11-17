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

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Процедурные векторные иконки MirrlyUI для Jetpack Compose по стандарту /mirrly-icons.
 * Категория: «ИИ-ассистент, журналы событий и документация» (9 компонентов).
 *
 * ТРЕБОВАНИЯ СТАНДАРТА MIRRLY-ICONS:
 * 1. Каркасная философия (Outline-Only): базовый штрих 1.5dp, StrokeCap.Round, StrokeJoin.Round.
 *    Полный отказ от глухих непрозрачных фоновых заливок — сквозь каркас свободно
 *    просвечивают динамические обои и частицы CyberEnergyCanvas.
 * 2. Zero-Allocation (120 FPS Battery Guard): Path кэшируется через remember { Path() } и
 *    сбрасывается через .reset() перед пересчетом. Никаких аллокаций внутри DrawScope.
 * 3. Четкое разграничение похожих смыслов и исключение коллизий:
 *    - ic_ai_chat vs ic_chat_plus vs ic_ai_thought:
 *      * ic_ai_chat: диалоговый баллон со скругленным хвостом, внутри которого мерцает
 *        контурная 4-лучевая звезда искры ИИ (✦).
 *      * ic_chat_plus: контурный диалоговый баллон, в правый нижний угол которого интегрирован
 *        значок плюса (+).
 *      * ic_ai_thought: нейронное облако/узел связей (3 точки в форме трилистника, соединенные
 *        тонкими дугами синапсов).
 *    - ic_logs vs ic_history:
 *      * ic_logs: шелл-терминал — прямоугольный экран с приглашением командной строки `>`
 *        и нижним штрихом курсора `_`.
 *      * ic_history: спиральная лента времени — циферблат с круговой стрелкой обратной
 *        перемотки событий.
 *    - ic_info_circle vs ic_question_mark:
 *      * ic_info_circle: окружность с тонкой вертикальной сенсорной планкой и парящей точкой над ней.
 *      * ic_question_mark: спиралевидный изгиб вопроса с оптически сбалансированной нижней
 *        точкой (без внешней окружности).
 *    - ic_bug: микросхема в форме жука с 6 угловыми лапками-выводами и парой усиков-антенн.
 *    - ic_license: официальный контурный пергамент/свиток с лентой печати внизу.
 * 4. Динамическая палитра (Zero Color Hardcode): привязка к LocalProtocolColors.current.primary.
 * 5. Lifecycle Guard: автоматическая остановка анимаций при ON_PAUSE/ON_STOP.
 */

@Composable
private fun rememberIconAnimationActive(isAnimated: Boolean): Boolean {
    if (!isAnimated) return false
    val lifecycleOwner = LocalLifecycleOwner.current
    val isAppResumed = remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isAppResumed.value = false
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> isAppResumed.value = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return isAppResumed.value
}

// =============================================================================
// 1. ic_ai_chat — Диалоговый баллон со скругленным хвостом и 4-лучевой искрой ИИ
// =============================================================================

/**
 * Векторная иконка чата с ИИ (ic_ai_chat).
 * Контурный диалоговый баллон со скругленным хвостом, внутри которого мерцает
 * контурная 4-лучевая звезда искры ИИ (✦).
 */
@Composable
fun MirrlyAiChatIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val bubblePath = remember { Path() }
    val sparkPath = remember { Path() }

    val shouldAnimate = rememberIconAnimationActive(isAnimated)
    val sparkScale = if (shouldAnimate) {
        val transition = rememberInfiniteTransition(label = "AiChatSparkTransition")
        transition.animateFloat(
            initialValue = 0.88f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "AiChatSparkScale"
        ).value
    } else {
        1.0f
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val s = minOf(w, h) / 24f
        val ox = (w - 24f * s) * 0.5f
        val oy = (h - 24f * s) * 0.5f

        // ── 1. КОНТУРНЫЙ ДИАЛОГОВЫЙ БАЛЛОН СО СКРУГЛЕННЫМ ХВОСТОМ ──
        bubblePath.reset()
        // Начинаем с левого основания хвоста на нижней грани
        bubblePath.moveTo(ox + 8.5f * s, oy + 18.0f * s)
        bubblePath.lineTo(ox + 7.5f * s, oy + 18.0f * s)
        // Нижний левый угол
        bubblePath.cubicTo(
            ox + 4.2f * s, oy + 18.0f * s,
            ox + 2.5f * s, oy + 16.3f * s,
            ox + 2.5f * s, oy + 13.0f * s
        )
        // Левая вертикальная грань
        bubblePath.lineTo(ox + 2.5f * s, oy + 7.5f * s)
        // Верхний левый угол
        bubblePath.cubicTo(
            ox + 2.5f * s, oy + 4.2f * s,
            ox + 4.2f * s, oy + 2.5f * s,
            ox + 7.5f * s, oy + 2.5f * s
        )
        // Верхняя горизонтальная грань
        bubblePath.lineTo(ox + 16.5f * s, oy + 2.5f * s)
        // Верхний правый угол
        bubblePath.cubicTo(
            ox + 19.8f * s, oy + 2.5f * s,
            ox + 21.5f * s, oy + 4.2f * s,
            ox + 21.5f * s, oy + 7.5f * s
        )
        // Правая вертикальная грань
        bubblePath.lineTo(ox + 21.5f * s, oy + 13.0f * s)
        // Нижний правый угол
        bubblePath.cubicTo(
            ox + 21.5f * s, oy + 16.3f * s,
            ox + 19.8f * s, oy + 18.0f * s,
            ox + 16.5f * s, oy + 18.0f * s
        )
        // Правое основание хвоста
        bubblePath.lineTo(ox + 15.2f * s, oy + 18.0f * s)
        // Скругленный хвост диалогового баллона
        bubblePath.lineTo(ox + 12.8f * s, oy + 20.8f * s)
        bubblePath.cubicTo(
            ox + 12.4f * s, oy + 21.3f * s,
            ox + 11.6f * s, oy + 21.3f * s,
            ox + 11.2f * s, oy + 20.8f * s
        )
        bubblePath.lineTo(ox + 8.5f * s, oy + 18.0f * s)
        bubblePath.close()

        drawPath(path = bubblePath, color = tint, style = stroke)

        // ── 2. КОНТУРНАЯ 4-ЛУЧЕВАЯ ИСКРА ИИ (✦) ──
        val sparkCx = ox + 12.0f * s
        val sparkCy = oy + 10.25f * s
        val rx = 3.3f * s * sparkScale
        val ry = 3.9f * s * sparkScale
        val waistRatio = 0.38f

        sparkPath.reset()
        // Верхний луч
        sparkPath.moveTo(sparkCx, sparkCy - ry)
        // Сплайн к левому лучу через сужение талии
        sparkPath.cubicTo(
            sparkCx, sparkCy - ry * waistRatio,
            sparkCx - rx * waistRatio, sparkCy,
            sparkCx - rx, sparkCy
        )
        // Сплайн к нижнему лучу
        sparkPath.cubicTo(
            sparkCx - rx * waistRatio, sparkCy,
            sparkCx, sparkCy + ry * waistRatio,
            sparkCx, sparkCy + ry
        )
        // Сплайн к правому лучу
        sparkPath.cubicTo(
            sparkCx, sparkCy + ry * waistRatio,
            sparkCx + rx * waistRatio, sparkCy,
            sparkCx + rx, sparkCy
        )
        // Сплайн обратно к верхнему лучу
        sparkPath.cubicTo(
            sparkCx + rx * waistRatio, sparkCy,
            sparkCx, sparkCy - ry * waistRatio,
            sparkCx, sparkCy - ry
        )
        sparkPath.close()

        drawPath(path = sparkPath, color = tint, style = stroke)
    }
}

@Composable
fun MirrlyAiChatIcon(
    color: Color,
    modifier: Modifier = Modifier.size(24.dp),
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyAiChatIcon(modifier = modifier, tint = color, strokeWidth = strokeWidth, isAnimated = isAnimated)

@Composable
fun MirrlyIconAiChat(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyAiChatIcon(modifier = modifier, tint = tint, strokeWidth = strokeWidth, isAnimated = isAnimated)


// =============================================================================
// 2. ic_chat_plus — Контурный баллон с интегрированным плюсом в правом нижнем углу
// =============================================================================

/**
 * Векторная иконка нового чата / добавления диалога (ic_chat_plus).
 * Контурный диалоговый баллон, в правый нижний угол которого интегрирован значок плюса.
 * Хвост смещен в левую зону, создавая четкое семантическое отличие от ic_ai_chat.
 */
@Composable
fun MirrlyChatPlusIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val bubblePath = remember { Path() }
    val plusPath = remember { Path() }

    val shouldAnimate = rememberIconAnimationActive(isAnimated)
    val plusPulse = if (shouldAnimate) {
        val transition = rememberInfiniteTransition(label = "ChatPlusTransition")
        transition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "ChatPlusPulse"
        ).value
    } else {
        1.0f
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val s = minOf(w, h) / 24f
        val ox = (w - 24f * s) * 0.5f
        val oy = (h - 24f * s) * 0.5f

        // ── 1. КОНТУР БАЛЛОНА С ВЫРЕЗОМ ПОД ИНТЕГРИРОВАННЫЙ ПЛЮС ──
        bubblePath.reset()
        // Правый край над зоной плюса
        bubblePath.moveTo(ox + 21.5f * s, oy + 12.0f * s)
        bubblePath.lineTo(ox + 21.5f * s, oy + 7.5f * s)
        // Верхний правый угол
        bubblePath.cubicTo(
            ox + 21.5f * s, oy + 4.2f * s,
            ox + 19.8f * s, oy + 2.5f * s,
            ox + 16.5f * s, oy + 2.5f * s
        )
        // Верхняя грань
        bubblePath.lineTo(ox + 7.5f * s, oy + 2.5f * s)
        // Верхний левый угол
        bubblePath.cubicTo(
            ox + 4.2f * s, oy + 2.5f * s,
            ox + 2.5f * s, oy + 4.2f * s,
            ox + 2.5f * s, oy + 7.5f * s
        )
        // Левая грань
        bubblePath.lineTo(ox + 2.5f * s, oy + 13.0f * s)
        // Нижний левый угол
        bubblePath.cubicTo(
            ox + 2.5f * s, oy + 16.3f * s,
            ox + 4.2f * s, oy + 18.0f * s,
            ox + 7.0f * s, oy + 18.0f * s
        )
        // Левый диалоговый хвост
        bubblePath.lineTo(ox + 4.5f * s, oy + 20.8f * s)
        bubblePath.cubicTo(
            ox + 4.0f * s, oy + 21.3f * s,
            ox + 3.2f * s, oy + 21.0f * s,
            ox + 3.4f * s, oy + 20.2f * s
        )
        bubblePath.lineTo(ox + 4.0f * s, oy + 18.0f * s)
        // Нижняя грань, подходящая к модулю плюса
        bubblePath.lineTo(ox + 12.2f * s, oy + 18.0f * s)

        // Интегрированное скругление огибания плюса в правом нижнем углу
        bubblePath.cubicTo(
            ox + 13.0f * s, oy + 15.0f * s,
            ox + 15.0f * s, oy + 13.0f * s,
            ox + 18.0f * s, oy + 13.0f * s
        )
        bubblePath.cubicTo(
            ox + 19.5f * s, oy + 13.0f * s,
            ox + 20.8f * s, oy + 13.8f * s,
            ox + 21.5f * s, oy + 15.0f * s
        )

        drawPath(path = bubblePath, color = tint, style = stroke)

        // ── 2. ИНТЕГРИРОВАННЫЙ ЗНАЧОК ПЛЮСА (+) ──
        val plusCx = ox + 17.5f * s
        val plusCy = oy + 17.5f * s
        val armLen = 3.3f * s * plusPulse

        plusPath.reset()
        // Горизонтальный штрих плюса
        plusPath.moveTo(plusCx - armLen, plusCy)
        plusPath.lineTo(plusCx + armLen, plusCy)
        // Вертикальный штрих плюса
        plusPath.moveTo(plusCx, plusCy - armLen)
        plusPath.lineTo(plusCx, plusCy + armLen)

        drawPath(path = plusPath, color = tint, style = stroke)

        // Внутренняя строка чата для оптического баланса баллона
        drawLine(
            color = tint.copy(alpha = 0.55f),
            start = Offset(ox + 7.5f * s, oy + 8.5f * s),
            end = Offset(ox + 15.5f * s, oy + 8.5f * s),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun MirrlyChatPlusIcon(
    color: Color,
    modifier: Modifier = Modifier.size(24.dp),
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyChatPlusIcon(modifier = modifier, tint = color, strokeWidth = strokeWidth, isAnimated = isAnimated)

@Composable
fun MirrlyIconChatPlus(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyChatPlusIcon(modifier = modifier, tint = tint, strokeWidth = strokeWidth, isAnimated = isAnimated)


// =============================================================================
// 3. ic_ai_thought — Нейронное облако/узел связей (3 точки в форме трилистника)
// =============================================================================

/**
 * Векторная иконка размышлений/нейросети ИИ (ic_ai_thought).
 * Нейронное облако/узел связей: 3 контурные точки в форме трилистника,
 * соединенные тонкими дугами синапсов и центральным квантовым узлом.
 */
@Composable
fun MirrlyAiThoughtIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val synapsePath = remember { Path() }
    val lobePath = remember { Path() }

    val shouldAnimate = rememberIconAnimationActive(isAnimated)
    val synapsePulse = if (shouldAnimate) {
        val transition = rememberInfiniteTransition(label = "ThoughtTransition")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(2400, easing = LinearEasing)
            ),
            label = "ThoughtPhase"
        ).value
    } else {
        0f
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val s = minOf(w, h) / 24f
        val ox = (w - 24f * s) * 0.5f
        val oy = (h - 24f * s) * 0.5f

        // Координаты трех узлов трилистника
        val n1x = ox + 12.0f * s
        val n1y = oy + 5.5f * s

        val n2x = ox + 6.2f * s
        val n2y = oy + 16.5f * s

        val n3x = ox + 17.8f * s
        val n3y = oy + 16.5f * s

        val nRadius = 1.8f * s
        val hubX = ox + 12.0f * s
        val hubY = oy + 12.8f * s

        // ── 1. ВНЕШНЕЕ НЕЙРОННОЕ ОБЛАКО (КОНТУРНЫЙ ТРИЛИСТНИК) ──
        lobePath.reset()
        // Верхний лепесток
        lobePath.moveTo(ox + 8.2f * s, oy + 7.5f * s)
        lobePath.cubicTo(
            ox + 7.5f * s, oy + 2.8f * s,
            ox + 16.5f * s, oy + 2.8f * s,
            ox + 15.8f * s, oy + 7.5f * s
        )
        // Переход к правому нижнему лепестку
        lobePath.cubicTo(
            ox + 17.2f * s, oy + 9.5f * s,
            ox + 21.8f * s, oy + 12.5f * s,
            ox + 20.8f * s, oy + 17.5f * s
        )
        lobePath.cubicTo(
            ox + 19.8f * s, oy + 21.2f * s,
            ox + 14.5f * s, oy + 21.0f * s,
            ox + 13.5f * s, oy + 17.8f * s
        )
        // Переход к левому нижнему лепестку
        lobePath.cubicTo(
            ox + 12.8f * s, oy + 17.2f * s,
            ox + 11.2f * s, oy + 17.2f * s,
            ox + 10.5f * s, oy + 17.8f * s
        )
        lobePath.cubicTo(
            ox + 9.5f * s, oy + 21.0f * s,
            ox + 4.2f * s, oy + 21.2f * s,
            ox + 3.2f * s, oy + 17.5f * s
        )
        lobePath.cubicTo(
            ox + 2.2f * s, oy + 12.5f * s,
            ox + 6.8f * s, oy + 9.5f * s,
            ox + 8.2f * s, oy + 7.5f * s
        )
        lobePath.close()

        drawPath(path = lobePath, color = tint.copy(alpha = 0.45f), style = stroke)

        // ── 2. ДУГИ СИНАПСОВ МЕЖДУ УЗЛАМИ ──
        synapsePath.reset()
        // Дуга: Верхний узел -> Левый узел
        synapsePath.moveTo(n1x, n1y + nRadius)
        synapsePath.cubicTo(
            ox + 8.5f * s, oy + 8.5f * s,
            ox + 6.8f * s, oy + 12.5f * s,
            n2x, n2y - nRadius
        )

        // Дуга: Верхний узел -> Правый узел
        synapsePath.moveTo(n1x, n1y + nRadius)
        synapsePath.cubicTo(
            ox + 15.5f * s, oy + 8.5f * s,
            ox + 17.2f * s, oy + 12.5f * s,
            n3x, n3y - nRadius
        )

        // Дуга: Левый узел -> Правый узел
        synapsePath.moveTo(n2x + nRadius, n2y)
        synapsePath.quadraticBezierTo(
            ox + 12.0f * s, oy + 14.5f * s,
            n3x - nRadius, n3y
        )

        // Радиальные мостики к центральному квантовому синаптическому хабу
        synapsePath.moveTo(hubX, hubY)
        synapsePath.lineTo(n1x, n1y + nRadius)
        synapsePath.moveTo(hubX, hubY)
        synapsePath.lineTo(n2x + nRadius * 0.7f, n2y - nRadius * 0.7f)
        synapsePath.moveTo(hubX, hubY)
        synapsePath.lineTo(n3x - nRadius * 0.7f, n3y - nRadius * 0.7f)

        drawPath(path = synapsePath, color = tint, style = stroke)

        // ── 3. ТРИ КОНТУРНЫЕ ТОЧКИ УЗЛОВ ТРИЛИСТНИКА ──
        drawCircle(color = tint, radius = nRadius, center = Offset(n1x, n1y), style = stroke)
        drawCircle(color = tint, radius = nRadius, center = Offset(n2x, n2y), style = stroke)
        drawCircle(color = tint, radius = nRadius, center = Offset(n3x, n3y), style = stroke)

        // Центральное микро-ядро синаптического узла
        val corePulse = if (shouldAnimate) 0.65f + 0.35f * sin(synapsePulse) else 0.85f
        drawCircle(
            color = tint.copy(alpha = corePulse),
            radius = 1.0f * s,
            center = Offset(hubX, hubY),
            style = stroke
        )
    }
}

@Composable
fun MirrlyAiThoughtIcon(
    color: Color,
    modifier: Modifier = Modifier.size(24.dp),
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyAiThoughtIcon(modifier = modifier, tint = color, strokeWidth = strokeWidth, isAnimated = isAnimated)

@Composable
fun MirrlyIconAiThought(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyAiThoughtIcon(modifier = modifier, tint = tint, strokeWidth = strokeWidth, isAnimated = isAnimated)


// =============================================================================
// 4. ic_logs — Шелл-терминал с приглашением `>` и курсором `_`
// =============================================================================

/**
 * Векторная иконка логов/терминала (ic_logs).
 * Шелл-терминал: прямоугольный экран с приглашением командной строки `>`
 * и нижним штрихом курсора `_`.
 */
@Composable
fun MirrlyLogsIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val screenPath = remember { Path() }
    val promptPath = remember { Path() }

    val shouldAnimate = rememberIconAnimationActive(isAnimated)
    val cursorAlpha = if (shouldAnimate) {
        val transition = rememberInfiniteTransition(label = "LogsCursorTransition")
        transition.animateFloat(
            initialValue = 0.15f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "LogsCursorAlpha"
        ).value
    } else {
        1.0f
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val s = minOf(w, h) / 24f
        val ox = (w - 24f * s) * 0.5f
        val oy = (h - 24f * s) * 0.5f

        // ── 1. ПРЯМОУГОЛЬНЫЙ ЭКРАН ШЕЛЛ-ТЕРМИНАЛА ──
        screenPath.reset()
        val r = 2.8f * s
        screenPath.addRoundRect(
            RoundRect(
                left = ox + 2.5f * s,
                top = oy + 3.8f * s,
                right = ox + 21.5f * s,
                bottom = oy + 20.2f * s,
                cornerRadius = CornerRadius(r, r)
            )
        )
        drawPath(path = screenPath, color = tint, style = stroke)

        // Верхняя разделительная полоса заголовка консоли
        drawLine(
            color = tint.copy(alpha = 0.50f),
            start = Offset(ox + 2.5f * s, oy + 8.2f * s),
            end = Offset(ox + 21.5f * s, oy + 8.2f * s),
            strokeWidth = strokeWidthPx * 0.85f,
            cap = StrokeCap.Round
        )

        // Микро-точки статуса заголовка окна консоли
        drawCircle(
            color = tint.copy(alpha = 0.85f),
            radius = 0.85f * s,
            center = Offset(ox + 5.5f * s, oy + 6.0f * s),
            style = stroke
        )
        drawCircle(
            color = tint.copy(alpha = 0.65f),
            radius = 0.85f * s,
            center = Offset(ox + 8.2f * s, oy + 6.0f * s),
            style = stroke
        )

        // ── 2. ПРИГЛАШЕНИЕ КОМАНДНОЙ СТРОКИ `>` ──
        promptPath.reset()
        promptPath.moveTo(ox + 6.2f * s, oy + 11.8f * s)
        promptPath.lineTo(ox + 9.4f * s, oy + 14.5f * s)
        promptPath.lineTo(ox + 6.2f * s, oy + 17.2f * s)
        drawPath(path = promptPath, color = tint, style = stroke)

        // ── 3. НИЖНИЙ ШТРИХ КУРСОРА `_` ──
        drawLine(
            color = tint.copy(alpha = cursorAlpha),
            start = Offset(ox + 12.0f * s, oy + 17.2f * s),
            end = Offset(ox + 17.2f * s, oy + 17.2f * s),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )

        // Дополнительная информационная строка лога (output stream preview)
        drawLine(
            color = tint.copy(alpha = 0.40f),
            start = Offset(ox + 12.0f * s, oy + 12.2f * s),
            end = Offset(ox + 18.5f * s, oy + 12.2f * s),
            strokeWidth = strokeWidthPx * 0.85f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun MirrlyLogsIcon(
    color: Color,
    modifier: Modifier = Modifier.size(24.dp),
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyLogsIcon(modifier = modifier, tint = color, strokeWidth = strokeWidth, isAnimated = isAnimated)

@Composable
fun MirrlyIconLogs(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyLogsIcon(modifier = modifier, tint = tint, strokeWidth = strokeWidth, isAnimated = isAnimated)


// =============================================================================
// 5. ic_history — Спиральная лента времени и циферблат с круговой стрелкой перемотки
// =============================================================================

/**
 * Векторная иконка истории событий (ic_history).
 * Спиральная лента времени: циферблат с круговой стрелкой обратной перемотки
 * событий и стрелками времени. Четко дифференцирована от прямоугольного шелл-терминала ic_logs.
 */
@Composable
fun MirrlyHistoryIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val arcPath = remember { Path() }
    val arrowPath = remember { Path() }

    val shouldAnimate = rememberIconAnimationActive(isAnimated)
    val rewindHandAngle = if (shouldAnimate) {
        val transition = rememberInfiniteTransition(label = "HistoryRewindTransition")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = -360f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing)
            ),
            label = "HistoryHandAngle"
        ).value
    } else {
        0f
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val s = minOf(w, h) / 24f
        val ox = (w - 24f * s) * 0.5f
        val oy = (h - 24f * s) * 0.5f

        val cx = ox + 12.0f * s
        val cy = oy + 12.0f * s
        val r = 8.5f * s

        // ── 1. КРУГОВАЯ СТРЕЛКА ОБРАТНОЙ ПЕРЕМОТКИ СОБЫТИЙ (REWIND ARC) ──
        // Дуга идет против часовой стрелки (назад во времени): от 45° через 0°, 270°, 180° к 135°
        arcPath.reset()
        arcPath.moveTo(ox + 18.0f * s, oy + 6.0f * s)
        // Правая верхняя часть круга к нижней
        arcPath.cubicTo(
            ox + 20.5f * s, oy + 8.5f * s,
            ox + 20.5f * s, oy + 15.5f * s,
            ox + 17.5f * s, oy + 18.5f * s
        )
        // Нижняя часть к левой
        arcPath.cubicTo(
            ox + 14.5f * s, oy + 21.0f * s,
            ox + 8.5f * s, oy + 21.0f * s,
            ox + 5.5f * s, oy + 18.0f * s
        )
        // Левая часть к верхней стрелке перемотки
        arcPath.cubicTo(
            ox + 3.0f * s, oy + 15.0f * s,
            ox + 3.2f * s, oy + 8.5f * s,
            ox + 6.8f * s, oy + 5.8f * s
        )
        drawPath(path = arcPath, color = tint, style = stroke)

        // Наконечник стрелки обратной перемотки
        arrowPath.reset()
        arrowPath.moveTo(ox + 3.2f * s, oy + 6.0f * s)
        arrowPath.lineTo(ox + 6.8f * s, oy + 5.8f * s)
        arrowPath.lineTo(ox + 6.5f * s, oy + 2.2f * s)
        drawPath(path = arrowPath, color = tint, style = stroke)

        // ── 2. СТРЕЛКИ ВРЕМЕНИ И ЦИФЕРБЛАТ ──
        // Центральная опорная точка
        drawCircle(
            color = tint,
            radius = 1.0f * s,
            center = Offset(cx, cy),
            style = stroke
        )

        // Часовая стрелка (на 12 часов)
        drawLine(
            color = tint,
            start = Offset(cx, cy),
            end = Offset(cx, cy - 4.5f * s),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )

        // Минутная стрелка (с анимацией обратной перемотки или фиксированная на 3 часа)
        val handAngleRad = (rewindHandAngle * Math.PI / 180.0).toFloat()
        val minuteLen = 4.0f * s
        val minuteEndX = cx + minuteLen * cos(handAngleRad)
        val minuteEndY = cy + minuteLen * sin(handAngleRad)
        drawLine(
            color = tint,
            start = Offset(cx, cy),
            end = Offset(minuteEndX, minuteEndY),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )

        // Внутренний спиральный виток ленты времени
        drawLine(
            color = tint.copy(alpha = 0.40f),
            start = Offset(ox + 9.5f * s, oy + 12.0f * s),
            end = Offset(ox + 8.0f * s, oy + 12.0f * s),
            strokeWidth = strokeWidthPx * 0.75f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun MirrlyHistoryIcon(
    color: Color,
    modifier: Modifier = Modifier.size(24.dp),
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyHistoryIcon(modifier = modifier, tint = color, strokeWidth = strokeWidth, isAnimated = isAnimated)

@Composable
fun MirrlyIconHistory(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyHistoryIcon(modifier = modifier, tint = tint, strokeWidth = strokeWidth, isAnimated = isAnimated)


// =============================================================================
// 6. ic_info_circle — Окружность с вертикальной сенсорной планкой и точкой
// =============================================================================

/**
 * Векторная иконка справочной информации (ic_info_circle).
 * Окружность с тонкой вертикальной сенсорной планкой и парящей точкой над ней.
 * Имеет внешнюю окружность в отличие от ic_question_mark.
 */
@Composable
fun MirrlyInfoCircleIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val shouldAnimate = rememberIconAnimationActive(isAnimated)
    val dotPulse = if (shouldAnimate) {
        val transition = rememberInfiniteTransition(label = "InfoDotTransition")
        transition.animateFloat(
            initialValue = 0.65f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "InfoDotPulse"
        ).value
    } else {
        1.0f
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val s = minOf(w, h) / 24f
        val ox = (w - 24f * s) * 0.5f
        val oy = (h - 24f * s) * 0.5f

        val cx = ox + 12.0f * s
        val cy = oy + 12.0f * s
        val r = 9.0f * s

        // ── 1. ВНЕШНЯЯ КОНТУРНАЯ ОКРУЖНОСТЬ ──
        drawCircle(
            color = tint,
            radius = r,
            center = Offset(cx, cy),
            style = stroke
        )

        // ── 2. ВЕРТИКАЛЬНАЯ СЕНСОРНАЯ ПЛАНКА ──
        drawLine(
            color = tint,
            start = Offset(cx, oy + 11.0f * s),
            end = Offset(cx, oy + 16.5f * s),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )

        // ── 3. ПАРЯЩАЯ ТОЧКА НАД ПЛАНКОЙ ──
        drawCircle(
            color = tint.copy(alpha = dotPulse),
            radius = strokeWidthPx * 0.55f,
            center = Offset(cx, oy + 7.3f * s)
        )
    }
}

@Composable
fun MirrlyInfoCircleIcon(
    color: Color,
    modifier: Modifier = Modifier.size(24.dp),
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyInfoCircleIcon(modifier = modifier, tint = color, strokeWidth = strokeWidth, isAnimated = isAnimated)

@Composable
fun MirrlyIconInfoCircle(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyInfoCircleIcon(modifier = modifier, tint = tint, strokeWidth = strokeWidth, isAnimated = isAnimated)


// =============================================================================
// 7. ic_question_mark — Спиралевидный изгиб вопроса с нижней точкой (БЕЗ окружности)
// =============================================================================

/**
 * Векторная иконка знака вопроса (ic_question_mark).
 * Спиралевидный изгиб вопроса с оптически сбалансированной нижней точкой.
 * Внимание: БЕЗ внешней окружности (четкое разграничение с ic_info_circle).
 */
@Composable
fun MirrlyQuestionMarkIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val hookPath = remember { Path() }

    val shouldAnimate = rememberIconAnimationActive(isAnimated)
    val floatOffset = if (shouldAnimate) {
        val transition = rememberInfiniteTransition(label = "QuestionFloatTransition")
        transition.animateFloat(
            initialValue = -0.4f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "QuestionFloat"
        ).value
    } else {
        0f
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val s = minOf(w, h) / 24f
        val ox = (w - 24f * s) * 0.5f
        val oy = (h - 24f * s) * 0.5f

        val cx = ox + 12.0f * s

        // ── 1. СПИРАЛЕВИДНЫЙ ИЗГИБ ВОПРОСА (БЕЗ ВНЕШНЕЙ ОКРУЖНОСТИ) ──
        hookPath.reset()
        // Начало левого завитка
        hookPath.moveTo(ox + 8.5f * s, oy + 8.5f * s)
        // Верхняя дуга вопроса
        hookPath.cubicTo(
            ox + 8.5f * s, oy + 5.0f * s,
            ox + 10.2f * s, oy + 3.8f * s,
            cx, oy + 3.8f * s
        )
        // Правая нисходящая дуга
        hookPath.cubicTo(
            ox + 14.8f * s, oy + 3.8f * s,
            ox + 16.5f * s, oy + 5.5f * s,
            ox + 16.5f * s, oy + 8.2f * s
        )
        // Спиральный вход в центральную стойку
        hookPath.cubicTo(
            ox + 16.5f * s, oy + 10.8f * s,
            ox + 13.5f * s, oy + 11.8f * s,
            cx, oy + 13.5f * s
        )
        // Вертикальное окончание стойки
        hookPath.lineTo(cx, oy + 14.8f * s)

        drawPath(path = hookPath, color = tint, style = stroke)

        // ── 2. ОПТИЧЕСКИ СБАЛАНСИРОВАННАЯ НИЖНЯЯ ТОЧКА ──
        val dotY = oy + (18.6f + floatOffset) * s
        drawCircle(
            color = tint,
            radius = strokeWidthPx * 0.65f,
            center = Offset(cx, dotY)
        )
    }
}

@Composable
fun MirrlyQuestionMarkIcon(
    color: Color,
    modifier: Modifier = Modifier.size(24.dp),
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyQuestionMarkIcon(modifier = modifier, tint = color, strokeWidth = strokeWidth, isAnimated = isAnimated)

@Composable
fun MirrlyIconQuestionMark(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyQuestionMarkIcon(modifier = modifier, tint = tint, strokeWidth = strokeWidth, isAnimated = isAnimated)


// =============================================================================
// 8. ic_bug — Микросхема в форме жука с 6 угловыми лапками-выводами и усиками
// =============================================================================

/**
 * Векторная иконка отчета об ошибках/багах (ic_bug).
 * Микросхема в форме жука: корпус чипа с 6 угловыми лапками-выводами (chip lead pins),
 * парой усиков-антенн и центральной шиной данных.
 */
@Composable
fun MirrlyBugIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val chipBodyPath = remember { Path() }
    val chipDetailsPath = remember { Path() }
    val legsPath = remember { Path() }

    val shouldAnimate = rememberIconAnimationActive(isAnimated)
    val clockPulse = if (shouldAnimate) {
        val transition = rememberInfiniteTransition(label = "BugPulseTransition")
        transition.animateFloat(
            initialValue = 0.70f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "BugPulse"
        ).value
    } else {
        1.0f
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val s = minOf(w, h) / 24f
        val ox = (w - 24f * s) * 0.5f
        val oy = (h - 24f * s) * 0.5f

        val cx = ox + 12.0f * s

        // ── 1. КОРПУС ЧИПА-ЖУКА (IC PACKAGE SILICON BODY) ──
        chipBodyPath.reset()
        val r = 3.2f * s
        chipBodyPath.addRoundRect(
            RoundRect(
                left = ox + 8.2f * s,
                top = oy + 7.0f * s,
                right = ox + 15.8f * s,
                bottom = oy + 17.8f * s,
                cornerRadius = CornerRadius(r, r)
            )
        )
        drawPath(path = chipBodyPath, color = tint, style = stroke)

        // ── 2. ВНУТРЕННЯЯ ШИНА И КОНТАКТНЫЕ ДОРОЖКИ ──
        chipDetailsPath.reset()
        // Центральная продольная шина
        chipDetailsPath.moveTo(cx, oy + 8.5f * s)
        chipDetailsPath.lineTo(cx, oy + 16.5f * s)
        // Поперечный сегмент кристалла
        chipDetailsPath.moveTo(ox + 8.2f * s, oy + 12.2f * s)
        chipDetailsPath.lineTo(ox + 15.8f * s, oy + 12.2f * s)
        drawPath(path = chipDetailsPath, color = tint.copy(alpha = 0.75f), style = stroke)

        // ── 3. ПАРА УСИКОВ-АНТЕНН (ANTENNA TRACES) ──
        legsPath.reset()
        // Левый усик
        legsPath.moveTo(ox + 9.8f * s, oy + 7.0f * s)
        legsPath.lineTo(ox + 7.2f * s, oy + 3.5f * s)
        // Правый усик
        legsPath.moveTo(ox + 14.2f * s, oy + 7.0f * s)
        legsPath.lineTo(ox + 16.8f * s, oy + 3.5f * s)

        // ── 4. 6 УГЛОВЫХ ЛАПОК-ВЫВОДОВ (3 СЛЕВА, 3 СПРАВА) ──
        // Левые лапки (выводы корпуса)
        // 1. Верхняя лапка (направлена вперед-вбок)
        legsPath.moveTo(ox + 8.2f * s, oy + 9.5f * s)
        legsPath.lineTo(ox + 5.2f * s, oy + 8.5f * s)
        legsPath.lineTo(ox + 2.8f * s, oy + 7.0f * s)

        // 2. Средняя лапка (горизонтальный вывод)
        legsPath.moveTo(ox + 8.2f * s, oy + 12.4f * s)
        legsPath.lineTo(ox + 4.8f * s, oy + 12.4f * s)
        legsPath.lineTo(ox + 2.4f * s, oy + 12.4f * s)

        // 3. Нижняя лапка (направлена назад-вбок)
        legsPath.moveTo(ox + 8.2f * s, oy + 15.3f * s)
        legsPath.lineTo(ox + 5.2f * s, oy + 16.3f * s)
        legsPath.lineTo(ox + 2.8f * s, oy + 17.8f * s)

        // Правые лапки (симметричные выводы корпуса)
        // 4. Верхняя лапка
        legsPath.moveTo(ox + 15.8f * s, oy + 9.5f * s)
        legsPath.lineTo(ox + 18.8f * s, oy + 8.5f * s)
        legsPath.lineTo(ox + 21.2f * s, oy + 7.0f * s)

        // 5. Средняя лапка
        legsPath.moveTo(ox + 15.8f * s, oy + 12.4f * s)
        legsPath.lineTo(ox + 19.2f * s, oy + 12.4f * s)
        legsPath.lineTo(ox + 21.6f * s, oy + 12.4f * s)

        // 6. Нижняя лапка
        legsPath.moveTo(ox + 15.8f * s, oy + 15.3f * s)
        legsPath.lineTo(ox + 18.8f * s, oy + 16.3f * s)
        legsPath.lineTo(ox + 21.2f * s, oy + 17.8f * s)

        drawPath(path = legsPath, color = tint, style = stroke)

        // Контактные микро-точки на концах усиков
        drawCircle(
            color = tint.copy(alpha = clockPulse),
            radius = strokeWidthPx * 0.55f,
            center = Offset(ox + 7.2f * s, oy + 3.5f * s)
        )
        drawCircle(
            color = tint.copy(alpha = clockPulse),
            radius = strokeWidthPx * 0.55f,
            center = Offset(ox + 16.8f * s, oy + 3.5f * s)
        )
    }
}

@Composable
fun MirrlyBugIcon(
    color: Color,
    modifier: Modifier = Modifier.size(24.dp),
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyBugIcon(modifier = modifier, tint = color, strokeWidth = strokeWidth, isAnimated = isAnimated)

@Composable
fun MirrlyIconBug(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyBugIcon(modifier = modifier, tint = tint, strokeWidth = strokeWidth, isAnimated = isAnimated)


// =============================================================================
// 9. ic_license — Официальный контурный пергамент/свиток с лентой печати внизу
// =============================================================================

/**
 * Векторная иконка лицензии и открытого ПО (ic_license).
 * Официальный контурный пергамент/свиток с лентой печати внизу и строками условий.
 */
@Composable
fun MirrlyLicenseIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) {
    val density = LocalDensity.current
    val strokeWidthPx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val scrollPath = remember { Path() }
    val sealPath = remember { Path() }

    val shouldAnimate = rememberIconAnimationActive(isAnimated)
    val ribbonSway = if (shouldAnimate) {
        val transition = rememberInfiniteTransition(label = "LicenseRibbonTransition")
        transition.animateFloat(
            initialValue = -0.5f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "LicenseRibbonSway"
        ).value
    } else {
        0f
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val s = minOf(w, h) / 24f
        val ox = (w - 24f * s) * 0.5f
        val oy = (h - 24f * s) * 0.5f

        // ── 1. КОНТУРНЫЙ ПЕРГАМЕНТ/СВИТОК СО СКРУЧЕННЫМИ КРАЯМИ ──
        scrollPath.reset()
        // Верхний свиток (scroll curl)
        scrollPath.moveTo(ox + 4.8f * s, oy + 5.5f * s)
        scrollPath.cubicTo(
            ox + 4.8f * s, oy + 3.2f * s,
            ox + 6.8f * s, oy + 2.5f * s,
            ox + 9.5f * s, oy + 2.5f * s
        )
        scrollPath.lineTo(ox + 16.5f * s, oy + 2.5f * s)
        scrollPath.cubicTo(
            ox + 18.5f * s, oy + 2.5f * s,
            ox + 19.5f * s, oy + 3.8f * s,
            ox + 19.5f * s, oy + 5.5f * s
        )
        // Правая грань пергамента
        scrollPath.lineTo(ox + 19.5f * s, oy + 16.5f * s)
        // Нижняя грань пергамента
        scrollPath.cubicTo(
            ox + 19.5f * s, oy + 17.8f * s,
            ox + 18.2f * s, oy + 18.2f * s,
            ox + 16.8f * s, oy + 17.2f * s
        )
        scrollPath.lineTo(ox + 7.5f * s, oy + 17.2f * s)
        scrollPath.cubicTo(
            ox + 5.8f * s, oy + 18.2f * s,
            ox + 4.8f * s, oy + 17.8f * s,
            ox + 4.8f * s, oy + 16.5f * s
        )
        // Левая грань пергамента
        scrollPath.lineTo(ox + 4.8f * s, oy + 5.5f * s)
        scrollPath.close()

        drawPath(path = scrollPath, color = tint, style = stroke)

        // Внутренний сгиб верхнего валика свитка
        drawLine(
            color = tint.copy(alpha = 0.55f),
            start = Offset(ox + 6.2f * s, oy + 5.2f * s),
            end = Offset(ox + 18.0f * s, oy + 5.2f * s),
            strokeWidth = strokeWidthPx * 0.85f,
            cap = StrokeCap.Round
        )

        // ── 2. СТРОКИ УСЛОВИЙ ЛИЦЕНЗИОННОГО ДОКУМЕНТА ──
        // Строка 1
        drawLine(
            color = tint,
            start = Offset(ox + 7.8f * s, oy + 8.2f * s),
            end = Offset(ox + 16.2f * s, oy + 8.2f * s),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )
        // Строка 2
        drawLine(
            color = tint,
            start = Offset(ox + 7.8f * s, oy + 11.2f * s),
            end = Offset(ox + 16.2f * s, oy + 11.2f * s),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )
        // Строка 3 (короткая)
        drawLine(
            color = tint,
            start = Offset(ox + 7.8f * s, oy + 14.2f * s),
            end = Offset(ox + 11.8f * s, oy + 14.2f * s),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )

        // ── 3. ОФИЦИАЛЬНАЯ ПЕЧАТЬ И ЛЕНТА ПЕЧАТИ (SEAL & RIBBON) ──
        val sealCx = ox + 15.5f * s
        val sealCy = oy + 16.8f * s
        val sealRadius = 2.4f * s

        // Круглая печать
        drawCircle(
            color = tint,
            radius = sealRadius,
            center = Offset(sealCx, sealCy),
            style = stroke
        )

        // Ленты печати, ниспадающие вниз
        sealPath.reset()
        // Левая лента печати
        sealPath.moveTo(sealCx - 1.2f * s, sealCy + sealRadius)
        sealPath.lineTo(sealCx - 2.4f * s + ribbonSway * s, oy + 22.0f * s)
        sealPath.lineTo(sealCx - 0.8f * s + ribbonSway * s, oy + 20.8f * s)

        // Правая лента печати
        sealPath.moveTo(sealCx + 1.2f * s, sealCy + sealRadius)
        sealPath.lineTo(sealCx + 2.4f * s - ribbonSway * s, oy + 22.0f * s)
        sealPath.lineTo(sealCx + 0.8f * s - ribbonSway * s, oy + 20.8f * s)

        drawPath(path = sealPath, color = tint, style = stroke)
    }
}

@Composable
fun MirrlyLicenseIcon(
    color: Color,
    modifier: Modifier = Modifier.size(24.dp),
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyLicenseIcon(modifier = modifier, tint = color, strokeWidth = strokeWidth, isAnimated = isAnimated)

@Composable
fun MirrlyIconLicense(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp,
    isAnimated: Boolean = false
) = MirrlyLicenseIcon(modifier = modifier, tint = tint, strokeWidth = strokeWidth, isAnimated = isAnimated)
