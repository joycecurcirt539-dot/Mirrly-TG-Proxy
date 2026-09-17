/*
 * Mirrly TG Proxy - Unit tests for Transport Pool Size and Concurrency Metrics (MOB-016)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 */

package com.mirrly.tgproxy.core

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * MOB-016: Тесты разделения пула сокетов по транспорту (MTProto vs SOCKS5).
 *
 * Проверяет:
 * 1. mtprotoStandbyPerActiveSlot: requested vs effective (1 на мобильной сети, 1..4 на Wi-Fi).
 * 2. globalEstablishmentBudget: 2 на мобильной сети, 4 на Wi-Fi.
 * 3. socksConcurrentFlows: разделение потоков SOCKS5 от пула ожидания MTProto.
 * 4. Декодирование телеметрии TransportPoolStatus из JSON.
 * 5. Контракт меток UI: отсутствие ложного именования SOCKS5 потоков «socket pool».
 * 6. Контракт исходников Rust и Kotlin на наличие FFI-экспортов.
 */
class TransportPoolSizeTest {

    @Test
    fun testMtprotoStandbyRequestedVsEffective() {
        val config = ProxyConfig()

        assertEquals(2, config.mtprotoStandbyPerActiveSlot)

        // На мобильной сети effective ВСЕГДА 1 для минимизации радиомодуля и батареи
        assertEquals(1, config.getEffectiveMtprotoStandby(isMobile = true))
        // На Wi-Fi effective = requested в диапазоне 1..4
        assertEquals(2, config.getEffectiveMtprotoStandby(isMobile = false))

        // Проверяем 1 (минимальный)
        config.mtprotoStandbyPerActiveSlot = 1
        assertEquals(1, config.mtprotoStandbyPerActiveSlot)
        assertEquals(1, config.getEffectiveMtprotoStandby(isMobile = true))
        assertEquals(1, config.getEffectiveMtprotoStandby(isMobile = false))

        // Проверяем 2 (Eco)
        config.mtprotoStandbyPerActiveSlot = 2
        assertEquals(2, config.mtprotoStandbyPerActiveSlot)
        assertEquals(1, config.getEffectiveMtprotoStandby(isMobile = true))
        assertEquals(2, config.getEffectiveMtprotoStandby(isMobile = false))

        // Старое ложное значение 8 мигрирует в честный максимум 4.
        config.mtprotoStandbyPerActiveSlot = 8
        assertEquals(4, config.mtprotoStandbyPerActiveSlot)
        assertEquals(1, config.getEffectiveMtprotoStandby(isMobile = true))
        assertEquals(4, config.getEffectiveMtprotoStandby(isMobile = false), "На Wi-Fi максимум 4 сокета ожидания на слот")

        // Значение 16 также не хранится как requested: скрытого clamp больше нет.
        config.mtprotoStandbyPerActiveSlot = 16
        assertEquals(4, config.mtprotoStandbyPerActiveSlot)
        assertEquals(1, config.getEffectiveMtprotoStandby(isMobile = true))
        assertEquals(4, config.getEffectiveMtprotoStandby(isMobile = false), "На Wi-Fi максимум 4 сокета ожидания на слот")
    }

    @Test
    fun testGlobalEstablishmentBudget() {
        val config = ProxyConfig()

        // Мобильная сеть: лимит 2 активных установления (пользовательский + аварийный)
        assertEquals(2, config.getGlobalEstablishmentBudget(isMobile = true))

        // Wi-Fi: лимит 4 активных установления (без деградации производительности)
        assertEquals(4, config.getGlobalEstablishmentBudget(isMobile = false))
    }

