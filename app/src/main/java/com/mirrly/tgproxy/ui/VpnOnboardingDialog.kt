/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.icon.MirrlyIcon
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.adaptiveContainerWidth
import com.mirrly.tgproxy.ui.theme.fadingEdges
import com.mirrly.tgproxy.ui.theme.springPress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Вводное интерактивное руководство по VPN режиму (VpnOnboardingScreen).
 *
 * Архитектура и стандарты MirrlyUI:
 * - Основано на механике интерактивного обучения AiOnboardingScreen.kt.
 * - Полноэкранный оверлей Color.Black.copy(alpha = 0.88f) с поддержкой размытия подложки.
 * - Динамическая ветка обучения в зависимости от выбранного режима:
 *   - Простой режим (Рекомендуется): 4 шага, без технической рутины, 14 готовых серверов с автопингом, DNS и исключения.
 *   - Продвинутый режим (BETA): 8 шагов (Протоколы AWG/WG, генератор WARP, обфускация, Clash YAML, DNS, сплит-туннелирование, статус BETA).
 * - Сворачиваемые компактные карточки по умолчанию с анимированным шевроном (MirrlyExpandChevronBadge),
 *   привлекающим внимание в свернутом состоянии.
 * - Важные примечания о скорости и пинге строго размещаются внизу экрана шага.
 * - Полное исключение кнопок-действий, вызывающих наложение экранов (генератор WARP, системный файловый пикер импорта).
 * - Оформление текста по стандартам no-ai-slop: компактные тезисы, визуальные теги, списки маршрутов.
 */
