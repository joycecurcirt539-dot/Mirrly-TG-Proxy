/*
 * Mirrly TG Proxy - Stage-Coded Connection Timeline & Diagnostic Telemetry Contract (MOB-001)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 */

package com.mirrly.tgproxy.core

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StageTimelineTest {

    @Test
    fun testStageProgressionAndTimelineParsing() {
        val sampleJson = JSONObject().apply {
            put("attempt_id", 42L)
            put("network_generation", 3L)
            put("is_mobile", true)
            put("transport", "socks5_worker")
            put("hostname", "worker.example.workers.dev")
            put("target_port", 443)
            put("family", "ipv4")
            put("ip_bucket", "104.21.45.0/24")
            put("dns_source", "doh")
            put("dns_ttl", 300L)
            put("dns_duration_ms", 22L)
            put("tcp_duration_ms", 35L)
            put("tcp_status", "ok")
            put("tls_duration_ms", 48L)
            put("tls_status", "ok")
            put("http_status", 101)
            put("wss_duration_ms", 25L)
            put("wss_status", "ok")
            put("relay_ack_duration_ms", 12L)
            put("relay_ack_status", "ok")
            put("first_tx_ms", 150L)
            put("first_useful_rx_ms", 210L)
            put("bytes_tx", 1024L)
            put("bytes_rx", 4096L)
            put("bytes_before_stall", 0L)
            put("is_stall_cutoff", false)
            put("close_code", JSONObject.NULL)
            put("close_reason", "")
            put("current_stage", "stable")
            put("failure_stage", "none")
            put("stage_timeout", "none")
            put("failure_reason", "")
            put("start_timestamp_ms", 1726240000000L)
            put("total_duration_ms", 5000L)
            put("completed", false)
        }

        val item = ConnectionTimelineItem.fromJson(sampleJson)

        assertEquals(42L, item.attemptId)
        assertEquals(3L, item.networkGeneration)
        assertTrue(item.isMobile)
        assertEquals("socks5_worker", item.transport)
        assertEquals("worker.example.workers.dev", item.hostname)
        assertEquals(443, item.targetPort)
        assertEquals("ipv4", item.family)
        assertEquals("104.21.45.0/24", item.ipBucket)
        assertEquals("doh", item.dnsSource)
        assertEquals(300L, item.dnsTtl)
        assertEquals(22L, item.dnsDurationMs)
        assertEquals(35L, item.tcpDurationMs)
        assertEquals("ok", item.tcpStatus)
        assertEquals(48L, item.tlsDurationMs)
        assertEquals("ok", item.tlsStatus)
        assertEquals(101, item.httpStatus)
        assertEquals(25L, item.wssDurationMs)
        assertEquals("ok", item.wssStatus)
        assertEquals(12L, item.relayAckDurationMs)
        assertEquals("ok", item.relayAckStatus)
        assertEquals(150L, item.firstTxMs)
        assertEquals(210L, item.firstUsefulRxMs) // Primary SLI verified!
        assertEquals(1024L, item.bytesTx)
        assertEquals(4096L, item.bytesRx)
        assertFalse(item.isStallCutoff)
        assertNull(item.closeCode)
        assertEquals("stable", item.currentStage)
        assertEquals("none", item.failureStage)
    }

    @Test
    fun testTimeToUsefulRxAsPrimarySli() {
        val sliJson = JSONObject().apply {
            put("total_attempts", 100L)
            put("successful_useful_rx_count", 95L)
            put("time_to_useful_rx_p50_ms", 180L)
            put("time_to_useful_rx_p95_ms", 350L)
            put("time_to_useful_rx_min_ms", 110L)
            put("time_to_useful_rx_max_ms", 620L)
            put("time_to_useful_rx_avg_ms", 215L)
            put("cutoff_suspected_count", 2L)
            put("failures_by_stage", JSONObject().apply {
                put("tcp", 3L)
                put("tls", 2L)
            })
            put("failures_by_timeout", JSONObject().apply {
                put("tcp_connect_timeout", 3L)
                put("tls_handshake_timeout", 2L)
            })
            put("active_connections_count", 5L)
            put("last_useful_rx_timestamp_ms", 1726240050000L)
            put("current_network_generation", 4L)
        }

        val sli = StageTimelineSliSummary.fromJson(sliJson)

        assertEquals(100L, sli.totalAttempts)
        assertEquals(95L, sli.successfulUsefulRxCount)
        assertEquals(95, sli.successRatePercent)
        assertEquals(180L, sli.timeToUsefulRxP50Ms)
        assertEquals(350L, sli.timeToUsefulRxP95Ms)
        assertEquals(110L, sli.timeToUsefulRxMinMs)
        assertEquals(620L, sli.timeToUsefulRxMaxMs)
        assertEquals(215L, sli.timeToUsefulRxAvgMs)
        assertEquals(2L, sli.cutoffSuspectedCount)
        assertEquals(3L, sli.failuresByStage["tcp"])
        assertEquals(2L, sli.failuresByStage["tls"])
        assertEquals(3L, sli.failuresByTimeout["tcp_connect_timeout"])
        assertEquals(2L, sli.failuresByTimeout["tls_handshake_timeout"])
        assertEquals(5L, sli.activeConnectionsCount)
        assertEquals(4L, sli.currentNetworkGeneration)
    }

    @Test
    fun testStageTimeoutDifferentiation() {
        val stages = listOf(
            "dns_timeout" to "dns",
            "tcp_connect_timeout" to "tcp",
            "tls_handshake_timeout" to "tls",
            "wss_handshake_timeout" to "wss",
            "relay_ack_timeout" to "relay_ack",
            "first_rx_timeout" to "useful_rx",
            "idle_read_timeout" to "stable"
        )

        for ((timeoutName, stageName) in stages) {
            val json = JSONObject().apply {
                put("attempt_id", 1L)
                put("network_generation", 1L)
                put("is_mobile", true)
                put("transport", "socks5_worker")
                put("hostname", "worker.dev")
                put("target_port", 443)
                put("failure_stage", stageName)
                put("stage_timeout", timeoutName)
                put("failure_reason", "Timeout after deadline")
                put("completed", true)
            }
            val item = ConnectionTimelineItem.fromJson(json)
            assertEquals(timeoutName, item.stageTimeout)
            assertEquals(stageName, item.failureStage)
            assertTrue(item.completed)
        }
    }

    @Test
    fun testZeroSensitiveDataInTimeline() {
        // Ensure that credentials in URLs are sanitized and do not appear in the hostname or timeline fields
        val secretKey = "0123456789abcdef0123456789abcdef"
        val socksUser = "myuser"
        val socksPass = "mypassword"

        val json = JSONObject().apply {
            put("attempt_id", 10L)
            put("network_generation", 1L)
            put("is_mobile", false)
            put("transport", "socks5_worker")
            put("hostname", "worker.dev") // Sanitized, no credentials
            put("target_port", 443)
            put("failure_reason", "Connection reset")
        }

        val item = ConnectionTimelineItem.fromJson(json)

        assertFalse(item.hostname.contains(secretKey))
        assertFalse(item.hostname.contains(socksUser))
        assertFalse(item.hostname.contains(socksPass))
        assertFalse(item.failureReason.contains(secretKey))
        assertFalse(item.failureReason.contains(socksUser))
        assertFalse(item.failureReason.contains(socksPass))
    }

    @Test
    fun testRussianIspCutoffDetection() {
        val json = JSONObject().apply {
            put("attempt_id", 77L)
            put("network_generation", 2L)
            put("is_mobile", true)
            put("transport", "socks5_worker")
            put("hostname", "cf-worker.workers.dev")
            put("target_port", 443)
            put("bytes_tx", 2048L)
            put("bytes_rx", 14336L)
            put("bytes_before_stall", 16384L) // 16 KB plateau!
            put("is_stall_cutoff", true)
            put("failure_stage", "stall")
            put("stage_timeout", "idle_read_timeout")
            put("failure_reason", "Cloudflare edge 16KB stall/cutoff detected")
            put("completed", true)
        }

        val item = ConnectionTimelineItem.fromJson(json)
        assertTrue(item.isStallCutoff)
        assertEquals(16384L, item.bytesBeforeStall)
        assertEquals("stall", item.failureStage)
        assertEquals("idle_read_timeout", item.stageTimeout)
    }

    @Test
    fun testStageTimelineReportFullRoundTrip() {
        val rootJson = JSONObject().apply {
            put("sli", JSONObject().apply {
                put("total_attempts", 5L)
                put("successful_useful_rx_count", 4L)
                put("time_to_useful_rx_p50_ms", 150L)
                put("time_to_useful_rx_p95_ms", 220L)
                put("cutoff_suspected_count", 1L)
                put("current_network_generation", 2L)
            })
            put("attempts", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("attempt_id", 1L)
                    put("hostname", "worker1.dev")
                    put("first_useful_rx_ms", 140L)
                })
                put(JSONObject().apply {
                    put("attempt_id", 2L)
                    put("hostname", "worker2.dev")
                    put("first_useful_rx_ms", 160L)
                })
            })
        }

        val report = StageTimelineReport.fromJson(rootJson.toString())
        assertNotNull(report)
        assertEquals(5L, report!!.sli.totalAttempts)
        assertEquals(4L, report.sli.successfulUsefulRxCount)
        assertEquals(80, report.sli.successRatePercent)
        assertEquals(150L, report.sli.timeToUsefulRxP50Ms)
        assertEquals(2, report.attempts.size)
        assertEquals(1L, report.attempts[0].attemptId)
        assertEquals("worker1.dev", report.attempts[0].hostname)
        assertEquals(140L, report.attempts[0].firstUsefulRxMs)
    }
}
