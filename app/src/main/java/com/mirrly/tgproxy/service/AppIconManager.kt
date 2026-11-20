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

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.mirrly.tgproxy.core.AppLogger

enum class IconComponentState {
    DEFAULT,
    ENABLED,
    DISABLED
}

data class IconSyncPlan(
    val enableFirst: AppIconPreset?,
    val disableAfter: List<AppIconPreset>,
    val updatedPreferenceId: String? = null
) {
    val isNoOp: Boolean
        get() = enableFirst == null && disableAfter.isEmpty() && updatedPreferenceId == null
}

/**
 * Pure JVM planner for safe launcher `<activity-alias>` transitions.
 * Always enables the target alias BEFORE disabling older aliases so Android never
 * observes a transient state with 0 launcher entry points.
 */
object AppIconSyncPlanner {

    fun isEffectivelyEnabled(preset: AppIconPreset, state: IconComponentState?): Boolean {
        return when (state) {
            IconComponentState.ENABLED -> true
            IconComponentState.DEFAULT -> preset.isManifestDefault
            IconComponentState.DISABLED, null -> false
        }
    }

    fun computeApplyPlan(
        target: AppIconPreset,
        currentStates: Map<String, IconComponentState>
    ): IconSyncPlan {
        val targetState = currentStates[target.id]
        val enableTarget = if (targetState == IconComponentState.ENABLED) null else target
        val disableOthers = AppIconPreset.entries.filter { preset ->
            preset != target && currentStates[preset.id] != IconComponentState.DISABLED
        }
        return IconSyncPlan(
            enableFirst = enableTarget,
            disableAfter = disableOthers,
            updatedPreferenceId = target.id
        )
    }

    fun computeStartupSyncPlan(
        savedId: String,
        currentStates: Map<String, IconComponentState>
    ): IconSyncPlan {
        val savedPreset = AppIconPreset.fromStorageKey(savedId)
        val activePresets = AppIconPreset.entries.filter { preset ->
            isEffectivelyEnabled(preset, currentStates[preset.id])
        }

        if (activePresets.size == 1 && activePresets.first() == savedPreset) {
            return IconSyncPlan(enableFirst = null, disableAfter = emptyList())
        }

        val target = when {
            activePresets.isEmpty() -> savedPreset
            activePresets.size == 1 -> savedPreset
            activePresets.contains(savedPreset) -> savedPreset
            else -> activePresets.first()
        }

        val targetState = currentStates[target.id]
        val enableTarget = if (targetState == IconComponentState.ENABLED) null else target
        val disableOthers = AppIconPreset.entries.filter { preset ->
            preset != target && currentStates[preset.id] != IconComponentState.DISABLED
        }
        val prefCorrection = if (target.id != savedId) target.id else null

        return IconSyncPlan(
            enableFirst = enableTarget,
            disableAfter = disableOthers,
            updatedPreferenceId = prefCorrection
        )
    }
}

/**
 * Manages runtime launcher icon switching via Manifest `<activity-alias>` components
 * across Android 8.0 - 16 (API 26 - 36) and all build variants.
 */
object AppIconManager {
    private const val TAG = "AppIconManager"

    fun componentNameFor(packageName: String, preset: AppIconPreset): ComponentName {
        return ComponentName(packageName, preset.aliasClassName)
    }

    private fun toComponentState(pmState: Int): IconComponentState {
        return when (pmState) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> IconComponentState.ENABLED
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> IconComponentState.DISABLED
            else -> IconComponentState.DEFAULT
        }
    }

    fun readCurrentStates(context: Context): Map<String, IconComponentState> {
        val pm = context.packageManager
        val pkg = context.packageName
        return AppIconPreset.entries.associate { preset ->
            val raw = try {
                pm.getComponentEnabledSetting(componentNameFor(pkg, preset))
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Failed to read component state for ${preset.aliasClassName}: ${t.message}")
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            preset.id to toComponentState(raw)
        }
    }

    fun resolveActiveIcon(context: Context, fallbackId: String): AppIconPreset {
        val states = readCurrentStates(context)
        val enabled = AppIconPreset.entries.filter { preset ->
            AppIconSyncPlanner.isEffectivelyEnabled(preset, states[preset.id])
        }
        val fallback = AppIconPreset.fromStorageKey(fallbackId)
        return when {
            enabled.contains(fallback) -> fallback
            enabled.isNotEmpty() -> enabled.first()
            else -> fallback
        }
    }

    /**
     * Switches the active launcher `<activity-alias>`.
     * Saves preference before applying so startup reconciliation remains consistent even
     * if the OS launcher closes or recreates the current task immediately after disabling
     * the previous entry component.
     */
    fun applyAppIcon(
        context: Context,
        target: AppIconPreset,
        prefsManager: PreferencesManager
    ): Boolean {
        val states = readCurrentStates(context)
        val plan = AppIconSyncPlanner.computeApplyPlan(target, states)

        prefsManager.setAppIconId(target.id)
        if (plan.isNoOp && plan.enableFirst == null && plan.disableAfter.isEmpty()) {
            return true
        }

        val success = executePlan(context, plan)
        if (!success) {
            val actual = resolveActiveIcon(context, AppIconPreset.DEFAULT.id)
            if (actual != target) {
                prefsManager.setAppIconId(actual.id)
            }
        }
        return success
    }

    /**
     * Reconciles manifest alias state at app startup (handles Auto Backup restore or
     * interrupted transitions without issuing unnecessary PackageManager calls).
     */
    fun syncOnStartup(context: Context, prefsManager: PreferencesManager) {
        try {
            val savedId = prefsManager.getAppIconId()
            val states = readCurrentStates(context)
            val plan = AppIconSyncPlanner.computeStartupSyncPlan(savedId, states)
            if (plan.isNoOp) return

            val executed = executePlan(context, plan)
            val updatedStates = if (executed) readCurrentStates(context) else states
            val active = AppIconPreset.entries.firstOrNull { preset ->
                AppIconSyncPlanner.isEffectivelyEnabled(preset, updatedStates[preset.id])
            }
            if (active != null && active.id != savedId) {
                // Keep preferences aligned if OEM firmware refused the component change
                prefsManager.setAppIconId(active.id)
            } else if (plan.updatedPreferenceId != null) {
                prefsManager.setAppIconId(plan.updatedPreferenceId)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Startup icon sync skipped: ${t.message}")
        }
    }

    private fun executePlan(context: Context, plan: IconSyncPlan): Boolean {
        val pm = context.packageManager
        val pkg = context.packageName
        return try {
            plan.enableFirst?.let { enablePreset ->
                pm.setComponentEnabledSetting(
                    componentNameFor(pkg, enablePreset),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            for (disablePreset in plan.disableAfter) {
                pm.setComponentEnabledSetting(
                    componentNameFor(pkg, disablePreset),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            true
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed to apply launcher icon plan: ${t.message}")
            false
        }
    }
}
