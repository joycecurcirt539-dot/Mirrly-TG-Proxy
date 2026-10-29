/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service.vpn

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.ProxyConfig
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

    val inStream: FileInputStream by lazy { FileInputStream(pfd.fileDescriptor) }
    val outStream: FileOutputStream by lazy { FileOutputStream(pfd.fileDescriptor) }

    fun isClosed(): Boolean = isClosed.get()

    fun closeSafely() {
        if (isClosed.compareAndSet(false, true)) {
            try { inStream.close() } catch (_: Exception) {}
            try { outStream.close() } catch (_: Exception) {}
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
        splitTunnel: SplitTunnelConfig? = null,
        customDnsServers: List<String>? = null
    ): Result<TunHolder> {
        return try {
            val effectiveMtu = VPN_MTU
            val builder = vpnService.Builder()
                .setSession(sessionName)
                .setMtu(effectiveMtu)

            // 1. IP-адресация интерфейса TUN и покрытие IPv4 (Task N08, Cloudflare Model)
            val ipv4 = config.warpClientIpv4.ifBlank { "172.16.0.2" }
            builder.addAddress(ipv4, 32)

            // 2. DNS серверы внутри туннеля VPN
            val effectiveDnsList = (customDnsServers ?: config.getEffectiveVpnDnsIpv4())
                .filter { it.isNotBlank() }
                .distinct()
                .ifEmpty { listOf("1.1.1.1", "1.0.0.1") }

            val isTunIpIn10 = isIpInSubnet(ipv4, "10.0.0.0", 8)
            val isTunIpIn172 = isIpInSubnet(ipv4, "172.16.0.0", 12)
            val isTunIpIn192 = isIpInSubnet(ipv4, "192.168.0.0", 16)

            val isDnsIn10 = effectiveDnsList.any { isIpInSubnet(it, "10.0.0.0", 8) }
            val isDnsIn172 = effectiveDnsList.any { isIpInSubnet(it, "172.16.0.0", 12) }
            val isDnsIn192 = effectiveDnsList.any { isIpInSubnet(it, "192.168.0.0", 16) }

            val shouldExclude10 = !isTunIpIn10 && !isDnsIn10
            val shouldExclude172 = !isTunIpIn172 && !isDnsIn172
            val shouldExclude192 = !isTunIpIn192 && !isDnsIn192

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // На Android 13+ маршрутизируем 0.0.0.0/0 и вызываем excludeRoute для локальных сетей
                builder.addRoute("0.0.0.0", 0)
                try {
                    // Исключаем петлю обратной связи (Loopback 127.0.0.0/8) для изоляции SOCKS5/MTProto прокси от захвата в TUN
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("127.0.0.0"), 8))
                    // Исключаем Link-Local (169.254.0.0/16)
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("169.254.0.0"), 16))

                    if (shouldExclude10) {
                        builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("10.0.0.0"), 8))
                    }
                    if (shouldExclude172) {
                        builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("172.16.0.0"), 12))
                    }
                    if (shouldExclude192) {
                        builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("192.168.0.0"), 16))
                    }
                    AppLogger.i(TAG, "Включен LAN bypass (Android 13+ excludeRoute): exclude10=$shouldExclude10, exclude172=$shouldExclude172, exclude192=$shouldExclude192, excludeLoopback=true")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось применить excludeRoute для LAN: ${e.message}")
                }
            } else {
                // На Android 7-12 разбиваем адресное пространство на непересекающиеся CIDR без локальных сетей
                val nonLanRoutes = calculateNonLanRoutes(
                    exclude10 = shouldExclude10,
                    exclude172 = shouldExclude172,
                    exclude192 = shouldExclude192
                )
                for ((routeIp, prefixLen) in nonLanRoutes) {
                    builder.addRoute(routeIp, prefixLen)
                }
                AppLogger.i(TAG, "Включен LAN bypass (Android < 13 CIDR декомпозиция): добавлено ${nonLanRoutes.size} маршрутов (exclude10=$shouldExclude10, exclude172=$shouldExclude172, exclude192=$shouldExclude192)")
            }

            // 3. Честная политика IPv6 и защита от утечек (Task N09)
            val effectiveIpv6 = when {
                config.warpClientIpv6.isNotBlank() && config.warpClientIpv6 != "2606:4700:110:812c:a554:b442:26d4:1330" -> config.warpClientIpv6
                else -> null
            }
            val hasIpv6 = effectiveIpv6 != null
            val ipv6Dns = config.getEffectiveVpnDnsIpv6()
            if (hasIpv6) {
                try {
                    builder.addAddress(effectiveIpv6!!, 128)
                    builder.addRoute("::", 0)
                    if (ipv6Dns != null) {
                        builder.addDnsServer(ipv6Dns)
                        AppLogger.i(TAG, "Включен IPv6 туннель: $effectiveIpv6/128 с DNS: $ipv6Dns")
                    } else {
                        AppLogger.i(TAG, "Включен IPv6 туннель: $effectiveIpv6/128")
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось настроить IPv6 в TUN: ${e.message}")
                }
            } else {
                AppLogger.i(TAG, "IPv6 туннелирование отключено (предотвращаем blackhole-задержки и ошибки сети)")
            }

            // 4. Регистрация DNS-серверов туннеля VPN (Cloudflare WARP модель: виртуальный DNS 192.0.2.3 первым)
            val virtualDns = "192.0.2.3"
            try {
                builder.addDnsServer(virtualDns)
                builder.addRoute(virtualDns, 32)
                AppLogger.i(TAG, "Настроен первичный виртуальный DNS сервер VPN: $virtualDns")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Не удалось добавить первичный виртуальный DNS: ${e.message}")
            }
            for (dns in effectiveDnsList) {
                if (dns == virtualDns) continue
                try {
                    builder.addDnsServer(dns)
                    builder.addRoute(dns, 32)
                    AppLogger.i(TAG, "Настроен дополнительный DNS сервер VPN: $dns")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось добавить DNS сервер $dns: ${e.message}")
                }
            }

            // 4. Раздельное туннелирование (Per-app routing, Cloudflare Model с защитой Knox/Work Profile)
            val pm = vpnService.packageManager
            val ownPackage = vpnService.packageName

            if (splitTunnel != null && splitTunnel.isEnabled) {
                if (splitTunnel.isAllowlist) {
                    // Режим «Только выбранные приложения» (Allowlist)
                    val validPackages = splitTunnel.packageNames
                        .filter { it.isNotBlank() && it != ownPackage }
                        .filter { pkg ->
                            try {
                                pm.getPackageInfo(pkg, 0)
                                true
                            } catch (_: Exception) {
                                AppLogger.w(TAG, "Allowlist: пакет $pkg не найден в системе, пропущен")
                                false
                            }
                        }

                    if (validPackages.isEmpty()) {
                        AppLogger.e(TAG, "Allowlist пуст: не выбрано ни одного установленного приложения")
                        return Result.failure(
                            IllegalStateException("В режиме «Только выбранные» не указано ни одного установленного приложения")
                        )
                    }

                    for (pkg in validPackages) {
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Не удалось добавить $pkg в allowed list: ${e.message}")
                        }
                    }
                    AppLogger.i(TAG, "Раздельное туннелирование (Allowlist): направлено ${validPackages.size} приложений: ${validPackages.joinToString(", ")}")
                } else {
                    // Режим «Исключить выбранные приложения» (Blocklist / Bypass)
                    var excludedCount = 0
                    for (pkg in splitTunnel.packageNames) {
                        if (pkg.isBlank() || pkg == ownPackage) continue
                        try {
                            builder.addDisallowedApplication(pkg)
                            excludedCount++
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Blocklist: не удалось исключить пакет $pkg: ${e.message}")
                        }
                    }
                    try {
                        builder.addDisallowedApplication(ownPackage)
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Не удалось добавить исключение для собственного пакета: ${e.message}")
                    }
                    AppLogger.i(TAG, "Раздельное туннелирование (Blocklist): исключено $excludedCount приложений (+ собственное)")
                }
            } else {
                // По умолчанию все приложения идут в VPN, кроме самого Mirrly
                try {
                    builder.addDisallowedApplication(ownPackage)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось добавить исключение для собственного пакета: ${e.message}")
                }
                // Эталон Cloudflare (VpnWarpTunnel): исключаем системный маркет Google Play от захвата туннелем
                try {
                    builder.addDisallowedApplication("com.android.vending")
                } catch (_: Exception) {}
            }

            // 5. Блокирующий режим файлового дескриптора TUN для стабильного нативного I/O (Task N13)
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

    private data class Ipv4Range(val start: Long, val end: Long)

    private fun ipToLong(ip: String): Long {
        val parts = ip.split('.').map { it.toLong() }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    private fun longToIp(value: Long): String {
        return "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
    }

    fun isIpInSubnet(ipStr: String, subnetStr: String, prefixLen: Int): Boolean {
        return try {
            val ip = ipToLong(ipStr)
            val subnet = ipToLong(subnetStr)
            val mask = if (prefixLen == 0) 0L else ((0xFFFFFFFFL shl (32 - prefixLen)) and 0xFFFFFFFFL)
            (ip and mask) == (subnet and mask)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Декомпозиция адресного пространства IPv4 (0.0.0.0/0) на непересекающиеся CIDR блоки,
     * исключающие локальные сети (RFC 1918), loopback (RFC 1122), link-local и multicast.
     * Используется на Android < 13, где VpnService.Builder.excludeRoute() не поддерживается платформой.
     */
    fun calculateNonLanRoutes(
        exclude10: Boolean = true,
        exclude172: Boolean = true,
        exclude192: Boolean = true
    ): List<Pair<String, Int>> {
        val excluded = mutableListOf(
            Ipv4Range(ipToLong("0.0.0.0"), ipToLong("0.255.255.255")),       // 0.0.0.0/8
            Ipv4Range(ipToLong("127.0.0.0"), ipToLong("127.255.255.255")),   // 127.0.0.0/8 (Loopback)
            Ipv4Range(ipToLong("169.254.0.0"), ipToLong("169.254.255.255")), // 169.254.0.0/16 (Link-Local)
            Ipv4Range(ipToLong("224.0.0.0"), ipToLong("255.255.255.255"))    // 224.0.0.0/3 (Multicast & Class E)
        )
        if (exclude10) {
            excluded.add(Ipv4Range(ipToLong("10.0.0.0"), ipToLong("10.255.255.255")))     // 10.0.0.0/8 (LAN)
        }
        if (exclude172) {
            excluded.add(Ipv4Range(ipToLong("172.16.0.0"), ipToLong("172.31.255.255"))) // 172.16.0.0/12 (LAN)
        }
        if (exclude192) {
            excluded.add(Ipv4Range(ipToLong("192.168.0.0"), ipToLong("192.168.255.255"))) // 192.168.0.0/16 (LAN)
        }
        val result = mutableListOf<Pair<String, Int>>()

        fun partition(ip: Long, prefixLen: Int) {
            val size = 1L shl (32 - prefixLen)
            val rangeStart = ip
            val rangeEnd = ip + size - 1

            val isFullyExcluded = excluded.any { it.start <= rangeStart && it.end >= rangeEnd }
            if (isFullyExcluded) return

            val hasOverlap = excluded.any { !(rangeEnd < it.start || rangeStart > it.end) }
            if (!hasOverlap) {
                result.add(Pair(longToIp(ip), prefixLen))
                return
            }

            if (prefixLen < 32) {
                val nextLen = prefixLen + 1
                val halfSize = 1L shl (32 - nextLen)
                partition(ip, nextLen)
                partition(ip + halfSize, nextLen)
            }
        }

        partition(0L, 0)
        return result
    }
}

