/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service.vpn

import com.mirrly.tgproxy.ui.theme.VpnUiState

/**
 * Внутренние состояния жизненного цикла VPN-службы (Task N02).
 */
enum class VpnInternalState {
    /** Служба не активна, ресурсы и TUN освобождены */
    IDLE,

    /** Ожидание системного диалога подтверждения пользователя (VpnService.prepare) */
    AWAITING_PERMISSION,

    /** Подготовка профиля (валидация ключей, авторегистрация WARP, проверка VLESS) */
    PREPARING,

    /** Поднятие интерфейса TUN, защита сокетов, старт локального бэкенда */
    CONNECTING,

    /** Проверка data-plane туннеля (probes, DNS resolution, synthetic handshake) */
    VERIFYING,

    /** Полноценная работа с активным пропуском пользовательского трафика */
    RUNNING,

    /** Временная потеря физической сети или сбой аплинка (TUN удерживается, утечки блокируются) */
    RECONNECTING,

    /** Идемпотентная процедура плавной остановки и закрытия TUN */
    STOPPING,

    /** Ошибка запуска или критический сбой (с детальной причиной) */
    FAILED;

    /**
     * Преобразование в UI-состояние для Jetpack Compose.
     */
    fun toUiState(): VpnUiState = when (this) {
        IDLE -> VpnUiState.DISCONNECTED
        AWAITING_PERMISSION, PREPARING, CONNECTING, VERIFYING -> VpnUiState.CONNECTING
        RUNNING -> VpnUiState.CONNECTED
        RECONNECTING -> VpnUiState.CONNECTING
        STOPPING -> VpnUiState.DISCONNECTING
        FAILED -> VpnUiState.DISCONNECTED
    }
}

/**
 * Причины отказа запуска или сбоя VPN-службы (Task N02).
 */
enum class VpnFailureReason {
    NONE,
    PERMISSION_DENIED,
    REVOKED,
    ESTABLISH_FAILED,
    PROFILE_INVALID,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    UNKNOWN;

    fun getDisplayMessage(): String = when (this) {
        NONE -> ""
        PERMISSION_DENIED -> "Пользователь отклонил системное разрешение VPN"
        REVOKED -> "Разрешение VPN отозвано системой или другим приложением"
        ESTABLISH_FAILED -> "Не удалось создать системный интерфейс TUN (VpnService.establish вернул null)"
        PROFILE_INVALID -> "Конфигурация профиля повреждена или не заполнена"
        NETWORK_UNAVAILABLE -> "Физическая сеть недоступна"
        TIMEOUT -> "Превышен таймаут установления защищенного туннеля"
        UNKNOWN -> "Неизвестная ошибка VPN службы"
    }
}

/**
 * Снимок текущего статуса VPN службы (Task N02, N14, N20).
 */
data class VpnStatus(
    val internalState: VpnInternalState = VpnInternalState.IDLE,
    val uiState: VpnUiState = VpnUiState.DISCONNECTED,
    val failureReason: VpnFailureReason = VpnFailureReason.NONE,
    val generation: Long = 0L,
    val activeProfileName: String = "",
    val uplinkMode: String = "",
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L,
    val activeTcpFlows: Int = 0,
    val activeUdpSessions: Int = 0,
    val uptimeMs: Long = 0L
) {
    val isRunning: Boolean get() = internalState == VpnInternalState.RUNNING
    val isConnecting: Boolean get() = internalState in setOf(
        VpnInternalState.PREPARING,
        VpnInternalState.CONNECTING,
        VpnInternalState.VERIFYING,
        VpnInternalState.RECONNECTING
    )
}
