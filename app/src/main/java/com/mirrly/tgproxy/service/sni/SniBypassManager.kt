package com.mirrly.tgproxy.service.sni

import android.content.Context
import android.os.Build
import com.mirrly.tgproxy.core.AppLogger
import com.`21Modz`.SNI

/**
 * Менеджер обхода блокировок DPI/ТСПУ через SNI-спуфинг (libsni.so).
 * Обеспечивает безопасную инициализацию хука OpenSSL/BoringSSL в Android
 * для прозрачной подмены SNI в пакетах ClientHello на cdnjs.cloudflare.com.
 */
object SniBypassManager {
    private const val TAG = "SniBypassManager"
    const val DEFAULT_BYPASS_SNI = "cdnjs.cloudflare.com"

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isHookActive = false

    fun isArm64Architecture(): Boolean {
        return Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) }
    }

    fun isAvailable(): Boolean {
        return try {
            isArm64Architecture() && SNI.ok && try { SNI.ready() } catch (_: Throwable) { false }
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun init(context: Context): Boolean {
        if (isInitialized && isHookActive) return true
        if (!isArm64Architecture()) {
            AppLogger.d(TAG, "SNI-спуфинг libsni недоступен для архитектуры ${Build.SUPPORTED_ABIS.firstOrNull()} (требуется arm64-v8a)")
            return false
        }

        return try {
            if (!SNI.ok) {
                AppLogger.w(TAG, "libsni.so не была загружена динамическим линкером")
                return false
            }
            isInitialized = true
            val applied = setSni(DEFAULT_BYPASS_SNI)
            isHookActive = applied
            val isReady = try { SNI.ready() } catch (_: Throwable) { false }
            AppLogger.i(TAG, "SNI-спуфинг успешно инициализирован: целевой домен $DEFAULT_BYPASS_SNI (ready=$isReady)")
            true
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Ошибка инициализации libsni SNI-спуфинга: ${t.message}")
            false
        }
    }

    fun setSni(sniDomain: String): Boolean {
        if (!SNI.ok) return false
        return try {
            val result = SNI.set(sniDomain)
            AppLogger.d(TAG, "Установка целевого SNI '$sniDomain': $result")
            result
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Ошибка установки SNI '$sniDomain': ${t.message}")
            false
        }
    }

    fun getCurrentSni(context: Context): String {
        if (!SNI.ok) return ""
        return try {
            SNI.current(context.applicationContext) ?: ""
        } catch (t: Throwable) {
            ""
        }
    }
}
