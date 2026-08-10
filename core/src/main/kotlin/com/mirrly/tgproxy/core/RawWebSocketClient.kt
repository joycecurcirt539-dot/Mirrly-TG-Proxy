package com.mirrly.tgproxy.core

import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
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
    val messageChannel = Channel<ByteArray>(256)
    val closeChannel = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var isClosed: Boolean = false
        private set

    val isAlive: Boolean
        get() = isConnected && !isClosed && !closeChannel.isClosedForReceive && closeChannel.isEmpty

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            isClosed = false
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            messageChannel.trySend(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messageChannel.trySend(text.toByteArray(Charsets.UTF_8))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            isClosed = true
            isConnected = false
            webSocket.close(1000, "Normal closure")
            closeChannel.trySend(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isClosed = true
            isConnected = false
            closeChannel.trySend(Unit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isClosed = true
            isConnected = false
            closeChannel.trySend(Unit)
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

    fun send(data: ByteArray): Boolean {
        if (isClosed) return false
        return webSocket?.send(ByteString.of(*data)) ?: false
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
