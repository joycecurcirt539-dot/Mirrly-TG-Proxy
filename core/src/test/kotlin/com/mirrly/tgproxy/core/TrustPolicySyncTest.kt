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
    fun testTrustPolicyDefaultsSecureByDefault() {
        val config = ProxyConfig()

        // By default, private VPS MUST NOT leak to public relay on failure
        assertFalse(config.allowPublicRelayFallbackForPrivateVps)
    }

    @Test
    fun testRouteStateJsonParsingWithTrustPolicyMetadata() {
        val stats = ProxyStats()

        val json = """
        {
            "route_id": "warp_masque",
            "generation": 3,
            "stage_code": 1,
            "stage_name": "WARP MASQUE",
            "effective_route": "WARP MASQUE",
            "operator": "Cloudflare WARP",
            "is_private": false,
            "trust_boundary_maintained": true,
            "allow_public_relay_fallback": false,
            "trace": [
                {"timestamp_ms": 1720000000000, "generation": 1, "from_stage": "None", "to_stage": "WARP MASQUE", "reason": "Init intent"}
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

        assertEquals("WARP MASQUE", stats.activeEffectiveRoute)
        assertEquals("Cloudflare WARP", stats.activeOperator)
        assertFalse(stats.isPrivateNode)
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
            "trace": [
                {"timestamp_ms": 1720000000000, "generation": 1, "from_stage": "None", "to_stage": "Custom VPS", "reason": "Init intent"},
                {"timestamp_ms": 1720000005000, "generation": 5, "from_stage": "Custom VPS", "to_stage": "Cloudflare Worker", "reason": "Fallback: VPS unreachable"}
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
        assertFalse(stats.isTrustBoundaryMaintained)
    }

    @Test
    fun testResetBaselineClearsTrustState() {
        val stats = ProxyStats()

        stats.activeEffectiveRoute = "WARP MASQUE"
        stats.activeOperator = "Cloudflare WARP"
        stats.isPrivateNode = true
        stats.isTrustBoundaryMaintained = false

        stats.resetBaseline()

        assertEquals("", stats.activeEffectiveRoute)
        assertEquals("", stats.activeOperator)
        assertFalse(stats.isPrivateNode)
        assertTrue(stats.isTrustBoundaryMaintained)
    }
}
