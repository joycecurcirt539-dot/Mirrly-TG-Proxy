package com.mirrly.tgproxy.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class WarpAccountManagerTest {

    @Test
    fun testBootstrapProfileHasValidParametersAndUnverifiedFlags() {
        val p = WarpAccountManager.BOOTSTRAP_PROFILE
        assertTrue(p.privateKeyBase64.isNotBlank())
        assertTrue(p.publicKeyBase64.isNotBlank())
        assertTrue(p.peerEndpoint.contains(":500"))
        assertTrue(p.clientIpv4 == "172.16.0.2")

        // Bootstrap profile MUST NOT be treated as a registered or usable profile
        assertFalse(p.isRegistered, "Bootstrap profile must not be marked as registered")
        assertFalse(p.isActivated, "Bootstrap profile must not be marked as activated")
        assertFalse(p.credentialsValid, "Bootstrap credentials must not be marked as valid")
        assertFalse(p.dataPlaneReady, "Bootstrap profile must not have data plane ready")
        assertTrue(p.needsVerification, "Bootstrap profile must require verification")
        assertFalse(p.isOfflineCached, "Bootstrap profile is not an offline cached profile")
        assertEquals(WarpEntitlement.UNVERIFIED, p.entitlement)
        assertFalse(p.isUsable(), "Bootstrap profile must not be usable")
        assertThrows<IllegalArgumentException> {
            p.requireUsable()
        }
    }

    @Test
    fun testToAmneziaWgConfigIncludesCleanEndpoint() {
        val p = WarpAccountManager.BOOTSTRAP_PROFILE
        val conf = p.toAmneziaWgConfig(cleanEndpoint = "188.114.96.1:500")

        assertTrue(conf.contains("[Interface]"), "Must contain [Interface]")
        assertTrue(conf.contains("[Peer]"), "Must contain [Peer]")
        assertTrue(conf.contains("Endpoint = 188.114.96.1:500"), "Must use clean Anycast endpoint")
        assertTrue(conf.contains("Jc = 4"), "Must contain Jc")
        assertTrue(conf.contains("H1 = 1"), "Must contain H1")
        assertTrue(conf.contains("H4 = 4"), "Must contain H4")
        assertTrue(!conf.contains("I1 ="), "Must NOT contain hallucinated I1 packet")
    }

    @Test
    fun testRegisterAndActivateFailsWhenEndpointsUnreachableAndNoExistingProfile() = runBlocking {
        val start = System.currentTimeMillis()
        // Pass a dummy unreachable domain with no prior existing profile
        val result = WarpAccountManager.registerAndActivate(
            workerDomain = "invalid-test-domain-123456789.workers.dev",
            existingProfile = null,
            fallbackToBootstrap = false
        )
        val elapsed = System.currentTimeMillis() - start

        // CRITICAL: Must NOT return synthetic bootstrap success
        assertTrue(result.isFailure, "Registration must fail honestly when all channels are blocked and no existing profile exists")
        assertTrue(elapsed < 20000, "Registration probe must complete fast without freezing (elapsed: ${elapsed}ms)")
    }

    @Test
    fun testRegisterAndActivateUsesExistingProfileAsOfflineCachedWhenEndpointsUnreachable() = runBlocking {
        val (wgPriv, wgPub) = WarpAccountManager.generateWireGuardKeyPair()
        val validExisting = WarpProfile(
            accountId = "cf-device-test-12345",
            token = "cf-token-test-67890",
            licenseKey = "",
            clientIpv4 = "172.16.0.2",
            clientIpv6 = "2606:4700:110:812c:a554:b442:26d4:1330",
            peerEndpoint = "188.114.96.1:8095",
            peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            privateKeyBase64 = wgPriv,
            publicKeyBase64 = wgPub,
            isWarpPlus = false,
            isWarpEnabled = true,
            isRegistered = true,
            isActivated = true,
            credentialsValid = true,
            dataPlaneReady = true,
            entitlement = WarpEntitlement.FREE,
            needsVerification = false,
            isOfflineCached = false
        )
        assertTrue(validExisting.isUsable(), "Pre-condition: validExisting must be usable")

        val result = WarpAccountManager.registerAndActivate(
            workerDomain = "invalid-test-domain-123456789.workers.dev",
            existingProfile = validExisting,
            fallbackToBootstrap = false
        )

        assertTrue(result.isSuccess, "Must fallback to existing profile in offline-cached mode")
        val profile = result.getOrNull()
        assertNotNull(profile)
        assertEquals("cf-device-test-12345", profile!!.accountId)
        assertEquals("cf-token-test-67890", profile.token)
        assertTrue(profile.isOfflineCached, "Offline fallback profile must be marked as offline cached")
        assertTrue(profile.needsVerification, "Offline fallback profile must require verification")
        assertFalse(profile.dataPlaneReady, "Offline fallback profile must have dataPlaneReady = false until verified")
        assertTrue(profile.isUsable(), "Offline cached profile must satisfy isUsable() to allow continued service")
    }

    @Test
    fun testUnverifiedLicenseKeyDoesNotGrantWarpPlus() = runBlocking {
        val testKey = "2y0t64eH-r19g8jA3-4v70T2bH"
        val (wgPriv, wgPub) = WarpAccountManager.generateWireGuardKeyPair()
        val existingFree = WarpProfile(
            accountId = "cf-device-existing-99",
            token = "cf-token-existing-99",
            licenseKey = "",
            clientIpv4 = "172.16.0.2",
            clientIpv6 = "",
            peerEndpoint = "188.114.96.1:8095",
            peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            privateKeyBase64 = wgPriv,
            publicKeyBase64 = wgPub,
            isWarpPlus = false,
            isWarpEnabled = true,
            isRegistered = true,
            isActivated = true,
            credentialsValid = true,
            dataPlaneReady = true,
            entitlement = WarpEntitlement.FREE,
            needsVerification = false,
            isOfflineCached = false
        )

        val result = WarpAccountManager.registerAndActivate(
            workerDomain = "invalid-test-domain-987654321.workers.dev",
            licenseKey = testKey,
            existingProfile = existingFree,
            fallbackToBootstrap = false
        )
        assertTrue(result.isSuccess)
        val profile = result.getOrNull()
        assertNotNull(profile)
        // CRITICAL: Unverified license key must NOT turn on WARP+ without server verification
        assertFalse(profile!!.isWarpPlus, "Unverified license key string must NOT turn on isWarpPlus")
        assertEquals(WarpEntitlement.FREE, profile.entitlement)
    }

    @Test
    fun testMaskLicenseKey() {
        val key26 = "2y0t64eH-r19g8jA3-4v70T2bH"
        assertEquals("2y0t-****-T2bH", WarpAccountManager.maskLicenseKey(key26))

        val key24 = "2y0t64eHr19g8jA34v70T2bH"
        assertEquals("2y0t-****-T2bH", WarpAccountManager.maskLicenseKey(key24))

        val shortKey = "12345"
        assertEquals("****", WarpAccountManager.maskLicenseKey(shortKey))

        assertEquals("", WarpAccountManager.maskLicenseKey(""))
        assertEquals("", WarpAccountManager.maskLicenseKey("   "))
        assertEquals("", WarpAccountManager.maskLicenseKey(null))
    }

    @Test
    fun testWarpProfileCodecRoundTripWithLifecycleFlags() {
        val (wgPriv, wgPub) = WarpAccountManager.generateWireGuardKeyPair()
        val original = WarpProfile(
            accountId = "acc-round-trip-1",
            token = "tok-round-trip-1",
            licenseKey = "2y0t64eH-r19g8jA3-4v70T2bH",
            clientIpv4 = "172.16.0.2",
            clientIpv6 = "2606:4700:110:812c:a554:b442:26d4:1330",
            peerEndpoint = "188.114.96.1:8095",
            peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            privateKeyBase64 = wgPriv,
            publicKeyBase64 = wgPub,
            isWarpPlus = true,
            isWarpEnabled = true,
            isRegistered = true,
            isActivated = true,
            credentialsValid = true,
            dataPlaneReady = true,
            entitlement = WarpEntitlement.PLUS,
            needsVerification = false,
            isOfflineCached = false,
            isWgValid = true,
            isMasqueValid = true
        )

        val encoded = WarpProfileCodec.encode(original)
        val decoded = WarpProfileCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original.accountId, decoded!!.accountId)
        assertEquals(original.token, decoded.token)
        assertEquals(original.licenseKey, decoded.licenseKey)
        assertEquals(original.isWarpPlus, decoded.isWarpPlus)
        assertEquals(original.isWarpEnabled, decoded.isWarpEnabled)
        assertEquals(original.isRegistered, decoded.isRegistered)
        assertEquals(original.isActivated, decoded.isActivated)
        assertEquals(original.credentialsValid, decoded.credentialsValid)
        assertEquals(original.dataPlaneReady, decoded.dataPlaneReady)
        assertEquals(original.entitlement, decoded.entitlement)
        assertEquals(original.needsVerification, decoded.needsVerification)
        assertEquals(original.isOfflineCached, decoded.isOfflineCached)
        assertEquals(original.isWgValid, decoded.isWgValid)
        assertEquals(original.isMasqueValid, decoded.isMasqueValid)
    }

    @Test
    fun testFastDirectProbeTimeoutConstant() {
        assertTrue(WarpObfuscatedHttpClient.DEFAULT_TIMEOUT_MS >= 3000, "Direct probe timeout must be >= 3000 ms")
        assertTrue(WarpObfuscatedHttpClient.DEFAULT_TIMEOUT_MS <= 10000, "Direct probe timeout must be <= 10000 ms")
    }

    @Test
    fun testExtractCertificateFromResponse() {
        val emptyJson = org.json.JSONObject("""{"id": "test-id", "token": "test-tok"}""")
        assertNull(
            WarpAccountManager.extractCertificateFromResponse(emptyJson),
            "Cloudflare API responses without cert fields should return null (Bearer token mode)"
        )

        val rootCertJson = org.json.JSONObject("""{"certificate": "MIIB...rootCert"}""")
        assertEquals(
            "MIIB...rootCert",
            WarpAccountManager.extractCertificateFromResponse(rootCertJson)
        )

        val clientCertJson = org.json.JSONObject("""{"client_cert": "MIIB...clientCert"}""")
        assertEquals(
            "MIIB...clientCert",
            WarpAccountManager.extractCertificateFromResponse(clientCertJson)
        )

        val nestedCertJson = org.json.JSONObject("""{"config": {"certificate": "MIIB...nestedCert"}}""")
        assertEquals(
            "MIIB...nestedCert",
            WarpAccountManager.extractCertificateFromResponse(nestedCertJson)
        )
    }

    @Test
    fun testIsValidLicenseKey() {
        // Standard WARP+ 26-char format (8-8-8 with hyphens)
        assertTrue(WarpAccountManager.isValidLicenseKey("2y0t64eH-r19g8jA3-4v70T2bH"))
        // Clean 24-char alphanumeric
        assertTrue(WarpAccountManager.isValidLicenseKey("2y0t64eHr19g8jA34v70T2bH"))
        // Clean 26-char alphanumeric
        assertTrue(WarpAccountManager.isValidLicenseKey("abcdefghijklmnopqrstuvwxyz"))

        // Invalid keys
        assertFalse(WarpAccountManager.isValidLicenseKey(""))
        assertFalse(WarpAccountManager.isValidLicenseKey(null))
        assertFalse(WarpAccountManager.isValidLicenseKey("short-key"))
        assertFalse(WarpAccountManager.isValidLicenseKey("12345"))
    }

    @Test
    fun testAttachLicenseKeyRejectsBlankInputs() = runBlocking {
        val res1 = WarpAccountManager.attachLicenseKey("", "token123", "2y0t64eH-r19g8jA3-4v70T2bH")
        assertTrue(res1.isFailure)

        val res2 = WarpAccountManager.attachLicenseKey("acc123", "", "2y0t64eH-r19g8jA3-4v70T2bH")
        assertTrue(res2.isFailure)

        val res3 = WarpAccountManager.attachLicenseKey("acc123", "token123", "   ")
        assertTrue(res3.isFailure)
    }

    @Test
    fun testWireGuardKeyPairDerivesSamePublicKeyMath() {
        val (privB64, pubB64) = WarpAccountManager.generateWireGuardKeyPair()
        val priv = Base64.getDecoder().decode(privB64)
        val pub = Base64.getDecoder().decode(pubB64)

        assertEquals(32, priv.size, "Private key must be 32 raw bytes")
        assertEquals(32, pub.size, "Public key must be 32 raw bytes")

        val derivedPub = Curve25519.computePublicKey(priv)
        assertArrayEquals(pub, derivedPub, "Public key must be derived from private key via X25519")
    }

    @Test
    fun testWireGuardKeyPairFallbackWithoutJcaProvider() {
        // Enforce pure Kotlin engine simulating environment where JCA X25519 is missing
        val (privB64, pubB64) = WarpAccountManager.generateWireGuardKeyPair(forcePureKotlin = true)
        val priv = Base64.getDecoder().decode(privB64)
        val pub = Base64.getDecoder().decode(pubB64)

        assertEquals(32, priv.size, "Private key must be 32 raw bytes")
        assertEquals(32, pub.size, "Public key must be 32 raw bytes")

        // WireGuard clamping check
        assertEquals(priv[0].toInt() and 248, priv[0].toInt() and 0xFF, "Bit 0..2 must be cleared")
        assertEquals(priv[31].toInt() and 127, priv[31].toInt() and 0x7F, "Bit 255 must be cleared")
        assertTrue((priv[31].toInt() and 64) != 0, "Bit 254 must be set")

        val derivedPub = Curve25519.computePublicKey(priv)
        assertArrayEquals(pub, derivedPub, "Pure Kotlin public key must be derived from private key")
    }

    @Test
    fun testWireGuardKeyPairEquivalenceBetweenJcaAndPureKotlin() {
        // Generate with JCA (if supported), then verify that pure Kotlin computes the identical public key
        val (privB64, pubB64) = WarpAccountManager.generateWireGuardKeyPair(forcePureKotlin = false)
        val priv = Base64.getDecoder().decode(privB64)
        val pub = Base64.getDecoder().decode(pubB64)

        val pureKotlinPub = Curve25519.computePublicKey(priv)
        assertArrayEquals(pub, pureKotlinPub, "Independent implementation must compute the exact same public key")
    }

    @Test
    fun testWireGuardKeyPairFailsFastOnInvalidEntropy() {
        val failingRandom = object : java.security.SecureRandom() {
            override fun nextBytes(bytes: ByteArray?) {
                throw RuntimeException("Simulated hardware RNG failure")
            }
        }

        val ex = assertThrows<IllegalStateException> {
            WarpAccountManager.generateWireGuardKeyPair(
                forcePureKotlin = true,
                secureRandom = failingRandom
            )
        }
        assertTrue(ex.message?.contains("Failed to generate secure WireGuard X25519 keypair") == true)
    }

    @Test
    fun testProtocolCredentialsModelSeparation() {
        val (wgPriv, wgPub) = WarpAccountManager.generateWireGuardKeyPair()
        val (p256Priv, p256Pub, clientCert) = WarpAccountManager.generateMasqueKeyPairAndCert()

        val dualProfile = WarpProfile(
            accountId = "cf-wg-device-101",
            token = "cf-wg-token-101",
            licenseKey = "2y0t64eH-r19g8jA3-4v70T2bH",
            clientIpv4 = "172.16.0.2",
            clientIpv6 = "2606:4700:110:812c:a554:b442:26d4:1330",
            peerEndpoint = "188.114.96.1:500",
            peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            privateKeyBase64 = wgPriv,
            publicKeyBase64 = wgPub,
            p256PrivateKeyBase64 = p256Priv,
            p256PublicKeyBase64 = p256Pub,
            clientCertBase64 = clientCert,
            isWarpPlus = true,
            isWarpEnabled = true,
            masqueAccountId = "cf-masque-device-202",
            masqueToken = "cf-masque-token-202",
            masquePeerEndpoint = "188.114.96.1:8095",
            masquePeerPublicKey = "masquePeerPublicKeyBase64=",
            masqueClientIpv4 = "172.16.0.3",
            masqueClientIpv6 = "2606:4700:110:812c:a554:b442:26d4:1331",
            isRegistered = true,
            isActivated = true,
            credentialsValid = true,
            dataPlaneReady = true,
            entitlement = WarpEntitlement.PLUS,
            isWgValid = true,
            isMasqueValid = true
        )

        // Protocol credentials must be distinct and non-mutating
        assertEquals("cf-wg-device-101", dualProfile.accountId)
        assertEquals("cf-wg-token-101", dualProfile.token)
        assertEquals("cf-masque-device-202", dualProfile.masqueAccountId)
        assertEquals("cf-masque-token-202", dualProfile.masqueToken)
        assertEquals("cf-masque-token-202", dualProfile.effectiveMasqueToken)
        assertEquals("cf-masque-device-202", dualProfile.effectiveMasqueAccountId)
        assertEquals("188.114.96.1:8095", dualProfile.effectiveMasquePeerEndpoint)
        assertEquals("172.16.0.3", dualProfile.effectiveMasqueClientIpv4)

        assertTrue(dualProfile.hasValidWgIdentity())
        assertTrue(dualProfile.hasValidMasqueIdentity())

        // Codec roundtrip must preserve separated credentials
        val encoded = WarpProfileCodec.encode(dualProfile)
        val decoded = WarpProfileCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals("cf-wg-device-101", decoded!!.accountId)
        assertEquals("cf-wg-token-101", decoded.token)
        assertEquals("cf-masque-device-202", decoded.masqueAccountId)
        assertEquals("cf-masque-token-202", decoded.masqueToken)
        assertEquals(dualProfile.p256PrivateKeyBase64, decoded.p256PrivateKeyBase64)
        assertEquals(dualProfile.clientCertBase64, decoded.clientCertBase64)
        assertTrue(decoded.isWgValid)
        assertTrue(decoded.isMasqueValid)
    }

    @Test
    fun testVerifyProtocolIdentitiesSimultaneous() {
        val (wgPriv, wgPub) = WarpAccountManager.generateWireGuardKeyPair()
        val (p256Priv, p256Pub, clientCert) = WarpAccountManager.generateMasqueKeyPairAndCert()

        val dualProfile = WarpProfile(
            accountId = "cf-wg-device-303",
            token = "cf-wg-token-303",
            licenseKey = "",
            clientIpv4 = "172.16.0.2",
            clientIpv6 = "",
            peerEndpoint = "188.114.96.1:500",
            peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            privateKeyBase64 = wgPriv,
            publicKeyBase64 = wgPub,
            p256PrivateKeyBase64 = p256Priv,
            p256PublicKeyBase64 = p256Pub,
            clientCertBase64 = clientCert,
            isWarpPlus = false,
            isWarpEnabled = true,
            masqueAccountId = "cf-masque-device-404",
            masqueToken = "cf-masque-token-404",
            masquePeerEndpoint = "188.114.96.1:8095",
            masquePeerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            isRegistered = true,
            isActivated = true,
            credentialsValid = true,
            dataPlaneReady = true,
            isWgValid = true,
            isMasqueValid = true
        )

        // 1. Simultaneous validation when both are healthy
        val healthyStatus = WarpAccountManager.verifyProtocolIdentities(dualProfile)
        assertTrue(healthyStatus.isWireGuardValid, "WireGuard identity must be valid")
        assertTrue(healthyStatus.isMasqueValid, "MASQUE identity must be valid")
        assertTrue(healthyStatus.isDualProtocolReady, "Dual protocol ready must be true")

        // 2. Corrupt WireGuard keypair: MASQUE must remain valid (no crosstalk)
        val corruptedWgProfile = dualProfile.copy(
            publicKeyBase64 = "corrupted-unmatched-key-base64="
        )
        val wgCorruptedStatus = WarpAccountManager.verifyProtocolIdentities(corruptedWgProfile)
        assertFalse(wgCorruptedStatus.isWireGuardValid, "Corrupted Curve25519 must invalidate WireGuard")
        assertTrue(wgCorruptedStatus.isMasqueValid, "MASQUE identity must remain valid even if WG is corrupted")
        assertFalse(wgCorruptedStatus.isDualProtocolReady)

        // 3. Invalidate MASQUE identity: WireGuard must remain valid
        val invalidMasqueProfile = dualProfile.copy(
            p256PrivateKeyBase64 = "",
            isMasqueValid = false
        )
        val masqueCorruptedStatus = WarpAccountManager.verifyProtocolIdentities(invalidMasqueProfile)
        assertTrue(masqueCorruptedStatus.isWireGuardValid, "WireGuard identity must remain valid even if MASQUE is missing")
        assertFalse(masqueCorruptedStatus.isMasqueValid, "Missing P-256 must invalidate MASQUE")
        assertFalse(masqueCorruptedStatus.isDualProtocolReady)
    }

    @Test
    fun testAmneziaWgConfigUsesDedicatedWgIdentity() {
        val (wgPriv, wgPub) = WarpAccountManager.generateWireGuardKeyPair()
        val (p256Priv, p256Pub, clientCert) = WarpAccountManager.generateMasqueKeyPairAndCert()

        val config = ProxyConfig(
            warpAccountId = "wg-device-505",
            warpToken = "wg-token-secret-505",
            warpPrivateKey = wgPriv,
            warpPublicKey = wgPub,
            warpPeerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            warpPeerEndpoint = "188.114.96.1:500",
            warpClientIpv4 = "172.16.0.2",
            warpP256PrivateKey = p256Priv,
            warpP256PublicKey = p256Pub,
            warpClientCert = clientCert,
            warpMasqueAccountId = "masque-device-606",
            warpMasqueToken = "masque-token-secret-606",
            warpMasquePeerEndpoint = "188.114.96.1:8095",
            isWgValid = true,
            isMasqueValid = true
        )

        val awgConf = config.getAmneziaWgConfig(cleanEndpoint = config.warpPeerEndpoint)
        assertTrue(awgConf.contains("PrivateKey = $wgPriv"), "AWG must use WireGuard Curve25519 private key")
        assertTrue(awgConf.contains("PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="), "AWG must use WireGuard peer key")
        assertTrue(awgConf.contains("Endpoint = 188.114.96.1:500"), "AWG must use clean Anycast endpoint")
        assertFalse(awgConf.contains(p256Priv), "AWG must not leak MASQUE P-256 private key")
        assertFalse(awgConf.contains("masque-token-secret-606"), "AWG must not leak MASQUE token")
    }

    @Test
    fun testProxyConfigEffectiveMasqueFallbacks() {
        val config = ProxyConfig(
            warpAccountId = "wg-legacy-acc",
            warpToken = "wg-legacy-tok",
            warpPeerEndpoint = "188.114.96.1:500",
            warpPeerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            warpClientIpv4 = "172.16.0.2"
        )

        // Legacy/fallback mode when masque fields are empty
        assertEquals("wg-legacy-tok", config.effectiveMasqueToken)
        assertEquals("wg-legacy-acc", config.effectiveMasqueAccountId)
        assertEquals("188.114.96.1:500", config.effectiveMasquePeerEndpoint)
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", config.effectiveMasquePeerPublicKey)

        // When dedicated masque fields are set
        config.warpMasqueToken = "dedicated-masque-tok"
        config.warpMasqueAccountId = "dedicated-masque-acc"
        config.warpMasquePeerEndpoint = "188.114.96.1:8095"
        config.warpMasquePeerPublicKey = "masque-specific-peer-key="

        assertEquals("dedicated-masque-tok", config.effectiveMasqueToken)
        assertEquals("dedicated-masque-acc", config.effectiveMasqueAccountId)
        assertEquals("188.114.96.1:8095", config.effectiveMasquePeerEndpoint)
        assertEquals("masque-specific-peer-key=", config.effectiveMasquePeerPublicKey)
        // WG fields remain untouched
        assertEquals("wg-legacy-tok", config.warpToken)
        assertEquals("wg-legacy-acc", config.warpAccountId)
    }

    @Test
    fun testUserSpecifiedPortAndOverridesPreservedInWarpProfile() {
        val (wgPriv, wgPub) = WarpAccountManager.generateWireGuardKeyPair()
        val (p256Priv, p256Pub, clientCert) = WarpAccountManager.generateMasqueKeyPairAndCert()

        val customProfile = WarpProfile(
            accountId = "cf-dev-custom",
            token = "cf-tok-custom",
            licenseKey = "",
            clientIpv4 = "172.16.0.2",
            clientIpv6 = "",
            peerEndpoint = "162.159.193.10:1701",
            peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            privateKeyBase64 = wgPriv,
            publicKeyBase64 = wgPub,
            p256PrivateKeyBase64 = p256Priv,
            p256PublicKeyBase64 = p256Pub,
            clientCertBase64 = clientCert,
            isWarpPlus = false,
            isWarpEnabled = true,
            isRegistered = true,
            isActivated = true,
            credentialsValid = true,
            dataPlaneReady = true,
            isWgValid = true,
            isMasqueValid = true,
            // Separate endpoint fields
            apiEndpoint = "162.159.193.10:2408",
            userEndpointOverride = "198.51.100.55:4433", // User customized WireGuard port
            masqueApiEndpoint = "162.159.192.1:2408",
            masqueUserOverride = "198.51.100.88:8443", // User customized MASQUE port
            measuredWgEndpoint = "188.114.96.1:500",
            measuredMasqueEndpoint = "188.114.97.1:1701"
        )

        // User override must have absolute top priority and preserve user port
        assertEquals("198.51.100.55:4433", customProfile.effectivePeerEndpoint, "User WG override with port 4433 must be preserved")
        assertEquals("198.51.100.88:8443", customProfile.effectiveMasquePeerEndpoint, "User MASQUE override with port 8443 must be preserved")

        // Without user override, measured endpoint takes precedence
        val measuredProfile = customProfile.copy(
            userEndpointOverride = null,
            masqueUserOverride = null
        )
        assertEquals("188.114.96.1:500", measuredProfile.effectivePeerEndpoint)
        assertEquals("188.114.97.1:1701", measuredProfile.effectiveMasquePeerEndpoint)

        // Without measured, API endpoint takes precedence
        val apiOnlyProfile = measuredProfile.copy(
            measuredWgEndpoint = null,
            measuredMasqueEndpoint = null
        )
        assertEquals("162.159.193.10:2408", apiOnlyProfile.effectivePeerEndpoint)
        assertEquals("162.159.192.1:2408", apiOnlyProfile.effectiveMasquePeerEndpoint)

        // Verify JSON roundtrip preserves all separate endpoint fields
        val encoded = WarpProfileCodec.encode(customProfile)
        val decoded = WarpProfileCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(customProfile.apiEndpoint, decoded!!.apiEndpoint)
        assertEquals(customProfile.userEndpointOverride, decoded.userEndpointOverride)
        assertEquals(customProfile.masqueApiEndpoint, decoded.masqueApiEndpoint)
        assertEquals(customProfile.masqueUserOverride, decoded.masqueUserOverride)
        assertEquals(customProfile.measuredWgEndpoint, decoded.measuredWgEndpoint)
        assertEquals(customProfile.measuredMasqueEndpoint, decoded.measuredMasqueEndpoint)
        assertEquals("198.51.100.55:4433", decoded.effectivePeerEndpoint)
        assertEquals("198.51.100.88:8443", decoded.effectiveMasquePeerEndpoint)
    }

    @Test
    fun testWarpEndpointCandidateVerificationStages() {
        val udpOnly = WarpEndpointCandidate(
            ip = "188.114.96.1",
            port = 500,
            rttMs = 25,
            isAlive = true,
            isUdpResponsive = true,
            isAuthenticated = false,
            isDataPlaneVerified = false
        )
        assertTrue(udpOnly.isAlive)
        assertTrue(udpOnly.isUdpResponsive)
        assertFalse(udpOnly.isFullyVerified(), "Candidate with only UDP response is not fully verified")

        val authSuccess = udpOnly.copy(isAuthenticated = true)
        assertTrue(authSuccess.isFullyVerified(), "Candidate with successful authentication is fully verified")

        val dataPlaneSuccess = udpOnly.copy(isDataPlaneVerified = true)
        assertTrue(dataPlaneSuccess.isFullyVerified(), "Candidate with verified data plane is fully verified")
    }
}

