package com.mirrly.tgproxy.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class WorkerFailoverFlowDrainTest {

    @Test
    fun `updateWorkerConfig must not reset network sockets or terminate active flows`() {
        val serverSource = File(
            "src/main/kotlin/com/mirrly/tgproxy/core/LocalProxyServer.kt"
        ).canonicalFile.readText()

        val start = serverSource.indexOf("fun updateWorkerConfig()")
        assertTrue(start >= 0, "updateWorkerConfig method must exist")
        val end = serverSource.indexOf("\n    /**", start + 1)
        assertTrue(end > start, "end of updateWorkerConfig must be found")

        val methodBody = serverSource.substring(start, end)
        assertTrue(
            methodBody.contains("setCfProxyConfig"),
            "updateWorkerConfig must apply new worker domain via setCfProxyConfig"
        )
        assertFalse(
            methodBody.contains("resetNetworkSockets"),
            "updateWorkerConfig must NEVER call resetNetworkSockets; existing flows must drain gracefully"
        )
    }

    @Test
    fun `LocalProxyServer declares dedicated emergencyKillAllSockets command`() {
        val serverSource = File(
            "src/main/kotlin/com/mirrly/tgproxy/core/LocalProxyServer.kt"
        ).canonicalFile.readText()

        val start = serverSource.indexOf("fun emergencyKillAllSockets(")
        assertTrue(start >= 0, "LocalProxyServer must declare emergencyKillAllSockets")
        val end = serverSource.indexOf("\n    /**", start + 1)
        assertTrue(end > start, "end of emergencyKillAllSockets must be found")

        val methodBody = serverSource.substring(start, end)
        assertTrue(
            methodBody.contains("NativeProxy.emergencyKillAllSockets"),
            "emergencyKillAllSockets must delegate to NativeProxy.emergencyKillAllSockets"
        )
    }

    @Test
    fun `NativeProxy declares EmergencyKillAllSockets FFI and emergencyKillAllSockets facade`() {
        val nativeProxySource = File(
            "src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt"
        ).canonicalFile.readText()

        assertTrue(
            nativeProxySource.contains("fun EmergencyKillAllSockets()"),
            "ProxyLibrary must declare EmergencyKillAllSockets()"
        )
        assertTrue(
            nativeProxySource.contains("fun emergencyKillAllSockets("),
            "NativeProxy facade must declare emergencyKillAllSockets()"
        )
    }

    @Test
    fun `WorkerFailoverManager declares emergencyKillExistingFlows command`() {
        val failoverSource = File(
            "../app/src/main/java/com/mirrly/tgproxy/service/WorkerFailoverManager.kt"
        ).canonicalFile.readText()

        assertTrue(
            failoverSource.contains("fun emergencyKillExistingFlows("),
            "WorkerFailoverManager must declare emergencyKillExistingFlows"
        )
        assertTrue(
            failoverSource.contains("emergencyKillAllSockets"),
            "emergencyKillExistingFlows must delegate to proxyServer.emergencyKillAllSockets"
        )
    }

    @Test
    fun `onWorkerChanged updates customCfDomain without advancing network profile generation`() {
        val config = ProxyConfig(
            proxyModeName = ProxyMode.SOCKS5.name,
            socks5Port = 10808,
            customCfDomain = "worker-initial.workers.dev"
        )
        val server = LocalProxyServer(config)
        val initialProfileGen = server.currentProfileGeneration.get()

        server.onWorkerChanged("worker-switched.workers.dev")

        assertEquals("worker-switched.workers.dev", config.customCfDomain)
        assertEquals(
            initialProfileGen,
            server.currentProfileGeneration.get(),
            "Worker switch must NOT increment network generation (existing flows must drain uninterrupted)"
        )

        assertDoesNotThrow {
            server.emergencyKillAllSockets("test_emergency_kill")
        }
    }

}
