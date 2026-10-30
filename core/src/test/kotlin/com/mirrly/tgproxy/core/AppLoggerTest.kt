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
        AppLogger.configure(
            loggingEnabled = true,
            logcatReaderAllowed = true,
            levels = setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.DEBUG),
            disabledCats = emptySet(),
            disabledTgs = emptySet()
        )
        AppLogger.clear()
    }

    @AfterEach
    fun tearDown() {
        AppLogger.clear()
        AppLogger.configure(
            loggingEnabled = true,
            logcatReaderAllowed = true,
            levels = setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.DEBUG),
            disabledCats = emptySet(),
            disabledTgs = emptySet()
        )
    }

    @Test
    fun `logs flow remains the authoritative bounded history`() {
        repeat(510) { index ->
            AppLogger.i("Test", "message-$index")
        }

        val snapshot = AppLogger.logsFlow.value
        assertEquals(500, snapshot.size)
        assertEquals("message-10", snapshot.first().rawMessage)
        assertEquals("message-509", snapshot.last().rawMessage)
        assertEquals(AppLogger.getLogs(), snapshot)
    }

    @Test
    fun `disabling global logging drops incoming logs immediately`() {
        AppLogger.setLoggingEnabled(false)
        AppLogger.i("Test", "dropped message")
        AppLogger.e("Test", "dropped error")

        assertTrue(AppLogger.getLogs().isEmpty())
        assertFalse(AppLogger.isLoggingEnabled)
    }

    @Test
    fun `disabling debug level drops debug logs but accepts info`() {
        AppLogger.setLevelEnabled(LogLevel.DEBUG, false)

        AppLogger.d("LocalProxyServer", "debug event")
        AppLogger.i("LocalProxyServer", "info event")

        val logs = AppLogger.getLogs()
        assertEquals(1, logs.size)
        assertEquals("info event", logs.first().rawMessage)
        assertEquals(LogLevel.INFO, logs.first().level)
    }

    @Test
    fun `disabling category drops logs matching any tag of category`() {
        AppLogger.setCategoryEnabled(LogCategory.WARP_SCANNER.key, false)

        AppLogger.i("WarpEndpointScanner", "warp probe message")
        AppLogger.i("LocalProxyServer", "proxy message")

        val logs = AppLogger.getLogs()
        assertEquals(1, logs.size)
        assertEquals("LocalProxyServer", logs.first().tag)
    }

    @Test
    fun `disabling specific tag drops only that tag`() {
        AppLogger.setTagEnabled("mirrlyengine", false)

        AppLogger.i("mirrlyengine", "engine event")
        AppLogger.i("LocalProxyServer", "proxy event")

        val logs = AppLogger.getLogs()
        assertEquals(1, logs.size)
        assertEquals("LocalProxyServer", logs.first().tag)
        assertTrue(AppLogger.isTagDisabled("mirrlyengine"))
        assertFalse(AppLogger.isTagDisabled("LocalProxyServer"))
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
