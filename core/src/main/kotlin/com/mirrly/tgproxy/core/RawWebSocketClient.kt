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
    val messageChannel = Channel<ByteArray>(Channel.UNLIMITED)
    val closeChannel = Channel<Unit>(Channel.CONFLATED)

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Connected
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            messageChannel.trySend(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messageChannel.trySend(text.toByteArray(Charsets.UTF_8))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, "Normal closure")
            closeChannel.trySend(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closeChannel.trySend(Unit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
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
        return webSocket?.send(ByteString.of(*data)) ?: false
    }

    fun close() {
        try {
            webSocket?.close(1000, "Normal closure")
        } catch (_: Exception) {}
        closeChannel.trySend(Unit)
    }

    companion object {
        val okHttpClient: OkHttpClient by lazy {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL").apply {
                init(null, trustAllCerts, SecureRandom())
            }

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive for WS
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
