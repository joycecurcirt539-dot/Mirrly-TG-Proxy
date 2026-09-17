package com.mirrly.tgproxy.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class PingProbeGenerationGuardTest {
    @Test
    fun `slow success A cannot become active health result B`() = runBlocking {
        var target = "worker-a.example"
        var network = 1L
        var profile = 1L
        var config = 1L
        val started = CompletableDeferred<String>()
        val release = CompletableDeferred<PingProbeResult>()
        val callbackCount = AtomicInteger(0)
        val recoveryCount = AtomicInteger(0)
        val engine = PingEngine(
            targetProvider = { target },
            networkGenerationProvider = { network },
            profileRevisionProvider = { profile },
            configGenerationProvider = { config },
            onSelfHealingRequired = { recoveryCount.incrementAndGet() },
            probeExecutor = { probedTarget ->
                started.complete(probedTarget)
                release.await()
            }
        )
        engine.onProbeCompleted = { _, _ -> callbackCount.incrementAndGet() }

        val pending = async { engine.triggerSingleProbe() }
        assertEquals("worker-a.example", started.await())
        target = "worker-b.example"
        network++
        profile++
        config++
        release.complete(PingProbeResult(rawRttMs = 10L, success = true))
        pending.await()

        assertEquals(-1L, engine.currentSnapshot.rawPingMs)
        assertEquals(0, callbackCount.get())
        assertEquals(0, recoveryCount.get())

        val b = engine.captureProbeStamp()
        assertTrue(engine.applyProbeIfCurrent(b, PingProbeResult(rawRttMs = 80L, success = true)))
        assertEquals(80L, engine.currentSnapshot.rawPingMs)
        assertEquals(1, callbackCount.get())

        val staleFailure = b.copy(configGeneration = b.configGeneration - 1)
        repeat(3) {
            assertFalse(engine.applyProbeIfCurrent(
                staleFailure,
                PingProbeResult(rawRttMs = -1L, success = false, failureType = FailureType.CONNECT_TIMEOUT)
            ))
        }
        assertEquals(0, recoveryCount.get(), "A must not invoke recovery/reset for B")
        assertEquals(80L, engine.currentSnapshot.rawPingMs)
    }
}
