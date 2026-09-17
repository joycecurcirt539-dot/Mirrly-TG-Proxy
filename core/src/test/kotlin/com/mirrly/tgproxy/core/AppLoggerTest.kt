package com.mirrly.tgproxy.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppLoggerTest {
    @BeforeEach
    fun setUp() {
        AppLogger.clear()
    }

    @AfterEach
    fun tearDown() {
        AppLogger.clear()
    }

    @Test
    fun `logs flow remains the authoritative bounded history`() {
        repeat(260) { index ->
            AppLogger.i("Test", "message-$index")
        }

        val snapshot = AppLogger.logsFlow.value
        assertEquals(250, snapshot.size)
        assertEquals("message-10", snapshot.first().rawMessage)
        assertEquals("message-259", snapshot.last().rawMessage)
        assertEquals(AppLogger.getLogs(), snapshot)
    }

    @Test
    fun `replay filter rejects only the exact same logcat event`() {
        val filter = BoundedLogcatReplayFilter(capacity = 4)
        val first = "09-14 12:00:00.100 I/mirrlyengine: Happy Eyeballs started"
        val repeatedLater = "09-14 12:00:01.200 I/mirrlyengine: Happy Eyeballs started"

        assertTrue(filter.shouldAccept(first))
        assertFalse(filter.shouldAccept(first))
        assertTrue(filter.shouldAccept(repeatedLater))
    }

    @Test
    fun `replay filter evicts the oldest fingerprint when bounded`() {
        val filter = BoundedLogcatReplayFilter(capacity = 2)

        assertTrue(filter.shouldAccept("line-1"))
        assertTrue(filter.shouldAccept("line-2"))
        assertTrue(filter.shouldAccept("line-3"))
        assertTrue(filter.shouldAccept("line-1"))
    }
}
