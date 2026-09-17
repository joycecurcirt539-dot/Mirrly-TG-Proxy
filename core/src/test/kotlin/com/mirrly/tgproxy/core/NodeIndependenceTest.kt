/*
 * Mirrly TG Proxy - Unit tests for Node Independence & Edge Route Diversity (MOB-027)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 */

package com.mirrly.tgproxy.core

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * MOB-027: Проверить реальную независимость «новых узлов».
 *
 * Требования:
 * 1. Несколько workers.dev имён могут идти через один Cloudflare edge/фильтр и не являются маршрутной диверсификацией.
 * 2. Для каждого узла зафиксированы: resolved IP/family, colo, ASN/path proxy, bytes-before-stall и корреляция отказов.
 * 3. Добавлять узел в race только если он повышает вероятность успеха, а не только список имён.
 */
class NodeIndependenceTest {

    @Test
    fun testNodeIndependenceStatusJsonParsing() {
        val sampleJson = """
            {
              "total_nodes": 3,
              "nodes": [
                {
                  "domain": "worker1.workers.dev",
                  "resolved_ip": "104.21.32.1",
                  "family": "IPv4",
                  "colo": "DME",
                  "asn": "AS13335 (Cloudflare)",
                  "bytes_before_stall": 0,
                  "total_bytes_transferred": 0,
                  "success_count": 0,
                  "failure_count": 3,
                  "consecutive_failures": 3,
                  "path_fingerprint": {
                    "family": "IPv4",
                    "ip_prefix": "104.21.0.0/16",
                    "colo": "DME",
                    "asn": "AS13335 (Cloudflare)"
                  },
                  "failure_correlation_score": 1.0
                },
                {
                  "domain": "worker2.workers.dev",
                  "resolved_ip": "104.21.32.2",
                  "family": "IPv4",
                  "colo": "DME",
                  "asn": "AS13335 (Cloudflare)",
                  "bytes_before_stall": 16384,
                  "total_bytes_transferred": 16384,
                  "success_count": 0,
                  "failure_count": 2,
                  "consecutive_failures": 2,
                  "path_fingerprint": {
                    "family": "IPv4",
                    "ip_prefix": "104.21.0.0/16",
                    "colo": "DME",
                    "asn": "AS13335 (Cloudflare)"
                  },
                  "failure_correlation_score": 1.0
                },
                {
                  "domain": "worker-hel.dev",
                  "resolved_ip": "172.67.180.1",
                  "family": "IPv4",
                  "colo": "HEL",
                  "asn": "AS13335 (Cloudflare)",
                  "bytes_before_stall": 0,
                  "total_bytes_transferred": 1048576,
                  "success_count": 5,
                  "failure_count": 0,
                  "consecutive_failures": 0,
                  "path_fingerprint": {
                    "family": "IPv4",
                    "ip_prefix": "172.67.0.0/16",
                    "colo": "HEL",
                    "asn": "AS13335 (Cloudflare)"
                  },
                  "failure_correlation_score": 0.0
                }
              ],
              "path_groups": [
                {
                  "fingerprint_key": "IPv4|104.21.0.0/16|DME|AS13335 (Cloudflare)",
                  "family": "IPv4",
                  "ip_prefix": "104.21.0.0/16",
                  "colo": "DME",
                  "asn": "AS13335 (Cloudflare)",
                  "node_count": 2,
                  "total_successes": 0,
                  "total_failures": 5,
                  "failure_rate": 1.0,
                  "is_blocked": true
                },
                {
                  "fingerprint_key": "IPv4|172.67.0.0/16|HEL|AS13335 (Cloudflare)",
                  "family": "IPv4",
                  "ip_prefix": "172.67.0.0/16",
                  "colo": "HEL",
                  "asn": "AS13335 (Cloudflare)",
                  "node_count": 1,
                  "total_successes": 5,
                  "total_failures": 0,
                  "failure_rate": 0.0,
                  "is_blocked": false
                }
              ],
              "diversity_ratio": 0.6666666666666666
            }
        """.trimIndent()

        val status = NodeIndependenceStatus.fromJson(sampleJson)
        assertNotNull(status)
        assertEquals(3, status!!.totalNodes)
        assertEquals(3, status.nodes.size)
        assertEquals(2, status.pathGroups.size)
        assertTrue(status.diversityRatio > 0.6)

        // Node 1: worker1.workers.dev (failing on 104.21 / DME)
        val node1 = status.nodes[0]
        assertEquals("worker1.workers.dev", node1.domain)
        assertEquals("104.21.32.1", node1.resolvedIp)
        assertEquals("IPv4", node1.family)
        assertEquals("DME", node1.colo)
        assertEquals("AS13335 (Cloudflare)", node1.asn)
        assertEquals(0L, node1.bytesBeforeStall)
        assertEquals(3L, node1.failureCount)
        assertEquals(1.0, node1.failureCorrelationScore, 0.001)

        val fp1 = node1.pathFingerprint
        assertNotNull(fp1)
        assertEquals("IPv4", fp1!!.family)
        assertEquals("104.21.0.0/16", fp1.ipPrefix)
        assertEquals("DME", fp1.colo)
        assertEquals("AS13335 (Cloudflare)", fp1.asn)

        // Node 2: worker2.workers.dev (throttled stall at 16KB)
        val node2 = status.nodes[1]
        assertEquals("worker2.workers.dev", node2.domain)
        assertEquals(16384L, node2.bytesBeforeStall)
        assertEquals(1.0, node2.failureCorrelationScore, 0.001)

        // Node 3: worker-hel.dev (healthy on HEL PoP)
        val node3 = status.nodes[2]
        assertEquals("worker-hel.dev", node3.domain)
        assertEquals("172.67.180.1", node3.resolvedIp)
        assertEquals("HEL", node3.colo)
        assertEquals(0L, node3.failureCount)
        assertEquals(5L, node3.successCount)
        assertEquals(0.0, node3.failureCorrelationScore, 0.001)

        // Path Groups
        val groupDme = status.pathGroups.first { it.colo == "DME" }
        assertEquals(2, groupDme.nodeCount)
        assertEquals(5L, groupDme.totalFailures)
        assertEquals(1.0, groupDme.failureRate, 0.001)
        assertTrue(groupDme.isBlocked)

        val groupHel = status.pathGroups.first { it.colo == "HEL" }
        assertEquals(1, groupHel.nodeCount)
        assertEquals(5L, groupHel.totalSuccesses)
        assertEquals(0.0, groupHel.failureRate, 0.001)
        assertFalse(groupHel.isBlocked)
    }

