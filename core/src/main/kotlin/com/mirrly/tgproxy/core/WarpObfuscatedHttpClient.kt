/*
 * Mirrly TG Proxy - Copyright (C) 2026 R1Xern (Mirrly Dev)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.mirrly.tgproxy.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * Direct registration transport, independent of the WARP tunnel and HTTP proxies.
 * SSLEngine keeps ClientHello writes in Java so Android's native TLS socket cannot
 * bypass the fragmenting stream. Fragmentation is best effort, not a DPI guarantee.
 */
object WarpObfuscatedHttpClient {
    private const val TAG = "WarpObfHttp"
    private const val HOST = "api.cloudflareclient.com"
    const val DEFAULT_TIMEOUT_MS = 700
    const val DEFAULT_MAX_CANDIDATES = 3
    internal const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_TLS_BUFFER_BYTES = 256 * 1024
    private val candidates = listOf("104.16.24.84", "104.16.192.82", "104.16.132.229")
    private val tlsContext by lazy { SSLContext.getInstance("TLS").apply { init(null, null, null) } }
    private val deadlineExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "warp-registration-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    data class HttpResponse(val statusCode: Int, val statusMessage: String, val body: String, val connectedIp: String)

    fun execute(
        method: String,
        path: String,
        bodyJson: String? = null,
        authToken: String? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES
    ): HttpResponse {
        require(timeoutMs > 0) { "Registration timeout must be positive" }
        val request = encodeRequest(method, path, bodyJson, authToken)
        // Provider initialization is shared. Every candidate's connect, handshake,
        // writes, and complete response have one deadline, never a per-read budget.
        val context = tlsContext
        val budget = timeoutMs.coerceAtMost(DEFAULT_TIMEOUT_MS)
        return tryCandidates(maxCandidates) { ip ->
            AppLogger.d(TAG, "Прямой зонд Cloudflare API: $ip (бюджет ${budget}мс)")
            Socket().use { socket ->
                withinDeadline(budget, { socket.close() }) { deadline ->
                    socket.tcpNoDelay = true
                    socket.soTimeout = budget
                    socket.connect(InetSocketAddress(ip, 443), budget)
                    val engine = createEngine(context)
                    val connection = EngineConnection(socket, engine, deadline)
                    connection.handshake()
                    connection.write(request)
                    readHttpResponse(connection, ip, method)
                }
            }
        }
    }

    fun probeDirectApi(timeoutMs: Int = DEFAULT_TIMEOUT_MS): Boolean = try {
        execute("GET", "/v0a4471/reg", timeoutMs = timeoutMs, maxCandidates = 1).statusCode in 200..499
    } catch (_: IOException) {
        false
    }

    internal fun createEngine(context: SSLContext = tlsContext): SSLEngine = context.createSSLEngine(HOST, 443).apply {
        useClientMode = true
        sslParameters = sslParameters.apply {
            serverNames = listOf(SNIHostName(HOST))
            endpointIdentificationAlgorithm = "HTTPS"
        }
    }

