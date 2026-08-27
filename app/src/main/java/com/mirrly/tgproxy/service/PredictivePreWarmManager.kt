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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.core.AppLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * Менеджер предиктивного упреждающего прогрева сокетов (Predictive Socket Pre-Warming).
 * Заранее поднимает WSS/TLS сессии при включении/разблокировке экрана, чтобы соединение Telegram
 * открывалось с 0 мс задержки на рукопожатие.
 */
object PredictivePreWarmManager {
    private const val TAG = "PredictivePreWarm"
    const val DEBOUNCE_INTERVAL_MS = 30_000L // Минимум 30 секунд между прогревами

    private val lastPreWarmTimestamp = AtomicLong(0L)
    private var isRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            handleTrigger(context, action)
        }
    }

    fun start(context: Context) {
        if (isRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_POWER_CONNECTED)
            }
            context.registerReceiver(receiver, filter)
            isRegistered = true
            AppLogger.i(TAG, "Служба предиктивного прогрева сокетов успешно зарегистрирована")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Ошибка регистрации ресивера предиктивного прогрева: ${t.message}")
        }
    }

    fun stop(context: Context) {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(receiver)
            isRegistered = false
            AppLogger.i(TAG, "Служба предиктивного прогрева сокетов остановлена")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Ошибка дерегистрации ресивера: ${t.message}")
        }
    }

    fun triggerExplicitly(reason: String = "APP_FOREGROUND") {
        handleTrigger(null, reason)
    }

    private fun handleTrigger(context: Context?, action: String) {
        val app = MirrlyApplication.instance
        if (!app.proxyServer.isRunning) return

        // 1. Проверка режима энергосбережения
        val ctx = context ?: app.applicationContext
        val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isPowerSave = powerManager?.isPowerSaveMode == true
        if (isPowerSave && action != Intent.ACTION_POWER_CONNECTED) {
            AppLogger.d(TAG, "Пропуск предиктивного прогрева ($action): активен системный режим энергосбережения")
            return
        }

        // 2. Дебаунс и лимит частоты (минимум 30 сек между прогревами)
        val now = System.currentTimeMillis()
        val last = lastPreWarmTimestamp.get()
        if (now - last < DEBOUNCE_INTERVAL_MS) {
            AppLogger.d(TAG, "Пропуск предиктивного прогрева ($action): дебаунс (${(now - last) / 1000}с < 30с)")
            return
        }

        lastPreWarmTimestamp.set(now)
        AppLogger.i(TAG, "Инициация предиктивного прогрева WsPool ($action)...")
        app.proxyServer.predictivePreWarm(action)
    }

    fun shouldPreWarm(
        isProxyRunning: Boolean,
        isPowerSaveMode: Boolean,
        currentTimeMs: Long,
        lastWarmTimeMs: Long,
        minCooldownMs: Long = DEBOUNCE_INTERVAL_MS
    ): Boolean {
        if (!isProxyRunning) return false
        if (isPowerSaveMode) return false
        return (currentTimeMs - lastWarmTimeMs) >= minCooldownMs
    }
}
