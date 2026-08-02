package com.mirrly.tgproxy.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mirrly.tgproxy.core.ReleaseInfo
import com.mirrly.tgproxy.core.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val PREFS_NAME = "mirrly_update_prefs"
    private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
    private const val KEY_CACHED_ETAG = "cached_etag"
    private const val WORK_NAME = "mirrly_daytime_update_checker"

    private val _updateState = MutableStateFlow<ReleaseInfo?>(null)
    val updateState: StateFlow<ReleaseInfo?> = _updateState.asStateFlow()

    suspend fun checkForUpdates(context: Context, notifyIfFound: Boolean = true): Result<ReleaseInfo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedEtag = prefs.getString(KEY_CACHED_ETAG, null)

        val result = UpdateChecker.checkForUpdates(cachedEtag = cachedEtag)
        result.onSuccess { info ->
            if (!info.etag.isNullOrBlank()) {
                prefs.edit().putString(KEY_CACHED_ETAG, info.etag).apply()
            }

            if (info.isUpdateAvailable) {
                _updateState.value = info
                val lastNotified = prefs.getString(KEY_LAST_NOTIFIED_VERSION, "")

                if (notifyIfFound && lastNotified != info.versionName) {
                    prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, info.versionName).apply()
                    NotificationHelper.showUpdateNotification(context, info)
                }
            } else if (!info.isNotModified) {
                _updateState.value = null
            }
        }
        return result
    }

    /**
     * Calculates the millisecond delay until the next daytime update check slot:
     * Slots: 08:00, 14:00, 20:00.
     */
    fun calculateDelayToNextCheckMillis(): Long {
        val now = LocalDateTime.now()
        val targetHours = listOf(8, 14, 20)

        for (hour in targetHours) {
            val target = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
            if (target.isAfter(now)) {
                return Duration.between(now, target).toMillis()
            }
        }

        val targetTomorrow = now.plusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0)
        return Duration.between(now, targetTomorrow).toMillis()
    }

    /**
     * Schedules the next daytime background check at 8:00, 14:00, or 20:00.
     */
    fun scheduleDaytimeCheck(context: Context) {
        try {
            val delayMillis = calculateDelayToNextCheckMillis()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        } catch (_: Exception) {
            // WorkManager initialization safety guard
        }
    }
}
