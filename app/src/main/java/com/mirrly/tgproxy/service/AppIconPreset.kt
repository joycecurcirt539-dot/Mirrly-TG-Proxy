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

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.mirrly.tgproxy.R

/**
 * Набор встроенных иконок рабочего стола для переключения через `<activity-alias>`.
 */
enum class AppIconPreset(
    val id: String,
    val aliasClassName: String,
    val isManifestDefault: Boolean,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    @DrawableRes val mipmapRes: Int,
    @DrawableRes val bgDrawableRes: Int,
    @DrawableRes val fgDrawableRes: Int,
    @DrawableRes val monochromeMaskRes: Int,
    val fgInsetFraction: Float,
    val accentHint: Color
) {
    DEFAULT(
        id = "default",
        aliasClassName = "com.mirrly.tgproxy.LauncherIconDefault",
        isManifestDefault = true,
        titleRes = R.string.app_icon_default_title,
        subtitleRes = R.string.app_icon_default_sub,
        mipmapRes = R.mipmap.ic_launcher_default,
        bgDrawableRes = R.drawable.ic_launcher_bg_black,
        fgDrawableRes = R.drawable.ic_launcher_mirrly,
        monochromeMaskRes = R.drawable.ic_launcher_monochrome_mask,
        fgInsetFraction = 0.08f,
        accentHint = Color(0xFF00F5D4)
    ),
    LEGACY(
        id = "legacy",
        aliasClassName = "com.mirrly.tgproxy.LauncherIconLegacy",
        isManifestDefault = false,
        titleRes = R.string.app_icon_legacy_title,
        subtitleRes = R.string.app_icon_legacy_sub,
        mipmapRes = R.mipmap.ic_launcher_legacy,
        bgDrawableRes = R.drawable.ic_launcher_bg_black,
        fgDrawableRes = R.drawable.ic_launcher_legacy_fg,
        monochromeMaskRes = R.drawable.ic_launcher_monochrome_mask,
        fgInsetFraction = 0f,
        accentHint = Color(0xFF00E676)
    ),
    OUTLINE(
        id = "outline",
        aliasClassName = "com.mirrly.tgproxy.LauncherIconOutline",
        isManifestDefault = false,
        titleRes = R.string.app_icon_outline_title,
        subtitleRes = R.string.app_icon_outline_sub,
        mipmapRes = R.mipmap.ic_launcher_outline,
        bgDrawableRes = R.drawable.ic_launcher_bg_black,
        fgDrawableRes = R.drawable.ic_launcher_outline_fg,
        monochromeMaskRes = R.drawable.ic_launcher_monochrome_mask,
        fgInsetFraction = 0f,
        accentHint = Color(0xFF00F5D4)
    ),
    STEALTH(
        id = "stealth",
        aliasClassName = "com.mirrly.tgproxy.LauncherIconStealth",
        isManifestDefault = false,
        titleRes = R.string.app_icon_stealth_title,
        subtitleRes = R.string.app_icon_stealth_sub,
        mipmapRes = R.mipmap.ic_launcher_stealth,
        bgDrawableRes = R.drawable.ic_launcher_bg_stealth,
        fgDrawableRes = R.drawable.ic_launcher_stealth_fg,
        monochromeMaskRes = R.drawable.ic_launcher_monochrome_mask,
        fgInsetFraction = 0f,
        accentHint = Color(0xFFFFFFFF)
    ),
    CYBER_RUBY(
        id = "cyber_ruby",
        aliasClassName = "com.mirrly.tgproxy.LauncherIconRuby",
        isManifestDefault = false,
        titleRes = R.string.app_icon_ruby_title,
        subtitleRes = R.string.app_icon_ruby_sub,
        mipmapRes = R.mipmap.ic_launcher_ruby,
        bgDrawableRes = R.drawable.ic_launcher_bg_ruby,
        fgDrawableRes = R.drawable.ic_launcher_ruby_fg,
        monochromeMaskRes = R.drawable.ic_launcher_monochrome_mask,
        fgInsetFraction = 0f,
        accentHint = Color(0xFFFF2A6D)
    ),
    COBALT_BLUE(
        id = "cobalt_blue",
        aliasClassName = "com.mirrly.tgproxy.LauncherIconCobalt",
        isManifestDefault = false,
        titleRes = R.string.app_icon_cobalt_title,
        subtitleRes = R.string.app_icon_cobalt_sub,
        mipmapRes = R.mipmap.ic_launcher_cobalt,
        bgDrawableRes = R.drawable.ic_launcher_bg_cobalt,
        fgDrawableRes = R.drawable.ic_launcher_cobalt_fg,
        monochromeMaskRes = R.drawable.ic_launcher_monochrome_mask,
        fgInsetFraction = 0f,
        accentHint = Color(0xFF2979FF)
    ),
    ELECTRIC_AMBER(
        id = "electric_amber",
        aliasClassName = "com.mirrly.tgproxy.LauncherIconAmber",
        isManifestDefault = false,
        titleRes = R.string.app_icon_amber_title,
        subtitleRes = R.string.app_icon_amber_sub,
        mipmapRes = R.mipmap.ic_launcher_amber,
        bgDrawableRes = R.drawable.ic_launcher_bg_amber,
        fgDrawableRes = R.drawable.ic_launcher_amber_fg,
        monochromeMaskRes = R.drawable.ic_launcher_monochrome_mask,
        fgInsetFraction = 0f,
        accentHint = Color(0xFFFF7B1C)
    ),
    VIOLET_SURGE(
        id = "violet_surge",
        aliasClassName = "com.mirrly.tgproxy.LauncherIconViolet",
        isManifestDefault = false,
        titleRes = R.string.app_icon_violet_title,
        subtitleRes = R.string.app_icon_violet_sub,
        mipmapRes = R.mipmap.ic_launcher_violet,
        bgDrawableRes = R.drawable.ic_launcher_bg_violet,
        fgDrawableRes = R.drawable.ic_launcher_violet_fg,
        monochromeMaskRes = R.drawable.ic_launcher_monochrome_mask,
        fgInsetFraction = 0f,
        accentHint = Color(0xFFB388FF)
    );

    companion object {
        fun fromStorageKey(key: String?): AppIconPreset {
            if (key.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.id.equals(key, ignoreCase = true) } ?: DEFAULT
        }
    }
}
