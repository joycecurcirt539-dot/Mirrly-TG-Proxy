/*
 * Mirrly TG Proxy - Diagnostic Report Generator (Task 05)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.mirrly.tgproxy.BuildConfig
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.core.FailureType
import com.mirrly.tgproxy.core.WarpPipelineProfiler
import com.mirrly.tgproxy.service.WorkerFailoverManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticReportGenerator {

    /**
     * Формирует анонимизированный диагностический Markdown-отчёт,
     * полностью безопасный для публикации в публичных GitHub Issues.
     */
    fun generateReport(context: Context): String {
        val app = MirrlyApplication.instance
        val config = app.config
        val server = app.proxyServer
        val stats = server.stats
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val timestamp = dateFormat.format(Date())

        val isRussian = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.resources.configuration.locales[0].language.lowercase().startsWith("ru")
            } else {
                @Suppress("DEPRECATION")
                context.resources.configuration.locale.language.lowercase().startsWith("ru")
            }
        } catch (_: Exception) {
            false
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null

        val netType = when {
            caps == null -> if (isRussian) "Нет подключения" else "No Connection"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> if (isRussian) "Мобильная сеть (LTE/5G)" else "Cellular Network (LTE/5G)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> if (isRussian) "Другое" else "Other"
        }

        val hasIpv4 = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val hasIpv6 = checkIpv6Available()
        val yesStr = if (isRussian) "Да" else "Yes"
        val noStr = if (isRussian) "Нет" else "No"

        val sb = StringBuilder()
        if (isRussian) {
            sb.append("### Mirrly TG Proxy — Диагностический отчёт\n")
            sb.append("Дата генерации: `").append(timestamp).append("`\n\n")

            sb.append("#### 1. Окружение и устройство\n")
            sb.append("- **Версия приложения**: `v").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")`\n")
            sb.append("- **Тип сборки**: `").append(BuildConfig.BUILD_TYPE).append("`\n")
            sb.append("- **Поддерживаемые ABI**: `").append(Build.SUPPORTED_ABIS.joinToString(", ")).append("`\n")
            sb.append("- **Модель**: `").append(sanitizeText("${Build.MANUFACTURER} ${Build.MODEL}")).append("`\n")
            sb.append("- **Android OS**: `Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")`\n\n")

            sb.append("#### 2. Сетевой стек\n")
            sb.append("- **Тип соединения**: `").append(netType).append("`\n")
            sb.append("- **Стек протоколов**: `IPv4=").append(if (hasIpv4) yesStr else noStr)
                .append(", IPv6=").append(if (hasIpv6) yesStr else noStr).append("`\n")
            sb.append("- **DNS-резолвер**: `").append(if (config.enabledDohProviderIds.isNotEmpty()) "DoH (${config.enabledDohProviderIds.joinToString(", ")})" else "Системный DNS").append("`\n\n")

            sb.append("#### 3. Конфигурация прокси и туннеля\n")
            sb.append("- **Режим**: `").append(if (config.isSocks5Mode) "SOCKS5 (:10808)" else "MTProto (:1080)").append("`\n")
            sb.append("- **Uplink**: `").append(config.uplinkMode.name).append("`\n")
            sb.append("- **Статус сервиса**: `").append(if (server.isRunning) "АКТИВЕН" else "ОСТАНОВЛЕН").append("`\n")
            sb.append("- **Uptime**: `").append(server.uptimeSeconds).append(" сек`\n")
            sb.append("- **Активный узел/воркер**: `").append(sanitizeWorkerDomain(app.prefsManager.getActiveWorker().name)).append("`\n")
            sb.append("- **TCP_NODELAY**: `").append(config.tcpNoDelayModeName).append("`\n")
            sb.append("- **Пресет скорости**: `").append(config.speedPresetName).append("`\n\n")

            sb.append("#### 4. Метрики качества канала связи (SQI)\n")
            sb.append("- **Оценка канала (Health Score)**: `").append(stats.healthScore).append(" / 100`\n")
            sb.append("- **Вердикт**: `").append(stats.healthVerdict).append("`\n")
            sb.append("- **RTT (пинг)**: `").append(server.currentPingMs).append(" мс`\n")
            sb.append("- **Джиттер**: `").append(stats.jitterMs).append(" мс`\n")
            sb.append("- **Успешность доставки проб**: `").append(stats.healthSuccessRate).append("%`\n")
            sb.append("- **Последний сбой**: `").append(stats.lastFailureType.description).append("`\n\n")

            val warpMetrics = WarpPipelineProfiler.getLatestMetrics()
            if (warpMetrics != null) {
                sb.append("#### 5. Профилирование этапов запуска WARP\n")
                sb.append("- **Итог**: `").append(if (warpMetrics.isSuccess) "Успешно" else "Сбой").append("`\n")
                sb.append("- **Общее время (t_total)**: `").append(warpMetrics.tTotalMs).append(" мс`\n")
                sb.append("- **Фаза 1 (Конфиг)**: `").append(warpMetrics.tConfigMs).append(" мс`\n")
                sb.append("- **Фаза 2 (DNS Anycast)**: `").append(warpMetrics.tDnsMs).append(" мс`\n")
                sb.append("- **Фаза 3 (Handshake)**: `").append(warpMetrics.tHandshakeMs).append(" мс`\n")
                sb.append("- **Фаза 4 (Туннель)**: `").append(warpMetrics.tTunnelMs).append(" мс`\n")
                if (!warpMetrics.isSuccess) {
                    sb.append("- **Фаза сбоя**: `").append(warpMetrics.failurePhase?.name ?: "UNKNOWN").append("`\n")
                }
                sb.append("\n")
            }

            val failover = WorkerFailoverManager.failoverState.value
            val activeWorker = app.prefsManager.getActiveWorker()
            sb.append("#### 6. Статус отказоустойчивости (Failover)\n")
            sb.append("- **Текущий узел**: `").append(sanitizeWorkerDomain(activeWorker.domain)).append("`\n")
            sb.append("- **Активен резерв**: `").append(if (failover.isFailoverActive) yesStr else noStr).append("`\n")
            if (failover.lastEvent != null) {
                sb.append("- **Последнее переключение**: `").append(failover.lastEvent.fromWorkerName)
                    .append(" -> ").append(failover.lastEvent.toWorkerName).append("`\n")
                sb.append("- **Причина**: `").append(failover.lastEvent.reason.description).append("`\n")
            }
            sb.append("\n")

            sb.append("---\n")
            sb.append("*Отчёт сформирован модулем DiagnosticReportGenerator. Все личные идентификаторы и пароли очищены.*")
        } else {
            sb.append("### Mirrly TG Proxy — Diagnostic Report\n")
            sb.append("Generated at: `").append(timestamp).append("`\n\n")

            sb.append("#### 1. Environment and Device\n")
            sb.append("- **App Version**: `v").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")`\n")
            sb.append("- **Build Type**: `").append(BuildConfig.BUILD_TYPE).append("`\n")
            sb.append("- **Supported ABIs**: `").append(Build.SUPPORTED_ABIS.joinToString(", ")).append("`\n")
            sb.append("- **Model**: `").append(sanitizeText("${Build.MANUFACTURER} ${Build.MODEL}")).append("`\n")
            sb.append("- **Android OS**: `Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")`\n\n")

            sb.append("#### 2. Network Stack\n")
            sb.append("- **Connection Type**: `").append(netType).append("`\n")
            sb.append("- **Protocol Stack**: `IPv4=").append(if (hasIpv4) yesStr else noStr)
                .append(", IPv6=").append(if (hasIpv6) yesStr else noStr).append("`\n")
            sb.append("- **DNS Resolver**: `").append(if (config.enabledDohProviderIds.isNotEmpty()) "DoH (${config.enabledDohProviderIds.joinToString(", ")})" else "System DNS").append("`\n\n")

            sb.append("#### 3. Proxy and Tunnel Configuration\n")
            sb.append("- **Mode**: `").append(if (config.isSocks5Mode) "SOCKS5 (:10808)" else "MTProto (:1080)").append("`\n")
            sb.append("- **Uplink**: `").append(config.uplinkMode.name).append("`\n")
            sb.append("- **Service Status**: `").append(if (server.isRunning) "ACTIVE" else "STOPPED").append("`\n")
            sb.append("- **Uptime**: `").append(server.uptimeSeconds).append(" sec`\n")
            sb.append("- **Active Node/Worker**: `").append(sanitizeWorkerDomain(app.prefsManager.getActiveWorker().name)).append("`\n")
            sb.append("- **TCP_NODELAY**: `").append(config.tcpNoDelayModeName).append("`\n")
            sb.append("- **Speed Preset**: `").append(config.speedPresetName).append("`\n\n")

            sb.append("#### 4. Signal Quality Indicators (SQI)\n")
            sb.append("- **Channel Health Score**: `").append(stats.healthScore).append(" / 100`\n")
            sb.append("- **Verdict**: `").append(stats.healthVerdict).append("`\n")
            sb.append("- **RTT (Ping)**: `").append(server.currentPingMs).append(" ms`\n")
            sb.append("- **Jitter**: `").append(stats.jitterMs).append(" ms`\n")
            sb.append("- **Probe Success Rate**: `").append(stats.healthSuccessRate).append("%`\n")
            sb.append("- **Last Failure**: `").append(stats.lastFailureType.description).append("`\n\n")

            val warpMetrics = WarpPipelineProfiler.getLatestMetrics()
            if (warpMetrics != null) {
                sb.append("#### 5. WARP Launch Profiling\n")
                sb.append("- **Result**: `").append(if (warpMetrics.isSuccess) "Success" else "Failure").append("`\n")
                sb.append("- **Total Time (t_total)**: `").append(warpMetrics.tTotalMs).append(" ms`\n")
                sb.append("- **Phase 1 (Config)**: `").append(warpMetrics.tConfigMs).append(" ms`\n")
                sb.append("- **Phase 2 (DNS Anycast)**: `").append(warpMetrics.tDnsMs).append(" ms`\n")
                sb.append("- **Phase 3 (Handshake)**: `").append(warpMetrics.tHandshakeMs).append(" ms`\n")
                sb.append("- **Phase 4 (Tunnel)**: `").append(warpMetrics.tTunnelMs).append(" ms`\n")
                if (!warpMetrics.isSuccess) {
                    sb.append("- **Failure Phase**: `").append(warpMetrics.failurePhase?.name ?: "UNKNOWN").append("`\n")
                }
                sb.append("\n")
            }

            val failover = WorkerFailoverManager.failoverState.value
            val activeWorker = app.prefsManager.getActiveWorker()
            sb.append("#### 6. Failover Status\n")
            sb.append("- **Current Node**: `").append(sanitizeWorkerDomain(activeWorker.domain)).append("`\n")
            sb.append("- **Standby Active**: `").append(if (failover.isFailoverActive) yesStr else noStr).append("`\n")
            if (failover.lastEvent != null) {
                sb.append("- **Last Switch**: `").append(failover.lastEvent.fromWorkerName)
                    .append(" -> ").append(failover.lastEvent.toWorkerName).append("`\n")
                sb.append("- **Reason**: `").append(failover.lastEvent.reason.description).append("`\n")
            }
            sb.append("\n")

            sb.append("---\n")
            sb.append("*Report generated by DiagnosticReportGenerator. All personal identifiers and credentials have been sanitized.*")
        }

        return sb.toString()
    }

    /**
     * Проверка наличия адресов IPv6 на локальных сетевых интерфейсах.
     */
    private fun checkIpv6Available(): Boolean {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return false
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (nif.isLoopback || !nif.isUp) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet6Address && !addr.isLinkLocalAddress && !addr.isLoopbackAddress) {
                        return true
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Санитарная очистка персональных доменов воркеров.
     * Заменяет поддомен на '***.workers.dev' для сохранения приватности.
     */
    fun sanitizeWorkerDomain(domain: String): String {
        if (domain.isBlank()) return "Default Node"
        if (domain.contains(".workers.dev", ignoreCase = true)) {
            val parts = domain.split(".workers.dev")
            return "***.workers.dev${parts.getOrNull(1).orEmpty()}"
        }
        return domain
    }

    /**
     * Очистка произвольного текста от приватных ключей, токенов и паролей.
     */
    fun sanitizeText(input: String): String {
        var text = input
        // Маскирование WireGuard Base64 32-byte keys (44 chars ending with =)
        text = text.replace(Regex("[A-Za-z0-9+/]{43}="), "[REDACTED_KEY]")
        // Маскирование токенов вида tok_... или token=...
        text = text.replace(Regex("(?i)(token|password|auth|secret)[=:\\s]+[^\\s,;]+"), "$1=[REDACTED]")
        // Маскирование IPv4
        text = text.replace(Regex("\\b(?!127\\.0\\.0\\.1)(?!0\\.0\\.0\\.0)(?!162\\.159\\.)\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"), "xxx.xxx.xxx.xxx")
        return text
    }
}
