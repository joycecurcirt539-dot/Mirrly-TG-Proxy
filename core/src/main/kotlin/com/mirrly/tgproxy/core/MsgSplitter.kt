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
    fun split(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size): List<ByteArray> {
        if (length <= 0) return emptyList()
        if (disabled) {
            val slice = if (offset == 0 && length == chunk.size) chunk else chunk.copyOfRange(offset, offset + length)
            return listOf(slice)
        }

        val decrypted = decCipher.update(chunk, offset, length)

        val cipherBytes: ByteArray
        val plainBytes: ByteArray
        val hasResidue = cipherBuf.size() > 0

        if (hasResidue) {
            cipherBuf.write(chunk, offset, length)
            plainBuf.write(decrypted)
            cipherBytes = cipherBuf.toByteArray()
            plainBytes = plainBuf.toByteArray()
        } else {
            cipherBytes = chunk
            plainBytes = decrypted
        }

        val parts = mutableListOf<ByteArray>()
        var curPlainOffset = 0
        val bufLen = if (hasResidue) cipherBytes.size else length

        while (curPlainOffset < bufLen) {
            val avail = bufLen - curPlainOffset
            val packetLen = nextPacketLen(plainBytes, curPlainOffset, avail) ?: break
            if (packetLen <= 0) {
                val startCipherOffset = if (hasResidue) curPlainOffset else (offset + curPlainOffset)
                val endCipherOffset = if (hasResidue) cipherBytes.size else (offset + bufLen)
                parts.add(cipherBytes.copyOfRange(startCipherOffset, endCipherOffset))
                curPlainOffset = bufLen
                disabled = true
                break
            }
            val startCipherOffset = if (hasResidue) curPlainOffset else (offset + curPlainOffset)
            parts.add(cipherBytes.copyOfRange(startCipherOffset, startCipherOffset + packetLen))
            curPlainOffset += packetLen
        }

        if (hasResidue) {
            if (curPlainOffset > 0) {
                val remainingLen = bufLen - curPlainOffset
                cipherBuf.reset()
                plainBuf.reset()
                if (remainingLen > 0) {
                    cipherBuf.write(cipherBytes, curPlainOffset, remainingLen)
                    plainBuf.write(plainBytes, curPlainOffset, remainingLen)
                }
            }
        } else {
            if (curPlainOffset < bufLen) {
                cipherBuf.write(cipherBytes, offset + curPlainOffset, bufLen - curPlainOffset)
                plainBuf.write(plainBytes, curPlainOffset, bufLen - curPlainOffset)
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
