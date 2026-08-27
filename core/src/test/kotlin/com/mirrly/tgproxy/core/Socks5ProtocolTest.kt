package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Socks5ProtocolTest {

    @Test
    fun testProxyConfigSocks5Defaults() {
        val config = ProxyConfig()
        assertEquals(10808, config.socks5Port)
        assertEquals(ProxyMode.MTPROTO, config.proxyMode)
        assertFalse(config.isSocks5Mode)
    }

    @Test
    fun testLocalProxyServerUrlGenerators() {
        val config = ProxyConfig(bindHost = "127.0.0.1", bindPort = 1443, socks5Port = 10808, proxyModeName = ProxyMode.SOCKS5.name)
        val server = LocalProxyServer(config)

        val socksUrl = server.getTelegramSocks5Url()
        assertEquals("tg://socks?server=127.0.0.1&port=10808&user=&pass=", socksUrl)

        val mtprotoUrl = server.getTelegramProxyUrl()
        assertTrue(mtprotoUrl.startsWith("tg://proxy?server=127.0.0.1&port=1443&secret="))
    }

    @Test
    fun testDcMappingForSocks5() {
        // DC1
        assertEquals(Pair(1, false), TgConstants.findDcByTarget("149.154.175.50"))
        // DC2
        assertEquals(Pair(2, false), TgConstants.findDcByTarget("149.154.167.51"))
        // DC3
        assertEquals(Pair(3, false), TgConstants.findDcByTarget("149.154.175.100"))
        // DC4
        assertEquals(Pair(4, false), TgConstants.findDcByTarget("149.154.167.91"))
        // DC5
        assertEquals(Pair(5, false), TgConstants.findDcByTarget("91.108.56.130"))
        // DC203 (Test/Prod cluster)
        assertEquals(Pair(203, false), TgConstants.findDcByTarget("91.105.192.100"))
        // Named gateways
        assertEquals(Pair(2, false), TgConstants.findDcByTarget("venus.web.telegram.org"))
        assertEquals(Pair(1, false), TgConstants.findDcByTarget("pluto.web.telegram.org"))
        // Non-DC IP (e.g. VoIP reflector or external server)
        assertNull(TgConstants.findDcByTarget("1.1.1.1"))
        assertNull(TgConstants.findDcByTarget("example.com"))
    }

    @Test
    fun testNativeRunningFlagInSocks5Mode() {
        val config = ProxyConfig(bindHost = "127.0.0.1", socks5Port = 19876, proxyModeName = ProxyMode.SOCKS5.name)
        val server = LocalProxyServer(config)
        assertFalse(server.isNativeRunning)
        assertFalse(server.isRunning)

        // On plain JVM without JNI native lib, start returns false safely
        val ok = server.start()
        assertFalse(server.isNativeRunning)

        server.stop()
        assertFalse(server.isRunning)
        assertFalse(server.isNativeRunning)
    }

    @Test
    fun testUnstartedNativeProxyStopSafety() {
        assertFalse(NativeProxy.isStarted)
        // Calling stopProxy, getStats, setPoolSize, resetNetworkSockets on unstarted NativeProxy must be safe no-ops
        assertDoesNotThrow { NativeProxy.stopProxy() }
        assertNull(NativeProxy.getStats())
        assertNull(NativeProxy.getSecretWithPrefix())
        assertDoesNotThrow { NativeProxy.setPoolSize(4) }
        assertDoesNotThrow { NativeProxy.setSecret("testsecret") }
        assertDoesNotThrow { NativeProxy.resetNetworkSockets() }
        assertFalse(NativeProxy.isStarted)
    }

    @Test
    fun testLocalProxyServerNetworkReset() {
        val config = ProxyConfig(bindHost = "127.0.0.1", bindPort = 19870)
        val server = LocalProxyServer(config)
        assertDoesNotThrow { server.onNetworkRestored() }
        assertDoesNotThrow { server.resetWsPool() }
    }

    @Test
    fun testLogEntryMonotonicUniqueIds() {
        val entry1 = LogEntry(level = LogLevel.INFO, tag = "TestTag", rawMessage = "Message 1")
        val entry2 = LogEntry(level = LogLevel.INFO, tag = "TestTag", rawMessage = "Message 1")
        assertNotEquals(entry1.id, entry2.id)
        assertTrue(entry2.id > entry1.id)
    }

    @Test
    fun testLocalProxyServerApplyPoolSize() {
        val config = ProxyConfig()
        val server = LocalProxyServer(config)
        assertEquals(4, config.poolSize)

        server.applyPoolSize(16)
        assertEquals(16, config.poolSize)

        server.applyPoolSize(2)
        assertEquals(2, config.poolSize)

        // Out of range should clamp
        server.applyPoolSize(1)
        assertEquals(2, config.poolSize)

        server.applyPoolSize(32)
        assertEquals(16, config.poolSize)
    }
}
