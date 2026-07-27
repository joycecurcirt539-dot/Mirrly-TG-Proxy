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

        return when {
            // Server status
            msg.contains("Proxy started", ignoreCase = true) || msg.contains("started on port", ignoreCase = true) -> {
                val port = Regex("""\b\d{2,5}\b""").find(msg)?.value ?: ""
                if (port.isNotEmpty()) "🚀 Прокси-сервер запущен на порту $port" else "🚀 Прокси-сервер успешно запущен"
            }
            msg.contains("Proxy stopped", ignoreCase = true) || msg.contains("stopping native engine", ignoreCase = true) -> {
                "🛑 Прокси-сервер остановлен"
            }
            msg.contains("restart", ignoreCase = true) && msg.contains("proxy", ignoreCase = true) -> {
                "🔄 Горячая перезагрузка конфигурации прокси"
            }

            // Connection events
            msg.contains("Client connected", ignoreCase = true) || msg.contains("Accepted connection", ignoreCase = true) -> {
                "💬 Telegram клиент подключился к прокси"
            }
            msg.contains("Client disconnected", ignoreCase = true) || msg.contains("closed by peer", ignoreCase = true) -> {
                "ℹ️ Соединение с Telegram закрыто"
            }
            msg.contains("Socket pool initialized", ignoreCase = true) || msg.contains("pool size", ignoreCase = true) -> {
                val count = Regex("""\b\d+\b""").find(msg)?.value ?: ""
                if (count.isNotEmpty()) "⚡ Пул сокетов инициализирован ($count потоков)" else "⚡ Пул сокетов готов к работе"
            }

            // Cloudflare
            msg.contains("Cloudflare", ignoreCase = true) && (msg.contains("tunnel", ignoreCase = true) || msg.contains("active", ignoreCase = true) || msg.contains("worker", ignoreCase = true)) -> {
                "🛡️ Защищенный туннель Cloudflare активирован"
            }

            // Network errors
            msg.contains("Address already in use", ignoreCase = true) || msg.contains("EADDRINUSE", ignoreCase = true) -> {
                "⚠️ Выбранный порт уже занят другим приложением!"
            }
            msg.contains("UnknownHostException", ignoreCase = true) || msg.contains("Network is unreachable", ignoreCase = true) -> {
                "❌ Ошибка сети: Отсутствует подключение к интернету"
            }
            msg.contains("Connection timed out", ignoreCase = true) || msg.contains("ETIMEDOUT", ignoreCase = true) -> {
                "⏱️ Превышено время ожидания ответа сервера"
            }
            msg.contains("Permission denied", ignoreCase = true) -> {
                "⛔ Ошибка доступа системы: недостаточно прав"
            }

            // Config & Settings
            msg.contains("Config saved", ignoreCase = true) || msg.contains("saved successfully", ignoreCase = true) -> {
                "⚙️ Настройки сохранены и применены"
            }
            msg.contains("Auto-reconnect", ignoreCase = true) -> {
                "🔄 Сеть изменилась, выполняется авто-переподключение"
            }

            // Fallback for short human messages
            msg.startsWith("🚀") || msg.startsWith("🛑") || msg.startsWith("⚙️") || msg.startsWith("💬") || msg.startsWith("⚡") || msg.startsWith("🛡️") -> {
                msg
            }

            else -> {
                if (msg.length > 120) msg.take(120) + "..." else msg
            }
        }
    }
}
