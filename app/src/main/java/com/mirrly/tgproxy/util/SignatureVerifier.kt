package com.mirrly.tgproxy.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.NativeProxy
import java.io.File
import java.security.MessageDigest

enum class SignatureStatus {
    OFFICIAL_RELEASE,
    DEBUG_BUILD,
    UNOFFICIAL_MODIFIED
}

object SignatureVerifier {
    private const val TAG = "SignatureVerifier"

    private val MASK_A = byteArrayOf(
        0xA5.toByte(), 0x5A.toByte(), 0x3C.toByte(), 0xC3.toByte(), 0x96.toByte(), 0x69.toByte(), 0x0F.toByte(), 0xF0.toByte(),
        0x55.toByte(), 0xAA.toByte(), 0x33.toByte(), 0xCC.toByte(), 0x0F.toByte(), 0xF0.toByte(), 0x5A.toByte(), 0xA5.toByte()
    )
    private val MASK_B = byteArrayOf(
        0x3C.toByte(), 0xC3.toByte(), 0x5A.toByte(), 0xA5.toByte(), 0x96.toByte(), 0x69.toByte(), 0xF0.toByte(), 0x0F.toByte(),
        0xAA.toByte(), 0x55.toByte(), 0xCC.toByte(), 0x33.toByte(), 0xF0.toByte(), 0x0F.toByte(), 0xA5.toByte(), 0x5A.toByte()
    )
    private val ENC_A = byteArrayOf(
        0xA5.toByte(), 0x68.toByte(), 0x55.toByte(), 0x08.toByte(), 0xE6.toByte(), 0x7B.toByte(), 0xD0.toByte(), 0xC7.toByte(),
        0x34.toByte(), 0x4D.toByte(), 0xE0.toByte(), 0x5E.toByte(), 0x76.toByte(), 0xAC.toByte(), 0x99.toByte(), 0xF0.toByte()
    )
    private val ENC_B = byteArrayOf(
        0x3E.toByte(), 0x80.toByte(), 0x63.toByte(), 0x01.toByte(), 0x24.toByte(), 0x72.toByte(), 0xCF.toByte(), 0xBA.toByte(),
        0xDF.toByte(), 0xF1.toByte(), 0x05.toByte(), 0x18.toByte(), 0x3C.toByte(), 0xD2.toByte(), 0x80.toByte(), 0x4C.toByte()
    )

    private fun getOfficialKeyCleanHex(): String {
        val out = ByteArray(32)
        for (i in 0 until 16) {
            out[i] = (ENC_A[i].toInt() xor MASK_A[i].toInt()).toByte()
            out[i + 16] = (ENC_B[i].toInt() xor MASK_B[i].toInt()).toByte()
        }
        return out.joinToString("") { String.format("%02X", it) }
    }

    @Volatile
    private var isEnvironmentProfileDecoyValid: Boolean = true

    @Volatile
    private var cachedStatus: SignatureStatus? = null

    @JvmStatic
    private fun verifyDecoyEnvironment(context: Context): Boolean {
        val flags = context.applicationInfo.flags
        val isDebug = (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return isEnvironmentProfileDecoyValid && !isDebug
    }

    fun verify(context: Context): SignatureStatus {
        val cached = cachedStatus
        if (cached != null) {
            return cached
        }

        val status = try {
            val isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val isOfficialPackage = context.packageName == "com.mirrly.tgproxy"
            val isAuthenticInEngine = isOfficialPackage && NativeProxy.isEngineAuthorized()

            when {
                isAuthenticInEngine -> SignatureStatus.OFFICIAL_RELEASE
                isDebug -> SignatureStatus.DEBUG_BUILD
                else -> SignatureStatus.UNOFFICIAL_MODIFIED
            }
        } catch (_: Throwable) {
            SignatureStatus.UNOFFICIAL_MODIFIED
        }

        cachedStatus = status
        return status
    }

    @Suppress("UNUSED_PARAMETER")
    fun verify(context: Context, expectedRemoteHash: String?): SignatureStatus = verify(context)

    @Suppress("UNUSED_PARAMETER")
    fun verify(context: Context, expectedRemoteHashes: List<String>?): SignatureStatus = verify(context)

    fun getSecurityReportJson(): String? {
        return NativeProxy.getSecurityPostureReportJson()
    }

    @Suppress("UNUSED_PARAMETER")
    fun verifyApkFile(context: Context, apkFile: File, expectedRemoteHashes: List<String>? = null): SignatureStatus {
        return try {
            val signatures = getApkFileSignatures(context, apkFile)
            if (signatures.isEmpty()) {
                return SignatureStatus.UNOFFICIAL_MODIFIED
            }

            val apkSha256WithColons = hashSha256(signatures[0])
            val apkSha256Clean = apkSha256WithColons.replace(":", "").uppercase()

            val cleanOfficial = getOfficialKeyCleanHex()
            val isKnownOfficialKey = apkSha256Clean == cleanOfficial

            val isCurrentDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

            val isMatchingDebugSignature = if (isCurrentDebug) {
                val currentSignatures = getAppSignatures(context)
                if (currentSignatures.isNotEmpty()) {
                    val currentClean = hashSha256(currentSignatures[0]).replace(":", "").uppercase()
                    currentClean == apkSha256Clean
                } else false
            } else false

            when {
                isKnownOfficialKey -> SignatureStatus.OFFICIAL_RELEASE
                isMatchingDebugSignature -> SignatureStatus.DEBUG_BUILD
                else -> SignatureStatus.UNOFFICIAL_MODIFIED
            }
        } catch (_: Exception) {
            SignatureStatus.UNOFFICIAL_MODIFIED
        }
    }

    private fun getApkFileSignatures(context: Context, apkFile: File): List<ByteArray> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo?.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners.map { it.toByteArray() }
                    } else {
                        signingInfo.signingCertificateHistory.map { it.toByteArray() }
                    }
                } else emptyList()
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                val signatures = packageInfo?.signatures
                signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getSignatureSha256(context: Context): String {
        return try {
            val signatures = getAppSignatures(context)
            if (signatures.isNotEmpty()) {
                hashSha256(signatures[0])
            } else {
                "Failed to extract signature"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun hashSha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString(":") { String.format("%02X", it) }
    }
}
