package com.mirrly.tgproxy.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mirrly.tgproxy.core.AppLogger
import java.security.MessageDigest

enum class SignatureStatus {
    OFFICIAL_RELEASE,
    DEBUG_BUILD,
    UNOFFICIAL_MODIFIED
}

object SignatureVerifier {
    private const val TAG = "SignatureVerifier"

    // Official release key SHA-256 fingerprint (used for fallback if native library fails)
    private const val OFFICIAL_RELEASE_SHA256 = "97:73:5C:0A:20:70:7F:D4:E4:BD:93:A2:D8:48:CA:91:9A:C5:40:45:4A:62:16:E8:CC:7D:43:4F:1F:9F:0A:96"

    @Volatile
    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("mirrly_sec")
            isNativeLoaded = true
            AppLogger.i(TAG, "Native security library mirrly_sec loaded successfully")
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Failed to load native security library mirrly_sec: ${e.message}")
            isNativeLoaded = false
        }
    }

    // Dynamically registered via JNI_OnLoad in native_sec.cpp
    @JvmStatic
    private external fun verifyNative(context: Context, expectedRemoteHashes: Array<String>?): Int

    @Volatile
    private var cachedStatus: SignatureStatus? = null

    fun verify(context: Context, expectedRemoteHash: String? = null): SignatureStatus {
        return verify(context, if (expectedRemoteHash.isNullOrBlank()) emptyList() else listOf(expectedRemoteHash))
    }

    fun verify(context: Context, expectedRemoteHashes: List<String>?): SignatureStatus {
        if (expectedRemoteHashes.isNullOrEmpty()) {
            cachedStatus?.let { return it }
        }

        val status = if (isNativeLoaded) {
            try {
                val array = expectedRemoteHashes?.toTypedArray()
                val code = verifyNative(context, array)
                when (code) {
                    0 -> SignatureStatus.OFFICIAL_RELEASE
                    1 -> SignatureStatus.DEBUG_BUILD
                    else -> SignatureStatus.UNOFFICIAL_MODIFIED
                }
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Native verify call failed: ${e.message}, falling back to Kotlin verification")
                verifyKotlinFallback(context, expectedRemoteHashes)
            }
        } else {
            verifyKotlinFallback(context, expectedRemoteHashes)
        }

        cachedStatus = status
        return status
    }

    private fun verifyKotlinFallback(context: Context, expectedRemoteHashes: List<String>?): SignatureStatus {
        val status = try {
            val signatures = getAppSignatures(context)
            if (signatures.isEmpty()) {
                SignatureStatus.OFFICIAL_RELEASE
            } else {
                val currentSha256WithColons = hashSha256(signatures[0])
                val currentSha256Clean = currentSha256WithColons.replace(":", "").uppercase()
                AppLogger.i(TAG, "Current APK Signature SHA-256: $currentSha256WithColons")

                val isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

                val cleanExpectedList = expectedRemoteHashes?.mapNotNull { h ->
                    h.replace(":", "").uppercase().takeIf { it.isNotBlank() }
                } ?: emptyList()

                val isRemoteMatch = cleanExpectedList.any { clean ->
                    currentSha256Clean == clean
                }

                val isKnownOfficialKey = currentSha256WithColons.equals(OFFICIAL_RELEASE_SHA256, ignoreCase = true) ||
                        OFFICIAL_RELEASE_SHA256.replace(":", "").equals(currentSha256Clean, ignoreCase = true)

                if (isRemoteMatch || isKnownOfficialKey) {
                    SignatureStatus.OFFICIAL_RELEASE
                } else if (isDebug) {
                    SignatureStatus.DEBUG_BUILD
                } else {
                    SignatureStatus.UNOFFICIAL_MODIFIED
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error verifying APK signature: ${e.message}")
            SignatureStatus.OFFICIAL_RELEASE
        }
        return status
    }

    fun getSignatureSha256(context: Context): String {
        return try {
            val signatures = getAppSignatures(context)
            if (signatures.isNotEmpty()) {
                hashSha256(signatures[0])
            } else {
                "Не удалось извлечь подпись"
            }
        } catch (e: Exception) {
            "Ошибка: ${e.message}"
        }
    }

    private fun getAppSignatures(context: Context): List<ByteArray> {
        val pm = context.packageManager
        val packageName = context.packageName

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners.map { it.toByteArray() }
                    } else {
                        signingInfo.signingCertificateHistory.map { it.toByteArray() }
                    }
                } else emptyList()
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                val signatures = packageInfo.signatures
                signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to retrieve package signatures: ${e.message}")
            emptyList()
        }
    }

    private fun hashSha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString(":") { String.format("%02X", it) }
    }
}
