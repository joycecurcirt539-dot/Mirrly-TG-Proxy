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

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

data class ConnectionHealthReport(
    val score: Int, // 0..100% (Общий комбинированный SQI)
    val chatScore: Int, // 0..100% (SQI для чатов и медиа)
    val chatVerdict: String,
    val callScore: Int, // 0..100% (SQI для голосовых и видеозвонков)
    val mosScore: Double, // 1.00..4.50 (ITU-T G.107 MOS)
    val mosGrade: String,
    val isCallRecommended: Boolean,
    val verdict: String,
    val detail: String,
    val operatorLatencyGrade: String,
    val workerStatusGrade: String,
    val packetReliabilityGrade: String,
    val pingMs: Long,
    val jitterMs: Long,
    val successRate: Int,
    val isExcellent: Boolean
)

object ConnectionHealthEngine {

    fun computeHealth(
        smoothedPingMs: Long,
        jitterMs: Long,
        successRatePercent: Int,
        lastFailureType: FailureType = FailureType.NONE,
        isFailoverActive: Boolean = false
    ): ConnectionHealthReport {
        if (smoothedPingMs <= 0L && successRatePercent <= 0) {
            return ConnectionHealthReport(
                score = 0,
                chatScore = 0,
                chatVerdict = "Нет связи",
                callScore = 0,
                mosScore = 1.00,
                mosGrade = "Нет связи",
                isCallRecommended = false,
                verdict = "Канал не активен",
                detail = "Ожидание первых сетевых проб",
                operatorLatencyGrade = "Недоступно",
                workerStatusGrade = "Ожидание",
                packetReliabilityGrade = "0%",
                pingMs = -1L,
                jitterMs = 0L,
                successRate = 0,
                isExcellent = false
            )
        }

        val ping = if (smoothedPingMs > 0L) smoothedPingMs else 300L
        val jitter = jitterMs.coerceAtLeast(0L)
        val success = successRatePercent.coerceIn(0, 100)

        // 1. Расчет качества для ЧАТОВ И МЕДИА (TCP/TLS трафик)
        // Высокий приоритет надежности доставки, мягкая толерантность к RTT до 250мс
        val chatRttDelta = max(0.0, (ping - 30).toDouble())
        val chatRttScore = (100.0 / (1.0 + (chatRttDelta / 260.0).pow(1.40))).coerceIn(5.0, 100.0)

        val chatJitterDelta = max(0.0, (jitter - 5).toDouble())
        val chatJitterScore = (100.0 / (1.0 + (chatJitterDelta / 50.0).pow(1.35))).coerceIn(5.0, 100.0)

        val chatDeliveryFactor = if (success >= 90) 1.0 else (success / 90.0).pow(0.70)
        var chatComposite = ((chatRttScore * 0.30) + (chatJitterScore * 0.20) + (success.toDouble() * 0.50)) * chatDeliveryFactor

        // 2. Расчет качества для ЗВОНКОВ И ВИДЕО (RTP/UDP Opus, ITU-T G.107 MOS)
        val (mosScore, mosGrade, isCallRecommended) = calculateMos(ping, jitter, success, lastFailureType)
        val callScore = (((mosScore - 1.0) / 3.5) * 100.0).roundToInt().coerceIn(0, 100)

        // Штрафы за сбои и блокировки
        if (lastFailureType == FailureType.DPI_BLOCKED || lastFailureType == FailureType.TLS_HANDSHAKE_FAILED) {
            chatComposite -= 35.0
        } else if (lastFailureType == FailureType.RATE_LIMITED_429) {
            chatComposite -= 25.0
        } else if (lastFailureType != FailureType.NONE) {
            chatComposite -= 15.0
        }
        if (isFailoverActive) {
            chatComposite -= 5.0
        }

        val finalChatScore = chatComposite.roundToInt().coerceIn(0, 100)

        // 3. Общий комбинированный скоринг для Главного экрана (55% чаты/медиа, 45% звонки)
        val finalTotalScore = ((finalChatScore * 0.55) + (callScore * 0.45)).roundToInt().coerceIn(0, 100)

        val chatVerdict = when {
            finalChatScore >= 90 -> "Идеально для медиа"
            finalChatScore >= 75 -> "Стабильно"
            finalChatScore >= 50 -> "Умеренная скорость"
            finalChatScore >= 25 -> "Задержка загрузки"
            else -> "Сбои доставки"
        }

        val (verdict, detail) = when {
            lastFailureType == FailureType.DPI_BLOCKED -> Pair(
                "Блокировка DPI оператором",
                "Обнаружен сброс TCP/ClientHello пакетов middlebox-системой"
            )
            lastFailureType == FailureType.TLS_HANDSHAKE_FAILED -> Pair(
                "Сбой защищенного TLS",
                "Разрыв TLS-рукопожатия или попытка подмены сертификата узла"
            )
            finalTotalScore >= 90 -> Pair(
                "Идеальный канал связи",
                "Минимальная задержка и стабильный прямой WSS-туннель"
            )
            finalTotalScore >= 75 -> Pair(
                "Хорошее соединение",
                "Небольшая вариация задержки (джиттер мобильной сети или Wi-Fi)"
            )
            finalTotalScore >= 50 -> Pair(
                "Задержки на стороне оператора",
                "Повышенный джиттер сотового радиоканала (LTE/5G) или перегрузка вышки"
            )
            finalTotalScore >= 25 -> Pair(
                "Деградация канала",
                "Потери пакетов на маршруте к узлу Cloudflare Edge"
            )
            else -> Pair(
                "Критическая нестабильность",
                "Высокий процент таймаутов или блокировка провайдером"
            )
        }

        val operatorGrade = when {
            jitter <= 15L && ping <= 120L -> "Отлично"
            jitter <= 40L && ping <= 250L -> "В норме"
            else -> "Высокий джиттер"
        }

        val workerGrade = when (lastFailureType) {
            FailureType.DPI_BLOCKED -> "DPI Блок"
            FailureType.TLS_HANDSHAKE_FAILED -> "TLS Сбой"
            FailureType.RATE_LIMITED_429 -> "Лимит 429"
            FailureType.NONE -> if (ping <= 100L) "Стабилен" else "Доступен"
            else -> "Сбои"
        }

        val reliabilityGrade = "$success%"

        return ConnectionHealthReport(
            score = finalTotalScore,
            chatScore = finalChatScore,
            chatVerdict = chatVerdict,
            callScore = callScore,
            mosScore = mosScore,
            mosGrade = mosGrade,
            isCallRecommended = isCallRecommended,
            verdict = verdict,
            detail = detail,
            operatorLatencyGrade = operatorGrade,
            workerStatusGrade = workerGrade,
            packetReliabilityGrade = reliabilityGrade,
            pingMs = ping,
            jitterMs = jitter,
            successRate = success,
            isExcellent = finalTotalScore >= 90
        )
    }

