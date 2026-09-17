/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.core

import kotlin.math.roundToLong

enum class AdaptiveTrafficClass {
    INTERACTIVE_CHAT,
    SUSTAINED_MEDIA
}

enum class AdaptiveNetworkState {
    UNAVAILABLE,
    NORMAL,
    DEGRADED,
    RECOVERING;

    companion object {
        @JvmField val RESPONSIVE = NORMAL
        @JvmField val CONGESTED = DEGRADED
    }
}

/** The only threshold table used by the adaptive network policy. */
data class AdaptivePolicyThresholds(
    val balancedEnterBps: Long = 153_600L,
    val turboEnterBps: Long = 1_572_864L,
    val ultraEnterBps: Long = 6_291_456L,
    val tierExitRatio: Double = 0.80,
    val mediaEnterBps: Long = 1_572_864L,
    val mediaExitBps: Long = 786_432L,
    val mediaEnterSamples: Int = 3,
    val interactiveEnterSamples: Int = 5,
    val congestionEnterRttMs: Long = 350L,
    val congestionExitRttMs: Long = 200L,
    val congestionEnterJitterMs: Long = 60L,
    val congestionExitJitterMs: Long = 30L,
    val congestionEnterBufferbloatMs: Long = 120L,
    val congestionExitBufferbloatMs: Long = 70L,
    val congestionEnterSuccessRate: Int = 75,
    val congestionExitSuccessRate: Int = 90,
    val minimumCandidateDwellMs: Long = 7_000L,
    val transitionCooldownMs: Long = 30_000L,
    val minimumCandidateSamples: Int = 5,
    val recoveringCooldownMs: Long = 30_000L,
    val throughputEmaAlpha: Double = 0.25
) {
    init {
        require(tierExitRatio in 0.1..0.99)
        require(mediaExitBps < mediaEnterBps)
        require(congestionExitRttMs < congestionEnterRttMs)
        require(congestionExitJitterMs < congestionEnterJitterMs)
        require(congestionExitBufferbloatMs < congestionEnterBufferbloatMs)
        require(congestionExitSuccessRate > congestionEnterSuccessRate)
        require(mediaEnterSamples > 0 && interactiveEnterSamples > 0)
        require(minimumCandidateSamples > 0)
        require(throughputEmaAlpha in 0.01..1.0)
    }
}

data class AdaptivePolicySnapshot(
    val observedAtMs: Long,
    val networkProfile: NetworkProfile,
    val throughputBps: Long,
    val smoothedPingMs: Long,
    val minRttMs: Long,
    val jitterMs: Long,
    val successRatePercent: Int,
    val consecutiveFailures: Int = 0,
    val mosScore: Double = 4.50,
    val isCallRecommended: Boolean = true,
    val qosThrottleLevel: QoSThrottleLevel = QoSThrottleLevel.NONE,
    val isAutoSpeedPreset: Boolean = true,
    val tcpNoDelayMode: TcpNoDelayMode = TcpNoDelayMode.AUTO,
    val configuredPoolSize: Int = 2,
    val configuredBufferSizeBytes: Int = 262_144,
    val configuredTcpNoDelay: Boolean = true,
    val probeId: Long = observedAtMs
)

data class NetworkEvaluationDecision(
    val recommendedPoolSize: Int,
    val recommendedBufferSizeBytes: Int,
    val recommendedTcpNoDelay: Boolean,
    val connectionQuality: ConnectionQuality,
    val trafficClass: AdaptiveTrafficClass,
    val networkState: AdaptiveNetworkState,
    val isInteractiveVoipActive: Boolean,
    val isBufferbloatMitigationActive: Boolean,
    val statusDescription: String,
    val detailReason: String,
    val summary: String,
    val revision: Long,
    val recommendedHappyEyeballsDelayMs: Long = 200L
)

/**
 * Sole owner of adaptive pool, buffer and TCP_NODELAY decisions.
 *
 * Callers submit observations and consume [currentDecision]. They never run a
 * second evaluator. Traffic classification, threshold hysteresis, candidate
 * dwell and transition cooldown all live here.
 */
