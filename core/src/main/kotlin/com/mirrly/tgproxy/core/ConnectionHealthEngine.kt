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
    val isExcellent: Boolean,
    val isCalibrating: Boolean = false,
    val isLiveTrafficActive: Boolean = false
)

object ConnectionHealthEngine {

    fun calibratingReport(): ConnectionHealthReport = ConnectionHealthReport(
        score = 0,
        chatScore = 0,
        chatVerdict = "Checking",
        callScore = 0,
        mosScore = 1.00,
        mosGrade = "Calibrating",
        isCallRecommended = false,
        verdict = "Calibrating",
        detail = "Checking transport route...",
        operatorLatencyGrade = "Awaiting",
        workerStatusGrade = "Awaiting",
        packetReliabilityGrade = "—",
        pingMs = -1L,
        jitterMs = 0L,
        successRate = 0,
        isExcellent = false,
        isCalibrating = true,
        isLiveTrafficActive = false
    )

    fun computeHealth(
        smoothedPingMs: Long,
        jitterMs: Long,
        successRatePercent: Int,
        lastFailureType: FailureType = FailureType.NONE,
        isFailoverActive: Boolean = false,
        isSocks5: Boolean = false,
        sliSnapshot: EngineSliSnapshot? = null,
        isTlsEnabled: Boolean = false,
        isFlowseal: Boolean = false,
        isVpn: Boolean = false
    ): ConnectionHealthReport {
        val hasLiveTraffic = sliSnapshot?.hasFreshLiveTraffic() == true
        val balancerRtt = sliSnapshot?.effectiveBalancerRttMs ?: -1L
        val effectiveSmoothedPing = when {
            smoothedPingMs > 0L -> smoothedPingMs
            hasLiveTraffic && balancerRtt > 0L -> balancerRtt
            hasLiveTraffic -> 140L
            else -> -1L
        }

        val effectiveFailure = when {
            lastFailureType == FailureType.NONE && (sliSnapshot?.delta429Hits ?: 0L) > 0L -> FailureType.RATE_LIMITED_429
            lastFailureType == FailureType.TLS_HANDSHAKE_FAILED && !isTlsEnabled && sliSnapshot != null -> FailureType.DPI_BLOCKED
            else -> lastFailureType
        }

        if (effectiveSmoothedPing <= 0L && successRatePercent <= 0 && !hasLiveTraffic) {
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
                isExcellent = false,
                isCalibrating = false,
                isLiveTrafficActive = false
            )
        }

        val ping = if (effectiveSmoothedPing > 0L) effectiveSmoothedPing else 260L
        val jitter = jitterMs.coerceAtLeast(0L)
        val success = if (hasLiveTraffic) {
            maxOf(successRatePercent.coerceIn(0, 100), sliSnapshot!!.liveSessionSuccessFloorPercent)
        } else {
            successRatePercent.coerceIn(0, 100)
        }

        // 1. Quality calculation for CHATS & MEDIA (TCP/WS/AWG traffic)
        val chatRttDelta = max(0.0, (ping - 30).toDouble())
        val chatRttScore = (100.0 / (1.0 + (chatRttDelta / 650.0).pow(1.40))).coerceIn(5.0, 100.0)

        val chatJitterDelta = max(0.0, (jitter - 5).toDouble())
        val chatJitterScore = (100.0 / (1.0 + (chatJitterDelta / 50.0).pow(1.35))).coerceIn(5.0, 100.0)

        val chatDeliveryFactor = if (success >= 90) 1.0 else (success / 90.0).pow(0.70)
        var chatComposite = ((chatRttScore * 0.25) + (chatJitterScore * 0.25) + (success.toDouble() * 0.50)) * chatDeliveryFactor

        // 2. Quality calculation for CALLS & VIDEO (RTP/UDP Opus, ITU-T G.107 MOS)
        val (mosScore, mosGrade, isCallRecommended) = calculateMos(ping, jitter, success, effectiveFailure)
        val callScore = (((mosScore - 1.0) / 3.5) * 100.0).roundToInt().coerceIn(0, 100)

