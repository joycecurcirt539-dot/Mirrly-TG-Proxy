/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service.vpn

import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.DohResolver
import com.mirrly.tgproxy.core.VpnSocketProtector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Пользовательский L3/L4 сетевой стек для интерфейса TUN (Tasks N06, N07, N10, N11, N17, N18).
 * - Обработка ICMP Echo (ping) и Path MTU Discovery (Task N18)
 * - Перехват и резолв DNS через DoH с кэшем и защитой от утечек (Task N10)
 * - L4 TCP Tun-to-SOCKS5 адаптер с полной TCP стейт-машиной и буферизацией Early-Data (Task N07)
 * - UDP NAT сессии и RFC 1928 SOCKS5 UDP ASSOCIATE релей (Task N11)
 * - Бюджетирование ресурсов: максимум 1024 TCP потоков, 512 UDP сессий (Task N17)
 */
class TunPacketEngine(
    private val tunHolder: TunHolder,
    private val socks5Host: String = "127.0.0.1",
    private val socks5Port: Int = 10808,
    private val socks5Username: String = "",
    private val socks5Password: String = "",
    private val dnsCache: VpnDnsCache = VpnDnsCache(),
    private val networkGenerationProvider: () -> Long = { 1L },
    private val isUplinkAliveProvider: () -> Boolean = { true },
    val vpnMtu: Int = 1420,
    val vpnBlockQuic: Boolean = true,
    val vpnBlockIpv6Leaks: Boolean = true
) {
    companion object {
        private const val TAG = "TunPacketEngine"
        const val MAX_ACTIVE_TCP_FLOWS = 1024
        const val MAX_ACTIVE_UDP_SESSIONS = 512
        const val TCP_CONNECT_TIMEOUT_MS = 10000
        const val UDP_IDLE_TIMEOUT_MS = 60000L
        const val DNS_TIMEOUT_MS = 5000L
        const val BUFFER_SIZE = 32767
    }

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    // Метрики передачи данных
    val totalBytesIn = AtomicLong(0L)
    val totalBytesOut = AtomicLong(0L)
    val activeTcpFlowCount = AtomicInteger(0)
    val activeUdpSessionCount = AtomicInteger(0)

    // TCP State Machine
    private data class TcpSession(
        val clientIp: ByteArray,
        val clientPort: Int,
        val targetIp: ByteArray,
        val targetPort: Int,
        var clientSeq: Long = 0L,
        var clientAck: Long = 0L,
        var serverSeq: Long = 0L,
        var serverAck: Long = 0L,
        var isEstablished: Boolean = false,
        var socksSocket: Socket? = null,
        var socksIn: InputStream? = null,
        var socksOut: OutputStream? = null,
        var readerJob: Job? = null,
        val pendingTxQueue: ConcurrentLinkedQueue<ByteArray> = ConcurrentLinkedQueue(),
        val createdAt: Long = System.currentTimeMillis()
    )

    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()

    // UDP NAT Table & RFC 1928 SOCKS5 UDP Relay
    private data class UdpNatEntry(
        val clientIp: ByteArray,
        val clientPort: Int,
        val targetIp: ByteArray,
        val targetPort: Int,
        val socket: DatagramSocket,
        var lastActivityMs: Long = System.currentTimeMillis(),
        var readerJob: Job? = null
    )

    private val udpSessions = ConcurrentHashMap<String, UdpNatEntry>()
    private val udpClientLookup = ConcurrentHashMap<String, Pair<ByteArray, Int>>()
    private val socks5Relay = Socks5UdpRelay()

    // Synthetic IP (Fake-IP RFC 2544 / RFC 5735: 198.18.0.0/15) для обхода цензуры DNS ТСПУ в РФ
    private val domainToSyntheticIp = ConcurrentHashMap<String, String>()
    private val syntheticIpToDomain = ConcurrentHashMap<String, String>()
    private val syntheticIpCounter = AtomicInteger(1)

    fun getOrAllocateSyntheticIp(domain: String): String {
        val cleanDomain = domain.trim().lowercase().trimEnd('.')
        return domainToSyntheticIp.computeIfAbsent(cleanDomain) {
            val id = (syntheticIpCounter.getAndIncrement() and 0x0001FFFF) + 1
            val octet3 = (id shr 8) and 0xFF
            val octet4 = id and 0xFF
            val ip = "198.18.$octet3.$octet4"
            syntheticIpToDomain[ip] = cleanDomain
            ip
        }
    }

    fun getDomainForIp(ipStr: String): String? {
        return syntheticIpToDomain[ipStr]
    }

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            AppLogger.i(TAG, "Запуск userspace пакетного конвейера TUN")
            readJob = engineScope.launch {
                val inStream = tunHolder.inStream
                val buffer = ByteArray(BUFFER_SIZE)

                while (isActive && isRunning.get() && !tunHolder.isClosed()) {
                    val length = try {
                        inStream.read(buffer)
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            AppLogger.w(TAG, "Ошибка чтения из TUN: ${e.message}")
                        }
                        break
                    }
                    if (length <= 0) continue

                    totalBytesIn.addAndGet(length.toLong())
                    processPacket(buffer, length)
                }
            }
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            AppLogger.i(TAG, "Остановка userspace пакетного конвейера TUN")
            readJob?.cancel()
            engineScope.cancel()

            // Закрытие всех TCP сессий
            for ((_, session) in tcpSessions) {
                try {
                    session.readerJob?.cancel()
                    session.socksSocket?.close()
                    session.pendingTxQueue.clear()
                } catch (_: Exception) {}
            }
            tcpSessions.clear()
            activeTcpFlowCount.set(0)

            // Закрытие всех UDP сессий и SOCKS5 UDP релея
            socks5Relay.close()
            udpClientLookup.clear()
            for ((_, session) in udpSessions) {
                try {
                    session.readerJob?.cancel()
                    session.socket.close()
                } catch (_: Exception) {}
            }
            udpSessions.clear()
            activeUdpSessionCount.set(0)
            domainToSyntheticIp.clear()
            syntheticIpToDomain.clear()
            syntheticIpCounter.set(1)
        }
    }

    private fun processPacket(buf: ByteArray, length: Int) {
        if (length < 20) return
        val version = (buf[0].toInt() ushr 4) and 0x0F

        if (version == 4) {
            processIpv4(buf, length)
        } else if (version == 6 && length >= 40) {
            processIpv6(buf, length)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // IPv4 Processing
    // ──────────────────────────────────────────────────────────────────────────
    private fun processIpv4(buf: ByteArray, length: Int) {
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || ihl > length) return

        val totalLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        if (totalLen < ihl || totalLen > length) return

        val protocol = buf[9].toInt() and 0xFF
        val srcIp = buf.copyOfRange(12, 16)
        val dstIp = buf.copyOfRange(16, 20)

        // Защита от сетевых петель и перехвата loopback трафика (127.0.0.0/8).
        // Локальные прокси Mirrly (MTProto :1080, SOCKS5 :10808) и системный стек lo
        // не должны захватываться внутри виртуального TUN интерфейса.
        if (dstIp[0] == 127.toByte() || (dstIp[0] == 10.toByte() && dstIp[1] == 233.toByte() && dstIp[2] == 233.toByte())) {
            return
        }

        // Kill switch: если аплинк временно недоступен, не пропускаем пользовательский трафик
        if (!isUplinkAliveProvider() && protocol != 1) {
            return
        }

        when (protocol) {
            1 -> handleIcmp(buf, ihl, totalLen)
            6 -> handleTcp(buf, ihl, totalLen, srcIp, dstIp)
            17 -> handleUdp(buf, ihl, totalLen, srcIp, dstIp)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // IPv6 Processing (Task N09, N18)
    // ──────────────────────────────────────────────────────────────────────────
    private fun processIpv6(buf: ByteArray, length: Int) {
        val payloadLen = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        val nextHeader = buf[6].toInt() and 0xFF
        if (40 + payloadLen > length) return

        // Защита от утечек IPv6: отбрасываем весь не-ICMPv6 трафик при включенной защите
        if (vpnBlockIpv6Leaks && nextHeader != 58) {
            return
        }

        // ICMPv6 Echo Request (type 128) -> Echo Reply (type 129)
        if (nextHeader == 58 && payloadLen >= 8) {
            val type = buf[40].toInt() and 0xFF
            if (type == 128) {
                val reply = buf.copyOfRange(0, 40 + payloadLen)
                reply[40] = 129.toByte() // Echo Reply
                // Swap IPv6 src and dst
                for (i in 0 until 16) {
                    val tmp = reply[8 + i]
                    reply[8 + i] = reply[24 + i]
                    reply[24 + i] = tmp
                }
                reply[42] = 0.toByte()
                reply[43] = 0.toByte()
                val ck = computeIpv6Checksum(reply, 8, 24, 58, payloadLen, 40)
                reply[42] = ((ck ushr 8) and 0xFF).toByte()
                reply[43] = (ck and 0xFF).toByte()
                writeToTun(reply)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ICMP Echo Reply (Task N18)
    // ──────────────────────────────────────────────────────────────────────────
    private fun handleIcmp(buf: ByteArray, ihl: Int, totalLen: Int) {
        if (totalLen < ihl + 8) return
        val icmpType = buf[ihl].toInt() and 0xFF
        if (icmpType == 8) { // Echo Request
            val reply = buf.copyOfRange(0, totalLen)
            reply[ihl] = 0.toByte() // Echo Reply type
            reply[ihl + 1] = 0.toByte()
            reply[ihl + 2] = 0.toByte()
            reply[ihl + 3] = 0.toByte()

            // Меняем местами src IP и dst IP
            for (i in 0 until 4) {
                val tmp = reply[12 + i]
                reply[12 + i] = reply[16 + i]
                reply[16 + i] = tmp
            }

            val icmpLen = totalLen - ihl
            val icmpCk = computeChecksum(reply, ihl, icmpLen)
            reply[ihl + 2] = ((icmpCk ushr 8) and 0xFF).toByte()
            reply[ihl + 3] = (icmpCk and 0xFF).toByte()

            reply[10] = 0.toByte()
            reply[11] = 0.toByte()
            val ipCk = computeChecksum(reply, 0, ihl)
            reply[10] = ((ipCk ushr 8) and 0xFF).toByte()
            reply[11] = (ipCk and 0xFF).toByte()

            writeToTun(reply)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UDP Dispatcher: Port 53 (DNS) vs Other (NAT) (Tasks N10, N11)
    // ──────────────────────────────────────────────────────────────────────────
    private fun handleUdp(buf: ByteArray, ihl: Int, totalLen: Int, srcIp: ByteArray, dstIp: ByteArray) {
        if (totalLen < ihl + 8) return
        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        val udpLen = ((buf[ihl + 4].toInt() and 0xFF) shl 8) or (buf[ihl + 5].toInt() and 0xFF)

        if (udpLen < 8 || ihl + udpLen > totalLen) return
        val payloadLen = udpLen - 8
        val payload = buf.copyOfRange(ihl + 8, ihl + 8 + payloadLen)

        if (dstPort == 53) {
            handleDnsQuery(srcIp, srcPort, dstIp, dstPort, payload)
        } else if (vpnBlockQuic && dstPort == 443) {
            // Блокировка QUIC (UDP 443) для мгновенного ускорения YouTube и сервисов Google в РФ.
            // При сбросе UDP 443 клиенты мгновенно переключаются на высокоскоростной TCP TLS 1.3 через WARP.
            return
        } else {
            handleGenericUdp(srcIp, srcPort, dstIp, dstPort, payload)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // DNS Interception via Encrypted DoH (Task N10)
    // ──────────────────────────────────────────────────────────────────────────
    private fun handleDnsQuery(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        payload: ByteArray
    ) {
        if (payload.size < 12) return
        val txId = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val qdCount = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        if (qdCount < 1) return

        // Парсинг доменного имени из DNS Question
        var offset = 12
        val sb = StringBuilder()
        while (offset < payload.size) {
            val labelLen = payload[offset].toInt() and 0xFF
            offset++
            if (labelLen == 0) break
            if (offset + labelLen > payload.size) return
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(payload, offset, labelLen, Charsets.US_ASCII))
            offset += labelLen
        }
        val domain = sb.toString()
        if (offset + 4 > payload.size) return
        val qType = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)

        // Проверяем кэш
        val currentGen = networkGenerationProvider()
        val cached = dnsCache.get(qType, domain, currentGen)
        if (cached != null) {
            val replyBytes = buildDnsResponse(txId, payload, cached.addresses, cached.isNxDomain)
            sendUdpResponse(dstIp, dstPort, srcIp, srcPort, replyBytes)
            return
        }

        // Асинхронный резолв через DohResolver
        engineScope.launch {
            try {
                val allAddresses = DohResolver.resolve(domain)
                var addresses = when (qType) {
                    1 -> allAddresses.filter { it.address.size == 4 }
                    28 -> allAddresses.filter { it.address.size == 16 }
                    else -> allAddresses
                }

                // Анти-блокировка для РФ: если DoH заблокирован ТСПУ (пустой ответ),
                // но аплинк активен, выделяем синтетический IP (Fake-IP RFC 2544)
                // для немедленного туннелирования через SOCKS5 CONNECT (ATYP 0x03)
                if (addresses.isEmpty() && isUplinkAliveProvider() && qType == 1) {
                    val fakeIp = getOrAllocateSyntheticIp(domain)
                    try {
                        addresses = listOf(InetAddress.getByName(fakeIp))
                        AppLogger.i(TAG, "DNS Fallback: выделен Synthetic IP $fakeIp для '$domain' (обход блокировок DNS ТСПУ)")
                    } catch (_: Exception) {}
                }

                val isNx = addresses.isEmpty()
                dnsCache.put(qType, domain, addresses, ttlSeconds = if (isNx) 5L else 60L, currentNetworkGeneration = currentGen, isNxDomain = isNx)
                val replyBytes = buildDnsResponse(txId, payload, addresses, isNxDomain = isNx)
                sendUdpResponse(dstIp, dstPort, srcIp, srcPort, replyBytes)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Сбой DNS резолва для $domain: ${e.message}")
            }
        }
    }

    private fun buildDnsResponse(
        txId: Int,
        queryPayload: ByteArray,
        addresses: List<InetAddress>,
        isNxDomain: Boolean
    ): ByteArray {
        val qEnd = findQuestionEnd(queryPayload)
        val questionSection = queryPayload.copyOfRange(0, qEnd)

        val bb = ByteBuffer.allocate(1024)
        // TxID
        bb.putShort(txId.toShort())
        // Flags: Standard query response, No error (0x8180) or NXDOMAIN (0x8183)
        val flags = if (isNxDomain) 0x8183 else 0x8180
        bb.putShort(flags.toShort())
        // QDCOUNT: 1
        bb.putShort(1.toShort())
        // ANCOUNT: addresses.size
        bb.putShort(if (isNxDomain) 0.toShort() else addresses.size.toShort())
        // NSCOUNT: 0, ARCOUNT: 0
        bb.putShort(0.toShort())
        bb.putShort(0.toShort())

        // Question section (без заголовка)
        if (questionSection.size > 12) {
            bb.put(questionSection, 12, questionSection.size - 12)
        }

        if (!isNxDomain) {
            for (addr in addresses) {
                // Name pointer to question domain (0xC00C)
                bb.putShort(0xC00C.toShort())
                val raw = addr.address
                if (raw.size == 4) {
                    bb.putShort(1.toShort()) // Type A
                    bb.putShort(1.toShort()) // Class IN
                    bb.putInt(60) // TTL 60s
                    bb.putShort(4.toShort()) // Data length
                    bb.put(raw)
                } else if (raw.size == 16) {
                    bb.putShort(28.toShort()) // Type AAAA
                    bb.putShort(1.toShort()) // Class IN
                    bb.putInt(60) // TTL 60s
                    bb.putShort(16.toShort()) // Data length
                    bb.put(raw)
                }
            }
        }

        return bb.array().copyOfRange(0, bb.position())
    }

    private fun findQuestionEnd(payload: ByteArray): Int {
        var offset = 12
        while (offset < payload.size) {
            val len = payload[offset].toInt() and 0xFF
            offset++
            if (len == 0) break
            offset += len
        }
        return (offset + 4).coerceAtMost(payload.size)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Generic UDP NAT Sessions & SOCKS5 UDP ASSOCIATE Relay (Task N11, RFC 1928)
    // ──────────────────────────────────────────────────────────────────────────
    private inner class Socks5UdpRelay {
        var tcpControlSocket: Socket? = null
        var udpSocket: DatagramSocket? = null
        var relayAddress: InetAddress? = null
        var relayPort: Int = 0
        val isReady = AtomicBoolean(false)
        val isConnecting = AtomicBoolean(false)
        var readerJob: Job? = null

        fun ensureReady() {
            if (isReady.get() || isConnecting.get()) return
            if (!isConnecting.compareAndSet(false, true)) return

            engineScope.launch {
                try {
                    val tcp = Socket()
                    VpnSocketProtector.protect(tcp)
                    tcp.connect(InetSocketAddress(socks5Host, socks5Port), TCP_CONNECT_TIMEOUT_MS)
                    tcp.tcpNoDelay = true

                    val out = tcp.getOutputStream()
                    val `in` = tcp.getInputStream()

                    // SOCKS5 Auth Handshake
                    if (socks5Username.isNotBlank()) {
                        out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
                    } else {
                        out.write(byteArrayOf(0x05, 0x01, 0x00))
                    }
                    out.flush()

                    val authResp = ByteArray(2)
                    val readAuth = `in`.read(authResp)
                    if (readAuth < 2 || authResp[0] != 0x05.toByte()) {
                        tcp.close()
                        isConnecting.set(false)
                        return@launch
                    }

                    if (authResp[1] == 0x02.toByte()) {
                        val uBytes = socks5Username.toByteArray(Charsets.US_ASCII)
                        val pBytes = socks5Password.toByteArray(Charsets.US_ASCII)
                        val authPayload = ByteArray(1 + 1 + uBytes.size + 1 + pBytes.size)
                        authPayload[0] = 0x01
                        authPayload[1] = uBytes.size.toByte()
                        System.arraycopy(uBytes, 0, authPayload, 2, uBytes.size)
                        val pOffset = 2 + uBytes.size
                        authPayload[pOffset] = pBytes.size.toByte()
                        System.arraycopy(pBytes, 0, authPayload, pOffset + 1, pBytes.size)

                        out.write(authPayload)
                        out.flush()

                        val subResp = ByteArray(2)
                        val readSub = `in`.read(subResp)
                        if (readSub < 2 || subResp[1] != 0x00.toByte()) {
                            tcp.close()
                            isConnecting.set(false)
                            return@launch
                        }
                    } else if (authResp[1] != 0x00.toByte()) {
                        tcp.close()
                        isConnecting.set(false)
                        return@launch
                    }

                    // SOCKS5 UDP ASSOCIATE request: CMD 0x03, ATYP 0x01, 0.0.0.0:0
                    out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    out.flush()

                    val respHead = ByteArray(4)
                    val readHead = `in`.read(respHead)
                    if (readHead < 4 || respHead[1] != 0x00.toByte()) {
                        tcp.close()
                        isConnecting.set(false)
                        return@launch
                    }

                    val (bndAddr, bndPort) = when (respHead[3].toInt() and 0xFF) {
                        0x01 -> {
                            val ipBytes = ByteArray(4)
                            `in`.read(ipBytes)
                            val pBytes = ByteArray(2)
                            `in`.read(pBytes)
                            val p = ((pBytes[0].toInt() and 0xFF) shl 8) or (pBytes[1].toInt() and 0xFF)
                            val ip = if (ipBytes.all { it == 0.toByte() }) {
                                InetAddress.getByName(socks5Host)
                            } else {
                                InetAddress.getByAddress(ipBytes)
                            }
                            Pair(ip, p)
                        }
                        0x04 -> {
                            val ipBytes = ByteArray(16)
                            `in`.read(ipBytes)
                            val pBytes = ByteArray(2)
                            `in`.read(pBytes)
                            val p = ((pBytes[0].toInt() and 0xFF) shl 8) or (pBytes[1].toInt() and 0xFF)
                            Pair(InetAddress.getByAddress(ipBytes), p)
                        }
                        0x03 -> {
                            val len = `in`.read()
                            val hostBytes = ByteArray(len)
                            `in`.read(hostBytes)
                            val pBytes = ByteArray(2)
                            `in`.read(pBytes)
                            val p = ((pBytes[0].toInt() and 0xFF) shl 8) or (pBytes[1].toInt() and 0xFF)
                            Pair(InetAddress.getByName(String(hostBytes, Charsets.US_ASCII)), p)
                        }
                        else -> {
                            tcp.close()
                            isConnecting.set(false)
                            return@launch
                        }
                    }

                    val udp = DatagramSocket()
                    VpnSocketProtector.protect(udp)

                    tcpControlSocket = tcp
                    udpSocket = udp
                    relayAddress = bndAddr
                    relayPort = bndPort
                    isReady.set(true)
                    isConnecting.set(false)
                    AppLogger.i(TAG, "SOCKS5 UDP ASSOCIATE установлен на $bndAddr:$bndPort")

                    // Чтение ответных UDP дейтаграмм от SOCKS5 relay
                    readerJob = engineScope.launch {
                        val recvBuf = ByteArray(65535)
                        while (isActive && isRunning.get() && isReady.get() && !udp.isClosed) {
                            try {
                                val packet = DatagramPacket(recvBuf, recvBuf.size)
                                udp.receive(packet)
                                if (packet.length < 10) continue

                                // Разбор RFC 1928 UDP заголовка: RSV(2B) + FRAG(1B) + ATYP(1B)
                                val atyp = packet.data[3].toInt() and 0xFF
                                val (srcIp, srcPort, dataOffset) = when (atyp) {
                                    0x01 -> { // IPv4
                                        val ip = packet.data.copyOfRange(4, 8)
                                        val port = ((packet.data[8].toInt() and 0xFF) shl 8) or (packet.data[9].toInt() and 0xFF)
                                        Triple(ip, port, 10)
                                    }
                                    0x04 -> { // IPv6
                                        val ip = packet.data.copyOfRange(4, 20)
                                        val port = ((packet.data[20].toInt() and 0xFF) shl 8) or (packet.data[21].toInt() and 0xFF)
                                        Triple(ip, port, 22)
                                    }
                                    else -> continue
                                }

                                val payload = packet.data.copyOfRange(dataOffset, packet.length)
                                val origDstKey = "${ipToString(srcIp)}:$srcPort"
                                val clientEntry = udpClientLookup[origDstKey]
                                if (clientEntry != null) {
                                    sendUdpResponse(srcIp, srcPort, clientEntry.first, clientEntry.second, payload)
                                }
                            } catch (_: Exception) {
                                break
                            }
                        }
                        close()
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Ошибка инициализации SOCKS5 UDP ASSOCIATE: ${e.message}")
                    isConnecting.set(false)
                    close()
                }
            }
        }

        fun sendRelayPacket(targetIp: ByteArray, targetPort: Int, payload: ByteArray): Boolean {
            val udp = udpSocket ?: return false
            val rAddr = relayAddress ?: return false
            if (!isReady.get()) return false

            return try {
                val headerLen = 4 + targetIp.size + 2
                val fullPacket = ByteArray(headerLen + payload.size)
                fullPacket[0] = 0x00 // RSV
                fullPacket[1] = 0x00 // RSV
                fullPacket[2] = 0x00 // FRAG
                fullPacket[3] = if (targetIp.size == 16) 0x04.toByte() else 0x01.toByte()
                System.arraycopy(targetIp, 0, fullPacket, 4, targetIp.size)
                val portOffset = 4 + targetIp.size
                fullPacket[portOffset] = ((targetPort ushr 8) and 0xFF).toByte()
                fullPacket[portOffset + 1] = (targetPort and 0xFF).toByte()
                System.arraycopy(payload, 0, fullPacket, headerLen, payload.size)

                val datagram = DatagramPacket(fullPacket, fullPacket.size, rAddr, relayPort)
                udp.send(datagram)
                totalBytesOut.addAndGet(payload.size.toLong())
                true
            } catch (_: Exception) {
                false
            }
        }

        fun close() {
            isReady.set(false)
            readerJob?.cancel()
            try { tcpControlSocket?.close() } catch (_: Exception) {}
            try { udpSocket?.close() } catch (_: Exception) {}
            tcpControlSocket = null
            udpSocket = null
        }
    }

    private fun handleGenericUdp(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        payload: ByteArray
    ) {
        val dstKey = "${ipToString(dstIp)}:$dstPort"
        udpClientLookup[dstKey] = Pair(srcIp, srcPort)

        // 1. Попытка туннелирования через SOCKS5 UDP ASSOCIATE (Cloudflare WARP / AWG / VLESS)
        socks5Relay.ensureReady()
        if (socks5Relay.isReady.get()) {
            if (socks5Relay.sendRelayPacket(dstIp, dstPort, payload)) {
                return
            }
        }

        // 2. Fallback: прямой защищенный сокет
        val key = "${ipToString(srcIp)}:$srcPort->${ipToString(dstIp)}:$dstPort"
        val existing = udpSessions[key]
        if (existing != null) {
            existing.lastActivityMs = System.currentTimeMillis()
            sendUdpDatagram(existing.socket, dstIp, dstPort, payload)
            return
        }

        // Проверка лимита сессий (Task N17)
        if (activeUdpSessionCount.get() >= MAX_ACTIVE_UDP_SESSIONS) {
            cleanupExpiredUdpSessions()
            if (activeUdpSessionCount.get() >= MAX_ACTIVE_UDP_SESSIONS) {
                return // дроп при перегрузке
            }
        }

        try {
            val udpSocket = DatagramSocket()
            VpnSocketProtector.protect(udpSocket) // Защита внешнего сокета (Task N04)

            val session = UdpNatEntry(
                clientIp = srcIp,
                clientPort = srcPort,
                targetIp = dstIp,
                targetPort = dstPort,
                socket = udpSocket
            )

            // Чтение ответов от целевого сервера
            session.readerJob = engineScope.launch {
                val recvBuf = ByteArray(4096)
                while (isActive && isRunning.get() && !udpSocket.isClosed) {
                    try {
                        val packet = DatagramPacket(recvBuf, recvBuf.size)
                        udpSocket.receive(packet)
                        val respData = packet.data.copyOfRange(0, packet.length)
                        sendUdpResponse(dstIp, dstPort, srcIp, srcPort, respData)
                    } catch (_: Exception) {
                        break
                    }
                }
            }

            udpSessions[key] = session
            activeUdpSessionCount.incrementAndGet()
            sendUdpDatagram(udpSocket, dstIp, dstPort, payload)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка открытия UDP NAT сокета: ${e.message}")
        }
    }

    private fun sendUdpDatagram(socket: DatagramSocket, targetIp: ByteArray, targetPort: Int, data: ByteArray) {
        engineScope.launch {
            try {
                val targetAddr = InetAddress.getByAddress(targetIp)
                val packet = DatagramPacket(data, data.size, targetAddr, targetPort)
                socket.send(packet)
                totalBytesOut.addAndGet(data.size.toLong())
            } catch (_: Exception) {}
        }
    }

    private fun cleanupExpiredUdpSessions() {
        val now = System.currentTimeMillis()
        val it = udpSessions.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.lastActivityMs > UDP_IDLE_TIMEOUT_MS) {
                try {
                    entry.value.readerJob?.cancel()
                    entry.value.socket.close()
                } catch (_: Exception) {}
                it.remove()
                activeUdpSessionCount.decrementAndGet()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TCP Tun-to-SOCKS5 Flow Adapter (Task N07, N17)
    // ──────────────────────────────────────────────────────────────────────────
    private fun handleTcp(
        buf: ByteArray,
        ihl: Int,
        totalLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ) {
        if (totalLen < ihl + 20) return
        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)

        // Защита от петель: не перехватываем loopback и обращения к портам локального прокси
        if (dstIp[0] == 127.toByte() || dstPort == socks5Port || dstPort == 1080) {
            return
        }

        val seq = ((buf[ihl + 4].toLong() and 0xFF) shl 24) or
            ((buf[ihl + 5].toLong() and 0xFF) shl 16) or
            ((buf[ihl + 6].toLong() and 0xFF) shl 8) or
            (buf[ihl + 7].toLong() and 0xFF)

        val ack = ((buf[ihl + 8].toLong() and 0xFF) shl 24) or
            ((buf[ihl + 9].toLong() and 0xFF) shl 16) or
            ((buf[ihl + 10].toLong() and 0xFF) shl 8) or
            (buf[ihl + 11].toLong() and 0xFF)

        val dataOffset = ((buf[ihl + 12].toInt() ushr 4) and 0x0F) * 4
        val flags = buf[ihl + 13].toInt() and 0xFF

        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        val payloadOffset = ihl + dataOffset
        val payloadLen = (totalLen - payloadOffset).coerceAtLeast(0)
        val key = "${ipToString(srcIp)}:$srcPort->${ipToString(dstIp)}:$dstPort"

        if (isRst) {
            closeTcpSession(key)
            return
        }

        val session = tcpSessions[key]

        // 1. Обработка SYN (начало соединения)
        if (isSyn && session == null) {
            if (dstPort == 853) {
                // Android Private DNS (DoT) на порту 853. В РФ порт 853 массово блокируется ТСПУ РКН.
                // Немедленный ответ TCP RST заставляет Android мгновенно переключиться на стандартный DNS (порт 53 UDP),
                // который перехватывается нашим зашифрованным DoH-резолвером без задержек и таймаутов (RFC 7858).
                sendTcpPacket(dstIp, dstPort, srcIp, srcPort, seq = 0, ack = seq + 1, flags = 0x14) // RST+ACK
                return
            }

            if (activeTcpFlowCount.get() >= MAX_ACTIVE_TCP_FLOWS) {
                // Превышен лимит одновременных потоков: отсылаем TCP RST (Task N17)
                sendTcpPacket(dstIp, dstPort, srcIp, srcPort, seq = 0, ack = seq + 1, flags = 0x14) // RST+ACK
                return
            }

            val serverIsn = Random.nextLong(100000L, 2000000000L)
            val newSession = TcpSession(
                clientIp = srcIp,
                clientPort = srcPort,
                targetIp = dstIp,
                targetPort = dstPort,
                clientSeq = seq + 1,
                serverSeq = serverIsn
            )
            tcpSessions[key] = newSession
            activeTcpFlowCount.incrementAndGet()

            // Отвечаем клиенту SYN-ACK
            sendTcpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                seq = serverIsn,
                ack = seq + 1,
                flags = 0x12 // SYN+ACK
            )

            // Асинхронно подключаемся к локальному SOCKS5 прокси
            connectSocks5(key, newSession, dstIp, dstPort)
            return
        }

        if (session == null) {
            // Пакет без активной сессии: отправляем RST
            if (!isRst) {
                sendTcpPacket(dstIp, dstPort, srcIp, srcPort, seq = ack, ack = seq + 1, flags = 0x14)
            }
            return
        }

        // 2. Обработка ACK завершения хэндшейка
        if (isAck && !session.isEstablished && !isSyn) {
            session.isEstablished = true
            session.serverSeq += 1
        }

        // 3. Обработка полезной нагрузки TCP (PSH/ACK)
        if (payloadLen > 0) {
            session.clientSeq = seq + payloadLen
            val payload = buf.copyOfRange(payloadOffset, payloadOffset + payloadLen)

            // Подтверждаем получение клиенту (ACK)
            sendTcpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                seq = session.serverSeq,
                ack = session.clientSeq,
                flags = 0x10 // ACK
            )

            // Запись в SOCKS5 сокет
            val out = session.socksOut
            if (out != null) {
                engineScope.launch {
                    try {
                        synchronized(out) {
                            out.write(payload)
                            out.flush()
                        }
                        totalBytesOut.addAndGet(payload.size.toLong())
                    } catch (e: Exception) {
                        closeTcpSession(key)
                    }
                }
            } else {
                // Ранние данные до завершения SOCKS5 handshake (Early-Data) буферизируются
                session.pendingTxQueue.add(payload)
            }
        }

        // 4. Обработка закрытия (FIN)
        if (isFin) {
            session.clientSeq = seq + 1
            // Отправляем FIN-ACK клиенту
            sendTcpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                seq = session.serverSeq,
                ack = session.clientSeq,
                flags = 0x11 // FIN+ACK
            )
            closeTcpSession(key)
        }
    }

    private fun connectSocks5(key: String, session: TcpSession, targetIp: ByteArray, targetPort: Int) {
        engineScope.launch {
            try {
                val socket = Socket()
                VpnSocketProtector.protect(socket) // Защита сокета от попадания в собственный TUN
                socket.connect(InetSocketAddress(socks5Host, socks5Port), TCP_CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true

                val out = socket.getOutputStream()
                val `in` = socket.getInputStream()

                // SOCKS5 Handshake: RFC 1928 / RFC 1929 Auth
                val hasAuth = socks5Username.isNotBlank()
                if (hasAuth) {
                    out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02)) // NO AUTH or USER/PASS
                } else {
                    out.write(byteArrayOf(0x05, 0x01, 0x00)) // NO AUTH
                }
                out.flush()

                val authResp = ByteArray(2)
                val readAuth = `in`.read(authResp)
                if (readAuth < 2 || authResp[0] != 0x05.toByte()) {
                    sendTcpRst(session)
                    closeTcpSession(key)
                    return@launch
                }

                if (authResp[1] == 0x02.toByte()) {
                    // RFC 1929 Subnegotiation
                    val uBytes = socks5Username.toByteArray(Charsets.US_ASCII)
                    val pBytes = socks5Password.toByteArray(Charsets.US_ASCII)
                    val authPayload = ByteArray(1 + 1 + uBytes.size + 1 + pBytes.size)
                    authPayload[0] = 0x01
                    authPayload[1] = uBytes.size.toByte()
                    System.arraycopy(uBytes, 0, authPayload, 2, uBytes.size)
                    val pOffset = 2 + uBytes.size
                    authPayload[pOffset] = pBytes.size.toByte()
                    System.arraycopy(pBytes, 0, authPayload, pOffset + 1, pBytes.size)

                    out.write(authPayload)
                    out.flush()

                    val subResp = ByteArray(2)
                    val readSub = `in`.read(subResp)
                    if (readSub < 2 || subResp[1] != 0x00.toByte()) {
                        sendTcpRst(session)
                        closeTcpSession(key)
                        return@launch
                    }
                } else if (authResp[1] != 0x00.toByte()) {
                    sendTcpRst(session)
                    closeTcpSession(key)
                    return@launch
                }

                // SOCKS5 CONNECT (ATYP 0x03 Domain Name или 0x01 IPv4 / 0x04 IPv6)
                val targetIpStr = ipToString(targetIp)
                val resolvedDomain = getDomainForIp(targetIpStr)

                val connectReq: ByteArray
                if (resolvedDomain != null && resolvedDomain.isNotBlank()) {
                    // ATYP = 0x03 (Domain Name) - домен разрешается на удаленном прокси узле в обход ТСПУ
                    val dBytes = resolvedDomain.toByteArray(Charsets.US_ASCII)
                    connectReq = ByteArray(4 + 1 + dBytes.size + 2)
                    connectReq[0] = 0x05
                    connectReq[1] = 0x01 // CMD CONNECT
                    connectReq[2] = 0x00 // RSV
                    connectReq[3] = 0x03 // ATYP 0x03 = DOMAINNAME
                    connectReq[4] = dBytes.size.toByte()
                    System.arraycopy(dBytes, 0, connectReq, 5, dBytes.size)
                    connectReq[5 + dBytes.size] = ((targetPort ushr 8) and 0xFF).toByte()
                    connectReq[5 + dBytes.size + 1] = (targetPort and 0xFF).toByte()
                    AppLogger.d(TAG, "SOCKS5 CONNECT через ATYP 0x03 (Domain): $resolvedDomain:$targetPort")
                } else {
                    val atyp = if (targetIp.size == 16) 0x04.toByte() else 0x01.toByte()
                    val addrLen = if (targetIp.size == 16) 16 else 4
                    connectReq = ByteArray(4 + addrLen + 2)
                    connectReq[0] = 0x05
                    connectReq[1] = 0x01 // CMD CONNECT
                    connectReq[2] = 0x00 // RSV
                    connectReq[3] = atyp
                    System.arraycopy(targetIp, 0, connectReq, 4, addrLen)
                    connectReq[4 + addrLen] = ((targetPort ushr 8) and 0xFF).toByte()
                    connectReq[4 + addrLen + 1] = (targetPort and 0xFF).toByte()
                }

                out.write(connectReq)
                out.flush()

                val connHead = ByteArray(4)
                val headRead = `in`.read(connHead)
                if (headRead < 4 || connHead[1] != 0x00.toByte()) {
                    sendTcpRst(session)
                    closeTcpSession(key)
                    return@launch
                }

                // Читаем оставшийся BND.ADDR и BND.PORT
                val bndAddrLen = when (connHead[3].toInt() and 0xFF) {
                    0x01 -> 4 + 2
                    0x04 -> 16 + 2
                    0x03 -> {
                        val dLen = `in`.read()
                        if (dLen < 0) {
                            sendTcpRst(session)
                            closeTcpSession(key)
                            return@launch
                        }
                        dLen + 2
                    }
                    else -> 4 + 2
                }
                val discardBuf = ByteArray(bndAddrLen)
                `in`.read(discardBuf)

                session.socksSocket = socket
                session.socksIn = `in`
                session.socksOut = out

                // Сбрасываем все накопившиеся ранние данные (Early-Data)
                synchronized(out) {
                    while (session.pendingTxQueue.isNotEmpty()) {
                        val pending = session.pendingTxQueue.poll() ?: break
                        out.write(pending)
                        totalBytesOut.addAndGet(pending.size.toLong())
                    }
                    out.flush()
                }

                // Фоновый поток чтения ответов от удаленного SOCKS сервера с сегментацией под MTU
                session.readerJob = engineScope.launch {
                    val maxSegmentSize = (vpnMtu - 40).coerceIn(1240, 1460)
                    val readBuf = ByteArray(32768)
                    try {
                        while (isActive && isRunning.get()) {
                            val count = `in`.read(readBuf)
                            if (count < 0) break // EOF
                            if (count > 0) {
                                var offset = 0
                                while (offset < count) {
                                    val sliceLen = minOf(maxSegmentSize, count - offset)
                                    val chunk = readBuf.copyOfRange(offset, offset + sliceLen)
                                    sendTcpData(session, chunk)
                                    session.serverSeq += sliceLen
                                    offset += sliceLen
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    // Закрытие со стороны удаленного сервера
                    sendTcpPacket(
                        srcIp = session.targetIp,
                        srcPort = session.targetPort,
                        dstIp = session.clientIp,
                        dstPort = session.clientPort,
                        seq = session.serverSeq,
                        ack = session.clientSeq,
                        flags = 0x11 // FIN+ACK
                    )
                    closeTcpSession(key)
                }
            } catch (e: Exception) {
                sendTcpRst(session)
                closeTcpSession(key)
            }
        }
    }

    private fun sendTcpRst(session: TcpSession) {
        sendTcpPacket(
            srcIp = session.targetIp,
            srcPort = session.targetPort,
            dstIp = session.clientIp,
            dstPort = session.clientPort,
            seq = session.serverSeq,
            ack = session.clientSeq,
            flags = 0x14 // RST+ACK
        )
    }

    private fun sendTcpData(session: TcpSession, data: ByteArray) {
        sendTcpPacket(
            srcIp = session.targetIp,
            srcPort = session.targetPort,
            dstIp = session.clientIp,
            dstPort = session.clientPort,
            seq = session.serverSeq,
            ack = session.clientSeq,
            flags = 0x18, // PSH+ACK
            payload = data
        )
    }

    private fun closeTcpSession(key: String) {
        val s = tcpSessions.remove(key) ?: return
        activeTcpFlowCount.decrementAndGet()
        s.pendingTxQueue.clear()
        try {
            s.readerJob?.cancel()
            s.socksSocket?.close()
        } catch (_: Exception) {}
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Packet Crafting & Checksums (Tasks N06, N18)
    // ──────────────────────────────────────────────────────────────────────────
    private fun sendUdpResponse(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        data: ByteArray
    ) {
        val totalLen = 20 + 8 + data.size
        val packet = ByteArray(totalLen)

        // IP Header
        packet[0] = 0x45.toByte() // IPv4, IHL = 5
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte() // Don't fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte() // TTL 64
        packet[9] = 17.toByte() // Protocol UDP
        packet[10] = 0.toByte()
        packet[11] = 0.toByte()

        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipCk = computeChecksum(packet, 0, 20)
        packet[10] = ((ipCk ushr 8) and 0xFF).toByte()
        packet[11] = (ipCk and 0xFF).toByte()

        // UDP Header
        val udpLen = 8 + data.size
        packet[20] = ((srcPort ushr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort ushr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = ((udpLen ushr 8) and 0xFF).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0.toByte()
        packet[27] = 0.toByte()

        System.arraycopy(data, 0, packet, 28, data.size)
        writeToTun(packet)
    }

    private fun sendTcpPacket(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray? = null
    ) {
        val isSynAck = flags == 0x12
        val maxSegmentSize = (vpnMtu - 40).coerceIn(1240, 1460)
        // Для SYN-ACK добавляем TCP MSS Option под выбранный MTU (Kind=2, Length=4)
        // Чтобы предотвратить PMTU blackholes и фрагментацию на сотовых сетях
        val optionsLen = if (isSynAck) 4 else 0
        val tcpHeaderLen = 20 + optionsLen
        val dataOffsetWords = tcpHeaderLen / 4

        val payloadLen = payload?.size ?: 0
        val totalLen = 20 + tcpHeaderLen + payloadLen
        val packet = ByteArray(totalLen)

        // IP Header
        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte() // DF
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte() // TTL 64
        packet[9] = 6.toByte() // TCP
        packet[10] = 0.toByte()
        packet[11] = 0.toByte()

        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipCk = computeChecksum(packet, 0, 20)
        packet[10] = ((ipCk ushr 8) and 0xFF).toByte()
        packet[11] = (ipCk and 0xFF).toByte()

        // TCP Header
        packet[20] = ((srcPort ushr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort ushr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()

        packet[24] = ((seq ushr 24) and 0xFF).toByte()
        packet[25] = ((seq ushr 16) and 0xFF).toByte()
        packet[26] = ((seq ushr 8) and 0xFF).toByte()
        packet[27] = (seq and 0xFF).toByte()

        packet[28] = ((ack ushr 24) and 0xFF).toByte()
        packet[29] = ((ack ushr 16) and 0xFF).toByte()
        packet[30] = ((ack ushr 8) and 0xFF).toByte()
        packet[31] = (ack and 0xFF).toByte()

        packet[32] = ((dataOffsetWords shl 4) and 0xF0).toByte()
        packet[33] = flags.toByte()
        packet[34] = 0xFF.toByte() // Window size (65535)
        packet[35] = 0xFF.toByte()
        packet[36] = 0.toByte()
        packet[37] = 0.toByte()
        packet[38] = 0.toByte()
        packet[39] = 0.toByte()

        if (isSynAck) {
            // Kind=2, Len=4, MSS Value
            packet[40] = 0x02.toByte()
            packet[41] = 0x04.toByte()
            packet[42] = ((maxSegmentSize ushr 8) and 0xFF).toByte()
            packet[43] = (maxSegmentSize and 0xFF).toByte()
        }

        if (payload != null && payloadLen > 0) {
            System.arraycopy(payload, 0, packet, 20 + tcpHeaderLen, payloadLen)
        }

        // TCP Checksum with Pseudo-Header
        val tcpCk = computeTcpChecksum(packet, 12, 16, tcpHeaderLen + payloadLen, 20)
        packet[36] = ((tcpCk ushr 8) and 0xFF).toByte()
        packet[37] = (tcpCk and 0xFF).toByte()

        writeToTun(packet)
    }

    private fun writeToTun(buf: ByteArray) {
        try {
            synchronized(tunHolder.outStream) {
                tunHolder.outStream.write(buf)
            }
            totalBytesOut.addAndGet(buf.size.toLong())
        } catch (_: Exception) {}
    }

    private fun computeChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun computeTcpChecksum(
        packet: ByteArray,
        srcOffset: Int,
        dstOffset: Int,
        tcpLength: Int,
        tcpOffset: Int
    ): Int {
        var sum = 0
        // Pseudo header: Src IP (4B) + Dst IP (4B) + Zero (1B) + Proto (1B, 6) + TCP Len (2B)
        for (i in 0 until 4 step 2) {
            sum += ((packet[srcOffset + i].toInt() and 0xFF) shl 8) or (packet[srcOffset + i + 1].toInt() and 0xFF)
            sum += ((packet[dstOffset + i].toInt() and 0xFF) shl 8) or (packet[dstOffset + i + 1].toInt() and 0xFF)
        }
        sum += 6 // Protocol TCP
        sum += tcpLength

        // TCP header and payload
        var i = tcpOffset
        val end = tcpOffset + tcpLength
        while (i < end - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun computeIpv6Checksum(
        packet: ByteArray,
        srcOffset: Int,
        dstOffset: Int,
        nextHeader: Int,
        length: Int,
        payloadOffset: Int
    ): Int {
        var sum = 0
        for (i in 0 until 16 step 2) {
            sum += ((packet[srcOffset + i].toInt() and 0xFF) shl 8) or (packet[srcOffset + i + 1].toInt() and 0xFF)
            sum += ((packet[dstOffset + i].toInt() and 0xFF) shl 8) or (packet[dstOffset + i + 1].toInt() and 0xFF)
        }
        sum += length
        sum += nextHeader

        var i = payloadOffset
        val end = payloadOffset + length
        while (i < end - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun ipToString(ip: ByteArray): String {
        return "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
    }
}
