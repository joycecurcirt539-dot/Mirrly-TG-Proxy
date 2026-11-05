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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ai.AiKeyManager
import com.mirrly.tgproxy.ai.AiPrompts
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.cloudflare.CloudflareOAuthManager
import com.mirrly.tgproxy.service.cloudflare.ServerAuthState
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.ColorEngine
import com.mirrly.tgproxy.ui.theme.MtprotoAccent
import com.mirrly.tgproxy.ui.theme.Socks5Accent
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.ThemeAccentMode
import com.mirrly.tgproxy.ui.theme.ThemePresets
import com.mirrly.tgproxy.ui.theme.VpnAccent
import com.mirrly.tgproxy.ui.theme.adaptiveContainerWidth
import com.mirrly.tgproxy.ui.theme.fadingEdges
import com.mirrly.tgproxy.ui.theme.horizontalFadingEdges
import com.mirrly.tgproxy.ui.theme.lightSweep
import com.mirrly.tgproxy.ui.theme.springPress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Интерактивное вводное руководство Mirrly AI BYOK Engine (5 шагов).
 *
 * Архитектурный стиль:
 * - Точное соответствие дизайну OnboardingScreen.kt (Zero-Popup, Outline-Only, Zero Emojis).
 * - Затемненный фон Color.Black.copy(alpha = 0.88f) с поддержкой размытия подложки.
 * - Точный честный таймер ознакомления: 5 секунд (5000 мс) на каждый шаг.
 * - Обязательные действия пользователя для разблокировки перехода по шагам:
 *   1. Шаг 1: Обязательный выбор провайдера.
 *   2. Шаг 2: Обязательный переход по ссылке / авторизация + 5 секунд.
 *   3. Шаг 3: Обязательный выбор модели + возможность ввода ключей (с кнопкой видимости) + 5 секунд.
 *   4. Шаг 4: Обязательное ознакомление с системной инструкцией + 5 секунд.
 *   5. Шаг 5: Обязательное ознакомление с инструментами + 5 секунд.
 * - При преждевременном выходе статус не сохраняется, обучение начнется заново с 1 шага.
 * - При завершении шага 5 сохраняется hasSeenAiOnboarding = true.
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

    val currentThemeMode by prefs.themeAccentModeFlow.collectAsState()
    val currentPresetId = prefs.getThemePresetId()
    val customHex = prefs.getThemeCustomColorHex()

    val activeAccentColor = remember(accentColor, currentThemeMode, currentPresetId, customHex) {
        accentColor ?: when (currentThemeMode) {
            ThemeAccentMode.PROTOCOL_DEFAULT -> MtprotoAccent
            ThemeAccentMode.STEALTH -> Color.White
            ThemeAccentMode.PRESET -> ThemePresets.getPresetById(currentPresetId)?.primaryColor ?: MtprotoAccent
            ThemeAccentMode.MONET -> ColorEngine.getSystemMonetAccent(context)
            ThemeAccentMode.CUSTOM -> ColorEngine.parseHexColor(customHex) ?: MtprotoAccent
        }
    }

    val isReplayMode = isReplay

    var currentStep by remember { mutableIntStateOf(0) }
    val totalSteps = 5

    // Локальные состояния конфигурации ИИ (при первом запуске провайдер и модель требуют явного выбора)
    var selectedProvider by remember { mutableStateOf<String?>(if (isReplayMode) prefs.getAiProvider() else null) }
    var selectedModel by remember { mutableStateOf<String?>(if (isReplayMode) prefs.getAiModel() else null) }
    var promptMode by remember { mutableStateOf(prefs.getAiSystemPromptMode()) }
    var geminiKey by remember { mutableStateOf(AiKeyManager.getApiKey(context)) }
    var openRouterKey by remember { mutableStateOf(AiKeyManager.getOpenRouterApiKey(context)) }
    var cfAccountId by remember { mutableStateOf(prefs.getCloudflareAccountId().orEmpty()) }
    var cfToken by remember { mutableStateOf(prefs.getCloudflareToken().orEmpty()) }

    // Контроллеры обязательных действий пользователя на шагах
    var hasPerformedStep2Action by remember(selectedProvider) { mutableStateOf(isReplayMode) }
    var hasInteractedStep4 by remember { mutableStateOf(isReplayMode) }
    var hasInteractedStep5 by remember { mutableStateOf(isReplayMode) }

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

    // Честный таймер чтения: ровно 5000 мс (wall-clock по системному времени)
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
    val safeBottomMargin = bottomInset + 20.dp

    // Валидация готовности к переходу на следующий шаг
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
            .background(Color.Black.copy(alpha = 0.88f))
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
                    Column {
                        Text(
                            text = "MIRRLY AI · ВВОДНОЕ РУКОВОДСТВО",
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

                        // Кнопка закрытия (без сохранения прохождения)
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
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_close),
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
                                    // Пользователь может свободно возвращаться на ранее изученные шаги
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
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        when (step) {
                            0 -> StepProviderSelection(
                                selectedProvider = selectedProvider,
                                accentColor = activeAccentColor,
                                onSelectProvider = { prov ->
                                    selectedProvider = prov
                                    prefs.setAiProvider(prov)
                                    // Сбрасываем модель для обязательного явного выбора пользователем на Шаге 3
                                    selectedModel = null
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            )
                            1 -> StepConnectionInstructions(
                                selectedProvider = selectedProvider,
                                accentColor = activeAccentColor,
                                onActionCompleted = {
                                    hasPerformedStep2Action = true
                                }
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
                // Всплывающий стильный баннер ошибки валидации (Zero-Popup)
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

                // Линия таймера ознакомления (5 секунд)
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
                            isReplayMode -> "Повторный просмотр · Свободная навигация"
                            !isTimerFinished -> "Ознакомление с шагом: $remainingSeconds с"
                            currentStep == 0 && selectedProvider == null -> "Выберите провайдера для продолжения"
                            currentStep == 1 && !hasPerformedStep2Action -> "Выполните действие для продолжения"
                            currentStep == 2 && selectedModel == null -> "Выберите модель для продолжения"
                            currentStep == 3 && !hasInteractedStep4 -> "Раскройте описание инструкции"
                            currentStep == 4 && !hasInteractedStep5 -> "Раскройте описание инструмента"
                            else -> "Ознакомление завершено"
                        }

                        Text(
                            text = statusText,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (canProceed) activeAccentColor else TextMuted
                        )

                        Text(
                            text = "${(timerProgress * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (canProceed) activeAccentColor else TextMuted
                        )
                    }

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
                                .background(
                                    if (canProceed) {
                                        Brush.horizontalGradient(
                                            listOf(
                                                activeAccentColor.copy(alpha = 0.70f),
                                                activeAccentColor
                                            )
                                        )
                                    } else {
                                        Brush.horizontalGradient(
                                            listOf(
                                                activeAccentColor.copy(alpha = 0.35f),
                                                activeAccentColor.copy(alpha = 0.65f)
                                            )
                                        )
                                    }
                                )
                        )
                    }
                }

                // Кнопки навигации: Назад / Закрыть и Далее
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Кнопка Назад
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier
                            .height(48.dp)
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (currentStep > 0) {
                                    currentStep -= 1
                                } else {
                                    executeExit(isCompleted = false)
                                }
                            })
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (currentStep > 0) "Назад" else "Закрыть",
                                color = Color(0xFFD4D4DC),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Кнопка Далее / Завершить
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                        border = if (canProceed) {
                            BorderStroke(1.5.dp, activeAccentColor.copy(alpha = 0.85f))
                        } else {
                            BorderStroke(1.dp, AmoledBorder)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .lightSweep(isEnabled = canProceed, shape = RoundedCornerShape(14.dp))
                            .springPress(
                                onClick = {
                                    if (!canProceed) {
                                        when {
                                            currentStep == 0 && selectedProvider == null ->
                                                showWarning("Выберите провайдера нейросети для продолжения")
                                            currentStep == 1 && !hasPerformedStep2Action ->
                                                showWarning("Выполните переход по ссылке или авторизацию для продолжения")
                                            currentStep == 2 && selectedModel == null ->
                                                showWarning("Выберите модель нейросети для продолжения")
                                            currentStep == 3 && !hasInteractedStep4 ->
                                                showWarning("Раскройте описание инструкции для ознакомления")
                                            currentStep == 4 && !hasInteractedStep5 ->
                                                showWarning("Раскройте описание инструмента для ознакомления")
                                            !isTimerFinished ->
                                                showWarning("Ознакомьтесь с материалом шага (осталось $remainingSeconds с)")
                                        }
                                        return@springPress
                                    }
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (currentStep < totalSteps - 1) {
                                        currentStep += 1
                                    } else {
                                        executeExit(isCompleted = true)
                                    }
                                }
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val buttonLabel = when {
                                    !isReplayMode && !isTimerFinished -> "Далее (${remainingSeconds}с)"
                                    !isReplayMode && currentStep == 0 && selectedProvider == null -> "Выберите провайдера"
                                    !isReplayMode && currentStep == 1 && !hasPerformedStep2Action -> "Выполните действие"
                                    !isReplayMode && currentStep == 2 && selectedModel == null -> "Выберите модель"
                                    !isReplayMode && currentStep == 3 && !hasInteractedStep4 -> "Ознакомьтесь с режимами"
                                    !isReplayMode && currentStep == 4 && !hasInteractedStep5 -> "Ознакомьтесь с функциями"
                                    currentStep == totalSteps - 1 -> "Завершить и начать работу"
                                    else -> "Далее"
                                }

                                Text(
                                    text = buttonLabel,
                                    color = if (canProceed) activeAccentColor else TextMuted,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.3.sp
                                )

                                Icon(
                                    painter = painterResource(
                                        id = if (currentStep == totalSteps - 1 && canProceed) R.drawable.ic_check else R.drawable.ic_chevron_right
                                    ),
                                    contentDescription = null,
                                    tint = if (canProceed) activeAccentColor else TextMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ШАГ 1: ВЫБОР ПРОВАЙДЕРА НЕЙРОСЕТИ
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepProviderSelection(
    selectedProvider: String?,
    accentColor: Color,
    onSelectProvider: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "ШАГ 1: ВЫБОР ПРОВАЙДЕРА НЕЙРОСЕТИ",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp,
                color = accentColor
            )
            Text(
                text = "Mirrly AI работает по принципу BYOK (Bring Your Own Key) — подключите персональный ключ или аккаунт выбранного провайдера без подписок и посредников.",
                fontSize = 13.sp,
                color = Color(0xFFD4D4DC),
                lineHeight = 18.sp
            )
        }

        // 1. Google Gemini (Рекомендуется)
        ProviderCardItem(
            name = "Google Gemini",
            badge = "РЕКОМЕНДУЕТСЯ",
            badgeColor = accentColor,
            desc = "Бесплатные лимиты Google AI Studio, мгновенный отклик, вызов системных диагностических инструментов (Function Calling) и генерация векторных SVG-иконок кнопок.",
            features = listOf("gemini-3.6-flash", "Инструменты", "Бесплатно"),
            isSelected = (selectedProvider == "Gemini"),
            accentColor = accentColor,
            onClick = { onSelectProvider("Gemini") }
        )

        // 2. Cloudflare Workers AI
        ProviderCardItem(
            name = "Cloudflare Workers AI",
            badge = "EDGE ИНФЕРЕНС",
            badgeColor = Socks5Accent,
            desc = "Автономный серверлесс-инференс моделей на глобальной распределенной сети Cloudflare. Поддерживает быстрый вход через OAuth или персональный API Token.",
            features = listOf("Llama 3.3 70B", "DeepSeek R1", "OAuth / Токен"),
            isSelected = (selectedProvider == "Cloudflare"),
            accentColor = accentColor,
            onClick = { onSelectProvider("Cloudflare") }
        )

        // 3. OpenRouter
        ProviderCardItem(
            name = "OpenRouter",
            badge = "МУЛЬТИ-ПРОВАЙДЕР",
            badgeColor = VpnAccent,
            desc = "Универсальный шлюз к сотням моделей: DeepSeek, Claude 3.5, Mistral, Qwen и Gemini по единому стандартизированному API-ключу sk-or-v1-...",
            features = listOf("DeepSeek V3", "Claude 3.5", "Бесплатно :free"),
            isSelected = (selectedProvider == "OpenRouter"),
            accentColor = accentColor,
            onClick = { onSelectProvider("OpenRouter") }
        )

        // 4. OpenAI (Скоро)
        ProviderCardItem(
            name = "OpenAI",
            badge = "В РАЗРАБОТКЕ",
            badgeColor = TextMuted,
            desc = "Прямое подключение к API OpenAI (модели GPT-4o, o3-mini). Находится в стадии интеграции.",
            features = listOf("GPT-4o", "Скоро"),
            isSelected = false,
            isEnabled = false,
            accentColor = accentColor,
            onClick = {}
        )
    }
}

