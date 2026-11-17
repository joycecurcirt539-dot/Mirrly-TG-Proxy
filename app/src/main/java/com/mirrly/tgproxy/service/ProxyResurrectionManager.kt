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
import com.mirrly.tgproxy.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Отказоустойчивый менеджер восстановления и удержания работы службы прокси/VPN
 * при удалении приложения из недавних задач (onTaskRemoved), вытеснении LMK
 * и в режиме глубокого сна (Doze Mode / PowerGenie) на Android 14–16 (API 34–36)
 * и агрессивных OEM-оболочках (MagicOS 9, HyperOS, ColorOS, Realme UI).
 *
 * Архитектура удержания и восстановления:
 * 1. Tier 1: Immediate Alarm (RTC_WAKEUP, +1.5s) — моментальное пробуждение при выгрузке по свайпу.
 * 2. Tier 2: WorkManager (OneTimeWork, +3s) — страховка на случай блокировки будильников лаунчером.
 * 3. Tier 3: Watchdog Heartbeat (+15 мин) — циклический сторожевой таймер:
 *    - Если служба жива: продлевает аппаратный WakeLock и сдвигает таймер вперед.
 *    - Если служба молча уничтожена ночью в Doze mode: пробуждает CPU, восстанавливает ForegroundService
 *      и обходит ограничения запуска из фона (ForegroundServiceStartNotAllowedException).
 */
object ProxyResurrectionManager {
    private const val TAG = "ProxyResurrectionManager"
    const val ACTION_RESURRECT_PROXY = "com.mirrly.tgproxy.ACTION_RESURRECT_PROXY"
    const val ACTION_WATCHDOG_HEARTBEAT = "com.mirrly.tgproxy.ACTION_WATCHDOG_HEARTBEAT"

    private const val RESURRECTION_REQUEST_CODE = 9021
    private const val WATCHDOG_REQUEST_CODE = 9022
    private const val UNIQUE_WORK_NAME = "mirrly_proxy_resurrection"
    private const val TAG_RESURRECTION_WORK = "proxy_resurrection"

    /** Интервал циклического сторожевого таймера (15 минут) */
    const val WATCHDOG_INTERVAL_MS = 15L * 60 * 1000L

    /** Дельта для мгновенного перезапуска при свайпе задачи */
    private const val SWIPE_RESURRECTION_DELTA_MS = 1500L

    /**
     * Планирует восстановление службы при смахивании задачи (onTaskRemoved) или неожиданном onDestroy.
     */
    fun schedule(context: Context) {
        val app = MirrlyApplication.instance
        val prefsManager = app.prefsManager
        if (!prefsManager.isProxyActive()) {
            AppLogger.d(TAG, "schedule: proxy is not marked active in preferences, skipping")
            return
        }

        AppLogger.i(TAG, "Scheduling two-tier immediate resurrection + arming watchdog (Android ${Build.VERSION.SDK_INT})")
        scheduleImmediateAlarm(context)
        scheduleImmediateWorkManager(context)
        scheduleWatchdogHeartbeat(context)
    }

    /**
     * Запускает периодический сторожевой таймер (Heartbeat) при успешном старте службы.
     */
    fun startWatchdog(context: Context) {
        val app = MirrlyApplication.instance
        if (!app.prefsManager.isProxyActive()) return

        cancelImmediateAlarms(context)
        scheduleWatchdogHeartbeat(context)
        AppLogger.i(TAG, "Watchdog heartbeat armed (every ${WATCHDOG_INTERVAL_MS / 60000}m)")
    }

    /**
     * Отменяет все запланированные задачи восстановления и сторожевой таймер
     * (вызывается при преднамеренном отключении прокси пользователем).
     */
    fun cancel(context: Context) {
        cancelImmediateAlarms(context)
        cancelWatchdogAlarm(context)
        cancelWorkManager(context)
        AppLogger.d(TAG, "All proxy resurrection and watchdog tasks cancelled")
    }

    /**
     * Обработка очередного срабатывания сторожевого пульса (Watchdog Heartbeat).
     */
    fun onHeartbeatReceived(context: Context) {
        val app = MirrlyApplication.instance
        val prefsManager = app.prefsManager
        if (!prefsManager.isProxyActive()) {
            AppLogger.d(TAG, "Watchdog heartbeat: proxy not active in prefs, disarming watchdog")
            cancel(context)
            return
        }

        val isVpnActive = prefsManager.isVpnModeEnabled()
        val isAlive = if (isVpnActive) {
            MirrlyVpnService.isRunning
        } else {
            app.proxyServer.isRunning
        }

        if (isAlive) {
            AppLogger.d(TAG, "Watchdog heartbeat: service is alive (vpn=$isVpnActive). Refreshing WakeLock...")
            if (!isVpnActive) {
                refreshWakeLockIfRunning(context)
            }
            // Перепланируем следующий пульс сторожевого таймера
            scheduleWatchdogHeartbeat(context)
        } else {
            AppLogger.w(TAG, "Watchdog heartbeat detected DEAD service while active in prefs! Resurrecting now...")
            resurrectIfNeeded(context, reason = "WatchdogHeartbeat")
            scheduleWatchdogHeartbeat(context)
        }
    }

