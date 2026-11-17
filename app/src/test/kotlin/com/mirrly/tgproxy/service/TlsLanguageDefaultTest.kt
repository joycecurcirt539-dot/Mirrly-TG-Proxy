/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.service

import com.mirrly.tgproxy.util.LocaleHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsLanguageDefaultTest {

    @Test
    fun testIsPersianLanguageDetection() {
        assertTrue(LocaleHelper.isPersianLanguage("fa", systemLanguage = "en"))
        assertTrue(LocaleHelper.isPersianLanguage("fa-IR", systemLanguage = "ru"))
        assertTrue(LocaleHelper.isPersianLanguage("fa_IR", systemLanguage = "en"))
        assertTrue(LocaleHelper.isPersianLanguage("system", systemLanguage = "fa"))
        assertTrue(LocaleHelper.isPersianLanguage("", systemLanguage = "fa"))

        assertFalse(LocaleHelper.isPersianLanguage("ru", systemLanguage = "fa"))
        assertFalse(LocaleHelper.isPersianLanguage("en", systemLanguage = "fa"))
        assertFalse(LocaleHelper.isPersianLanguage("system", systemLanguage = "ru"))
        assertFalse(LocaleHelper.isPersianLanguage("system", systemLanguage = "en"))
    }

    @Test
    fun testFreshInstallDefaultsTlsByLanguage() {
        val faFresh = PreferencesManager.resolveTlsStartupState(
            langCode = "system",
            systemLanguage = "fa",
            hasStoredTlsValue = false,
            storedTlsValue = false,
            v1MigrationDone = false,
            faMigrationDone = false,
            hasUserOverride = false
        )
        assertTrue(faFresh.isTlsEnabled)
        assertTrue(faFresh.shouldPersistTls)
        assertTrue(faFresh.markV1Migrated)
        assertTrue(faFresh.markFaDefaultMigrated)
        assertFalse(faFresh.markUserOverride)

        val ruFresh = PreferencesManager.resolveTlsStartupState(
            langCode = "system",
            systemLanguage = "ru",
            hasStoredTlsValue = false,
            storedTlsValue = false,
            v1MigrationDone = false,
            faMigrationDone = false,
            hasUserOverride = false
        )
        assertFalse(ruFresh.isTlsEnabled)
        assertTrue(ruFresh.shouldPersistTls)
        assertTrue(ruFresh.markV1Migrated)
        assertTrue(ruFresh.markFaDefaultMigrated)
    }

    @Test
    fun testUpgradeFromV1EnablesTlsForExistingPersianUsersAndPreservesManualRuChoice() {
        val existingFaUser = PreferencesManager.resolveTlsStartupState(
            langCode = "fa",
            systemLanguage = "ru",
            hasStoredTlsValue = true,
            storedTlsValue = false,
            v1MigrationDone = true,
            faMigrationDone = false,
            hasUserOverride = false
        )
        assertTrue(existingFaUser.isTlsEnabled)
        assertTrue(existingFaUser.shouldPersistTls)
        assertTrue(existingFaUser.markFaDefaultMigrated)

        val existingRuManualOnUser = PreferencesManager.resolveTlsStartupState(
            langCode = "ru",
            systemLanguage = "ru",
            hasStoredTlsValue = true,
            storedTlsValue = true,
            v1MigrationDone = true,
            faMigrationDone = false,
            hasUserOverride = false
        )
        assertTrue(existingRuManualOnUser.isTlsEnabled)
        assertTrue(existingRuManualOnUser.markUserOverride)
        assertTrue(existingRuManualOnUser.markFaDefaultMigrated)
    }

    @Test
    fun testManualDisableOnPersianPersistsAcrossRestarts() {
        val state = PreferencesManager.resolveTlsStartupState(
            langCode = "fa",
            systemLanguage = "fa",
            hasStoredTlsValue = true,
            storedTlsValue = false,
            v1MigrationDone = true,
            faMigrationDone = true,
            hasUserOverride = true
        )
        assertFalse(state.isTlsEnabled)
        assertFalse(state.shouldPersistTls)
    }

    @Test
    fun testLanguageChangeTransitions() {
        val toFa = PreferencesManager.resolveTlsOnLanguageChange(
            previousLangCode = "ru",
            newLangCode = "fa",
            systemLanguage = "ru",
            currentTlsEnabled = false,
            hasUserOverride = true
        )
        assertTrue(toFa.shouldUpdateTls)
        assertTrue(toFa.newTlsEnabled)
        assertTrue(toFa.clearUserOverride)

        val faBackToRuWithoutOverride = PreferencesManager.resolveTlsOnLanguageChange(
            previousLangCode = "fa",
            newLangCode = "ru",
            systemLanguage = "ru",
            currentTlsEnabled = true,
            hasUserOverride = false
        )
        assertTrue(faBackToRuWithoutOverride.shouldUpdateTls)
        assertFalse(faBackToRuWithoutOverride.newTlsEnabled)

        val faToSystemOnPersianPhone = PreferencesManager.resolveTlsOnLanguageChange(
            previousLangCode = "fa",
            newLangCode = "system",
            systemLanguage = "fa",
            currentTlsEnabled = false,
            hasUserOverride = true
        )
        assertFalse(faToSystemOnPersianPhone.shouldUpdateTls)
        assertEquals(false, faToSystemOnPersianPhone.newTlsEnabled)
    }
}