@Composable
private fun ProviderCardItem(
    name: String,
    badge: String,
    badgeColor: Color,
    desc: String,
    features: listOfString,
    isSelected: Boolean,
    isEnabled: Boolean = true,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = if (isSelected) {
            BorderStroke(1.5.dp, accentColor)
        } else {
            BorderStroke(1.dp, AmoledBorder.copy(alpha = if (isEnabled) 1f else 0.5f))
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .lightSweep(isEnabled = isSelected, shape = RoundedCornerShape(16.dp))
            .springPress(
                onClick = { if (isEnabled) onClick() }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEnabled) TextWhite else TextMuted
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = badge,
                        color = badgeColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = desc,
                fontSize = 12.sp,
                color = if (isEnabled) Color(0xFFD4D4DC) else TextMuted.copy(alpha = 0.6f),
                lineHeight = 17.sp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                features.forEach { feat ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = feat,
                            color = if (isSelected) accentColor.copy(alpha = 0.9f) else TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

// Typealias helper
private typealias listOfString = List<String>

private data class OnboardingModelDetail(
    val id: String,
    val shortName: String,
    val badge: String,
    val badgeColor: Color,
    val tierLabel: String,
    val description: String,
    val bestFor: String
)

// ─────────────────────────────────────────────────────────────────────────────
// ШАГ 2: ИНСТРУКЦИЯ ПО ПОДКЛЮЧЕНИЮ И ПОЛУЧЕНИЮ КЛЮЧА
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepConnectionInstructions(
    selectedProvider: String?,
    accentColor: Color,
    onActionCompleted: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val prov = selectedProvider ?: "Gemini"

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "ШАГ 2: ИНСТРУКЦИЯ ДЛЯ $prov",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp,
                color = accentColor
            )
            Text(
                text = "Пошаговый регламент получения доступа и обхода сетевых ограничений.",
                fontSize = 13.sp,
                color = TextWhite
            )
        }

        when (prov) {
            "Gemini" -> {
                InstructionBlockItem(
                    stepNum = "01",
                    title = "Откройте Google AI Studio",
                    desc = "Портал разработчиков Google AI Studio предоставляет бесплатный персональный API-ключ для Gemini.",
                    accentColor = accentColor
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .springPress(onClick = {
                                onActionCompleted()
                                uriHandler.openUri("https://aistudio.google.com/app/apikey")
                            })
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Открыть aistudio.google.com",
                                color = accentColor,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                InstructionBlockItem(
                    stepNum = "02",
                    title = "Сгенерируйте API-ключ",
                    desc = "Нажмите «Get API key» → «Create API key in new project». Скопируйте строку ключа (начинается с AIzaSy...).",
                    accentColor = accentColor
                )

                // Важный акцент на региональные ограничения Google
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, Socks5Accent.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(RoundedCornerShape(3.5.dp))
                                    .background(Socks5Accent)
                            )
                            Text(
                                text = "ВАЖНО: ОБХОД РЕГИОНАЛЬНЫХ ОГРАНИЧЕНИЙ",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Socks5Accent
                            )
                        }

                        Text(
                            text = "Google блокирует запросы к Gemini с российских IP-адресов (ошибка «User location is not supported»). В Mirrly TG Proxy встроен модуль SmartDNS (DoH GeoHide DNS с резервным узлом dns-ai.ru), который активен по умолчанию и автоматически перенаправляет запросы к generativelanguage.googleapis.com в обход геоблокировок без снижения скорости и без постоянного VPN.",
                            fontSize = 11.5.sp,
                            color = Color(0xFFD4D4DC),
                            lineHeight = 16.sp
                        )

                        Text(
                            text = "Статус: встроенный SmartDNS (GeoHide DNS DoH / dns-ai.ru) активен",
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = accentColor
                        )
                    }
                }

                InstructionBlockItem(
                    stepNum = "03",
                    title = "Флагманская модель 2026",
                    desc = "Рекомендуем gemini-3.6-flash — она обладает высочайшим интеллектом линейки Flash, глубоким пониманием протоколов и гарантирует стабильный вызов Function Calling инструментов диагностики.",
                    accentColor = accentColor
                )
            }

            "Cloudflare" -> {
                InstructionBlockItem(
                    stepNum = "01",
                    title = "Edge-инфраструктура Cloudflare",
                    desc = "Workers AI выполняет инференс нейросетей непосредственно на всемирной сети дата-центров Cloudflare Edge. Доступ стабилен из РФ, соединение защищено прямым протоколом API.",
                    accentColor = accentColor
                )

                InstructionBlockItem(
                    stepNum = "02",
                    title = "Авторизация в один клик через OAuth",
                    desc = "Вы можете выполнить быстрый вход через аккаунт Cloudflare. Приложение автоматически получит Account ID и защищенный токен.",
                    accentColor = accentColor
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Socks5Accent.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .springPress(onClick = {
                                onActionCompleted()
                                CloudflareOAuthManager.startAuthSession(context)
                            })
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Войти через Cloudflare (OAuth)",
                                color = Socks5Accent,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                InstructionBlockItem(
                    stepNum = "03",
                    title = "Ручной ввод Account ID и API Token",
                    desc = "Если вы предпочитаете ручную настройку, откройте Cloudflare Dashboard (dash.cloudflare.com), скопируйте Account ID и сгенерируйте токен с правами «Workers AI Read».",
                    accentColor = accentColor
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .springPress(onClick = {
                                onActionCompleted()
                                uriHandler.openUri("https://dash.cloudflare.com")
                            })
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Открыть dash.cloudflare.com",
                                color = Color(0xFFD4D4DC),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                InstructionBlockItem(
                    stepNum = "04",
                    title = "Рекомендуемые модели",
                    desc = "Для основного анализа сокетов и логов используйте @cf/meta/llama-3.3-70b-instruct, а для пошагового логического рассуждения — @cf/deepseek-ai/deepseek-r1-distill-qwen-32b.",
                    accentColor = accentColor
                )
            }

            "OpenRouter" -> {
                InstructionBlockItem(
                    stepNum = "01",
                    title = "Универсальный шлюз нейросетей",
                    desc = "OpenRouter предоставляет доступ к сотням ведущих открытых и коммерческих моделей (DeepSeek R1, Claude 3.5 Sonnet, GPT-4o, Llama 3.3) через единый API.",
                    accentColor = accentColor
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, VpnAccent.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .springPress(onClick = {
                                onActionCompleted()
                                uriHandler.openUri("https://openrouter.ai/keys")
                            })
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Открыть openrouter.ai/keys",
                                color = VpnAccent,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                InstructionBlockItem(
                    stepNum = "02",
                    title = "Создание ключа доступа",
                    desc = "В разделе «Keys» нажмите кнопку «Create Key» и скопируйте созданный ключ вида sk-or-v1-...",
                    accentColor = accentColor
                )

                InstructionBlockItem(
                    stepNum = "03",
                    title = "Бесплатные модели (:free) и баланс",
                    desc = "Модели с пометкой :free (например, deepseek/deepseek-r1:free) доступны бесплатно без необходимости пополнения баланса и без привязки карт.",
                    accentColor = accentColor
                )

                InstructionBlockItem(
                    stepNum = "04",
                    title = "Защита DNS DoH",
                    desc = "Запросы к API OpenRouter внутри приложения могут маршрутизироваться через встроенный DoH SmartDNS, защищая инференс от DNS-блокировок провайдера.",
                    accentColor = accentColor
                )
            }

            else -> {
                InstructionBlockItem(
                    stepNum = "01",
                    title = "Интеграция в разработке",
                    desc = "Модуль прямого подключения к OpenAI находится в стадии финального тестирования. Рекомендуем переключиться на Google Gemini на первом шаге.",
                    accentColor = accentColor
                )
            }
        }
    }
}

