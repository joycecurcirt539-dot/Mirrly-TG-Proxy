package com.mirrly.tgproxy.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mirrly.tgproxy.core.ReleaseInfo
import com.mirrly.tgproxy.core.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val PREFS_NAME = "mirrly_update_prefs"
    private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
    private const val KEY_LAST_NOTIFIED_TIME = "last_notified_time_ms"
    private const val KEY_CACHED_ETAG = "cached_etag"
    private const val WORK_NAME = "mirrly_periodic_update_checker"

    // Re-notify reminder interval: 4 hours (14,400,000 ms)
    private const val NOTIFICATION_REMINDER_INTERVAL_MS = 4 * 60 * 60 * 1000L

    private val _updateState = MutableStateFlow<ReleaseInfo?>(null)
    val updateState: StateFlow<ReleaseInfo?> = _updateState.asStateFlow()

    suspend fun checkForUpdates(
        context: Context,
        notifyIfFound: Boolean = true,
        forceRefresh: Boolean = false
    ): Result<ReleaseInfo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedEtag = if (forceRefresh) null else prefs.getString(KEY_CACHED_ETAG, null)

        val result = UpdateChecker.checkForUpdates(
            currentVersion = com.mirrly.tgproxy.BuildConfig.VERSION_NAME,
            cachedEtag = cachedEtag
        )
        result.onSuccess { info ->
            if (!info.etag.isNullOrBlank()) {
                prefs.edit().putString(KEY_CACHED_ETAG, info.etag).apply()
            }

            if (info.isUpdateAvailable) {
                _updateState.value = info

                val lastNotifiedVersion = prefs.getString(KEY_LAST_NOTIFIED_VERSION, "")
                val lastNotifiedTimeMs = prefs.getLong(KEY_LAST_NOTIFIED_TIME, 0L)
                val now = System.currentTimeMillis()

                val isNewVersion = lastNotifiedVersion != info.versionName
                val isReminderDue = (now - lastNotifiedTimeMs) >= NOTIFICATION_REMINDER_INTERVAL_MS

                if (notifyIfFound && (isNewVersion || isReminderDue)) {
                    prefs.edit()
                        .putString(KEY_LAST_NOTIFIED_VERSION, info.versionName)
                        .putLong(KEY_LAST_NOTIFIED_TIME, now)
                        .apply()
                    NotificationHelper.showUpdateNotification(context, info)
                }
            } else if (!info.isNotModified) {
                _updateState.value = null
                // Clear notification state if user is now on latest version
                prefs.edit()
                    .remove(KEY_LAST_NOTIFIED_VERSION)
                    .remove(KEY_LAST_NOTIFIED_TIME)
                    .apply()
            }
        }
        return result
    }

    /**
     * Schedules 24/7 periodic background update checks every 2 hours via WorkManager.
     */
    fun scheduleDaytimeCheck(context: Context) {
        schedulePeriodicCheck(context)
    }

    fun schedulePeriodicCheck(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(2, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        } catch (_: Exception) {
            // WorkManager initialization safety guard
        }
    }
}
