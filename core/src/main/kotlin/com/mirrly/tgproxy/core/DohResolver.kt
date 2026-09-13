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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * Запись в локальном кэше DNS-over-HTTPS.
 */
data class DohCacheEntry(
    val domain: String,
    val addresses: List<InetAddress>,
    val expiresAtTimestampMs: Long,
    val providerName: String
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= expiresAtTimestampMs
}

/**
 * Статистика работы DoH-резолвера.
 */
data class DohStats(
    val cacheHits: Long,
    val cacheMisses: Long,
    val dohSuccessCount: Long,
    val fallbackSystemDnsCount: Long,
    val cachedEntriesCount: Int
)

/**
 * Провайдер DNS-over-HTTPS.
 */
data class DohProvider(
    val id: String,
    val name: String,
    val description: String,
    val endpointUrl: String,
    val isDefaultEnabled: Boolean,
    val isGoogleStyle: Boolean = false,
    val acceptHeader: String = "application/dns-json",
    val useDnsParam: Boolean = false
) {
    constructor(
        name: String,
        endpointUrl: String,
        isGoogleStyle: Boolean = false,
        acceptHeader: String = "application/dns-json",
        useDnsParam: Boolean = false
    ) : this(
        id = name.lowercase().replace("-", "_").replace(" ", "_"),
        name = name,
        description = "",
        endpointUrl = endpointUrl,
        isDefaultEnabled = true,
        isGoogleStyle = isGoogleStyle,
        acceptHeader = acceptHeader,
        useDnsParam = useDnsParam
    )
}

/**
 * Высокопроизводительный и защищенный от цензуры DNS-over-HTTPS (DoH) резолвер.
 *
 * Реализует:
 * 1. Конкурентный опрос независимых DoH-серверов (Race Resolver / First-to-Respond wins).
 * 2. Потокобезопасный локальный LRU TTL-кэш с автоматической инвалидацией.
 * 3. Отказоустойчивый прозрачный Fallback на системный DNS при сетевых сбоях.
 * 4. Прямой опрос по IP (1.1.1.1, 8.8.8.8, 9.9.9.9) без рекурсивного DNS-бутстраппинга.
 */
object DohResolver {
    private const val TAG = "DohResolver"
    private const val MIN_TTL_SECONDS = 30L
    private const val MAX_TTL_SECONDS = 3600L
    private const val DEFAULT_TTL_SECONDS = 300L
    private const val RACE_TIMEOUT_MS = 2500L

    private val cache = ConcurrentHashMap<String, DohCacheEntry>()

    private val totalHits = AtomicLong(0L)
    private val totalMisses = AtomicLong(0L)
    private val totalDohSuccess = AtomicLong(0L)
    private val totalFallback = AtomicLong(0L)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val ALL_PROVIDERS: List<DohProvider> = listOf(
        DohProvider(
            id = "adguard",
            name = "AdGuard DNS",
            description = "Блокировка рекламы и трекеров (Anycast)",
            endpointUrl = "https://94.140.14.14/resolve",
            isDefaultEnabled = true,
            isGoogleStyle = true,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "dnssb",
            name = "DNS.SB (Primary)",
            description = "Приватный DNS без логов и цензуры (Anycast)",
            endpointUrl = "https://185.222.222.222/dns-query",
            isDefaultEnabled = true,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "dnssb_sec",
            name = "DNS.SB (Secondary)",
            description = "Резервный европейский Anycast-узел",
            endpointUrl = "https://45.11.45.11/dns-query",
            isDefaultEnabled = true,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "nextdns",
            name = "NextDNS",
            description = "Глобальная сверхбыстрая Anycast-сеть",
            endpointUrl = "https://dns.nextdns.io/dns-query",
            isDefaultEnabled = true,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "controld",
            name = "Control D (Standard)",
            description = "Высокоскоростной DNS без цензуры",
            endpointUrl = "https://freedns.controld.com/p0",
            isDefaultEnabled = true,
            acceptHeader = "application/dns-message"
        ),
        DohProvider(
            id = "controld_uncensored",
            name = "Control D (Uncensored)",
            description = "Открытый резолвер без каких-либо фильтров",
            endpointUrl = "https://freedns.controld.com/uncensored",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-message"
        ),
        DohProvider(
            id = "controld_malware",
            name = "Control D (Security)",
            description = "Фильтрация вредоносных сайтов и фишинга",
            endpointUrl = "https://freedns.controld.com/malware",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-message"
        ),
        DohProvider(
            id = "quad9",
            name = "Quad9 DNS",
            description = "Швейцарский Anycast без коммерческого трекинга",
            endpointUrl = "https://dns.quad9.net/dns-query",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-message"
        ),
        DohProvider(
            id = "geohide",
            name = "GeoHide DNS",
            description = "Обход региональных блокировок и цензуры",
            endpointUrl = "https://dns.geohide.ru/dns-query",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-message",
            useDnsParam = true
        ),
        DohProvider(
            id = "xbox",
            name = "Xbox DNS",
            description = "Smart DNS для сервисов Microsoft и игр",
            endpointUrl = "https://xbox-dns.ru/dns-query",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-message",
            useDnsParam = true
        ),
        DohProvider(
            id = "cloudflare",
            name = "Cloudflare (1.1.1.1)",
            description = "Глобальный Anycast (может замедляться в РФ)",
            endpointUrl = "https://1.1.1.1/dns-query",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "cloudflare_sec",
            name = "Cloudflare (1.0.0.1)",
            description = "Второй Anycast-адрес Cloudflare",
            endpointUrl = "https://1.0.0.1/dns-query",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "google",
            name = "Google DNS (8.8.8.8)",
            description = "Резервный глобальный резолвер Google",
            endpointUrl = "https://8.8.8.8/resolve",
            isDefaultEnabled = false,
            isGoogleStyle = true,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "google_sec",
            name = "Google DNS (8.8.4.4)",
            description = "Второй глобальный адрес Google Public DNS",
            endpointUrl = "https://8.8.4.4/resolve",
            isDefaultEnabled = false,
            isGoogleStyle = true,
            acceptHeader = "application/dns-json"
        )
    )

