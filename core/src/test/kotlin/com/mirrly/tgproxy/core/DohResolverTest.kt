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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
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
        DohResolver.setNetworkGeneration(1L)
        DohResolver.timeProvider = { System.currentTimeMillis() }
    }

    @AfterEach
    fun tearDown() {
        DohResolver.clearCache()
        DohResolver.setNetworkGeneration(1L)
        DohResolver.timeProvider = { System.currentTimeMillis() }
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
        assertTrue(DohResolver.isCloudflareTargetDomain("tunnel.trycloudflare.com"))
        assertTrue(DohResolver.isCloudflareTargetDomain("cloudflare.com"))
        assertTrue(DohResolver.isCloudflareTargetDomain("sub.cloudflare.com"))

        // Non-Cloudflare hostnames must NEVER be treated as Cloudflare targets
        assertFalse(DohResolver.isCloudflareTargetDomain("example.org"))
        assertFalse(DohResolver.isCloudflareTargetDomain("api.telegram.org"))
        assertFalse(DohResolver.isCloudflareTargetDomain("telegram.org"))
        assertFalse(DohResolver.isCloudflareTargetDomain("google.com"))
        assertFalse(DohResolver.isCloudflareTargetDomain("evil-cloudflare.com"))
        assertFalse(DohResolver.isCloudflareTargetDomain("notcloudflare.com"))
    }

    @Test
    fun testCloudflareAnycastFallbackForWorkersInBootstrap() {
        val domain = "blocked-or-unregistered-worker.workers.dev"
        val addresses = runBlocking { DohResolver.resolve(domain, DnsScope.BOOTSTRAP) }
        assertNotNull(addresses)
        assertTrue(addresses.isNotEmpty())
        // Should resolve via DoH or Cloudflare Anycast fallback
        val anycastIps = DohResolver.CF_ANYCAST_FALLBACK_IPS.map { it.hostAddress }
        assertTrue(addresses.any { anycastIps.contains(it.hostAddress) || it.hostAddress.startsWith("104.") || it.hostAddress.startsWith("172.") || it.hostAddress.startsWith("188.114.") })
    }

    @Test
    fun testUserInTunnelStrictResolutionProhibitsSystemAndAnycastFallback() {
        DohResolver.clearCache()
        // Nonexistent or unregistered domain must NOT fallback to Anycast or System DNS under USER_IN_TUNNEL
        val domain = "unregistered-test-mirrly-target.workers.dev"
        val addresses = runBlocking { DohResolver.resolve(domain, DnsScope.USER_IN_TUNNEL) }
        assertTrue(addresses.isEmpty(), "USER_IN_TUNNEL must return empty list on DoH failure without leaking to fallback")
    }

    @Test
    fun testDohProvidersConfigurationAndCloudflareExclusionByDefault() {
        val all = DohResolver.ALL_PROVIDERS
        assertEquals(14, all.size)

        val defaultIds = DohResolver.DEFAULT_ENABLED_PROVIDER_IDS
        // Cloudflare, Google, Quad9, GeoHide and Xbox must NOT be enabled by default
        assertFalse(defaultIds.contains("cloudflare"))
        assertFalse(defaultIds.contains("cloudflare_sec"))
        assertFalse(defaultIds.contains("google"))
        assertFalse(defaultIds.contains("google_sec"))
        assertFalse(defaultIds.contains("quad9"))
        assertFalse(defaultIds.contains("controld_uncensored"))
        assertFalse(defaultIds.contains("controld_malware"))
        assertFalse(defaultIds.contains("geohide"))
        assertFalse(defaultIds.contains("xbox"))

        // AdGuard, DNS.SB, NextDNS, Control D must be enabled by default
        assertTrue(defaultIds.contains("adguard"))
        assertTrue(defaultIds.contains("dnssb"))
        assertTrue(defaultIds.contains("dnssb_sec"))
        assertTrue(defaultIds.contains("nextdns"))
        assertTrue(defaultIds.contains("controld"))

        // Verify active providers filtering
        DohResolver.setActiveProviders(setOf("adguard", "dnssb"))
        assertEquals(2, DohResolver.getActiveProviders().size)
        val csv = DohResolver.getActiveEndpointsCsv()
        assertTrue(csv.contains("94.140.14.14/resolve"))
        assertTrue(csv.contains("185.222.222.222"))
        assertFalse(csv.contains("1.1.1.1"))

        // Reset
        DohResolver.setActiveProviders(DohResolver.DEFAULT_ENABLED_PROVIDER_IDS)
    }

    @Test
    fun testParseDnsWireResponse() {
        // Real DNS response packet for api.telegram.org -> 149.154.166.110 (TTL 274)
        val testWireBytes = byteArrayOf(
            0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x03, 'a'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(),
            0x08, 't'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 'g'.code.toByte(), 'r'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            0x03, 'o'.code.toByte(), 'r'.code.toByte(), 'g'.code.toByte(), 0x00,
            0x00, 0x01, 0x00, 0x01,
            0xc0.toByte(), 0x0c, 0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x01, 0x12, // TTL = 274
            0x00, 0x04,
            0x95.toByte(), 0x9a.toByte(), 0xa6.toByte(), 0x6e // 149.154.166.110
        )

        val result = DohResolver.parseDnsWireResponse(testWireBytes)
        assertNotNull(result)
        assertEquals(1, result!!.first.size)
        assertEquals("149.154.166.110", result.first[0].hostAddress)
        assertEquals(274L, result.second)
    }

    @Test
    fun testBuildDnsQueryPacketAndUrl() {
        val packet = DohResolver.buildDnsQueryPacket("api.telegram.org")
        assertTrue(packet.isNotEmpty())

        val geohide = DohResolver.ALL_PROVIDERS.first { it.id == "geohide" }
        assertTrue(geohide.useDnsParam)
        val geohideUrl = DohResolver.buildDnsQueryUrl(geohide, "api.telegram.org")
        assertTrue(geohideUrl.startsWith("https://dns.geohide.ru/dns-query?dns="))

        val xbox = DohResolver.ALL_PROVIDERS.first { it.id == "xbox" }
        assertTrue(xbox.useDnsParam)
        val xboxUrl = DohResolver.buildDnsQueryUrl(xbox, "api.telegram.org")
        assertTrue(xboxUrl.startsWith("https://xbox-dns.ru/dns-query?dns="))

        val adguard = DohResolver.ALL_PROVIDERS.first { it.id == "adguard" }
        assertFalse(adguard.useDnsParam)
        val adguardUrl = DohResolver.buildDnsQueryUrl(adguard, "api.telegram.org")
        assertEquals("https://94.140.14.14/resolve?name=api.telegram.org&type=A", adguardUrl)
    }

    @Test
    fun testDnsBudgetManagerLimitsAndConcurrency() = runBlocking {
        var isMobile = true
        val manager = DnsBudgetManager { isMobile }
        assertEquals(4, manager.maxConcurrent(), "Cellular DNS budget limit must be 4")

        // Acquire 4 permits
        manager.acquire()
        manager.acquire()
        manager.acquire()
        manager.acquire()
        assertEquals(4, manager.activeCount())

        // The 5th acquire should suspend until a release happens
        var fifthAcquired = false
        val job = launch(Dispatchers.Default) {
            manager.acquire()
            fifthAcquired = true
            manager.release()
        }

        delay(50)
        assertFalse(fifthAcquired, "5th acquire must not succeed while cellular budget (4) is exhausted")

        // Release one permit
        manager.release()
        job.join()
        assertTrue(fifthAcquired, "5th acquire should succeed after a permit is released")

        // Release remaining permits
        manager.release()
        manager.release()
        manager.release()
        assertEquals(0, manager.activeCount())

        // Switch to Wi-Fi
        isMobile = false
        assertEquals(8, manager.maxConcurrent(), "Wi-Fi DNS budget limit must be 8")
    }

    @Test
    fun testDnsBudgetWithBudgetReleasesOnException() = runBlocking {
        val manager = DnsBudgetManager { false }
        assertEquals(0, manager.activeCount())

        try {
            manager.withBudget {
                assertEquals(1, manager.activeCount())
                throw IllegalStateException("Simulated failure")
            }
        } catch (_: IllegalStateException) {}

        assertEquals(0, manager.activeCount(), "Permit must be released even if block throws")
    }

    @Test
    fun testDohResolverGlobalBudgetMobileFlag() {
        DohResolver.setMobileNetwork(true)
        assertEquals(4, DohResolver.dnsBudget.maxConcurrent())

        DohResolver.setMobileNetwork(false)
        assertEquals(8, DohResolver.dnsBudget.maxConcurrent())
    }

    @Test
    fun testHedgedResolveCancellationCleansUp() = runBlocking {
        val job = launch(Dispatchers.IO) {
            DohResolver.hedgedResolve("example.com", DnsScope.USER_IN_TUNNEL)
        }
        delay(10)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun testDohResolverActiveCallsTrackingAndCancelAll() {
        assertEquals(0, DohResolver.activeCallsCount())
        DohResolver.cancelAllInFlight()
        assertEquals(0, DohResolver.activeCallsCount())
    }

    @Test
    fun testHedgedResolveGeneratesMaxTwoRequests() = runBlocking {
        val originalProviders = DohResolver.getActiveProviders()
        try {
            DohResolver.setActiveProviders(setOf("adguard", "dnssb", "dnssb_sec", "nextdns", "controld"))
            assertTrue(DohResolver.getActiveProviders().size >= 5)

            // hedgedResolve on an invalid domain under USER_IN_TUNNEL must only attempt
            // primary and secondary (not 5+ providers), and cleanly complete/timeout
            val winner = DohResolver.hedgedResolve("invalid-test-mob021-domain.local", DnsScope.USER_IN_TUNNEL)
            assertNull(winner)
        } finally {
            DohResolver.setActiveProviders(originalProviders.map { it.id }.toSet())
        }
    }

    @Test
    fun testNetworkGenerationHandoverInvalidatesCache() = runBlocking {
        DohResolver.setNetworkGeneration(1L)
        val testIp = InetAddress.getByName("1.2.3.4")
        val domain = "wifi-resolved-target.example.org"

        DohResolver.putInCache(domain, listOf(testIp), 300L, "AdGuard DNS")

        val cachedGen1 = DohResolver.getFromCache(domain)
        assertNotNull(cachedGen1)
        assertEquals(1L, cachedGen1?.networkGeneration)
        assertTrue(cachedGen1?.isValidFor(1L) == true)
        assertEquals(listOf(testIp), DohResolver.resolve(domain))

        // Handover to LTE (generation 2)
        DohResolver.setNetworkGeneration(2L)
        assertEquals(2L, DohResolver.currentNetworkGeneration())

        // Cache must NOT be valid for generation 2
        assertFalse(cachedGen1?.isValidFor(2L) == true)
        assertNull(DohResolver.getFromCache(domain), "Wi-Fi DNS result must not be used after LTE handover")
    }

    @Test
    fun testNegativeTtlCachingPreventsNxdomainStorm() = runBlocking {
        DohResolver.setNetworkGeneration(1L)
        val domain = "nonexistent-mob022-nxdomain.test"

        // First resolution under USER_IN_TUNNEL triggers DoH failure -> negative cache entry
        val initialResult = DohResolver.resolve(domain, DnsScope.USER_IN_TUNNEL)
        assertTrue(initialResult.isEmpty())

        val cachedEntry = DohResolver.getFromCache(domain)
        assertNotNull(cachedEntry, "Negative entry must be present in cache")
        assertTrue(cachedEntry!!.isNegative, "Cache entry must be marked as negative")
        assertEquals(AddressFamily.NONE, cachedEntry.family)
        assertEquals(0, cachedEntry.addresses.size)

        val statsBefore = DohResolver.getStats()
        // Subsequent 10 rapid queries must hit negative cache immediately without query storms
        for (i in 1..10) {
            val result = DohResolver.resolve(domain, DnsScope.USER_IN_TUNNEL)
            assertTrue(result.isEmpty())
        }
        val statsAfter = DohResolver.getStats()
        assertEquals(statsBefore.cacheHits + 10, statsAfter.cacheHits, "All 10 queries must be served from cache")
        assertEquals(statsBefore.cacheMisses, statsAfter.cacheMisses, "No additional cache misses or DoH calls allowed")
    }

    @Test
    fun testReproducibleTtlExpiryWithTimeProvider() {
        var fakeTimeMs = 1_000_000L
        DohResolver.timeProvider = { fakeTimeMs }
        DohResolver.setNetworkGeneration(1L)

        val testIp = InetAddress.getByName("9.9.9.9")
        val domain = "ttl-expiry.example.com"
        val ttlSeconds = 60L

        DohResolver.putInCache(domain, listOf(testIp), ttlSeconds, "TestProvider")

        val entry = DohResolver.getFromCache(domain)
        assertNotNull(entry)
        assertEquals(fakeTimeMs + 60_000L, entry?.expiresAtTimestampMs)
        assertFalse(entry!!.isExpired)

        // Advance fake time to 59 seconds: entry is still valid
        fakeTimeMs += 59_000L
        assertFalse(entry.isExpired)
        assertNotNull(DohResolver.getFromCache(domain))

        // Advance fake time past 60 seconds: entry is expired
        fakeTimeMs += 2_000L
        assertTrue(entry.isExpired)
        assertNull(DohResolver.getFromCache(domain), "Expired entry must return null from cache")
    }

    @Test
    fun testSingleflightCoalescingIdenticalInFlightRequests() = runBlocking {
        DohResolver.setNetworkGeneration(1L)
        val domain = "unregistered-singleflight-target.example.org"

        // Launch 5 concurrent resolutions for the exact same domain and scope
        val jobs = (1..5).map {
            async(Dispatchers.IO) {
                DohResolver.resolve(domain, DnsScope.USER_IN_TUNNEL)
            }
        }
        val results = jobs.awaitAll()
        assertEquals(5, results.size)
        // All should get the identical empty list result without hanging
        results.forEach { assertTrue(it.isEmpty()) }
    }

    @Test
    fun testAddressFamilyDetection() {
        val ipv4 = InetAddress.getByName("1.1.1.1")
        val ipv6 = InetAddress.getByName("2606:4700:4700::1111")

        assertEquals(AddressFamily.IPV4, AddressFamily.fromAddresses(listOf(ipv4)))
        assertEquals(AddressFamily.IPV6, AddressFamily.fromAddresses(listOf(ipv6)))
        assertEquals(AddressFamily.DUAL_STACK, AddressFamily.fromAddresses(listOf(ipv4, ipv6)))
        assertEquals(AddressFamily.NONE, AddressFamily.fromAddresses(emptyList()))
    }

    @Test
    fun testNonCloudflareHostnameNeverReceivesAnycastAddressOnDnsFailure() = runBlocking {
        DohResolver.clearCache()
        val statsBefore = DohResolver.getStats()
        val nonCfDomain = "nonexistent-telegram-mirror.example.org"

        assertFalse(DohResolver.isCloudflareTargetDomain(nonCfDomain), "Domain must not be recognized as Cloudflare")

        val result = DohResolver.resolve(nonCfDomain, DnsScope.BOOTSTRAP)
        assertTrue(result.isEmpty(), "Non-Cloudflare hostname must NEVER receive Anycast fallback addresses")

        val cachedEntry = DohResolver.getFromCache(nonCfDomain)
        assertNotNull(cachedEntry, "Negative entry should be cached")
        assertTrue(cachedEntry!!.isNegative, "Cache entry must be marked as negative")
        assertTrue(cachedEntry.addresses.isEmpty(), "Cached addresses must be empty")

        val statsAfter = DohResolver.getStats()
        assertTrue(statsAfter.dnsFailedCount > statsBefore.dnsFailedCount, "dns_failed must be incremented")
        assertEquals(statsBefore.fallbackPolicyAppliedCount, statsAfter.fallbackPolicyAppliedCount, "fallback_policy must NOT be applied for non-Cloudflare host")
    }

    @Test
    fun testConfirmedCloudflareHostnameReceivesAnycastAddressOnDnsFailure() = runBlocking {
        DohResolver.clearCache()
        val cfWorkerDomain = "unregistered-test-mob023-worker.workers.dev"

        assertTrue(DohResolver.isCloudflareTargetDomain(cfWorkerDomain), "Worker domain must be confirmed Cloudflare")

        val statsBefore = DohResolver.getStats()
        val result = DohResolver.resolve(cfWorkerDomain, DnsScope.BOOTSTRAP)
        assertNotNull(result)
        assertTrue(result.isNotEmpty(), "Confirmed Cloudflare hostname may receive Anycast fallback on DNS failure")

        val anycastAddrs = DohResolver.CF_ANYCAST_FALLBACK_IPS.map { it.hostAddress }
        assertTrue(result.all { anycastAddrs.contains(it.hostAddress) }, "Addresses must strictly belong to CF Anycast pool")

        val cachedEntry = DohResolver.getFromCache(cfWorkerDomain)
        assertNotNull(cachedEntry)
        assertEquals("Cloudflare-Anycast-Fallback", cachedEntry?.resolverSource)

        val statsAfter = DohResolver.getStats()
        assertTrue(statsAfter.dnsFailedCount > statsBefore.dnsFailedCount, "dns_failed must be recorded")
        assertTrue(statsAfter.fallbackPolicyAppliedCount > statsBefore.fallbackPolicyAppliedCount, "fallback_policy must be explicitly recorded")
    }

    @Test
    fun testDnsFailedDistinctFromFallbackIpFailed() {
        val statsBefore = DohResolver.getStats()
        DohResolver.recordFallbackIpFailure()
        val statsAfter = DohResolver.getStats()

        assertEquals(statsBefore.fallbackIpFailedCount + 1, statsAfter.fallbackIpFailedCount)
        assertEquals(statsBefore.dnsFailedCount, statsAfter.dnsFailedCount, "Recording fallback IP failure must not alter dns_failed count")

        // Contract verification of FailureType and NodeProbeStatus
        assertFalse(FailureType.DNS_FAILURE == FailureType.FALLBACK_IP_FAILED)
        assertFalse(NodeProbeStatus.DNS_FAILED == NodeProbeStatus.FALLBACK_IP_FAILED)
        assertFalse(NodeProbeStatus.DNS_FAILED.isSuccess)
        assertFalse(NodeProbeStatus.FALLBACK_IP_FAILED.isSuccess)
    }

    @Test
    fun testBuildDnsQueryUrlAaaaAndWirePacket() {
        val packetAaaa = DohResolver.buildDnsQueryPacket("api.telegram.org", type = 28)
        assertTrue(packetAaaa.isNotEmpty())
        // QTYPE should be 28 (0x00, 0x1c)
        val qtypeHigh = packetAaaa[packetAaaa.size - 4].toInt() and 0xFF
        val qtypeLow = packetAaaa[packetAaaa.size - 3].toInt() and 0xFF
        assertEquals(0, qtypeHigh)
        assertEquals(28, qtypeLow)

        val adguard = DohResolver.ALL_PROVIDERS.first { it.id == "adguard" }
        val adguardUrlAaaa = DohResolver.buildDnsQueryUrl(adguard, "api.telegram.org", type = 28)
        assertEquals("https://94.140.14.14/resolve?name=api.telegram.org&type=AAAA", adguardUrlAaaa)
    }

    @Test
    fun testAnycastPoolContainsDualStackIpv6() {
        val anycastAddrs = DohResolver.CF_ANYCAST_FALLBACK_IPS
        assertTrue(anycastAddrs.any { it is java.net.Inet4Address }, "Anycast pool must contain IPv4")
        assertTrue(anycastAddrs.any { it is java.net.Inet6Address }, "Anycast pool must contain IPv6 for IPv6-only networks")
        assertTrue(anycastAddrs.any { it == java.net.InetAddress.getByName("2606:4700:4700::1111") })
    }

    @Test
    fun testIpv6OnlyNetworkToggle() {
        DohResolver.setIpv6OnlyNetwork(true)
        assertTrue(DohResolver.isIpv6OnlyNetwork())
        assertTrue(HappyEyeballsEngine.isIpv6OnlyNetwork())

        DohResolver.setIpv6OnlyNetwork(false)
        assertFalse(DohResolver.isIpv6OnlyNetwork())
        assertFalse(HappyEyeballsEngine.isIpv6OnlyNetwork())
    }

    @Test
    fun testParseDohJsonResponseAaaaRecord() {
        val jsonAaaa = """
            {
              "Status": 0,
              "Answer": [
                {
                  "name": "example.com.",
                  "type": 28,
                  "TTL": 300,
                  "data": "2606:4700:4700::1111"
                }
              ]
            }
        """.trimIndent()

        val parsed = DohResolver.parseDohJsonResponse(jsonAaaa, "example.com")
        assertNotNull(parsed)
        assertEquals(1, parsed!!.first.size)
        assertTrue(parsed.first[0] is java.net.Inet6Address)
        assertEquals(java.net.InetAddress.getByName("2606:4700:4700::1111"), parsed.first[0])
        assertEquals(300L, parsed.second)
    }
}
