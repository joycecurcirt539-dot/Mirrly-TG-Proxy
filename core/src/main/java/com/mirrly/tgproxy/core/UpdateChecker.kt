package com.mirrly.tgproxy.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val htmlUrl: String,
    val releaseNotes: String,
    val isUpdateAvailable: Boolean,
    val downloadUrl: String? = null,
    val etag: String? = null,
    val isNotModified: Boolean = false,
    val expectedSha256: String? = null,
    val expectedSha256List: List<String> = emptyList(),
    val changelogPreview: String = ""
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val GITHUB_API_RELEASES_URL = "https://api.github.com/repos/joycecurcirt539-dot/Mirrly-TG-Proxy/releases/latest"
    const val CURRENT_VERSION_NAME = "1.0.7"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    suspend fun checkForUpdates(
        currentVersion: String = CURRENT_VERSION_NAME,
        cachedEtag: String? = null
    ): Result<ReleaseInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val requestBuilder = Request.Builder()
                    .url(GITHUB_API_RELEASES_URL)
                    .header("User-Agent", "Mirrly-TG-Proxy-AndroidApp/$currentVersion")
                    .header("Accept", "application/vnd.github.v3+json")

                if (!cachedEtag.isNullOrBlank()) {
                    requestBuilder.header("If-None-Match", cachedEtag!!)
                }

                val request = requestBuilder.build()
                val response = client.newCall(request).execute()

                response.use { resp ->
                    when (resp.code) {
                        304 -> {
                            AppLogger.i(TAG, "Release info not modified (HTTP 304). Already on latest state.")
                            Result.success(
                                ReleaseInfo(
                                    tagName = "",
                                    versionName = currentVersion,
                                    htmlUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases",
                                    releaseNotes = "",
                                    isUpdateAvailable = false,
                                    downloadUrl = null,
                                    etag = cachedEtag,
                                    isNotModified = true
                                )
                            )
                        }
                        403 -> {
                            AppLogger.w(TAG, "GitHub API rate limit exceeded (HTTP 403). Try again later.")
                            Result.failure(Exception("GitHub API limit exceeded (HTTP 403)"))
                        }
                        200 -> {
                            val bodyString = resp.body?.string() ?: ""
                            val responseEtag = resp.header("ETag") ?: resp.header("etag")

                            val json = JSONObject(bodyString)
                            val tagName = json.optString("tag_name", "")
                            val htmlUrl = json.optString("html_url", "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases")
                            val bodyText = json.optString("body", "")

                            var downloadUrl: String? = null
                            if (json.has("assets")) {
                                val assetsArray = json.optJSONArray("assets")
                                if (assetsArray != null) {
                                    for (i in 0 until assetsArray.length()) {
                                        val asset = assetsArray.optJSONObject(i) ?: continue
                                        val assetName = asset.optString("name", "")
                                        val assetUrl = asset.optString("browser_download_url", "")
                                        if (assetName.endsWith("release.apk", ignoreCase = true)) {
                                            downloadUrl = assetUrl
                                            break
                                        } else if (assetName.endsWith(".apk", ignoreCase = true) && downloadUrl == null) {
                                            downloadUrl = assetUrl
                                        } else if (downloadUrl == null && assetUrl.isNotBlank()) {
                                            downloadUrl = assetUrl
                                        }
                                    }
                                }
                            }

                            val sha256Regex = Regex("(?i)sha-?256[:\\s]+([a-fA-F0-9:-]{32,95})")
                            val shaMatches = sha256Regex.findAll(bodyText).toList()
                            val expectedSha256List = shaMatches.map { it.groupValues[1].trim() }
                            val expectedSha256 = expectedSha256List.firstOrNull()

                            val latestVerClean = cleanVersionString(tagName)
                            val currentVerClean = cleanVersionString(currentVersion)
                            val isUpdateAvailable = isVersionNewer(latestVerClean, currentVerClean)
                            val preview = extractChangelogPreview(bodyText, latestVerClean)

                            AppLogger.i(
                                TAG,
                                "Check completed. Latest: v$latestVerClean, Current: v$currentVerClean, Update available: $isUpdateAvailable, SHA-256 count: ${expectedSha256List.size}"
                            )

                            Result.success(
                                ReleaseInfo(
                                    tagName = tagName,
                                    versionName = latestVerClean,
                                    htmlUrl = htmlUrl,
                                    releaseNotes = bodyText,
                                    isUpdateAvailable = isUpdateAvailable,
                                    downloadUrl = downloadUrl,
                                    etag = responseEtag,
                                    isNotModified = false,
                                    expectedSha256 = expectedSha256,
                                    expectedSha256List = expectedSha256List,
                                    changelogPreview = preview
                                )
                            )
                        }
                        else -> {
                            AppLogger.w(TAG, "GitHub API returned HTTP status ${resp.code}")
                            Result.failure(Exception("HTTP Error ${resp.code}"))
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Network error during update check: ${e.message ?: e.javaClass.simpleName}")
                Result.failure(e)
            }
        }
    }

    private fun String?.isNullOrBlank(): Boolean = this == null || this.trim().isEmpty()

    fun extractChangelogPreview(bodyText: String, versionName: String): String {
        if (bodyText.isBlank()) return "Доступна новая версия v$versionName. Нажмите для обновления."
        val lines = bodyText.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                        !line.startsWith("#") &&
                        !line.startsWith("---") &&
                        !line.contains("keytool", ignoreCase = true) &&
                        !line.contains("apksigner", ignoreCase = true) &&
                        !line.contains("SHA256", ignoreCase = true)
            }

        val summary = lines.take(2).joinToString(" ").take(160).trim()
        return if (summary.isNotBlank()) {
            "В v$versionName: $summary"
        } else {
            "Доступна новая версия v$versionName со свежими улучшениями безопасности и скорости."
        }
    }

    fun cleanVersionString(ver: String): String {
        var v = ver.trim()
        if (v.startsWith("v", ignoreCase = true)) {
            v = v.substring(1).trim()
        }
        val dashIdx = v.indexOf('-')
        if (dashIdx != -1) {
            v = v.substring(0, dashIdx)
        }
        return v
    }

    fun isVersionNewer(latest: String, current: String): Boolean {
        val cleanLatest = cleanVersionString(latest)
        val cleanCurrent = cleanVersionString(current)
        if (cleanLatest.isBlank()) return false

        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

        val maxParts = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxParts) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
