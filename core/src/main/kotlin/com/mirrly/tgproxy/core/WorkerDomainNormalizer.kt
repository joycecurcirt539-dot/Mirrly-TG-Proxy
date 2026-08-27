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
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.mirrly.tgproxy.core

import java.net.IDN
import java.net.URLDecoder

enum class DomainFormatStatus {
    VALID,
    HOMOGLYPHS_FIXED,
    DASHBOARD_URL,
    NAME_ONLY,
    SWAPPED_FIELDS,
    EMPTY,
    INVALID
}

data class NormalizedDomainResult(
    val rawInput: String,
    val cleanDomain: String,
    val suggestedName: String,
    val candidateDomains: List<String> = emptyList(),
    val status: DomainFormatStatus,
    val extractedDashboardWorkerName: String? = null,
    val userMessage: String
) {
    val isValid: Boolean
        get() = (status == DomainFormatStatus.VALID || status == DomainFormatStatus.HOMOGLYPHS_FIXED) && cleanDomain.isNotBlank()
}

data class FormNormalizationResult(
    val normalizedDomain: String,
    val normalizedName: String,
    val wasSwapped: Boolean,
    val domainResult: NormalizedDomainResult
)

object WorkerDomainNormalizer {

    private val DASHBOARD_REGEX = Regex(
        """dash\.cloudflare\.com/[a-fA-F0-9]+/workers-and-pages/view/([^/?#\s]+)""",
        RegexOption.IGNORE_CASE
    )

    private val DASHBOARD_SERVICE_REGEX = Regex(
        """dash\.cloudflare\.com/[a-fA-F0-9]+/workers/services/view/([^/?#\s]+)""",
        RegexOption.IGNORE_CASE
    )

    private val FQDN_REGEX = Regex(
        """\b([a-zA-Z0-9][-a-zA-Z0-9]*(?:\.[a-zA-Z0-9][-a-zA-Z0-9]*)*\.workers\.dev|[a-zA-Z0-9][-a-zA-Z0-9]*\.[a-zA-Z]{2,}(?::\d+)?)\b""",
        RegexOption.IGNORE_CASE
    )

    private val HOMOGLYPH_MAP = mapOf(
        'а' to 'a', 'А' to 'A',
        'о' to 'o', 'О' to 'O',
        'е' to 'e', 'Е' to 'E',
        'р' to 'p', 'Р' to 'P',
        'с' to 'c', 'С' to 'C',
        'х' to 'x', 'Х' to 'X',
        'у' to 'y', 'У' to 'Y',
        'і' to 'i', 'І' to 'I',
        'к' to 'k', 'К' to 'K',
        'м' to 'm', 'М' to 'M',
        'в' to 'b', 'В' to 'B',
        'т' to 't', 'Т' to 'T'
    )