@Composable
private fun InstructionBlockItem(
    stepNum: String,
    title: String,
    desc: String,
    accentColor: Color,
    action: (@Composable () -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, AmoledBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = stepNum,
                        color = accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Text(
                    text = title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            }

            Text(
                text = desc,
                fontSize = 12.sp,
                color = Color(0xFFD4D4DC),
                lineHeight = 17.sp
            )

            if (action != null) {
                Spacer(modifier = Modifier.height(2.dp))
                action()
            }
        }
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

    var isGeminiKeyVisible by remember { mutableStateOf(false) }
    var isCfTokenVisible by remember { mutableStateOf(false) }
    var isOpenRouterKeyVisible by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "ШАГ 3: НАСТРОЙКА КЛЮЧА И МОДЕЛИ",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp,
                color = accentColor
            )
            Text(
                text = "Введите персональный ключ доступа и выберите активную модель для запуска ядра Mirrly AI.",
                fontSize = 13.sp,
                color = Color(0xFFD4D4DC)
            )
        }

        // Блок ввода ключей в зависимости от провайдера
        when (selectedProvider) {
            "Gemini" -> {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Google Gemini API Key",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )

                            // Кнопка вставки из буфера
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
                                modifier = Modifier.springPress(onClick = {
                                    val clip = clipboard?.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val text = clip.getItemAt(0).text?.toString()?.trim().orEmpty()
                                        if (text.isNotBlank()) {
                                            onGeminiKeyChanged(text)
                                        }
                                    }
                                })
                            ) {
                                Text(
                                    text = "Вставить",
                                    color = accentColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = geminiKey,
                            onValueChange = onGeminiKeyChanged,
                            placeholder = { Text("Вставьте ключ AIzaSy...", fontSize = 12.sp, color = TextMuted) },
                            singleLine = true,
                            visualTransformation = if (isGeminiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(
                                    onClick = { isGeminiKeyVisible = !isGeminiKeyVisible },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            id = if (isGeminiKeyVisible) R.drawable.ic_eye else R.drawable.ic_eye_slash
                                        ),
                                        contentDescription = "Видимость ключа",
                                        tint = if (isGeminiKeyVisible) accentColor else TextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = AmoledBorder,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = if (geminiKey.isNotBlank()) "Ключ сохранен в защищенном хранилище" else "Ключ можно ввести сейчас или позже в настройках ИИ",
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (geminiKey.isNotBlank()) accentColor else TextMuted
                        )
                    }
                }
            }

            "Cloudflare" -> {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Cloudflare Account ID & Token",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )

                        // Кнопка быстрого входа через OAuth
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Socks5Accent.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .springPress(onClick = {
                                    CloudflareOAuthManager.startAuthSession(context)
                                })
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Войти через Cloudflare (OAuth)",
                                    color = Socks5Accent,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        OutlinedTextField(
                            value = cfAccountId,
                            onValueChange = onCfAccountIdChanged,
                            placeholder = { Text("Cloudflare Account ID", fontSize = 12.sp, color = TextMuted) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Socks5Accent,
                                unfocusedBorderColor = AmoledBorder,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = cfToken,
                            onValueChange = onCfTokenChanged,
                            placeholder = { Text("API Token (Workers AI Read)", fontSize = 12.sp, color = TextMuted) },
                            singleLine = true,
                            visualTransformation = if (isCfTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(
                                    onClick = { isCfTokenVisible = !isCfTokenVisible },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            id = if (isCfTokenVisible) R.drawable.ic_eye else R.drawable.ic_eye_slash
                                        ),
                                        contentDescription = "Видимость токена",
                                        tint = if (isCfTokenVisible) Socks5Accent else TextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Socks5Accent,
                                unfocusedBorderColor = AmoledBorder,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            "OpenRouter" -> {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "OpenRouter API Key",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, VpnAccent.copy(alpha = 0.5f)),
                                modifier = Modifier.springPress(onClick = {
                                    val clip = clipboard?.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val text = clip.getItemAt(0).text?.toString()?.trim().orEmpty()
                                        if (text.isNotBlank()) {
                                            onOpenRouterKeyChanged(text)
                                        }
                                    }
                                })
                            ) {
                                Text(
                                    text = "Вставить",
                                    color = VpnAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = openRouterKey,
                            onValueChange = onOpenRouterKeyChanged,
                            placeholder = { Text("Вставьте sk-or-v1-...", fontSize = 12.sp, color = TextMuted) },
                            singleLine = true,
                            visualTransformation = if (isOpenRouterKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(
                                    onClick = { isOpenRouterKeyVisible = !isOpenRouterKeyVisible },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            id = if (isOpenRouterKeyVisible) R.drawable.ic_eye else R.drawable.ic_eye_slash
                                        ),
                                        contentDescription = "Видимость ключа",
                                        tint = if (isOpenRouterKeyVisible) VpnAccent else TextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VpnAccent,
                                unfocusedBorderColor = AmoledBorder,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Блок выбора модели
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "ВЫБОР МОДЕЛИ ДЛЯ $selectedProvider",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = accentColor
            )
            Text(
                text = "Каждая модель обладает своей специализацией, скоростью и глубиной рассуждений. Выберите оптимальную конфигурацию:",
                fontSize = 12.sp,
                color = TextMuted,
                lineHeight = 16.sp
            )

            val modelDetails = when (selectedProvider) {
                "Cloudflare" -> listOf(
                    OnboardingModelDetail(
                        id = "@cf/meta/llama-3.3-70b-instruct",
                        shortName = "Llama 3.3 70B",
                        badge = "ФЛАГМАН 70B",
                        badgeColor = accentColor,
                        tierLabel = "Самая умная · Максимальный интеллект",
                        description = "Флагманская 70-миллиардная открытая модель Meta на серверах Cloudflare. Комплексный технический анализ сетевых сокетов, сопоставление трафика и глубокие инженерные выкладки.",
                        bestFor = "Основная универсальная модель для диагностики прокси и анализа логов."
                    ),
                    OnboardingModelDetail(
                        id = "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b",
                        shortName = "DeepSeek R1 32B",
                        badge = "REASONING",
                        badgeColor = Socks5Accent,
                        tierLabel = "Экстремальная логика · Пошаговые рассуждения",
                        description = "Модель глубоких рассуждений (Chain-of-Thought). Долго и тщательно обдумывает проблему перед ответом, выявляя скрытые сетевые аномалии, сбои TLS и блокировки ТСПУ/DPI.",
                        bestFor = "Расследование сложных сетевых инцидентов, ошибок сокетов и отладка туннелей."
                    ),
                    OnboardingModelDetail(
                        id = "@cf/qwen/qwen2.5-72b-instruct",
                        shortName = "Qwen 2.5 72B",
                        badge = "ДЛЯ КОДА И JSON",
                        badgeColor = VpnAccent,
                        tierLabel = "Высокий интеллект · Программирование",
                        description = "Крупная модель 72B от Alibaba, специально обученная на коде и строгих форматах данных. Безупречно генерирует векторные pathData для SVG-иконок кнопок и парсит дампы.",
                        bestFor = "Генератор векторных SVG-иконок для интерфейса и разбор JSON-конфигураций."
                    ),
                    OnboardingModelDetail(
                        id = "@cf/meta/llama-3.1-8b-instruct",
                        shortName = "Llama 3.1 8B",
                        badge = "БЫСТРАЯ 8B",
                        badgeColor = TextMuted,
                        tierLabel = "Базовый интеллект · Скоростная",
                        description = "Компактная легковесная модель. Быстро генерирует ответ при минимальной задержке первого токена, но менее глубока в сложных вычислениях.",
                        bestFor = "Быстрые повседневные вопросы и лаконичные текстовые справки."
                    ),
                    OnboardingModelDetail(
                        id = "@cf/mistral/mistral-7b-instruct-v0.2",
                        shortName = "Mistral 7B",
                        badge = "ЭКОНОМИЧНАЯ 7B",
                        badgeColor = TextMuted,
                        tierLabel = "Базовый интеллект · Легкая",
                        description = "Компактная европейская модель 7B от Mistral AI с лаконичным стилем изложения.",
                        bestFor = "Быстрые справки при медленном соединении."
                    )
                )

                "OpenRouter" -> listOf(
                    OnboardingModelDetail(
                        id = "deepseek/deepseek-r1",
                        shortName = "DeepSeek R1",
                        badge = "REASONING",
                        badgeColor = accentColor,
                        tierLabel = "Самая умная · Экстремальная логика",
                        description = "Передовая открытая reasoning-модель мирового класса. Проводит пошаговый математический и архитектурный анализ каждого аспекта сетевого стека.",
                        bestFor = "Сложные сетевые расследования, анализ дампов трафика и глубокий аудит."
                    ),
                    OnboardingModelDetail(
                        id = "deepseek/deepseek-chat",
                        shortName = "DeepSeek V3",
                        badge = "УНИВЕРСАЛЬНАЯ V3",
                        badgeColor = accentColor,
                        tierLabel = "Высочайший интеллект · Быстрая",
                        description = "Полноразмерная модель DeepSeek V3. Высочайший общий интеллект, безупречный русский язык и молниеносная скорость инференса.",
                        bestFor = "Рекомендуется по умолчанию для диалогов, настройки прокси и сетевых консультаций."
                    ),
                    OnboardingModelDetail(
                        id = "anthropic/claude-3.5-sonnet",
                        shortName = "Claude 3.5 Sonnet",
                        badge = "ЭТАЛОН ТОЧНОСТИ",
                        badgeColor = VpnAccent,
                        tierLabel = "Премиальный интеллект",
                        description = "Мировой эталон среди коммерческих моделей по точности следования директивам, качеству кода и чистой геометрии SVG.",
                        bestFor = "Генерация сложной векторной графики кнопок и строгое следование ролям."
                    ),
                    OnboardingModelDetail(
                        id = "meta-llama/llama-3.3-70b-instruct",
                        shortName = "Llama 3.3 70B",
                        badge = "МОЩНАЯ 70B",
                        badgeColor = Socks5Accent,
                        tierLabel = "Высокий интеллект",
                        description = "Полноразмерная модель Meta 70B параметров через скоростные сервера OpenRouter.",
                        bestFor = "Универсальный помощник по сетевому стеку."
                    ),
                    OnboardingModelDetail(
                        id = "google/gemini-2.5-flash",
                        shortName = "Gemini 2.5 Flash",
                        badge = "УЛЬТРАБЫСТРАЯ",
                        badgeColor = TextMuted,
                        tierLabel = "Высокий интеллект · Быстрая",
                        description = "Скоростная модель Google в облаке OpenRouter для мгновенных ответов.",
                        bestFor = "Быстрые вопросы и мгновенный отклик."
                    )
                )

                else -> listOf(
                    OnboardingModelDetail(
                        id = "gemini-3.6-flash",
                        shortName = "Gemini 3.6 Flash",
                        badge = "ФЛАГМАН 2026",
                        badgeColor = accentColor,
                        tierLabel = "Самая умная · Максимальный интеллект",
                        description = "Новейшая флагманская Flash-модель Google 2026 года. Обладает глубочайшим пониманием протоколов MTProto, SOCKS5 и Cloudflare WSS, безупречно следует сложным инструкциям.",
                        bestFor = "Рекомендуется по умолчанию: автономная диагностика сокетов, вызов функций Function Calling и решение сложных инцидентов."
                    ),
                    OnboardingModelDetail(
                        id = "gemini-3.5-flash",
                        shortName = "Gemini 3.5 Flash",
                        badge = "СБАЛАНСИРОВАННАЯ",
                        badgeColor = accentColor.copy(alpha = 0.8f),
                        tierLabel = "Высокий интеллект",
                        description = "Надежная рабочая модель с отличным балансом между глубиной анализа и задержкой ответа.",
                        bestFor = "Анализ системных логов, мониторинг сбоев и типовые консультации."
                    ),
                    OnboardingModelDetail(
                        id = "gemini-2.5-flash",
                        shortName = "Gemini 2.5 Flash",
                        badge = "УЛЬТРАБЫСТРАЯ",
                        badgeColor = Socks5Accent,
                        tierLabel = "Высокая скорость · Мгновенный отклик",
                        description = "Проверенная сверхбыстрая модель. Минимальная задержка вывода первого токена.",
                        bestFor = "Мгновенные ответы, быстрые справки и генерация векторных SVG-иконок для кнопок."
                    ),
                    OnboardingModelDetail(
                        id = "gemini-2.5-flash-lite",
                        shortName = "Gemini 2.5 Lite",
                        badge = "ЭКОНОМИЧНАЯ",
                        badgeColor = TextMuted,
                        tierLabel = "Базовый интеллект · Легкая",
                        description = "Облегченная версия с минимальным расходом квоты. Подходит для простых запросов, но уступает флагманам в глубоких инженерных расчетах.",
                        bestFor = "Экономия лимитов и базовые вопросы."
                    )
                )
            }

            modelDetails.forEach { model ->
                val isSelected = (selectedModel == model.id)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    border = if (isSelected) {
                        BorderStroke(1.5.dp, accentColor)
                    } else {
                        BorderStroke(1.dp, AmoledBorder)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .lightSweep(isEnabled = isSelected, shape = RoundedCornerShape(14.dp))
                        .springPress(onClick = { onModelSelected(model.id) })
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.shortName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) accentColor else TextWhite
                                )
                                Text(
                                    text = model.id,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextMuted
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, model.badgeColor.copy(alpha = 0.7f))
                                ) {
                                    Text(
                                        text = model.badge,
                                        color = model.badgeColor,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                if (isSelected) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_check),
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = model.tierLabel,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) accentColor else accentColor.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace
                        )

                        Text(
                            text = model.description,
                            fontSize = 12.sp,
                            color = Color(0xFFD4D4DC),
                            lineHeight = 16.5.sp
                        )

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Назначение: ${model.bestFor}",
                                fontSize = 11.sp,
                                color = TextMuted,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ШАГ 4: СИСТЕМНЫЕ ИНСТРУКЦИИ (АККОРДЕОНЫ)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepSystemPrompts(
    currentMode: String,
    accentColor: Color,
    onModeSelected: (String) -> Unit,
    onAccordionInteracted: () -> Unit
) {
    var expandedPrompt by remember { mutableStateOf<String?>(currentMode) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "ШАГ 4: СИСТЕМНЫЕ ИНСТРУКЦИИ (ПРОМПТЫ)",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp,
                color = accentColor
            )
            Text(
                text = "Нажмите на карточку, чтобы развернуть и прочитать системную инструкцию. Выберите желаемый профиль работы нейросети.",
                fontSize = 13.sp,
                color = Color(0xFFD4D4DC),
                lineHeight = 18.sp
            )
        }

        // 1. Диагностический режим
        PromptAccordionItem(
            mode = AiPrompts.MODE_DIAGNOSTIC,
            title = "Диагностический режим",
            badge = "ПО УМОЛЧАНИЮ",
            shortDesc = "Автономный сетевой инженер Mirrly TG Proxy. Анализирует телеметрию, выявляет блокировки ТСПУ РКН и сбои соединения.",
            fullPromptText = "Системная директива:\n" +
                "«Ты — встроенный технический диагност Mirrly TG Proxy. При любых вопросах о сбоях ОБЯЗАТЕЛЬНО вызови диагностические инструменты Function Calling: get_service_status(), get_network_health(), get_recent_failure_logs() или test_uplink_connection(). Формулируй ответы строго по делу, без эмодзи и маркетинговых штампов. Поддерживаешь генерацию SVG-иконок для кнопок MTProto, SOCKS5 и VPN.»",
            isSelected = (currentMode == AiPrompts.MODE_DIAGNOSTIC),
            isExpanded = (expandedPrompt == AiPrompts.MODE_DIAGNOSTIC),
            accentColor = accentColor,
            onToggleExpand = {
                expandedPrompt = if (expandedPrompt == AiPrompts.MODE_DIAGNOSTIC) null else AiPrompts.MODE_DIAGNOSTIC
                onAccordionInteracted()
            },
            onSelectMode = {
                onModeSelected(AiPrompts.MODE_DIAGNOSTIC)
                onAccordionInteracted()
            }
        )

        // 2. Дружелюбный помощник
        PromptAccordionItem(
            mode = AiPrompts.MODE_FUN,
            title = "Дружелюбный помощник",
            badge = "ДЛЯ НАЧИНАЮЩИХ",
            shortDesc = "Лаконичные и понятные ответы без избыточного сетевого сленга. Помогает настроить прокси простыми словами.",
            fullPromptText = "Системная директива:\n" +
                "«Ты — остроумный и дружелюбный помощник в приложении Mirrly TG Proxy. Отвечай на вопросы пользователя доступно, помогай настроить прокси Telegram и VPN без сложной математической терминологии, сохраняя доброжелательный тон.»",
            isSelected = (currentMode == AiPrompts.MODE_FUN),
            isExpanded = (expandedPrompt == AiPrompts.MODE_FUN),
            accentColor = accentColor,
            onToggleExpand = {
                expandedPrompt = if (expandedPrompt == AiPrompts.MODE_FUN) null else AiPrompts.MODE_FUN
                onAccordionInteracted()
            },
            onSelectMode = {
                onModeSelected(AiPrompts.MODE_FUN)
                onAccordionInteracted()
            }
        )

        // 3. Без инструкции (Чистый ИИ)
        PromptAccordionItem(
            mode = AiPrompts.MODE_NONE,
            title = "Без инструкции (Чистый ИИ)",
            badge = "БАЗОВЫЙ",
            shortDesc = "Стандартное поведение выбранной языковой модели без системных ограничений и специализированных рамок.",
            fullPromptText = "Системный промпт не передается. Модель функционирует в соответствии с оригинальным заводским обучением разработчиков (Google / Meta / Anthropic).",
            isSelected = (currentMode == AiPrompts.MODE_NONE),
            isExpanded = (expandedPrompt == AiPrompts.MODE_NONE),
            accentColor = accentColor,
            onToggleExpand = {
                expandedPrompt = if (expandedPrompt == AiPrompts.MODE_NONE) null else AiPrompts.MODE_NONE
                onAccordionInteracted()
            },
            onSelectMode = {
                onModeSelected(AiPrompts.MODE_NONE)
                onAccordionInteracted()
            }
        )

        // 4. Пользовательский промпт
        PromptAccordionItem(
            mode = AiPrompts.MODE_CUSTOM,
            title = "Пользовательский промпт",
            badge = "КАСТОМНЫЙ",
            shortDesc = "Ваши собственные инструкции, правила форматирования и язык общения, заданные в настройках ИИ.",
            fullPromptText = "Вы можете ввести любой произвольный системный промпт в шторке настроек ИИ (AiSettingsSheet) в любое время.",
            isSelected = (currentMode == AiPrompts.MODE_CUSTOM),
            isExpanded = (expandedPrompt == AiPrompts.MODE_CUSTOM),
            accentColor = accentColor,
            onToggleExpand = {
                expandedPrompt = if (expandedPrompt == AiPrompts.MODE_CUSTOM) null else AiPrompts.MODE_CUSTOM
                onAccordionInteracted()
            },
            onSelectMode = {
                onModeSelected(AiPrompts.MODE_CUSTOM)
                onAccordionInteracted()
            }
        )
    }
}

