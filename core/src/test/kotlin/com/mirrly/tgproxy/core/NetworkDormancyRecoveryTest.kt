package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkDormancyRecoveryTest {
    @Test
    fun `network recovery wakes both monitors without changing generation`() {
        val server = LocalProxyServer()
        server.setNetworkDormancy(true)
        assertTrue(server.pingEngine.isDormant)
        assertTrue(server.activeLivenessProbe.isDormant)
        val generation = server.currentProfileGeneration.get()

        server.resumeNetworkMonitoring()
        assertFalse(server.pingEngine.isDormant)
        assertFalse(server.activeLivenessProbe.isDormant)
        assertEquals(generation, server.currentProfileGeneration.get())

        server.resumeNetworkMonitoring()
        assertEquals(generation, server.currentProfileGeneration.get())
    }
}
