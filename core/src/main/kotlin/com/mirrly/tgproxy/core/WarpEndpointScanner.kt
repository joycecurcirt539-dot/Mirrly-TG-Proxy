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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ступени верификации эндпоинта Cloudflare WARP / MASQUE:
 * 1. UNREACHABLE — узел не отвечает по UDP (таймаут или отброшен сетью/ТСПУ).
 * 2. UDP_RESPONSIVE — получен непустой UDP-ответ, но структура пакета не подтверждает целевой протокол
 *    (произвольный UDP echo, сторонний DNS/NTP сервис, QUIC Version Negotiation или Retry Token).
 * 3. PROTOCOL_RECOGNIZED — подтверждена сигнатура и валидный заголовок целевого протокола
 *    (WireGuard Handshake Response 92B / Cookie Reply, либо QUIC Initial ServerHello / Handshake).
 * 4. AUTHENTICATED — завершена взаимная криптографическая аутентификация (TLS/QUIC handshake или WireGuard key-exchange).
 * 5. DATA_PLANE_VERIFIED — подтверждена сквозная передача полезной нагрузки через туннель (HTTP/3 200..300 или обмен с Telegram DC).
 */
enum class WarpEndpointStage(val priority: Int, val displayName: String) {
    UNREACHABLE(0, "Unreachable"),
    UDP_RESPONSIVE(1, "UdpResponsive"),
    PROTOCOL_RECOGNIZED(2, "ProtocolRecognized"),
    AUTHENTICATED(3, "Authenticated"),
    DATA_PLANE_VERIFIED(4, "DataPlaneVerified");

    fun isAtLeast(other: WarpEndpointStage): Boolean = this.priority >= other.priority
}

typealias WarpEndpointStatus = WarpEndpointStage

data class WarpEndpointCandidate(
    val ip: String,
    val port: Int,
    val endpoint: String = if (ip.contains(":") && !ip.startsWith("[")) "[$ip]:$port" else "$ip:$port",
    val rttMs: Long = -1L,
    val isAlive: Boolean = false,
    val responseBytes: Int = 0,
    val probeProtocol: String = "QUIC",
    val scanProtocol: WarpProbeProtocol = WarpProbeProtocol.MASQUE_QUIC,
    val isUdpResponsive: Boolean = isAlive,
    val isProtocolRecognized: Boolean = false,
    val isAuthenticated: Boolean = false,
    val isDataPlaneVerified: Boolean = false
) : Comparable<WarpEndpointCandidate> {

    val stage: WarpEndpointStage
        get() = when {
            !isAlive -> WarpEndpointStage.UNREACHABLE
            isDataPlaneVerified -> WarpEndpointStage.DATA_PLANE_VERIFIED
            isAuthenticated -> WarpEndpointStage.AUTHENTICATED
            isProtocolRecognized -> WarpEndpointStage.PROTOCOL_RECOGNIZED
            isUdpResponsive -> WarpEndpointStage.UDP_RESPONSIVE
            else -> WarpEndpointStage.UNREACHABLE
        }

    constructor(
        ip: String,
        port: Int,
        endpoint: String = if (ip.contains(":") && !ip.startsWith("[")) "[$ip]:$port" else "$ip:$port",
        rttMs: Long = -1L,
        isAlive: Boolean = false,
        responseBytes: Int = 0,
        probeProtocol: String = "QUIC",
        scanProtocol: WarpProbeProtocol = WarpProbeProtocol.MASQUE_QUIC,
        stage: WarpEndpointStage
    ) : this(
        ip = ip,
        port = port,
        endpoint = endpoint,
        rttMs = rttMs,
        isAlive = isAlive && stage != WarpEndpointStage.UNREACHABLE,
        responseBytes = responseBytes,
        probeProtocol = probeProtocol,
        scanProtocol = scanProtocol,
        isUdpResponsive = isAlive && stage.priority >= WarpEndpointStage.UDP_RESPONSIVE.priority,
        isProtocolRecognized = isAlive && stage.priority >= WarpEndpointStage.PROTOCOL_RECOGNIZED.priority,
        isAuthenticated = isAlive && stage.priority >= WarpEndpointStage.AUTHENTICATED.priority,
        isDataPlaneVerified = isAlive && stage.priority >= WarpEndpointStage.DATA_PLANE_VERIFIED.priority
    )

    /**
     * Возвращает true, если кандидат прошел минимальную проверку протокола (не просто UDP echo или QUIC VN).
     */
    fun isWorkingWarp(): Boolean = isAlive && stage.priority >= WarpEndpointStage.PROTOCOL_RECOGNIZED.priority

    /**
     * Проверяет, подходит ли кандидат для закрепления в качестве Sticky Profile.
     */
    fun isSuitableForSticky(minStage: WarpEndpointStage = WarpEndpointStage.PROTOCOL_RECOGNIZED): Boolean =
        isAlive && stage.priority >= minStage.priority

    /**
     * Полная сквозная верификация (аутентификация или подтвержденный data plane).
     */
    fun isFullyVerified(): Boolean = isAlive && (isAuthenticated || isDataPlaneVerified)

    override fun compareTo(other: WarpEndpointCandidate): Int {
        if (this.isAlive != other.isAlive) {
            return if (this.isAlive) -1 else 1
        }
        if (!this.isAlive && !other.isAlive) return 0

        // 1. Сравнение по ступени проверки (более высокая ступень строго приоритетнее)
        val stageComp = other.stage.priority.compareTo(this.stage.priority)
        if (stageComp != 0) {
            return stageComp
        }

        // 2. Одинаковый вид проверки (одинаковая ступень) - сравниваем задержку RTT
        if (this.rttMs >= 0 && other.rttMs >= 0) {
            val rttComp = this.rttMs.compareTo(other.rttMs)
            if (rttComp != 0) return rttComp
        } else if (this.rttMs >= 0) {
            return -1
        } else if (other.rttMs >= 0) {
            return 1
        }

        return this.endpoint.compareTo(other.endpoint)
    }
}

