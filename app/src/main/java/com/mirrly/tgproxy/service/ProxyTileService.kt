package com.mirrly.tgproxy.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.core.ProxyMode

/**
 * Плитка быстрых настроек Android для управления исключительно локальным прокси (MTProto / SOCKS5).
 * Не затрагивает режим системного VPN (MirrlyVpnService вынесен в независимый VpnTileService).
 */
@RequiresApi(Build.VERSION_CODES.N)
class ProxyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val app = MirrlyApplication.instance
        val server = app.proxyServer
        val serviceIntent = Intent(this, ProxyForegroundService::class.java)

        val willBeRunning = !server.isRunning
        var tileFlowsealNotice = Socks5UplinkPolicy.QuickTileNotice.NONE
        if (willBeRunning) {
            val prefs = app.prefsManager
            val activeSocks5Worker = prefs.getActiveWorker(ProxyMode.SOCKS5)
            val activeSocks5Domain = ProxyConfig.sanitizeDomain(activeSocks5Worker.domain)
            val hasCustomWorkers = prefs.getCustomWorkers().any { ProxyConfig.sanitizeDomain(it.domain).isNotBlank() }
            val hasUsableWorker = app.config.getEffectiveCfDomain().isNotBlank() ||
                activeSocks5Domain.isNotBlank() ||
                hasCustomWorkers

            val decision = Socks5UplinkPolicy.resolveQuickTileStart(
                isSocks5Mode = app.config.isSocks5Mode,
                configUplinkMode = app.config.socks5UplinkMode,
                persistedUplinkMode = prefs.getSocks5UplinkMode(),
                isCloudflareAuthorized = prefs.isCloudflareAuthorized(),
                hasUsableWorker = hasUsableWorker
            )
            tileFlowsealNotice = decision.notice

            if (decision.shouldPersistUplinkMode) {
                prefs.setSocks5UplinkMode(decision.targetUplinkMode)
                app.config.socks5UplinkMode = decision.targetUplinkMode
                if (decision.targetUplinkMode == ProxyConfig.SOCKS5_UPLINK_FLOWSEAL && !hasUsableWorker) {
                    app.config.customCfDomain = ""
                }
                app.saveConfig()
            } else if (app.config.isSocks5Mode &&
                decision.targetUplinkMode == ProxyConfig.SOCKS5_UPLINK_WORKER &&
                app.config.getEffectiveCfDomain().isBlank() &&
                activeSocks5Domain.isNotBlank()
            ) {
                app.config.customCfDomain = activeSocks5Domain
                app.saveConfig()
            }
        }

        try {
            if (server.isRunning) {
                serviceIntent.action = ProxyForegroundService.ACTION_STOP
                startService(serviceIntent)
            } else {
                serviceIntent.action = ProxyForegroundService.ACTION_START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                if (tileFlowsealNotice != Socks5UplinkPolicy.QuickTileNotice.NONE) {
                    NotificationHelper.showSocks5FlowsealFallbackNotification(this, tileFlowsealNotice)
                }
            }
            updateTileState(willBeRunning)
        } catch (e: Exception) {
            AppLogger.e("ProxyTileService", "Failed to start/stop service from quick settings: ${e.message}")
            updateTileState()
        }
    }

    private fun updateTileState(forcedState: Boolean? = null) {
        val tile = qsTile ?: return
        val isProxy = MirrlyApplication.instance.proxyServer.isRunning
        val isRunning = forcedState ?: isProxy

        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_proxy_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_proxy)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRunning) getString(R.string.tile_subtitle_connected) else getString(R.string.tile_subtitle_disconnected)
        }

        tile.updateTile()
    }

    companion object {
        fun requestSync(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestListeningState(
                        context.applicationContext,
                        ComponentName(context.applicationContext, ProxyTileService::class.java)
                    )
                } catch (_: Exception) {}
            }
        }
    }
}
