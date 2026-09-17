/*
 * Mirrly TG Proxy - Unit tests for Per-DC and Per-Network Domain Scoring (MOB-026)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 */

package com.mirrly.tgproxy.core

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * MOB-026: Domain scoring должен быть per-DC и per-network.
 *
 * Требования:
 * 1. Не переносить скорость kws2 на другие DC/сети.
 * 2. Score включает useful success, а не только probe RTT.
 * 3. Смена оператора не наследует плохой winner.
 * 4. DC и media class видны отдельно.
 */
class DomainScoringPerDcTest {

    @Test
    fun testDomainBalancerStatusParsing() {
        val sampleJson = """
            {
              "current_network_generation": 3,
              "routes": [
                {
                  "dc_id": 2,
                  "is_media": false,
                  "active_domain": "worker1.dev",
                  "rankings": [
                    {
                      "domain": "worker1.dev",
                      "probe_rtt_ms": 45,
                      "useful_success_count": 8,
                      "failure_count": 0,
                      "consecutive_failures": 0,
                      "score": 30
                    },
                    {
                      "domain": "worker2.dev",
                      "probe_rtt_ms": 70,
                      "useful_success_count": 0,
                      "failure_count": 1,
                      "consecutive_failures": 1,
                      "score": 190
                    }
                  ]
                },
                {
                  "dc_id": 4,
                  "is_media": true,
                  "active_domain": "worker3.dev",
                  "rankings": [
                    {
                      "domain": "worker3.dev",
                      "probe_rtt_ms": 35,
                      "useful_success_count": 15,
                      "failure_count": 0,
                      "consecutive_failures": 0,
                      "score": 20
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val status = DomainBalancerStatus.fromJson(sampleJson)
        assertNotNull(status)
        assertEquals(3L, status!!.currentNetworkGeneration)
        assertEquals(2, status.routes.size)

        // Route 1: DC2 Chat
        val dc2Chat = status.routes[0]
        assertEquals(2, dc2Chat.dcId)
        assertFalse(dc2Chat.isMedia)
        assertEquals("worker1.dev", dc2Chat.activeDomain)
        assertEquals(2, dc2Chat.rankings.size)

        val rank1 = dc2Chat.rankings[0]
        assertEquals("worker1.dev", rank1.domain)
        assertEquals(45L, rank1.probeRttMs)
        assertEquals(8L, rank1.usefulSuccessCount)
        assertEquals(0L, rank1.failureCount)
        assertEquals(0, rank1.consecutiveFailures)
        assertEquals(30L, rank1.score)

        val rank2 = dc2Chat.rankings[1]
        assertEquals("worker2.dev", rank2.domain)
        assertEquals(70L, rank2.probeRttMs)
        assertEquals(0L, rank2.usefulSuccessCount)
        assertEquals(1L, rank2.failureCount)
        assertEquals(1, rank2.consecutiveFailures)
        assertEquals(190L, rank2.score)

        // Route 2: DC4 Media
        val dc4Media = status.routes[1]
        assertEquals(4, dc4Media.dcId)
        assertTrue(dc4Media.isMedia)
        assertEquals("worker3.dev", dc4Media.activeDomain)
        assertEquals(1, dc4Media.rankings.size)
        assertEquals("worker3.dev", dc4Media.rankings[0].domain)
        assertEquals(35L, dc4Media.rankings[0].probeRttMs)
        assertEquals(15L, dc4Media.rankings[0].usefulSuccessCount)
    }

    @Test
    fun testDomainBalancerStatusParsingNullAndEmpty() {
        assertNull(DomainBalancerStatus.fromJson(null))
        assertNull(DomainBalancerStatus.fromJson(""))
        assertNull(DomainBalancerStatus.fromJson("   "))
        assertNull(DomainBalancerStatus.fromJson("not a json"))
    }

    @Test
    fun testRustBalancerNoLeakageFromDc2Contract() {
        val balancerRs = File("..", "mirrlyengine/src/balancer.rs").canonicalFile.readText()

        // 1. MOB-026: No fallback to DC2 in get_fastest_domain_for_dc or get_domains_for_dc
        assertFalse(
            balancerRs.contains(".get(&2)"),
            "balancer.rs must NOT fall back to DC2 for unprobed DCs: speed of kws2 must never leak to other DCs"
        )
        assertFalse(
            balancerRs.contains("dc2_ranked"),
            "balancer.rs must NOT have a dc2_ranked fallback path"
        )

        // 2. Key contains both dc_id and is_media
        assertTrue(balancerRs.contains("pub struct DcTargetKey"))
        assertTrue(balancerRs.contains("pub dc_id: i32"))
        assertTrue(balancerRs.contains("pub is_media: bool"))

        // 3. Score calculation includes useful success and failure penalty
        assertTrue(balancerRs.contains("pub useful_success_count: u64"))
        assertTrue(balancerRs.contains("pub failure_count: u64"))
        assertTrue(balancerRs.contains("pub consecutive_failures: u32"))
        assertTrue(balancerRs.contains("fn calculate_score"))
        assertTrue(balancerRs.contains("USEFUL_SUCCESS_BONUS_MS"))
        assertTrue(balancerRs.contains("STABILITY_HYSTERESIS_MS"))

        // 4. Network generation tracking and isolation
        assertTrue(balancerRs.contains("pub fn notify_generation_change"))
        assertTrue(balancerRs.contains("current_generation"))
    }

    @Test
    fun testNetworkGenerationNotificationContract() {
        val networkProfileRs = File("..", "mirrlyengine/src/network_profile.rs").canonicalFile.readText()

        // When generation changes, BALANCER must be notified to switch generation and prune stale winners
        assertTrue(
            networkProfileRs.contains("crate::balancer::BALANCER.write().notify_generation_change(effective.generation);"),
            "network_profile.rs must notify BALANCER of generation changes"
        )
    }

    @Test
    fun testProxyTracksUsefulSuccessAndFailureContract() {
        val proxyRs = File("..", "mirrlyengine/src/proxy.rs").canonicalFile.readText()

        // 1. bridge_ws records useful success when downstream data is received
        assertTrue(
            proxyRs.contains("crate::balancer::BALANCER.write().record_useful_success("),
            "proxy.rs bridge_ws must record useful success for confirmed downstream Telegram data"
        )

        // 2. bridge_ws records failure if connection terminates without downstream data
        assertTrue(
            proxyRs.contains("crate::balancer::BALANCER.write().record_failure("),
            "proxy.rs bridge_ws must record failure when connection closes before useful data"
        )

        // 3. get_domains_for_dc receives is_media parameter
        assertTrue(
            proxyRs.contains("get_domains_for_dc(effective_dc, slot.is_media != 0)") ||
                    proxyRs.contains("get_domains_for_dc(effective_dc, is_media)"),
            "proxy.rs must pass is_media to get_domains_for_dc"
        )
    }

    @Test
    fun testFfiAndJnaContract() {
        val rustLibRs = File("..", "mirrlyengine/src/lib.rs").canonicalFile.readText()
        val kotlinNativeProxy = File("src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt").canonicalFile.readText()

        // Rust exports GetDomainBalancerStatusJson
        assertTrue(
            rustLibRs.contains("pub extern \"C\" fn GetDomainBalancerStatusJson"),
            "lib.rs must export GetDomainBalancerStatusJson"
        )

        // Kotlin ProxyLibrary declares GetDomainBalancerStatusJson
        assertTrue(
            kotlinNativeProxy.contains("fun GetDomainBalancerStatusJson(): Pointer?"),
            "NativeProxy.kt must declare GetDomainBalancerStatusJson"
        )

        // Kotlin NativeProxy exposes getDomainBalancerStatusJson & getDomainBalancerStatus
        assertTrue(
            kotlinNativeProxy.contains("fun getDomainBalancerStatusJson(): String?"),
            "NativeProxy must expose getDomainBalancerStatusJson"
        )
        assertTrue(
            kotlinNativeProxy.contains("fun getDomainBalancerStatus(): DomainBalancerStatus?"),
            "NativeProxy must expose getDomainBalancerStatus"
        )
        assertTrue(
            kotlinNativeProxy.contains("data class DomainBalancerStatus("),
            "NativeProxy must define DomainBalancerStatus"
        )
        assertTrue(
            kotlinNativeProxy.contains("data class TargetRouteStatus("),
            "NativeProxy must define TargetRouteStatus"
        )
        assertTrue(
            kotlinNativeProxy.contains("data class RankedDomainSummary("),
            "NativeProxy must define RankedDomainSummary"
        )
    }
}
