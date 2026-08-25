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
        assertEquals(4, config.poolSize, "Default poolSize should be 4 sockets per DC to prevent battery drain")
        assertEquals(SpeedPreset.AUTO, config.speedPreset)
        assertEquals(4, SpeedPreset.AUTO.defaultPoolSize)
        assertEquals(262144, SpeedPreset.AUTO.defaultBufferSizeBytes)
    }

    @Test
    fun testSpeedPresetsConfiguration() {
        assertEquals("Эко (2 сокета)", SpeedPreset.ECO.displayName)
        assertEquals(2, SpeedPreset.ECO.defaultPoolSize)
        assertEquals(131072, SpeedPreset.ECO.defaultBufferSizeBytes)

        assertEquals("Баланс (4 сокета)", SpeedPreset.BALANCED.displayName)
        assertEquals(4, SpeedPreset.BALANCED.defaultPoolSize)
        assertEquals(262144, SpeedPreset.BALANCED.defaultBufferSizeBytes)

        assertEquals("Турбо (8 сокетов)", SpeedPreset.TURBO.displayName)
        assertEquals(8, SpeedPreset.TURBO.defaultPoolSize)
        assertEquals(1048576, SpeedPreset.TURBO.defaultBufferSizeBytes)

        assertEquals("Ультра (16 сокетов)", SpeedPreset.ULTRA.displayName)
        assertEquals(16, SpeedPreset.ULTRA.defaultPoolSize)
        assertEquals(2097152, SpeedPreset.ULTRA.defaultBufferSizeBytes)

        assertEquals("Авто (динамический)", SpeedPreset.AUTO.displayName)
        assertEquals(4, SpeedPreset.AUTO.defaultPoolSize)
        assertEquals(262144, SpeedPreset.AUTO.defaultBufferSizeBytes)
    }

    @Test
    fun testApplyPresetUpdatesPoolSizeAndBuffer() {
        val config = ProxyConfig()
        
        config.applyPreset(SpeedPreset.ECO)
        assertEquals(SpeedPreset.ECO.name, config.speedPresetName)
        assertEquals(2, config.poolSize)
        assertEquals(131072, config.bufferSizeBytes)

        config.applyPreset(SpeedPreset.TURBO)
        assertEquals(SpeedPreset.TURBO.name, config.speedPresetName)
        assertEquals(8, config.poolSize)
        assertEquals(1048576, config.bufferSizeBytes)

        config.applyPreset(SpeedPreset.ULTRA)
        assertEquals(SpeedPreset.ULTRA.name, config.speedPresetName)
        assertEquals(16, config.poolSize)
        assertEquals(2097152, config.bufferSizeBytes)

        config.applyPreset(SpeedPreset.AUTO)
        assertEquals(SpeedPreset.AUTO.name, config.speedPresetName)
        assertTrue(config.isAutoSpeedPreset)

        config.applyPreset(SpeedPreset.BALANCED)
        assertEquals(SpeedPreset.BALANCED.name, config.speedPresetName)
        assertEquals(4, config.poolSize)
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
    fun testTcpNoDelayModeParsingAndFallback() {
        val config = ProxyConfig()

        config.tcpNoDelayModeName = "ON"
        assertEquals(TcpNoDelayMode.ON, config.tcpNoDelayMode)

        config.tcpNoDelayModeName = "OFF"
        assertEquals(TcpNoDelayMode.OFF, config.tcpNoDelayMode)

        config.tcpNoDelayModeName = "INVALID_MODE"
        assertEquals(TcpNoDelayMode.AUTO, config.tcpNoDelayMode, "Invalid name should fallback to AUTO")
    }
}


