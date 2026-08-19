package com.mirrly.tgproxy.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Splits MTProto stream into discrete packets for WebSocket /apiws framing.
 * Performs full re-encryption: client_dec(ciphertext) -> plaintext -> upstream_enc(plaintext).
 * This is required because Telegram WS server expects data encrypted with the relay_init upstream key,
 * NOT the client's original encryption key.
 *
 * @param clientDec AES-CTR cipher initialized with the client's handshake decryption key (already +64 discarded)
 * @param upstreamEnc AES-CTR cipher initialized with the upstream relay_init encryption key (already +64 discarded)
 */
class MsgSplitter(
    private val clientDec: AesCtrCipher,
    private val upstreamEnc: AesCtrCipher,
    private val protoInt: Int
) {
    /**
     * Convenience constructor initializing symmetric ciphers from relayInit.
     */
    constructor(relayInit: ByteArray, protoInt: Int) : this(
        AesCtrCipher(relayInit.copyOfRange(8, 40), relayInit.copyOfRange(40, 56)).also { it.update(ByteArray(64)) },
        AesCtrCipher(relayInit.copyOfRange(8, 40), relayInit.copyOfRange(40, 56)).also { it.update(ByteArray(64)) },
        protoInt
    )

    private val plainBuf = ByteArrayOutputStream()
    private var disabled = false

    @Synchronized
    fun split(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size): List<ByteArray> {
        if (length <= 0) return emptyList()
        if (disabled) {
            // After disable, pass data with re-encryption but without splitting
            val slice = if (offset == 0 && length == chunk.size) chunk else chunk.copyOfRange(offset, offset + length)
            val decrypted = clientDec.update(slice)
            return listOf(upstreamEnc.update(decrypted))
        }

        val decrypted = clientDec.update(chunk, offset, length)

        val plainBytes: ByteArray
        val hasResidue = plainBuf.size() > 0

        if (hasResidue) {
            plainBuf.write(decrypted)
            plainBytes = plainBuf.toByteArray()
        } else {
            plainBytes = decrypted
        }

        val parts = mutableListOf<ByteArray>()
        var curPlainOffset = 0
        val bufLen = plainBytes.size

        while (curPlainOffset < bufLen) {
            val avail = bufLen - curPlainOffset
            val packetLen = nextPacketLen(plainBytes, curPlainOffset, avail) ?: break
            if (packetLen <= 0) {
                // Unknown protocol — re-encrypt and pass through remaining data
                val remaining = plainBytes.copyOfRange(curPlainOffset, bufLen)
                parts.add(upstreamEnc.update(remaining))
                curPlainOffset = bufLen
                disabled = true
                break
            }
            val packet = plainBytes.copyOfRange(curPlainOffset, curPlainOffset + packetLen)
            parts.add(upstreamEnc.update(packet))
            curPlainOffset += packetLen
        }

        if (hasResidue) {
            if (curPlainOffset > 0) {
                val remainingLen = bufLen - curPlainOffset
                plainBuf.reset()
                if (remainingLen > 0) {
                    plainBuf.write(plainBytes, curPlainOffset, remainingLen)
                }
            }
        } else {
            if (curPlainOffset < bufLen) {
                plainBuf.write(plainBytes, curPlainOffset, bufLen - curPlainOffset)
            }
        }

        return parts
    }

    @Synchronized
    fun flush(): List<ByteArray> {
        val remaining = plainBuf.toByteArray()
        plainBuf.reset()
        return if (remaining.isNotEmpty()) listOf(upstreamEnc.update(remaining)) else emptyList()
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
