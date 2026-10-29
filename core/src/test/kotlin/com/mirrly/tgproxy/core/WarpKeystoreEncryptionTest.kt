/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WarpKeystoreEncryptionTest {

    @Test
    fun testEncryptAndDecryptRoundTrip() {
        val sampleConf = """
            [Interface]
            PrivateKey = 4B7hZt4c12pU7o+s3f79m1Q6gL8k3z1A5x7e9c0B2d4=
            Address = 172.16.0.2/32
            DNS = 1.1.1.1
            MTU = 1280
            [Peer]
            PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
            Endpoint = 188.114.96.1:8095
        """.trimIndent()

        val encrypted = WarpKeystoreEncryption.encrypt(sampleConf)
        assertNotEquals(sampleConf, encrypted)
        assertTrue(encrypted.startsWith("enc:v1:"))
        assertFalse(encrypted.contains("4B7hZt4c12pU7o+s3f79m1Q6gL8k3z1A5x7e9c0B2d4="))

        val decrypted = WarpKeystoreEncryption.decrypt(encrypted)
        assertEquals(sampleConf, decrypted)
    }

    @Test
    fun testPlaintextFallbackWhenNotEncrypted() {
        val plain = "PrivateKey = testPlainKey123"
        val decrypted = WarpKeystoreEncryption.decrypt(plain)
        assertEquals(plain, decrypted)
    }

    @Test
    fun testEmptyAndBlankStrings() {
        assertEquals("", WarpKeystoreEncryption.encrypt(""))
        assertEquals("   ", WarpKeystoreEncryption.encrypt("   "))
        assertEquals("", WarpKeystoreEncryption.decrypt(""))
    }
}
