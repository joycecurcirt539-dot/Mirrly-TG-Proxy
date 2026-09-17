/*
 * Mirrly TG Proxy - Stage-Coded Connection Timeline & Diagnostic Telemetry Contract (MOB-001)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 */

package com.mirrly.tgproxy.core

import org.json.JSONObject

/**
 * Immutable record representing a single connection attempt timeline.
 * Captures all checkpoints from DNS -> TCP -> TLS -> WSS -> Relay ACK -> First TX -> Useful RX.
 * Strictly guarantees that no sensitive secrets, passwords, or payload bytes are preserved.
 */
data class ConnectionTimelineItem(
    val attemptId: Long,
    val networkGeneration: Long,
    val isMobile: Boolean,
    val transport: String,
    val hostname: String,
    val targetPort: Int,
    val family: String,
    val ipBucket: String,
    val dnsSource: String,
    val dnsTtl: Long,
    val dnsDurationMs: Long,
    val tcpDurationMs: Long,
    val tcpStatus: String,
    val tlsDurationMs: Long,
    val tlsStatus: String,
    val httpStatus: Int?,
    val wssDurationMs: Long,
    val wssStatus: String,
    val relayAckDurationMs: Long,
    val relayAckStatus: String,
    val firstTxMs: Long?,
    val firstUsefulRxMs: Long?, // Core SLI: time_to_useful_rx
    val bytesTx: Long,
    val bytesRx: Long,
    val bytesBeforeStall: Long,
    val isStallCutoff: Boolean, // Russian ISP ~16KB cutoff indicator
    val closeCode: Int?,
    val closeReason: String,
    val currentStage: String,
    val failureStage: String,
    val stageTimeout: String,
    val failureReason: String,
    val startTimestampMs: Long,
    val totalDurationMs: Long,
    val completed: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject): ConnectionTimelineItem {
            return ConnectionTimelineItem(
                attemptId = json.optLong("attempt_id", 0L),
                networkGeneration = json.optLong("network_generation", 0L),
                isMobile = json.optBoolean("is_mobile", false),
                transport = json.optString("transport", ""),
                hostname = json.optString("hostname", ""),
                targetPort = json.optInt("target_port", 0),
                family = json.optString("family", "unknown"),
                ipBucket = json.optString("ip_bucket", "none"),
                dnsSource = json.optString("dns_source", "none"),
                dnsTtl = json.optLong("dns_ttl", 0L),
                dnsDurationMs = json.optLong("dns_duration_ms", 0L),
                tcpDurationMs = json.optLong("tcp_duration_ms", 0L),
                tcpStatus = json.optString("tcp_status", "none"),
                tlsDurationMs = json.optLong("tls_duration_ms", 0L),
                tlsStatus = json.optString("tls_status", "none"),
                httpStatus = if (json.has("http_status") && !json.isNull("http_status")) json.optInt("http_status") else null,
                wssDurationMs = json.optLong("wss_duration_ms", 0L),
                wssStatus = json.optString("wss_status", "none"),
                relayAckDurationMs = json.optLong("relay_ack_duration_ms", 0L),
                relayAckStatus = json.optString("relay_ack_status", "none"),
                firstTxMs = if (json.has("first_tx_ms") && !json.isNull("first_tx_ms")) json.optLong("first_tx_ms") else null,
                firstUsefulRxMs = if (json.has("first_useful_rx_ms") && !json.isNull("first_useful_rx_ms")) json.optLong("first_useful_rx_ms") else null,
                bytesTx = json.optLong("bytes_tx", 0L),
                bytesRx = json.optLong("bytes_rx", 0L),
                bytesBeforeStall = json.optLong("bytes_before_stall", 0L),
                isStallCutoff = json.optBoolean("is_stall_cutoff", false),
                closeCode = if (json.has("close_code") && !json.isNull("close_code")) json.optInt("close_code") else null,
                closeReason = json.optString("close_reason", ""),
                currentStage = json.optString("current_stage", "dns"),
                failureStage = json.optString("failure_stage", "none"),
                stageTimeout = json.optString("stage_timeout", "none"),
                failureReason = json.optString("failure_reason", ""),
                startTimestampMs = json.optLong("start_timestamp_ms", 0L),
                totalDurationMs = json.optLong("total_duration_ms", 0L),
                completed = json.optBoolean("completed", false)
            )
        }
    }
}

