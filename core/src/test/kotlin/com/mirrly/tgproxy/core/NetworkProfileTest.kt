package com.mirrly.tgproxy.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkProfileTest {
    private val sample = NetworkProfile(
        generation = 41,
        validated = true,
        suspended = false,
        metered = true,
        roaming = true,
        congested = true,
        transport = NetworkTransport.CELLULAR,
        cellular = true,
        estimatedDownKbps = 52_000,
        estimatedUpKbps = 11_000,
        screenOn = true,
        powerSaveMode = false,
        transportSli = TransportSli(
            measuredAtMs = 123_456,
            smoothedRttMs = 87,
            jitterMs = 13,
            successRatePercent = 94,
            downstreamBps = 2_500_000,
            upstreamBps = 440_000,
            bufferbloatMs = 35
        )
    ).normalized()

    @Test
    fun `profile JSON round trip preserves all fields`() {
        assertEquals(sample, NetworkProfile.fromJsonString(sample.toJsonString()))
    }

    @Test
    fun `runtime-only update preserves environment generation and SLI`() {
        val updated = sample.withRuntime(screenOn = false, powerSaveMode = true)

        assertEquals(sample.generation, updated.generation)
        assertEquals(sample.transport, updated.transport)
        assertEquals(sample.estimatedDownKbps, updated.estimatedDownKbps)
        assertEquals(sample.estimatedUpKbps, updated.estimatedUpKbps)
        assertEquals(sample.transportSli, updated.transportSli)
        assertEquals(NetworkPowerMode.POWER_SAVE, updated.powerMode)
    }

    @Test
    fun `SLI-only update preserves network and runtime fields`() {
        val nextSli = sample.transportSli.copy(smoothedRttMs = 144, jitterMs = 29)
        val updated = sample.withTransportSli(nextSli)

        assertEquals(sample.generation, updated.generation)
        assertEquals(sample.transport, updated.transport)
        assertEquals(sample.validated, updated.validated)
        assertEquals(sample.metered, updated.metered)
        assertEquals(sample.powerMode, updated.powerMode)
        assertEquals(nextSli, updated.transportSli)
    }

    @Test
    fun `environment-only update preserves runtime and SLI`() {
        val updated = sample.withEnvironment(
            NetworkEnvironment(
                validated = true,
                transport = NetworkTransport.WIFI,
                estimatedDownKbps = 120_000,
                estimatedUpKbps = 60_000
            )
        )

        assertEquals(sample.generation, updated.generation)
        assertEquals(sample.screenOn, updated.screenOn)
        assertEquals(sample.powerMode, updated.powerMode)
        assertEquals(sample.transportSli, updated.transportSli)
        assertTrue(updated.wifi)
        assertFalse(updated.cellular)
    }

    @Test
    fun `native profile setter has no scorer reset side effects`() {
        val source = File("..", "mirrlyengine/src/network_profile.rs").canonicalFile.readText()
        val applyStart = source.indexOf("pub fn apply_profile(")
        val nextFunction = source.indexOf("\npub fn set_profile_json", applyStart)

        assertTrue(applyStart >= 0 && nextFunction > applyStart)
        val applyBody = source.substring(applyStart, nextFunction)
        assertFalse(applyBody.contains("reset_"))
        assertFalse(applyBody.contains("scorer"))
        assertTrue(applyBody.contains("notify_generation_change"))
    }

    @Test
    fun `round-trip FFI is declared on both Kotlin and Rust sides`() {
        val kotlin = File(
            "src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt"
        ).canonicalFile.readText()
        val rust = File("..", "mirrlyengine/src/lib.rs").canonicalFile.readText()

        assertTrue(kotlin.contains("fun SetNetworkProfileJson(json: String): Int"))
        assertTrue(kotlin.contains("fun GetNetworkProfileJson(): Pointer?"))
        assertTrue(rust.contains("fn SetNetworkProfileJson"))
        assertTrue(rust.contains("fn GetNetworkProfileJson"))
    }
}
