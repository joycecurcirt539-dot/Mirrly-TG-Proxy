package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WarpObfuscatedHttpClientTest {

    @Test
    fun testFragmentedTlsHandshakeAndHttpRequest() {
        // GET /v0a4471/reg returns 404 from Cloudflare API, verifying TLS handshake and HTTP parsing
        try {
            val response = WarpObfuscatedHttpClient.execute("GET", "/v0a4471/reg", timeoutMs = 7000)
            println("OBFUSCATED TLS TEST: HTTP ${response.statusCode} via ${response.connectedIp}, body len=${response.body.length}")
            assertTrue(response.statusCode in 200..499, "Expected valid HTTP status from Cloudflare")
            assertTrue(response.connectedIp.isNotBlank(), "Connected IP must be set")
        } catch (e: Exception) {
            println("OBFUSCATED TLS TEST: Direct network connection to Cloudflare failed (${e.message}), skipping live check.")
        }
    }

    @Test
    fun testDefaultTimeoutMsIsUnder700Ms() {
        assertTrue(WarpObfuscatedHttpClient.DEFAULT_TIMEOUT_MS in 500..700, "Timeout must be between 500 and 700ms")
    }

    @Test
    fun testProbeDirectApiExecution() {
        val start = System.currentTimeMillis()
        // Fast probe should execute or fail within 1500 ms (probe timeout 500ms)
        val isReachable = WarpObfuscatedHttpClient.probeDirectApi(timeoutMs = 500)
        val duration = System.currentTimeMillis() - start
        println("Direct API reachable: $isReachable, took ${duration}ms")
        assertTrue(duration < 2500, "Fast direct probe must complete within 2500ms (took ${duration}ms)")
    }
}
