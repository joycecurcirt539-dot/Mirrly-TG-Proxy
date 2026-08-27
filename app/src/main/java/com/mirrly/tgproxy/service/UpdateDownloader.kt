package com.mirrly.tgproxy.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.DohOkHttpDns
import com.mirrly.tgproxy.core.UpdateChecker
import com.mirrly.tgproxy.util.SignatureStatus
import com.mirrly.tgproxy.util.SignatureVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class DownloadStatus {
    object Idle : DownloadStatus()
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long = 0L,
        val etaSeconds: Long = -1L
    ) : DownloadStatus()
    object Verifying : DownloadStatus()
    data class ReadyToInstall(val file: File) : DownloadStatus()
    data class Error(val message: String) : DownloadStatus()
}

object UpdateDownloader {

    private const val TAG = "UpdateDownloader"

    private val _status = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val status: StateFlow<DownloadStatus> = _status.asStateFlow()

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(DohOkHttpDns.INSTANCE)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private const val MAX_APK_SIZE_BYTES = 100L * 1024L * 1024L // 100 MB Limit

    private val lock = Any()
    @Volatile
    private var isCancelled = false
    private var activeCall: okhttp3.Call? = null
    private var currentDownloadFile: File? = null

    fun cancelDownload() {
        synchronized(lock) {
            isCancelled = true
            try {
                activeCall?.cancel()
            } catch (_: Exception) {}
            activeCall = null

            try {
                currentDownloadFile?.delete()
            } catch (_: Exception) {}
            currentDownloadFile = null

            _status.value = DownloadStatus.Idle
        }
        AppLogger.i(TAG, "Download cancelled by user.")
    }

    fun resetStatus() {
        cancelDownload()
    }

