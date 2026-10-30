package com.mirrly.tgproxy.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val id: Long = nextId.getAndIncrement(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val rawMessage: String
) {
    val humanMessage: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        HumanLogTranslator.translateToHumanRussian(tag, rawMessage)
    }

    val formattedTime: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        timeFormatter.get()?.format(Date(timestamp)) ?: ""
    }

    companion object {
        private val nextId = java.util.concurrent.atomic.AtomicLong(1L)
        private val timeFormatter = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            }
        }
    }
}

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

internal class BoundedLogcatReplayFilter(
    private val capacity: Int
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val seenLines = LinkedHashSet<String>(capacity)

    @Synchronized
    fun shouldAccept(line: String): Boolean {
        if (!seenLines.add(line)) return false
        while (seenLines.size > capacity) {
            val oldest = seenLines.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }
}

object AppLogger {
    private const val MAX_LOGS = 500
    private const val MAX_SEEN_LOGCAT_LINES = 4096
    private val logQueue = ArrayDeque<LogEntry>(MAX_LOGS)
    private val logcatReplayFilter = BoundedLogcatReplayFilter(MAX_SEEN_LOGCAT_LINES)

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    @Volatile
    var isLoggingEnabled: Boolean = true
        private set

    @Volatile
    var isLogcatReaderAllowed: Boolean = true
        private set

    @Volatile
    var enabledLevels: Set<LogLevel> = setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.DEBUG)
        private set

    @Volatile
    var disabledCategories: Set<String> = emptySet()
        private set

    @Volatile
    var disabledTags: Set<String> = emptySet()
        private set

    private val dynamicKnownTags = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
        LogCategory.entries.forEach { addAll(it.tags) }
    }

    @Volatile
    private var isLogcatReaderStarted = false
    private var logcatProcess: Process? = null
    private val loggerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var logcatJob: Job? = null

    @Synchronized
    fun configure(
        loggingEnabled: Boolean = isLoggingEnabled,
        logcatReaderAllowed: Boolean = isLogcatReaderAllowed,
        levels: Set<LogLevel> = enabledLevels,
        disabledCats: Set<String> = disabledCategories,
        disabledTgs: Set<String> = disabledTags
    ) {
        isLoggingEnabled = loggingEnabled
        isLogcatReaderAllowed = logcatReaderAllowed
        enabledLevels = levels
        disabledCategories = disabledCats
        disabledTags = disabledTgs
        if (!isLoggingEnabled || !isLogcatReaderAllowed) {
            stopLogcatReader()
        }
    }

    @Synchronized
    fun setLoggingEnabled(enabled: Boolean) {
        isLoggingEnabled = enabled
        if (!enabled) {
            stopLogcatReader()
        }
    }

    @Synchronized
    fun setLogcatReaderAllowed(allowed: Boolean) {
        isLogcatReaderAllowed = allowed
        if (!allowed) {
            stopLogcatReader()
        }
    }

    @Synchronized
    fun setLevelEnabled(level: LogLevel, enabled: Boolean) {
        enabledLevels = if (enabled) enabledLevels + level else enabledLevels - level
    }

    @Synchronized
    fun setCategoryEnabled(categoryKey: String, enabled: Boolean) {
        disabledCategories = if (enabled) disabledCategories - categoryKey else disabledCategories + categoryKey
    }

    @Synchronized
    fun setTagEnabled(tag: String, enabled: Boolean) {
        val cleanTag = tag.trim()
        disabledTags = if (enabled) {
            disabledTags.filterNot { it.equals(cleanTag, ignoreCase = true) }.toSet()
        } else {
            disabledTags + cleanTag
        }
    }

    fun isLevelEnabled(level: LogLevel): Boolean = level in enabledLevels

    fun isCategoryEnabled(categoryKey: String): Boolean = categoryKey !in disabledCategories

    fun isTagDisabled(tag: String): Boolean {
        val cleanTag = tag.trim()
        if (cleanTag.isEmpty()) return false
        if (disabledTags.any { it.equals(cleanTag, ignoreCase = true) }) return true
        val cat = LogCategory.findCategoryForTag(cleanTag)
        if (cat != null && disabledCategories.contains(cat.key)) return true
        return false
    }

    fun isTagEnabled(tag: String): Boolean = !isTagDisabled(tag)

    fun isLoggingEnabledFor(level: LogLevel, tag: String): Boolean {
        return isLoggingEnabled && level in enabledLevels && !isTagDisabled(tag)
    }

    fun getAllKnownTags(): Set<String> = dynamicKnownTags.toSet()

    @Synchronized
    fun getLogs(): List<LogEntry> {
        return logQueue.toList()
    }

    @Synchronized
    fun log(level: LogLevel, tag: String, message: String) {
        if (!isLoggingEnabled) return
        if (level !in enabledLevels) return
        val cleanTag = tag.trim().ifEmpty { "System" }
        if (isTagDisabled(cleanTag)) return
        if (HumanLogTranslator.shouldIgnoreLogcatLine(cleanTag, message)) return

        dynamicKnownTags.add(cleanTag)

        val entry = LogEntry(level = level, tag = cleanTag, rawMessage = message)
        logQueue.addLast(entry)
        if (logQueue.size > MAX_LOGS) {
            logQueue.removeFirst()
        }

        _logsFlow.value = logQueue.toList()
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val finalMessage = if (throwable != null && (throwable.message == null || message.endsWith(": null"))) {
            message.replace(": null", ": ${throwable.javaClass.simpleName}")
        } else {
            message
        }
        log(LogLevel.ERROR, tag, finalMessage)
    }

    @Synchronized
    fun startLogcatReader(pid: Int = -1) {
        if (!isLoggingEnabled || !isLogcatReaderAllowed) return
        if (isLogcatReaderStarted) return
        isLogcatReaderStarted = true

        logcatJob = loggerScope.launch {
            try {
                // Filter logcat strictly to relevant app/proxy tags to stop framework log flooding
                val tagsFilter = "TgWsProxy:V mirrlyengine:V LocalProxyServer:V ProxyForegroundService:V TgProxy:V BootReceiver:V MirrlySecNative:V AppLogger:V *:S"
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
        if (!isLoggingEnabled) return
        if (line.isBlank()) return
        val level = when {
            line.contains("[ERROR]") || line.contains(" E/") || line.contains(" E ") || line.startsWith("E/") -> LogLevel.ERROR
            line.contains("[WARN]") || line.contains(" W/") || line.contains(" W ") || line.startsWith("W/") -> LogLevel.WARN
            line.contains("[DEBUG]") || line.contains(" D/") || line.contains(" D ") || line.startsWith("D/") -> LogLevel.DEBUG
            line.contains(" I/") || line.contains(" I ") || line.startsWith("I/") -> LogLevel.INFO
            else -> return
        }
        if (level !in enabledLevels) return

        val tag = try {
            val startIdx = line.indexOf(")")
            val slashIdx = line.indexOf("/", if (startIdx != -1) startIdx else 0)
            val colonIdx = line.indexOf(":", if (slashIdx != -1) slashIdx else 0)
            if (slashIdx != -1 && colonIdx != -1 && colonIdx > slashIdx) {
                line.substring(slashIdx + 1, colonIdx).trim()
            } else "System"
        } catch (_: Exception) { "System" }

        // Ignore internal AppLogger logcat lines to avoid duplicate entries
        if (tag == "AppLogger") return
        if (isTagDisabled(tag)) return

        // Reopening the screen starts a new `logcat` process, which replays its
        // ring buffer. The original line includes the event timestamp, so exact
        // equality identifies replay without hiding a genuine repeated event.
        if (!logcatReplayFilter.shouldAccept(line)) return

        val msg = try {
            val slashIdx = line.indexOf("/")
            val colonIdx = line.indexOf(":", if (slashIdx >= 0) slashIdx else 0)
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
    }
}
