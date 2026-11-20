package com.mirrly.tgproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.ReleaseInfo
import com.mirrly.tgproxy.ui.MainActivity

enum class ProxyStatusIndicator(val iconRes: Int, val color: Int) {
    GREEN(R.drawable.ic_stat_proxy_connected, 0xFF10B981.toInt()),
    YELLOW(R.drawable.ic_stat_proxy_warning, 0xFFF59E0B.toInt()),
    RED(R.drawable.ic_stat_proxy_error, 0xFFEF4444.toInt())
}

object NotificationHelper {
    const val CHANNEL_ID = "mirrly_proxy_channel"
    const val NOTIFICATION_ID = 1001

    const val UPDATE_CHANNEL_ID = "mirrly_update_channel"
    const val UPDATE_NOTIFICATION_ID = 2002

    const val SUMMARY_NOTIFICATION_ID = 3003

    const val TIMER_CHANNEL_ID = "mirrly_timer_channel"
    const val TIMER_WARNING_NOTIFICATION_ID = 4004
    const val TIMER_EXPIRED_NOTIFICATION_ID = 4005

    const val FAILOVER_CHANNEL_ID = "mirrly_failover_channel"
    const val FAILOVER_NOTIFICATION_ID = 5005
    const val PORT_CONFLICT_NOTIFICATION_ID = 5008
    const val SOCKS5_FLOWSEAL_FALLBACK_NOTIFICATION_ID = 5009
    const val EXTRA_OPEN_PORT_CONFLICT = "extra_open_port_conflict"

    const val BATTERY_GUARD_CHANNEL_ID = "mirrly_battery_guard_channel"
    const val BATTERY_GUARD_WARNING_NOTIFICATION_ID = 6006
    const val BATTERY_GUARD_STOPPED_NOTIFICATION_ID = 6007

    const val VPN_CHANNEL_ID = "mirrly_vpn_channel"
    const val VPN_NOTIFICATION_ID = 7007

    private fun isVibrationDisabled(): Boolean {
        return try {
            MirrlyApplication.instance.prefsManager.isVibrationDisabled()
        } catch (_: Throwable) {
            false
        }
    }

    private fun NotificationCompat.Builder.applyVibrationPreference(
        useDefaultAllWhenEnabled: Boolean = false
    ): NotificationCompat.Builder {
        if (isVibrationDisabled()) {
            setSilent(true)
            setVibrate(longArrayOf(0L))
        } else if (useDefaultAllWhenEnabled) {
            setDefaults(NotificationCompat.DEFAULT_ALL)
        }
        return this
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val vibrationEnabled = !isVibrationDisabled()

            val proxyChannel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_proxy_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_proxy_desc)
                setShowBadge(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(proxyChannel)

            val updateChannel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                context.getString(R.string.notif_channel_update_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_update_desc)
                enableVibration(vibrationEnabled)
            }
            manager.createNotificationChannel(updateChannel)

            val timerChannel = NotificationChannel(
                TIMER_CHANNEL_ID,
                context.getString(R.string.notif_channel_timer_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_timer_desc)
                enableVibration(vibrationEnabled)
            }
            manager.createNotificationChannel(timerChannel)

            val failoverChannel = NotificationChannel(
                FAILOVER_CHANNEL_ID,
                context.getString(R.string.notif_channel_failover_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_failover_desc)
                enableVibration(vibrationEnabled)
            }
            manager.createNotificationChannel(failoverChannel)

            val batteryGuardChannel = NotificationChannel(
                BATTERY_GUARD_CHANNEL_ID,
                context.getString(R.string.notif_channel_battery_guard_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_battery_guard_desc)
                enableVibration(vibrationEnabled)
            }
            manager.createNotificationChannel(batteryGuardChannel)

