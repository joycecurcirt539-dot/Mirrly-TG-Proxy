package com.mirrly.tgproxy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class SignatureVerifierTest {

    private fun sha256HexClean(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { String.format(Locale.US, "%02X", it) }
    }

    private fun sha256HexColon(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(":") { String.format(Locale.US, "%02X", it) }
    }

    private fun locateAppSourceFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("..", relativePath),
            File("../app", relativePath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Source file not found: $relativePath in $candidates")
    }

    @Test
    fun officialKeyXorDecoding_matchesOfficialReleaseFingerprintFormat() {
        val formatted = SignatureVerifier.getOfficialFingerprintFormatted()
        assertEquals(95, formatted.length)
        assertTrue(
            "Formatted fingerprint must be 32 colon-separated uppercase hex bytes",
            Regex("""^([0-9A-F]{2}:){31}[0-9A-F]{2}$""").matches(formatted)
        )
        assertEquals(
            "00:32:69:CB:70:12:DF:37:61:E7:D3:92:79:5C:C3:55:02:43:39:A4:B2:1B:3F:B5:75:A4:C9:2B:CC:DD:25:16",
            formatted
        )
    }

    @Test
    fun evaluateSignatureStatus_officialPackageAndMatchingCert_returnsOfficialRelease() {
        val certBytes = "synthetic-official-x509-der".toByteArray()
        val expectedClean = sha256HexClean(certBytes)
        val expectedColonLower = sha256HexColon(certBytes).lowercase(Locale.US)

        assertEquals(
            SignatureStatus.OFFICIAL_RELEASE,
            SignatureVerifier.evaluateSignatureStatus(
                packageName = "com.mirrly.tgproxy",
                isDebug = false,
                signatures = listOf(certBytes),
                expectedOfficialHex = expectedClean
            )
        )
        assertEquals(
            SignatureStatus.OFFICIAL_RELEASE,
            SignatureVerifier.evaluateSignatureStatus(
                packageName = "com.mirrly.tgproxy",
                isDebug = false,
                signatures = listOf(certBytes),
                expectedOfficialHex = expectedColonLower
            )
        )
    }

    @Test
    fun evaluateSignatureStatus_officialPackageAndMismatchingCert_returnsUnofficialModified() {
        assertEquals(
            SignatureStatus.UNOFFICIAL_MODIFIED,
            SignatureVerifier.evaluateSignatureStatus(
                packageName = "com.mirrly.tgproxy",
                isDebug = false,
                signatures = listOf("tampered-cert".toByteArray())
            )
        )
    }

    @Test
    fun evaluateSignatureStatus_debugBuildWithUnofficialCert_returnsDebugBuild() {
        assertEquals(
            SignatureStatus.DEBUG_BUILD,
            SignatureVerifier.evaluateSignatureStatus(
                packageName = "com.mirrly.tgproxy",
                isDebug = true,
                signatures = listOf("android-debug-cert".toByteArray())
            )
        )
    }

    @Test
    fun evaluateSignatureStatus_debugBuildWithOfficialCert_returnsOfficialRelease() {
        val certBytes = "synthetic-release-key-in-debug".toByteArray()
        val expectedClean = sha256HexClean(certBytes)

        assertEquals(
            SignatureStatus.OFFICIAL_RELEASE,
            SignatureVerifier.evaluateSignatureStatus(
                packageName = "com.mirrly.tgproxy",
                isDebug = true,
                signatures = listOf(certBytes),
                expectedOfficialHex = expectedClean
            )
        )
    }

    @Test
    fun evaluateSignatureStatus_spoofedPackageNameWithMatchingCert_returnsUnofficialModified() {
        val certBytes = "synthetic-official-x509-der".toByteArray()
        val expectedClean = sha256HexClean(certBytes)

        assertEquals(
            SignatureStatus.UNOFFICIAL_MODIFIED,
            SignatureVerifier.evaluateSignatureStatus(
                packageName = "com.mirrly.tgproxy.mod",
                isDebug = false,
                signatures = listOf(certBytes),
                expectedOfficialHex = expectedClean
            )
        )
    }

    @Test
    fun evaluateSignatureStatus_emptySignaturesNonDebug_returnsUnofficialModified() {
        assertEquals(
            SignatureStatus.UNOFFICIAL_MODIFIED,
            SignatureVerifier.evaluateSignatureStatus(
                packageName = "com.mirrly.tgproxy",
                isDebug = false,
                signatures = emptyList()
            )
        )
    }

    @Test
    fun evaluateSignatureStatus_emptySignaturesDebug_returnsDebugBuild() {
        assertEquals(
            SignatureStatus.DEBUG_BUILD,
            SignatureVerifier.evaluateSignatureStatus(
                packageName = "com.mirrly.tgproxy",
                isDebug = true,
                signatures = emptyList()
            )
        )
    }

    @Test
    fun evaluateSignatureStatus_multipleSignersWithRogueCert_returnsUnofficialModified() {
        val officialCert = "synthetic-official-x509-der".toByteArray()
        val expectedClean = sha256HexClean(officialCert)

        assertEquals(
            SignatureStatus.UNOFFICIAL_MODIFIED,
            SignatureVerifier.evaluateSignatureStatus(
                packageName = "com.mirrly.tgproxy",
                isDebug = false,
                signatures = listOf(officialCert, "rogue-cert".toByteArray()),
                expectedOfficialHex = expectedClean
            )
        )
    }

    @Test
    fun sourceContract_noGetEngineSecurityStateOrIsEngineAuthorizedInApp() {
        val files = listOf(
            "src/main/java/com/mirrly/tgproxy/util/SignatureVerifier.kt",
            "src/main/java/com/mirrly/tgproxy/ui/MainActivity.kt",
            "src/main/java/com/mirrly/tgproxy/service/MirrlyVpnService.kt",
            "src/main/java/com/mirrly/tgproxy/ui/UnofficialBuildDialog.kt"
        )
        val forbidden = listOf(
            "GetEngineSecurityState",
            "getEngineSecurityState",
            "isEngineAuthorized",
            "isEngineBlocked",
            "GetSecurityPostureReportJson",
            "getSecurityPostureReportJson",
            "getSecurityReportJson"
        )

        files.forEach { relPath ->
            val src = locateAppSourceFile(relPath).readText()
            forbidden.forEach { token ->
                assertFalse(
                    "$relPath must not reference $token",
                    src.contains(token)
                )
            }
        }
    }
}
