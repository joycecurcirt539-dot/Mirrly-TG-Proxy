package com.mirrly.tgproxy.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MsgSplitter(relayInit: ByteArray, private val protoInt: Int) {
    private val decCipher: AesCtrCipher
    private val cipherBuf = ByteArrayOutputStream()
    private val plainBuf = ByteArrayOutputStream()
    private var disabled = false

    init {
        val key = relayInit.copyOfRange(8, 40)
        val iv = relayInit.copyOfRange(40, 56)
        decCipher = AesCtrCipher(key, iv)
        // Discard 64 bytes keystream
        decCipher.update(ByteArray(64))
    }

    @Synchronized
    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipherBuf.write(chunk)
        val decrypted = decCipher.update(chunk)
        plainBuf.write(decrypted)

        val parts = mutableListOf<ByteArray>()
        var offset = 0
        val cipherBytes = cipherBuf.toByteArray()
        val plainBytes = plainBuf.toByteArray()
        val bufLen = cipherBytes.size

        while (offset < bufLen) {
            val avail = bufLen - offset
            val packetLen = nextPacketLen(plainBytes, offset, avail) ?: break
            if (packetLen <= 0) {
                parts.add(cipherBytes.copyOfRange(offset, bufLen))
                offset = bufLen
                disabled = true
                break
            }
            parts.add(cipherBytes.copyOfRange(offset, offset + packetLen))
            offset += packetLen
        }

        if (offset > 0) {
            val remainingCipher = if (offset < bufLen) cipherBytes.copyOfRange(offset, bufLen) else ByteArray(0)
            val remainingPlain = if (offset < bufLen) plainBytes.copyOfRange(offset, bufLen) else ByteArray(0)
            cipherBuf.reset()
            plainBuf.reset()
            if (remainingCipher.isNotEmpty()) {
                cipherBuf.write(remainingCipher)
                plainBuf.write(remainingPlain)
            }
        }

        return parts
    }

    @Synchronized
    fun flush(): List<ByteArray> {
        val remaining = cipherBuf.toByteArray()
        cipherBuf.reset()
        plainBuf.reset()
        return if (remaining.isNotEmpty()) listOf(remaining) else emptyList()
    }

    private fun nextPacketLen(plain: ByteArray, offset: Int, avail: Int): Int? {
        if (avail <= 0) return null
        return when (protoInt) {
            TgConstants.PROTO_ABRIDGED_INT -> nextAbridgedLen(plain, offset, avail)
            TgConstants.PROTO_INTERMEDIATE_INT, TgConstants.PROTO_PADDED_INTERMEDIATE_INT -> nextIntermediateLen(plain, offset, avail)
            else -> 0
        }
    }

    private fun nextAbridgedLen(plain: ByteArray, offset: Int, avail: Int): Int? {
        val first = plain[offset].toInt() and 0xFF
        val payloadLen: Int
        val headerLen: Int
        if (first == 0x7F || first == 0xFF) {
            if (avail < 4) return null
            val b1 = plain[offset + 1].toInt() and 0xFF
            val b2 = plain[offset + 2].toInt() and 0xFF
            val b3 = plain[offset + 3].toInt() and 0xFF
            payloadLen = (b1 or (b2 shl 8) or (b3 shl 16)) * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }
        if (payloadLen <= 0) return 0
        val packetLen = headerLen + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }

    private fun nextIntermediateLen(plain: ByteArray, offset: Int, avail: Int): Int? {
        if (avail < 4) return null
        val lenBuf = ByteBuffer.wrap(plain, offset, 4).order(ByteOrder.LITTLE_ENDIAN)
        val rawLen = lenBuf.int
        val payloadLen = rawLen and 0x7FFFFFFF
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }
}
