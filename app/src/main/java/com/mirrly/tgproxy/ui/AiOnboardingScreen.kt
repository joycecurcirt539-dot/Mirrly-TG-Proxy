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
 */

package com.mirrly.tgproxy.ui

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ai.AiKeyManager
import com.mirrly.tgproxy.ai.AiPrompts
import com.mirrly.tgproxy.service.cloudflare.CloudflareOAuthManager
import com.mirrly.tgproxy.service.cloudflare.ServerAuthState
import com.mirrly.tgproxy.ui.icon.MirrlyIcon
import com.mirrly.tgproxy.ui.theme.AmoledBackground
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import com.mirrly.tgproxy.ui.theme.Socks5Accent
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.VpnAccent
import com.mirrly.tgproxy.ui.theme.adaptiveContainerWidth
import com.mirrly.tgproxy.ui.theme.fadingEdges
import com.mirrly.tgproxy.ui.theme.lightSweep
import com.mirrly.tgproxy.ui.theme.springPress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Интерактивное вводное руководство Mirrly AI BYOK Engine (5 шагов).
 *
 * Архитектурные стандарты MirrlyUI и no-ai-slop:
 * - Полноэкранный оверлей Color.Black.copy(alpha = 0.88f) с аппаратным размытием подложки (rememberDialogBlurState).
 * - Сворачиваемые компактные карточки по образцу VpnOnboardingDialog с шевроном MirrlyExpandChevronBadge.
 * - Интерактивный процедурный Canvas-превью генератора векторных SVG-иконок кнопок.
 * - Переходы по внешним ссылкам строго через ExternalLinkConfirmDialog с размытием фона.
 * - Отсутствие искусственной нумерации «01 / 02 / 03» и декоративного ИИ-слопа.
 * - Честный таймер чтения: 5000 мс на шагах при первом просмотре, отключен при повторе (isReplay).
 * - Обязательные действия пользователя для разблокировки перехода по шагам.
 */
