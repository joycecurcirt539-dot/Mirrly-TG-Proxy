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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Transparent SOCKS5 bridge that acts as a pure TCP relay.
 *
 * All traffic (Telegram DC chats/media AND VoIP calls) is routed through
 * handleVoIpOrGeneralTraffic() which uses Cloudflare CDN FlowSila domains
 * as the sole transport. No direct TCP to Telegram is ever attempted.
 *
 * There is NO MTProto-specific processing here — SOCKS5 is a transparent proxy.
 * Telegram handles its own encryption end-to-end with the DC.
 */
class Socks5WsBridge(
    private val clientSocket: Socket,
    private val config: ProxyConfig,
    private val stats: ProxyStats,
    private val wsPool: WsPool? = null
) {
    private val bridgeScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    suspend fun handleConnection() = withContext(Dispatchers.IO) {
        stats.activeConnections.incrementAndGet()
        val clientIp = try { clientSocket.remoteSocketAddress.toString() } catch (_: Exception) { "unknown" }
        AppLogger.i("Socks5WsBridge", "▶ [SOCKS5] Новое подключение от $clientIp")
        try {
            if (config.tcpNoDelay) {
                try { clientSocket.tcpNoDelay = true } catch (_: Exception) {}
            }
            try {
                clientSocket.receiveBufferSize = config.bufferSizeBytes
                clientSocket.sendBufferSize = config.bufferSizeBytes
            } catch (_: Exception) {}

            val inputStream: InputStream = clientSocket.getInputStream()
            val outputStream: OutputStream = clientSocket.getOutputStream()

            // ── 1. SOCKS5 Handshake / Auth Negotiation (RFC 1928) ────────────────
            val ver = inputStream.read()
            if (ver != 0x05) {
                AppLogger.w("Socks5WsBridge", "❌ Неверная версия SOCKS5 протокола: $ver (ожидалось 0x05)")
                return@withContext
            }

            val nMethods = inputStream.read()
            if (nMethods <= 0) {
                AppLogger.w("Socks5WsBridge", "❌ Пустой список методов аутентификации")
                return@withContext
            }

            val methods = ByteArray(nMethods)
            var mRead = 0
            while (mRead < nMethods) {
                val count = inputStream.read(methods, mRead, nMethods - mRead)
                if (count < 0) return@withContext
                mRead += count
            }

            // Check if NO AUTH (0x00) is supported
            val supportsNoAuth = methods.contains(0x00.toByte())
            if (!supportsNoAuth) {
                AppLogger.w("Socks5WsBridge", "❌ Клиент не поддерживает NO AUTH (0x00)")
                outputStream.write(byteArrayOf(0x05, 0xFF.toByte()))
                outputStream.flush()
                return@withContext
            }

            // Accept NO AUTH: 0x05 0x00
            outputStream.write(byteArrayOf(0x05, 0x00))
            outputStream.flush()

            // ── 2. SOCKS5 Request Details ─────────────────────────────────────────
            val reqVer = inputStream.read()
            if (reqVer != 0x05) {
                AppLogger.w("Socks5WsBridge", "❌ Неверная версия запроса SOCKS5: $reqVer")
                return@withContext
            }

            val cmd = inputStream.read()
            val rsv = inputStream.read() // Reserved 0x00
            val atyp = inputStream.read()

            // Handle 0x03 (UDP ASSOCIATE) -> Command Not Supported (0x07)
            // Forces Telegram to instantly fall back to TCP VoIP Relay mode for calls.
            if (cmd == 0x03) {
                AppLogger.i("Socks5WsBridge", "ℹ [SOCKS5] UDP ASSOCIATE (0x03) -> Ответ 0x07 (перевод Telegram в TCP VoIP режим)")
                outputStream.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                outputStream.flush()
                return@withContext
            }

            // Only 0x01 (CONNECT) is supported
            if (cmd != 0x01) {
                AppLogger.w("Socks5WsBridge", "❌ Неподдерживаемая команда SOCKS5 cmd=$cmd")
                outputStream.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                outputStream.flush()
                return@withContext
            }

            // Parse Target Address
            val targetHost: String = when (atyp) {
                0x01 -> { // IPv4 (4 bytes)
                    val ipBuf = ByteArray(4)
                    var readLen = 0
                    while (readLen < 4) {
                        val count = inputStream.read(ipBuf, readLen, 4 - readLen)
                        if (count < 0) return@withContext
                        readLen += count
                    }
                    InetAddress.getByAddress(ipBuf).hostAddress ?: return@withContext
                }
                0x03 -> { // Domain Name (1 byte length + string)
                    val domainLen = inputStream.read()
                    if (domainLen <= 0) return@withContext
                    val domainBuf = ByteArray(domainLen)
                    var readLen = 0
                    while (readLen < domainLen) {
                        val count = inputStream.read(domainBuf, readLen, domainLen - readLen)
                        if (count < 0) return@withContext
                        readLen += count
                    }
                    String(domainBuf, Charsets.UTF_8)
                }
                0x04 -> { // IPv6 (16 bytes)
                    val ipBuf = ByteArray(16)
                    var readLen = 0
                    while (readLen < 16) {
                        val count = inputStream.read(ipBuf, readLen, 16 - readLen)
                        if (count < 0) return@withContext
                        readLen += count
                    }
                    InetAddress.getByAddress(ipBuf).hostAddress ?: return@withContext
                }
                else -> {
                    AppLogger.w("Socks5WsBridge", "❌ Неподдерживаемый тип адреса ATYP=$atyp")
                    outputStream.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                    outputStream.flush()
                    return@withContext
                }
            }

            // Parse Target Port (2 bytes big-endian)
            val p1 = inputStream.read()
            val p2 = inputStream.read()
            if (p1 < 0 || p2 < 0) return@withContext
            val targetPort = ((p1 and 0xFF) shl 8) or (p2 and 0xFF)

            AppLogger.i("Socks5WsBridge", "🎯 [SOCKS5 CONNECT] Назначение: $targetHost:$targetPort")

            // Respond SOCKS5 SUCCESS (0x05 0x00 0x00 0x01 0x00 0x00 0x00 0x00 0x00 0x00)
            outputStream.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            outputStream.flush()

            // ── 3. Прозрачный relay: весь трафик через CF Worker ───────────
            handleVoIpOrGeneralTraffic(targetHost, targetPort, inputStream, outputStream)
        } catch (t: Throwable) {
            AppLogger.e("Socks5WsBridge", "💥 Ошибка во время SOCKS5 сессии ($clientIp): ${t.message}")
        } finally {
            stats.activeConnections.decrementAndGet()
            bridgeScope.cancel()
            try { clientSocket.close() } catch (_: Exception) {}
            AppLogger.i("Socks5WsBridge", "🏁 [SOCKS5] Сессия $clientIp завершена")
        }
    }

    /**
     * Routes all traffic through Cloudflare CDN FlowSila domains.
     * WsPool is checked first (zero-latency). Then sequential CDN domain attempts.
     * If all CDN domains fail, connection is refused with no direct TCP fallback.
     */
    private suspend fun handleVoIpOrGeneralTraffic(
        targetHost: String,
        targetPort: Int,
        inputStream: InputStream,
        outputStream: OutputStream
    ) {
        var wsConnected = false
        var wsClient: RawWebSocketClient? = null

        // Priority 1: WsPool pre-warmed connection (Zero-Latency)
        val dcInfo = TgConstants.findDcByTarget(targetHost)
        if (dcInfo != null && wsPool != null) {
            val pooled = wsPool.get(dcInfo.first, dcInfo.second, config.isTestEnvironment)
            if (pooled != null) {
                wsClient = pooled
                wsConnected = true
                AppLogger.i("Socks5WsBridge", "[SOCKS5 WsPool] Pre-warmed CDN socket for DC${dcInfo.first}")
            }
        }

        // Priority 2: Cloudflare CDN FlowSila domains (sole transport)
        if (!wsConnected) {
            val cfDomain = config.getEffectiveCfDomain()
            val targetSpec = if (targetHost.contains(":") && !targetHost.startsWith("[")) "[$targetHost]:$targetPort" else "$targetHost:$targetPort"

            val domainsToTry = if (cfDomain.isNotBlank()) {
                listOf(cfDomain)
            } else {
                TgConstants.DEFAULT_EMBEDDED_DOMAINS
            }

            for (domain in domainsToTry) {
                val wsUrl = "wss://$domain/tcp?target=$targetSpec"
                AppLogger.i("Socks5WsBridge", "[SOCKS5 CDN] Connecting via $domain for $targetSpec...")
                val client = RawWebSocketClient(wsUrl)
                try {
                    val connected = client.connectAndAwait(4000)
                    if (connected && client.isAlive) {
                        wsClient = client
                        wsConnected = true
                        AppLogger.i("Socks5WsBridge", "[SOCKS5 CDN] Connected via $domain")
                        TgConstants.promoteDomain(domain)
                        break
                    } else {
                        client.close()
                    }
                } catch (e: Exception) {
                    AppLogger.e("Socks5WsBridge", "❌ [SOCKS5 CDN] Не удалось подключиться через $domain: ${e.message}")
                }
            }
        }

        if (!wsConnected || wsClient == null) {
            AppLogger.w("Socks5WsBridge", "❌ [SOCKS5 CDN] Все CDN домены недоступны для $targetHost:$targetPort")
            return
        }

        val activeWs = wsClient

        // Coroutine 1: WS -> Client Socket (Download)
        val wsToClientJob = bridgeScope.launch {
            try {
                activeWs.messageChannel.consumeEach { frame ->
                    outputStream.write(frame)
                    outputStream.flush()
                    stats.addReceived(frame.size.toLong())
                }
            } catch (e: Exception) {
                AppLogger.w("Socks5WsBridge", "⚠️ [WSS -> Client] Завершено/Ошибка: ${e.message}")
            } finally {
                try { clientSocket.close() } catch (_: Exception) {}
            }
        }

        // Coroutine 2: Monitor WS closure
        val closeMonitorJob = bridgeScope.launch {
            try {
                activeWs.closeChannel.receive()
            } catch (_: Exception) {
            } finally {
                try { clientSocket.close() } catch (_: Exception) {}
            }
        }

        // Loop: Client Socket -> WS (Upload)
        val bufLen = config.bufferSizeBytes.coerceAtLeast(16384)
        val buf = ByteArray(bufLen)
        try {
            while (bridgeScope.isActive && !clientSocket.isClosed && activeWs.isAlive) {
                val count = inputStream.read(buf)
                if (count < 0) break
                if (!activeWs.send(buf, 0, count)) break
                stats.addSent(count.toLong())
            }
        } catch (e: Exception) {
            AppLogger.w("Socks5WsBridge", "⚠️ [Client -> WSS] Завершено/Ошибка: ${e.message}")
        } finally {
            wsToClientJob.cancel()
            closeMonitorJob.cancel()
            activeWs.close()
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }
}
