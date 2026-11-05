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
import android.widget.Toast
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.collection.LruCache
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.mirrly.tgproxy.ai.ProposedSvgAction
import com.mirrly.tgproxy.ai.CloudflareAiClient
import com.mirrly.tgproxy.ai.GeminiApiClient
import com.mirrly.tgproxy.ai.OpenRouterAiClient
import com.mirrly.tgproxy.ai.RateLimitException
import com.mirrly.tgproxy.ui.MirrlyModalBottomSheet
import com.mirrly.tgproxy.ui.icon.AnimatedDynamicVectorButton
import com.mirrly.tgproxy.ui.icon.SvgPathDataParser
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.MtprotoAccent
import com.mirrly.tgproxy.ui.theme.Socks5Accent
import com.mirrly.tgproxy.ui.theme.VpnAccent
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.springPress
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
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
    onBack: () -> Unit = onClose,
    initialPrompt: String? = null
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
    val cfApiClient = remember { CloudflareAiClient() }
    val openRouterClient = remember { OpenRouterAiClient() }

    var selectedProvider by remember { mutableStateOf(prefs.getAiProvider()) }
    var selectedModel by remember { mutableStateOf(prefs.getAiModel()) }
    var temperature by remember { mutableFloatStateOf(prefs.getAiTemperature()) }
    var reasoningEnabled by remember { mutableStateOf(prefs.isAiReasoningEnabled()) }
    var useSmartDns by remember { mutableStateOf(prefs.isAiUseSmartDns()) }
    var smartDnsProvider by remember { mutableStateOf(prefs.getAiSmartDnsProvider()) }
    var promptMode by remember { mutableStateOf(prefs.getAiSystemPromptMode()) }
    var customPrompt by remember { mutableStateOf(prefs.getAiCustomSystemPrompt()) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showQuickModelPicker by remember { mutableStateOf(false) }
    var showAiOnboarding by rememberSaveable { mutableStateOf(!prefs.hasSeenAiOnboarding()) }
    var isAiOnboardingReplay by rememberSaveable { mutableStateOf(false) }
    var isInputExpanded by rememberSaveable { mutableStateOf(false) }

    val chatBlurRadius by animateDpAsState(
        targetValue = if (showSettingsSheet || showQuickModelPicker || showAiOnboarding || isInputExpanded) 16.dp else 0.dp,
        animationSpec = tween(220),
        label = "chatBlur"
    )

    val messages = remember { mutableStateListOf<AiChatMessage>() }
    var inputMessage by rememberSaveable {
        mutableStateOf(if (!initialPrompt.isNullOrBlank()) initialPrompt else prefs.getAiChatDraft())
    }
    var isGenerating by remember { mutableStateOf(false) }
    var rateLimitCountdown by remember { mutableIntStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(initialPrompt) {
        if (!initialPrompt.isNullOrBlank()) {
            inputMessage = initialPrompt
            prefs.setAiChatDraft(initialPrompt)
        }
    }

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
            cfApiClient.cancelCurrent()
            openRouterClient.cancelCurrent()
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
        cfApiClient.cancelCurrent()
        openRouterClient.cancelCurrent()
        isGenerating = false
        messages.clear()
        AiHistoryManager.clear(context)
        prefs.setAiChatDraft("")
        inputMessage = ""
        focusManager.clearFocus()
    }

    fun sendMessage(userText: String) {
        val cleanText = userText.trim()
        if (cleanText.isEmpty() || isGenerating) return

        when (selectedProvider) {
            "Cloudflare" -> {
                val token = prefs.getCloudflareToken().orEmpty()
                val accountId = prefs.getCloudflareAccountId().orEmpty()
                if (!prefs.isCloudflareAuthorized() || token.isEmpty() || accountId.isEmpty()) {
                    showSettingsSheet = true
                    return
                }
            }
            "OpenRouter" -> {
                val key = AiKeyManager.getOpenRouterApiKey(context)
                if (key.isEmpty()) {
                    showSettingsSheet = true
                    return
                }
            }
            else -> {
                val apiKey = AiKeyManager.getApiKey(context)
                if (apiKey.isEmpty()) {
                    showSettingsSheet = true
                    return
                }
            }
        }

        val userMessage = AiChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = cleanText
        )
        messages.add(userMessage)
        prefs.setAiChatDraft("")
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

            val batcher = StreamChunkBatcher { textChunk, thoughtChunk ->
                val idx = messages.indexOfFirst { it.id == assistantMessageId }
                if (idx != -1) {
                    val current = messages[idx]
                    messages[idx] = current.copy(
                        text = current.text + textChunk,
                        thought = current.thought + thoughtChunk
                    )
                }
            }

            val result = when (selectedProvider) {
                "Cloudflare" -> {
                    val token = prefs.getCloudflareToken().orEmpty()
                    val accountId = prefs.getCloudflareAccountId().orEmpty()
                    cfApiClient.sendMessageStream(
                        token = token,
                        accountId = accountId,
                        model = selectedModel,
                        history = messages.dropLast(2),
                        userPrompt = cleanText,
                        systemInstruction = effectiveInstruction,
                        onChunk = { textChunk, thoughtChunk ->
                            batcher.append(textChunk, thoughtChunk)
                        }
                    )
                }
                "OpenRouter" -> {
                    val openRouterKey = AiKeyManager.getOpenRouterApiKey(context)
                    openRouterClient.sendMessageStream(
                        apiKey = openRouterKey,
                        model = selectedModel,
                        history = messages.dropLast(2),
                        userPrompt = cleanText,
                        systemInstruction = effectiveInstruction,
                        temperature = temperature,
                        useSmartDns = useSmartDns,
                        onChunk = { textChunk, thoughtChunk ->
                            batcher.append(textChunk, thoughtChunk)
                        }
                    )
                }
                else -> {
                    val apiKey = AiKeyManager.getApiKey(context)
                    apiClient.sendMessageStream(
                        context = context,
                        history = messages.dropLast(2),
                        userPrompt = cleanText,
                        endpoint = prefs.getAiEndpoint(),
                        model = selectedModel,
                        apiKey = apiKey,
                        temperature = temperature,
                        reasoningEnabled = reasoningEnabled,
                        systemInstruction = effectiveInstruction,
                        useSmartDns = useSmartDns,
                        onToolCallStarted = { toolName ->
                            batcher.flush()
                            val idx = messages.indexOfFirst { it.id == assistantMessageId }
                            if (idx != -1) {
                                messages[idx] = messages[idx].copy(
                                    isExecutingTool = true,
                                    toolName = toolName
                                )
                            }
                        },
                        onToolCallFinished = { toolName, result ->
                            batcher.flush()
                            val idx = messages.indexOfFirst { it.id == assistantMessageId }
                            if (idx != -1) {
                                val proposed = if (toolName == "apply_svg_button_icon" && result.optString("status") == "proposed") {
                                    ProposedSvgAction(
                                        protocol = result.optString("protocol", "mtproto"),
                                        pathData = result.optString("path_data", ""),
                                        iconName = result.optString("icon_name", "Кастомная иконка")
                                    )
                                } else messages[idx].proposedSvgAction

                                messages[idx] = messages[idx].copy(
                                    isExecutingTool = false,
                                    proposedSvgAction = proposed
                                )
                            }
                        },
                        onChunk = { textChunk, thoughtChunk ->
                            batcher.append(textChunk, thoughtChunk)
                        }
                    )
                }
            }

            batcher.flush()
            isGenerating = false
            result.onSuccess { (finalText, finalThought) ->
                val idx = messages.indexOfFirst { it.id == assistantMessageId }
                if (idx != -1) {
                    val currentMsg = messages[idx]
                    var action = currentMsg.proposedSvgAction
                    if (action == null && SvgPathDataParser.validatePathData(finalText)) {
                        val extracted = SvgPathDataParser.extractPathData(finalText)
                        if (extracted.isNotBlank() && (finalText.contains("<svg") || finalText.contains("pathData") || finalText.contains("d=\""))) {
                            val detectedProto = when {
                                finalText.contains("socks5", ignoreCase = true) -> "socks5"
                                finalText.contains("vpn", ignoreCase = true) -> "vpn"
                                else -> "mtproto"
                            }
                            action = ProposedSvgAction(detectedProto, extracted, "Сгенерированная SVG иконка")
                        }
                    }

                    messages[idx] = currentMsg.copy(
                        text = finalText,
                        thought = finalThought,
                        isExecutingTool = false,
                        proposedSvgAction = action
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
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                    Column(modifier = Modifier.weight(1f, fill = false)) {
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
                        ModelStatusIndicator(
                            selectedProvider = selectedProvider,
                            selectedModel = selectedModel,
                            onClick = { showQuickModelPicker = true }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

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

            // ── Баннер при отсутствии ключа / авторизации ──
            val isConfigured = when (selectedProvider) {
                "Cloudflare" -> prefs.isCloudflareAuthorized() && prefs.getCloudflareToken().orEmpty().isNotBlank()
                "OpenRouter" -> AiKeyManager.hasOpenRouterApiKey(context)
                else -> AiKeyManager.hasApiKey(context)
            }

            if (!isConfigured) {
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
                            text = when (selectedProvider) {
                                "Cloudflare" -> "Требуется авторизация Cloudflare Workers AI"
                                "OpenRouter" -> "Требуется API-ключ OpenRouter"
                                else -> "Требуется API-ключ Gemini"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = when (selectedProvider) {
                                "Cloudflare" -> {
                                    "Для работы серверных моделей Llama, DeepSeek R1 и Qwen выполните вход через официальный Cloudflare OAuth."
                                }
                                "OpenRouter" -> {
                                    "Для доступа к DeepSeek R1, Claude 3.5, GPT-4o и Llama укажите ваш API-ключ OpenRouter (sk-or-v1-...)."
                                }
                                else -> {
                                    "Для работы автономной диагностики укажите ваш бесплатный ключ Google AI Studio. Ключ хранится локально в защищенном виде."
                                }
                            },
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
                                text = when (selectedProvider) {
                                    "Cloudflare" -> "Войти через Cloudflare"
                                    "OpenRouter" -> "Ввести ключ OpenRouter"
                                    else -> "Настроить ключ"
                                },
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

                // Поле ввода и кнопка Отправка/Стоп (скрывается при развёрнутом редакторе)
                AnimatedVisibility(
                    visible = !isInputExpanded,
                    enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(140))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    OutlinedTextField(
                        value = inputMessage,
                        onValueChange = {
                            inputMessage = it
                            prefs.setAiChatDraft(it)
                        },
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = TextWhite,
                            fontSize = 14.sp
                        ),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.ai_chat_input_placeholder),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        },
                        trailingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .springPress(onClick = {
                                        focusManager.clearFocus()
                                        isInputExpanded = true
                                    }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_expand),
                                    contentDescription = stringResource(R.string.ai_chat_expand_editor_title),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
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

    if (isInputExpanded) {
        ExpandedChatInputSheet(
            text = inputMessage,
            onTextChange = {
                inputMessage = it
                prefs.setAiChatDraft(it)
            },
            onApply = {
                isInputExpanded = false
            },
            onSend = {
                isInputExpanded = false
                sendMessage(inputMessage)
            },
            onDismiss = {
                isInputExpanded = false
            }
        )
    }

    if (showSettingsSheet) {
        AiSettingsSheet(
            onDismiss = { showSettingsSheet = false },
            provider = selectedProvider,
            onProviderSelected = { newProvider ->
                selectedProvider = newProvider
                prefs.setAiProvider(newProvider)
                val newModel = prefs.getAiModel()
                selectedModel = newModel
            },
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
            useSmartDns = useSmartDns,
            onUseSmartDnsToggled = {
                useSmartDns = it
                prefs.setAiUseSmartDns(it)
            },
            smartDnsProvider = smartDnsProvider,
            onSmartDnsProviderChanged = {
                smartDnsProvider = it
                prefs.setAiSmartDnsProvider(it)
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
            onOpenAiOnboarding = {
                showSettingsSheet = false
                isAiOnboardingReplay = true
                showAiOnboarding = true
            },
            onClearHistory = {
                startNewChat()
            }
        )
    }

    if (showQuickModelPicker) {
        AiQuickModelSheet(
            onDismiss = { showQuickModelPicker = false },
            currentProvider = selectedProvider,
            onProviderChanged = { newProvider ->
                selectedProvider = newProvider
                prefs.setAiProvider(newProvider)
                val newModel = prefs.getAiModel()
                selectedModel = newModel
            },
            selectedModel = selectedModel,
            onModelSelected = { newModel ->
                selectedModel = newModel
                prefs.setAiModel(newModel)
            },
            useSmartDns = useSmartDns
        )
    }

    if (showAiOnboarding) {
        AiOnboardingScreen(
            isReplay = isAiOnboardingReplay,
            onDismiss = {
                showAiOnboarding = false
                isAiOnboardingReplay = false
            },
            onComplete = {
                showAiOnboarding = false
                isAiOnboardingReplay = false
                selectedProvider = prefs.getAiProvider()
                selectedModel = prefs.getAiModel()
                promptMode = prefs.getAiSystemPromptMode()
                customPrompt = prefs.getAiCustomSystemPrompt()
                useSmartDns = prefs.isAiUseSmartDns()
                smartDnsProvider = prefs.getAiSmartDnsProvider()
            }
        )
    }
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

                    if (msg.proposedSvgAction != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ProposedSvgActionCard(action = msg.proposedSvgAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProposedSvgActionCard(action: ProposedSvgAction) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = MirrlyApplication.instance.prefsManager
    var isApplied by rememberSaveable(action.pathData) {
        val currentPreset = prefs.getCentralButtonIcon(action.protocol)
        val currentCode = prefs.getCustomIconCode(action.protocol)
        mutableStateOf(currentPreset == "custom" && currentCode == action.pathData)
    }

    val protoAccent = when (action.protocol.lowercase()) {
        "mtproto" -> MtprotoAccent
        "socks5" -> Socks5Accent
        "vpn" -> VpnAccent
        else -> MaterialTheme.colorScheme.primary
    }

    val protoLabel = when (action.protocol.lowercase()) {
        "mtproto" -> "MTProto"
        "socks5" -> "SOCKS5"
        "vpn" -> "VPN"
        else -> action.protocol.uppercase()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, if (isApplied) protoAccent else AmoledBorder, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Vector Live Animated Preview
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Transparent)
                        .border(1.dp, protoAccent.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedDynamicVectorButton(
                        pathData = action.pathData,
                        state = ProxyUiState.CONNECTED,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = action.iconName.ifEmpty { "Векторная иконка" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        text = "$protoLabel · SVG код кнопки",
                        style = MaterialTheme.typography.labelSmall,
                        color = protoAccent
                    )
                }
            }

            // Apply Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isApplied) protoAccent.copy(alpha = 0.12f) else Color.Transparent)
                    .border(
                        1.dp,
                        if (isApplied) protoAccent else protoAccent.copy(alpha = 0.6f),
                        RoundedCornerShape(8.dp)
                    )
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        prefs.setCustomIconCode(action.protocol, action.pathData)
                        prefs.setCentralButtonIcon(action.protocol, "custom")
                        isApplied = true
                        Toast.makeText(
                            context,
                            context.getString(R.string.ai_chat_action_svg_applied, protoLabel),
                            Toast.LENGTH_SHORT
                        ).show()
                    })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isApplied) stringResource(R.string.ai_chat_action_svg_applied, protoLabel) else stringResource(R.string.ai_chat_apply_svg_btn),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isApplied) protoAccent else TextWhite
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ExpandedChatInputSheet(
    text: String,
    onTextChange: (String) -> Unit,
    onApply: () -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var editorHeightDp by rememberSaveable { mutableFloatStateOf(280f) }
    val animatedEditorHeight by animateDpAsState(
        targetValue = editorHeightDp.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "editorHeight"
    )
    val isFullHeight = editorHeightDp > 360f

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Transparent)
                            .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_expand),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Text(
                            text = stringResource(R.string.ai_chat_expand_editor_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(R.string.ai_chat_expand_editor_char_count_fmt, text.length),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }

                // Quick Actions: Resize, Paste & Clear
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Resize Toggle: Expand / Collapse
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Transparent)
                            .border(1.dp, AmoledBorder, RoundedCornerShape(6.dp))
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                editorHeightDp = if (isFullHeight) 280f else 500f
                            })
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isFullHeight) stringResource(R.string.ai_chat_expand_editor_resize_collapse) else stringResource(R.string.ai_chat_expand_editor_resize_expand),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Transparent)
                            .border(1.dp, AmoledBorder, RoundedCornerShape(6.dp))
                            .springPress(onClick = {
                                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrBlank()) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onTextChange(text + (if (text.isNotEmpty()) "\n" else "") + clip)
                                }
                            })
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = "Вставить", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }

                    if (text.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Transparent)
                                .border(1.dp, AmoledBorder, RoundedCornerShape(6.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onTextChange("")
                                })
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(text = "Очистить", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Draggable resize bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            val deltaDp = -dragAmount / density.density
                            editorHeightDp = (editorHeightDp + deltaDp).coerceIn(260f, 600f)
                        }
                    }
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AmoledBorder)
                )
            }

            // Large Multiline Editor with pure white text and dynamic height
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(animatedEditorHeight),
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = TextWhite,
                    fontSize = 14.sp
                ),
                placeholder = {
                    Text(
                        text = stringResource(R.string.ai_chat_expand_editor_placeholder),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = AmoledBorder,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )

            // Bottom Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onApply()
                        })
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.ai_chat_expand_editor_apply),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite
                    )
                }

                val canSend = text.isNotBlank()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (canSend) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .border(
                            1.dp,
                            if (canSend) MaterialTheme.colorScheme.primary else AmoledBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .springPress(onClick = {
                            if (canSend) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSend()
                            }
                        })
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.ai_chat_expand_editor_send),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (canSend) MaterialTheme.colorScheme.primary else TextMuted
                    )
                }
            }
        }
    }
}

