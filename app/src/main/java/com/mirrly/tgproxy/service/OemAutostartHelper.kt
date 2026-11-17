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
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.mirrly.tgproxy.core.AppLogger

/**
 * Утилита прямого вызова системных менеджеров автозапуска и снятия
 * ограничений энергосбережения для агрессивных OEM-оболочек:
 * - MagicOS (Honor)
 * - EMUI (Huawei)
 * - HyperOS / MIUI (Xiaomi / Redmi / POCO)
 * - ColorOS / Realme UI / OxygenOS (Oppo / Realme / OnePlus)
 * - OriginOS / Funtouch OS (Vivo / iQOO)
 * - One UI (Samsung)
 */
object OemAutostartHelper {
    private const val TAG = "OemAutostartHelper"

    fun getOemBrandName(): String {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("honor") -> "MagicOS · Honor"
            m.contains("huawei") -> "EMUI · Huawei"
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "HyperOS · Xiaomi"
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> "ColorOS · Realme UI"
            m.contains("vivo") || m.contains("iqoo") -> "OriginOS · Vivo"
            m.contains("samsung") -> "One UI · Samsung"
            else -> "Android"
        }
    }

    fun isOemAutostartSupported(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("honor") || m.contains("huawei") || m.contains("xiaomi") ||
            m.contains("redmi") || m.contains("poco") || m.contains("oppo") ||
            m.contains("realme") || m.contains("oneplus") || m.contains("vivo")
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Direct ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS failed: ${e.message}")
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                return true
            } catch (fallbackEx: Exception) {
                AppLogger.e(TAG, "Fallback ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS failed: ${fallbackEx.message}")
                return false
            }
        }
    }

    fun openAutostartSettings(context: Context): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        val candidates = mutableListOf<Intent>()

        if (m.contains("honor")) {
            candidates.add(Intent().setComponent(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
            candidates.add(Intent().setComponent(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity")))
            candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
            candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")))
        }
        if (m.contains("huawei")) {
            candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
            candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")))
            candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")))
        }
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) {
            candidates.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            candidates.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")))
        }
        if (m.contains("oppo") || m.contains("realme") || m.contains("oneplus")) {
            candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")))
            candidates.add(Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")))
            candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")))
        }
        if (m.contains("vivo") || m.contains("iqoo")) {
            candidates.add(Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")))
            candidates.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")))
        }

        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                if (matches.isNotEmpty()) {
                    context.startActivity(intent)
                    AppLogger.i(TAG, "Launched OEM autostart activity: ${intent.component}")
                    return true
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Failed candidate ${intent.component}: ${e.message}")
            }
        }

        // Fallback to app details
        return try {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed fallback to application details settings: ${e.message}")
            false
        }
    }
}
