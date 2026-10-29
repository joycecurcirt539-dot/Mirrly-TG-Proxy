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

import java.util.regex.Pattern

/**
 * Модуль санитарной обработки и маскирования данных перед отправкой в Gemini.
 * Обеспечивает соблюдение политики Zero Leakage:
 * 1. Маскирует персональные адреса Cloudflare Workers (*.workers.dev и кастомные домены).
 * 2. Удаляет приватные ключи, токены ботов, API-ключи и зашифрованные конфигурации.
 * 3. Маскирует внешние IP-адреса, сохраняя только локальный loopback (127.0.0.1) и сервисные порты (1080, 10808).
 */
object AiSanitizer {

    // Регулярные выражения для учетных данных
    private val GOOGLE_API_KEY_REGEX = Pattern.compile("\\bAIzaSy[A-Za-z0-9_-]{33}\\b")
    private val TELEGRAM_BOT_TOKEN_REGEX = Pattern.compile("\\b\\d{8,11}:[A-Za-z0-9_-]{35}\\b")
    private val WIREGUARD_KEY_REGEX = Pattern.compile("\\b[A-Za-z0-9+/]{42,43}={1,2}(?![A-Za-z0-9+/=])")
    private val BEARER_TOKEN_REGEX = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9_\\-\\.\\:]{20,}\\b")
    private val FLOWSEAL_SECRET_REGEX = Pattern.compile("enc:v1:[A-Za-z0-9+/=]+")

    // Регулярные выражения для сетевых узлов и доменов
    private val CF_WORKER_SUBDOMAIN_REGEX = Pattern.compile("(?i)\\b[a-zA-Z0-9_-]+\\.workers\\.dev\\b")

    // IPv4 (исключая loopback 127.0.0.1 и wildcard 0.0.0.0)
    private val IPV4_REGEX = Pattern.compile(
        "\\b(?!(?:127\\.0\\.0\\.1|0\\.0\\.0\\.0)\\b)(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\b"
    )

    // IPv6 (исключая ::1)
    private val IPV6_REGEX = Pattern.compile(
        "(?i)\\b(?!(?:::1)\\b)(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}\\b|" +
        "(?i)\\b(?!(?:::1)\\b)(?:[0-9a-f]{1,4}:){1,7}:[0-9a-f]{1,4}\\b|" +
        "(?i)\\b(?!(?:::1)\\b)(?:[0-9a-f]{1,4}:){1,6}:(?:[0-9a-f]{1,4}:)?[0-9a-f]{1,4}\\b"
    )

    /**
     * Выполняет комплексную санитарную обработку входящего текста.
     *
     * @param rawText Необработанный текст лога, системной метрики или запроса.
     * @param customDomains Список пользовательских доменов воркеров для дополнительного маскирования.
     * @return Полностью анонимизированный и безопасный для внешней передачи текст.
     */
    fun sanitize(rawText: String, customDomains: Collection<String> = emptySet()): String {
        if (rawText.isBlank()) return rawText

        var sanitized = rawText

        // 1. Маскирование учетных данных и токенов
        sanitized = GOOGLE_API_KEY_REGEX.matcher(sanitized).replaceAll("[REDACTED_CREDENTIAL]")
        sanitized = TELEGRAM_BOT_TOKEN_REGEX.matcher(sanitized).replaceAll("[REDACTED_CREDENTIAL]")
        sanitized = WIREGUARD_KEY_REGEX.matcher(sanitized).replaceAll("[REDACTED_CREDENTIAL]")
        sanitized = BEARER_TOKEN_REGEX.matcher(sanitized).replaceAll("Bearer [REDACTED_CREDENTIAL]")
        sanitized = FLOWSEAL_SECRET_REGEX.matcher(sanitized).replaceAll("[REDACTED_CREDENTIAL]")

        // 2. Маскирование Cloudflare Workers (*.workers.dev -> ***.workers.dev)
        sanitized = CF_WORKER_SUBDOMAIN_REGEX.matcher(sanitized).replaceAll("***.workers.dev")

        // Маскирование кастомных доменов пользователя
        for (domain in customDomains) {
            val cleanDomain = domain.trim().removePrefix("https://").removePrefix("http://")
                .removePrefix("wss://").removeSuffix("/")
            if (cleanDomain.length >= 4 && !cleanDomain.contains("cloudflare.com") && !cleanDomain.contains("workers.dev")) {
                val escaped = Pattern.quote(cleanDomain)
                val maskedDomain = maskDomainName(cleanDomain)
                sanitized = sanitized.replace(Regex(escaped, RegexOption.IGNORE_CASE), maskedDomain)
            }
        }

        // 3. Маскирование внешних IP-адресов
        sanitized = IPV4_REGEX.matcher(sanitized).replaceAll("xxx.xxx.xxx.xxx")
        sanitized = IPV6_REGEX.matcher(sanitized).replaceAll("xxxx::xxxx")

        return sanitized
    }

    /**
     * Маскирует кастомный домен в формате p***y.com или [CUSTOM_DOMAIN].
     */
    private fun maskDomainName(domain: String): String {
        val parts = domain.split(".")
        if (parts.size >= 2) {
            val first = domain.first()
            val tld = parts.last()
            val beforeTld = domain.substringBeforeLast(".")
            val lastCharBeforeTld = beforeTld.last()
            return "$first***$lastCharBeforeTld.$tld"
        }
        return "[CUSTOM_DOMAIN]"
    }
}
