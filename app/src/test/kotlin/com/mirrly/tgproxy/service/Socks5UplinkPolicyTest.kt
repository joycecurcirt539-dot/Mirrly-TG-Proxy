package com.mirrly.tgproxy.service

import com.mirrly.tgproxy.core.ProxyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5UplinkPolicyTest {

    @Test
    fun testStartupMigrationResetsStaleWorkerModeWhenNoValidWorkerExists() {
        val state = Socks5UplinkPolicy.resolveStartupState(
            storedUplinkMode = ProxyConfig.SOCKS5_UPLINK_WORKER,
            v2MigrationDone = false,
            hasValidCustomWorker = false
        )

        assertEquals(ProxyConfig.SOCKS5_UPLINK_FLOWSEAL, state.effectiveUplinkMode)
        assertTrue(state.shouldPersistMode)
        assertTrue(state.markV2Migrated)
    }

    @Test
    fun testStartupMigrationPreservesWorkerModeWhenValidCustomWorkerExists() {
        val state = Socks5UplinkPolicy.resolveStartupState(
            storedUplinkMode = ProxyConfig.SOCKS5_UPLINK_WORKER,
            v2MigrationDone = false,
            hasValidCustomWorker = true
        )

        assertEquals(ProxyConfig.SOCKS5_UPLINK_WORKER, state.effectiveUplinkMode)
        assertFalse(state.shouldPersistMode)
        assertTrue(state.markV2Migrated)
    }

    @Test
    fun testStartupPreservesExplicitModeAfterV2Migration() {
        val state = Socks5UplinkPolicy.resolveStartupState(
            storedUplinkMode = ProxyConfig.SOCKS5_UPLINK_WORKER,
            v2MigrationDone = true,
            hasValidCustomWorker = false
        )

        assertEquals(ProxyConfig.SOCKS5_UPLINK_WORKER, state.effectiveUplinkMode)
        assertFalse(state.shouldPersistMode)
        assertFalse(state.markV2Migrated)
    }

    @Test
    fun testStartupCoercesUnknownModeToFlowseal() {
        val state = Socks5UplinkPolicy.resolveStartupState(
            storedUplinkMode = 99,
            v2MigrationDone = true,
            hasValidCustomWorker = false
        )

        assertEquals(ProxyConfig.SOCKS5_UPLINK_FLOWSEAL, state.effectiveUplinkMode)
        assertTrue(state.shouldPersistMode)
    }

    @Test
    fun testQuickTileSocks5NoAuthNoWorkersWithFlowsealStartsFlowsealAndNotifies() {
        val decision = Socks5UplinkPolicy.resolveQuickTileStart(
            isSocks5Mode = true,
            configUplinkMode = ProxyConfig.SOCKS5_UPLINK_FLOWSEAL,
            persistedUplinkMode = ProxyConfig.SOCKS5_UPLINK_FLOWSEAL,
            isCloudflareAuthorized = false,
            hasUsableWorker = false
        )

        assertEquals(ProxyConfig.SOCKS5_UPLINK_FLOWSEAL, decision.targetUplinkMode)
        assertFalse(decision.shouldPersistUplinkMode)
        assertEquals(Socks5UplinkPolicy.QuickTileNotice.NO_AUTH_NO_WORKERS, decision.notice)
    }

    @Test
    fun testQuickTileSocks5NoAuthNoWorkersWithStaleWorkerModeSwitchesToFlowsealAndNotifies() {
        val decision = Socks5UplinkPolicy.resolveQuickTileStart(
            isSocks5Mode = true,
            configUplinkMode = ProxyConfig.SOCKS5_UPLINK_WORKER,
            persistedUplinkMode = ProxyConfig.SOCKS5_UPLINK_WORKER,
            isCloudflareAuthorized = false,
            hasUsableWorker = false
        )

        assertEquals(ProxyConfig.SOCKS5_UPLINK_FLOWSEAL, decision.targetUplinkMode)
        assertTrue(decision.shouldPersistUplinkMode)
        assertEquals(Socks5UplinkPolicy.QuickTileNotice.NO_AUTH_NO_WORKERS, decision.notice)
    }

    @Test
    fun testQuickTileSocks5AuthorizedWithoutWorkersFallbacksFromWorkerToFlowseal() {
        val decision = Socks5UplinkPolicy.resolveQuickTileStart(
            isSocks5Mode = true,
            configUplinkMode = ProxyConfig.SOCKS5_UPLINK_WORKER,
            persistedUplinkMode = ProxyConfig.SOCKS5_UPLINK_WORKER,
            isCloudflareAuthorized = true,
            hasUsableWorker = false
        )

        assertEquals(ProxyConfig.SOCKS5_UPLINK_FLOWSEAL, decision.targetUplinkMode)
        assertTrue(decision.shouldPersistUplinkMode)
        assertEquals(Socks5UplinkPolicy.QuickTileNotice.NO_WORKER_FALLBACK, decision.notice)
    }

    @Test
    fun testQuickTileSocks5AuthorizedWithExplicitFlowsealDoesNotNotify() {
        val decision = Socks5UplinkPolicy.resolveQuickTileStart(
            isSocks5Mode = true,
            configUplinkMode = ProxyConfig.SOCKS5_UPLINK_FLOWSEAL,
            persistedUplinkMode = ProxyConfig.SOCKS5_UPLINK_FLOWSEAL,
            isCloudflareAuthorized = true,
            hasUsableWorker = false
        )

        assertEquals(ProxyConfig.SOCKS5_UPLINK_FLOWSEAL, decision.targetUplinkMode)
        assertFalse(decision.shouldPersistUplinkMode)
        assertEquals(Socks5UplinkPolicy.QuickTileNotice.NONE, decision.notice)
    }

    @Test
    fun testQuickTileSocks5WithValidWorkerPreservesSelectedUplinkWithoutFallbackNotice() {
        val workerDecision = Socks5UplinkPolicy.resolveQuickTileStart(
            isSocks5Mode = true,
            configUplinkMode = ProxyConfig.SOCKS5_UPLINK_WORKER,
            persistedUplinkMode = ProxyConfig.SOCKS5_UPLINK_WORKER,
            isCloudflareAuthorized = false,
            hasUsableWorker = true
        )
        assertEquals(ProxyConfig.SOCKS5_UPLINK_WORKER, workerDecision.targetUplinkMode)
        assertFalse(workerDecision.shouldPersistUplinkMode)
        assertEquals(Socks5UplinkPolicy.QuickTileNotice.NONE, workerDecision.notice)

        val flowsealDecision = Socks5UplinkPolicy.resolveQuickTileStart(
            isSocks5Mode = true,
            configUplinkMode = ProxyConfig.SOCKS5_UPLINK_FLOWSEAL,
            persistedUplinkMode = ProxyConfig.SOCKS5_UPLINK_FLOWSEAL,
            isCloudflareAuthorized = false,
            hasUsableWorker = true
        )
        assertEquals(ProxyConfig.SOCKS5_UPLINK_FLOWSEAL, flowsealDecision.targetUplinkMode)
        assertFalse(flowsealDecision.shouldPersistUplinkMode)
        assertEquals(Socks5UplinkPolicy.QuickTileNotice.NONE, flowsealDecision.notice)
    }

    @Test
    fun testQuickTileMtprotoNeverEmitsSocks5Notice() {
        val decision = Socks5UplinkPolicy.resolveQuickTileStart(
            isSocks5Mode = false,
            configUplinkMode = ProxyConfig.SOCKS5_UPLINK_WORKER,
            persistedUplinkMode = ProxyConfig.SOCKS5_UPLINK_WORKER,
            isCloudflareAuthorized = false,
            hasUsableWorker = false
        )

        assertEquals(ProxyConfig.SOCKS5_UPLINK_WORKER, decision.targetUplinkMode)
        assertFalse(decision.shouldPersistUplinkMode)
        assertEquals(Socks5UplinkPolicy.QuickTileNotice.NONE, decision.notice)
    }
}
