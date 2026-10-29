/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.annotation.DrawableRes
import com.mirrly.tgproxy.ui.theme.fadingEdges
import com.mirrly.tgproxy.ui.theme.horizontalFadingEdges
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ai.AiChatMessage
import com.mirrly.tgproxy.ai.AiDiagnosticTools
import com.mirrly.tgproxy.ai.AiHistoryManager
import com.mirrly.tgproxy.ai.AiKeyManager
import com.mirrly.tgproxy.ai.AiPrompts
import com.mirrly.tgproxy.ai.GeminiApiClient
import com.mirrly.tgproxy.ai.RateLimitException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Основной экран Mirrly AI BYOK Engine.
 * Дизайн-система: MirrlyUI (Outline-Only, Zero-Popup, Zero Emojis, Motion Engine 60/120 FPS).
 */
@Composable
fun AiChatScreen(
    onClose: () -> Unit = {},
    onBack: () -> Unit = onClose
) {
    val handleBack = {
        onClose()
        onBack()
    }
    BackHandler { handleBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    val app = MirrlyApplication.instance
    val prefs = app.prefsManager
    val apiClient = remember { GeminiApiClient() }

    var selectedModel by remember { mutableStateOf(prefs.getAiModel()) }
    var temperature by remember { mutableFloatStateOf(prefs.getAiTemperature()) }
    var reasoningEnabled by remember { mutableStateOf(prefs.isAiReasoningEnabled()) }
    var useSocks5 by remember { mutableStateOf(prefs.isAiUseSocks5()) }
    var promptMode by remember { mutableStateOf(prefs.getAiSystemPromptMode()) }
    var customPrompt by remember { mutableStateOf(prefs.getAiCustomSystemPrompt()) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    val chatBlurRadius by animateDpAsState(
        targetValue = if (showSettingsSheet) 16.dp else 0.dp,
        animationSpec = tween(220),
        label = "chatBlur"
    )

    val messages = remember { mutableStateListOf<AiChatMessage>() }
    var inputMessage by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var rateLimitCountdown by remember { mutableIntStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }

    // Загрузка сохраненных сообщений
    LaunchedEffect(Unit) {
        val saved = AiHistoryManager.loadMessages(context)
        messages.clear()
        messages.addAll(saved)
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    // Сохранение при выходе
    DisposableEffect(Unit) {
        onDispose {
            AiHistoryManager.saveMessages(context, messages)
            apiClient.cancelCurrent()
        }
    }

    fun startCountdown(seconds: Int) {
        countdownJob?.cancel()
        rateLimitCountdown = seconds
        countdownJob = scope.launch {
            while (rateLimitCountdown > 0) {
                delay(1000)
                rateLimitCountdown--
            }
        }
    }

    fun startNewChat() {
        apiClient.cancelCurrent()
        isGenerating = false
        messages.clear()
        AiHistoryManager.clear(context)
        inputMessage = ""
        focusManager.clearFocus()
    }

    fun sendMessage(userText: String) {
        val cleanText = userText.trim()
        if (cleanText.isEmpty() || isGenerating) return

        val apiKey = AiKeyManager.getApiKey(context)
        if (apiKey.isEmpty()) {
            showSettingsSheet = true
            return
        }

        val userMessage = AiChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = cleanText
        )
        messages.add(userMessage)
        inputMessage = ""
        focusManager.clearFocus()

        val assistantMessageId = UUID.randomUUID().toString()
        val assistantMessage = AiChatMessage(
            id = assistantMessageId,
            role = "model",
            text = "",
            thought = "",
            isExecutingTool = false
        )
        messages.add(assistantMessage)
        isGenerating = true

        val effectiveInstruction = when (promptMode) {
            AiPrompts.MODE_NONE -> ""
            AiPrompts.MODE_FUN -> AiPrompts.PROMPT_FUN
            AiPrompts.MODE_CUSTOM -> if (customPrompt.isNotBlank()) customPrompt else ""
            else -> AiPrompts.PROMPT_DIAGNOSTIC
        }

        scope.launch {
            listState.animateScrollToItem(messages.size - 1)

            val result = apiClient.sendMessageStream(
                context = context,
                history = messages.dropLast(2),
                userPrompt = cleanText,
                endpoint = prefs.getAiEndpoint(),
                model = selectedModel,
                apiKey = apiKey,
                temperature = temperature,
                reasoningEnabled = reasoningEnabled,
                systemInstruction = effectiveInstruction,
                useSocks5 = useSocks5,
                onToolCallStarted = { toolName ->
                    val idx = messages.indexOfFirst { it.id == assistantMessageId }
                    if (idx != -1) {
                        messages[idx] = messages[idx].copy(
                            isExecutingTool = true,
                            toolName = toolName
                        )
                    }
                },
                onToolCallFinished = { _, _ ->
                    val idx = messages.indexOfFirst { it.id == assistantMessageId }
                    if (idx != -1) {
                        messages[idx] = messages[idx].copy(isExecutingTool = false)
                    }
                },
                onChunk = { textChunk, thoughtChunk ->
                    val idx = messages.indexOfFirst { it.id == assistantMessageId }
                    if (idx != -1) {
                        val current = messages[idx]
                        messages[idx] = current.copy(
                            text = current.text + textChunk,
                            thought = current.thought + thoughtChunk
                        )
                    }
                }
            )

            isGenerating = false
            result.onSuccess { (finalText, finalThought) ->
                val idx = messages.indexOfFirst { it.id == assistantMessageId }
                if (idx != -1) {
                    messages[idx] = messages[idx].copy(
                        text = finalText,
                        thought = finalThought,
                        isExecutingTool = false
                    )
                }
                AiHistoryManager.saveMessages(context, messages)
            }.onFailure { err ->
                val idx = messages.indexOfFirst { it.id == assistantMessageId }
                if (err is RateLimitException) {
                    startCountdown(err.retryAfterSeconds)
                    if (idx != -1) {
                        messages[idx] = messages[idx].copy(
                            text = "Лимит бесплатных запросов исчерпан. Повторите попытку через ${err.retryAfterSeconds} сек.",
                            isError = true,
                            isExecutingTool = false
                        )
                    }
                } else {
                    if (idx != -1) {
                        val currentText = messages[idx].text
                        if (currentText.isBlank()) {
                            messages[idx] = messages[idx].copy(
                                text = "Ошибка выполнения запроса: ${err.message ?: "Сбой соединения"}",
                                isError = true,
                                isExecutingTool = false
                            )
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.25f))
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (chatBlurRadius > 0.dp) Modifier.blur(chatBlurRadius) else Modifier)
        ) {
            // ── Top Bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.65f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = handleBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_close),
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Mirrly TG Proxy",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "AI",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showSettingsSheet = true }
                        ) {
                            Text(
                                text = selectedModel,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "▾",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Кнопка "Новый чат" (иконка)
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { startNewChat() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chat_plus),
                            contentDescription = "Новый чат",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Кнопка "Настройки" (иконка)
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { showSettingsSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = "Настройки",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // ── Rate Limit 429 Inline Banner ──
            AnimatedVisibility(
                visible = rateLimitCountdown > 0,
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
                ) + fadeIn(animationSpec = tween(220)),
                exit = shrinkVertically(
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
                ) + fadeOut(animationSpec = tween(180))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .border(1.dp, Color(0xFFFF9100), RoundedCornerShape(10.dp))
                        .background(Color(0xFFFF9100).copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Лимит запросов исчерпан. Повтор через $rateLimitCountdown сек.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF9100),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "429",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFFF9100).copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // ── Баннер при отсутствии ключа ──
            if (!AiKeyManager.hasApiKey(context)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            RoundedCornerShape(10.dp)
                        )
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = "Требуется API-ключ Gemini",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Для работы автономной диагностики укажите ваш бесплатный ключ Google AI Studio. Ключ хранится локально в защищенном виде.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                        )
                        Box(
                            modifier = Modifier
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { showSettingsSheet = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Настроить ключ",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // ── Область сообщений / Центрированное приветствие ──
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Иконка AI с контурной рамкой
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    RoundedCornerShape(16.dp)
                                )
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                    RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_ai_chat),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Система диагностики готова к работе",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Задайте вопрос о работе прокси или выберите быстрый сценарий",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // 3 быстрых карточки сценариев на русском языке
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PromptCard(
                                icon = R.drawable.ic_diag_antenna,
                                title = "Почему Telegram не подключается?",
                                subtitle = "Анализ сетевого стека, сокетов и блокировок",
                                onClick = {
                                    sendMessage("Почему Telegram не подключается? Проверь статус службы, сокеты и последние сбои сети.")
                                }
                            )

                            PromptCard(
                                icon = R.drawable.ic_diag_rtt,
                                title = "Проверить статус прокси и пинг",
                                subtitle = "Замер отклика узлов Anycast и валидация портов",
                                onClick = {
                                    sendMessage("Проверь текущий статус службы прокси, активные соединения и задержку сети.")
                                }
                            )

                            PromptCard(
                                icon = R.drawable.ic_diag_protocol,
                                title = "Диагностика ошибок соединения",
                                subtitle = "Срез журнала сбоев и рекомендации по настройке",
                                onClick = {
                                    sendMessage("Проанализируй последние ошибки в журнале сбоев и предложи решения.")
                                }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 24.dp)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(msg = msg)
                    }

                    if (isGenerating && messages.lastOrNull()?.text.isNullOrBlank() && !messages.lastOrNull()?.isExecutingTool!!) {
                        item {
                            Box(
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                ThinkingDotsCanvas()
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(6.dp)) }
                }
            }

            // ── Расчет нижнего отступа клавиатуры / навбара ──
            val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val bottomInset = maxOf(imeBottom, navBottom)

            // ── Нижний блок: Чипы быстрой диагностики + Поле ввода (с точной привязкой к клавиатуре) ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.75f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.98f)
                            )
                        )
                    )
                    .padding(bottom = bottomInset)
            ) {
                // Чипы быстрой диагностики
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalFadingEdges(startFadeWidth = 12.dp, endFadeWidth = 16.dp)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickChip(title = "+ Статус сети") {
                        inputMessage = AiDiagnosticTools.getNetworkHealthChipText(context)
                    }
                    QuickChip(title = "+ Лог сбоев") {
                        inputMessage = AiDiagnosticTools.getFailureLogsChipText(20)
                    }
                    QuickChip(title = "+ Полная диагностика") {
                        inputMessage = AiDiagnosticTools.getFullDiagnosticChipText(context)
                    }
                }

                // Поле ввода и кнопка Отправка/Стоп
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputMessage,
                        onValueChange = { inputMessage = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                text = "Введите запрос или проблему...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        },
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Кнопка Отправка / Стоп с кинетической анимацией
                    AnimatedContent(
                        targetState = isGenerating,
                        transitionSpec = {
                            (scaleIn(tween(220)) + rotateIn) togetherWith (scaleOut(tween(180)) + rotateOut)
                        },
                        label = "SendStopButton"
                    ) { generating ->
                        if (generating) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(10.dp))
                                    .clickable {
                                        apiClient.cancelCurrent()
                                        isGenerating = false
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp))
                                )
                            }
                        } else {
                            val canSend = inputMessage.isNotBlank()
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .border(
                                        1.dp,
                                        if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable(enabled = canSend) {
                                        sendMessage(inputMessage)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_send),
                                    contentDescription = "Отправить",
                                    tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        AiSettingsSheet(
            onDismiss = { showSettingsSheet = false },
            selectedModel = selectedModel,
            onModelSelected = {
                selectedModel = it
                prefs.setAiModel(it)
            },
            temperature = temperature,
            onTemperatureChanged = {
                temperature = it
                prefs.setAiTemperature(it)
            },
            reasoningEnabled = reasoningEnabled,
            onReasoningToggled = {
                reasoningEnabled = it
                prefs.setAiReasoningEnabled(it)
            },
            useSocks5 = useSocks5,
            onUseSocks5Toggled = {
                useSocks5 = it
                prefs.setAiUseSocks5(it)
            },
            promptMode = promptMode,
            onPromptModeChanged = {
                promptMode = it
                prefs.setAiSystemPromptMode(it)
            },
            customPrompt = customPrompt,
            onCustomPromptChanged = {
                customPrompt = it
                prefs.setAiCustomSystemPrompt(it)
            },
            onClearHistory = {
                startNewChat()
            }
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Вспомогательные компоненты и анимации
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(msg: AiChatMessage) {
    val isUser = msg.isUser
    val alignment = if (isUser) Alignment.End else Alignment.Start

    // Каскадный вход нового сообщения (Staggered Entrance)
    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(
            initialOffsetY = { fullHeight -> (fullHeight * 0.2f).toInt().coerceAtMost(48) },
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(300))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = alignment
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 36.dp, max = 320.dp)
                    .runningBeamBorder(isExecuting = msg.isExecutingTool)
                    .background(
                        if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.wrapContentSize(),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                ) {
                    if (msg.toolName != null || msg.isExecutingTool) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = "Инструмент: ${msg.toolName ?: "выполняется сбор данных..."}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (msg.isExecutingTool) {
                                Spacer(modifier = Modifier.width(6.dp))
                                ThinkingDotsCanvas(modifier = Modifier.size(width = 24.dp, height = 12.dp))
                            }
                        }
                    }

                    // Аккордеон рассуждений (Thinking Process)
                    if (msg.thought.isNotBlank()) {
                        ThinkingAccordion(thoughtText = msg.thought)
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    if (msg.text.isNotBlank()) {
                        SelectionContainer {
                            if (msg.isError) {
                                Text(
                                    text = msg.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    lineHeight = 19.sp
                                )
                            } else {
                                AiMarkdownRenderer(text = msg.text)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Модели блоков Markdown для чистого отделения парсинга от рендеринга.
 */
private sealed interface MarkdownBlock {
    data class Code(val content: String) : MarkdownBlock
    data class Table(val rows: List<String>) : MarkdownBlock
    data object Divider : MarkdownBlock
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class ListItem(val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data object EmptyLine : MarkdownBlock
}

/**
 * Чистая функция парсинга Markdown в список типизированных блоков.
 */
private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MarkdownBlock>()

    var inCodeBlock = false
    val codeBlockLines = mutableListOf<String>()

    var inTable = false
    val tableLines = mutableListOf<String>()

    fun flushTable() {
        if (tableLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.Table(tableLines.toList()))
            tableLines.clear()
            inTable = false
        }
    }

    fun flushCodeBlock() {
        if (codeBlockLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.Code(codeBlockLines.joinToString("\n")))
            codeBlockLines.clear()
            inCodeBlock = false
        }
    }

    for (line in lines) {
        val trimmed = line.trim()

        // Проверка блока кода
        if (trimmed.startsWith("```")) {
            flushTable()
            if (inCodeBlock) {
                flushCodeBlock()
            } else {
                inCodeBlock = true
            }
            continue
        }

        if (inCodeBlock) {
            codeBlockLines.add(line)
            continue
        }

        // Проверка строк таблицы Markdown (| col | col |)
        if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length > 2) {
            inTable = true
            tableLines.add(trimmed)
            continue
        } else if (inTable) {
            flushTable()
        }

        when {
            // Разделитель: ---, ***, ___ (СТРОГО отдельная строка или 3+ символа разделителя)
            trimmed == "---" || trimmed == "***" || trimmed == "___" || trimmed.matches(Regex("^[-*_]{3,}$")) -> {
                blocks.add(MarkdownBlock.Divider)
            }

            // Заголовки (проверяем строго от h6 к h1)
            trimmed.startsWith("###### ") -> {
                blocks.add(MarkdownBlock.Header(6, trimmed.removePrefix("###### ").trim()))
            }
            trimmed.startsWith("##### ") -> {
                blocks.add(MarkdownBlock.Header(5, trimmed.removePrefix("##### ").trim()))
            }
            trimmed.startsWith("#### ") -> {
                blocks.add(MarkdownBlock.Header(4, trimmed.removePrefix("#### ").trim()))
            }
            trimmed.startsWith("### ") -> {
                blocks.add(MarkdownBlock.Header(3, trimmed.removePrefix("### ").trim()))
            }
            trimmed.startsWith("## ") -> {
                blocks.add(MarkdownBlock.Header(2, trimmed.removePrefix("## ").trim()))
            }
            trimmed.startsWith("# ") -> {
                blocks.add(MarkdownBlock.Header(1, trimmed.removePrefix("# ").trim()))
            }

            // Цитата
            trimmed.startsWith("> ") -> {
                blocks.add(MarkdownBlock.Quote(trimmed.removePrefix("> ").trim()))
            }

            // Списки (маркированные)
            trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("+ ") -> {
                blocks.add(MarkdownBlock.ListItem(trimmed.substring(2).trim()))
            }

            // Списки (нумерованные: 1. 2. 10. и т.д.)
            trimmed.matches(Regex("^\\d+\\.\\s+.*$")) -> {
                val num = trimmed.substringBefore(". ").trim() + "."
                val itemText = trimmed.substringAfter(". ").trim()
                blocks.add(MarkdownBlock.NumberedItem(num, itemText))
            }

            trimmed.isNotBlank() -> {
                blocks.add(MarkdownBlock.Paragraph(trimmed))
            }

            else -> {
                blocks.add(MarkdownBlock.EmptyLine)
            }
        }
    }

    flushTable()
    flushCodeBlock()

    return blocks
}

/**
 * Парсер и рендерер Markdown с поддержкой:
 * - Заголовков (#, ##, ###, ####, #####, ######)
 * - Разделителей (---, ***)
 * - Маркированных и нумерованных списков (*, -, +, 1.)
 * - Цитат (> )
 * - Блоков кода (```) с моноширинным шрифтом и кнопкой копирования
 * - Таблиц Markdown (| col1 | col2 |)
 * - Инлайн-форматирования (**жирный**, *курсив*, `код`)
 */
@Composable
fun AiMarkdownRenderer(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    Column(
        modifier = modifier.wrapContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> {
                    CodeBlockCard(block.content, context)
                }

                is MarkdownBlock.Table -> {
                    MarkdownTableCard(block.rows)
                }

                is MarkdownBlock.Divider -> {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }

                is MarkdownBlock.Header -> {
                    when (block.level) {
                        1 -> Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                        2 -> Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                        3 -> Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        4 -> Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        5 -> Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        else -> Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                is MarkdownBlock.Quote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(20.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                        )
                    }
                }

                is MarkdownBlock.NumberedItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = block.number,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            lineHeight = 19.sp
                        )
                        Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 19.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MarkdownBlock.ListItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 19.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 19.sp
                    )
                }

                is MarkdownBlock.EmptyLine -> {
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}

/**
 * Рендерер таблицы Markdown (| col | col |) в контурный грид.
 */
@Composable
private fun MarkdownTableCard(lines: List<String>) {
    if (lines.isEmpty()) return

    // Фильтруем строки разделителей (|---|---|)
    val contentRows = lines.filter { line ->
        val clean = line.replace("|", "").replace("-", "").replace(":", "").trim()
        clean.isNotEmpty()
    }

    if (contentRows.isEmpty()) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            contentRows.forEachIndexed { idx, rowStr ->
                val cells = rowStr.split("|")
                    .map { it.trim() }
                    .filterIndexed { i, _ -> i != 0 && i != rowStr.split("|").lastIndex }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    cells.forEach { cell ->
                        Text(
                            text = parseInlineMarkdown(cell),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (idx == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (idx == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.widthIn(min = 60.dp)
                        )
                    }
                }

                if (idx == 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockCard(codeText: String, context: Context) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Код / Конфигурация",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                Box(
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Code", codeText))
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Копировать",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = codeText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

/**
 * Парсер инлайн разметки (**жирный**, *курсив*, `код`).
 */
fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val n = text.length

        while (i < n) {
            when {
                // Код: `code`
                text[i] == '`' -> {
                    val endIdx = text.indexOf('`', i + 1)
                    if (endIdx != -1) {
                        val code = text.substring(i + 1, endIdx)
                        withStyle(
                            style = SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF00E5FF),
                                background = Color.Transparent
                            )
                        ) {
                            append(" $code ")
                        }
                        i = endIdx + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                // Жирный: **text**
                i + 1 < n && text[i] == '*' && text[i + 1] == '*' -> {
                    val endIdx = text.indexOf("**", i + 2)
                    if (endIdx != -1) {
                        val boldText = text.substring(i + 2, endIdx)
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(boldText)
                        }
                        i = endIdx + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }

                // Курсив: *text*
                text[i] == '*' -> {
                    val endIdx = text.indexOf('*', i + 1)
                    if (endIdx != -1 && (endIdx + 1 >= n || text[endIdx + 1] != '*')) {
                        val italicText = text.substring(i + 1, endIdx)
                        withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(italicText)
                        }
                        i = endIdx + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

/**
 * 1. Индикатор размышлений (Thinking Dots):
 * 3 точки диаметром 4dp с интервалом 6dp на Canvas с синусоидальным подпрыгиванием Y (-6dp..0dp).
 */
@Composable
fun ThinkingDotsCanvas(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ThinkingDots")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = modifier.size(width = 36.dp, height = 16.dp)) {
        val dotRadius = 2.dp.toPx()
        val spacing = 6.dp.toPx()
        val totalWidth = (dotRadius * 2 * 3) + (spacing * 2)
        val startX = (size.width - totalWidth) / 2f + dotRadius
        val centerY = size.height / 2f
        val maxBounce = 6.dp.toPx()

        for (i in 0..2) {
            val phaseShift = i * (2f * Math.PI.toFloat() / 3f)
            val sinVal = kotlin.math.sin(phase + phaseShift)
            val bounceY = (sinVal * 0.5f - 0.5f) * maxBounce
            val x = startX + i * (dotRadius * 2 + spacing)
            drawCircle(
                color = color,
                radius = dotRadius,
                center = androidx.compose.ui.geometry.Offset(x, centerY + bounceY)
            )
        }
    }
}

/**
 * 3. Аккордеон рассуждений (Thinking Process):
 * Контурный блок с заголовком «Рассуждения» и стрелкой-шевроном. Текст серый (#888894).
 */
@Composable
fun ThinkingAccordion(
    thoughtText: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "Chevron"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp)
            )
            .background(Color.Transparent)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_ai_thought),
                    contentDescription = null,
                    tint = Color(0xFF888894),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Рассуждения",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF888894),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "▼",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF888894),
                modifier = Modifier.rotate(chevronRotation)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessLow)) + fadeOut()
        ) {
            Text(
                text = thoughtText,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF888894),
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * 4. Световой импульс диагностики (Running Beam):
 * Вокруг блока сообщения плавно скользит световой луч по контурной рамке при FunctionCall.
 */
fun Modifier.runningBeamBorder(
    isExecuting: Boolean,
    color: Color = Color(0xFF00E5FF)
): Modifier = composed {
    if (!isExecuting) {
        return@composed this.border(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            RoundedCornerShape(12.dp)
        )
    }

    val transition = rememberInfiniteTransition(label = "RunningBeam")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "beamOffset"
    )

    this.drawWithContent {
        drawContent()
        val strokeWidth = 1.5.dp.toPx()
        val cornerRadius = CornerRadius(12.dp.toPx())
        val brush = Brush.sweepGradient(
            listOf(
                Color.Transparent,
                color.copy(alpha = 0.15f),
                color,
                color.copy(alpha = 0.15f),
                Color.Transparent
            ),
            center = center
        )
        rotate(degrees = offset * 360f, pivot = center) {
            drawRoundRect(
                brush = brush,
                size = size,
                cornerRadius = cornerRadius,
                style = Stroke(width = strokeWidth)
            )
        }
    }
}

@Composable
private fun QuickChip(
    title: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PromptCard(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                RoundedCornerShape(12.dp)
            )
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private val rotateIn = fadeIn(animationSpec = tween(220))
private val rotateOut = fadeOut(animationSpec = tween(180))
