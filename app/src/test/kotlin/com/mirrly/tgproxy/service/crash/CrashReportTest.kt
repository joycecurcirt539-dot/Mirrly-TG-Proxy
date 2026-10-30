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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class CrashReportTest {

    private fun createSampleCrashReport(
        exceptionClass: String = "java.lang.NullPointerException",
        exceptionMessage: String = "Attempt to invoke virtual method on a null object reference",
        stackTrace: String = "java.lang.NullPointerException: Attempt to invoke virtual method\n\tat com.mirrly.tgproxy.Test.crash(Test.kt:42)\n\tat java.lang.Thread.run(Thread.java:1012)",
        recentLogs: List<String> = listOf(
            "[12:00:01] [INFO] [LocalProxyServer] Starting SOCKS5 listener on :10808",
            "[12:00:02] [DEBUG] [WorkerClient] Dialing Cloudflare worker",
            "[12:00:03] [ERROR] [App] Unexpected error during connection"
        )
    ): CrashReport {
        return CrashReport(
            id = "test-uuid-12345",
            timestampMs = 1730000000000L,
            timestampIso = "2026-10-30T04:00:00Z",
            threadName = "main",
            threadId = 1L,
            exceptionClass = exceptionClass,
            exceptionMessage = exceptionMessage,
            stackTrace = stackTrace,
            causeMessage = "java.lang.IllegalStateException: Root cause failure",
            appVersionName = "1.0.12",
            appVersionCode = 28L,
            buildType = "release",
            deviceManufacturer = "Google",
            deviceModel = "Pixel 8 Pro",
            deviceProduct = "husky",
            deviceBoard = "shiba",
            androidRelease = "14",
            androidSdk = 34,
            supportedAbis = "arm64-v8a",
            proxyMode = "SOCKS5",
            isProxyRunning = true,
            isVpnRunning = false,
            workerDomain = "prox****.workers.dev",
            recentLogs = recentLogs,
            reportFilePath = "/data/user/0/com.mirrly.tgproxy/cache/logs/crash_report_1730000000000.log"
        )
    }

    @Test
    fun testJsonSerializationRoundTrip() {
        val original = createSampleCrashReport()
        val jsonString = original.toJson()
        val restored = CrashReport.fromJson(jsonString)

        assertEquals(original.id, restored.id)
        assertEquals(original.timestampMs, restored.timestampMs)
        assertEquals(original.timestampIso, restored.timestampIso)
        assertEquals(original.threadName, restored.threadName)
        assertEquals(original.threadId, restored.threadId)
        assertEquals(original.exceptionClass, restored.exceptionClass)
        assertEquals(original.exceptionMessage, restored.exceptionMessage)
        assertEquals(original.stackTrace, restored.stackTrace)
        assertEquals(original.causeMessage, restored.causeMessage)
        assertEquals(original.appVersionName, restored.appVersionName)
        assertEquals(original.appVersionCode, restored.appVersionCode)
        assertEquals(original.buildType, restored.buildType)
        assertEquals(original.deviceManufacturer, restored.deviceManufacturer)
        assertEquals(original.deviceModel, restored.deviceModel)
        assertEquals(original.deviceProduct, restored.deviceProduct)
        assertEquals(original.deviceBoard, restored.deviceBoard)
        assertEquals(original.androidRelease, restored.androidRelease)
        assertEquals(original.androidSdk, restored.androidSdk)
        assertEquals(original.supportedAbis, restored.supportedAbis)
        assertEquals(original.proxyMode, restored.proxyMode)
        assertEquals(original.isProxyRunning, restored.isProxyRunning)
        assertEquals(original.isVpnRunning, restored.isVpnRunning)
        assertEquals(original.workerDomain, restored.workerDomain)
        assertEquals(original.recentLogs, restored.recentLogs)
        assertEquals(original.reportFilePath, restored.reportFilePath)
    }

    @Test
    fun testMarkdownFormatting() {
        val report = createSampleCrashReport()
        val md = report.toMarkdown()

        assertTrue(md.contains("# Mirrly TG Proxy — Crash Report"))
        assertTrue(md.contains("java.lang.NullPointerException"))
        assertTrue(md.contains("Google Pixel 8 Pro"))
        assertTrue(md.contains("Android**: 14 (API 34)"))
        assertTrue(md.contains("SOCKS5"))
        assertTrue(md.contains("```\njava.lang.NullPointerException"))
        assertTrue(md.contains("[LocalProxyServer] Starting SOCKS5 listener"))
    }

    @Test
    fun testGitHubIssueUrlConstruction() {
        val report = createSampleCrashReport()
        val url = report.buildGitHubIssueUrl()

        assertTrue(url.startsWith("https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/issues/new?"))
        assertTrue(url.contains("title="))
        assertTrue(url.contains("body="))

        // Verify URL decodability
        val decoded = URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("[Crash] java.lang.NullPointerException"))
        assertTrue(decoded.contains("Google Pixel 8 Pro"))
        assertTrue(decoded.contains("com.mirrly.tgproxy.Test.crash"))
    }

    @Test
    fun testGitHubIssueUrlLimitsLengthForLargeTraces() {
        val hugeTrace = "a".repeat(10000)
        val hugeLogs = (1..200).map { "log line $it: " + "x".repeat(100) }
        val report = createSampleCrashReport(stackTrace = hugeTrace, recentLogs = hugeLogs)

        val url = report.buildGitHubIssueUrl()
        // Must be capped safely so browsers and Android Intents don't crash
        assertTrue("URL length ${url.length} should be reasonably bounded", url.length < 8000)
    }
}
