package com.mirrly.tgproxy.core

object HumanLogTranslator {

    private val ignoredTags = setOf(
        "dalvikvm", "hwui", "InputTransport", "RenderThread", "chatty",
        "SurfaceView", "ViewRootImpl", "InputMethodManager", "Choreographer",
        "GraphicsEnvironment", "mali_utgard", "Adreno", "Vulkan", "LB",
        "OpenGLRenderer", "FramePredict", "MQSEventManagerDelegate", "MQSService",
        "libEGL", "QTI", "MIUI", "HardwareRenderer", "ActivityThread"
    )

    private val ignoredMessageSubstrings = listOf(
        "fail to open node",
        "Unable to match the desired swap behavior",
        "registerContentObserver fail",
        "Invalid resource ID",
        "failed to get MQSService",
        "skipped frames"
    )

    fun shouldIgnoreLogcatLine(tag: String, message: String): Boolean {
        if (ignoredTags.any { tag.contains(it, ignoreCase = true) }) return true
        if (ignoredMessageSubstrings.any { message.contains(it, ignoreCase = true) }) return true
        return false
    }

    fun translateToHumanRussian(tag: String, rawMessage: String): String {
        val msg = rawMessage.trim()

        val rawTranslated = when {
            // Boot & Service Lifecycle
            msg.contains("Received boot action", ignoreCase = true) || msg.contains("Системный сигнал", ignoreCase = true) -> {
                val action = msg.substringAfter(":", "").trim()
                if (action.isNotEmpty()) "Системное событие автозапуска ($action)" else "Системное событие автозапуска устройства"
            }
            msg.contains("Autostart on boot is enabled", ignoreCase = true) || msg.contains("Автозапуск при включении активен", ignoreCase = true) -> {
                "Автозапуск активен, служба прокси запускается..."
            }
            msg.contains("Autostart on boot is disabled", ignoreCase = true) || msg.contains("Автозапуск при включении выключен", ignoreCase = true) -> {
                "Автозапуск при включении отключен в настройках"
            }
            msg.contains("Failed to start foreground service", ignoreCase = true) -> {
                "Не удалось запустить службу (ограничения фоновой работы ОС)"
            }
            msg.contains("WakeLock acquired", ignoreCase = true) -> {
                "Активирован режим защиты от усыпления системой"
            }
            msg.contains("WakeLock refreshed", ignoreCase = true) -> {
                "Продлен режим стабильной фоновой работы"
            }

            // Proxy Server Lifecycle
            msg.contains("Proxy started", ignoreCase = true) || msg.contains("started on port", ignoreCase = true) || msg.contains("успешно запущен", ignoreCase = true) -> {
                val port = Regex("""\b\d{2,5}\b""").find(msg)?.value ?: ""
                if (port.isNotEmpty()) "Прокси-сервер запущен на порту $port" else "Прокси-сервер успешно запущен"
            }
            msg.contains("Proxy stopped", ignoreCase = true) || msg.contains("stopping native engine", ignoreCase = true) || msg.contains("остановлен", ignoreCase = true) -> {
                "Прокси-сервер остановлен"
            }
            msg.contains("restart", ignoreCase = true) && msg.contains("proxy", ignoreCase = true) -> {
                "Горячая перезагрузка конфигурации прокси"
            }
            msg.contains("Настройка нативного", ignoreCase = true) || msg.contains("Native proxy", ignoreCase = true) -> {
                "Настройка нативного движка MTProto..."
            }

            // Telegram Clients & Socket Pool
            msg.contains("Client connected", ignoreCase = true) || msg.contains("Accepted connection", ignoreCase = true) -> {
                "Telegram клиент подключился к прокси"
            }
            msg.contains("Client disconnected", ignoreCase = true) || msg.contains("closed by peer", ignoreCase = true) -> {
                "Соединение с Telegram закрыто"
            }
            msg.contains("Socket pool initialized", ignoreCase = true) || msg.contains("pool size", ignoreCase = true) || msg.contains("Пул сокетов", ignoreCase = true) -> {
                val count = Regex("""\b\d+\b""").find(msg)?.value ?: ""
                if (count.isNotEmpty()) "Пул сокетов обновлен ($count параллельных потоков)" else "Пул сокетов готов к работе"
            }

            // Cloudflare Tunnel
            msg.contains("Cloudflare", ignoreCase = true) -> {
                if (msg.contains("fail", ignoreCase = true) || msg.contains("ошибка", ignoreCase = true)) {
                    "Ошибка настройки кэша Cloudflare"
                } else {
                    "Защищенный туннель Cloudflare активирован"
                }
            }

            // Network Errors & System Warnings
            msg.contains("Address already in use", ignoreCase = true) || msg.contains("EADDRINUSE", ignoreCase = true) -> {
                "Порт уже занят другим приложением! Смените порт в настройках."
            }
            msg.contains("UnknownHostException", ignoreCase = true) || msg.contains("Network is unreachable", ignoreCase = true) -> {
                "Ошибка сети: Отсутствует подключение к интернету"
            }
            msg.contains("Connection timed out", ignoreCase = true) || msg.contains("ETIMEDOUT", ignoreCase = true) -> {
                "Превышено время ожидания ответа сервера"
            }
            msg.contains("Permission denied", ignoreCase = true) -> {
                "Доступ запрещен операционной системой"
            }

            // Settings & Configuration
            msg.contains("Config saved", ignoreCase = true) || msg.contains("saved successfully", ignoreCase = true) -> {
                "Настройки сохранены и применены"
            }
            msg.contains("Auto-reconnect", ignoreCase = true) || msg.contains("Сеть изменилась", ignoreCase = true) -> {
                "Изменение сети: выполняется авто-переподключение"
            }

            else -> {
                var translated = msg
                    .replace("Starting", "Запуск", ignoreCase = true)
                    .replace("Stopping", "Остановка", ignoreCase = true)
                    .replace("Success", "Успешно", ignoreCase = true)
                    .replace("Failed to", "Не удалось", ignoreCase = true)
                    .replace("Error", "Ошибка", ignoreCase = true)
                    .replace("Received", "Получено", ignoreCase = true)
                    .replace("Connection", "Соединение", ignoreCase = true)

                if (translated.length > 130) translated.take(130) + "..." else translated
            }
        }

        // Clean out any lingering emojis
        return rawTranslated.replace(Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF\u2600-\u27BF]"""), "").trim()
    }
}
