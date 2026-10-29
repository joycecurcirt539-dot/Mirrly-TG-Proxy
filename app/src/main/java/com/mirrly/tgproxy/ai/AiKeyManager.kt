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
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.WarpKeystoreEncryption

/**
 * Менеджер защищенного хранения API-ключа Google Gemini.
 * Использует EncryptedSharedPreferences (AES256_SIV для ключей, AES256_GCM для значений)
 * с аппаратным мастер-ключом Android KeyStore.
 * При аппаратных сбоях KeyStore на кастомных прошивках (MIUI/HyperOS)
 * автоматически применяется отказоустойчивый контур на базе WarpKeystoreEncryption.
 */
object AiKeyManager {

    private const val TAG = "AiKeyManager"
    private const val PREFS_FILE = "mirrly_gemini_encrypted_prefs"
    private const val KEY_GEMINI_API = "gemini_api_key_v1"
    private const val FALLBACK_PREFS_FILE = "mirrly_gemini_fallback_prefs"

    @Volatile
    private var cachedEncryptedPrefs: SharedPreferences? = null

    @Synchronized
    private fun getEncryptedPrefs(context: Context): SharedPreferences? {
        if (cachedEncryptedPrefs != null) return cachedEncryptedPrefs
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            cachedEncryptedPrefs = prefs
            prefs
        } catch (e: Exception) {
            AppLogger.w(TAG, "EncryptedSharedPreferences недоступен, включен резервный контур шифрования: ${e.message}")
            null
        }
    }

    private fun getFallbackPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)
    }

    /**
     * Сохраняет API-ключ в зашифрованное хранилище.
     */
    fun saveApiKey(context: Context, apiKey: String) {
        val cleanKey = apiKey.trim()
        val encPrefs = getEncryptedPrefs(context)
        if (encPrefs != null) {
            try {
                encPrefs.edit().putString(KEY_GEMINI_API, cleanKey).apply()
                // Очищаем старый резервный файл при успехе
                getFallbackPrefs(context).edit().remove(KEY_GEMINI_API).apply()
                return
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка записи в EncryptedSharedPreferences, сохранение в fallback", e)
            }
        }

        // Резервный контур через WarpKeystoreEncryption
        try {
            val fallback = getFallbackPrefs(context)
            if (cleanKey.isEmpty()) {
                fallback.edit().remove(KEY_GEMINI_API).apply()
            } else {
                val cipherText = WarpKeystoreEncryption.encrypt(cleanKey)
                fallback.edit().putString(KEY_GEMINI_API, cipherText).apply()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Критическая ошибка сохранения API-ключа в fallback", e)
        }
    }

    /**
     * Получает сохраненный API-ключ.
     */
    fun getApiKey(context: Context): String {
        val encPrefs = getEncryptedPrefs(context)
        if (encPrefs != null) {
            try {
                val key = encPrefs.getString(KEY_GEMINI_API, null)
                if (!key.isNullOrBlank()) {
                    return key.trim()
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Ошибка чтения из EncryptedSharedPreferences: ${e.message}")
            }
        }

        // Проверяем резервный контур
        try {
            val fallback = getFallbackPrefs(context)
            val cipherText = fallback.getString(KEY_GEMINI_API, null)
            if (!cipherText.isNullOrBlank()) {
                val decrypted = WarpKeystoreEncryption.decrypt(cipherText).trim()
                // Если основной контур заработал, переносим ключ туда
                if (encPrefs != null && decrypted.isNotEmpty()) {
                    runCatching { encPrefs.edit().putString(KEY_GEMINI_API, decrypted).apply() }
                }
                return decrypted
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Ошибка расшифровки API-ключа из fallback", e)
        }

        return ""
    }

    /**
     * Проверяет наличие сохраненного API-ключа.
     */
    fun hasApiKey(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }

    /**
     * Удаляет API-ключ из всех хранилищ.
     */
    fun clearApiKey(context: Context) {
        runCatching {
            getEncryptedPrefs(context)?.edit()?.remove(KEY_GEMINI_API)?.apply()
        }
        runCatching {
            getFallbackPrefs(context).edit().remove(KEY_GEMINI_API).apply()
        }
    }
}