@Composable
fun VpnOnboardingScreen(
    onDismiss: () -> Unit,
    onComplete: () -> Unit = onDismiss,
    onCreateProfileAuto: () -> Unit = {},
    onOpenImport: () -> Unit = {},
    isReplay: Boolean = false,
    accentColor: Color? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val prefs = remember { MirrlyApplication.instance.prefsManager }

    val protocolColors = LocalProtocolColors.current
    val activeAccentColor = accentColor ?: protocolColors.primary

    // Количество шагов: 3 в простом режиме
    val totalSteps = 3
    var currentStep by rememberSaveable {
        mutableIntStateOf(0)
    }

    // Таймер чтения: ровно 5000 мс при первом просмотре, 0 мс при повторе
    val stepDurationMs = 5000L
    var remainingMs by remember(currentStep, isReplay) {
        mutableStateOf(if (isReplay) 0L else stepDurationMs)
    }
    var isTimerFinished by remember(currentStep, isReplay) {
        mutableStateOf(isReplay)
    }

    LaunchedEffect(currentStep, isReplay) {
        if (isReplay) {
            remainingMs = 0L
            isTimerFinished = true
        } else {
            remainingMs = stepDurationMs
            isTimerFinished = false
            val startTime = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val rem = (stepDurationMs - elapsed).coerceAtLeast(0L)
                remainingMs = rem
                if (rem <= 0L) {
                    isTimerFinished = true
                    break
                }
                delay(40L)
            }
        }
    }

    val timerProgress = if (isReplay) 1f else ((stepDurationMs - remainingMs).toFloat() / stepDurationMs.toFloat()).coerceIn(0f, 1f)
    val remainingSeconds = if (isReplay || isTimerFinished) 0 else ((remainingMs + 999L) / 1000L).toInt().coerceIn(1, 5)

    // Всплывающее предупреждение в стиле MirrlyUI (Zero-Popup inline-баннер)
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var validationMessageKey by remember { mutableIntStateOf(0) }

    fun showWarning(msg: String) {
        validationMessage = msg
        validationMessageKey += 1
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    LaunchedEffect(validationMessageKey) {
        if (validationMessage != null) {
            delay(2800L)
            validationMessage = null
        }
    }

    // Анимация плавного выхода
    val exitProgress = remember { Animatable(0f) }
    var isExiting by remember { mutableStateOf(false) }

    fun executeExit(isCompleted: Boolean) {
        if (isExiting) return
        isExiting = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            try {
                exitProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                )
            } finally {
                if (isCompleted) {
                    prefs.setHasSeenVpnOnboarding(true)
                    onComplete()
                } else {
                    onDismiss()
                }
            }
        }
    }

    BackHandler {
        if (currentStep > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentStep -= 1
        } else {
            executeExit(isCompleted = false)
        }
    }

    val exitVal = exitProgress.value
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val safeBottomMargin = bottomInset + 18.dp

    // Валидация возможности перехода
    val canProceed = isReplay || isTimerFinished

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .graphicsLayer {
                alpha = (1f - exitVal).coerceIn(0f, 1f)
                scaleX = 1f + 0.04f * exitVal
                scaleY = 1f + 0.04f * exitVal
                translationY = -24.dp.toPx() * exitVal
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContainerWidth(540.dp)
                .align(Alignment.Center)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── ВЕРХНИЙ БЛОК: Заголовок, закрытие и индикатор шагов ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topInset + 14.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.vpn_onboard_header_title),
                            color = TextWhite,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.1.sp
                        )
                        Text(
                            text = stringResource(R.string.vpn_onboard_mode_simple),
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.50f))
                        ) {
                            Text(
                                text = "${currentStep + 1} / $totalSteps",
                                color = activeAccentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        // Кнопка закрытия
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .size(28.dp)
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    executeExit(isCompleted = false)
                                })
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                MirrlyIcon(
                                    id = R.drawable.ic_close,
                                    contentDescription = stringResource(R.string.action_close),
                                    tint = TextWhite.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // Индикаторы шагов с плавной морфинг-анимацией ширины
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (totalSteps > 5) 5.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until totalSteps) {
                        val isSelected = i == currentStep
                        val isPassed = i < currentStep
                        val barWidth by animateDpAsState(
                            targetValue = when {
                                isSelected -> if (totalSteps > 5) 26.dp else 36.dp
                                else -> if (totalSteps > 5) 8.dp else 12.dp
                            },
                            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                            label = "vpnStepBarWidth_$i"
                        )
                        val barColor by animateColorAsState(
                            targetValue = when {
                                isSelected -> activeAccentColor
                                isPassed -> activeAccentColor.copy(alpha = 0.50f)
                                else -> Color.White.copy(alpha = 0.25f)
                            },
                            animationSpec = tween(durationMillis = 300),
                            label = "vpnStepBarColor_$i"
                        )

                        Box(
                            modifier = Modifier
                                .height(4.dp)
                                .width(barWidth)
                                .clip(RoundedCornerShape(2.dp))
                                .background(barColor)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (i <= currentStep && !isExiting) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentStep = i
                                    }
                                }
                        )
                    }
                }
            }

            // ── СРЕДНИЙ БЛОК: Контент текущего шага ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .fadingEdges(topFadeHeight = 16.dp, bottomFadeHeight = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally(
                                initialOffsetX = { fullWidth -> (fullWidth * 0.30f).toInt() },
                                animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = tween(280))).togetherWith(
                                slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> (-fullWidth * 0.30f).toInt() },
                                    animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(200))
                            )
                        } else {
                            (slideInHorizontally(
                                initialOffsetX = { fullWidth -> (-fullWidth * 0.30f).toInt() },
                                animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = tween(280))).togetherWith(
                                slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> (fullWidth * 0.30f).toInt() },
                                    animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(200))
                            )
                        }
                    },
                    label = "vpnOnboardingStepTransition",
                    modifier = Modifier.fillMaxWidth()
                ) { step ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        when (step) {
                            0 -> StepSimpleServers(accentColor = activeAccentColor)
                            1 -> StepSimpleDnsAndRouting(accentColor = activeAccentColor)
                            2 -> StepSimpleQuickStart(accentColor = activeAccentColor)
                        }
                    }
                }
            }

            // ── НИЖНЯЯ ПАНЕЛЬ: Предупреждения, линия таймера и кнопки навигации ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = safeBottomMargin, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Всплывающий баннер ошибки валидации (Zero-Popup)
                AnimatedVisibility(
                    visible = validationMessage != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    validationMessage?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF1E1414),
                            border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.85f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color(0xFFEF5350))
                                )
                                Text(
                                    text = msg,
                                    color = Color(0xFFFFCDD2),
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Линия таймера ознакомления
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statusText = when {
                            isReplay -> stringResource(R.string.vpn_onboard_replay_status)
                            !isTimerFinished -> stringResource(R.string.vpn_onboard_timer_status, remainingSeconds)
                            else -> stringResource(R.string.vpn_onboard_completed_status)
                        }

                        Text(
                            text = statusText,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (canProceed) activeAccentColor else TextMuted
                        )

                        Text(
                            text = if (isReplay) "100%" else "${(timerProgress * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (canProceed) activeAccentColor else TextMuted
                        )
                    }

                    // Индикатор прогресса таймера
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(timerProgress)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(activeAccentColor)
                        )
                    }
                }

                // Кнопки навигации (НАЗАД / ДАЛЕЕ / НАЧАТЬ РАБОТУ)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentStep > 0) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .weight(0.40f)
                                .height(46.dp)
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentStep -= 1
                                })
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.vpn_onboard_btn_back),
                                    color = TextWhite.copy(alpha = 0.8f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    val isLastStep = currentStep == totalSteps - 1
                    val buttonBorderColor by animateColorAsState(
                        targetValue = if (canProceed) activeAccentColor else AmoledBorder,
                        animationSpec = tween(240),
                        label = "vpnProceedButtonBorder"
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, buttonBorderColor),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .springPress(onClick = {
                                if (!canProceed) {
                                    if (!isTimerFinished) {
                                        showWarning(context.getString(R.string.vpn_onboard_warn_wait_timer, remainingSeconds))
                                    }
                                    return@springPress
                                }

                                if (isLastStep) {
                                    executeExit(isCompleted = true)
                                } else {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentStep += 1
                                }
                            })
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = when {
                                    isLastStep -> stringResource(R.string.vpn_onboard_btn_start)
                                    !canProceed && !isTimerFinished -> stringResource(R.string.vpn_onboard_btn_reviewing, remainingSeconds)
                                    else -> stringResource(R.string.vpn_onboard_btn_next)
                                },
                                color = if (canProceed) activeAccentColor else TextMuted,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Совместимый фасад вызова для существующих экранов (HomeScreen, SettingsScreen).
 */
@Composable
fun VpnOnboardingDialog(
    onDismiss: () -> Unit,
    onCreateProfileAuto: () -> Unit = {},
    onOpenImport: () -> Unit = {},
    isReplay: Boolean = false,
    accentColor: Color? = null
) {
    VpnOnboardingScreen(
        onDismiss = onDismiss,
        onComplete = onDismiss,
        onCreateProfileAuto = onCreateProfileAuto,
        onOpenImport = onOpenImport,
        isReplay = isReplay,
        accentColor = accentColor
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// ПРОСТОЙ РЕЖИМ · ШАГ 1: ВСТРОЕННЫЕ СЕРВЕРЫ И АВТОПИНГ
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepSimpleServers(accentColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        VpnStepHeader(
            badge = stringResource(R.string.vpn_onboard_s2_simple_badge),
            title = stringResource(R.string.vpn_onboard_s2_simple_title),
            subtitle = stringResource(R.string.vpn_onboard_s2_simple_sub),
            accentColor = accentColor
        )

        // Карточка 1: 14 серверов (Свернута по умолчанию)
        VpnExpandableCard(
            title = stringResource(R.string.vpn_onboard_s2_simple_c1_title),
            summary = stringResource(R.string.vpn_onboard_s2_simple_c1_sum),
            accentColor = accentColor,
            initiallyExpanded = false
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.vpn_onboard_s2_simple_c1_desc),
                    fontSize = 11.5.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    lineHeight = 16.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VpnTechChip(stringResource(R.string.vpn_country_netherlands), accentColor)
                    VpnTechChip(stringResource(R.string.vpn_country_germany), accentColor)
                    VpnTechChip(stringResource(R.string.vpn_country_usa), accentColor)
                    VpnTechChip(stringResource(R.string.vpn_country_finland), accentColor)
                }
            }
        }

        // Карточка 2: Свои профили (Свернута по умолчанию)
        VpnExpandableCard(
            title = stringResource(R.string.vpn_onboard_s2_simple_c2_title),
            summary = stringResource(R.string.vpn_onboard_s2_simple_c2_sum),
            accentColor = accentColor,
            initiallyExpanded = false
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.vpn_onboard_s2_simple_c2_desc),
                    fontSize = 11.5.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    lineHeight = 16.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VpnTechChip(".conf", accentColor)
                    VpnTechChip("warp://", accentColor)
                    VpnTechChip(stringResource(R.string.vpn_onboard_s2_simple_c2_chip3), accentColor)
                }
            }
        }

        // Важное примечание о скорости и загруженности — СТРОГО СНИЗУ!
        VpnHighlightNoteCard(
            title = stringResource(R.string.vpn_onboard_s2_simple_note_title),
            note = stringResource(R.string.vpn_onboard_s2_simple_note_lead),
            detail = stringResource(R.string.vpn_onboard_s2_simple_note_detail),
            accentColor = accentColor
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ПРОСТОЙ РЕЖИМ · ШАГ 2: DNS И РАЗДЕЛЬНОЕ ТУННЕЛИРОВАНИЕ
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepSimpleDnsAndRouting(accentColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        VpnStepHeader(
            badge = stringResource(R.string.vpn_onboard_s3_simple_badge),
            title = stringResource(R.string.vpn_onboard_s3_simple_title),
            subtitle = stringResource(R.string.vpn_onboard_s3_simple_sub),
            accentColor = accentColor
        )

        // Карточка 1: Выбор DNS (Свернута по умолчанию с анимированным шевроном)
        VpnExpandableCard(
            title = stringResource(R.string.vpn_onboard_s3_simple_dns_title),
            summary = stringResource(R.string.vpn_onboard_s3_simple_dns_sum),
            accentColor = accentColor,
            initiallyExpanded = false
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.vpn_onboard_s3_simple_dns_intro),
                    fontSize = 11.5.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    lineHeight = 16.sp
                )
                VpnBulletItem(stringResource(R.string.vpn_onboard_s3_simple_dns_cf))
                VpnBulletItem(stringResource(R.string.vpn_onboard_s3_simple_dns_goog))
                VpnBulletItem(stringResource(R.string.vpn_onboard_s3_simple_dns_adg))
            }
        }

        // Карточка 2: Раздельное туннелирование (Свернута по умолчанию с анимированным шевроном)
        VpnExpandableCard(
            title = stringResource(R.string.vpn_onboard_s3_simple_split_title),
            summary = stringResource(R.string.vpn_onboard_s3_simple_split_sum),
            accentColor = accentColor,
            initiallyExpanded = false
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.vpn_onboard_s3_simple_split_intro),
                    fontSize = 11.5.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    lineHeight = 16.sp
                )

                VpnRouteBadge(
                    title = stringResource(R.string.vpn_onboard_s3_simple_split_direct_title),
                    subtitle = stringResource(R.string.vpn_onboard_s3_simple_split_direct_sub),
                    tag = stringResource(R.string.vpn_onboard_s3_simple_split_direct_tag),
                    tagColor = Color(0xFF66BB6A),
                    accentColor = accentColor
                )

                VpnRouteBadge(
                    title = stringResource(R.string.vpn_onboard_s3_simple_split_vpn_title),
                    subtitle = stringResource(R.string.vpn_onboard_s3_simple_split_vpn_sub),
                    tag = stringResource(R.string.vpn_onboard_s3_simple_split_vpn_tag),
                    tagColor = accentColor,
                    accentColor = accentColor
                )

                Text(
                    text = stringResource(R.string.vpn_onboard_s3_simple_split_tip),
                    fontSize = 11.sp,
                    color = TextMuted,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ПРОСТОЙ РЕЖИМ · ШАГ 3: БЫСТРЫЙ СТАРТ
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepSimpleQuickStart(accentColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        VpnStepHeader(
            badge = stringResource(R.string.vpn_onboard_s4_simple_badge),
            title = stringResource(R.string.vpn_onboard_s4_simple_title),
            subtitle = stringResource(R.string.vpn_onboard_s4_simple_sub),
            accentColor = accentColor
        )

        VpnInfoCard(
            title = stringResource(R.string.vpn_onboard_s4_simple_c1_title),
            description = stringResource(R.string.vpn_onboard_s4_simple_c1_desc)
        )

        VpnInfoCard(
            title = stringResource(R.string.vpn_onboard_s4_simple_c2_title),
            description = stringResource(R.string.vpn_onboard_s4_simple_c2_desc)
        )

        VpnHighlightNoteCard(
            title = stringResource(R.string.vpn_onboard_s4_simple_rule_title),
            note = stringResource(R.string.vpn_onboard_s4_simple_rule_lead),
            detail = stringResource(R.string.vpn_onboard_s4_simple_rule_desc),
            accentColor = accentColor
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ВСПОМОГАТЕЛЬНЫЕ КОМПОНЕНТЫ ОФОРМЛЕНИЯ В СТИЛЕ MIRRLYUI
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VpnStepHeader(
    badge: String,
    title: String,
    subtitle: String,
    accentColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, accentColor.copy(alpha = 0.60f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = badge,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = accentColor,
                letterSpacing = 0.8.sp
            )
        }
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = TextWhite
        )
        Text(
            text = subtitle,
            fontSize = 12.sp,
            color = TextMuted,
            lineHeight = 16.sp
        )
    }
}

