package com.mirrly.tgproxy.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** HTTP reachability (including branded JSON) cannot establish SOCKS relay health. */
class WorkerRelayHealthProbe(private val client: OkHttpClient) {
    suspend fun probe(request: Request, timeoutMs: Long): Pair<WorkerStatus, Long?> {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val start = System.nanoTime()
                val finished = AtomicBoolean(false)
                fun finish(socket: WebSocket, status: WorkerStatus) {
                    if (finished.compareAndSet(false, true)) {
                        socket.cancel()
                        if (cont.isActive) {
                            cont.resume(status to (System.nanoTime() - start) / 1_000_000)
                        }
                    }
                }
                val socket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        // The four-byte control frame confirms v2 support AND upstream readiness.
                        val valid = bytes.size == 4 && bytes[0] == 0x56.toByte() &&
                            bytes[1] == 0x02.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0.toByte()
                        finish(webSocket, if (valid) WorkerStatus.ONLINE else WorkerStatus.ERROR_UNREACHABLE)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) =
                        finish(webSocket, WorkerStatus.ERROR_UNREACHABLE)

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) =
                        finish(webSocket, WorkerStatus.ERROR_UNREACHABLE)

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                        finish(webSocket, WorkerStatus.ERROR_UNREACHABLE)

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        finish(webSocket, if (response?.code == 429) WorkerStatus.RATE_LIMITED_429
                            else WorkerStatus.ERROR_UNREACHABLE)
                    }
                })
                cont.invokeOnCancellation {
                    finished.set(true)
                    socket.cancel()
                }
            }
        } ?: (WorkerStatus.ERROR_UNREACHABLE to null)
    }
}
