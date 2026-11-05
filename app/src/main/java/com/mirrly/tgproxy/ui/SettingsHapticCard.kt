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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.HapticProfile
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.LocalCardShape
import com.mirrly.tgproxy.ui.theme.MtprotoAccent
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.springPress

/**
 * Карточка тонкой настройки профилей тактильного виброотклика (Haptic Profiles).
 */
@Composable
fun SettingsHapticCard(
    accentColor: Color = MtprotoAccent,
    onInfoClick: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs = MirrlyApplication.instance.prefsManager
    val activeProfile by prefs.hapticProfileFlow.collectAsState()
    val cardShape = LocalCardShape.current

    var isExpanded by rememberSaveable { mutableStateOf(false) }

    val profiles = remember {
        listOf(
            HapticProfile.MECHANICAL_CLICK to R.string.settings_customization_haptic_mechanical,
            HapticProfile.SUBBASS_PULSE to R.string.settings_customization_haptic_subbass,
            HapticProfile.CYBERNETIC_DOUBLE_TICK to R.string.settings_customization_haptic_double_tick,
            HapticProfile.STEALTH to R.string.settings_customization_haptic_stealth
        )
    }

    val activeLabelRes = remember(activeProfile) {
        profiles.firstOrNull { it.first == activeProfile }?.second
            ?: R.string.settings_customization_haptic_mechanical
    }

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
                        painter = painterResource(id = R.drawable.ic_fingerprint_badge),
                        contentDescription = null,
                        tint = if (activeProfile != HapticProfile.STEALTH) accentColor else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_customization_haptic_title),
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
                                    HapticHelper.performTapClick(context)
                                    onInfoClick("haptic_profiles_info")
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

                    if (!isExpanded) {
                        Text(
                            text = stringResource(activeLabelRes),
                            fontSize = 11.5.sp,
                            color = if (activeProfile != HapticProfile.STEALTH) accentColor else TextMuted,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

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
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_customization_haptic_desc),
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 17.sp
                )

                // Сетка вариантов 2x2
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.chunked(2).forEach { rowProfiles ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowProfiles.forEach { (profile, labelRes) ->
                                val isSelected = activeProfile == profile
                                val borderColor by animateColorAsState(
                                    targetValue = if (isSelected) accentColor else AmoledBorder,
                                    label = "haptic_profile_border"
                                )
                                val textColor by animateColorAsState(
                                    targetValue = if (isSelected) TextWhite else TextMuted,
                                    label = "haptic_profile_text"
                                )

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) accentColor.copy(alpha = 0.12f) else Color.Transparent,
                                    border = BorderStroke(1.dp, borderColor),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .springPress(onClick = {
                                            prefs.setHapticProfile(profile)
                                            HapticHelper.performTapClick(context, overrideProfile = profile)
                                        })
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp, horizontal = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(labelRes),
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = textColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Кнопка быстрого тестирования выбранного виброотклика
                if (activeProfile != HapticProfile.STEALTH) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .springPress(onClick = {
                                HapticHelper.performTapClick(context, overrideProfile = activeProfile)
                            })
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fingerprint_badge),
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Box(modifier = Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.settings_customization_haptic_test),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                        }
                    }
                }
            }
        }
    }
}
