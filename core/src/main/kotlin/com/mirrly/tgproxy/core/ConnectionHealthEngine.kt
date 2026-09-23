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
        isFailoverActive: Boolean = false,
        isSocks5: Boolean = false
    ): ConnectionHealthReport {
        if (smoothedPingMs <= 0L && successRatePercent <= 0) {
            return ConnectionHealthReport(
                score = 0,
                chatScore = 0,
                chatVerdict = "No connection",
                callScore = 0,
                mosScore = 1.00,
                mosGrade = "No connection",
                isCallRecommended = false,
                verdict = "Channel inactive",
                detail = "Awaiting initial network probes",
                operatorLatencyGrade = "Unavailable",
                workerStatusGrade = "Awaiting",
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

        // 1. Quality calculation for CHATS & MEDIA (TCP/TLS traffic)
        // High delivery reliability priority, soft tolerance to RTT up to 250ms
        val chatRttDelta = max(0.0, (ping - 30).toDouble())
        val chatRttScore = (100.0 / (1.0 + (chatRttDelta / 650.0).pow(1.40))).coerceIn(5.0, 100.0)

        val chatJitterDelta = max(0.0, (jitter - 5).toDouble())
        val chatJitterScore = (100.0 / (1.0 + (chatJitterDelta / 50.0).pow(1.35))).coerceIn(5.0, 100.0)

        val chatDeliveryFactor = if (success >= 90) 1.0 else (success / 90.0).pow(0.70)
        var chatComposite = ((chatRttScore * 0.25) + (chatJitterScore * 0.25) + (success.toDouble() * 0.50)) * chatDeliveryFactor

        // 2. Quality calculation for CALLS & VIDEO (RTP/UDP Opus, ITU-T G.107 MOS)
        val (mosScore, mosGrade, isCallRecommended) = calculateMos(ping, jitter, success, lastFailureType)
        val callScore = (((mosScore - 1.0) / 3.5) * 100.0).roundToInt().coerceIn(0, 100)

        // Penalties for failures and blocks
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

        // 3. Overall combined scoring (SOCKS5 accounts for 20% calls; MTProto is 100% chats/media)
        val finalTotalScore = if (isSocks5) {
            ((finalChatScore * 0.80) + (callScore * 0.20)).roundToInt().coerceIn(0, 100)
        } else {
            finalChatScore
        }

        val chatVerdict = when {
            finalChatScore >= 90 -> "Ideal for media"
            finalChatScore >= 75 -> "Stable"
            finalChatScore >= 50 -> "Moderate speed"
            finalChatScore >= 25 -> "Download delays"
            else -> "Delivery failures"
        }

        val (verdict, detail) = when {
            lastFailureType == FailureType.DPI_BLOCKED -> Pair(
                "Operator DPI Block",
                "TCP/ClientHello packet reset detected by middlebox"
            )
            lastFailureType == FailureType.TLS_HANDSHAKE_FAILED -> Pair(
                "Secure TLS Failure",
                "TLS handshake aborted or invalid server certificate"
            )
            finalTotalScore >= 90 -> Pair(
                "Optimal Connection",
                "Minimal latency and stable direct WSS tunnel"
            )
            finalTotalScore >= 75 -> Pair(
                "Good Connection",
                "Minor latency variation (mobile or Wi-Fi jitter)"
            )
            finalTotalScore >= 50 -> Pair(
                "Operator Delays",
                "Increased cellular radio jitter (LTE/5G) or cell congestion"
            )
            finalTotalScore >= 25 -> Pair(
                "Link Degradation",
                "Packet loss en route to Cloudflare Edge"
            )
            else -> Pair(
                "Critical Instability",
                "High timeout rate or provider blocking"
            )
        }

        val operatorGrade = when {
            jitter <= 20L && ping <= 250L -> "Excellent"
            jitter <= 40L && ping <= 650L -> "Normal"
            jitter > 40L -> "High jitter"
            else -> "Delays"
        }

        val workerGrade = when (lastFailureType) {
            FailureType.DPI_BLOCKED -> "DPI Block"
            FailureType.TLS_HANDSHAKE_FAILED -> "TLS Failure"
            FailureType.RATE_LIMITED_429 -> "Limit 429"
            FailureType.NONE -> if (ping <= 500L) "Stable" else "Reachable"
            else -> "Failures"
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
            return Triple(1.00, "No connection", false)
        }

        val r0 = 93.2 // Base transmission rating factor (Opus Wideband)

        // 1. Effective one-way delay: d = (RTT / 2) + 2 * Jitter
        val oneWayDelay = (pingMs / 2.0) + (2.0 * jitterMs.coerceAtLeast(0L))

        // 2. Delay impairment factor Id (ITU-T G.107)
        val id = if (oneWayDelay <= 100.0) {
            0.024 * oneWayDelay
        } else {
            0.024 * oneWayDelay + 0.11 * (oneWayDelay - 100.0) + ((oneWayDelay - 100.0).pow(2.0) / 4000.0)
        }

        // 3. Equipment impairment factor Ie (Opus packet loss concealment curve)
        val lossPercent = (100 - successRatePercent).coerceIn(0, 100).toDouble()
        val ie = 30.0 * ln(1.0 + 0.15 * lossPercent) + (1.2 * lossPercent)

        // 4. Transmission rating factor R (0..100)
        var r = (r0 - id - ie).coerceIn(0.0, 100.0)

        if (lastFailureType == FailureType.DPI_BLOCKED || lastFailureType == FailureType.TLS_HANDSHAKE_FAILED) {
            r = 0.0
        } else if (lastFailureType == FailureType.RATE_LIMITED_429) {
            r = (r - 30.0).coerceAtLeast(0.0)
        } else if (lastFailureType != FailureType.NONE) {
            r = (r - 15.0).coerceAtLeast(0.0)
        }

        // 5. Transform R -> MOS according to ITU-T G.107
        val rawMos = when {
            r <= 0.0 -> 1.00
            r >= 100.0 -> 4.50
            else -> 1.0 + (0.035 * r) + (7.0e-6 * r * (r - 60.0) * (100.0 - r))
        }

        val mos = (rawMos * 100.0).roundToInt() / 100.0
        val clampedMos = mos.coerceIn(1.00, 4.50)

        val (grade, recommended) = when {
            clampedMos >= 4.20 -> Pair("HD Voice (Excellent)", true)
            clampedMos >= 3.80 -> Pair("Good quality", true)
            clampedMos >= 3.10 -> Pair("Acceptable", true)
            clampedMos >= 2.40 -> Pair("With noise", false)
            else -> Pair("Unusable", false)
        }

        return Triple(clampedMos, grade, recommended)
    }
}
