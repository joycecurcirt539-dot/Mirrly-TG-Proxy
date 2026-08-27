package com.mirrly.tgproxy.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class ApkType(
    val id: String,
    val title: String,
    val shortName: String,
    val abiName: String,
    val description: String,
    val targetDevices: String
) {
    ARM64(
        id = "arm64-v8a",
        title = "ARM64",
        shortName = "arm64-v8a",
        abiName = "arm64-v8a",
        description = "Оптимизирована для 64-битных процессоров ARM. Обладает меньшим размером файла и максимальной энергоэффективностью.",
        targetDevices = "Большинство современных смартфонов и планшетов (64-битные ARM)"
    ),
    ARM_V7(
        id = "armeabi-v7a",
        title = "ARMv7",
        shortName = "armeabi-v7a",
        abiName = "armeabi-v7a",
        description = "Сборка для 32-битных процессоров ARM. Подходит для устаревших мобильных устройств.",
        targetDevices = "Старые Android-смартфоны и 32-битные ТВ-приставки"
    ),
    X86_64(
        id = "x86_64",
        title = "x86_64",
        shortName = "x86_64",
        abiName = "x86_64",
        description = "Для 64-битных эмуляторов Android на ПК и устройств с процессорами Intel/AMD.",
        targetDevices = "Эмуляторы (LDPlayer, BlueStacks, Nox, Android Studio) и ПК"
    ),
    X86(
        id = "x86",
        title = "x86",
        shortName = "x86",
        abiName = "x86",
        description = "Для 32-битных эмуляторов Android x86 и старых устройств с чипами Intel Atom.",
        targetDevices = "32-битные эмуляторы Android на ПК"
    ),
    UNIVERSAL(
        id = "universal",
        title = "Universal",
        shortName = "universal",
        abiName = "universal",
        description = "Содержит нативные библиотеки под все архитектуры (ARM64, ARMv7, x86, x86_64). Гарантированно подходит для любого устройства.",
        targetDevices = "Любые устройства (гарантированная совместимость)"
    );

    companion object {
        fun fromAssetName(name: String): ApkType {
            val lower = name.lowercase()
            return when {
                lower.contains("arm64") || lower.contains("arm64-v8a") -> ARM64
                lower.contains("armeabi-v7a") || lower.contains("armv7") -> ARM_V7
                lower.contains("x86_64") || lower.contains("x86-64") || lower.contains("amd64") -> X86_64
                lower.contains("x86") -> X86
                lower.contains("universal") -> UNIVERSAL
                else -> UNIVERSAL
            }
        }

        fun fromAbis(supportedAbis: List<String>): ApkType {
            for (abi in supportedAbis) {
                val lower = abi.lowercase().trim()
                when {
                    lower.contains("arm64") -> return ARM64
                    lower.contains("armeabi-v7a") || lower.contains("armv7") -> return ARM_V7
                    lower.contains("x86_64") || lower.contains("amd64") -> return X86_64
                    lower.contains("x86") -> return X86
                }
            }
            return UNIVERSAL
        }
    }
}