enum class WarpProbeProtocol(val displayName: String) {
    MASQUE_QUIC("MASQUE / QUIC"),
    WIREGUARD("WireGuard / AWG")
}

/**
 * Высокопроизводительный асинхронный сканер и автоподборщик рабочих эндпоинтов и портов Cloudflare WARP.
 *
 * Поддерживает раздельное зондирование:
 * 1. MASQUE_QUIC: RFC 9001 валидный QUIC Initial ClientHello с ALPN `h3` и SNI `consumer-masque.cloudflareclient.com`.
 * 2. WIREGUARD: 148-байтный handshake initiation зонд для AmneziaWG / WireGuard.
 * 3. Sticky Profile: фиксация быстрого надежного узла для мгновенного переподключения.
 * 4. Сквозная валидация: через SOCKS5 прокси до дата-центров Telegram DC.
 */
object WarpEndpointScanner {

    private const val TAG = "WarpEndpointScanner"

    const val MASQUE_SNI = "consumer-masque.cloudflareclient.com"
    const val MASQUE_ALPN = "h3"

    @Volatile
    private var stickyProfile: WarpEndpointCandidate? = null
    private val consecutiveTimeouts = AtomicInteger(0)

    fun getStickyProfile(): WarpEndpointCandidate? = stickyProfile

    fun getConsecutiveTimeouts(): Int = consecutiveTimeouts.get()

    fun setStickyProfile(candidate: WarpEndpointCandidate?) {
        stickyProfile = candidate
        consecutiveTimeouts.set(0)
        if (candidate != null) {
            AppLogger.d(TAG, "Sticky Profile установлен: ${candidate.endpoint} (${candidate.probeProtocol}, RTT=${candidate.rttMs}мс)")
            try {
                NativeProxy.setWarpStickyEndpoint(candidate.endpoint)
            } catch (_: Throwable) {}
        } else {
            try {
                NativeProxy.clearWarpStickyProfile()
            } catch (_: Throwable) {}
        }
    }

    fun clearStickyProfile() {
        stickyProfile = null
        consecutiveTimeouts.set(0)
        try {
            NativeProxy.clearWarpStickyProfile()
        } catch (_: Throwable) {}
    }

    fun recordStickySuccess() {
        consecutiveTimeouts.set(0)
        try {
            NativeProxy.recordWarpStickySuccess()
        } catch (_: Throwable) {}
    }

