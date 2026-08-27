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

enum class WorkerStatus {
    ONLINE,
    RATE_LIMITED_429,
    ERROR_UNREACHABLE,
    UNKNOWN
}

data class WorkerProfile(
    val id: String,
    val name: String,
    val domain: String,
    val isDeveloperWorker: Boolean = false,
    var lastPingMs: Long? = null,
    var lastStatus: WorkerStatus = WorkerStatus.UNKNOWN,
    var circuitState: CircuitState = CircuitState.CLOSED,
    var cooldownRemainingSec: Long = 0L
)
