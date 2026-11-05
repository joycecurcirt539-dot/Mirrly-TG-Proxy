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
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.ProxyMode
import kotlin.math.abs

/**
 * Mode determining the visual accent color engine of the application.
 */
enum class ThemeAccentMode(val storageKey: String) {
    PROTOCOL_DEFAULT("PROTOCOL_DEFAULT"),
    STEALTH("STEALTH"),
    PRESET("PRESET"),
    MONET("MONET"),
    CUSTOM("CUSTOM");

    companion object {
        fun fromStorageKey(key: String?): ThemeAccentMode {
            return entries.firstOrNull { it.storageKey.equals(key, ignoreCase = true) }
                ?: PROTOCOL_DEFAULT
        }
    }
}

/**
 * Monochrome Stealth Palette.
 * Strips all neon hues for pure AMOLED black & high-contrast silver-white minimalism.
 */
val StealthPalette = ProtocolColors(
    primary = Color(0xFFFFFFFF),       // Pure TextWhite
    glow = Color(0x33FFFFFF),          // 20% alpha subtle white halo
    secondary = Color(0xFFB0B0B8),     // Crisp Slate Gray
    light = Color(0xFFFFFFFF),         // Pure White
    orb1 = Color(0xFF383840),          // Graphite
    orb2 = Color(0xFF282830),          // Dark Slate
    orb3 = Color(0xFF1E1E24),          // Deep Obsidian
    orb4 = Color(0xFF121216)           // Carbon
)

/**
 * Curated Cyberpunk Preset description.
 */
data class CuratedPreset(
    val id: String,
    val nameResId: Int,
    val primaryColor: Color,
    val hex: String,
    val palette: ProtocolColors
)

/**
 * Curated cyberpunk neon presets.
 */
object ThemePresets {
    val CYBER_RUBY = CuratedPreset(
        id = "CYBER_RUBY",
        nameResId = R.string.settings_theme_preset_cyber_ruby,
        primaryColor = Color(0xFFFF2A6D),
        hex = "#FF2A6D",
        palette = ProtocolColors(
            primary = Color(0xFFFF2A6D),
            glow = Color(0x3DFF2A6D),
            secondary = Color(0xFFFF0055),
            light = Color(0xFFFF7597),
            orb1 = Color(0xFFFF2A6D),
            orb2 = Color(0xFFFF0055),
            orb3 = Color(0xFFFF5277),
            orb4 = Color(0xFFD90429)
        )
    )

    val ACID_LIME = CuratedPreset(
        id = "ACID_LIME",
        nameResId = R.string.settings_theme_preset_acid_lime,
        primaryColor = Color(0xFFA6FF00),
        hex = "#A6FF00",
        palette = ProtocolColors(
            primary = Color(0xFFA6FF00),
            glow = Color(0x3DA6FF00),
            secondary = Color(0xFF84CC16),
            light = Color(0xFFD4FF70),
            orb1 = Color(0xFFA6FF00),
            orb2 = Color(0xFF84CC16),
            orb3 = Color(0xFFCCFF00),
            orb4 = Color(0xFF65A30D)
        )
    )

    val ELECTRIC_AMBER = CuratedPreset(
        id = "ELECTRIC_AMBER",
        nameResId = R.string.settings_theme_preset_electric_amber,
        primaryColor = Color(0xFFFF6B2B),
        hex = "#FF6B2B",
        palette = ProtocolColors(
            primary = Color(0xFFFF6B2B),
            glow = Color(0x3DFF6B2B),
            secondary = Color(0xFFFF9E0B),
            light = Color(0xFFFFD166),
            orb1 = Color(0xFFFF6B2B),
            orb2 = Color(0xFFFF9E0B),
            orb3 = Color(0xFFFFB703),
            orb4 = Color(0xFFFF5400)
        )
    )

    val COBALT_BLUE = CuratedPreset(
        id = "COBALT_BLUE",
        nameResId = R.string.settings_theme_preset_cobalt_blue,
        primaryColor = Color(0xFF2979FF),
        hex = "#2979FF",
        palette = ProtocolColors(
            primary = Color(0xFF2979FF),
            glow = Color(0x3D2979FF),
            secondary = Color(0xFF0051CC),
            light = Color(0xFF82B1FF),
            orb1 = Color(0xFF2979FF),
            orb2 = Color(0xFF0051CC),
            orb3 = Color(0xFF448AFF),
            orb4 = Color(0xFF0039CB)
        )
    )

    val MATRIX_GREEN = CuratedPreset(
        id = "MATRIX_GREEN",
        nameResId = R.string.settings_theme_preset_matrix_green,
        primaryColor = Color(0xFF00E676),
        hex = "#00E676",
        palette = ProtocolColors(
            primary = Color(0xFF00E676),
            glow = Color(0x3D00E676),
            secondary = Color(0xFF00C853),
            light = Color(0xFF69F0AE),
            orb1 = Color(0xFF00E676),
            orb2 = Color(0xFF00C853),
            orb3 = Color(0xFF69F0AE),
            orb4 = Color(0xFF009624)
        )
    )

