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
import java.net.Socket

class TgWsBridge(
    private val clientSocket: Socket,
    private val config: ProxyConfig,
    private val stats: ProxyStats,
    private val wsPool: WsPool? = null
) {
    private val bridgeScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    suspend fun handleConnection() = withContext(Dispatchers.IO) {
        stats.activeConnections.incrementAndGet()
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

            val handshakeHeader = ByteArray(TgConstants.HANDSHAKE_LEN)
            var bytesRead = 0
            while (bytesRead < TgConstants.HANDSHAKE_LEN) {
                val count = inputStream.read(handshakeHeader, bytesRead, TgConstants.HANDSHAKE_LEN - bytesRead)
                if (count < 0) return@withContext
                bytesRead += count
            }

            val handshakeResult = MTProtoCrypto.tryHandshake(handshakeHeader, config.secretBytes)
                ?: return@withContext

            val relayInit = MTProtoCrypto.generateRelayInit(handshakeResult.protoTag, handshakeResult.dcIdx)
            val protoInt = when {
                handshakeResult.protoTag.contentEquals(TgConstants.PROTO_TAG_ABRIDGED) -> TgConstants.PROTO_ABRIDGED_INT
                handshakeResult.protoTag.contentEquals(TgConstants.PROTO_TAG_INTERMEDIATE) -> TgConstants.PROTO_INTERMEDIATE_INT
                else -> TgConstants.PROTO_PADDED_INTERMEDIATE_INT
            }

            val msgSplitter = MsgSplitter(relayInit, protoInt)
            val domains = TgConstants.getWsDomains(handshakeResult.dcId, handshakeResult.isMedia)

            var wsConnected = false
            var wsClient: RawWebSocketClient? = null

            val cfDomain = config.getEffectiveCfDomain()

            // 1. Попытка подключения через Cloudflare Worker (Кастомный воркер пользователя или включенный дефолтный)
            if (cfDomain.isNotEmpty()) {
                val dcIpMap = if (config.isTestEnvironment) TgConstants.DC_TEST_IPS else TgConstants.DC_DEFAULT_IPS
                val dcIp = dcIpMap[handshakeResult.dcId] ?: "149.154.167.51"
                val wsUrl = "wss://$cfDomain/tcp?target=$dcIp:443&host=$dcIp&port=443"
                val client = RawWebSocketClient(wsUrl)
                try {
                    AppLogger.i("TgWsBridge", "MTProto: Подключение к Cloudflare Worker ($cfDomain) для DC ${handshakeResult.dcId} ($dcIp:443)...")
                    client.connect()
                    if (client.send(relayInit)) {
                        wsClient = client
                        wsConnected = true
                        AppLogger.i("TgWsBridge", "MTProto: Успешное WSS туннелирование через Cloudflare Worker ($cfDomain) для DC ${handshakeResult.dcId}")
                    } else {
                        client.close()
                    }
                } catch (e: Exception) {
                    AppLogger.w("TgWsBridge", "MTProto: Ошибка подключения к Cloudflare Worker ($cfDomain): ${e.message}")
                    client.close()
                }
            }

            // 2. Попытка использования прогретого пула WsPool (если CF воркер не был использован или отвалился)
            if (!wsConnected && wsPool != null) {
                val pooledClient = wsPool.get(handshakeResult.dcId, handshakeResult.isMedia, config.isTestEnvironment)
                if (pooledClient != null) {
                    if (pooledClient.send(relayInit)) {
                        wsClient = pooledClient
                        wsConnected = true
                    } else {
                        pooledClient.close()
                    }
                }
            }

            // 3. Фолбек на прямые домены Telegram WebSockets
            if (!wsConnected) {
                for (domain in domains) {
                    val wsUrl = "wss://$domain${if (config.isTestEnvironment) TgConstants.WS_PATH_TEST else TgConstants.WS_PATH}"
                    val client = RawWebSocketClient(wsUrl)
                    try {
                        client.connect()
                        if (client.send(relayInit)) {
                            wsClient = client
                            wsConnected = true
                            wsPool?.triggerRefill(PoolKey(handshakeResult.dcId, handshakeResult.isMedia), config.isTestEnvironment)
                            break
                        } else {
                            client.close()
                        }
                    } catch (_: Exception) {
                        client.close()
                    }
                }
            }

            if (!wsConnected || wsClient == null) {
                // Direct TCP Fallback (Last-resort single attempt with 2.5s fast-fail timeout)
                if (config.fallbackDirectTcp) {
                    val dcIp = (if (config.isTestEnvironment) TgConstants.DC_TEST_IPS else TgConstants.DC_DEFAULT_IPS)[handshakeResult.dcId]
                    if (dcIp != null) {
                        try {
                            Socket().use { directSocket ->
                                if (config.tcpNoDelay) {
                                    try { directSocket.tcpNoDelay = true } catch (_: Exception) {}
                                }
                                try {
                                    directSocket.receiveBufferSize = config.bufferSizeBytes
                                    directSocket.sendBufferSize = config.bufferSizeBytes
                                    directSocket.soTimeout = 15000
                                } catch (_: Exception) {}

                                // Fast-fail connect timeout: 2500ms (prevent hanging when Telegram DC is blocked by DPI)
                                directSocket.connect(java.net.InetSocketAddress(dcIp, 443), 2500)

                                directSocket.getOutputStream().write(relayInit)
                                directSocket.getOutputStream().flush()

                                val bufLen = config.bufferSizeBytes.coerceAtLeast(16384)
                                val fallbackJob = bridgeScope.launch {
                                    val buf = ByteArray(bufLen)
                                    val directIn = directSocket.getInputStream()
                                    try {
                                        while (isActive) {
                                            val len = directIn.read(buf)
                                            if (len < 0) break
                                            outputStream.write(buf, 0, len)
                                            outputStream.flush()
                                            stats.addSent(len.toLong())
                                        }
                                    } catch (_: Throwable) {}
                                    finally {
                                        try { directSocket.close() } catch (_: Throwable) {}
                                    }
                                }
                                try {
                                    val buf = ByteArray(bufLen)
                                    while (isActive && !clientSocket.isClosed) {
                                        val len = inputStream.read(buf)
                                        if (len < 0) break
                                        directSocket.getOutputStream().write(buf, 0, len)
                                        directSocket.getOutputStream().flush()
                                        stats.addReceived(len.toLong())
                                    }
                                } catch (_: Throwable) {}
                                finally {
                                    fallbackJob.cancel()
                                    try { directSocket.close() } catch (_: Throwable) {}
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                return@withContext
            }

            val activeWs = wsClient

            // Coroutine 1: WS -> Client Socket (Download / Входящий трафик)
            val wsToClientJob = bridgeScope.launch {
                try {
                    activeWs.messageChannel.consumeEach { frame ->
                        outputStream.write(frame)
                        outputStream.flush()
                        stats.addReceived(frame.size.toLong())
                    }
                } catch (_: Exception) {
                } finally {
                    clientSocket.close()
                }
            }

            // Coroutine 2: Close monitor
            bridgeScope.launch {
                activeWs.closeChannel.receive()
                clientSocket.close()
            }

            // Main loop: Client Socket -> WS (Upload / Исходящий трафик)
            val bufLen = config.bufferSizeBytes.coerceAtLeast(16384)
            val readBuffer = ByteArray(bufLen)
            while (isActive && !clientSocket.isClosed) {
                val readCount = inputStream.read(readBuffer)
                if (readCount < 0) break
                stats.addSent(readCount.toLong())

                val packets = msgSplitter.split(readBuffer, 0, readCount)
                for (packet in packets) {
                    if (!activeWs.send(packet)) {
                        break
                    }
                }
            }

            activeWs.close()
            wsToClientJob.cancel()
        } catch (_: Exception) {
        } finally {
            stats.activeConnections.decrementAndGet()
            try { clientSocket.close() } catch (_: Exception) {}
            bridgeScope.cancel()
        }
    }
}
