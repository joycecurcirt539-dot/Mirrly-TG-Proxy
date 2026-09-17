/*
 * Mirrly TG Proxy - Connection Health Formatter Test (Task 06)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui

import com.mirrly.tgproxy.core.FailureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConnectionHealthFormatterTest {

    @Test
    fun testFormatFailureTechnicalOutputsCodeAndStage() {
        val formatted = ConnectionHealthFormatter.formatFailureTechnical(
            failureType = FailureType.WORKER_QUOTA_EXCEEDED,
            httpStatus = 429,
            detail = "Daily limit 100k requests reached"
        )

        assertTrue(formatted.contains("WORKER_QUOTA_EXCEEDED"))
        assertTrue(formatted.contains("[stage=WSS]"))
        assertTrue(formatted.contains("[http=429]"))
        assertTrue(formatted.contains("(Daily limit 100k requests reached)"))
    }

    @Test
    fun testFormatFailureTechnicalDnsStage() {
        val formatted = ConnectionHealthFormatter.formatFailureTechnical(
            failureType = FailureType.DNS_RESOLUTION_UNAVAILABLE
        )

        assertTrue(formatted.contains("DNS_RESOLUTION_UNAVAILABLE"))
        assertTrue(formatted.contains("[stage=DNS]"))
    }

    @Test
    fun testFormatFailureTechnicalWarpTimeoutStage() {
        val formatted = ConnectionHealthFormatter.formatFailureTechnical(
            failureType = FailureType.WARP_HANDSHAKE_TIMEOUT
        )

        assertTrue(formatted.contains("WARP_HANDSHAKE_TIMEOUT"))
        assertTrue(formatted.contains("[stage=TCP]"))
    }

    @Test
    fun testAllFailureTypesHaveConsistentTechnicalCodes() {
        for (failure in FailureType.values()) {
            val code = failure.technicalCode.ifEmpty { failure.name }
            assertTrue("Failure $failure must have non-empty technical code", code.isNotBlank())
            assertTrue("Failure $failure must have non-empty description", failure.description.isNotBlank())
        }
    }

    @Test
    fun testFailureStringsExistInRussianAndEnglish() {
        val userDir = System.getProperty("user.dir") ?: "."
        val rootDir = if (userDir.endsWith("app")) File(userDir).parentFile else File(userDir)
        val resRu = File(rootDir, "app/src/main/res/values/strings.xml")
        val resEn = File(rootDir, "app/src/main/res/values-en/strings.xml")

        assertTrue("Russian strings.xml must exist", resRu.exists())
        assertTrue("English strings.xml must exist", resEn.exists())

        val ruContent = resRu.readText()
        val enContent = resEn.readText()

        val failureStringKeys = listOf(
            "failure_worker_quota_exceeded",
            "failure_dns_resolution_unavailable",
            "failure_socks5_auth_rejected",
            "failure_cloudflare_edge_blocked",
            "failure_warp_handshake_timeout",
            "failure_network_interface_down",
            "failure_connect_timeout",
            "failure_host_unreachable",
            "failure_tls_handshake_failed",
            "failure_relay_ack_failed",
            "failure_predictive_degradation",
            "failure_unknown"
        )

        for (key in failureStringKeys) {
            assertTrue("Key $key must exist in Russian strings.xml", ruContent.contains("name=\"$key\""))
            assertTrue("Key $key must exist in English strings.xml", enContent.contains("name=\"$key\""))
        }
    }
}