    fun buildDnsQueryPacket(domain: String, type: Int = 1): ByteArray {
        val stream = java.io.ByteArrayOutputStream(64)
        // Transaction ID: 0x0000
        stream.write(0); stream.write(0)
        // Flags: 0x0100 (Standard query, RD = 1)
        stream.write(1); stream.write(0)
        // QDCOUNT: 1
        stream.write(0); stream.write(1)
        // ANCOUNT, NSCOUNT, ARCOUNT: 0
        stream.write(0); stream.write(0)
        stream.write(0); stream.write(0)
        stream.write(0); stream.write(0)
        // QNAME
        for (part in domain.split('.')) {
            if (part.isNotEmpty()) {
                val bytes = part.toByteArray(Charsets.US_ASCII)
                stream.write(bytes.size)
                stream.write(bytes)
            }
        }
        stream.write(0) // Root label
        // QTYPE: 1 (A)
        stream.write(0); stream.write(type)
        // QCLASS: 1 (IN)
        stream.write(0); stream.write(1)
        return stream.toByteArray()
    }

    fun buildDnsQueryUrl(provider: DohProvider, domain: String): String {
        return if (provider.useDnsParam) {
            val packet = buildDnsQueryPacket(domain)
            val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(packet)
            "${provider.endpointUrl}?dns=$b64"
        } else {
            "${provider.endpointUrl}?name=$domain&type=A"
        }
    }

    val DEFAULT_ENABLED_PROVIDER_IDS: Set<String> = ALL_PROVIDERS
        .filter { it.isDefaultEnabled }
        .map { it.id }
        .toSet()

    val DEFAULT_PROVIDERS: List<DohProvider>
        get() = ALL_PROVIDERS

    @Volatile
    private var activeProviderIds: Set<String> = DEFAULT_ENABLED_PROVIDER_IDS

    fun setActiveProviders(ids: Set<String>) {
        activeProviderIds = if (ids.isEmpty()) DEFAULT_ENABLED_PROVIDER_IDS else ids
    }

    fun getActiveProviders(): List<DohProvider> {
        val currentIds = activeProviderIds
        val filtered = ALL_PROVIDERS.filter { currentIds.contains(it.id) }
        return if (filtered.isNotEmpty()) filtered else ALL_PROVIDERS.filter { it.isDefaultEnabled }
    }

    fun getActiveProviderIds(): Set<String> = activeProviderIds

    fun getActiveEndpointsCsv(): String = getActiveProviders().joinToString(",") { it.endpointUrl }

