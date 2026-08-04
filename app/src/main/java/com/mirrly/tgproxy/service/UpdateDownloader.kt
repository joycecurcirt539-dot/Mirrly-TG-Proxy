package com.mirrly.tgproxy.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mirrly.tgproxy.core.AppLogger
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
        val totalBytes: Long
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
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun resetStatus() {
        _status.value = DownloadStatus.Idle
    }

    suspend fun downloadAndVerifyApk(
        context: Context,
        downloadUrl: String,
        expectedSha256List: List<String>,
        versionName: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                _status.value = DownloadStatus.Downloading(0f, 0L, 0L)

                val apkDir = File(context.cacheDir, "apks").apply {
                    if (!exists()) mkdirs()
                }

                // Cleanup previous apk files
                apkDir.listFiles()?.forEach { file ->
                    runCatching { file.delete() }
                }

                val targetFile = File(apkDir, "mirrly_v${versionName.replace(".", "_")}.apk")

                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "Mirrly-TG-Proxy-Downloader/$versionName")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    _status.value = DownloadStatus.Error("Ошибка загрузки HTTP ${response.code}")
                    return@withContext false
                }

                val body = response.body
                if (body == null) {
                    _status.value = DownloadStatus.Error("Пустой ответ сервера")
                    return@withContext false
                }

                val totalBytes = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(16 * 1024)
                var bytesRead: Int
                var downloadedBytes = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val progress = if (totalBytes > 0) {
                        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        -1f
                    }

                    _status.value = DownloadStatus.Downloading(
                        progress = progress,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes
                    )
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                AppLogger.i(TAG, "Download finished: ${targetFile.length()} bytes saved to ${targetFile.absolutePath}")

                // Step 2: Verification of SHA-256
                _status.value = DownloadStatus.Verifying

                val calculatedSha256 = calculateSha256(targetFile)
                AppLogger.i(TAG, "Calculated SHA-256: $calculatedSha256")

                if (expectedSha256List.isNotEmpty()) {
                    val normalizedCalculated = calculatedSha256.lowercase().replace(":", "").replace(" ", "")
                    val matches = expectedSha256List.any { expected ->
                        val normalizedExpected = expected.lowercase().replace(":", "").replace(" ", "")
                        normalizedExpected == normalizedCalculated
                    }

                    if (!matches) {
                        AppLogger.e(
                            TAG,
                            "SHA-256 mismatch! Calculated: $normalizedCalculated, Expected list: $expectedSha256List"
                        )
                        targetFile.delete()
                        _status.value = DownloadStatus.Error("Ошибка целостности файла: SHA-256 не совпадает с официальным релизом!")
                        return@withContext false
                    }
                    AppLogger.i(TAG, "SHA-256 hash verified successfully against release notes.")
                } else {
                    AppLogger.w(TAG, "No expected SHA-256 provided in release notes. Skipping strict verification.")
                }

                _status.value = DownloadStatus.ReadyToInstall(targetFile)
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error downloading update: ${e.message}")
                val errMsg = when {
                    e is java.net.UnknownHostException || e.message?.contains("Unable to resolve host", ignoreCase = true) == true -> {
                        "Сбой DNS (блокировка провайдера): Сервер CDN GitHub (release-assets.githubusercontent.com) заблокирован или недоступен. Включите прокси/VPN или используйте скачивание через браузер."
                    }
                    else -> "Ошибка сети: ${e.localizedMessage ?: "Неизвестная ошибка"}"
                }
                _status.value = DownloadStatus.Error(errMsg)
                false
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
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
