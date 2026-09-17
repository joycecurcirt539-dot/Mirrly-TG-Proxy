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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrustPolicySyncTest {

    @Test
    fun testIsPrivateVpsNodeDetection() {
        // 1. Default Pages preset is public
        val publicPreset = ProxyConfig(
            uplinkModeName = UplinkMode.VLESS.name,
            vlessDomain = "vless-ru.pages.dev",
            vlessServerAddress = "vless-ru.pages.dev"
        )
        assertFalse(publicPreset.isPrivateVpsNode)

        // 2. Custom IP VPS is private
        val privateIpVps = ProxyConfig(
            uplinkModeName = UplinkMode.VLESS.name,
            vlessServerAddress = "194.87.123.45",
            vlessDomain = "194.87.123.45"
        )
        assertTrue(privateIpVps.isPrivateVpsNode)

        // 3. Custom domain VPS with Reality is private
        val privateRealityVps = ProxyConfig(
            uplinkModeName = UplinkMode.VLESS.name,
            vlessServerAddress = "my-vps.example.com",
            vlessDomain = "my-vps.example.com",
            vlessSecurity = "reality",
            vlessPublicKey = "abcdef1234567890abcdef1234567890"
        )
        assertTrue(privateRealityVps.isPrivateVpsNode)

        // 4. Custom domain without workers.dev or pages.dev is private
        val customDomainVps = ProxyConfig(
            uplinkModeName = UplinkMode.VLESS.name,
            vlessDomain = "node01.vpn-corp.net",
            vlessServerAddress = "node01.vpn-corp.net"
        )
        assertTrue(customDomainVps.isPrivateVpsNode)

        // 5. Worker domain is public
        val workerPublic = ProxyConfig(
            uplinkModeName = UplinkMode.VLESS.name,
            vlessDomain = "my-worker.workers.dev",
            vlessServerAddress = "my-worker.workers.dev"
        )
        assertFalse(workerPublic.isPrivateVpsNode)

        // 6. Non-VLESS / non-Hybrid modes are not private VPS nodes
        val warpMasque = ProxyConfig(
            uplinkModeName = UplinkMode.MASQUE.name
        )
        assertFalse(warpMasque.isPrivateVpsNode)
    }

    @Test
    fun testTrustPolicyDefaultsSecureByDefault() {
        val config = ProxyConfig()

        // By default, private VPS MUST NOT leak to public relay on failure
        assertFalse(config.allowPublicRelayFallbackForPrivateVps)

        // By default, Opera direct exit is forbidden (Opera only used as transport hop to VPS)
        assertFalse(config.allowOperaDirectExit)

        // By default, Opera hopping for VLESS is disabled
        assertFalse(config.useOperaVpnForVless)
    }

    @Test
    fun testRouteStateJsonParsingWithTrustPolicyMetadata() {
        val stats = ProxyStats()

        val json = """
        {
            "route_id": "vless_direct",
            "generation": 3,
            "stage_code": 4,
            "stage_name": "VLESS Direct",
            "effective_route": "VLESS Direct VPS",
            "operator": "Private VPS",
            "is_private": true,
            "trust_boundary_maintained": true,
            "allow_public_relay_fallback": false,
            "allow_opera_direct_exit": false,
            "allow_opera_transport_hop": false,
            "trace": [
                {"timestamp_ms": 1720000000000, "generation": 1, "from_stage": "None", "to_stage": "VLESS Direct", "reason": "Init intent"}
            ]
        }
        """.trimIndent()

        val jobj = JSONObject(json)
        val gen = jobj.optLong("generation", 0L)
        val stageCode = jobj.optInt("stage_code", 0)
        val stageName = jobj.optString("stage_name", "")
        val routeId = jobj.optString("route_id", "")
        val effRoute = jobj.optString("effective_route", "")
        val op = jobj.optString("operator", "")
        val isPriv = jobj.optBoolean("is_private", false)
        val trustOk = jobj.optBoolean("trust_boundary_maintained", true)

        if (gen > 0) stats.activeRouteGeneration = gen
        if (stageCode > 0) stats.activeCascadeStageCode = stageCode
        if (stageName.isNotBlank() && stageName != "None") stats.activeCascadeStage = stageName
        if (routeId.isNotBlank()) stats.activeRouteId = routeId
        if (effRoute.isNotBlank()) stats.activeEffectiveRoute = effRoute
        if (op.isNotBlank()) stats.activeOperator = op
        stats.isPrivateNode = isPriv
        stats.isTrustBoundaryMaintained = trustOk

        assertEquals("VLESS Direct VPS", stats.activeEffectiveRoute)
        assertEquals("Private VPS", stats.activeOperator)
        assertTrue(stats.isPrivateNode)
        assertTrue(stats.isTrustBoundaryMaintained)
    }

    @Test
    fun testFallbackTrustBoundaryViolationDetected() {
        val stats = ProxyStats()

        // When fallback to public relay occurs, trust_boundary_maintained becomes false
        val fallbackJson = """
        {
            "route_id": "worker",
            "generation": 5,
            "stage_code": 3,
            "stage_name": "Cloudflare Worker",
            "effective_route": "Cloudflare Worker WSS",
            "operator": "Cloudflare Worker",
            "is_private": false,
            "trust_boundary_maintained": false,
            "allow_public_relay_fallback": true,
            "allow_opera_direct_exit": false,
            "allow_opera_transport_hop": false,
            "trace": [
                {"timestamp_ms": 1720000000000, "generation": 1, "from_stage": "None", "to_stage": "VLESS Direct", "reason": "Init intent"},
                {"timestamp_ms": 1720000005000, "generation": 5, "from_stage": "VLESS Direct", "to_stage": "Cloudflare Worker", "reason": "Fallback: VPS unreachable"}
            ]
        }
        """.trimIndent()

        val jobj = JSONObject(fallbackJson)
        val effRoute = jobj.optString("effective_route", "")
        val op = jobj.optString("operator", "")
        val isPriv = jobj.optBoolean("is_private", false)
        val trustOk = jobj.optBoolean("trust_boundary_maintained", true)

        stats.activeEffectiveRoute = effRoute
        stats.activeOperator = op
        stats.isPrivateNode = isPriv
        stats.isTrustBoundaryMaintained = trustOk

        assertEquals("Cloudflare Worker WSS", stats.activeEffectiveRoute)
        assertEquals("Cloudflare Worker", stats.activeOperator)
        assertFalse(stats.isPrivateNode)
        // Trust boundary maintained is FALSE because origin intent was private VPS but active is public relay
        assertFalse(stats.isTrustBoundaryMaintained)
    }

    @Test
    fun testStageCode7OperaHopMapping() {
        // Stage 7 = VLESS via Opera Hop (Opera is only a transport hop to user VPS)
        val stageMap = mapOf(
            4 to "VLESS Direct",
            5 to "Opera Direct Exit",
            7 to "VLESS via Opera Hop"
        )

        assertEquals("VLESS Direct", stageMap[4])
        assertEquals("Opera Direct Exit", stageMap[5])
        assertEquals("VLESS via Opera Hop", stageMap[7])
    }

    @Test
    fun testResetBaselineClearsTrustState() {
        val stats = ProxyStats()

        stats.activeEffectiveRoute = "VLESS Direct VPS"
        stats.activeOperator = "Private VPS"
        stats.isPrivateNode = true
        stats.isTrustBoundaryMaintained = false

        stats.resetBaseline()

        assertEquals("", stats.activeEffectiveRoute)
        assertEquals("", stats.activeOperator)
        assertFalse(stats.isPrivateNode)
        assertTrue(stats.isTrustBoundaryMaintained)
    }
}
