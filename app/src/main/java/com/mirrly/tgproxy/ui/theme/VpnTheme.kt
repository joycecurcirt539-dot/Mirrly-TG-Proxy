/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

enum class VpnUiState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

val VpnElectricAmberPalette = ProtocolColors(
    primary = Color(0xFFFF6B2B), // Electric Amber
    glow = Color(0x3DFF6B2B),
    secondary = Color(0xFFFF9E0B),
    light = Color(0xFFFFD166),
    orb1 = Color(0xFFFF6B2B),
    orb2 = Color(0xFFFF9E0B),
    orb3 = Color(0xFFFFB703),
    orb4 = Color(0xFFFF5400)
)

val VpnCyberRubyPalette = ProtocolColors(
    primary = Color(0xFFFF2A6D), // Cyber Ruby
    glow = Color(0x3DFF2A6D),
    secondary = Color(0xFFFF0055),
    light = Color(0xFFFF7597),
    orb1 = Color(0xFFFF2A6D),
    orb2 = Color(0xFFFF0055),
    orb3 = Color(0xFFFF5277),
    orb4 = Color(0xFFD90429)
)

val VpnAcidLimePalette = ProtocolColors(
    primary = Color(0xFFA6FF00), // Acid Lime
    glow = Color(0x3DA6FF00),
    secondary = Color(0xFF84CC16),
    light = Color(0xFFD4FF70),
    orb1 = Color(0xFFA6FF00),
    orb2 = Color(0xFF84CC16),
    orb3 = Color(0xFFCCFF00),
    orb4 = Color(0xFF65A30D)
)

val VpnElectricBluePalette = ProtocolColors(
    primary = Color(0xFF2979FF), // Cobalt Sapphire
    glow = Color(0x3D2979FF),
    secondary = Color(0xFF0051CC),
    light = Color(0xFF82B1FF),
    orb1 = Color(0xFF2979FF),
    orb2 = Color(0xFF0051CC),
    orb3 = Color(0xFF448AFF),
    orb4 = Color(0xFF0039CB)
)

val VpnSolarFlarePalette = ProtocolColors(
    primary = Color(0xFFFF5252), // Solar Flare / Coral
    glow = Color(0x3DFF5252),
    secondary = Color(0xFFFF7A00),
    light = Color(0xFFFF8A80),
    orb1 = Color(0xFFFF5252),
    orb2 = Color(0xFFFF7A00),
    orb3 = Color(0xFFFF3D00),
    orb4 = Color(0xFFFF9100)
)

val VpnCyberGoldPalette = ProtocolColors(
    primary = Color(0xFFFFB703), // Cyber Gold
    glow = Color(0x3DFFB703),
    secondary = Color(0xFFFB8500),
    light = Color(0xFFFFE169),
    orb1 = Color(0xFFFFB703),
    orb2 = Color(0xFFFB8500),
    orb3 = Color(0xFFFFD166),
    orb4 = Color(0xFFFF9E0B)
)

object VpnThemeManager {
    val curatedPalettes = listOf(
        VpnElectricAmberPalette,
        VpnCyberRubyPalette,
        VpnAcidLimePalette,
        VpnElectricBluePalette,
        VpnSolarFlarePalette,
        VpnCyberGoldPalette
    )

    private var cachedPalette: ProtocolColors? = null

    fun getSystemVpnPalette(context: Context): ProtocolColors {
        cachedPalette?.let { return it }

        // Deterministic hardware/device seed:
        val seed = (Build.FINGERPRINT.hashCode() xor Build.MODEL.hashCode()).let {
            if (it == Int.MIN_VALUE) 0 else abs(it)
        }
        val selected = curatedPalettes[seed % curatedPalettes.size]
        cachedPalette = selected
        return selected
    }
}
