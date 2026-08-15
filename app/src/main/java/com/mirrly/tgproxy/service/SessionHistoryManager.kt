package com.mirrly.tgproxy.service

import android.content.Context
import android.content.SharedPreferences
import com.mirrly.tgproxy.core.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SessionStatus {
    ACTIVE,
    COMPLETED,
    INTERRUPTED
}

data class SessionRecord(
    val id: String = UUID.randomUUID().toString(),
    val startTimeMs: Long = System.currentTimeMillis(),
    val endTimeMs: Long = 0L,
    val bytesReceived: Long = 0L,
    val bytesSent: Long = 0L,
    val peakSpeedBps: Long = 0L,
    val maxConnections: Int = 0,
    val presetName: String = "Баланс",
    val status: SessionStatus = SessionStatus.ACTIVE,
    val proxyMode: String = "MTPROTO"
) {
    val totalBytes: Long
        get() = bytesReceived + bytesSent

    val durationSeconds: Long
        get() {
            return if (status == SessionStatus.ACTIVE) {
                if (startTimeMs > 0L) (System.currentTimeMillis() - startTimeMs) / 1000L else 0L
            } else {
                if (endTimeMs > startTimeMs && startTimeMs > 0L) (endTimeMs - startTimeMs) / 1000L else 0L
            }
        }

    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("startTimeMs", startTimeMs)
        json.put("endTimeMs", endTimeMs)
        json.put("bytesReceived", bytesReceived)
        json.put("bytesSent", bytesSent)
        json.put("peakSpeedBps", peakSpeedBps)
        json.put("maxConnections", maxConnections)
        json.put("presetName", presetName)
        json.put("status", status.name)
        json.put("proxyMode", proxyMode)
        return json
    }

    companion object {
        fun fromJsonObject(json: JSONObject): SessionRecord {
            val statusStr = json.optString("status", SessionStatus.COMPLETED.name)
            val statusEnum = try {
                SessionStatus.valueOf(statusStr)
            } catch (_: Exception) {
                SessionStatus.COMPLETED
            }

            return SessionRecord(
                id = json.optString("id", UUID.randomUUID().toString()),
                startTimeMs = json.optLong("startTimeMs", System.currentTimeMillis()),
                endTimeMs = json.optLong("endTimeMs", 0L),
                bytesReceived = json.optLong("bytesReceived", 0L),
                bytesSent = json.optLong("bytesSent", 0L),
                peakSpeedBps = json.optLong("peakSpeedBps", 0L),
                maxConnections = json.optInt("maxConnections", 0),
                presetName = json.optString("presetName", "Баланс"),
                status = statusEnum,
                proxyMode = json.optString("proxyMode", "MTPROTO")
            )
        }
    }
}

object SessionHistoryManager {
    private const val PREFS_NAME = "mirrly_session_history_prefs"
    private const val KEY_HISTORY = "history_json"
    private const val MAX_HISTORY_ITEMS = 100

    private var prefs: SharedPreferences? = null
    private val historyList = ArrayList<SessionRecord>()

    private val _historyFlow = MutableStateFlow<List<SessionRecord>>(emptyList())
    val historyFlow: StateFlow<List<SessionRecord>> = _historyFlow.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
        sanitizeOrphanActiveSessions()
    }

    @Synchronized
    private fun loadFromPrefs() {
        historyList.clear()
        val jsonStr = prefs?.getString(KEY_HISTORY, null) ?: return
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i)
                historyList.add(SessionRecord.fromJsonObject(json))
            }
        } catch (e: Exception) {
            AppLogger.e("SessionHistoryManager", "Ошибка загрузки истории сессий: ${e.message}")
        }
        _historyFlow.value = historyList.toList()
    }

    @Synchronized
    private fun saveToPrefs() {
        try {
            val array = JSONArray()
            for (record in historyList) {
                array.put(record.toJsonObject())
            }
            prefs?.edit()?.putString(KEY_HISTORY, array.toString())?.apply()
        } catch (e: Exception) {
            AppLogger.e("SessionHistoryManager", "Ошибка сохранения истории сессий: ${e.message}")
        }
        _historyFlow.value = historyList.toList()
    }

    @Synchronized
    fun sanitizeOrphanActiveSessions() {
        var changed = false
        val now = System.currentTimeMillis()
        for (i in historyList.indices) {
            val rec = historyList[i]
            if (rec.status == SessionStatus.ACTIVE) {
                historyList[i] = rec.copy(
                    status = SessionStatus.INTERRUPTED,
                    endTimeMs = if (rec.endTimeMs > 0L) rec.endTimeMs else now
                )
                changed = true
            }
        }
        if (changed) {
            saveToPrefs()
        }
    }

    @Synchronized
    fun onSessionStarted(presetName: String, proxyMode: String = "MTPROTO"): SessionRecord {
        // If there's an existing active session, mark it as completed first
        val now = System.currentTimeMillis()
        for (i in historyList.indices) {
            if (historyList[i].status == SessionStatus.ACTIVE) {
                val old = historyList[i]
                historyList[i] = old.copy(
                    status = SessionStatus.COMPLETED,
                    endTimeMs = now
                )
            }
        }

        val newRecord = SessionRecord(
            startTimeMs = now,
            presetName = presetName,
            status = SessionStatus.ACTIVE,
            proxyMode = proxyMode
        )
        historyList.add(0, newRecord) // add newest at top

        while (historyList.size > MAX_HISTORY_ITEMS) {
            historyList.removeAt(historyList.size - 1)
        }

        saveToPrefs()
        AppLogger.i("SessionHistoryManager", "Сессия запущена [${newRecord.id}] ($presetName, $proxyMode)")
        return newRecord
    }

    @Synchronized
    fun onSessionUpdate(
        bytesReceived: Long,
        bytesSent: Long,
        peakSpeedBps: Long,
        activeConnections: Int
    ) {
        if (historyList.isEmpty()) return
        val activeIdx = historyList.indexOfFirst { it.status == SessionStatus.ACTIVE }
        if (activeIdx != -1) {
            val current = historyList[activeIdx]
            val maxConn = maxOf(current.maxConnections, activeConnections)
            val peakSpd = maxOf(current.peakSpeedBps, peakSpeedBps)
            historyList[activeIdx] = current.copy(
                bytesReceived = bytesReceived,
                bytesSent = bytesSent,
                peakSpeedBps = peakSpd,
                maxConnections = maxConn
            )
            _historyFlow.value = historyList.toList()
        }
    }

    @Synchronized
    fun onSessionEnded(
        bytesReceived: Long,
        bytesSent: Long,
        peakSpeedBps: Long,
        maxConnections: Int
    ) {
        val now = System.currentTimeMillis()
        val activeIdx = historyList.indexOfFirst { it.status == SessionStatus.ACTIVE }
        if (activeIdx != -1) {
            val current = historyList[activeIdx]
            historyList[activeIdx] = current.copy(
                endTimeMs = now,
                bytesReceived = bytesReceived,
                bytesSent = bytesSent,
                peakSpeedBps = maxOf(current.peakSpeedBps, peakSpeedBps),
                maxConnections = maxOf(current.maxConnections, maxConnections),
                status = SessionStatus.COMPLETED
            )
            saveToPrefs()
            AppLogger.i("SessionHistoryManager", "Сессия завершена [${current.id}]")
        } else {
            // Save state if flow changed
            saveToPrefs()
        }
    }

    @Synchronized
    fun clearHistory() {
        historyList.clear()
        saveToPrefs()
        AppLogger.i("SessionHistoryManager", "История сессий очищена")
    }
}
