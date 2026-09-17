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

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class NodeProbeStatus(val label: String, val isSuccess: Boolean) {
    AVAILABLE("Доступен", true),
    WARNING("Задержка", true),
    DPI_BLOCKED("Блок ТСПУ", false),
    TIMEOUT("Таймаут", false),
    DNS_FAILED("Сбой DNS", false),
    FALLBACK_IP_FAILED("Сбой Fallback IP", false),
    RATE_LIMITED("Лимит 429", false),
    UNSUPPORTED_STAGE("Не поддерживается сетью", false),
    ERROR("Сбой", false),
    IDLE("Не проверен", false),
    CHECKING("Проверка...", false)
}

enum class HealthTargetType(val displayName: String) {
    VLESS_PAGES("Pages / CDN"),
    WORKER("Cloudflare Worker"),
    OPERA_VPN("Opera VPN")
}

data class HealthTargetNode(
    val id: String,
    val name: String,
    val domain: String,
    val port: Int = 443,
    val path: String = "/",
    val type: HealthTargetType,
    val region: String = "",
    val uuid: String = "",
    val isCustom: Boolean = false
)

data class HealthNodeResult(
    val targetId: String,
    val directStatus: NodeProbeStatus,
    val directLatencyMs: Long? = null,
    val directDetail: String = "",
    val operaStatus: NodeProbeStatus? = null,
    val operaLatencyMs: Long? = null,
    val operaDetail: String = "",
    val nodeStatus: NodeProbeStatus? = null,
    val nodeLatencyMs: Long? = null,
    val nodeDetail: String = ""
)

object NodeHealthProber {

    private const val TAG = "NodeHealthProber"
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 4000
    private val resultsCache = ConcurrentHashMap<String, HealthNodeResult>()

    fun getCachedResult(targetId: String): HealthNodeResult? = resultsCache[targetId]

    fun clearCache() {
        resultsCache.clear()
    }

    /**
     * Пакетный параллельный опрос всех узлов с регулируемой многопоточностью (Semaphore).
     */
    suspend fun probeAll(
        targets: List<HealthTargetNode>,
        operaEndpoint: String? = "77.111.247.139:443",
        localSocksPort: Int? = null,
        localSocksAuth: Pair<String, String>? = null,
        vlessDomain: String? = null,
        vlessPath: String? = null,
        vlessUuid: String? = null,
        onProgress: ((completed: Int, total: Int, currentResult: HealthNodeResult) -> Unit)? = null
    ): Map<String, HealthNodeResult> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(6)
        var completedCount = 0
        val total = targets.size

        val deferreds = targets.map { target ->
            async {
                semaphore.withPermit {
                    val res = probeSingle(
                        target = target,
                        operaEndpoint = operaEndpoint,
                        localSocksPort = localSocksPort,
                        localSocksAuth = localSocksAuth,
                        vlessDomain = vlessDomain,
                        vlessPath = vlessPath,
                        vlessUuid = vlessUuid
                    )
                    resultsCache[target.id] = res
                    synchronized(this@NodeHealthProber) {
                        completedCount++
                        onProgress?.invoke(completedCount, total, res)
                    }
                    Pair(target.id, res)
                }
            }
        }

