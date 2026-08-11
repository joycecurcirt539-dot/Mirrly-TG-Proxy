package com.mirrly.tgproxy.core

import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class RawWebSocketClient(
    private val url: String,
    private val hostHeader: String? = null
) {
    private var webSocket: WebSocket? = null
    val messageChannel = Channel<ByteArray>(Channel.UNLIMITED)
    val closeChannel = Channel<Unit>(Channel.CONFLATED)
    val openChannel = Channel<Boolean>(Channel.CONFLATED)

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var isClosed: Boolean = false
        private set

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isAlive: Boolean
        get() = isConnected && !isClosed && !closeChannel.isClosedForReceive && closeChannel.isEmpty

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            isClosed = false
            openChannel.trySend(true)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val result = messageChannel.trySend(bytes.toByteArray())
            if (!result.isSuccess) {
                AppLogger.w("RawWebSocketClient", "⚠️ messageChannel.trySend failed. Closing WS to prevent TCP stream corruption.")
                close()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val result = messageChannel.trySend(text.toByteArray(Charsets.UTF_8))
            if (!result.isSuccess) {
                AppLogger.w("RawWebSocketClient", "⚠️ messageChannel.trySend failed. Closing WS to prevent TCP stream corruption.")
                close()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            isClosed = true
            isConnected = false
            openChannel.trySend(false)
            webSocket.close(1000, "Normal closure")
            closeChannel.trySend(Unit)
            messageChannel.close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isClosed = true
            isConnected = false
            openChannel.trySend(false)
            closeChannel.trySend(Unit)
            messageChannel.close(t)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isClosed = true
            isConnected = false
            openChannel.trySend(false)
            closeChannel.trySend(Unit)
            messageChannel.close()
        }
    }

    fun connect() {
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Sec-WebSocket-Protocol", "binary")

        if (!hostHeader.isNullOrEmpty()) {
            requestBuilder.addHeader("Host", hostHeader)
        }

        webSocket = okHttpClient.newWebSocket(requestBuilder.build(), listener)
    }

    suspend fun connectAndAwait(timeoutMs: Long = 3000): Boolean {
        connect()
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            try {
                openChannel.receive()
            } catch (_: Exception) {
                false
            }
        } ?: false
    }

    fun send(data: ByteArray): Boolean {
        if (isClosed) return false
        return webSocket?.send(data.toByteString(0, data.size)) ?: false
    }

    fun send(bytes: ByteArray, offset: Int, byteCount: Int): Boolean {
        if (isClosed) return false
        return webSocket?.send(bytes.toByteString(offset, byteCount)) ?: false
    }

    fun close() {
        isClosed = true
        isConnected = false
        try {
            webSocket?.close(1000, "Normal closure")
        } catch (_: Exception) {}
        closeChannel.trySend(Unit)
        messageChannel.close()
    }

    companion object {
        val okHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive for WS
                .writeTimeout(8, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
