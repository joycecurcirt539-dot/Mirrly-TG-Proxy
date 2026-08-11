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
    private const val KEY_CACHED_VERSION = "cached_version"
    private const val KEY_CACHED_HTML_URL = "cached_html_url"
    private const val KEY_CACHED_RELEASE_NOTES = "cached_release_notes"
    private const val KEY_CACHED_DOWNLOAD_URL = "cached_download_url"
    private const val KEY_CACHED_EXPECTED_SHA256 = "cached_expected_sha256"
    private const val KEY_CACHED_CHANGELOG_PREVIEW = "cached_changelog_preview"
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

            if (info.isNotModified) {
                val cachedVersion = prefs.getString(KEY_CACHED_VERSION, null)
                    ?: _updateState.value?.versionName

                if (!cachedVersion.isNullOrBlank()) {
                    val isStillAvailable = UpdateChecker.isVersionNewer(
                        cachedVersion,
                        com.mirrly.tgproxy.BuildConfig.VERSION_NAME
                    )
                    if (isStillAvailable) {
                        val cachedHtmlUrl = prefs.getString(KEY_CACHED_HTML_URL, "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases") ?: ""
                        val cachedNotes = prefs.getString(KEY_CACHED_RELEASE_NOTES, "") ?: ""
                        val cachedDownloadUrl = prefs.getString(KEY_CACHED_DOWNLOAD_URL, null)
                        val cachedSha256 = prefs.getString(KEY_CACHED_EXPECTED_SHA256, null)
                        val cachedPreview = prefs.getString(KEY_CACHED_CHANGELOG_PREVIEW, "") ?: ""

                        val reconstructedInfo = ReleaseInfo(
                            tagName = cachedVersion,
                            versionName = cachedVersion,
                            htmlUrl = cachedHtmlUrl,
                            releaseNotes = cachedNotes,
                            isUpdateAvailable = true,
                            downloadUrl = cachedDownloadUrl,
                            etag = info.etag ?: cachedEtag,
                            isNotModified = true,
                            expectedSha256 = cachedSha256,
                            expectedSha256List = if (cachedSha256 != null) listOf(cachedSha256) else emptyList(),
                            changelogPreview = cachedPreview
                        )
                        _updateState.value = reconstructedInfo
                    } else {
                        _updateState.value = null
                        prefs.edit()
                            .remove(KEY_LAST_NOTIFIED_VERSION)
                            .remove(KEY_LAST_NOTIFIED_TIME)
                            .apply()
                    }
                } else {
                    if (!forceRefresh) {
                        return checkForUpdates(context, notifyIfFound = notifyIfFound, forceRefresh = true)
                    } else {
                        _updateState.value = null
                    }
                }
            } else {
                prefs.edit()
                    .putString(KEY_CACHED_VERSION, info.versionName)
                    .putString(KEY_CACHED_HTML_URL, info.htmlUrl)
                    .putString(KEY_CACHED_RELEASE_NOTES, info.releaseNotes)
                    .putString(KEY_CACHED_DOWNLOAD_URL, info.downloadUrl)
                    .putString(KEY_CACHED_EXPECTED_SHA256, info.expectedSha256)
                    .putString(KEY_CACHED_CHANGELOG_PREVIEW, info.changelogPreview)
                    .apply()

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
                } else {
                    _updateState.value = null
                    // Clear notification state if user is now on latest version or no update is available
                    prefs.edit()
                        .remove(KEY_LAST_NOTIFIED_VERSION)
                        .remove(KEY_LAST_NOTIFIED_TIME)
                        .apply()
                }
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
