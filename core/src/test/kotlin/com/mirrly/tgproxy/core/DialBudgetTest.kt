/*
 * Mirrly TG Proxy - Unit tests for DialBudgetStats (MOB-003)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 */

package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialBudgetTest {

    @Test
    fun testParseDialBudgetStatsFromJson() {
        val json = """
            {
                "generation": 42,
                "is_mobile": true,
                "max_establishment": 2,
                "max_background": 1,
                "active_user": 1,
                "active_recovery": 0,
                "active_background": 0,
                "active_total": 1,
                "waiting_user": 2,
                "waiting_recovery": 0,
                "waiting_background": 1
            }
        """.trimIndent()

        val stats = DialBudgetStats.fromJson(json)
        assertNotNull(stats)
        assertEquals(42L, stats!!.generation)
        assertTrue(stats.isMobile)
        assertEquals(2, stats.maxEstablishment)
        assertEquals(1, stats.maxBackground)
        assertEquals(1, stats.activeUser)
        assertEquals(0, stats.activeRecovery)
        assertEquals(0, stats.activeBackground)
        assertEquals(1, stats.activeTotal)
        assertEquals(2, stats.waitingUser)
        assertEquals(0, stats.waitingRecovery)
        assertEquals(1, stats.waitingBackground)
    }

    @Test
    fun testEmptyOrInvalidDialBudgetStats() {
        assertNull(DialBudgetStats.fromJson(null))
        assertNull(DialBudgetStats.fromJson(""))
        assertNull(DialBudgetStats.fromJson("{}"))
        assertNull(DialBudgetStats.fromJson("not-a-json"))
    }
}
