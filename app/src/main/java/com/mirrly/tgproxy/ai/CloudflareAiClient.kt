/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ai

import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.service.cloudflare.CloudflareNetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference

data class CloudflareAiModelInfo(
    val id: String,
    val displayName: String,
    val description: String
)

/**
 * Клиент Cloudflare Workers AI для взаимодействия с серверными моделями генерации текста
 * (Llama 3.3 70B, DeepSeek R1 Distill 32B, Qwen 2.5 72B и др.) через официальный REST API.
 *
 * Поддерживает:
 * - Авторизацию через OAuth access token или персональный API Token
 * - Потоковый вывод Server-Sent Events (SSE)
 * - Разделение рассуждений модели (<think>...</think> в DeepSeek R1) и финального ответа
 * - Динамическую загрузку каталога доступных моделей Text Generation
 * - Защиту от сетевых сбоев и DPI через CloudflareNetworkClient
 */
class CloudflareAiClient {

    companion object {
        private const val TAG = "CloudflareAiClient"
        private const val BASE_URL = "https://api.cloudflare.com/client/v4"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val POPULAR_MODELS = listOf(
            CloudflareAiModelInfo(
                id = "@cf/meta/llama-3.3-70b-instruct",
                displayName = "Llama 3.3 70B Instruct",
                description = "Флагманская модель Meta общего назначения высокой точности"
            ),
            CloudflareAiModelInfo(
                id = "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b",
                displayName = "DeepSeek R1 Distill 32B",
                description = "Продвинутая модель логического рассуждения (CoT Reasoning)"
            ),
            CloudflareAiModelInfo(
                id = "@cf/meta/llama-3.1-8b-instruct",
                displayName = "Llama 3.1 8B Instruct",
                description = "Быстрая и легкая модель для повседневных ответов"
            ),
            CloudflareAiModelInfo(
                id = "@cf/qwen/qwen2.5-72b-instruct",
                displayName = "Qwen 2.5 72B Instruct",
                description = "Мощная многоязычная модель с превосходным знанием кода"
            ),
            CloudflareAiModelInfo(
                id = "@cf/mistral/mistral-7b-instruct-v0.2",
                displayName = "Mistral 7B Instruct",
                description = "Компактная сбалансированная модель европейской разработки"
            )
        )
    }

    private val currentCall = AtomicReference<Call?>(null)

    fun cancelCurrent() {
        currentCall.getAndSet(null)?.cancel()
    }

    /**
     * Динамически запрашивает список доступных текстовых генеративных моделей в Cloudflare Workers AI.
     */
    suspend fun fetchAvailableModels(
        token: String,
        accountId: String
    ): Result<List<CloudflareAiModelInfo>> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        val cleanAccountId = accountId.trim()
        if (cleanToken.isEmpty() || cleanAccountId.isEmpty()) {
            return@withContext Result.success(POPULAR_MODELS)
        }