    suspend fun downloadAndVerifyApk(
        context: Context,
        downloadUrl: String,
        expectedSha256List: List<String>,
        versionName: String,
        fileName: String? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            var targetFile: File? = null
            synchronized(lock) {
                isCancelled = false
                activeCall = null
                currentDownloadFile = null
            }

            try {
                val apkDir = File(context.cacheDir, "apks").apply {
                    if (!exists()) mkdirs()
                }

                val safeFileName = if (!fileName.isNullOrBlank() && fileName.endsWith(".apk", ignoreCase = true)) {
                    fileName
                } else {
                    "mirrly_v${versionName.replace(".", "_")}.apk"
                }
                val destFile = File(apkDir, safeFileName)
                targetFile = destFile

                // ── 1. SMART CACHE VERIFICATION: Instant Install without downloading if valid ──
                if (destFile.exists() && destFile.length() > 0 && expectedSha256List.isNotEmpty()) {
                    _status.value = DownloadStatus.Verifying
                    val cachedSha = calculateSha256(destFile)
                    val normalizedCalculated = cachedSha.lowercase().replace(":", "").replace(" ", "")
                    var cacheMatches = expectedSha256List.any { expected ->
                        val normalizedExpected = expected.lowercase().replace(":", "").replace(" ", "")
                        normalizedExpected == normalizedCalculated
                    }
                    var cacheShaList = expectedSha256List

                    if (!cacheMatches) {
                        // Fallback check against fresh release notes before deleting cached file
                        val freshReleaseResult = UpdateChecker.checkForUpdates(
                            currentVersion = versionName,
                            cachedEtag = null
                        )
                        val freshInfo = freshReleaseResult.getOrNull()
                        val freshList = freshInfo?.expectedSha256List.orEmpty()
                        if (freshList.any { expected ->
                            val normalizedExpected = expected.lowercase().replace(":", "").replace(" ", "")
                            normalizedExpected == normalizedCalculated
                        }) {
                            cacheMatches = true
                            cacheShaList = freshList
                        }
                    }

                    if (cacheMatches) {
                        val signatureStatus = SignatureVerifier.verifyApkFile(context, destFile, cacheShaList)
                        val isCurrentDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                        val isAccepted = signatureStatus == SignatureStatus.OFFICIAL_RELEASE || (signatureStatus == SignatureStatus.DEBUG_BUILD && isCurrentDebug)
                        if (isAccepted) {
                            AppLogger.i(TAG, "Smart Cache HIT for $safeFileName: valid SHA-256 and signature. Instant install.")
                            _status.value = DownloadStatus.ReadyToInstall(destFile)
                            return@withContext true
                        }
                    }
                    // Stale or corrupted cache file -> clean up before re-downloading
                    destFile.delete()
                } else {
                    // Cleanup other stale apk files
                    apkDir.listFiles()?.filter { it.name != safeFileName }?.forEach { runCatching { it.delete() } }
                }

                _status.value = DownloadStatus.Downloading(0f, 0L, 0L)

                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "Mirrly-TG-Proxy-Downloader/$versionName")
                    .build()

                val call = client.newCall(request)
                synchronized(lock) {
                    if (isCancelled) {
                        destFile.delete()
                        _status.value = DownloadStatus.Idle
                        return@withContext false
                    }
                    activeCall = call
                    currentDownloadFile = destFile
                }

                val response = call.execute()
                if (!response.isSuccessful) {
                    if (isCancelled) {
                        destFile.delete()
                        _status.value = DownloadStatus.Idle
                        return@withContext false
                    }
                    _status.value = DownloadStatus.Error("Ошибка загрузки HTTP ${response.code}")
                    return@withContext false
                }

                val body = response.body
                if (body == null) {
                    if (isCancelled) {
                        destFile.delete()
                        _status.value = DownloadStatus.Idle
                        return@withContext false
                    }
                    _status.value = DownloadStatus.Error("Пустой ответ сервера")
                    return@withContext false
                }

                val totalBytes = body.contentLength()
                if (totalBytes > MAX_APK_SIZE_BYTES) {
                    _status.value = DownloadStatus.Error("Превышен допустимый размер файла обновления (макс. 100 МБ)")
                    return@withContext false
                }

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(destFile)

                val buffer = ByteArray(16 * 1024)
                var bytesRead: Int
                var downloadedBytes = 0L

                var lastEmitTime = 0L
                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesSinceLastCalc = 0L
                var currentSpeed = 0L
                var emaSpeed = 0L

                try {
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled) {
                            outputStream.close()
                            inputStream.close()
                            destFile.delete()
                            _status.value = DownloadStatus.Idle
                            return@withContext false
                        }

                        downloadedBytes += bytesRead
                        bytesSinceLastCalc += bytesRead
                        if (downloadedBytes > MAX_APK_SIZE_BYTES) {
                            outputStream.close()
                            inputStream.close()
                            destFile.delete()
                            _status.value = DownloadStatus.Error("Превышен лимит размера файла (>100 МБ). Загрузка остановлена.")
                            return@withContext false
                        }
                        outputStream.write(buffer, 0, bytesRead)

                        val now = System.currentTimeMillis()
                        val timeDiff = now - lastSpeedCalcTime
                        if (timeDiff >= 250) {
                            val instantSpeed = (bytesSinceLastCalc * 1000L) / timeDiff
                            emaSpeed = if (emaSpeed == 0L) instantSpeed else (0.7f * instantSpeed + 0.3f * emaSpeed).toLong()
                            currentSpeed = emaSpeed
                            lastSpeedCalcTime = now
                            bytesSinceLastCalc = 0L
                        }

                        val isFinished = (totalBytes > 0 && downloadedBytes >= totalBytes)
                        if (now - lastEmitTime >= 80 || isFinished) {
                            lastEmitTime = now
                            val progress = if (totalBytes > 0) {
                                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                -1f
                            }

                            val remainingBytes = if (totalBytes > downloadedBytes) totalBytes - downloadedBytes else 0L
                            val etaSeconds = if (currentSpeed > 0 && remainingBytes > 0) {
                                remainingBytes / currentSpeed
                            } else {
                                -1L
                            }

                            if (!isCancelled) {
                                _status.value = DownloadStatus.Downloading(
                                    progress = progress,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    speedBytesPerSec = currentSpeed,
                                    etaSeconds = etaSeconds
                                )
                            }
                        }
                    }
                } finally {
                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                }

                if (isCancelled) {
                    destFile.delete()
                    _status.value = DownloadStatus.Idle
                    return@withContext false
                }

                AppLogger.i(TAG, "Download finished: ${destFile.length()} bytes saved to ${destFile.absolutePath}")

                // Step 2: Verification of SHA-256
                _status.value = DownloadStatus.Verifying

                val calculatedSha256 = calculateSha256(destFile)
                if (isCancelled) {
                    destFile.delete()
                    _status.value = DownloadStatus.Idle
                    return@withContext false
                }
                AppLogger.i(TAG, "Calculated SHA-256: $calculatedSha256")

                var effectiveShaList = expectedSha256List
                if (expectedSha256List.isNotEmpty()) {
                    val normalizedCalculated = calculatedSha256.lowercase().replace(":", "").replace(" ", "")
                    val matches = expectedSha256List.any { expected ->
                        val normalizedExpected = expected.lowercase().replace(":", "").replace(" ", "")
                        normalizedExpected == normalizedCalculated
                    }

                    if (!matches) {
                        AppLogger.w(
                            TAG,
                            "SHA-256 mismatch with initial list. Performing fallback Force-Refresh of GitHub release notes..."
                        )
                        val freshReleaseResult = UpdateChecker.checkForUpdates(
                            currentVersion = versionName,
                            cachedEtag = null
                        )
                        val freshInfo = freshReleaseResult.getOrNull()
                        val freshShaList = freshInfo?.expectedSha256List.orEmpty()
                        val freshMatches = freshShaList.any { expected ->
                            val normalizedExpected = expected.lowercase().replace(":", "").replace(" ", "")
                            normalizedExpected == normalizedCalculated
                        }

                        if (freshMatches) {
                            AppLogger.i(TAG, "SHA-256 hash verified successfully after fallback force refresh against GitHub.")
                            effectiveShaList = freshShaList
                        } else {
                            AppLogger.e(
                                TAG,
                                "SHA-256 mismatch even after force refresh! Calculated: $normalizedCalculated, Expected list: $expectedSha256List, Fresh list: $freshShaList"
                            )
                            destFile.delete()
                            _status.value = DownloadStatus.Error("Ошибка целостности файла: SHA-256 не совпадает с официальным релизом!")
                            return@withContext false
                        }
                    } else {
                        AppLogger.i(TAG, "SHA-256 hash verified successfully against release notes.")
                    }
                } else {
                    AppLogger.w(TAG, "No expected SHA-256 provided in release notes. Skipping strict verification.")
                }

                // Step 3: Sanity check that the downloaded file is a valid Android APK archive
                val archiveInfo = try {
                    context.packageManager.getPackageArchiveInfo(destFile.absolutePath, 0)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error checking package archive info: ${e.message}")
                    null
                }
                if (archiveInfo == null) {
                    AppLogger.e(TAG, "Downloaded file is not a valid Android APK archive.")
                    destFile.delete()
                    _status.value = DownloadStatus.Error("Повреждённый файл: архив не является корректным Android APK")
                    return@withContext false
                }


                // Step 4: Cryptographic signature verification of the downloaded APK BEFORE installation
                val signatureStatus = SignatureVerifier.verifyApkFile(context, destFile, effectiveShaList)
                val isCurrentDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                val isAccepted = signatureStatus == SignatureStatus.OFFICIAL_RELEASE || (signatureStatus == SignatureStatus.DEBUG_BUILD && isCurrentDebug)

                if (!isAccepted) {
                    AppLogger.e(TAG, "Downloaded APK signature verification failed! Status: $signatureStatus")
                    destFile.delete()
                    _status.value = DownloadStatus.Error("Ошибка безопасности: цифровая подпись APK не совпадает с официальным ключом разработчика!")
                    return@withContext false
                }
                AppLogger.i(TAG, "Downloaded APK digital signature verified successfully: $signatureStatus")

                if (isCancelled) {
                    destFile.delete()
                    _status.value = DownloadStatus.Idle
                    return@withContext false
                }

                _status.value = DownloadStatus.ReadyToInstall(destFile)
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error downloading update: ${e.message}")
                runCatching { targetFile?.delete() }

                if (isCancelled || e is java.io.InterruptedIOException || e is java.net.SocketException && e.message?.contains("Socket closed", ignoreCase = true) == true || e.message?.contains("Canceled", ignoreCase = true) == true) {
                    AppLogger.i(TAG, "Download cleanly cancelled.")
                    _status.value = DownloadStatus.Idle
                    return@withContext false
                }

                val errMsg = when {
                    e is java.net.UnknownHostException || e.message?.contains("Unable to resolve host", ignoreCase = true) == true -> {
                        "Сбой DNS (блокировка провайдера): Сервер CDN GitHub (release-assets.githubusercontent.com) заблокирован или недоступен. Включите прокси/VPN или используйте скачивание через браузер."
                    }
                    else -> "Ошибка сети: ${e.localizedMessage ?: "Неизвестная ошибка"}"
                }
                _status.value = DownloadStatus.Error(errMsg)
                false
            } finally {
                synchronized(lock) {
                    activeCall = null
                    currentDownloadFile = null
                }
            }
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(16 * 1024)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    fun canInstallPackages(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.packageManager.canRequestPackageInstalls()
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }
    }


    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to launch install permission settings: ${e.message}")
            }
        }
    }

    fun triggerInstall(context: Context, apkFile: File): Boolean {
        return try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to launch installer intent: ${e.message}")
            false
        }
    }
}
