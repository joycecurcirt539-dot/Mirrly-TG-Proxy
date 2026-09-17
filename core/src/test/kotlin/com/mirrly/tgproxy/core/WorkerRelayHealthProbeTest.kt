package com.mirrly.tgproxy.core

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkerRelayHealthProbeTest {
    private fun check(response: MockResponse, expected: WorkerStatus, timeout: Long = 1500) = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response)
            val client = OkHttpClient.Builder().followRedirects(false).build()
            try {
                val result = WorkerRelayHealthProbe(client).probe(
                    Request.Builder().url(server.url("/tcp-v2?target=149.154.167.50:443")).build(), timeout)
                assertEquals(expected, result.first)
                assertEquals(1, server.requestCount, "No root fallback")
                assertTrue(server.takeRequest().path!!.startsWith("/tcp-v2?"))
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun frame(vararg bytes: Int) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(bytes.map { it.toByte() }.toByteArray().toByteString())
        }
    })

    @Test fun `HTTP errors cannot establish health`() {
        for (code in listOf(403, 500, 503, 404, 302)) {
            check(MockResponse().setResponseCode(code), WorkerStatus.ERROR_UNREACHABLE)
        }
    }
    @Test fun `static site and branded wrong script cannot establish health`() {
        for (body in listOf("<html>OK</html>", "Mirrly TG Proxy Dedicated Worker",
            """{"description":"TCP over WebSocket Relay-Ready ACK"}""")) {
            check(MockResponse().setBody(body), WorkerStatus.ERROR_UNREACHABLE)
        }
    }
    @Test fun `429 remains rate limited`() =
        check(MockResponse().setResponseCode(429), WorkerStatus.RATE_LIMITED_429)

    @Test fun `exact v2 ACK establishes health`() = check(frame(0x56, 2, 0, 0), WorkerStatus.ONLINE)

    @Test fun `invalid version status reserved byte and frame length fail`() {
        for (bytes in listOf(intArrayOf(0x56, 1, 0, 0), intArrayOf(0x56, 2, 1, 0),
            intArrayOf(0x56, 2, 0, 1), intArrayOf(0x56, 2, 0), intArrayOf(0x56, 2, 0, 0, 0))) {
            check(frame(*bytes), WorkerStatus.ERROR_UNREACHABLE)
        }
    }
    @Test fun `text ACK fails`() = check(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("56020000") }
    }), WorkerStatus.ERROR_UNREACHABLE)

    @Test fun `close before ACK fails`() = check(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1000, "done") }
    }), WorkerStatus.ERROR_UNREACHABLE)

    @Test fun `upgrade without ACK times out`() = check(
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}), WorkerStatus.ERROR_UNREACHABLE, 250)

    @Test fun `caller cancellation propagates`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            val client = OkHttpClient()
            try {
                val task = async { WorkerRelayHealthProbe(client).probe(
                    Request.Builder().url(server.url("/tcp-v2")).build(), 10000) }
                withContext(Dispatchers.IO) { server.takeRequest() }
                task.cancelAndJoin()
                assertTrue(task.isCancelled)
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
