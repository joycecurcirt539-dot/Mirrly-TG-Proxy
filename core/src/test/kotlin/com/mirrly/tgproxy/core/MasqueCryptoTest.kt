package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class MasqueCryptoTest {

    @Test
    fun testGenerateWireGuardX25519KeyPair() {
        var generated = false
        // Try standard X25519
        try {
            val kpg = KeyPairGenerator.getInstance("X25519")
            val pair = kpg.generateKeyPair()
            val priv = pair.private.encoded
            val pub = pair.public.encoded
            assertNotNull(priv)
            assertNotNull(pub)
            // Public key in SPKI is 44 bytes, raw key is last 32 bytes
            val pubRaw = pub.takeLast(32).toByteArray()
            assertEquals(32, pubRaw.size)
            generated = true
        } catch (e: Exception) {
            println("X25519 direct not available: ")
        }
        assertTrue(generated, "Should generate X25519 keypair")
    }

    @Test
    fun testGenerateP256KeyPairAndSelfSignedCert() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val spkiBytes = keyPair.public.encoded
        val pkcs8Bytes = keyPair.private.encoded

        assertNotNull(spkiBytes)
        assertNotNull(pkcs8Bytes)
        assertEquals(91, spkiBytes.size, "P-256 SubjectPublicKeyInfo should be exactly 91 bytes")

        val certDer = buildSelfSignedCert(keyPair)
        assertNotNull(certDer)
        assertTrue(certDer.isNotEmpty())

        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

        assertEquals(3, cert.version)
        assertEquals("CN=Mirrly", cert.subjectX500Principal.name)
        assertEquals("CN=Mirrly", cert.issuerX500Principal.name)
        cert.verify(keyPair.public)
    }

    private fun buildSelfSignedCert(keyPair: java.security.KeyPair): ByteArray {
        val spki = keyPair.public.encoded

        val sigAlg = byteArrayOf(
            0x30.toByte(), 0x0A.toByte(),
            0x06.toByte(), 0x08.toByte(),
            0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0xCE.toByte(),
            0x3D.toByte(), 0x04.toByte(), 0x03.toByte(), 0x02.toByte()
        )

        val version = byteArrayOf(0xA0.toByte(), 0x03.toByte(), 0x02.toByte(), 0x01.toByte(), 0x02.toByte())
        val serial = byteArrayOf(0x02.toByte(), 0x01.toByte(), 0x01.toByte())
        
        val nameDer = byteArrayOf(
            0x30.toByte(), 0x11.toByte(),
            0x31.toByte(), 0x0F.toByte(),
            0x30.toByte(), 0x0D.toByte(),
            0x06.toByte(), 0x03.toByte(), 0x55.toByte(), 0x04.toByte(), 0x03.toByte(),
            0x0C.toByte(), 0x06.toByte(), 0x4D.toByte(), 0x69.toByte(), 0x72.toByte(), 0x72.toByte(), 0x6C.toByte(), 0x79.toByte()
        )
        val notBefore = byteArrayOf(0x17.toByte(), 0x0D.toByte()) + "260101000000Z".toByteArray(Charsets.US_ASCII)
        val notAfter = byteArrayOf(0x17.toByte(), 0x0D.toByte()) + "360101000000Z".toByteArray(Charsets.US_ASCII)
        val validity = asn1Sequence(notBefore + notAfter)

        val tbsInner = version + serial + sigAlg + nameDer + validity + nameDer + spki
        val tbsCertificate = asn1Sequence(tbsInner)

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(tbsCertificate)
        val rawSig = sig.sign()

        val bitStringSig = byteArrayOf(0x03.toByte()) + asn1Length(rawSig.size + 1) + byteArrayOf(0x00.toByte()) + rawSig

        return asn1Sequence(tbsCertificate + sigAlg + bitStringSig)
    }

    private fun asn1Sequence(content: ByteArray): ByteArray {
        return byteArrayOf(0x30.toByte()) + asn1Length(content.size) + content
    }

    private fun asn1Length(len: Int): ByteArray {
        return when {
            len < 128 -> byteArrayOf(len.toByte())
            len < 256 -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
        }
    }
}
