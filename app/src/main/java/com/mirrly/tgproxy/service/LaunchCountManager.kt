package com.mirrly.tgproxy.service

import android.content.Context
import android.content.SharedPreferences

object LaunchCountManager {
    private const val PREFS_NAME = "mirrly_launch_prefs"
    private const val KEY_LAUNCH_COUNT = "launch_count"
    private const val KEY_STAR_DISMISSED = "github_star_dismissed"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Increments application launch count on cold start and returns updated count.
     */
    fun onAppLaunched(context: Context): Int {
        val prefs = getPrefs(context)
        val currentCount = prefs.getInt(KEY_LAUNCH_COUNT, 0) + 1
        prefs.edit().putInt(KEY_LAUNCH_COUNT, currentCount).apply()
        return currentCount
    }

    fun getLaunchCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_LAUNCH_COUNT, 0)
    }

    fun isStarDismissed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STAR_DISMISSED, false)
    }

    fun setStarDismissed(context: Context, dismissed: Boolean = true) {
        getPrefs(context).edit().putBoolean(KEY_STAR_DISMISSED, dismissed).apply()
    }

    /**
     * Checks if star dialog should be presented to the user:
     * - Triggers on 2nd launch (or 10th launch if postponed earlier).
     * - Does NOT trigger if user previously dismissed or starred the app.
     */
    fun shouldShowStarDialog(context: Context): Boolean {
        if (isStarDismissed(context)) return false
        val count = getLaunchCount(context)
        return count == 2 || count == 10
    }
}
