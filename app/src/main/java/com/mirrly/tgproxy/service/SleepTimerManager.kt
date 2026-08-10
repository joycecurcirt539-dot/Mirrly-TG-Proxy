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

import android.content.Context
import android.content.Intent
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.core.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

data class SleepTimerState(
    val isActive: Boolean = false,
    val targetTimeMs: Long = 0L,
    val totalDurationMinutes: Int = 0,
    val remainingSeconds: Long = 0L
) {
    fun formatRemainingTime(): String {
        if (!isActive || remainingSeconds <= 0) return "00:00:00"
        val hours = remainingSeconds / 3600
        val minutes = (remainingSeconds % 3600) / 60
        val seconds = remainingSeconds % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun getRemainingMinutesCeil(): Int {
        if (!isActive || remainingSeconds <= 0) return 0
        return ((remainingSeconds + 59) / 60).toInt()
    }
}

object SleepTimerManager {
    private const val TAG = "SleepTimerManager"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var timerJob: Job? = null

    private val _timerState = MutableStateFlow(SleepTimerState())
    val timerState: StateFlow<SleepTimerState> = _timerState.asStateFlow()

    private var warned15 = false
    private var warned10 = false
    private var warned5 = false

    fun startTimer(context: Context, durationMinutes: Int) {
        if (durationMinutes <= 0) {
            cancelTimer(context)
            return
        }

        val targetMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(durationMinutes.toLong())
        val remainingSec = (durationMinutes * 60).toLong()

        warned15 = durationMinutes <= 15
        warned10 = durationMinutes <= 10
        warned5 = durationMinutes <= 5

        _timerState.value = SleepTimerState(
            isActive = true,
            targetTimeMs = targetMs,
            totalDurationMinutes = durationMinutes,
            remainingSeconds = remainingSec
        )

        AppLogger.i(TAG, "Таймер автоотключения запущен на $durationMinutes мин (до $targetMs)")
        startTickLoop(context.applicationContext)
    }

    fun extendTimer(context: Context, extraMinutes: Int) {
        val current = _timerState.value
        if (!current.isActive) {
            startTimer(context, extraMinutes)
            return
        }

        val newTargetMs = current.targetTimeMs + TimeUnit.MINUTES.toMillis(extraMinutes.toLong())
        val newTotalMinutes = current.totalDurationMinutes + extraMinutes
        val newRemainingSec = ((newTargetMs - System.currentTimeMillis()).coerceAtLeast(0) / 1000)

        val remMinutes = ((newRemainingSec + 59) / 60).toInt()
        if (remMinutes > 15) warned15 = false
        if (remMinutes > 10) warned10 = false
        if (remMinutes > 5) warned5 = false

        _timerState.value = current.copy(
            targetTimeMs = newTargetMs,
            totalDurationMinutes = newTotalMinutes,
            remainingSeconds = newRemainingSec
        )

        NotificationHelper.cancelTimerWarningNotification(context)
        AppLogger.i(TAG, "Таймер продлен на +$extraMinutes мин (осталось $remMinutes мин)")
    }

    fun cancelTimer(context: Context) {
        timerJob?.cancel()
        timerJob = null
        _timerState.value = SleepTimerState(isActive = false)
        warned15 = false
        warned10 = false
        warned5 = false
        NotificationHelper.cancelTimerWarningNotification(context)
        AppLogger.i(TAG, "Таймер автоотключения отменен")
    }

    private fun startTickLoop(appContext: Context) {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                val current = _timerState.value
                if (!current.isActive) break

                val now = System.currentTimeMillis()
                val diffMs = current.targetTimeMs - now
                val remSeconds = (diffMs.coerceAtLeast(0) / 1000)

                if (diffMs <= 0) {
                    _timerState.value = SleepTimerState(isActive = false)
                    onTimerExpired(appContext)
                    break
                }

                _timerState.value = current.copy(remainingSeconds = remSeconds)

                val remMinutes = ((remSeconds + 59) / 60).toInt()
                checkAndSendWarnings(appContext, remMinutes)

                delay(1000)
            }
        }
    }

    private fun checkAndSendWarnings(context: Context, remainingMinutes: Int) {
        if (remainingMinutes in 11..15 && !warned15) {
            warned15 = true
            NotificationHelper.showTimerWarningNotification(context, remainingMinutes)
        } else if (remainingMinutes in 6..10 && !warned10) {
            warned10 = true
            NotificationHelper.showTimerWarningNotification(context, remainingMinutes)
        } else if (remainingMinutes in 1..5 && !warned5) {
            warned5 = true
            NotificationHelper.showTimerWarningNotification(context, remainingMinutes)
        }
    }

    private fun onTimerExpired(context: Context) {
        AppLogger.i(TAG, "Таймер автоотключения истек! Остановка прокси-сервера...")
        NotificationHelper.cancelTimerWarningNotification(context)

        // Stop Proxy Service via Intent
        val stopIntent = Intent(context, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_STOP
        }
        try {
            context.startService(stopIntent)
        } catch (_: Exception) {
            MirrlyApplication.instance.proxyServer.stop()
        }

        NotificationHelper.showTimerExpiredNotification(context)
    }
}