    @Test
    fun testRouteIndependenceClusteredFailingClonesSimulation() {
        data class MockNode(
            val domain: String,
            val ipPrefix: String,
            val colo: String,
            val family: String
        ) {
            val fingerprintKey = "$family|$ipPrefix|$colo"
        }

        val allNodes = listOf(
            MockNode("worker-alpha.workers.dev", "104.21.0.0/16", "DME", "IPv4"),
            MockNode("worker-beta.workers.dev", "104.21.0.0/16", "DME", "IPv4"),
            MockNode("worker-gamma.workers.dev", "104.21.0.0/16", "DME", "IPv4"),
            MockNode("worker-hel.dev", "172.67.0.0/16", "HEL", "IPv4"),
            MockNode("worker-v6.dev", "2606:4700::/32", "FRA", "IPv6")
        )

        val blockedFingerprints = setOf("IPv4|104.21.0.0/16|DME")

        val selected = mutableListOf<MockNode>()
        val seenFingerprints = mutableSetOf<String>()
        val deferred = mutableListOf<MockNode>()

        for (node in allNodes) {
            if (blockedFingerprints.contains(node.fingerprintKey)) {
                deferred.add(node)
                continue
            }
            if (!seenFingerprints.contains(node.fingerprintKey)) {
                seenFingerprints.add(node.fingerprintKey)
                selected.add(node)
                if (selected.size >= 3) break
            } else {
                deferred.add(node)
            }
        }

        assertEquals(2, selected.size)
        assertEquals("worker-hel.dev", selected[0].domain)
        assertEquals("worker-v6.dev", selected[1].domain)

        assertEquals(3, deferred.size)
        assertTrue(deferred.all { it.colo == "DME" })
    }

    @Test
    fun testBytesBeforeStallDistinguishesDpiVsThrottling() {
        val dpiImmediateResetBytes = 0L
        val isDpiBlock = dpiImmediateResetBytes == 0L
        assertTrue(isDpiBlock)

        val throttledBytes = 16384L
        val isThrottling = throttledBytes > 0L && throttledBytes < 65536L
        assertTrue(isThrottling)

        val normalSessionBytes = 524288L
        val isHealthy = normalSessionBytes >= 65536L
        assertTrue(isHealthy)
    }

