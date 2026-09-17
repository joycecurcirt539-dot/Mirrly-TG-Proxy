package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProxyDisplayLabelsTest {
    @Test
    fun `worker telemetry is collapsed for notification`() {
        assertEquals(
            "Cloudflare WSS",
            ProxyDisplayLabels.notificationRouteLabel(
                effectiveRoute = "Cloudflare Worker WSS (Публичный релей)",
                operator = "Cloudflare Worker",
                fallbackLabel = "unused"
            )
        )
    }

    @Test
    fun `worker telemetry is collapsed for home status`() {
        assertEquals(
            "Cloudflare WSS · Защищено",
            ProxyDisplayLabels.homeStatusLabel(
                effectiveRoute = "Cloudflare Worker WSS (Публичный релей)",
                operator = "Cloudflare Worker",
                isTrustBoundaryMaintained = false
            )
        )
    }

    @Test
    fun `configured worker uses short label before telemetry arrives`() {
        assertEquals(
            "Cloudflare WSS · Защищено",
            ProxyDisplayLabels.homeStatusLabel(
                effectiveRoute = "",
                operator = "",
                isTrustBoundaryMaintained = true,
                configuredWorker = true
            )
        )
    }

    @Test
    fun `other routes retain operator and trust state`() {
        assertEquals(
            "VLESS (Частный VPS) (Private VPS) · Публичный резерв",
            ProxyDisplayLabels.homeStatusLabel(
                effectiveRoute = "VLESS (Частный VPS)",
                operator = "Private VPS",
                isTrustBoundaryMaintained = false
            )
        )
    }
}
