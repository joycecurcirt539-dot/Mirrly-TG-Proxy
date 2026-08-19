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

    fun onAppInit(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentAppVersion = com.mirrly.tgproxy.BuildConfig.VERSION_NAME
            val lastAppVersion = prefs.getString("last_installed_app_version", null)
            val lastNotifiedVersion = prefs.getString(KEY_LAST_NOTIFIED_VERSION, null)

            val isAppUpgraded = lastAppVersion != currentAppVersion
            val isNotifiedNotNewer = !lastNotifiedVersion.isNullOrBlank() && !UpdateChecker.isVersionNewer(lastNotifiedVersion, currentAppVersion)

            if (isAppUpgraded || isNotifiedNotNewer) {
                prefs.edit()
                    .putString("last_installed_app_version", currentAppVersion)
                    .remove(KEY_CACHED_ETAG)
                    .remove(KEY_CACHED_VERSION)
                    .remove(KEY_LAST_NOTIFIED_VERSION)
                    .remove(KEY_LAST_NOTIFIED_TIME)
                    .apply()
                NotificationHelper.cancelUpdateNotification(context)
            }

            // Clean up any stale downloaded APKs in cache
            val apkDir = java.io.File(context.cacheDir, "apks")
            if (apkDir.exists() && apkDir.isDirectory) {
                apkDir.listFiles()?.forEach { file ->
                    try {
                        file.delete()
                    } catch (_: Exception) {}
                }
            }
            UpdateDownloader.resetStatus()
        } catch (_: Exception) {}
    }

    suspend fun checkForUpdates(
        context: Context,
        notifyIfFound: Boolean = true,
        forceRefresh: Boolean = false
    ): Result<ReleaseInfo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentAppVersion = com.mirrly.tgproxy.BuildConfig.VERSION_NAME

        val lastAppVersion = prefs.getString("last_installed_app_version", null)
        val lastNotifiedVersion = prefs.getString(KEY_LAST_NOTIFIED_VERSION, null)

        val isAppUpgraded = lastAppVersion != currentAppVersion
        val isNotifiedNotNewer = !lastNotifiedVersion.isNullOrBlank() && !UpdateChecker.isVersionNewer(lastNotifiedVersion, currentAppVersion)

        if (isAppUpgraded || isNotifiedNotNewer) {
            prefs.edit()
                .putString("last_installed_app_version", currentAppVersion)
                .remove(KEY_CACHED_ETAG)
                .remove(KEY_CACHED_VERSION)
                .remove(KEY_LAST_NOTIFIED_VERSION)
                .remove(KEY_LAST_NOTIFIED_TIME)
                .apply()
            NotificationHelper.cancelUpdateNotification(context)
        }

        val cachedEtag = if (forceRefresh) null else prefs.getString(KEY_CACHED_ETAG, null)

        val result = UpdateChecker.checkForUpdates(
            currentVersion = currentAppVersion,
            cachedEtag = cachedEtag
        )
        result.onSuccess { info ->
            if (!info.etag.isNullOrBlank()) {
                prefs.edit().putString(KEY_CACHED_ETAG, info.etag).apply()
            }

            if (info.isNotModified) {
                val rawCachedVersion = prefs.getString(KEY_CACHED_VERSION, null)
                    ?: _updateState.value?.versionName

                if (!rawCachedVersion.isNullOrBlank()) {
                    val cleanCachedVersion = UpdateChecker.cleanVersionString(rawCachedVersion)
                    val isStillAvailable = UpdateChecker.isVersionNewer(
                        cleanCachedVersion,
                        currentAppVersion
                    )
                    val cachedHtmlUrl = prefs.getString(KEY_CACHED_HTML_URL, "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases") ?: ""
                    val cachedNotes = prefs.getString(KEY_CACHED_RELEASE_NOTES, "") ?: ""
                    val cachedDownloadUrl = prefs.getString(KEY_CACHED_DOWNLOAD_URL, null)
                    val cachedSha256 = prefs.getString(KEY_CACHED_EXPECTED_SHA256, null)
                    val cachedPreview = prefs.getString(KEY_CACHED_CHANGELOG_PREVIEW, "") ?: ""

                    val reconstructedInfo = ReleaseInfo(
                        tagName = cleanCachedVersion,
                        versionName = cleanCachedVersion,
                        htmlUrl = cachedHtmlUrl,
                        releaseNotes = cachedNotes,
                        isUpdateAvailable = isStillAvailable,
                        downloadUrl = cachedDownloadUrl,
                        etag = info.etag ?: cachedEtag,
                        isNotModified = true,
                        expectedSha256 = cachedSha256,
                        expectedSha256List = if (cachedSha256 != null) listOf(cachedSha256) else emptyList(),
                        changelogPreview = cachedPreview
                    )
                    _updateState.value = reconstructedInfo

                    if (!isStillAvailable) {
                        prefs.edit()
                            .remove(KEY_LAST_NOTIFIED_VERSION)
                            .remove(KEY_LAST_NOTIFIED_TIME)
                            .apply()
                        NotificationHelper.cancelUpdateNotification(context)
                    }
                } else {
                    if (!forceRefresh) {
                        return checkForUpdates(context, notifyIfFound = notifyIfFound, forceRefresh = true)
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

                _updateState.value = info

                if (info.isUpdateAvailable) {
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
                    prefs.edit()
                        .remove(KEY_LAST_NOTIFIED_VERSION)
                        .remove(KEY_LAST_NOTIFIED_TIME)
                        .apply()
                    NotificationHelper.cancelUpdateNotification(context)
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
