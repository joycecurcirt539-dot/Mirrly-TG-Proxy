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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class OpenRouterModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val contextLength: Int = 0
)

/**
 * Клиент OpenRouter AI для работы с универсальным каталогом передовых нейросетей
 * (Claude 3.5 Sonnet, DeepSeek R1, GPT-4o, Llama 3.3, Qwen 2.5) через OpenAI-совместимый API.
 *
 * Архитектура:
 * - Потоковый Server-Sent Events (SSE) парсер с разделением цепочек рассуждений (reasoning / <think>).
 * - Обход блокировок и DPI через каскадный SmartDNS DoH (Comss.one, GeoHide, DNS-AI).
 * - Динамическая загрузка каталога доступных моделей OpenRouter.
 */
class OpenRouterAiClient {

    companion object {
        private const val TAG = "OpenRouterAiClient"
        private const val BASE_URL = "https://openrouter.ai/api/v1"
        private const val REFERER = "https://github.com/mirrly-dev/Mirrly-TG-Proxy"
        private const val APP_TITLE = "Mirrly TG Proxy"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val POPULAR_MODELS = listOf(
            OpenRouterModelInfo(
                id = "deepseek/deepseek-r1:free",
                displayName = "DeepSeek R1 (Free)",
                description = "Бесплатная флагманская модель с открытыми цепочками логических рассуждений"
            ),
            OpenRouterModelInfo(
                id = "meta-llama/llama-3.3-70b-instruct:free",
                displayName = "Meta Llama 3.3 70B (Free)",
                description = "Бесплатная открытая модель общего назначения с высокой точностью"
            ),
            OpenRouterModelInfo(
                id = "google/gemini-2.0-flash-exp:free",
                displayName = "Google Gemini 2.0 Flash (Free)",
                description = "Бесплатная быстрая генерация с поддержкой мультимодальности"
            ),
            OpenRouterModelInfo(
                id = "qwen/qwen-2.5-72b-instruct:free",
                displayName = "Qwen 2.5 72B (Free)",
                description = "Бесплатная передовая многоязычная модель с глубоким пониманием кода"
            ),
            OpenRouterModelInfo(
                id = "anthropic/claude-3.5-sonnet",
                displayName = "Claude 3.5 Sonnet",
                description = "Флагманская модель для сложного анализа (требует баланс OpenRouter)"
            ),
            OpenRouterModelInfo(
                id = "openai/gpt-4o",
                displayName = "OpenAI GPT-4o",
                description = "Флагманская мультимодальная модель (требует баланс OpenRouter)"
            )
        )
    }

