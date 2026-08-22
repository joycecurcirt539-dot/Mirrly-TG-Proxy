package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HumanLogTranslatorTest {

    @Test
    fun testNativeProxyStartedWithIpAndPort() {
        val raw = "Нативный прокси успешно запущен на 127.0.0.1:1080 (Cloudflare: false)"
        val translated = HumanLogTranslator.translateToHumanRussian("LocalProxyServer", raw)
        assertEquals("Прокси-сервер запущен на 127.0.0.1:1080", translated)
    }

    @Test
    fun testSocks5EngineStartedWithPort() {
        val raw = "Нативный SOCKS5 движок успешно запущен на порту 10808"
        val translated = HumanLogTranslator.translateToHumanRussian("LocalProxyServer", raw)
        assertEquals("Прокси-сервер запущен на порту 10808", translated)
    }

    @Test
    fun testProxyStartedWithPortOnly() {
        val raw = "Proxy started on port 8080"
        val translated = HumanLogTranslator.translateToHumanRussian("LocalProxyServer", raw)
        assertEquals("Прокси-сервер запущен на порту 8080", translated)
    }

    @Test
    fun testSocketPoolUpdate() {
        val raw = "Изменение пула сокетов → 8"
        val translated = HumanLogTranslator.translateToHumanRussian("LocalProxyServer", raw)
        assertEquals("Пул сокетов обновлен (8 параллельных потоков)", translated)
    }

    @Test
    fun testCloudflareErrorHandling() {
        val raw = "Не удалось установить кэш-директорию Cloudflare: Permission denied"
        val translated = HumanLogTranslator.translateToHumanRussian("LocalProxyServer", raw)
        assertEquals("Ошибка настройки Cloudflare: Permission denied", translated)
    }

    @Test
    fun testProxyStoppingErrorHandling() {
        val raw = "Ошибка остановки нативного движка: NullPointerException"
        val translated = HumanLogTranslator.translateToHumanRussian("LocalProxyServer", raw)
        assertEquals("Ошибка при остановке движка прокси: NullPointerException", translated)
    }

    @Test
    fun testRussianMessagePreservation() {
        val raw = "📱 Системный сигнал загрузки устройства: android.intent.action.BOOT_COMPLETED"
        val translated = HumanLogTranslator.translateToHumanRussian("BootReceiver", raw)
        assertEquals("Системный сигнал автозапуска (BOOT_COMPLETED)", translated)
    }

    @Test
    fun testWakeLockFailures() {
        val rawAcquire = "Failed to acquire WakeLock"
        assertEquals("Не удалось активировать режим WakeLock (ограничения ОС)", HumanLogTranslator.translateToHumanRussian("ProxyForegroundService", rawAcquire))

        val rawRefresh = "Failed to refresh WakeLock"
        assertEquals("Не удалось обновить режим WakeLock", HumanLogTranslator.translateToHumanRussian("ProxyForegroundService", rawRefresh))
    }

    @Test
    fun testSha256MismatchError() {
        val raw = "Calculated SHA-256 abc123def does not match any expected hash in release notes!"
        assertEquals("Ошибка безопасности: Хэш SHA-256 файла не совпадает с описанием релиза!", HumanLogTranslator.translateToHumanRussian("UpdateDownloader", raw))
    }

    @Test
    fun testUpdateCheckerCompletedLog() {
        val raw = "Check completed. Latest: v1.0.6, Current: v1.0.5, Update available: true, SHA-256 count: 2"
        assertEquals("Доступно обновление до версии v1.0.6", HumanLogTranslator.translateToHumanRussian("UpdateChecker", raw))
    }

    @Test
    fun testNativeJniLogs() {
        val raw = "JNI_OnLoad: Successfully registered mirrly_sec native methods"
        assertEquals("Модуль защиты mirrly_sec успешно зарегистрирован", HumanLogTranslator.translateToHumanRussian("MirrlySecNative", raw))
    }
}
