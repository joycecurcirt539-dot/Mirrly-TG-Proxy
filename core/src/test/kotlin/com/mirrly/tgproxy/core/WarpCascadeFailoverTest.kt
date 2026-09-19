package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WarpCascadeFailoverTest {

    @Test
    fun testTwoChannelWarpModesInProxyConfig() {
        val masqueConfig = ProxyConfig(vpnUplinkModeName = UplinkMode.MASQUE.name)
        assertTrue(masqueConfig.isVpnMasqueUplink)
        assertTrue(masqueConfig.isVpnAnyWarpUplink)
        assertEquals("WARP MASQUE (HTTP/3)", masqueConfig.vpnUplinkMode.displayName)

        val awgConfig = ProxyConfig(vpnUplinkModeName = UplinkMode.AWG.name)
        assertTrue(awgConfig.isVpnAwgUplink)
        assertTrue(awgConfig.isVpnAnyWarpUplink)
        assertEquals("WARP AmneziaWG (AWG)", awgConfig.vpnUplinkMode.displayName)

        // Backward-compatible alias for WARP_CASCADE
        val cascadeConfig = ProxyConfig(vpnUplinkModeName = UplinkMode.WARP_CASCADE.name)
        assertTrue(cascadeConfig.isVpnWarpCascadeUplink)
        assertTrue(cascadeConfig.isVpnAnyWarpUplink)
    }

    @Test
    fun testAmneziaWgConfigGenerationForBothWarpModes() {
        val config = ProxyConfig(
            warpClientIpv4 = "172.16.0.2",
            warpClientIpv6 = "2606:4700:110:873b:c746:58eb:dc33:a59c",
            warpPrivateKey = "mK6QcWc40yC+iO49p8P+o/jN8bVbL9e2b1R0W3y4Q0E=",
            warpPeerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            warpPeerEndpoint = "188.114.96.1:500"
        )

        val awgIni = config.getAmneziaWgConfig(cleanEndpoint = config.warpPeerEndpoint)
        assertTrue(awgIni.contains("[Interface]"))
        assertTrue(awgIni.contains("PrivateKey = mK6QcWc40yC+iO49p8P+o/jN8bVbL9e2b1R0W3y4Q0E="))
        assertTrue(awgIni.contains("Address = 172.16.0.2/32, 2606:4700:110:873b:c746:58eb:dc33:a59c/128"))
        assertTrue(awgIni.contains("Jc = 4"))
        assertTrue(awgIni.contains("Jmin = 40"))
        assertTrue(awgIni.contains("Jmax = 80"))
        assertTrue(awgIni.contains("[Peer]"))
        assertTrue(awgIni.contains("Endpoint = 188.114.96.1:500"))
        assertTrue(awgIni.contains("PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="))
    }

    @Test
    fun testCascadeStageCodeMappingInProxyStats() {
        val stats = ProxyStats()

        // 1 = MASQUE
        stats.activeCascadeStageCode = 1
        stats.activeCascadeStage = when (stats.activeCascadeStageCode) {
            1 -> "WARP MASQUE"
            2 -> "WARP AmneziaWG"
            else -> "Unknown"
        }
        assertEquals("WARP MASQUE", stats.activeCascadeStage)

        // 2 = AWG (Failover from MASQUE when QUIC is blocked)
        stats.activeCascadeStageCode = 2
        stats.activeCascadeStage = when (stats.activeCascadeStageCode) {
            1 -> "WARP MASQUE"
            2 -> "WARP AmneziaWG"
            else -> "Unknown"
        }
        assertEquals("WARP AmneziaWG", stats.activeCascadeStage)
    }
}
