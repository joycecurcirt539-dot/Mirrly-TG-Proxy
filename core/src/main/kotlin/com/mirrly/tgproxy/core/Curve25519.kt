package com.mirrly.tgproxy.core

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Arrays

/**
 * Pure Kotlin RFC 7748 compliant implementation of Curve25519 / X25519 Diffie-Hellman function.
 *
 * Implements:
 * 1. Clamping of 32-byte scalar as required by WireGuard and RFC 7748 Section 5.
 * 2. Montgomery ladder scalar multiplication over GF(2^255 - 19).
 * 3. Base point (u = 9) public key derivation: X25519(clamp(k), 9).
 * 4. Documented RFC 8410 extraction of raw 32-byte keys from SPKI (44 bytes) and PKCS#8 (48 bytes).
 * 5. Deterministic fallback for environments without JCA "X25519" provider (e.g. Android API < 33).
 */
object Curve25519 {

    /**
     * Prime field modulus p = 2^255 - 19
     */
    val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

    /**
     * Montgomery curve constant A24 = (486662 - 2) / 4 = 121665
     */
    private val A24: BigInteger = BigInteger.valueOf(121665)

    private val TWO: BigInteger = BigInteger.valueOf(2)

    /**
     * Standard RFC 7748 base point coordinate u = 9 (encoded in 32 bytes little-endian)
     */
    val BASE_POINT_U: ByteArray = ByteArray(32).apply { this[0] = 9 }

    /**
     * RFC 8410 SPKI header for id-X25519 (12 bytes):
     * 30 2a (SEQUENCE 42)
     *   30 05 (SEQUENCE 5 - AlgorithmIdentifier)
     *     06 03 2b 65 6e (OID 1.3.101.110 id-X25519)
     *   03 21 00 (BIT STRING 33, 0 unused bits)
     */
    private val SPKI_X25519_PREFIX = byteArrayOf(
        0x30.toByte(), 0x2a.toByte(),
        0x30.toByte(), 0x05.toByte(),
        0x06.toByte(), 0x03.toByte(), 0x2b.toByte(), 0x65.toByte(), 0x6e.toByte(),
        0x03.toByte(), 0x21.toByte(), 0x00.toByte()
    )

    /**
     * RFC 8410 PKCS#8 header for id-X25519 (16 bytes):
     * 30 2e (SEQUENCE 46)
     *   02 01 00 (INTEGER 0 - Version)
     *   30 05 (SEQUENCE 5 - AlgorithmIdentifier)
     *     06 03 2b 65 6e (OID 1.3.101.110 id-X25519)
     *   04 22 (OCTET STRING 34)
     *     04 20 (OCTET STRING 32 - CurvePrivateKey)
     */
    private val PKCS8_X25519_PREFIX = byteArrayOf(
        0x30.toByte(), 0x2e.toByte(),
        0x02.toByte(), 0x01.toByte(), 0x00.toByte(),
        0x30.toByte(), 0x05.toByte(),
        0x06.toByte(), 0x03.toByte(), 0x2b.toByte(), 0x65.toByte(), 0x6e.toByte(),
        0x04.toByte(), 0x22.toByte(),
        0x04.toByte(), 0x20.toByte()
    )

    /**
     * Applies WireGuard / RFC 7748 clamping to a 32-byte private key.
     * key[0] &= 248
     * key[31] &= 127
     * key[31] |= 64
     */
    fun clampPrivateKey(key: ByteArray): ByteArray {
        require(key.size == 32) { "X25519 private key must be exactly 32 bytes, got ${key.size}" }
        val clamped = key.copyOf()
        clamped[0] = (clamped[0].toInt() and 248).toByte()
        clamped[31] = (clamped[31].toInt() and 127).toByte()
        clamped[31] = (clamped[31].toInt() or 64).toByte()
        return clamped
    }

    /**
     * Decodes a little-endian byte array into an unsigned BigInteger.
     */
    fun decodeLittleEndian(bytes: ByteArray): BigInteger {
        val reversed = bytes.reversedArray()
        return BigInteger(1, reversed)
    }

    /**
     * Encodes an integer modulo p into a 32-byte little-endian array.
     */
    fun encodeLittleEndian(num: BigInteger, length: Int = 32): ByteArray {
        val modNum = num.mod(P)
        val beBytes = modNum.toByteArray()
        val out = ByteArray(length)
        var beIdx = beBytes.size - 1
        var outIdx = 0
        while (beIdx >= 0 && outIdx < length) {
            out[outIdx++] = beBytes[beIdx--]
        }
        return out
    }

