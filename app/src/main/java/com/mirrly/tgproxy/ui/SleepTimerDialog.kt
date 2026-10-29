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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.ScheduleConfig
import com.mirrly.tgproxy.service.ScheduleDaysMode
import com.mirrly.tgproxy.service.ScheduleManager
import com.mirrly.tgproxy.service.SleepTimerManager
import com.mirrly.tgproxy.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class TimerDialogTab {
    TIMER,
    SCHEDULE
}

private enum class TimeEditTarget {
    START,
    STOP
}

/**
 * MirrlyUI Modal Bottom Sheet for Sleep Timer & Scheduled Proxy Operation.
 * Fully compliant with MirrlyUI Dogma 1 (Zero-Background / Outline-Only) and
 * Dogma 2 (Zero-Popup) architecture.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    var selectedTab by remember { mutableStateOf(initialTab) }
    var customMinutes by remember { mutableFloatStateOf(45f) }

    // Auto-Stop on Start settings
    var autoStopOnStartEnabled by remember { mutableStateOf(app.prefsManager.isAutoStopOnStartEnabled()) }
    var autoStopMinutes by remember { mutableIntStateOf(app.prefsManager.getAutoStopMinutes()) }

    // Schedule settings state
    var scheduleConfig by remember { mutableStateOf(app.prefsManager.loadScheduleConfig()) }

    // Inline Time Selector state (Zero-Popup: replaces TimePickerDialog)
    var activeTimeTarget by remember { mutableStateOf<TimeEditTarget?>(null) }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 480.dp
    ) {
        // MirrlyUI Bottom Sheet Header with close button
        MirrlyBottomSheetHeader(
            title = stringResource(R.string.content_desc_sleep_timer),
            subtitle = if (selectedTab == TimerDialogTab.TIMER) {
                if (timerState.isActive) stringResource(R.string.sleep_timer_title_active) else stringResource(R.string.sleep_timer_title_setup)
            } else {
                stringResource(R.string.sleep_timer_title_schedule)
            },
            onClose = onDismiss
        )

        // Scrollable content area with butter-smooth edge dissolving
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 8.dp, bottomFadeHeight = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Segmented Tab Switcher (Outline-Only with LED indicators)
            TimerSegmentedControl(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                isTimerActive = timerState.isActive,
                isScheduleActive = scheduleConfig.isEnabled,
                accentColor = accentColor
            )

            // Animated Tab Transition
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    if (targetState == TimerDialogTab.SCHEDULE) {
                        (slideInHorizontally { width -> width / 3 } + fadeIn(tween(200)))
                            .togetherWith(slideOutHorizontally { width -> -width / 3 } + fadeOut(tween(150)))
                    } else {
                        (slideInHorizontally { width -> -width / 3 } + fadeIn(tween(200)))
                            .togetherWith(slideOutHorizontally { width -> width / 3 } + fadeOut(tween(150)))
                    }
                },
                label = "TimerScheduleTabTransition"
            ) { currentTab ->
                if (currentTab == TimerDialogTab.TIMER) {
                    // ── SECTION 1: SLEEP TIMER ──────────────────────────────────────────
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (timerState.isActive) {
                            // Active Timer Card: Monospace countdown, end time, extend chips, cancel button
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, greenLed.copy(alpha = 0.50f), RoundedCornerShape(16.dp))
                                    .background(Color.Transparent)
                                    .padding(18.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.sleep_timer_remaining_header),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.2.sp,
                                        color = TextMuted
                                    )

                                    Text(
                                        text = timerState.formatRemainingTime(),
                                        fontSize = 38.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        color = greenLed,
                                        letterSpacing = 2.sp
                                    )

                                    val targetTimeStr = remember(timerState.targetTimeMs) {
                                        if (timerState.targetTimeMs > 0) {
                                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timerState.targetTimeMs))
                                        } else ""
                                    }
                                    Text(
                                        text = stringResource(R.string.sleep_timer_desc_stopping, targetTimeStr),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextWhite.copy(alpha = 0.85f),
                                        textAlign = TextAlign.Center,
                                        lineHeight = 17.sp
                                    )

                                    // Quick extend chips
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.sleep_timer_quick_extend_header),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextMuted,
                                            letterSpacing = 0.6.sp
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(
                                                5 to stringResource(R.string.sleep_timer_plus_5m),
                                                15 to stringResource(R.string.sleep_timer_plus_15m),
                                                30 to stringResource(R.string.sleep_timer_plus_30m),
                                                60 to stringResource(R.string.sleep_timer_plus_1h)
                                            ).forEach { (minutes, label) ->
                                                QuickExtendChip(
                                                    title = label,
                                                    activeColor = accentColor,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    SleepTimerManager.extendTimer(context, minutes)
                                                }
                                            }
                                        }
                                    }

                                    // Destructive Outline Button: Cancel Timer
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.60f), RoundedCornerShape(14.dp))
                                            .background(Color.Transparent)
                                            .springPress(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                SleepTimerManager.cancelTimer(context)
                                            }),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.action_stop),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFEF4444)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Inactive Timer Card: Presets, slider, start button
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(16.dp))
                                    .background(Color.Transparent)
                                    .padding(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = stringResource(R.string.sleep_timer_status_one_time),
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextWhite
                                        )
                                        Text(
                                            text = stringResource(R.string.sleep_timer_desc_choose),
                                            fontSize = 11.5.sp,
                                            color = TextMuted,
                                            lineHeight = 16.sp
                                        )
                                    }

                                    // Quick Presets Grid (15m, 30m, 45m / 1h, 2h, 4h)
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(
                                                15 to stringResource(R.string.time_min_val, 15),
                                                30 to stringResource(R.string.time_min_val, 30),
                                                45 to stringResource(R.string.time_min_val, 45)
                                            ).forEach { (min, label) ->
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
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(
                                                60 to stringResource(R.string.time_1_hour),
                                                120 to stringResource(R.string.time_2_hours),
                                                240 to stringResource(R.string.time_4_hours)
                                            ).forEach { (min, label) ->
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

                                    // Precise Slider Container
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                                            .background(Color.Transparent)
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                                    color = TextMuted
                                                )
                                                Text(
                                                    text = formatMinutes(customMinutes.toInt()),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace,
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
                                                    inactiveTrackColor = AmoledBorder
                                                )
                                            )
                                        }
                                    }

                                    // Outline Action Button: Start Timer
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .border(1.dp, accentColor, RoundedCornerShape(14.dp))
                                            .background(Color.Transparent)
                                            .springPress(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                SleepTimerManager.startTimer(context, customMinutes.toInt())
                                                onDismiss()
                                            }),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.sleep_timer_btn_start),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = accentColor
                                        )
                                    }
                                }
                            }
                        }

                        // Auto-Stop on Start Card (Independent Persistent Rule)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, AmoledBorder, RoundedCornerShape(16.dp))
                                .background(Color.Transparent)
                                .padding(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                                    ) {
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
                                            color = TextMuted,
                                            lineHeight = 16.sp
                                        )
                                    }

                                    InertialSpringSwitch(
                                        checked = autoStopOnStartEnabled,
                                        onCheckedChange = {
                                            autoStopOnStartEnabled = it
                                            app.prefsManager.setAutoStopOnStartEnabled(it)
                                        },
                                        activeColor = accentColor
                                    )
                                }

                                AnimatedVisibility(
                                    visible = autoStopOnStartEnabled,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
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
                                                    .size(4.dp)
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
                        }
                    }
                } else {
                    // ── SECTION 2: SCHEDULE (ZERO-POPUP) ───────────────────────────────
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Master Toggle Card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    1.dp,
                                    if (scheduleConfig.isEnabled) greenLed.copy(alpha = 0.50f) else AmoledBorder,
                                    RoundedCornerShape(16.dp)
                                )
                                .background(Color.Transparent)
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = stringResource(R.string.sleep_timer_enable_schedule),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (scheduleConfig.isEnabled) {
                                            stringResource(R.string.sleep_timer_schedule_enabled_desc)
                                        } else {
                                            stringResource(R.string.sleep_timer_schedule_disabled_desc)
                                        },
                                        fontSize = 11.5.sp,
                                        color = if (scheduleConfig.isEnabled) greenLed else TextMuted,
                                        lineHeight = 16.sp
                                    )
                                }
                                InertialSpringSwitch(
                                    checked = scheduleConfig.isEnabled,
                                    onCheckedChange = { isEnabled ->
                                        scheduleConfig = scheduleConfig.copy(isEnabled = isEnabled)
                                        app.prefsManager.saveScheduleConfig(scheduleConfig)
                                        ScheduleManager.syncSchedule(context)
                                    },
                                    activeColor = greenLed
                                )
                            }
                        }

                        // Inline Time Selectors (Start & Stop Cards)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Start Time Card
                            val isStartActive = activeTimeTarget == TimeEditTarget.START
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(
                                        1.dp,
                                        if (isStartActive) accentColor else AmoledBorder,
                                        RoundedCornerShape(14.dp)
                                    )
                                    .background(Color.Transparent)
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        activeTimeTarget = if (isStartActive) null else TimeEditTarget.START
                                    })
                                    .padding(14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = stringResource(R.string.sleep_timer_start_time_label),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                        color = if (isStartActive) accentColor else TextMuted
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = scheduleConfig.formatStartTime(),
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        color = accentColor
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isStartActive) "Свернуть" else "Настроить",
                                        fontSize = 10.5.sp,
                                        color = TextMuted
                                    )
                                }
                            }

                            // Stop Time Card
                            val isStopActive = activeTimeTarget == TimeEditTarget.STOP
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(
                                        1.dp,
                                        if (isStopActive) Color(0xFFEF4444) else AmoledBorder,
                                        RoundedCornerShape(14.dp)
                                    )
                                    .background(Color.Transparent)
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        activeTimeTarget = if (isStopActive) null else TimeEditTarget.STOP
                                    })
                                    .padding(14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = stringResource(R.string.sleep_timer_stop_time_label),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                        color = if (isStopActive) Color(0xFFEF4444) else TextMuted
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = scheduleConfig.formatStopTime(),
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFEF4444)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isStopActive) "Свернуть" else "Настроить",
                                        fontSize = 10.5.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                        }

                        // Inline Stepper Editor Panel (Zero-Popup)
                        AnimatedVisibility(
                            visible = activeTimeTarget != null,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            val target = activeTimeTarget ?: TimeEditTarget.START
                            val isStart = target == TimeEditTarget.START
                            val targetAccent = if (isStart) accentColor else Color(0xFFEF4444)
                            val currentHour = if (isStart) scheduleConfig.startHour else scheduleConfig.stopHour
                            val currentMin = if (isStart) scheduleConfig.startMinute else scheduleConfig.stopMinute

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                                    .background(Color.Transparent)
                                    .padding(14.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = if (isStart) "Время включения" else "Время выключения",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = targetAccent
                                    )

                                    // Steppers for Hours and Minutes
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Hours Stepper
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "ЧАСЫ",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextMuted,
                                                letterSpacing = 0.8.sp
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                StepperButton(label = "—") {
                                                    val newH = (currentHour - 1 + 24) % 24
                                                    scheduleConfig = if (isStart) {
                                                        scheduleConfig.copy(startHour = newH)
                                                    } else {
                                                        scheduleConfig.copy(stopHour = newH)
                                                    }
                                                    app.prefsManager.saveScheduleConfig(scheduleConfig)
                                                    ScheduleManager.syncSchedule(context)
                                                }

                                                Text(
                                                    text = "%02d".format(currentHour),
                                                    fontSize = 22.sp,
                                                    fontWeight = FontWeight.Black,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = targetAccent,
                                                    modifier = Modifier.width(36.dp),
                                                    textAlign = TextAlign.Center
                                                )

                                                StepperButton(label = "+") {
                                                    val newH = (currentHour + 1) % 24
                                                    scheduleConfig = if (isStart) {
                                                        scheduleConfig.copy(startHour = newH)
                                                    } else {
                                                        scheduleConfig.copy(stopHour = newH)
                                                    }
                                                    app.prefsManager.saveScheduleConfig(scheduleConfig)
                                                    ScheduleManager.syncSchedule(context)
                                                }
                                            }
                                        }

                                        // Separator
                                        Text(
                                            text = ":",
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextMuted
                                        )

                                        // Minutes Stepper (step 5)
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "МИНУТЫ",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextMuted,
                                                letterSpacing = 0.8.sp
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                StepperButton(label = "—") {
                                                    val newM = (currentMin - 5 + 60) % 60
                                                    scheduleConfig = if (isStart) {
                                                        scheduleConfig.copy(startMinute = newM)
                                                    } else {
                                                        scheduleConfig.copy(stopMinute = newM)
                                                    }
                                                    app.prefsManager.saveScheduleConfig(scheduleConfig)
                                                    ScheduleManager.syncSchedule(context)
                                                }

                                                Text(
                                                    text = "%02d".format(currentMin),
                                                    fontSize = 22.sp,
                                                    fontWeight = FontWeight.Black,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = targetAccent,
                                                    modifier = Modifier.width(36.dp),
                                                    textAlign = TextAlign.Center
                                                )

                                                StepperButton(label = "+") {
                                                    val newM = (currentMin + 5) % 60
                                                    scheduleConfig = if (isStart) {
                                                        scheduleConfig.copy(startMinute = newM)
                                                    } else {
                                                        scheduleConfig.copy(stopMinute = newM)
                                                    }
                                                    app.prefsManager.saveScheduleConfig(scheduleConfig)
                                                    ScheduleManager.syncSchedule(context)
                                                }
                                            }
                                        }
                                    }

                                    // Quick minute presets
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(0, 15, 30, 45).forEach { m ->
                                            val isSelected = currentMin == m
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(32.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .border(
                                                        1.dp,
                                                        if (isSelected) targetAccent else AmoledBorder,
                                                        RoundedCornerShape(8.dp)
                                                    )
                                                    .background(Color.Transparent)
                                                    .springPress(onClick = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        scheduleConfig = if (isStart) {
                                                            scheduleConfig.copy(startMinute = m)
                                                        } else {
                                                            scheduleConfig.copy(stopMinute = m)
                                                        }
                                                        app.prefsManager.saveScheduleConfig(scheduleConfig)
                                                        ScheduleManager.syncSchedule(context)
                                                    }),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = ":%02d".format(m),
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = if (isSelected) targetAccent else TextMuted
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Days Mode Selection (Clean typography without parentheses)
                        Text(
                            text = stringResource(R.string.sleep_timer_working_days_label),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 0.6.sp
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val modes = listOf(
                                ScheduleDaysMode.EVERY_DAY to "Каждый день",
                                ScheduleDaysMode.WEEKDAYS to "Будни · Пн-Пт",
                                ScheduleDaysMode.WEEKENDS to "Выходные · Сб-Вс",
                                ScheduleDaysMode.CUSTOM to "Выбранные дни"
                            )
                            modes.chunked(2).forEach { rowModes ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    rowModes.forEach { (mode, title) ->
                                        PresetChip(
                                            title = title,
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
                            }
                        }

                        // Custom Day Selection (7 Outline Circular Buttons)
                        AnimatedVisibility(
                            visible = scheduleConfig.daysMode == ScheduleDaysMode.CUSTOM,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
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
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                            .clip(CircleShape)
                                            .border(
                                                1.dp,
                                                if (isDaySelected) accentColor else AmoledBorder,
                                                CircleShape
                                            )
                                            .background(Color.Transparent)
                                            .springPress(onClick = {
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
                                            }),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 12.sp,
                                            fontWeight = if (isDaySelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isDaySelected) accentColor else TextMuted
                                        )
                                    }
                                }
                            }
                        }

                        // Summary Info Badge (Zero-Popup Auto-Sync, no redundant save button)
                        val modeTitle = when (scheduleConfig.daysMode) {
                            ScheduleDaysMode.EVERY_DAY -> "Каждый день"
                            ScheduleDaysMode.WEEKDAYS -> "Будни · Пн-Пт"
                            ScheduleDaysMode.WEEKENDS -> "Выходные · Сб-Вс"
                            ScheduleDaysMode.CUSTOM -> {
                                if (scheduleConfig.customDays.size == 7) "Каждый день" else "Выбранные дни"
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = if (scheduleConfig.isEnabled) {
                                    "С ${scheduleConfig.formatStartTime()} до ${scheduleConfig.formatStopTime()} · $modeTitle"
                                } else {
                                    stringResource(R.string.sleep_timer_schedule_enable_hint)
                                },
                                fontSize = 12.sp,
                                color = TextWhite.copy(alpha = 0.85f),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Outline-Only Segmented Control with Green LED indicators for active states.
 */
