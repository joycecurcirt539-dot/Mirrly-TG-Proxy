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
 * Procedural AGSL GPU shader presets for background rendering (Android 13+ / API 33+).
 */
enum class AgslShaderPreset(val id: String) {
    /** 3D vector plane with receding perspective lines towards the horizon. */
    PERSPECTIVE_GRID("perspective_grid"),

    /** Vertical cascading streams of cybernetic data packets. */
    DIGITAL_STREAM("digital_stream"),

    /** Micro CRT raster lines with subtle edge chromatic aberration. */
    SCANLINES("scanlines");

    companion object {
        fun fromString(value: String?): AgslShaderPreset {
            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.id.equals(value, ignoreCase = true)
            } ?: PERSPECTIVE_GRID
        }
    }
}