/**
 * Анимированный шеврон-бейдж (Mirrly Icons standard):
 * - В свернутом состоянии мягко пульсирует по вертикали (bobbing / beckon) и прозрачности,
 *   ненавязчиво привлекая внимание пользователя.
 * - При раскрытии плавно разворачивается на 180 градусов и фиксируется в активном цвете темы.
 */
@Composable
private fun MirrlyExpandChevronBadge(
    isExpanded: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "expandChevronRotation"
    )

    // Анимация призыва к действию (beckon/приманка) в свернутом состоянии
    val infiniteTransition = rememberInfiniteTransition(label = "expandBeckonTransition")
    val beckonOffsetY by infiniteTransition.animateFloat(
        initialValue = -1.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "expandBeckonY"
    )
    val beckonAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "expandBeckonAlpha"
    )

    val currentOffsetY = if (isExpanded) 0f else beckonOffsetY
    val currentBorderAlpha = if (isExpanded) 0.80f else (0.35f * beckonAlpha)
    val currentTint = if (isExpanded) accentColor else accentColor.copy(alpha = (0.70f + 0.30f * beckonAlpha).coerceIn(0f, 1f))

    Box(
        modifier = modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                1.dp,
                accentColor.copy(alpha = currentBorderAlpha),
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        MirrlyIcon(
            id = R.drawable.ic_chevron_down,
            contentDescription = if (isExpanded) stringResource(R.string.ai_chat_expand_editor_resize_collapse) else stringResource(R.string.ai_chat_expand_editor_resize_expand),
            tint = currentTint,
            modifier = Modifier
                .size(15.dp)
                .graphicsLayer {
                    rotationZ = rotation
                    translationY = currentOffsetY
                }
        )
    }
}