    val all: List<CuratedPreset> = listOf(
        CYBER_RUBY,
        ACID_LIME,
        ELECTRIC_AMBER,
        COBALT_BLUE,
        MATRIX_GREEN
    )

    fun getPresetById(id: String?): CuratedPreset? {
        if (id == null) return null
        return all.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * Pure Kotlin mathematical Color Engine.
 * Provides allocation-friendly HSL conversions and algorithmic ProtocolColors derivation.
 */
object ColorEngine {

    /**
     * Parses HEX strings into [Color].
     * Supports formats: `RGB`, `#RGB`, `RRGGBB`, `#RRGGBB`, `AARRGGBB`, `#AARRGGBB`.
     * Returns null if string is malformed.
     */
    fun parseHexColor(hexString: String?): Color? {
        if (hexString.isNullOrBlank()) return null
        val clean = hexString.trim().removePrefix("#")
        return try {
            when (clean.length) {
                3 -> {
                    val r = clean[0].toString().repeat(2).toInt(16)
                    val g = clean[1].toString().repeat(2).toInt(16)
                    val b = clean[2].toString().repeat(2).toInt(16)
                    Color(red = r, green = g, blue = b, alpha = 255)
                }
                6 -> {
                    val colorInt = clean.toLong(16).toInt()
                    Color(colorInt or (0xFF shl 24))
                }
                8 -> {
                    val colorLong = clean.toLong(16)
                    Color(colorLong.toInt())
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Converts a [Color] into a standard 6-digit `#RRGGBB` uppercase string.
     */
    fun toHex(color: Color): String {
        val r = (color.red * 255f).toInt().coerceIn(0, 255)
        val g = (color.green * 255f).toInt().coerceIn(0, 255)
        val b = (color.blue * 255f).toInt().coerceIn(0, 255)
        return String.format("#%02X%02X%02X", r, g, b)
    }

    /**
     * Converts RGB [0f..1f] components to HSL:
     * - Hue: [0f..360f]
     * - Saturation: [0f..1f]
     * - Lightness: [0f..1f]
     */
    fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val max = maxOf(r, maxOf(g, b))
        val min = minOf(r, minOf(g, b))
        val delta = max - min
        val l = (max + min) / 2f
        val s = if (delta == 0f) {
            0f
        } else {
            if (l <= 0.5f) delta / (max + min) else delta / (2f - max - min)
        }

        var h = when {
            delta == 0f -> 0f
            max == r -> ((g - b) / delta) % 6f
            max == g -> ((b - r) / delta) + 2f
            else -> ((r - g) / delta) + 4f
        }
        h *= 60f
        if (h < 0f) h += 360f

        return floatArrayOf(h, s, l)
    }

    /**
     * Converts HSL back to [Color].
     */
    fun hslToColor(h: Float, s: Float, l: Float, alpha: Float = 1f): Color {
        val normalizedH = (h % 360f + 360f) % 360f
        val clampedS = s.coerceIn(0f, 1f)
        val clampedL = l.coerceIn(0f, 1f)
        val c = (1f - abs(2f * clampedL - 1f)) * clampedS
        val x = c * (1f - abs((normalizedH / 60f) % 2f - 1f))
        val m = clampedL - c / 2f

        val (rPrime, gPrime, bPrime) = when ((normalizedH / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        return Color(
            red = (rPrime + m).coerceIn(0f, 1f),
            green = (gPrime + m).coerceIn(0f, 1f),
            blue = (bPrime + m).coerceIn(0f, 1f),
            alpha = alpha.coerceIn(0f, 1f)
        )
    }

    /**
     * Derives a full harmonic [ProtocolColors] structure from any arbitrary base [Color]:
     * - primary: chosen color
     * - glow: base color with 24% alpha (0x3D)
     * - secondary: harmonic hue shift (-15°) with 15% brightness reduction
     * - light: lightened tint (+20% luminance)
     * - orb1..orb4: gradient transitions for background energy spheres
     */
    fun generateProtocolColors(baseColor: Color): ProtocolColors {
        val hsl = rgbToHsl(baseColor.red, baseColor.green, baseColor.blue)
        val h = hsl[0]
        val s = hsl[1]
        val l = hsl[2]

        val primary = baseColor.copy(alpha = 1f)
        val glow = baseColor.copy(alpha = 0.24f) // 0x3D alpha
        val secondary = hslToColor(h - 15f, s, (l * 0.85f).coerceIn(0.10f, 0.95f))
        val light = hslToColor(h, (s * 0.95f).coerceIn(0f, 1f), (l + 0.20f).coerceIn(0f, 1f))

        val orb1 = primary
        val orb2 = hslToColor(h + 12f, s, (l * 1.05f).coerceIn(0f, 1f))
        val orb3 = hslToColor(h - 12f, s, (l * 0.90f).coerceIn(0f, 1f))
        val orb4 = secondary

        return ProtocolColors(
            primary = primary,
            glow = glow,
            secondary = secondary,
            light = light,
            orb1 = orb1,
            orb2 = orb2,
            orb3 = orb3,
            orb4 = orb4
        )
    }

    /**
     * Extracts Monet dynamic system accent on Android 12+ (API 31+).
     * Falls back to [MtprotoAccent] on older API levels.
     */
    fun getSystemMonetAccent(context: Context): Color {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val resId = android.R.color.system_accent1_500
                val resolvedColor = context.getColor(resId)
                Color(resolvedColor)
            } catch (_: Exception) {
                MtprotoAccent
            }
        } else {
            MtprotoAccent
        }
    }

    /**
     * Adjusts a [Color]'s brightness, saturation, and alpha channel.
     * - brightness: multiplier on lightness [0.2f..1.5f]
     * - saturation: multiplier on saturation [0.0f..1.5f] (0f = grayscale)
     * - alphaFactor: multiplier on alpha [0.2f..1.0f]
     */
    fun adjustColor(
        baseColor: Color,
        brightness: Float = 1.0f,
        saturation: Float = 1.0f,
        alphaFactor: Float = 1.0f
    ): Color {
        if (brightness == 1.0f && saturation == 1.0f && alphaFactor == 1.0f) return baseColor
        val hsl = rgbToHsl(baseColor.red, baseColor.green, baseColor.blue)
        val h = hsl[0]
        val s = (hsl[1] * saturation).coerceIn(0f, 1f)
        val l = (hsl[2] * brightness).coerceIn(0f, 1f)
        val a = (baseColor.alpha * alphaFactor).coerceIn(0f, 1f)
        return hslToColor(h, s, l, a)
    }
}

/**
 * Adjusts all palette colors using [ColorEngine.adjustColor].
 */
fun ProtocolColors.adjust(
    brightness: Float = 1.0f,
    saturation: Float = 1.0f,
    alphaFactor: Float = 1.0f
): ProtocolColors {
    if (brightness == 1.0f && saturation == 1.0f && alphaFactor == 1.0f) return this
    return ProtocolColors(
        primary = ColorEngine.adjustColor(primary, brightness, saturation, alphaFactor),
        glow = ColorEngine.adjustColor(glow, brightness, saturation, alphaFactor),
        secondary = ColorEngine.adjustColor(secondary, brightness, saturation, alphaFactor),
        light = ColorEngine.adjustColor(light, brightness, saturation, alphaFactor),
        orb1 = ColorEngine.adjustColor(orb1, brightness, saturation, alphaFactor),
        orb2 = ColorEngine.adjustColor(orb2, brightness, saturation, alphaFactor),
        orb3 = ColorEngine.adjustColor(orb3, brightness, saturation, alphaFactor),
        orb4 = ColorEngine.adjustColor(orb4, brightness, saturation, alphaFactor)
    )
}

/**
 * Calculates the effective [ProtocolColors] for the given [mode] and settings.
 */
fun calculateEffectiveProtocolColors(
    mode: ThemeAccentMode,
    presetId: String,
    customColorHex: String,
    context: Context,
    proxyMode: ProxyMode? = null,
    isSocks5: Boolean = false,
    isVpn: Boolean = false,
    vpnPalette: ProtocolColors? = null,
    brightness: Float = 1.0f,
    saturation: Float = 1.0f,
    alpha: Float = 1.0f
): ProtocolColors {
    val base = when (mode) {
        ThemeAccentMode.PROTOCOL_DEFAULT -> {
            val isTargetSocks5 = when {
                proxyMode != null -> proxyMode == ProxyMode.SOCKS5
                else -> isSocks5
            }
            when {
                isVpn && vpnPalette != null -> vpnPalette
                isVpn -> VpnElectricAmberPalette
                isTargetSocks5 -> Socks5Palette
                else -> MtprotoPalette
            }
        }
        ThemeAccentMode.STEALTH -> {
            StealthPalette
        }
        ThemeAccentMode.PRESET -> {
            ThemePresets.getPresetById(presetId)?.palette
                ?: ThemePresets.CYBER_RUBY.palette
        }
        ThemeAccentMode.MONET -> {
            val monetColor = ColorEngine.getSystemMonetAccent(context)
            ColorEngine.generateProtocolColors(monetColor)
        }
        ThemeAccentMode.CUSTOM -> {
            val parsed = ColorEngine.parseHexColor(customColorHex) ?: MtprotoAccent
            ColorEngine.generateProtocolColors(parsed)
        }
    }
    return base.adjust(brightness, saturation, alpha)
}
