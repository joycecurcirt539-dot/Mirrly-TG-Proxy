/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.ProxyMode
import com.mirrly.tgproxy.core.SpeedPreset

/**
 * Cross-process IPC bridge receiver between Mirrly TG Proxy and MirrlyGram.
 * Enables zero-latency status broadcasts, speed preset switching, mode toggles,
 * and background watchdog resurrection.
 */
class MirrlyBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = MirrlyApplication.instance
        AppLogger.i(TAG, "Bridge received action: $action")

        when (action) {
            ACTION_QUERY_STATUS -> {
                broadcastStatus(context)
            }
            ACTION_BRIDGE_CMD -> {
                val cmd = intent.getStringExtra(EXTRA_CMD) ?: return
                AppLogger.i(TAG, "Bridge executing command: $cmd")
                when (cmd) {
                    CMD_START -> {
                        app.prefsManager.setProxyActive(true)
                        val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                            this.action = ProxyForegroundService.ACTION_START
                        }
                        startServiceCompat(context, serviceIntent)
                    }
                    CMD_STOP -> {
                        app.prefsManager.setProxyActive(false)
                        val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                            this.action = ProxyForegroundService.ACTION_STOP
                        }
                        startServiceCompat(context, serviceIntent)
                    }
                    CMD_RESTART -> {
                        ProxyForegroundService.restartIfRunning(context)
                    }
                    CMD_CYCLE_PRESET -> {
                        val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                            this.action = ProxyForegroundService.ACTION_CYCLE_PRESET
                        }
                        startServiceCompat(context, serviceIntent)
                    }
                    CMD_SET_MODE -> {
                        val modeStr = intent.getStringExtra(EXTRA_MODE)
                        if (!modeStr.isNullOrBlank()) {
                            val targetMode = if (modeStr.equals("MTPROTO", ignoreCase = true)) {
                                ProxyMode.MTPROTO
                            } else {
                                ProxyMode.SOCKS5
                            }
                            app.config.proxyModeName = targetMode.name
                            app.prefsManager.saveConfig(app.config)
                            ProxyForegroundService.restartIfRunning(context)
                        }
                    }
                    CMD_SET_PRESET -> {
                        val presetStr = intent.getStringExtra(EXTRA_PRESET)
                        if (!presetStr.isNullOrBlank()) {
                            try {
                                val targetPreset = SpeedPreset.valueOf(presetStr.uppercase())
                                app.config.speedPresetName = targetPreset.name
                                app.prefsManager.saveConfig(app.config)
                                ProxyForegroundService.restartIfRunning(context)
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Invalid speed preset: $presetStr")
                            }
                        }
                    }
                    CMD_TOGGLE_VPN -> {
                        if (MirrlyVpnService.isRunning) {
                            MirrlyVpnService.stop(context)
                        } else {
                            val prep = MirrlyVpnService.prepare(context)
                            if (prep == null) {
                                MirrlyVpnService.start(context)
                            }
                        }
                    }
                    CMD_PING_TEST -> {
                        try {
                            app.proxyServer.measurePingAsync()
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Ping test trigger failed: ${e.message}")
                        }
                    }
                    CMD_ENSURE_RUNNING -> {
                        if (!app.proxyServer.isRunning) {
                            app.prefsManager.setProxyActive(true)
                            val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                                this.action = ProxyForegroundService.ACTION_START
                            }
                            startServiceCompat(context, serviceIntent)
                        }
                    }
                    CMD_TOGGLE_UPLINK -> {
                        if (app.config.isSocks5Mode) {
                            app.config.socks5UplinkMode = if (app.config.socks5UplinkMode == 1) 0 else 1
                        } else {
                            app.config.useWorkerMtproto = !app.config.useWorkerMtproto
                        }
                        app.prefsManager.saveConfig(app.config)
                        ProxyForegroundService.restartIfRunning(context)
                    }
                }
                broadcastStatus(context)
            }
        }
    }

    private fun startServiceCompat(context: Context, serviceIntent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start service: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MirrlyBridge"

        const val ACTION_BRIDGE_CMD = "com.mirrly.tgproxy.BRIDGE_CMD"
        const val ACTION_QUERY_STATUS = "com.mirrly.tgproxy.QUERY_STATUS"
        const val ACTION_STATUS_BROADCAST = "com.mirrly.tgproxy.STATUS_BROADCAST"

        const val EXTRA_CMD = "cmd"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PRESET = "preset"

        const val CMD_START = "START"
        const val CMD_STOP = "STOP"
        const val CMD_RESTART = "RESTART"
        const val CMD_CYCLE_PRESET = "CYCLE_PRESET"
        const val CMD_SET_MODE = "SET_MODE"
        const val CMD_SET_PRESET = "SET_PRESET"
        const val CMD_TOGGLE_VPN = "TOGGLE_VPN"
        const val CMD_PING_TEST = "PING_TEST"
        const val CMD_ENSURE_RUNNING = "ENSURE_RUNNING"
        const val CMD_TOGGLE_UPLINK = "TOGGLE_UPLINK"

        fun broadcastStatus(context: Context) {
            try {
                val app = MirrlyApplication.instance
                val server = app.proxyServer
                val stats = server.stats
                val isRunning = server.isRunning
                val config = app.config

                val broadcastIntent = Intent(ACTION_STATUS_BROADCAST).apply {
                    putExtra("is_running", isRunning)
                    putExtra("proxy_mode", if (config.isSocks5Mode) "SOCKS5" else "MTPROTO")
                    putExtra("active_port", config.activePort)
                    putExtra("bind_host", config.bindHost)
                    putExtra("speed_preset", config.speedPreset.name)
                    putExtra("dl_bps", stats.downloadSpeedBps)
                    putExtra("ul_bps", stats.uploadSpeedBps)
                    putExtra("bytes_in", stats.totalBytesReceived.get())
                    putExtra("bytes_out", stats.totalBytesSent.get())
                    putExtra("ping_ms", server.currentPingMs)
                    putExtra("active_connections", stats.activeConnections.get())
                    putExtra("uplink", if (config.isSocks5Mode) "Anycast CDN" else (if (config.useWorkerMtproto) "Cloudflare Worker" else "Anycast CDN"))
                    putExtra("vpn_running", MirrlyVpnService.isRunning)
                }
                context.sendBroadcast(broadcastIntent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "broadcastStatus error: ${e.message}")
            }
        }
    }
}