    /**
     * Мгновенный будильник на +1.5 сек.
     */
    private fun scheduleImmediateAlarm(context: Context) {
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

        val triggerAtMillis = System.currentTimeMillis() + SWIPE_RESURRECTION_DELTA_MS
        armAlarmWithFallback(alarmManager, triggerAtMillis, pendingIntent, context)
        AppLogger.d(TAG, "Immediate resurrection alarm scheduled (+${SWIPE_RESURRECTION_DELTA_MS}ms)")
    }

    /**
     * Планирование следующего сторожевого пульса (Heartbeat).
     */
    private fun scheduleWatchdogHeartbeat(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ProxyResurrectionReceiver::class.java).apply {
            action = ACTION_WATCHDOG_HEARTBEAT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
        armAlarmWithFallback(alarmManager, triggerAtMillis, pendingIntent, context)
    }

    /**
     * Универсальная постановка будильника с гарантированным обходом ограничений Android 12–16.
     * Если exact-будильники заблокированы политикой ОС, используется setAlarmClock,
     * который не требует разрешений и всегда выводит процессор из глубокого Doze.
     */
    private fun armAlarmWithFallback(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
        context: Context
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val app = MirrlyApplication.instance
                val preferAlarmClock = app.config.isAlarmClockWatchdogEnabled
                if (preferAlarmClock) {
                    armAlarmClock(alarmManager, triggerAtMillis, pendingIntent, context)
                } else {
                    val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        alarmManager.canScheduleExactAlarms()
                    } else {
                        true
                    }
                    if (canExact) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    } else {
                        armAlarmClock(alarmManager, triggerAtMillis, pendingIntent, context)
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (_: SecurityException) {
            try {
                armAlarmClock(alarmManager, triggerAtMillis, pendingIntent, context)
            } catch (fallbackEx: Exception) {
                AppLogger.w(TAG, "Failed to arm alarm via alarm clock fallback: ${fallbackEx.message}")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Unexpected error scheduling alarm: ${e.message}")
        }
    }

    /**
     * Запасной запуск через AlarmClockInfo. Дает 100% гарантию пробуждения устройства
     * и официальное право на старт ForegroundService из фона на Android 14–16.
     */
    private fun armAlarmClock(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
        context: Context
    ) {
        val showIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val showPendingIntent = PendingIntent.getActivity(
            context,
            0,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent)
        alarmManager.setAlarmClock(info, pendingIntent)
    }

    /**
     * WorkManager страховка (+3 сек) при удалении из недавних.
     */
    private fun scheduleImmediateWorkManager(context: Context) {
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

    private fun cancelImmediateAlarms(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
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
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to cancel immediate resurrection alarm: ${e.message}")
        }
    }

    private fun cancelWatchdogAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, ProxyResurrectionReceiver::class.java).apply {
                action = ACTION_WATCHDOG_HEARTBEAT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to cancel watchdog alarm: ${e.message}")
        }
    }

    private fun cancelWorkManager(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to cancel WorkManager: ${e.message}")
        }
    }

    private fun refreshWakeLockIfRunning(context: Context) {
        try {
            ProxyForegroundService.refreshWakeLockDirectly()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to direct-refresh WakeLock: ${e.message}")
        }
    }

    /**
     * Проверка и восстановление службы.
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
            if (MirrlyVpnService.isRunning) {
                AppLogger.d(TAG, "Resurrection skipped: VPN service is already running")
                return
            }
            if (MirrlyVpnService.prepare(context) == null) {
                AppLogger.i(TAG, "Resurrecting MirrlyVpnService ($reason)...")
                MirrlyVpnService.start(context)
            } else {
                AppLogger.w(TAG, "Cannot resurrect MirrlyVpnService: VPN permission not granted")
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
            AppLogger.i(TAG, "Successfully requested ProxyForegroundService resurrection ($reason)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start service during resurrection ($reason): ${e.message}")
            // Если Android 14+ заблокировал прямой запуск из фона (ForegroundServiceStartNotAllowedException),
            // взводим микро-будильник через setAlarmClock на +300мс, гарантирующий исключение из блокировки
            armMicroResurrectionAlarmClock(context)
        }
    }

    private fun armMicroResurrectionAlarmClock(context: Context) {
        try {
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
            armAlarmClock(alarmManager, System.currentTimeMillis() + 300L, pendingIntent, context)
            AppLogger.i(TAG, "Armed setAlarmClock micro-fallback (+300ms) to bypass background start restriction")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to arm setAlarmClock micro-fallback: ${e.message}")
        }
    }
}

/**
 * BroadcastReceiver для получения сигналов пробуждения от AlarmManager.
 */
class ProxyResurrectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ProxyResurrectionManager.ACTION_RESURRECT_PROXY -> {
                AppLogger.i("ProxyResurrectionReceiver", "Resurrection alarm received")
                ProxyResurrectionManager.resurrectIfNeeded(context, reason = "AlarmManager")
            }
            ProxyResurrectionManager.ACTION_WATCHDOG_HEARTBEAT -> {
                AppLogger.i("ProxyResurrectionReceiver", "Watchdog heartbeat alarm received")
                ProxyResurrectionManager.onHeartbeatReceived(context)
            }
        }
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
