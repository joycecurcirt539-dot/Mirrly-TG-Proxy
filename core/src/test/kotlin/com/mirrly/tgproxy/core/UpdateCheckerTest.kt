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
    }

    @Test
    fun testIsVersionNewer() {
        assertTrue(UpdateChecker.isVersionNewer("1.0.6", "1.0.5"))
        assertTrue(UpdateChecker.isVersionNewer("v1.1.0", "1.0.5"))
        assertTrue(UpdateChecker.isVersionNewer("2.0.0", "1.9.9"))

        assertFalse(UpdateChecker.isVersionNewer("1.0.5", "1.0.5"))
        assertFalse(UpdateChecker.isVersionNewer("1.0.4", "1.0.5"))
        assertFalse(UpdateChecker.isVersionNewer("v1.0.5", "1.0.5"))
        assertFalse(UpdateChecker.isVersionNewer("", "1.0.5"))
    }
}
