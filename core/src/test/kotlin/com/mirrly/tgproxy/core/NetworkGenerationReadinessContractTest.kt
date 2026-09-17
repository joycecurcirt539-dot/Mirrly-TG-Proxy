package com.mirrly.tgproxy.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Source-level guards for the Android callback contract implemented by MOB-012. */
class NetworkGenerationReadinessContractTest {
    private val observerSource = File(
        "..",
        "app/src/main/java/com/mirrly/tgproxy/service/NetworkChangeObserver.kt"
    ).canonicalFile.readText()

    private val serviceSource = File(
        "..",
        "app/src/main/java/com/mirrly/tgproxy/service/ProxyForegroundService.kt"
    ).canonicalFile.readText()

    @Test
    fun `observer never races callbacks with a synchronous connectivity snapshot`() {
        assertFalse(observerSource.contains(".activeNetwork"))
        assertFalse(observerSource.contains("getNetworkCapabilities("))
        assertTrue(observerSource.contains("override fun onCapabilitiesChanged"))
        assertTrue(observerSource.contains("override fun onLinkPropertiesChanged"))
    }

    @Test
    fun `usable generation requires validation and a non-suspended network`() {
        assertTrue(observerSource.contains("NET_CAPABILITY_INTERNET"))
        assertTrue(observerSource.contains("NET_CAPABILITY_VALIDATED"))
        assertTrue(observerSource.contains("NET_CAPABILITY_NOT_SUSPENDED"))
        assertTrue(observerSource.contains("AVAILABLE_UNVALIDATED"))
        assertTrue(observerSource.contains("VALIDATED_STABLE"))
        assertTrue(observerSource.contains("SUSPENDED"))
    }

    @Test
    fun `service distinguishes initial delivery handover suspend and resume`() {
        assertTrue(serviceSource.contains("if (isInitial)"))
        assertTrue(serviceSource.contains("server.handleNetworkChanged("))
        assertTrue(serviceSource.contains("server.handleNetworkSuspended()"))
        assertTrue(serviceSource.contains("server.handleNetworkResumed("))
    }

    @Test
    fun `handover advances generation without resetting active bridges`() {
        val serverSource = File(
            "src/main/kotlin/com/mirrly/tgproxy/core/LocalProxyServer.kt"
        ).canonicalFile.readText()
        val functionStart = serverSource.indexOf("fun handleNetworkChanged(")
        val nextFunction = serverSource.indexOf("\n    /**", functionStart + 1)

        assertTrue(functionStart >= 0)
        assertTrue(nextFunction > functionStart)

        val functionBody = serverSource.substring(functionStart, nextFunction)
        assertTrue(functionBody.contains("nextGeneration()"))
        assertFalse(functionBody.contains("resetNetworkSockets"))
        assertFalse(functionBody.contains("setNetworkDormancy(false)"))
    }
}
