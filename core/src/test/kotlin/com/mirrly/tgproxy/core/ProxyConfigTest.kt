package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyConfigTest {

    @Test
    fun testSanitizeDomainWithHttpsPrefix() {
        val input = "https://my-worker.user.workers.dev/"
        val sanitized = ProxyConfig.sanitizeDomain(input)
        assertEquals("my-worker.user.workers.dev", sanitized)
    }

    @Test
    fun testSanitizeDomainWithHttpPrefix() {
        val input = "http://my-worker.user.workers.dev/"
        val sanitized = ProxyConfig.sanitizeDomain(input)
        assertEquals("my-worker.user.workers.dev", sanitized)
    }

    @Test
    fun testSanitizeDomainWithWssPrefixAndPath() {
        val input = "wss://my-worker.user.workers.dev//tcp?target=149.154.175.50:443"
        val sanitized = ProxyConfig.sanitizeDomain(input)
        assertEquals("my-worker.user.workers.dev", sanitized)
    }

    @Test
    fun testSanitizeDomainWithWsPrefix() {
        val input = "ws://my-worker.user.workers.dev/ws"
        val sanitized = ProxyConfig.sanitizeDomain(input)
        assertEquals("my-worker.user.workers.dev", sanitized)
    }

    @Test
    fun testSanitizeDomainWithMixedCaseAndWhitespace() {
        val input = "   HTTPS://MY-WORKER.USER.WORKERS.DEV///   "
        val sanitized = ProxyConfig.sanitizeDomain(input)
        assertEquals("MY-WORKER.USER.WORKERS.DEV", sanitized)
    }

    @Test
    fun testSanitizeDomainWithCleanDomain() {
        val input = "my-worker.user.workers.dev"
        val sanitized = ProxyConfig.sanitizeDomain(input)
        assertEquals("my-worker.user.workers.dev", sanitized)
    }

    @Test
    fun testSanitizeDomainWithEmptyString() {
        val input = "   "
        val sanitized = ProxyConfig.sanitizeDomain(input)
        assertEquals("", sanitized)
    }

    @Test
    fun testSanitizeDomainWithShareUrl() {
        val input = "https://mirrly.app/worker?domain=sample-worker.custom-team.workers.dev/&name=Worker"
        val sanitized = ProxyConfig.sanitizeDomain(input)
        assertEquals("sample-worker.custom-team.workers.dev", sanitized)
    }

    @Test
    fun testSanitizeDomainWithMirrlySchemeDeepLink() {
        val input = "mirrly://worker?domain=my-tg.example.workers.dev:443"
        val sanitized = ProxyConfig.sanitizeDomain(input)
        assertEquals("my-tg.example.workers.dev", sanitized)
    }

    @Test
    fun testSanitizeDomainWithPort() {
        val input = "https://my-worker.workers.dev:8443/tcp"
        val sanitized = ProxyConfig.sanitizeDomain(input)
        assertEquals("my-worker.workers.dev", sanitized)
    }

    @Test
    fun testGetEffectiveCfDomainSanitizesCustomDomain() {
        val config = ProxyConfig(
            customCfDomain = "https://custom-worker.workers.dev//path"
        )
        assertEquals("custom-worker.workers.dev", config.getEffectiveCfDomain())
    }

    @Test
    fun testGetEffectiveCfDomainDefaultIsEmptyWhenNoCustomDomain() {
        val config = ProxyConfig(
            customCfDomain = ""
        )
        assertEquals("", config.getEffectiveCfDomain())
    }

    @Test
    fun testGetEffectiveCfDomainWithActiveWorker() {
        val workerDomain = "my-custom-proxy.user.workers.dev"
        val config = ProxyConfig(
            customCfDomain = workerDomain
        )
        assertEquals(workerDomain, config.getEffectiveCfDomain())
    }

    @Test
    fun testDefaultPoolSizeAndAutoPreset() {
        val config = ProxyConfig()
        assertEquals(2, config.mtprotoStandbyPerActiveSlot)
        assertEquals(SpeedPreset.AUTO, config.speedPreset)
        assertEquals(2, SpeedPreset.AUTO.defaultMtprotoStandbyPerActiveSlot)
        assertEquals(0, SpeedPreset.AUTO.defaultBufferSizeBytes)
        assertEquals(1443, config.bindPort)
        assertFalse(config.useWorkerMtproto)
    }

    @Test
    fun testSpeedPresetsConfiguration() {
        assertEquals("Эко (1 резерв/слот)", SpeedPreset.ECO.displayName)
        assertEquals(1, SpeedPreset.ECO.defaultMtprotoStandbyPerActiveSlot)
        assertEquals(131072, SpeedPreset.ECO.defaultBufferSizeBytes)

        assertEquals("Баланс (2 резерва/слот)", SpeedPreset.BALANCED.displayName)
        assertEquals(2, SpeedPreset.BALANCED.defaultMtprotoStandbyPerActiveSlot)
        assertEquals(262144, SpeedPreset.BALANCED.defaultBufferSizeBytes)

        assertEquals("Турбо (3 резерва/слот)", SpeedPreset.TURBO.displayName)
        assertEquals(3, SpeedPreset.TURBO.defaultMtprotoStandbyPerActiveSlot)
        assertEquals(524288, SpeedPreset.TURBO.defaultBufferSizeBytes)

        assertEquals("Ультра (4 резерва/слот)", SpeedPreset.ULTRA.displayName)
        assertEquals(4, SpeedPreset.ULTRA.defaultMtprotoStandbyPerActiveSlot)
        assertEquals(1048576, SpeedPreset.ULTRA.defaultBufferSizeBytes)

        assertEquals("Авто (динамический)", SpeedPreset.AUTO.displayName)
        assertEquals(2, SpeedPreset.AUTO.defaultMtprotoStandbyPerActiveSlot)
        assertEquals(0, SpeedPreset.AUTO.defaultBufferSizeBytes)
    }

    @Test
    fun testApplyPresetUpdatesPoolSizeAndBuffer() {
        val config = ProxyConfig()
        
        config.applyPreset(SpeedPreset.ECO)
        assertEquals(SpeedPreset.ECO.name, config.speedPresetName)
        assertEquals(1, config.mtprotoStandbyPerActiveSlot)
        assertEquals(131072, config.bufferSizeBytes)

        config.applyPreset(SpeedPreset.TURBO)
        assertEquals(SpeedPreset.TURBO.name, config.speedPresetName)
        assertEquals(3, config.mtprotoStandbyPerActiveSlot)
        assertEquals(524288, config.bufferSizeBytes)

        config.applyPreset(SpeedPreset.ULTRA)
        assertEquals(SpeedPreset.ULTRA.name, config.speedPresetName)
        assertEquals(4, config.mtprotoStandbyPerActiveSlot)
        assertEquals(1048576, config.bufferSizeBytes)

        config.applyPreset(SpeedPreset.AUTO)
        assertEquals(SpeedPreset.AUTO.name, config.speedPresetName)
        assertTrue(config.isAutoSpeedPreset)

        config.applyPreset(SpeedPreset.BALANCED)
        assertEquals(SpeedPreset.BALANCED.name, config.speedPresetName)
        assertEquals(2, config.mtprotoStandbyPerActiveSlot)
        assertEquals(262144, config.bufferSizeBytes)
    }

    @Test
    fun testDefaultNagleModeIsEnabled() {
        val config = ProxyConfig()
        assertEquals(NagleMode.ENABLED, config.nagleMode)
        assertEquals(NagleMode.ENABLED.name, config.tcpNoDelayModeName)
        assertFalse(config.tcpNoDelay)
        assertEquals("Включен", NagleMode.ENABLED.displayName)
        assertEquals("Выключен", NagleMode.DISABLED.displayName)
    }

    @Test
    fun testSecretPreservedAcrossProtocolSwitches() {
        val originalSecret = ProxyConfig.generateRandomSecret()
        val config = ProxyConfig(
            bindHost = "127.0.0.1",
            bindPort = 1443,
            socks5Port = 10888,
            secretHex = originalSecret,
            proxyModeName = ProxyMode.MTPROTO.name
        )
        val server = LocalProxyServer(config)

        val mtprotoUrlInitial = server.getTelegramProxyUrl()
        assertTrue(mtprotoUrlInitial.contains("secret=$originalSecret"))

        // Switch to SOCKS5
        config.proxyModeName = ProxyMode.SOCKS5.name
        assertEquals(originalSecret, config.secretHex)
        val socks5Url = server.getTelegramSocks5Url()
        assertTrue(socks5Url.startsWith("tg://socks?server=127.0.0.1&port=10888"))

        // Switch back to MTProto
        config.proxyModeName = ProxyMode.MTPROTO.name
        assertEquals(originalSecret, config.secretHex)
        val mtprotoUrlRestored = server.getTelegramProxyUrl()
        assertEquals(mtprotoUrlInitial, mtprotoUrlRestored)
    }

    @Test
    fun testUplinkModes() {
        val config = ProxyConfig()
        assertEquals(UplinkMode.WORKER, config.uplinkMode)

        config.uplinkModeName = UplinkMode.AWG.name
        assertEquals(UplinkMode.AWG, config.uplinkMode)

        // Для системного VPN используются vpnUplinkMode
        config.vpnUplinkModeName = UplinkMode.AWG.name
        assertTrue(config.isVpnAwgUplink)
        assertEquals(UplinkMode.AWG, config.vpnUplinkMode)
    }

    @Test
    fun testAmneziaWgConfigGeneration() {
        val config = ProxyConfig(
            warpPrivateKey = "mYPrivateKeY123=",
            warpClientIpv4 = "172.16.0.2",
            warpPeerEndpoint = "162.159.198.1:443"
        )
        val awg = config.getAmneziaWgConfig()
        assertTrue(awg.contains("PrivateKey = mYPrivateKeY123="))
        assertTrue(awg.contains("Address = 172.16.0.2/32"))
        assertTrue(awg.contains("Jc = 0"))
        assertTrue(awg.contains("S1 = 0"))
        assertTrue(awg.contains("S2 = 0"))
        assertTrue(awg.contains("H1 = 1"))
        assertTrue(awg.contains("H2 = 2"))
        assertTrue(awg.contains("H3 = 3"))
        assertTrue(awg.contains("H4 = 4"))
        assertTrue(!awg.contains("I1 ="), "AWG config must NOT contain hallucinated I1")
        assertTrue(awg.contains("Endpoint = 162.159.198.1:443"))

        // DEEP_STEALTH strategy generates recommended QUIC CPS I1 and Jc = 5 for custom VPS
        val deepAwg = config.getAmneziaWgConfig(cleanEndpoint = "198.51.100.25:51820", strategy = AwgObfuscationStrategy.DEEP_STEALTH)
        assertTrue(deepAwg.contains("Jc = 5"))
        assertTrue(deepAwg.contains("I1 = ${ProxyConfig.DEFAULT_QUIC_I1}"))

        // Explicit custom awgI1 overrides strategy default
        val customI1Cfg = ProxyConfig(
            warpPrivateKey = "mYPrivateKeY123=",
            warpClientIpv4 = "172.16.0.2",
            awgI1 = "<b 0xc000000001><rc 8><t><rd 4><r 32>"
        )
        val customAwg = customI1Cfg.getAmneziaWgConfig()
        assertTrue(customAwg.contains("I1 = <b 0xc000000001><rc 8><t><rd 4><r 32>"))
    }

    @Test
    fun testCloudflareWorkerOnlyRelaysTelegramTcp() {
        val workerCode = TgConstants.CLOUDFLARE_WORKER_JS_CODE
        assertTrue(workerCode.contains("url.pathname !== '/tcp' && url.pathname !== '/tcp-v2'"))
        assertTrue(workerCode.contains("Telegram destination not allowed"))
        assertTrue(!workerCode.contains("/warp-api"))
        assertTrue(!workerCode.contains("/warp-reg"))
    }

    @Test
    fun testVpnDnsModesAndIpv6LeakPrevention() {
        // 1. Default Cloudflare DNS mode
        val cfgDefault = ProxyConfig()
        assertEquals(VpnDnsMode.CLOUDFLARE, cfgDefault.vpnDnsMode)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), cfgDefault.getEffectiveVpnDnsIpv4())
        assertEquals("2606:4700:4700::1111", cfgDefault.getEffectiveVpnDnsIpv6())
        val awgDefault = cfgDefault.getAmneziaWgConfig()
        assertTrue(awgDefault.contains("DNS = 1.1.1.1, 1.0.0.1"))

        // 2. Explicit Comss.one SmartDNS mode
        val cfgComss = ProxyConfig(vpnDnsModeName = VpnDnsMode.COMSS_DNS.name)
        assertEquals(VpnDnsMode.COMSS_DNS, cfgComss.vpnDnsMode)
        assertEquals(ProxyConfig.COMSS_DNS_IPV4, cfgComss.getEffectiveVpnDnsIpv4())
        assertEquals(null, cfgComss.getEffectiveVpnDnsIpv6(), "SmartDNS must disable IPv6 DNS to prevent AAAA query bypass")
        val awgComss = cfgComss.getAmneziaWgConfig()
        assertTrue(awgComss.contains("DNS = ${ProxyConfig.COMSS_DNS_IPV4.joinToString(", ")}"))

        // Google & Quad9 modes
        val cfgGoogle = ProxyConfig(vpnDnsModeName = VpnDnsMode.GOOGLE.name)
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), cfgGoogle.getEffectiveVpnDnsIpv4())

        val cfgQuad9 = ProxyConfig(vpnDnsModeName = VpnDnsMode.QUAD9.name)
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), cfgQuad9.getEffectiveVpnDnsIpv4())

        // Legacy migration: XBOX_DNS name string should map to COMSS_DNS
        val cfgLegacyXbox = ProxyConfig(vpnDnsModeName = "XBOX_DNS")
        assertEquals(VpnDnsMode.COMSS_DNS, cfgLegacyXbox.vpnDnsMode)

        // 3. Custom DNS with valid IPv4
        val cfgCustom = ProxyConfig(
            vpnDnsModeName = VpnDnsMode.CUSTOM.name,
            vpnCustomDnsIps = "94.140.14.14, 94.140.15.15"
        )
        assertEquals(VpnDnsMode.CUSTOM, cfgCustom.vpnDnsMode)
        assertEquals(listOf("94.140.14.14", "94.140.15.15"), cfgCustom.getEffectiveVpnDnsIpv4())
        assertEquals(null, cfgCustom.getEffectiveVpnDnsIpv6())
        val awgCustom = cfgCustom.getAmneziaWgConfig()
        assertTrue(awgCustom.contains("DNS = 94.140.14.14, 94.140.15.15"))

        // 4. Custom DNS with blank input falls back to Cloudflare IPv4
        val cfgCustomBlank = ProxyConfig(
            vpnDnsModeName = VpnDnsMode.CUSTOM.name,
            vpnCustomDnsIps = "   "
        )
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), cfgCustomBlank.getEffectiveVpnDnsIpv4())

        // 5. Custom parser validates IPv4 syntax and skips invalid entries
        val parsed = ProxyConfig.parseCustomDnsIps(" 8.8.8.8 , invalid.host ; 8.8.4.4 \n 999.1.1.1 \t 1.1.1.1 ")
        assertEquals(listOf("8.8.8.8", "8.8.4.4", "1.1.1.1"), parsed)
    }

    @Test
    fun testIsCloudflareEndpoint() {
        // Cloudflare IPv4 ranges
        assertTrue(isCloudflareEndpoint("162.159.193.10:1701"))
        assertTrue(isCloudflareEndpoint("162.159.192.1:2408"))
        assertTrue(isCloudflareEndpoint("188.114.96.1:8095"))
        assertTrue(isCloudflareEndpoint("188.114.97.66:4500"))
        assertTrue(isCloudflareEndpoint("172.64.0.1:443"))
        assertTrue(isCloudflareEndpoint("1.1.1.1:53"))
        assertTrue(isCloudflareEndpoint("1.0.0.1"))

        // Cloudflare IPv6
        assertTrue(isCloudflareEndpoint("[2606:4700:d0::a29f:c001]:8443"))
        assertTrue(isCloudflareEndpoint("2606:4700:4700::1111"))

        // Cloudflare hostnames
        assertTrue(isCloudflareEndpoint("engage.cloudflareclient.com:2408"))
        assertTrue(isCloudflareEndpoint("cloudflareclient.com"))
        assertTrue(isCloudflareEndpoint("cdnjs.cloudflare.com:443"))
        assertTrue(isCloudflareEndpoint("teams.cloudflareaccess.com"))

        // Third-party VPS endpoints (non-Cloudflare)
        assertFalse(isCloudflareEndpoint("198.51.100.25:51820"))
        assertFalse(isCloudflareEndpoint("95.217.1.1:51820"))
        assertFalse(isCloudflareEndpoint("vpn.example.com:51820"))
        assertFalse(isCloudflareEndpoint("8.8.8.8:53"))
        assertFalse(isCloudflareEndpoint(null))
        assertFalse(isCloudflareEndpoint(""))
    }

    @Test
    fun testSanitizeAwgConfigForCloudflare() {
        val unsafeRaw = """
            [Interface]
            PrivateKey = aaaa
            Address = 172.16.0.2/32
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 15
            S2 = 35
            S3 = 10
            S4 = 20
            H1 = 100
            H2 = 200
            H3 = 300
            H4 = 400
            I1 = <b 0x01>
            DisableCookies = on
            RandomTrailers = on

            [Peer]
            PublicKey = bbbb
            Endpoint = 162.159.193.10:1701
        """.trimIndent()

        val sanitized = sanitizeAwgConfigForCloudflare(unsafeRaw)
        assertTrue(sanitized.contains("Jc = 0"))
        assertTrue(sanitized.contains("Jmin = 0"))
        assertTrue(sanitized.contains("Jmax = 0"))
        assertTrue(sanitized.contains("S1 = 0"))
        assertTrue(sanitized.contains("S2 = 0"))
        assertTrue(sanitized.contains("S3 = 0"))
        assertTrue(sanitized.contains("S4 = 0"))
        assertTrue(sanitized.contains("H1 = 1"))
        assertTrue(sanitized.contains("H2 = 2"))
        assertTrue(sanitized.contains("H3 = 3"))
        assertTrue(sanitized.contains("H4 = 4"))
        assertTrue(sanitized.contains("DisableCookies = off"))
        assertTrue(sanitized.contains("RandomTrailers = off"))
        assertFalse(sanitized.contains("I1 ="))
        assertFalse(sanitized.contains("Jc = 4"))
        assertFalse(sanitized.contains("H1 = 100"))
    }

    @Test
    fun testApplyWarpProfileForCloudflareDisablesJunkPackets() {
        val profile = WarpProfile(
            accountId = "test-cf-acc",
            token = "test-token",
            licenseKey = "",
            clientIpv4 = "172.16.0.2",
            clientIpv6 = "",
            peerEndpoint = "162.159.193.10:1701",
            peerPublicKey = ProxyConfig.DEFAULT_CF_PEER_KEY,
            privateKeyBase64 = "4B7hZt4c12pU7o+s3f79m1Q6gL8k3z1A5x7e9c0B2d4=",
            publicKeyBase64 = "testPub",
            isWarpPlus = false,
            isWarpEnabled = true,
            isRegistered = true,
            isActivated = true,
            credentialsValid = true,
            dataPlaneReady = true,
            awgJc = 4,
            awgJmin = 40,
            awgJmax = 70,
            awgS1 = 15,
            awgS2 = 35,
            awgH1 = 100L,
            awgRawConfig = """
                [Interface]
                PrivateKey = 4B7hZt4c12pU7o+s3f79m1Q6gL8k3z1A5x7e9c0B2d4=
                Address = 172.16.0.2/32
                Jc = 4
                S1 = 15
                [Peer]
                PublicKey = ${ProxyConfig.DEFAULT_CF_PEER_KEY}
                Endpoint = 162.159.193.10:1701
            """.trimIndent()
        )

        val config = ProxyConfig()
        config.applyWarpProfile(profile)

        // For Cloudflare WARP, Jc must be strictly 0
        assertEquals(0, config.awgJc)
        assertEquals(0, config.awgJmin)
        assertEquals(0, config.awgJmax)
        assertEquals(0, config.awgS1)
        assertEquals(0, config.awgS2)
        assertEquals(1L, config.awgH1)
        assertEquals(2L, config.awgH2)
        assertEquals(3L, config.awgH3)
        assertEquals(4L, config.awgH4)

        val generatedAwg = config.getAmneziaWgConfig()
        assertTrue(generatedAwg.contains("Jc = 0"))
        assertTrue(generatedAwg.contains("S1 = 0"))
        assertTrue(generatedAwg.contains("H1 = 1"))
        assertFalse(generatedAwg.contains("Jc = 4"))
        assertFalse(generatedAwg.contains("S1 = 15"))
    }

    @Test
    fun testApplyWarpProfileForCustomVpsPreservesAwgParams() {
        val customPeerKey = "t9Zk4c12pU7o+s3f79m1Q6gL8k3z1A5x7e9c0B2d4="
        val vpsEndpoint = "198.51.100.25:51820"
        val customRaw = """
            [Interface]
            PrivateKey = 4B7hZt4c12pU7o+s3f79m1Q6gL8k3z1A5x7e9c0B2d4=
            Address = 10.8.0.2/24
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

        val profile = WarpProfile(
            accountId = "custom-vps-acc",
            token = "custom-token",
            licenseKey = "",
            clientIpv4 = "10.8.0.2",
            clientIpv6 = "",
            peerEndpoint = vpsEndpoint,
            peerPublicKey = customPeerKey,
            privateKeyBase64 = "4B7hZt4c12pU7o+s3f79m1Q6gL8k3z1A5x7e9c0B2d4=",
            publicKeyBase64 = "testPub",
            isWarpPlus = false,
            isWarpEnabled = true,
            isRegistered = true,
            isActivated = true,
            credentialsValid = true,
            dataPlaneReady = true,
            awgJc = 5,
            awgJmin = 50,
            awgJmax = 80,
            awgS1 = 20,
            awgS2 = 40,
            awgH1 = 100L,
            awgH2 = 200L,
            awgH3 = 300L,
            awgH4 = 400L,
            awgRawConfig = customRaw
        )

        val config = ProxyConfig()
        config.applyWarpProfile(profile)

        // For 3rd-party VPS, custom parameters must be preserved!
        assertEquals(5, config.awgJc)
        assertEquals(50, config.awgJmin)
        assertEquals(80, config.awgJmax)
        assertEquals(20, config.awgS1)
        assertEquals(40, config.awgS2)
        assertEquals(100L, config.awgH1)
        assertEquals(200L, config.awgH2)
        assertEquals(300L, config.awgH3)
        assertEquals(400L, config.awgH4)
        assertEquals(customRaw, config.awgCustomIni)

        val generatedAwg = config.getAmneziaWgConfig()
        assertTrue(generatedAwg.contains("Jc = 5"))
        assertTrue(generatedAwg.contains("S1 = 20"))
        assertTrue(generatedAwg.contains("H1 = 100"))
    }

    @Test
    fun testApplyWarpProfileWithOverrideDnsFalsePreservesUserDns() {
        val profile = WarpProfile(
            accountId = "acc-test",
            token = "tok-test",
            clientIpv4 = "172.16.0.2",
            clientIpv6 = "2606:4700:110:8612:a3ec:f441:d3fc:3f76",
            peerPublicKey = ProxyConfig.DEFAULT_CF_PEER_KEY,
            privateKeyBase64 = "4B7hZt4c12pU7o+s3f79m1Q6gL8k3z1A5x7e9c0B2d4=",
            publicKeyBase64 = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            dnsServers = "1.1.1.1, 1.0.0.1",
            isWarpEnabled = true,
            isRegistered = true,
            isActivated = true,
            credentialsValid = true,
            dataPlaneReady = true
        )

        val config = ProxyConfig(
            vpnDnsModeName = VpnDnsMode.COMSS_DNS.name,
            vpnCustomDnsIps = "83.220.169.155, 195.133.25.16"
        )

        // When connecting with built-in server, overrideDns is false
        config.applyWarpProfile(profile, overrideDns = false)

        assertEquals(VpnDnsMode.COMSS_DNS.name, config.vpnDnsModeName)
        assertEquals("83.220.169.155, 195.133.25.16", config.vpnCustomDnsIps)
        assertEquals(ProxyConfig.COMSS_DNS_IPV4, config.getEffectiveVpnDnsIpv4())
        val generatedAwg = config.getAmneziaWgConfig()
        assertTrue(generatedAwg.contains("DNS = ${ProxyConfig.COMSS_DNS_IPV4.joinToString(", ")}"))
    }

    @Test
    fun testIsTlsEnabledDefaultsToFalseAndCanBeEnabled() {
        val defaultConfig = ProxyConfig()
        assertFalse(defaultConfig.isTlsEnabled)

        val tlsOnConfig = ProxyConfig(isTlsEnabled = true, customCfDomain = "worker.example.dev")
        assertTrue(tlsOnConfig.isTlsEnabled)
        assertEquals("worker.example.dev", tlsOnConfig.getEffectiveCfDomain())
    }

    @Test
    fun testIsSocks5WorkerMissingRespectsFlowsealAndWorkers() {
        val flowsealConfig = ProxyConfig(
            proxyModeName = ProxyMode.SOCKS5.name,
            socks5UplinkMode = ProxyConfig.SOCKS5_UPLINK_FLOWSEAL,
            customCfDomain = ""
        )
        assertFalse(flowsealConfig.isSocks5WorkerMissing())

        val workerMissingConfig = ProxyConfig(
            proxyModeName = ProxyMode.SOCKS5.name,
            socks5UplinkMode = ProxyConfig.SOCKS5_UPLINK_WORKER,
            customCfDomain = ""
        )
        assertTrue(workerMissingConfig.isSocks5WorkerMissing())
        assertFalse(workerMissingConfig.isSocks5WorkerMissing("fallback.example.workers.dev"))

        val mtprotoConfig = ProxyConfig(
            proxyModeName = ProxyMode.MTPROTO.name,
            socks5UplinkMode = ProxyConfig.SOCKS5_UPLINK_WORKER,
            customCfDomain = ""
        )
        assertFalse(mtprotoConfig.isSocks5WorkerMissing())
    }
}