    /**
     * RFC 7748 Section 5: The X25519 function.
     * Computes the scalar multiplication of scalar [scalarBytes] with Montgomery curve point u [uBytes].
     */
    fun scalarMult(scalarBytes: ByteArray, uBytes: ByteArray): ByteArray {
        require(scalarBytes.size == 32) { "Scalar must be 32 bytes, got ${scalarBytes.size}" }
        require(uBytes.size == 32) { "u coordinate must be 32 bytes, got ${uBytes.size}" }

        // Clamp the scalar as per RFC 7748 Section 5
        val clamped = clampPrivateKey(scalarBytes)
        val k = decodeLittleEndian(clamped)

        // Mask the most significant bit in the final byte of u
        val uClamped = uBytes.copyOf()
        uClamped[31] = (uClamped[31].toInt() and 127).toByte()
        val x1 = decodeLittleEndian(uClamped).mod(P)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) {
                val tmpX = x2; x2 = x3; x3 = tmpX
                val tmpZ = z2; z2 = z3; z3 = tmpZ
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            val daPlusCb = da.add(cb).mod(P)
            x3 = daPlusCb.multiply(daPlusCb).mod(P)

            val daMinusCb = da.subtract(cb).mod(P)
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb).mod(P)).mod(P)

            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }

        if (swap == 1) {
            val tmpX = x2; x2 = x3; x3 = tmpX
            val tmpZ = z2; z2 = z3; z3 = tmpZ
        }

        // result = (x2 * (z2^(p - 2))) mod p
        val result = x2.multiply(z2.modPow(P.subtract(TWO), P)).mod(P)
        return encodeLittleEndian(result, 32)
    }

    /**
     * Derives the X25519 public key corresponding to [privateKey].
     * Computes X25519(clamp(privateKey), 9).
     */
    fun computePublicKey(privateKey: ByteArray): ByteArray {
        return scalarMult(privateKey, BASE_POINT_U)
    }

    /**
     * Extracts raw 32-byte public key from RFC 8410 SubjectPublicKeyInfo (SPKI) or raw bytes.
     *
     * @param encoded Raw 32 bytes or 44-byte DER SubjectPublicKeyInfo
     * @throws IllegalArgumentException if format does not conform to RFC 8410
     */
    fun extractRawPublicKey(encoded: ByteArray): ByteArray {
        if (encoded.size == 32) {
            return encoded.copyOf()
        }
        if (encoded.size == 44 && matchesPrefix(encoded, SPKI_X25519_PREFIX)) {
            return encoded.copyOfRange(12, 44)
        }
        // If encoded starts with standard OID sequence, attempt precise slice
        if (encoded.size >= 44 && matchesPrefix(encoded.copyOfRange(2, encoded.size), SPKI_X25519_PREFIX.copyOfRange(2, 12))) {
            return encoded.copyOfRange(encoded.size - 32, encoded.size)
        }
        throw IllegalArgumentException(
            "Unsupported or invalid X25519 public key format: size=${encoded.size}, expected raw 32 bytes or 44-byte RFC 8410 SPKI"
        )
    }

    /**
     * Extracts raw 32-byte private key from RFC 8410 PKCS#8 or raw bytes.
     *
     * @param encoded Raw 32 bytes or 48-byte DER PKCS#8 OneAsymmetricKey
     * @throws IllegalArgumentException if format does not conform to RFC 8410
     */
    fun extractRawPrivateKey(encoded: ByteArray): ByteArray {
        if (encoded.size == 32) {
            return encoded.copyOf()
        }
        if (encoded.size == 48 && matchesPrefix(encoded, PKCS8_X25519_PREFIX)) {
            return encoded.copyOfRange(16, 48)
        }
        // Fallback for PKCS#8 with attributes or minor prefix variation containing id-X25519 OID
        if (encoded.size >= 48 && containsOidX25519(encoded)) {
            return encoded.copyOfRange(encoded.size - 32, encoded.size)
        }
        throw IllegalArgumentException(
            "Unsupported or invalid X25519 private key format: size=${encoded.size}, expected raw 32 bytes or 48-byte RFC 8410 PKCS#8"
        )
    }

    private fun matchesPrefix(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) {
            if (data[i] != prefix[i]) return false
        }
        return true
    }

    private fun containsOidX25519(data: ByteArray): Boolean {
        // id-X25519 OID: 06 03 2B 65 6E
        val oid = byteArrayOf(0x06, 0x03, 0x2b, 0x65, 0x6e)
        if (data.size < oid.size) return false
        for (i in 0..(data.size - oid.size)) {
            var match = true
            for (j in oid.indices) {
                if (data[i + j] != oid[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    /**
     * Generates a cryptographically strong clamped 32-byte private key using [SecureRandom].
     */
    fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray {
        val priv = ByteArray(32)
        random.nextBytes(priv)
        return clampPrivateKey(priv)
    }

    /**
     * Generates a cryptographically linked (private, public) WireGuard keypair.
     * The public key is guaranteed to be mathematically derived from the private key.
     */
    fun generateKeyPair(random: SecureRandom = SecureRandom()): Pair<ByteArray, ByteArray> {
        val priv = generatePrivateKey(random)
        val pub = computePublicKey(priv)
        return Pair(priv, pub)
    }
}
