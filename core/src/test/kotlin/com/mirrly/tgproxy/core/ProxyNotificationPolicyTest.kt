package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyNotificationPolicyTest {
    private val now = 100_000L

    @Test
    fun `recent tunnel traffic overrides a transient disconnected callback`() {
        assertFalse(
            ProxyNotificationPolicy.isConfirmedOffline(
                serverRunning = true,
                observerDisconnected = true,
                totalBytes = 4096L,
                lastActivityTimestamp = now - 2_000L,
                lastProbeTimestamp = 0L,
                probeSucceeded = false,
                nowMs = now
            )
        )
    }

    @Test
    fun `recent successful probe overrides a transient disconnected callback`() {
        assertFalse(
            ProxyNotificationPolicy.isConfirmedOffline(
                serverRunning = true,
                observerDisconnected = true,
                totalBytes = 0L,
                lastActivityTimestamp = 0L,
                lastProbeTimestamp = now - 5_000L,
                probeSucceeded = true,
                nowMs = now
            )
        )
    }

    @Test
    fun `offline requires disconnected callback without fresh positive evidence`() {
        assertTrue(
            ProxyNotificationPolicy.isConfirmedOffline(
                serverRunning = true,
                observerDisconnected = true,
                totalBytes = 1024L,
                lastActivityTimestamp = now - 30_000L,
                lastProbeTimestamp = now - 30_000L,
                probeSucceeded = false,
                nowMs = now
            )
        )
    }

    @Test
    fun `initial route observation is not a failover`() {
        assertFalse(ProxyNotificationPolicy.isConfirmedFailover(0, 2, 1))
    }

    @Test
    fun `primary to fallback transition is a failover`() {
        assertTrue(ProxyNotificationPolicy.isConfirmedFailover(1, 2, 1))
        assertTrue(ProxyNotificationPolicy.isConfirmedFailover(2, 1, 2))
    }
}