@Composable
private fun PromptAccordionItem(
    mode: String,
    title: String,
    badge: String,
    shortDesc: String,
    fullPromptText: String,
    isSelected: Boolean,
    isExpanded: Boolean,
    accentColor: Color,
    onToggleExpand: () -> Unit,
    onSelectMode: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = if (isSelected) {
            BorderStroke(1.5.dp, accentColor)
        } else {
            BorderStroke(1.dp, AmoledBorder)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .lightSweep(isEnabled = isSelected, shape = RoundedCornerShape(14.dp))
            .springPress(onClick = onToggleExpand)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) accentColor else TextWhite
                )

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, (if (isSelected) accentColor else TextMuted).copy(alpha = 0.6f))
                ) {
                    Text(
                        text = badge,
                        color = if (isSelected) accentColor else TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = shortDesc,
                fontSize = 12.sp,
                color = Color(0xFFD4D4DC),
                lineHeight = 16.sp
            )

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = AmoledBorder.copy(alpha = 0.5f))

                    Text(
                        text = fullPromptText,
                        fontSize = 11.5.sp,
                        color = TextWhite,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, if (isSelected) accentColor else AmoledBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .springPress(onClick = onSelectMode)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isSelected) "Выбран данный режим" else "Выбрать этот режим",
                                color = if (isSelected) accentColor else TextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ШАГ 5: ВОЗМОЖНОСТИ И ВЫЗОВ ИНСТРУМЕНТОВ FUNCTION CALLING
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepFeaturesAndTools(
    accentColor: Color,
    onAccordionInteracted: () -> Unit
) {
    val expandedTools = remember { mutableStateListOf<String>() }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "ШАГ 5: ВОЗМОЖНОСТИ И FUNCTION CALLING",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp,
                color = accentColor
            )
            Text(
                text = "Изучите ключевые возможности Mirrly AI: автономную диагностику сокетов, генератор SVG и системные инструменты.",
                fontSize = 13.sp,
                color = Color(0xFFD4D4DC),
                lineHeight = 18.sp
            )
        }

        // 1. Автономная диагностика прокси
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "1. Автономная диагностика сети",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )

                Text(
                    text = "В окне чата встроены готовые быстрые пресеты для мгновенной проверки состояния без ручного набора текста:",
                    fontSize = 12.sp,
                    color = Color(0xFFD4D4DC),
                    lineHeight = 16.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Статус сети", "Лог сбоев", "Полная диагностика").forEach { chip ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = chip,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = accentColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Генератор векторных SVG-иконок
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "2. Генератор векторных SVG-иконок кнопок",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )

                Text(
                    text = "Нейросеть обучена проектировать чистую векторную геометрию (pathData) для центральных кнопок главного экрана. Вы можете попросить «Сделай иконку сокола для кнопки MTProto», и ИИ сформирует код и предложит применить его в один клик.",
                    fontSize = 12.sp,
                    color = Color(0xFFD4D4DC),
                    lineHeight = 17.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "MTProto",
                            color = accentColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Socks5Accent.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "SOCKS5",
                            color = Socks5Accent,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, VpnAccent.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "VPN",
                            color = VpnAccent,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // 3. Инструменты Function Calling (Аккордеоны)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "3. ИНСТРУМЕНТЫ FUNCTION CALLING (6 ИНСТРУМЕНТОВ)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = accentColor
            )

            // Tool 1
            ToolAccordionItem(
                toolId = "get_service_status",
                russianName = "Статус службы и портов",
                systemName = "get_service_status()",
                desc = "Предоставляет статус фоновой службы прокси, состояние локальных сокетов на портах 1080 (MTProto) и 10808 (SOCKS5), количество активных клиентских соединений и общее время непрерывной работы.",
                isExpanded = expandedTools.contains("get_service_status"),
                accentColor = accentColor,
                onToggle = {
                    if (expandedTools.contains("get_service_status")) expandedTools.remove("get_service_status")
                    else expandedTools.add("get_service_status")
                    onAccordionInteracted()
                }
            )

            // Tool 2
            ToolAccordionItem(
                toolId = "get_network_health",
                russianName = "Здоровье сети и задержка",
                systemName = "get_network_health()",
                desc = "Анализирует активный сетевой интерфейс (Wi-Fi или сотовая связь LTE/5G), задержку RTT до Anycast-шлюза, уровень джиттера пакетов и активность системы аварийного переключения сокетов (Failover).",
                isExpanded = expandedTools.contains("get_network_health"),
                accentColor = accentColor,
                onToggle = {
                    if (expandedTools.contains("get_network_health")) expandedTools.remove("get_network_health")
                    else expandedTools.add("get_network_health")
                    onAccordionInteracted()
                }
            )

            // Tool 3
            ToolAccordionItem(
                toolId = "get_recent_failure_logs",
                russianName = "Срез журнала сетевых сбоев",
                systemName = "get_recent_failure_logs()",
                desc = "Извлекает недавние системные инциденты и ошибки соединения из кольцевого буфера логов приложения для точного выявления блокировок провайдера (DPI ТСПУ) или сбоев TLS/WSS.",
                isExpanded = expandedTools.contains("get_recent_failure_logs"),
                accentColor = accentColor,
                onToggle = {
                    if (expandedTools.contains("get_recent_failure_logs")) expandedTools.remove("get_recent_failure_logs")
                    else expandedTools.add("get_recent_failure_logs")
                    onAccordionInteracted()
                }
            )

            // Tool 4
            ToolAccordionItem(
                toolId = "test_uplink_connection",
                russianName = "Тест магистрального канала",
                systemName = "test_uplink_connection()",
                desc = "Выполняет замер скорости отклика и доступности магистральных узлов Cloudflare Edge. Помогает определить наилучший дата-центр маршрутизации.",
                isExpanded = expandedTools.contains("test_uplink_connection"),
                accentColor = accentColor,
                onToggle = {
                    if (expandedTools.contains("test_uplink_connection")) expandedTools.remove("test_uplink_connection")
                    else expandedTools.add("test_uplink_connection")
                    onAccordionInteracted()
                }
            )

            // Tool 5
            ToolAccordionItem(
                toolId = "get_reference_icon_code",
                russianName = "Эталонная векторная геометрия",
                systemName = "get_reference_icon_code(protocol)",
                desc = "Загружает проверенный разработчиками каркас сплайнов (Вьюпорт 100x100, Stroke 1.5-2.0dp, замкнутые контуры Z) для MTProto, SOCKS5 или VPN перед генерацией новой кнопки.",
                isExpanded = expandedTools.contains("get_reference_icon_code"),
                accentColor = accentColor,
                onToggle = {
                    if (expandedTools.contains("get_reference_icon_code")) expandedTools.remove("get_reference_icon_code")
                    else expandedTools.add("get_reference_icon_code")
                    onAccordionInteracted()
                }
            )

            // Tool 6
            ToolAccordionItem(
                toolId = "apply_svg_button_icon",
                russianName = "Применение SVG иконки в интерфейс",
                systemName = "apply_svg_button_icon(protocol, pathData)",
                desc = "Интерактивно компилирует созданную векторную иконку, проверяет корректность pathData и сохраняет её в настройки центральной кнопки выбранного протокола в один клик.",
                isExpanded = expandedTools.contains("apply_svg_button_icon"),
                accentColor = accentColor,
                onToggle = {
                    if (expandedTools.contains("apply_svg_button_icon")) expandedTools.remove("apply_svg_button_icon")
                    else expandedTools.add("apply_svg_button_icon")
                    onAccordionInteracted()
                }
            )
        }

        // Заметка о маскировании приватности
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Безопасность: все данные перед отправкой в нейросеть проходят локальную деперсонализацию фильтром AiSanitizer. Персональные IP-адреса, токены и приватные ключи маскируются.",
                fontSize = 11.sp,
                color = TextMuted,
                lineHeight = 15.sp,
                modifier = Modifier.padding(12.dp)
            )
        }

        // Повторное прохождение руководства
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(accentColor)
                    )
                    Text(
                        text = "ПОВТОРНОЕ ПРОХОЖДЕНИЕ ОБУЧЕНИЯ",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = accentColor
                    )
                }

                Text(
                    text = "Вы в любое время можете вернуться к данному руководству и перечитать инструкции:\n• В шторке настроек ИИ-чата (кнопка параметров в правом верхнем углу).\n• В общих настройках: Настройки → Справка и руководства → Вводное руководство по ИИ.\n\nПри повторном открытии из настроек 5-секундный таймер автоматически отключается, позволяя свободно перемещаться по всем шагам без задержек.",
                    fontSize = 12.sp,
                    color = TextWhite,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun ToolAccordionItem(
    toolId: String,
    russianName: String,
    systemName: String,
    desc: String,
    isExpanded: Boolean,
    accentColor: Color,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, AmoledBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .springPress(onClick = onToggle)
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
                Column {
                    Text(
                        text = russianName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        text = systemName,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = accentColor
                    )
                }

                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = if (isExpanded) accentColor else TextMuted,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer {
                            rotationZ = if (isExpanded) 90f else 0f
                        }
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Text(
                    text = desc,
                    fontSize = 11.5.sp,
                    color = Color(0xFFD4D4DC),
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