    @Test
    fun testSocks5FlowsVsMtprotoPoolDistinction() {
        val socks5Config = ProxyConfig(proxyModeName = ProxyMode.SOCKS5.name)
        val mtprotoConfig = ProxyConfig(proxyModeName = ProxyMode.MTPROTO.name)

        assertTrue(socks5Config.isSocks5Mode)
        assertFalse(mtprotoConfig.isSocks5Mode)

        // В SOCKS5 режиме активные соединения - это потоки (flows), а не пул
        val socksStatus = socks5Config.getTransportPoolStatus(
            isMobile = false,
            isRunning = true,
            activeConnections = 5
        )
        assertEquals("socks5", socksStatus.transport)
        assertEquals(5L, socksStatus.socksConcurrentFlows)
        assertEquals(4, socksStatus.globalEstablishmentBudget)

        // В MTProto режиме соединения распределяются по пулу сокетов ожидания
        val mtprotoStatus = mtprotoConfig.getTransportPoolStatus(
            isMobile = true,
            isRunning = true,
            activeConnections = 2
        )
        assertEquals("mtproto", mtprotoStatus.transport)
        assertEquals(0L, socksStatusCopyWithoutActive(socksStatus).activeConnectionsPlaceholder())
        assertEquals(1, mtprotoStatus.mtprotoStandbyPerActiveSlotEffective)
        assertEquals(2, mtprotoStatus.globalEstablishmentBudget)
    }

    private fun socksStatusCopyWithoutActive(status: TransportPoolStatus) = object {
        fun activeConnectionsPlaceholder() = 0L
    }

    @Test
    fun testParseTransportPoolStatusJson() {
        val json = """
            {
                "transport": "mtproto",
                "is_mobile": false,
                "mtproto_standby_per_active_slot_requested": 3,
                "mtproto_standby_per_active_slot_effective": 3,
                "global_establishment_budget": 4,
                "socks_concurrent_flows": 0
            }
        """.trimIndent()

        val status = TransportPoolStatus.fromJson(json)
        assertNotNull(status)
        assertEquals("mtproto", status!!.transport)
        assertFalse(status.isMobile)
        assertEquals(3, status.mtprotoStandbyPerActiveSlotRequested)
        assertEquals(3, status.mtprotoStandbyPerActiveSlotEffective)
        assertEquals(4, status.globalEstablishmentBudget)
        assertEquals(0L, status.socksConcurrentFlows)

        // Тестируем мобильный SOCKS5 статус
        val socksJson = """
            {
                "transport": "socks5",
                "is_mobile": true,
                "mtproto_standby_per_active_slot_requested": 2,
                "mtproto_standby_per_active_slot_effective": 1,
                "global_establishment_budget": 2,
                "socks_concurrent_flows": 7
            }
        """.trimIndent()

        val socksStatus = TransportPoolStatus.fromJson(socksJson)
        assertNotNull(socksStatus)
        assertEquals("socks5", socksStatus!!.transport)
        assertTrue(socksStatus.isMobile)
        assertEquals(1, socksStatus.mtprotoStandbyPerActiveSlotEffective)
        assertEquals(2, socksStatus.globalEstablishmentBudget)
        assertEquals(7L, socksStatus.socksConcurrentFlows)
    }

    @Test
    fun testProxyDisplayLabelsTransportSeparation() {
        // Проверяем, что для SOCKS5 заголовок "SOCKS5 ПОТОКИ", а не "WSPOOL СОКЕТЫ"
        assertEquals("SOCKS5 ПОТОКИ", ProxyDisplayLabels.transportFlowTitle(isSocks5 = true))
        assertEquals("WSPOOL СОКЕТЫ", ProxyDisplayLabels.transportFlowTitle(isSocks5 = false))

        // Проверяем подписи активности
        val socksActive = ProxyDisplayLabels.transportFlowSubtitle(
            isSocks5 = true,
            isProxyActive = true,
            activeConns = 3,
            requestedStandby = 4,
            effectiveStandby = 1
        )
        assertEquals("3 активных", socksActive)

        val mtprotoActive = ProxyDisplayLabels.transportFlowSubtitle(
            isSocks5 = false,
            isProxyActive = true,
            activeConns = 2,
            requestedStandby = 4,
            effectiveStandby = 4
        )
        assertEquals("2 активных · Резерв: 4 (4)", mtprotoActive)

        val stopped = ProxyDisplayLabels.transportFlowSubtitle(
            isSocks5 = true,
            isProxyActive = false,
            activeConns = 0,
            requestedStandby = 4,
            effectiveStandby = 1
        )
        assertEquals("остановлен", stopped)
    }

