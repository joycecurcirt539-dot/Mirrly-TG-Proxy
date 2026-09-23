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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.stringResource
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
                    .navigationBarsPadding()
                    .padding(
                        top = statusBarTop + 44.dp,
                        bottom = 96.dp
                    )
                    .padding(horizontal = 20.dp)
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
                                text = if (timerState.isActive) stringResource(R.string.sleep_timer_tab_active) else stringResource(R.string.sleep_timer_tab),
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
                                text = if (scheduleConfig.isEnabled) stringResource(R.string.sleep_timer_tab_schedule_on) else stringResource(R.string.sleep_timer_tab_schedule),
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
                        selectedTab == TimerDialogTab.SCHEDULE -> stringResource(R.string.sleep_timer_title_schedule)
                        timerState.isActive -> stringResource(R.string.sleep_timer_title_active)
                        else -> stringResource(R.string.sleep_timer_title_setup)
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                if (selectedTab == TimerDialogTab.TIMER) {
                    // ── TIMER TAB CONTENT ───────────────────────────────────
                    // CARD 1: ONE-OFF SLEEP TIMER
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
                                text = if (timerState.isActive) stringResource(R.string.sleep_timer_status_current) else stringResource(R.string.sleep_timer_status_one_time),
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
                                    stringResource(R.string.sleep_timer_desc_stopping, targetTimeStr)
                                } else {
                                    stringResource(R.string.sleep_timer_desc_choose)
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
                                            text = stringResource(R.string.sleep_timer_remaining_header),
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

                                // Quick extend chips (+5 min, +15 min, +30 min, +1 hour)
                                Text(
                                    text = stringResource(R.string.sleep_timer_quick_extend_header),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite.copy(alpha = 0.70f),
                                    letterSpacing = 0.6.sp
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    QuickExtendChip(stringResource(R.string.sleep_timer_plus_5m), accentColor, Modifier.weight(1f)) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        SleepTimerManager.extendTimer(context, 5)
                                    }
                                    QuickExtendChip(stringResource(R.string.sleep_timer_plus_15m), accentColor, Modifier.weight(1f)) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        SleepTimerManager.extendTimer(context, 15)
                                    }
                                    QuickExtendChip(stringResource(R.string.sleep_timer_plus_30m), accentColor, Modifier.weight(1f)) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        SleepTimerManager.extendTimer(context, 30)
                                    }
                                    QuickExtendChip(stringResource(R.string.sleep_timer_plus_1h), accentColor, Modifier.weight(1f)) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        SleepTimerManager.extendTimer(context, 60)
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Active Timer Actions: Close / Disable
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
                                            .height(46.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .springPress(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onDismiss()
                                            })
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = stringResource(R.string.action_close),
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
                                            .height(46.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .springPress(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                SleepTimerManager.cancelTimer(context)
                                                onDismiss()
                                            })
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = stringResource(R.string.action_disable),
                                                color = Color(0xFFEF4444),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                        }
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
                                        listOf(5 to stringResource(R.string.time_min_val, 5), 15 to stringResource(R.string.time_min_val, 15), 30 to stringResource(R.string.time_min_val, 30), 45 to stringResource(R.string.time_min_val, 45)).forEach { (min, label) ->
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
                                        listOf(60 to stringResource(R.string.time_1_hour), 120 to stringResource(R.string.time_2_hours), 240 to stringResource(R.string.time_4_hours)).forEach { (min, label) ->
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
                                                text = stringResource(R.string.sleep_timer_precise_setup),
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

                                Spacer(modifier = Modifier.height(2.dp))

                                // Action buttons for one-off timer: Decline / Start timer
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
                                            .height(46.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .springPress(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onDismiss()
                                            })
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = stringResource(R.string.action_dismiss),
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
                                            .weight(1.3f)
                                            .height(46.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .springPress(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                SleepTimerManager.startTimer(context, customMinutes.toInt())
                                                onDismiss()
                                            })
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = stringResource(R.string.sleep_timer_btn_start),
                                                color = Color(0xFF0A0E1A),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // CARD 2: AUTO-STOP ON START (Independent persistent rule)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = stringResource(R.string.sleep_timer_autostop_title),
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextWhite
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (autoStopOnStartEnabled) {
                                            stringResource(R.string.sleep_timer_autostop_active, autoStopMinutes)
                                        } else {
                                            stringResource(R.string.sleep_timer_autostop_desc)
                                        },
                                        fontSize = 11.5.sp,
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
                                            title = stringResource(R.string.time_min_val, m),
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

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(greenLed)
                                    )
                                    Text(
                                        text = stringResource(R.string.sleep_timer_saved_hint),
                                        fontSize = 10.5.sp,
                                        color = TextMuted.copy(alpha = 0.8f)
                                    )
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
                                        text = stringResource(R.string.sleep_timer_enable_schedule),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = if (scheduleConfig.isEnabled) stringResource(R.string.sleep_timer_schedule_enabled_desc) else stringResource(R.string.sleep_timer_schedule_disabled_desc),
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
                                            text = stringResource(R.string.sleep_timer_start_time_label),
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
                                            text = stringResource(R.string.sleep_timer_stop_time_label),
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
                                text = stringResource(R.string.sleep_timer_working_days_label),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite.copy(alpha = 0.70f),
                                letterSpacing = 0.6.sp
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ScheduleDaysMode.entries.chunked(2).forEach { modesRow ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        modesRow.forEach { mode ->
                                            PresetChip(
                                                title = stringResource(mode.titleRes).substringBefore(" ("),
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
                                        if (modesRow.size == 1) Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }

                            // Custom Day Pills if CUSTOM mode selected
                            if (scheduleConfig.daysMode == ScheduleDaysMode.CUSTOM) {
                                val dayNames = listOf(
                                    Calendar.MONDAY to stringResource(R.string.day_mon_short),
                                    Calendar.TUESDAY to stringResource(R.string.day_tue_short),
                                    Calendar.WEDNESDAY to stringResource(R.string.day_wed_short),
                                    Calendar.THURSDAY to stringResource(R.string.day_thu_short),
                                    Calendar.FRIDAY to stringResource(R.string.day_fri_short),
                                    Calendar.SATURDAY to stringResource(R.string.day_sat_short),
                                    Calendar.SUNDAY to stringResource(R.string.day_sun_short)
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
                                        stringResource(R.string.sleep_timer_schedule_summary, scheduleConfig.formatStartTime(), scheduleConfig.formatStopTime(), scheduleConfig.daysMode.title)
                                    } else {
                                        stringResource(R.string.sleep_timer_schedule_enable_hint)
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

                // Schedule Action Button (Done / Save)
                if (selectedTab == TimerDialogTab.SCHEDULE) {
                    Spacer(modifier = Modifier.height(4.dp))
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
                                text = stringResource(R.string.sleep_timer_btn_save_schedule),
                                color = Color(0xFF0A0E1A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))
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
                        contentDescription = stringResource(R.string.action_back),
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
        hours > 0 && mins > 0 -> "$hours h $mins m"
        hours > 0 -> "$hours h"
        else -> "$mins m"
    }
}
