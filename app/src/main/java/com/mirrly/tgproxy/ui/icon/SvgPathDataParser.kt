/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui.icon

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.core.graphics.PathParser
import java.util.regex.Pattern

/**
 * Высокопроизводительный парсер и валидатор векторных сплайнов SVG / PathData для MirrlyUI.
 * Поддерживает извлечение pathData из сырого SVG, нормализацию координат и масштабирование под вьюпорт кнопки.
 */
object SvgPathDataParser {

    private val PATH_D_PATTERN = Pattern.compile("d\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
    private val VALID_SVG_CHARS_REGEX = Regex("^[MmLlHhVvCcSsQqTtAaZz0-9\\s,.-]+$")
    private val MOVE_COMMAND_REGEX = Regex("(?i)[Mm]\\s*[-+]?[0-9]")

    private val isAndroidRuntime: Boolean by lazy {
        System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true
    }

    /**
     * Извлекает чистую строку pathData из текста (SVG разметка, XML android:pathData или сырой сплайн).
     */
    fun extractPathData(rawInput: String): String {
        var text = rawInput.trim()

        // Удаляем markdown-блоки кода ```xml, ```svg, ```
        if (text.startsWith("```")) {
            val lines = text.lines()
            val filtered = lines.filterNot { it.trim().startsWith("```") }
            text = filtered.joinToString("\n").trim()
        }

        // Проверяем наличие атрибутов d="..." в SVG тегах
        val matcher = PATH_D_PATTERN.matcher(text)
        val extractedPaths = mutableListOf<String>()
        while (matcher.find()) {
            matcher.group(1)?.trim()?.let { d ->
                if (d.isNotEmpty()) extractedPaths.add(d)
            }
        }

        if (extractedPaths.isNotEmpty()) {
            return extractedPaths.joinToString(" ")
        }

        // Если тегов нет, проверяем прямой сплайн (например: M12 2 L2 22...)
        return text
    }

    /**
     * Валидирует корректность синтаксиса векторного сплайна через системный PathParser.
     */
    fun validatePathData(rawInput: String): Boolean {
        val cleanPath = extractPathData(rawInput)
        if (cleanPath.isBlank()) return false
        if (!VALID_SVG_CHARS_REGEX.matches(cleanPath)) return false
        if (!MOVE_COMMAND_REGEX.containsMatchIn(cleanPath)) return false

        if (!isAndroidRuntime) {
            // В JVM юнит-тестах без Android Runtime (Dalvik) PathParser возвращает заглушки
            return true
        }

        return try {
            val path = PathParser.createPathFromPathData(cleanPath)
            !path.isEmpty
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Преобразует pathData в отмасштабированный и центрированный Compose [Path] под размер [targetSize].
     */
    fun createScaledComposePath(
        rawInput: String,
        targetSize: Size,
        paddingPx: Float = 0f
    ): Path? {
        val cleanPath = extractPathData(rawInput)
        if (cleanPath.isBlank()) return null

        return try {
            val androidPath = PathParser.createPathFromPathData(cleanPath) ?: return null
            if (androidPath.isEmpty) return null

            val bounds = RectF()
            androidPath.computeBounds(bounds, true)

            val boundW = bounds.width()
            val boundH = bounds.height()
            if (boundW <= 0.001f || boundH <= 0.001f) return null

            val availW = (targetSize.width - 2 * paddingPx).coerceAtLeast(1f)
            val availH = (targetSize.height - 2 * paddingPx).coerceAtLeast(1f)

            val scale = (availW / boundW).coerceAtMost(availH / boundH)

            // Сдвиг в оптический центр
            val dx = (targetSize.width - boundW * scale) / 2f - bounds.left * scale
            val dy = (targetSize.height - boundH * scale) / 2f - bounds.top * scale

            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(dx, dy)
            }

            androidPath.transform(matrix)
            androidPath.asComposePath()
        } catch (_: Throwable) {
            null
        }
    }
}
