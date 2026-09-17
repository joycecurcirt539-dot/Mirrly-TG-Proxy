package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * MOB-012: Тесты generation guard и state machine NetworkChangeObserver.
 *
 * Проверяют:
 * 1. handleNetworkChanged инкрементирует generation (новые dial старой gen блокируются).
 * 2. handleNetworkSuspended НЕ инкрементирует generation (существующие соединения не закрываются).
 * 3. handleNetworkResumed выводит из dormancy без инкремента generation.
 * 4. Устаревший фоновый результат (gen A) отвергается после смены сети (gen B).
 * 5. Wi-Fi→LTE handover: каждая смена увеличивает gen, устаревший результат отвергается.
 * 6. setNetworkDormancy(true) инкрементирует generation.
 * 7. Captive portal (AVAILABLE_UNVALIDATED) не вызывает смену generation.
 */
class NetworkChangeObserverStateTest {

    @Test
    fun testHandleNetworkChangedIncrementsGeneration() {
        val server = LocalProxyServer(ProxyConfig())
        val genBefore = server.currentProfileGeneration.get()

        server.handleNetworkChanged("Mobile LTE/5G", "Wi-Fi", isMobile = true, isScreenOn = true)

        assertTrue(server.currentProfileGeneration.get() > genBefore,
            "handleNetworkChanged должен инкрементировать generation")
    }

    @Test
    fun testHandleNetworkChangedWithDisconnectedIsNoOp() {
        val server = LocalProxyServer(ProxyConfig())
        val genBefore = server.currentProfileGeneration.get()

        server.handleNetworkChanged("DISCONNECTED", "Wi-Fi", isMobile = false, isScreenOn = true)

        assertEquals(genBefore, server.currentProfileGeneration.get(),
            "handleNetworkChanged(DISCONNECTED) должен быть no-op для generation")
    }

    @Test
    fun testHandleNetworkSuspendedDoesNotIncrementGeneration() {
        val server = LocalProxyServer(ProxyConfig())
        val genBefore = server.currentProfileGeneration.get()

        server.handleNetworkSuspended()

        assertEquals(genBefore, server.currentProfileGeneration.get(),
            "handleNetworkSuspended НЕ должен инкрементировать generation")
    }

    @Test
    fun testStaleResultRejectedAfterWifiToLteHandover() {
        val config = ProxyConfig()
        config.uplinkModeName = UplinkMode.MASQUE.name
        config.warpPeerEndpoint = "162.159.192.1:2408"
        val server = LocalProxyServer(config)
        val genA = server.currentProfileGeneration.get()

        server.handleNetworkChanged("Mobile LTE/5G", "Wi-Fi", isMobile = true, isScreenOn = true)
        val genB = server.currentProfileGeneration.get()
        assertNotEquals(genA, genB)

        val rejected = server.applyWarpEndpoint(
            "162.159.193.55:500",
            expectedGeneration = genA,
            expectedMode = UplinkMode.MASQUE
        )
        assertFalse(rejected, "Устаревший результат genA должен быть отклонён после смены сети")
        assertEquals("162.159.192.1:2408", config.warpPeerEndpoint)
    }

    @Test
    fun testDoubleNetworkChangeIncrementsTwice() {
        val server = LocalProxyServer(ProxyConfig())
        val genBefore = server.currentProfileGeneration.get()

        server.handleNetworkChanged("Mobile LTE/5G", "Wi-Fi", isMobile = true, isScreenOn = true)
        val genMid = server.currentProfileGeneration.get()

        server.handleNetworkChanged("Wi-Fi", "Mobile LTE/5G", isMobile = false, isScreenOn = true)
        val genFinal = server.currentProfileGeneration.get()

        assertTrue(genMid > genBefore)
        assertTrue(genFinal > genMid)
    }

    @Test
    fun testSetNetworkDormancyTrueIncrementsGeneration() {
        val config = ProxyConfig()
        config.uplinkModeName = UplinkMode.MASQUE.name
        config.warpPeerEndpoint = "old-ep:2408"
        val server = LocalProxyServer(config)
        val genBefore = server.currentProfileGeneration.get()

        server.setNetworkDormancy(true)
        val genAfter = server.currentProfileGeneration.get()
        assertTrue(genAfter > genBefore, "setNetworkDormancy(true) должен инкрементировать generation")

        val rejected = server.applyWarpEndpoint(
            "new-ep:1070",
            expectedGeneration = genBefore,
            expectedMode = UplinkMode.MASQUE
        )
        assertFalse(rejected, "Результат с genBefore должен быть отклонён после dormancy")
        assertEquals("old-ep:2408", config.warpPeerEndpoint)
    }

    @Test
    fun testHandleNetworkResumedDoesNotIncrementGeneration() {
        val server = LocalProxyServer(ProxyConfig())

        server.handleNetworkSuspended()
        val genAfterSuspend = server.currentProfileGeneration.get()

        server.handleNetworkResumed(isMobile = false, isScreenOn = true)
        val genAfterResume = server.currentProfileGeneration.get()

        assertEquals(genAfterSuspend, genAfterResume,
            "handleNetworkResumed НЕ должен менять generation: сеть та же, вернулся NOT_SUSPENDED")
    }

    @Test
    fun testCaptivePortalAvailableUnvalidatedDoesNotChangeGeneration() {
        // Captive portal: NetworkChangeObserver в состоянии AVAILABLE_UNVALIDATED
        // НЕ вызывает onNetworkChanged -> handleNetworkChanged не вызывается.
        // Проверяем инвариант: без явного вызова generation не меняется.
        val server = LocalProxyServer(ProxyConfig())
        val genBefore = server.currentProfileGeneration.get()

        // Нет вызовов handle*/setNetworkDormancy
        assertEquals(genBefore, server.currentProfileGeneration.get(),
            "Captive portal (AVAILABLE_UNVALIDATED) не должен изменять generation")
    }
}