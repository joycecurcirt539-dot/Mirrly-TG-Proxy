package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateCheckerTest {

    @Test
    fun testCleanVersionString() {
        assertEquals("1.0.6", UpdateChecker.cleanVersionString("v1.0.6"))
        assertEquals("1.0.6", UpdateChecker.cleanVersionString("V1.0.6"))
        assertEquals("1.0.6", UpdateChecker.cleanVersionString("1.0.6"))
        assertEquals("1.0.6", UpdateChecker.cleanVersionString("v1.0.6-beta"))
        assertEquals("1.0.6", UpdateChecker.cleanVersionString(" 1.0.6 "))
        assertEquals("1.1.2", UpdateChecker.cleanVersionString("v1.1.2"))
        assertEquals("1.1.3", UpdateChecker.cleanVersionString("v1.1.3"))
        assertEquals("1.1.3", UpdateChecker.cleanVersionString("v1.1.3-release"))
        assertEquals("1.1.3", UpdateChecker.cleanVersionString("Mirrly TG Proxy v1.1.3"))
        assertEquals("1.1.3.1", UpdateChecker.cleanVersionString("v1.1.3.1"))
        assertEquals("1.1.3.1", UpdateChecker.cleanVersionString("1.1.3.1"))
        assertEquals("1.1.3.1", UpdateChecker.cleanVersionString("Mirrly TG Proxy v1.1.3.1"))
        assertEquals("1.1.4", UpdateChecker.cleanVersionString("v1.1.4"))
        assertEquals("1.1.4", UpdateChecker.cleanVersionString("1.1.4"))
        assertEquals("1.1.4", UpdateChecker.cleanVersionString("Mirrly TG Proxy v1.1.4"))
        assertEquals("1.1.4", UpdateChecker.cleanVersionString("v1.1.4-release"))
        assertEquals("1.1.5", UpdateChecker.cleanVersionString("v1.1.5"))
        assertEquals("1.1.5", UpdateChecker.cleanVersionString("1.1.5"))
        assertEquals("1.1.5", UpdateChecker.cleanVersionString("Mirrly TG Proxy v1.1.5"))
        assertEquals("1.1.5", UpdateChecker.cleanVersionString("v1.1.5-release"))
        assertEquals("1.1.6", UpdateChecker.cleanVersionString("v1.1.6"))
        assertEquals("1.1.6", UpdateChecker.cleanVersionString("1.1.6"))
        assertEquals("1.1.6", UpdateChecker.cleanVersionString("Mirrly TG Proxy v1.1.6"))
        assertEquals("1.1.6", UpdateChecker.cleanVersionString("v1.1.6-release"))
        assertEquals("1.1.6.1", UpdateChecker.cleanVersionString("v1.1.6.1"))
        assertEquals("1.1.6.1", UpdateChecker.cleanVersionString("1.1.6.1"))
        assertEquals("1.1.6.1", UpdateChecker.cleanVersionString("Mirrly TG Proxy v1.1.6.1"))
        assertEquals("1.1.6.1", UpdateChecker.cleanVersionString("v1.1.6.1-release"))
        assertEquals("1.1.7", UpdateChecker.cleanVersionString("v1.1.7"))
        assertEquals("1.1.7", UpdateChecker.cleanVersionString("1.1.7"))
        assertEquals("1.1.7", UpdateChecker.cleanVersionString("Mirrly TG Proxy v1.1.7"))
        assertEquals("1.1.8", UpdateChecker.cleanVersionString("v1.1.8"))
        assertEquals("1.1.8", UpdateChecker.cleanVersionString("1.1.8"))
        assertEquals("1.1.8", UpdateChecker.cleanVersionString("Mirrly TG Proxy v1.1.8"))
        assertEquals("1.1.8", UpdateChecker.cleanVersionString("v1.1.8-release"))
    }

    @Test
    fun testIsVersionNewer() {
        assertTrue(UpdateChecker.isVersionNewer("1.0.6", "1.0.5"))
        assertTrue(UpdateChecker.isVersionNewer("v1.1.0", "1.0.5"))
        assertTrue(UpdateChecker.isVersionNewer("2.0.0", "1.9.9"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.3.1", "1.1.3"))
        assertTrue(UpdateChecker.isVersionNewer("v1.1.3.1", "1.1.3"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.4", "1.1.3.1"))
        assertTrue(UpdateChecker.isVersionNewer("v1.1.4", "1.1.3.1"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.5", "1.1.4"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.6", "1.1.5"))
        assertTrue(UpdateChecker.isVersionNewer("v1.1.6", "1.1.5"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.6.1", "1.1.6"))
        assertTrue(UpdateChecker.isVersionNewer("v1.1.6.1", "1.1.6"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.7", "1.1.6.1"))
        assertTrue(UpdateChecker.isVersionNewer("v1.1.7", "1.1.6.1"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.8", "1.1.7"))
        assertTrue(UpdateChecker.isVersionNewer("v1.1.8", "1.1.7"))
        assertTrue(UpdateChecker.isVersionNewer("1.2.0", "1.1.8"))
        assertTrue(UpdateChecker.isVersionNewer("2.0.0", "1.1.8"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.10", "1.1.9"))

        // Self-update prevention and older version checks
        assertFalse(UpdateChecker.isVersionNewer("1.0.5", "1.0.5"))
        assertFalse(UpdateChecker.isVersionNewer("1.0.4", "1.0.5"))
        assertFalse(UpdateChecker.isVersionNewer("v1.0.5", "1.0.5"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.3", "1.1.3"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.3", "1.1.3"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.3.1", "1.1.3.1"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.3.1", "1.1.3.1"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.3.1", "v1.1.3.1"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.3", "1.1.3.1"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.3-release", "1.1.3"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.3", "v1.1.3"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.3.0", "1.1.3"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.3", "1.1.3.0"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.2", "1.1.3"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.3.1", "1.1.4"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.3.1", "1.1.4"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.4", "1.1.4"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.4", "1.1.4"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.4", "v1.1.4"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.4-release", "1.1.4"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.4", "1.1.5"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.4", "1.1.5"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.5", "1.1.5"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.5", "1.1.5"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.5", "v1.1.5"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.5-release", "1.1.5"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.5", "1.1.6"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.5", "1.1.6"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.6", "1.1.6"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.6", "1.1.6"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.6", "v1.1.6"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.6-release", "1.1.6"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.6", "1.1.6.1"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.6", "1.1.6.1"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.6.1", "1.1.6.1"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.6.1", "1.1.6.1"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.6.1", "v1.1.6.1"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.6.1-release", "1.1.6.1"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.7", "1.1.7"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.7", "1.1.7"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.8", "1.1.8"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.8", "1.1.8"))
        assertFalse(UpdateChecker.isVersionNewer("v1.1.8-release", "1.1.8"))
        assertFalse(UpdateChecker.isVersionNewer("", "1.0.5"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.8", ""))
    }

    @Test
    fun testApkTypeClassification() {
        assertEquals(ApkType.ARM64, ApkType.fromAssetName("app-arm64-v8a-release.apk"))
        assertEquals(ApkType.ARM64, ApkType.fromAssetName("Mirrly-arm64.apk"))
        assertEquals(ApkType.ARM_V7, ApkType.fromAssetName("app-armeabi-v7a-release.apk"))
        assertEquals(ApkType.ARM_V7, ApkType.fromAssetName("Mirrly-armv7.apk"))
        assertEquals(ApkType.X86_64, ApkType.fromAssetName("app-x86_64-release.apk"))
        assertEquals(ApkType.X86_64, ApkType.fromAssetName("Mirrly-x86-64.apk"))
        assertEquals(ApkType.X86, ApkType.fromAssetName("app-x86-release.apk"))
        assertEquals(ApkType.UNIVERSAL, ApkType.fromAssetName("app-universal-release.apk"))
        assertEquals(ApkType.UNIVERSAL, ApkType.fromAssetName("app-release.apk"))

        assertEquals(ApkType.ARM64, ApkType.fromAbis(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals(ApkType.ARM_V7, ApkType.fromAbis(listOf("armeabi-v7a", "armeabi")))
        assertEquals(ApkType.X86_64, ApkType.fromAbis(listOf("x86_64", "x86")))
        assertEquals(ApkType.X86, ApkType.fromAbis(listOf("x86")))
        assertEquals(ApkType.UNIVERSAL, ApkType.fromAbis(listOf("mips")))
    }

    @Test
    fun testExtractSha256ForAsset() {
        val sampleReleaseBody = """
            Ключевые изменения и контрольные суммы.

            ---
            ## 1. Контрольные отпечатки SHA-256
            * **app-universal-release.apk SHA-256**: `0A98197EC49E70F0C5844E449C89AB1193CB48245C62E991F6469BEDF68B729E`
            * **app-arm64-v8a-release.apk SHA-256**: `A75EBE0AAA2856D4FEEE2C6B89D617F98F2CE132ACB8E6549F410EFCA3A35553`
            * **app-armeabi-v7a-release.apk SHA-256**: `E85508CF10B91970D0A03C899893BA56D3E932EE12E6D0B7C3C106F32236534B`
            * **app-x86_64-release.apk SHA-256**: `34217926C9AD2ED4F6DAB282392289C20DDB7D7E1CC83300545861D085A597D5`
            * **app-x86-release.apk SHA-256**: `F0F71901F8712A709EA5926FF109544F7D28AAFB3ED794752B59163823B1D3AE`
        """.trimIndent()

        assertEquals("0A98197EC49E70F0C5844E449C89AB1193CB48245C62E991F6469BEDF68B729E", UpdateChecker.extractSha256ForAsset(sampleReleaseBody, "app-universal-release.apk"))
        assertEquals("A75EBE0AAA2856D4FEEE2C6B89D617F98F2CE132ACB8E6549F410EFCA3A35553", UpdateChecker.extractSha256ForAsset(sampleReleaseBody, "app-arm64-v8a-release.apk"))
        assertEquals("E85508CF10B91970D0A03C899893BA56D3E932EE12E6D0B7C3C106F32236534B", UpdateChecker.extractSha256ForAsset(sampleReleaseBody, "app-armeabi-v7a-release.apk"))
        assertEquals("34217926C9AD2ED4F6DAB282392289C20DDB7D7E1CC83300545861D085A597D5", UpdateChecker.extractSha256ForAsset(sampleReleaseBody, "app-x86_64-release.apk"))
        assertEquals("F0F71901F8712A709EA5926FF109544F7D28AAFB3ED794752B59163823B1D3AE", UpdateChecker.extractSha256ForAsset(sampleReleaseBody, "app-x86-release.apk"))
    }
}
