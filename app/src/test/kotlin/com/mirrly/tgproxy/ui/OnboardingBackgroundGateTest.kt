/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingBackgroundGateTest {

    @Test
    fun blocksExitWhenBatteryAndOemMissing() {
        val state = OnboardingBackgroundGateState(
            isIgnoringBattery = false,
            isOemSupported = true,
            hasOpenedOemSettings = false,
            isOemConfirmed = false
        )
        assertFalse(state.isComplete)
        assertFalse(state.canExit())
        assertFalse(state.canNavigateToStep(ONBOARDING_STEP_BACKGROUND + 1))
        assertTrue(state.canNavigateToStep(ONBOARDING_STEP_BACKGROUND))
        assertEquals(BackgroundGateMissing.BATTERY_AND_OEM, state.missingRequirement)
    }

    @Test
    fun blocksExitWhenBatteryMissingOnStockAndroid() {
        val state = OnboardingBackgroundGateState(
            isIgnoringBattery = false,
            isOemSupported = false,
            hasOpenedOemSettings = false,
            isOemConfirmed = false
        )
        assertFalse(state.isComplete)
        assertFalse(state.canExit())
        assertEquals(BackgroundGateMissing.BATTERY_ONLY, state.missingRequirement)
    }

    @Test
    fun blocksExitWhenOemSettingsNotOpenedYet() {
        val state = OnboardingBackgroundGateState(
            isIgnoringBattery = true,
            isOemSupported = true,
            hasOpenedOemSettings = false,
            isOemConfirmed = false
        )
        assertFalse(state.isComplete)
        assertFalse(state.canExit())
        assertEquals(BackgroundGateMissing.OEM_NOT_OPENED, state.missingRequirement)
    }

    @Test
    fun blocksExitWhenOemSettingsOpenedButNotConfirmed() {
        val state = OnboardingBackgroundGateState(
            isIgnoringBattery = true,
            isOemSupported = true,
            hasOpenedOemSettings = true,
            isOemConfirmed = false
        )
        assertFalse(state.isComplete)
        assertFalse(state.canExit())
        assertEquals(BackgroundGateMissing.OEM_NOT_CONFIRMED, state.missingRequirement)
    }

    @Test
    fun unlocksExitWhenBatteryIgnoredAndOemConfirmed() {
        val state = OnboardingBackgroundGateState(
            isIgnoringBattery = true,
            isOemSupported = true,
            hasOpenedOemSettings = true,
            isOemConfirmed = true
        )
        assertTrue(state.isComplete)
        assertTrue(state.canExit())
        assertTrue(state.canNavigateToStep(ONBOARDING_STEP_BACKGROUND + 1))
        assertEquals(BackgroundGateMissing.NONE, state.missingRequirement)
    }

    @Test
    fun unlocksExitWhenBatteryIgnoredOnNonOemDevice() {
        val state = OnboardingBackgroundGateState(
            isIgnoringBattery = true,
            isOemSupported = false,
            hasOpenedOemSettings = false,
            isOemConfirmed = false
        )
        assertTrue(state.isComplete)
        assertTrue(state.canExit())
        assertTrue(state.canNavigateToStep(ONBOARDING_STEP_BACKGROUND + 1))
        assertEquals(BackgroundGateMissing.NONE, state.missingRequirement)
    }
}