/**
 * Сворачиваемая информационная карточка (VpnExpandableCard):
 * - По умолчанию свернута (initiallyExpanded = false).
 * - Оснащена привлекающим шевроном MirrlyExpandChevronBadge.
 */
@Composable
private fun VpnExpandableCard(
    title: String,
    summary: String,
    accentColor: Color,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var isExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (isExpanded) accentColor.copy(alpha = 0.60f) else AmoledBorder),
        modifier = Modifier
            .fillMaxWidth()
            .springPress(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                isExpanded = !isExpanded
            })
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        text = summary,
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }

                MirrlyExpandChevronBadge(
                    isExpanded = isExpanded,
                    accentColor = accentColor
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun VpnInfoCard(
    title: String,
    description: String
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, AmoledBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite
            )
            Text(
                text = description,
                fontSize = 11.5.sp,
                color = TextMuted,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun VpnHighlightNoteCard(
    title: String,
    note: String,
    detail: String,
    accentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.50f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = title,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = accentColor,
                letterSpacing = 0.8.sp
            )
            Text(
                text = note,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextWhite,
                lineHeight = 16.5.sp
            )
            Text(
                text = detail,
                fontSize = 11.sp,
                color = TextMuted,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun VpnTechChip(
    text: String,
    accentColor: Color = LocalProtocolColors.current.primary,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .background(accentColor.copy(alpha = 0.08f))
            .padding(horizontal = 7.dp, vertical = 2.5.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            color = accentColor
        )
    }
}

@Composable
private fun VpnRouteBadge(
    title: String,
    subtitle: String,
    tag: String,
    tagColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, AmoledBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Text(
                    text = subtitle,
                    fontSize = 10.5.sp,
                    color = TextMuted,
                    lineHeight = 14.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, tagColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = tag,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = tagColor
                )
            }
        }
    }
}

@Composable
private fun VpnBulletItem(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "•",
            color = TextWhite.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = text,
            fontSize = 11.5.sp,
            color = TextWhite.copy(alpha = 0.85f),
            lineHeight = 16.sp
        )
    }
}
