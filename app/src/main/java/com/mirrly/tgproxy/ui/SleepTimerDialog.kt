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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mirrly.tgproxy.R
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

    var customMinutes by remember { mutableStateOf(45f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window?.attributes = window?.attributes?.apply {
                    blurBehindRadius = 70
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
                .padding(horizontal = 22.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Catch clicks inside */ },
                shape = RoundedCornerShape(26.dp),
                color = AmoledSurface,
                border = BorderStroke(1.dp, Color(0xFF232838))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Icon & Title
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                if (timerState.isActive) ActiveGreenLed.copy(alpha = 0.15f)
                                else Color(0xFF1E2333)
                            )
                            .border(
                                1.dp,
                                if (timerState.isActive) ActiveGreenLed.copy(alpha = 0.4f)
                                else Color(0xFF2C3246),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_timer),
                            contentDescription = null,
                            tint = if (timerState.isActive) ActiveGreenLed else TextWhite,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (timerState.isActive) "Таймер автоотключения" else "Таймер сна",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    if (timerState.isActive) {
                        // ── ACTIVE TIMER STATE ──
                        val targetTimeStr = remember(timerState.targetTimeMs) {
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timerState.targetTimeMs))
                        }

                        Text(
                            text = "Прокси автоматически выключится в $targetTimeStr",
                            fontSize = 13.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Large Countdown display
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0xFF0C0E14),
                            border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "ОСТАЛОСЬ",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.5.sp,
                                    color = TextMuted
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = timerState.formatRemainingTime(),
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    color = ActiveGreenLed,
                                    letterSpacing = 1.5.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "БЫСТРОЕ ПРОДЛЕНИЕ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 1.2.sp,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

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

                        Spacer(modifier = Modifier.height(20.dp))

                        // Turn off timer button
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                SleepTimerManager.cancelTimer(context)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444).copy(alpha = 0.15f),
                                contentColor = Color(0xFFEF4444)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f))
                        ) {
                            Text("Отключить таймер", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    } else {
                        // ── INACTIVE TIMER: SELECT PRESET ──
                        Text(
                            text = "Выберите время, через которое прокси-сервер автоматически отключится для экономии заряда и трафика:",
                            fontSize = 12.5.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // 6 Quick Presets Grid
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PresetButton("15 мин", 15, Modifier.weight(1f)) { min ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    SleepTimerManager.startTimer(context, min)
                                    onDismiss()
                                }
                                PresetButton("30 мин", 30, Modifier.weight(1f)) { min ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    SleepTimerManager.startTimer(context, min)
                                    onDismiss()
                                }
                                PresetButton("45 мин", 45, Modifier.weight(1f)) { min ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    SleepTimerManager.startTimer(context, min)
                                    onDismiss()
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PresetButton("1 час", 60, Modifier.weight(1f)) { min ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    SleepTimerManager.startTimer(context, min)
                                    onDismiss()
                                }
                                PresetButton("2 часа", 120, Modifier.weight(1f)) { min ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    SleepTimerManager.startTimer(context, min)
                                    onDismiss()
                                }
                                PresetButton("4 часа", 240, Modifier.weight(1f)) { min ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    SleepTimerManager.startTimer(context, min)
                                    onDismiss()
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Custom Slider Section
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF0C0E14),
                            border = BorderStroke(1.dp, Color(0xFF1E2333))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Свой интервал:",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextWhite
                                    )
                                    val formattedCustom = formatMinutes(customMinutes.toInt())
                                    Text(
                                        text = formattedCustom,
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
                                        inactiveTrackColor = Color(0xFF222838)
                                    )
                                )

                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        SleepTimerManager.startTimer(context, customMinutes.toInt())
                                        onDismiss()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ActiveGreenLed,
                                        contentColor = Color.Black
                                    )
                                ) {
                                    Text(
                                        text = "Запустить на ${formatMinutes(customMinutes.toInt())}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Close / Dismiss
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Закрыть", color = TextMuted, fontSize = 13.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetButton(
    title: String,
    minutes: Int,
    modifier: Modifier = Modifier,
    onClick: (Int) -> Unit
) {
    Surface(
        onClick = { onClick(minutes) },
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF141824),
        border = BorderStroke(1.dp, Color(0xFF252C3E))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextWhite
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
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF161B28),
        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = title,
                fontSize = 12.5.sp,
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
