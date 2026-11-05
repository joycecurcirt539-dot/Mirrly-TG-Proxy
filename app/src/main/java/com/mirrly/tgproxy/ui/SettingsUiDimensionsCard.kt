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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.CardCornerStyle
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.MtprotoAccent
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.springPress
import com.mirrly.tgproxy.ui.theme.springPress
import java.util.Locale

/**
 * Карточка тонкой настройки масштабирования интерфейса и геометрических пропорций.
 */
@Composable
fun SettingsUiDimensionsCard(
    accentColor: Color = MtprotoAccent,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val prefs = MirrlyApplication.instance.prefsManager

    val uiScale by prefs.uiScaleFlow.collectAsState()
    val textScale by prefs.textScaleFlow.collectAsState()
    val orbitalScale by prefs.orbitalSpheresScaleFlow.collectAsState()
    val homeRingsEnabled by prefs.homeRingsEnabledFlow.collectAsState()
    val liquidGlassEnabled by prefs.liquidGlassEnabledFlow.collectAsState()
    val cardCornerStyle by prefs.cardCornerStyleFlow.collectAsState()
    val cardCornerRadiusDp by prefs.cardCornerRadiusDpFlow.collectAsState()

    var isExpanded by rememberSaveable { mutableStateOf(false) }

    var sliderUiScale by remember(uiScale) { mutableFloatStateOf(uiScale) }
    var sliderTextScale by remember(textScale) { mutableFloatStateOf(textScale) }
    var sliderOrbitalScale by remember(orbitalScale) { mutableFloatStateOf(orbitalScale) }
    var sliderCornerRadius by remember(cardCornerRadiusDp) { mutableFloatStateOf(cardCornerRadiusDp) }

    val isDefaultProportions = remember(uiScale, textScale, orbitalScale, cardCornerStyle, cardCornerRadiusDp) {
        uiScale == PreferencesManager.UI_SCALE_DEFAULT &&
                textScale == PreferencesManager.TEXT_SCALE_DEFAULT &&
                orbitalScale == PreferencesManager.ORBITAL_SPHERES_SCALE_DEFAULT &&
                cardCornerStyle == CardCornerStyle.ROUNDED &&
                cardCornerRadiusDp == PreferencesManager.CARD_CORNER_RADIUS_DP_DEFAULT
    }

    val cardShape = com.mirrly.tgproxy.ui.theme.LocalCardShape.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, cardShape)
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
                    Icon(
                        painter = painterResource(id = R.drawable.ic_expand),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_customization_dimensions_title),
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
                                onInfoClick("dimensions_info")
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
                accentColor = accentColor
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
                    text = stringResource(R.string.settings_customization_dimensions_desc),
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 17.sp
                )

                // Слайдер: Масштаб интерфейса
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.settings_customization_ui_scale),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Text(
                                text = stringResource(R.string.settings_customization_ui_scale_desc),
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "%.2fx", sliderUiScale),
                            fontSize = 11.5.sp,
                            color = accentColor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = sliderUiScale,
                        onValueChange = {
                            sliderUiScale = it
                            prefs.setUiScale(it, persist = false)
                        },
                        onValueChangeFinished = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            prefs.setUiScale(sliderUiScale, persist = true)
                        },
                        valueRange = PreferencesManager.UI_SCALE_MIN..PreferencesManager.UI_SCALE_MAX,
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor,
                            inactiveTrackColor = AmoledBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Слайдер: Масштаб текста
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.settings_customization_text_scale),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Text(
                                text = stringResource(R.string.settings_customization_text_scale_desc),
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "%.2fx", sliderTextScale),
                            fontSize = 11.5.sp,
                            color = accentColor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = sliderTextScale,
                        onValueChange = {
                            sliderTextScale = it
                            prefs.setTextScale(it, persist = false)
                        },
                        onValueChangeFinished = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            prefs.setTextScale(sliderTextScale, persist = true)
                        },
                        valueRange = PreferencesManager.TEXT_SCALE_MIN..PreferencesManager.TEXT_SCALE_MAX,
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor,
                            inactiveTrackColor = AmoledBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Слайдер: Размер орбитальных сфер
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.settings_customization_orbital_spheres_scale),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Text(
                                text = stringResource(R.string.settings_customization_orbital_spheres_scale_desc),
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "%.2fx", sliderOrbitalScale),
                            fontSize = 11.5.sp,
                            color = accentColor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = sliderOrbitalScale,
                        onValueChange = {
                            sliderOrbitalScale = it
                            prefs.setOrbitalSpheresScale(it, persist = false)
                        },
                        onValueChangeFinished = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            prefs.setOrbitalSpheresScale(sliderOrbitalScale, persist = true)
                        },
                        valueRange = PreferencesManager.ORBITAL_SPHERES_SCALE_MIN..PreferencesManager.ORBITAL_SPHERES_SCALE_MAX,
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor,
                            inactiveTrackColor = AmoledBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Переключатель орбитальных колец главного экрана
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = stringResource(R.string.settings_customization_home_rings),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(R.string.settings_customization_home_rings_desc),
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 14.sp
                        )
                    }

                    InertialSpringSwitch(
                        checked = homeRingsEnabled,
                        onCheckedChange = { checked ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            prefs.setHomeRingsEnabled(checked)
                        },
                        activeColor = accentColor
                    )
                }

                // Переключатель эффектов жидкого стекла (Apple Liquid Glass)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_customization_liquid_glass_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(R.string.settings_customization_liquid_glass_desc),
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 14.sp
                        )
                    }

                    InertialSpringSwitch(
                        checked = liquidGlassEnabled,
                        onCheckedChange = { checked ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            prefs.setLiquidGlassEnabled(checked)
                        },
                        activeColor = accentColor
                    )
                }

                // ── Форма карточек (Corner Geometry & Cyber Chisel) ──
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(
                            text = stringResource(R.string.settings_customization_corner_geometry_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(R.string.settings_customization_corner_geometry_desc),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }

                    // 3 варианта формы
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val cornerOptions = listOf(
                            CardCornerStyle.ROUNDED to R.string.settings_customization_corner_style_rounded,
                            CardCornerStyle.SHARP to R.string.settings_customization_corner_style_sharp,
                            CardCornerStyle.CYBER_CHISEL to R.string.settings_customization_corner_style_chisel
                        )

                        cornerOptions.forEach { (style, labelRes) ->
                            val isSelected = cardCornerStyle == style
                            val borderColor by animateColorAsState(
                                targetValue = if (isSelected) accentColor else AmoledBorder,
                                label = "corner_style_border"
                            )
                            val textColor by animateColorAsState(
                                targetValue = if (isSelected) TextWhite else TextMuted,
                                label = "corner_style_text"
                            )

                            val previewShape = remember(style) {
                                when (style) {
                                    CardCornerStyle.ROUNDED -> RoundedCornerShape(10.dp)
                                    CardCornerStyle.SHARP -> RoundedCornerShape(2.dp)
                                    CardCornerStyle.CYBER_CHISEL -> CutCornerShape(8.dp)
                                }
                            }

                            Surface(
                                shape = previewShape,
                                color = if (isSelected) accentColor.copy(alpha = 0.12f) else Color.Transparent,
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(previewShape)
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        prefs.setCardCornerStyle(style)
                                    })
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(labelRes),
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = textColor
                                    )
                                }
                            }
                        }
                    }

                    // Слайдер радиуса скругления (только для ROUNDED)
                    AnimatedVisibility(
                        visible = cardCornerStyle == CardCornerStyle.ROUNDED,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_customization_corner_radius),
                                    fontSize = 11.5.sp,
                                    color = TextWhite
                                )
                                Text(
                                    text = String.format(Locale.US, "%.0fdp", sliderCornerRadius),
                                    fontSize = 11.5.sp,
                                    color = accentColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Slider(
                                value = sliderCornerRadius,
                                onValueChange = {
                                    sliderCornerRadius = it
                                    prefs.setCardCornerRadiusDp(it, persist = false)
                                },
                                onValueChangeFinished = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    prefs.setCardCornerRadiusDp(sliderCornerRadius, persist = true)
                                },
                                valueRange = PreferencesManager.CARD_CORNER_RADIUS_DP_MIN..PreferencesManager.CARD_CORNER_RADIUS_DP_MAX,
                                colors = SliderDefaults.colors(
                                    thumbColor = accentColor,
                                    activeTrackColor = accentColor,
                                    inactiveTrackColor = AmoledBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Кнопка сброса пропорций к эталону
                if (!isDefaultProportions) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                prefs.resetAllUiDimensions()
                                prefs.setCardCornerStyle(CardCornerStyle.ROUNDED)
                                prefs.setCardCornerRadiusDp(PreferencesManager.CARD_CORNER_RADIUS_DP_DEFAULT)
                                sliderCornerRadius = PreferencesManager.CARD_CORNER_RADIUS_DP_DEFAULT
                            })
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.settings_customization_dimensions_reset),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFFF4D4D)
                                )
                                Text(
                                    text = stringResource(R.string.settings_customization_dimensions_reset_desc),
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = null,
                                tint = Color(0xFFFF4D4D),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
