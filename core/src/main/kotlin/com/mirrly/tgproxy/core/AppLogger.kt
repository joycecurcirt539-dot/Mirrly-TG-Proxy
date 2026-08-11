package com.mirrly.tgproxy.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val rawMessage: String
) {
    val humanMessage: String by lazy(LazyThreadSafetyMode.NONE) {
        HumanLogTranslator.translateToHumanRussian(tag, rawMessage)
    }

    val formattedTime: String by lazy(LazyThreadSafetyMode.NONE) {
        timeFormatter.get()?.format(Date(timestamp)) ?: ""
    }

    companion object {
        private val timeFormatter = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            }
        }
    }
}

enum class LogLevel {
    INFO, WARN, ERROR
}

sealed interface LogEvent {
    data class Added(val entry: LogEntry) : LogEvent
    data object Cleared : LogEvent
}

object AppLogger {
    private const val MAX_LOGS = 250
    private val logQueue = ArrayDeque<LogEntry>(MAX_LOGS)

    private val _logEvents = MutableSharedFlow<LogEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val logEvents: SharedFlow<LogEvent> = _logEvents.asSharedFlow()

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    @Deprecated("Use logEvents and getLogs() instead", ReplaceWith("getLogs()"))
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    @Volatile
    private var isLogcatReaderStarted = false
    private var logcatProcess: Process? = null
    private val loggerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var logcatJob: Job? = null

    @Synchronized
    fun getLogs(): List<LogEntry> {
        return logQueue.toList()
    }

    @Synchronized
    fun log(level: LogLevel, tag: String, message: String) {
        if (HumanLogTranslator.shouldIgnoreLogcatLine(tag, message)) return

        val entry = LogEntry(level = level, tag = tag, rawMessage = message)
        logQueue.addLast(entry)
        if (logQueue.size > MAX_LOGS) {
            logQueue.removeFirst()
        }

        _logEvents.tryEmit(LogEvent.Added(entry))
    }

    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun startLogcatReader(pid: Int = -1) {
        if (isLogcatReaderStarted) return
        isLogcatReaderStarted = true

        logcatJob = loggerScope.launch {
            try {
                // Filter logcat strictly to relevant app/proxy tags to stop framework log flooding
                val tagsFilter = "LocalProxyServer:V ProxyForegroundService:V TgProxy:V BootReceiver:V AppLogger:V *:S"
                val cmd = if (pid > 0) "logcat -v time --pid=$pid $tagsFilter" else "logcat -v time $tagsFilter"
                val process = Runtime.getRuntime().exec(cmd)
                logcatProcess = process
                val reader = process.inputStream.bufferedReader()
                var line: String? = null
                while (isActive && reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    parseAndAddLogcatLine(currentLine)
                }
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun stopLogcatReader() {
        try {
            logcatProcess?.destroy()
        } catch (_: Exception) {}
        logcatProcess = null
        logcatJob?.cancel()
        logcatJob = null
        isLogcatReaderStarted = false
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
        logQueue.clear()
        _logsFlow.value = emptyList()
        _logEvents.tryEmit(LogEvent.Cleared)
    }
}
