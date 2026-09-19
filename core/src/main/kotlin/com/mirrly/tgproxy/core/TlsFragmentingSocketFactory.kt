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

import okhttp3.Dns
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel
import javax.net.SocketFactory

/**
 * SocketFactory, фрагментирующий TLS ClientHello на уровне TCP-пакетов.
 * Обходит блокировку SNI (api.cloudflareclient.com) со стороны ТСПУ / РКН в России,
 * разделяя первый TLS Handshake пакет на несколько мелких TCP-сегментов с микрозадержкой.
 */
class TlsFragmentingSocketFactory : SocketFactory() {
    private val defaultFactory: SocketFactory = getDefault()

    override fun createSocket(): Socket {
        val s = defaultFactory.createSocket()
        s.tcpNoDelay = true
        return TlsFragmentingSocket(s)
    }

    override fun createSocket(host: String?, port: Int): Socket {
        val s = defaultFactory.createSocket(host, port)
        s.tcpNoDelay = true
        return TlsFragmentingSocket(s)
    }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        val s = defaultFactory.createSocket(host, port, localHost, localPort)
        s.tcpNoDelay = true
        return TlsFragmentingSocket(s)
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket {
        val s = defaultFactory.createSocket(host, port)
        s.tcpNoDelay = true
        return TlsFragmentingSocket(s)
    }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
        val s = defaultFactory.createSocket(address, port, localAddress, localPort)
        s.tcpNoDelay = true
        return TlsFragmentingSocket(s)
    }
}

class TlsFragmentingSocket(private val delegate: Socket) : Socket() {
    private var wrappedOutputStream: OutputStream? = null

    override fun connect(endpoint: SocketAddress?) {
        if (!VpnSocketProtector.protect(delegate)) {
            throw java.io.IOException("Socket protection failed; aborted to prevent VPN routing loop")
        }
        delegate.connect(endpoint)
        delegate.tcpNoDelay = true
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (!VpnSocketProtector.protect(delegate)) {
            throw java.io.IOException("Socket protection failed; aborted to prevent VPN routing loop")
        }
        delegate.connect(endpoint, timeout)
        delegate.tcpNoDelay = true
    }

    override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)
    override fun getInetAddress(): InetAddress? = delegate.inetAddress
    override fun getLocalAddress(): InetAddress = delegate.localAddress
    override fun getPort(): Int = delegate.port
    override fun getLocalPort(): Int = delegate.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
    override fun getChannel(): SocketChannel? = delegate.channel
    override fun getInputStream(): InputStream = delegate.inputStream

    override fun getOutputStream(): OutputStream {
        if (wrappedOutputStream == null) {
            wrappedOutputStream = TlsFragmentingOutputStream(delegate.outputStream)
        }
        return wrappedOutputStream!!
    }

    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) { delegate.setSoLinger(on, linger) }
    override fun getSoLinger(): Int = delegate.soLinger
    override fun sendUrgentData(data: Int) = delegate.sendUrgentData(data)
    override fun setOOBInline(on: Boolean) { delegate.oobInline = on }
    override fun getOOBInline(): Boolean = delegate.oobInline
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun setTrafficClass(tc: Int) { delegate.trafficClass = tc }
    override fun getTrafficClass(): Int = delegate.trafficClass
    override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
    override fun getReuseAddress(): Boolean = delegate.reuseAddress
    override fun close() = delegate.close()
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun toString(): String = delegate.toString()
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isBound(): Boolean = delegate.isBound
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
}

class TlsFragmentingOutputStream(private val delegate: OutputStream) : OutputStream() {
    private var isFirstWrite = true