data class ReleaseApkAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val apkType: ApkType,
    val sha256: String? = null,
    val isRecommended: Boolean = false
)

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
    val changelogPreview: String = "",
    val isIgnored: Boolean = false,
    val apkAssets: List<ReleaseApkAsset> = emptyList()
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val GITHUB_API_RELEASES_URL = "https://api.github.com/repos/joycecurcirt539-dot/Mirrly-TG-Proxy/releases/latest"
    private const val GITHUB_API_ALL_RELEASES_URL = "https://api.github.com/repos/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
    const val CURRENT_VERSION_NAME = "1.1.8"

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(DohOkHttpDns.INSTANCE)
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

                            val rawAssets = mutableListOf<ReleaseApkAsset>()
                            var universalApkUrl: String? = null
                            var arm64ApkUrl: String? = null
                            var releaseApkUrl: String? = null
                            var fallbackApkUrl: String? = null

                            if (json.has("assets")) {
                                val assetsArray = json.optJSONArray("assets")
                                if (assetsArray != null) {
                                    for (i in 0 until assetsArray.length()) {
                                        val asset = assetsArray.optJSONObject(i) ?: continue
                                        val assetName = asset.optString("name", "")
                                        val assetUrl = asset.optString("browser_download_url", "")
                                        val assetSize = asset.optLong("size", 0L)

                                        if (assetName.endsWith(".apk", ignoreCase = true) && !assetName.contains("debug", ignoreCase = true)) {
                                            val type = ApkType.fromAssetName(assetName)
                                            val assetSha = extractSha256ForAsset(bodyText, assetName)

                                            val apkAsset = ReleaseApkAsset(
                                                name = assetName,
                                                downloadUrl = assetUrl,
                                                sizeBytes = assetSize,
                                                apkType = type,
                                                sha256 = assetSha
                                            )
                                            rawAssets.add(apkAsset)

                                            if (assetName.contains("universal", ignoreCase = true)) {
                                                universalApkUrl = assetUrl
                                            } else if (assetName.contains("arm64", ignoreCase = true)) {
                                                arm64ApkUrl = assetUrl
                                            } else if (assetName.contains("release", ignoreCase = true) && releaseApkUrl == null) {
                                                releaseApkUrl = assetUrl
                                            } else if (fallbackApkUrl == null) {
                                                fallbackApkUrl = assetUrl
                                            }
                                        }
                                    }
                                }
                            }

                            // Sort assets logically: ARM64 -> Universal -> ARMv7 -> x86_64 -> x86
                            val sortedAssets = rawAssets.sortedBy { asset ->
                                when (asset.apkType) {
                                    ApkType.ARM64 -> 0
                                    ApkType.UNIVERSAL -> 1
                                    ApkType.ARM_V7 -> 2
                                    ApkType.X86_64 -> 3
                                    ApkType.X86 -> 4
                                }
                            }

                            val downloadUrl: String? = universalApkUrl
                                ?: arm64ApkUrl
                                ?: releaseApkUrl
                                ?: fallbackApkUrl
                                ?: sortedAssets.firstOrNull()?.downloadUrl

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
                                "Check completed. Latest: v$latestVerClean, Current: v$currentVerClean, Update available: $isUpdateAvailable, Assets: ${sortedAssets.size}, SHA-256 count: ${expectedSha256List.size}"
                            )

                            Result.success(
                                ReleaseInfo(
                                    tagName = tagName,
                                    versionName = if (isUpdateAvailable) latestVerClean else currentVerClean,
                                    htmlUrl = htmlUrl,
                                    releaseNotes = bodyText,
                                    isUpdateAvailable = isUpdateAvailable,
                                    downloadUrl = if (isUpdateAvailable) downloadUrl else null,
                                    etag = responseEtag,
                                    isNotModified = false,
                                    expectedSha256 = expectedSha256,
                                    expectedSha256List = expectedSha256List,
                                    changelogPreview = preview,
                                    apkAssets = sortedAssets
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

    fun extractSha256ForAsset(bodyText: String, assetName: String): String? {
        if (bodyText.isBlank() || assetName.isBlank()) return null
        val escapedName = Regex.escape(assetName)
        // 1. Match full file name with SHA-256 (e.g. `* **app-arm64-v8a-release.apk SHA-256**: `A75EBE...``)
        val lineRegex = Regex("""(?i)(?:^|[\r\n])[^\r\n]*?$escapedName[^\r\n]*?`?([a-fA-F0-9]{64})`?""")
        val match = lineRegex.find(bodyText)
        if (match != null) {
            return match.groupValues[1].uppercase()
        }

        // 2. Match by APK type identifier if filename was slightly different
        val apkType = ApkType.fromAssetName(assetName)
        if (apkType != ApkType.UNIVERSAL) {
            val abiRegex = Regex("""(?i)(?:^|[\r\n])[^\r\n]*?${apkType.abiName}[^\r\n]*?`?([a-fA-F0-9]{64})`?""")
            val abiMatch = abiRegex.find(bodyText)
            if (abiMatch != null) {
                return abiMatch.groupValues[1].uppercase()
            }
        } else {
            val uniRegex = Regex("""(?i)(?:^|[\r\n])[^\r\n]*?universal[^\r\n]*?`?([a-fA-F0-9]{64})`?""")
            val uniMatch = uniRegex.find(bodyText)
            if (uniMatch != null) {
                return uniMatch.groupValues[1].uppercase()
            }
        }
        return null
    }

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
