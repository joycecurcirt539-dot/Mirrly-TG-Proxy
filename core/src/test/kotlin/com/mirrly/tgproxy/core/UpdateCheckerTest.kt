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
    }

    @Test
    fun testIsVersionNewer() {
        assertTrue(UpdateChecker.isVersionNewer("1.0.6", "1.0.5"))
        assertTrue(UpdateChecker.isVersionNewer("v1.1.0", "1.0.5"))
        assertTrue(UpdateChecker.isVersionNewer("2.0.0", "1.9.9"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.3.1", "1.1.3"))
        assertTrue(UpdateChecker.isVersionNewer("v1.1.3.1", "1.1.3"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.4", "1.1.3.1"))
        assertTrue(UpdateChecker.isVersionNewer("1.2.0", "1.1.3.1"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.10", "1.1.9"))

        // Self-update prevention (same versions in all formats must return false)
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
        assertFalse(UpdateChecker.isVersionNewer("", "1.0.5"))
        assertFalse(UpdateChecker.isVersionNewer("1.1.3.1", ""))
    }
}
