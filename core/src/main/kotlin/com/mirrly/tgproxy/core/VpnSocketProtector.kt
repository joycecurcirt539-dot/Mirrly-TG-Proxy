/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.core

import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Глобальный диспетчер защиты внешних сокетов VPN-туннеля (Task N04).
 * Исключает исходящие служебные сокеты (DoH, регистрация WARP, воркеры,
 * пробы здоровья, нативные транспорты) из маршрутизации через виртуальный TUN.
 * Защита обязана вызываться СТРОГО ДО connect/send.
 */
object VpnSocketProtector {
    private const val TAG = "VpnSocketProtector"

    @Volatile
    private var protectSocketFn: ((Socket) -> Boolean)? = null

    @Volatile
    private var protectDatagramFn: ((DatagramSocket) -> Boolean)? = null

    @Volatile
    private var protectFdFn: ((Int) -> Boolean)? = null

    private val isRegistered = AtomicBoolean(false)

    @Synchronized
    fun register(
        onProtectSocket: (Socket) -> Boolean,
        onProtectDatagram: (DatagramSocket) -> Boolean,
        onProtectFd: (Int) -> Boolean
    ) {
        protectSocketFn = onProtectSocket
        protectDatagramFn = onProtectDatagram
        protectFdFn = onProtectFd
        isRegistered.set(true)
        AppLogger.i(TAG, "Обработчики защиты сокетов VpnService успешно зарегистрированы")
    }

    @Synchronized
    fun unregister() {
        protectSocketFn = null
        protectDatagramFn = null
        protectFdFn = null
        isRegistered.set(false)
        AppLogger.i(TAG, "Обработчики защиты сокетов VpnService сняты")
    }

    fun isRegistered(): Boolean = isRegistered.get()

    /**
     * Защита TCP сокета от заворачивания в собственный TUN.
     * Возвращает true, если сокет успешно защищен либо если VPN не активен.
     * Если VPN активен и protect() возвращает false, возвращает false для прерывания соединения.
     */
    fun protect(socket: Socket): Boolean {
        val fn = protectSocketFn ?: return true
        return try {
            val ok = fn(socket)
            if (!ok) {
                AppLogger.w(TAG, "VpnService.protect(Socket) вернул false. Прерывание соединения во избежание сетевой петли.")
            }
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "Сбой VpnService.protect(Socket): ${e.message}")
            false
        }
    }

    /**
     * Защита UDP сокета (DNS, QUIC, WireGuard, AWG).
     */
    fun protect(socket: DatagramSocket): Boolean {
        val fn = protectDatagramFn ?: return true
        return try {
            val ok = fn(socket)
            if (!ok) {
                AppLogger.w(TAG, "VpnService.protect(DatagramSocket) вернул false. Прерывание датаграммы.")
            }
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "Сбой VpnService.protect(DatagramSocket): ${e.message}")
            false
        }
    }

    /**
     * Защита низкоуровневого файлового дескриптора сокета (JNA / POSIX fd).
     */
    fun protect(fd: Int): Boolean {
        val fn = protectFdFn ?: return true
        return try {
            val ok = fn(fd)
            if (!ok) {
                AppLogger.w(TAG, "VpnService.protect(fd=$fd) вернул false.")
            }
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "Сбой VpnService.protect(fd=$fd): ${e.message}")
            false
        }
    }
}