        // Penalties for failures and blocks (softened if live Telegram sessions continue transferring data cleanly)
        if (effectiveFailure == FailureType.DPI_BLOCKED || effectiveFailure == FailureType.TLS_HANDSHAKE_FAILED) {
            chatComposite -= if (hasLiveTraffic && (sliSnapshot?.deltaWsErrors ?: 0L) == 0L) 15.0 else 35.0
        } else if (effectiveFailure == FailureType.RATE_LIMITED_429) {
            chatComposite -= 25.0
        } else if (effectiveFailure != FailureType.NONE) {
            chatComposite -= if (hasLiveTraffic && (sliSnapshot?.deltaWsErrors ?: 0L) == 0L) 5.0 else 15.0
        }

        if (sliSnapshot != null) {
            if (sliSnapshot.warmTarget > 0 && sliSnapshot.warmReady >= sliSnapshot.warmTarget && effectiveFailure == FailureType.NONE) {
                chatComposite += 2.0
            } else if (sliSnapshot.warmTarget > 0 && sliSnapshot.warmReady == 0 && sliSnapshot.activeConns > 0 && sliSnapshot.deltaWsErrors > 0L) {
                chatComposite -= 6.0
            }
            if (isTlsEnabled && sliSnapshot.deltaTlsFailures > 0L && !hasLiveTraffic) {
                chatComposite -= 12.0
            }
        }
        if (isFailoverActive) {
            chatComposite -= 5.0
        }

        val minFloor = if (hasLiveTraffic && (sliSnapshot?.delta429Hits ?: 0L) == 0L && (sliSnapshot?.deltaWsErrors ?: 0L) == 0L) 60 else 0
        val finalChatScore = chatComposite.roundToInt().coerceIn(minFloor, 100)

        // 3. Overall combined scoring (SOCKS5 Worker accounts for 20% calls; Flowseal/MTProto is 100% chats/media)
        val includeCallWeight = isSocks5 && !isFlowseal
        val finalTotalScore = if (includeCallWeight) {
            ((finalChatScore * 0.80) + (callScore * 0.20)).roundToInt().coerceIn(minFloor, 100)
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

        val optimalDetail = when {
            isVpn -> "Minimal latency and stable WARP AmneziaWG tunnel"
            isFlowseal && !isTlsEnabled -> "Low latency over Flowseal WS :80 Anycast CDN"
            isFlowseal && isTlsEnabled -> "Low latency over Flowseal WSS TLS Anycast CDN"
            !isFlowseal && !isTlsEnabled -> "Low latency over Worker WS :80 tunnel"
            else -> "Minimal latency and stable Worker WSS TLS tunnel"
        }

        val (verdict, detail) = when {
            effectiveFailure == FailureType.RATE_LIMITED_429 -> Pair(
                "Worker Quota 429",
                "Cloudflare Worker daily request quota exceeded"
            )
            effectiveFailure == FailureType.DPI_BLOCKED && !hasLiveTraffic -> Pair(
                "Operator DPI Block",
                "TCP/ClientHello packet reset detected by middlebox"
            )
            effectiveFailure == FailureType.TLS_HANDSHAKE_FAILED && !hasLiveTraffic -> Pair(
                "Secure TLS Failure",
                "TLS handshake aborted or invalid server certificate"
            )
            finalTotalScore >= 90 -> Pair("Optimal Connection", optimalDetail)
            finalTotalScore >= 75 -> Pair(
                "Good Connection",
                if (hasLiveTraffic) "Live session active with minor latency variation" else "Minor latency variation (mobile or Wi-Fi jitter)"
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

        val workerGrade = when (effectiveFailure) {
            FailureType.DPI_BLOCKED -> if (hasLiveTraffic) "Reachable" else "DPI Block"
            FailureType.TLS_HANDSHAKE_FAILED -> if (hasLiveTraffic) "Reachable" else "TLS Failure"
            FailureType.RATE_LIMITED_429 -> "Limit 429"
            FailureType.NONE -> if (ping <= 500L) "Stable" else "Reachable"
            else -> if (hasLiveTraffic) "Reachable" else "Failures"
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
            isExcellent = finalTotalScore >= 90,
            isCalibrating = false,
            isLiveTrafficActive = hasLiveTraffic
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
