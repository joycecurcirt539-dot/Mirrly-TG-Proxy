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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Status of suitability and speed of DoH server.
 */
enum class DohHealthStatus(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    MODERATE("Moderate"),
    SLOW("Slow"),
    POISONED("IP Poisoned"),
    BLOCKED("Blocked"),
    TIMEOUT("Timeout")
}

/**
 * Результат бенчмарка одного DoH-провайдера.
 */
data class DohBenchmarkResult(
    val providerId: String,
    val providerName: String,
    val latencyMs: Long,
    val status: DohHealthStatus,
    val resolvedIps: List<String>,
    val statusDetail: String,
    val isRecommended: Boolean = false
) {
    val isUsable: Boolean
        get() = status == DohHealthStatus.EXCELLENT ||
                status == DohHealthStatus.GOOD ||
                status == DohHealthStatus.MODERATE ||
                status == DohHealthStatus.SLOW
}

/**
 * Итоговый отчет автоматического подбора DoH-серверов.
 */
data class DohBenchmarkReport(
    val results: List<DohBenchmarkResult>,
    val recommendedProviderIds: Set<String>,
    val summaryText: String
)

/**
 * Движок глубокого тестирования и автоматического подбора лучших DoH-провайдеров.
 *
 * Выполняет:
 * 1. Проверку сетевой доступности каждого DoH-узла в текущей сети (Wi-Fi / сотовый оператор).
 * 2. Детектирование фильтрации ТСПУ (TCP RST при рукопожатии, SSLException, таймауты).
 * 3. Защиту от DNS-отравления (Anti-Poisoning): верификация принадлежности резолвнутых IP
 *    официальным подсетям Telegram (AS44907) и отсечение Bogon/RKN-заглушек.
 * 4. Точный замер RTT (Latency) каждого узла.
 * 5. Алгоритм ранжирования и автовыбора Top-3/Top-4 наиболее стабильных и быстрых серверов.
 */
object DohBenchmarkEngine {
    private const val TAG = "DohBenchmarkEngine"
    const val PROBE_DOMAIN = "api.telegram.org"
    const val PROBE_TIMEOUT_MS = 2500L

    // Официальные IPv4 подсети Telegram (AS44907, AS62041, AS59930, AS62014)
    val TG_IPV4_SUBNETS: List<Ipv4Cidr> = listOf(
        Ipv4Cidr("91.108.4.0", 22),
        Ipv4Cidr("91.108.8.0", 22),
        Ipv4Cidr("91.108.12.0", 22),
        Ipv4Cidr("91.108.16.0", 22),
        Ipv4Cidr("91.108.20.0", 22),
        Ipv4Cidr("91.108.36.0", 23),
        Ipv4Cidr("91.108.38.0", 23),
        Ipv4Cidr("91.108.56.0", 22),
        Ipv4Cidr("149.154.160.0", 20),
        Ipv4Cidr("91.105.192.0", 23),
        Ipv4Cidr("185.76.151.0", 24)
    )

