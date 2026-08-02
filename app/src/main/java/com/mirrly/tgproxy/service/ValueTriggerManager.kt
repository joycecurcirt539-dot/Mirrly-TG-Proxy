package com.mirrly.tgproxy.service

import android.content.Context
import android.content.SharedPreferences

object ValueTriggerManager {
    private const val PREFS_NAME = "mirrly_value_trigger_prefs"
    private const val KEY_ACTIVE_MINUTES = "total_proxy_active_minutes"
    private const val KEY_ACCUM_SECONDS = "accumulated_seconds"
    private const val KEY_PROMPT_SHOWN = "github_star_value_prompt_shown"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Accumulates active proxy running seconds and updates total_proxy_active_minutes.
     * Returns true if 60+ minutes active work condition is met for the first time.
     */
    fun addActiveSeconds(context: Context, seconds: Int): Boolean {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_PROMPT_SHOWN, false)) return false

        val currentAccSec = prefs.getInt(KEY_ACCUM_SECONDS, 0) + seconds
        val newMinutes = currentAccSec / 60
        val remainingSec = currentAccSec % 60

        val totalMinutes = prefs.getInt(KEY_ACTIVE_MINUTES, 0) + newMinutes

        prefs.edit()
            .putInt(KEY_ACCUM_SECONDS, remainingSec)
            .putInt(KEY_ACTIVE_MINUTES, totalMinutes)
            .apply()

        return totalMinutes >= 60 && !prefs.getBoolean(KEY_PROMPT_SHOWN, false)
    }

    fun getTotalActiveMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_ACTIVE_MINUTES, 0)
    }

    fun shouldShowValueBanner(context: Context): Boolean {
        val prefs = getPrefs(context)
        val shown = prefs.getBoolean(KEY_PROMPT_SHOWN, false)
        val minutes = prefs.getInt(KEY_ACTIVE_MINUTES, 0)
        return minutes >= 60 && !shown
    }

    fun markValuePromptShown(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_PROMPT_SHOWN, true).apply()
    }
}
