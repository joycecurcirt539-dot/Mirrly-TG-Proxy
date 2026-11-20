package com.mirrly.tgproxy.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineSplitContractTest {

    private fun loadNativeProxySource(): String {
        val candidates = listOf(
            File("src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt"),
            File("core/src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt")
        )
        return candidates.first { it.exists() }.readText()
    }

    private fun extractInterfaceBlock(source: String, interfaceName: String): String {
        val marker = "interface $interfaceName : Library"
        val startIdx = source.indexOf(marker)
        assertTrue(startIdx >= 0, "Missing $marker declaration in NativeProxy.kt")
        val openBraceIdx = source.indexOf('{', startIdx)
        assertTrue(openBraceIdx >= 0, "Missing opening brace for $interfaceName")
        var depth = 0
        for (i in openBraceIdx until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(openBraceIdx + 1, i)
                    }
                }
            }
        }
        error("Unclosed brace for $interfaceName")
    }

    private fun extractDeclaredMethodNames(interfaceBody: String): Set<String> {
        return Regex("""^\s*fun\s+([A-Za-z0-9_]+)\s*\(""", RegexOption.MULTILINE)
            .findAll(interfaceBody)
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `ProxyLibrary and VpnLibrary split FFI symbols cleanly`() {
        val source = loadNativeProxySource()
        val proxyBody = extractInterfaceBlock(source, "ProxyLibrary")
        val vpnBody = extractInterfaceBlock(source, "VpnLibrary")

        assertTrue(proxyBody.contains("Native.load(\"mirrlyengine\", ProxyLibrary::class.java)"))
        assertTrue(vpnBody.contains("Native.load(\"mirrlyvpn\", VpnLibrary::class.java)"))

        val proxyMethods = extractDeclaredMethodNames(proxyBody)
        val vpnMethods = extractDeclaredMethodNames(vpnBody)

        val removedFromProxy = setOf(
            "StartVpn",
            "StopVpn",
            "SetVpnUplinkMode",
            "GetVpnStatusJson",
            "ResetVpnSockets",
            "NotifyVpnReconnect",
            "SetAwgConfig",
            "SetAwgConfigParams",
            "SetAwgWarpMode",
            "SetAwgKeepalive",
            "GetAwgStatus",
            "ProbeWarpEndpoints"
        )
        removedFromProxy.forEach { method ->
            assertFalse(
                proxyMethods.contains(method),
                "ProxyLibrary must not declare VPN/AWG symbol $method"
            )
        }

        val expectedProxyMethods = setOf(
            "StartProxy",
            "StartSocks5Proxy",
            "StopProxy",
            "ResetNetworkSockets",
            "EmergencyKillAllSockets",
            "SetTlsEnabled",
            "IsTlsEnabled",
            "SetProtectSocketCallback",
            "PingHost",
            "PingWorker",
            "ProbeTransportHealth",
            "GetStats",
            "GetUsefulRxSliJson",
            "FreeString",
            "GetEngineSecurityState",
            "GetSecurityPostureReportJson"
        )
        expectedProxyMethods.forEach { method ->
            assertTrue(
                proxyMethods.contains(method),
                "ProxyLibrary must keep proxy/security symbol $method"
            )
        }

        val expectedVpnMethods = setOf(
            "StartVpn",
            "StopVpn",
            "SetVpnUplinkMode",
            "GetVpnStatusJson",
            "ResetVpnSockets",
            "NotifyVpnReconnect",
            "SetAwgConfigParams",
            "SetAwgConfig",
            "SetAwgWarpMode",
            "SetAwgKeepalive",
            "GetAwgStatus",
            "ProbeWarpEndpoints",
            "ProbeVpnTransportHealth",
            "SetVpnTlsEnabled",
            "IsVpnTlsEnabled",
            "SetVpnProtectSocketCallback",
            "SetVpnNetworkGeneration",
            "SetVpnNetworkProfile",
            "SetVpnNetworkProfileJson",
            "SetVpnIpv6OnlyNetwork",
            "SetVpnBatteryQoSLevel",
            "OnVpnScreenWakeup",
            "SetVpnSocketBufferSizes",
            "GetVpnStats",
            "GetVpnLastError",
            "FreeVpnString"
        )
        assertEquals(expectedVpnMethods, vpnMethods, "VpnLibrary must declare the exact 26 mirrlyvpn FFI symbols")

        val setAwgSignature = Regex("""fun\s+SetAwgConfigParams\s*\(([\s\S]*?)\)\s*([^\r\n]*)""")
            .find(vpnBody)
        assertNotNull(setAwgSignature, "SetAwgConfigParams signature must exist on VpnLibrary")
        val params = setAwgSignature!!.groupValues[1]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        assertEquals(14, params.size, "SetAwgConfigParams must have 14 parameters")
        val returnSuffix = setAwgSignature.groupValues[2].trim()
        assertFalse(returnSuffix.startsWith(":"), "SetAwgConfigParams must return Unit, got '$returnSuffix'")
    }

    @Test
    fun `NativeProxy routes mode 4 probes to VpnLibrary and keeps VPN unloaded on proxy events`() {
        val nativeProxySrc = loadNativeProxySource()

        assertTrue(nativeProxySrc.contains("VpnLibrary.INSTANCE.ProbeVpnTransportHealth(domain, timeoutMs)"))
        assertTrue(nativeProxySrc.contains("VpnLibrary.INSTANCE.FreeVpnString(ptr)"))

        try {
            NativeProxy.setNetworkGeneration(1L)
            NativeProxy.setIpv6OnlyNetwork(false)
            NativeProxy.setBatteryQoSLevel(QoSThrottleLevel.NONE)
            NativeProxy.setSocketBufferSizes(65536, 65536)
            NativeProxy.onScreenWakeup()
            NativeProxy.setVpnTlsEnabled(false)
            assertFalse(NativeProxy.isVpnTlsEnabled())
            NativeProxy.setVpnTlsEnabled(true)
            assertTrue(NativeProxy.isVpnTlsEnabled())
            assertFalse(NativeProxy.isVpnLibraryLoaded, "VpnLibrary must remain unloaded before VPN usage")
        } finally {
            NativeProxy.setVpnTlsEnabled(true)
        }
    }

    @Test
    fun `GetStats and GetUsefulRxSliJson consumers degrade gracefully without awg or vpn fields`() {
        val stats = ProxyStats()
        stats.parseNativeStats("total=12 active=3 ws=7 cf=5 v2=2 v1_down=0 fp=1 fb=0 bad=0 err=0 pool=4/6 up=10KB down=50KB")
        assertEquals(3, stats.activeConnections.get())
        assertEquals(7L, stats.totalWsConnections.get())
        assertEquals(0L, stats.totalAwgConnections.get())

        val sliJson = """
            {
                "transport": "mtproto",
                "tls_enabled": true,
                "active_conns": 2,
                "connections_total": 10,
                "connections_ws": 6,
                "connections_cfproxy": 4,
                "rx_bytes": 4096,
                "tx_bytes": 1024
            }
        """.trimIndent()
        val snapshot = EngineSliSnapshot.fromJson(sliJson)
        assertNotNull(snapshot)
        assertEquals(0L, snapshot!!.connectionsAwg)
        assertFalse(snapshot.vpnEstablished)
        assertTrue(snapshot.tlsEnabled)
        assertEquals(6L, snapshot.connectionsWs)
    }

    @Test
    fun `VPN TLS state defaults to enabled when native library is not loaded`() {
        assertTrue(NativeProxy.isVpnTlsEnabled())
        assertFalse(NativeProxy.isVpnStarted)
    }
}
