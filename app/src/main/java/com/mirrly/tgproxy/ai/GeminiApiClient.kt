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

import android.content.Context
import com.mirrly.tgproxy.core.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

object AiPrompts {
    const val MODE_DIAGNOSTIC = "DIAGNOSTIC"
    const val MODE_FUN = "FUN"
    const val MODE_CUSTOM = "CUSTOM"
    const val MODE_NONE = "NONE"

    const val PROMPT_DIAGNOSTIC =
        "Ты — встроенный технический ИИ-диагност приложения Mirrly TG Proxy для платформы Android. " +
        "Приложение функционирует на различных устройствах (смартфоны, планшеты, ТВ-приставки широкого спектра производителей, различные версии Android и процессорные архитектуры ARM64, ARMv7, x86_64). " +
        "Твоя задача — анализировать работу сетевых протоколов MTProto (порт 1080) и SOCKS5 (порт 10808), маршрутизации Cloudflare Worker WSS, " +
        "выявлять причины сбоев соединения, таймаутов TLS/WSS, сетевых блокировок провайдера и помогать пользователю.\n\n" +
        "ПРАВИЛА:\n" +
        "1. При любых вопросах о проблемах, сбоях, недоступности сети или вопросах вида 'почему не работает' " +
        "ОБЯЗАТЕЛЬНО сначала вызови диагностические инструменты: get_service_status(), get_network_health(), " +
        "get_recent_failure_logs() или test_uplink_connection(). Только после получения структурированного JSON формулируй ответ на их основе.\n" +
        "2. Отвечай строго, по делу, технически грамотно на русском языке. Запрещены любые эмодзи, смайлики, пафосные или маркетинговые штампы.\n" +
        "3. Не выдумывай несуществующие ошибки. Анализируй исключительно возвращенные JSON-метрики."

    const val PROMPT_FUN =
        "Ты — остроумный и дружелюбный ИИ-собеседник в приложении Mirrly TG Proxy. " +
        "Общайся свободно, с юмором, шути, отвечай на любые вопросы, веселись и поддерживай диалог с пользователем, оставаясь сообразительным ассистентом."
}

data class GeminiModelInfo(
    val id: String,
    val displayName: String,
    val description: String
)

data class PendingFunctionCall(
    val name: String,
    val args: JSONObject,
    val thoughtSignature: String?
)

class RateLimitException(val retryAfterSeconds: Int, message: String) : Exception(message)

/**
 * Клиент Google Gemini API (v1beta).
 * Поддерживает:
 * - Потоковую передачу Server-Sent Events (SSE)
 * - Автономный цикл вызова локальных инструментов (Function Calling)
 * - Разделение рассуждений модели (Reasoning Thoughts) и финального ответа
 * - Обработку ошибок 429 с извлечением Retry-After
 * - Туннелирование через локальный SOCKS5-прокси (127.0.0.1:10808)
 * - Динамическую загрузку каталога доступных моделей Google AI Studio
 */
class GeminiApiClient {

    companion object {
        private const val TAG = "GeminiApiClient"
        private val RETRY_AFTER_REGEX = Pattern.compile("(?i)(?:retry after|wait)\\s+(\\d+)\\s*s")
    }

