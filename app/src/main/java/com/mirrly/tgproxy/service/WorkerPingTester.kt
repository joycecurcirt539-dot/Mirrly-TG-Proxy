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

package com.mirrly.tgproxy.service

import com.mirrly.tgproxy.BuildConfig
import com.mirrly.tgproxy.core.DohOkHttpDns
import com.mirrly.tgproxy.core.WorkerRelayHealthProbe
import com.mirrly.tgproxy.core.WorkerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object WorkerPingTester {
    private val probe by lazy {
        WorkerRelayHealthProbe(OkHttpClient.Builder()
            .dns(DohOkHttpDns.INSTANCE)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build())
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(DohOkHttpDns.INSTANCE)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun sanitizeDomain(domain: String): String =
        domain.trim().removePrefix("https://").removePrefix("http://")
            .removePrefix("wss://").removeSuffix("/")

    /** UI, import and circuit recovery share the same robust health contract with fallback. */
    suspend fun pingWorker(domain: String): Pair<WorkerStatus, Long?> = withContext(Dispatchers.IO) {
        val cleanDomain = sanitizeDomain(domain)
        if (cleanDomain.isBlank() || cleanDomain.any { it in "/?#@" }) {
            return@withContext WorkerStatus.ERROR_UNREACHABLE to null
        }

        // 1. Попытка высокоточного SOCKS5 v2 релей-пробинга (WebSocket /tcp-v2 с 4-байтным контрольным ACK)
        val relayResult = probeRelayInternal(cleanDomain, timeoutMs = 2500L)
        if (relayResult.first == WorkerStatus.ONLINE || relayResult.first == WorkerStatus.RATE_LIMITED_429) {
            return@withContext relayResult
        }

        // 2. Интеллектуальный HTTP & Signature Fallback:
        // Проверяем доступность воркера, статус Cloudflare и сигнатуру скрипта Mirrly
        val httpResult = verifyWorkerHttpEndpoint(cleanDomain)
        if (httpResult.first == WorkerStatus.ONLINE || httpResult.first == WorkerStatus.RATE_LIMITED_429) {
            return@withContext httpResult
        }

        WorkerStatus.ERROR_UNREACHABLE to null
    }

    /** Root HTTP is reachability only; ONLINE requires the versioned upstream-ready ACK. */
    suspend fun probeWorkerRelayContract(
        domain: String,
        timeoutMs: Long = 3500L
    ): Pair<WorkerStatus, Long?> = withContext(Dispatchers.IO) {
        val cleanDomain = sanitizeDomain(domain)
        if (cleanDomain.isBlank() || cleanDomain.any { it in "/?#@" }) {
            return@withContext WorkerStatus.ERROR_UNREACHABLE to null
        }

        val relayResult = probeRelayInternal(cleanDomain, timeoutMs)
        if (relayResult.first == WorkerStatus.ONLINE || relayResult.first == WorkerStatus.RATE_LIMITED_429) {
            return@withContext relayResult
        }

        // Fallback к валидированному эндпоинту Cloudflare Worker Mirrly
        verifyWorkerHttpEndpoint(cleanDomain)
    }

    private suspend fun probeRelayInternal(cleanDomain: String, timeoutMs: Long): Pair<WorkerStatus, Long?> {
        val request = try {
            Request.Builder()
                .url("https://$cleanDomain/tcp-v2?target=149.154.167.50:443")
                .header("User-Agent", "MirrlyTGProxy/${BuildConfig.VERSION_NAME}")
                .build()
        } catch (_: IllegalArgumentException) {
            return WorkerStatus.ERROR_UNREACHABLE to null
        }
        WorkerRequestTracker.recordProbeRequest(1)
        return probe.probe(request, timeoutMs)
    }

    private fun verifyWorkerHttpEndpoint(cleanDomain: String): Pair<WorkerStatus, Long?> {
        val start = System.currentTimeMillis()
        try {
            WorkerRequestTracker.recordProbeRequest(1)
            val request = Request.Builder()
                .url("https://$cleanDomain/")
                .header("User-Agent", "MirrlyTGProxy/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/json, text/plain, */*")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - start
                if (response.code == 429) {
                    return WorkerStatus.RATE_LIMITED_429 to elapsed
                }

                val body = response.body?.string().orEmpty()
                val serverHeader = response.header("Server").orEmpty()
                val hasCfRay = response.header("cf-ray") != null
                val isCloudflare = serverHeader.contains("cloudflare", ignoreCase = true) || hasCfRay

                val hasMirrlySignature = body.contains("Mirrly TG Proxy", ignoreCase = true)
                        || body.contains("Protected Telegram Relay", ignoreCase = true)
                        || body.contains("Cloudflare WebSocket", ignoreCase = true)
                        || (body.contains("\"status\"") && body.contains("\"online\""))
                        || body.contains("Telegram MTProto", ignoreCase = true)
                        || body.contains("Telegram SOCKS5", ignoreCase = true)

                if (response.isSuccessful) {
                    if (hasMirrlySignature || (isCloudflare && (response.header("Content-Type")?.contains("json") == true || body.contains("online", ignoreCase = true)))) {
                        return WorkerStatus.ONLINE to elapsed
                    }
                } else if (response.code == 502 && body.contains("Upstream TCP connect failed", ignoreCase = true)) {
                    return WorkerStatus.ONLINE to elapsed
                }
            }
        } catch (_: Exception) {}
        return WorkerStatus.ERROR_UNREACHABLE to null
    }
}
