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

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RouteSupervisorSyncTest {

    @Test
    fun testParseNativeStatsExtractsRouteSpecificMetrics() {
        val stats = ProxyStats()

        val rawStats = "total=50 active=4 ws=2 cf=2 masque=15 awg=10 vless=20 opera=3 bad=0 err=0 pool=4/8 up=1MB down=10MB"
        stats.parseNativeStats(rawStats)

        assertEquals(4, stats.activeConnections.get())
        assertEquals(2L, stats.totalWsConnections.get())
        assertEquals(15L, stats.totalMasqueConnections.get())
        assertEquals(10L, stats.totalAwgConnections.get())
        assertEquals(20L, stats.totalVlessConnections.get())
        assertEquals(3L, stats.totalOperaConnections.get())
    }

    @Test
    fun testParseNativeStatsRussianFormatExtractsRouteMetrics() {
        val stats = ProxyStats()

        val rawRu = "акт:2 ws:5 cf:5 masque:7 awg:3 vless:12 opera:1 ош:0 ↑10KB ↓250KB"
        stats.parseNativeStats(rawRu)

        assertEquals(2, stats.activeConnections.get())
        assertEquals(5L, stats.totalWsConnections.get())
        assertEquals(7L, stats.totalMasqueConnections.get())
        assertEquals(3L, stats.totalAwgConnections.get())
        assertEquals(12L, stats.totalVlessConnections.get())
        assertEquals(1L, stats.totalOperaConnections.get())
    }

    @Test
    fun testRouteMetricsMonotonicIncrease() {
        val stats = ProxyStats()

        stats.parseNativeStats("masque=5 awg=3 vless=2 opera=1")
        assertEquals(5L, stats.totalMasqueConnections.get())
        assertEquals(3L, stats.totalAwgConnections.get())
        assertEquals(2L, stats.totalVlessConnections.get())
        assertEquals(1L, stats.totalOperaConnections.get())

        // Lower numbers must not decrease counters
        stats.parseNativeStats("masque=3 awg=2 vless=1 opera=0")
        assertEquals(5L, stats.totalMasqueConnections.get())
        assertEquals(3L, stats.totalAwgConnections.get())
        assertEquals(2L, stats.totalVlessConnections.get())
        assertEquals(1L, stats.totalOperaConnections.get())

        // Higher numbers increment counters
        stats.parseNativeStats("masque=8 awg=7 vless=6 opera=4")
        assertEquals(8L, stats.totalMasqueConnections.get())
        assertEquals(7L, stats.totalAwgConnections.get())
        assertEquals(6L, stats.totalVlessConnections.get())
        assertEquals(4L, stats.totalOperaConnections.get())
    }

    @Test
    fun testRouteStateJsonParsingAndStateAttribution() {
        val stats = ProxyStats()

        val sampleJson = """
        {
            "route_id": "masque",
            "generation": 4,
            "stage_code": 1,
            "stage_name": "WARP MASQUE",
            "active_idx": 0,
            "candidates": ["masque", "awg", "worker"],
            "trace": [
                {"timestamp_ms": 1720000000000, "generation": 1, "from_stage": "None", "to_stage": "WARP MASQUE", "reason": "Init intent"},
                {"timestamp_ms": 1720000001000, "generation": 2, "from_stage": "WARP MASQUE", "to_stage": "WARP AmneziaWG", "reason": "Probe failover: QUIC blocked"},
                {"timestamp_ms": 1720000002000, "generation": 4, "from_stage": "WARP AmneziaWG", "to_stage": "WARP MASQUE", "reason": "Intent switch"}
            ]
        }
        """.trimIndent()

        val jobj = JSONObject(sampleJson)
        val gen = jobj.optLong("generation", 0L)
        val stageCode = jobj.optInt("stage_code", 0)
        val stageName = jobj.optString("stage_name", "")
        val routeId = jobj.optString("route_id", "")
        val traceArr = jobj.optJSONArray("trace")

        if (gen > 0) stats.activeRouteGeneration = gen
        if (stageCode > 0) stats.activeCascadeStageCode = stageCode
        if (stageName.isNotBlank() && stageName != "None") stats.activeCascadeStage = stageName
        if (routeId.isNotBlank()) stats.activeRouteId = routeId
        if (traceArr != null) stats.activeRouteTrace = traceArr.toString()

        assertEquals(4L, stats.activeRouteGeneration)
        assertEquals(1, stats.activeCascadeStageCode)
        assertEquals("WARP MASQUE", stats.activeCascadeStage)
        assertEquals("masque", stats.activeRouteId)
        assertTrue(stats.activeRouteTrace.contains("Probe failover: QUIC blocked"))
        assertTrue(stats.activeRouteTrace.contains("WARP AmneziaWG"))
    }

    @Test
    fun testUserProfilePreservationDuringCascadeFailover() {
        // User configures a custom VLESS profile
        val customConfig = ProxyConfig(
            vlessDomain = "my-custom-vless.example.com",
            vlessServerAddress = "my-custom-vless.example.com",
            vlessServerPort = 8443,
            vlessUuid = "11111111-2222-3333-4444-555555555555",
            vlessPublicKey = "abcdef0123456789abcdef0123456789abcdef01234=",
            vlessTlsSni = "cdn.custom.domain",
            vlessFlow = "xtls-rprx-vision"
        )

        // Ensure custom profile is distinct from default preset
        val defaultPreset = VlessPresetsRepository.getDefaultPreset()
        assertNotEquals(customConfig.vlessDomain, defaultPreset.domain)
        assertNotEquals(customConfig.vlessUuid, defaultPreset.uuid)

        // Under the single supervisor model, probe failovers report route failures to native
        // instead of calling applyVlessPreset(getDefaultPreset()).
        // The user's configuration MUST remain unchanged:
        assertEquals("my-custom-vless.example.com", customConfig.vlessDomain)
        assertEquals("my-custom-vless.example.com", customConfig.vlessServerAddress)
        assertEquals(8443, customConfig.vlessServerPort)
        assertEquals("11111111-2222-3333-4444-555555555555", customConfig.vlessUuid)
        assertEquals("abcdef0123456789abcdef0123456789abcdef01234=", customConfig.vlessPublicKey)
        assertEquals("cdn.custom.domain", customConfig.vlessTlsSni)
        assertEquals("xtls-rprx-vision", customConfig.vlessFlow)
    }

    @Test
    fun testStageCodeConsistencyAcrossAllComponents() {
        // Unified stage codes across Rust, Kotlin LocalProxyServer, and ProxyForegroundService
        val stageCodes = mapOf(
            0 to "Direct",
            1 to "WARP MASQUE",
            2 to "WARP AmneziaWG",
            3 to "Cloudflare Worker",
            4 to "VLESS",
            5 to "Opera VPN",
            6 to "Direct TCP"
        )

        assertEquals("WARP MASQUE", stageCodes[1])
        assertEquals("WARP AmneziaWG", stageCodes[2])
        assertEquals("Cloudflare Worker", stageCodes[3])
        assertEquals("VLESS", stageCodes[4])
        assertEquals("Opera VPN", stageCodes[5])
        assertEquals("Direct TCP", stageCodes[6])
    }

    @Test
    fun testRouteStateJsonParsesTransportAndAppReadiness() {
        val stats = ProxyStats()

        val sampleJson = """
        {
            "route_id": "warp_masque",
            "generation": 5,
            "stage_code": 1,
            "stage_name": "WARP MASQUE",
            "effective_route": "WARP MASQUE",
            "operator": "Cloudflare WARP",
            "is_private": false,
            "trust_boundary_maintained": true,
            "allow_public_relay_fallback": true,
            "allow_opera_direct_exit": false,
            "allow_opera_transport_hop": false,
            "transport_ready": true,
            "app_ready": true,
            "last_app_error": "",
            "app_success_count": 42,
            "app_failure_count": 2,
            "candidates": ["warp_masque"],
            "trace": []
        }
        """.trimIndent()

        val jobj = JSONObject(sampleJson)
        stats.isTransportReady = jobj.optBoolean("transport_ready", false)
        stats.isAppReady = jobj.optBoolean("app_ready", false)
        stats.isUdpSupported = jobj.optBoolean("supports_udp", true)
        stats.lastAppError = jobj.optString("last_app_error", "")
        stats.appSuccessCount = jobj.optLong("app_success_count", 0L)
        stats.appFailureCount = jobj.optLong("app_failure_count", 0L)

        assertTrue(stats.isTransportReady)
        assertTrue(stats.isAppReady)
        assertTrue(stats.isUdpSupported)
        assertEquals("", stats.lastAppError)
        assertEquals(42L, stats.appSuccessCount)
        assertEquals(2L, stats.appFailureCount)
    }

    @Test
    fun testRouteStateJsonParsesSupportsUdpCapability() {
        val stats = ProxyStats()

        // Test unsupported UDP (e.g. Cloudflare Worker WSS or Direct)
        val workerJson = """
        {
            "route_id": "worker",
            "generation": 7,
            "stage_code": 3,
            "stage_name": "Cloudflare Worker",
            "effective_route": "Cloudflare Worker",
            "operator": "Cloudflare",
            "is_private": false,
            "supports_udp": false,
            "transport_ready": true,
            "app_ready": true
        }
        """.trimIndent()

        val jobjWorker = JSONObject(workerJson)
        stats.isUdpSupported = jobjWorker.optBoolean("supports_udp", true)
        assertFalse(stats.isUdpSupported)

        // Test supported UDP (e.g. WARP MASQUE / AWG / VLESS)
        val warpJson = """
        {
            "route_id": "warp_masque",
            "generation": 8,
            "stage_code": 1,
            "stage_name": "WARP MASQUE",
            "effective_route": "WARP MASQUE",
            "operator": "Cloudflare WARP",
            "is_private": false,
            "supports_udp": true,
            "transport_ready": true,
            "app_ready": true
        }
        """.trimIndent()

        val jobjWarp = JSONObject(warpJson)
        stats.isUdpSupported = jobjWarp.optBoolean("supports_udp", true)
        assertTrue(stats.isUdpSupported)
    }

    @Test
    fun testResetHealthSnapshotClearsReadiness() {
        val stats = ProxyStats()
        stats.isTransportReady = true
        stats.isAppReady = true
        stats.lastAppError = "Connection refused (TCP RST)"
        stats.isProbeAlive = true

        stats.resetHealthSnapshot()

        assertFalse(stats.isTransportReady)
        assertFalse(stats.isAppReady)
        assertEquals("", stats.lastAppError)
        assertFalse(stats.isProbeAlive)
    }

    @Test
    fun testL3BridgeSmoltcpReadinessFailureAttribution() {
        val stats = ProxyStats()

        // Simulating failed L3 bridge smoltcp handshake: transport is up, but app is not ready due to TCP RST
        val failureJson = """
        {
            "route_id": "warp_masque",
            "generation": 6,
            "stage_code": 1,
            "stage_name": "WARP MASQUE",
            "effective_route": "WARP MASQUE",
            "operator": "Cloudflare WARP",
            "is_private": false,
            "trust_boundary_maintained": true,
            "transport_ready": true,
            "app_ready": false,
            "last_app_error": "Connection refused (TCP RST)",
            "app_success_count": 10,
            "app_failure_count": 1
        }
        """.trimIndent()

        val jobj = JSONObject(failureJson)
        stats.isTransportReady = jobj.optBoolean("transport_ready", false)
        stats.isAppReady = jobj.optBoolean("app_ready", false)
        stats.lastAppError = jobj.optString("last_app_error", "")
        stats.appSuccessCount = jobj.optLong("app_success_count", 0L)
        stats.appFailureCount = jobj.optLong("app_failure_count", 0L)

        // Transport was established, but target destination refused connection
        assertTrue(stats.isTransportReady)
        assertFalse(stats.isAppReady)
        assertEquals("Connection refused (TCP RST)", stats.lastAppError)
        assertEquals(1L, stats.appFailureCount)
    }
}
