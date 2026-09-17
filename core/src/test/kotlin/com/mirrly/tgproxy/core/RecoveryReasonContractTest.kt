package com.mirrly.tgproxy.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecoveryReasonContractTest {
    @Test
    fun `probe watchdog cannot perform an unconditional native reset`() {
        val source = File(
            "src/main/kotlin/com/mirrly/tgproxy/core/LocalProxyServer.kt"
        ).canonicalFile.readText()
        val start = source.indexOf("onSelfHealingRequired = { failureType ->")
        val end = source.indexOf("val qosEngine", startIndex = start)

        assertTrue(start >= 0 && end > start, "watchdog callback boundary must be detectable")
        val callback = source.substring(start, end)
        assertFalse(
            callback.contains("resetNetworkSockets"),
            "a diagnostic probe must not reset every active native flow"
        )
        assertTrue(callback.contains("FailureType.DNS_FAILURE"))
        assertTrue(callback.contains("FailureType.CONNECT_TIMEOUT"))
    }
}
