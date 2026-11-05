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

package com.mirrly.tgproxy.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.ColorEngine
import com.mirrly.tgproxy.ui.theme.MtprotoAccent
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.ThemeAccentMode
import com.mirrly.tgproxy.ui.theme.ThemePresets
import com.mirrly.tgproxy.ui.theme.springPress

/**
 * Карточка кастомизации цветового профиля в MirrlyUI.
 * Поддерживает:
 * - Выбор режима: По протоколу, Stealth, Пресеты, Material You, Свой цвет.
 * - Интерактивную палитру пресетов (Cyber Ruby, Acid Lime, Electric Amber, Cobalt Blue, Matrix Green).
 * - Спектральный слайдер оттенка HUE и прямой ввод HEX-кода.
 */
@Composable
fun SettingsThemeAccentCard(
    onInfoClick: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val prefs = MirrlyApplication.instance.prefsManager

    val currentMode by prefs.themeAccentModeFlow.collectAsState()
    val currentPresetId by prefs.themePresetIdFlow.collectAsState()
    val customHex by prefs.themeCustomColorHexFlow.collectAsState()
    val brightness by prefs.themeBrightnessFlow.collectAsState()
    val saturation by prefs.themeSaturationFlow.collectAsState()
    val alpha by prefs.themeAlphaFlow.collectAsState()

    var isExpanded by rememberSaveable { mutableStateOf(false) }

    val baseActiveColor = remember(currentMode, currentPresetId, customHex) {
        when (currentMode) {
            ThemeAccentMode.PROTOCOL_DEFAULT -> MtprotoAccent
            ThemeAccentMode.STEALTH -> Color.White
            ThemeAccentMode.PRESET -> ThemePresets.getPresetById(currentPresetId)?.primaryColor ?: Color(0xFFFF2A6D)
            ThemeAccentMode.MONET -> ColorEngine.getSystemMonetAccent(context)
            ThemeAccentMode.CUSTOM -> ColorEngine.parseHexColor(customHex) ?: MtprotoAccent
        }
    }

    val activeColor = remember(baseActiveColor, brightness, saturation, alpha) {
        ColorEngine.adjustColor(baseActiveColor, brightness, saturation, alpha)
    }

    val modes = remember {
        listOf(
            ThemeAccentMode.PROTOCOL_DEFAULT to R.string.settings_theme_mode_protocol,
            ThemeAccentMode.STEALTH to R.string.settings_theme_mode_stealth,
            ThemeAccentMode.PRESET to R.string.settings_theme_mode_preset,
            ThemeAccentMode.MONET to R.string.settings_theme_mode_monet,
            ThemeAccentMode.CUSTOM to R.string.settings_theme_mode_custom
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Заголовок карточки
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(activeColor)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_theme_accent_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .springPress()
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onInfoClick("theme_accent_info")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info_circle),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Переключатель разворачивания
            SettingsCollapseButton(
                isExpanded = isExpanded,
                onToggle = { isExpanded = !isExpanded },
                accentColor = activeColor
            )
        }

        // Развернутая секция настроек
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_theme_accent_desc),
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 17.sp
                )

                // Горизонтальный ряд режимов темы
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    modes.forEach { (mode, labelRes) ->
                        val isSelected = currentMode == mode
                        val borderColor by animateColorAsState(
                            targetValue = if (isSelected) activeColor else AmoledBorder,
                            label = "theme_mode_border"
                        )
                        val textColor by animateColorAsState(
                            targetValue = if (isSelected) TextWhite else TextMuted,
                            label = "theme_mode_text"
                        )

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) activeColor.copy(alpha = 0.12f) else Color.Transparent,
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    prefs.setThemeAccentMode(mode)
                                })
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = textColor,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                // Пояснение выбранного режима
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = when (currentMode) {
                                ThemeAccentMode.PROTOCOL_DEFAULT -> stringResource(R.string.settings_theme_desc_protocol)
                                ThemeAccentMode.STEALTH -> stringResource(R.string.settings_theme_desc_stealth)
                                ThemeAccentMode.PRESET -> stringResource(R.string.settings_theme_desc_preset)
                                ThemeAccentMode.MONET -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        stringResource(R.string.settings_theme_desc_monet)
                                    } else {
                                        stringResource(R.string.settings_theme_desc_monet_unsupported)
                                    }
                                }
                                ThemeAccentMode.CUSTOM -> stringResource(R.string.settings_theme_desc_custom)
                            },
                            fontSize = 11.5.sp,
                            color = TextMuted,
                            lineHeight = 15.sp
                        )
                    }
                }

                // Секция пресетов (когда выбран режим PRESET)
                if (currentMode == ThemeAccentMode.PRESET) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_theme_status_preset),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ThemePresets.all.forEach { preset ->
                                val isSelected = currentPresetId.equals(preset.id, ignoreCase = true)
                                val borderColor by animateColorAsState(
                                    targetValue = if (isSelected) preset.primaryColor else AmoledBorder,
                                    label = "preset_border"
                                )

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) preset.primaryColor.copy(alpha = 0.12f) else Color.Transparent,
                                    border = BorderStroke(1.dp, borderColor),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            prefs.setThemePresetId(preset.id)
                                        })
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(preset.primaryColor)
                                        )
                                        Text(
                                            text = stringResource(preset.nameResId),
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) TextWhite else TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Секция кастомного цвета (когда выбран режим CUSTOM)
                if (currentMode == ThemeAccentMode.CUSTOM) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Быстрые оттенки
                        Text(
                            text = stringResource(R.string.settings_theme_custom_palette_quick),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )

                        val quickColors = remember {
                            listOf(
                                "#00F5D4", // Neon Cyan
                                "#00BBF9", // Bright Sky
                                "#2979FF", // Electric Blue
                                "#9D4EDD", // Cyber Violet
                                "#FF007F", // Neon Pink
                                "#FF2A6D", // Crimson Ruby
                                "#FF6B2B", // Cyber Amber
                                "#00E676"  // Matrix Green
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            quickColors.forEach { hex ->
                                val color = ColorEngine.parseHexColor(hex) ?: Color.Cyan
                                val isSelected = customHex.equals(hex, ignoreCase = true)

                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(color.copy(alpha = if (isSelected) 0.35f else 0.15f))
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) color else AmoledBorder,
                                            shape = CircleShape
                                        )
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            prefs.setThemeCustomColorHex(hex)
                                        }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                    )
                                }
                            }
                        }

                        // Спектр оттенка (Hue slider)
                        var sliderHue by rememberSaveable(customHex) {
                            val parsed = ColorEngine.parseHexColor(customHex) ?: MtprotoAccent
                            val hsl = ColorEngine.rgbToHsl(parsed.red, parsed.green, parsed.blue)
                            mutableFloatStateOf(hsl[0])
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_theme_custom_hue_slider),
                                    fontSize = 11.5.sp,
                                    color = TextWhite
                                )
                                Text(
                                    text = "${sliderHue.toInt()}°",
                                    fontSize = 11.5.sp,
                                    color = activeColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Радужный градиент для слайдера
                            val hueGradient = remember {
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Red,
                                        Color.Yellow,
                                        Color.Green,
                                        Color.Cyan,
                                        Color.Blue,
                                        Color.Magenta,
                                        Color.Red
                                    )
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(hueGradient)
                            )

                            Slider(
                                value = sliderHue,
                                onValueChange = { newHue ->
                                    sliderHue = newHue
                                    val generated = ColorEngine.hslToColor(newHue, 0.90f, 0.52f)
                                    val hexString = String.format("#%02X%02X%02X",
                                        (generated.red * 255f).toInt(),
                                        (generated.green * 255f).toInt(),
                                        (generated.blue * 255f).toInt()
                                    )
                                    prefs.setThemeCustomColorHex(hexString)
                                },
                                onValueChangeFinished = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                valueRange = 0f..360f,
                                colors = SliderDefaults.colors(
                                    thumbColor = activeColor,
                                    activeTrackColor = Color.Transparent,
                                    inactiveTrackColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Поле ввода HEX
                        var inputHexText by remember(customHex) { mutableStateOf(customHex.removePrefix("#")) }
                        var isInputError by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .border(
                                    1.dp,
                                    if (isInputError) Color(0xFFFF4D4D) else AmoledBorder,
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "#",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )

                            BasicTextField(
                                value = inputHexText,
                                onValueChange = { raw ->
                                    val filtered = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(8)
                                    inputHexText = filtered
                                    val parsed = ColorEngine.parseHexColor(filtered)
                                    if (parsed != null && (filtered.length == 6 || filtered.length == 8)) {
                                        isInputError = false
                                        prefs.setThemeCustomColorHex("#$filtered")
                                    } else if (filtered.length >= 6) {
                                        isInputError = true
                                    } else {
                                        isInputError = false
                                    }
                                },
                                textStyle = TextStyle(
                                    color = TextWhite,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        focusManager.clearFocus()
                                    }
                                ),
                                cursorBrush = SolidColor(activeColor),
                                modifier = Modifier.weight(1f)
                            )

                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(activeColor)
                                    .border(1.dp, AmoledBorder, CircleShape)
                            )
                        }
                    }
                }

                // Секция тонкой настройки: Яркость, Насыщенность, Прозрачность
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_theme_adjustments_title),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                        if (brightness != 1.0f || saturation != 1.0f || alpha != 1.0f) {
                            Text(
                                text = stringResource(R.string.settings_theme_adjustments_reset),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = activeColor,
                                modifier = Modifier
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        prefs.resetThemeAdjustments()
                                    })
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // 1. Яркость (Brightness) 30%..150%
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.settings_theme_brightness),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_theme_brightness_desc),
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                                Text(
                                    text = "${(brightness * 100).toInt()}%",
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = activeColor
                                )
                            }
                            Slider(
                                value = brightness,
                                onValueChange = { prefs.setThemeBrightness(it) },
                                onValueChangeFinished = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                valueRange = 0.3f..1.5f,
                                colors = SliderDefaults.colors(
                                    thumbColor = activeColor,
                                    activeTrackColor = activeColor,
                                    inactiveTrackColor = AmoledBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // 2. Насыщенность (Saturation) 0%..150%
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.settings_theme_saturation),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_theme_saturation_desc),
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                                Text(
                                    text = "${(saturation * 100).toInt()}%",
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = activeColor
                                )
                            }
                            Slider(
                                value = saturation,
                                onValueChange = { prefs.setThemeSaturation(it) },
                                onValueChangeFinished = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                valueRange = 0.0f..1.5f,
                                colors = SliderDefaults.colors(
                                    thumbColor = activeColor,
                                    activeTrackColor = activeColor,
                                    inactiveTrackColor = AmoledBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // 3. Прозрачность (Alpha / Opacity) 20%..100%
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.settings_theme_alpha),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_theme_alpha_desc),
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                                Text(
                                    text = "${(alpha * 100).toInt()}%",
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = activeColor
                                )
                            }
                            Slider(
                                value = alpha,
                                onValueChange = { prefs.setThemeAlpha(it) },
                                onValueChangeFinished = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                valueRange = 0.2f..1.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = activeColor,
                                    activeTrackColor = activeColor,
                                    inactiveTrackColor = AmoledBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
