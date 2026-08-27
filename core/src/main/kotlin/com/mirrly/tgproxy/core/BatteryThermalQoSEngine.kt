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

package com.mirrly.tgproxy.core

enum class QoSThrottleLevel(val maxPoolSize: Int, val maxBufferSizeBytes: Int, val description: String) {
    NONE(16, 2097152, "Полная производительность"),
    MODERATE(4, 262144, "Ограничение Balanced (умеренный нагрев / низкий заряд)"),
    SEVERE(2, 131072, "Энергосбережение Eco (сильный нагрев / режим энергосбережения)")
}

data class DeviceThermalState(
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val isPowerSaveMode: Boolean = false,
    val thermalStatus: Int = 0 // 0 = THERMAL_STATUS_NONE
)

class BatteryThermalQoSEngine(
    private val onThrottleLevelChanged: ((QoSThrottleLevel) -> Unit)? = null
) {
    @Volatile
    var currentState: DeviceThermalState = DeviceThermalState()
        private set

    @Volatile
    var currentThrottleLevel: QoSThrottleLevel = QoSThrottleLevel.NONE
        private set

    val maxAllowedPoolSize: Int
        get() = currentThrottleLevel.maxPoolSize

    val maxAllowedBufferSizeBytes: Int
        get() = currentThrottleLevel.maxBufferSizeBytes

    fun updateState(
        batteryPercent: Int,
        isCharging: Boolean,
        isPowerSaveMode: Boolean,
        thermalStatus: Int
    ) {
        val newState = DeviceThermalState(
            batteryPercent = batteryPercent.coerceIn(0, 100),
            isCharging = isCharging,
            isPowerSaveMode = isPowerSaveMode,
            thermalStatus = thermalStatus
        )
        currentState = newState

        val newLevel = evaluateThrottleLevel(newState)
        if (newLevel != currentThrottleLevel) {
            val oldLevel = currentThrottleLevel
            currentThrottleLevel = newLevel
            AppLogger.i(
                "QoS",
                "Изменение уровня троттлинга QoS: ${oldLevel.name} -> ${newLevel.name} (${newLevel.description})"
            )
            onThrottleLevelChanged?.invoke(newLevel)
        }
    }

    companion object {
        const val THERMAL_STATUS_NONE = 0
        const val THERMAL_STATUS_LIGHT = 1
        const val THERMAL_STATUS_MODERATE = 2
        const val THERMAL_STATUS_SEVERE = 3
        const val THERMAL_STATUS_CRITICAL = 4
        const val THERMAL_STATUS_EMERGENCY = 5
        const val THERMAL_STATUS_SHUTDOWN = 6

        fun evaluateThrottleLevel(state: DeviceThermalState): QoSThrottleLevel {
            // 1. Сильный нагрев или системный режим экстремального энергосбережения
            if (state.thermalStatus >= THERMAL_STATUS_SEVERE ||
                state.isPowerSaveMode ||
                (!state.isCharging && state.batteryPercent <= 10)
            ) {
                return QoSThrottleLevel.SEVERE
            }

            // 2. Умеренный нагрев или разряд батареи ниже 20% без зарядки
            if (state.thermalStatus >= THERMAL_STATUS_MODERATE ||
                (!state.isCharging && state.batteryPercent <= 20)
            ) {
                return QoSThrottleLevel.MODERATE
            }

            // 3. Нормальный режим
            return QoSThrottleLevel.NONE
        }
    }
}
