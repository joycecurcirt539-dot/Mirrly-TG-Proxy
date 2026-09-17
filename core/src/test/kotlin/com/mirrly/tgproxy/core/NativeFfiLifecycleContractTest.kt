package com.mirrly.tgproxy.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeFfiLifecycleContractTest {
    @Test
    fun `network generation FFI path does not require an entered Tokio runtime`() {
        val source = File("..", "mirrlyengine/src/budget.rs").canonicalFile.readText()
        val functionStart = source.indexOf("pub fn notify_generation_change")
        val nextFunction = source.indexOf("\n    ///", startIndex = functionStart + 1)

        assertTrue(functionStart >= 0, "notify_generation_change must exist")
        assertTrue(nextFunction > functionStart, "notify_generation_change boundary must be detectable")

        val functionBody = source.substring(functionStart, nextFunction)
        assertFalse(
            functionBody.contains("tokio::spawn"),
            "SetNetworkGeneration is called through JNA and must not use context-dependent tokio::spawn"
        )
        assertTrue(functionBody.contains("current_gen.store"), "generation must update synchronously")
        assertTrue(functionBody.contains("notify.notify_waiters"), "generation waiters must wake synchronously")
    }
}