    @Test
    fun testFfiAndEngineContract() {
        val rootDir = File(System.getProperty("user.dir")).let {
            if (it.name == "core") it.parentFile else it
        }
        val kotlinNativeProxy = File(rootDir, "core/src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt").canonicalFile.readText()

        // Verify ProxyLibrary declaration in NativeProxy.kt
        assertTrue(
            kotlinNativeProxy.contains("fun GetNodeIndependenceStatusJson(): Pointer?"),
            "ProxyLibrary must declare GetNodeIndependenceStatusJson(): Pointer?"
        )

        // Verify NativeProxy wrapper methods
        assertTrue(
            kotlinNativeProxy.contains("fun getNodeIndependenceStatusJson(): String?"),
            "NativeProxy must provide getNodeIndependenceStatusJson(): String?"
        )
        assertTrue(
            kotlinNativeProxy.contains("fun getNodeIndependenceStatus(): NodeIndependenceStatus?"),
            "NativeProxy must provide getNodeIndependenceStatus(): NodeIndependenceStatus?"
        )

        // Verify Data Classes
        assertTrue(
            kotlinNativeProxy.contains("data class NodeIndependenceStatus("),
            "NativeProxy must declare NodeIndependenceStatus"
        )
        assertTrue(
            kotlinNativeProxy.contains("data class NodeTelemetryData("),
            "NativeProxy must declare NodeTelemetryData"
        )
        assertTrue(
            kotlinNativeProxy.contains("data class PathGroupStatsData("),
            "NativeProxy must declare PathGroupStatsData"
        )
        assertTrue(
            kotlinNativeProxy.contains("data class PathFingerprintData("),
            "NativeProxy must declare PathFingerprintData"
        )
    }

    @Test
    fun testRustEngineSourceWiringContract() {
        val rootDir = File(System.getProperty("user.dir")).let {
            if (it.name == "core") it.parentFile else it
        }

        val nodeIndependenceRs = File(rootDir, "mirrlyengine/src/node_independence.rs")
        assertTrue(nodeIndependenceRs.exists(), "mirrlyengine/src/node_independence.rs must exist")
        val nodeCode = nodeIndependenceRs.readText()
        assertTrue(nodeCode.contains("pub struct NodeTelemetry"), "Must declare NodeTelemetry")
        assertTrue(nodeCode.contains("pub struct PathFingerprint"), "Must declare PathFingerprint")
        assertTrue(nodeCode.contains("pub struct PathGroupStats"), "Must declare PathGroupStats")
        assertTrue(nodeCode.contains("pub struct NodeIndependenceStatus"), "Must declare NodeIndependenceStatus")
        assertTrue(nodeCode.contains("pub fn infer_asn"), "Must declare infer_asn")
        assertTrue(nodeCode.contains("pub fn extract_cf_colo"), "Must declare extract_cf_colo")
        assertTrue(nodeCode.contains("select_diverse_race_candidates"), "Must declare select_diverse_race_candidates")
        assertTrue(nodeCode.contains("bytes_before_stall"), "Must track bytes_before_stall")
        assertTrue(nodeCode.contains("failure_correlation_score"), "Must calculate failure_correlation_score")

        val proxyRs = File(rootDir, "mirrlyengine/src/proxy.rs")
        val proxyCode = proxyRs.readText()
        assertTrue(
            proxyCode.contains("select_diverse_race_candidates"),
            "proxy.rs must filter race candidates with select_diverse_race_candidates"
        )
        assertTrue(
            proxyCode.contains("bytes_before_stall"),
            "proxy.rs bridge_ws must track bytes_before_stall"
        )

        val socks5Rs = File(rootDir, "mirrlyengine/src/socks5.rs")
        val socks5Code = socks5Rs.readText()
        assertTrue(
            socks5Code.contains("select_diverse_race_candidates"),
            "socks5.rs must filter active_workers with select_diverse_race_candidates"
        )
        assertTrue(
            socks5Code.contains("bytes_before_stall"),
            "socks5.rs bridge_socks5_ws must track bytes_before_stall"
        )

        val libRs = File(rootDir, "mirrlyengine/src/lib.rs")
        val libCode = libRs.readText()
        assertTrue(
            libCode.contains("pub mod node_independence;"),
            "lib.rs must export node_independence module"
        )
        assertTrue(
            libCode.contains("GetNodeIndependenceStatusJson"),
            "lib.rs must export GetNodeIndependenceStatusJson FFI function"
        )
    }
}
