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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.mirrly.tgproxy.BuildConfig
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Manages crash capture, persistent crash detection across restarts,
 * crash report file generation, and GitHub Issue preparation.
 */
object CrashReportManager {

    private const val CRASH_DIR_NAME = "crash_reports"
    private const val PENDING_CRASH_FILENAME = "crash_pending.json"

    private val _pendingCrashFlow = MutableStateFlow<CrashReport?>(null)
    val pendingCrashFlow: StateFlow<CrashReport?> = _pendingCrashFlow.asStateFlow()

    @Volatile
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrashSynchronously(context, thread, throwable)
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        checkAndLoadPendingCrash(context)
    }

    private fun checkAndLoadPendingCrash(context: Context) {
        try {
            val crashDir = File(context.filesDir, CRASH_DIR_NAME)
            val pendingFile = File(crashDir, PENDING_CRASH_FILENAME)
            if (pendingFile.exists() && pendingFile.length() > 0) {
                val jsonStr = pendingFile.readText(Charsets.UTF_8)
                val report = CrashReport.fromJson(jsonStr)

                val reportFile = generateReportFile(context, report)
                report.reportFilePath = reportFile?.absolutePath

                _pendingCrashFlow.value = report
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun generateReportFile(context: Context, report: CrashReport): File? {
        return try {
            val logsDir = File(context.cacheDir, "logs").apply { mkdirs() }
            val reportFile = File(logsDir, "crash_report_${report.timestampMs}.log")
            reportFile.writeText(report.toMarkdown(), Charsets.UTF_8)
            reportFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun clearPendingCrash(context: Context) {
        try {
            val crashDir = File(context.filesDir, CRASH_DIR_NAME)
            val pendingFile = File(crashDir, PENDING_CRASH_FILENAME)
            if (pendingFile.exists()) {
                pendingFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _pendingCrashFlow.value = null
    }

    fun copyCrashReport(context: Context, report: CrashReport) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Mirrly Crash Report", report.toMarkdown())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, context.getString(R.string.crash_toast_copied), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun shareCrashReportFile(context: Context, report: CrashReport) {
        try {
            val file = if (report.reportFilePath != null && File(report.reportFilePath!!).exists()) {
                File(report.reportFilePath!!)
            } else {
                generateReportFile(context, report)
            }

            if (file == null || !file.exists()) {
                Toast.makeText(context, context.getString(R.string.logs_export_failed), Toast.LENGTH_SHORT).show()
                return
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Mirrly TG Proxy Crash Report - ${report.timestampIso}")
                putExtra(Intent.EXTRA_TEXT, "Mirrly TG Proxy Crash Report (${report.exceptionClass})\nFile: ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, context.getString(R.string.crash_btn_share_file)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, context.getString(R.string.logs_export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun openGitHubIssue(context: Context, report: CrashReport) {
        try {
            val issueUrl = report.buildGitHubIssueUrl()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(issueUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            copyCrashReport(context, report)
        }
    }

    private fun recordCrashSynchronously(context: Context, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val timestampIso = isoFormat.format(Date(now))

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val recentLogs = try {
            AppLogger.getLogs().takeLast(40).map { entry ->
                "[${entry.formattedTime}] [${entry.level.name}] [${entry.tag}] ${entry.rawMessage}"
            }
        } catch (_: Throwable) {
            emptyList()
        }

        val app = runCatching { MirrlyApplication.instance }.getOrNull()
        val proxyMode = app?.config?.proxyMode?.name ?: "UNKNOWN"
        val isProxyRunning = app?.proxyServer?.isRunning ?: false
        val isVpnRunning = runCatching {
            com.mirrly.tgproxy.service.MirrlyVpnService.isRunning
        }.getOrDefault(false)

        val rawWorker = runCatching { app?.config?.getEffectiveCfDomain() }.getOrNull()
        val maskedWorker = if (!rawWorker.isNullOrBlank()) {
            if (rawWorker.length > 8) "${rawWorker.take(4)}****${rawWorker.takeLast(4)}" else "****"
        } else null

        @Suppress("DEPRECATION")
        val threadIdVal = thread.id

        val report = CrashReport(
            id = UUID.randomUUID().toString(),
            timestampMs = now,
            timestampIso = timestampIso,
            threadName = thread.name,
            threadId = threadIdVal,
            exceptionClass = throwable.javaClass.name,
            exceptionMessage = throwable.localizedMessage ?: throwable.message ?: "",
            stackTrace = stackTrace,
            causeMessage = throwable.cause?.let { "${it.javaClass.name}: ${it.localizedMessage ?: it.message}" },
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            buildType = BuildConfig.BUILD_TYPE,
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            deviceProduct = Build.PRODUCT,
            deviceBoard = Build.BOARD,
            androidRelease = Build.VERSION.RELEASE,
            androidSdk = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.joinToString(", "),
            proxyMode = proxyMode,
            isProxyRunning = isProxyRunning,
            isVpnRunning = isVpnRunning,
            workerDomain = maskedWorker,
            recentLogs = recentLogs
        )

        val crashDir = File(context.filesDir, CRASH_DIR_NAME).apply { mkdirs() }
        val pendingFile = File(crashDir, PENDING_CRASH_FILENAME)
        val jsonBytes = report.toJson().toByteArray(Charsets.UTF_8)

        FileOutputStream(pendingFile).use { fos ->
            fos.write(jsonBytes)
            fos.flush()
            fos.fd.sync()
        }
    }
}
