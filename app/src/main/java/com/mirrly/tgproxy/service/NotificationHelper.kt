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

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val proxyChannel = NotificationChannel(
                CHANNEL_ID,
                "Служба прокси Mirrly TG",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Отображает статус работы прокси и скорость трафика"
            }
            manager.createNotificationChannel(proxyChannel)

            val updateChannel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "Обновления Mirrly TG Proxy",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о доступных обновлениях приложения"
                enableVibration(true)
            }
            manager.createNotificationChannel(updateChannel)

            val timerChannel = NotificationChannel(
                TIMER_CHANNEL_ID,
                "Таймер автоотключения Mirrly",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Предупреждения об автоотключении прокси по таймеру сна"
                enableVibration(true)
            }
            manager.createNotificationChannel(timerChannel)
        }
    }

    fun buildNotification(
        context: Context,
        statusText: String,
        speedText: String,
        statusIndicator: ProxyStatusIndicator = ProxyStatusIndicator.GREEN
    ): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
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
            .setPriority(NotificationCompat.PRIORITY_LOW)

        builder.addAction(R.drawable.ic_notif_stop, "Остановить", stopPendingIntent)

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
            "Нажмите, чтобы открыть приложение и установить новую версию v${releaseInfo.versionName}."
        }

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setContentTitle("Доступно новое обновление v${releaseInfo.versionName}")
            .setContentText(previewText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(previewText))
            .setSmallIcon(R.drawable.ic_stat_update)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stat_update, "Скачать APK", downloadPendingIntent)
            .addAction(R.drawable.ic_eye, "Что нового", notesPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
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

        val summaryText = "Передано за сессию: $transferredStr | Время работы: $durationStr | Пиковая скорость: $peakSpeedStr"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Прокси остановлен")
            .setContentText(summaryText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setSmallIcon(R.drawable.ic_stat_proxy_connected)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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

        val text = "До автоматического отключения прокси осталось $remainingMinutes мин."

        val notification = NotificationCompat.Builder(context, TIMER_CHANNEL_ID)
            .setContentTitle("Таймер автоотключения")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(R.drawable.ic_stat_timer, "+15 минут", extendPendingIntent)
            .addAction(R.drawable.ic_notif_stop, "Отменить", cancelTimerPendingIntent)
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

        val text = "Прокси-сервер автоматически отключен по таймеру сна."

        val notification = NotificationCompat.Builder(context, TIMER_CHANNEL_ID)
            .setContentTitle("Прокси отключен по таймеру")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(R.drawable.ic_power, "Включить снова", restartPendingIntent)
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
}

