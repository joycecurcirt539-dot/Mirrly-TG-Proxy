package com.mirrly.tgproxy.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val htmlUrl: String,
    val releaseNotes: String,
    val isUpdateAvailable: Boolean
)

object UpdateChecker {
    private const val GITHUB_API_RELEASES_URL = "https://api.github.com/repos/joycecurcirt539-dot/Mirrly-TG-Proxy/releases/latest"
    const val CURRENT_VERSION_NAME = "1.0.2"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    suspend fun checkForUpdates(currentVersion: String = CURRENT_VERSION_NAME): Result<ReleaseInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(GITHUB_API_RELEASES_URL)
                    .header("User-Agent", "Mirrly-TG-Proxy-AndroidApp/$currentVersion")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""

                    val tagName = extractJsonValue(bodyString, "tag_name")
                    val htmlUrl = extractJsonValue(bodyString, "html_url").ifBlank {
                        "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
                    }
                    val bodyText = extractJsonValue(bodyString, "body")

                    val latestVerClean = cleanVersionString(tagName)
                    val currentVerClean = cleanVersionString(currentVersion)

                    val isUpdateAvailable = isVersionNewer(latestVerClean, currentVerClean)

                    Result.success(
                        ReleaseInfo(
                            tagName = tagName,
                            versionName = latestVerClean,
                            htmlUrl = htmlUrl,
                            releaseNotes = bodyText,
                            isUpdateAvailable = isUpdateAvailable
                        )
                    )
                } else {
                    Result.failure(Exception("HTTP Error ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun cleanVersionString(ver: String): String {
        var v = ver.trim()
        if (v.startsWith("v", ignoreCase = true)) {
            v = v.substring(1)
        }
        return v
    }

    private fun extractJsonValue(json: String, key: String): String {
        val regex = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
        val match = regex.find(json)
        return match?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\n", "\n") ?: ""
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        if (latest.isBlank()) return false
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

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
