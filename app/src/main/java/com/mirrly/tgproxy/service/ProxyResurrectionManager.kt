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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.core.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Двухуровневый менеджер восстановления работы службы прокси/VPN
 * при удалении приложения из списка недавних задач (Swipe from Recents / onTaskRemoved)
 * на Android 14–16 (API 34–36) и агрессивных китайских оболочках (MagicOS, Realme UI, HyperOS).
 *
 * Tier 1: AlarmManager (RTC_WAKEUP, ~1.5s) — моментальный запуск при гибели процесса.
 * Tier 2: WorkManager (OneTimeWork, ~3s) — гарантированная страховка на случай подавления будильников OEM-лаунчером.
 */
object ProxyResurrectionManager {
    private const val TAG = "ProxyResurrectionManager"
    const val ACTION_RESURRECT_PROXY = "com.mirrly.tgproxy.ACTION_RESURRECT_PROXY"
    private const val RESURRECTION_REQUEST_CODE = 9021
    private const val UNIQUE_WORK_NAME = "mirrly_proxy_resurrection"
    private const val TAG_RESURRECTION_WORK = "proxy_resurrection"

    /**
     * Планирует восстановление службы через AlarmManager и WorkManager,
     * если служба активна и не находится в процессе преднамеренной остановки.
     */
    fun schedule(context: Context) {
        val app = MirrlyApplication.instance
        val prefsManager = app.prefsManager
        if (!prefsManager.isProxyActive()) {
            AppLogger.d(TAG, "schedule: proxy is not marked active in preferences, skipping resurrection scheduling")
            return
        }

        AppLogger.i(TAG, "Scheduling two-tier proxy resurrection after task swipe (Android ${Build.VERSION.SDK_INT})")
        scheduleAlarmManagerTier(context)
        scheduleWorkManagerTier(context)
    }

    /**
     * Отменяет все запланированные задачи восстановления (при ручной остановке пользователем).
     */
    fun cancel(context: Context) {
        // 1. Отмена AlarmManager PendingIntent
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager != null) {
                val intent = Intent(context, ProxyResurrectionReceiver::class.java).apply {
                    action = ACTION_RESURRECT_PROXY
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    RESURRECTION_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                    AppLogger.d(TAG, "AlarmManager resurrection pending intent cancelled")
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to cancel AlarmManager resurrection: ${e.message}")
        }

        // 2. Отмена WorkManager задачи
        try {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            AppLogger.d(TAG, "WorkManager resurrection work cancelled")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to cancel WorkManager resurrection: ${e.message}")
        }
    }

    /**
     * Tier 1: AlarmManager с дельта-задержкой 1.5 секунды.
     */
    private fun scheduleAlarmManagerTier(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ProxyResurrectionReceiver::class.java).apply {
            action = ACTION_RESURRECT_PROXY
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            RESURRECTION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = System.currentTimeMillis() + 1500L

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }
                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            AppLogger.d(TAG, "AlarmManager resurrection scheduled (+1500ms)")
        } catch (e: SecurityException) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
                AppLogger.d(TAG, "AlarmManager resurrection scheduled via fallback (+1500ms)")
            } catch (fallbackEx: Exception) {
                AppLogger.w(TAG, "Failed to schedule AlarmManager resurrection: ${fallbackEx.message}")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Unexpected error scheduling AlarmManager resurrection: ${e.message}")
        }
    }

    /**
     * Tier 2: WorkManager с отложенным запуском на 3 секунды.
     */
    private fun scheduleWorkManagerTier(context: Context) {
        try {
            val workRequest = OneTimeWorkRequestBuilder<ProxyResurrectionWorker>()
                .setInitialDelay(3, TimeUnit.SECONDS)
                .addTag(TAG_RESURRECTION_WORK)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            AppLogger.d(TAG, "WorkManager fallback scheduled (+3s)")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to schedule WorkManager fallback: ${e.message}")
        }
    }

    /**
     * Общий метод проверки и запуска службы при срабатывании любого из уровней восстановления.
     */
    fun resurrectIfNeeded(context: Context, reason: String) {
        val app = MirrlyApplication.instance
        val prefsManager = app.prefsManager
        val isProxyActive = prefsManager.isProxyActive()
        val isServerRunning = app.proxyServer.isRunning
        val isVpnActive = prefsManager.isVpnModeEnabled()

        AppLogger.i(
            TAG,
            "resurrectIfNeeded (source=$reason): isProxyActive=$isProxyActive, " +
                "isServerRunning=$isServerRunning, isVpnActive=$isVpnActive"
        )

        if (!isProxyActive) {
            AppLogger.d(TAG, "Resurrection skipped: proxy is not marked active in preferences")
            return
        }

        if (isVpnActive) {
            if (MirrlyVpnService.prepare(context) == null) {
                AppLogger.i(TAG, "Resurrecting MirrlyVpnService...")
                MirrlyVpnService.start(context)
            } else {
                AppLogger.w(TAG, "Cannot resurrect MirrlyVpnService: VPN system permission not granted")
            }
            return
        }

        if (isServerRunning) {
            AppLogger.d(TAG, "Resurrection skipped: proxy server is already running")
            return
        }

        AppLogger.i(TAG, "Starting ProxyForegroundService from $reason...")
        val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            AppLogger.i(TAG, "Successfully requested ProxyForegroundService resurrection")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start service during resurrection ($reason): ${e.message}")
        }
    }
}

/**
 * BroadcastReceiver для получения сигнала пробуждения от AlarmManager.
 */
class ProxyResurrectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != ProxyResurrectionManager.ACTION_RESURRECT_PROXY) return

        AppLogger.i("ProxyResurrectionReceiver", "Resurrection alarm received")
        ProxyResurrectionManager.resurrectIfNeeded(context, reason = "AlarmManager")
    }
}

/**
 * CoroutineWorker для гарантированного резервного восстановления через WorkManager.
 */
class ProxyResurrectionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        AppLogger.i("ProxyResurrectionWorker", "Executing WorkManager resurrection check")
        ProxyResurrectionManager.resurrectIfNeeded(applicationContext, reason = "WorkManager")
        return Result.success()
    }
}
