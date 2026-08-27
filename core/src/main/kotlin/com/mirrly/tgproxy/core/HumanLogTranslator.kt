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
        if (msg.isEmpty()) return ""

        val rawTranslated = when {
            // Boot & Service Lifecycle
            msg.contains("Received boot action", ignoreCase = true) || msg.contains("Системный сигнал", ignoreCase = true) -> {
                val action = msg.substringAfter(":", "").trim()
                val cleanAction = if (action.contains(".")) action.substringAfterLast(".") else action
                if (cleanAction.isNotEmpty()) "Системный сигнал автозапуска ($cleanAction)" else "Системный сигнал автозапуска устройства"
            }
            msg.contains("Autostart on boot is enabled", ignoreCase = true) || msg.contains("Автозапуск при включении активен", ignoreCase = true) -> {
                "Автозапуск активен, запуск службы..."
            }
            msg.contains("Autostart on boot is disabled", ignoreCase = true) || msg.contains("Автозапуск при включении выключен", ignoreCase = true) -> {
                "Автозапуск при включении отключен в настройках"
            }
            msg.contains("Failed to start foreground service", ignoreCase = true) || msg.contains("Не удалось запустить службу при загрузке", ignoreCase = true) -> {
                val cause = msg.substringAfter(":", "").trim()
                if (cause.isNotEmpty()) "Ошибка запуска службы при загрузке: $cause" else "Ошибка запуска фоновой службы (ограничения ОС)"
            }
            msg.contains("Failed to acquire WakeLock", ignoreCase = true) -> {
                "Не удалось активировать режим WakeLock (ограничения ОС)"
            }
            msg.contains("WakeLock acquired", ignoreCase = true) -> {
                "Активирован режим защиты от усыпления (WakeLock)"
            }
            msg.contains("Failed to refresh WakeLock", ignoreCase = true) -> {
                "Не удалось обновить режим WakeLock"
            }
            msg.contains("WakeLock refreshed", ignoreCase = true) -> {
                "Продлен режим стабильной фоновой работы"
            }
            msg.contains("Failed to release WakeLock", ignoreCase = true) -> {
                "Ошибка освобождения ресурсов WakeLock"
            }
            msg.contains("onTaskRemoved", ignoreCase = true) -> {
                "Приложение закрыто в недавних, служба продолжает работу"
            }

            // Proxy Server Lifecycle Errors & Warnings (Check before generic started/stopped)
            msg.contains("Ошибка остановки", ignoreCase = true) || msg.contains("Failed to stop proxy", ignoreCase = true) -> {
                val cause = msg.substringAfter(":", "").trim()
                if (cause.isNotEmpty()) "Ошибка при остановке движка прокси: $cause" else "Ошибка при остановке движка прокси"
            }
            msg.contains("Не удалось запустить нативный SOCKS5 движок", ignoreCase = true) || msg.contains("Не удалось запустить SOCKS5-движок", ignoreCase = true) -> {
                val code = Regex("""\b-?\d+\b""").find(msg)?.value ?: ""
                if (code.isNotEmpty()) "Ошибка запуска SOCKS5 движка (код: $code)" else "Ошибка запуска SOCKS5 движка"
            }
            msg.contains("Не удалось запустить нативный MTProto движок", ignoreCase = true) || msg.contains("Не удалось запустить движок", ignoreCase = true) -> {
                val code = Regex("""\b-?\d+\b""").find(msg)?.value ?: ""
                if (code.isNotEmpty()) "Ошибка запуска MTProto движка (код: $code)" else "Ошибка запуска MTProto движка"
            }
            msg.contains("setPoolSize() не удался", ignoreCase = true) || msg.contains("setPoolSize failed", ignoreCase = true) -> {
                "Ошибка изменения размера пула сокетов, выполняем перезапуск..."
            }
            msg.contains("Код ответа нативной библиотеки", ignoreCase = true) || msg.contains("Native proxy returned code", ignoreCase = true) -> {
                val code = Regex("""\b-?\d+\b""").find(msg)?.value ?: ""
                if (code.isNotEmpty()) "Код нативной библиотеки: $code" else "Нативная библиотека вернула ошибку"
            }
            msg.contains("Нативный прокси недоступен", ignoreCase = true) || msg.contains("Native proxy unavailable", ignoreCase = true) -> {
                "Нативный прокси недоступен"
            }
            msg.contains("Сбой вызова FFI", ignoreCase = true) -> {
                val method = Regex("""\[(.*?)\]""").find(msg)?.groupValues?.get(1) ?: ""
                val cause = msg.substringAfter(":", "").trim()
                if (method.isNotEmpty() && cause.isNotEmpty()) {
                    "Сбой вызова нативного метода $method: $cause"
                } else if (method.isNotEmpty()) {
                    "Сбой вызова нативного метода $method"
                } else {
                    "Сбой вызова нативного метода библиотеки"
                }
            }

            // Proxy Server Started
            msg.contains("Proxy started", ignoreCase = true) ||
            msg.contains("started on port", ignoreCase = true) ||
            msg.contains("успешно запущен", ignoreCase = true) ||
            (msg.contains("запущен на", ignoreCase = true) && msg.contains("LocalProxyServer", ignoreCase = true)) -> {
                // Try matching IP:Port (e.g. 127.0.0.1:1080 or 0.0.0.0:1080)
                val hostPortMatch = Regex("""\b((?:\d{1,3}\.){3}\d{1,3}):(\d{2,5})\b""").find(msg)
                if (hostPortMatch != null) {
                    val host = hostPortMatch.groupValues[1]
                    val port = hostPortMatch.groupValues[2]
                    "Прокси-сервер запущен на $host:$port"
                } else {
                    val portMatch = Regex("""(?::|port\s+|порту\s+)(\d{2,5})\b""", RegexOption.IGNORE_CASE).find(msg)
                        ?.groupValues?.get(1)
                        ?: Regex("""\b(\d{4,5})\b""").find(msg)?.value

                    if (!portMatch.isNullOrEmpty()) {
                        "Прокси-сервер запущен на порту $portMatch"
                    } else {
                        "Прокси-сервер успешно запущен"
                    }
                }
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

            // Telegram Clients & Native Engine Events
            msg.contains("Incoming MTProto client connected from", ignoreCase = true) -> {
                val ip = msg.substringAfter("from", "").trim()
                if (ip.isNotEmpty()) "MTProto: Подключение Telegram-клиента ($ip)" else "MTProto: Подключение Telegram-клиента"
            }
            msg.contains("Handshake parsed:", ignoreCase = true) -> {
                val dcMatch = Regex("""dc_id=(\d+)""").find(msg)?.groupValues?.get(1) ?: ""
                val targetMatch = Regex("""target_dc=([0-9\.:]+)""").find(msg)?.groupValues?.get(1) ?: ""
                if (dcMatch.isNotEmpty() && targetMatch.isNotEmpty()) {
                    "MTProto: Рукопожатие клиента (Telegram DC $dcMatch, $targetMatch)"
                } else {
                    "MTProto: Рукопожатие Telegram-клиента"
                }
            }
            msg.contains("Connected 1-hop Native WS to", ignoreCase = true) -> {
                val dom = msg.substringAfter("to", "").substringBefore("!").trim()
                "WSS: Успешный туннель через Cloudflare ($dom), передача MTProto данных"
            }
            msg.contains("Pre-warmed socket in pool for", ignoreCase = true) -> {
                val dc = Regex("""DC(\d+)""").find(msg)?.groupValues?.get(1) ?: ""
                val dom = msg.substringAfter("via", "").trim()
                "Пул сокетов: Прогрет WSS-сокет для DC $dc через $dom"
            }
            msg.contains("Successfully fetched", ignoreCase = true) && msg.contains("upstream cfproxy domains", ignoreCase = true) -> {
                val count = Regex("""fetched\s+(\d+)""").find(msg)?.groupValues?.get(1) ?: ""
                "Загружен актуальный список $count CDN-доменов Cloudflare Fronting"
            }
            msg.contains("WS connect to", ignoreCase = true) && msg.contains("failed", ignoreCase = true) -> {
                val dom = Regex("""connect to\s+([0-9a-zA-Z\._-]+)""").find(msg)?.groupValues?.get(1) ?: ""
                "Cloudflare Fronting: Сбой подключения к узлу $dom"
            }
            msg.contains("MTProto client connection closed", ignoreCase = true) -> {
                "MTProto: Соединение с Telegram-клиентом завершено"
            }
            msg.contains("StartProxy requested:", ignoreCase = true) -> {
                "Запуск нативного движка прокси mirrlyengine"
            }
            msg.contains("SetCfProxyConfig:", ignoreCase = true) -> {
                "Обновлена конфигурация Cloudflare туннелирования"
            }
            msg.contains("Client connected", ignoreCase = true) || msg.contains("Accepted connection", ignoreCase = true) -> {
                "Telegram-клиент подключился к прокси"
            }
            msg.contains("Client disconnected", ignoreCase = true) || msg.contains("closed by peer", ignoreCase = true) -> {
                "Соединение с Telegram-клиентом закрыто"
            }
            msg.contains("Socket pool", ignoreCase = true) || msg.contains("pool size", ignoreCase = true) || msg.contains("пул", ignoreCase = true) -> {
                val count = Regex("""(?:→|:|\bsize\b|\bпула\b|\bсокетов\b)?\s*(\d+)\b""", RegexOption.IGNORE_CASE).find(msg)?.groupValues?.get(1)
                    ?: Regex("""\b\d+\b""").find(msg)?.value
                if (!count.isNullOrEmpty()) "Пул сокетов обновлен ($count параллельных потоков)" else "Пул сокетов готов к работе"
            }

            // SOCKS5 & MTProto Cloudflare Worker & CDN Tunneling
            msg.contains("MTProto подключен через Worker", ignoreCase = true) -> {
                val dom = msg.substringAfter("Worker", "").trim()
                if (dom.isNotEmpty()) "MTProto: Успешный WSS-туннель через Cloudflare Worker ($dom)" else "MTProto: Успешный WSS-туннель через Cloudflare Worker"
            }
            msg.contains("подключен через Worker:", ignoreCase = true) -> {
                val dom = msg.substringAfter("Worker:", "").trim()
                if (dom.isNotEmpty()) "MTProto: Успешный WSS-туннель через Cloudflare Worker ($dom)" else "MTProto: Успешный WSS-туннель через Cloudflare Worker"
            }
            msg.contains("подключен через CDN:", ignoreCase = true) -> {
                val dom = msg.substringAfter("CDN:", "").trim()
                if (dom.isNotEmpty()) "MTProto: WSS-туннель через Cloudflare CDN ($dom)" else "MTProto: WSS-туннель через Cloudflare CDN"
            }
            msg.contains("MTProto endpoint выбран:", ignoreCase = true) -> {
                val dom = msg.substringAfter("выбран:", "").trim()
                if (dom.isNotEmpty()) "MTProto: Выбран активный Cloudflare узел ($dom)" else "MTProto: Выбран активный Cloudflare узел"
            }
            msg.contains("Кастомный домен Cloudflare Worker не назначен", ignoreCase = true) -> {
                "SOCKS5: Кастомный домен Worker не задан! Пропуск WSS и переход на прямое TCP-подключение (может блокироваться в РФ)"
            }
            msg.contains("Успешное туннелирование", ignoreCase = true) -> {
                val target = Regex("""к\s+([0-9a-zA-Z\.:_-]+)""").find(msg)?.groupValues?.get(1) ?: ""
                if (target.isNotEmpty()) "SOCKS5: Успешный WSS-туннель к $target через Cloudflare Worker" else "SOCKS5: Успешный WSS-туннель через Cloudflare Worker"
            }
            msg.contains("Не удалось установить сокет с Worker", ignoreCase = true) -> {
                "SOCKS5: Не удалось соединиться с Cloudflare Worker (таймаут или сокет закрыт)"
            }
            msg.contains("Ошибка подключения к Worker", ignoreCase = true) -> {
                val err = msg.substringAfter("Worker", "").trim()
                if (err.isNotEmpty()) "SOCKS5: Ошибка соединения с Worker: $err" else "SOCKS5: Ошибка подключения к Cloudflare Worker"
            }
            msg.contains("SOCKS5 Direct TCP", ignoreCase = true) && msg.contains("Не удалось", ignoreCase = true) -> {
                "SOCKS5: Ошибка прямого TCP-подключения (заблокировано провайдером/РКН)"
            }
            msg.contains("SOCKS5 Direct TCP", ignoreCase = true) -> {
                val target = Regex("""к\s+([0-9a-zA-Z\.:_-]+)""").find(msg)?.groupValues?.get(1) ?: ""
                if (target.isNotEmpty()) "SOCKS5: Прямое TCP-подключение к $target (без защиты WSS)" else "SOCKS5: Прямое TCP-подключение (без защиты WSS)"
            }
            msg.contains("[SOCKS5 CONNECT]", ignoreCase = true) -> {
                val target = msg.substringAfter("Назначение:").trim()
                if (target.isNotEmpty()) "SOCKS5: Запрос соединения с $target" else "SOCKS5: Запрос нового TCP-подключения"
            }

            // Cloudflare Tunnel
            msg.contains("Cloudflare", ignoreCase = true) -> {
                val isError = msg.contains("fail", ignoreCase = true) ||
                        msg.contains("ошибка", ignoreCase = true) ||
                        msg.contains("не удалось", ignoreCase = true) ||
                        msg.contains("error", ignoreCase = true)
                if (isError) {
                    val cause = msg.substringAfter(":", "").trim()
                    if (cause.isNotEmpty()) "Ошибка настройки Cloudflare: $cause" else "Ошибка настройки Cloudflare"
                } else {
                    "Защищенный туннель Cloudflare активирован"
                }
            }

            // Updates & Downloads (Check errors first!)
            msg.contains("does not match", ignoreCase = true) || msg.contains("hash mismatch", ignoreCase = true) -> {
                "Ошибка безопасности: Хэш SHA-256 файла не совпадает с описанием релиза!"
            }
            msg.contains("Download finished", ignoreCase = true) -> {
                val bytesMatch = Regex("""(\d+)\s*bytes""", RegexOption.IGNORE_CASE).find(msg)?.groupValues?.get(1)
                if (bytesMatch != null) "Загрузка обновления завершена ($bytesMatch байт)" else "Загрузка обновления завершена"
            }
            msg.contains("Calculated SHA-256", ignoreCase = true) -> {
                val hash = msg.substringAfter("Calculated SHA-256:").trim()
                if (hash.isNotEmpty() && hash.length > 12) "Вычислен SHA-256 файла: ${hash.take(12)}..." else "Вычислен контрольный хэш SHA-256 файла"
            }
            msg.contains("SHA-256 hash verified successfully", ignoreCase = true) -> {
                "Проверка подписи SHA-256 успешно пройдена"
            }
            msg.contains("No expected SHA-256 provided", ignoreCase = true) -> {
                "В релизе отсутствует SHA-256, проверка пропущена"
            }
            msg.contains("Error downloading update", ignoreCase = true) -> {
                val cause = msg.substringAfter(":", "").trim()
                if (cause.isNotEmpty()) "Ошибка скачивания обновления: $cause" else "Ошибка скачивания обновления"
            }
            msg.contains("Failed to launch install permission", ignoreCase = true) -> {
                "Не удалось открыть настройки разрешения установки приложений"
            }
            msg.contains("Failed to launch installer", ignoreCase = true) -> {
                "Ошибка запуска установки обновления"
            }
            msg.contains("Release info not modified", ignoreCase = true) || msg.contains("Already on latest state", ignoreCase = true) -> {
                "Проверка обновлений: у вас установлена последняя версия"
            }
            msg.contains("GitHub API rate limit exceeded", ignoreCase = true) -> {
                "Превышен лимит запросов к GitHub API (HTTP 403)"
            }
            msg.contains("Check completed. Latest:", ignoreCase = true) -> {
                val latest = Regex("""Latest:\s*v?([0-9\.]+)""").find(msg)?.groupValues?.get(1) ?: ""
                val isAvail = msg.contains("Update available: true", ignoreCase = true)
                if (latest.isNotEmpty()) {
                    if (isAvail) "Доступно обновление до версии v$latest" else "Установлена последняя версия v$latest"
                } else {
                    "Проверка обновлений завершена"
                }
            }
            msg.contains("GitHub API returned HTTP status", ignoreCase = true) -> {
                val status = Regex("""HTTP status\s*(\d+)""", RegexOption.IGNORE_CASE).find(msg)?.groupValues?.get(1) ?: ""
                if (status.isNotEmpty()) "GitHub API вернул код HTTP $status" else "Предупреждение GitHub API"
            }
            msg.contains("Network error during update check", ignoreCase = true) -> {
                val cause = msg.substringAfter(":", "").trim()
                if (cause.isNotEmpty()) "Ошибка сети при проверке обновлений: $cause" else "Ошибка сети при проверке обновлений"
            }

            // Security & Signature Verification
            msg.contains("Native security library mirrly_sec loaded successfully", ignoreCase = true) -> {
                "Модуль безопасности mirrly_sec успешно загружен"
            }
            msg.contains("Failed to load native security library", ignoreCase = true) -> {
                "Не удалось загрузить модуль безопасности mirrly_sec"
            }
            msg.contains("Native verify call failed", ignoreCase = true) -> {
                "Ошибка C-модуля безопасности, переход на Kotlin-проверку"
            }
            msg.contains("Current APK Signature SHA-256", ignoreCase = true) -> {
                val sig = msg.substringAfter("SHA-256:").trim()
                if (sig.isNotEmpty() && sig.length > 14) "Отпечаток подписи APK: ${sig.take(14)}..." else "Получен отпечаток подписи APK"
            }
            msg.contains("Failed to retrieve package signatures", ignoreCase = true) -> {
                "Не удалось получить отпечаток подписи приложения"
            }
            msg.contains("Error verifying APK signature", ignoreCase = true) -> {
                "Ошибка проверки подписи APK"
            }
            msg.contains("JNI_OnLoad: Successfully registered", ignoreCase = true) -> {
                "Модуль защиты mirrly_sec успешно зарегистрирован"
            }
            msg.contains("JNI_OnLoad: RegisterNatives failed", ignoreCase = true) -> {
                "Ошибка регистрации методов модуля mirrly_sec"
            }
            msg.contains("JNI_OnLoad: SignatureVerifier class not found", ignoreCase = true) -> {
                "Ошибка JNI: Класс SignatureVerifier не найден"
            }
            msg.contains("Native verify:", ignoreCase = true) -> {
                "Проверка подписи в C-модуле завершена"
            }

            // Network Errors & System Warnings
            msg.contains("DPI_BLOCKED", ignoreCase = true) || msg.contains("Блокировка DPI", ignoreCase = true) -> {
                "Обнаружена DPI-блокировка узла (сброс пакетов ТСПУ). Мгновенный переход на резервный воркер"
            }
            msg.contains("TLS_HANDSHAKE_FAILED", ignoreCase = true) || msg.contains("Сбой TLS", ignoreCase = true) -> {
                "Сбой защищенного TLS-рукопожатия (возможен перехват или блокировка сертификата)"
            }
            msg.contains("Instant Deep Failover", ignoreCase = true) -> {
                "Мгновенное переключение на резервный узел без задержки"
            }
            msg.contains("Address already in use", ignoreCase = true) || msg.contains("EADDRINUSE", ignoreCase = true) -> {
                "Порт уже занят другим приложением! Смените порт в настройках."
            }
            msg.contains("UnknownHostException", ignoreCase = true) || msg.contains("Network is unreachable", ignoreCase = true) -> {
                "Ошибка сети: Отсутствует подключение к интернету"
            }
            msg.contains("Connection timed out", ignoreCase = true) || msg.contains("ETIMEDOUT", ignoreCase = true) -> {
                "Превышено время ожидания ответа сервера"
            }
            msg.contains("Connection refused", ignoreCase = true) || msg.contains("ECONNREFUSED", ignoreCase = true) -> {
                "Соединение отклонено сервером"
            }
            msg.contains("Connection reset", ignoreCase = true) || msg.contains("ECONNRESET", ignoreCase = true) -> {
                "Соединение сброшено узлом сети (DPI / TCP Reset)"
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
                val cleanMsg = msg.replace(Regex("""^[\uD83C-\uDBFF\uDC00-\uDFFF\u2600-\u27BF\s️ℹ️⚠️📱⚙️]+"""), "").trim()
                if (cleanMsg.length > 150) cleanMsg.take(150) + "..." else cleanMsg
            }
        }

        // Clean out any lingering leading/trailing emojis and redundant whitespace
        return rawTranslated.replace(Regex("""^[\uD83C-\uDBFF\uDC00-\uDFFF\u2600-\u27BF\s️]+"""), "").trim()
    }
}
