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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Модель сообщения в AI-диалоге с Gemini.
 */
data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user", "model", "tool"
    val text: String,
    val thought: String = "",
    val toolName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val isExecutingTool: Boolean = false
) {
    val isUser: Boolean get() = role == "user"
    val isModel: Boolean get() = role == "model"
    val isTool: Boolean get() = role == "tool"
}

/**
 * Менеджер сохранения и загрузки истории сообщений в локальном JSON-файле.
 * Реализует скользящее окно (максимум 50 сообщений) для защиты от переполнения памяти и токенов.
 */
object AiHistoryManager {

    private const val TAG = "AiHistoryManager"
    private const val FILE_NAME = "ai_chat_history.json"
    private const val MAX_SAVED_MESSAGES = 50

    @Synchronized
    fun loadMessages(context: Context): List<AiChatMessage> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()

        return try {
            val content = file.readText()
            val array = JSONArray(content)
            val list = mutableListOf<AiChatMessage>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    AiChatMessage(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        role = obj.optString("role", "user"),
                        text = obj.optString("text", ""),
                        thought = obj.optString("thought", ""),
                        toolName = if (obj.has("toolName")) obj.optString("toolName") else null,
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        isError = obj.optBoolean("isError", false),
                        isExecutingTool = false
                    )
                )
            }
            list
        } catch (e: Exception) {
            AppLogger.e(TAG, "Ошибка чтения истории AI чата", e)
            emptyList()
        }
    }

    @Synchronized
    fun saveMessages(context: Context, messages: List<AiChatMessage>) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val trimmed = messages.takeLast(MAX_SAVED_MESSAGES)
            val array = JSONArray()
            for (msg in trimmed) {
                val obj = JSONObject().apply {
                    put("id", msg.id)
                    put("role", msg.role)
                    put("text", msg.text)
                    put("thought", msg.thought)
                    if (msg.toolName != null) put("toolName", msg.toolName)
                    put("timestamp", msg.timestamp)
                    put("isError", msg.isError)
                }
                array.put(obj)
            }
            file.writeText(array.toString(2))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Ошибка сохранения истории AI чата", e)
        }
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Ошибка очистки истории AI чата", e)
        }
    }
}
