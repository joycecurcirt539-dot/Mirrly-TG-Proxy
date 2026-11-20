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

package com.mirrly.tgproxy.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconManagerTest {

    @Test
    fun presets_haveUniqueIdsAliasesAndSingleManifestDefault() {
        val entries = AppIconPreset.entries
        assertEquals(8, entries.size)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertEquals(entries.size, entries.map { it.aliasClassName }.toSet().size)

        val defaults = entries.filter { it.isManifestDefault }
        assertEquals(1, defaults.size)
        assertEquals(AppIconPreset.DEFAULT, defaults.first())

        assertNotNull(entries.firstOrNull { it.id == "default" })
        assertNotNull(entries.firstOrNull { it.id == "legacy" })
        assertNotNull(entries.firstOrNull { it.id == "outline" })
        assertNotNull(entries.firstOrNull { it.id == "stealth" })
        assertTrue(entries.all { it.aliasClassName.startsWith("com.mirrly.tgproxy.LauncherIcon") })
    }

    @Test
    fun fromStorageKey_handlesUnknownAndCaseInsensitiveKeys() {
        assertEquals(AppIconPreset.DEFAULT, AppIconPreset.fromStorageKey(null))
        assertEquals(AppIconPreset.DEFAULT, AppIconPreset.fromStorageKey(""))
        assertEquals(AppIconPreset.DEFAULT, AppIconPreset.fromStorageKey("non_existent_icon"))
        assertEquals(AppIconPreset.LEGACY, AppIconPreset.fromStorageKey("legacy"))
        assertEquals(AppIconPreset.CYBER_RUBY, AppIconPreset.fromStorageKey("CYBER_RUBY"))
    }

    @Test
    fun computeApplyPlan_enablesTargetFirstAndDisablesDefaultAndEnabledOthers() {
        val initialManifestStates = AppIconPreset.entries.associate { it.id to IconComponentState.DEFAULT }
        val plan = AppIconSyncPlanner.computeApplyPlan(
            target = AppIconPreset.LEGACY,
            currentStates = initialManifestStates
        )

        assertEquals(AppIconPreset.LEGACY, plan.enableFirst)
        assertTrue(plan.disableAfter.contains(AppIconPreset.DEFAULT))
        assertFalse(plan.disableAfter.contains(AppIconPreset.LEGACY))
        assertEquals(AppIconPreset.entries.size - 1, plan.disableAfter.size)
        assertEquals(AppIconPreset.LEGACY.id, plan.updatedPreferenceId)
    }

    @Test
    fun computeApplyPlan_switchingBackToDefaultEnablesDefaultAndDisablesActiveAlias() {
        val states = AppIconPreset.entries.associate { preset ->
            preset.id to when (preset) {
                AppIconPreset.CYBER_RUBY -> IconComponentState.ENABLED
                else -> IconComponentState.DISABLED
            }
        }
        val plan = AppIconSyncPlanner.computeApplyPlan(
            target = AppIconPreset.DEFAULT,
            currentStates = states
        )

        assertEquals(AppIconPreset.DEFAULT, plan.enableFirst)
        assertEquals(listOf(AppIconPreset.CYBER_RUBY), plan.disableAfter)
    }

    @Test
    fun computeStartupSyncPlan_isNoOpWhenSingleActiveMatchesSaved() {
        val initialManifestStates = AppIconPreset.entries.associate { it.id to IconComponentState.DEFAULT }
        val plan = AppIconSyncPlanner.computeStartupSyncPlan(
            savedId = AppIconPreset.DEFAULT.id,
            currentStates = initialManifestStates
        )

        assertTrue(plan.isNoOp)
        assertNull(plan.enableFirst)
        assertTrue(plan.disableAfter.isEmpty())
    }

    @Test
    fun computeStartupSyncPlan_reconcilesBackupRestoreMismatch() {
        val freshInstallStates = AppIconPreset.entries.associate { it.id to IconComponentState.DEFAULT }
        val plan = AppIconSyncPlanner.computeStartupSyncPlan(
            savedId = AppIconPreset.OUTLINE.id,
            currentStates = freshInstallStates
        )

        assertEquals(AppIconPreset.OUTLINE, plan.enableFirst)
        assertTrue(plan.disableAfter.contains(AppIconPreset.DEFAULT))
        assertNull(plan.updatedPreferenceId)
    }

    @Test
    fun computeStartupSyncPlan_recoversWhenZeroAliasesEnabled() {
        val brokenStates = AppIconPreset.entries.associate { it.id to IconComponentState.DISABLED }
        val plan = AppIconSyncPlanner.computeStartupSyncPlan(
            savedId = AppIconPreset.LEGACY.id,
            currentStates = brokenStates
        )

        assertEquals(AppIconPreset.LEGACY, plan.enableFirst)
        assertTrue(plan.disableAfter.isEmpty())
    }

    @Test
    fun computeStartupSyncPlan_deduplicatesWhenMultipleAliasesEnabled() {
        val duplicateStates = AppIconPreset.entries.associate { preset ->
            preset.id to when (preset) {
                AppIconPreset.DEFAULT, AppIconPreset.LEGACY -> IconComponentState.ENABLED
                else -> IconComponentState.DISABLED
            }
        }
        val plan = AppIconSyncPlanner.computeStartupSyncPlan(
            savedId = AppIconPreset.LEGACY.id,
            currentStates = duplicateStates
        )

        assertNull(plan.enableFirst)
        assertEquals(listOf(AppIconPreset.DEFAULT), plan.disableAfter)
    }
}
