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
    SWITCHING_ACCENT,
    RECONNECTING
}

/**
 * Orchestrator for deliberate, silky-smooth and conflict-free protocol switching (MTProto <-> SOCKS5).
 * Prevents rapid spamming, smoothly tears down running connections, morphs the UI theme accent,
 * and boots the new protocol cleanly without glitches.
 */
object ProtocolSwitchManager {

    private const val TAG = "ProtocolSwitchManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var switchJob: Job? = null

    private val _switchPhase = MutableStateFlow(SwitchPhase.IDLE)
    val switchPhase: StateFlow<SwitchPhase> = _switchPhase.asStateFlow()

    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    private val _targetMode = MutableStateFlow<ProxyMode?>(null)
    val targetMode: StateFlow<ProxyMode?> = _targetMode.asStateFlow()

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

        // 1. Immediately apply target mode in memory (0 ms UI state synchronization)
        app.config.proxyModeName = newTarget.name
        _targetMode.value = newTarget
        _isSwitching.value = true

        val modeLabel = if (newTarget == ProxyMode.SOCKS5) "SOCKS5" else "MTProto"
        showToast(context, "Режим: $modeLabel")

        switchJob = scope.launch {
            try {
                // Asynchronously persist configuration to disk without blocking the main looper
                withContext(Dispatchers.IO) {
                    app.prefsManager.saveConfig(app.config)
                }

                val server = app.proxyServer
                val wasRunning = server.isRunning

                // ── STEP 1: Graceful Disconnect / Spin-Down (if proxy is currently running) ──
                if (wasRunning) {
                    _switchPhase.value = SwitchPhase.DISCONNECTING
                    AppLogger.i(TAG, "Шаг 1: Плавная остановка прокси перед сменой протокола...")

                    withContext(Dispatchers.IO) {
                        try {
                            server.stop()
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Ошибка остановки сокетов: ${e.message}")
                        }
                    }

                    _switchPhase.value = SwitchPhase.SWITCHING_ACCENT
                    delay(260)

                    // ── STEP 2: Graceful Reconnect / Start on New Protocol (if previously running) ──
                    _switchPhase.value = SwitchPhase.RECONNECTING
                    AppLogger.i(TAG, "Шаг 2: Плавный запуск службы на протоколе ${newTarget.name}...")

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
                    delay(200)
                } else {
                    _switchPhase.value = SwitchPhase.SWITCHING_ACCENT
                    delay(150)
                }

                AppLogger.i(TAG, "Переключение протокола успешно завершено.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Исключение при переключении протокола: ${e.message}")
            } finally {
                _switchPhase.value = SwitchPhase.IDLE
                _targetMode.value = null
                lastSwitchCompletedMs = System.currentTimeMillis()
                _isSwitching.value = false
                onComplete?.invoke()
            }
        }
        return true
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
