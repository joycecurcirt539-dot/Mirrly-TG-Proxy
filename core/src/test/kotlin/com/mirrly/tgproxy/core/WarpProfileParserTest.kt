/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class WarpProfileParserTest {

    // Valid 32-byte Curve25519 private key for tests
    private val testPrivKey = "4B7hZt4c12pU7o+s3f79m1Q6gL8k3z1A5x7e9c0B2d4="
    // Valid 32-byte Curve25519 peer key
    private val testPeerKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    @Test
    fun testParseWarpLicenseKey() {
        val licenseKey = "2y0t64eH-r19g8jA3-4v70T2bH"
        val result = WarpProfileParser.parse(licenseKey)

        assertTrue(result is WarpImportResult.LicenseKeyOnly)
        assertEquals(licenseKey, (result as WarpImportResult.LicenseKeyOnly).licenseKey)
    }

    @Test
    fun testParseUuidLicenseKey() {
        val uuidKey = "d2b78129-e85c-4482-a0b4-106518a221f7"
        val result = WarpProfileParser.parse(uuidKey)

        assertTrue(result is WarpImportResult.LicenseKeyOnly)
        assertEquals(uuidKey, (result as WarpImportResult.LicenseKeyOnly).licenseKey)
    }

    @Test
    fun testParseWireGuardConf() {
        val wgConf = """
            [Interface]
            PrivateKey = $testPrivKey
            Address = 172.16.0.5/32, 2606:4700:110:8735::2/128
            DNS = 1.1.1.1, 1.0.0.1
            MTU = 1280

            [Peer]
            PublicKey = $testPeerKey
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 162.159.192.1:2408
        """.trimIndent()

        val result = WarpProfileParser.parse(wgConf)
        assertTrue(result is WarpImportResult.Success)

        val success = result as WarpImportResult.Success
        assertEquals(ImportedProfileType.WIREGUARD_CONF, success.type)
        assertEquals("172.16.0.5", success.profile.clientIpv4)
        assertEquals("162.159.192.1:2408", success.profile.peerEndpoint)
        assertEquals(testPeerKey, success.profile.peerPublicKey)
        assertEquals(testPrivKey, success.profile.privateKeyBase64)
        assertTrue(success.profile.isUsable())

        // Public key derivation check
        val expectedPub = Base64.getEncoder().encodeToString(
            Curve25519.computePublicKey(Base64.getDecoder().decode(testPrivKey))
        )
        assertEquals(expectedPub, success.profile.publicKeyBase64)
    }

    @Test
    fun testParseAmneziaWgConf() {
        val awgConf = """
            [Interface]
            PrivateKey = $testPrivKey
            Address = 172.16.0.2/32
            DNS = 1.1.1.1
            MTU = 1280
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 0
            S2 = 0
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4

            [Peer]
            PublicKey = $testPeerKey
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 188.114.96.1:8095
        """.trimIndent()

        val result = WarpProfileParser.parse(awgConf)
        assertTrue(result is WarpImportResult.Success)

        val success = result as WarpImportResult.Success
        assertEquals(ImportedProfileType.AMNEZIA_WG_CONF, success.type)
        assertEquals("172.16.0.2", success.profile.clientIpv4)
        assertEquals("188.114.96.1:8095", success.profile.peerEndpoint)
        assertEquals(awgConf.trim(), success.profile.awgRawConfig)
        assertTrue(success.profile.isUsable())
    }

    @Test
    fun testParseWgcfJson() {
        val wgcfJson = """
            {
              "account_id": "cf-acc-12345",
              "access_token": "cf-token-abcde",
              "private_key": "$testPrivKey",
              "license_key": "2y0t64eH-r19g8jA3-4v70T2bH",
              "address": {
                "v4": "172.16.0.9",
                "v6": "2606:4700:110:8735:60e1:f708:9207:29cd"
              },
              "config": {
                "peers": [
                  {
                    "public_key": "$testPeerKey",
                    "endpoint": {
                      "host": "engage.cloudflareclient.com:2408",
                      "v4": "162.159.192.1:2408"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = WarpProfileParser.parse(wgcfJson)
        assertTrue(result is WarpImportResult.Success)

        val success = result as WarpImportResult.Success
        assertEquals(ImportedProfileType.CLOUDFLARE_JSON, success.type)
        assertEquals("cf-acc-12345", success.profile.accountId)
        assertEquals("cf-token-abcde", success.profile.token)
        assertEquals("172.16.0.9", success.profile.clientIpv4)
        assertEquals("engage.cloudflareclient.com:2408", success.profile.peerEndpoint)
        assertEquals(testPeerKey, success.profile.peerPublicKey)
        assertEquals("2y0t64eH-r19g8jA3-4v70T2bH", success.profile.licenseKey)
        assertTrue(success.profile.isWarpPlus)
        assertTrue(success.profile.isUsable())
    }

    @Test
    fun testParseBotFlatJson() {
        val botJson = """
            {
              "private_key": "$testPrivKey",
              "public_key": "custom-pub-key-123",
              "address": "172.16.0.7/32, 2606:4700::1/128",
              "endpoint": "188.114.97.1:8095",
              "license_key": ""
            }
        """.trimIndent()

        val result = WarpProfileParser.parse(botJson)
        assertTrue(result is WarpImportResult.Success)

        val success = result as WarpImportResult.Success
        assertEquals(ImportedProfileType.CLOUDFLARE_JSON, success.type)
        assertEquals("172.16.0.7", success.profile.clientIpv4)
        assertEquals("188.114.97.1:8095", success.profile.peerEndpoint)
        assertEquals("custom-pub-key-123", success.profile.publicKeyBase64)
        assertTrue(success.profile.isUsable())
    }

    @Test
    fun testParseInvalidInputs() {
        // Blank input
        assertTrue(WarpProfileParser.parse("") is WarpImportResult.Error)
        assertTrue(WarpProfileParser.parse("   ") is WarpImportResult.Error)

        // Invalid JSON without private_key
        val badJson = """{"foo": "bar"}"""
        assertTrue(WarpProfileParser.parse(badJson) is WarpImportResult.Error)

        // Corrupted base64 in INI
        val badIni = """
            [Interface]
            PrivateKey = not_a_valid_base64!!!
        """.trimIndent()
        assertTrue(WarpProfileParser.parse(badIni) is WarpImportResult.Error)

        // Unrecognized format
        assertTrue(WarpProfileParser.parse("random string with no structure") is WarpImportResult.Error)
    }
}
