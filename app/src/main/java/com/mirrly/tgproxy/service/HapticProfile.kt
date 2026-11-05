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

/**
 * Hardware haptic vibration profiles for UI interactions.
 */
enum class HapticProfile(val id: String) {
    /** Mechanical toggle switch tactile click (Mechanical Click). */
    MECHANICAL_CLICK("mechanical_click"),

    /** Deep low-frequency sub-bass pulse (Sub-bass Pulse). */
    SUBBASS_PULSE("subbass_pulse"),

    /** Dual ultra-crisp micro-ticks (Cybernetic Double-Tick). */
    CYBERNETIC_DOUBLE_TICK("cybernetic_double_tick"),

    /** Silent mode: haptic feedback disabled (Silent). */
    STEALTH("stealth");

    companion object {
        fun fromString(value: String?): HapticProfile {
            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.id.equals(value, ignoreCase = true)
            } ?: MECHANICAL_CLICK
        }
    }
}