    fun normalize(input: String): NormalizedDomainResult {
        val trimmed = cleanSurroundingTrash(input)
        if (trimmed.isBlank()) {
            return NormalizedDomainResult(
                rawInput = input,
                cleanDomain = "",
                suggestedName = "",
                status = DomainFormatStatus.EMPTY,
                userMessage = "Введите публичный адрес воркера (например: my-proxy.username.workers.dev)"
            )
        }

        // 1. Проверка на Deep Link / Sharing URL (mirrly.app/worker?domain=... или mirrly://...)
        val extractedFromDeepLink = extractFromDeepLink(trimmed)
        if (extractedFromDeepLink != null) {
            val (deepDomain, deepName) = extractedFromDeepLink
            val innerRes = normalize(deepDomain)
            val finalName = if (deepName.isNotBlank()) deepName else innerRes.suggestedName
            return innerRes.copy(
                rawInput = input,
                suggestedName = finalName
            )
        }

        // 2. Детектор ссылки Cloudflare Dashboard (dash.cloudflare.com/...)
        val dashWorkerName = extractDashboardWorkerName(trimmed)
        if (dashWorkerName != null) {
            val candidate = "$dashWorkerName.workers.dev"
            return NormalizedDomainResult(
                rawInput = input,
                cleanDomain = candidate,
                suggestedName = dashWorkerName,
                candidateDomains = listOf(candidate),
                status = DomainFormatStatus.DASHBOARD_URL,
                extractedDashboardWorkerName = dashWorkerName,
                userMessage = "Вы вставили ссылку на панель управления Cloudflare. Публичный адрес находится на вкладке Deployments и имеет вид: $dashWorkerName.ваш-аккаунт.workers.dev"
            )
        }

        // 3. Замена кириллических букв-двойников (гомоглифов)
        val (homoglyphCleaned, hadHomoglyphs) = fixHomoglyphs(trimmed)

        // 4. Очистка схем протоколов, путей, портов и учетных данных
        var clean = homoglyphCleaned
            .replace(Regex("""^(?i)(https?|wss?|tcp|mirrly|tg)://+"""), "")
            .replace(Regex("""^(?i)(https?|wss?|tcp|mirrly|tg):/*"""), "")

        if (clean.contains("@")) {
            clean = clean.substringAfterLast("@")
        }

        // Удаление путей, query и fragments
        clean = clean.substringBefore('/').substringBefore('?').substringBefore('#')

        // Удаление порта (:443, :8443, :10808)
        if (clean.contains(":")) {
            clean = clean.substringBefore(':')
        }

        // Удаление остаточных кавычек и слэшей
        clean = clean.trim().trim('/', '"', '\'', '`', '«', '»', '“', '”', ' ', '\t', '\n', '\r')

        // 5. Поиск валидного FQDN внутри строки (если пользователь вставил текст с логами)
        if (!isValidDomainPattern(clean)) {
            val foundMatch = FQDN_REGEX.find(homoglyphCleaned)
            if (foundMatch != null) {
                var extracted = foundMatch.groupValues[1]
                if (extracted.contains(":")) extracted = extracted.substringBefore(':')
                clean = extracted.trim()
            }
        }

        // 6. Поддержка Punycode IDN (если кириллический домен вроде воркер.рф)
        clean = try {
            IDN.toASCII(clean)
        } catch (_: Exception) {
            clean
        }

        // 7. Анализ результата
        if (clean.isBlank()) {
            return NormalizedDomainResult(
                rawInput = input,
                cleanDomain = "",
                suggestedName = "",
                status = DomainFormatStatus.INVALID,
                userMessage = "Не удалось распознать адрес. Пример: my-proxy.username.workers.dev"
            )
        }

        // Запрещенные петли и локальные хосты
        if (clean.equals("localhost", ignoreCase = true) ||
            clean.equals("127.0.0.1", ignoreCase = true) ||
            clean.equals("0.0.0.0", ignoreCase = true) ||
            clean.equals("dash.cloudflare.com", ignoreCase = true) ||
            clean.equals("cloudflare.com", ignoreCase = true)
        ) {
            return NormalizedDomainResult(
                rawInput = input,
                cleanDomain = clean,
                suggestedName = clean,
                status = DomainFormatStatus.INVALID,
                userMessage = "Адрес «$clean» не может быть использован в качестве прокси-узла."
            )
        }

        // Если введен только идентификатор без точки (например, "my-proxy")
        if (!clean.contains(".")) {
            val candidate = "$clean.workers.dev"
            return NormalizedDomainResult(
                rawInput = input,
                cleanDomain = candidate,
                suggestedName = clean,
                candidateDomains = listOf(candidate),
                status = DomainFormatStatus.NAME_ONLY,
                userMessage = "Указано только имя воркера. Публичный адрес должен иметь вид: $clean.ваш-аккаунт.workers.dev"
            )
        }

        val suggestedName = suggestWorkerName(clean)
        val finalStatus = if (hadHomoglyphs) DomainFormatStatus.HOMOGLYPHS_FIXED else DomainFormatStatus.VALID
        val userMsg = if (hadHomoglyphs) {
            "Распознан узел: $clean (исправлены буквы русской раскладки)"
        } else {
            "Распознан публичный узел: $clean"
        }

        return NormalizedDomainResult(
            rawInput = input,
            cleanDomain = clean,
            suggestedName = suggestedName,
            candidateDomains = listOf(clean),
            status = finalStatus,
            userMessage = userMsg
        )
    }

    fun sanitizeDomain(input: String): String {
        return normalize(input).cleanDomain
    }

    fun normalizeForm(nameInput: String, domainInput: String): FormNormalizationResult {
        val cleanName = cleanSurroundingTrash(nameInput)
        val cleanDomain = cleanSurroundingTrash(domainInput)

        val nameLooksLikeDomain = isLikelyDomain(cleanName)
        val domainLooksLikeDomain = isLikelyDomain(cleanDomain)

        // Случай: пользователь вставил URL/домен в поле названия, а поле домена пустое или содержит обычный текст
        if (nameLooksLikeDomain && !domainLooksLikeDomain) {
            val domainRes = normalize(cleanName)
            val finalName = if (cleanDomain.isNotBlank() && !cleanDomain.contains("http", ignoreCase = true)) {
                cleanDomain
            } else {
                domainRes.suggestedName
            }
            return FormNormalizationResult(
                normalizedDomain = domainRes.cleanDomain,
                normalizedName = finalName,
                wasSwapped = true,
                domainResult = domainRes
            )
        }

        // Обычный случай: домен в поле домена
        val domainRes = normalize(cleanDomain)
        val finalName = if (cleanName.isNotBlank()) {
            cleanName
        } else {
            domainRes.suggestedName
        }

        return FormNormalizationResult(
            normalizedDomain = domainRes.cleanDomain,
            normalizedName = finalName,
            wasSwapped = false,
            domainResult = domainRes
        )
    }

