package com.mirrly.tgproxy.service

import android.content.Context
import androidx.work.WorkManager
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.core.ApkType
import com.mirrly.tgproxy.core.ReleaseApkAsset
import com.mirrly.tgproxy.core.ReleaseInfo
import com.mirrly.tgproxy.core.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
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
    private const val KEY_CACHED_EXPECTED_SHA256_LIST = "cached_expected_sha256_list"
    private const val KEY_CACHED_CHANGELOG_PREVIEW = "cached_changelog_preview"
    private const val KEY_CACHED_APK_ASSETS = "cached_apk_assets"
    private const val KEY_LAST_CHECK_TIMESTAMP = "last_update_check_timestamp"
    private const val WORK_NAME = "mirrly_periodic_update_checker"

    // Automatic update check cooldown: 24 hours (eliminates redundant cold-start network calls)
    private const val AUTO_CHECK_COOLDOWN_MS = 24 * 60 * 60 * 1000L

    // Re-notify reminder interval: 4 hours (14,400,000 ms)
    private const val NOTIFICATION_REMINDER_INTERVAL_MS = 4 * 60 * 60 * 1000L

    private val _updateState = MutableStateFlow<ReleaseInfo?>(null)
    val updateState: StateFlow<ReleaseInfo?> = _updateState.asStateFlow()

    private fun clearUpdateCache(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .remove(KEY_CACHED_ETAG)
            .remove(KEY_CACHED_VERSION)
            .remove(KEY_CACHED_HTML_URL)
            .remove(KEY_CACHED_RELEASE_NOTES)
            .remove(KEY_CACHED_DOWNLOAD_URL)
            .remove(KEY_CACHED_EXPECTED_SHA256)
            .remove(KEY_CACHED_EXPECTED_SHA256_LIST)
            .remove(KEY_CACHED_CHANGELOG_PREVIEW)
            .remove(KEY_CACHED_APK_ASSETS)
            .remove(KEY_LAST_NOTIFIED_VERSION)
            .remove(KEY_LAST_NOTIFIED_TIME)
            .apply()
    }

    fun onAppInit(context: Context) {
        try {
            // Cancel any obsolete background WorkManager jobs (0 periodic wakeups)
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (_: Exception) {}

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentAppVersion = com.mirrly.tgproxy.BuildConfig.VERSION_NAME
            val lastAppVersion = prefs.getString("last_installed_app_version", null)
            val lastNotifiedVersion = prefs.getString(KEY_LAST_NOTIFIED_VERSION, null)
            val cachedVersion = prefs.getString(KEY_CACHED_VERSION, null)

            val isAppUpgraded = UpdateChecker.cleanVersionString(lastAppVersion ?: "") != UpdateChecker.cleanVersionString(currentAppVersion)
            val isNotifiedNotNewer = !lastNotifiedVersion.isNullOrBlank() && !UpdateChecker.isVersionNewer(lastNotifiedVersion, currentAppVersion)
            val isCachedNotNewer = !cachedVersion.isNullOrBlank() && !UpdateChecker.isVersionNewer(cachedVersion, currentAppVersion)

            if (isAppUpgraded || isNotifiedNotNewer || isCachedNotNewer) {
                clearUpdateCache(prefs)
                prefs.edit().putString("last_installed_app_version", currentAppVersion).apply()
                try {
                    MirrlyApplication.instance.prefsManager.clearIgnoredUpdateVersion()
                } catch (_: Exception) {}
                NotificationHelper.cancelUpdateNotification(context)
                _updateState.value = null
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

            // Restore cached release state immediately without network requests
            if (_updateState.value == null) {
                val cached = restoreCachedReleaseInfo(prefs, currentAppVersion)
                if (cached != null && cached.isUpdateAvailable) {
                    _updateState.value = cached
                }
            }
        } catch (_: Exception) {}
    }

    private fun restoreCachedReleaseInfo(
        prefs: android.content.SharedPreferences,
        currentAppVersion: String
    ): ReleaseInfo? {
        val rawCachedVersion = prefs.getString(KEY_CACHED_VERSION, null) ?: return null
        val cleanCachedVersion = UpdateChecker.cleanVersionString(rawCachedVersion)
        val isStillAvailable = UpdateChecker.isVersionNewer(cleanCachedVersion, currentAppVersion)
        val isCachedIgnored = try {
            MirrlyApplication.instance.prefsManager.isUpdateVersionIgnored(cleanCachedVersion)
        } catch (_: Exception) { false }

        val cachedHtmlUrl = prefs.getString(KEY_CACHED_HTML_URL, "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases") ?: ""
        val cachedNotes = prefs.getString(KEY_CACHED_RELEASE_NOTES, "") ?: ""
        val cachedDownloadUrl = prefs.getString(KEY_CACHED_DOWNLOAD_URL, null)
        val cachedSha256 = prefs.getString(KEY_CACHED_EXPECTED_SHA256, null)
        val cachedShaListJson = prefs.getString(KEY_CACHED_EXPECTED_SHA256_LIST, null)
        val cachedPreview = prefs.getString(KEY_CACHED_CHANGELOG_PREVIEW, "") ?: ""
        val cachedAssetsJson = prefs.getString(KEY_CACHED_APK_ASSETS, null)
        val cachedEtag = prefs.getString(KEY_CACHED_ETAG, null)

        val restoredShaList = mutableListOf<String>()
        if (!cachedShaListJson.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(cachedShaListJson)
                for (i in 0 until arr.length()) {
                    val sha = arr.optString(i, "")
                    if (sha.isNotBlank()) {
                        restoredShaList.add(sha)
                    }
                }
            }
        }
        if (restoredShaList.isEmpty() && cachedSha256 != null) {
            restoredShaList.add(cachedSha256)
        }

        val restoredAssets = mutableListOf<ReleaseApkAsset>()
        if (!cachedAssetsJson.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(cachedAssetsJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val typeName = obj.optString("type", "UNIVERSAL")
                    val apkType = runCatching { ApkType.valueOf(typeName) }.getOrDefault(ApkType.UNIVERSAL)
                    restoredAssets.add(
                        ReleaseApkAsset(
                            name = obj.optString("name", ""),
                            downloadUrl = obj.optString("url", ""),
                            sizeBytes = obj.optLong("size", 0L),
                            apkType = apkType,
                            sha256 = obj.optString("sha256", "").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }

        return ReleaseInfo(
            tagName = if (isStillAvailable) cleanCachedVersion else currentAppVersion,
            versionName = if (isStillAvailable) cleanCachedVersion else currentAppVersion,
            htmlUrl = cachedHtmlUrl,
            releaseNotes = cachedNotes,
            isUpdateAvailable = isStillAvailable,
            downloadUrl = if (isStillAvailable) cachedDownloadUrl else null,
            etag = cachedEtag,
            isNotModified = true,
            expectedSha256 = if (isStillAvailable) cachedSha256 else null,
            expectedSha256List = if (isStillAvailable) restoredShaList else emptyList(),
            changelogPreview = cachedPreview,
            isIgnored = isCachedIgnored,
            apkAssets = restoredAssets
        )
    }

    fun ignoreVersion(context: Context, version: String) {
        try {
            MirrlyApplication.instance.prefsManager.setIgnoredUpdateVersion(version)
            val current = _updateState.value
            if (current != null) {
                _updateState.value = current.copy(isIgnored = true)
            }
            NotificationHelper.cancelUpdateNotification(context)
        } catch (_: Exception) {}
    }

    fun unignoreVersion(context: Context, version: String) {
        try {
            MirrlyApplication.instance.prefsManager.clearIgnoredUpdateVersion()
            val current = _updateState.value
            if (current != null) {
                _updateState.value = current.copy(isIgnored = false)
            }
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
        val cachedVersion = prefs.getString(KEY_CACHED_VERSION, null)

        val isAppUpgraded = UpdateChecker.cleanVersionString(lastAppVersion ?: "") != UpdateChecker.cleanVersionString(currentAppVersion)
        val isNotifiedNotNewer = !lastNotifiedVersion.isNullOrBlank() && !UpdateChecker.isVersionNewer(lastNotifiedVersion, currentAppVersion)
        val isCachedNotNewer = !cachedVersion.isNullOrBlank() && !UpdateChecker.isVersionNewer(cachedVersion, currentAppVersion)

        if (isAppUpgraded || isNotifiedNotNewer || isCachedNotNewer) {
            clearUpdateCache(prefs)
            prefs.edit().putString("last_installed_app_version", currentAppVersion).apply()
            try {
                MirrlyApplication.instance.prefsManager.clearIgnoredUpdateVersion()
            } catch (_: Exception) {}
            NotificationHelper.cancelUpdateNotification(context)
            _updateState.value = null
        }

        // Automatic check rate-limiter: eliminates parasitic network calls on repeated app launches
        if (!forceRefresh) {
            val lastCheckMs = prefs.getLong(KEY_LAST_CHECK_TIMESTAMP, 0L)
            val elapsed = System.currentTimeMillis() - lastCheckMs
            if (elapsed in 0 until AUTO_CHECK_COOLDOWN_MS) {
                val existing = _updateState.value ?: restoreCachedReleaseInfo(prefs, currentAppVersion)
                if (existing != null) {
                    _updateState.value = existing
                    return Result.success(existing)
                } else if (lastCheckMs > 0L) {
                    val upToDate = ReleaseInfo(
                        tagName = currentAppVersion,
                        versionName = currentAppVersion,
                        htmlUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases",
                        releaseNotes = "",
                        isUpdateAvailable = false,
                        isNotModified = true
                    )
                    return Result.success(upToDate)
                }
            }
        }

        val cachedEtag = if (forceRefresh) null else prefs.getString(KEY_CACHED_ETAG, null)

        val result = UpdateChecker.checkForUpdates(
            currentVersion = currentAppVersion,
            cachedEtag = cachedEtag
        )
        result.onSuccess { rawInfo ->
            prefs.edit().putLong(KEY_LAST_CHECK_TIMESTAMP, System.currentTimeMillis()).apply()

            val cleanVer = UpdateChecker.cleanVersionString(rawInfo.versionName)
            val isIgnored = try {
                MirrlyApplication.instance.prefsManager.isUpdateVersionIgnored(cleanVer)
            } catch (_: Exception) { false }

            val isActuallyNewer = UpdateChecker.isVersionNewer(cleanVer, currentAppVersion)
            val isUpdateAvailable = rawInfo.isUpdateAvailable && isActuallyNewer
            val info = rawInfo.copy(isUpdateAvailable = isUpdateAvailable, isIgnored = isIgnored)

            if (!info.etag.isNullOrBlank()) {
                prefs.edit().putString(KEY_CACHED_ETAG, info.etag).apply()
            }

            if (info.isNotModified) {
                val reconstructedInfo = restoreCachedReleaseInfo(prefs, currentAppVersion)
                if (reconstructedInfo != null) {
                    _updateState.value = reconstructedInfo
                    if (!reconstructedInfo.isUpdateAvailable || reconstructedInfo.isIgnored) {
                        clearUpdateCache(prefs)
                        if (!reconstructedInfo.etag.isNullOrBlank()) {
                            prefs.edit().putString(KEY_CACHED_ETAG, reconstructedInfo.etag).apply()
                        }
                        NotificationHelper.cancelUpdateNotification(context)
                    }
                } else {
                    _updateState.value = null
                    clearUpdateCache(prefs)
                    if (!forceRefresh) {
                        return checkForUpdates(context, notifyIfFound = notifyIfFound, forceRefresh = true)
                    }
                }
            } else {
                if (info.isUpdateAvailable && !info.isIgnored) {
                    val assetsJson = JSONArray().apply {
                        info.apkAssets.forEach { asset ->
                            put(JSONObject().apply {
                                put("name", asset.name)
                                put("url", asset.downloadUrl)
                                put("size", asset.sizeBytes)
                                put("type", asset.apkType.name)
                                put("sha256", asset.sha256 ?: "")
                            })
                        }
                    }.toString()

                    val shaListJson = JSONArray().apply {
                        info.expectedSha256List.forEach { put(it) }
                    }.toString()

                    prefs.edit()
                        .putString(KEY_CACHED_VERSION, info.versionName)
                        .putString(KEY_CACHED_HTML_URL, info.htmlUrl)
                        .putString(KEY_CACHED_RELEASE_NOTES, info.releaseNotes)
                        .putString(KEY_CACHED_DOWNLOAD_URL, info.downloadUrl)
                        .putString(KEY_CACHED_EXPECTED_SHA256, info.expectedSha256)
                        .putString(KEY_CACHED_EXPECTED_SHA256_LIST, shaListJson)
                        .putString(KEY_CACHED_CHANGELOG_PREVIEW, info.changelogPreview)
                        .putString(KEY_CACHED_APK_ASSETS, assetsJson)
                        .apply()

                    _updateState.value = info

                    val lastNotifiedVersion = prefs.getString(KEY_LAST_NOTIFIED_VERSION, "")
                    val lastNotifiedTimeMs = prefs.getLong(KEY_LAST_NOTIFIED_TIME, 0L)
                    val now = System.currentTimeMillis()

                    val isNewVersion = UpdateChecker.cleanVersionString(lastNotifiedVersion ?: "") != UpdateChecker.cleanVersionString(info.versionName)
                    val isReminderDue = (now - lastNotifiedTimeMs) >= NOTIFICATION_REMINDER_INTERVAL_MS

                    if (notifyIfFound && (isNewVersion || isReminderDue)) {
                        prefs.edit()
                            .putString(KEY_LAST_NOTIFIED_VERSION, info.versionName)
                            .putLong(KEY_LAST_NOTIFIED_TIME, now)
                            .apply()
                        NotificationHelper.showUpdateNotification(context, info)
                    }
                } else {
                    clearUpdateCache(prefs)
                    if (!info.etag.isNullOrBlank()) {
                        prefs.edit().putString(KEY_CACHED_ETAG, info.etag).apply()
                    }
                    _updateState.value = info.copy(isUpdateAvailable = false)
                    NotificationHelper.cancelUpdateNotification(context)
                }
            }
        }
        return result
    }

    /**
     * Cancels any previously scheduled background WorkManager update tasks.
     * Background periodic polling is disabled to eliminate parasitic network and battery overhead.
     */
    fun cancelPeriodicCheck(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (_: Exception) {}
    }
}