        val url = "$BASE_URL/accounts/$cleanAccountId/ai/models/search?task=Text%20Generation"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $cleanToken")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            CloudflareNetworkClient.executeWithFallback(request).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "Ошибка каталога моделей Cloudflare: HTTP ${response.code} $body")
                    return@withContext Result.success(POPULAR_MODELS)
                }

                val json = JSONObject(body)
                val resultArray = json.optJSONArray("result") ?: JSONArray()
                val list = mutableListOf<CloudflareAiModelInfo>()

                for (i in 0 until resultArray.length()) {
                    val m = resultArray.getJSONObject(i)
                    val rawName = m.optString("name", "")
                    if (rawName.isNotBlank()) {
                        val desc = m.optString("description", "")
                        val friendly = formatModelName(rawName)
                        list.add(
                            CloudflareAiModelInfo(
                                id = rawName,
                                displayName = friendly,
                                description = desc
                            )
                        )
                    }
                }

                if (list.isEmpty()) Result.success(POPULAR_MODELS) else Result.success(list)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Сбой загрузки каталога Cloudflare Workers AI: ${e.message}")
            Result.success(POPULAR_MODELS)
        }
    }

    /**
     * Отправляет запрос к модели Cloudflare Workers AI в потоковом режиме Server-Sent Events (SSE).
     */
    suspend fun sendMessageStream(
        token: String,
        accountId: String,
        model: String,
        history: List<AiChatMessage>,
        userPrompt: String,
        systemInstruction: String?,
        onChunk: (textChunk: String, thoughtChunk: String) -> Unit
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        val cleanAccountId = accountId.trim()
        val cleanModel = model.trim().ifEmpty { "@cf/meta/llama-3.3-70b-instruct" }

        if (cleanToken.isEmpty() || cleanAccountId.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Требуется авторизация в Cloudflare"))
        }

        val messagesArray = JSONArray()

        // 1. Системная инструкция
        if (!systemInstruction.isNullOrBlank()) {
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemInstruction)
            })
        }

        // 2. История диалога
        for (msg in history.takeLast(16)) {
            if (msg.text.isBlank()) continue
            val role = if (msg.isUser) "user" else "assistant"
            messagesArray.put(JSONObject().apply {
                put("role", role)
                put("content", msg.text)
            })
        }

        // 3. Текущее сообщение пользователя
        messagesArray.put(JSONObject().apply {
            put("role", "user")
            put("content", userPrompt)
        })

        val requestJson = JSONObject().apply {
            put("messages", messagesArray)
            put("stream", true)
        }

        val url = "$BASE_URL/accounts/$cleanAccountId/ai/run/$cleanModel"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $cleanToken")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val fullText = StringBuilder()
        val fullThought = StringBuilder()
        var insideThinkTag = false

        try {
            CloudflareNetworkClient.executeWithFallback(request).use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty()
                    val errorMsg = parseErrorMessage(errBody, response.code)
                    return@withContext Result.failure(Exception(errorMsg))
                }

                val body = response.body ?: return@withContext Result.failure(Exception("Пустой ответ от Cloudflare Workers AI"))
                val reader = BufferedReader(InputStreamReader(body.byteStream()))

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim().orEmpty()
                    if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                    if (trimmed == "data: [DONE]") break

                    if (trimmed.startsWith("data:")) {
                        val dataJson = trimmed.removePrefix("data:").trim()
                        try {
                            val chunkObj = JSONObject(dataJson)
                            val tokenPiece = chunkObj.optString("response", "")
                            if (tokenPiece.isNotEmpty()) {
                                // Парсер тегов рассуждений <think>...</think> (для DeepSeek R1)
                                var remaining = tokenPiece
                                while (remaining.isNotEmpty()) {
                                    if (!insideThinkTag) {
                                        val thinkStart = remaining.indexOf("<think>")
                                        if (thinkStart != -1) {
                                            val before = remaining.substring(0, thinkStart)
                                            if (before.isNotEmpty()) {
                                                fullText.append(before)
                                                onChunk(before, "")
                                            }
                                            insideThinkTag = true
                                            remaining = remaining.substring(thinkStart + 7)
                                        } else {
                                            fullText.append(remaining)
                                            onChunk(remaining, "")
                                            remaining = ""
                                        }
                                    } else {
                                        val thinkEnd = remaining.indexOf("</think>")
                                        if (thinkEnd != -1) {
                                            val thoughtText = remaining.substring(0, thinkEnd)
                                            if (thoughtText.isNotEmpty()) {
                                                fullThought.append(thoughtText)
                                                onChunk("", thoughtText)
                                            }
                                            insideThinkTag = false
                                            remaining = remaining.substring(thinkEnd + 8)
                                        } else {
                                            fullThought.append(remaining)
                                            onChunk("", remaining)
                                            remaining = ""
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            Result.success(fullText.toString() to fullThought.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatModelName(raw: String): String {
        return raw.removePrefix("@cf/")
            .replace("-instruct", "")
            .replace("-", " ")
            .split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }

    private fun parseErrorMessage(body: String, code: Int): String {
        return try {
            val json = JSONObject(body)
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                errors.getJSONObject(0).optString("message", "HTTP $code")
            } else {
                json.optString("message", "HTTP $code")
            }
        } catch (_: Exception) {
            "Cloudflare Workers AI: HTTP $code"
        }
    }
}
