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

package com.mirrly.tgproxy.core

import kotlin.math.roundToLong

/**
 * Адаптивный фильтр экспоненциального сглаживания скорости (EMA Speed Filter).
 *
 * Устраняет высокочастотный джиттер сотовых сетей (пакетные всплески TCP),
 * обеспечивает естественную плавность спидометра в UI и предотвращает ложные
 * срабатывания авто-тюнинга сокетов.
 */
class EmaSpeedFilter(
    private val defaultAlpha: Double = 0.30,
    private val cutoffBps: Double = 1024.0 // Порог отсечения остаточного шлейфа (1 КБ/с)
) {
    @Volatile
    private var smoothedSpeedBps: Double = -1.0

    val currentSmoothedSpeed: Long
        get() = if (smoothedSpeedBps > 0.0) smoothedSpeedBps.roundToLong() else 0L

    /**
     * Подает на вход фильтра новое мгновенное измерение скорости и возвращает сглаженное значение.
     */
    @Synchronized
    fun update(rawSpeedBps: Long): Long {
        val raw = rawSpeedBps.coerceAtLeast(0L).toDouble()

        if (raw <= 0.0) {
            if (smoothedSpeedBps <= 0.0) {
                smoothedSpeedBps = 0.0
                return 0L
            }

            // Быстрое затухание при остановке передачи (alpha = 0.50)
            smoothedSpeedBps *= 0.50

            // Пороговое отсечение для предотвращения "залипания" остаточной скорости
            if (smoothedSpeedBps < cutoffBps) {
                smoothedSpeedBps = 0.0
            }
            return currentSmoothedSpeed
        }

        if (smoothedSpeedBps <= 0.0) {
            // Первый замер скорости — инициализируем базовое значение
            smoothedSpeedBps = raw
            return currentSmoothedSpeed
        }

        // Динамическая адаптация alpha:
        // - При резком старте скачивания (Ramp-up > 2.5x) увеличиваем alpha до 0.65 для мгновенного отклика
        // - В установившемся режиме используем defaultAlpha (0.30) для максимальной плавности
        val alpha = if (raw > (smoothedSpeedBps * 2.5)) {
            0.65
        } else {
            defaultAlpha
        }

        smoothedSpeedBps = ((1.0 - alpha) * smoothedSpeedBps) + (alpha * raw)
        return currentSmoothedSpeed
    }

    @Synchronized
    fun reset() {
        smoothedSpeedBps = -1.0
    }
}
