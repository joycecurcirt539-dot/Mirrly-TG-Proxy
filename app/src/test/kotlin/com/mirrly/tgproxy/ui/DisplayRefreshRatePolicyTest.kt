/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.mirrly.tgproxy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayRefreshRatePolicyTest {

    @Test
    fun filtersSupportedModesStrictlyByActivePhysicalResolution() {
        val pixel1080p60 = DisplayModeCandidate(modeId = 1, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 60f)
        val pixel1080p120 = DisplayModeCandidate(modeId = 2, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 120f)
        val pixel1440p60 = DisplayModeCandidate(modeId = 3, physicalWidth = 1440, physicalHeight = 3200, refreshRate = 60f)
        val pixel1440p120 = DisplayModeCandidate(modeId = 4, physicalWidth = 1440, physicalHeight = 3200, refreshRate = 120f)
        val supported = listOf(pixel1080p60, pixel1080p120, pixel1440p60, pixel1440p120)

        // When user uses 1440p, must pick 120Hz from 1440p modes and never match 1080p modes.
        assertEquals(120f, selectPreferredRefreshRateHz(pixel1440p60, supported) ?: -1f, 0.01f)

        // Even if 1440p only supports 60Hz while 1080p supports 120Hz, must NOT switch to 1080p.
        val mismatchedOnly = listOf(pixel1080p60, pixel1080p120, pixel1440p60)
        assertNull(selectPreferredRefreshRateHz(pixel1440p60, mismatchedOnly))
    }

    @Test
    fun returnsNullFor60HzOnlyDisplaysOrInvalidCurrentMode() {
        val mode60 = DisplayModeCandidate(modeId = 1, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 60f)
        assertNull(selectPreferredRefreshRateHz(mode60, listOf(mode60)))
        assertNull(selectPreferredRefreshRateHz(null, listOf(mode60)))
        assertNull(
            selectPreferredRefreshRateHz(
                DisplayModeCandidate(modeId = 0, physicalWidth = 0, physicalHeight = 0, refreshRate = 60f),
                listOf(mode60)
            )
        )
    }
}
