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
 * Юнит-тесты валидации перечисления и метаданных процедурных иконок MirrlyMediaArcadeIcons.
 */
class MirrlyMediaArcadeIconsTest {

    @Test
    fun testAllEightComponentsAreRegistered() {
        val types = MirrlyMediaArcadeIconType.values()
        assertEquals("Должно быть ровно 8 компонентов категории", 8, types.size)

        val expectedNames = setOf(
            "ic_wallpaper",
            "ic_traffic_pulse",
            "ic_volume_up",
            "ic_volume_off",
            "ic_vibrate",
            "ic_vibrate_off",
            "ic_gamepad",
            "ic_crown"
        )

        val actualNames = types.map { it.iconName }.toSet()
        assertEquals(expectedNames, actualNames)
    }

    @Test
    fun testIconNamesAreNotEmpty() {
        for (type in MirrlyMediaArcadeIconType.values()) {
            assertNotNull(type.iconName)
            assertTrue("Имя ресурса иконки не должно быть пустым", type.iconName.isNotBlank())
            assertTrue("Имя должно начинаться с префикса ic_", type.iconName.startsWith("ic_"))
        }
    }
}
