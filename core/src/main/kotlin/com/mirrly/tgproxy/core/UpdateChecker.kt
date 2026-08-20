package com.mirrly.tgproxy.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
    private const val GITHUB_API_ALL_RELEASES_URL = "https://api.github.com/repos/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
    const val CURRENT_VERSION_NAME = "1.1.3"

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

                val etag = cachedEtag
                if (etag != null && etag.isNotBlank()) {
                    requestBuilder.header("If-None-Match", etag)
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
                            var universalApkUrl: String? = null
                            var releaseApkUrl: String? = null
                            var fallbackApkUrl: String? = null

                            if (json.has("assets")) {
                                val assetsArray = json.optJSONArray("assets")
                                if (assetsArray != null) {
                                    for (i in 0 until assetsArray.length()) {
                                        val asset = assetsArray.optJSONObject(i) ?: continue
                                        val assetName = asset.optString("name", "")
                                        val assetUrl = asset.optString("browser_download_url", "")

                                        if (assetName.endsWith(".apk", ignoreCase = true)) {
                                            if (assetName.contains("universal", ignoreCase = true) && !assetName.contains("debug", ignoreCase = true)) {
                                                universalApkUrl = assetUrl
                                                break
                                            } else if (assetName.contains("release", ignoreCase = true) && !assetName.contains("debug", ignoreCase = true) && releaseApkUrl == null) {
                                                releaseApkUrl = assetUrl
                                            } else if (!assetName.contains("debug", ignoreCase = true) && fallbackApkUrl == null) {
                                                fallbackApkUrl = assetUrl
                                            }
                                        }
                                    }
                                }
                            }
                            downloadUrl = universalApkUrl ?: releaseApkUrl ?: fallbackApkUrl

                            val hex64Matches = Regex("""(?i)\b[a-fA-F0-9]{64}\b""").findAll(bodyText).map { it.value.trim().uppercase() }
                            val colonMatches = Regex("""(?i)\b(?:[a-fA-F0-9]{2}:){31}[a-fA-F0-9]{2}\b""").findAll(bodyText).map { it.value.trim().uppercase() }
                            val expectedSha256List = (hex64Matches + colonMatches).distinct().toList()
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
            summary
        } else {
            "Доступна новая версия v$versionName со свежими улучшениями безопасности и скорости."
        }
    }

    fun cleanVersionString(ver: String): String {
        var v = ver.trim()
        val versionRegex = Regex("""\d+(\.\d+)+""")
        val match = versionRegex.find(v)
        if (match != null) {
            return match.value
        }
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
        if (cleanLatest.isBlank() || cleanCurrent.isBlank()) return false
        if (cleanLatest == cleanCurrent) return false

        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        if (latestParts.isEmpty() || currentParts.isEmpty()) return false

        val maxParts = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxParts) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    suspend fun fetchTotalDownloads(
        currentVersion: String = CURRENT_VERSION_NAME
    ): Result<Int> {
        return withContext(Dispatchers.IO) {
            var shieldsCount = 0
            var githubCount = 0

            // 1. Fetch from Shields.io API (1:1 sync with GitHub badge)
            try {
                val shieldsRequest = Request.Builder()
                    .url("https://img.shields.io/github/downloads/joycecurcirt539-dot/Mirrly-TG-Proxy/total.json")
                    .header("User-Agent", "Mirrly-TG-Proxy-AndroidApp/$currentVersion")
                    .build()

                client.newCall(shieldsRequest).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)
                        val msg = json.optString("message", "").replace("[^0-9]".toRegex(), "")
                        shieldsCount = msg.toIntOrNull() ?: json.optInt("value", 0)
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Shields API error: ${e.message}")
            }

            // 2. Fetch direct from GitHub REST API (all releases per_page=100)
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/joycecurcirt539-dot/Mirrly-TG-Proxy/releases?per_page=100")
                    .header("User-Agent", "Mirrly-TG-Proxy-AndroidApp/$currentVersion")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                val response = client.newCall(request).execute()
                response.use { resp ->
                    if (resp.isSuccessful) {
                        val bodyString = resp.body?.string() ?: "[]"
                        val jsonArray = JSONArray(bodyString)
                        var total = 0
                        for (i in 0 until jsonArray.length()) {
                            val releaseObj = jsonArray.optJSONObject(i) ?: continue
                            val assets = releaseObj.optJSONArray("assets") ?: continue
                            for (j in 0 until assets.length()) {
                                val asset = assets.optJSONObject(j) ?: continue
                                val name = asset.optString("name", "")
                                if (name.endsWith(".apk", ignoreCase = true)) {
                                    total += asset.optInt("download_count", 0)
                                }
                            }
                        }
                        githubCount = total
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "GitHub API error: ${e.message}")
            }

            val finalTotal = maxOf(shieldsCount, githubCount)
            AppLogger.i(TAG, "Fetched downloads: Shields=$shieldsCount, GitHubAPI=$githubCount => Final=$finalTotal")
            if (finalTotal > 0) {
                Result.success(finalTotal)
            } else {
                Result.failure(Exception("Could not fetch download stats"))
            }
        }
    }
}
