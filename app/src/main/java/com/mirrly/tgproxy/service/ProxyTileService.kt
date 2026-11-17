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
        val server = MirrlyApplication.instance.proxyServer
        val serviceIntent = Intent(this, ProxyForegroundService::class.java)

        val willBeRunning = !server.isRunning
        if (willBeRunning && MirrlyApplication.instance.config.isSocks5Mode && MirrlyApplication.instance.config.getEffectiveCfDomain().isBlank()) {
            android.widget.Toast.makeText(this, getString(R.string.socks5_no_worker_error), android.widget.Toast.LENGTH_LONG).show()
            updateTileState(false)
            return
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