    internal fun <T> tryCandidates(maxCandidates: Int, attempt: (String) -> T): T {
        var failure: IOException? = null
        for (ip in candidates.take(maxCandidates.coerceIn(1, DEFAULT_MAX_CANDIDATES))) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Registration interrupted")
            try {
                return attempt(ip)
            } catch (e: IOException) {
                if (Thread.currentThread().isInterrupted) throw e
                failure = e
                // Exception messages may contain server data. Keep credentials out of logs.
                AppLogger.w(TAG, "Прямой зонд $ip: ${e.javaClass.simpleName}")
            }
        }
        throw failure ?: IOException("No direct registration candidate")
    }

    internal class Deadline(timeoutMs: Int) {
        private val expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        fun check() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Registration interrupted")
            if (System.nanoTime() >= expiresAt) throw SocketTimeoutException("Registration candidate deadline exceeded")
        }
    }

    internal fun <T> withinDeadline(timeoutMs: Int, close: () -> Unit, action: (Deadline) -> T): T {
        val deadline = Deadline(timeoutMs)
        val watchdog = deadlineExecutor.schedule({ try { close() } catch (_: IOException) { } }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        try {
            val result = action(deadline)
            deadline.check()
            return result
        } finally {
            watchdog.cancel(false)
        }
    }

    private fun encodeRequest(method: String, path: String, body: String?, token: String?): ByteArray {
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) { "Unsupported registration method" }
        require(path.startsWith('/') && path.all { it.code in 0x21..0x7e } && '#' !in path) { "Invalid registration path" }
        require(token == null || token.all { it.code in 0x21..0x7e }) { "Invalid registration token" }
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        require(bytes.size <= MAX_RESPONSE_BYTES) { "Registration request is too large" }
        val headers = buildString {
            append("$method $path HTTP/1.1\r\nHost: $HOST\r\n")
            append("User-Agent: WARP for Android\r\nCF-Client-Version: a-6.35-4471\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\nAccept: application/json\r\n")
            append("Accept-Encoding: identity\r\nConnection: close\r\n")
            if (!token.isNullOrEmpty()) append("Authorization: Bearer $token\r\n")
            if (body != null || method in setOf("POST", "PUT", "PATCH")) append("Content-Length: ${bytes.size}\r\n")
            append("\r\n")
        }
        return headers.toByteArray(Charsets.US_ASCII) + bytes
    }

    private class EngineConnection(socket: Socket, private val engine: SSLEngine, private val deadline: Deadline) : InputStream() {
        private val input = socket.getInputStream()
        private val output = TlsFragmentingOutputStream(socket.getOutputStream())
        private var encrypted = ByteBuffer.allocate(engine.session.packetBufferSize).apply { flip() }
        private var plaintext = ByteBuffer.allocate(engine.session.applicationBufferSize).apply { flip() }
        private var outbound = ByteBuffer.allocate(engine.session.packetBufferSize)
        private val empty = ByteBuffer.allocate(0)
        private var closed = false

        fun handshake() {
            engine.beginHandshake()
            finishHandshake()
            if (closed) throw EOFException("TLS peer closed during handshake")
        }

        private fun finishHandshake() {
            while (!closed) {
                deadline.check()
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        var task = engine.delegatedTask ?: throw SSLException("TLS delegated task missing")
                        while (true) {
                            deadline.check()
                            task.run()
                            task = engine.delegatedTask ?: break
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(empty)
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> unwrap()
                    SSLEngineResult.HandshakeStatus.FINISHED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> return
                    else -> unwrap() // NEED_UNWRAP_AGAIN on providers that expose it.
                }
            }
        }

        fun write(bytes: ByteArray) {
            val source = ByteBuffer.wrap(bytes)
            while (source.hasRemaining()) {
                deadline.check()
                finishHandshake()
                if (closed) throw EOFException("TLS peer closed before request")
                wrap(source)
            }
        }

        private fun wrap(source: ByteBuffer) {
            while (true) {
                deadline.check()
                outbound.clear()
                val result = engine.wrap(source, outbound)
                when (result.status) {
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> { outbound = grow(outbound.apply { flip() }); continue }
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> throw SSLException("Unexpected TLS wrap underflow")
                    SSLEngineResult.Status.CLOSED -> throw EOFException("TLS outbound closed")
                    SSLEngineResult.Status.OK -> Unit
                }
                outbound.flip()
                if (outbound.hasRemaining()) {
                    output.write(outbound.array(), outbound.position(), outbound.remaining())
                    output.flush()
                }
                if (result.bytesConsumed() == 0 && result.bytesProduced() == 0 && result.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING && source.hasRemaining()) {
                    throw SSLException("TLS wrap made no progress")
                }
                return
            }
        }

        private fun unwrap() {
            while (true) {
                deadline.check()
                plaintext.compact()
                val result = engine.unwrap(encrypted, plaintext)
                plaintext.flip()
                when (result.status) {
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> plaintext = grow(plaintext)
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> readEncrypted()
                    SSLEngineResult.Status.CLOSED -> { closed = true; return }
                    SSLEngineResult.Status.OK -> {
                        if (result.bytesConsumed() == 0 && result.bytesProduced() == 0 && result.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                            throw SSLException("TLS unwrap made no progress")
                        }
                        return
                    }
                }
            }
        }

        private fun readEncrypted() {
            if (encrypted.remaining() == encrypted.capacity()) encrypted = grow(encrypted)
            encrypted.compact()
            deadline.check()
            val count = input.read(encrypted.array(), encrypted.position(), encrypted.remaining())
            if (count == -1) throw EOFException("Truncated TLS response")
            encrypted.position(encrypted.position() + count)
            encrypted.flip()
        }

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            while (!plaintext.hasRemaining()) {
                deadline.check()
                if (closed) return -1
                finishHandshake()
                if (!plaintext.hasRemaining() && !closed) unwrap()
            }
            val count = minOf(length, plaintext.remaining())
            plaintext.get(bytes, offset, count)
            return count
        }

        private fun grow(buffer: ByteBuffer): ByteBuffer {
            if (buffer.capacity() >= MAX_TLS_BUFFER_BYTES) throw SSLException("TLS record exceeds buffer limit")
            return ByteBuffer.allocate((buffer.capacity() * 2).coerceAtMost(MAX_TLS_BUFFER_BYTES)).apply { put(buffer); flip() }
        }
    }

    /** Byte-oriented HTTP framing: never fabricate success from malformed or truncated data. */
    internal fun readHttpResponse(input: InputStream, ip: String, method: String = "GET"): HttpResponse {
        var headerBytes = 0
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next == -1) throw EOFException("Truncated HTTP headers")
                if (++headerBytes > MAX_HEADER_BYTES) throw ProtocolException("HTTP headers exceed limit")
                if (next == 13) {
                    if (input.read() != 10) throw ProtocolException("Invalid HTTP line ending")
                    if (++headerBytes > MAX_HEADER_BYTES) throw ProtocolException("HTTP headers exceed limit")
                    return bytes.toString(Charsets.ISO_8859_1.name())
                }
                if (next == 10 || next == 0) throw ProtocolException("Invalid HTTP header character")
                bytes.write(next)
            }
        }
        var interim = 0
        while (true) {
            val status = Regex("HTTP/1\\.[01] ([1-5][0-9]{2})(?: (.*))?").matchEntire(line())
                ?: throw ProtocolException("Invalid HTTP status line")
            val code = status.groupValues[1].toInt()
            val headers = linkedMapOf<String, MutableList<String>>()
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                val colon = header.indexOf(':')
                if (colon <= 0 || !header.substring(0, colon).all { it.isLetterOrDigit() && it.code < 128 || it in "!#$%&'*+-.^_`|~" }) {
                    throw ProtocolException("Invalid HTTP header")
                }
                headers.getOrPut(header.substring(0, colon).lowercase(Locale.ROOT)) { mutableListOf() }.add(header.substring(colon + 1).trim())
            }
            if (code < 200) {
                if (code == 101 || ++interim > 8) throw ProtocolException("Unsupported HTTP interim response")
                continue
            }
            if (method == "HEAD" || code == 204 || code == 304) return HttpResponse(code, status.groupValues[2], "", ip)
            val encoding = headers["transfer-encoding"]?.joinToString(",")?.lowercase(Locale.ROOT)
            val lengths = headers["content-length"]?.flatMap { it.split(',') }?.map {
                val value = it.trim()
                if (value.isEmpty() || !value.all { char -> char in '0'..'9' }) throw ProtocolException("Invalid HTTP content length")
                value.toLongOrNull() ?: throw ProtocolException("Invalid HTTP content length")
            }
            if (lengths != null && lengths.distinct().size != 1) throw ProtocolException("Conflicting HTTP content lengths")
            if (encoding != null && (encoding != "chunked" || lengths != null)) throw ProtocolException("Ambiguous or unsupported HTTP framing")
            val body = ByteArrayOutputStream()
            fun copy(count: Long) {
                if (count > MAX_RESPONSE_BYTES - body.size()) throw ProtocolException("HTTP response exceeds limit")
                var remaining = count
                val buffer = ByteArray(8192)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) throw EOFException("Truncated HTTP body")
                    body.write(buffer, 0, read)
                    remaining -= read
                }
            }
            if (encoding == "chunked") {
                while (true) {
                    val sizeText = line().substringBefore(';')
                    if (sizeText.isEmpty() || !sizeText.all { it in "0123456789abcdefABCDEF" }) throw ProtocolException("Invalid HTTP chunk size")
                    val size = sizeText.toLongOrNull(16) ?: throw ProtocolException("Invalid HTTP chunk size")
                    if (size == 0L) {
                        while (true) {
                            val trailer = line()
                            if (trailer.isEmpty()) break
                            if (trailer.indexOf(':') <= 0) throw ProtocolException("Invalid HTTP trailer")
                        }
                        break
                    }
                    copy(size)
                    if (line().isNotEmpty()) throw ProtocolException("Invalid HTTP chunk ending")
                }
            } else if (lengths != null) {
                copy(lengths.first())
            } else {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count == 0) throw IOException("HTTP body read made no progress")
                    if (body.size() + count > MAX_RESPONSE_BYTES) throw ProtocolException("HTTP response exceeds limit")
                    body.write(buffer, 0, count)
                }
            }
            return HttpResponse(code, status.groupValues[2], body.toString(Charsets.UTF_8.name()), ip)
        }
    }
}