class AdaptiveNetworkPolicyController(
    val thresholds: AdaptivePolicyThresholds = AdaptivePolicyThresholds(),
    initialPoolSize: Int = 2,
    initialBufferSizeBytes: Int = BUFFER_SIZE_BALANCED,
    initialTcpNoDelay: Boolean = true,
    private val stabilizationGate: NetworkStabilizationGate? = null
) {
    private data class EffectiveSignature(
        val poolSize: Int,
        val bufferSizeBytes: Int,
        val tcpNoDelay: Boolean,
        val trafficClass: AdaptiveTrafficClass,
        val networkState: AdaptiveNetworkState,
        val happyEyeballsDelayMs: Long
    )

    @Volatile
    var currentDecision: NetworkEvaluationDecision = initialDecision(
        poolSize = initialPoolSize,
        bufferSizeBytes = initialBufferSizeBytes,
        tcpNoDelay = initialTcpNoDelay
    )
        private set

    private var hasAcceptedObservation = false
    private var throughputEma = 0.0
    private var hasThroughputSample = false
    private var trafficClass = AdaptiveTrafficClass.INTERACTIVE_CHAT
    private var mediaEnterCount = 0
    private var interactiveEnterCount = 0
    private var pendingSignature: EffectiveSignature? = null
    private var pendingSinceMs = 0L
    private var pendingSampleCount = 0
    private var lastTransitionAtMs = Long.MIN_VALUE
    private var lastObservedAtMs = Long.MIN_VALUE
    private var lastProbeId = Long.MIN_VALUE
    private var lastFreshProbeAtMs = Long.MIN_VALUE

    /** Endpoint changes start a recovery period without changing any socket options. */
    @Synchronized
    fun beginRecovery(observedAtMs: Long) {
        pendingSignature = null
        pendingSampleCount = 0
        lastTransitionAtMs = observedAtMs
        if (hasAcceptedObservation && currentDecision.networkState != AdaptiveNetworkState.UNAVAILABLE) {
            currentDecision = currentDecision.copy(
                networkState = AdaptiveNetworkState.RECOVERING,
                revision = currentDecision.revision + 1L
            )
        }
    }

    @Synchronized
    fun reset(
        poolSize: Int,
        bufferSizeBytes: Int,
        tcpNoDelay: Boolean
    ): NetworkEvaluationDecision {
        hasAcceptedObservation = false
        throughputEma = 0.0
        hasThroughputSample = false
        trafficClass = AdaptiveTrafficClass.INTERACTIVE_CHAT
        mediaEnterCount = 0
        interactiveEnterCount = 0
        pendingSignature = null
        pendingSinceMs = 0L
        pendingSampleCount = 0
        lastTransitionAtMs = Long.MIN_VALUE
        lastObservedAtMs = Long.MIN_VALUE
        lastProbeId = Long.MIN_VALUE
        lastFreshProbeAtMs = Long.MIN_VALUE
        currentDecision = initialDecision(poolSize, bufferSizeBytes, tcpNoDelay)
        return currentDecision
    }

    @Synchronized
    fun evaluate(snapshot: AdaptivePolicySnapshot, force: Boolean = false): NetworkEvaluationDecision {
        if (!force && snapshot.observedAtMs <= lastObservedAtMs) {
            return currentDecision
        }
        if (lastObservedAtMs != Long.MIN_VALUE && snapshot.observedAtMs - lastObservedAtMs > 20_000L) {
            pendingSignature = null
            pendingSampleCount = 0
        }
        lastObservedAtMs = maxOf(lastObservedAtMs, snapshot.observedAtMs)
        val freshProbe = snapshot.probeId != lastProbeId
        if (lastFreshProbeAtMs != Long.MIN_VALUE && snapshot.observedAtMs - lastFreshProbeAtMs > 20_000L) {
            pendingSignature = null
            pendingSampleCount = 0
        }
        if (freshProbe) lastFreshProbeAtMs = snapshot.observedAtMs
        lastProbeId = snapshot.probeId

        updateThroughputEma(snapshot.throughputBps)
        updateTrafficClass()

        val candidate = buildCandidate(snapshot)
        val candidateSignature = candidate.signature()
        val currentSignature = currentDecision.signature()
        val availabilityBoundary = candidate.networkState != currentDecision.networkState &&
            (candidate.networkState == AdaptiveNetworkState.UNAVAILABLE ||
                currentDecision.networkState == AdaptiveNetworkState.UNAVAILABLE)

        if (!hasAcceptedObservation || force || availabilityBoundary) {
            // Link availability must be reported promptly, but it must not toggle
            // socket options on every suspend/resume callback.
            val accepted = if (availabilityBoundary && hasAcceptedObservation && !force) {
                candidate.copy(
                    recommendedPoolSize = currentDecision.recommendedPoolSize,
                    recommendedBufferSizeBytes = currentDecision.recommendedBufferSizeBytes,
                    recommendedTcpNoDelay = currentDecision.recommendedTcpNoDelay,
                    recommendedHappyEyeballsDelayMs = currentDecision.recommendedHappyEyeballsDelayMs
                )
            } else candidate
            if (!hasAcceptedObservation || force) stabilizationGate?.markChanged()
            return accept(accepted, snapshot.observedAtMs)
        }

        if (candidateSignature == currentSignature) {
            pendingSignature = null
            pendingSampleCount = 0
            currentDecision = candidate.copy(revision = currentDecision.revision)
            return currentDecision
        }

        if (pendingSignature != candidateSignature) {
            pendingSignature = candidateSignature
            pendingSinceMs = snapshot.observedAtMs
            pendingSampleCount = 1
            return currentDecision
        }

        pendingSampleCount++
        val candidateMature = pendingSampleCount >= thresholds.minimumCandidateSamples &&
            snapshot.observedAtMs - pendingSinceMs >= thresholds.minimumCandidateDwellMs
        val cooldownExpired = lastTransitionAtMs == Long.MIN_VALUE ||
            snapshot.observedAtMs - lastTransitionAtMs >= thresholds.transitionCooldownMs

        return if (candidateMature && cooldownExpired &&
            (candidate.networkState == currentDecision.networkState || freshProbe)) {
            if (stabilizationGate == null) {
                accept(candidate, snapshot.observedAtMs)
            } else {
                stabilizationGate.tryChange { accept(candidate, snapshot.observedAtMs); true }
                currentDecision
            }
        } else {
            currentDecision
        }
    }

    private fun updateThroughputEma(throughputBps: Long) {
        val sample = throughputBps.coerceAtLeast(0L).toDouble()
        throughputEma = if (!hasThroughputSample) {
            hasThroughputSample = true
            sample
        } else {
            thresholds.throughputEmaAlpha * sample +
                (1.0 - thresholds.throughputEmaAlpha) * throughputEma
        }
    }

    private fun updateTrafficClass() {
        when (trafficClass) {
            AdaptiveTrafficClass.INTERACTIVE_CHAT -> {
                if (throughputEma >= thresholds.mediaEnterBps) {
                    mediaEnterCount++
                    if (mediaEnterCount >= thresholds.mediaEnterSamples) {
                        trafficClass = AdaptiveTrafficClass.SUSTAINED_MEDIA
                        mediaEnterCount = 0
                    }
                } else {
                    mediaEnterCount = 0
                }
                interactiveEnterCount = 0
            }

            AdaptiveTrafficClass.SUSTAINED_MEDIA -> {
                if (throughputEma <= thresholds.mediaExitBps) {
                    interactiveEnterCount++
                    if (interactiveEnterCount >= thresholds.interactiveEnterSamples) {
                        trafficClass = AdaptiveTrafficClass.INTERACTIVE_CHAT
                        interactiveEnterCount = 0
                    }
                } else {
                    interactiveEnterCount = 0
                }
                mediaEnterCount = 0
            }
        }
    }

    private fun computeCandidateNetworkState(snapshot: AdaptivePolicySnapshot): AdaptiveNetworkState {
        val bufferbloatMs = bufferbloat(snapshot)
        val profile = snapshot.networkProfile
        val unavailable = !profile.validated || profile.suspended || profile.transport == NetworkTransport.NONE

        if (unavailable) {
            return AdaptiveNetworkState.UNAVAILABLE
        }

        val hasDegradation = profile.congested ||
            snapshot.consecutiveFailures >= 2 ||
            snapshot.smoothedPingMs >= thresholds.congestionEnterRttMs ||
            snapshot.jitterMs >= thresholds.congestionEnterJitterMs ||
            bufferbloatMs >= thresholds.congestionEnterBufferbloatMs ||
            snapshot.successRatePercent in 0 until thresholds.congestionEnterSuccessRate

        val latencyRecovered = snapshot.smoothedPingMs <= 0L ||
            snapshot.smoothedPingMs <= thresholds.congestionExitRttMs
        val isClean = !profile.congested &&
            snapshot.consecutiveFailures == 0 &&
            latencyRecovered &&
            snapshot.jitterMs <= thresholds.congestionExitJitterMs &&
            bufferbloatMs <= thresholds.congestionExitBufferbloatMs &&
            snapshot.successRatePercent >= thresholds.congestionExitSuccessRate

        return when (currentDecision.networkState) {
            AdaptiveNetworkState.UNAVAILABLE -> {
                if (hasAcceptedObservation) AdaptiveNetworkState.RECOVERING else AdaptiveNetworkState.NORMAL
            }
            AdaptiveNetworkState.NORMAL -> {
                if (hasDegradation) AdaptiveNetworkState.DEGRADED else AdaptiveNetworkState.NORMAL
            }
            AdaptiveNetworkState.DEGRADED -> {
                if (isClean) AdaptiveNetworkState.RECOVERING else AdaptiveNetworkState.DEGRADED
            }
            AdaptiveNetworkState.RECOVERING -> {
                if (hasDegradation) {
                    AdaptiveNetworkState.DEGRADED
                } else if (isClean && (snapshot.observedAtMs - lastTransitionAtMs >= thresholds.recoveringCooldownMs)) {
                    AdaptiveNetworkState.NORMAL
                } else {
                    AdaptiveNetworkState.RECOVERING
                }
            }
        }
    }

    private fun buildCandidate(snapshot: AdaptivePolicySnapshot): NetworkEvaluationDecision {
        val state = computeCandidateNetworkState(snapshot)
        val quality = NetworkQualityClassifier.evaluate(
            smoothedPingMs = snapshot.smoothedPingMs,
            jitterMs = snapshot.jitterMs,
            consecutiveFailures = snapshot.consecutiveFailures,
            successRatePercent = snapshot.successRatePercent
        )

        val rawPool = if (!snapshot.isAutoSpeedPreset) {
            snapshot.configuredPoolSize
        } else if (state == AdaptiveNetworkState.DEGRADED || state == AdaptiveNetworkState.UNAVAILABLE) {
            1
        } else if (state == AdaptiveNetworkState.RECOVERING) {
            currentDecision.recommendedPoolSize.coerceIn(1, 2)
        } else if (trafficClass == AdaptiveTrafficClass.INTERACTIVE_CHAT) {
            val balancedExit =
                (thresholds.balancedEnterBps * thresholds.tierExitRatio).roundToLong()
            if (throughputEma >= thresholds.balancedEnterBps ||
                (currentDecision.recommendedPoolSize >= 2 && throughputEma >= balancedExit)
            ) {
                2
            } else {
                1
            }
        } else {
            selectPoolWithHysteresis(currentDecision.recommendedPoolSize, throughputEma.roundToLong())
        }
        val targetPool = rawPool.coerceIn(1, 4).coerceAtMost(snapshot.qosThrottleLevel.maxPoolSize)
        val rawBuffer = if (!snapshot.isAutoSpeedPreset) {
            snapshot.configuredBufferSizeBytes
        } else if (state == AdaptiveNetworkState.DEGRADED || state == AdaptiveNetworkState.UNAVAILABLE) {
            BUFFER_SIZE_ECO
        } else if (state == AdaptiveNetworkState.RECOVERING) {
            BUFFER_SIZE_BALANCED
        } else if (throughputEma > 0.0 && snapshot.smoothedPingMs > 0L) {
            evaluateBdpBuffer(
                poolSize = targetPool,
                throughputBps = throughputEma.roundToLong(),
                rttMs = snapshot.smoothedPingMs,
                isCongested = false
            )
        } else {
            bufferForPool(targetPool)
        }
        val targetBuffer = if (rawBuffer == BUFFER_SIZE_AUTOTUNE) {
            BUFFER_SIZE_AUTOTUNE
        } else {
            rawBuffer.coerceAtMost(snapshot.qosThrottleLevel.maxBufferSizeBytes)
        }

        val tcpNoDelay = when (snapshot.tcpNoDelayMode) {
            TcpNoDelayMode.ON -> true
            TcpNoDelayMode.OFF -> false
            TcpNoDelayMode.AUTO -> (state == AdaptiveNetworkState.NORMAL || state == AdaptiveNetworkState.RECOVERING) &&
                trafficClass == AdaptiveTrafficClass.INTERACTIVE_CHAT
        }
        val status = when (snapshot.tcpNoDelayMode) {
            TcpNoDelayMode.ON -> "On · Instant delivery"
            TcpNoDelayMode.OFF -> "Off · Packet coalescing"
            TcpNoDelayMode.AUTO -> when (state) {
                AdaptiveNetworkState.UNAVAILABLE -> "Auto · Network not ready · Coalescing"
                AdaptiveNetworkState.DEGRADED -> "Auto · Congestion protection · Coalescing"
                AdaptiveNetworkState.RECOVERING -> "Auto · Stabilizing channel · Instant"
                AdaptiveNetworkState.NORMAL -> if (trafficClass == AdaptiveTrafficClass.SUSTAINED_MEDIA) {
                    "Auto · Media stream · Coalescing"
                } else {
                    "Auto · Chats & calls · Instant"
                }
            }
        }
        val detail = when (state) {
            AdaptiveNetworkState.UNAVAILABLE -> "Waiting for VALIDATED/NOT_SUSPENDED network generation"
            AdaptiveNetworkState.DEGRADED -> {
                if (snapshot.networkProfile.congested) {
                    "Android reported NET_CAPABILITY_NOT_CONGESTED=false"
                } else if (snapshot.smoothedPingMs >= thresholds.congestionEnterRttMs) {
                    "RTT ${snapshot.smoothedPingMs} ms; enter threshold >= ${thresholds.congestionEnterRttMs} ms, exit <= ${thresholds.congestionExitRttMs} ms"
                } else if (snapshot.jitterMs >= thresholds.congestionEnterJitterMs) {
                    "Jitter ${snapshot.jitterMs} ms; enter threshold >= ${thresholds.congestionEnterJitterMs} ms, exit <= ${thresholds.congestionExitJitterMs} ms"
                } else if (bufferbloat(snapshot) >= thresholds.congestionEnterBufferbloatMs) {
                    "Bufferbloat ${bufferbloat(snapshot)} ms; enter threshold >= ${thresholds.congestionEnterBufferbloatMs} ms, exit <= ${thresholds.congestionExitBufferbloatMs} ms"
                } else {
                    "Confirmed degradation: packet loss or socket errors"
                }
            }
            AdaptiveNetworkState.RECOVERING -> "Stabilizing network channel after degradation (${thresholds.recoveringCooldownMs / 1000}s cool-down)"
            AdaptiveNetworkState.NORMAL -> {
                if (trafficClass == AdaptiveTrafficClass.SUSTAINED_MEDIA) {
                    "Sustained media stream confirmed by ${thresholds.mediaEnterSamples} consecutive EMA samples"
                } else {
                    "Interactive profile prioritizing small messages"
                }
            }
        }
        val summary = when (state) {
            AdaptiveNetworkState.UNAVAILABLE -> "Waiting for network ready"
            AdaptiveNetworkState.DEGRADED -> "Congestion protection (Eco / $targetPool sockets)"
            AdaptiveNetworkState.RECOVERING -> "Recovering channel ($targetPool standby/slot)"
            AdaptiveNetworkState.NORMAL -> when {
                targetPool >= 4 -> "Ultra mode (4 standby/slot, 2 MB buffer)"
                targetPool >= 3 -> "Turbo mode (3 standby/slot, 1 MB buffer)"
                targetPool >= 2 -> "Balanced mode (2 standby/slot, 256 KB buffer)"
                else -> "Eco mode (1 standby/slot, 128 KB buffer)"
            }
        }

        return NetworkEvaluationDecision(
            recommendedPoolSize = targetPool,
            recommendedBufferSizeBytes = targetBuffer,
            recommendedTcpNoDelay = tcpNoDelay,
            connectionQuality = quality,
            trafficClass = trafficClass,
            networkState = state,
            isInteractiveVoipActive = trafficClass == AdaptiveTrafficClass.INTERACTIVE_CHAT &&
                snapshot.isCallRecommended && snapshot.mosScore >= 3.80,
            isBufferbloatMitigationActive = state == AdaptiveNetworkState.DEGRADED,
            statusDescription = status,
            detailReason = detail,
            summary = summary,
            revision = currentDecision.revision,
            // Discrete delay belongs to the same committed policy as TCP_NODELAY.
            // Rust must not retune it independently from every RTT observation.
            recommendedHappyEyeballsDelayMs = when (state) {
                AdaptiveNetworkState.UNAVAILABLE, AdaptiveNetworkState.RECOVERING ->
                    currentDecision.recommendedHappyEyeballsDelayMs
                AdaptiveNetworkState.DEGRADED -> if (snapshot.networkProfile.isMobile) 700L else 400L
                AdaptiveNetworkState.NORMAL -> if (snapshot.networkProfile.isMobile) 350L else 200L
            }
        )
    }

    private fun selectPoolWithHysteresis(currentPool: Int, throughputBps: Long): Int {
        val balancedExit = (thresholds.balancedEnterBps * thresholds.tierExitRatio).roundToLong()
        val turboExit = (thresholds.turboEnterBps * thresholds.tierExitRatio).roundToLong()
        val ultraExit = (thresholds.ultraEnterBps * thresholds.tierExitRatio).roundToLong()
        return when {
            throughputBps >= thresholds.ultraEnterBps -> 4
            currentPool >= 4 && throughputBps >= ultraExit -> 4
            throughputBps >= thresholds.turboEnterBps -> 3
            currentPool >= 3 && throughputBps >= turboExit -> 3
            throughputBps >= thresholds.balancedEnterBps -> 2
            currentPool >= 2 && throughputBps >= balancedExit -> 2
            else -> 1
        }
    }

    private fun accept(candidate: NetworkEvaluationDecision, observedAtMs: Long): NetworkEvaluationDecision {
        hasAcceptedObservation = true
        pendingSignature = null
        pendingSampleCount = 0
        lastTransitionAtMs = observedAtMs
        currentDecision = candidate.copy(revision = currentDecision.revision + 1L)
        return currentDecision
    }

    private fun NetworkEvaluationDecision.signature() = EffectiveSignature(
        poolSize = recommendedPoolSize,
        bufferSizeBytes = recommendedBufferSizeBytes,
        tcpNoDelay = recommendedTcpNoDelay,
        trafficClass = trafficClass,
        networkState = networkState,
        happyEyeballsDelayMs = recommendedHappyEyeballsDelayMs
    )

    companion object {
        const val BUFFER_SIZE_AUTOTUNE = 0
        const val BUFFER_SIZE_ULTRA = 2_097_152
        const val BUFFER_SIZE_TURBO = 1_048_576
        const val BUFFER_SIZE_BALANCED = 262_144
        const val BUFFER_SIZE_ECO = 131_072

        fun calculateBdpBytes(throughputBps: Long, rttMs: Long): Long {
            if (throughputBps <= 0L || rttMs <= 0L) return 0L
            return (throughputBps * rttMs) / 8000L
        }

        fun evaluateBdpBuffer(
            poolSize: Int,
            throughputBps: Long,
            rttMs: Long,
            isCongested: Boolean
        ): Int {
            if (isCongested) return BUFFER_SIZE_ECO
            val bdp = calculateBdpBytes(throughputBps, rttMs)
            if (bdp <= 0L) return bufferForPool(poolSize)
            // Headroom of 2x BDP is standard for TCP to avoid window stalls
            val bdpHeadroom = bdp * 2L
            return when {
                poolSize >= 4 && bdpHeadroom >= 1_500_000L -> BUFFER_SIZE_ULTRA
                poolSize >= 3 && bdpHeadroom >= 600_000L -> BUFFER_SIZE_TURBO
                poolSize >= 2 && bdpHeadroom >= 150_000L -> BUFFER_SIZE_BALANCED
                else -> BUFFER_SIZE_ECO
            }
        }

        fun bufferForPool(poolSize: Int): Int = when {
            poolSize >= 4 -> BUFFER_SIZE_ULTRA
            poolSize >= 3 -> BUFFER_SIZE_TURBO
            poolSize >= 2 -> BUFFER_SIZE_BALANCED
            else -> BUFFER_SIZE_ECO
        }

        private fun initialDecision(
            poolSize: Int,
            bufferSizeBytes: Int,
            tcpNoDelay: Boolean
        ) = NetworkEvaluationDecision(
            recommendedPoolSize = poolSize.coerceIn(1, 4),
            recommendedBufferSizeBytes = bufferSizeBytes,
            recommendedTcpNoDelay = tcpNoDelay,
            connectionQuality = ConnectionQuality.OFFLINE,
            trafficClass = AdaptiveTrafficClass.INTERACTIVE_CHAT,
            networkState = AdaptiveNetworkState.UNAVAILABLE,
            isInteractiveVoipActive = false,
            isBufferbloatMitigationActive = false,
            statusDescription = "Auto · Awaiting measurements",
            detailReason = "Controller has not received first network snapshot yet",
            summary = "Adaptive policy initialization",
            revision = 0L
        )

        private fun bufferbloat(snapshot: AdaptivePolicySnapshot): Long =
            if (snapshot.smoothedPingMs > 0L && snapshot.minRttMs > 0L) {
                maxOf(0L, snapshot.smoothedPingMs - snapshot.minRttMs)
            } else {
                0L
            }
    }
}

