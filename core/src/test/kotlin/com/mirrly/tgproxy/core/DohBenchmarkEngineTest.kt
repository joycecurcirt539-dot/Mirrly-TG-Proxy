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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DohBenchmarkEngineTest {

    @Test
    fun testTelegramSubnetValidation() {
        // Valid Telegram official datacenter IPv4 addresses (AS44907)
        assertTrue(DohBenchmarkEngine.isTelegramIp("149.154.167.220")) // api.telegram.org
        assertTrue(DohBenchmarkEngine.isTelegramIp("149.154.175.50"))  // DC1
        assertTrue(DohBenchmarkEngine.isTelegramIp("149.154.167.51"))  // DC2
        assertTrue(DohBenchmarkEngine.isTelegramIp("149.154.175.100")) // DC3
        assertTrue(DohBenchmarkEngine.isTelegramIp("149.154.167.91"))  // DC4
        assertTrue(DohBenchmarkEngine.isTelegramIp("91.108.56.130"))   // DC5
        assertTrue(DohBenchmarkEngine.isTelegramIp("91.105.192.100"))  // DC203

        // Valid Telegram IPv6 prefixes
        assertTrue(DohBenchmarkEngine.isTelegramIp("2001:b28:f23d:f001::a"))
        assertTrue(DohBenchmarkEngine.isTelegramIp("2001:67c:4e8:f002::4"))

        // Non-Telegram public IPs
        assertFalse(DohBenchmarkEngine.isTelegramIp("8.8.8.8"))
        assertFalse(DohBenchmarkEngine.isTelegramIp("1.1.1.1"))
        assertFalse(DohBenchmarkEngine.isTelegramIp("9.9.9.9"))
        assertFalse(DohBenchmarkEngine.isTelegramIp("195.82.146.214")) // Russian ISP stub
    }

    @Test
    fun testBogonDetection() {
        assertTrue(DohBenchmarkEngine.isBogon("127.0.0.1"))
        assertTrue(DohBenchmarkEngine.isBogon("127.0.0.2"))
        assertTrue(DohBenchmarkEngine.isBogon("0.0.0.0"))
        assertTrue(DohBenchmarkEngine.isBogon("10.0.0.1"))
        assertTrue(DohBenchmarkEngine.isBogon("192.168.1.100"))
        assertTrue(DohBenchmarkEngine.isBogon("172.16.0.1"))
        assertTrue(DohBenchmarkEngine.isBogon("172.31.255.255"))
        assertTrue(DohBenchmarkEngine.isBogon("100.64.0.1")) // CGNAT
        assertTrue(DohBenchmarkEngine.isBogon("169.254.1.1")) // Link-local
        assertTrue(DohBenchmarkEngine.isBogon("224.0.0.1")) // Multicast
        assertTrue(DohBenchmarkEngine.isBogon("::1")) // Loopback IPv6

        // Public non-bogons
        assertFalse(DohBenchmarkEngine.isBogon("149.154.167.220"))
        assertFalse(DohBenchmarkEngine.isBogon("9.9.9.9"))
        assertFalse(DohBenchmarkEngine.isBogon("94.140.14.14"))
    }

    @Test
    fun testParseAndVerifyDnsResponseSuccess() {
        val validJson = """
            {
              "Status": 0,
              "Answer": [
                {
                  "name": "api.telegram.org.",
                  "type": 1,
                  "TTL": 300,
                  "data": "149.154.167.220"
                }
              ]
            }
        """.trimIndent()

        val res = DohBenchmarkEngine.parseAndVerifyDnsResponse(validJson)
        assertTrue(res.isDnsSuccess)
        assertFalse(res.isPoisoned)
        assertEquals(listOf("149.154.167.220"), res.ips)
        assertEquals("AS44907 validated", res.detail)
    }

    @Test
    fun testParseAndVerifyDnsResponseBogonPoisoned() {
        val loopbackJson = """
            {
              "Status": 0,
              "Answer": [
                {
                  "name": "api.telegram.org.",
                  "type": 1,
                  "TTL": 300,
                  "data": "127.0.0.1"
                }
              ]
            }
        """.trimIndent()

        val res = DohBenchmarkEngine.parseAndVerifyDnsResponse(loopbackJson)
        assertTrue(res.isDnsSuccess)
        assertTrue(res.isPoisoned)
        assertTrue(res.detail.contains("Bogon/Loopback"))
    }

    @Test
    fun testParseAndVerifyDnsResponseForeignIpPoisoned() {
        val ispRedirectJson = """
            {
              "Status": 0,
              "Answer": [
                {
                  "name": "api.telegram.org.",
                  "type": 1,
                  "TTL": 300,
                  "data": "195.82.146.214"
                }
              ]
            }
        """.trimIndent()

        val res = DohBenchmarkEngine.parseAndVerifyDnsResponse(ispRedirectJson)
        assertTrue(res.isDnsSuccess)
        assertTrue(res.isPoisoned)
        assertTrue(res.detail.contains("does not belong to Telegram"))
    }

    @Test
    fun testParseAndVerifyDnsResponseDnsError() {
        val nxdomainJson = """
            {
              "Status": 3,
              "Answer": []
            }
        """.trimIndent()

        val res = DohBenchmarkEngine.parseAndVerifyDnsResponse(nxdomainJson)
        assertFalse(res.isDnsSuccess)
        assertTrue(res.detail.contains("Status=3"))
    }

    @Test
    fun testAnalyzeAndRecommendRanking() {
        val mockResults = listOf(
            DohBenchmarkResult(
                providerId = "quad9",
                providerName = "Quad9",
                latencyMs = 28L,
                status = DohHealthStatus.EXCELLENT,
                resolvedIps = listOf("149.154.167.220"),
                statusDetail = "AS44907 OK"
            ),
            DohBenchmarkResult(
                providerId = "adguard",
                providerName = "AdGuard DNS",
                latencyMs = 18L,
                status = DohHealthStatus.EXCELLENT,
                resolvedIps = listOf("149.154.167.220"),
                statusDetail = "AS44907 OK"
            ),
            DohBenchmarkResult(
                providerId = "comms",
                providerName = "COMMS DNS",
                latencyMs = 45L,
                status = DohHealthStatus.EXCELLENT,
                resolvedIps = listOf("149.154.167.220"),
                statusDetail = "AS44907 OK"
            ),
            DohBenchmarkResult(
                providerId = "cloudflare",
                providerName = "Cloudflare DNS",
                latencyMs = 2500L,
                status = DohHealthStatus.BLOCKED,
                resolvedIps = emptyList(),
                statusDetail = "DPI block / connection reset"
            ),
            DohBenchmarkResult(
                providerId = "fake_isp",
                providerName = "Fake ISP",
                latencyMs = 12L,
                status = DohHealthStatus.POISONED,
                resolvedIps = listOf("127.0.0.1"),
                statusDetail = "Bogon"
            )
        )

        val report = DohBenchmarkEngine.analyzeAndRecommend(mockResults)

        // Only clean & usable providers can be recommended: adguard (18ms), quad9 (28ms), comms (45ms)
        assertEquals(setOf("adguard", "quad9", "comms"), report.recommendedProviderIds)
        assertFalse(report.recommendedProviderIds.contains("cloudflare"))
        assertFalse(report.recommendedProviderIds.contains("fake_isp"))

        // Top providers must have isRecommended = true
        val adguardResult = report.results.find { it.providerId == "adguard" }
        assertTrue(adguardResult?.isRecommended == true)

        val cloudflareResult = report.results.find { it.providerId == "cloudflare" }
        assertFalse(cloudflareResult?.isRecommended == true)

        assertTrue(report.summaryText.contains("3 best servers"))
    }

    @Test
    fun testAnalyzeAndRecommendAllBlocked() {
        val mockResults = listOf(
            DohBenchmarkResult(
                providerId = "p1",
                providerName = "P1",
                latencyMs = 2500L,
                status = DohHealthStatus.TIMEOUT,
                resolvedIps = emptyList(),
                statusDetail = "Timeout"
            ),
            DohBenchmarkResult(
                providerId = "p2",
                providerName = "P2",
                latencyMs = 100L,
                status = DohHealthStatus.BLOCKED,
                resolvedIps = emptyList(),
                statusDetail = "TCP RST"
            )
        )

        val report = DohBenchmarkEngine.analyzeAndRecommend(mockResults)
        assertTrue(report.recommendedProviderIds.isEmpty())
        assertTrue(report.summaryText.contains("unreachable or blocked"))
    }
}
