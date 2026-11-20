package com.mirrly.tgproxy.service

import com.mirrly.tgproxy.core.ProxyConfig

/**
 * Pure policy helpers for SOCKS5 uplink startup migration and Quick Settings tile launch behavior.
 */
object Socks5UplinkPolicy {

    /**
     * Resolved startup uplink state after evaluating v2 migration and value validity.
     */
    data class StartupState(
        val effectiveUplinkMode: Int,
        val shouldPersistMode: Boolean,
        val markV2Migrated: Boolean
    )

    /**
     * Notification variant to post after starting SOCKS5 from the Quick Settings tile.
     */
    enum class QuickTileNotice {
        NONE,
        NO_AUTH_NO_WORKERS,
        NO_WORKER_FALLBACK
    }

    /**
     * Decision for starting the proxy from the Android Quick Settings tile.
     */
    data class QuickTileStartDecision(
        val targetUplinkMode: Int,
        val shouldPersistUplinkMode: Boolean,
        val notice: QuickTileNotice
    )

    /**
     * Normalizes stored SOCKS5 uplink mode during config load.
     * Ensures users without valid custom workers are migrated off stale Worker mode (0)
     * onto Anycast CDN Flowseal (1).
     */
    fun resolveStartupState(
        storedUplinkMode: Int,
        v2MigrationDone: Boolean,
        hasValidCustomWorker: Boolean
    ): StartupState {
        val isKnownMode = storedUplinkMode == ProxyConfig.SOCKS5_UPLINK_WORKER ||
            storedUplinkMode == ProxyConfig.SOCKS5_UPLINK_FLOWSEAL

        if (!isKnownMode) {
            return StartupState(
                effectiveUplinkMode = ProxyConfig.SOCKS5_UPLINK_FLOWSEAL,
                shouldPersistMode = true,
                markV2Migrated = !v2MigrationDone
            )
        }

        if (!v2MigrationDone) {
            val shouldResetToFlowseal = !hasValidCustomWorker &&
                storedUplinkMode != ProxyConfig.SOCKS5_UPLINK_FLOWSEAL
            return StartupState(
                effectiveUplinkMode = if (shouldResetToFlowseal) ProxyConfig.SOCKS5_UPLINK_FLOWSEAL else storedUplinkMode,
                shouldPersistMode = shouldResetToFlowseal,
                markV2Migrated = true
            )
        }

        return StartupState(
            effectiveUplinkMode = storedUplinkMode,
            shouldPersistMode = false,
            markV2Migrated = false
        )
    }

    /**
     * Resolves how the Android Quick Settings tile should start the proxy in SOCKS5 mode.
     * Never blocks startup with a missing-worker error:
     * - If neither Cloudflare authorization nor any valid worker exists, ensures Flowseal CDN (1)
     *   and emits [QuickTileNotice.NO_AUTH_NO_WORKERS].
     * - Fallback: if Cloudflare is authorized without workers while Worker mode (0) is active,
     *   switches to Flowseal CDN (1) and emits [QuickTileNotice.NO_WORKER_FALLBACK].
     */
    fun resolveQuickTileStart(
        isSocks5Mode: Boolean,
        configUplinkMode: Int,
        persistedUplinkMode: Int,
        isCloudflareAuthorized: Boolean,
        hasUsableWorker: Boolean
    ): QuickTileStartDecision {
        val normalizedPersisted = if (
            persistedUplinkMode == ProxyConfig.SOCKS5_UPLINK_WORKER ||
            persistedUplinkMode == ProxyConfig.SOCKS5_UPLINK_FLOWSEAL
        ) {
            persistedUplinkMode
        } else {
            ProxyConfig.SOCKS5_UPLINK_FLOWSEAL
        }

        if (!isSocks5Mode) {
            return QuickTileStartDecision(
                targetUplinkMode = normalizedPersisted,
                shouldPersistUplinkMode = normalizedPersisted != configUplinkMode || normalizedPersisted != persistedUplinkMode,
                notice = QuickTileNotice.NONE
            )
        }

        if (!hasUsableWorker) {
            val notice = when {
                !isCloudflareAuthorized -> QuickTileNotice.NO_AUTH_NO_WORKERS
                configUplinkMode != ProxyConfig.SOCKS5_UPLINK_FLOWSEAL ||
                    normalizedPersisted != ProxyConfig.SOCKS5_UPLINK_FLOWSEAL -> QuickTileNotice.NO_WORKER_FALLBACK
                else -> QuickTileNotice.NONE
            }
            return QuickTileStartDecision(
                targetUplinkMode = ProxyConfig.SOCKS5_UPLINK_FLOWSEAL,
                shouldPersistUplinkMode = configUplinkMode != ProxyConfig.SOCKS5_UPLINK_FLOWSEAL ||
                    persistedUplinkMode != ProxyConfig.SOCKS5_UPLINK_FLOWSEAL,
                notice = notice
            )
        }

        return QuickTileStartDecision(
            targetUplinkMode = normalizedPersisted,
            shouldPersistUplinkMode = normalizedPersisted != configUplinkMode || normalizedPersisted != persistedUplinkMode,
            notice = QuickTileNotice.NONE
        )
    }
}
