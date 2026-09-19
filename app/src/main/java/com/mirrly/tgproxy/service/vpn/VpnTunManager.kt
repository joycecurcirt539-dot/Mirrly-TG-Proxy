/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service.vpn

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.ProxyConfig
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Безопасная обертка над дескриптором интерфейса TUN с гарантией единственного владельца
 * и защитой от double-close (Task N03).
 */
class TunHolder(
    private val pfd: ParcelFileDescriptor
) {
    private val isClosed = AtomicBoolean(false)

    val fd: Int get() = pfd.fd
    val fileDescriptor: FileDescriptor get() = pfd.fileDescriptor
    val inStream: FileInputStream = FileInputStream(pfd.fileDescriptor)
    val outStream: FileOutputStream = FileOutputStream(pfd.fileDescriptor)

    fun isClosed(): Boolean = isClosed.get()

    fun closeSafely() {
        if (isClosed.compareAndSet(false, true)) {
            try {
                inStream.close()
            } catch (_: Exception) {}
            try {
                outStream.close()
            } catch (_: Exception) {}
            try {
                pfd.close()
            } catch (e: Exception) {
                AppLogger.w("TunHolder", "Ошибка при закрытии PFD: ${e.message}")
            }
            AppLogger.i("TunHolder", "Файловый дескриптор TUN (fd=$fd) успешно и безопасно освобожден")
        }
    }
}

/**
 * Конфигуратор и строитель системного интерфейса TUN (Tasks N03, N08, N09, N12, N13, N18).
 */
object VpnTunManager {
    private const val TAG = "VpnTunManager"
    const val VPN_MTU = 1280 // RFC 2460 minimum IPv6 MTU, безопасный для WireGuard/MASQUE outer envelope

    data class SplitTunnelConfig(
        val isEnabled: Boolean = false,
        val isAllowlist: Boolean = false, // true: только выбранные, false: все кроме выбранных
        val packageNames: Set<String> = emptySet()
    )

    fun establish(
        vpnService: VpnService,
        config: ProxyConfig,
        sessionName: String = "Mirrly VPN",
        splitTunnel: SplitTunnelConfig? = null
    ): Result<TunHolder> {
        return try {
            val effectiveMtu = config.vpnMtu.coerceIn(1280, 1500)
            val builder = vpnService.Builder()
                .setSession(sessionName)
                .setMtu(effectiveMtu)

            // 1. IP-адресация интерфейса TUN и покрытие IPv4 (Task N08)
            // Маршрутизируем все IPv4 адреса (0.0.0.0 - 255.255.255.255), явно исключая loopback диапазон
            // 127.0.0.0/8 (RFC 1122). Это позволяет клиентам (включая Telegram) обращаться к локальным прокси
            // Mirrly (MTProto :1080, SOCKS5 :10808) напрямую через loopback без перехвата в TUN и без сетевых петель.
            val isWarp = config.isVpnAnyWarpUplink
            val ipv4 = if (isWarp) {
                config.warpClientIpv4.ifBlank { "172.16.0.2" }
            } else {
                "10.233.233.2"
            }
            builder.addAddress(ipv4, if (isWarp) 32 else 30)

            val ipv4NonLoopbackRoutes = listOf(
                Pair("0.0.0.0", 2),    // 0.0.0.0 - 63.255.255.255
                Pair("64.0.0.0", 3),   // 64.0.0.0 - 95.255.255.255
                Pair("96.0.0.0", 4),   // 96.0.0.0 - 111.255.255.255
                Pair("112.0.0.0", 5),  // 112.0.0.0 - 119.255.255.255
                Pair("120.0.0.0", 6),  // 120.0.0.0 - 123.255.255.255
                Pair("124.0.0.0", 7),  // 124.0.0.0 - 125.255.255.255
                Pair("126.0.0.0", 8),  // 126.0.0.0 - 126.255.255.255
                Pair("128.0.0.0", 1)   // 128.0.0.0 - 255.255.255.255
            )
            for ((routeIp, prefixLen) in ipv4NonLoopbackRoutes) {
                builder.addRoute(routeIp, prefixLen)
            }

            // 2. Честная политика IPv6 и защита от утечек (Task N09)
            val hasIpv6 = isWarp && config.warpClientIpv6.isNotBlank()
            if (hasIpv6) {
                try {
                    builder.addAddress(config.warpClientIpv6, 128)
                    builder.addRoute("::", 0)
                    builder.addDnsServer("2606:4700:4700::1111")
                    AppLogger.i(TAG, "Включен IPv6 туннель: ${config.warpClientIpv6}/128")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось настроить IPv6 в TUN: ${e.message}")
                }
            } else if (config.vpnBlockIpv6Leaks) {
                try {
                    // Блокировка утечек IPv6: перехватываем маршрут ::/0 в TUN с фиктивным ULA адресом (RFC 4193)
                    builder.addAddress("fd00::1", 128)
                    builder.addRoute("::", 0)
                    AppLogger.i(TAG, "Включена защита от утечек IPv6 (blackhole ::/0)")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось настроить blackhole IPv6: ${e.message}")
                }
            } else {
                AppLogger.i(TAG, "IPv6 туннелирование отключено")
            }

            // 3. DNS серверы внутри туннеля (Task N10)
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("1.0.0.1")

            // 4. Раздельное туннелирование (Per-app routing, Task N12)
            val pm = vpnService.packageManager
            val ownPackage = vpnService.packageName

            if (splitTunnel != null && splitTunnel.isEnabled && splitTunnel.packageNames.isNotEmpty()) {
                if (splitTunnel.isAllowlist) {
                    var addedCount = 0
                    for (pkg in splitTunnel.packageNames) {
                        if (pkg == ownPackage) continue // собственное приложение не должно перехватывать собственные внешние сокеты
                        try {
                            pm.getPackageInfo(pkg, 0)
                            builder.addAllowedApplication(pkg)
                            addedCount++
                        } catch (_: PackageManager.NameNotFoundException) {
                            AppLogger.w(TAG, "Пакет $pkg не найден в системе, пропущен")
                        }
                    }
                    if (addedCount == 0) {
                        AppLogger.w(TAG, "Allowlist пуст после валидации, исключаем только собственное приложение")
                        builder.addDisallowedApplication(ownPackage)
                    }
                } else {
                    for (pkg in splitTunnel.packageNames) {
                        try {
                            pm.getPackageInfo(pkg, 0)
                            builder.addDisallowedApplication(pkg)
                        } catch (_: PackageManager.NameNotFoundException) {}
                    }
                    builder.addDisallowedApplication(ownPackage)
                }
            } else {
                // По умолчанию все приложения идут в VPN, кроме самого Mirrly
                try {
                    builder.addDisallowedApplication(ownPackage)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось добавить исключение для собственного пакета: ${e.message}")
                }
            }

            // 5. Блокирующий режим / Kill Switch (Task N13)
            builder.setBlocking(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            // 6. Поднятие системного дескриптора
            val pfd = builder.establish()
            if (pfd != null) {
                AppLogger.i(TAG, "Системный TUN успешно поднят: fd=${pfd.fd}, MTU=$VPN_MTU, IPv4=$ipv4, hasIpv6=$hasIpv6")
                Result.success(TunHolder(pfd))
            } else {
                AppLogger.e(TAG, "VpnService.Builder.establish() вернул null")
                Result.failure(IllegalStateException("VpnService.Builder.establish() returned null"))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Исключение при инициализации TUN: ${e.message}", e)
            Result.failure(e)
        }
    }
}