private val markdownBlocksCache = LruCache<String, List<MarkdownBlock>>(200)
private val inlineMarkdownCache = LruCache<String, AnnotatedString>(500)

/**
 * Потоковый батчер чанков SSE для ограничения частоты обновлений SnapshotStateList в Jetpack Compose.
 * Буферизует входящие токены и сбрасывает их в состояние с интервалом не чаще 45 мс.
 */
private class StreamChunkBatcher(
    private val intervalMs: Long = 45L,
    private val onFlush: (textChunk: String, thoughtChunk: String) -> Unit
) {
    private val textBuf = StringBuilder()
    private val thoughtBuf = StringBuilder()
    private var lastFlushTime = System.currentTimeMillis()

    @Synchronized
    fun append(text: String, thought: String) {
        if (text.isNotEmpty()) textBuf.append(text)
        if (thought.isNotEmpty()) thoughtBuf.append(thought)
        val now = System.currentTimeMillis()
        if (now - lastFlushTime >= intervalMs) {
            flushInternal(now)
        }
    }

    @Synchronized
    fun flush() {
        flushInternal(System.currentTimeMillis())
    }

    private fun flushInternal(now: Long) {
        if (textBuf.isNotEmpty() || thoughtBuf.isNotEmpty()) {
            val t = textBuf.toString()
            val th = thoughtBuf.toString()
            textBuf.clear()
            thoughtBuf.clear()
            lastFlushTime = now
            onFlush(t, th)
        }
    }
}

/**
 * Изолированный индикатор текущей модели с анимацией пульсации.
 * Локализует фазу анимации внутри собственного Composable, предотвращая рекомпозицию экрана чата.
 */
@Composable
private fun ModelStatusIndicator(
    selectedProvider: String,
    selectedModel: String,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "modelPulse")
    val modelPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "modelPulseAlpha"
    )

    val providerLabel = when (selectedProvider) {
        "Cloudflare" -> "Workers AI"
        "OpenRouter" -> "OpenRouter"
        else -> "Gemini"
    }
    val shortModelName = com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName(selectedModel)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = modelPulseAlpha),
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$providerLabel • $shortModelName",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary.copy(alpha = modelPulseAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "▾",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = modelPulseAlpha)
        )
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
    markdownBlocksCache.get(text)?.let { return it }
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

    markdownBlocksCache.put(text, blocks)
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
    inlineMarkdownCache.get(text)?.let { return it }
    val result = buildAnnotatedString {
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
    inlineMarkdownCache.put(text, result)
    return result
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
