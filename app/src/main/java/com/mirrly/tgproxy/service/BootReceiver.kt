package com.mirrly.tgproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mirrly.tgproxy.core.AppLogger

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            AppLogger.i("BootReceiver", "System boot intent received: $action")

            if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                UpdateManager.onAppInit(context)
            }

            val prefsManager = PreferencesManager(context)
            val config = prefsManager.loadConfig()

            // Восстановление будильников расписания после перезагрузки
            ScheduleManager.syncSchedule(context)

            if (config.autostartOnBoot) {
                if (prefsManager.isVpnModeEnabled() && MirrlyVpnService.prepare(context) == null) {
                    AppLogger.i("BootReceiver", "Автозапуск при загрузке: запуск системного VPN...")
                    MirrlyVpnService.start(context)
                } else {
                    AppLogger.i("BootReceiver", "Автозапуск при загрузке: запуск службы прокси...")
                    val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                        this.action = ProxyForegroundService.ACTION_START
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        AppLogger.e("BootReceiver", "Сбой автозапуска службы при загрузке: ${e.message}")
                    }
                }
            } else {
                AppLogger.i("BootReceiver", "Autostart on boot is disabled")
            }
        }
    }
}
