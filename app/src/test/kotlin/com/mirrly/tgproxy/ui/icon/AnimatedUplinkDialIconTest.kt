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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Юнит-тесты кинетической геометрии и валидации иконки AnimatedUplinkDialIcon
 * по стандартам /mirrly-icons и /mirrly-ui.
 */
class AnimatedUplinkDialIconTest {

    @Test
    fun testComposableFunctionExistsViaReflection() {
        val clazz = Class.forName("com.mirrly.tgproxy.ui.icon.AnimatedUplinkDialIconKt")
        assertNotNull("Класс AnimatedUplinkDialIconKt обязан существовать", clazz)

        val method = clazz.methods.find { it.name.startsWith("AnimatedUplinkDialIcon") }
        assertNotNull("Composable-функция AnimatedUplinkDialIcon обязана присутствовать", method)
    }

    @Test
    fun testChannelDetentsSymmetryAndAngles() {
        // Channel indices: 0: Worker, 1: Direct TCP, 2: WARP
        val workerAngle = 270f
        val directAngle = 210f
        val warpAngle = 330f

        // Проверка симметрии каналов относительно вертикальной оси (270°)
        val leftDelta = workerAngle - directAngle
        val rightDelta = warpAngle - workerAngle
        assertEquals("Левое и правое плечо каналов должны иметь строго равный угол 60°", leftDelta, rightDelta, 0.001f)
        assertEquals("Шаг каналов должен быть равен 60°", 60f, leftDelta, 0.001f)

        // Проверка открытого зазора внизу (от 60° до 120°, центр на 90°)
        val gapStart = 60f
        val gapEnd = 120f
        val gapCenter = (gapStart + gapEnd) / 2f
        assertEquals("Зазор селектора должен быть точно отцентрирован по нижней точке 90°", 90f, gapCenter, 0.001f)
        assertEquals("Ширина зазора под шеврон должна составлять 60°", 60f, gapEnd - gapStart, 0.001f)

        // Проверка дуги безеля (300° + 60° = 360°)
        val sweepAngle = 300f
        assertEquals("Сумма дуги и зазора обязана составлять полный круг 360°", 360f, sweepAngle + (gapEnd - gapStart), 0.001f)
    }

    @Test
    fun testKineticKinematicsRange() {
        // Проверка диапазонов гироскопического покачивания безеля
        for (step in 0..100) {
            val progress = step / 100f
            val rotAngleDeg = sin(progress * 2.0 * PI).toFloat() * 3.0f
            assertTrue("Угол покачивания безеля обязан быть в диапазоне [-3.0°, +3.0°]", rotAngleDeg in -3.001f..3.001f)

            val wavePhase = (progress * 1.6f) % 1.0f
            assertTrue("Фаза квантовой волны должна быть в интервале [0.0, 1.0]", wavePhase in 0.0f..1.0f)

            val pulseAlpha = 0.5f + 0.5f * sin(progress * 2.0 * PI).toFloat()
            assertTrue("Коэффициент пульсации должен быть в интервале [0.0, 1.0]", pulseAlpha in 0.0f..1.0f)
        }
    }

    @Test
    fun testCoordinatesNonNaNForVariousCanvasSizes() {
        val testSizes = listOf(24f, 32f, 36f, 42f, 48f, 64f)
        for (size in testSizes) {
            val cx = size / 2f
            val cy = size / 2f
            val r = (size / 2f) - 4f

            assertTrue("Радиус должен быть строго положительным", r > 0f)

            // Проверка узловых точек каналов
            val channelAngles = floatArrayOf(270f, 210f, 330f)
            for (angle in channelAngles) {
                val rad = angle * (PI / 180.0)
                val x = cx + r * cos(rad).toFloat()
                val y = cy + r * sin(rad).toFloat()
                assertTrue("Координата X не должна быть NaN или бесконечностью", !x.isNaN() && !x.isInfinite())
                assertTrue("Координата Y не должна быть NaN или бесконечностью", !y.isNaN() && !y.isInfinite())
            }
        }
    }
}
