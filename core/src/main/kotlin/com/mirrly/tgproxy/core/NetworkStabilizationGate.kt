package com.mirrly.tgproxy.core

/** Shared by automatic socket policy and endpoint selection. Uses elapsed, not wall time. */
class NetworkStabilizationGate(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val cooldownMs: Long = 30_000L
) {
    private var lastChangeMs: Long? = null

    @Synchronized
    fun tryChange(change: () -> Boolean): Boolean {
        val now = clock()
        if (lastChangeMs?.let { now - it < cooldownMs } == true) return false
        if (!change()) return false
        lastChangeMs = clock()
        return true
    }

    @Synchronized
    fun markChanged() { lastChangeMs = clock() }
}

/** A successful probe interrupts a failure window; duplicate/stale probes cannot mature it. */
class WorkerSwitchPolicy {
    private var source: String? = null
    private var generation = -1L
    private var badSinceMs: Long? = null
    private var lastObservationMs: Long? = null
    private var samples = 0
    private val recentlyLeft = mutableMapOf<String, Long>()

    @Synchronized
    fun observe(workerId: String, networkGeneration: Long, degraded: Boolean, nowMs: Long): Boolean {
        if (source != workerId || generation != networkGeneration) {
            source = workerId
            generation = networkGeneration
            badSinceMs = null
            lastObservationMs = null
            samples = 0
        }
        if (lastObservationMs?.let { nowMs <= it } == true) return false
        // A long silence is not evidence of continuous degradation.
        if (lastObservationMs?.let { nowMs - it > 20_000L } == true) {
            badSinceMs = null
            samples = 0
        }
        lastObservationMs = nowMs
        if (!degraded) {
            badSinceMs = null
            samples = 0
            return false
        }
        if (badSinceMs == null) badSinceMs = nowMs
        samples++
        return samples >= 2 && nowMs - badSinceMs!! >= 7_000L
    }

    @Synchronized
    fun canSelect(workerId: String, nowMs: Long): Boolean =
        recentlyLeft[workerId]?.let { nowMs - it >= 60_000L } ?: true

    @Synchronized
    fun switched(fromWorkerId: String, nowMs: Long) {
        recentlyLeft.entries.removeAll { nowMs - it.value >= 60_000L }
        recentlyLeft[fromWorkerId] = nowMs
        source = null
        badSinceMs = null
        lastObservationMs = null
        samples = 0
    }
}