    fun recordStickyTimeout(): Boolean {
        val count = consecutiveTimeouts.incrementAndGet()
        val nativeInvalidated = try {
            NativeProxy.recordWarpStickyTimeout()
        } catch (_: Throwable) {
            false
        }
        return if (count >= 3 || nativeInvalidated) {
            AppLogger.w(TAG, "Sticky Profile ${stickyProfile?.endpoint} аннулирован после 3 таймаутов подряд!")
            stickyProfile = null
            consecutiveTimeouts.set(0)
            true
        } else {
            AppLogger.d(TAG, "Sticky Profile зафиксирован таймаут ($count/3)")
            false
        }
    }

    /**
     * Пул чистых Anycast IPv4-адресов Cloudflare с маршрутизацией через европейские и азиатские PoP.
     */
    val CLEAN_IPV4_POOL = listOf(
        "188.114.96.1",
        "188.114.96.2",
        "188.114.96.3",
        "188.114.96.5",
        "188.114.96.10",
        "188.114.97.1",
        "188.114.97.2",
        "188.114.97.3",
        "188.114.97.5",
        "188.114.97.10",
        "162.159.192.1",
        "162.159.192.2",
        "162.159.192.5",
        "162.159.193.1",
        "162.159.193.2",
        "162.159.193.5",
        "162.159.195.1",
        "162.159.195.2",
        "162.159.204.1",
        "162.159.204.2"
    )

    /**
     * Пул Anycast IPv6-адресов Cloudflare WARP (часто игнорируются или менее жестко фильтруются ТСПУ).
     */
    val CLEAN_IPV6_POOL = listOf(
        "2606:4700:d0::a29f:c001",
        "2606:4700:d0::a29f:c101",
        "2606:4700:110::a29f:c001"
    )

    /**
     * Полный спектр активных UDP-портов Cloudflare WARP.
     * Порты отсортированы по статистике выживаемости под фильтрами ТСПУ в России.
     */
    val WARP_PORTS_PRIORITIZED = listOf(
        // Топ нестандартных портов (наиболее редкие в правилах DPI)
        8095, 8443, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908,
        928, 934, 939, 942, 943, 945, 946, 955, 968, 987, 988, 1002,
        1010, 1014, 1070, 1074, 1194, 1701, 8981,
        // Популярные стандартные порты (чаще подвержены черным спискам)
        500, 4500, 2408
    )

    /**
     * Возвращает пул из 50 преднастроенных Anycast-профилей (комбинация чистых IP и приоритетных портов).
     */
    fun getPreconfiguredProfiles50(includeIpv6: Boolean = true): List<String> {
        val candidates = LinkedHashSet<String>()
        val ipPool = if (includeIpv6) CLEAN_IPV4_POOL + CLEAN_IPV6_POOL else CLEAN_IPV4_POOL
        var ipIndex = 0

        // Проход 1: по одному уникальному IP на каждый из приоритетных портов (полный охват портов)
        for (port in WARP_PORTS_PRIORITIZED) {
            val ip = ipPool[ipIndex % ipPool.size]
            ipIndex++
            val ep = if (ip.contains(":") && !ip.startsWith("[")) "[$ip]:$port" else "$ip:$port"
            candidates.add(ep)
            if (candidates.size >= 50) {
                return candidates.toList()
            }
        }

        // Проход 2: дополнение пула до 50 профилей со следующими IP
        for (port in WARP_PORTS_PRIORITIZED) {
            val ip = ipPool[ipIndex % ipPool.size]
            ipIndex++
            val ep = if (ip.contains(":") && !ip.startsWith("[")) "[$ip]:$port" else "$ip:$port"
            candidates.add(ep)
            if (candidates.size >= 50) {
                return candidates.toList()
            }
        }

        return candidates.toList()
    }

    val PRECONFIGURED_PROFILES_50: List<String> by lazy {
        getPreconfiguredProfiles50(includeIpv6 = true)
    }

    /**
     * Генерирует дефолтный приоритетный список кандидатов эндпоинтов (IP:порт).
     */
    fun getDefaultCandidates(includeIpv6: Boolean = false): List<String> = getPreconfiguredProfiles50(includeIpv6)

