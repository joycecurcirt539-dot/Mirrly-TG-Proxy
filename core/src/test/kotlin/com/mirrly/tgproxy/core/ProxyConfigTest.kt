package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
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
    fun testGetEffectiveCfDomainMtprotoDefaultEmpty() {
        val config = ProxyConfig(
            proxyModeName = ProxyMode.MTPROTO.name,
            customCfDomain = ""
        )
        assertEquals("", config.getEffectiveCfDomain())
    }

    @Test
    fun testGetEffectiveCfDomainSocks5DefaultEmpty() {
        val config = ProxyConfig(
            proxyModeName = ProxyMode.SOCKS5.name,
            customCfDomain = ""
        )
        assertEquals("", config.getEffectiveCfDomain())
    }
}
