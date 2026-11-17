/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun testParseAmneziaWgConfForCloudflareSanitizesJcAndS() {
        val awgConf = """
            [Interface]
            PrivateKey = $testPrivKey
            Address = 172.16.0.2/32
            DNS = 1.1.1.1
            MTU = 1280
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 15
            S2 = 35
            H1 = 100
            H2 = 200
            H3 = 300
            H4 = 400
            DisableCookies = on

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
        // Cloudflare Anycast: Jc, Jmin, Jmax, S1..S4 strictly forced to 0, H1..H4 to 1..4
        assertEquals(0, success.profile.awgJc)
        assertEquals(0, success.profile.awgJmin)
        assertEquals(0, success.profile.awgJmax)
        assertEquals(0, success.profile.awgS1)
        assertEquals(0, success.profile.awgS2)
        assertEquals(1L, success.profile.awgH1)
        assertEquals(2L, success.profile.awgH2)
        assertEquals(3L, success.profile.awgH3)
        assertEquals(4L, success.profile.awgH4)
        assertTrue(success.profile.awgRawConfig.contains("Jc = 0"))
        assertTrue(success.profile.awgRawConfig.contains("S1 = 0"))
        assertTrue(success.profile.awgRawConfig.contains("H1 = 1"))
        assertTrue(success.profile.awgRawConfig.contains("DisableCookies = off"))
        assertFalse(success.profile.awgRawConfig.contains("Jc = 4"))
        assertTrue(success.profile.isUsable())
    }

    @Test
    fun testParseAmneziaWgConfCustomVpsPreservesParameters() {
        val customPeerKey = "t9Zk4c12pU7o+s3f79m1Q6gL8k3z1A5x7e9c0B2d4="
        val vpsEndpoint = "198.51.100.25:51820"
        val awgConf = """
            [Interface]
            PrivateKey = $testPrivKey
            Address = 10.8.0.2/24
            DNS = 8.8.8.8
            MTU = 1420
            Jc = 5
            Jmin = 50
            Jmax = 80
            S1 = 20
            S2 = 40
            H1 = 100
            H2 = 200
            H3 = 300
            H4 = 400

            [Peer]
            PublicKey = $customPeerKey
            AllowedIPs = 0.0.0.0/0
            Endpoint = $vpsEndpoint
        """.trimIndent()

        val result = WarpProfileParser.parse(awgConf)
        assertTrue(result is WarpImportResult.Success)

        val success = result as WarpImportResult.Success
        assertEquals(ImportedProfileType.AMNEZIA_WG_CONF, success.type)
        assertEquals("10.8.0.2", success.profile.clientIpv4)
        assertEquals(vpsEndpoint, success.profile.peerEndpoint)
        // 3rd-party VPS: parameters MUST be preserved intact!
        assertEquals(5, success.profile.awgJc)
        assertEquals(50, success.profile.awgJmin)
        assertEquals(80, success.profile.awgJmax)
        assertEquals(20, success.profile.awgS1)
        assertEquals(40, success.profile.awgS2)
        assertEquals(100L, success.profile.awgH1)
        assertEquals(200L, success.profile.awgH2)
        assertEquals(300L, success.profile.awgH3)
        assertEquals(400L, success.profile.awgH4)
        assertEquals(awgConf.trim(), success.profile.awgRawConfig)
        assertTrue(success.profile.awgRawConfig.contains("Jc = 5"))
        assertTrue(success.profile.awgRawConfig.contains("S1 = 20"))
        assertTrue(success.profile.awgRawConfig.contains("H1 = 100"))
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

    @Test
    fun testParseAmneziaWgConfWithI1ToI5AndExport() {
        val i1Pattern = "<b 0xc000000001><rc 8><t><rd 4><r 32>"
        val i2Pattern = "<b 0xd100000001><r 64>"
        val awgConfWithI1 = """
            [Interface]
            PrivateKey = $testPrivKey
            Address = 172.16.0.2/32
            Reserved = 10, 20, 30
            I1 = $i1Pattern
            I2 = $i2Pattern
            DNS = 1.1.1.1
            MTU = 1280
            Jc = 4
            Jmin = 40
            Jmax = 70
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4

            [Peer]
            PublicKey = $testPeerKey
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 188.114.96.1:8095
        """.trimIndent()

        val result = WarpProfileParser.parse(awgConfWithI1)
        assertTrue(result is WarpImportResult.Success)

        val success = result as WarpImportResult.Success
        assertEquals(ImportedProfileType.AMNEZIA_WG_CONF, success.type)
        assertEquals(i1Pattern, success.profile.awgI1)
        assertEquals(i2Pattern, success.profile.awgI2)
        assertEquals("10, 20, 30", success.profile.clientId)

        // 1. Export via WarpProfile.toAmneziaWgConfig
        val exportedAwg = success.profile.toAmneziaWgConfig()
        assertTrue(exportedAwg.contains("I1 = $i1Pattern"), "Exported AWG must contain I1 directive")
        assertTrue(exportedAwg.contains("I2 = $i2Pattern"), "Exported AWG must contain I2 directive")
        assertTrue(exportedAwg.contains("Reserved = 10, 20, 30"), "Exported AWG must contain Reserved directive")

        // 2. Application to ProxyConfig and export via getAmneziaWgConfig
        val config = ProxyConfig()
        config.applyWarpProfile(success.profile)
        assertEquals(i1Pattern, config.awgI1)
        assertEquals(i2Pattern, config.awgI2)

        val proxyAwg = config.getAmneziaWgConfig(cleanEndpoint = "188.114.96.1:8095")
        assertTrue(proxyAwg.contains("I1 = $i1Pattern"), "ProxyConfig AWG must generate I1 in [Interface]")
        assertTrue(proxyAwg.contains("I2 = $i2Pattern"), "ProxyConfig AWG must generate I2 in [Interface]")
        assertTrue(proxyAwg.contains("Reserved = 10, 20, 30"), "ProxyConfig AWG must generate Reserved in [Interface]")

        // 3. Round-trip serialization through WarpProfileCodec
        val encoded = WarpProfileCodec.encode(success.profile)
        val decoded = WarpProfileCodec.decode(encoded)
        org.junit.jupiter.api.Assertions.assertNotNull(decoded)
        assertEquals(i1Pattern, decoded?.awgI1)
        assertEquals(i2Pattern, decoded?.awgI2)
        assertEquals("10, 20, 30", decoded?.clientId)
    }

    @Test
    fun testParseReservedFormatsInConfAndYaml() {
        // 1. CONF with bracketed Reserved = [5, 6, 7]
        val confBracketed = """
            [Interface]
            PrivateKey = $testPrivKey
            Address = 172.16.0.2/32
            Reserved = [5, 6, 7]
            DNS = 1.1.1.1
            MTU = 1280

            [Peer]
            PublicKey = $testPeerKey
            Endpoint = 188.114.96.1:8095
        """.trimIndent()
        val resConf = WarpProfileParser.parse(confBracketed)
        assertTrue(resConf is WarpImportResult.Success)
        val successConf = resConf as WarpImportResult.Success
        assertEquals("5, 6, 7", successConf.profile.clientId)
        assertEquals("5, 6, 7", successConf.profile.reserved)

        val cfg = ProxyConfig()
        cfg.applyWarpProfile(successConf.profile)
        assertEquals("5, 6, 7", cfg.warpClientId)
        assertTrue(cfg.getAmneziaWgConfig().contains("Reserved = 5, 6, 7"))

        // 2. YAML with reserved: [10, 20, 30]
        val yamlWithReserved = """
            type: wireguard
            server: 188.114.96.1
            port: 8095
            ip: 172.16.0.2
            public-key: $testPeerKey
            private-key: $testPrivKey
            reserved: [10, 20, 30]
        """.trimIndent()
        val resYaml = WarpProfileParser.parse(yamlWithReserved)
        assertTrue(resYaml is WarpImportResult.Success)
        val successYaml = resYaml as WarpImportResult.Success
        assertEquals("10, 20, 30", successYaml.profile.clientId)
        assertEquals("10, 20, 30", successYaml.profile.reserved)

        val cfgYaml = ProxyConfig()
        cfgYaml.applyWarpProfile(successYaml.profile)
        assertEquals("10, 20, 30", cfgYaml.warpClientId)

        // 3. JSON with reserved array: [11, 22, 33]
        val jsonWithReservedArray = """
            {
              "private_key": "$testPrivKey",
              "public_key": "$testPeerKey",
              "address": "172.16.0.2/32",
              "endpoint": "188.114.96.1:8095",
              "reserved": [11, 22, 33]
            }
        """.trimIndent()
        val resJson = WarpProfileParser.parse(jsonWithReservedArray)
        assertTrue(resJson is WarpImportResult.Success)
        val successJson = resJson as WarpImportResult.Success
        assertEquals("11, 22, 33", successJson.profile.clientId)
        assertEquals("11, 22, 33", successJson.profile.reserved)
    }
}