/**
 * Aggregated summary of Service Level Indicators (SLI) focusing on time_to_useful_rx.
 */
data class StageTimelineSliSummary(
    val totalAttempts: Long,
    val successfulUsefulRxCount: Long,
    val timeToUsefulRxP50Ms: Long,
    val timeToUsefulRxP95Ms: Long,
    val timeToUsefulRxMinMs: Long,
    val timeToUsefulRxMaxMs: Long,
    val timeToUsefulRxAvgMs: Long,
    val cutoffSuspectedCount: Long,
    val failuresByStage: Map<String, Long>,
    val failuresByTimeout: Map<String, Long>,
    val activeConnectionsCount: Long,
    val lastUsefulRxTimestampMs: Long,
    val currentNetworkGeneration: Long
) {
    val successRatePercent: Int
        get() = if (totalAttempts > 0) ((successfulUsefulRxCount * 100) / totalAttempts).toInt() else 100

    companion object {
        fun fromJson(json: JSONObject): StageTimelineSliSummary {
            val stagesMap = mutableMapOf<String, Long>()
            json.optJSONObject("failures_by_stage")?.let { obj ->
                for (key in obj.keys()) {
                    stagesMap[key] = obj.optLong(key, 0L)
                }
            }

            val timeoutMap = mutableMapOf<String, Long>()
            json.optJSONObject("failures_by_timeout")?.let { obj ->
                for (key in obj.keys()) {
                    timeoutMap[key] = obj.optLong(key, 0L)
                }
            }

            return StageTimelineSliSummary(
                totalAttempts = json.optLong("total_attempts", 0L),
                successfulUsefulRxCount = json.optLong("successful_useful_rx_count", 0L),
                timeToUsefulRxP50Ms = json.optLong("time_to_useful_rx_p50_ms", 0L),
                timeToUsefulRxP95Ms = json.optLong("time_to_useful_rx_p95_ms", 0L),
                timeToUsefulRxMinMs = json.optLong("time_to_useful_rx_min_ms", 0L),
                timeToUsefulRxMaxMs = json.optLong("time_to_useful_rx_max_ms", 0L),
                timeToUsefulRxAvgMs = json.optLong("time_to_useful_rx_avg_ms", 0L),
                cutoffSuspectedCount = json.optLong("cutoff_suspected_count", 0L),
                failuresByStage = stagesMap,
                failuresByTimeout = timeoutMap,
                activeConnectionsCount = json.optLong("active_connections_count", 0L),
                lastUsefulRxTimestampMs = json.optLong("last_useful_rx_timestamp_ms", 0L),
                currentNetworkGeneration = json.optLong("current_network_generation", 1L)
            )
        }
    }
}

data class StageTimelineReport(
    val sli: StageTimelineSliSummary,
    val attempts: List<ConnectionTimelineItem>
) {
    companion object {
        fun fromJson(jsonStr: String): StageTimelineReport? {
            return try {
                val root = JSONObject(jsonStr)
                val sliJson = root.optJSONObject("sli") ?: JSONObject()
                val sli = StageTimelineSliSummary.fromJson(sliJson)

                val attemptsList = mutableListOf<ConnectionTimelineItem>()
                val arr = root.optJSONArray("attempts")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val itemObj = arr.optJSONObject(i) ?: continue
                        attemptsList.add(ConnectionTimelineItem.fromJson(itemObj))
                    }
                }
                StageTimelineReport(sli = sli, attempts = attemptsList)
            } catch (_: Exception) {
                null
            }
        }
    }
}
