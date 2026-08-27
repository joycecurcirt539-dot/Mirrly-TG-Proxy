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

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress

class DohResolverTest {

    @BeforeEach
    fun setUp() {
        DohResolver.clearCache()
    }

    @Test
    fun testParseDohJsonResponseCloudflareFormat() {
        val cloudflareJson = """
            {
              "Status": 0,
              "TC": false,
              "RD": true,
              "RA": true,
              "AD": false,
              "CD": false,
              "Question": [
                {
                  "name": "example.com.",
                  "type": 1
                }
              ],
              "Answer": [
                {
                  "name": "example.com.",
                  "type": 1,
                  "TTL": 300,
                  "data": "93.184.216.34"
                },
                {
                  "name": "example.com.",
                  "type": 1,
                  "TTL": 300,
                  "data": "93.184.216.35"
                }
              ]
            }
        """.trimIndent()

        val parsed = DohResolver.parseDohJsonResponse(cloudflareJson, "example.com")
        assertNotNull(parsed)
        val (addresses, ttl) = parsed!!
        assertEquals(2, addresses.size)
        assertEquals("93.184.216.34", addresses[0].hostAddress)
        assertEquals("93.184.216.35", addresses[1].hostAddress)
        assertEquals(300L, ttl)
    }

    @Test
    fun testParseDohJsonResponseGoogleFormat() {
        val googleJson = """
            {
              "Status": 0,
              "TC": false,
              "RD": true,
              "RA": true,
              "AD": false,
              "CD": false,
              "Question": [
                {
                  "name": "cloudflare.com.",
                  "type": 1
                }
              ],
              "Answer": [
                {
                  "name": "cloudflare.com.",
                  "type": 1,
                  "TTL": 120,
                  "data": "104.16.132.229"
                }
              ]
            }
        """.trimIndent()

        val parsed = DohResolver.parseDohJsonResponse(googleJson, "cloudflare.com")
        assertNotNull(parsed)
        val (addresses, ttl) = parsed!!
        assertEquals(1, addresses.size)
        assertEquals("104.16.132.229", addresses[0].hostAddress)
        assertEquals(120L, ttl)
    }

    @Test
    fun testParseDohJsonResponseErrorOrEmpty() {
        // Status != 0 (NXDOMAIN or SERVFAIL)
        val errorJson = """
            {
              "Status": 3,
              "TC": false,
              "RD": true,
              "RA": true,
              "AD": false,
              "CD": false,
              "Question": [
                {
                  "name": "nonexistent.domain.xyz.",
                  "type": 1
                }
              ],
              "Comment": ["Response from 1.1.1.1."]
            }
        """.trimIndent()

        val parsedError = DohResolver.parseDohJsonResponse(errorJson, "nonexistent.domain.xyz")
        assertNull(parsedError)

        // Empty answer array
        val emptyAnswerJson = """
            {
              "Status": 0,
              "Question": [{"name": "example.com", "type": 1}],
              "Answer": []
            }
        """.trimIndent()
        assertNull(DohResolver.parseDohJsonResponse(emptyAnswerJson, "example.com"))

        // Malformed JSON
        assertNull(DohResolver.parseDohJsonResponse("{invalid_json", "example.com"))
    }

    @Test
    fun testLocalLruTtlCacheHitAndExpiry() {
        val testIp = InetAddress.getByName("1.2.3.4")
        val domain = "my-custom-worker.dev"

        // Put in cache with 60 seconds TTL
        DohResolver.putInCache(domain, listOf(testIp), 60L, "TestProvider")

        val entry = DohResolver.getFromCache(domain)
        assertNotNull(entry)
        assertEquals(domain, entry?.domain)
        assertEquals("1.2.3.4", entry?.addresses?.firstOrNull()?.hostAddress)
        assertFalse(entry?.isExpired ?: true)

        // Resolve should return cached result immediately without network query
        val resolved = runBlocking { DohResolver.resolve(domain) }
        assertEquals(1, resolved.size)
        assertEquals("1.2.3.4", resolved[0].hostAddress)

        // Clear cache
        DohResolver.clearCache()
        assertNull(DohResolver.getFromCache(domain))
    }

    @Test
    fun testNumericIpResolutionDirectly() {
        val ipStr = "127.0.0.1"
        val resolved = runBlocking { DohResolver.resolve(ipStr) }
        assertEquals(1, resolved.size)
        assertEquals("127.0.0.1", resolved[0].hostAddress)
    }

    @Test
    fun testDohOkHttpDns() {
        val testIp = InetAddress.getByName("104.21.5.8")
        val domain = "mirrly-test.workers.dev"

        DohResolver.putInCache(domain, listOf(testIp), 120L, "Unit-Test")

        val addresses = DohOkHttpDns.INSTANCE.lookup(domain)
        assertEquals(1, addresses.size)
        assertEquals("104.21.5.8", addresses[0].hostAddress)
    }

    @Test
    fun testIsCloudflareTargetDomain() {
        assertTrue(DohResolver.isCloudflareTargetDomain("my-worker.workers.dev"))
        assertTrue(DohResolver.isCloudflareTargetDomain("https://custom-proxy.pages.dev/"))
        assertTrue(DohResolver.isCloudflareTargetDomain("cloudflare-dns.com"))
        assertFalse(DohResolver.isCloudflareTargetDomain("example.org"))
    }

    @Test
    fun testCloudflareAnycastFallbackForWorkers() {
        val domain = "blocked-or-unregistered-worker.workers.dev"
        val addresses = runBlocking { DohResolver.resolve(domain) }
        assertNotNull(addresses)
        assertTrue(addresses.isNotEmpty())
        // Should resolve via DoH or Cloudflare Anycast fallback
        val anycastIps = DohResolver.CF_ANYCAST_FALLBACK_IPS.map { it.hostAddress }
        assertTrue(addresses.any { anycastIps.contains(it.hostAddress) || it.hostAddress.startsWith("104.") || it.hostAddress.startsWith("172.") || it.hostAddress.startsWith("188.114.") })
    }
}
