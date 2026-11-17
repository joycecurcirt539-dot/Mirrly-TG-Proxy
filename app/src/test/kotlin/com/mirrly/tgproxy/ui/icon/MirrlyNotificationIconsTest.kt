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

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты валидации геометрии и стандартов векторных путей MirrlyNotificationIcons.
 */
class MirrlyNotificationIconsTest {

    @Test
    fun testAllNotificationPathConstantsAreValid() {
        val paths = listOf(
            MirrlyNotificationIcons.PATH_STAT_PROXY_CONNECTED_CONTOUR,
            MirrlyNotificationIcons.PATH_STAT_PROXY_CONNECTED_POINT,
            MirrlyNotificationIcons.PATH_STAT_PROXY_ERROR,
            MirrlyNotificationIcons.PATH_STAT_PROXY_WARNING,
            MirrlyNotificationIcons.PATH_STAT_TIMER,
            MirrlyNotificationIcons.PATH_STAT_UPDATE,
            MirrlyNotificationIcons.PATH_NOTIF_STOP,
            MirrlyNotificationIcons.PATH_QS_PROXY
        )

        for (path in paths) {
            assertNotNull(path)
            assertTrue("Path must not be blank: $path", path.isNotBlank())
            assertTrue(
                "Path must be valid SVG pathData syntax: $path",
                SvgPathDataParser.validatePathData(path)
            )
        }
    }

    @Test
    fun testAllNotificationIconTypesHandled() {
        val types = MirrlyNotificationIconType.values()
        assertTrue("Must contain all 7 icon components", types.size == 7)
    }
}
