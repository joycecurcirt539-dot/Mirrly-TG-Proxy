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
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mirrly.tgproxy.service.SleepTimerManager
import com.mirrly.tgproxy.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SleepTimerDialog(
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
            // Detailed Content (Centered)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 110.dp, top = 24.dp)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable(enabled = false) {}
            ) {
                // Category Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ActiveGreenLed.copy(alpha = 0.12f),
                    border = BorderStroke(
                        1.dp,
                        ActiveGreenLed.copy(alpha = 0.35f)
                    )
                ) {
                    Text(
                        text = if (timerState.isActive) "ТАЙМЕР АКТИВЕН" else "ТАЙМЕР СНА И АВТООТКЛЮЧЕНИЯ",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                // Main Title
                Text(
                    text = if (timerState.isActive) "Таймер автоотключения" else "Настройка таймера сна",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Detailed Description
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
                    fontSize = 13.5.sp,
                    color = TextWhite.copy(alpha = 0.88f),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(2.dp))

                if (timerState.isActive) {
                    // ── ACTIVE TIMER STATE ──
                    // Large Countdown display
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White.copy(alpha = 0.05f),
                        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.30f))
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

                    Spacer(modifier = Modifier.height(4.dp))

                    // Quick Extend Section
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "БЫСТРОЕ ПРОДЛЕНИЕ",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = TextWhite.copy(alpha = 0.60f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuickExtendChip("+15 мин", Modifier.weight(1f)) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                SleepTimerManager.extendTimer(context, 15)
                            }
                            QuickExtendChip("+30 мин", Modifier.weight(1f)) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                SleepTimerManager.extendTimer(context, 30)
                            }
                            QuickExtendChip("+1 час", Modifier.weight(1f)) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                SleepTimerManager.extendTimer(context, 60)
                            }
                        }
                    }
                } else {
                    // ── INACTIVE TIMER: SELECT PRESET OR CUSTOM ──
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
                                    modifier = Modifier.weight(1f)
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    customMinutes = min.toFloat()
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // Custom Slider Section
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.05f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp)
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
                                    color = ActiveGreenLed
                                )
                            }

                            Slider(
                                value = customMinutes,
                                onValueChange = { customMinutes = it },
                                valueRange = 5f..360f,
                                steps = 70, // 5 min increments
                                colors = SliderDefaults.colors(
                                    thumbColor = ActiveGreenLed,
                                    activeTrackColor = ActiveGreenLed,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.12f)
                                )
                            )
                        }
                    }
                }
            }

            // Floating Bottom Action Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
            ) {
                if (timerState.isActive) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextWhite.copy(alpha = 0.90f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Закрыть", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            SleepTimerManager.cancelTimer(context)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF3B30).copy(alpha = 0.20f),
                            contentColor = Color(0xFFFF453A)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFFF3B30).copy(alpha = 0.45f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Отключить", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextWhite.copy(alpha = 0.90f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Отклонить", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            SleepTimerManager.startTimer(context, customMinutes.toInt())
                            onDismiss()
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ActiveGreenLed.copy(alpha = 0.22f),
                            contentColor = ActiveGreenLed
                        ),
                        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.60f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Запустить", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetChip(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) ActiveGreenLed.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f),
        border = BorderStroke(
            1.dp,
            if (isSelected) ActiveGreenLed.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.18f)
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) ActiveGreenLed else TextWhite.copy(alpha = 0.90f)
            )
        }
    }
}

@Composable
private fun QuickExtendChip(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = ActiveGreenLed.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = ActiveGreenLed
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
