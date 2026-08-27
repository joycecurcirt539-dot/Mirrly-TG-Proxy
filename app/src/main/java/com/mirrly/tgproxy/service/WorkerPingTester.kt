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
import com.mirrly.tgproxy.core.WorkerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object WorkerPingTester {
    private val client by lazy {
        OkHttpClient.Builder()
            .dns(DohOkHttpDns.INSTANCE)
            .connectTimeout(5000, TimeUnit.MILLISECONDS)
            .readTimeout(5000, TimeUnit.MILLISECONDS)
            .callTimeout(6000, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun pingWorker(domain: String): Pair<WorkerStatus, Long?> = withContext(Dispatchers.IO) {
        val cleanDomain = domain.trim().removePrefix("https://").removePrefix("http://").removePrefix("wss://").removeSuffix("/")
        if (cleanDomain.isBlank()) {
            return@withContext Pair(WorkerStatus.ERROR_UNREACHABLE, null)
        }

        val url = "https://$cleanDomain/"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MirrlyTGProxy/${BuildConfig.VERSION_NAME}")
            .header("Accept", "*/*")
            .build()

        val start = System.currentTimeMillis()
        try {
            WorkerRequestTracker.recordProbeRequest(1)
            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - start
                when {
                    response.code == 429 -> Pair(WorkerStatus.RATE_LIMITED_429, elapsed)
                    response.isSuccessful || response.code == 400 || response.code == 404 || response.code == 101 -> {
                        Pair(WorkerStatus.ONLINE, elapsed)
                    }
                    else -> Pair(WorkerStatus.ONLINE, elapsed)
                }
            }
        } catch (_: Exception) {
            Pair(WorkerStatus.ERROR_UNREACHABLE, null)
        }
    }
}
