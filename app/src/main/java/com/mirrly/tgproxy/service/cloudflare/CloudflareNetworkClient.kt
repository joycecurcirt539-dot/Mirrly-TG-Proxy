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

package com.mirrly.tgproxy.service.cloudflare

import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.DohOkHttpDns
import com.mirrly.tgproxy.core.TlsFragmentingSocketFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * High-resilience HTTP client for Cloudflare OAuth, Token exchange, and API operations.
 *
 * Features:
 * 1. Secure DoH resolution (DohOkHttpDns) + TLS ClientHello fragmentation (TlsFragmentingSocketFactory)
 *    to bypass ISP / TSPU SNI filtering without requiring external proxying.
 * 2. Direct connection fallback for non-censored networks.
 * 3. Dedicated zero-proxy client for loopback health checks and self-tests.
 * 4. Robust dual-stack loopback address validation (IPv4, IPv6, and IPv4-mapped IPv6).
 */
object CloudflareNetworkClient {

    private const val TAG = "CloudflareNetworkClient"
    const val SOCKS5_DEFAULT_PORT = 10808

    /**
     * Dedicated loopback client strictly isolated from external proxies and VPN hijack.
     */
    val loopbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .writeTimeout(2500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Client utilizing encrypted DoH and TCP-level TLS ClientHello fragmentation to bypass TSPU.
     */
    private val dohTlsHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DohOkHttpDns.INSTANCE)
            .socketFactory(TlsFragmentingSocketFactory())
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Direct standard client used as fallback.
     */
    private val directHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Checks whether an incoming connection IP address is an authentic loopback origin.
     * Supports:
     * - Standard IPv4 loopback (127.0.0.1, 127.0.0.0/8)
     * - Standard IPv6 loopback (::1, 0:0:0:0:0:0:0:1)
     * - Dual-stack IPv4-mapped IPv6 loopback (::ffff:127.0.0.1)
     */
    fun isLoopbackAddress(address: InetAddress?): Boolean {
        if (address == null) return false
        if (address.isLoopbackAddress) return true
        val host = address.hostAddress ?: return false
        return host == "127.0.0.1" ||
                host == "::1" ||
                host == "0:0:0:0:0:0:0:1" ||
                host.endsWith("127.0.0.1") ||
                host.startsWith("127.")
    }

    /**
     * Executes an HTTP request using an anti-censorship fallback pipeline:
     * Tier 1: DoH + TLS ClientHello fragmentation (bypasses ISP/TSPU DPI blocks directly).
     * Tier 2: Direct connection fallback.
     */
    @Throws(IOException::class)
    fun executeWithFallback(request: Request): Response {
        var lastException: IOException? = null

        // 1. DoH + TLS fragmentation (Bypasses Russian ISP / TSPU blocks)
        try {
            AppLogger.d(TAG, "Executing Cloudflare request via DoH + TLS fragmentation: ${request.url}")
            return dohTlsHttpClient.newCall(request).execute()
        } catch (e: IOException) {
            AppLogger.w(TAG, "DoH+TLS request to Cloudflare failed (${e.message}), falling back to direct connection")
            lastException = e
        }

        // 2. Direct connection fallback
        try {
            AppLogger.d(TAG, "Executing Cloudflare request via direct connection: ${request.url}")
            return directHttpClient.newCall(request).execute()
        } catch (e: IOException) {
            AppLogger.e(TAG, "All Cloudflare connection tiers failed: ${e.message}")
            throw lastException ?: e
        }
    }
}
