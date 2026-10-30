package com.mirrly.tgproxy.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.LogEntry
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExportHelper {

    private const val MAX_CACHED_LOG_FILES = 6
    private const val LOGS_DIR_NAME = "logs"

    fun formatLogEntries(
        entries: List<LogEntry>,
        title: String? = null
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val now = dateFormat.format(Date())

        val sb = StringBuilder()
        sb.append("=== Mirrly TG Proxy — Журнал событий ===\n")
        if (!title.isNullOrBlank()) {
            sb.append("Секция: ").append(title).append("\n")
        }
        sb.append("Дата экспорта: ").append(now).append("\n")
        sb.append("Устройство: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.RELEASE)
            .append(", SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Всего записей: ").append(entries.size).append("\n")
        sb.append("========================================\n\n")

        for (entry in entries) {
            sb.append("[").append(entry.formattedTime).append("] ")
                .append("[").append(entry.level.name).append("] ")
                .append("[").append(entry.tag.ifBlank { "System" }).append("] ")
                .append(entry.humanMessage)
                .append("\n")

            if (entry.rawMessage.isNotBlank() && entry.rawMessage != entry.humanMessage) {
                sb.append("  raw: ").append(entry.rawMessage).append("\n")
            }
        }

        return sb.toString()
    }

    fun formatLogEntriesMarkdown(
        entries: List<LogEntry>,
        title: String? = null
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val now = dateFormat.format(Date())

        val sb = StringBuilder()
        sb.append("# Mirrly TG Proxy — Журнал событий\n\n")
        if (!title.isNullOrBlank()) {
            sb.append("- **Раздел**: ").append(title).append("\n")
        }
        sb.append("- **Дата экспорта**: ").append(now).append("\n")
        sb.append("- **Устройство**: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.RELEASE)
            .append(", SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("- **Всего записей**: ").append(entries.size).append("\n\n")
        sb.append("```markdown\n")

        for (entry in entries) {
            sb.append("[").append(entry.formattedTime).append("] ")
                .append("[").append(entry.level.name).append("] ")
                .append("[").append(entry.tag.ifBlank { "System" }).append("] ")
                .append(entry.humanMessage)
                .append("\n")

            if (entry.rawMessage.isNotBlank() && entry.rawMessage != entry.humanMessage) {
                sb.append("  raw: ").append(entry.rawMessage).append("\n")
            }
        }

        sb.append("```\n")
        return sb.toString()
    }

    fun createMarkdownLogFile(
        context: Context,
        entries: List<LogEntry>,
        filenamePrefix: String = "mirrly_logs",
        sectionTitle: String? = null
    ): File? {
        return try {
            val logsDir = File(context.cacheDir, LOGS_DIR_NAME).apply {
                if (!exists()) mkdirs()
            }

            purgeOldLogFiles(logsDir)

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safePrefix = filenamePrefix.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val targetFile = File(logsDir, "${safePrefix}_$timeStamp.md")

            val content = formatLogEntriesMarkdown(entries, sectionTitle)
            FileOutputStream(targetFile).use { fos ->
                OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
            }
            targetFile
        } catch (_: Throwable) {
            null
        }
    }

    fun createLogFile(
        context: Context,
        entries: List<LogEntry>,
        filenamePrefix: String = "mirrly_logs"
    ): File? {
        return try {
            val logsDir = File(context.cacheDir, LOGS_DIR_NAME).apply {
                if (!exists()) mkdirs()
            }

            // Purge older cache files to keep storage clean and efficient
            purgeOldLogFiles(logsDir)

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safePrefix = filenamePrefix.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val targetFile = File(logsDir, "${safePrefix}_$timeStamp.log")

            val content = formatLogEntries(entries)
            FileOutputStream(targetFile).use { fos ->
                OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
            }
            targetFile
        } catch (_: Throwable) {
            null
        }
    }

    fun shareLogFile(
        context: Context,
        file: File,
        chooserTitle: String? = null
    ): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                putExtra(Intent.EXTRA_TEXT, "Mirrly TG Proxy — Экспорт логов: ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val title = chooserTitle ?: context.getString(R.string.logs_export_share_chooser_title)
            val chooser = Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun copyToClipboard(
        context: Context,
        entries: List<LogEntry>,
        label: String = "Mirrly Proxy Logs"
    ): Int {
        if (entries.isEmpty()) return 0
        val text = formatLogEntries(entries)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        return entries.size
    }

    fun copyMarkdownToClipboard(
        context: Context,
        entries: List<LogEntry>,
        title: String? = null,
        label: String = "Mirrly Proxy Logs"
    ): Int {
        if (entries.isEmpty()) return 0
        val text = formatLogEntriesMarkdown(entries, title)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        return entries.size
    }

    private fun purgeOldLogFiles(logsDir: File) {
        val files = logsDir.listFiles { f -> f.isFile && (f.name.endsWith(".log") || f.name.endsWith(".md")) } ?: return
        if (files.size >= MAX_CACHED_LOG_FILES) {
            val sorted = files.sortedBy { it.lastModified() }
            val removeCount = (sorted.size - MAX_CACHED_LOG_FILES) + 1
            for (i in 0 until removeCount.coerceAtMost(sorted.size)) {
                sorted[i].delete()
            }
        }
    }
}
