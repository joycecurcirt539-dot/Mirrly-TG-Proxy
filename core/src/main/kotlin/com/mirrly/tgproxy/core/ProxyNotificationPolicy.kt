package com.mirrly.tgproxy.core

/** Pure policy for user-facing proxy status. Android callbacks are hints;
 * recent tunnel traffic and successful probes are stronger evidence. */
object ProxyNotificationPolicy {
    private const val RECENT_TRAFFIC_WINDOW_MS = 12_000L
    private const val RECENT_PROBE_WINDOW_MS = 20_000L

    fun isConfirmedOffline(
        serverRunning: Boolean,
        observerDisconnected: Boolean,
        totalBytes: Long,
        lastActivityTimestamp: Long,
        lastProbeTimestamp: Long,
        probeSucceeded: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!serverRunning) return true
        if (!observerDisconnected) return false

        val hasRecentTraffic = totalBytes > 0L &&
            lastActivityTimestamp > 0L &&
            nowMs - lastActivityTimestamp in 0L..RECENT_TRAFFIC_WINDOW_MS
        val hasRecentSuccessfulProbe = probeSucceeded &&
            lastProbeTimestamp > 0L &&
            nowMs - lastProbeTimestamp in 0L..RECENT_PROBE_WINDOW_MS

        return !hasRecentTraffic && !hasRecentSuccessfulProbe
    }

    fun isConfirmedFailover(
        previousStage: Int,
        currentStage: Int,
        configuredPrimaryStage: Int
    ): Boolean = previousStage > 0 &&
        currentStage > 0 &&
        previousStage != currentStage &&
        previousStage == configuredPrimaryStage
}
