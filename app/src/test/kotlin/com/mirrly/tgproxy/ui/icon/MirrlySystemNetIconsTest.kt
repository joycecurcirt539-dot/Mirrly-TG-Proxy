/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты валидации набора процедурных векторных иконок MirrlySystemNetIcons:
 * «Сетевой стек, VPN-туннели и системные демоны» по стандарту /mirrly-icons.
 */
class MirrlySystemNetIconsTest {

    @Test
    fun testAllSystemNetIconTypesPresent() {
        val types = MirrlySystemNetIconType.values()
        assertEquals("Должно быть ровно 10 типов векторных компонентов", 10, types.size)

        val expectedTypes = listOf(
            MirrlySystemNetIconType.VPN,
            MirrlySystemNetIconType.GLOBE,
            MirrlySystemNetIconType.SPLIT_TUNNEL,
            MirrlySystemNetIconType.POWER,
            MirrlySystemNetIconType.TIMER,
            MirrlySystemNetIconType.WATCHDOG_CLOCK,
            MirrlySystemNetIconType.CPU_ANTIFREEZE,
            MirrlySystemNetIconType.OEM_STARTUP,
            MirrlySystemNetIconType.AUTO_SELECT,
            MirrlySystemNetIconType.GLOBAL_ANYCAST
        )

        for (expected in expectedTypes) {
            assertTrue("Тип $expected обязан присутствовать в перечислении", types.contains(expected))
        }
    }

    @Test
    fun testComposableFunctionsExistViaReflection() {
        val clazz = Class.forName("com.mirrly.tgproxy.ui.icon.MirrlySystemNetIconsKt")
        assertNotNull(clazz)

        val expectedMethodNames = listOf(
            "MirrlySystemNetIcon",
            "MirrlyVpnIcon",
            "MirrlyIconVpn",
            "MirrlyGlobeIcon",
            "MirrlyIconGlobe",
            "MirrlySplitTunnelIcon",
            "MirrlyIconSplitTunnel",
            "MirrlyPowerIcon",
            "MirrlyIconPower",
            "MirrlyTimerIcon",
            "MirrlyIconTimer",
            "MirrlyWatchdogClockIcon",
            "MirrlyIconWatchdogClock",
            "MirrlyCpuAntifreezeIcon",
            "MirrlyIconCpuAntifreeze",
            "MirrlyOemStartupIcon",
            "MirrlyIconOemStartup",
            "MirrlyAutoSelectIcon",
            "AutoSelectBadgeIcon",
            "MirrlyGlobalAnycastIcon",
            "GlobalAnycastBadgeIcon"
        )

        val declaredMethodNames = clazz.declaredMethods.map { it.name }.toSet()

        for (methodName in expectedMethodNames) {
            assertTrue(
                "Метод $methodName обязан быть объявлен в MirrlySystemNetIconsKt",
                declaredMethodNames.any { it == methodName || it.startsWith("$methodName-") }
            )
        }
    }
}
