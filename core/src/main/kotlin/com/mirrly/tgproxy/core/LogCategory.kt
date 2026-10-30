package com.mirrly.tgproxy.core

/**
 * Standard log categories grouping high-frequency log sources.
 * Used to disable specific log streams and drastically reduce CPU and memory load.
 */
enum class LogCategory(
    val key: String,
    val defaultTitle: String,
    val defaultDescription: String,
    val tags: Set<String>
) {
    PROXY_ENGINE(
        key = "proxy_engine",
        defaultTitle = "Сетевой стек и сокеты",
        defaultDescription = "Трафик прокси, рукопожатия WSS и сокеты",
        tags = setOf(
            "mirrlyengine",
            "LocalProxyServer",
            "TgWsProxy",
            "TgProxy",
            "NativeProxy",
            "TlsFragmentingSocketFactory"
        )
    ),
    WARP_SCANNER(
        key = "warp_scanner",
        defaultTitle = "Cloudflare WARP и сканер",
        defaultDescription = "Фоновые зонды портов, регистрация и фрагментация",
        tags = setOf(
            "WarpEndpointScanner",
            "WarpAccountManager",
            "WarpPipelineProfiler",
            "WarpPacketFragmenter",
            "WarpObfuscatedHttpClient",
            "WarpKeystoreEncryption",
            "WarpProfileCodec",
            "WarpProfileParser"
        )
    ),
    HEALTH_PROBES(
        key = "health_probes",
        defaultTitle = "Диагностика и пинги узлов",
        defaultDescription = "Регулярные фоновые проверки доступности и задержки",
        tags = setOf(
            "ConnectionHealthEngine",
            "PingEngine",
            "NodeHealthProber",
            "WorkerRelayHealthProbe",
            "DohResolver",
            "DohBenchmarkEngine",
            "DpiAnomalyDetector",
            "AdaptiveHeartbeatEngine",
            "BatteryThermalQoSEngine"
        )
    ),
    SERVICES_SYSTEM(
        key = "services_system",
        defaultTitle = "Системные службы и VPN",
        defaultDescription = "Жизненный цикл фоновых служб и VPN",
        tags = setOf(
            "ProxyForegroundService",
            "MirrlyVpnService",
            "BootReceiver",
            "ScheduleManager",
            "System"
        )
    ),
    SECURITY_UPDATES(
        key = "security_updates",
        defaultTitle = "Безопасность и обновления",
        defaultDescription = "Проверка обновлений и валидация подписи",
        tags = setOf(
            "UpdateChecker",
            "MirrlySecNative",
            "SignatureVerifier",
            "SniBypassManager"
        )
    ),
    AI_DIAGNOSTICS(
        key = "ai_diagnostics",
        defaultTitle = "ИИ-диагностика",
        defaultDescription = "Журнал вызовов инструментов нейросети",
        tags = setOf(
            "AiDiagnosticTools",
            "GeminiApiClient",
            "AiHistoryManager",
            "AiKeyManager"
        )
    );

    companion object {
        fun findCategoryForTag(tag: String): LogCategory? {
            val normalizedTag = tag.trim()
            return entries.firstOrNull { category ->
                category.tags.any { it.equals(normalizedTag, ignoreCase = true) }
            }
        }
    }
}