    // Официальные IPv6 префиксы Telegram
    val TG_IPV6_PREFIXES = listOf(
        "2001:b28:f23d:",
        "2001:b28:f23f:",
        "2001:67c:4e8:"
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS + 500L, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Одиночный зонд DoH-провайдера с замером RTT и валидацией подсетей.
     */
    suspend fun probeProvider(
        provider: DohProvider,
        domain: String = PROBE_DOMAIN
    ): DohBenchmarkResult = withContext(Dispatchers.IO) {
        val queryUrl = DohResolver.buildDnsQueryUrl(provider, domain)
        val request = Request.Builder()
            .url(queryUrl)
            .header("Accept", provider.acceptHeader)
            .header("User-Agent", "MirrlyTGProxy-Benchmark/2.0.0")
            .build()

        val startNs = System.nanoTime()
        try {
            httpClient.newCall(request).execute().use { response ->
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                if (!response.isSuccessful) {
                    return@withContext DohBenchmarkResult(
                        providerId = provider.id,
                        providerName = provider.name,
                        latencyMs = elapsedMs,
                        status = DohHealthStatus.BLOCKED,
                        resolvedIps = emptyList(),
                        statusDetail = "HTTP ${response.code}"
                    )
                }

                val bytes = response.body?.bytes() ?: byteArrayOf()
                val contentType = response.header("Content-Type") ?: ""
                val parsed = parseAndVerifyDnsResponse(bytes, contentType)
                val status = when {
                    !parsed.isDnsSuccess -> DohHealthStatus.BLOCKED
                    parsed.isPoisoned -> DohHealthStatus.POISONED
                    elapsedMs < 65L -> DohHealthStatus.EXCELLENT
                    elapsedMs < 130L -> DohHealthStatus.GOOD
                    elapsedMs < 260L -> DohHealthStatus.MODERATE
                    else -> DohHealthStatus.SLOW
                }

                DohBenchmarkResult(
                    providerId = provider.id,
                    providerName = provider.name,
                    latencyMs = elapsedMs,
                    status = status,
                    resolvedIps = parsed.ips,
                    statusDetail = parsed.detail
                )
            }
        } catch (e: SocketTimeoutException) {
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            DohBenchmarkResult(
                providerId = provider.id,
                providerName = provider.name,
                latencyMs = elapsedMs,
                status = DohHealthStatus.TIMEOUT,
                resolvedIps = emptyList(),
                statusDetail = "Timeout exceeded (>2.5s)"
            )
        } catch (e: Exception) {
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val isTspuOrConn = e is SSLException || e is ConnectException || e is IOException
            val detail = if (isTspuOrConn) "DPI block / connection reset" else (e.message ?: "Network error")
            DohBenchmarkResult(
                providerId = provider.id,
                providerName = provider.name,
                latencyMs = elapsedMs,
                status = DohHealthStatus.BLOCKED,
                resolvedIps = emptyList(),
                statusDetail = detail
            )
        }
    }

    /**
     * Параллельное тестирование списка провайдеров с вызовом обратной связи по прогрессу.
     */
    suspend fun benchmarkAll(
        providers: List<DohProvider> = DohResolver.ALL_PROVIDERS,
        onProgress: ((current: Int, total: Int, result: DohBenchmarkResult) -> Unit)? = null
    ): DohBenchmarkReport = coroutineScope {
        val total = providers.size
        var completedCount = 0

        val deferredResults = providers.map { provider ->
            async(Dispatchers.IO) {
                val res = probeProvider(provider)
                val current = synchronized(this@DohBenchmarkEngine) {
                    completedCount++
                    completedCount
                }
                onProgress?.invoke(current, total, res)
                res
            }
        }

        val rawResults = deferredResults.awaitAll()
        analyzeAndRecommend(rawResults)
    }

    /**
     * Формирует итоговый отчет и выбирает оптимальные серверы.
     */
    fun analyzeAndRecommend(rawResults: List<DohBenchmarkResult>): DohBenchmarkReport {
        val usable = rawResults.filter { it.isUsable }.sortedBy { it.latencyMs }
        val recommendedIds = mutableSetOf<String>()

        if (usable.isEmpty()) {
            return DohBenchmarkReport(
                results = rawResults,
                recommendedProviderIds = emptySet(),
                summaryText = "All DoH servers are unreachable or blocked. Check internet connection."
            )
        }

        // Select Top-3 (or Top-4 if 4th is similarly fast)
        val targetCount = if (usable.size <= 3) {
            usable.size
        } else {
            val thirdLatency = usable[2].latencyMs
            val fourthLatency = usable[3].latencyMs
            if (fourthLatency <= thirdLatency + 25L) 4 else 3
        }

        val selected = usable.take(targetCount)
        selected.forEach { recommendedIds.add(it.providerId) }

        val markedResults = rawResults.map { r ->
            if (recommendedIds.contains(r.providerId)) r.copy(isRecommended = true) else r
        }

        val namesWithPing = selected.joinToString(", ") { "${it.providerName} (${it.latencyMs} ms)" }
        val summary = "Selected ${selected.size} best servers: $namesWithPing"

        return DohBenchmarkReport(
            results = markedResults,
            recommendedProviderIds = recommendedIds,
            summaryText = summary
        )
    }

    data class ParsedDnsResult(
        val isDnsSuccess: Boolean,
        val isPoisoned: Boolean,
        val ips: List<String>,
        val detail: String
    )

    fun parseAndVerifyDnsResponse(bytes: ByteArray, contentType: String? = null): ParsedDnsResult {
        if (bytes.isEmpty()) {
            return ParsedDnsResult(false, false, emptyList(), "Empty server response")
        }
        val isWire = contentType?.contains("application/dns-message") == true ||
                (bytes.size >= 12 && (bytes[2].toInt() and 0x80) != 0 && bytes[0] != '{'.code.toByte())

        if (isWire) {
            val wireRes = DohResolver.parseDnsWireResponse(bytes)
            if (wireRes == null || wireRes.first.isEmpty()) {
                return ParsedDnsResult(false, false, emptyList(), "Error parsing DNS Wireformat")
            }
            val ips = wireRes.first.map { it.hostAddress }
            return verifyTgSubnets(ips)
        }

        val jsonStr = String(bytes, Charsets.UTF_8).trim()
        return parseAndVerifyDnsResponse(jsonStr)
    }

    fun parseAndVerifyDnsResponse(jsonStr: String): ParsedDnsResult {
        if (jsonStr.isBlank()) {
            return ParsedDnsResult(false, false, emptyList(), "Empty server response")
        }
        return try {
            val json = JSONObject(jsonStr)
            val status = json.optInt("Status", -1)
            if (status != 0) {
                return ParsedDnsResult(false, false, emptyList(), "DNS error Status=$status")
            }

            val answerArray = json.optJSONArray("Answer")
            if (answerArray == null || answerArray.length() == 0) {
                return ParsedDnsResult(false, false, emptyList(), "Empty Answer section")
            }

            val ips = mutableListOf<String>()
            for (i in 0 until answerArray.length()) {
                val record = answerArray.optJSONObject(i) ?: continue
                val type = record.optInt("type", 0)
                val data = record.optString("data", "").trim()
                if ((type == 1 || type == 28) && data.isNotBlank()) {
                    ips.add(data)
                }
            }

            if (ips.isEmpty()) {
                return ParsedDnsResult(false, false, emptyList(), "Missing A/AAAA records")
            }

            verifyTgSubnets(ips)
        } catch (e: Exception) {
            ParsedDnsResult(false, false, emptyList(), "JSON parse error: ${e.message}")
        }
    }

    private fun verifyTgSubnets(ips: List<String>): ParsedDnsResult {
        // Anti-Poisoning & Subnet validation
        for (ip in ips) {
            if (isBogon(ip)) {
                return ParsedDnsResult(true, true, ips, "Bogon/Loopback IP: $ip")
            }
        }

        val hasValidTelegramIp = ips.any { isTelegramIp(it) }
        if (!hasValidTelegramIp) {
            return ParsedDnsResult(true, true, ips, "IP ${ips.first()} does not belong to Telegram (AS44907)")
        }

        return ParsedDnsResult(true, false, ips, "AS44907 validated")
    }

    fun isTelegramIp(ip: String): Boolean {
        val clean = ip.trim().lowercase()
        if (clean.contains(":")) {
            return TG_IPV6_PREFIXES.any { clean.startsWith(it) }
        }
        return TG_IPV4_SUBNETS.any { it.matches(clean) }
    }

    fun isBogon(ip: String): Boolean {
        val clean = ip.trim()
        if (clean == "0.0.0.0" || clean == "127.0.0.1" || clean.startsWith("127.")) return true
        if (clean == "::1" || clean.startsWith("fc00:") || clean.startsWith("fe80:")) return true

        val parts = clean.split('.')
        if (parts.size == 4) {
            val o1 = parts[0].toIntOrNull() ?: return true
            val o2 = parts[1].toIntOrNull() ?: return true
            if (o1 == 10) return true
            if (o1 == 172 && o2 in 16..31) return true
            if (o1 == 192 && o2 == 168) return true
            if (o1 == 100 && o2 in 64..127) return true // CGNAT
            if (o1 == 169 && o2 == 254) return true // Link-local
            if (o1 >= 224) return true // Multicast & Reserved
        }
        return false
    }
}

/**
 * Быстрое сопоставление IPv4 с CIDR-подсетью без сторонних библиотек.
 */
data class Ipv4Cidr(val networkIp: String, val prefixLen: Int) {
    private val mask: Long = if (prefixLen == 0) 0L else ((0xFFFFFFFFL shl (32 - prefixLen)) and 0xFFFFFFFFL)
    private val net: Long = (ipToLong(networkIp) ?: 0L) and mask

    fun matches(ip: String): Boolean {
        val target = ipToLong(ip) ?: return false
        return (target and mask) == net
    }

    companion object {
        fun ipToLong(ip: String): Long? {
            val parts = ip.split('.')
            if (parts.size != 4) return null
            var res = 0L
            for (p in parts) {
                val octet = p.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                res = (res shl 8) or octet.toLong()
            }
            return res and 0xFFFFFFFFL
        }
    }
}
