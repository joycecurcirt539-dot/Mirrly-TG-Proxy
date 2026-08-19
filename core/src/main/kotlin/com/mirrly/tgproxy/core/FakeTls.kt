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

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

object FakeTls {
    const val TLS_RECORD_HEADER_LEN = 5
    const val MAX_TLS_RECORD_PAYLOAD = 16384

    const val CONTENT_TYPE_CHANGE_CIPHER_SPEC: Byte = 0x14
    const val CONTENT_TYPE_ALERT: Byte = 0x15
    const val CONTENT_TYPE_HANDSHAKE: Byte = 0x16
    const val CONTENT_TYPE_APPLICATION_DATA: Byte = 0x17

    private val random = SecureRandom()

    fun isTlsHandshake(buf: ByteArray): Boolean {
        if (buf.size < 3) return false
        val c0 = buf[0].toInt() and 0xFF
        val c1 = buf[1].toInt() and 0xFF
        val c2 = buf[2].toInt() and 0xFF
        return c0 == 0x16 && c1 == 0x03 && (c2 in 0x01..0x04)
    }

    fun buildFakeTlsServerHello(sessionId: ByteArray): ByteArray {
        val serverRandom = ByteArray(32)
        random.nextBytes(serverRandom)

        val keySharePub = ByteArray(32)
        random.nextBytes(keySharePub)

        val out = ByteArrayOutputStream(256)

        // ── 1. TLS Record Header для ServerHello ───────────────────────────────
        // Record Header: 0x16 (Handshake), 0x03, 0x03 (TLS 1.2 legacy), 2 байта длины (122 = 0x00, 0x7a)
        out.write(byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x7a))

        // Handshake Type: 0x02 (ServerHello), 3 байта длины (118 = 0x00, 0x00, 0x76)
        out.write(byteArrayOf(0x02, 0x00, 0x00, 0x76))

        // Legacy Version: 0x03, 0x03
        out.write(byteArrayOf(0x03, 0x03))

        // Server Random (32 байта)
        out.write(serverRandom)

        // Session ID length (32) + Session ID (эхо от клиента)
        out.write(0x20)
        out.write(sessionId, 0, 32)

        // Cipher Suite: TLS_AES_128_GCM_SHA256 (0x13, 0x01)
        out.write(byteArrayOf(0x13, 0x01))

        // Compression Method: null (0x00)
        out.write(0x00)

        // Extensions Length: 46 байт (0x00, 0x2e)
        out.write(byteArrayOf(0x00, 0x2e))

        // Extension 1: supported_versions (type 43 = 0x00, 0x2b, len = 2, TLS 1.3 = 0x03, 0x04)
        out.write(byteArrayOf(0x00, 0x2b, 0x00, 0x02, 0x03, 0x04))

        // Extension 2: key_share (type 51 = 0x00, 0x33, len = 36, group x25519 = 0x00, 0x1d, key_len = 32)
        out.write(byteArrayOf(0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20))
        out.write(keySharePub)

        // ── 2. ChangeCipherSpec Record ─────────────────────────────────────────
        // 0x14, 0x03, 0x03, 0x00, 0x01, 0x01
        out.write(byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01))

        // ── 3. Dummy ApplicationData (Encrypted Handshake / Finished) ──────────
        // 0x17, 0x03, 0x03, 0x00, 0x35 (53 байта фиктивных зашифрованных данных)
        val dummyAppData = ByteArray(53)
        random.nextBytes(dummyAppData)
        out.write(byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x35))
        out.write(dummyAppData)

        return out.toByteArray()
    }

    private fun readExact(inputStream: InputStream, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Boolean {
        var read = 0
        while (read < length) {
            val count = inputStream.read(buffer, offset + read, length - read)
            if (count < 0) return false
            read += count
        }
        return true
    }

    /**
     * Выполняет обработку ClientHello, отправляет ответ ServerHello + CCS + Finished
     * и считывает первую запись TLS ApplicationData, содержащую MTProto заголовок.
     */
    fun handleFakeTlsHandshake(
        inputStream: InputStream,
        outputStream: OutputStream,
        initial5: ByteArray
    ): ByteArray? {
        val recordLen = ((initial5[3].toInt() and 0xFF) shl 8) or (initial5[4].toInt() and 0xFF)
        val clientHelloBody = ByteArray(recordLen)
        if (!readExact(inputStream, clientHelloBody)) return null

        val sessionId = ByteArray(32)
        if (clientHelloBody.size >= 71 && clientHelloBody[38].toInt() == 32) {
            System.arraycopy(clientHelloBody, 39, sessionId, 0, 32)
        } else {
            random.nextBytes(sessionId)
        }

        val serverHello = buildFakeTlsServerHello(sessionId)
        outputStream.write(serverHello)
        outputStream.flush()

        // Считываем первую запись ApplicationData
        return readTlsAppData(inputStream)
    }

    /**
     * Считывает следующую запись TLS Application Data (0x17), пропуская ChangeCipherSpec / Handshake.
     */
    fun readTlsAppData(inputStream: InputStream): ByteArray? {
        val hdr = ByteArray(TLS_RECORD_HEADER_LEN)
        while (true) {
            if (!readExact(inputStream, hdr)) return null

            val contentType = hdr[0]
            val recordLen = ((hdr[3].toInt() and 0xFF) shl 8) or (hdr[4].toInt() and 0xFF)

            if (recordLen > MAX_TLS_RECORD_PAYLOAD + 2048) {
                return null
            }

            val payload = ByteArray(recordLen)
            if (!readExact(inputStream, payload)) return null

            if (contentType == CONTENT_TYPE_APPLICATION_DATA) {
                return payload
            } else if (contentType == CONTENT_TYPE_CHANGE_CIPHER_SPEC || contentType == CONTENT_TYPE_HANDSHAKE) {
                continue
            } else if (contentType == CONTENT_TYPE_ALERT) {
                return null
            }
        }
    }

    /**
     * Оборачивает данные в TLS ApplicationData (0x17 0x03 0x03) и записывает в сокет.
     */
    fun writeTlsAppData(outputStream: OutputStream, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        var cur = offset
        var remaining = length
        while (remaining > 0) {
            val chunkLen = remaining.coerceAtMost(MAX_TLS_RECORD_PAYLOAD)
            val hdr = byteArrayOf(
                CONTENT_TYPE_APPLICATION_DATA,
                0x03,
                0x03,
                ((chunkLen shr 8) and 0xFF).toByte(),
                (chunkLen and 0xFF).toByte()
            )
            outputStream.write(hdr)
            outputStream.write(data, cur, chunkLen)
            cur += chunkLen
            remaining -= chunkLen
        }
        outputStream.flush()
    }
}
