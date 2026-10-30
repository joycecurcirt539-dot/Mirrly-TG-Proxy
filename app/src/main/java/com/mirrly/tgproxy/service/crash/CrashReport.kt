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

package com.mirrly.tgproxy.service.crash

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Immutable crash report model holding diagnostic information,
 * stack traces, device specs, and recent log snapshot.
 */
data class CrashReport(
    val id: String,
    val timestampMs: Long,
    val timestampIso: String,
    val threadName: String,
    val threadId: Long,
    val exceptionClass: String,
    val exceptionMessage: String,
    val stackTrace: String,
    val causeMessage: String?,
    val appVersionName: String,
    val appVersionCode: Long,
    val buildType: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val deviceProduct: String,
    val deviceBoard: String,
    val androidRelease: String,
    val androidSdk: Int,
    val supportedAbis: String,
    val proxyMode: String,
    val isProxyRunning: Boolean,
    val isVpnRunning: Boolean,
    val workerDomain: String?,
    val recentLogs: List<String>,
    var reportFilePath: String? = null
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("id", id)
        root.put("timestampMs", timestampMs)
        root.put("timestampIso", timestampIso)
        root.put("threadName", threadName)
        root.put("threadId", threadId)
        root.put("exceptionClass", exceptionClass)
        root.put("exceptionMessage", exceptionMessage)
        root.put("stackTrace", stackTrace)
        root.put("causeMessage", causeMessage ?: JSONObject.NULL)
        root.put("appVersionName", appVersionName)
        root.put("appVersionCode", appVersionCode)
        root.put("buildType", buildType)
        root.put("deviceManufacturer", deviceManufacturer)
        root.put("deviceModel", deviceModel)
        root.put("deviceProduct", deviceProduct)
        root.put("deviceBoard", deviceBoard)
        root.put("androidRelease", androidRelease)
        root.put("androidSdk", androidSdk)
        root.put("supportedAbis", supportedAbis)
        root.put("proxyMode", proxyMode)
        root.put("isProxyRunning", isProxyRunning)
        root.put("isVpnRunning", isVpnRunning)
        root.put("workerDomain", workerDomain ?: JSONObject.NULL)
        root.put("reportFilePath", reportFilePath ?: JSONObject.NULL)

        val logsArray = JSONArray()
        for (log in recentLogs) {
            logsArray.put(log)
        }
        root.put("recentLogs", logsArray)

        return root.toString(2)
    }

    fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.append("# Mirrly TG Proxy — Crash Report\n\n")
        sb.append("## Общая информация\n")
        sb.append("- **Дата и время**: $timestampIso\n")
        sb.append("- **Исключение**: `$exceptionClass`\n")
        if (exceptionMessage.isNotBlank()) {
            sb.append("- **Сообщение**: $exceptionMessage\n")
        }
        if (!causeMessage.isNullOrBlank()) {
            sb.append("- **Причина**: $causeMessage\n")
        }
        sb.append("- **Поток**: `$threadName` (ID: $threadId)\n\n")

        sb.append("## Сведения о системе и приложении\n")
        sb.append("- **Версия приложения**: $appVersionName (код: $appVersionCode, сборка: $buildType)\n")
        sb.append("- **Устройство**: $deviceManufacturer $deviceModel ($deviceProduct, плата: $deviceBoard)\n")
        sb.append("- **Версия Android**: $androidRelease (API $androidSdk)\n")
        sb.append("- **Поддерживаемые ABI**: $supportedAbis\n")
        sb.append("- **Режим прокси**: $proxyMode\n")
        sb.append("- **Статус прокси**: ${if (isProxyRunning) "Активен" else "Остановлен"}\n")
        sb.append("- **VPN-служба**: ${if (isVpnRunning) "Активна" else "Неактивна"}\n")
        if (!workerDomain.isNullOrBlank()) {
            sb.append("- **Cloudflare Worker**: $workerDomain\n")
        }
        sb.append("\n")

        sb.append("## Трассировка стека\n")
        sb.append("```\n")
        sb.append(stackTrace.trim())
        sb.append("\n```\n\n")

        if (recentLogs.isNotEmpty()) {
            sb.append("## Последние события журнала\n")
            sb.append("```\n")
            sb.append(recentLogs.joinToString("\n").trim())
            sb.append("\n```\n")
        }

        return sb.toString()
    }

    fun buildGitHubIssueUrl(): String {
        val base = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/issues/new"
        val titleText = "[Crash] $exceptionClass: ${exceptionMessage.take(70).trim()}"
        val titleParam = urlEncode(titleText)

        val bodyBuilder = StringBuilder()
        bodyBuilder.append("### Описание сбоя\n")
        bodyBuilder.append("Произошло непредвиденное аварийное завершение приложения.\n\n")

        bodyBuilder.append("### Параметры окружения\n")
        bodyBuilder.append("- **Версия**: $appVersionName ($buildType, code $appVersionCode)\n")
        bodyBuilder.append("- **Устройство**: $deviceManufacturer $deviceModel (Android $androidRelease, API $androidSdk)\n")
        bodyBuilder.append("- **Архитектура**: $supportedAbis\n")
        bodyBuilder.append("- **Режим прокси**: $proxyMode (активен: $isProxyRunning, vpn: $isVpnRunning)\n")
        bodyBuilder.append("- **Поток**: $threadName\n")
        bodyBuilder.append("- **Время сбоя**: $timestampIso\n\n")

        bodyBuilder.append("### Исключение\n")
        bodyBuilder.append("`$exceptionClass`: $exceptionMessage\n\n")

        bodyBuilder.append("### Трассировка стека\n")
        bodyBuilder.append("```\n")
        val safeStackTrace = if (stackTrace.length > 2500) {
            stackTrace.take(2500) + "\n... [краткая выжимка для URL, полный стек в прикрепленном файле]"
        } else {
            stackTrace
        }
        bodyBuilder.append(safeStackTrace.trim())
        bodyBuilder.append("\n```\n\n")

        if (recentLogs.isNotEmpty()) {
            bodyBuilder.append("### Последние логи\n")
            bodyBuilder.append("```\n")
            val recentSnippet = recentLogs.takeLast(15).joinToString("\n")
            val safeLogs = if (recentSnippet.length > 1500) {
                recentSnippet.take(1500) + "\n... [сокращено]"
            } else {
                recentSnippet
            }
            bodyBuilder.append(safeLogs.trim())
            bodyBuilder.append("\n```\n")
        }

        val bodyParam = urlEncode(bodyBuilder.toString())
        return "$base?title=$titleParam&body=$bodyParam"
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    companion object {
        fun fromJson(jsonStr: String): CrashReport {
            val root = JSONObject(jsonStr)
            val logsList = mutableListOf<String>()
            val logsArray = root.optJSONArray("recentLogs")
            if (logsArray != null) {
                for (i in 0 until logsArray.length()) {
                    logsList.add(logsArray.getString(i))
                }
            }
            return CrashReport(
                id = root.getString("id"),
                timestampMs = root.getLong("timestampMs"),
                timestampIso = root.getString("timestampIso"),
                threadName = root.getString("threadName"),
                threadId = root.getLong("threadId"),
                exceptionClass = root.getString("exceptionClass"),
                exceptionMessage = root.optString("exceptionMessage", ""),
                stackTrace = root.getString("stackTrace"),
                causeMessage = if (root.isNull("causeMessage")) null else root.optString("causeMessage"),
                appVersionName = root.optString("appVersionName", "unknown"),
                appVersionCode = root.optLong("appVersionCode", 0L),
                buildType = root.optString("buildType", "release"),
                deviceManufacturer = root.optString("deviceManufacturer", ""),
                deviceModel = root.optString("deviceModel", ""),
                deviceProduct = root.optString("deviceProduct", ""),
                deviceBoard = root.optString("deviceBoard", ""),
                androidRelease = root.optString("androidRelease", ""),
                androidSdk = root.optInt("androidSdk", 0),
                supportedAbis = root.optString("supportedAbis", ""),
                proxyMode = root.optString("proxyMode", "UNKNOWN"),
                isProxyRunning = root.optBoolean("isProxyRunning", false),
                isVpnRunning = root.optBoolean("isVpnRunning", false),
                workerDomain = if (root.isNull("workerDomain")) null else root.optString("workerDomain"),
                recentLogs = logsList,
                reportFilePath = if (root.isNull("reportFilePath")) null else root.optString("reportFilePath")
            )
        }
    }
}
