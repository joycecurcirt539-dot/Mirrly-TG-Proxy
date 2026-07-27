package com.mirrly.tgproxy.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val rawMessage: String,
    val humanMessage: String = HumanLogTranslator.translateToHumanRussian(tag, rawMessage)
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

enum class LogLevel {
    INFO, WARN, ERROR
}

object AppLogger {
    private const val MAX_LOGS = 1000
    private val logList = mutableListOf<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()
    @Volatile
    private var isLogcatReaderStarted = false

    @Synchronized
    fun log(level: LogLevel, tag: String, message: String) {
        if (HumanLogTranslator.shouldIgnoreLogcatLine(tag, message)) return

        val entry = LogEntry(level = level, tag = tag, rawMessage = message)
        logList.add(entry)
        if (logList.size > MAX_LOGS) {
            logList.removeAt(0)
        }
        _logsFlow.value = logList.toList()
    }

    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun startLogcatReader(pid: Int = -1) {
        if (isLogcatReaderStarted) return
        isLogcatReaderStarted = true

        Thread({
            try {
                val cmd = if (pid > 0) "logcat -v time --pid=$pid *:V" else "logcat -v time *:V"
                val process = Runtime.getRuntime().exec(cmd)
                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    parseAndAddLogcatLine(currentLine)
                }
            } catch (_: Exception) {}
        }, "LogcatReader").apply {
            isDaemon = true
            start()
        }
    }

    private fun parseAndAddLogcatLine(line: String) {
        if (line.isBlank()) return
        val level = when {
            line.contains(" E/") || line.contains(" E ") || line.startsWith("E/") -> LogLevel.ERROR
            line.contains(" W/") || line.contains(" W ") || line.startsWith("W/") -> LogLevel.WARN
            line.contains(" I/") || line.contains(" I ") || line.startsWith("I/") -> LogLevel.INFO
            else -> return
        }

        val tag = try {
            val startIdx = line.indexOf(")")
            val slashIdx = line.indexOf("/", if (startIdx != -1) startIdx else 0)
            val colonIdx = line.indexOf(":", if (slashIdx != -1) slashIdx else 0)
            if (slashIdx != -1 && colonIdx != -1 && colonIdx > slashIdx) {
                line.substring(slashIdx + 1, colonIdx).trim()
            } else "System"
        } catch (_: Exception) { "System" }

        val msg = try {
            val colonIdx = line.indexOf(":")
            if (colonIdx != -1 && colonIdx < line.length - 1) {
                line.substring(colonIdx + 1).trim()
            } else line
        } catch (_: Exception) { line }

        log(level, tag, msg)
    }

    @Synchronized
    fun clear() {
        logList.clear()
        _logsFlow.value = emptyList()
    }
}