@Composable
fun AiOnboardingScreen(
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    isReplay: Boolean = false,
    accentColor: Color? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val prefs = remember { MirrlyApplication.instance.prefsManager }

    val protocolColors = LocalProtocolColors.current
    val activeAccentColor = accentColor ?: protocolColors.primary

    // Активация аппаратного размытия экрана под оверлеем руководства
    rememberDialogBlurState(blurRadiusPx = 60)

    val isReplayMode = isReplay
    val totalSteps = 5
    var currentStep by rememberSaveable { mutableIntStateOf(0) }

    // Локальные состояния конфигурации ИИ
    var selectedProvider by rememberSaveable { mutableStateOf<String?>(if (isReplayMode) prefs.getAiProvider() else null) }
    var selectedModel by rememberSaveable { mutableStateOf<String?>(if (isReplayMode) prefs.getAiModel() else null) }
    var promptMode by rememberSaveable { mutableStateOf(prefs.getAiSystemPromptMode()) }
    var geminiKey by remember { mutableStateOf(AiKeyManager.getApiKey(context)) }
    var openRouterKey by remember { mutableStateOf(AiKeyManager.getOpenRouterApiKey(context)) }
    var cfAccountId by remember { mutableStateOf(prefs.getCloudflareAccountId().orEmpty()) }
    var cfToken by remember { mutableStateOf(prefs.getCloudflareToken().orEmpty()) }

    // Контроллеры обязательных действий пользователя на шагах
    var hasPerformedStep2Action by rememberSaveable(selectedProvider) { mutableStateOf(isReplayMode) }
    var hasInteractedStep4 by rememberSaveable { mutableStateOf(isReplayMode) }
    var hasInteractedStep5 by rememberSaveable { mutableStateOf(isReplayMode) }

    // Состояние подтверждения внешнего перехода (MirrlyUI External Link Warning Contract)
    var pendingExternalUrl by remember { mutableStateOf<String?>(null) }
    if (pendingExternalUrl != null) {
        ExternalLinkConfirmDialog(
            url = pendingExternalUrl!!,
            onDismiss = { pendingExternalUrl = null },
            onConfirmed = {
                hasPerformedStep2Action = true
                pendingExternalUrl = null
            }
        )
    }

    // Отслеживание авторизации через Cloudflare OAuth
    val cfAuthState by CloudflareOAuthManager.authState.collectAsState()
    LaunchedEffect(cfAuthState) {
        if (cfAuthState is ServerAuthState.Success) {
            val tokens = (cfAuthState as ServerAuthState.Success).tokens
            scope.launch {
                val ok = CloudflareOAuthManager.finalizeSuccessfulAuth(tokens, prefs)
                if (ok) {
                    cfAccountId = prefs.getCloudflareAccountId().orEmpty()
                    cfToken = prefs.getCloudflareToken().orEmpty()
                    hasPerformedStep2Action = true
                }
            }
        }
    }

    // Честный таймер чтения: ровно 5000 мс (wall-clock)
    val stepDurationMs = 5000L
    var remainingMs by remember(currentStep, isReplayMode) {
        mutableStateOf(if (isReplayMode) 0L else stepDurationMs)
    }
    var isTimerFinished by remember(currentStep, isReplayMode) {
        mutableStateOf(isReplayMode)
    }

    LaunchedEffect(currentStep, isReplayMode) {
        if (isReplayMode) {
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

    val timerProgress = if (isReplayMode) 1f else ((stepDurationMs - remainingMs).toFloat() / stepDurationMs.toFloat()).coerceIn(0f, 1f)
    val remainingSeconds = if (isReplayMode || isTimerFinished) 0 else ((remainingMs + 999L) / 1000L).toInt().coerceIn(1, 5)

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
        CloudflareOAuthManager.cancelActiveListener()
        scope.launch {
            try {
                exitProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                )
            } finally {
                if (isCompleted) {
                    selectedProvider?.let { prefs.setAiProvider(it) }
                    selectedModel?.let { prefs.setAiModel(it) }
                    prefs.setAiSystemPromptMode(promptMode)
                    if (geminiKey.isNotBlank()) AiKeyManager.saveApiKey(context, geminiKey)
                    if (openRouterKey.isNotBlank()) AiKeyManager.saveOpenRouterApiKey(context, openRouterKey)
                    if (cfAccountId.isNotBlank()) prefs.setCloudflareAccountId(cfAccountId)
                    if (cfToken.isNotBlank()) prefs.setCloudflareToken(cfToken)
                    prefs.setHasSeenAiOnboarding(true)
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

    // Валидация готовности к переходу
    val canProceed = isReplayMode || (isTimerFinished && when (currentStep) {
        0 -> selectedProvider != null
        1 -> hasPerformedStep2Action
        2 -> selectedModel != null
        3 -> hasInteractedStep4
        4 -> hasInteractedStep5
        else -> true
    })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBackground)
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
                            text = "ВВОДНОЕ РУКОВОДСТВО ПО ИИ",
                            color = TextWhite,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.1.sp
                        )
                        Text(
                            text = "BYOK Engine · Автономная диагностика",
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
                                    contentDescription = "Закрыть",
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until totalSteps) {
                        val isSelected = i == currentStep
                        val isPassed = i < currentStep
                        val barWidth by animateDpAsState(
                            targetValue = if (isSelected) 36.dp else 12.dp,
                            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                            label = "aiStepBarWidth_$i"
                        )
                        val barColor by animateColorAsState(
                            targetValue = when {
                                isSelected -> activeAccentColor
                                isPassed -> activeAccentColor.copy(alpha = 0.50f)
                                else -> Color.White.copy(alpha = 0.25f)
                            },
                            animationSpec = tween(durationMillis = 300),
                            label = "aiStepBarColor_$i"
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
                    label = "aiOnboardingStepTransition",
                    modifier = Modifier.fillMaxWidth()
                ) { step ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        when (step) {
                            0 -> StepProviderSelection(
                                selectedProvider = selectedProvider,
                                accentColor = activeAccentColor,
                                onSelectProvider = { prov ->
                                    selectedProvider = prov
                                    prefs.setAiProvider(prov)
                                    selectedModel = null
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            )

                            1 -> StepConnectionInstructions(
                                selectedProvider = selectedProvider,
                                accentColor = activeAccentColor,
                                onRequestOpenUrl = { url -> pendingExternalUrl = url },
                                onActionCompleted = { hasPerformedStep2Action = true }
                            )

                            2 -> StepCredentialsAndModel(
                                selectedProvider = selectedProvider ?: "Gemini",
                                selectedModel = selectedModel,
                                geminiKey = geminiKey,
                                openRouterKey = openRouterKey,
                                cfAccountId = cfAccountId,
                                cfToken = cfToken,
                                accentColor = activeAccentColor,
                                onGeminiKeyChanged = { key ->
                                    geminiKey = key
                                    AiKeyManager.saveApiKey(context, key)
                                },
                                onOpenRouterKeyChanged = { key ->
                                    openRouterKey = key
                                    AiKeyManager.saveOpenRouterApiKey(context, key)
                                },
                                onCfAccountIdChanged = { id ->
                                    cfAccountId = id
                                    prefs.setCloudflareAccountId(id)
                                },
                                onCfTokenChanged = { tok ->
                                    cfToken = tok
                                    prefs.setCloudflareToken(tok)
                                },
                                onModelSelected = { model ->
                                    selectedModel = model
                                    prefs.setAiModel(model)
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            )

                            3 -> StepSystemPrompts(
                                currentMode = promptMode,
                                accentColor = activeAccentColor,
                                onModeSelected = { mode ->
                                    promptMode = mode
                                    prefs.setAiSystemPromptMode(mode)
                                    hasInteractedStep4 = true
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                onAccordionInteracted = {
                                    hasInteractedStep4 = true
                                }
                            )

                            4 -> StepFeaturesAndTools(
                                accentColor = activeAccentColor,
                                onAccordionInteracted = {
                                    hasInteractedStep5 = true
                                }
                            )
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
                                    lineHeight = 15.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // Индикатор честного таймера чтения (5 секунд)
                if (!isReplayMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(AmoledBorder.copy(alpha = 0.40f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = timerProgress)
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
                                    text = "НАЗАД",
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
                        label = "aiProceedButtonBorder"
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
                                    when {
                                        currentStep == 0 && selectedProvider == null ->
                                            showWarning("Выберите провайдера нейросети для продолжения")
                                        currentStep == 1 && !hasPerformedStep2Action ->
                                            showWarning("Ознакомьтесь с этапом или откройте ссылку портала")
                                        currentStep == 2 && selectedModel == null ->
                                            showWarning("Выберите активную модель для продолжения")
                                        currentStep == 3 && !hasInteractedStep4 ->
                                            showWarning("Раскройте описание директивы для ознакомления")
                                        currentStep == 4 && !hasInteractedStep5 ->
                                            showWarning("Раскройте описание инструмента для ознакомления")
                                        !isTimerFinished ->
                                            showWarning("Ознакомьтесь с материалом шага (осталось $remainingSeconds с)")
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
                                    isLastStep -> "НАЧАТЬ РАБОТУ С ИИ"
                                    !canProceed && !isTimerFinished -> "ОЗНАКОМЛЕНИЕ · $remainingSeconds С"
                                    currentStep == 0 && selectedProvider == null -> "ВЫБЕРИТЕ ПРОВАЙДЕРА"
                                    currentStep == 2 && selectedModel == null -> "ВЫБЕРИТЕ МОДЕЛЬ"
                                    else -> "ДАЛЕЕ"
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

// ─────────────────────────────────────────────────────────────────────────────
// ШАГ 1: ВЫБОР ПРОВАЙДЕРА НЕЙРОСЕТИ (СВОРАЧИВАЕМЫЕ КАРТОЧКИ)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepProviderSelection(
    selectedProvider: String?,
    accentColor: Color,
    onSelectProvider: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AiStepHeader(
            badge = "ШАГ 1 · ПРОВАЙДЕР НЕЙРОСЕТИ",
            title = "Выберите провайдера интеллекта",
            subtitle = "Прямой доступ BYOK без подписок, посредников и внешних комиссий",
            accentColor = accentColor
        )

        // 1. Google Gemini (Рекомендуется)
        AiProviderOptionCard(
            name = "Google Gemini",
            tagline = "Бесплатные лимиты Google AI Studio · 15 RPM · 1M Context",
            badgeText = "РЕКОМЕНДУЕТСЯ",
            badgeColor = accentColor,
            isSelected = (selectedProvider == "Gemini"),
            accentColor = accentColor,
            onSelect = { onSelectProvider("Gemini") }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Идеальный выбор для приложения: бесплатные квоты Google AI Studio, мгновенный отклик, системный вызов инструментов Function Calling и генератор векторных SVG-иконок кнопок.",
                    fontSize = 11.5.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    lineHeight = 16.sp
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AiTechChip("gemini-3.6-flash", accentColor)
                    AiTechChip("Function Calling", accentColor)
                    AiTechChip("Генерация SVG", accentColor)
                    AiTechChip("15 RPM Free", accentColor)
                }
            }
        }

        // 2. Cloudflare Workers AI
        AiProviderOptionCard(
            name = "Cloudflare Workers AI",
            tagline = "Edge-инференс в 300+ городах · Вход через OAuth в 1 клик",
            badgeText = "EDGE ИНФЕРЕНС",
            badgeColor = Socks5Accent,
            isSelected = (selectedProvider == "Cloudflare"),
            accentColor = accentColor,
            onSelect = { onSelectProvider("Cloudflare") }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Автономный серверлесс-инференс на глобальной сети Cloudflare. Поддерживает быстрый вход через OAuth или ручной токен Workers AI Read. 10 000 бесплатных Neurons в день.",
                    fontSize = 11.5.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    lineHeight = 16.sp
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AiTechChip("Llama 3.3 70B", Socks5Accent)
                    AiTechChip("DeepSeek R1", Socks5Accent)
                    AiTechChip("OAuth вход", Socks5Accent)
                }
            }
        }

        // 3. OpenRouter
        AiProviderOptionCard(
            name = "OpenRouter",
            tagline = "Единый шлюз ко всем мировым моделям · Ключ sk-or-v1-...",
            badgeText = "МУЛЬТИ-ШЛЮЗ",
            badgeColor = VpnAccent,
            isSelected = (selectedProvider == "OpenRouter"),
            accentColor = accentColor,
            onSelect = { onSelectProvider("OpenRouter") }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Единый доступ к сотням моделей: DeepSeek V3, Claude 3.5 Sonnet, Mistral Large и Qwen по одному API-ключу. Модели с пометкой :free работают полностью бесплатно.",
                    fontSize = 11.5.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    lineHeight = 16.sp
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AiTechChip("DeepSeek V3", VpnAccent)
                    AiTechChip("Claude 3.5 Sonnet", VpnAccent)
                    AiTechChip("Модели :free", VpnAccent)
                }
            }
        }

        // Важное примечание об архитектуре BYOK
        AiHighlightNoteCard(
            title = "ПРИНЦИП BYOK (BRING YOUR OWN KEY)",
            note = "Приложение не берет плату за использование ИИ и не использует сторонние прокси-серверы.",
            detail = "Все сетевые запросы отправляются напрямую с вашего устройства в официальный API выбранного провайдера. Ваши личные ключи шифруются аппаратно в Keystore.",
            accentColor = accentColor
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ШАГ 2: ИНСТРУКЦИЯ ПО ПОДКЛЮЧЕНИЮ И ПОЛУЧЕНИЮ КЛЮЧА
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepConnectionInstructions(
    selectedProvider: String?,
    accentColor: Color,
    onRequestOpenUrl: (String) -> Unit,
    onActionCompleted: () -> Unit
) {
    val context = LocalContext.current
    val prov = selectedProvider ?: "Gemini"

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AiStepHeader(
            badge = "ШАГ 2 · ПОДКЛЮЧЕНИЕ И КЛЮЧИ",
            title = "Регламент получения доступа для $prov",
            subtitle = "Нажмите на карточку этапа для раскрытия подробностей и безопасного перехода",
            accentColor = accentColor
        )

        when (prov) {
            "Gemini" -> {
                AiExpandableCard(
                    title = "1. Личный кабинет Google AI Studio",
                    summary = "Получение бесплатного ключа формата AIzaSy... за 30 секунд",
                    accentColor = accentColor,
                    initiallyExpanded = true
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Официальный портал Google AI Studio предоставляет бесплатный персональный ключ. Перейдите по ссылке, авторизуйтесь в Google и нажмите «Create API Key».",
                            fontSize = 11.5.sp,
                            color = TextWhite.copy(alpha = 0.85f),
                            lineHeight = 16.sp
                        )

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.70f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .springPress(onClick = {
                                    onRequestOpenUrl("https://aistudio.google.com/app/apikey")
                                })
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "ОТКРЫТЬ GOOGLE AI STUDIO",
                                    color = accentColor,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }

                AiExpandableCard(
                    title = "2. Бесплатные квоты и лимиты",
                    summary = "15 запросов в минуту (RPM) и контекст 1 млн токенов",
                    accentColor = accentColor,
                    initiallyExpanded = false
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Ключ Google AI Studio полностью бесплатен и не требует привязки банковских карт. 15 запросов в минуту с избытком хватает для десятков сетевых диагностик и генерации векторных иконок каждый день.",
                            fontSize = 11.5.sp,
                            color = TextWhite.copy(alpha = 0.85f),
                            lineHeight = 16.sp
                        )
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AiTechChip("15 RPM", accentColor)
                            AiTechChip("1M Tokens", accentColor)
                            AiTechChip("Без карт", accentColor)
                        }
                    }
                }

                AiExpandableCard(
                    title = "3. Доступность из РФ и SmartDNS",
                    summary = "Автоматический обход региональных блокировок через туннель",
                    accentColor = accentColor,
                    initiallyExpanded = false
                ) {
                    Text(
                        text = "Если Google AI Studio недоступен в вашей сети, включите встроенный VPN или MTProto-прокси в Mirrly TG Proxy. Защищенный DoH SmartDNS гарантирует надежный инференс без сбоев.",
                        fontSize = 11.5.sp,
                        color = TextWhite.copy(alpha = 0.85f),
                        lineHeight = 16.sp
                    )
                }
            }

            "Cloudflare" -> {
                AiExpandableCard(
                    title = "1. Авторизация через OAuth",
                    summary = "Вход в учетную запись Cloudflare в одно касание",
                    accentColor = Socks5Accent,
                    initiallyExpanded = true
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Самый быстрый способ: нажмите кнопку ниже. Откроется системный браузер для подтверждения доступа Workers AI Read. Токен применится автоматически.",
                            fontSize = 11.5.sp,
                            color = TextWhite.copy(alpha = 0.85f),
                            lineHeight = 16.sp
                        )

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Socks5Accent.copy(alpha = 0.70f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .springPress(onClick = {
                                    CloudflareOAuthManager.startAuthSession(context)
                                })
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "ВОЙТИ ЧЕРЕЗ CLOUDFLARE (OAUTH)",
                                    color = Socks5Accent,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }

                AiExpandableCard(
                    title = "2. Ручной токен API (dash.cloudflare.com)",
                    summary = "Копирование Account ID и токена Workers AI Read",
                    accentColor = Socks5Accent,
                    initiallyExpanded = false
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Для ручной настройки откройте Cloudflare Dashboard, скопируйте Account ID на главной странице и сгенерируйте токен с шаблоном Workers AI Read.",
                            fontSize = 11.5.sp,
                            color = TextWhite.copy(alpha = 0.85f),
                            lineHeight = 16.sp
                        )

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .springPress(onClick = {
                                    onRequestOpenUrl("https://dash.cloudflare.com")
                                })
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "ОТКРЫТЬ DASH.CLOUDFLARE.COM",
                                    color = TextWhite.copy(alpha = 0.85f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                AiExpandableCard(
                    title = "3. Бесплатный ежедневный лимит Neurons",
                    summary = "10 000 единиц Neurons в сутки на бесплатном тарифе",
                    accentColor = Socks5Accent,
                    initiallyExpanded = false
                ) {
                    Text(
                        text = "Cloudflare ежедневно начисляет 10 000 Neurons бесплатно. Этого достаточно для 50+ сессий анализа логов или ответов на вопросы по настройке.",
                        fontSize = 11.5.sp,
                        color = TextWhite.copy(alpha = 0.85f),
                        lineHeight = 16.sp
                    )
                }
            }

            "OpenRouter" -> {
                AiExpandableCard(
                    title = "1. Личный кабинет openrouter.ai/keys",
                    summary = "Создание универсального ключа sk-or-v1-...",
                    accentColor = VpnAccent,
                    initiallyExpanded = true
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "В разделе Keys нажмите «Create Key» и скопируйте созданный ключ. Он открывает доступ ко всем открытым и коммерческим моделям мира.",
                            fontSize = 11.5.sp,
                            color = TextWhite.copy(alpha = 0.85f),
                            lineHeight = 16.sp
                        )

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, VpnAccent.copy(alpha = 0.70f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .springPress(onClick = {
                                    onRequestOpenUrl("https://openrouter.ai/keys")
                                })
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "ОТКРЫТЬ OPENROUTER.AI/KEYS",
                                    color = VpnAccent,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }

                AiExpandableCard(
                    title = "2. Бесплатные модели с пометкой :free",
                    summary = "DeepSeek R1, DeepSeek V3, Llama 3.3 без пополнения баланса",
                    accentColor = VpnAccent,
                    initiallyExpanded = false
                ) {
                    Text(
                        text = "Модели с пометкой :free (например, deepseek/deepseek-r1:free) работают бесплатно без привязки карт и без депозита на счете.",
                        fontSize = 11.5.sp,
                        color = TextWhite.copy(alpha = 0.85f),
                        lineHeight = 16.sp
                    )
                }

                AiExpandableCard(
                    title = "3. Платные флагманы (Claude 3.5 Sonnet)",
                    summary = "Посекундная тарификация за токены без ежемесячных подписок",
                    accentColor = VpnAccent,
                    initiallyExpanded = false
                ) {
                    Text(
                        text = "При желании можно пополнить баланс на $5 и использовать Claude 3.5 Sonnet или GPT-4o с микро-тарификацией за каждый сгенерированный токен.",
                        fontSize = 11.5.sp,
                        color = TextWhite.copy(alpha = 0.85f),
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Нижнее примечание о безопасности ключей
        AiHighlightNoteCard(
            title = "БЕЗОПАСНОСТЬ И ЛОКАЛЬНОЕ ХРАНЕНИЕ КЛЮЧЕЙ",
            note = "Ключи сохраняются исключительно в зашифрованном хранилище EncryptedSharedPreferences.",
            detail = "Приложение никогда не передает ваши учетные данные третьим лицам. Исходный код сетевых клиентов полностью открыт под лицензией GNU GPL v3+.",
            accentColor = accentColor
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ШАГ 3: НАСТРОЙКА УЧЕТНЫХ ДАННЫХ И ВЫБОР МОДЕЛИ
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepCredentialsAndModel(
    selectedProvider: String,
    selectedModel: String?,
    geminiKey: String,
    openRouterKey: String,
    cfAccountId: String,
    cfToken: String,
    accentColor: Color,
    onGeminiKeyChanged: (String) -> Unit,
    onOpenRouterKeyChanged: (String) -> Unit,
    onCfAccountIdChanged: (String) -> Unit,
    onCfTokenChanged: (String) -> Unit,
    onModelSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }

    var isGeminiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var isCfTokenVisible by rememberSaveable { mutableStateOf(false) }
    var isOpenRouterKeyVisible by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AiStepHeader(
            badge = "ШАГ 3 · КЛЮЧ И ВЫБОР МОДЕЛИ",
            title = "Настройте доступ и рабочую модель",
            subtitle = "Введите API-ключ и выберите нейросеть под ваши задачи",
            accentColor = accentColor
        )

        // Блок ввода ключа
        when (selectedProvider) {
            "Gemini" -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, if (geminiKey.isNotBlank()) accentColor.copy(alpha = 0.6f) else AmoledBorder),
                    modifier = Modifier.fillMaxWidth()
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
                            Text(
                                text = "Google Gemini API Key",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.60f)),
                                modifier = Modifier.springPress(onClick = {
                                    val clip = clipboard?.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val text = clip.getItemAt(0).text?.toString()?.trim().orEmpty()
                                        if (text.isNotBlank()) onGeminiKeyChanged(text)
                                    }
                                })
                            ) {
                                Text(
                                    text = "ВСТАВИТЬ",
                                    color = accentColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = geminiKey,
                            onValueChange = onGeminiKeyChanged,
                            placeholder = { Text("Вставьте ключ AIzaSy...", fontSize = 11.5.sp, color = TextMuted) },
                            singleLine = true,
                            visualTransformation = if (isGeminiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(
                                    onClick = { isGeminiKeyVisible = !isGeminiKeyVisible },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    MirrlyIcon(
                                        id = if (isGeminiKeyVisible) R.drawable.ic_eye else R.drawable.ic_eye_slash,
                                        contentDescription = "Видимость ключа",
                                        tint = if (isGeminiKeyVisible) accentColor else TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = AmoledBorder,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = if (geminiKey.isNotBlank()) "Ключ сохранен в локальном защищенном хранилище" else "Ключ можно ввести сейчас или позже в настройках ИИ",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (geminiKey.isNotBlank()) accentColor else TextMuted
                        )
                    }
                }
            }

            "Cloudflare" -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Cloudflare Account ID & Token",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )

                        OutlinedTextField(
                            value = cfAccountId,
                            onValueChange = onCfAccountIdChanged,
                            placeholder = { Text("Cloudflare Account ID", fontSize = 11.5.sp, color = TextMuted) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Socks5Accent,
                                unfocusedBorderColor = AmoledBorder,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = cfToken,
                            onValueChange = onCfTokenChanged,
                            placeholder = { Text("API Token (Workers AI Read)", fontSize = 11.5.sp, color = TextMuted) },
                            singleLine = true,
                            visualTransformation = if (isCfTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(
                                    onClick = { isCfTokenVisible = !isCfTokenVisible },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    MirrlyIcon(
                                        id = if (isCfTokenVisible) R.drawable.ic_eye else R.drawable.ic_eye_slash,
                                        contentDescription = "Видимость токена",
                                        tint = if (isCfTokenVisible) Socks5Accent else TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Socks5Accent,
                                unfocusedBorderColor = AmoledBorder,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            "OpenRouter" -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, if (openRouterKey.isNotBlank()) VpnAccent.copy(alpha = 0.6f) else AmoledBorder),
                    modifier = Modifier.fillMaxWidth()
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
                            Text(
                                text = "OpenRouter API Key",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, VpnAccent.copy(alpha = 0.60f)),
                                modifier = Modifier.springPress(onClick = {
                                    val clip = clipboard?.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val text = clip.getItemAt(0).text?.toString()?.trim().orEmpty()
                                        if (text.isNotBlank()) onOpenRouterKeyChanged(text)
                                    }
                                })
                            ) {
                                Text(
                                    text = "ВСТАВИТЬ",
                                    color = VpnAccent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = openRouterKey,
                            onValueChange = onOpenRouterKeyChanged,
                            placeholder = { Text("Вставьте sk-or-v1-...", fontSize = 11.5.sp, color = TextMuted) },
                            singleLine = true,
                            visualTransformation = if (isOpenRouterKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(
                                    onClick = { isOpenRouterKeyVisible = !isOpenRouterKeyVisible },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    MirrlyIcon(
                                        id = if (isOpenRouterKeyVisible) R.drawable.ic_eye else R.drawable.ic_eye_slash,
                                        contentDescription = "Видимость ключа",
                                        tint = if (isOpenRouterKeyVisible) VpnAccent else TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VpnAccent,
                                unfocusedBorderColor = AmoledBorder,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Селектор моделей (компактный список с автовыбором)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "МОДЕЛИ ДЛЯ $selectedProvider",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = accentColor
            )

            val modelDetails = when (selectedProvider) {
                "Cloudflare" -> listOf(
                    OnboardingModelDetail(
                        id = "@cf/meta/llama-3.3-70b-instruct",
                        shortName = "Llama 3.3 70B",
                        badge = "ФЛАГМАН 70B",
                        badgeColor = accentColor,
                        tierLabel = "Максимальный интеллект · Универсальная",
                        description = "Флагманская 70B модель Meta на серверах Cloudflare. Комплексный технический анализ сетевых сокетов, сопоставление трафика и глубокие инженерные выкладки.",
                        bestFor = "Анализ сетевых логов, мониторинг отказов и диагностика."
                    ),
                    OnboardingModelDetail(
                        id = "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b",
                        shortName = "DeepSeek R1 32B",
                        badge = "REASONING",
                        badgeColor = Socks5Accent,
                        tierLabel = "Экстремальная логика · Пошаговые рассуждения",
                        description = "Модель глубоких рассуждений (Chain-of-Thought). Долго и тщательно обдумывает проблему, выявляя скрытые сетевые аномалии и блокировки ТСПУ.",
                        bestFor = "Сложные сетевые инциденты и отладка туннелей."
                    ),
                    OnboardingModelDetail(
                        id = "@cf/qwen/qwen2.5-72b-instruct",
                        shortName = "Qwen 2.5 72B",
                        badge = "КОД И SVG",
                        badgeColor = VpnAccent,
                        tierLabel = "Высокий интеллект · Программирование",
                        description = "Модель 72B от Alibaba, специально обученная на коде. Безупречно генерирует векторные pathData для SVG-иконок кнопок.",
                        bestFor = "Генератор векторных SVG-иконок для интерфейса."
                    ),
                    OnboardingModelDetail(
                        id = "@cf/meta/llama-3.1-8b-instruct",
                        shortName = "Llama 3.1 8B",
                        badge = "БЫСТРАЯ 8B",
                        badgeColor = TextMuted,
                        tierLabel = "Базовый интеллект · Скоростная",
                        description = "Компактная легковесная модель. Быстро генерирует ответ при минимальной задержке первого токена.",
                        bestFor = "Быстрые повседневные вопросы и справки."
                    )
                )

                "OpenRouter" -> listOf(
                    OnboardingModelDetail(
                        id = "deepseek/deepseek-r1",
                        shortName = "DeepSeek R1",
                        badge = "REASONING",
                        badgeColor = accentColor,
                        tierLabel = "Самая умная · Экстремальная логика",
                        description = "Передовая открытая reasoning-модель мирового класса. Пошаговый математический анализ каждого аспекта сетевого стека.",
                        bestFor = "Глубокий аудит сетевого стека и анализ дампов."
                    ),
                    OnboardingModelDetail(
                        id = "deepseek/deepseek-chat",
                        shortName = "DeepSeek V3",
                        badge = "УНИВЕРСАЛЬНАЯ V3",
                        badgeColor = accentColor,
                        tierLabel = "Высочайший интеллект · Быстрая",
                        description = "Полноразмерная модель DeepSeek V3. Высочайший интеллект, отличный русский язык и молниеносная скорость генерации.",
                        bestFor = "Рекомендуется по умолчанию для диалогов и настройки прокси."
                    ),
                    OnboardingModelDetail(
                        id = "anthropic/claude-3.5-sonnet",
                        shortName = "Claude 3.5 Sonnet",
                        badge = "ЭТАЛОН ТОЧНОСТИ",
                        badgeColor = VpnAccent,
                        tierLabel = "Премиальный интеллект",
                        description = "Мировой эталон точности следования системным инструкциям и генерации чистой геометрии SVG.",
                        bestFor = "Векторные SVG-иконки и сложное интервью."
                    )
                )

                else -> listOf(
                    OnboardingModelDetail(
                        id = "gemini-3.6-flash",
                        shortName = "Gemini 3.6 Flash",
                        badge = "ФЛАГМАН 2026",
                        badgeColor = accentColor,
                        tierLabel = "Самая умная · Максимальный интеллект",
                        description = "Флагманская модель Google 2026 года. Глубокое понимание MTProto, SOCKS5 и Cloudflare WSS, безупречно вызывает инструменты Function Calling.",
                        bestFor = "Рекомендуется по умолчанию: автономная диагностика сокетов и генерация иконок."
                    ),
                    OnboardingModelDetail(
                        id = "gemini-3.5-flash",
                        shortName = "Gemini 3.5 Flash",
                        badge = "СБАЛАНСИРОВАННАЯ",
                        badgeColor = accentColor.copy(alpha = 0.8f),
                        tierLabel = "Высокий интеллект · Надежная",
                        description = "Рабочая модель с отличным балансом между глубиной анализа и задержкой ответа.",
                        bestFor = "Анализ системных логов и мониторинг сбоев."
                    ),
                    OnboardingModelDetail(
                        id = "gemini-2.5-flash",
                        shortName = "Gemini 2.5 Flash",
                        badge = "УЛЬТРАБЫСТРАЯ",
                        badgeColor = Socks5Accent,
                        tierLabel = "Высокая скорость · Мгновенный отклик",
                        description = "Сверхбыстрая проверенная модель. Минимальная задержка вывода первого токена.",
                        bestFor = "Мгновенные ответы и быстрые справки."
                    ),
                    OnboardingModelDetail(
                        id = "gemini-2.5-flash-lite",
                        shortName = "Gemini 2.5 Lite",
                        badge = "ЭКОНОМИЧНАЯ",
                        badgeColor = TextMuted,
                        tierLabel = "Базовый интеллект · Легкая",
                        description = "Облегченная версия с минимальным расходом квоты. Подходит для простых запросов.",
                        bestFor = "Экономия лимитов запросов."
                    )
                )
            }

            modelDetails.forEach { model ->
                val isSelected = (selectedModel == model.id)
                AiModelOptionCard(
                    model = model,
                    isSelected = isSelected,
                    accentColor = accentColor,
                    onSelect = { onModelSelected(model.id) }
                )
            }
        }

        // Нижнее примечание с рекомендацией
        AiHighlightNoteCard(
            title = "РЕКОМЕНДАЦИЯ ПО ВЫБОРУ МОДЕЛИ",
            note = "Для стабильной работы выбирайте Gemini 3.6 Flash или DeepSeek V3.",
            detail = "Они обладают минимальной задержкой отклика и оптимизированы для глубокого разбора сетевых логов и генерации векторных иконок кнопок.",
            accentColor = accentColor
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ШАГ 4: СИСТЕМНЫЕ ИНСТРУКЦИИ (ПРОМПТЫ И РОЛИ)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepSystemPrompts(
    currentMode: String,
    accentColor: Color,
    onModeSelected: (String) -> Unit,
    onAccordionInteracted: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AiStepHeader(
            badge = "ШАГ 4 · СИСТЕМНЫЕ РОЛИ",
            title = "Выберите специализацию ассистента",
            subtitle = "Каждый режим задает фокус внимания и тон ответов нейросети",
            accentColor = accentColor
        )

        // 1. Диагностический режим
        AiPromptOptionCard(
            mode = AiPrompts.MODE_DIAGNOSTIC,
            title = "Диагностический режим",
            badge = "ИНЖЕНЕР СЕТИ",
            badgeColor = accentColor,
            tagline = "Анализ логов, сокетов, ТСПУ и генерация векторных иконок",
            fullPromptText = "«Ты — встроенный технический диагност Mirrly TG Proxy. При любых вопросах о сбоях ОБЯЗАТЕЛЬНО вызови диагностические инструменты Function Calling: get_service_status(), get_network_health(), get_recent_failure_logs() или test_uplink_connection(). Формулируй ответы строго по делу, без эмодзи и маркетинговых штампов. Поддерживаешь генерацию SVG-иконок для кнопок MTProto, SOCKS5 и VPN.»",
            isSelected = (currentMode == AiPrompts.MODE_DIAGNOSTIC),
            accentColor = accentColor,
            onSelect = {
                onModeSelected(AiPrompts.MODE_DIAGNOSTIC)
                onAccordionInteracted()
            }
        )

        // 2. Дружелюбный помощник
        AiPromptOptionCard(
            mode = AiPrompts.MODE_FUN,
            title = "Дружелюбный помощник",
            badge = "ДЛЯ НАЧИНАЮЩИХ",
            badgeColor = Socks5Accent,
            tagline = "Простые объяснения без сетевого сленга и терминологии",
            fullPromptText = "«Ты — остроумный и дружелюбный помощник в приложении Mirrly TG Proxy. Отвечай на вопросы пользователя доступно, помогай настроить прокси Telegram и VPN без сложной математической терминологии, сохраняя доброжелательный тон.»",
            isSelected = (currentMode == AiPrompts.MODE_FUN),
            accentColor = accentColor,
            onSelect = {
                onModeSelected(AiPrompts.MODE_FUN)
                onAccordionInteracted()
            }
        )

        // 3. Без инструкции (Чистый ИИ)
        AiPromptOptionCard(
            mode = AiPrompts.MODE_NONE,
            title = "Без инструкции (Чистый ИИ)",
            badge = "БАЗОВЫЙ",
            badgeColor = TextMuted,
            tagline = "Оригинальное заводское поведение выбранной модели",
            fullPromptText = "Системный промпт не передается. Модель функционирует в соответствии с оригинальным заводским обучением разработчиков лаборатории.",
            isSelected = (currentMode == AiPrompts.MODE_NONE),
            accentColor = accentColor,
            onSelect = {
                onModeSelected(AiPrompts.MODE_NONE)
                onAccordionInteracted()
            }
        )

        // 4. Пользовательский промпт
        AiPromptOptionCard(
            mode = AiPrompts.MODE_CUSTOM,
            title = "Пользовательский промпт",
            badge = "КАСТОМНЫЙ",
            badgeColor = VpnAccent,
            tagline = "Ваши собственные инструкции и правила общения из настроек",
            fullPromptText = "Вы можете ввести любой произвольный системный промпт в шторке настроек ИИ (AiSettingsSheet) в любое время.",
            isSelected = (currentMode == AiPrompts.MODE_CUSTOM),
            accentColor = accentColor,
            onSelect = {
                onModeSelected(AiPrompts.MODE_CUSTOM)
                onAccordionInteracted()
            }
        )

        // Нижнее примечание о приватности контекста
        AiHighlightNoteCard(
            title = "КОНТЕКСТНАЯ ПРИВАТНОСТЬ И БЕЗОПАСНОСТЬ",
            note = "Нейросеть получает срез логов только при явном вопросе о проблемах соединения.",
            detail = "История ваших сообщений сохраняется локально на смартфоне. Вы в любой момент можете очистить диалог одной кнопкой в окне чата.",
            accentColor = accentColor
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ШАГ 5: ВОЗМОЖНОСТИ, FUNCTION CALLING И SVG-ГЕНЕРАТОР
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepFeaturesAndTools(
    accentColor: Color,
    onAccordionInteracted: () -> Unit
) {
    val expandedTools = remember { mutableStateListOf<String>() }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AiStepHeader(
            badge = "ШАГ 5 · ВОЗМОЖНОСТИ И ИНСТРУМЕНТЫ",
            title = "Инструменты автономного интеллекта",
            subtitle = "Генерация векторных иконок кнопок и 6 системных функций диагностики",
            accentColor = accentColor
        )

        // Карточка 1: Генератор векторных SVG-иконок кнопок
        AiExpandableCard(
            title = "1. Генератор векторных SVG-иконок кнопок",
            summary = "Интерактивное проектирование иконки в чате и установка в 1 тап",
            accentColor = accentColor,
            initiallyExpanded = true
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Попросите ИИ создать иконку (например, «Сделай иконку сокола для кнопки MTProto»). Нейросеть проведет структурированное интервью о геометрии линий, сгенерирует чистый векторный pathData (100x100, 1.5dp) и предложит применить его к центральной кнопке главного экрана.",
                    fontSize = 11.5.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    lineHeight = 16.sp
                )

                // Процедурный интерактивный Canvas-превью векторной кнопки (Zero-Allocation по стандартам mirrly-icons)
                AiSvgVectorDemoCanvas(accentColor = accentColor)

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AiTechChip("Вьюпорт 100x100", accentColor)
                    AiTechChip("Stroke 1.5dp", accentColor)
                    AiTechChip("Zero Background", accentColor)
                    AiTechChip("Применение в 1 тап", accentColor)
                }
            }
        }

        // Карточка 2: 6 системных инструментов Function Calling
        AiExpandableCard(
            title = "2. 6 системных инструментов Function Calling",
            summary = "Автономная диагностика состояния сокетов, логов и каналов",
            accentColor = accentColor,
            initiallyExpanded = false
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Нейросеть напрямую опрашивает сетевое ядро приложения через структурированные вызовы Function Calling:",
                    fontSize = 11.5.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    lineHeight = 16.sp
                )

                AiToolItem(
                    systemName = "get_service_status()",
                    label = "Статус службы и локальных портов",
                    desc = "Состояние сокетов :1443 (MTProto) и :10808 (SOCKS5), число активных сессий и аптайм службы.",
                    isExpanded = expandedTools.contains("get_service_status"),
                    accentColor = accentColor,
                    onToggle = {
                        if (expandedTools.contains("get_service_status")) expandedTools.remove("get_service_status")
                        else expandedTools.add("get_service_status")
                        onAccordionInteracted()
                    }
                )

                AiToolItem(
                    systemName = "get_network_health()",
                    label = "Здоровье сети и задержка",
                    desc = "Анализ типа соединения (Wi-Fi/LTE), задержки RTT до Anycast-шлюза и джиттера пакетов.",
                    isExpanded = expandedTools.contains("get_network_health"),
                    accentColor = accentColor,
                    onToggle = {
                        if (expandedTools.contains("get_network_health")) expandedTools.remove("get_network_health")
                        else expandedTools.add("get_network_health")
                        onAccordionInteracted()
                    }
                )

                AiToolItem(
                    systemName = "get_recent_failure_logs()",
                    label = "Срез журнала сетевых сбоев",
                    desc = "Извлечение инцидентов из кольцевого буфера приложения для выявления ТСПУ-блокировок и сбоев TLS.",
                    isExpanded = expandedTools.contains("get_recent_failure_logs"),
                    accentColor = accentColor,
                    onToggle = {
                        if (expandedTools.contains("get_recent_failure_logs")) expandedTools.remove("get_recent_failure_logs")
                        else expandedTools.add("get_recent_failure_logs")
                        onAccordionInteracted()
                    }
                )

                AiToolItem(
                    systemName = "test_uplink_connection()",
                    label = "Тест магистрального канала",
                    desc = "Замер скорости отклика и доступности узлов Cloudflare Edge для выбора наилучшего дата-центра.",
                    isExpanded = expandedTools.contains("test_uplink_connection"),
                    accentColor = accentColor,
                    onToggle = {
                        if (expandedTools.contains("test_uplink_connection")) expandedTools.remove("test_uplink_connection")
                        else expandedTools.add("test_uplink_connection")
                        onAccordionInteracted()
                    }
                )

                AiToolItem(
                    systemName = "get_reference_icon_code()",
                    label = "Эталонная векторная геометрия",
                    desc = "Загрузка проверенных каркасов геометрии (100x100, Stroke 1.5dp) перед генерацией новой кнопки.",
                    isExpanded = expandedTools.contains("get_reference_icon_code"),
                    accentColor = accentColor,
                    onToggle = {
                        if (expandedTools.contains("get_reference_icon_code")) expandedTools.remove("get_reference_icon_code")
                        else expandedTools.add("get_reference_icon_code")
                        onAccordionInteracted()
                    }
                )

                AiToolItem(
                    systemName = "apply_svg_button_icon()",
                    label = "Установка векторной иконки",
                    desc = "Компиляция и сохранение созданной векторной иконки на центральную кнопку в один клик.",
                    isExpanded = expandedTools.contains("apply_svg_button_icon"),
                    accentColor = accentColor,
                    onToggle = {
                        if (expandedTools.contains("apply_svg_button_icon")) expandedTools.remove("apply_svg_button_icon")
                        else expandedTools.add("apply_svg_button_icon")
                        onAccordionInteracted()
                    }
                )
            }
        }

        // Карточка 3: Локальная деперсонализация (AiSanitizer)
        AiExpandableCard(
            title = "3. Локальная деперсонализация (AiSanitizer)",
            summary = "Автоматическое маскирование персональных IP, токенов и ключей",
            accentColor = accentColor,
            initiallyExpanded = false
        ) {
            Text(
                text = "Все сетевые логи перед отправкой в нейросеть проходят локальную фильтрацию AiSanitizer. Ваши публичные IP-адреса, токены авторизации и приватные ключи автоматически заменяются на <REDACTED_DATA>.",
                fontSize = 11.5.sp,
                color = TextWhite.copy(alpha = 0.85f),
                lineHeight = 16.sp
            )
        }

        // Нижнее примечание о готовности к запуску
        AiHighlightNoteCard(
            title = "ГОТОВНОСТЬ К ЗАПУСКУ ИИ-ДИАГНОСТИКИ",
            note = "Кнопка вызова ИИ расположена в правом нижнем углу главного экрана.",
            detail = "Нажмите на круглую кнопку интеллекта для начала работы. В любой момент вы можете повторно просмотреть данное руководство в настройках без ожидания таймера.",
            accentColor = accentColor
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ВСПОМОГАТЕЛЬНЫЕ КОМПОНЕНТЫ И КАРТОЧКИ MIRRLYUI
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AiStepHeader(
    badge: String,
    title: String,
    subtitle: String,
    accentColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.60f)),
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            Text(
                text = badge,
                color = accentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }

        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = TextWhite,
            letterSpacing = 0.2.sp
        )

        Text(
            text = subtitle,
            fontSize = 11.5.sp,
            color = TextMuted,
            lineHeight = 16.sp
        )
    }
}

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

    val infiniteTransition = rememberInfiniteTransition(label = "expandBeckonTransition")
    val beckonAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "expandBeckonAlpha"
    )

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
            contentDescription = if (isExpanded) "Свернуть" else "Развернуть",
            tint = currentTint,
            modifier = Modifier
                .size(15.dp)
                .graphicsLayer { rotationZ = rotation }
        )
    }
}

@Composable
private fun AiExpandableCard(
    title: String,
    summary: String,
    accentColor: Color,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AmoledBackground,
        border = BorderStroke(
            1.dp,
            if (isExpanded) accentColor.copy(alpha = 0.65f) else AmoledBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .springPress(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                isExpanded = !isExpanded
            })
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
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
                        color = if (isExpanded) accentColor else TextWhite
                    )
                    Text(
                        text = summary,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
private fun AiProviderOptionCard(
    name: String,
    tagline: String,
    badgeText: String,
    badgeColor: Color,
    isSelected: Boolean,
    accentColor: Color,
    onSelect: () -> Unit,
    content: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isExpanded by rememberSaveable(isSelected) { mutableStateOf(isSelected) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AmoledBackground,
        border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) accentColor else AmoledBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .lightSweep(isEnabled = isSelected, shape = RoundedCornerShape(14.dp))
            .springPress(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect()
                isExpanded = !isExpanded
            })
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
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .border(1.dp, if (isSelected) accentColor else AmoledBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) accentColor else TextWhite
                        )
                        Text(
                            text = tagline,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.60f))
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    MirrlyExpandChevronBadge(
                        isExpanded = isExpanded,
                        accentColor = accentColor
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun AiModelOptionCard(
    model: OnboardingModelDetail,
    isSelected: Boolean,
    accentColor: Color,
    onSelect: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isExpanded by rememberSaveable(isSelected) { mutableStateOf(isSelected) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AmoledBackground,
        border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) accentColor else AmoledBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .springPress(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect()
                isExpanded = !isExpanded
            })
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .border(1.dp, if (isSelected) accentColor else AmoledBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = model.shortName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) accentColor else TextWhite
                        )
                        Text(
                            text = model.tierLabel,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, model.badgeColor.copy(alpha = 0.60f))
                    ) {
                        Text(
                            text = model.badge,
                            color = model.badgeColor,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    MirrlyExpandChevronBadge(
                        isExpanded = isExpanded,
                        accentColor = accentColor
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = model.description,
                        fontSize = 11.5.sp,
                        color = TextWhite.copy(alpha = 0.85f),
                        lineHeight = 16.sp
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Назначение: ${model.bestFor}",
                            fontSize = 10.5.sp,
                            color = TextMuted,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiPromptOptionCard(
    mode: String,
    title: String,
    badge: String,
    badgeColor: Color,
    tagline: String,
    fullPromptText: String,
    isSelected: Boolean,
    accentColor: Color,
    onSelect: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isExpanded by rememberSaveable(isSelected) { mutableStateOf(isSelected) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AmoledBackground,
        border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) accentColor else AmoledBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .springPress(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect()
                isExpanded = !isExpanded
            })
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .border(1.dp, if (isSelected) accentColor else AmoledBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) accentColor else TextWhite
                        )
                        Text(
                            text = tagline,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.60f))
                    ) {
                        Text(
                            text = badge,
                            color = badgeColor,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    MirrlyExpandChevronBadge(
                        isExpanded = isExpanded,
                        accentColor = accentColor
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = AmoledBorder.copy(alpha = 0.5f))

                    Text(
                        text = fullPromptText,
                        fontSize = 11.sp,
                        color = TextWhite.copy(alpha = 0.90f),
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun AiToolItem(
    systemName: String,
    label: String,
    desc: String,
    isExpanded: Boolean,
    accentColor: Color,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (isExpanded) accentColor.copy(alpha = 0.60f) else AmoledBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .springPress(onClick = onToggle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = systemName,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        color = TextWhite,
                        fontWeight = FontWeight.Medium
                    )
                }

                MirrlyIcon(
                    id = R.drawable.ic_chevron_right,
                    contentDescription = null,
                    tint = if (isExpanded) accentColor else TextMuted,
                    modifier = Modifier
                        .size(13.dp)
                        .graphicsLayer { rotationZ = if (isExpanded) 90f else 0f }
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = desc,
                    fontSize = 11.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * Процедурный интерактивный холст (Canvas) с демонстрацией векторного сплайна иконки кнопки.
 * Разработан по стандартам mirrly-icons и mirrly-ui:
 * - Вьюпорт 100x100, Stroke 1.5dp, замкнутые сплайны.
 * - Zero-Allocation: пути Path кэшируются через remember и очищаются через reset().
 * - Анимированный квантовый импульс орбиты.
 */
@Composable
private fun AiSvgVectorDemoCanvas(
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "svgDemoTransition")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "svgPulseProgress"
    )

    val gliderPath = remember { Path() }
    val orbitPath = remember { Path() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.40f)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(110.dp)) {
            val w = size.width
            val h = size.height
            val strokeWidthPx = 1.5.dp.toPx()

            // 1. Аэродинамический силуэт сверхзвукового глайдера (100x100 вьюпорт)
            gliderPath.reset()
            // Носовая часть
            gliderPath.moveTo(w * 0.50f, h * 0.14f)
            // Правое стреловидное крыло
            gliderPath.lineTo(w * 0.86f, h * 0.68f)
            // Правое сопло
            gliderPath.lineTo(w * 0.62f, h * 0.62f)
            // Центральный хвостовой стабилизатор
            gliderPath.lineTo(w * 0.50f, h * 0.84f)
            // Левое сопло
            gliderPath.lineTo(w * 0.38f, h * 0.62f)
            // Левое стреловидное крыло
            gliderPath.lineTo(w * 0.14f, h * 0.68f)
            gliderPath.close()

            // Отрисовка основного силуэта глайдера
            drawPath(
                path = gliderPath,
                color = accentColor,
                style = Stroke(
                    width = strokeWidthPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Внутренний кристаллический кокпит
            gliderPath.reset()
            gliderPath.moveTo(w * 0.50f, h * 0.30f)
            gliderPath.lineTo(w * 0.58f, h * 0.54f)
            gliderPath.lineTo(w * 0.50f, h * 0.60f)
            gliderPath.lineTo(w * 0.42f, h * 0.54f)
            gliderPath.close()

            drawPath(
                path = gliderPath,
                color = accentColor.copy(alpha = 0.50f),
                style = Stroke(
                    width = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 2. Анимированное защитное квантовое кольцо импульса
            val maxRadius = w * 0.46f
            val currentRadius = maxRadius * (0.35f + 0.65f * pulseProgress)
            val ringAlpha = (1f - pulseProgress).coerceIn(0f, 1f) * 0.45f

            drawCircle(
                color = accentColor.copy(alpha = ringAlpha),
                radius = currentRadius,
                center = Offset(w * 0.50f, h * 0.50f),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Подпись в нижнем углу карточки
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(0.8.dp, accentColor.copy(alpha = 0.30f), RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.60f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "0 0 100 100 · 1.5dp",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = accentColor.copy(alpha = 0.90f)
            )
        }
    }
}

@Composable
private fun AiTechChip(text: String, accentColor: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            color = accentColor,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun AiHighlightNoteCard(
    title: String,
    note: String,
    detail: String,
    accentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AmoledBackground,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Text(
                    text = title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = accentColor,
                    letterSpacing = 0.8.sp
                )
            }

            Text(
                text = note,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextWhite,
                lineHeight = 16.sp
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

private data class OnboardingModelDetail(
    val id: String,
    val shortName: String,
    val badge: String,
    val badgeColor: Color,
    val tierLabel: String,
    val description: String,
    val bestFor: String
)
