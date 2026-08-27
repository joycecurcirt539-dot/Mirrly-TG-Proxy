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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BatteryThermalQoSTest {

    @Test
    fun testNormalStateReturnsNoThrottle() {
        val state = DeviceThermalState(
            batteryPercent = 85,
            isCharging = false,
            isPowerSaveMode = false,
            thermalStatus = BatteryThermalQoSEngine.THERMAL_STATUS_NONE
        )
        val level = BatteryThermalQoSEngine.evaluateThrottleLevel(state)
        assertEquals(QoSThrottleLevel.NONE, level)
        assertEquals(16, level.maxPoolSize)
        assertEquals(2097152, level.maxBufferSizeBytes)
    }

    @Test
    fun testPowerSaveModeEnforcesSevereThrottle() {
        val state = DeviceThermalState(
            batteryPercent = 50,
            isCharging = false,
            isPowerSaveMode = true,
            thermalStatus = BatteryThermalQoSEngine.THERMAL_STATUS_NONE
        )
        val level = BatteryThermalQoSEngine.evaluateThrottleLevel(state)
        assertEquals(QoSThrottleLevel.SEVERE, level)
        assertEquals(2, level.maxPoolSize)
        assertEquals(131072, level.maxBufferSizeBytes)
    }

    @Test
    fun testThermalSevereEnforcesSevereThrottle() {
        val state = DeviceThermalState(
            batteryPercent = 90,
            isCharging = true,
            isPowerSaveMode = false,
            thermalStatus = BatteryThermalQoSEngine.THERMAL_STATUS_SEVERE
        )
        val level = BatteryThermalQoSEngine.evaluateThrottleLevel(state)
        assertEquals(QoSThrottleLevel.SEVERE, level)
        assertEquals(2, level.maxPoolSize)
    }

    @Test
    fun testLowBatteryUnpluggedEnforcesModerateThrottle() {
        val state = DeviceThermalState(
            batteryPercent = 18,
            isCharging = false,
            isPowerSaveMode = false,
            thermalStatus = BatteryThermalQoSEngine.THERMAL_STATUS_NONE
        )
        val level = BatteryThermalQoSEngine.evaluateThrottleLevel(state)
        assertEquals(QoSThrottleLevel.MODERATE, level)
        assertEquals(4, level.maxPoolSize)
        assertEquals(262144, level.maxBufferSizeBytes)
    }

    @Test
    fun testLowBatteryPluggedInAllowsFullSpeed() {
        val state = DeviceThermalState(
            batteryPercent = 15,
            isCharging = true, // Phone is charging
            isPowerSaveMode = false,
            thermalStatus = BatteryThermalQoSEngine.THERMAL_STATUS_NONE
        )
        val level = BatteryThermalQoSEngine.evaluateThrottleLevel(state)
        assertEquals(QoSThrottleLevel.NONE, level, "Charging should allow full performance even at low battery")
    }

    @Test
    fun testCriticalBatteryUnpluggedEnforcesSevereThrottle() {
        val state = DeviceThermalState(
            batteryPercent = 8,
            isCharging = false,
            isPowerSaveMode = false,
            thermalStatus = BatteryThermalQoSEngine.THERMAL_STATUS_NONE
        )
        val level = BatteryThermalQoSEngine.evaluateThrottleLevel(state)
        assertEquals(QoSThrottleLevel.SEVERE, level)
    }
}
