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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.mirrly.tgproxy.service.ParticleStyle
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.MtprotoAccent
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.springPress
import java.util.Locale

/**
 * Карточка тонкой настройки динамики частиц и аппаратного гиро-параллакса.
 */
@Composable
fun SettingsParticleDynamicsCard(
    accentColor: Color = MtprotoAccent,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val prefs = MirrlyApplication.instance.prefsManager

    val particlesEnabled by prefs.customBackgroundParticlesFlow.collectAsState()
    val activeStyleName by prefs.wallpaperParticlesStyleFlow.collectAsState()
    val activeStyle = remember(activeStyleName) { ParticleStyle.fromString(activeStyleName) }

    val particleCount by prefs.wallpaperParticlesCountFlow.collectAsState()
    val particleSpeed by prefs.wallpaperParticlesSpeedFlow.collectAsState()
    val particleSize by prefs.wallpaperParticlesSizeFlow.collectAsState()
    val parallaxEnabled by prefs.wallpaperParallaxEnabledFlow.collectAsState()
    val parallaxDepth by prefs.wallpaperParallaxDepthFlow.collectAsState()

    var isExpanded by rememberSaveable { mutableStateOf(false) }

    var sliderCount by remember(particleCount) { mutableIntStateOf(particleCount) }
    var sliderSpeed by remember(particleSpeed) { mutableFloatStateOf(particleSpeed) }
    var sliderSize by remember(particleSize) { mutableFloatStateOf(particleSize) }
    var sliderDepth by remember(parallaxDepth) { mutableFloatStateOf(parallaxDepth) }

    val styles = remember {
        listOf(
            ParticleStyle.BOKEH_DUST to R.string.settings_customization_wallpaper_particles_style_bokeh,
            ParticleStyle.BITSTREAM_MATRIX to R.string.settings_customization_wallpaper_particles_style_matrix,
            ParticleStyle.STARFIELD_WARP to R.string.settings_customization_wallpaper_particles_style_warp
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
                    Icon(
                        painter = painterResource(id = R.drawable.ic_star),
                        contentDescription = null,
                        tint = if (particlesEnabled) accentColor else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_customization_wallpaper_particles_style),
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
                                onInfoClick("particle_dynamics_info")
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
                    text = stringResource(R.string.settings_customization_wallpaper_particles_desc),
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 17.sp
                )

                // Главный переключатель слоя частиц
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
                            text = stringResource(R.string.settings_customization_wallpaper_particles),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(R.string.settings_customization_wallpaper_particles_desc),
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 14.sp
                        )
                    }

                    InertialSpringSwitch(
                        checked = particlesEnabled,
                        onCheckedChange = { checked ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            prefs.setWallpaperParticlesEnabled(checked)
                        },
                        activeColor = accentColor
                    )
                }

                if (particlesEnabled) {
                    // Выбор кинетического стиля частиц
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_customization_wallpaper_particles_style),
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
                            styles.forEach { (style, labelRes) ->
                                val isSelected = activeStyle == style
                                val borderColor by animateColorAsState(
                                    targetValue = if (isSelected) accentColor else AmoledBorder,
                                    label = "particle_style_border"
                                )
                                val textColor by animateColorAsState(
                                    targetValue = if (isSelected) TextWhite else TextMuted,
                                    label = "particle_style_text"
                                )

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) accentColor.copy(alpha = 0.12f) else Color.Transparent,
                                    border = BorderStroke(1.dp, borderColor),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            prefs.setWallpaperParticlesStyle(style)
                                        })
                                ) {
                                    Text(
                                        text = stringResource(labelRes),
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = textColor,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Слайдер: Размер частиц
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_customization_particles_size),
                                fontSize = 11.5.sp,
                                color = TextWhite
                            )
                            Text(
                                text = String.format(Locale.US, "%.2fx", sliderSize),
                                fontSize = 11.5.sp,
                                color = accentColor,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Slider(
                            value = sliderSize,
                            onValueChange = {
                                sliderSize = it
                                prefs.setWallpaperParticlesSize(it, persist = false)
                            },
                            onValueChangeFinished = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                prefs.setWallpaperParticlesSize(sliderSize, persist = true)
                            },
                            valueRange = PreferencesManager.WALLPAPER_PARTICLES_SIZE_MIN..PreferencesManager.WALLPAPER_PARTICLES_SIZE_MAX,
                            colors = SliderDefaults.colors(
                                thumbColor = accentColor,
                                activeTrackColor = accentColor,
                                inactiveTrackColor = AmoledBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Слайдер: Скорость частиц
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_customization_wallpaper_particles_speed),
                                fontSize = 11.5.sp,
                                color = TextWhite
                            )
                            Text(
                                text = String.format(Locale.US, "%.2fx", sliderSpeed),
                                fontSize = 11.5.sp,
                                color = accentColor,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Slider(
                            value = sliderSpeed,
                            onValueChange = {
                                sliderSpeed = it
                                prefs.setWallpaperParticlesSpeed(it, persist = false)
                            },
                            onValueChangeFinished = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                prefs.setWallpaperParticlesSpeed(sliderSpeed, persist = true)
                            },
                            valueRange = PreferencesManager.WALLPAPER_PARTICLES_SPEED_MIN..PreferencesManager.WALLPAPER_PARTICLES_SPEED_MAX,
                            colors = SliderDefaults.colors(
                                thumbColor = accentColor,
                                activeTrackColor = accentColor,
                                inactiveTrackColor = AmoledBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Слайдер: Плотность / Количество
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_customization_wallpaper_particles_count),
                                fontSize = 11.5.sp,
                                color = TextWhite
                            )
                            Text(
                                text = "$sliderCount",
                                fontSize = 11.5.sp,
                                color = accentColor,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Slider(
                            value = sliderCount.toFloat(),
                            onValueChange = {
                                sliderCount = it.toInt()
                                prefs.setWallpaperParticlesCount(it.toInt(), persist = false)
                            },
                            onValueChangeFinished = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                prefs.setWallpaperParticlesCount(sliderCount, persist = true)
                            },
                            valueRange = PreferencesManager.WALLPAPER_PARTICLES_COUNT_MIN.toFloat()..PreferencesManager.WALLPAPER_PARTICLES_COUNT_MAX.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = accentColor,
                                activeTrackColor = accentColor,
                                inactiveTrackColor = AmoledBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Слайдер: Глубина гироскопа (Parallax Depth)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_parallax_depth),
                                    fontSize = 11.5.sp,
                                    color = TextWhite
                                )
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_parallax_depth_desc),
                                    fontSize = 10.5.sp,
                                    color = TextMuted,
                                    lineHeight = 13.sp
                                )
                            }
                            Text(
                                text = String.format(Locale.US, "%.1fx", sliderDepth),
                                fontSize = 11.5.sp,
                                color = accentColor,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Slider(
                            value = sliderDepth,
                            onValueChange = {
                                sliderDepth = it
                                if (!parallaxEnabled) prefs.setWallpaperParallaxEnabled(true)
                                prefs.setWallpaperParallaxDepth(it, persist = false)
                            },
                            onValueChangeFinished = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (!parallaxEnabled) prefs.setWallpaperParallaxEnabled(true)
                                prefs.setWallpaperParallaxDepth(sliderDepth, persist = true)
                            },
                            valueRange = PreferencesManager.WALLPAPER_PARALLAX_DEPTH_MIN..PreferencesManager.WALLPAPER_PARALLAX_DEPTH_MAX,
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
        }
    }
}