    /**
     * Параллельный Fast Probe по пулу эндпоинтов с жестким лимитом времени 400 мс (RFC 9000/NOVA style).
     */
    suspend fun fastProbeParallel(
        endpoints: List<String> = PRECONFIGURED_PROFILES_50,
        timeoutMs: Long = 400L,
        maxConcurrency: Int = 20,
        useFragmentation: Boolean = true,
        protocol: WarpProbeProtocol = WarpProbeProtocol.MASQUE_QUIC,
        minStage: WarpEndpointStage? = null
    ): List<WarpEndpointCandidate> = scanEndpoints(
        endpoints = endpoints,
        maxConcurrency = maxConcurrency,
        timeoutMs = timeoutMs,
        useFragmentation = useFragmentation,
        protocol = protocol,
        minStage = minStage
    )

    /**
     * Выполняет адаптивное зондирование конкретного эндпоинта по UDP с разделением протоколов:
     * 1. Сначала отправляется чистый зонд без мусора.
     * 2. Если чистый зонд не дал ответа (блокировка ТСПУ) и разрешена десинхронизация, отправляется десинхронизированный запрос.
     *
     * @param endpoint Адрес в формате "IP:port" или "[IPv6]:port"
     * @param timeoutMs Таймаут ожидания ответа в миллисекундах
     * @param useFragmentation Использовать фрагментацию / десинхронизацию первого пакета
     * @param protocol Протокол зонда: MASQUE_QUIC (по умолчанию) или WIREGUARD
     */
    suspend fun probeEndpoint(
        endpoint: String,
        timeoutMs: Long = 750,
        useFragmentation: Boolean = true,
        protocol: WarpProbeProtocol = WarpProbeProtocol.MASQUE_QUIC
    ): WarpEndpointCandidate = withContext(Dispatchers.IO) {
        val (ipStr, port) = parseEndpoint(endpoint)
        if (ipStr.isBlank() || port <= 0) {
            return@withContext WarpEndpointCandidate(ipStr, port, endpoint, isAlive = false, scanProtocol = protocol)
        }

        // 1. Быстрая попытка чистого зонда
        val cleanTimeout = if (useFragmentation) 400L else timeoutMs
        val cleanCandidate = probeSocketInternal(ipStr, port, endpoint, cleanTimeout, useDesync = false, protocol = protocol)
        if (cleanCandidate.isAlive) {
            return@withContext cleanCandidate
        }

        // 2. Если чистый зонд отброшен ТСПУ и включен fallback десинхронизации
        if (useFragmentation) {
            val desyncedCandidate = probeSocketInternal(ipStr, port, endpoint, timeoutMs, useDesync = true, protocol = protocol)
            if (desyncedCandidate.isAlive) {
                return@withContext desyncedCandidate
            }
        }

        cleanCandidate
    }

