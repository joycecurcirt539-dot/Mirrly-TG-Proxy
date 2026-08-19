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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

            val prefix = ByteArray(5)
            var prefixRead = 0
            while (prefixRead < 5) {
                val count = inputStream.read(prefix, prefixRead, 5 - prefixRead)
                if (count < 0) return@withContext
                prefixRead += count
            }

            val isFakeTls = FakeTls.isTlsHandshake(prefix)
            val handshakeHeader = ByteArray(TgConstants.HANDSHAKE_LEN)
            var initialExtraPayload: ByteArray? = null

            if (isFakeTls) {
                AppLogger.i("TgWsBridge", "Fake-TLS Handshake обнаружен от клиента")
                val appData = FakeTls.handleFakeTlsHandshake(inputStream, outputStream, prefix)
                    ?: return@withContext
                if (appData.size < TgConstants.HANDSHAKE_LEN) {
                    AppLogger.w("TgWsBridge", "Fake-TLS payload < 64 байт")
                    return@withContext
                }
                System.arraycopy(appData, 0, handshakeHeader, 0, TgConstants.HANDSHAKE_LEN)
                if (appData.size > TgConstants.HANDSHAKE_LEN) {
                    initialExtraPayload = appData.copyOfRange(TgConstants.HANDSHAKE_LEN, appData.size)
                }
            } else {
                System.arraycopy(prefix, 0, handshakeHeader, 0, 5)
                var bytesRead = 5
                while (bytesRead < TgConstants.HANDSHAKE_LEN) {
                    val count = inputStream.read(handshakeHeader, bytesRead, TgConstants.HANDSHAKE_LEN - bytesRead)
                    if (count < 0) return@withContext
                    bytesRead += count
                }
            }

            val handshakeResult = MTProtoCrypto.tryHandshake(handshakeHeader, config.secretBytes)
                ?: return@withContext

            val relayInit = MTProtoCrypto.generateRelayInit(handshakeResult.protoTag, handshakeResult.dcIdx)
            val protoInt = when {
                handshakeResult.protoTag.contentEquals(TgConstants.PROTO_TAG_ABRIDGED) -> TgConstants.PROTO_ABRIDGED_INT
                handshakeResult.protoTag.contentEquals(TgConstants.PROTO_TAG_INTERMEDIATE) -> TgConstants.PROTO_INTERMEDIATE_INT
                else -> TgConstants.PROTO_PADDED_INTERMEDIATE_INT
            }

            // ── Upload ciphers (client → WS): client_dec + upstream_enc ──
            // client_dec: decrypt data arriving from the Telegram client
            val clientDecPrekey = handshakeHeader.copyOfRange(TgConstants.SKIP_LEN, TgConstants.SKIP_LEN + TgConstants.PREKEY_LEN)
            val clientDecIv = handshakeHeader.copyOfRange(TgConstants.SKIP_LEN + TgConstants.PREKEY_LEN, TgConstants.SKIP_LEN + TgConstants.PREKEY_LEN + TgConstants.IV_LEN)
            val clientDecKey = MTProtoCrypto.sha256(clientDecPrekey + config.secretBytes)
            val clientDec = AesCtrCipher(clientDecKey, clientDecIv)
            clientDec.update(ByteArray(64)) // discard 64 bytes (handshake header)

            // upstream_enc: encrypt data going to the Telegram WS server
            val upstreamEncKey = relayInit.copyOfRange(TgConstants.SKIP_LEN, TgConstants.SKIP_LEN + TgConstants.PREKEY_LEN)
            val upstreamEncIv = relayInit.copyOfRange(TgConstants.SKIP_LEN + TgConstants.PREKEY_LEN, TgConstants.SKIP_LEN + TgConstants.PREKEY_LEN + TgConstants.IV_LEN)
            val upstreamEnc = AesCtrCipher(upstreamEncKey, upstreamEncIv)
            upstreamEnc.update(ByteArray(64)) // discard 64 bytes (relay_init header)

            val msgSplitter = MsgSplitter(clientDec, upstreamEnc, protoInt)

            // ── Download ciphers (WS → client): upstream_dec + client_enc ──
            // upstream_dec: decrypt data arriving from the Telegram WS server (reversed relay_init key/iv)
            val upstreamRev48 = relayInit.copyOfRange(TgConstants.SKIP_LEN, TgConstants.SKIP_LEN + TgConstants.PREKEY_LEN + TgConstants.IV_LEN).reversedArray()
            val upstreamDecKey = upstreamRev48.copyOfRange(0, TgConstants.PREKEY_LEN)
            val upstreamDecIv = upstreamRev48.copyOfRange(TgConstants.PREKEY_LEN, TgConstants.PREKEY_LEN + TgConstants.IV_LEN)
            val upstreamDec = AesCtrCipher(upstreamDecKey, upstreamDecIv)
            upstreamDec.update(ByteArray(64)) // discard 64 bytes

            // client_enc: encrypt data going back to the Telegram client (reversed handshake key/iv)
            val clientRev48 = handshakeHeader.copyOfRange(TgConstants.SKIP_LEN, TgConstants.SKIP_LEN + TgConstants.PREKEY_LEN + TgConstants.IV_LEN).reversedArray()
            val clientEncPrekey = clientRev48.copyOfRange(0, TgConstants.PREKEY_LEN)
            val clientEncIv = clientRev48.copyOfRange(TgConstants.PREKEY_LEN, TgConstants.PREKEY_LEN + TgConstants.IV_LEN)
            val clientEncKey = MTProtoCrypto.sha256(clientEncPrekey + config.secretBytes)
            val clientEnc = AesCtrCipher(clientEncKey, clientEncIv)
            clientEnc.update(ByteArray(64)) // discard 64 bytes

            var wsConnected = false
            var wsClient: RawWebSocketClient? = null

            // 1. Попытка использования прогретого пула WsPool (/apiws)
            if (wsPool != null) {
                val pooledClient = wsPool.get(handshakeResult.dcId, handshakeResult.isMedia, config.isTestEnvironment)
                if (pooledClient != null) {
                    if (pooledClient.isAlive && pooledClient.send(relayInit)) {
                        wsClient = pooledClient
                        wsConnected = true
                    } else {
                        pooledClient.close()
                    }
                }
            }

            // 3. Быстрая параллельная гонка (Happy Eyeballs, 150ms stagger) по доверенным доменам и шлюзам Telegram
            if (!wsConnected) {
                val candidateDomains = TgConstants.getWsDomains(handshakeResult.dcId, handshakeResult.isMedia)
                val wsPath = if (config.isTestEnvironment) TgConstants.WS_PATH_TEST else TgConstants.WS_PATH

                val raceChannel = Channel<Pair<String, RawWebSocketClient>>(1)
                val jobs = mutableListOf<Job>()

                val raceJob = bridgeScope.launch {
                    for (domain in candidateDomains) {
                        val job = bridgeScope.launch {
                            val wsUrl = "wss://$domain$wsPath"
                            val client = RawWebSocketClient(wsUrl)
                            try {
                                val connected = client.connectAndAwait(2000)
                                if (connected && client.isAlive && client.send(relayInit)) {
                                    if (raceChannel.trySend(Pair(domain, client)).isSuccess) {
                                        return@launch
                                    }
                                }
                                client.close()
                            } catch (_: Exception) {
                                client.close()
                            }
                        }
                        jobs.add(job)
                        delay(150)
                    }
                }

                try {
                    withTimeoutOrNull(5000) {
                        val winner = raceChannel.receive()
                        wsClient = winner.second
                        wsConnected = true
                        AppLogger.i("TgWsBridge", "MTProto: Успешное быстрое WSS подключение через ${winner.first} для DC ${handshakeResult.dcId}")
                        TgConstants.promoteDomain(winner.first)
                        wsPool?.triggerRefill(PoolKey(handshakeResult.dcId, handshakeResult.isMedia), config.isTestEnvironment)
                    }
                } catch (_: Exception) {
                } finally {
                    raceJob.cancel()
                    jobs.forEach { it.cancel() }
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
                                            if (isFakeTls) {
                                                FakeTls.writeTlsAppData(outputStream, buf, 0, len)
                                            } else {
                                                outputStream.write(buf, 0, len)
                                                outputStream.flush()
                                            }
                                            stats.addReceived(len.toLong())
                                        }
                                    } catch (_: Throwable) {}
                                    finally {
                                        try { directSocket.close() } catch (_: Throwable) {}
                                    }
                                }
                                try {
                                    if (initialExtraPayload != null && initialExtraPayload.isNotEmpty()) {
                                        directSocket.getOutputStream().write(initialExtraPayload)
                                        directSocket.getOutputStream().flush()
                                        stats.addSent(initialExtraPayload.size.toLong())
                                    }
                                    val buf = ByteArray(bufLen)
                                    while (isActive && !clientSocket.isClosed) {
                                        val dataToSend: ByteArray
                                        val offset: Int
                                        val len: Int
                                        if (isFakeTls) {
                                            val frame = FakeTls.readTlsAppData(inputStream) ?: break
                                            if (frame.isEmpty()) continue
                                            dataToSend = frame
                                            offset = 0
                                            len = frame.size
                                        } else {
                                            val count = inputStream.read(buf)
                                            if (count < 0) break
                                            dataToSend = buf
                                            offset = 0
                                            len = count
                                        }
                                        directSocket.getOutputStream().write(dataToSend, offset, len)
                                        directSocket.getOutputStream().flush()
                                        stats.addSent(len.toLong())
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

            val activeWs = wsClient ?: return@withContext

            // Coroutine 1: WS -> Client Socket (Download / Входящий трафик)
            // Re-encrypt: upstream_dec(WS frame) → plaintext → client_enc(plaintext) → client socket
            val wsToClientJob = bridgeScope.launch {
                try {
                    activeWs.messageChannel.consumeEach { frame ->
                        // Re-encrypt: upstream_dec → client_enc
                        val decrypted = upstreamDec.update(frame)
                        val reEncrypted = clientEnc.update(decrypted)
                        if (isFakeTls) {
                            FakeTls.writeTlsAppData(outputStream, reEncrypted)
                        } else {
                            outputStream.write(reEncrypted)
                            outputStream.flush()
                        }
                        stats.addReceived(frame.size.toLong())
                    }
                } catch (_: Exception) {
                } finally {
                    try { clientSocket.close() } catch (_: Exception) {}
                }
            }


            // Coroutine 2: Close monitor
            bridgeScope.launch {
                try {
                    activeWs.closeChannel.receive()
                } catch (_: Throwable) {
                } finally {
                    try { clientSocket.close() } catch (_: Exception) {}
                }
            }

            // Main loop: Client Socket -> WS (Upload / Исходящий трафик)
            if (initialExtraPayload != null && initialExtraPayload.isNotEmpty()) {
                stats.addSent(initialExtraPayload.size.toLong())
                val packets = msgSplitter.split(initialExtraPayload, 0, initialExtraPayload.size)
                for (packet in packets) {
                    if (!activeWs.send(packet)) break
                }
            }

            val bufLen = config.bufferSizeBytes.coerceAtLeast(16384)
            val readBuffer = ByteArray(bufLen)
            while (isActive && !clientSocket.isClosed) {
                val dataToProcess: ByteArray
                val readOffset: Int
                val readCount: Int

                if (isFakeTls) {
                    val frame = FakeTls.readTlsAppData(inputStream) ?: break
                    if (frame.isEmpty()) continue
                    dataToProcess = frame
                    readOffset = 0
                    readCount = frame.size
                } else {
                    val count = inputStream.read(readBuffer)
                    if (count < 0) break
                    dataToProcess = readBuffer
                    readOffset = 0
                    readCount = count
                }

                stats.addSent(readCount.toLong())

                val packets = msgSplitter.split(dataToProcess, readOffset, readCount)
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
