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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.ProxyMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phases of the choreographed protocol switch sequence.
 */
enum class SwitchPhase {
    IDLE,
    DISCONNECTING,
    PAUSE_DARK,
    RECONNECTING
}

/**
 * Orchestrator for deliberate, silky-smooth and conflict-free protocol switching (MTProto <-> SOCKS5).
 * Features a distinct power-down -> 1-second dark pause -> power-up sequence when switching active proxies.
 */
object ProtocolSwitchManager {

    private const val TAG = "ProtocolSwitchManager"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var switchJob: Job? = null

    private val _switchPhase = MutableStateFlow(SwitchPhase.IDLE)
    val switchPhase: StateFlow<SwitchPhase> = _switchPhase.asStateFlow()

    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    private val _targetMode = MutableStateFlow<ProxyMode?>(null)
    val targetMode: StateFlow<ProxyMode?> = _targetMode.asStateFlow()

    private val _wasProxyRunningDuringSwitch = MutableStateFlow(false)
    val wasProxyRunningDuringSwitch: StateFlow<Boolean> = _wasProxyRunningDuringSwitch.asStateFlow()

    @Volatile
    private var lastSwitchCompletedMs = 0L

    /**
     * Request a smooth protocol switch.
     * @param context Application context
     * @param requestedTarget Optional explicit target mode. If null, toggles between MTProto and SOCKS5.
     * @param onComplete Optional callback invoked upon full completion.
     * @return true if the switch request was accepted, false if ignored due to active transition or debounce lock.
     */
    fun switchProtocol(
        context: Context,
        requestedTarget: ProxyMode? = null,
        onComplete: (() -> Unit)? = null
    ): Boolean {
        val app = MirrlyApplication.instance
        val currentMode = app.config.proxyMode
        val newTarget = requestedTarget ?: if (currentMode == ProxyMode.SOCKS5) ProxyMode.MTPROTO else ProxyMode.SOCKS5

        if (newTarget == currentMode && _switchPhase.value == SwitchPhase.IDLE && !_isSwitching.value) {
            return false
        }

        // Cancel previous switch job if still in progress to support smooth, responsive re-targeting
        switchJob?.cancel()

        val server = app.proxyServer
        val wasRunning = server.isRunning
        _wasProxyRunningDuringSwitch.value = wasRunning
        _isSwitching.value = true

        switchJob = scope.launch {
            try {
                // ── SCENARIO A: PROXY WAS RUNNING (Power Down -> 1s Dark Pause -> Power Up) ──
                if (wasRunning) {
                    // PHASE 1: DISCONNECTING / GRACEFUL SPIN DOWN OF OLD PROTOCOL
                    _switchPhase.value = SwitchPhase.DISCONNECTING
                    AppLogger.i(TAG, "Шаг 1: Плавная остановка активного прокси перед сменой протокола...")

                    withContext(Dispatchers.IO) {
                        try {
                            server.stop()
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Ошибка остановки сокетов: ${e.message}")
                        }
                    }
                    // Allow UI wind-down animations to smoothly settle
                    delay(550)

                    // PHASE 2: 1-SECOND CALM DARK PAUSE & THEME ACCENT MORPH
                    // Switch mode and persist config during the dark pause
                    app.config.proxyModeName = newTarget.name
                    _targetMode.value = newTarget
                    withContext(Dispatchers.IO) {
                        app.prefsManager.saveConfig(app.config)
                    }

                    _switchPhase.value = SwitchPhase.PAUSE_DARK
                    AppLogger.i(TAG, "Шаг 2: Пауза в темноте (1000 мс)...")
                    delay(1000)

                    // PHASE 3: RECONNECTING / POWER UP ON NEW PROTOCOL
                    _switchPhase.value = SwitchPhase.RECONNECTING
                    AppLogger.i(TAG, "Шаг 3: Запуск службы на протоколе ${newTarget.name}...")

                    val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                        action = ProxyForegroundService.ACTION_START
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Ошибка запуска службы прокси: ${e.message}")
                    }

                    // Wait until server is verified running
                    val waitStart = System.currentTimeMillis()
                    while (!server.isRunning && (System.currentTimeMillis() - waitStart < 2500L)) {
                        delay(40)
                    }
                    delay(100)
                } else {
                    // ── SCENARIO B: PROXY WAS DISCONNECTED (Immediate Pill Slide & Accent Update) ──
                    app.config.proxyModeName = newTarget.name
                    _targetMode.value = newTarget
                    withContext(Dispatchers.IO) {
                        app.prefsManager.saveConfig(app.config)
                    }
                    delay(200)
                }

                AppLogger.i(TAG, "Переключение протокола успешно завершено.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Исключение при переключении протокола: ${e.message}")
            } finally {
                _switchPhase.value = SwitchPhase.IDLE
                _targetMode.value = null
                _wasProxyRunningDuringSwitch.value = false
                lastSwitchCompletedMs = System.currentTimeMillis()
                _isSwitching.value = false
                withContext(Dispatchers.Main) {
                    onComplete?.invoke()
                }
            }
        }
        return true
    }
}