            val vpnChannel = NotificationChannel(
                VPN_CHANNEL_ID,
                context.getString(R.string.vpn_tunnel_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.status_vpn_system_connected_desc)
                enableVibration(false)
            }
            manager.createNotificationChannel(vpnChannel)
        }
    }

    private fun showChannelFailoverNotice(
        context: Context,
        notificationId: Int,
        requestCode: Int,
        title: String,
        message: String,
        onlyAlertOnce: Boolean
    ) {
        createNotificationChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, FAILOVER_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_stat_proxy_warning)
            .setColor(0xFFF59E0B.toInt())
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .applyVibrationPreference()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(notificationId, builder.build())
        } catch (_: Exception) {}
    }

    fun showFailoverNotification(context: Context, title: String, message: String) {
        showChannelFailoverNotice(
            context = context,
            notificationId = FAILOVER_NOTIFICATION_ID,
            requestCode = 5006,
            title = title,
            message = message,
            onlyAlertOnce = false
        )
    }

    fun showSocks5FlowsealFallbackNotification(
        context: Context,
        notice: Socks5UplinkPolicy.QuickTileNotice
    ) {
        val (titleRes, bodyRes) = when (notice) {
            Socks5UplinkPolicy.QuickTileNotice.NO_AUTH_NO_WORKERS ->
                R.string.notif_socks5_tile_flowseal_no_auth_title to R.string.notif_socks5_tile_flowseal_no_auth_body
            Socks5UplinkPolicy.QuickTileNotice.NO_WORKER_FALLBACK ->
                R.string.notif_socks5_tile_flowseal_no_worker_title to R.string.notif_socks5_tile_flowseal_no_worker_body
            Socks5UplinkPolicy.QuickTileNotice.NONE -> return
        }
        showChannelFailoverNotice(
            context = context,
            notificationId = SOCKS5_FLOWSEAL_FALLBACK_NOTIFICATION_ID,
            requestCode = SOCKS5_FLOWSEAL_FALLBACK_NOTIFICATION_ID,
            title = context.getString(titleRes),
            message = context.getString(bodyRes),
            onlyAlertOnce = true
        )
    }

    fun cancelSocks5FlowsealFallbackNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.cancel(SOCKS5_FLOWSEAL_FALLBACK_NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    fun buildNotification(
        context: Context,
        statusText: String,
        speedText: String,
        statusIndicator: ProxyStatusIndicator = ProxyStatusIndicator.GREEN
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val copyIntent = Intent(context, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_COPY_LINK
        }
        val copyPendingIntent = PendingIntent.getService(
            context, 2, copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(statusText)
            .setContentText(speedText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(speedText))
            .setSmallIcon(statusIndicator.iconRes)
            .setColor(statusIndicator.color)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .applyVibrationPreference()

        builder.addAction(R.drawable.ic_copy, context.getString(R.string.notif_action_copy_link), copyPendingIntent)
        builder.addAction(R.drawable.ic_notif_stop, context.getString(R.string.notif_action_stop), stopPendingIntent)

        return builder.build()
    }

    fun showUpdateNotification(context: Context, releaseInfo: ReleaseInfo) {
        createNotificationChannel(context)

        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val downloadUrl = releaseInfo.downloadUrl ?: releaseInfo.htmlUrl
        val downloadIntent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val downloadPendingIntent = PendingIntent.getActivity(
            context,
            2003,
            downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notesIntent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseInfo.htmlUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val notesPendingIntent = PendingIntent.getActivity(
            context,
            2004,
            notesIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val previewText = releaseInfo.changelogPreview.ifBlank {
            context.getString(R.string.notif_update_preview_default, releaseInfo.versionName)
        }

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_update_title, releaseInfo.versionName))
            .setContentText(previewText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(previewText))
            .setSmallIcon(R.drawable.ic_stat_update)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stat_update, context.getString(R.string.notif_action_download_apk), downloadPendingIntent)
            .addAction(R.drawable.ic_eye, context.getString(R.string.notif_action_whats_new), notesPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .applyVibrationPreference(useDefaultAllWhenEnabled = true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    fun cancelUpdateNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.cancel(UPDATE_NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    fun showSessionSummaryNotification(
        context: Context,
        transferredStr: String,
        durationStr: String,
        peakSpeedStr: String
    ) {
        createNotificationChannel(context)

        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            SUMMARY_NOTIFICATION_ID,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val summaryText = context.getString(
            R.string.notif_summary_body,
            transferredStr,
            durationStr,
            peakSpeedStr
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_summary_title))
            .setContentText(summaryText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setSmallIcon(R.drawable.ic_stat_proxy_connected)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .applyVibrationPreference()
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(SUMMARY_NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    fun cancelSummaryNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            manager?.cancel(SUMMARY_NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    fun cancelProxyNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            manager?.cancel(NOTIFICATION_ID)
            manager?.cancel(SUMMARY_NOTIFICATION_ID)
            manager?.cancel(BATTERY_GUARD_WARNING_NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    fun showTimerWarningNotification(context: Context, remainingMinutes: Int) {
        createNotificationChannel(context)

        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            TIMER_WARNING_NOTIFICATION_ID,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val extendIntent = Intent(context, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_EXTEND_TIMER
            putExtra("extra_minutes", 15)
        }
        val extendPendingIntent = PendingIntent.getService(
            context,
            4006,
            extendIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelTimerIntent = Intent(context, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_CANCEL_TIMER
        }
        val cancelTimerPendingIntent = PendingIntent.getService(
            context,
            4007,
            cancelTimerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = context.getString(R.string.notif_timer_warning_body, remainingMinutes)

        val notification = NotificationCompat.Builder(context, TIMER_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_timer_warning_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .applyVibrationPreference()
            .addAction(R.drawable.ic_stat_timer, context.getString(R.string.notif_action_extend_15m), extendPendingIntent)
            .addAction(R.drawable.ic_notif_stop, context.getString(R.string.notif_action_cancel), cancelTimerPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(TIMER_WARNING_NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    fun showTimerExpiredNotification(context: Context) {
        createNotificationChannel(context)

        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            TIMER_EXPIRED_NOTIFICATION_ID,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restartIntent = Intent(context, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_START
        }
        val restartPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context,
                4008,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                4008,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val text = context.getString(R.string.notif_timer_expired_body)

        val notification = NotificationCompat.Builder(context, TIMER_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_timer_expired_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .applyVibrationPreference()
            .addAction(R.drawable.ic_power, context.getString(R.string.notif_action_restart), restartPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(TIMER_EXPIRED_NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    fun cancelTimerWarningNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.cancel(TIMER_WARNING_NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    fun showBatteryGuardWarningNotification(
        context: Context,
        targetTimeMs: Long,
        remainingSeconds: Int,
        reason: String,
        batteryPct: Int
    ) {
        createNotificationChannel(context)

        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            6008,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(context, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_CANCEL_BATTERY_GUARD
        }
        val cancelPendingIntent = PendingIntent.getService(
            context,
            6009,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            6010,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remainingMinutes = ((remainingSeconds + 59) / 60).coerceAtLeast(1)
        val timeRemainingStr = if (remainingSeconds > 60) {
            context.getString(R.string.notif_unit_minutes, remainingMinutes)
        } else {
            context.getString(R.string.notif_unit_seconds, remainingSeconds)
        }

        val contentText = context.getString(R.string.notif_battery_guard_warning_content, timeRemainingStr, batteryPct)
        val detailedText = context.getString(R.string.notif_battery_guard_warning_detail, reason, timeRemainingStr)

        val builder = NotificationCompat.Builder(context, BATTERY_GUARD_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_battery_guard_warning_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailedText))
            .setSmallIcon(R.drawable.ic_stat_proxy_warning)
            .setColor(0xFFF59E0B.toInt())
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .applyVibrationPreference()
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.ic_check, context.getString(R.string.notif_action_cancel), cancelPendingIntent)
            .addAction(R.drawable.ic_notif_stop, context.getString(R.string.notif_action_stop_now), stopPendingIntent)

        if (targetTimeMs > System.currentTimeMillis()) {
            builder.setWhen(targetTimeMs)
            builder.setUsesChronometer(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setChronometerCountDown(true)
            }
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(BATTERY_GUARD_WARNING_NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {}
    }

    fun showBatteryGuardStoppedNotification(context: Context, reason: String) {
        createNotificationChannel(context)

        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            6011,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restartIntent = Intent(context, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_START
        }
        val restartPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context,
                6012,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                6012,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val detailedText = context.getString(R.string.notif_battery_guard_stopped_detail, reason)

        val notification = NotificationCompat.Builder(context, BATTERY_GUARD_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_battery_guard_stopped_title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailedText))
            .setSmallIcon(R.drawable.ic_stat_proxy_warning)
            .setColor(0xFFEF4444.toInt())
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .applyVibrationPreference()
            .addAction(R.drawable.ic_power, context.getString(R.string.notif_action_restart), restartPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(BATTERY_GUARD_STOPPED_NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    fun cancelBatteryGuardNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.cancel(BATTERY_GUARD_WARNING_NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    fun showPortConflictNotification(
        context: Context,
        report: com.mirrly.tgproxy.core.PortConflictReport
    ) {
        createNotificationChannel(context)

        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PORT_CONFLICT, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            PORT_CONFLICT_NOTIFICATION_ID,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val portsStr = report.occupiedPorts.joinToString(", ") { it.port.toString() }
            .ifBlank { report.failedPort?.toString().orEmpty() }
        val exactApps = report.occupiedPorts.mapNotNull { it.exactOwnerApp?.appName }.distinct()
        val candidateNames = if (exactApps.isNotEmpty()) {
            exactApps
        } else {
            report.candidateApps.take(3).map { it.appName }
        }

        val bodyText = if (candidateNames.isNotEmpty()) {
            context.getString(
                R.string.port_conflict_notif_body_with_apps,
                portsStr,
                candidateNames.joinToString(", ")
            )
        } else {
            context.getString(R.string.port_conflict_notif_body_generic, portsStr)
        }

        val notification = NotificationCompat.Builder(context, FAILOVER_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.port_conflict_notif_title))
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setSmallIcon(R.drawable.ic_stat_proxy_error)
            .setColor(0xFFEF4444.toInt())
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .applyVibrationPreference()
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(PORT_CONFLICT_NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    fun cancelPortConflictNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.cancel(PORT_CONFLICT_NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}

