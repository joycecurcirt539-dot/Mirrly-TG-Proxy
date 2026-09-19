/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service.vpn

import com.mirrly.tgproxy.ui.theme.VpnUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnStateTest {

    @Test
    fun testInternalStateToUiStateMapping() {
        assertEquals(VpnUiState.DISCONNECTED, VpnInternalState.IDLE.toUiState())
        assertEquals(VpnUiState.CONNECTING, VpnInternalState.AWAITING_PERMISSION.toUiState())
        assertEquals(VpnUiState.CONNECTING, VpnInternalState.PREPARING.toUiState())
        assertEquals(VpnUiState.CONNECTING, VpnInternalState.CONNECTING.toUiState())
        assertEquals(VpnUiState.CONNECTING, VpnInternalState.VERIFYING.toUiState())
        assertEquals(VpnUiState.CONNECTED, VpnInternalState.RUNNING.toUiState())
        assertEquals(VpnUiState.CONNECTING, VpnInternalState.RECONNECTING.toUiState())
        assertEquals(VpnUiState.DISCONNECTING, VpnInternalState.STOPPING.toUiState())
        assertEquals(VpnUiState.DISCONNECTED, VpnInternalState.FAILED.toUiState())
    }

    @Test
    fun testVpnStatusProperties() {
        val status = VpnStatus(
            internalState = VpnInternalState.RUNNING,
            uiState = VpnUiState.CONNECTED,
            failureReason = VpnFailureReason.NONE,
            generation = 5L,
            activeProfileName = "Cloudflare WARP+",
            uplinkMode = "WARP",
            bytesIn = 1048576L,
            bytesOut = 524288L,
            activeTcpFlows = 12,
            activeUdpSessions = 4,
            uptimeMs = 15000L
        )

        assertTrue(status.isRunning)
        assertFalse(status.isConnecting)
        assertEquals(5L, status.generation)
        assertEquals("Cloudflare WARP+", status.activeProfileName)
        assertEquals(12, status.activeTcpFlows)
        assertEquals(4, status.activeUdpSessions)
    }

    @Test
    fun testFailureReasonMessages() {
        assertEquals("", VpnFailureReason.NONE.getDisplayMessage())
        assertTrue(VpnFailureReason.PERMISSION_DENIED.getDisplayMessage().contains("отклонил"))
        assertTrue(VpnFailureReason.REVOKED.getDisplayMessage().contains("отозвано"))
        assertTrue(VpnFailureReason.ESTABLISH_FAILED.getDisplayMessage().contains("TUN"))
        assertTrue(VpnFailureReason.PROFILE_INVALID.getDisplayMessage().contains("Конфигурация"))
        assertTrue(VpnFailureReason.NETWORK_UNAVAILABLE.getDisplayMessage().contains("недоступна"))
        assertTrue(VpnFailureReason.TIMEOUT.getDisplayMessage().contains("Таймаут") || VpnFailureReason.TIMEOUT.getDisplayMessage().contains("таймаут"))
    }
}
