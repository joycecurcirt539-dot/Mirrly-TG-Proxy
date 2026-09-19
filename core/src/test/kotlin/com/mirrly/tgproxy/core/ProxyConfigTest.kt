package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
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
        val input = "https://mirrly.app/worker?domain=mirrly-tg-proxy-alpha.brawny-singer.workers.dev/&name=Worker"
        val sanitized = ProxyConfig.sanitizeDomain(input)
        assertEquals("mirrly-tg-proxy-alpha.brawny-singer.workers.dev", sanitized)
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
    fun testGetEffectiveCfDomainDefaultUsesDevWorker() {
        val config = ProxyConfig(
            customCfDomain = ""
        )
        assertEquals(TgConstants.DEFAULT_SOCKS5_DEV_WORKER, config.getEffectiveCfDomain())
    }

    @Test
    fun testGetEffectiveCfDomainWithActiveWorker() {
        val workerDomain = "mtg-relay-5o77p2.mtg-alfaj.workers.dev"
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
        assertEquals(262144, SpeedPreset.AUTO.defaultBufferSizeBytes)
    }

    @Test
    fun testSpeedPresetsConfiguration() {
        assertEquals("Эко (1 резерв/слот)", SpeedPreset.ECO.displayName)
        assertEquals(1, SpeedPreset.ECO.defaultMtprotoStandbyPerActiveSlot)
        assertEquals(131072, SpeedPreset.ECO.defaultBufferSizeBytes)

        assertEquals("Баланс (2 резерва/слот)", SpeedPreset.BALANCED.displayName)
        assertEquals(2, SpeedPreset.BALANCED.defaultMtprotoStandbyPerActiveSlot)
        assertEquals(262144, SpeedPreset.BALANCED.defaultBufferSizeBytes)

        assertEquals(262144, SpeedPreset.BALANCED.defaultBufferSizeBytes)

        assertEquals("Турбо (3 резерва/слот)", SpeedPreset.TURBO.displayName)
        assertEquals(3, SpeedPreset.TURBO.defaultMtprotoStandbyPerActiveSlot)
        assertEquals(1048576, SpeedPreset.TURBO.defaultBufferSizeBytes)

        assertEquals("Ультра (4 резерва/слот)", SpeedPreset.ULTRA.displayName)
        assertEquals(4, SpeedPreset.ULTRA.defaultMtprotoStandbyPerActiveSlot)
        assertEquals(2097152, SpeedPreset.ULTRA.defaultBufferSizeBytes)

        assertEquals("Авто (динамический)", SpeedPreset.AUTO.displayName)
        assertEquals(2, SpeedPreset.AUTO.defaultMtprotoStandbyPerActiveSlot)
        assertEquals(262144, SpeedPreset.AUTO.defaultBufferSizeBytes)
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
        assertEquals(1048576, config.bufferSizeBytes)

        config.applyPreset(SpeedPreset.ULTRA)
        assertEquals(SpeedPreset.ULTRA.name, config.speedPresetName)
        assertEquals(4, config.mtprotoStandbyPerActiveSlot)
        assertEquals(2097152, config.bufferSizeBytes)

        config.applyPreset(SpeedPreset.AUTO)
        assertEquals(SpeedPreset.AUTO.name, config.speedPresetName)
        assertTrue(config.isAutoSpeedPreset)

        config.applyPreset(SpeedPreset.BALANCED)
        assertEquals(SpeedPreset.BALANCED.name, config.speedPresetName)
        assertEquals(2, config.mtprotoStandbyPerActiveSlot)
        assertEquals(262144, config.bufferSizeBytes)
    }

    @Test
    fun testDefaultTcpNoDelayModeIsAuto() {
        val config = ProxyConfig()
        assertEquals(TcpNoDelayMode.AUTO, config.tcpNoDelayMode)
        assertEquals("AUTO", config.tcpNoDelayModeName)
        assertEquals("Авто", TcpNoDelayMode.AUTO.displayName)
        assertEquals("ВКЛ", TcpNoDelayMode.ON.displayName)
        assertEquals("ВЫКЛ", TcpNoDelayMode.OFF.displayName)
    }

    @Test
    fun testSecretPreservedAcrossProtocolSwitches() {
        val originalSecret = ProxyConfig.generateRandomSecret()
        val config = ProxyConfig(
            bindHost = "127.0.0.1",
            bindPort = 1443,
            socks5Port = 10808,
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
        assertTrue(socks5Url.startsWith("tg://socks?server=127.0.0.1&port=10808"))

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

        config.uplinkModeName = UplinkMode.MASQUE.name
        assertEquals(UplinkMode.MASQUE, config.uplinkMode)

        // Для системного VPN используются vpnUplinkMode
        config.vpnUplinkModeName = UplinkMode.MASQUE.name
        assertTrue(config.isVpnMasqueUplink)

        config.vpnUplinkModeName = UplinkMode.VLESS.name
        assertTrue(config.isVpnVlessUplink)
        assertEquals(UplinkMode.VLESS, config.vpnUplinkMode)
    }

    @Test
    fun testVlessShareUrlGeneration() {
        val config = ProxyConfig(
            customCfDomain = "my-custom-worker.workers.dev",
            vlessUuid = "d342d11e-d424-4583-b36e-524ab1f0afa4",
            vlessPath = "/"
        )
        val url = config.getVlessShareUrl()
        assertTrue(url.startsWith("vless://d342d11e-d424-4583-b36e-524ab1f0afa4@my-custom-worker.workers.dev:443"))
        assertTrue(url.contains("type=ws"))
        assertTrue(url.contains("security=tls"))
        assertTrue(url.contains("sni=my-custom-worker.workers.dev"))
        assertTrue(url.contains("host=my-custom-worker.workers.dev"))
        assertTrue(url.contains("#Mirrly-TG-Proxy"))

        val newUuid = ProxyConfig.generateVlessUuid()
        assertTrue(newUuid.isNotBlank())
        assertEquals(36, newUuid.length)
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
        assertTrue(awg.contains("Jc = 4"))
        assertTrue(awg.contains("S1 = 0"))
        assertTrue(awg.contains("S2 = 0"))
        assertTrue(awg.contains("H1 = 1"))
        assertTrue(awg.contains("H2 = 2"))
        assertTrue(awg.contains("H3 = 3"))
        assertTrue(awg.contains("H4 = 4"))
        assertTrue(!awg.contains("I1 ="), "AWG config must NOT contain hallucinated I1")
        assertTrue(awg.contains("Endpoint = 162.159.198.1:443"))
    }

    @Test
    fun testCloudflareWorkerIncludesWarpApiReverseProxy() {
        val workerCode = TgConstants.CLOUDFLARE_WORKER_JS_CODE
        assertTrue(workerCode.contains("/warp-api"), "Worker code must contain /warp-api route handler")
        assertTrue(workerCode.contains("/warp-reg"), "Worker code must contain /warp-reg route handler")
        assertTrue(workerCode.contains("api.cloudflareclient.com/v0a4471"), "Worker code must forward to Cloudflare client API v0a4471")
        assertTrue(workerCode.contains("Authorization"), "Worker code must forward Authorization header")
        assertTrue(workerCode.contains("OPTIONS"), "Worker code must handle OPTIONS CORS preflight")
    }
}
