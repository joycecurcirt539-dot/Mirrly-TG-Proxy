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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ai.AiKeyManager
import com.mirrly.tgproxy.ai.CloudflareAiClient
import com.mirrly.tgproxy.ai.CloudflareAiModelInfo
import com.mirrly.tgproxy.ai.GeminiApiClient
import com.mirrly.tgproxy.ai.GeminiModelInfo
import com.mirrly.tgproxy.ai.OpenRouterAiClient
import com.mirrly.tgproxy.ai.OpenRouterModelInfo
import com.mirrly.tgproxy.ui.theme.fadingEdges
import com.mirrly.tgproxy.ui.theme.horizontalFadingEdges
import kotlinx.coroutines.launch

/**
 * Быстрый переключатель моделей непосредственно из экрана чата.
 * Поддерживает смену провайдера (Gemini / Cloudflare / OpenRouter),
 * мгновенный выбор из чипов популярных моделей и поиск по каталогу.
 * Дизайн: MirrlyUI (Outline-Only, Zero-Popup, Zero Emojis).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiQuickModelSheet(
    onDismiss: () -> Unit,
    currentProvider: String,
    onProviderChanged: (String) -> Unit,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    useSmartDns: Boolean = true
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val prefs = MirrlyApplication.instance.prefsManager
    var activeProvider by remember { mutableStateOf(currentProvider) }
    var searchQuery by remember { mutableStateOf("") }
    var customModelInput by remember { mutableStateOf("") }

    // Каталоги
    var geminiModels by remember { mutableStateOf<List<GeminiModelInfo>>(emptyList()) }
    var cfModels by remember { mutableStateOf<List<CloudflareAiModelInfo>>(CloudflareAiClient.POPULAR_MODELS) }
    var openRouterModels by remember { mutableStateOf<List<OpenRouterModelInfo>>(OpenRouterAiClient.POPULAR_MODELS) }
    var isLoadingCatalog by remember { mutableStateOf(false) }

    val geminiClient = remember { GeminiApiClient() }
    val cfClient = remember { CloudflareAiClient() }
    val openRouterClient = remember { OpenRouterAiClient() }

    fun loadModelsForActiveProvider() {
        isLoadingCatalog = true
        scope.launch {
            when (activeProvider) {
                "Cloudflare" -> {
                    val token = prefs.getCloudflareToken().orEmpty()
                    val acc = prefs.getCloudflareAccountId().orEmpty()
                    if (token.isNotEmpty() && acc.isNotEmpty()) {
                        val res = cfClient.fetchAvailableModels(token, acc)
                        res.onSuccess { cfModels = it }
                    }
                }
                "OpenRouter" -> {
                    val key = AiKeyManager.getOpenRouterApiKey(context)
                    val res = openRouterClient.fetchAvailableModels(key, useSmartDns)
                    res.onSuccess { openRouterModels = it }
                }
                else -> {
                    val key = AiKeyManager.getApiKey(context)
                    if (key.isNotEmpty()) {
                        val res = geminiClient.fetchAvailableModels(key, prefs.getAiEndpoint(), useSmartDns)
                        res.onSuccess { geminiModels = it }
                    }
                }
            }
            isLoadingCatalog = false
        }
    }

    LaunchedEffect(activeProvider) {
        loadModelsForActiveProvider()
    }

    val popularChips = when (activeProvider) {
        "Cloudflare" -> listOf(
            "@cf/meta/llama-3.3-70b-instruct",
            "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b",
            "@cf/meta/llama-3.1-8b-instruct",
            "@cf/qwen/qwen2.5-72b-instruct",
            "@cf/mistral/mistral-7b-instruct-v0.2"
        )
        "OpenRouter" -> listOf("deepseek/deepseek-r1:free", "meta-llama/llama-3.3-70b-instruct:free", "google/gemini-2.0-flash-exp:free", "qwen/qwen-2.5-72b-instruct:free", "deepseek/deepseek-chat:free", "deepseek/deepseek-r1", "anthropic/claude-3.5-sonnet", "openai/gpt-4o")
        else -> listOf(
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-3.8-flash"
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.50f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    ) {
        rememberDialogBlurState(blurRadiusPx = 50)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // Заголовок
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Выбор модели нейросети",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Модель переключается на лету без потери текущего диалога",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close),
                        contentDescription = "Закрыть",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                thickness = 1.dp,
                modifier = Modifier.padding(top = 10.dp, bottom = 12.dp)
            )

            // Переключатель провайдера
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "Gemini" to "Google Gemini",
                    "Cloudflare" to "Workers AI",
                    "OpenRouter" to "OpenRouter"
                ).forEach { (key, label) ->
                    val isSelected = activeProvider == key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                activeProvider = key
                                onProviderChanged(key)
                                prefs.setAiProvider(key)
                                val fallbackModel = prefs.getAiModel()
                                onModelSelected(fallbackModel)
                            }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Чипы популярных моделей
            Text(
                text = "Популярные модели",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalFadingEdges(startFadeWidth = 6.dp, endFadeWidth = 14.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (chipModel in popularChips) {
                    val isCur = chipModel == selectedModel
                    Box(
                        modifier = Modifier
                            .border(
                                1.dp,
                                if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                onModelSelected(chipModel)
                                prefs.setAiModel(chipModel)
                                onDismiss()
                            }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName(chipModel),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Поле поиска / ввода модели
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = when (activeProvider) {
                                "Cloudflare" -> "Поиск или @cf/..."
                                "OpenRouter" -> "Поиск или model/name..."
                                else -> "Поиск или gemini-..."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        val clean = searchQuery.trim()
                        if (clean.isNotEmpty()) {
                            onModelSelected(clean)
                            prefs.setAiModel(clean)
                            onDismiss()
                        }
                    })
                )

                if (searchQuery.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .clickable {
                                focusManager.clearFocus()
                                val clean = searchQuery.trim()
                                if (clean.isNotEmpty()) {
                                    onModelSelected(clean)
                                    prefs.setAiModel(clean)
                                    onDismiss()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "Выбрать",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Список моделей каталога
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Каталог моделей",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isLoadingCatalog) {
                    Text(
                        text = "Загрузка...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Фильтрация элементов
            val query = searchQuery.trim().lowercase()
            val filteredItems: List<Triple<String, String, String>> = when (activeProvider) {
                "Cloudflare" -> cfModels
                    .filter { query.isEmpty() || it.id.lowercase().contains(query) || it.displayName.lowercase().contains(query) }
                    .map { Triple(it.id, it.displayName, it.description) }
                "OpenRouter" -> openRouterModels
                    .filter { query.isEmpty() || it.id.lowercase().contains(query) || it.displayName.lowercase().contains(query) }
                    .map { Triple(it.id, it.displayName, it.description) }
                else -> geminiModels
                    .filter { query.isEmpty() || it.id.lowercase().contains(query) || it.displayName.lowercase().contains(query) }
                    .map { Triple(it.id, it.displayName, "") }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(4.dp)
            ) {
                if (filteredItems.isEmpty()) {
                    item {
                        Text(
                            text = if (isLoadingCatalog) "Запрос каталога моделей..." else "Модели не найдены. Вы можете ввести ID модели вручную.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    items(filteredItems) { (id, name, desc) ->
                        val isCur = id == selectedModel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    onModelSelected(id)
                                    prefs.setAiModel(id)
                                    onDismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val shortName = com.mirrly.tgproxy.ai.AiModelFormatter.formatShortName(id)
                                Text(
                                    text = if (shortName.isNotBlank()) shortName else id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = id,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    fontSize = 10.sp
                                )
                                if (desc.isNotBlank()) {
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                } else if (name.isNotBlank() && name != id && name != shortName) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            if (isCur) {
                                Text(
                                    text = "Выбрано",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