    /**
     * Стандартный расчет голосового рейтинга R-Factor и шкалы MOS по модели ITU-T G.107 E-Model.
     * Адаптирован для широкополосного аудиокодека Opus (Telegram Voice/Video Calls).
     */
    fun calculateMos(
        pingMs: Long,
        jitterMs: Long,
        successRatePercent: Int,
        lastFailureType: FailureType
    ): Triple<Double, String, Boolean> {
        if (successRatePercent <= 0 || pingMs < 0L || lastFailureType == FailureType.NETWORK_LOST) {
            return Triple(1.00, "Нет связи", false)
        }

        val r0 = 93.2 // Базовый фактор качества передачи речи (Opus Wideband)

        // 1. Односторонняя эффективная задержка: d = (RTT / 2) + 2 * Jitter
        val oneWayDelay = (pingMs / 2.0) + (2.0 * jitterMs.coerceAtLeast(0L))

        // 2. Фактор ухудшения от задержки Id (ITU-T G.107)
        val id = if (oneWayDelay <= 100.0) {
            0.024 * oneWayDelay
        } else {
            0.024 * oneWayDelay + 0.11 * (oneWayDelay - 100.0) + ((oneWayDelay - 100.0).pow(2.0) / 4000.0)
        }

        // 3. Фактор ухудшения от потери пакетов Ie (Opus packet loss concealment curve)
        val lossPercent = (100 - successRatePercent).coerceIn(0, 100).toDouble()
        val ie = 30.0 * ln(1.0 + 0.15 * lossPercent) + (1.2 * lossPercent)

        // 4. Рейтинговый фактор передачи R (0..100)
        var r = (r0 - id - ie).coerceIn(0.0, 100.0)

        if (lastFailureType == FailureType.DPI_BLOCKED || lastFailureType == FailureType.TLS_HANDSHAKE_FAILED) {
            r = 0.0
        } else if (lastFailureType == FailureType.RATE_LIMITED_429) {
            r = (r - 30.0).coerceAtLeast(0.0)
        } else if (lastFailureType != FailureType.NONE) {
            r = (r - 15.0).coerceAtLeast(0.0)
        }

        // 5. Преобразование R -> MOS по стандарту ITU-T G.107
        val rawMos = when {
            r <= 0.0 -> 1.00
            r >= 100.0 -> 4.50
            else -> 1.0 + (0.035 * r) + (7.0e-6 * r * (r - 60.0) * (100.0 - r))
        }

        val mos = (rawMos * 100.0).roundToInt() / 100.0
        val clampedMos = mos.coerceIn(1.00, 4.50)

        val (grade, recommended) = when {
            clampedMos >= 4.20 -> Pair("HD Voice (Отлично)", true)
            clampedMos >= 3.80 -> Pair("Хорошее качество", true)
            clampedMos >= 3.10 -> Pair("Приемлемо", true)
            clampedMos >= 2.40 -> Pair("С помехами", false)
            else -> Pair("Непригодно", false)
        }

        return Triple(clampedMos, grade, recommended)
    }
}
