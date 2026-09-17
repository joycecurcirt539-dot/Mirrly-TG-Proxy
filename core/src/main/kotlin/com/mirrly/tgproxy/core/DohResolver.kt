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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
 * Область видимости и политика разрешения DNS.
 */
enum class DnsScope {
    /**
     * Бутстрап-резолвер для прокси-узлов и эндпоинтов (Worker, VLESS, MASQUE Anycast, DoH сервера).
     * Разрешает системный DNS fallback и гарантированный Anycast fallback при недоступности DoH.
     */
    BOOTSTRAP,

    /**
     * Пользовательский резолвер внутри защищенного туннеля (SOCKS5, VPN TUN).
     * Запрещает нешифрованный системный fallback на локальный DNS во избежание утечек DNS
     * и рекурсивных петель в VPN-режиме.
     */
    USER_IN_TUNNEL
}

/**
 * Менеджер бюджета параллельных DNS-запросов (MOB-021).
 * Ограничивает число одновременных in-flight разрешений для предотвращения
 * перегрузки радиомодуля (cellular: 4, Wi-Fi: 8).
 */
class DnsBudgetManager(private val isMobileProvider: () -> Boolean = { false }) {
    private val active = AtomicInteger(0)
    private val channel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    fun maxConcurrent(): Int = if (isMobileProvider()) 4 else 8

    suspend fun acquire() {
        while (true) {
            val max = maxConcurrent()
            val current = active.get()
            if (current < max) {
                if (active.compareAndSet(current, current + 1)) {
                    return
                }
            } else {
                kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    channel.receive()
                }
            }
        }
    }

    fun release() {
        active.decrementAndGet()
        channel.trySend(Unit)
    }

    suspend fun <T> withBudget(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    fun activeCount(): Int = active.get()

    fun reset() {
        active.set(0)
    }
}

/**
 * Семейство IP-адресов DNS-записи (MOB-022).
 */
enum class AddressFamily {
    IPV4,
    IPV6,
    DUAL_STACK,
    NONE;

    companion object {
        fun fromAddresses(addresses: List<InetAddress>): AddressFamily {
            if (addresses.isEmpty()) return NONE
            var hasV4 = false
            var hasV6 = false
            for (addr in addresses) {
                if (addr is java.net.Inet4Address) hasV4 = true
                if (addr is java.net.Inet6Address) hasV6 = true
            }
            return when {
                hasV4 && hasV6 -> DUAL_STACK
                hasV4 -> IPV4
                hasV6 -> IPV6
                else -> NONE
            }
        }
    }
}

/**
 * Запись в локальном кэше DNS-over-HTTPS (MOB-022).
 * Хранит positive/negative TTL, семейство адресов, источник резолвера и generation сети.
 */
data class DohCacheEntry(
    val domain: String,
    val addresses: List<InetAddress>,
    val expiresAtTimestampMs: Long,
    val providerName: String,
    val isSecure: Boolean = true,
    val isNegative: Boolean = false,
    val family: AddressFamily = AddressFamily.fromAddresses(addresses),
    val resolverSource: String = providerName,
    val networkGeneration: Long = DohResolver.currentNetworkGeneration()
) {
    val isExpired: Boolean
        get() = DohResolver.timeProvider() >= expiresAtTimestampMs

    fun isExpired(nowMs: Long = DohResolver.timeProvider()): Boolean =
        nowMs >= expiresAtTimestampMs

    fun isValidFor(
        currentGen: Long,
        nowMs: Long = DohResolver.timeProvider()
    ): Boolean = !isExpired(nowMs) && networkGeneration == currentGen
}

/**
 * Статистика работы DoH-резолвера.
 */
