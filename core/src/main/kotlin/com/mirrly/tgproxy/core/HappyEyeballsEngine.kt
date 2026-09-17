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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Стадии установления соединения (MOB-001 / MOB-024).
 */
enum class EstablishmentStage(val level: Int, val isReady: Boolean) {
    DNS(1, false),
    TCP(2, false),
    TLS(3, false),
    WSS(4, false),
    READY(5, true); // Полное установление (TLS + WSS 101 / Relay ACK / Useful RX)

    companion object {
        fun fromLevel(level: Int): EstablishmentStage =
            entries.find { it.level == level } ?: DNS
    }
}

/**
 * Результат выполнения алгоритма Happy Eyeballs v2 (RFC 8305).
 */
data class HappyEyeballsResult(
    val winningAddress: InetAddress,
    val handshakeRttMs: Long,
    val attemptIndex: Int,
    val totalCandidates: Int,
    val stage: EstablishmentStage = EstablishmentStage.READY,
    val tcpRttMs: Long = handshakeRttMs
)

/**
 * Составной ключ скоринга маршрута по полному установлению (MOB-024).
 * Score key: network generation/fingerprint, hostname, IP, family, stage.
 */
data class RouteScoreKey(
    val networkGeneration: Long,
    val networkFingerprint: String,
    val hostname: String,
    val ip: String,
    val family: AddressFamily,
    val stage: EstablishmentStage
)

/**
 * Метрики маршрута для конкретного хоста и поколения сети.
 */
data class RouteScoreEntry(
    val key: RouteScoreKey,
    val rttMs: Long,
    val consecutiveFailures: Int = 0,
    val successCount: Int = 0,
    val isReady: Boolean = false,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)

/**
 * Движок ступенчатого параллельного подключения Happy Eyeballs v2 (RFC 8305 / RFC 6555)
 * с поддержкой стадийного скоринга маршрутов по полному establishment (MOB-024).
 *
 * Архитектура MOB-024:
 * 1. Score key: (network generation / fingerprint, hostname, IP, family, stage).
 * 2. Успех TCP без TLS/WSS НЕ повышает маршрут до Ready (сохраняется как stage=TCP, isReady=false).
 * 3. Broken IPv6 быстро понижается только для соответствующего пути (hostname на текущем generation).
 * 4. Новая сеть (новое поколение network generation) начинает с безопасного history hint,
 *    но никогда не наследует вечный ban из предыдущей сети.
 */
object HappyEyeballsEngine {
    private const val TAG = "HappyEyeballs"
    const val DEFAULT_ATTEMPT_DELAY_MS = 200L // RFC 8305 рекомендованный интервал 100-250 мс
    const val DEFAULT_CONNECT_TIMEOUT_MS = 2500L

    private val ipRttRatings = ConcurrentHashMap<String, Long>()
    private val generationLock = Any()
    private val raceEpoch = AtomicLong(1L)
    private val networkGen = AtomicLong(1L)
    @Volatile
    private var networkFingerprint: String = "default"

    // Key: "$gen:$cleanHost:$cleanIp:${stage.name}" -> RouteScoreEntry
    private val routeScores = ConcurrentHashMap<String, RouteScoreEntry>()

    // Key: "$gen:$cleanHost:${family.name}" -> consecutive failure count
    private val familyFailures = ConcurrentHashMap<String, AtomicInteger>()

    // Key: "$gen:$cleanHost:$cleanIp" -> highest stage achieved
    private val ipHighestStage = ConcurrentHashMap<String, EstablishmentStage>()

    private val ipv6OnlyNetwork = java.util.concurrent.atomic.AtomicBoolean(false)

    fun setIpv6OnlyNetwork(isIpv6Only: Boolean) {
        val old = ipv6OnlyNetwork.getAndSet(isIpv6Only)
        if (old != isIpv6Only) {
            invalidateInFlight()
            AppLogger.d(TAG, "IPv6-only network state updated: $old -> $isIpv6Only")
        }
    }

    fun isIpv6OnlyNetwork(): Boolean = ipv6OnlyNetwork.get()

    fun setNetworkGeneration(gen: Long) {
        val oldGen = networkGen.getAndSet(gen)
        if (oldGen != gen) {
            invalidateInFlight()
            AppLogger.d(TAG, "Network generation updated: $oldGen -> $gen (старые баны не наследуются)")
        }
    }

    fun currentNetworkGeneration(): Long = networkGen.get()

    fun setNetworkFingerprint(fp: String) {
        networkFingerprint = fp
    }