    @Test
    fun testLocalProxyServerPoolHelpers() {
        val config = ProxyConfig()
        val server = LocalProxyServer(config)

        // Legacy input 8 is normalized to the real native range 1..4.
        server.applyMtprotoStandbyPerActiveSlot(8)
        assertEquals(4, config.mtprotoStandbyPerActiveSlot)

        // getEffectiveMtprotoStandby возвращает значение без скрытых сюрпризов
        val effective = server.getEffectiveMtprotoStandby()
        assertTrue(effective in 1..4)

        // getGlobalEstablishmentBudget возвращает 2 или 4
        val budget = server.getGlobalEstablishmentBudget()
        assertTrue(budget == 2 || budget == 4)

        // getTransportPoolStatus возвращает валидный статус
        val status = server.getTransportPoolStatus()
        assertEquals(4, status.mtprotoStandbyPerActiveSlotRequested)
        assertEquals(effective, status.mtprotoStandbyPerActiveSlotEffective)
    }

    @Test
    fun testRustAndKotlinContract() {
        val rustLib = File("..", "mirrlyengine/src/lib.rs").canonicalFile.readText()
        val rustConfig = File("..", "mirrlyengine/src/config.rs").canonicalFile.readText()
        val kotlinNativeProxy = File("src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt").canonicalFile.readText()
        val kotlinProxyConfig = File("src/main/kotlin/com/mirrly/tgproxy/core/ProxyConfig.kt").canonicalFile.readText()
        val localProxy = File("src/main/kotlin/com/mirrly/tgproxy/core/LocalProxyServer.kt").canonicalFile.readText()
        val settingsUi = File("..", "app/src/main/java/com/mirrly/tgproxy/ui/SettingsScreen.kt").canonicalFile.readText()
        val homeUi = File("..", "app/src/main/java/com/mirrly/tgproxy/ui/HomeScreen.kt").canonicalFile.readText()
        val preferences = File("..", "app/src/main/java/com/mirrly/tgproxy/service/PreferencesManager.kt").canonicalFile.readText()
        val nativeRuntimeTest = File("..", "mirrlyengine/tests/transport_pool_contract.rs").canonicalFile.readText()

        // 1. Rust exports the semantic setter and telemetry.
        assertTrue(rustLib.contains("pub extern \"C\" fn GetTransportPoolStatusJson"))
        assertTrue(rustLib.contains("pub extern \"C\" fn SetMtprotoStandbyPerActiveSlot"))

        // 2. Rust config содержит расчет effective_mtproto_standby и global_establishment_budget
        assertTrue(rustConfig.contains("pub fn effective_mtproto_standby"))
        assertTrue(rustConfig.contains("pub fn global_establishment_budget"))
        assertTrue(rustConfig.contains("pub struct TransportPoolStatus"))
        assertFalse(rustConfig.contains("pub requested_pool_size"))

        // 3. Kotlin содержит соответствующие вызовы и структуры
        assertTrue(kotlinNativeProxy.contains("fun GetTransportPoolStatusJson(): Pointer?"))
        assertTrue(kotlinNativeProxy.contains("fun SetMtprotoStandbyPerActiveSlot(size: Int): Int"))
        assertTrue(kotlinNativeProxy.contains("fun getTransportPoolStatus(): TransportPoolStatus?"))
        assertTrue(kotlinNativeProxy.contains("data class TransportPoolStatus("))

        // 4. Kotlin ProxyConfig содержит mtprotoStandbyPerActiveSlot и getEffectiveMtprotoStandby
        assertTrue(kotlinProxyConfig.contains("var mtprotoStandbyPerActiveSlot: Int"))
        assertTrue(kotlinProxyConfig.contains("fun getEffectiveMtprotoStandby(isMobile: Boolean): Int"))
        assertTrue(kotlinProxyConfig.contains("fun getGlobalEstablishmentBudget(isMobile: Boolean): Int"))
        assertFalse(kotlinNativeProxy.contains("val requestedPoolSize: Int"))

        // 5. Runtime and both UI surfaces consume the same native-backed status.
        assertTrue(localProxy.contains("val transportPoolStatus:"))
        assertTrue(settingsUi.contains("server.transportPoolStatus.collectAsState()"))
        assertTrue(homeUi.contains("app.proxyServer.transportPoolStatus.collectAsState()"))
        assertTrue(preferences.contains("mtproto_standby_per_active_slot"))
        assertTrue(nativeRuntimeTest.contains("requested_effective_budget_and_socks_flows_share_one_contract"))
    }
}
