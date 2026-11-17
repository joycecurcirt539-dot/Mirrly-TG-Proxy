/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * Cyber Snake Leaderboard & Supabase Sync Contract
 */

package com.mirrly.tgproxy.ui.snake

import android.content.Context
import android.os.Build
import com.mirrly.tgproxy.core.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Data model matching the Supabase 'snake_leaderboard' table schema:
 *
 * ```sql
 * CREATE TABLE public.snake_leaderboard (
 *     id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 *     player_name TEXT NOT NULL,
 *     score INTEGER NOT NULL,
 *     mode TEXT NOT NULL, -- 'MANUAL' or 'AI'
 *     generation INTEGER NOT NULL DEFAULT 1,
 *     fitness REAL NOT NULL DEFAULT 0.0,
 *     created_at TIMESTAMPTZ DEFAULT timezone('utc'::text, now()) NOT NULL
 * );
 * CREATE INDEX idx_snake_score ON public.snake_leaderboard (score DESC);
 * ```
 */
data class SnakeScoreEntry(
    val id: String = UUID.randomUUID().toString(),
    val playerName: String,
    val score: Int,
    val mode: String, // "MANUAL" or "AI"
    val generation: Int,
    val fitness: Float,
    val createdAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
) {
    fun formattedDate(): String {
        val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        return sdf.format(Date(createdAt))
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("player_name", playerName)
        put("score", score)
        put("mode", mode)
        put("generation", generation)
        put("fitness", fitness.toDouble())
        put("created_at", createdAt)
        put("is_synced", isSynced)
    }

    companion object {
        fun fromJson(json: JSONObject): SnakeScoreEntry = SnakeScoreEntry(
            id = json.optString("id", UUID.randomUUID().toString()),
            playerName = json.optString("player_name", "Pilot_Cyber"),
            score = json.optInt("score", 0),
            mode = json.optString("mode", "MANUAL"),
            generation = json.optInt("generation", 1),
            fitness = json.optDouble("fitness", 0.0).toFloat(),
            createdAt = json.optLong("created_at", System.currentTimeMillis()),
            isSynced = json.optBoolean("is_synced", false)
        )
    }
}

class SnakeLeaderboardManager(private val context: Context) {
    private val TAG = "SnakeLeaderboard"
    private val storageFile by lazy { File(context.filesDir, "snake_leaderboard.json") }
    private val entries = mutableListOf<SnakeScoreEntry>()

    init {
        loadEntries()
    }

    @Synchronized
    fun getTopScores(mode: String? = null, limit: Int = 10): List<SnakeScoreEntry> {
        val filtered = if (mode != null) {
            entries.filter { it.mode.equals(mode, ignoreCase = true) }
        } else {
            entries
        }
        return filtered.sortedByDescending { it.score }.take(limit)
    }

    @Synchronized
    fun recordScore(
        score: Int,
        mode: String,
        generation: Int,
        fitness: Float,
        playerName: String = getDefaultPlayerName()
    ): SnakeScoreEntry? {
        if (score <= 0) return null
        val entry = SnakeScoreEntry(
            playerName = playerName,
            score = score,
            mode = mode,
            generation = generation,
            fitness = fitness,
            isSynced = false
        )
        entries.add(entry)
        saveEntries()
        AppLogger.i(TAG, "Recorded new snake record: score=$score, mode=$mode, id=${entry.id}")
        return entry
    }

    fun getDefaultPlayerName(): String {
        val modelHash = (Build.MODEL.hashCode() and 0xFFFF).toString(16).uppercase()
        return "Pilot_$modelHash"
    }

    @Synchronized
    private fun loadEntries() {
        entries.clear()
        if (!storageFile.exists()) return
        try {
            val text = storageFile.readText()
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                entries.add(SnakeScoreEntry.fromJson(array.getJSONObject(i)))
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed to load leaderboard: ${t.message}")
        }
    }

    @Synchronized
    private fun saveEntries() {
        try {
            val array = JSONArray()
            val trimmed = entries.sortedByDescending { it.score }.take(100)
            for (entry in trimmed) {
                array.put(entry.toJson())
            }
            storageFile.writeText(array.toString())
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed to save leaderboard: ${t.message}")
        }
    }

    /**
     * Ready for Supabase: returns unsynced records formatted for bulk upsert.
     */
    @Synchronized
    fun getPendingSyncPayload(): List<SnakeScoreEntry> {
        return entries.filter { !it.isSynced }
    }

    @Synchronized
    fun markAsSynced(ids: Set<String>) {
        for (i in entries.indices) {
            if (ids.contains(entries[i].id)) {
                entries[i] = entries[i].copy(isSynced = true)
            }
        }
        saveEntries()
    }
}