/** Quality classification is diagnostic only; it never applies socket policy. */
object NetworkQualityClassifier {
    fun evaluate(
        smoothedPingMs: Long,
        jitterMs: Long,
        consecutiveFailures: Int,
        successRatePercent: Int
    ): ConnectionQuality {
        if (consecutiveFailures >= 3 || smoothedPingMs <= 0L || successRatePercent < 40) {
            return ConnectionQuality.OFFLINE
        }
        return when {
            smoothedPingMs <= 80L && jitterMs <= 25L && successRatePercent >= 90 -> ConnectionQuality.EXCELLENT
            smoothedPingMs <= 200L && jitterMs <= 60L && successRatePercent >= 80 -> ConnectionQuality.GOOD
            smoothedPingMs <= 750L && successRatePercent >= 60 -> ConnectionQuality.MODERATE
            else -> ConnectionQuality.POOR
        }
    }
}

object NetworkConditionEvaluator {
    fun evaluate(
        throughputBps: Long,
        smoothedPingMs: Long,
        minRttMs: Long,
        jitterMs: Long,
        successRatePercent: Int,
        consecutiveFailures: Int = 0,
        mosScore: Double = 4.50,
        isCallRecommended: Boolean = true,
        qosThrottleLevel: QoSThrottleLevel = QoSThrottleLevel.NONE,
        isAutoSpeedPreset: Boolean = true,
        baseTcpNoDelay: Boolean = true
    ): NetworkEvaluationDecision {
        val controller = AdaptiveNetworkPolicyController()
        return controller.evaluate(
            AdaptivePolicySnapshot(
                observedAtMs = System.currentTimeMillis(),
                networkProfile = NetworkProfile(validated = true, suspended = false),
                throughputBps = throughputBps,
                smoothedPingMs = smoothedPingMs,
                minRttMs = minRttMs,
                jitterMs = jitterMs,
                successRatePercent = successRatePercent,
                consecutiveFailures = consecutiveFailures,
                mosScore = mosScore,
                isCallRecommended = isCallRecommended,
                qosThrottleLevel = qosThrottleLevel,
                isAutoSpeedPreset = isAutoSpeedPreset,
                configuredTcpNoDelay = baseTcpNoDelay
            ),
            force = true
        )
    }
}
