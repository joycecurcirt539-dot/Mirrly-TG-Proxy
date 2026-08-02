package com.mirrly.tgproxy.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.mirrly.tgproxy.MirrlyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProxyForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var updateJob: Job? = null
    private var wakeLockJob: Job? = null
    private var networkObserver: NetworkChangeObserver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START = "com.mirrly.tgproxy.START"
        const val ACTION_STOP = "com.mirrly.tgproxy.STOP"
        const val ACTION_RESTART = "com.mirrly.tgproxy.RESTART"
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60 * 1000
        private const val WAKELOCK_REFRESH_MS = 25L * 60 * 1000
        private const val TAG = "ProxyForegroundService"
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)

        networkObserver = NetworkChangeObserver(this) { networkType ->
            if (networkType == "DISCONNECTED") return@NetworkChangeObserver
            val app = MirrlyApplication.instance
            if (app.proxyServer.isRunning && app.prefsManager.isAutoReconnectEnabled()) {
                serviceScope.launch {
                    try {
                        delay(1000)
                        if (app.proxyServer.isRunning) {
                            app.proxyServer.stop()
                            delay(500)
                            app.proxyServer.start(cacheDir)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        networkObserver?.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProxyService()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RESTART) {
            serviceScope.launch {
                try {
                    val server = MirrlyApplication.instance.proxyServer
                    server.stop()
                    delay(300)
                    server.start(cacheDir)
                } catch (_: Exception) {}
            }
            return START_REDELIVER_INTENT
        }

        val notification = NotificationHelper.buildNotification(
            this,
            "Mirrly TG Proxy работает",
            "Порт: ${MirrlyApplication.instance.config.bindPort} | Скорость: 0 Б/с"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }

        acquireWakeLock()

        val server = MirrlyApplication.instance.proxyServer
        if (!server.isRunning) {
            server.start(cacheDir)
        }

        ProxyTileService.requestSync(this)
        startNotificationUpdates()
        startWakeLockRefresh()
        return START_REDELIVER_INTENT
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MirrlyProxy::ServiceWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(WAKELOCK_TIMEOUT_MS)
                }
                Log.d(TAG, "WakeLock acquired for 30 minutes")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun startWakeLockRefresh() {
        wakeLockJob?.cancel()
        wakeLockJob = serviceScope.launch {
            while (isActive) {
                delay(WAKELOCK_REFRESH_MS)
                try {
                    releaseWakeLock()
                    acquireWakeLock()
                    Log.d(TAG, "WakeLock refreshed")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to refresh WakeLock", e)
                }
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock", e)
        }
        wakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "onTaskRemoved: proxy service active, task removed from recents")
    }

    private fun startNotificationUpdates() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val server = MirrlyApplication.instance.proxyServer
            var secondsCounter = 0

            while (isActive && server.isRunning) {
                delay(1000)
                secondsCounter++
                if (secondsCounter >= 10) {
                    ValueTriggerManager.addActiveSeconds(this@ProxyForegroundService, secondsCounter)
                    secondsCounter = 0
                }

                val stats = server.stats
                stats.updateSpeed()

                val dlSpeed = humanBytes(stats.downloadSpeedBps)
                val ulSpeed = humanBytes(stats.uploadSpeedBps)
                val netName = when (networkObserver?.getCurrentNetworkTypeName()) {
                    "Wi-Fi" -> "Wi-Fi"
                    "Cellular" -> "Мобильная сеть"
                    else -> "Сеть активна"
                }
                val text = "↓ $dlSpeed/с  ↑ $ulSpeed/с | $netName | Сокетов: ${stats.activeConnections.get()}"

                val updatedNotification = NotificationHelper.buildNotification(
                    this@ProxyForegroundService,
                    "Обход Telegram активен",
                    text
                )
                notificationManager.notify(NotificationHelper.NOTIFICATION_ID, updatedNotification)
            }
        }
    }

    private fun stopProxyService() {
        networkObserver?.stop()
        networkObserver = null
        wakeLockJob?.cancel()
        wakeLockJob = null
        releaseWakeLock()
        MirrlyApplication.instance.proxyServer.stop()
        updateJob?.cancel()
        ProxyTileService.requestSync(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopProxyService()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

fun humanBytes(bytes: Long): String {
    var n = bytes.toDouble()
    for (unit in arrayOf("B", "KB", "MB", "GB")) {
        if (Math.abs(n) < 1024) {
            return String.format("%.1f %s", n, unit)
        }
        n /= 1024.0
    }
    return String.format("%.1f TB", n)
}