@Composable
private fun TimerSegmentedControl(
    selectedTab: TimerDialogTab,
    onTabSelected: (TimerDialogTab) -> Unit,
    isTimerActive: Boolean,
    isScheduleActive: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Tab 1: Sleep Timer
        val isTimerSelected = selectedTab == TimerDialogTab.TIMER
        val timerBorderColor = when {
            isTimerSelected -> if (isTimerActive) ActiveGreenLed else accentColor
            else -> AmoledBorder
        }
        val timerTextColor = when {
            isTimerSelected -> if (isTimerActive) ActiveGreenLed else accentColor
            else -> TextMuted
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, timerBorderColor, RoundedCornerShape(12.dp))
                .background(Color.Transparent)
                .springPress(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onTabSelected(TimerDialogTab.TIMER)
                }),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isTimerActive) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(ActiveGreenLed)
                    )
                }
                Text(
                    text = "Таймер сна",
                    fontSize = 12.sp,
                    fontWeight = if (isTimerSelected) FontWeight.Bold else FontWeight.Medium,
                    color = timerTextColor,
                    letterSpacing = 0.5.sp
                )
            }
        }

        // Tab 2: Schedule
        val isScheduleSelected = selectedTab == TimerDialogTab.SCHEDULE
        val scheduleBorderColor = when {
            isScheduleSelected -> if (isScheduleActive) ActiveGreenLed else accentColor
            else -> AmoledBorder
        }
        val scheduleTextColor = when {
            isScheduleSelected -> if (isScheduleActive) ActiveGreenLed else accentColor
            else -> TextMuted
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, scheduleBorderColor, RoundedCornerShape(12.dp))
                .background(Color.Transparent)
                .springPress(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onTabSelected(TimerDialogTab.SCHEDULE)
                }),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isScheduleActive) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(ActiveGreenLed)
                    )
                }
                Text(
                    text = "Расписание",
                    fontSize = 12.sp,
                    fontWeight = if (isScheduleSelected) FontWeight.Bold else FontWeight.Medium,
                    color = scheduleTextColor,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

/**
 * Stepper button for inline hour/minute adjustments.
 */
@Composable
private fun StepperButton(
    label: String,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
            .background(Color.Transparent)
            .springPress(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextWhite
        )
    }
}

/**
 * Outline-Only Preset Chip.
 */
@Composable
private fun PresetChip(
    title: String,
    isSelected: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (isSelected) activeColor else AmoledBorder,
                RoundedCornerShape(12.dp)
            )
            .background(Color.Transparent)
            .springPress(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = 12.5.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) activeColor else TextWhite.copy(alpha = 0.85f),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Outline-Only Quick Extend Chip.
 */
@Composable
private fun QuickExtendChip(
    title: String,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, activeColor.copy(alpha = 0.50f), RoundedCornerShape(12.dp))
            .background(Color.Transparent)
            .springPress(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = activeColor,
            textAlign = TextAlign.Center
        )
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