data class DohStats(
    val cacheHits: Long,
    val cacheMisses: Long,
    val dohSuccessCount: Long,
    val fallbackSystemDnsCount: Long,
    val cachedEntriesCount: Int,
    val dnsFailedCount: Long = 0L,
    val fallbackPolicyAppliedCount: Long = 0L,
    val fallbackIpFailedCount: Long = 0L
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
    const val DEFAULT_POSITIVE_TTL_SECONDS = 300L
    const val DEFAULT_NEGATIVE_TTL_SECONDS = 15L
    private const val RACE_TIMEOUT_MS = 2500L
    const val HEDGE_DELAY_MS = 180L

    @Volatile
    var timeProvider: () -> Long = { System.currentTimeMillis() }

    private val networkGeneration = AtomicLong(1L)

    fun currentNetworkGeneration(): Long = networkGeneration.get()

    fun setNetworkGeneration(gen: Long) {
        if (gen <= 0L) return
        val old = networkGeneration.getAndSet(gen)
        if (old != gen) {
            invalidateInFlight()
            clearInFlightResolutions()
        }
    }

    private val inFlightResolutions = ConcurrentHashMap<String, Deferred<List<InetAddress>>>()

    fun clearInFlightResolutions() {
        inFlightResolutions.clear()
    }

    fun activeInFlightResolutionsCount(): Int = inFlightResolutions.size

    private val cache = ConcurrentHashMap<String, DohCacheEntry>()
    private val generationLock = Any()
    private val resolutionEpoch = AtomicLong(1L)

    fun invalidateInFlight() {
        synchronized(generationLock) { resolutionEpoch.incrementAndGet() }
    }

    fun cacheIfCurrent(expectedEpoch: Long, entry: DohCacheEntry): Boolean = synchronized(generationLock) {
        if (resolutionEpoch.get() != expectedEpoch) return@synchronized false
        cache[entry.domain] = entry
        true
    }

    fun currentResolutionEpoch(): Long = resolutionEpoch.get()

    @Volatile
    private var isMobileNetwork: Boolean = false

    fun setMobileNetwork(isMobile: Boolean) {
        isMobileNetwork = isMobile
    }

    val dnsBudget = DnsBudgetManager { isMobileNetwork }

    private val activeCalls = java.util.Collections.newSetFromMap(ConcurrentHashMap<okhttp3.Call, Boolean>())

    fun cancelAllInFlight() {
        val iterator = activeCalls.iterator()
        while (iterator.hasNext()) {
            try {
                iterator.next().cancel()
            } catch (_: Throwable) {}
            iterator.remove()
        }
    }

    fun activeCallsCount(): Int = activeCalls.size

    private val totalHits = AtomicLong(0L)
    private val totalMisses = AtomicLong(0L)
    private val totalDohSuccess = AtomicLong(0L)
    private val totalFallback = AtomicLong(0L)
    private val totalDnsFailed = AtomicLong(0L)
    private val totalFallbackPolicyApplied = AtomicLong(0L)
    private val totalFallbackIpFailed = AtomicLong(0L)

    fun recordFallbackIpFailure() {
        totalFallbackIpFailed.incrementAndGet()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val ALL_PROVIDERS: List<DohProvider> = listOf(
        DohProvider(
            id = "adguard",
            name = "AdGuard DNS",
            description = "Ad and tracker blocking (Anycast)",
            endpointUrl = "https://94.140.14.14/resolve",
            isDefaultEnabled = true,
            isGoogleStyle = true,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "dnssb",
            name = "DNS.SB (Primary)",
            description = "Private DNS without logs or filtering (Anycast)",
            endpointUrl = "https://185.222.222.222/dns-query",
            isDefaultEnabled = true,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "dnssb_sec",
            name = "DNS.SB (Secondary)",
            description = "Backup European Anycast node",
            endpointUrl = "https://45.11.45.11/dns-query",
            isDefaultEnabled = true,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "nextdns",
            name = "NextDNS",
            description = "Global ultrafast Anycast network",
            endpointUrl = "https://dns.nextdns.io/dns-query",
            isDefaultEnabled = true,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "controld",
            name = "Control D (Standard)",
            description = "High-speed uncensored DNS",
            endpointUrl = "https://freedns.controld.com/p0",
            isDefaultEnabled = true,
            acceptHeader = "application/dns-message"
        ),
        DohProvider(
            id = "controld_uncensored",
            name = "Control D (Uncensored)",
            description = "Open resolver without filters",
            endpointUrl = "https://freedns.controld.com/uncensored",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-message"
        ),
        DohProvider(
            id = "controld_malware",
            name = "Control D (Security)",
            description = "Malware and phishing protection",
            endpointUrl = "https://freedns.controld.com/malware",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-message"
        ),
        DohProvider(
            id = "quad9",
            name = "Quad9 DNS",
            description = "Swiss Anycast without commercial tracking",
            endpointUrl = "https://dns.quad9.net/dns-query",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-message"
        ),
        DohProvider(
            id = "geohide",
            name = "GeoHide DNS",
            description = "Bypass regional blocks and filtering",
            endpointUrl = "https://dns.geohide.ru/dns-query",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-message",
            useDnsParam = true
        ),
        DohProvider(
            id = "xbox",
            name = "Xbox DNS",
            description = "Smart DNS for Microsoft services and gaming",
            endpointUrl = "https://xbox-dns.ru/dns-query",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-message",
            useDnsParam = true
        ),
        DohProvider(
            id = "cloudflare",
            name = "Cloudflare (1.1.1.1)",
            description = "Global Anycast (may be throttled in RU)",
            endpointUrl = "https://1.1.1.1/dns-query",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "cloudflare_sec",
            name = "Cloudflare (1.0.0.1)",
            description = "Secondary Cloudflare Anycast address",
            endpointUrl = "https://1.0.0.1/dns-query",
            isDefaultEnabled = false,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "google",
            name = "Google DNS (8.8.8.8)",
            description = "Backup global Google resolver",
            endpointUrl = "https://8.8.8.8/resolve",
            isDefaultEnabled = false,
            isGoogleStyle = true,
            acceptHeader = "application/dns-json"
        ),
        DohProvider(
            id = "google_sec",
            name = "Google DNS (8.8.4.4)",
            description = "Secondary Google Public DNS address",
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
        // QTYPE: 1 (A) or 28 (AAAA)
        stream.write(type shr 8); stream.write(type and 0xFF)
        // QCLASS: 1 (IN)
        stream.write(0); stream.write(1)
        return stream.toByteArray()
    }

    fun buildDnsQueryUrl(provider: DohProvider, domain: String, type: Int = 1): String {
        val typeParam = if (type == 28) "AAAA" else "A"
        return if (provider.useDnsParam) {
            val packet = buildDnsQueryPacket(domain, type)
            val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(packet)
            "${provider.endpointUrl}?dns=$b64"
        } else {
            "${provider.endpointUrl}?name=$domain&type=$typeParam"
        }
    }

    private val ipv6OnlyNetwork = java.util.concurrent.atomic.AtomicBoolean(false)

    fun setIpv6OnlyNetwork(isIpv6Only: Boolean) {
        ipv6OnlyNetwork.set(isIpv6Only)
        NativeProxy.setIpv6OnlyNetwork(isIpv6Only)
        HappyEyeballsEngine.setIpv6OnlyNetwork(isIpv6Only)
    }

    fun isIpv6OnlyNetwork(): Boolean = ipv6OnlyNetwork.get()

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
        "104.26.13.1",
        "2606:4700:4700::1111",
        "2606:4700:4700::1001",
        "2a06:98c1:3121::1",
        "2a06:98c1:3120::1"
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

    data class HedgedResolutionWinner(
        val addresses: List<InetAddress>,
        val ttlSec: Long,
        val providerName: String,
        val isSecure: Boolean
    )

    /**
     * Асинхронное разрешение доменного имени в список IP-адресов (MOB-021: Hedged DNS, MOB-022: Singleflight & Generation Cache).
     *
     * @param domain Целевое доменное имя или IP-адрес.
     * @param scope Область видимости DNS (USER_IN_TUNNEL запрещает plaintext fallback).
     */
    suspend fun resolve(
        domain: String,
        scope: DnsScope = DnsScope.USER_IN_TUNNEL
    ): List<InetAddress> = withContext(Dispatchers.IO) {
        val cleanDomain = sanitizeHostname(domain)
        if (cleanDomain.isBlank()) return@withContext emptyList()

        val expectedEpoch = currentResolutionEpoch()
        val curGen = currentNetworkGeneration()

        // 0. Если это уже числовой IPv4/IPv6 адрес, возвращаем его напрямую
        if (isNumericIpAddress(cleanDomain)) {
            return@withContext try {
                listOf(InetAddress.getByName(cleanDomain))
            } catch (_: Exception) {
                emptyList()
            }
        }

        // 1. Проверка локального TTL кэша с учетом network generation и negative TTL (MOB-022)
        val cached = cache[cleanDomain]
        if (cached != null && cached.isValidFor(curGen)) {
            if (cached.isNegative) {
                totalHits.incrementAndGet()
                return@withContext emptyList()
            }
            // Для USER_IN_TUNNEL запрещено использовать записи из System-Fallback / System-Resolver
            if (scope != DnsScope.USER_IN_TUNNEL || (cached.isSecure && cached.providerName != "System-Fallback" && cached.providerName != "System-Resolver")) {
                totalHits.incrementAndGet()
                return@withContext HappyEyeballsEngine.prioritizeAddresses(cached.addresses)
            }
        }

        // 2. Объединение идентичных in-flight запросов (Singleflight / Coalescing, MOB-022)
        val singleflightKey = "$curGen:$scope:$cleanDomain"
        val deferred = inFlightResolutions.computeIfAbsent(singleflightKey) {
            this@DohResolver.scope.async(Dispatchers.IO) {
                try {
                    executeResolveUncached(cleanDomain, scope, curGen, expectedEpoch)
                } finally {
                    inFlightResolutions.remove(singleflightKey)
                }
            }
        }

        try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun executeResolveUncached(
        cleanDomain: String,
        scope: DnsScope,
        curGen: Long,
        expectedEpoch: Long
    ): List<InetAddress> {
        totalMisses.incrementAndGet()

        // Hedged Resolution с учетом общего DNS бюджета (MOB-021)
        val hedgedResult = dnsBudget.withBudget {
            hedgedResolve(cleanDomain, scope)
        }

        if (currentResolutionEpoch() != expectedEpoch || currentNetworkGeneration() != curGen) {
            return emptyList()
        }

        if (hedgedResult != null && hedgedResult.addresses.isNotEmpty()) {
            val (rawAddresses, ttlSec, providerName, isSecure) = hedgedResult
            val addresses = HappyEyeballsEngine.prioritizeAddresses(rawAddresses)
            val effectiveTtlMs = if (isSecure) {
                ttlSec.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS) * 1000L
            } else {
                60_000L
            }
            val entry = DohCacheEntry(
                domain = cleanDomain,
                addresses = addresses,
                expiresAtTimestampMs = timeProvider() + effectiveTtlMs,
                providerName = providerName,
                isSecure = isSecure,
                isNegative = false,
                family = AddressFamily.fromAddresses(addresses),
                resolverSource = providerName,
                networkGeneration = curGen
            )
            if (!cacheIfCurrent(expectedEpoch, entry)) return emptyList()
            if (isSecure) {
                totalDohSuccess.incrementAndGet()
            } else {
                totalFallback.incrementAndGet()
            }
            AppLogger.d(
                TAG,
                "DNS [scope=$scope, gen=$curGen] successfully resolved '$cleanDomain' → ${addresses.map { it.hostAddress }} (${entry.family}, Provider: $providerName, TTL: ${effectiveTtlMs / 1000}s, Secure=$isSecure)"
            )
            return addresses
        }

        totalDnsFailed.incrementAndGet()

        // Fallback в зависимости от DnsScope
        if (scope == DnsScope.USER_IN_TUNNEL) {
            // STRICT PRIVACY: Никакого системного plaintext fallback для пользовательского DNS!
            // Кэшируем отрицательный ответ (NXDOMAIN) для предотвращения шторма повторов (MOB-022)
            val negEntry = DohCacheEntry(
                domain = cleanDomain,
                addresses = emptyList(),
                expiresAtTimestampMs = timeProvider() + (DEFAULT_NEGATIVE_TTL_SECONDS * 1000L),
                providerName = "NXDOMAIN-NegativeCache",
                isSecure = true,
                isNegative = true,
                family = AddressFamily.NONE,
                resolverSource = "NXDOMAIN",
                networkGeneration = curGen
            )
            cacheIfCurrent(expectedEpoch, negEntry)
            AppLogger.w(
                TAG,
                "DoH [scope=USER_IN_TUNNEL, gen=$curGen] failed to resolve '$cleanDomain' (dns_failed). Plaintext system fallback disabled. Negative response cached for ${DEFAULT_NEGATIVE_TTL_SECONDS}s."
            )
            return emptyList()
        }

        // Гарантированный Anycast IP Fallback для ПОДТВЕРЖДЕННЫХ Cloudflare доменов (только для BOOTSTRAP, MOB-023)
        if (isCloudflareTargetDomain(cleanDomain) && CF_ANYCAST_FALLBACK_IPS.isNotEmpty()) {
            totalFallbackPolicyApplied.incrementAndGet()
            AppLogger.w(
                TAG,
                "DNS failure for confirmed Cloudflare domain '$cleanDomain' (dns_failed). Applying explicit Anycast Fallback policy."
            )
            val anycastAddrs = HappyEyeballsEngine.prioritizeAddresses(CF_ANYCAST_FALLBACK_IPS)
            val entry = DohCacheEntry(
                domain = cleanDomain,
                addresses = anycastAddrs,
                expiresAtTimestampMs = timeProvider() + (DEFAULT_TTL_SECONDS * 1000L),
                providerName = "Cloudflare-Anycast-Fallback",
                isSecure = false,
                isNegative = false,
                family = AddressFamily.fromAddresses(anycastAddrs),
                resolverSource = "Cloudflare-Anycast-Fallback",
                networkGeneration = curGen
            )
            if (!cacheIfCurrent(expectedEpoch, entry)) return emptyList()
            return anycastAddrs
        }

        // Для не-Cloudflare доменов Anycast fallback категорически запрещен (MOB-023)
        AppLogger.w(
            TAG,
            "DNS failure for non-Cloudflare domain '$cleanDomain' (dns_failed). Anycast fallback strictly prohibited."
        )
        val negEntry = DohCacheEntry(
            domain = cleanDomain,
            addresses = emptyList(),
            expiresAtTimestampMs = timeProvider() + (DEFAULT_NEGATIVE_TTL_SECONDS * 1000L),
            providerName = "Bootstrap-Failure-NegativeCache",
            isSecure = false,
            isNegative = true,
            family = AddressFamily.NONE,
            resolverSource = "NXDOMAIN",
            networkGeneration = curGen
        )
        cacheIfCurrent(expectedEpoch, negEntry)
        return emptyList()
    }

    /**
     * Синхронная блокирующая версия для интеграции с библиотеками (например, OkHttp Dns).
     */
    fun resolveSync(
        domain: String,
        scope: DnsScope = DnsScope.USER_IN_TUNNEL
    ): List<InetAddress> {
        val clean = sanitizeHostname(domain)
        val curGen = currentNetworkGeneration()
        val cached = cache[clean]
        if (cached != null && cached.isValidFor(curGen)) {
            if (cached.isNegative) {
                totalHits.incrementAndGet()
                return emptyList()
            }
            if (scope != DnsScope.USER_IN_TUNNEL || (cached.isSecure && cached.providerName != "System-Fallback" && cached.providerName != "System-Resolver")) {
                totalHits.incrementAndGet()
                return cached.addresses
            }
        }

        return try {
            runBlocking(Dispatchers.IO) {
                resolve(domain, scope)
            }
        } catch (_: Exception) {
            if (scope == DnsScope.BOOTSTRAP) {
                try {
                    InetAddress.getAllByName(domain).toList()
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }
    }

    /**
     * MOB-021: Hedged DNS Resolver вместо параллельного spray по всем серверам.
     * Запускает system resolver (для BOOTSTRAP) и один выбранный DoH.
     * Второй DoH запускается ТОЛЬКО после истечения hedge delay (180ms) или при ранней ошибке первого DoH.
     * При отмене или победе закрывает все in-flight сетевые сокеты через Call.cancel().
     */
    internal suspend fun hedgedResolve(
        domain: String,
        scope: DnsScope
    ): HedgedResolutionWinner? = coroutineScope {
        val providers = getActiveProviders()
        if (providers.isEmpty() && scope == DnsScope.USER_IN_TUNNEL) return@coroutineScope null

        val primaryProvider = providers.firstOrNull()
        val secondaryProvider = providers.getOrNull(1)

        val localCalls = java.util.Collections.newSetFromMap(ConcurrentHashMap<okhttp3.Call, Boolean>())
        val deferredWinner = CompletableDeferred<HedgedResolutionWinner>()
        val hedgeTrigger = CompletableDeferred<Unit>()

        fun tryComplete(addresses: List<InetAddress>, ttlSec: Long, providerName: String, isSecure: Boolean) {
            if (addresses.isNotEmpty()) {
                deferredWinner.complete(HedgedResolutionWinner(addresses, ttlSec, providerName, isSecure))
            }
        }

        // 1. Для BOOTSTRAP запускаем быстрый system DNS параллельно с primary DoH
        if (scope == DnsScope.BOOTSTRAP) {
            launch {
                try {
                    val rawList = InetAddress.getAllByName(domain).toList()
                    val sysAddrs = rawList.filterNot { isBogonOrLoopback(it) }
                    if (sysAddrs.isNotEmpty()) {
                        tryComplete(sysAddrs, 60L, "System-Resolver", false)
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Primary DoH провайдер (один выбранный DoH)
        if (primaryProvider != null) {
            launch {
                val result = queryDohProvider(primaryProvider, domain, localCalls)
                if (result != null && result.first.isNotEmpty()) {
                    tryComplete(result.first, result.second, primaryProvider.name, true)
                } else {
                    hedgeTrigger.complete(Unit)
                }
            }
        } else {
            hedgeTrigger.complete(Unit)
        }

        // 3. Hedge delay таймер (180ms)
        launch {
            delay(HEDGE_DELAY_MS)
            hedgeTrigger.complete(Unit)
        }

        // 4. Secondary DoH провайдер (запускается ТОЛЬКО после hedge delay или ошибки первого DoH)
        if (secondaryProvider != null) {
            launch {
                hedgeTrigger.await()
                if (!deferredWinner.isCompleted) {
                    val result = queryDohProvider(secondaryProvider, domain, localCalls)
                    if (result != null && result.first.isNotEmpty()) {
                        tryComplete(result.first, result.second, secondaryProvider.name, true)
                    }
                }
            }
        }

        val winner = try {
            withTimeoutOrNull(RACE_TIMEOUT_MS) {
                try {
                    deferredWinner.await()
                } catch (_: Exception) {
                    null
                }
            }
        } finally {
            // Cancel closing requests: отменяем все дочерние корутины и активные вызовы OkHttp
            coroutineContext[Job]?.cancelChildren()
            for (call in localCalls) {
                try { call.cancel() } catch (_: Throwable) {}
            }
        }

        winner
    }

    internal suspend fun raceResolve(domain: String): Triple<List<InetAddress>, Long, String>? {
        val winner = hedgedResolve(domain, DnsScope.USER_IN_TUNNEL) ?: return null
        return Triple(winner.addresses, winner.ttlSec, winner.providerName)
    }

    /**
     * Выполняет HTTPS запрос к указанному DoH провайдеру для заданного типа DNS-записи (1 = A, 28 = AAAA).
     */
    private fun querySingleDohType(
        provider: DohProvider,
        domain: String,
        type: Int,
        trackedCalls: MutableSet<okhttp3.Call>? = null
    ): Pair<List<InetAddress>, Long>? {
        val url = buildDnsQueryUrl(provider, domain, type)
        val request = Request.Builder()
            .url(url)
            .header("Accept", provider.acceptHeader)
            .header("User-Agent", "MirrlyTGProxy-DoH/2.0.0")
            .build()

        val call = httpClient.newCall(request)
        trackedCalls?.add(call)
        activeCalls.add(call)

        return try {
            call.execute().use { response ->
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
        } finally {
            trackedCalls?.remove(call)
            activeCalls.remove(call)
        }
    }

    /**
     * Выполняет параллельный HTTPS запрос к указанному DoH провайдеру для записей A (IPv4) и AAAA (IPv6).
     * Поддерживает Dual-Stack, AAAA-only и DNS64/NAT64 сценарии (MOB-025).
     */
    private suspend fun queryDohProvider(
        provider: DohProvider,
        domain: String,
        trackedCalls: MutableSet<okhttp3.Call>? = null
    ): Pair<List<InetAddress>, Long>? = coroutineScope {
        val callA = async(Dispatchers.IO) { querySingleDohType(provider, domain, 1, trackedCalls) }
        val callAaaa = async(Dispatchers.IO) { querySingleDohType(provider, domain, 28, trackedCalls) }

        val resA = callA.await()
        val resAaaa = callAaaa.await()

        val allAddresses = mutableListOf<InetAddress>()
        var minTtl = DEFAULT_TTL_SECONDS

        if (resA != null && resA.first.isNotEmpty()) {
            allAddresses.addAll(resA.first)
            if (resA.second in 1 until minTtl) minTtl = resA.second
        }
        if (resAaaa != null && resAaaa.first.isNotEmpty()) {
            allAddresses.addAll(resAaaa.first)
            if (resAaaa.second in 1 until minTtl) minTtl = resAaaa.second
        }

        if (allAddresses.isNotEmpty()) {
            Pair(allAddresses, minTtl)
        } else {
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
        invalidateInFlight()
        clearInFlightResolutions()
        val size = cache.size
        cache.clear()
        AppLogger.i(TAG, "Local DoH cache cleared ($size entries removed)")
    }

    fun getStats(): DohStats {
        return DohStats(
            cacheHits = totalHits.get(),
            cacheMisses = totalMisses.get(),
            dohSuccessCount = totalDohSuccess.get(),
            fallbackSystemDnsCount = totalFallback.get(),
            cachedEntriesCount = cache.size,
            dnsFailedCount = totalDnsFailed.get(),
            fallbackPolicyAppliedCount = totalFallbackPolicyApplied.get(),
            fallbackIpFailedCount = totalFallbackIpFailed.get()
        )
    }

    fun putInCache(
        domain: String,
        addresses: List<InetAddress>,
        ttlSeconds: Long,
        provider: String = "Manual",
        isSecure: Boolean = true,
        isNegative: Boolean = false
    ) {
        val clean = sanitizeHostname(domain)
        val clampedTtl = ttlSeconds.coerceIn(if (isNegative) 1L else MIN_TTL_SECONDS, MAX_TTL_SECONDS)
        cache[clean] = DohCacheEntry(
            domain = clean,
            addresses = addresses,
            expiresAtTimestampMs = timeProvider() + (clampedTtl * 1000L),
            providerName = provider,
            isSecure = isSecure,
            isNegative = isNegative,
            family = AddressFamily.fromAddresses(addresses),
            resolverSource = provider,
            networkGeneration = currentNetworkGeneration()
        )
    }

    fun getFromCache(domain: String): DohCacheEntry? {
        val clean = sanitizeHostname(domain)
        val entry = cache[clean] ?: return null
        return if (entry.isValidFor(currentNetworkGeneration())) entry else null
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
        if (clean.isEmpty()) return false
        if (clean.endsWith(".workers.dev") ||
            clean.endsWith(".pages.dev") ||
            clean.endsWith(".cloudflare.com") ||
            clean.endsWith(".trycloudflare.com") ||
            clean.endsWith(".cloudflareclient.com") ||
            clean.endsWith(".cloudflareaccess.com") ||
            clean == "workers.dev" ||
            clean == "pages.dev" ||
            clean == "cloudflare.com" ||
            clean == "cloudflare-dns.com"
        ) {
            return true
        }
        return TgConstants.DEFAULT_EMBEDDED_DOMAINS.any { clean == it || clean.endsWith(".$it") }
    }
}