    private val directClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val socks5Client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808)))
        .build()

    private val currentCall = AtomicReference<Call?>(null)

    fun cancelCurrent() {
        currentCall.getAndSet(null)?.cancel()
    }

    private fun getClient(useSocks5: Boolean): OkHttpClient {
        return if (useSocks5) socks5Client else directClient
    }

    /**
     * Динамически запрашивает список доступных моделей в Google AI Studio.
     */
    suspend fun fetchAvailableModels(
        apiKey: String,
        endpoint: String,
        useSocks5: Boolean = false
    ): Result<List<GeminiModelInfo>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanEndpoint = endpoint.trim().trimEnd('/')
        if (cleanKey.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("API-ключ не указан"))
        }

        val url = "$cleanEndpoint/models?key=$cleanKey"
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .get()
            .build()

        try {
            getClient(useSocks5).newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 429) {
                    val waitSec = parseRetryAfter(response.header("Retry-After"), body)
                    return@withContext Result.failure(RateLimitException(waitSec, "Лимит запросов исчерпан"))
                }

                if (!response.isSuccessful) {
                    val errorMsg = parseErrorMessage(body, response.code)
                    return@withContext Result.failure(Exception(errorMsg))
                }

                val json = JSONObject(body)
                val modelsArray = json.optJSONArray("models") ?: JSONArray()
                val list = mutableListOf<GeminiModelInfo>()

                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.getJSONObject(i)
                    val rawName = m.optString("name", "") // e.g. "models/gemini-2.5-flash"
                    val modelId = rawName.removePrefix("models/")
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    val methodsList = (0 until methods.length()).map { methods.getString(it) }

                    // Отбираем только текстовые и мультимодальные генеративные модели
                    if (methodsList.contains("generateContent") && !modelId.contains("embedding") && !modelId.contains("aqa")) {
                        list.add(
                            GeminiModelInfo(
                                id = modelId,
                                displayName = m.optString("displayName", modelId),
                                description = m.optString("description", "")
                            )
                        )
                    }
                }

                // Сортируем: сначала рекомендуемые модели семейства 2.5 / 2.0 / 1.5 flash
                list.sortWith(Comparator { a, b ->
                    val priorityA = getModelPriority(a.id)
                    val priorityB = getModelPriority(b.id)
                    priorityB.compareTo(priorityA)
                })

                Result.success(list)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Ошибка загрузки каталога моделей", e)
            Result.failure(e)
        }
    }

    private fun getModelPriority(id: String): Int {
        val lower = id.lowercase()
        return when {
            lower.contains("gemini-3.6-flash") -> 100
            lower.contains("gemini-3.5-flash-lite") -> 95
            lower.contains("gemini-3.5-flash") -> 90
            lower.contains("gemini-3.8-flash") -> 85
            lower.contains("gemini-2.5-flash") -> 80
            lower.contains("gemini-2.5-flash-lite") -> 75
            lower.contains("flash") -> 60
            lower.contains("pro") -> 40
            else -> 10
        }
    }

    /**
     * Потоковая отправка сообщения с полной поддержкой Function Calling и Reasoning.
     */
    suspend fun sendMessageStream(
        context: Context,
        history: List<AiChatMessage>,
        userPrompt: String,
        endpoint: String,
        model: String,
        apiKey: String,
        temperature: Float,
        reasoningEnabled: Boolean,
        systemInstruction: String? = null,
        useSocks5: Boolean = false,
        onToolCallStarted: (toolName: String) -> Unit = {},
        onToolCallFinished: (toolName: String, result: JSONObject) -> Unit = { _, _ -> },
        onChunk: (textChunk: String, thoughtChunk: String) -> Unit
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanEndpoint = endpoint.trim().trimEnd('/')
        val cleanModel = model.trim()

        if (cleanKey.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("API-ключ не указан"))
        }

        try {
            // Формируем начальный массив диалога
            val contentsArray = JSONArray()

            // Предыдущие сообщения (скользящее окно)
            for (msg in history.takeLast(20)) {
                if (msg.text.isBlank() && msg.thought.isBlank() && msg.toolName == null) continue
                val role = if (msg.isUser) "user" else "model"
                val parts = JSONArray().apply {
                    if (msg.text.isNotBlank()) {
                        put(JSONObject().apply { put("text", msg.text) })
                    }
                }
                contentsArray.put(JSONObject().apply {
                    put("role", role)
                    put("parts", parts)
                })
            }

            // Добавляем текущий запрос пользователя
            contentsArray.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", userPrompt) })
                })
            })

            // Цикл Function Calling (максимум 3 последовательных вызова)
            var currentIteration = 0
            val maxIterations = 3
            val accumulatedText = StringBuilder()
            val accumulatedThought = StringBuilder()

            while (currentIteration < maxIterations) {
                currentIteration++

                val requestJson = buildRequestBody(
                    contents = contentsArray,
                    model = cleanModel,
                    temperature = temperature,
                    reasoningEnabled = reasoningEnabled,
                    systemInstructionText = systemInstruction
                )

                val url = "$cleanEndpoint/models/$cleanModel:streamGenerateContent?alt=sse&key=$cleanKey"
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = requestJson.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build()

                val call = getClient(useSocks5).newCall(request)
                currentCall.set(call)

                val requestedFunctionCalls = mutableListOf<PendingFunctionCall>()

                call.execute().use { response ->
                    if (response.code == 429 || response.code == 503) {
                        val respBody = response.body?.string().orEmpty()
                        val waitSec = parseRetryAfter(response.header("Retry-After"), respBody)
                        if (response.code == 503 || respBody.contains("high demand", ignoreCase = true) || respBody.contains("overloaded", ignoreCase = true)) {
                            return@withContext Result.failure(
                                RateLimitException(
                                    waitSec.coerceAtLeast(10),
                                    "Модель временно перегружена (высокий спрос на серверах Google). Попробуйте снова через $waitSec сек. или выберите gemini-3.6-flash / gemini-3.5-flash в настройках."
                                )
                            )
                        }
                        return@withContext Result.failure(RateLimitException(waitSec, "Лимит запросов исчерпан. Повторите попытку через $waitSec сек."))
                    }

                    if (!response.isSuccessful) {
                        val respBody = response.body?.string().orEmpty()
                        val errorMsg = parseErrorMessage(respBody, response.code)
                        return@withContext Result.failure(Exception(errorMsg))
                    }

                    val source = response.body?.byteStream()
                        ?: return@withContext Result.failure(Exception("Пустой ответ от сервера"))

                    BufferedReader(InputStreamReader(source, Charsets.UTF_8)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val cur = line ?: break
                            if (!cur.startsWith("data:")) continue
                            val jsonStr = cur.removePrefix("data:").trim()
                            if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue

                            try {
                                val chunkJson = JSONObject(jsonStr)
                                val candidates = chunkJson.optJSONArray("candidates") ?: continue
                                if (candidates.length() == 0) continue

                                val firstCandidate = candidates.getJSONObject(0)
                                val contentObj = firstCandidate.optJSONObject("content") ?: continue
                                val parts = contentObj.optJSONArray("parts") ?: continue

                                for (i in 0 until parts.length()) {
                                    val part = parts.getJSONObject(i)

                                    // Проверяем вызов инструмента (FunctionCall)
                                    if (part.has("functionCall")) {
                                        val fCall = part.getJSONObject("functionCall")
                                        val fnName = fCall.optString("name")
                                        val fnArgs = fCall.optJSONObject("args") ?: JSONObject()
                                        val sig = part.optString("thoughtSignature").ifEmpty {
                                            part.optString("thought_signature").ifEmpty {
                                                fCall.optString("thought_signature").ifEmpty {
                                                    fCall.optString("thoughtSignature")
                                                }
                                            }
                                        }.ifEmpty { null }
                                        requestedFunctionCalls.add(PendingFunctionCall(fnName, fnArgs, sig))
                                        continue
                                    }

                                    // Рассуждения (Reasoning Thoughts)
                                    val isThought = part.optBoolean("thought", false)
                                    val textPart = part.optString("text", "")

                                    if (textPart.isNotEmpty()) {
                                        if (isThought) {
                                            accumulatedThought.append(textPart)
                                            onChunk("", textPart)
                                        } else {
                                            accumulatedText.append(textPart)
                                            onChunk(textPart, "")
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                // Игнорируем единичные сбои парсинга SSE чанков
                            }
                        }
                    }
                }

                // Если модель не вызывала инструмент — завершаем диалог
                if (requestedFunctionCalls.isEmpty()) {
                    break
                }

                // 1. Формируем единый блок вызовов функций модели
                val modelParts = JSONArray()
                for ((index, fc) in requestedFunctionCalls.withIndex()) {
                    val partObj = JSONObject().apply {
                        put("functionCall", JSONObject().apply {
                            put("name", fc.name)
                            put("args", fc.args)
                        })
                        // Первый вызов функции ОБЯЗАН содержать thought_signature в Gemini 3.x / 4.x
                        if (index == 0) {
                            val sig = fc.thoughtSignature ?: "skip_thought_signature_validator"
                            put("thought_signature", sig)
                            put("thoughtSignature", sig)
                        } else if (fc.thoughtSignature != null) {
                            put("thought_signature", fc.thoughtSignature)
                            put("thoughtSignature", fc.thoughtSignature)
                        }
                    }
                    modelParts.put(partObj)
                }
                contentsArray.put(JSONObject().apply {
                    put("role", "model")
                    put("parts", modelParts)
                })

                // 2. Выполняем инструменты локально и формируем единый блок ответов пользователя
                val userParts = JSONArray()
                for (fc in requestedFunctionCalls) {
                    onToolCallStarted(fc.name)
                    val toolResult = AiDiagnosticTools.executeTool(context, fc.name, fc.args)
                    onToolCallFinished(fc.name, toolResult)

                    userParts.put(JSONObject().apply {
                        put("functionResponse", JSONObject().apply {
                            put("name", fc.name)
                            put("response", JSONObject().apply {
                                put("name", fc.name)
                                put("content", toolResult)
                            })
                        })
                    })
                }
                contentsArray.put(JSONObject().apply {
                    put("role", "user")
                    put("parts", userParts)
                })

                // Защитная пауза для предотвращения всплеска запросов (burst rate limit 429) на бесплатном тарифе
                delay(600)
            }

            Result.success(Pair(accumulatedText.toString(), accumulatedThought.toString()))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Ошибка стриминга сообщений", e)
            Result.failure(e)
        } finally {
            currentCall.set(null)
        }
    }

    private fun buildRequestBody(
        contents: JSONArray,
        model: String,
        temperature: Float,
        reasoningEnabled: Boolean,
        systemInstructionText: String?
    ): JSONObject {
        val instruction = if (systemInstructionText != null) {
            systemInstructionText.trim()
        } else {
            AiPrompts.PROMPT_DIAGNOSTIC
        }

        return JSONObject().apply {
            put("contents", contents)

            // Системная инструкция (только канонический systemInstruction по REST спецификации Google AI Studio)
            if (instruction.isNotEmpty()) {
                val instructionObj = JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", instruction)
                        })
                    })
                }
                put("systemInstruction", instructionObj)
            }

            // Инструменты Function Calling (включаются для автономной диагностики)
            put("tools", JSONArray().apply {
                put(JSONObject().apply {
                    put("functionDeclarations", AiDiagnosticTools.getToolDeclarations())
                })
            })

            // Конфигурация генерации
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature.toDouble())
                val lowerModel = model.lowercase()
                if (reasoningEnabled && (lowerModel.contains("2.5") || lowerModel.contains("thinking") || lowerModel.contains("3."))) {
                    put("thinkingConfig", JSONObject().apply {
                        put("includeThoughts", true)
                        if (lowerModel.contains("3.")) {
                            put("thinkingLevel", "MEDIUM")
                        } else {
                            put("thinkingBudget", 2048)
                        }
                    })
                }
            })
        }
    }

    private fun parseRetryAfter(header: String?, body: String): Int {
        if (!header.isNullOrBlank()) {
            val hVal = header.trim().toIntOrNull()
            if (hVal != null && hVal > 0) return hVal
        }
        val matcher = RETRY_AFTER_REGEX.matcher(body)
        if (matcher.find()) {
            val sec = matcher.group(1)?.toIntOrNull()
            if (sec != null && sec > 0) return sec
        }
        return 30
    }

    private fun parseErrorMessage(body: String, code: Int): String {
        return try {
            val errObj = JSONObject(body).optJSONObject("error")
            errObj?.optString("message") ?: "HTTP $code"
        } catch (_: Exception) {
            "HTTP $code"
        }
    }
}