    override fun write(b: Int) {
        delegate.write(b)
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        // Проверяем первый TLS-пакет (Handshake ClientHello)
        // 0x16 = Handshake, 0x03 = SSL 3.0 / TLS 1.x
        if (isFirstWrite && len > 5 && b[off] == 0x16.toByte() && b[off + 1] == 0x03.toByte()) {
            isFirstWrite = false
            try {
                val hostPattern = "api.cloudflareclient.com".toByteArray(Charsets.US_ASCII)
                var sniOffset = -1
                for (i in off..off + len - hostPattern.size) {
                    var match = true
                    for (j in hostPattern.indices) {
                        if (b[i + j] != hostPattern[j]) {
                            match = false
                            break
                        }
                    }
                    if (match) {
                        sniOffset = i - off + (hostPattern.size / 2)
                        break
                    }
                }

                if (sniOffset <= 0) {
                    sniOffset = findDynamicSniOffset(b, off, len)
                }

                val splitPoint = if (sniOffset > 0) sniOffset else 72.coerceAtMost(len - 1)
                delegate.write(b, off, splitPoint)
                delegate.flush()
                Thread.sleep(6)

                val remaining = len - splitPoint
                if (remaining > 0) {
                    delegate.write(b, off + splitPoint, remaining)
                    delegate.flush()
                }
            } catch (_: Throwable) {
                delegate.write(b, off, len)
            }
        } else {
            delegate.write(b, off, len)
        }
    }

    private fun findDynamicSniOffset(b: ByteArray, off: Int, len: Int): Int {
        try {
            if (len < 48 || b[off] != 0x16.toByte() || b[off + 1] != 0x03.toByte()) return -1
            if (b[off + 5] != 0x01.toByte()) return -1

            var cursor = off + 43
            if (cursor >= off + len) return -1
            val sessionIdLen = b[cursor].toInt() and 0xFF
            cursor += 1 + sessionIdLen

            if (cursor + 2 > off + len) return -1
            val cipherSuitesLen = ((b[cursor].toInt() and 0xFF) shl 8) or (b[cursor + 1].toInt() and 0xFF)
            cursor += 2 + cipherSuitesLen

            if (cursor + 1 > off + len) return -1
            val compressionLen = b[cursor].toInt() and 0xFF
            cursor += 1 + compressionLen

            if (cursor + 2 > off + len) return -1
            val extensionsLen = ((b[cursor].toInt() and 0xFF) shl 8) or (b[cursor + 1].toInt() and 0xFF)
            cursor += 2

            val extensionsEnd = (cursor + extensionsLen).coerceAtMost(off + len)
            while (cursor + 4 <= extensionsEnd) {
                val extType = ((b[cursor].toInt() and 0xFF) shl 8) or (b[cursor + 1].toInt() and 0xFF)
                val extDataLen = ((b[cursor + 2].toInt() and 0xFF) shl 8) or (b[cursor + 3].toInt() and 0xFF)
                cursor += 4

                if (extType == 0) {
                    if (cursor + 5 <= extensionsEnd) {
                        val hostLen = ((b[cursor + 3].toInt() and 0xFF) shl 8) or (b[cursor + 4].toInt() and 0xFF)
                        val hostStart = cursor + 5
                        if (hostStart + hostLen <= off + len && hostLen > 2) {
                            return (hostStart - off) + (hostLen / 2)
                        }
                    }
                    return cursor - off
                }
                cursor += extDataLen
            }
        } catch (_: Throwable) {}
        return -1
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}

/**
 * DNS-резолвер для api.cloudflareclient.com с пулом чистых Anycast IP-адресов.
 */
object WarpCloudflareDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.equals("api.cloudflareclient.com", ignoreCase = true)) {
            return listOf(
                // Европейские чистые Anycast-узлы Cloudflare API (CDN / Edge)
                InetAddress.getByAddress("api.cloudflareclient.com", byteArrayOf(104.toByte(), 16.toByte(), 24.toByte(), 84.toByte())),
                InetAddress.getByAddress("api.cloudflareclient.com", byteArrayOf(104.toByte(), 16.toByte(), 192.toByte(), 82.toByte())),
                InetAddress.getByAddress("api.cloudflareclient.com", byteArrayOf(104.toByte(), 16.toByte(), 132.toByte(), 229.toByte())),
                InetAddress.getByAddress("api.cloudflareclient.com", byteArrayOf(104.toByte(), 16.toByte(), 133.toByte(), 229.toByte())),
                InetAddress.getByAddress("api.cloudflareclient.com", byteArrayOf(8.toByte(), 47.toByte(), 69.toByte(), 0.toByte())),
                InetAddress.getByAddress("api.cloudflareclient.com", byteArrayOf(8.toByte(), 6.toByte(), 112.toByte(), 0.toByte()))
            )
        }
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
