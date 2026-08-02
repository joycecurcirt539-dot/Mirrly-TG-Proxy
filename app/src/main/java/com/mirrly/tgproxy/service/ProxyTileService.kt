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
    }

    private fun updateTileState(forcedState: Boolean? = null) {
        val tile = qsTile ?: return
        val isRunning = forcedState ?: MirrlyApplication.instance.proxyServer.isRunning
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isRunning) "Mirrly Proxy ON" else "Mirrly Proxy OFF"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_proxy)
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
