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

/**
 * Запись истории замера скорости сетевого туннеля.
 */
data class SpeedTestRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val downloadSpeedMbps: Double = 0.0,
    val uploadSpeedMbps: Double = 0.0,
    val pingMs: Long = 0L,
    val jitterMs: Long = 0L,
    val edgeColo: String = "—",
    val targetDomain: String = "",
    val protocol: String = "SOCKS5",
    val qualityGrade: String = "—",
    val overallScore: Int = 100,
    val durationMs: Long = 0L
) {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("timestampMs", timestampMs)
        json.put("downloadSpeedMbps", downloadSpeedMbps)
        json.put("uploadSpeedMbps", uploadSpeedMbps)
        json.put("pingMs", pingMs)
        json.put("jitterMs", jitterMs)
        json.put("edgeColo", edgeColo)
        json.put("targetDomain", targetDomain)
        json.put("protocol", protocol)
        json.put("qualityGrade", qualityGrade)
        json.put("overallScore", overallScore)
        json.put("durationMs", durationMs)
        return json
    }

    companion object {
        fun fromJsonObject(json: JSONObject): SpeedTestRecord {
            return SpeedTestRecord(
                id = json.optString("id", UUID.randomUUID().toString()),
                timestampMs = json.optLong("timestampMs", System.currentTimeMillis()),
                downloadSpeedMbps = json.optDouble("downloadSpeedMbps", 0.0),
                uploadSpeedMbps = json.optDouble("uploadSpeedMbps", 0.0),
                pingMs = json.optLong("pingMs", 0L),
                jitterMs = json.optLong("jitterMs", 0L),
                edgeColo = json.optString("edgeColo", "—"),
                targetDomain = json.optString("targetDomain", ""),
                protocol = json.optString("protocol", "SOCKS5"),
                qualityGrade = json.optString("qualityGrade", "—"),
                overallScore = json.optInt("overallScore", 100),
                durationMs = json.optLong("durationMs", 0L)
            )
        }
    }
}

/**
 * Потокобезопасный менеджер персистентной истории замеров скорости сети.
 * Хранит до 50 последних замеров в SharedPreferences в формате JSON.
 */
object SpeedTestHistoryManager {
    private const val PREFS_NAME = "mirrly_speed_history_prefs"
    private const val KEY_HISTORY = "speed_history_json"
    private const val MAX_HISTORY_ITEMS = 50

    private var prefs: SharedPreferences? = null
    private val historyList = ArrayList<SpeedTestRecord>()

    private val _historyFlow = MutableStateFlow<List<SpeedTestRecord>>(emptyList())
    val historyFlow: StateFlow<List<SpeedTestRecord>> = _historyFlow.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    @Synchronized
    fun addRecord(record: SpeedTestRecord) {
        historyList.add(0, record)
        if (historyList.size > MAX_HISTORY_ITEMS) {
            historyList.removeAt(historyList.size - 1)
        }
        saveToPrefs()
        _historyFlow.value = historyList.toList()
    }

    @Synchronized
    fun clearHistory() {
        historyList.clear()
        saveToPrefs()
        _historyFlow.value = emptyList()
    }

    @Synchronized
    fun deleteRecord(id: String) {
        historyList.removeAll { it.id == id }
        saveToPrefs()
        _historyFlow.value = historyList.toList()
    }

    private fun loadFromPrefs() {
        val p = prefs ?: return
        val jsonStr = p.getString(KEY_HISTORY, null) ?: return
        try {
            val jsonArray = JSONArray(jsonStr)
            historyList.clear()
            for (i in 0 until jsonArray.length()) {
                val itemObj = jsonArray.getJSONObject(i)
                historyList.add(SpeedTestRecord.fromJsonObject(itemObj))
            }
            _historyFlow.value = historyList.toList()
        } catch (e: Exception) {
            AppLogger.e("SpeedTestHistory", "Ошибка чтения истории: ${e.message}")
        }
    }

    private fun saveToPrefs() {
        val p = prefs ?: return
        try {
            val jsonArray = JSONArray()
            for (record in historyList) {
                jsonArray.put(record.toJsonObject())
            }
            p.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
        } catch (e: Exception) {
            AppLogger.e("SpeedTestHistory", "Ошибка сохранения истории: ${e.message}")
        }
    }
}