    private val directClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val smartDnsClient = OkHttpClient.Builder()
        .dns(AiSmartDns.dohDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val currentCall = AtomicReference<Call?>(null)

    fun cancelCurrent() {
        currentCall.getAndSet(null)?.cancel()
    }

    private fun getClient(useSmartDns: Boolean): OkHttpClient {
        return if (useSmartDns) smartDnsClient else directClient
    }

    /**
     * Загружает актуальный каталог моделей OpenRouter.
     */
    suspend fun fetchAvailableModels(
        apiKey: String,
        useSmartDns: Boolean = true
    ): Result<List<OpenRouterModelInfo>> = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url("$BASE_URL/models")
            .header("HTTP-Referer", REFERER)
            .header("X-Title", APP_TITLE)
            .header("Accept", "application/json")
            .get()

        val cleanKey = apiKey.trim()
        if (cleanKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $cleanKey")
        }

        // Проверяем наличие платного доступа / подписки у API-ключа
        var hasPaidCredits = false
        if (cleanKey.isNotEmpty()) {
            try {
                val keyAuthReq = Request.Builder()
                    .url("$BASE_URL/auth/key")
                    .header("Authorization", "Bearer $cleanKey")
                    .header("HTTP-Referer", REFERER)
                    .header("X-Title", APP_TITLE)
                    .get()
                    .build()
                val keyResp = getClient(useSmartDns).newCall(keyAuthReq).execute()
                keyResp.use { kr ->
                    if (kr.isSuccessful) {
                        val authJson = JSONObject(kr.body?.string().orEmpty())
                        val dataObj = authJson.optJSONObject("data")
                        if (dataObj != null) {
                            val isFreeTier = dataObj.optBoolean("is_free_tier", true)
                            val limit = dataObj.optDouble("limit", 0.0)
                            val usage = dataObj.optDouble("usage", 0.0)
                            hasPaidCredits = !isFreeTier || (limit > 0.0 && usage < limit)
                        }
                    }
                }
            } catch (_: Exception) {
                // Игнорируем сбой проверки баланса, опираемся на pricing моделей
            }
        }

        try {
            val response = getClient(useSmartDns).newCall(requestBuilder.build()).execute()
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    AppLogger.w(TAG, "Ошибка каталога OpenRouter: HTTP ${resp.code} $body")
                    return@withContext Result.success(POPULAR_MODELS)
                }

                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: JSONArray()
                val list = mutableListOf<OpenRouterModelInfo>()

                for (i in 0 until data.length()) {
                    val m = data.getJSONObject(i)
                    val id = m.optString("id", "")
                    if (id.isBlank()) continue

                    val pricing = m.optJSONObject("pricing")
                    val promptPrice = pricing?.optDouble("prompt", 0.0) ?: 0.0
                    val compPrice = pricing?.optDouble("completion", 0.0) ?: 0.0
                    val isFreeModel = id.endsWith(":free") || (promptPrice == 0.0 && compPrice == 0.0)

                    // Если у пользователя бесплатный аккаунт/ключ — показываем только бесплатные модели.
                    // Если у ключа есть подписка или платный баланс — открываем и платные.
                    if (isFreeModel || hasPaidCredits) {
                        val name = m.optString("name", id) + if (isFreeModel && !id.endsWith(":free")) " (Free)" else ""
                        val desc = m.optString("description", "")
                        val ctx = m.optInt("context_length", 0)
                        list.add(
                            OpenRouterModelInfo(
                                id = id,
                                displayName = name,
                                description = desc,
                                contextLength = ctx
                            )
                        )
                    }
                }

                // Сортировка: сначала бесплатные модели, затем по алфавиту
                list.sortWith(Comparator { a, b ->
                    val aFree = a.id.endsWith(":free") || a.displayName.contains("(Free)")
                    val bFree = b.id.endsWith(":free") || b.displayName.contains("(Free)")
                    if (aFree && !bFree) -1 else if (!aFree && bFree) 1 else a.displayName.compareTo(b.displayName)
                })

                if (list.isEmpty()) Result.success(POPULAR_MODELS) else Result.success(list)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Сбой загрузки каталога OpenRouter: ${e.message}")
            Result.success(POPULAR_MODELS)
        }
    }

    /**
     * Потоковая отправка запроса в OpenRouter (Server-Sent Events).
     */
    suspend fun sendMessageStream(
        apiKey: String,
        model: String,
        history: List<AiChatMessage>,
        userPrompt: String,
        systemInstruction: String?,
        temperature: Float = 0.7f,
        useSmartDns: Boolean = true,
        onChunk: (textChunk: String, thoughtChunk: String) -> Unit
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Требуется указать API-ключ OpenRouter"))
        }

        val cleanModel = model.trim().ifEmpty { "deepseek/deepseek-r1" }
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

        // 3. Текущее сообщение
        messagesArray.put(JSONObject().apply {
            put("role", "user")
            put("content", userPrompt)
        })

        val requestJson = JSONObject().apply {
            put("model", cleanModel)
            put("messages", messagesArray)
            put("stream", true)
            put("temperature", temperature.toDouble())
        }

        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer $cleanKey")
            .header("HTTP-Referer", REFERER)
            .header("X-Title", APP_TITLE)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = getClient(useSmartDns).newCall(request)
        currentCall.set(call)

        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                response.close()
                val message = parseErrorMessage(errorBody, response.code)
                return@withContext Result.failure(IllegalStateException(message))
            }

            val body = response.body ?: return@withContext Result.failure(IllegalStateException("Пустой ответ от сервера OpenRouter"))
            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))

            val fullText = StringBuilder()
            val fullThought = StringBuilder()
            var isInsideThinkBlock = false

            reader.useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                    if (!trimmed.startsWith("data:")) continue

                    val dataStr = trimmed.removePrefix("data:").trim()
                    if (dataStr == "[DONE]") break

                    try {
                        val chunkObj = JSONObject(dataStr)
                        val choices = chunkObj.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue

                        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue

                        // 1. Прямое поле рассуждений в OpenRouter (reasoning)
                        val reasoningPiece = delta.optString("reasoning", "")
                        if (reasoningPiece.isNotEmpty()) {
                            fullThought.append(reasoningPiece)
                            onChunk("", reasoningPiece)
                        }

                        // 2. Текстовое содержимое (content)
                        val contentPiece = delta.optString("content", "")
                        if (contentPiece.isNotEmpty()) {
                            var remaining = contentPiece
                            while (remaining.isNotEmpty()) {
                                if (!isInsideThinkBlock) {
                                    val thinkStart = remaining.indexOf("<think>")
                                    if (thinkStart != -1) {
                                        val textBefore = remaining.substring(0, thinkStart)
                                        if (textBefore.isNotEmpty()) {
                                            fullText.append(textBefore)
                                            onChunk(textBefore, "")
                                        }
                                        isInsideThinkBlock = true
                                        remaining = remaining.substring(thinkStart + 7)
                                    } else {
                                        fullText.append(remaining)
                                        onChunk(remaining, "")
                                        remaining = ""
                                    }
                                } else {
                                    val thinkEnd = remaining.indexOf("</think>")
                                    if (thinkEnd != -1) {
                                        val thoughtPiece = remaining.substring(0, thinkEnd)
                                        if (thoughtPiece.isNotEmpty()) {
                                            fullThought.append(thoughtPiece)
                                            onChunk("", thoughtPiece)
                                        }
                                        isInsideThinkBlock = false
                                        remaining = remaining.substring(thinkEnd + 8)
                                    } else {
                                        fullThought.append(remaining)
                                        onChunk("", remaining)
                                        remaining = ""
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Сбой разбора чанка OpenRouter: ${e.message}")
                    }
                }
            }

            Result.success(Pair(fullText.toString(), fullThought.toString()))
        } catch (e: Exception) {
            if (call.isCanceled()) {
                Result.failure(CancellationException("Запрос отменен пользователем"))
            } else {
                AppLogger.e(TAG, "Ошибка соединения OpenRouter: ${e.message}", e)
                Result.failure(e)
            }
        } finally {
            currentCall.compareAndSet(call, null)
        }
    }

    private fun parseErrorMessage(body: String, code: Int): String {
        return try {
            val json = JSONObject(body)
            val err = json.optJSONObject("error")
            val msg = err?.optString("message", "")
            if (!msg.isNullOrBlank()) {
                "OpenRouter ($code): $msg"
            } else {
                defaultErrorText(code)
            }
        } catch (_: Exception) {
            defaultErrorText(code)
        }
    }

    private fun defaultErrorText(code: Int): String = when (code) {
        401 -> "Неверный API-ключ OpenRouter (HTTP 401 Unauthorized)"
        402 -> "Недостаточно средств на балансе аккаунта OpenRouter (HTTP 402 Payment Required)"
        403 -> "Доступ к модели ограничен или запрещен (HTTP 403 Forbidden)"
        429 -> "Превышен лимит запросов OpenRouter (HTTP 429 Too Many Requests)"
        500, 502, 503 -> "Сервер OpenRouter временно недоступен (HTTP $code)"
        else -> "Сбой обращения к OpenRouter (HTTP $code)"
    }
}