    fun currentNetworkFingerprint(): String = networkFingerprint

    fun invalidateInFlight() {
        synchronized(generationLock) { raceEpoch.incrementAndGet() }
    }

    fun currentRaceEpoch(): Long = raceEpoch.get()

    fun recordIpRttIfCurrent(expectedEpoch: Long, ip: String, rtt: Long): Boolean =
        synchronized(generationLock) {
            if (raceEpoch.get() != expectedEpoch) return@synchronized false
            recordIpRtt(ip, rtt)
            true
        }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var lastLoggedWinningIp: String? = null

    /**
     * Запись успеха прохождения стадии для (generation, hostname, ip, family, stage).
     * КРИТИЧЕСКОЕ ПРАВИЛО: Успех TCP без TLS/WSS НЕ повышает маршрут до Ready!
     */
    fun recordStageSuccess(
        generation: Long,
        hostname: String,
        ip: String,
        family: AddressFamily,
        stage: EstablishmentStage,
        rttMs: Long
    ) {
        val cleanHost = sanitizeHost(hostname)
        val cleanIp = ip.trim()
        val key = RouteScoreKey(
            networkGeneration = generation,
            networkFingerprint = currentNetworkFingerprint(),
            hostname = cleanHost,
            ip = cleanIp,
            family = family,
            stage = stage
        )
        val routeKeyStr = "$generation:$cleanHost:$cleanIp:${stage.name}"
        val existing = routeScores[routeKeyStr]
        val smoothedRtt = if (existing == null) {
            rttMs
        } else {
            ((existing.rttMs * 0.7) + (rttMs * 0.3)).toLong().coerceAtLeast(1L)
        }

        // Маршрут становится Ready ТОЛЬКО при полном установлении (stage == READY)
        val ready = stage == EstablishmentStage.READY

        val entry = RouteScoreEntry(
            key = key,
            rttMs = smoothedRtt,
            consecutiveFailures = 0,
            successCount = (existing?.successCount ?: 0) + 1,
            isReady = ready,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        routeScores[routeKeyStr] = entry

        val highestKey = "$generation:$cleanHost:$cleanIp"
        ipHighestStage.compute(highestKey) { _, current ->
            if (current == null || stage.level > current.level) stage else current
        }

        if (stage == EstablishmentStage.READY) {
            val famKey = "$generation:$cleanHost:${family.name}"
            familyFailures[famKey]?.set(0)
            recordIpRtt(cleanIp, smoothedRtt)
        } else if (stage == EstablishmentStage.TCP) {
            recordIpRtt(cleanIp, smoothedRtt)
        }
    }

    /**
     * Запись сбоя стадии для (generation, hostname, ip, family, stage).
     * При сбое IPv6 увеличивается счетчик отказов только для данного хоста и поколения сети.
     */
    fun recordStageFailure(
        generation: Long,
        hostname: String,
        ip: String,
        family: AddressFamily,
        stage: EstablishmentStage
    ) {
        val cleanHost = sanitizeHost(hostname)
        val cleanIp = ip.trim()
        val key = RouteScoreKey(
            networkGeneration = generation,
            networkFingerprint = currentNetworkFingerprint(),
            hostname = cleanHost,
            ip = cleanIp,
            family = family,
            stage = stage
        )
        val routeKeyStr = "$generation:$cleanHost:$cleanIp:${stage.name}"
        val existing = routeScores[routeKeyStr]
        val newFailures = (existing?.consecutiveFailures ?: 0) + 1
        val entry = RouteScoreEntry(
            key = key,
            rttMs = existing?.rttMs ?: 9999L,
            consecutiveFailures = newFailures,
            successCount = existing?.successCount ?: 0,
            isReady = false,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        routeScores[routeKeyStr] = entry

        val famKey = "$generation:$cleanHost:${family.name}"
        familyFailures.computeIfAbsent(famKey) { AtomicInteger(0) }.incrementAndGet()

        val highestKey = "$generation:$cleanHost:$cleanIp"
        ipHighestStage.remove(highestKey)
    }

    fun isRouteReady(generation: Long, hostname: String, ip: String): Boolean {
        val cleanHost = sanitizeHost(hostname)
        val cleanIp = ip.trim()
        val highestKey = "$generation:$cleanHost:$cleanIp"
        return ipHighestStage[highestKey] == EstablishmentStage.READY
    }

    fun getFamilyFailures(generation: Long, hostname: String, family: AddressFamily): Int {
        val cleanHost = sanitizeHost(hostname)
        val famKey = "$generation:$cleanHost:${family.name}"
        return familyFailures[famKey]?.get() ?: 0
    }

    /**
     * Сортировка пула IP-адресов с учетом пути хоста, семейства адресов, NAT64/IPv6-only сети
     * и быстрого понижения broken IPv6 (MOB-024 / MOB-025).
     */
    fun prioritizeAddresses(hostname: String, addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.size <= 1) return addresses
        val cleanHost = sanitizeHost(hostname)
        val curGen = currentNetworkGeneration()
        val isIpv6Only = isIpv6OnlyNetwork()

        val ipv6Failures = if (cleanHost.isNotEmpty()) {
            getFamilyFailures(curGen, cleanHost, AddressFamily.IPV6)
        } else 0

        val ipv4Failures = if (cleanHost.isNotEmpty()) {
            getFamilyFailures(curGen, cleanHost, AddressFamily.IPV4)
        } else 0

        val comparator = Comparator<InetAddress> { a, b ->
            val ipA = a.hostAddress ?: ""
            val ipB = b.hostAddress ?: ""

            val readyA = if (cleanHost.isNotEmpty()) isRouteReady(curGen, cleanHost, ipA) else false
            val readyB = if (cleanHost.isNotEmpty()) isRouteReady(curGen, cleanHost, ipB) else false
            if (readyA != readyB) {
                return@Comparator if (readyA) -1 else 1
            }

            val rttA = ipRttRatings[ipA] ?: 9999L
            val rttB = ipRttRatings[ipB] ?: 9999L
            rttA.compareTo(rttB)
        }

        val v6 = addresses.filter { it is java.net.Inet6Address }.sortedWith(comparator)
        val v4 = addresses.filter { it !is java.net.Inet6Address }.sortedWith(comparator)

        if (v6.isEmpty()) return v4
        if (v4.isEmpty()) return v6

        // 1. В IPv6-only сети или если IPv4 сбоил на этом хосте: все IPv6 идут первыми без ожидания IPv4
        if (isIpv6Only || (ipv4Failures > 0 && ipv6Failures == 0)) {
            return v6 + v4
        }

        // 2. Broken IPv6 rapid demotion: если IPv6 сбоил для этого хоста в текущей сети,
        // адреса IPv4 идут первыми!
        if (ipv6Failures > 0 && ipv4Failures == 0) {
            return v4 + v6
        }

        // 3. Стандартная dual-stack сеть по RFC 8305: чередование (interleaving) IPv6 и IPv4, начиная с IPv6
        val result = ArrayList<InetAddress>(addresses.size)
        val maxLen = maxOf(v6.size, v4.size)
        for (i in 0 until maxLen) {
            if (i < v6.size) result.add(v6[i])
            if (i < v4.size) result.add(v4[i])
        }
        return result
    }

    /**
     * Синтезирует IPv6-адрес из IPv4 по RFC 6052 Well-Known Prefix (64:ff9b::/96) для работы через NAT64/DNS64.
     */
    fun synthesizeNat64(ipv4: InetAddress, prefix: String = TgConstants.NAT64_WELL_KNOWN_PREFIX): InetAddress? {
        val ipStr = ipv4.hostAddress ?: return null
        val nat64Str = TgConstants.synthesizeNat64(ipStr, prefix) ?: return null
        return try {
            InetAddress.getByName(nat64Str)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Проверяет, является ли адрес синтезированным адресом NAT64 (RFC 6052).
     */
    fun isNat64Address(address: InetAddress): Boolean {
        val bytes = address.address ?: return false
        if (bytes.size != 16) return false
        return bytes[0] == 0.toByte() && bytes[1] == 0x64.toByte() &&
               bytes[2] == 0xFF.toByte() && bytes[3] == 0x9B.toByte() &&
               (4..11).all { bytes[it] == 0.toByte() }
    }

    /**
     * Извлекает исходный IPv4-адрес из синтезированного адреса NAT64 (RFC 6052).
     */
    fun extractIpv4FromNat64(address: InetAddress): InetAddress? {
        val bytes = address.address ?: return null
        if (bytes.size != 16) return null
        if (bytes[0] != 0.toByte() || bytes[1] != 0x64.toByte() ||
            bytes[2] != 0xFF.toByte() || bytes[3] != 0x9B.toByte() ||
            !(4..11).all { bytes[it] == 0.toByte() }) {
            return null
        }
        val v4Bytes = byteArrayOf(bytes[12], bytes[13], bytes[14], bytes[15])
        return try {
            InetAddress.getByAddress(v4Bytes)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Обратная совместимость со старым вызовом без указания hostname.
     */
    fun prioritizeAddresses(addresses: List<InetAddress>): List<InetAddress> =
        prioritizeAddresses("", addresses)

    /**
     * Ступенчатый конкурентный опрос пула IP-адресов с поддержкой стадийного установления.
     */
    suspend fun raceConnect(
        hostname: String = "",
        addresses: List<InetAddress>,
        port: Int = 443,
        attemptDelayMs: Long = DEFAULT_ATTEMPT_DELAY_MS,
        timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
        tlsSni: String? = null,
        requireFullEstablishment: Boolean = false
    ): HappyEyeballsResult? = withContext(Dispatchers.IO) {
        val expectedEpoch = currentRaceEpoch()
        val curGen = currentNetworkGeneration()
        if (addresses.isEmpty()) return@withContext null

        val prioritized = prioritizeAddresses(hostname, addresses)
        if (prioritized.size == 1) {
            val single = prioritized[0]
            val fam = if (single is java.net.Inet6Address) AddressFamily.IPV6 else AddressFamily.IPV4
            val ipStr = single.hostAddress ?: ""
            val outcome = probeEstablishment(single, port, timeoutMs, tlsSni, requireFullEstablishment)
            return@withContext if (outcome != null) {
                if (recordIpRttIfCurrent(expectedEpoch, ipStr, outcome.handshakeRttMs)) {
                    val targetStage = if (requireFullEstablishment || !tlsSni.isNullOrBlank()) {
                        EstablishmentStage.READY
                    } else {
                        EstablishmentStage.TCP
                    }
                    recordStageSuccess(curGen, hostname, ipStr, fam, targetStage, outcome.handshakeRttMs)
                    HappyEyeballsResult(single, outcome.handshakeRttMs, 0, 1, targetStage, outcome.tcpRttMs)
                } else null
            } else {
                val failStage = if (requireFullEstablishment || !tlsSni.isNullOrBlank()) {
                    EstablishmentStage.TLS
                } else {
                    EstablishmentStage.TCP
                }
                recordStageFailure(curGen, hostname, ipStr, fam, failStage)
                null
            }
        }

        withTimeoutOrNull(timeoutMs + (prioritized.size * attemptDelayMs)) {
            val deferred = CompletableDeferred<HappyEyeballsResult>()
            val raceJob = Job()
            val failuresCount = AtomicInteger(0)
            val total = prioritized.size

            for (index in prioritized.indices) {
                val candidate = prioritized[index]
                val fam = if (candidate is java.net.Inet6Address) AddressFamily.IPV6 else AddressFamily.IPV4
                val candIp = candidate.hostAddress ?: ""

                scope.launch(raceJob) {
                    if (index > 0) {
                        delay(index * attemptDelayMs)
                    }

                    if (!isActive || deferred.isCompleted) return@launch

                    val outcome = probeEstablishment(candidate, port, timeoutMs, tlsSni, requireFullEstablishment)
                    if (outcome != null) {
                        if (currentRaceEpoch() != expectedEpoch || currentNetworkGeneration() != curGen) {
                            deferred.completeExceptionally(kotlinx.coroutines.CancellationException("stale race generation"))
                            return@launch
                        }
                        val targetStage = if (requireFullEstablishment || !tlsSni.isNullOrBlank()) {
                            EstablishmentStage.READY
                        } else {
                            EstablishmentStage.TCP
                        }
                        val result = HappyEyeballsResult(
                            winningAddress = candidate,
                            handshakeRttMs = outcome.handshakeRttMs,
                            attemptIndex = index,
                            totalCandidates = total,
                            stage = targetStage,
                            tcpRttMs = outcome.tcpRttMs
                        )
                        if (deferred.complete(result)) {
                            val applied = recordIpRttIfCurrent(expectedEpoch, candIp, outcome.handshakeRttMs)
                            recordStageSuccess(curGen, hostname, candIp, fam, targetStage, outcome.handshakeRttMs)
                            if (applied && lastLoggedWinningIp != candIp) {
                                lastLoggedWinningIp = candIp
                                AppLogger.i(
                                    TAG,
                                    "Happy Eyeballs v2 выбрал оптимальный IP $candIp ($fam, stage=$targetStage, RTT: ${outcome.handshakeRttMs}мс, попытка #$index из $total)"
                                )
                            }
                            raceJob.cancelChildren()
                        }
                    } else {
                        val failStage = if (requireFullEstablishment || !tlsSni.isNullOrBlank()) {
                            EstablishmentStage.TLS
                        } else {
                            EstablishmentStage.TCP
                        }
                        recordStageFailure(curGen, hostname, candIp, fam, failStage)

                        if (failuresCount.incrementAndGet() >= total) {
                            deferred.completeExceptionally(NoSuchElementException("Все Anycast IP недоступны"))
                        }
                    }
                }
            }

            try {
                val result = deferred.await()
                if (currentRaceEpoch() == expectedEpoch && currentNetworkGeneration() == curGen) result else null
            } catch (_: Exception) {
                null
            } finally {
                raceJob.cancel()
            }
        }
    }

    /**
     * Обратная совместимость с вызовом без указания hostname и TLS параметров.
     */
    suspend fun raceConnect(
        addresses: List<InetAddress>,
        port: Int = 443,
        attemptDelayMs: Long = DEFAULT_ATTEMPT_DELAY_MS,
        timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS
    ): HappyEyeballsResult? = raceConnect(
        hostname = "",
        addresses = addresses,
        port = port,
        attemptDelayMs = attemptDelayMs,
        timeoutMs = timeoutMs,
        tlsSni = null,
        requireFullEstablishment = false
    )

    private data class EstablishmentProbeOutcome(
        val handshakeRttMs: Long,
        val tcpRttMs: Long
    )

    /**
     * Проверка соединения: TCP connect и, при необходимости, полное TLS рукопожатие с SNI.
     */
    private fun probeEstablishment(
        address: InetAddress,
        port: Int,
        timeoutMs: Long,
        tlsSni: String?,
        requireFullEstablishment: Boolean
    ): EstablishmentProbeOutcome? {
        val socket = Socket()
        var sslSocket: SSLSocket? = null
        return try {
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs.toInt()
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(address, port), timeoutMs.toInt())
            val tcpConnected = System.currentTimeMillis()
            val tcpRtt = (tcpConnected - start).coerceAtLeast(1L)

            if (!requireFullEstablishment && tlsSni.isNullOrBlank()) {
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                EstablishmentProbeOutcome(handshakeRttMs = elapsed, tcpRttMs = tcpRtt)
            } else {
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sniHost = tlsSni ?: address.hostAddress ?: ""
                sslSocket = sslFactory.createSocket(socket, sniHost, port, true) as SSLSocket
                sslSocket.soTimeout = timeoutMs.toInt()
                val sslParams = sslSocket.sslParameters ?: SSLParameters()
                if (!tlsSni.isNullOrBlank()) {
                    sslParams.serverNames = listOf(SNIHostName(tlsSni))
                }
                try {
                    sslParams.endpointIdentificationAlgorithm = "HTTPS"
                } catch (_: Throwable) {}
                sslSocket.sslParameters = sslParams
                sslSocket.startHandshake()

                if (!tlsSni.isNullOrBlank()) {
                    val session = sslSocket.session
                    if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(tlsSni, session)) {
                        throw javax.net.ssl.SSLPeerUnverifiedException("Certificate hostname mismatch: $tlsSni")
                    }
                }
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                EstablishmentProbeOutcome(handshakeRttMs = elapsed, tcpRttMs = tcpRtt)
            }
        } catch (_: Exception) {
            null
        } finally {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Обновление рейтинга RTT для IP-адреса по формуле EWMA.
     */
    fun recordIpRtt(ip: String, newRtt: Long) {
        if (ip.isBlank()) return
        val current = ipRttRatings[ip]
        if (current == null) {
            ipRttRatings[ip] = newRtt
        } else {
            // 70% старого значения + 30% нового
            val smoothed = ((current * 0.7) + (newRtt * 0.3)).toLong().coerceAtLeast(1L)
            ipRttRatings[ip] = smoothed
        }
    }

    fun getKnownRatings(): Map<String, Long> = ipRttRatings.toMap()

    fun clearRating() {
        synchronized(generationLock) {
            raceEpoch.incrementAndGet()
            ipRttRatings.clear()
            routeScores.clear()
            familyFailures.clear()
            ipHighestStage.clear()
            ipv6OnlyNetwork.set(false)
            lastLoggedWinningIp = null
        }
        AppLogger.i(TAG, "Рейтинг Anycast IP и метрики маршрутов очищены")
    }

    private fun sanitizeHost(host: String): String =
        host.removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("wss://")
            .substringBefore(":")
            .substringBefore("/")
            .trim()
            .lowercase()
}