    fun isLikelyDomain(input: String): Boolean {
        val s = input.trim().lowercase()
        if (s.isBlank()) return false
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("wss://") || s.startsWith("ws://") || s.startsWith("mirrly://")) {
            return true
        }
        if (s.contains(".workers.dev") || s.contains("dash.cloudflare.com")) {
            return true
        }
        if (s.contains(".") && !s.contains(" ") && s.length >= 4) {
            val lastPart = s.substringAfterLast('.')
            return lastPart.length in 2..10 && lastPart.all { it in 'a'..'z' }
        }
        return false
    }

    fun suggestWorkerName(domain: String): String {
        val s = domain.trim().lowercase()
        if (s.contains(".workers.dev")) {
            val prefix = s.substringBefore(".workers.dev")
            val workerName = if (prefix.contains(".")) prefix.substringBefore(".") else prefix
            return workerName.replace('-', ' ').replace('_', ' ').split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
                .ifBlank { "Мой воркер" }
        }

        val clean = s.substringBefore('.').replace('-', ' ').replace('_', ' ')
        return clean.split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
            .ifBlank { "Личный воркер" }
    }

    fun extractDashboardWorkerName(input: String): String? {
        val trimmed = cleanSurroundingTrash(input)
        val match1 = DASHBOARD_REGEX.find(trimmed)
        if (match1 != null) {
            return match1.groupValues[1].substringBefore('?').substringBefore('#').trim()
        }
        val match2 = DASHBOARD_SERVICE_REGEX.find(trimmed)
        if (match2 != null) {
            return match2.groupValues[1].substringBefore('?').substringBefore('#').trim()
        }
        if (trimmed.contains("dash.cloudflare.com", ignoreCase = true)) {
            val after = trimmed.substringAfter("view/", "").substringBefore('/').substringBefore('?').substringBefore('#').trim()
            if (after.isNotBlank()) return after
        }
        return null
    }

    fun fixHomoglyphs(input: String): Pair<String, Boolean> {
        var modified = false
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val replacement = HOMOGLYPH_MAP[ch]
            if (replacement != null) {
                sb.append(replacement)
                modified = true
            } else {
                sb.append(ch)
            }
        }
        return Pair(sb.toString(), modified)
    }

    private fun cleanSurroundingTrash(input: String): String {
        var s = input.trim()
        // Удаление Zero-width и неразрывных пробелов
        s = s.replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\uFEFF", "")
            .replace("\u00A0", " ")
            .trim()

        // Удаление кавычек, скобок и префиксов вроде "Домен: " или "url = "
        s = s.replace(Regex("""^(?iu)(домен|url|host|worker|адрес|сервер)\s*[:=]\s*"""), "")
        s = s.trim('"', '\'', '`', '«', '»', '“', '”', '‘', '’', '(', ')', '[', ']', '<', '>', '{', '}', ' ', '\t', '\n', '\r')
        return s
    }

    private fun extractFromDeepLink(input: String): Pair<String, String>? {
        if (!input.contains("domain=", ignoreCase = true) &&
            !input.contains("d=", ignoreCase = true) &&
            !input.contains("worker=", ignoreCase = true) &&
            !input.contains("server=", ignoreCase = true) &&
            !input.contains("host=", ignoreCase = true)
        ) {
            return null
        }

        val queryPart = input.substringAfter('?', "")
        if (queryPart.isEmpty()) return null

        var extractedDomain = ""
        var extractedName = ""

        val params = queryPart.split('&')
        for (p in params) {
            val key = p.substringBefore('=').lowercase().trim()
            val rawVal = p.substringAfter('=', "").trim()
            val value = try {
                URLDecoder.decode(rawVal, "UTF-8")
            } catch (_: Exception) {
                rawVal
            }

            if (key in listOf("domain", "d", "worker", "host", "url", "server", "s") && value.isNotEmpty()) {
                extractedDomain = value
            } else if (key in listOf("name", "n", "title") && value.isNotEmpty()) {
                extractedName = value
            }
        }

        if (extractedDomain.isNotEmpty()) {
            return Pair(extractedDomain, extractedName)
        }
        return null
    }

    private fun isValidDomainPattern(domain: String): Boolean {
        if (domain.isBlank() || !domain.contains(".")) return false
        val parts = domain.split('.')
        if (parts.any { it.isBlank() }) return false
        val tld = parts.last()
        return tld.length >= 2 && tld.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    }
}
