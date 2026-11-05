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

package com.mirrly.tgproxy.ai

/**
 * Утилита форматирования наименований нейросетевых моделей.
 *
 * Преобразует технические идентификаторы моделей (например,
 * "@cf/meta/llama-3.3-70b-instruct" или "deepseek/deepseek-r1:free")
 * в компактные, лаконичные и понятные человеку названия для отображения
 * в шапке чата, чипах выбора и списках каталога (например, "Llama 3.3 70B", "DeepSeek R1").
 */
object AiModelFormatter {

    /**
     * Возвращает компактное читаемое имя модели для компактных плашек и заголовков.
     */
    fun formatShortName(modelId: String): String {
        val clean = modelId.trim()
        if (clean.isEmpty()) return ""

        // Точные соответствия для популярных моделей
        when (clean) {
            // Cloudflare Workers AI
            "@cf/meta/llama-3.3-70b-instruct" -> return "Llama 3.3 70B"
            "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b" -> return "DeepSeek R1 32B"
            "@cf/meta/llama-3.1-8b-instruct" -> return "Llama 3.1 8B"
            "@cf/qwen/qwen2.5-72b-instruct" -> return "Qwen 2.5 72B"
            "@cf/mistral/mistral-7b-instruct-v0.2" -> return "Mistral 7B"

            // OpenRouter популярные
            "deepseek/deepseek-r1", "deepseek/deepseek-r1:free" -> return "DeepSeek R1"
            "deepseek/deepseek-chat", "deepseek/deepseek-chat:free" -> return "DeepSeek V3"
            "anthropic/claude-3.5-sonnet", "anthropic/claude-3-5-sonnet" -> return "Claude 3.5 Sonnet"
            "openai/gpt-4o" -> return "GPT-4o"
            "openai/gpt-4o-mini" -> return "GPT-4o Mini"
            "meta-llama/llama-3.3-70b-instruct" -> return "Llama 3.3 70B"
            "meta-llama/llama-3.1-8b-instruct" -> return "Llama 3.1 8B"
            "google/gemini-2.0-flash-001" -> return "Gemini 2.0 Flash"
            "google/gemini-2.5-flash" -> return "Gemini 2.5 Flash"
            "google/gemini-2.5-flash-lite" -> return "Gemini 2.5 Lite"
            "qwen/qwen-2.5-72b-instruct" -> return "Qwen 2.5 72B"
            "mistralai/mistral-7b-instruct" -> return "Mistral 7B"

            // Google Gemini
            "gemini-3.6-flash" -> return "Gemini 3.6 Flash"
            "gemini-3.5-flash" -> return "Gemini 3.5 Flash"
            "gemini-3.5-flash-lite" -> return "Gemini 3.5 Lite"
            "gemini-2.5-flash" -> return "Gemini 2.5 Flash"
            "gemini-2.5-flash-lite" -> return "Gemini 2.5 Lite"
            "gemini-2.5-pro" -> return "Gemini 2.5 Pro"
            "gemini-3.8-flash" -> return "Gemini 3.8 Flash"
            "gemini-2.0-flash" -> return "Gemini 2.0 Flash"
            "gemini-1.5-flash" -> return "Gemini 1.5 Flash"
            "gemini-1.5-pro" -> return "Gemini 1.5 Pro"
        }

        // Эвристический парсер для произвольных ID
        var s = clean.removeSuffix(":free").trim()

        if (s.startsWith("@cf/")) {
            val parts = s.split("/")
            s = parts.lastOrNull() ?: s
        } else if (s.contains("/")) {
            val parts = s.split("/")
            s = parts.lastOrNull() ?: s
        }

        s = s.replace(Regex("-instruct(-v[0-9.]+)?$", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("-preview$", RegexOption.IGNORE_CASE), "")

        return when {
            s.startsWith("llama-", ignoreCase = true) -> {
                val rem = s.substring(6)
                "Llama ${rem.replace("-", " ").uppercase()}"
            }
            s.startsWith("deepseek-", ignoreCase = true) -> {
                val rem = s.substring(9)
                "DeepSeek ${rem.replace("-", " ").uppercase()}"
            }
            s.startsWith("qwen", ignoreCase = true) -> {
                val rem = s.removePrefix("qwen-").removePrefix("qwen").removePrefix("2.").let { "2.$it" }
                "Qwen ${rem.replace("-", " ").uppercase()}"
            }
            s.startsWith("gemini-", ignoreCase = true) -> {
                val rem = s.substring(7)
                "Gemini ${rem.replace("-", " ").capitalizeWords()}"
            }
            s.startsWith("claude-", ignoreCase = true) -> {
                val rem = s.substring(7)
                "Claude ${rem.replace("-", " ").capitalizeWords()}"
            }
            s.startsWith("mistral-", ignoreCase = true) -> {
                val rem = s.substring(8)
                "Mistral ${rem.replace("-", " ").uppercase()}"
            }
            s.startsWith("gpt-", ignoreCase = true) -> {
                s.uppercase()
            }
            else -> {
                s.replace("-", " ").trim().capitalizeWords()
            }
        }
    }

    private fun String.capitalizeWords(): String = split(" ").filter { it.isNotEmpty() }.joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