    val CF_ANYCAST_FALLBACK_IPS: List<InetAddress> = listOf(
        "188.114.96.1",
        "188.114.97.1",
        "188.114.98.1",
        "188.114.99.1",
        "172.67.73.1",
        "104.21.234.1",
        "104.26.12.1",
        "104.26.13.1"
    ).mapNotNull {
        try {
            InetAddress.getByName(it)
        } catch (_: Exception) {
            null
        }
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2000, TimeUnit.MILLISECONDS)
            .readTimeout(2000, TimeUnit.MILLISECONDS)
            .callTimeout(2500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Асинхронное разрешение доменного имени в список IP-адресов.
     */
    suspend fun resolve(domain: String): List<InetAddress> = withContext(Dispatchers.IO) {
        val cleanDomain = sanitizeHostname(domain)
        if (cleanDomain.isBlank()) return@withContext emptyList()

        // Если это уже числовой IPv4/IPv6 адрес, возвращаем его напрямую
        if (isNumericIpAddress(cleanDomain)) {
            return@withContext try {
                listOf(InetAddress.getByName(cleanDomain))
            } catch (_: Exception) {
                emptyList()
            }
        }

        // 1. Проверка локального TTL кэша
        val cached = cache[cleanDomain]
        if (cached != null && !cached.isExpired) {
            totalHits.incrementAndGet()
            return@withContext HappyEyeballsEngine.prioritizeAddresses(cached.addresses)
        }

        totalMisses.incrementAndGet()

        // 2. Параллельный DoH Race Resolver
        val raceResult = raceResolve(cleanDomain)
        if (raceResult != null && raceResult.first.isNotEmpty()) {
            val (rawAddresses, ttlSec, providerName) = raceResult
            val addresses = HappyEyeballsEngine.prioritizeAddresses(rawAddresses)
            val effectiveTtlMs = ttlSec.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS) * 1000L
            val entry = DohCacheEntry(
                domain = cleanDomain,
                addresses = addresses,
                expiresAtTimestampMs = System.currentTimeMillis() + effectiveTtlMs,
                providerName = providerName
            )
            cache[cleanDomain] = entry
            totalDohSuccess.incrementAndGet()
            AppLogger.d(
                TAG,
                "DoH успешно разрешил '$cleanDomain' → ${addresses.map { it.hostAddress }} (Провайдер: $providerName, TTL: ${ttlSec}с)"
            )
            return@withContext addresses
        }

        // 3. Fallback на системный DNS
        AppLogger.w(TAG, "DoH провайдеры не ответили для '$cleanDomain', выполняется системный Fallback...")
        totalFallback.incrementAndGet()
        val sysAddrs = try {
            val rawList = InetAddress.getAllByName(cleanDomain).toList()
            rawList.filterNot { isBogonOrLoopback(it) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Ошибка системного DNS для '$cleanDomain': ${e.message}")
            emptyList()
        }

        if (sysAddrs.isNotEmpty()) {
            // Кэшируем системный ответ на короткий промежуток (60 сек)
            cache[cleanDomain] = DohCacheEntry(
                domain = cleanDomain,
                addresses = sysAddrs,
                expiresAtTimestampMs = System.currentTimeMillis() + 60_000L,
                providerName = "System-Fallback"
            )
            return@withContext sysAddrs
        }

        // 4. Гарантированный Anycast IP Fallback для Cloudflare Worker / CDN доменов
        if (isCloudflareTargetDomain(cleanDomain) && CF_ANYCAST_FALLBACK_IPS.isNotEmpty()) {
            AppLogger.i(TAG, "Применен Cloudflare Anycast Fallback для '$cleanDomain'")
            val anycastAddrs = HappyEyeballsEngine.prioritizeAddresses(CF_ANYCAST_FALLBACK_IPS)
            cache[cleanDomain] = DohCacheEntry(
                domain = cleanDomain,
                addresses = anycastAddrs,
                expiresAtTimestampMs = System.currentTimeMillis() + (DEFAULT_TTL_SECONDS * 1000L),
                providerName = "Cloudflare-Anycast-Fallback"
            )
            return@withContext anycastAddrs
        }

        emptyList()
    }

    /**
     * Синхронная блокирующая версия для интеграции с библиотеками (например, OkHttp Dns).
     */
    fun resolveSync(domain: String): List<InetAddress> {
        val clean = sanitizeHostname(domain)
        val cached = cache[clean]
        if (cached != null && !cached.isExpired) {
            totalHits.incrementAndGet()
            return cached.addresses
        }

        return try {
            runBlocking(Dispatchers.IO) {
                resolve(domain)
            }
        } catch (_: Exception) {
            try {
                InetAddress.getAllByName(domain).toList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Конкурентный опрос нескольких DoH провайдеров.
     * Возвращает первый валидный ответ с IPv4/IPv6 адресами и отменяет остальные запросы.
     */
    private suspend fun raceResolve(domain: String): Triple<List<InetAddress>, Long, String>? {
        val providers = getActiveProviders()
        if (providers.isEmpty()) return null

        return withTimeoutOrNull(RACE_TIMEOUT_MS) {
            val deferred = CompletableDeferred<Triple<List<InetAddress>, Long, String>>()
            val raceJob = Job()
            val failuresCount = AtomicInteger(0)
            val totalProviders = providers.size

            for (provider in providers) {
                scope.launch(raceJob) {
                    val result = queryDohProvider(provider, domain)
                    if (result != null && result.first.isNotEmpty()) {
                        if (deferred.complete(Triple(result.first, result.second, provider.name))) {
                            raceJob.cancelChildren()
                        }
                    } else {
                        if (failuresCount.incrementAndGet() >= totalProviders) {
                            deferred.completeExceptionally(NoSuchElementException("All DoH providers failed"))
                        }
                    }
                }
            }

            try {
                deferred.await()
            } catch (_: Exception) {
                null
            } finally {
                raceJob.cancel()
            }
        }
    }

    /**
     * Выполняет HTTPS запрос к указанному DoH провайдеру и парсит ответ (поддерживает JSON и RFC 8484 Wireformat).
     */
    private fun queryDohProvider(provider: DohProvider, domain: String): Pair<List<InetAddress>, Long>? {
        val url = "${provider.endpointUrl}?name=$domain&type=A"
        val request = Request.Builder()
            .url(url)
            .header("Accept", provider.acceptHeader)
            .header("User-Agent", "MirrlyTGProxy-DoH/1.1.8.4")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                val contentType = response.header("Content-Type") ?: ""
                if (contentType.contains("application/dns-message")) {
                    parseDnsWireResponse(bytes)
                } else {
                    val bodyStr = String(bytes, Charsets.UTF_8).trim()
                    if (bodyStr.startsWith("{")) {
                        parseDohJsonResponse(bodyStr, domain)
                    } else {
                        parseDnsWireResponse(bytes)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Парсинг бинарного DNS-пакета RFC 8484 (Wireformat) от Control D / Quad9 / OpenDNS.
     */
    fun parseDnsWireResponse(bytes: ByteArray): Pair<List<InetAddress>, Long>? {
        if (bytes.size < 12) return null
        return try {
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            buffer.short // txid
            val flags = buffer.short.toInt() and 0xFFFF
            val rcode = flags and 0x000F
            if (rcode != 0) return null // 0 = NOERROR

            val qdCount = buffer.short.toInt() and 0xFFFF
            val anCount = buffer.short.toInt() and 0xFFFF
            buffer.short // nsCount
            buffer.short // arCount

            // Пропуск секции Question
            for (i in 0 until qdCount) {
                if (!skipDnsName(buffer)) return null
                if (buffer.remaining() < 4) return null
                buffer.short // qtype
                buffer.short // qclass
            }

            val addresses = mutableListOf<InetAddress>()
            var minTtl = DEFAULT_TTL_SECONDS

            // Чтение записей Answer
            for (i in 0 until anCount) {
                if (!skipDnsName(buffer)) break
                if (buffer.remaining() < 10) break
                val type = buffer.short.toInt() and 0xFFFF
                val clazz = buffer.short.toInt() and 0xFFFF
                val ttl = buffer.int.toLong() and 0xFFFFFFFFL
                val rdLength = buffer.short.toInt() and 0xFFFF

                if (buffer.remaining() < rdLength) break

                if (type == 1 && rdLength == 4) { // A Record (IPv4)
                    val ipBytes = ByteArray(4)
                    buffer.get(ipBytes)
                    try {
                        addresses.add(InetAddress.getByAddress(ipBytes))
                        if (ttl in 1 until minTtl) minTtl = ttl
                    } catch (_: Exception) {}
                } else if (type == 28 && rdLength == 16) { // AAAA Record (IPv6)
                    val ipBytes = ByteArray(16)
                    buffer.get(ipBytes)
                    try {
                        addresses.add(InetAddress.getByAddress(ipBytes))
                        if (ttl in 1 until minTtl) minTtl = ttl
                    } catch (_: Exception) {}
                } else {
                    buffer.position(buffer.position() + rdLength)
                }
            }

            if (addresses.isNotEmpty()) Pair(addresses, minTtl) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun skipDnsName(buffer: java.nio.ByteBuffer): Boolean {
        var jumps = 0
        while (buffer.hasRemaining()) {
            val len = buffer.get().toInt() and 0xFF
            if (len == 0) return true
            if ((len and 0xC0) == 0xC0) {
                // Указатель сжатия DNS: 2 байта
                if (buffer.hasRemaining()) {
                    buffer.get()
                    return true
                } else {
                    return false
                }
            } else {
                if (buffer.remaining() >= len) {
                    buffer.position(buffer.position() + len)
                } else {
                    return false
                }
            }
            if (++jumps > 128) return false
        }
        return false
    }

    /**
     * Парсинг DNS JSON ответа (RFC 8427) от Cloudflare / Google / Quad9.
     */
    fun parseDohJsonResponse(jsonStr: String, expectedDomain: String): Pair<List<InetAddress>, Long>? {
        if (jsonStr.isBlank()) return null
        return try {
            val json = JSONObject(jsonStr)
            val status = json.optInt("Status", -1)
            if (status != 0) return null // Status 0 = NOERROR

            val answerArray = json.optJSONArray("Answer") ?: return null
            val addresses = mutableListOf<InetAddress>()
            var minTtl = DEFAULT_TTL_SECONDS

            for (i in 0 until answerArray.length()) {
                val record = answerArray.optJSONObject(i) ?: continue
                val type = record.optInt("type", 0)
                val data = record.optString("data", "").trim()
                val ttl = record.optLong("TTL", DEFAULT_TTL_SECONDS)

                // Type 1: A Record (IPv4), Type 28: AAAA Record (IPv6)
                if ((type == 1 || type == 28) && data.isNotBlank()) {
                    try {
                        val inet = InetAddress.getByName(data)
                        addresses.add(inet)
                        if (ttl in 1 until minTtl) {
                            minTtl = ttl
                        }
                    } catch (_: Exception) {}
                }
            }

            if (addresses.isNotEmpty()) {
                Pair(addresses, minTtl)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Очистка локального кэша DoH (вызывается при смене сети или воркера).
     */
    fun clearCache() {
        val size = cache.size
        cache.clear()
        AppLogger.i(TAG, "Локальный DoH-кэш очищен ($size записей удалено)")
    }

    fun getStats(): DohStats {
        return DohStats(
            cacheHits = totalHits.get(),
            cacheMisses = totalMisses.get(),
            dohSuccessCount = totalDohSuccess.get(),
            fallbackSystemDnsCount = totalFallback.get(),
            cachedEntriesCount = cache.size
        )
    }

    fun putInCache(domain: String, addresses: List<InetAddress>, ttlSeconds: Long, provider: String = "Manual") {
        val clean = sanitizeHostname(domain)
        val clampedTtl = ttlSeconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
        cache[clean] = DohCacheEntry(
            domain = clean,
            addresses = addresses,
            expiresAtTimestampMs = System.currentTimeMillis() + (clampedTtl * 1000L),
            providerName = provider
        )
    }

    fun getFromCache(domain: String): DohCacheEntry? {
        val clean = sanitizeHostname(domain)
        val entry = cache[clean] ?: return null
        return if (!entry.isExpired) entry else null
    }

    private fun sanitizeHostname(input: String): String {
        return input.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("wss://")
            .removePrefix("ws://")
            .substringBefore(":")
            .substringBefore("/")
            .trim()
            .trimEnd('.')
    }

    private fun isNumericIpAddress(host: String): Boolean {
        // Простая проверка на IPv4 / IPv6 адрес
        val isIpv4 = host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
        val isIpv6 = host.contains(":") && host.matches(Regex("""^[0-9a-fA-F:]+$"""))
        return isIpv4 || isIpv6
    }

    private fun isBogonOrLoopback(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress) return true
        val host = addr.hostAddress ?: ""
        return host.startsWith("127.") || host == "0.0.0.0" || host == "::1"
    }

    fun isCloudflareTargetDomain(domain: String): Boolean {
        val clean = sanitizeHostname(domain).lowercase()
        return clean.endsWith(".workers.dev") ||
                clean.endsWith(".pages.dev") ||
                clean.contains("cloudflare") ||
                TgConstants.DEFAULT_EMBEDDED_DOMAINS.any { clean.endsWith(it) } ||
                clean.endsWith(".workers.dev")
    }
}
