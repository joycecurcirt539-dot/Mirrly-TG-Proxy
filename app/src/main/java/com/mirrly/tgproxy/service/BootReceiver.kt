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
            AppLogger.i("BootReceiver", "Системный сигнал загрузки устройства: $action")

            val prefsManager = PreferencesManager(context)
            val config = prefsManager.loadConfig()

            if (config.autostartOnBoot) {
                AppLogger.i("BootReceiver", "Автозапуск при включении активен, запускается служба прокси...")
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
                    // On Android 12+ ForegroundServiceStartNotAllowedException may be thrown
                    // if the system is in a restricted state (locked screen, Doze, battery saver)
                    AppLogger.e("BootReceiver", "Не удалось запустить службу при загрузке: ${e.message}")
                }
            } else {
                AppLogger.i("BootReceiver", "Автозапуск при включении выключен")
            }
        }
    }
}
