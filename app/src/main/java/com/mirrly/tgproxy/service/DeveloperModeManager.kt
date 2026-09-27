/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+  <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Временный переключатель режима разработчика для Mirrly TG Proxy.
 *
 * Хранит состояние разблокировки VPN-режима исключительно в оперативной памяти (in-memory).
 * При перезапуске приложения или завершении процесса состояние автоматически
 * сбрасывается в false, требуя повторной активации через 5 нажатий по аватару разработчика.
 */
object DeveloperModeManager {
    private val _isDevVpnUnlocked = MutableStateFlow(false)
    val isDevVpnUnlocked: StateFlow<Boolean> = _isDevVpnUnlocked.asStateFlow()

    val isUnlocked: Boolean
        get() = _isDevVpnUnlocked.value

    fun unlockDevVpn() {
        _isDevVpnUnlocked.value = true
    }

    fun lockDevVpn() {
        _isDevVpnUnlocked.value = false
    }

    fun toggleDevVpn() {
        _isDevVpnUnlocked.value = !_isDevVpnUnlocked.value
    }
}
