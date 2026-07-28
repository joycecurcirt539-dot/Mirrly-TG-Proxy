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
    private val bridgeScope = CoroutineScope(Dispatchers.IO + Job())

    suspend fun handleConnection() = withContext(Dispatchers.IO) {
        stats.activeConnections.incrementAndGet()
        val inputStream: InputStream = clientSocket.getInputStream()
        val outputStream: OutputStream = clientSocket.getOutputStream()

        try {
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

            // 1. Try pre-warmed connection pool hit
            val pooledClient = wsPool?.get(handshakeResult.dcId, handshakeResult.isMedia, config.isTestEnvironment)
            if (pooledClient != null) {
                if (pooledClient.send(relayInit)) {
                    wsClient = pooledClient
                    wsConnected = true
                } else {
                    pooledClient.close()
                }
            }

            // 2. If pool missed, establish on-demand connection
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
                // Direct TCP Fallback
                if (config.fallbackDirectTcp) {
                    val dcIp = (if (config.isTestEnvironment) TgConstants.DC_TEST_IPS else TgConstants.DC_DEFAULT_IPS)[handshakeResult.dcId]
                    if (dcIp != null) {
                        try {
                            Socket(dcIp, 443).use { directSocket ->
                                directSocket.getOutputStream().write(relayInit)
                                directSocket.getOutputStream().flush()
                                val fallbackJob = bridgeScope.launch {
                                    val buf = ByteArray(16384)
                                    val directIn = directSocket.getInputStream()
                                    try {
                                        while (isActive) {
                                            val len = directIn.read(buf)
                                            if (len < 0) break
                                            outputStream.write(buf, 0, len)
                                            outputStream.flush()
                                            stats.addSent(len.toLong())
                                        }
                                    } catch (_: Exception) {}
                                    finally {
                                        try { directSocket.close() } catch (_: Exception) {}
                                    }
                                }
                                try {
                                    val buf = ByteArray(16384)
                                    while (isActive && !clientSocket.isClosed) {
                                        val len = inputStream.read(buf)
                                        if (len < 0) break
                                        directSocket.getOutputStream().write(buf, 0, len)
                                        directSocket.getOutputStream().flush()
                                        stats.addReceived(len.toLong())
                                    }
                                } finally {
                                    fallbackJob.cancel()
                                    try { directSocket.close() } catch (_: Exception) {}
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                return@withContext
            }

            val activeWs = wsClient

            // Coroutine 1: WS -> Client Socket
            val wsToClientJob = bridgeScope.launch {
                try {
                    activeWs.messageChannel.consumeEach { frame ->
                        outputStream.write(frame)
                        outputStream.flush()
                        stats.addSent(frame.size.toLong())
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

            // Main loop: Client Socket -> WS
            val readBuffer = ByteArray(16384)
            while (isActive && !clientSocket.isClosed) {
                val readCount = inputStream.read(readBuffer)
                if (readCount < 0) break
                val chunk = readBuffer.copyOfRange(0, readCount)
                stats.addReceived(readCount.toLong())

                val packets = msgSplitter.split(chunk)
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
