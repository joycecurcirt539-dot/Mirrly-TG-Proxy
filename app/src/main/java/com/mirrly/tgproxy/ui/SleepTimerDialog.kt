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

import android.app.TimePickerDialog
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
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.ScheduleConfig
import com.mirrly.tgproxy.service.ScheduleDaysMode
import com.mirrly.tgproxy.service.ScheduleManager
import com.mirrly.tgproxy.service.SleepTimerManager
import com.mirrly.tgproxy.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

enum class TimerDialogTab {
    TIMER,
    SCHEDULE
}

/**
 * Frosted Glass Sleep Timer & Schedule Modal Dialog (No emojis, strict style).
 */
@Composable
fun SleepTimerDialog(
    initialTab: TimerDialogTab = TimerDialogTab.TIMER,
    activeAccentColor: Color? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val timerState by SleepTimerManager.timerState.collectAsState()
    val app = MirrlyApplication.instance

    val greenLed = ActiveGreenLed
    val accentColor = activeAccentColor ?: greenLed

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentInteractionSource = remember { MutableInteractionSource() }
    val scrollState = rememberScrollState()

    var selectedTab by remember { mutableStateOf(initialTab) }
    var customMinutes by remember { mutableFloatStateOf(45f) }

    // Auto-Stop on Start settings
    var autoStopOnStartEnabled by remember { mutableStateOf(app.prefsManager.isAutoStopOnStartEnabled()) }
    var autoStopMinutes by remember { mutableIntStateOf(app.prefsManager.getAutoStopMinutes()) }

    // Schedule settings state
    var scheduleConfig by remember { mutableStateOf(app.prefsManager.loadScheduleConfig()) }

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
                    .verticalScroll(scrollState)
                    .padding(
                        top = statusBarTop + 60.dp,
                        bottom = navBarBottom + 24.dp
                    )
                    .padding(horizontal = 24.dp)
                    .clickable(
                        interactionSource = contentInteractionSource,
                        indication = null
                    ) {}
            ) {
                // Category Switcher Pills (Timer vs Schedule)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedTab = TimerDialogTab.TIMER
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = if (selectedTab == TimerDialogTab.TIMER) {
                            (if (timerState.isActive) greenLed else accentColor).copy(alpha = 0.16f)
                        } else Color.White.copy(alpha = 0.05f),
                        border = BorderStroke(
                            1.dp,
                            if (selectedTab == TimerDialogTab.TIMER) {
                                (if (timerState.isActive) greenLed else accentColor).copy(alpha = 0.45f)
                            } else Color.White.copy(alpha = 0.10f)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = if (timerState.isActive) "ТАЙМЕР (АКТИВЕН)" else "ТАЙМЕР СНА",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == TimerDialogTab.TIMER) {
                                    if (timerState.isActive) greenLed else accentColor
                                } else TextWhite.copy(alpha = 0.7f),
                                letterSpacing = 0.8.sp
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedTab = TimerDialogTab.SCHEDULE
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = if (selectedTab == TimerDialogTab.SCHEDULE) {
                            accentColor.copy(alpha = 0.16f)
                        } else Color.White.copy(alpha = 0.05f),
                        border = BorderStroke(
                            1.dp,
                            if (selectedTab == TimerDialogTab.SCHEDULE) {
                                accentColor.copy(alpha = 0.45f)
                            } else Color.White.copy(alpha = 0.10f)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = if (scheduleConfig.isEnabled) "РАСПИСАНИЕ (ВКЛ)" else "РАСПИСАНИЕ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == TimerDialogTab.SCHEDULE) {
                                    if (scheduleConfig.isEnabled) greenLed else accentColor
                                } else TextWhite.copy(alpha = 0.7f),
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }

                // Main Title
                Text(
                    text = when {
                        selectedTab == TimerDialogTab.SCHEDULE -> "Расписание работы прокси"
                        timerState.isActive -> "Таймер автоотключения"
                        else -> "Настройка таймера сна"
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                if (selectedTab == TimerDialogTab.TIMER) {
                    // ── TIMER TAB CONTENT ───────────────────────────────────
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
                                color = if (timerState.isActive) greenLed else accentColor,
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
                                    border = BorderStroke(1.dp, greenLed.copy(alpha = 0.35f)),
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
                                            color = greenLed,
                                            letterSpacing = 1.5.sp
                                        )
                                    }
                                }

                                // Quick extend chips (+5 мин, +15 мин, +30 мин, +1 час)
                                Text(
                                    text = "БЫСТРОЕ ПРОДЛЕНИЕ:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite.copy(alpha = 0.70f),
                                    letterSpacing = 0.6.sp
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    QuickExtendChip("+5 мин", accentColor, Modifier.weight(1f)) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        SleepTimerManager.extendTimer(context, 5)
                                    }
                                    QuickExtendChip("+15 мин", accentColor, Modifier.weight(1f)) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        SleepTimerManager.extendTimer(context, 15)
                                    }
                                    QuickExtendChip("+30 мин", accentColor, Modifier.weight(1f)) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        SleepTimerManager.extendTimer(context, 30)
                                    }
                                    QuickExtendChip("+1 ч", accentColor, Modifier.weight(1f)) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        SleepTimerManager.extendTimer(context, 60)
                                    }
                                }
                            } else {
                                // Presets Selection (5m, 15m, 30m, 45m / 1h, 2h, 4h)
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(5 to "5 мин", 15 to "15 мин", 30 to "30 мин", 45 to "45 мин").forEach { (min, label) ->
                                            PresetChip(
                                                title = label,
                                                isSelected = customMinutes.toInt() == min,
                                                activeColor = accentColor,
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
                                                activeColor = accentColor,
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
                                                color = accentColor
                                            )
                                        }

                                        Slider(
                                            value = customMinutes,
                                            onValueChange = { customMinutes = it },
                                            valueRange = 1f..360f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = accentColor,
                                                activeTrackColor = accentColor,
                                                inactiveTrackColor = Color.White.copy(alpha = 0.12f)
                                            )
                                        )
                                    }
                                }

                                // Auto-stop on Start option
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF0F172A).copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    text = "Автоотключение при старте",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = TextWhite
                                                )
                                                Text(
                                                    text = "Автоматически выключать через $autoStopMinutes мин после запуска",
                                                    fontSize = 11.sp,
                                                    color = TextMuted
                                                )
                                            }
                                            InertialSpringSwitch(
                                                checked = autoStopOnStartEnabled,
                                                onCheckedChange = {
                                                    autoStopOnStartEnabled = it
                                                    app.prefsManager.setAutoStopOnStartEnabled(it)
                                                }
                                            )
                                        }

                                        if (autoStopOnStartEnabled) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf(5, 10, 15, 30, 60).forEach { m ->
                                                    PresetChip(
                                                        title = "$m мин",
                                                        isSelected = autoStopMinutes == m,
                                                        activeColor = accentColor,
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        autoStopMinutes = m
                                                        app.prefsManager.setAutoStopMinutes(m)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ── SCHEDULE TAB CONTENT ────────────────────────────────
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Master Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = "Включить расписание",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = if (scheduleConfig.isEnabled) "Служба прокси будет работать по заданному времени" else "Автоматический запуск и остановка отключены",
                                        fontSize = 11.5.sp,
                                        color = if (scheduleConfig.isEnabled) greenLed else TextMuted
                                    )
                                }
                                InertialSpringSwitch(
                                    checked = scheduleConfig.isEnabled,
                                    onCheckedChange = {
                                        scheduleConfig = scheduleConfig.copy(isEnabled = it)
                                        app.prefsManager.saveScheduleConfig(scheduleConfig)
                                        ScheduleManager.syncSchedule(context)
                                    }
                                )
                            }

                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                            // Time pickers row (Start / Stop)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Start Time Box
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        TimePickerDialog(
                                            context,
                                            { _, hour, minute ->
                                                scheduleConfig = scheduleConfig.copy(startHour = hour, startMinute = minute)
                                                app.prefsManager.saveScheduleConfig(scheduleConfig)
                                                ScheduleManager.syncSchedule(context)
                                            },
                                            scheduleConfig.startHour,
                                            scheduleConfig.startMinute,
                                            true
                                        ).show()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF0F172A).copy(alpha = 0.6f),
                                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "ВКЛЮЧЕНИЕ",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                            color = TextMuted
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = scheduleConfig.formatStartTime(),
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Black,
                                            color = accentColor
                                        )
                                    }
                                }

                                // Stop Time Box
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        TimePickerDialog(
                                            context,
                                            { _, hour, minute ->
                                                scheduleConfig = scheduleConfig.copy(stopHour = hour, stopMinute = minute)
                                                app.prefsManager.saveScheduleConfig(scheduleConfig)
                                                ScheduleManager.syncSchedule(context)
                                            },
                                            scheduleConfig.stopHour,
                                            scheduleConfig.stopMinute,
                                            true
                                        ).show()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF0F172A).copy(alpha = 0.6f),
                                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "ВЫКЛЮЧЕНИЕ",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                            color = TextMuted
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = scheduleConfig.formatStopTime(),
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color(0xFFEF4444)
                                        )
                                    }
                                }
                            }

                            // Days Mode Selection
                            Text(
                                text = "ДНИ РАБОТЫ:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite.copy(alpha = 0.70f),
                                letterSpacing = 0.6.sp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ScheduleDaysMode.entries.forEach { mode ->
                                    PresetChip(
                                        title = mode.title.substringBefore(" ("),
                                        isSelected = scheduleConfig.daysMode == mode,
                                        activeColor = accentColor,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        scheduleConfig = scheduleConfig.copy(daysMode = mode)
                                        app.prefsManager.saveScheduleConfig(scheduleConfig)
                                        ScheduleManager.syncSchedule(context)
                                    }
                                }
                            }

                            // Custom Day Pills if CUSTOM mode selected
                            if (scheduleConfig.daysMode == ScheduleDaysMode.CUSTOM) {
                                val dayNames = listOf(
                                    Calendar.MONDAY to "Пн",
                                    Calendar.TUESDAY to "Вт",
                                    Calendar.WEDNESDAY to "Ср",
                                    Calendar.THURSDAY to "Чт",
                                    Calendar.FRIDAY to "Пт",
                                    Calendar.SATURDAY to "Сб",
                                    Calendar.SUNDAY to "Вс"
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    dayNames.forEach { (calDay, label) ->
                                        val isDaySelected = calDay in scheduleConfig.customDays
                                        Surface(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                val newDays = scheduleConfig.customDays.toMutableSet()
                                                if (isDaySelected) {
                                                    if (newDays.size > 1) newDays.remove(calDay)
                                                } else {
                                                    newDays.add(calDay)
                                                }
                                                scheduleConfig = scheduleConfig.copy(customDays = newDays)
                                                app.prefsManager.saveScheduleConfig(scheduleConfig)
                                                ScheduleManager.syncSchedule(context)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isDaySelected) accentColor.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.04f),
                                            border = BorderStroke(
                                                1.dp,
                                                if (isDaySelected) accentColor.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.10f)
                                            ),
                                            modifier = Modifier.weight(1f).height(36.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = label,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isDaySelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isDaySelected) accentColor else TextWhite.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Summary Banner
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF0F172A).copy(alpha = 0.4f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (scheduleConfig.isEnabled) {
                                        "Прокси будет автоматически запускаться в ${scheduleConfig.formatStartTime()} и выключаться в ${scheduleConfig.formatStopTime()} (${scheduleConfig.daysMode.title})."
                                    } else {
                                        "Включите переключатель сверху, чтобы активировать расписание."
                                    },
                                    fontSize = 12.sp,
                                    color = TextWhite.copy(alpha = 0.75f),
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Action Buttons below
                if (selectedTab == TimerDialogTab.TIMER) {
                    if (timerState.isActive) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
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

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = accentColor,
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
                } else {
                    // Schedule Action Button (Готово / Сохранить)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = accentColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                app.prefsManager.saveScheduleConfig(scheduleConfig)
                                ScheduleManager.syncSchedule(context)
                                onDismiss()
                            })
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Сохранить расписание",
                                color = Color(0xFF0A0E1A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // Top Header with Back Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(top = statusBarTop + 8.dp)
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
    activeColor: Color,
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
    activeColor: Color,
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
