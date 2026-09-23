/*
 * Mirrly TG Proxy - Diagnostic Report Generator Test (Task 05)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportGeneratorTest {

    @Test
    fun effectiveRouteUsesAnycastForMtprotoInsteadOfSelectedWorker() {
        val route = DiagnosticReportGenerator.effectiveRouteFor(isSocks5Mode = false)

        assertEquals("ANYCAST_CDN", route.uplink)
        assertFalse(route.usesWorker)
        assertTrue(route.description.contains("Flowseal"))
    }

    @Test
    fun effectiveRouteUsesWorkerForSocks5() {
        val route = DiagnosticReportGenerator.effectiveRouteFor(isSocks5Mode = true)

        assertEquals("WORKER", route.uplink)
        assertTrue(route.usesWorker)
    }

    @Test
    fun testSanitizeWorkerDomainReplacesPersonalPrefix() {
        val domain = "my-secret-team.workers.dev"
        val sanitized = DiagnosticReportGenerator.sanitizeWorkerDomain(domain)
        assertEquals("***.workers.dev", sanitized)
    }

    @Test
    fun testSanitizeWorkerDomainPreservesDefaultNode() {
        assertEquals("Default Node", DiagnosticReportGenerator.sanitizeWorkerDomain(""))
        assertEquals("Default Node", DiagnosticReportGenerator.sanitizeWorkerDomain("   "))
    }

    @Test
    fun testSanitizeTextRedactsWireGuardBase64Keys() {
        val textWithKey = "Connecting using private key aGVsbG8gd29ybGQgdGhpcyBpcyBhIHZhbGlkIGtleTEyMzQ= on endpoint"
        val sanitized = DiagnosticReportGenerator.sanitizeText(textWithKey)

        assertFalse(sanitized.contains("aGVsbG8gd29ybGQgdGhpcyBpcyBhIHZhbGlkIGtleTEyMzQ="))
        assertTrue(sanitized.contains("[REDACTED_KEY]"))
    }

    @Test
    fun testSanitizeTextRedactsTokensAndPasswords() {
        val text = "Request auth failed: token=secret_token_12345, password: super_password_xyz, auth secret_key_abc"
        val sanitized = DiagnosticReportGenerator.sanitizeText(text)

        assertFalse(sanitized.contains("secret_token_12345"))
        assertFalse(sanitized.contains("super_password_xyz"))
        assertTrue(sanitized.contains("token=[REDACTED]"))
        assertTrue(sanitized.contains("password=[REDACTED]"))
    }

    @Test
    fun testSanitizeTextRedactsPersonalIpv4Addresses() {
        val text = "Client connected from 178.62.195.42 to local 127.0.0.1 and Cloudflare 162.159.192.1"
        val sanitized = DiagnosticReportGenerator.sanitizeText(text)

        assertFalse(sanitized.contains("178.62.195.42"))
        assertTrue(sanitized.contains("xxx.xxx.xxx.xxx"))
        // Localhost and Cloudflare Anycast are kept for technical context
        assertTrue(sanitized.contains("127.0.0.1"))
        assertTrue(sanitized.contains("162.159.192.1"))
    }
}
