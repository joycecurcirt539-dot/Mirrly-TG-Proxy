package com.mirrly.tgproxy.service

import android.content.Context
import android.content.SharedPreferences

object LaunchCountManager {
    private const val PREFS_NAME = "mirrly_launch_prefs"
    private const val KEY_LAUNCH_COUNT = "launch_count"
    private const val KEY_STAR_DISMISSED = "github_star_dismissed"
    private const val KEY_LAST_SECURITY_NOTICE_TIME = "last_security_notice_time_ms"
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

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

    fun shouldShowStarDialog(context: Context): Boolean {
        if (isStarDismissed(context)) return false
        val count = getLaunchCount(context)
        return count == 2 || count == 10
    }

    fun shouldShowDailySecurityNotice(context: Context): Boolean {
        val lastTime = getPrefs(context).getLong(KEY_LAST_SECURITY_NOTICE_TIME, 0L)
        val now = System.currentTimeMillis()
        return (now - lastTime) >= ONE_DAY_MS
    }

    fun onDailySecurityNoticeDismissed(context: Context) {
        getPrefs(context).edit().putLong(KEY_LAST_SECURITY_NOTICE_TIME, System.currentTimeMillis()).apply()
    }
}
