/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+  <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.NativeProxy
import com.mirrly.tgproxy.core.WarpAccountManager
import com.mirrly.tgproxy.ui.MainActivity
import com.mirrly.tgproxy.ui.theme.VpnUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Системная служба VpnService для перехвата сетевого трафика через виртуальный интерфейс TUN.
 * Настраивает адресное пространство (172.16.0.2/32, IPv6), DNS (1.1.1.1, 1.0.0.1) и MTU (1280).
 * Исключает собственный пакет Mirrly во избежание сетевых петель.
 * Реализует конвейер обработки пакетов TUN (ICMP Echo Reply и UDP DNS резолвер).
 */
class MirrlyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pumpJob: Job? = null

    companion object {
        private const val TAG = "MirrlyVpnService"
        const val ACTION_START = "com.mirrly.tgproxy.VPN_START"
        const val ACTION_STOP = "com.mirrly.tgproxy.VPN_STOP"

        private val _vpnState = MutableStateFlow(VpnUiState.DISCONNECTED)
        val vpnState: StateFlow<VpnUiState> = _vpnState.asStateFlow()

        val isRunning: Boolean
            get() = _vpnState.value == VpnUiState.CONNECTED

        fun prepare(context: Context): Intent? {
            return try {
                VpnService.prepare(context)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка проверки готовности VpnService: ${e.message}")
                null
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, MirrlyVpnService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка запуска MirrlyVpnService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MirrlyVpnService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка остановки MirrlyVpnService: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> {
                startVpn()
            }
            ACTION_STOP -> {
                stopVpn()
            }
            else -> {
                AppLogger.w(TAG, "Неизвестное действие: $action")
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureProxyRunning() {
        val app = MirrlyApplication.instance
        if (!app.proxyServer.isRunning) {
            val serviceIntent = Intent(this, ProxyForegroundService::class.java).apply {
                action = ProxyForegroundService.ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка автозапуска ProxyForegroundService: ${e.message}")
            }
        }
    }

    private fun startVpn() {
        if (vpnInterface != null) {
            AppLogger.d(TAG, "VPN уже запущен, повторный запуск пропущен")
            _vpnState.value = VpnUiState.CONNECTED
            return
        }

        _vpnState.value = VpnUiState.CONNECTING
        val notification = buildVpnNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.VPN_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NotificationHelper.VPN_NOTIFICATION_ID, notification)
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val app = MirrlyApplication.instance
                val config = app.config

                // Проверка и автоматическая регистрация профиля WARP при необходимости
                val hasInvalidWarpCredentials = config.warpToken.isBlank() ||
                    config.warpToken == "mirrly-bootstrap-token" ||
                    config.warpPrivateKey == WarpAccountManager.BOOTSTRAP_PROFILE.privateKeyBase64

                if (hasInvalidWarpCredentials) {
                    AppLogger.i(TAG, "VPN режим: отсутствует профиль WARP, выполняем регистрацию...")
                    val regResult = WarpAccountManager.registerAndActivate(fallbackToBootstrap = false)
                    regResult.onSuccess { profile ->
                        app.prefsManager.saveWarpProfile(profile)
                        config.applyWarpProfile(profile)
                        app.prefsManager.saveConfig(config)
                        AppLogger.i(TAG, "VPN режим: профиль WARP успешно сохранен: ${profile.getSummary()}")
                    }.onFailure { err ->
                        AppLogger.w(TAG, "VPN режим: сбой авторегистрации WARP (${err.message}), используется резервная конфигурация")
                    }
                }

                // Передача настроек AmneziaWG в нативный движок
                val awgIni = config.getAmneziaWgConfig(cleanEndpoint = config.warpPeerEndpoint)
                NativeProxy.setAwgConfig(awgIni)
                AppLogger.i(TAG, "VPN режим: параметры AmneziaWG переданы в нативный движок")

                // Гарантируем запуск локального прокси
                ensureProxyRunning()

                // Конфигурация системного интерфейса TUN
                val builder = Builder()
                    .setSession("Mirrly WARP VPN")
                    .setMtu(1280)

                val ipv4 = config.warpClientIpv4.ifBlank { "172.16.0.2" }
                builder.addAddress(ipv4, 32)

                if (config.warpClientIpv6.isNotBlank()) {
                    try {
                        builder.addAddress(config.warpClientIpv6, 128)
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Не удалось добавить IPv6 адрес в TUN: ${e.message}")
                    }
                }

                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("1.0.0.1")
                builder.addRoute("0.0.0.0", 0)
                try {
                    builder.addRoute("::", 0)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось добавить маршрут IPv6 в TUN: ${e.message}")
                }

                // Исключаем трафик собственного приложения во избежание сетевых петель
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось исключить приложение из TUN: ${e.message}")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                val pfd = builder.establish()
                if (pfd != null) {
                    vpnInterface = pfd
                    _vpnState.value = VpnUiState.CONNECTED
                    AppLogger.i(TAG, "Интерфейс TUN успешно поднят: fd=${pfd.fd}, mtu=1280, ipv4=$ipv4")
                    startTunPump(pfd)
                } else {
                    AppLogger.e(TAG, "Builder.establish() вернул null. VPN не запущен.")
                    _vpnState.value = VpnUiState.DISCONNECTED
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Исключение при инициализации TUN: ${e.message}")
                _vpnState.value = VpnUiState.DISCONNECTED
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startTunPump(pfd: ParcelFileDescriptor) {
        pumpJob?.cancel()
        pumpJob = serviceScope.launch(Dispatchers.IO) {
            val inStream = FileInputStream(pfd.fileDescriptor)
            val outStream = FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32767)
            var dnsSocket: DatagramSocket? = null

            try {
                dnsSocket = DatagramSocket().apply {
                    soTimeout = 2500
                }
                try {
                    protect(dnsSocket)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось защитить сокет DNS от петли TUN: ${e.message}")
                }

                val dnsServer = InetAddress.getByName("1.1.1.1")
                val dnsPort = 53
                val dnsRespBuf = ByteArray(4096)

                AppLogger.i(TAG, "Запущен конвейер обработки пакетов интерфейса TUN")

                while (isActive && vpnInterface != null) {
                    val length = try {
                        inStream.read(buffer)
                    } catch (e: Exception) {
                        if (isActive) {
                            AppLogger.w(TAG, "Ошибка чтения из TUN: ${e.message}")
                        }
                        break
                    }
                    if (length <= 0) continue

                    val version = (buffer[0].toInt() ushr 4) and 0x0F
                    if (version == 4 && length >= 20) {
                        val ihl = (buffer[0].toInt() and 0x0F) * 4
                        val totalLen = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
                        if (totalLen in 20..length) {
                            val protocol = buffer[9].toInt() and 0xFF
                            when (protocol) {
                                1 -> {
                                    // ICMP (Echo Request -> Echo Reply)
                                    if (totalLen >= ihl + 8) {
                                        val icmpType = buffer[ihl].toInt() and 0xFF
                                        if (icmpType == 8) {
                                            buffer[ihl] = 0.toByte()
                                            buffer[ihl + 1] = 0.toByte()
                                            buffer[ihl + 2] = 0.toByte()
                                            buffer[ihl + 3] = 0.toByte()

                                            for (i in 0 until 4) {
                                                val tmp = buffer[12 + i]
                                                buffer[12 + i] = buffer[16 + i]
                                                buffer[16 + i] = tmp
                                            }

                                            val icmpLen = totalLen - ihl
                                            val icmpChecksum = computeChecksum(buffer, ihl, icmpLen)
                                            buffer[ihl + 2] = ((icmpChecksum ushr 8) and 0xFF).toByte()
                                            buffer[ihl + 3] = (icmpChecksum and 0xFF).toByte()

                                            buffer[10] = 0.toByte()
                                            buffer[11] = 0.toByte()
                                            val ipChecksum = computeChecksum(buffer, 0, ihl)
                                            buffer[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
                                            buffer[11] = (ipChecksum and 0xFF).toByte()

                                            try {
                                                synchronized(outStream) {
                                                    outStream.write(buffer, 0, totalLen)
                                                }
                                            } catch (e: Exception) {
                                                AppLogger.w(TAG, "Ошибка отправки ICMP ответа в TUN: ${e.message}")
                                            }
                                        }
                                    }
                                }
                                17 -> {
                                    // UDP (DNS перехват на порту 53)
                                    if (totalLen >= ihl + 8) {
                                        val srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
                                        val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
                                        val udpLen = ((buffer[ihl + 4].toInt() and 0xFF) shl 8) or (buffer[ihl + 5].toInt() and 0xFF)

                                        if (dstPort == 53 && udpLen in 8..(totalLen - ihl)) {
                                            val dnsPayloadLen = udpLen - 8
                                            if (dnsPayloadLen > 0) {
                                                val dnsPayload = buffer.copyOfRange(ihl + 8, ihl + 8 + dnsPayloadLen)
                                                val srcIpBytes = buffer.copyOfRange(12, 16)
                                                val dstIpBytes = buffer.copyOfRange(16, 20)

                                                serviceScope.launch(Dispatchers.IO) {
                                                    try {
                                                        val dnsReq = DatagramPacket(dnsPayload, dnsPayload.size, dnsServer, dnsPort)
                                                        dnsSocket.send(dnsReq)

                                                        val dnsRespPacket = DatagramPacket(dnsRespBuf, dnsRespBuf.size)
                                                        dnsSocket.receive(dnsRespPacket)
                                                        val respDataLen = dnsRespPacket.length

                                                        val replyTotalLen = 20 + 8 + respDataLen
                                                        val replyBuf = ByteArray(replyTotalLen)

                                                        replyBuf[0] = 0x45.toByte()
                                                        replyBuf[1] = 0x00.toByte()
                                                        replyBuf[2] = ((replyTotalLen ushr 8) and 0xFF).toByte()
                                                        replyBuf[3] = (replyTotalLen and 0xFF).toByte()
                                                        replyBuf[4] = 0x00.toByte()
                                                        replyBuf[5] = 0x00.toByte()
                                                        replyBuf[6] = 0x40.toByte()
                                                        replyBuf[7] = 0x00.toByte()
                                                        replyBuf[8] = 64.toByte()
                                                        replyBuf[9] = 17.toByte()
                                                        replyBuf[10] = 0.toByte()
                                                        replyBuf[11] = 0.toByte()

                                                        System.arraycopy(dstIpBytes, 0, replyBuf, 12, 4)
                                                        System.arraycopy(srcIpBytes, 0, replyBuf, 16, 4)

                                                        val ipCk = computeChecksum(replyBuf, 0, 20)
                                                        replyBuf[10] = ((ipCk ushr 8) and 0xFF).toByte()
                                                        replyBuf[11] = (ipCk and 0xFF).toByte()

                                                        val replyUdpLen = 8 + respDataLen
                                                        replyBuf[20] = ((dstPort ushr 8) and 0xFF).toByte()
                                                        replyBuf[21] = (dstPort and 0xFF).toByte()
                                                        replyBuf[22] = ((srcPort ushr 8) and 0xFF).toByte()
                                                        replyBuf[23] = (srcPort and 0xFF).toByte()
                                                        replyBuf[24] = ((replyUdpLen ushr 8) and 0xFF).toByte()
                                                        replyBuf[25] = (replyUdpLen and 0xFF).toByte()
                                                        replyBuf[26] = 0.toByte()
                                                        replyBuf[27] = 0.toByte()

                                                        System.arraycopy(dnsRespPacket.data, 0, replyBuf, 28, respDataLen)

                                                        synchronized(outStream) {
                                                            outStream.write(replyBuf, 0, replyTotalLen)
                                                        }
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    AppLogger.e(TAG, "Сбой цикла обработки пакетов TUN: ${e.message}")
                }
            } finally {
                try {
                    dnsSocket?.close()
                } catch (_: Exception) {}
                try {
                    inStream.close()
                } catch (_: Exception) {}
                try {
                    outStream.close()
                } catch (_: Exception) {}
                AppLogger.i(TAG, "Конвейер обработки пакетов TUN завершен")
            }
        }
    }

    private fun computeChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun stopVpn() {
        _vpnState.value = VpnUiState.DISCONNECTING
        pumpJob?.cancel()
        pumpJob = null
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка при закрытии TUN: ${e.message}")
        }
        vpnInterface = null
        _vpnState.value = VpnUiState.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        AppLogger.i(TAG, "Интерфейс TUN остановлен")
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        AppLogger.w(TAG, "Системное разрешение VPN отозвано пользователем")
        stopVpn()
        super.onRevoke()
    }

    private fun buildVpnNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MirrlyVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationHelper.VPN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(R.string.vpn_tunnel_title))
            .setContentText(getString(R.string.status_vpn_system_connected_desc))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_disconnect),
                stopPendingIntent
            )
            .build()
    }
}
