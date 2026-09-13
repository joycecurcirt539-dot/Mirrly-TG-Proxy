package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyPairGenerator
import java.util.HexFormat

class Curve25519Test {

    private val hex = HexFormat.of()

    @Test
    fun testRfc7748Section5Point2TestVectors() {
        // Test vector 1
        val scalar1 = hex.parseHex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u1 = hex.parseHex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        val expected1 = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"

        val out1 = Curve25519.scalarMult(scalar1, u1)
        assertEquals(expected1, hex.formatHex(out1), "RFC 7748 Section 5.2 Vector 1")

        // Test vector 2
        val scalar2 = hex.parseHex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d")
        val u2 = hex.parseHex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")
        val expected2 = "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"

        val out2 = Curve25519.scalarMult(scalar2, u2)
        assertEquals(expected2, hex.formatHex(out2), "RFC 7748 Section 5.2 Vector 2")
    }

    @Test
    fun testRfc7748Section6Point1AliceAndBobDiffieHellman() {
        // Alice
        val alicePrivHex = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        val alicePubExpectedHex = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"

        val alicePriv = hex.parseHex(alicePrivHex)
        val alicePubComputed = Curve25519.computePublicKey(alicePriv)
        assertEquals(alicePubExpectedHex, hex.formatHex(alicePubComputed), "Alice public key must match RFC 7748 Section 6.1")

        // Bob
        val bobPrivHex = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
        val bobPubExpectedHex = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"

        val bobPriv = hex.parseHex(bobPrivHex)
        val bobPubComputed = Curve25519.computePublicKey(bobPriv)
        assertEquals(bobPubExpectedHex, hex.formatHex(bobPubComputed), "Bob public key must match RFC 7748 Section 6.1")

        // Diffie-Hellman Shared Secret: X25519(alicePriv, bobPub) == X25519(bobPriv, alicePub)
        val sharedSecretExpectedHex = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

        val sharedSecretAlice = Curve25519.scalarMult(alicePriv, bobPubComputed)
        val sharedSecretBob = Curve25519.scalarMult(bobPriv, alicePubComputed)

        assertEquals(sharedSecretExpectedHex, hex.formatHex(sharedSecretAlice), "Alice's computed shared secret must match RFC 7748")
        assertEquals(sharedSecretExpectedHex, hex.formatHex(sharedSecretBob), "Bob's computed shared secret must match RFC 7748")
        assertArrayEquals(sharedSecretAlice, sharedSecretBob, "Diffie-Hellman key exchange must produce identical shared secret")
    }

    @Test
    fun testWireGuardClamping() {
        val raw = ByteArray(32) { 0xFF.toByte() }
        val clamped = Curve25519.clampPrivateKey(raw)

        // byte 0: 0xFF & 248 = 248 (0xF8)
        assertEquals(0xF8.toByte(), clamped[0])
        // byte 31: (0xFF & 127) | 64 = 0x7F | 0x40 = 127
        assertEquals(127.toByte(), clamped[31])

        // Clamping must be idempotent
        val doubleClamped = Curve25519.clampPrivateKey(clamped)
        assertArrayEquals(clamped, doubleClamped)

        // Zero test
        val zero = ByteArray(32) { 0x00 }
        val clampedZero = Curve25519.clampPrivateKey(zero)
        assertEquals(0x00.toByte(), clampedZero[0])
        assertEquals(0x40.toByte(), clampedZero[31]) // 0 | 64 = 64
    }

    @Test
    fun testGenerateKeyPairDerivesMathPair() {
        for (i in 1..5) {
            val (priv, pub) = Curve25519.generateKeyPair()
            assertEquals(32, priv.size)
            assertEquals(32, pub.size)

            val derivedPub = Curve25519.computePublicKey(priv)
            assertArrayEquals(pub, derivedPub, "Public key MUST be strictly derived from private key")
        }
    }

    @Test
    fun testExtractRawKeysFromJcaKeyPair() {
        try {
            val kpg = KeyPairGenerator.getInstance("X25519")
            val jcaPair = kpg.generateKeyPair()

            val rawPub = Curve25519.extractRawPublicKey(jcaPair.public.encoded)
            val rawPriv = Curve25519.extractRawPrivateKey(jcaPair.private.encoded)

            assertEquals(32, rawPub.size)
            assertEquals(32, rawPriv.size)

            // Cross check: public key computed from rawPriv via pure Kotlin Curve25519 must equal rawPub from JCA!
            val pureKotlinDerivedPub = Curve25519.computePublicKey(rawPriv)
            assertArrayEquals(rawPub, pureKotlinDerivedPub, "JCA public key and Pure Kotlin derived public key must be mathematically identical")
        } catch (e: java.security.NoSuchAlgorithmException) {
            println("JCA X25519 not present in runtime environment, skipping JCA cross-check")
        }
    }

    @Test
    fun testExtractRawPublicKeyRejectsMalformed() {
        assertThrows<IllegalArgumentException> {
            Curve25519.extractRawPublicKey(ByteArray(10))
        }

        // 44 bytes but invalid prefix
        val badSpki = ByteArray(44) { 0xAA.toByte() }
        assertThrows<IllegalArgumentException> {
            Curve25519.extractRawPublicKey(badSpki)
        }
    }

    @Test
    fun testExtractRawPrivateKeyRejectsMalformed() {
        assertThrows<IllegalArgumentException> {
            Curve25519.extractRawPrivateKey(ByteArray(15))
        }

        // 48 bytes but invalid prefix
        val badPkcs8 = ByteArray(48) { 0xBB.toByte() }
        assertThrows<IllegalArgumentException> {
            Curve25519.extractRawPrivateKey(badPkcs8)
        }
    }
}