        deferreds.awaitAll().toMap()
    }

    /**
     * Замеряет доступность одного узла:
     * 1. Прямой опрос (DNS -> TCP -> TLS SNI -> WS HTTP probe, детектирует сброс TCP RST ТСПУ).
     * 2. Каскадный опрос:
     *    - Для узлов Opera VPN: замер доступности ЧЕРЕЗ VLESS (через туннель).
     *    - Для узлов Pages и Workers: замер доступности ЧЕРЕЗ OPERA VPN (HTTP CONNECT).
     */
    suspend fun probeSingle(
        target: HealthTargetNode,
        operaEndpoint: String? = "77.111.247.139:443",
        localSocksPort: Int? = null,
        localSocksAuth: Pair<String, String>? = null,
        vlessDomain: String? = null,
        vlessPath: String? = null,
        vlessUuid: String? = null
    ): HealthNodeResult = coroutineScope {
        val directDeferred = async(Dispatchers.IO) { probeDirect(target) }
        val cascadeDeferred = async(Dispatchers.IO) {
            if (target.type == HealthTargetType.OPERA_VPN) {
                probeViaVless(target, localSocksPort, localSocksAuth, vlessDomain, vlessPath, vlessUuid)
            } else if (!operaEndpoint.isNullOrBlank()) {
                probeViaOperaVpn(target, operaEndpoint)
            } else null
        }
        // TSK-V12: Сквозной VLESS E2E замер для Pages/Workers (полный VLESS relay handshake)
        val vlessE2eDeferred = if (target.type != HealthTargetType.OPERA_VPN) {
            async(Dispatchers.IO) { probeVlessE2E(target, vlessUuid) }
        } else null

        val direct = directDeferred.await()
        val cascade = cascadeDeferred.await()
        val vlessE2e = vlessE2eDeferred?.await()

        HealthNodeResult(
            targetId = target.id,
            directStatus = direct.status,
            directLatencyMs = direct.latencyMs,
            directDetail = direct.detail,
            operaStatus = cascade?.status,
            operaLatencyMs = cascade?.latencyMs,
            operaDetail = cascade?.detail ?: "",
            nodeStatus = vlessE2e?.status,
            nodeLatencyMs = vlessE2e?.latencyMs,
            nodeDetail = vlessE2e?.detail ?: ""
        )
    }

    /**
     * Прямая проверка доступности узла и детекция блокировок ТСПУ РКН:
     * - DNS резолвинг (системный/DoH).
     * - TCP соединение.
     * - TLS рукопожатие с передачей SNI.
     *   Если TCP открывается (<300мс), но на TLS летит RST/Connection reset/Timeout -> это 100% блокировка ТСПУ по SNI.
     * - HTTP/WS проба (101 Switching Protocols или 200..399).
     */
    internal fun probeDirect(target: HealthTargetNode): ProbeMetric {
        val host = target.domain.trim()
        val port = target.port
        val startTime = System.currentTimeMillis()

        // 1. Резолвинг DNS
        val addresses = try {
            val resolved = DohResolver.resolveSync(host, DnsScope.BOOTSTRAP)
            if (resolved.isNotEmpty()) {
                resolved
            } else {
                InetAddress.getAllByName(host).toList()
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            return ProbeMetric(NodeProbeStatus.DNS_FAILED, null, "Ошибка DNS: ${e.message ?: "Не найден"}")
        }

        if (addresses.isEmpty()) {
            return ProbeMetric(NodeProbeStatus.DNS_FAILED, null, "Не найден IP адрес (dns_failed)")
        }

        val cachedEntry = DohResolver.getFromCache(host)
        val isFallbackIp = cachedEntry?.resolverSource == "Cloudflare-Anycast-Fallback" ||
                (DohResolver.isCloudflareTargetDomain(host) && addresses.all { DohResolver.CF_ANYCAST_FALLBACK_IPS.contains(it) })

        val targetIp = addresses.first()
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        var isTcpConnected = false

        return try {
            rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = READ_TIMEOUT_MS

            val tcpStart = System.currentTimeMillis()
            rawSocket.connect(InetSocketAddress(targetIp, port), CONNECT_TIMEOUT_MS)
            isTcpConnected = true
            val tcpElapsed = System.currentTimeMillis() - tcpStart

            // 2. Проверка TLS рукопожатия с SNI и валидацией сертификата (MOB-023)
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = sslFactory.createSocket(rawSocket, host, port, true) as SSLSocket
            sslSocket.soTimeout = READ_TIMEOUT_MS

            val sslParams = sslSocket.sslParameters ?: SSLParameters()
            sslParams.serverNames = listOf(SNIHostName(host))
            try {
                sslParams.endpointIdentificationAlgorithm = "HTTPS"
            } catch (_: Throwable) {}
            sslSocket.sslParameters = sslParams

            val tlsStart = System.currentTimeMillis()
            sslSocket.startHandshake()
            val session = sslSocket.session
            if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
                throw javax.net.ssl.SSLPeerUnverifiedException("Certificate hostname mismatch: $host")
            }
            val tlsElapsed = System.currentTimeMillis() - tlsStart

            // 3. Быстрая HTTP/WebSocket проба
            val httpStart = System.currentTimeMillis()
            val cleanPath = if (target.path.startsWith("/")) target.path else "/${target.path}"
            val request = "GET $cleanPath HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36\r\n\r\n"

            val os = sslSocket.getOutputStream()
            os.write(request.toByteArray(StandardCharsets.UTF_8))
            os.flush()

            val isr = sslSocket.getInputStream()
            val buffer = ByteArray(512)
            val readBytes = isr.read(buffer)
            val totalLatency = System.currentTimeMillis() - startTime

            if (readBytes > 0) {
                val header = String(buffer, 0, readBytes, StandardCharsets.UTF_8)
                val statusLine = header.lineSequence().firstOrNull() ?: ""
                when {
                    statusLine.contains("101") -> {
                        val status = if (totalLatency > 400) NodeProbeStatus.WARNING else NodeProbeStatus.AVAILABLE
                        ProbeMetric(status, totalLatency, "WS 101 OK • TLS ${tlsElapsed}мс")
                    }
                    statusLine.contains("429") -> {
                        ProbeMetric(NodeProbeStatus.RATE_LIMITED, totalLatency, "Лимит запросов 429")
                    }
                    statusLine.contains("200") || statusLine.contains("400") || statusLine.contains("404") -> {
                        val status = if (totalLatency > 400) NodeProbeStatus.WARNING else NodeProbeStatus.AVAILABLE
                        ProbeMetric(status, totalLatency, "HTTP OK • TLS ${tlsElapsed}мс")
                    }
                    else -> {
                        ProbeMetric(NodeProbeStatus.AVAILABLE, totalLatency, statusLine.take(30))
                    }
                }
            } else {
                ProbeMetric(NodeProbeStatus.AVAILABLE, totalLatency, "TLS OK (${tlsElapsed}мс)")
            }
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if (isFallbackIp) {
                DohResolver.recordFallbackIpFailure()
                ProbeMetric(NodeProbeStatus.FALLBACK_IP_FAILED, null, "Сбой Anycast Fallback IP: ${e.message ?: "Сбой соединения"}")
            } else if (msg.contains("network is unreachable") || msg.contains("enetunreach") || msg.contains("eafnosupport") || msg.contains("address family not supported")) {
                ProbeMetric(NodeProbeStatus.UNSUPPORTED_STAGE, null, "Семейство адресов не поддерживается сетью: ${e.message}")
            } else if (isTcpConnected && (msg.contains("reset") || msg.contains("broken pipe") || msg.contains("handshake_failure") || e is java.net.SocketException)) {
                ProbeMetric(NodeProbeStatus.DPI_BLOCKED, null, "ТСПУ: сброс TLS SNI")
            } else if (e is SocketTimeoutException) {
                if (isTcpConnected) {
                    ProbeMetric(NodeProbeStatus.DPI_BLOCKED, null, "ТСПУ: глушение TLS (таймаут)")
                } else {
                    ProbeMetric(NodeProbeStatus.TIMEOUT, null, "Таймаут соединения")
                }
            } else {
                ProbeMetric(NodeProbeStatus.ERROR, null, e.message ?: "Сбой соединения")
            }
        } finally {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { rawSocket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Замеряет доступность и задержку узла через upstream HTTP CONNECT прокси Opera VPN.
     */
    internal fun probeViaOperaVpn(target: HealthTargetNode, operaEndpoint: String): ProbeMetric {
        val host = target.domain.trim()
        val port = target.port
        val (operaHost, operaPort) = parseHostPort(operaEndpoint, 443)
        val startTime = System.currentTimeMillis()

        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null

        return try {
            rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = READ_TIMEOUT_MS

            rawSocket.connect(InetSocketAddress(operaHost, operaPort), CONNECT_TIMEOUT_MS)

            val connectReq = "CONNECT $host:$port HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Proxy-Connection: Keep-Alive\r\n" +
                    "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36\r\n\r\n"

            val out = rawSocket.getOutputStream()
            out.write(connectReq.toByteArray(StandardCharsets.UTF_8))
            out.flush()

            val inp = rawSocket.getInputStream()
            val buf = ByteArray(1024)
            val n = inp.read(buf)
            if (n <= 0) {
                return ProbeMetric(NodeProbeStatus.ERROR, null, "Opera: сброс туннеля")
            }
            val resp = String(buf, 0, n, StandardCharsets.UTF_8)
            if (!resp.contains("200")) {
                return ProbeMetric(NodeProbeStatus.ERROR, null, "Opera: ${resp.lines().firstOrNull()?.take(20)}")
            }

            // Туннель поднят, проводим TLS рукопожатие с конечным узлом
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = sslFactory.createSocket(rawSocket, host, port, true) as SSLSocket
            sslSocket.soTimeout = READ_TIMEOUT_MS
            val sslParams = sslSocket.sslParameters ?: SSLParameters()
            sslParams.serverNames = listOf(SNIHostName(host))
            sslSocket.sslParameters = sslParams
            sslSocket.startHandshake()

            val elapsed = System.currentTimeMillis() - startTime
            ProbeMetric(NodeProbeStatus.AVAILABLE, elapsed, "Opera туннель OK")
        } catch (e: Exception) {
            ProbeMetric(NodeProbeStatus.TIMEOUT, null, "Opera: ${e.message ?: "таймаут"}")
        } finally {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { rawSocket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Замеряет доступность узла Opera VPN через VLESS туннель:
     * 1. Если запущен локальный SOCKS5 (с VLESS аплинком) -> опрос через него.
     * 2. Иначе -> прямой замер через VLESS WebSocket хост.
     */
    internal fun probeViaVless(
        target: HealthTargetNode,
        localSocksPort: Int?,
        localSocksAuth: Pair<String, String>?,
        vlessDomain: String?,
        vlessPath: String?,
        vlessUuid: String?
    ): ProbeMetric {
        if (localSocksPort != null && localSocksPort > 0) {
            val localRes = probeViaLocalSocks(target, localSocksPort, localSocksAuth)
            if (localRes.status == NodeProbeStatus.AVAILABLE) {
                return localRes.copy(detail = "VLESS туннель OK")
            }
        }

        val domain = vlessDomain?.trim()?.ifEmpty { null } ?: VlessPresetsRepository.getDefaultPreset().domain
        val path = vlessPath?.trim()?.ifEmpty { "/vless-ws?ed=2048" } ?: "/vless-ws?ed=2048"
        val uuid = vlessUuid?.trim()?.ifEmpty { "d342d11e-d424-4583-b36e-524ab1f0afa4" } ?: "d342d11e-d424-4583-b36e-524ab1f0afa4"

        return probeViaVlessDirectWs(target, domain, path, uuid)
    }

    private fun probeViaVlessDirectWs(
        target: HealthTargetNode,
        vlessDomain: String,
        vlessPath: String,
        vlessUuid: String
    ): ProbeMetric {
        val startTime = System.currentTimeMillis()
        var sslSocket: SSLSocket? = null
        var rawSocket: Socket? = null

        return try {
            rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = READ_TIMEOUT_MS
            rawSocket.connect(InetSocketAddress(vlessDomain, 443), CONNECT_TIMEOUT_MS)

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = sslFactory.createSocket(rawSocket, vlessDomain, 443, true) as SSLSocket
            sslSocket.soTimeout = READ_TIMEOUT_MS
            val sslParams = sslSocket.sslParameters ?: SSLParameters()
            sslParams.serverNames = listOf(SNIHostName(vlessDomain))
            sslSocket.sslParameters = sslParams
            sslSocket.startHandshake()

            val out = sslSocket.getOutputStream()
            val inp = sslSocket.getInputStream()

            val req = "GET $vlessPath HTTP/1.1\r\n" +
                    "Host: $vlessDomain\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"
            out.write(req.toByteArray(StandardCharsets.US_ASCII))
            out.flush()

            val buf = ByteArray(1024)
            val n = inp.read(buf)
            if (n > 0) {
                val resp = String(buf, 0, n, StandardCharsets.US_ASCII)
                if (resp.contains("101") || resp.contains("200")) {
                    val elapsed = System.currentTimeMillis() - startTime
                    ProbeMetric(NodeProbeStatus.AVAILABLE, elapsed, "VLESS туннель OK")
                } else {
                    ProbeMetric(NodeProbeStatus.ERROR, null, "VLESS: ${resp.lines().firstOrNull()?.take(20)}")
                }
            } else {
                ProbeMetric(NodeProbeStatus.ERROR, null, "VLESS: сброс")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "ошибка"
            if (msg.contains("reset", ignoreCase = true) || msg.contains("RST", ignoreCase = true)) {
                ProbeMetric(NodeProbeStatus.DPI_BLOCKED, null, "ТСПУ блок VLESS")
            } else {
                ProbeMetric(NodeProbeStatus.TIMEOUT, null, "VLESS: $msg")
            }
        } finally {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { rawSocket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Замеряет доступность через локальный активный SOCKS5 прокси (поверх VLESS или WARP).
     */
    internal fun probeViaLocalSocks(
        target: HealthTargetNode,
        socksPort: Int,
        auth: Pair<String, String>? = null
    ): ProbeMetric = probeViaLocalSocks(target.domain.trim(), target.port, socksPort, auth)

    /**
     * Сквозная валидация туннеля через локальный SOCKS5 прокси (RFC 1928 / RFC 1929)
     * до произвольного хоста/IP и порта (включая IPv4 дата-центров Telegram).
     *
     * Обеспечивает строгое побайтовое чтение протокольных структур SOCKS5 (readFully),
     * валидацию версии, методов авторизации и адреса связывания по RFC 1928.
     * При [e2eProbe] == true выполняет сквозную проверку передачи прикладных данных
     * и проверяет полезный входящий RX-трафик (не доверяя только успешному ответу CONNECT).
     */
    fun probeViaLocalSocks(
        host: String,
        port: Int,
        socksPort: Int,
        auth: Pair<String, String>? = null,
        timeoutMs: Int = CONNECT_TIMEOUT_MS,
        e2eProbe: Boolean = false,
        e2ePayload: ByteArray? = null
    ): ProbeMetric {
        val cleanHost = host.trim().trim('[').trim(']')
        val startTime = System.currentTimeMillis()
        var socket: Socket? = null

        return try {
            socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), 1500)

            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            // 1. SOCKS5 Auth negotiation (RFC 1928 / RFC 1929)
            if (auth != null && (auth.first.isNotEmpty() || auth.second.isNotEmpty())) {
                out.write(byteArrayOf(0x05, 0x01, 0x02)) // SOCKS5, 1 method: Username/Password
                out.flush()
                val authRes = ByteArray(2)
                readFully(inp, authRes)
                if (authRes[0] != 0x05.toByte()) {
                    return ProbeMetric(NodeProbeStatus.ERROR, null, "Неверная версия SOCKS5 (${authRes[0].toInt() and 0xFF})")
                }
                if (authRes[1] != 0x02.toByte()) {
                    return ProbeMetric(NodeProbeStatus.ERROR, null, "SOCKS5 метод авторизации отклонен (${authRes[1].toInt() and 0xFF})")
                }
                val uBytes = auth.first.toByteArray(StandardCharsets.UTF_8)
                val pBytes = auth.second.toByteArray(StandardCharsets.UTF_8)
                val authPayload = byteArrayOf(0x01, uBytes.size.toByte()) + uBytes + byteArrayOf(pBytes.size.toByte()) + pBytes
                out.write(authPayload)
                out.flush()
                val authSubRes = ByteArray(2)
                readFully(inp, authSubRes)
                if (authSubRes[0] != 0x01.toByte() || authSubRes[1] != 0x00.toByte()) {
                    return ProbeMetric(NodeProbeStatus.ERROR, null, "SOCKS5 Auth error (код ${authSubRes[1].toInt() and 0xFF})")
                }
            } else {
                out.write(byteArrayOf(0x05, 0x01, 0x00)) // No auth
                out.flush()
                val res = ByteArray(2)
                readFully(inp, res)
                if (res[0] != 0x05.toByte()) {
                    return ProbeMetric(NodeProbeStatus.ERROR, null, "Неверная версия SOCKS5 (${res[0].toInt() and 0xFF})")
                }
                if (res[1] != 0x00.toByte()) {
                    return ProbeMetric(NodeProbeStatus.ERROR, null, "SOCKS5 метод NO AUTH отклонен (${res[1].toInt() and 0xFF})")
                }
            }

            // 2. SOCKS5 Connect (RFC 1928 §5)
            val portBytes = byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte())
            val connectReq = if (cleanHost.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
                // ATYP 0x01: IPv4 Address (4 bytes)
                val octets = cleanHost.split('.').map { it.toInt().toByte() }.toByteArray()
                byteArrayOf(0x05, 0x01, 0x00, 0x01) + octets + portBytes
            } else if (cleanHost.contains(':')) {
                // ATYP 0x04: IPv6 Address (16 bytes)
                try {
                    val inet6 = InetAddress.getByName(cleanHost)
                    val ipBytes = inet6.address
                    if (ipBytes.size == 16) {
                        byteArrayOf(0x05, 0x01, 0x00, 0x04) + ipBytes + portBytes
                    } else {
                        val hostBytes = cleanHost.toByteArray(StandardCharsets.UTF_8)
                        byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) + hostBytes + portBytes
                    }
                } catch (_: Exception) {
                    val hostBytes = cleanHost.toByteArray(StandardCharsets.UTF_8)
                    byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) + hostBytes + portBytes
                }
            } else {
                // ATYP 0x03: Domain Name (1 byte length + ASCII bytes)
                val hostBytes = cleanHost.toByteArray(StandardCharsets.UTF_8)
                byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) + hostBytes + portBytes
            }
            out.write(connectReq)
            out.flush()

            // 3. Точный разбор ответа SOCKS5 (RFC 1928 §6)
            val (rep, _) = readSocks5Reply(inp)
            if (rep != 0x00) {
                if (rep == 0x03 || rep == 0x08) {
                    return ProbeMetric(NodeProbeStatus.UNSUPPORTED_STAGE, null, "Не поддерживается сетью (SOCKS5 rep=$rep)")
                }
                return ProbeMetric(NodeProbeStatus.ERROR, null, "Отказ туннеля (код $rep)")
            }

            // 4. Отдельная сквозная E2E проба полезных данных через аплинк
            if (e2eProbe || e2ePayload != null) {
                val payload = e2ePayload ?: buildDefaultProbePayload(cleanHost, port)
                out.write(payload)
                out.flush()

                val rxBuf = ByteArray(1024)
                val rxCount = inp.read(rxBuf)
                if (rxCount <= 0) {
                    return ProbeMetric(
                        NodeProbeStatus.ERROR,
                        null,
                        "E2E: сервер не вернул полезных данных после CONNECT (RX=0)",
                        rxBytes = 0
                    )
                }
                val elapsed = System.currentTimeMillis() - startTime
                ProbeMetric(
                    NodeProbeStatus.AVAILABLE,
                    elapsed,
                    "E2E туннель активен (RX ${rxCount}Б)",
                    rxBytes = rxCount
                )
            } else {
                val elapsed = System.currentTimeMillis() - startTime
                ProbeMetric(NodeProbeStatus.AVAILABLE, elapsed, "Туннель активен", rxBytes = 0)
            }
        } catch (e: EOFException) {
            ProbeMetric(NodeProbeStatus.ERROR, null, "Обрыв потока SOCKS5: ${e.message ?: "EOF"}")
        } catch (e: SocketTimeoutException) {
            ProbeMetric(NodeProbeStatus.TIMEOUT, null, "Таймаут прокси")
        } catch (e: java.net.ConnectException) {
            ProbeMetric(NodeProbeStatus.TIMEOUT, null, "Прокси недоступен (порт закрыт)")
        } catch (e: Exception) {
            val msg = e.message ?: "Сбой SOCKS5"
            if (msg.contains("Network is unreachable", ignoreCase = true) ||
                msg.contains("ENETUNREACH", ignoreCase = true) ||
                msg.contains("EAFNOSUPPORT", ignoreCase = true) ||
                msg.contains("Address family not supported", ignoreCase = true)
            ) {
                ProbeMetric(NodeProbeStatus.UNSUPPORTED_STAGE, null, msg)
            } else {
                ProbeMetric(NodeProbeStatus.ERROR, null, msg)
            }
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Сквозная E2E проба туннеля с обязательной отправкой прикладного запроса и валидацией полезного RX.
     */
    fun probeViaLocalSocksE2E(
        host: String,
        port: Int,
        socksPort: Int,
        auth: Pair<String, String>? = null,
        timeoutMs: Int = CONNECT_TIMEOUT_MS,
        e2ePayload: ByteArray? = null
    ): ProbeMetric = probeViaLocalSocks(
        host = host,
        port = port,
        socksPort = socksPort,
        auth = auth,
        timeoutMs = timeoutMs,
        e2eProbe = true,
        e2ePayload = e2ePayload
    )

    /**
     * Сквозная валидация туннеля через локальный SOCKS5 прокси до целевого дата-центра Telegram (DC2 149.154.167.50:443).
     */
    fun probeTelegramViaLocalSocks(
        socksPort: Int,
        auth: Pair<String, String>? = null,
        targetIp: String = "149.154.167.50",
        targetPort: Int = 443,
        timeoutMs: Int = CONNECT_TIMEOUT_MS,
        e2eProbe: Boolean = false
    ): ProbeMetric = probeViaLocalSocks(targetIp, targetPort, socksPort, auth, timeoutMs, e2eProbe = e2eProbe)

    /**
     * TSK-V12: Сквозной VLESS E2E замер.
     * Полный путь: TCP -> TLS -> WS 101 Upgrade -> VLESS binary header -> VLESS response.
     * Валидирует реальное прохождение байт через Worker/Pages VLESS-релей.
     * Целевой хост: 149.154.167.51:443 (Telegram DC2).
     */
    internal fun probeVlessE2E(
        target: HealthTargetNode,
        fallbackUuid: String?
    ): ProbeMetric {
        val host = target.domain.trim()
        val port = target.port
        val path = target.path.ifBlank { "/vless-ws?ed=2048" }
        val uuid = target.uuid.ifBlank { fallbackUuid ?: "d342d11e-d424-4583-b36e-524ab1f0afa4" }
        val startTime = System.currentTimeMillis()

        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null

        return try {
            // 1. TCP + TLS
            rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = READ_TIMEOUT_MS
            rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = sslFactory.createSocket(rawSocket, host, port, true) as SSLSocket
            sslSocket.soTimeout = READ_TIMEOUT_MS
            val sslParams = sslSocket.sslParameters ?: SSLParameters()
            sslParams.serverNames = listOf(SNIHostName(host))
            sslSocket.sslParameters = sslParams
            sslSocket.startHandshake()

            val out = sslSocket.getOutputStream()
            val inp = sslSocket.getInputStream()

            // 2. WS Upgrade
            val cleanPath = if (path.startsWith("/")) path else "/$path"
            val wsReq = "GET $cleanPath HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36\r\n\r\n"
            out.write(wsReq.toByteArray(StandardCharsets.US_ASCII))
            out.flush()

            val buf = ByteArray(1024)
            val n = inp.read(buf)
            if (n <= 0) {
                return ProbeMetric(NodeProbeStatus.ERROR, null, "E2E: WS сброс")
            }
            val resp = String(buf, 0, n, StandardCharsets.US_ASCII)
            if (resp.contains("429")) {
                return ProbeMetric(NodeProbeStatus.RATE_LIMITED, null, "E2E: лимит 429")
            }
            if (!resp.contains("101")) {
                val status = resp.lines().firstOrNull()?.take(25) ?: "unknown"
                return ProbeMetric(NodeProbeStatus.ERROR, null, "E2E: $status")
            }

            // 3. Отправка VLESS header как WS binary frame
            val vlessHeader = buildVlessProbeHeader(uuid)
            val vlessStart = System.currentTimeMillis()
            sendWsBinaryFrame(out, vlessHeader)

            // 4. Чтение VLESS response из WS frame
            val vlessResp = readWsBinaryFrame(inp)
            val vlessRtt = System.currentTimeMillis() - vlessStart
            val totalLatency = System.currentTimeMillis() - startTime

            if (vlessResp == null || vlessResp.size < 2) {
                return ProbeMetric(NodeProbeStatus.ERROR, null, "E2E: пустой VLESS ответ")
            }

            // 5. Валидация VLESS response (version 0x00 + addons_len)
            if (vlessResp[0] != 0x00.toByte()) {
                return ProbeMetric(NodeProbeStatus.ERROR, null, "E2E: VLESS v${vlessResp[0].toInt() and 0xFF}")
            }

            val status = if (totalLatency > 600) NodeProbeStatus.WARNING else NodeProbeStatus.AVAILABLE
            ProbeMetric(status, totalLatency, "Relay ${vlessRtt}мс")
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            when {
                msg.contains("reset") || msg.contains("broken pipe") ||
                        msg.contains("handshake_failure") || e is java.net.SocketException ->
                    ProbeMetric(NodeProbeStatus.DPI_BLOCKED, null, "ТСПУ блок E2E")
                e is SocketTimeoutException ->
                    ProbeMetric(NodeProbeStatus.TIMEOUT, null, "E2E: таймаут relay")
                e is java.io.EOFException ->
                    ProbeMetric(NodeProbeStatus.ERROR, null, "E2E: обрыв relay")
                else ->
                    ProbeMetric(NodeProbeStatus.ERROR, null, "E2E: ${e.message?.take(30)}")
            }
        } finally {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { rawSocket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Собирает минимальный VLESS TCP-request header для зондирования.
     * Target: 149.154.167.51:443 (Telegram DC2 — гарантированно активный TCP-endpoint).
     * Формат: version(1) + uuid(16) + addons_len(1) + cmd(1) + port(2) + atyp(1) + ipv4(4) = 26 байт.
     */
    private fun buildVlessProbeHeader(uuidHex: String): ByteArray {
        val uuid = parseUuidHexToBytes(uuidHex)
        val buf = ByteArray(26)
        buf[0] = 0x00 // VLESS version
        System.arraycopy(uuid, 0, buf, 1, 16)
        buf[17] = 0x00 // addons length = 0
        buf[18] = 0x01 // command = TCP connect
        buf[19] = (443 shr 8).toByte()  // port high byte
        buf[20] = (443 and 0xFF).toByte() // port low byte
        buf[21] = 0x01 // address type = IPv4
        buf[22] = 149.toByte() // 149.154.167.51 (Telegram DC2)
        buf[23] = 154.toByte()
        buf[24] = 167.toByte()
        buf[25] = 51
        return buf
    }

    /** Парсит UUID строку (с дефисами или без) в 16-байтовый массив. */
    private fun parseUuidHexToBytes(hex: String): ByteArray {
        val clean = hex.replace("-", "").lowercase()
        if (clean.length != 32) return ByteArray(16)
        return ByteArray(16) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Отправляет бинарный WebSocket frame (клиент -> сервер, с маской).
     * FIN=1, opcode=0x02 (Binary), MASK=1.
     */
    private fun sendWsBinaryFrame(out: OutputStream, payload: ByteArray) {
        val len = payload.size
        val maskKey = byteArrayOf(0x37, 0x7A, 0x11, 0x5B)

        out.write(0x82) // FIN + Binary opcode

        if (len < 126) {
            out.write(0x80 or len) // MASK bit set + length
        } else {
            out.write(0x80 or 126)
            out.write(len shr 8)
            out.write(len and 0xFF)
        }

        out.write(maskKey)

        for (i in payload.indices) {
            out.write(payload[i].toInt() xor maskKey[i % 4].toInt())
        }
        out.flush()
    }

    /**
     * Читает один WebSocket binary frame (сервер -> клиент, без маски).
     * Возвращает payload или null при ошибке.
     */
    private fun readWsBinaryFrame(inp: InputStream): ByteArray? {
        val b0 = inp.read()
        if (b0 < 0) return null
        val b1 = inp.read()
        if (b1 < 0) return null

        val masked = (b1 and 0x80) != 0
        var payloadLen = (b1 and 0x7F)

        if (payloadLen == 126) {
            val h = inp.read()
            val l = inp.read()
            if (h < 0 || l < 0) return null
            payloadLen = (h shl 8) or l
        } else if (payloadLen == 127) {
            return null // 8-byte extended length — слишком большой для VLESS response
        }

        if (payloadLen <= 0 || payloadLen > 65536) return null

        val maskKey = if (masked) {
            val mk = ByteArray(4)
            readInputFully(inp, mk)
            mk
        } else null

        val data = ByteArray(payloadLen)
        readInputFully(inp, data)

        if (maskKey != null) {
            for (i in data.indices) {
                data[i] = (data[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }

        return data
    }

    /** Полное чтение буфера из InputStream с обработкой частичных reads. */
    private fun readInputFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException("unexpected EOF reading WS frame")
            off += n
        }
    }

    private fun parseHostPort(raw: String, defaultPort: Int = 443): Pair<String, Int> {
        val trimmed = raw.trim()
        val colon = trimmed.lastIndexOf(':')
        return if (colon >= 0) {
            val h = trimmed.substring(0, colon).trim().removePrefix("[").removeSuffix("]")
            val p = trimmed.substring(colon + 1).toIntOrNull() ?: defaultPort
            Pair(h, p)
        } else {
            Pair(trimmed, defaultPort)
        }
    }

    private fun readFully(inp: InputStream, buf: ByteArray, offset: Int = 0, len: Int = buf.size - offset) {
        var n = 0
        while (n < len) {
            val count = inp.read(buf, offset + n, len - n)
            if (count < 0) {
                throw EOFException("Неожиданный конец потока SOCKS5: прочитано $n из $len байт")
            }
            n += count
        }
    }

    private fun readSocks5Reply(inp: InputStream): Pair<Int, String> {
        val header = ByteArray(4)
        readFully(inp, header)
        if (header[0] != 0x05.toByte()) {
            throw IllegalArgumentException("Неверная версия SOCKS5 reply: ${header[0].toInt() and 0xFF}")
        }
        val rep = header[1].toInt() and 0xFF
        val atyp = header[3].toInt() and 0xFF

        val boundAddr = when (atyp) {
            0x01 -> {
                val bnd = ByteArray(6)
                readFully(inp, bnd)
                val ip = "${bnd[0].toInt() and 0xFF}.${bnd[1].toInt() and 0xFF}.${bnd[2].toInt() and 0xFF}.${bnd[3].toInt() and 0xFF}"
                val port = ((bnd[4].toInt() and 0xFF) shl 8) or (bnd[5].toInt() and 0xFF)
                "$ip:$port"
            }
            0x03 -> {
                val lenBuf = ByteArray(1)
                readFully(inp, lenBuf)
                val domainLen = lenBuf[0].toInt() and 0xFF
                val domainBuf = ByteArray(domainLen)
                readFully(inp, domainBuf)
                val portBuf = ByteArray(2)
                readFully(inp, portBuf)
                val domain = String(domainBuf, StandardCharsets.UTF_8)
                val port = ((portBuf[0].toInt() and 0xFF) shl 8) or (portBuf[1].toInt() and 0xFF)
                "$domain:$port"
            }
            0x04 -> {
                val bnd = ByteArray(18)
                readFully(inp, bnd)
                val port = ((bnd[16].toInt() and 0xFF) shl 8) or (bnd[17].toInt() and 0xFF)
                "[IPv6]:$port"
            }
            else -> throw IllegalArgumentException("Неподдерживаемый SOCKS5 ATYP: $atyp")
        }
        return Pair(rep, boundAddr)
    }

    fun buildDefaultProbePayload(host: String, port: Int): ByteArray {
        return when (port) {
            443 -> buildTlsClientHello(host)
            80, 8080 -> "HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
            else -> "MIRRLY_E2E_PING\r\n".toByteArray(StandardCharsets.US_ASCII)
        }
    }

    fun buildTlsClientHello(sni: String): ByteArray {
        val hostBytes = sni.trim().trim('[').trim(']').toByteArray(StandardCharsets.UTF_8)
        val nameListLen = hostBytes.size + 3
        val sniExtensionData = byteArrayOf(
            ((nameListLen shr 8) and 0xFF).toByte(),
            (nameListLen and 0xFF).toByte(),
            0x00, // host_name
            ((hostBytes.size shr 8) and 0xFF).toByte(),
            (hostBytes.size and 0xFF).toByte()
        ) + hostBytes

        val sniExtLen = sniExtensionData.size
        val sniExtension = byteArrayOf(
            0x00, 0x00, // server_name (0)
            ((sniExtLen shr 8) and 0xFF).toByte(),
            (sniExtLen and 0xFF).toByte()
        ) + sniExtensionData

        val supportedVersions = byteArrayOf(
            0x00, 0x2b, // supported_versions (43)
            0x00, 0x05, // length = 5
            0x04,       // versions length = 4
            0x03, 0x04, // TLS 1.3
            0x03, 0x03  // TLS 1.2
        )

        val extensionsBytes = sniExtension + supportedVersions
        val extensions = byteArrayOf(
            ((extensionsBytes.size shr 8) and 0xFF).toByte(),
            (extensionsBytes.size and 0xFF).toByte()
        ) + extensionsBytes

        val random = ByteArray(32).apply { java.security.SecureRandom().nextBytes(this) }
        val ciphers = byteArrayOf(
            0x00, 0x04, // length = 4
            0x13.toByte(), 0x01.toByte(), // TLS_AES_128_GCM_SHA256
            0xc0.toByte(), 0x2f.toByte()  // TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
        )
        val compression = byteArrayOf(0x01, 0x00)

        val clientHelloBody = byteArrayOf(0x03, 0x03) + random + byteArrayOf(0x00) + ciphers + compression + extensions
        val handshakeHeader = byteArrayOf(
            0x01, // ClientHello
            0x00,
            ((clientHelloBody.size shr 8) and 0xFF).toByte(),
            (clientHelloBody.size and 0xFF).toByte()
        )
        val recordPayload = handshakeHeader + clientHelloBody
        val recordHeader = byteArrayOf(
            0x16, // Handshake
            0x03, 0x01, // TLS 1.0 record
            ((recordPayload.size shr 8) and 0xFF).toByte(),
            (recordPayload.size and 0xFF).toByte()
        )
        return recordHeader + recordPayload
    }

    data class ProbeMetric(
        val status: NodeProbeStatus,
        val latencyMs: Long?,
        val detail: String,
        val rxBytes: Int = 0
    ) {
        val isReady: Boolean
            get() = status.isSuccess && (latencyMs != null && latencyMs >= 0)
    }
}
