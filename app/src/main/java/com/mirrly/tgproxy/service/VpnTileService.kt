package com.mirrly.tgproxy.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.ui.theme.VpnUiState

/**
 * Отдельная плитка быстрых настроек Android для режима системного VPN (MirrlyVpnService).
 * Требования:
 * - Название строго "Mirrly VPN" (без "вкл/выкл").
 * - Иконка в выключенном состоянии: ic_vpn (щит с замком).
 * - Иконка во включенном состоянии: ic_qs_warp_active (облако Cloudflare WARP).
 */
@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = MirrlyVpnService.isRunning

        if (isRunning) {
            MirrlyVpnService.stop(this)
            updateTileState(false)
            return
        }

        // Проверяем наличие системного разрешения VpnService
        val prepIntent = MirrlyVpnService.prepare(this)
        if (prepIntent != null) {
            prepIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pi = PendingIntent.getActivity(
                        this,
                        0,
                        prepIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    startActivityAndCollapse(pi)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(prepIntent)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка запуска системного диалога разрешения VPN: ${e.message}")
            }
            return
        }

        MirrlyVpnService.start(this)
        updateTileState(true)
    }

    private fun updateTileState(forcedState: Boolean? = null) {
        val tile = qsTile ?: return
        val isRunning = forcedState ?: MirrlyVpnService.isRunning
        val isConnecting = !isRunning && MirrlyVpnService.vpnState.value == VpnUiState.CONNECTING

        tile.state = when {
            isRunning -> Tile.STATE_ACTIVE
            isConnecting -> Tile.STATE_INACTIVE
            else -> Tile.STATE_INACTIVE
        }

        // Строгое название "Mirrly VPN" по требованию пользователя
        tile.label = getString(R.string.tile_vpn_label)

        // Иконка: облачко WARP при активном туннеле, выключенное контурное облачко (без заливки) при неактивном
        tile.icon = Icon.createWithResource(
            this,
            if (isRunning) R.drawable.ic_qs_warp_active else R.drawable.ic_qs_warp_inactive
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                isRunning -> getString(R.string.tile_subtitle_connected)
                isConnecting -> getString(R.string.tile_subtitle_connecting)
                else -> getString(R.string.tile_subtitle_disconnected)
            }
        }

        tile.updateTile()
    }

    companion object {
        private const val TAG = "VpnTileService"

        fun requestSync(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestListeningState(
                        context.applicationContext,
                        ComponentName(context.applicationContext, VpnTileService::class.java)
                    )
                } catch (_: Exception) {}
            }
        }
    }
}
