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

package com.mirrly.tgproxy.service.cloudflare

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

object CloudflareWorkerPayload {

    const val SCRIPT_VERSION = 2
    const val DEFAULT_WORKER_PREFIX = "mtg-relay-"

    private val CYRILLIC_MAP = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    private var cachedScriptContent: String? = null
    private var cachedScriptSha256: String? = null

    /**
     * Retrieves the JS code of the Telegram Relay Cloudflare Worker.
     */
    fun getWorkerScript(context: Context): String {
        cachedScriptContent?.let { return it }
        return try {
            val content = context.assets.open("worker.js").bufferedReader().use { it.readText() }
            cachedScriptContent = content
            content
        } catch (e: Exception) {
            // Safe fallback if asset read fails
            cachedScriptContent ?: ""
        }
    }

    /**
     * Calculates SHA-256 fingerprint of the current bundled worker.js
     */
    fun getScriptSha256(context: Context): String {
        cachedScriptSha256?.let { return it }
        val script = getWorkerScript(context)
        if (script.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        cachedScriptSha256 = hex
        return hex
    }

    /**
     * Generates a random compliant worker name: mtg-relay-xxxxxx
     */
    fun generateRandomWorkerName(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rng = SecureRandom()
        val randomSuffix = (1..6)
            .map { chars[rng.nextInt(chars.length)] }
            .joinToString("")
        return "$DEFAULT_WORKER_PREFIX$randomSuffix"
    }

    /**
     * Normalizes worker name according to Cloudflare rules:
     * - Transliterates Cyrillic characters
     * - Replaces spaces and disallowed symbols with hyphens
     * - Max 60 characters
     */
    fun normalizeWorkerName(rawInput: String?): String {
        if (rawInput.isNullOrBlank()) return generateRandomWorkerName()

        var s = rawInput.trim().lowercase(Locale.ROOT)
        CYRILLIC_MAP.forEach { (cyr, lat) ->
            s = s.replace(cyr.toString(), lat)
        }

        var clean = s.replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')

        if (clean.length > 60) {
            clean = clean.substring(0, 60).trimEnd('-')
        }

        return if (clean.isBlank()) generateRandomWorkerName() else clean
    }
}