    private fun probeSocketInternal(
        ipStr: String,
        port: Int,
        endpoint: String,
        timeoutMs: Long,
        useDesync: Boolean,
        protocol: WarpProbeProtocol
    ): WarpEndpointCandidate {
        var socket: DatagramSocket? = null
        return try {
            val targetAddr = InetAddress.getByName(ipStr)
            val socketAddress = InetSocketAddress(targetAddr, port)

            socket = DatagramSocket()
            socket.soTimeout = timeoutMs.toInt()

            val probePayload = when (protocol) {
                WarpProbeProtocol.MASQUE_QUIC -> ProtonQuicInitial.createQuicInitialPacket(
                    sni = MASQUE_SNI,
                    alpn = MASQUE_ALPN
                )
                WarpProbeProtocol.WIREGUARD -> WarpPacketFragmenter.createWireGuardInitiationProbe()
            }
            if (probePayload.isEmpty()) {
                return WarpEndpointCandidate(ipStr, port, endpoint, rttMs = -1L, isAlive = false, scanProtocol = protocol)
            }

            val startNs = System.nanoTime()

            if (useDesync) {
                WarpPacketFragmenter.sendDesyncedUdp(
                    socket = socket,
                    target = socketAddress,
                    payload = probePayload,
                    delayMs = 2
                )
            } else {
                val packet = DatagramPacket(probePayload, probePayload.size, socketAddress)
                socket.send(packet)
            }

            val recvBuffer = ByteArray(1500)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)

            val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
            var matchedResponse = false

            while (System.nanoTime() < deadlineNs) {
                val remainingMs = ((deadlineNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
                socket.soTimeout = remainingMs.toInt()
                socket.receive(recvPacket)

                // Проверяем источник ответа: адрес и порт обязаны строго совпадать с опрашиваемым узлом
                val isExpectedHost = recvPacket.address == targetAddr ||
                    recvPacket.address.hostAddress == targetAddr.hostAddress
                val isExpectedPort = recvPacket.port == port

                if (isExpectedHost && isExpectedPort) {
                    matchedResponse = true
                    break
                } else {
                    AppLogger.w(TAG, "Пропущен посторонний UDP пакет от ${recvPacket.address?.hostAddress}:${recvPacket.port} при опросе $endpoint")
                }
            }

            if (!matchedResponse) {
                return WarpEndpointCandidate(ipStr, port, endpoint, rttMs = -1L, isAlive = false, scanProtocol = protocol)
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            val recvLen = recvPacket.length

            val (stage, protocolDesc) = when (protocol) {
                WarpProbeProtocol.MASQUE_QUIC -> classifyQuicResponse(recvBuffer, recvLen)
                WarpProbeProtocol.WIREGUARD -> classifyWireGuardResponse(recvBuffer, recvLen)
            }

            AppLogger.d(TAG, "Эндпоинт $endpoint ответил ($protocol, stage=$stage, ${if (useDesync) "десинхр" else "чистый"}): $protocolDesc за ${elapsedMs}мс")
            WarpEndpointCandidate(
                ip = ipStr,
                port = port,
                endpoint = endpoint,
                rttMs = elapsedMs,
                isAlive = true,
                responseBytes = recvLen,
                probeProtocol = protocolDesc,
                scanProtocol = protocol,
                stage = stage
            )
        } catch (_: SocketTimeoutException) {
            WarpEndpointCandidate(ipStr, port, endpoint, rttMs = -1L, isAlive = false, scanProtocol = protocol)
        } catch (e: Exception) {
            AppLogger.d(TAG, "Ошибка проверки $endpoint: ${e.message}")
            WarpEndpointCandidate(ipStr, port, endpoint, rttMs = -1L, isAlive = false, scanProtocol = protocol)
        } finally {
            socket?.close()
        }
    }

    fun classifyWireGuardResponse(buf: ByteArray, recvLen: Int): Pair<WarpEndpointStage, String> {
        if (recvLen < 4) {
            return Pair(WarpEndpointStage.UDP_RESPONSIVE, "WireGuard (UDP Alive, $recvLen bytes)")
        }
        val msgType = buf[0].toInt() and 0xFF
        val rsv0 = buf[1].toInt() and 0xFF
        val rsv1 = buf[2].toInt() and 0xFF
        val rsv2 = buf[3].toInt() and 0xFF
        val isReservedZero = (rsv0 or rsv1 or rsv2) == 0

        // Эхо исходящего Handshake Initiation пакета (Тип 1)
        if (msgType == 0x01) {
            return Pair(WarpEndpointStage.UDP_RESPONSIVE, "WireGuard (UDP Echo Detected, $recvLen bytes)")
        }

        // Handshake Response: Message Type 2, 3 зарезервированных байта 0x00, длина ровно 92 байта
        if (recvLen == 92 && msgType == 0x02 && isReservedZero) {
            return Pair(WarpEndpointStage.PROTOCOL_RECOGNIZED, "WireGuard (Handshake Response)")
        }

        // Cookie Reply: Message Type 3, 3 зарезервированных байта 0x00, длина 60..64 байта
        if (recvLen in 60..64 && msgType == 0x03 && isReservedZero) {
            return Pair(WarpEndpointStage.PROTOCOL_RECOGNIZED, "WireGuard (Cookie Reply)")
        }

        return Pair(WarpEndpointStage.UDP_RESPONSIVE, "WireGuard (UDP Alive, $recvLen bytes)")
    }

    fun classifyQuicResponse(buf: ByteArray, recvLen: Int): Pair<WarpEndpointStage, String> {
        if (recvLen < 5) {
            return Pair(WarpEndpointStage.UDP_RESPONSIVE, "QUIC (UDP Alive, $recvLen bytes)")
        }
        val firstByte = buf[0].toInt() and 0xFF
        val isLongHeader = (firstByte and 0x80) != 0

        if (isLongHeader) {
            val v0 = buf[1].toInt() and 0xFF
            val v1 = buf[2].toInt() and 0xFF
            val v2 = buf[3].toInt() and 0xFF
            val v3 = buf[4].toInt() and 0xFF
            val isVersionNegotiation = (v0 or v1 or v2 or v3) == 0

            if (isVersionNegotiation) {
                // Version Negotiation доказывает наличие QUIC стека, но НЕ означает согласованный туннель или авторизацию
                return Pair(WarpEndpointStage.UDP_RESPONSIVE, "QUIC (Version Negotiation, $recvLen bytes)")
            }

            val pktType = (firstByte and 0x30) shr 4
            return when (pktType) {
                0x00 -> Pair(WarpEndpointStage.PROTOCOL_RECOGNIZED, "QUIC (Initial / ServerHello, $recvLen bytes)")
                0x01 -> Pair(WarpEndpointStage.PROTOCOL_RECOGNIZED, "QUIC (0-RTT, $recvLen bytes)")
                0x02 -> Pair(WarpEndpointStage.PROTOCOL_RECOGNIZED, "QUIC (Handshake, $recvLen bytes)")
                0x03 -> Pair(WarpEndpointStage.UDP_RESPONSIVE, "QUIC (Retry Token, $recvLen bytes)")
                else -> Pair(WarpEndpointStage.UDP_RESPONSIVE, "QUIC (Long Header, $recvLen bytes)")
            }
        } else {
            return Pair(WarpEndpointStage.PROTOCOL_RECOGNIZED, "QUIC (1-RTT Data, $recvLen bytes)")
        }
    }

    private fun parseWireGuardResponse(recvLen: Int): String = classifyWireGuardResponse(ByteArray(recvLen), recvLen).second

    private fun parseQuicResponse(buf: ByteArray, recvLen: Int): String = classifyQuicResponse(buf, recvLen).second

    /**
     * Сканирует список эндпоинтов с ограничением параллелизма и возвращает список живых,
     * отсортированных по возрастанию задержки (RTT) с учетом ступени проверки.
     */
    suspend fun scanEndpoints(
        endpoints: List<String> = getDefaultCandidates(),
        maxConcurrency: Int = 12,
        timeoutMs: Long = 750,
        useFragmentation: Boolean = true,
        protocol: WarpProbeProtocol = WarpProbeProtocol.MASQUE_QUIC,
        minStage: WarpEndpointStage? = null,
        onProgress: ((scanned: Int, total: Int, bestFound: WarpEndpointCandidate?) -> Unit)? = null
    ): List<WarpEndpointCandidate> = coroutineScope {
        val semaphore = Semaphore(maxConcurrency)
        val total = endpoints.size
        var scannedCount = 0
        var currentBest: WarpEndpointCandidate? = null
        val lock = Any()

        val deferredResults = endpoints.map { ep ->
            async(Dispatchers.IO) {
                val res = semaphore.withPermit {
                    probeEndpoint(ep, timeoutMs = timeoutMs, useFragmentation = useFragmentation, protocol = protocol)
                }
                synchronized(lock) {
                    scannedCount++
                    if (res.isAlive && (minStage == null || res.stage.isAtLeast(minStage))) {
                        if (currentBest == null || res < currentBest!!) {
                            currentBest = res
                        }
                    }
                    onProgress?.invoke(scannedCount, total, currentBest)
                }
                res
            }
        }

        val allResults = deferredResults.awaitAll()
        if (minStage != null) {
            allResults.filter { it.isAlive && it.stage.isAtLeast(minStage) }.sorted()
        } else {
            allResults.filter { it.isAlive }.sorted()
        }
    }

    /**
     * Быстрый автоподбор: проверяет Sticky Profile с лимитом 400мс, либо запускает параллельный Fast Probe пула 50 узлов.
     * Если рабочий узел зафиксирован (Sticky Profile), последующий трафик Telegram идет через него до момента фиксации 3 таймаутов подряд.
     * Закрепление в качестве Sticky Profile допускается только после достижения требуемой ступени (по умолчанию PROTOCOL_RECOGNIZED).
     */
    suspend fun findBestEndpoint(
        useFragmentation: Boolean = true,
        maxCandidatesToProbe: Int = 24,
        timeoutMs: Long = 400L,
        protocol: WarpProbeProtocol = WarpProbeProtocol.MASQUE_QUIC,
        preferSticky: Boolean = true,
        minStage: WarpEndpointStage = WarpEndpointStage.PROTOCOL_RECOGNIZED
    ): WarpEndpointCandidate? = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Запуск быстрого автоподбора портов Cloudflare WARP (протокол=$protocol, фрагментация=$useFragmentation, требуемая ступень=$minStage)...")

        // 1. Проверяем Sticky Profile если включен и не превышен лимит 3 таймаутов
        if (preferSticky) {
            stickyProfile?.let { sticky ->
                if (sticky.scanProtocol == protocol && consecutiveTimeouts.get() < 3) {
                    val fastProbe = probeEndpoint(
                        endpoint = sticky.endpoint,
                        timeoutMs = 400L,
                        useFragmentation = useFragmentation,
                        protocol = protocol
                    )
                    if (fastProbe.isSuitableForSticky(minStage) && fastProbe.rttMs <= 400L) {
                        AppLogger.i(TAG, "Sticky Profile подтвержден: ${fastProbe.endpoint} (${fastProbe.probeProtocol}, RTT=${fastProbe.rttMs}мс, stage=${fastProbe.stage})")
                        stickyProfile = fastProbe
                        recordStickySuccess()
                        return@withContext fastProbe
                    } else {
                        val invalidated = recordStickyTimeout()
                        if (!invalidated && sticky.isSuitableForSticky(minStage)) {
                            AppLogger.w(TAG, "Sticky Profile ${sticky.endpoint} не подтвержден за 400мс (${consecutiveTimeouts.get()}/3 таймаутов), удерживаем профиль")
                            return@withContext sticky
                        }
                    }
                }
            }
        }

        // 2. Параллельный Fast Probe с лимитом времени 400 мс по пулу преднастроенных Anycast-профилей
        val candidates = getPreconfiguredProfiles50().take(maxCandidatesToProbe)
        val aliveCandidates = fastProbeParallel(
            endpoints = candidates,
            maxConcurrency = 16,
            timeoutMs = timeoutMs,
            useFragmentation = useFragmentation,
            protocol = protocol,
            minStage = null
        )

        val verifiedCandidates = aliveCandidates.filter { it.isSuitableForSticky(minStage) }
        val best = verifiedCandidates.firstOrNull()
        if (best != null) {
            setStickyProfile(best)
            recordStickySuccess()
            AppLogger.i(TAG, "Лучший эндпоинт найден и зафиксирован (Sticky): ${best.endpoint} (${best.probeProtocol}, RTT=${best.rttMs}мс, stage=${best.stage})")
        } else {
            AppLogger.w(TAG, "Подходящих Anycast-портов WARP ступени $minStage не обнаружено в выборке из ${candidates.size} кандидатов ($protocol)")
        }
        best
    }

    /**
     * Сквозная проверка через локальный SOCKS5 прокси до целевого Telegram DC (RFC 1928).
     */
    fun verifyThroughLocalSocks(
        socksPort: Int,
        auth: Pair<String, String>? = null,
        targetIp: String = "149.154.167.50",
        targetPort: Int = 443,
        timeoutMs: Int = 2500
    ): NodeHealthProber.ProbeMetric = NodeHealthProber.probeTelegramViaLocalSocks(socksPort, auth, targetIp, targetPort, timeoutMs)

    /**
     * Парсит эндпоинт вида "1.2.3.4:500" или "[2606:4700::1]:500" в пару (IP, Port).
     */
    fun parseEndpoint(endpoint: String): Pair<String, Int> {
        val trimmed = endpoint.trim()
        return try {
            if (trimmed.startsWith("[")) {
                val closeBracket = trimmed.indexOf(']')
                val ip = trimmed.substring(1, closeBracket)
                val port = trimmed.substring(closeBracket + 2).toInt()
                Pair(ip, port)
            } else {
                val lastColon = trimmed.lastIndexOf(':')
                if (lastColon > 0) {
                    val ip = trimmed.substring(0, lastColon)
                    val port = trimmed.substring(lastColon + 1).toInt()
                    Pair(ip, port)
                } else {
                    Pair(trimmed, 500)
                }
            }
        } catch (_: Exception) {
            Pair("", 0)
        }
    }
}
