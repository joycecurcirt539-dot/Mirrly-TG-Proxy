/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WarpConfigGeneratorEngineTest {

    // Valid 32-byte Curve25519 private key for tests
    private val testPrivKey = "4B7hZt4c12pU7o+s3f79m1Q6gL8k3z1A5x7e9c0B2d4="
    private val testPeerKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    private val baseProfile = WarpProfile(
        accountId = "test-acc-1",
        token = "test-token-1",
        licenseKey = "",
        clientIpv4 = "172.16.0.2",
        clientIpv6 = "2606:4700:110:8735::2",
        peerEndpoint = "188.114.96.1:8095",
        peerPublicKey = testPeerKey,
        privateKeyBase64 = testPrivKey,
        publicKeyBase64 = "testPubKeyBase64",
        isWarpPlus = false,
        isWarpEnabled = true,
        isRegistered = true,
        isActivated = true,
        credentialsValid = true,
        dataPlaneReady = true
    )

    @Test
    fun testMaskPrivateKey() {
        val masked = WarpConfigGeneratorEngine.maskPrivateKey(testPrivKey)
        assertTrue(masked.startsWith("4B7h"))
        assertTrue(masked.endsWith("2d4="))
        assertFalse(masked.contains("m1Q6gL8k3z1A5x7e9c0B"))
        assertTrue(masked.contains("••••"))

        val shortKey = "short"
        val maskedShort = WarpConfigGeneratorEngine.maskPrivateKey(shortKey)
        assertEquals("••••••••••••••••", maskedShort)
    }

    @Test
    fun testResolveRangeValue() {
        // Scalar value
        val scalar = WarpConfigGeneratorEngine.resolveRangeValue("40", 0, 100, randomize = true)
        assertEquals("40", scalar)

        // Range without randomizing
        val nonRandRange = WarpConfigGeneratorEngine.resolveRangeValue("10-50", 0, 100, randomize = false)
        assertEquals("10-50", nonRandRange)

        // Range with randomizing
        val randRange = WarpConfigGeneratorEngine.resolveRangeValue("10-50", 0, 100, randomize = true)
        val intVal = randRange.toIntOrNull()
        assertNotNull(intVal)
        assertTrue(intVal!! in 10..50)

        // Off and blank
        assertEquals("off", WarpConfigGeneratorEngine.resolveRangeValue("off"))
        assertEquals("", WarpConfigGeneratorEngine.resolveRangeValue(""))
    }

    @Test
    fun testBuildAddressLine() {
        val v4Only = WarpConfigGeneratorEngine.buildAddressLine("172.16.0.2", "2606:4700::1", includeIPv6 = false)
        assertEquals("172.16.0.2/32", v4Only)

        val v4AndV6 = WarpConfigGeneratorEngine.buildAddressLine("172.16.0.2", "2606:4700::1", includeIPv6 = true)
        assertEquals("172.16.0.2/32, 2606:4700::1/128", v4AndV6)
    }

    @Test
    fun testBuildWireguardConfAndRoundTrip() {
        val conf = WarpConfigGeneratorEngine.buildWireguardConf(
            privateKey = testPrivKey,
            addressLine = "172.16.0.2/32",
            dnsLine = "1.1.1.1, 1.0.0.1",
            peerPublicKey = testPeerKey,
            endpoint = "188.114.96.1:8095"
        )

        assertTrue(conf.contains("[Interface]"))
        assertTrue(conf.contains("PrivateKey = $testPrivKey"))
        assertTrue(conf.contains("Address = 172.16.0.2/32"))
        assertTrue(conf.contains("DNS = 1.1.1.1, 1.0.0.1"))
        assertTrue(conf.contains("[Peer]"))
        assertTrue(conf.contains("PublicKey = $testPeerKey"))
        assertTrue(conf.contains("Endpoint = 188.114.96.1:8095"))

        val parseResult = WarpProfileParser.parse(conf)
        assertTrue(parseResult is WarpImportResult.Success)
        val success = parseResult as WarpImportResult.Success
        assertEquals(ImportedProfileType.WIREGUARD_CONF, success.type)
        assertEquals("172.16.0.2", success.profile.clientIpv4)
        assertEquals("188.114.96.1:8095", success.profile.peerEndpoint)
        assertEquals(testPrivKey, success.profile.privateKeyBase64)
    }

    @Test
    fun testBuildAwg1ConfAndInvariants() {
        val params = WarpAwgConfigParams(jc = 4, jmin = 40, jmax = 70)
        val conf = WarpConfigGeneratorEngine.buildAwg1Conf(
            privateKey = testPrivKey,
            addressLine = "172.16.0.2/32",
            dnsLine = "1.1.1.1",
            peerPublicKey = testPeerKey,
            endpoint = "188.114.96.1:8095",
            params = params
        )

        assertTrue(conf.contains("Jc = 4"))
        assertTrue(conf.contains("Jmin = 40"))
        assertTrue(conf.contains("Jmax = 70"))
        assertTrue(conf.contains("S1 = 0"))
        assertTrue(conf.contains("S2 = 0"))
        assertTrue(conf.contains("H1 = 1"))
        assertTrue(conf.contains("H4 = 4"))

        val parseResult = WarpProfileParser.parse(conf)
        assertTrue(parseResult is WarpImportResult.Success)
        val success = parseResult as WarpImportResult.Success
        assertEquals(ImportedProfileType.AMNEZIA_WG_CONF, success.type)
    }

    @Test
    fun testBuildAwg2Conf() {
        val params = WarpAwgConfigParams(jc = 5, jmin = 50, jmax = 80)
        val conf = WarpConfigGeneratorEngine.buildAwg2Conf(
            privateKey = testPrivKey,
            addressLine = "172.16.0.2/32",
            dnsLine = "1.1.1.1",
            peerPublicKey = testPeerKey,
            endpoint = "188.114.96.1:8095",
            params = params
        )

        assertTrue(conf.contains("S3 = 0"))
        assertTrue(conf.contains("S4 = 0"))
        assertTrue(conf.contains("H1 = 1"))
        assertTrue(conf.contains("H4 = 4"))

        val parseResult = WarpProfileParser.parse(conf)
        assertTrue(parseResult is WarpImportResult.Success)
    }

    @Test
    fun testBuildAwg3Conf() {
        val params = WarpAwgConfigParams(
            jc = 4,
            jmin = 40,
            jmax = 70,
            i1 = "10000000010000000000000000",
            contentPaddingAddition = "10-20",
            rekeyAfterTime = "600",
            keepaliveTimeout = "10"
        )
        val conf = WarpConfigGeneratorEngine.buildAwg3Conf(
            privateKey = testPrivKey,
            addressLine = "172.16.0.2/32",
            dnsLine = "1.1.1.1",
            peerPublicKey = testPeerKey,
            endpoint = "188.114.96.1:8095",
            params = params,
            randomize = false
        )

        assertTrue(conf.contains("I1 = 10000000010000000000000000"))
        assertTrue(conf.contains("ContentPaddingAddition = 10-20"))
        assertTrue(conf.contains("RekeyAfterTime = 600"))
        assertTrue(conf.contains("KeepaliveTimeout = 10"))

        val parseResult = WarpProfileParser.parse(conf)
        assertTrue(parseResult is WarpImportResult.Success)
    }

    @Test
    fun testBuildAwg31Conf() {
        val params = WarpAwgConfigParams(
            disableCookies = true
        )
        val conf = WarpConfigGeneratorEngine.buildAwg31Conf(
            privateKey = testPrivKey,
            addressLine = "172.16.0.2/32",
            dnsLine = "1.1.1.1",
            peerPublicKey = testPeerKey,
            endpoint = "188.114.96.1:8095",
            params = params
        )

        assertTrue(conf.contains("DisableCookies = on"))
        assertTrue(conf.contains("RandomTrailers = off"))

        val parseResult = WarpProfileParser.parse(conf)
        assertTrue(parseResult is WarpImportResult.Success)
    }

    @Test
    fun testBuildClashMetaYaml() {
        val yaml = WarpConfigGeneratorEngine.buildClashMetaYaml(
            profileName = "WARP-Test",
            privateKey = testPrivKey,
            ipv4 = "172.16.0.2",
            ipv6 = "2606:4700::1",
            includeIPv6 = true,
            dnsList = listOf("1.1.1.1", "1.0.0.1"),
            peerPublicKey = testPeerKey,
            endpoint = "188.114.96.1:8095"
        )

        assertTrue(yaml.contains("proxies:"))
        assertTrue(yaml.contains("name: \"WARP-Test\""))
        assertTrue(yaml.contains("type: wireguard"))
        assertTrue(yaml.contains("server: 188.114.96.1"))
        assertTrue(yaml.contains("port: 8095"))
        assertTrue(yaml.contains("ip: 172.16.0.2"))
        assertTrue(yaml.contains("ipv6: 2606:4700::1"))
        assertTrue(yaml.contains("public-key: $testPeerKey"))
        assertTrue(yaml.contains("private-key: $testPrivKey"))

        val parseResult = WarpProfileParser.parse(yaml)
        assertTrue(parseResult is WarpImportResult.Success)
        val success = parseResult as WarpImportResult.Success
        assertEquals(ImportedProfileType.CLASH_META_YAML, success.type)
        assertEquals("172.16.0.2", success.profile.clientIpv4)
        assertEquals("188.114.96.1:8095", success.profile.peerEndpoint)
    }

    @Test
    fun testValidateWarpSafety() {
        val safeParams = WarpAwgConfigParams(
            s1 = 0, s2 = 0, s3 = 0, s4 = 0,
            h1 = 1L, h2 = 2L, h3 = 3L, h4 = 4L
        )
        val (safeOk, safeWarnings) = WarpConfigGeneratorEngine.validateWarpSafety(WarpGeneratorFormat.AWG_1_0, safeParams)
        assertTrue(safeOk)
        assertTrue(safeWarnings.isEmpty())

        val unsafeParams = WarpAwgConfigParams(
            s1 = 5,
            h1 = 99L
        )
        val (unsafeOk, unsafeWarnings) = WarpConfigGeneratorEngine.validateWarpSafety(WarpGeneratorFormat.AWG_1_0, unsafeParams)
        assertFalse(unsafeOk)
        assertEquals(2, unsafeWarnings.size)
    }

    @Test
    fun testGenerateBatch() {
        val settings = WarpGeneratorBuildSettings(
            formats = setOf(WarpGeneratorFormat.WIREGUARD, WarpGeneratorFormat.AWG_1_0),
            endpoints = listOf(WarpEndpointPresetOption.CLEAN_8095, WarpEndpointPresetOption.ENGAGE_ALT),
            dnsPresets = listOf(WarpDnsPresetOption.CLOUDFLARE)
        )

        val batch = WarpConfigGeneratorEngine.generateBatch(baseProfile, settings)
        // 2 formats * 2 endpoints * 1 dns = 4 items
        assertEquals(4, batch.size)

        for (item in batch) {
            assertTrue(item.content.isNotBlank())
            assertTrue(item.isWarpSafe)
            assertNotNull(item.associatedWarpProfile)
            assertEquals(item.endpoint, item.associatedWarpProfile.peerEndpoint)
        }
    }

    @Test
    fun testResolveCountryCodeForEndpoint() {
        assertEquals("DE", resolveCountryCodeForEndpoint("188.114.96.1:8095"))
        assertEquals("DE", resolveCountryCodeForEndpoint("188.114.97.1:4500"))
        assertEquals("DE", resolveCountryCodeForEndpoint("188.114.97.66:4500"))
        assertEquals("DE", resolveCountryCodeForEndpoint("188.114.96.125:4500"))
        assertEquals("NL", resolveCountryCodeForEndpoint("188.114.96.2:8443"))
        assertEquals("NL", resolveCountryCodeForEndpoint("188.114.97.2:8443"))
        assertEquals("FI", resolveCountryCodeForEndpoint("188.114.96.3:8095"))
        assertEquals("FI", resolveCountryCodeForEndpoint("188.114.97.3:8095"))
        assertEquals("SE", resolveCountryCodeForEndpoint("188.114.96.5:8095"))
        assertEquals("SE", resolveCountryCodeForEndpoint("188.114.97.5:8095"))
        assertEquals("SE", resolveCountryCodeForEndpoint("188.114.97.15:8095"))
        assertEquals("GB", resolveCountryCodeForEndpoint("188.114.96.10:8095"))
        assertEquals("GB", resolveCountryCodeForEndpoint("188.114.97.10:8095"))
        assertEquals("US", resolveCountryCodeForEndpoint("162.159.192.1:2408"))
        assertEquals("US", resolveCountryCodeForEndpoint("162.159.193.1:2408"))
        assertEquals("US", resolveCountryCodeForEndpoint("162.159.204.1:2408"))
        assertEquals("GLOBAL", resolveCountryCodeForEndpoint("162.159.195.1:500"))
        assertEquals("GLOBAL", resolveCountryCodeForEndpoint("engage.cloudflareclient.com:2408"))
        assertEquals("GLOBAL", resolveCountryCodeForEndpoint("[2606:4700:d0::a29f:c001]:8443"))
        assertEquals("RANDOM", resolveCountryCodeForEndpoint("random"))
        assertEquals("CUSTOM", resolveCountryCodeForEndpoint("custom"))
    }
}

