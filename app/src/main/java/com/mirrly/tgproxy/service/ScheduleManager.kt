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
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.core.AppLogger
import java.util.Calendar

enum class ScheduleDaysMode(val title: String) {
    EVERY_DAY("Каждый день"),
    WEEKDAYS("Будни (Пн-Пт)"),
    WEEKENDS("Выходные (Сб-Вс)"),
    CUSTOM("Выбранные дни");

    companion object {
        fun fromName(name: String?): ScheduleDaysMode {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: EVERY_DAY
        }
    }
}

data class ScheduleConfig(
    val isEnabled: Boolean = false,
    val startHour: Int = 8,
    val startMinute: Int = 0,
    val stopHour: Int = 23,
    val stopMinute: Int = 0,
    val daysMode: ScheduleDaysMode = ScheduleDaysMode.EVERY_DAY,
    val customDays: Set<Int> = setOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
        Calendar.SATURDAY,
        Calendar.SUNDAY
    )
) {
    fun formatStartTime(): String = String.format("%02d:%02d", startHour, startMinute)
    fun formatStopTime(): String = String.format("%02d:%02d", stopHour, stopMinute)

    fun isDayActive(calendarDayOfWeek: Int): Boolean {
        return when (daysMode) {
            ScheduleDaysMode.EVERY_DAY -> true
            ScheduleDaysMode.WEEKDAYS -> calendarDayOfWeek in Calendar.MONDAY..Calendar.FRIDAY
            ScheduleDaysMode.WEEKENDS -> calendarDayOfWeek == Calendar.SATURDAY || calendarDayOfWeek == Calendar.SUNDAY
            ScheduleDaysMode.CUSTOM -> calendarDayOfWeek in customDays
        }
    }

    fun getSummaryText(): String {
        if (!isEnabled) return "Расписание выключено"
        val timeRange = "${formatStartTime()} – ${formatStopTime()}"
        return "${daysMode.title}: $timeRange"
    }
}

object ScheduleManager {
    private const val TAG = "ScheduleManager"
    const val ACTION_SCHEDULE_START = "com.mirrly.tgproxy.SCHEDULE_START"
    const val ACTION_SCHEDULE_STOP = "com.mirrly.tgproxy.SCHEDULE_STOP"

    private const val REQUEST_CODE_START = 1001
    private const val REQUEST_CODE_STOP = 1002

    fun syncSchedule(context: Context) {
        val app = MirrlyApplication.instance
        val config = app.prefsManager.loadScheduleConfig()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val startIntent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_SCHEDULE_START
        }
        val stopIntent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_SCHEDULE_STOP
        }

        val startPending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_START,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (!config.isEnabled) {
            alarmManager.cancel(startPending)
            alarmManager.cancel(stopPending)
            AppLogger.d(TAG, "Расписание выключено, будильники отменены")
            return
        }

        val nextStartTime = calculateNextOccurrence(config.startHour, config.startMinute, config)
        val nextStopTime = calculateNextOccurrence(config.stopHour, config.stopMinute, config)

        scheduleAlarm(alarmManager, nextStartTime, startPending)
        scheduleAlarm(alarmManager, nextStopTime, stopPending)

        AppLogger.i(TAG, "Расписание активно: следующий старт в ${config.formatStartTime()}, стоп в ${config.formatStopTime()}")
    }

    private fun scheduleAlarm(alarmManager: AlarmManager, triggerMs: Long, pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            }
        }
    }

    fun calculateNextOccurrence(hour: Int, minute: Int, config: ScheduleConfig): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val now = System.currentTimeMillis()
        if (calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        for (i in 0..7) {
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            if (config.isDayActive(dayOfWeek)) {
                break
            }
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        return calendar.timeInMillis
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = MirrlyApplication.instance
        val scheduleConfig = app.prefsManager.loadScheduleConfig()

        when (action) {
            ScheduleManager.ACTION_SCHEDULE_START -> {
                val calendar = Calendar.getInstance()
                if (scheduleConfig.isEnabled && scheduleConfig.isDayActive(calendar.get(Calendar.DAY_OF_WEEK))) {
                    if (!app.proxyServer.isRunning) {
                        AppLogger.i("ScheduleReceiver", "Запуск прокси по расписанию (${scheduleConfig.formatStartTime()})")
                        val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                            this.action = ProxyForegroundService.ACTION_START
                        }
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                        } catch (e: Exception) {
                            AppLogger.e("ScheduleReceiver", "Не удалось запустить службу по расписанию: ${e.message}")
                        }
                    }
                }
                ScheduleManager.syncSchedule(context)
            }
            ScheduleManager.ACTION_SCHEDULE_STOP -> {
                if (app.proxyServer.isRunning) {
                    AppLogger.i("ScheduleReceiver", "Остановка прокси по расписанию (${scheduleConfig.formatStopTime()})")
                    val stopIntent = Intent(context, ProxyForegroundService::class.java).apply {
                        this.action = ProxyForegroundService.ACTION_STOP
                    }
                    try {
                        context.startService(stopIntent)
                    } catch (e: Exception) {
                        AppLogger.e("ScheduleReceiver", "Не удалось остановить службу по расписанию: ${e.message}")
                    }
                }
                ScheduleManager.syncSchedule(context)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                ScheduleManager.syncSchedule(context)
            }
        }
    }
}
