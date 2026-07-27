package com.mirrly.tgproxy.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class HandshakeResult(
    val dcId: Int,
    val isMedia: Boolean,
    val protoTag: ByteArray,
    val decPrekeyAndIv: ByteArray,
    val dcIdx: Short
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HandshakeResult
        return dcId == other.dcId && isMedia == other.isMedia && protoTag.contentEquals(other.protoTag)
    }

    override fun hashCode(): Int {
        var result = dcId
        result = 31 * result + isMedia.hashCode()
        result = 31 * result + protoTag.contentHashCode()
        return result
    }
}

class AesCtrCipher(key: ByteArray, iv: ByteArray) {
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding")

    init {
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
    }

    fun update(input: ByteArray): ByteArray {
        return cipher.update(input)
    }

    fun update(input: ByteArray, offset: Int, length: Int): ByteArray {
        return cipher.update(input, offset, length)
    }
}

object MTProtoCrypto {
    private val random = SecureRandom()

    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    fun tryHandshake(handshake: ByteArray, secret: ByteArray): HandshakeResult? {
        if (handshake.size < TgConstants.HANDSHAKE_LEN) return null

        val decPrekeyAndIv = handshake.copyOfRange(TgConstants.SKIP_LEN, TgConstants.SKIP_LEN + TgConstants.PREKEY_LEN + TgConstants.IV_LEN)
        val decPrekey = decPrekeyAndIv.copyOfRange(0, TgConstants.PREKEY_LEN)
        val decIv = decPrekeyAndIv.copyOfRange(TgConstants.PREKEY_LEN, TgConstants.PREKEY_LEN + TgConstants.IV_LEN)

        val decKey = sha256(decPrekey + secret)
        val decryptor = AesCtrCipher(decKey, decIv)
        val decrypted = decryptor.update(handshake)

        val protoTag = decrypted.copyOfRange(TgConstants.PROTO_TAG_POS, TgConstants.PROTO_TAG_POS + 4)
        if (!protoTag.contentEquals(TgConstants.PROTO_TAG_ABRIDGED) &&
            !protoTag.contentEquals(TgConstants.PROTO_TAG_INTERMEDIATE) &&
            !protoTag.contentEquals(TgConstants.PROTO_TAG_SECURE)
        ) {
            return null
        }

        val dcBuffer = ByteBuffer.wrap(decrypted, TgConstants.DC_IDX_POS, 2).order(ByteOrder.LITTLE_ENDIAN)
        val dcIdx = dcBuffer.short
        val dcId = Math.abs(dcIdx.toInt())
        val isMedia = dcIdx < 0

        return HandshakeResult(
            dcId = dcId,
            isMedia = isMedia,
            protoTag = protoTag,
            decPrekeyAndIv = decPrekeyAndIv,
            dcIdx = dcIdx
        )
    }

    fun generateRelayInit(protoTag: ByteArray, dcIdx: Short): ByteArray {
        val rnd = ByteArray(TgConstants.HANDSHAKE_LEN)
        while (true) {
            random.nextBytes(rnd)
            val firstByte = rnd[0]
            if (TgConstants.RESERVED_FIRST_BYTES.contains(firstByte)) continue

            val start4 = rnd.copyOfRange(0, 4)
            if (start4.contentEquals(byteArrayOf('H'.code.toByte(), 'E'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte())) ||
                start4.contentEquals(byteArrayOf('P'.code.toByte(), 'O'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte())) ||
                start4.contentEquals(byteArrayOf('G'.code.toByte(), 'E'.code.toByte(), 'T'.code.toByte(), ' '.code.toByte())) ||
                start4.contentEquals(TgConstants.PROTO_TAG_INTERMEDIATE) ||
                start4.contentEquals(TgConstants.PROTO_TAG_SECURE) ||
                start4.contentEquals(byteArrayOf(0x16, 0x03, 0x01, 0x02))
            ) {
                continue
            }

            val cont = rnd.copyOfRange(4, 8)
            if (cont.contentEquals(TgConstants.RESERVED_CONTINUE)) continue
            break
        }

        val encKey = rnd.copyOfRange(TgConstants.SKIP_LEN, TgConstants.SKIP_LEN + TgConstants.PREKEY_LEN)
        val encIv = rnd.copyOfRange(TgConstants.SKIP_LEN + TgConstants.PREKEY_LEN, TgConstants.SKIP_LEN + TgConstants.PREKEY_LEN + TgConstants.IV_LEN)

        val encryptor = AesCtrCipher(encKey, encIv)
        val dcBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(dcIdx).array()
        val randomTail = ByteArray(2)
        random.nextBytes(randomTail)

        val tailPlain = protoTag + dcBytes + randomTail
        val encryptedFull = encryptor.update(rnd)

        val keystreamTail = ByteArray(8) { i ->
            (encryptedFull[56 + i].toInt() xor rnd[56 + i].toInt()).toByte()
        }

        val encryptedTail = ByteArray(8) { i ->
            (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte()
        }

        val result = rnd.copyOf()
        System.arraycopy(encryptedTail, 0, result, TgConstants.PROTO_TAG_POS, 8)
        return result
    }
}
