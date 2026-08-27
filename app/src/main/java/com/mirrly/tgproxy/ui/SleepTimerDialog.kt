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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.SleepTimerManager
import com.mirrly.tgproxy.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Frosted Glass Sleep Timer Modal Dialog (Add Worker style, top-left back button, no emojis).
 */
@Composable
fun SleepTimerDialog(
    activeAccentColor: Color = ActiveGreenLed,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val timerState by SleepTimerManager.timerState.collectAsState()

    var customMinutes by remember { mutableFloatStateOf(45f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        DialogBackdropBox(
            onDismiss = onDismiss
        ) {
            // Scrollable Content
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .adaptiveContainerWidth(440.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                    )
                    .padding(horizontal = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                // Category Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = (if (timerState.isActive) ActiveGreenLed else activeAccentColor).copy(alpha = 0.12f),
                    border = BorderStroke(
                        1.dp,
                        (if (timerState.isActive) ActiveGreenLed else activeAccentColor).copy(alpha = 0.35f)
                    )
                ) {
                    Text(
                        text = if (timerState.isActive) "ТАЙМЕР АКТИВЕН" else "ТАЙМЕР СНА И АВТООТКЛЮЧЕНИЯ",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (timerState.isActive) ActiveGreenLed else activeAccentColor,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                // Main Title
                Text(
                    text = if (timerState.isActive) "Таймер автоотключения" else "Настройка таймера сна",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Transparent Glass Container for Sleep Timer Parameters / Status
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = if (timerState.isActive) "ТЕКУЩИЙ СТАТУС:" else "ПАРАМЕТРЫ АВТООТКЛЮЧЕНИЯ:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (timerState.isActive) ActiveGreenLed else activeAccentColor,
                            letterSpacing = 0.5.sp
                        )

                        val targetTimeStr = remember(timerState.targetTimeMs) {
                            if (timerState.targetTimeMs > 0) {
                                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timerState.targetTimeMs))
                            } else ""
                        }

                        Text(
                            text = if (timerState.isActive) {
                                "Прокси-сервер автоматически остановится в $targetTimeStr. Соединение и фоновые службы будут безопасно отключены."
                            } else {
                                "Выберите время, через которое прокси-сервер автоматически отключится для экономии заряда батареи и мобильного трафика."
                            },
                            fontSize = 12.5.sp,
                            color = TextWhite.copy(alpha = 0.8f),
                            lineHeight = 17.sp
                        )

                        if (timerState.isActive) {
                            // Large Countdown display inside styled container
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF0F172A).copy(alpha = 0.6f),
                                border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "ОСТАЛОСЬ ДО ОТКЛЮЧЕНИЯ",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.4.sp,
                                        color = TextWhite.copy(alpha = 0.65f)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = timerState.formatRemainingTime(),
                                        fontSize = 34.sp,
                                        fontWeight = FontWeight.Black,
                                        color = ActiveGreenLed,
                                        letterSpacing = 1.5.sp
                                    )
                                }
                            }

                            // Quick extend chips
                            Text(
                                text = "БЫСТРОЕ ПРОДЛЕНИЕ:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite.copy(alpha = 0.70f),
                                letterSpacing = 0.6.sp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                QuickExtendChip("+15 мин", activeAccentColor, Modifier.weight(1f)) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    SleepTimerManager.extendTimer(context, 15)
                                }
                                QuickExtendChip("+30 мин", activeAccentColor, Modifier.weight(1f)) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    SleepTimerManager.extendTimer(context, 30)
                                }
                                QuickExtendChip("+1 час", activeAccentColor, Modifier.weight(1f)) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    SleepTimerManager.extendTimer(context, 60)
                                }
                            }
                        } else {
                            // Presets Selection
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(15 to "15 мин", 30 to "30 мин", 45 to "45 мин").forEach { (min, label) ->
                                        PresetChip(
                                            title = label,
                                            isSelected = customMinutes.toInt() == min,
                                            activeColor = activeAccentColor,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            customMinutes = min.toFloat()
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(60 to "1 час", 120 to "2 часа", 240 to "4 часа").forEach { (min, label) ->
                                        PresetChip(
                                            title = label,
                                            isSelected = customMinutes.toInt() == min,
                                            activeColor = activeAccentColor,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            customMinutes = min.toFloat()
                                        }
                                    }
                                }
                            }

                            // Custom Slider Section
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF0F172A).copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Точная настройка:",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextWhite.copy(alpha = 0.85f)
                                        )
                                        Text(
                                            text = formatMinutes(customMinutes.toInt()),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = activeAccentColor
                                        )
                                    }

                                    Slider(
                                        value = customMinutes,
                                        onValueChange = { customMinutes = it },
                                        valueRange = 5f..360f,
                                        steps = 70, // 5 min increments
                                        colors = SliderDefaults.colors(
                                            thumbColor = activeAccentColor,
                                            activeTrackColor = activeAccentColor,
                                            inactiveTrackColor = Color.White.copy(alpha = 0.12f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Action Buttons placed in the flow directly below the container
                if (timerState.isActive) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Закрыть
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onDismiss()
                                })
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Закрыть",
                                    color = TextWhite.copy(alpha = 0.90f),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        // Отключить
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.50f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    SleepTimerManager.cancelTimer(context)
                                    onDismiss()
                                })
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Отключить",
                                    color = Color(0xFFEF4444),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Отклонить
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onDismiss()
                                })
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Отклонить",
                                    color = TextWhite.copy(alpha = 0.90f),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        // Запустить
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = activeAccentColor,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    SleepTimerManager.startTimer(context, customMinutes.toInt())
                                    onDismiss()
                                })
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Запустить",
                                    color = Color(0xFF0A0E1A),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // Top Header with Back Button (pinned at top left over blurred background)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
            ) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_left),
                        contentDescription = "Назад",
                        tint = TextWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetChip(
    title: String,
    isSelected: Boolean,
    activeColor: Color = ActiveGreenLed,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .springPress(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) activeColor.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f),
        border = BorderStroke(
            1.dp,
            if (isSelected) activeColor.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f)
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = title,
                fontSize = 12.5.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) activeColor else TextWhite.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun QuickExtendChip(
    title: String,
    activeColor: Color = ActiveGreenLed,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .springPress(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = activeColor.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, activeColor.copy(alpha = 0.35f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = activeColor
            )
        }
    }
}

private fun formatMinutes(totalMin: Int): String {
    val hours = totalMin / 60
    val mins = totalMin % 60
    return when {
        hours > 0 && mins > 0 -> "$hours ч $mins мин"
        hours > 0 -> "$hours ч"
        else -> "$mins мин"
    }
}

