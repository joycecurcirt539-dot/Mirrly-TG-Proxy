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

object DonationManager {
    private const val PREFS_NAME = "mirrly_donation_prefs"
    private const val KEY_DISMISSED_FOREVER = "donation_dismissed_forever"
    private const val KEY_POSTPONE_UNTIL_MS = "donation_postpone_until_ms"
    private const val KEY_SUCCESSFUL_CONNECTIONS = "donation_successful_connections"
    private const val THREE_DAYS_MS = 3 * 24 * 60 * 60 * 1000L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isDismissedForever(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DISMISSED_FOREVER, false)
    }

    fun setDismissedForever(context: Context, dismissed: Boolean = true) {
        getPrefs(context).edit().putBoolean(KEY_DISMISSED_FOREVER, dismissed).apply()
    }

    fun postpone3Days(context: Context) {
        val postponeTime = System.currentTimeMillis() + THREE_DAYS_MS
        getPrefs(context).edit().putLong(KEY_POSTPONE_UNTIL_MS, postponeTime).apply()
    }

    fun getPostponeUntilMs(context: Context): Long {
        return getPrefs(context).getLong(KEY_POSTPONE_UNTIL_MS, 0L)
    }

    fun recordSuccessfulConnection(context: Context) {
        val prefs = getPrefs(context)
        val currentCount = prefs.getInt(KEY_SUCCESSFUL_CONNECTIONS, 0) + 1
        prefs.edit().putInt(KEY_SUCCESSFUL_CONNECTIONS, currentCount).apply()
    }

    fun getSuccessfulConnections(context: Context): Int {
        return getPrefs(context).getInt(KEY_SUCCESSFUL_CONNECTIONS, 0)
    }

    /**
     * Checks whether developer support banner should be displayed:
     * - Only after the user has tested proxy connections at least 2 times.
     * - Does not show if permanently dismissed.
     * - Does not show if currently snoozed / postponed.
     */
    fun shouldShowDonationBanner(context: Context): Boolean {
        if (isDismissedForever(context)) return false
        val postponeUntil = getPostponeUntilMs(context)
        if (System.currentTimeMillis() < postponeUntil) return false
        val connections = getSuccessfulConnections(context)
        return connections >= 2
    }
}
