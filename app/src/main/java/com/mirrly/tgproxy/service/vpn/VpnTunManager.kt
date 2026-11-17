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
import android.util.Log
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
    const val VPN_MTU_SAFE = 1280 // RFC 2460 minimum IPv6 MTU, безопасный для WireGuard/AWG outer envelope (Cellular LTE/5G)
    const val VPN_MTU_HIGH_PERF = 1420 // Оптимальный MTU для WireGuard/AWG при внешнем MTU 1500 (Wi-Fi/Ethernet)
    const val VPN_MTU = VPN_MTU_SAFE

    data class SplitTunnelConfig(
        val isEnabled: Boolean = false,
        val isAllowlist: Boolean = false, // true: только выбранные, false: все кроме выбранных
        val packageNames: Set<String> = emptySet()
    )

    fun calculateOptimalMtu(vpnService: VpnService, explicitMtu: Int? = null): Int {
        if (explicitMtu != null && explicitMtu in VPN_MTU_SAFE..1500) {
            return explicitMtu
        }
        return try {
            val cm = vpnService.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return VPN_MTU_SAFE
            val activeNet = cm.activeNetwork ?: return VPN_MTU_SAFE
            val caps = cm.getNetworkCapabilities(activeNet) ?: return VPN_MTU_SAFE
            val isCellular = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            if (isCellular) {
                // На LTE/5G сетях с агрессивным CGNAT и 464XLAT держим безопасный 1280
                VPN_MTU_SAFE
            } else {
                val lp = cm.getLinkProperties(activeNet)
                val physMtu = lp?.mtu ?: 0
                if (physMtu >= 1500 || physMtu == 0) {
                    VPN_MTU_HIGH_PERF
                } else if (physMtu > 1360) {
                    (physMtu - 80).coerceIn(VPN_MTU_SAFE, VPN_MTU_HIGH_PERF)
                } else {
                    VPN_MTU_SAFE
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка определения физического MTU, fallback на $VPN_MTU_SAFE: ${e.message}")
            VPN_MTU_SAFE
        }
    }

    fun establish(
        vpnService: VpnService,
        config: ProxyConfig,
        sessionName: String = "Mirrly VPN",
        splitTunnel: SplitTunnelConfig? = null,
        customDnsServers: List<String>? = null,
        explicitMtu: Int? = null
    ): Result<TunHolder> {
        return try {
            val effectiveMtu = calculateOptimalMtu(vpnService, explicitMtu)
            AppLogger.i(TAG, "VPN [TUN]: Назначен эффективный MTU: $effectiveMtu (динамический расчет под сеть)")
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

                // ВНИМАНИЕ: Петля обратной связи (127.0.0.0/8) обслуживается ядром Linux локально.
                // Вызов excludeRoute для loopback категорически запрещен: Android VpnService.Builder
                // проверяет isLoopbackAddress() и выбрасывает IllegalArgumentException.
                // Cloudflare WARP эталон: "WarpTunnel: Ignoring loopback address in exclude route".

                // Исключаем Link-Local (169.254.0.0/16)
                try {
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("169.254.0.0"), 16))
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось применить excludeRoute для 169.254.0.0/16: ${e.message}")
                }

                if (shouldExclude10) {
                    try {
                        builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("10.0.0.0"), 8))
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Не удалось применить excludeRoute для 10.0.0.0/8: ${e.message}")
                    }
                }
                if (shouldExclude172) {
                    try {
                        builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("172.16.0.0"), 12))
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Не удалось применить excludeRoute для 172.16.0.0/12: ${e.message}")
                    }
                }
                if (shouldExclude192) {
                    try {
                        builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("192.168.0.0"), 16))
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Не удалось применить excludeRoute для 192.168.0.0/16: ${e.message}")
                    }
                }
                AppLogger.i(TAG, "Включен LAN bypass (Android 13+ excludeRoute): exclude10=$shouldExclude10, exclude172=$shouldExclude172, exclude192=$shouldExclude192")
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
                        builder.addRoute(ipv6Dns, 128)
                        AppLogger.i(TAG, "Включен IPv6 туннель: $effectiveIpv6/128 с DNS: $ipv6Dns/128")
                    } else {
                        AppLogger.i(TAG, "Включен IPv6 туннель: $effectiveIpv6/128")
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось настроить IPv6 в TUN: ${e.message}")
                }
            } else {
                AppLogger.i(TAG, "IPv6 туннелирование отключено (предотвращаем blackhole-задержки и ошибки сети)")
            }

            // 4. Регистрация DNS-серверов туннеля VPN (проверенные адреса из effectiveDnsList: 1.1.1.1, 1.0.0.1 или пользовательские)
            for (dns in effectiveDnsList) {
                try {
                    builder.addDnsServer(dns)
                    val prefixLen = if (dns.contains(":")) 128 else 32
                    builder.addRoute(dns, prefixLen)
                    Log.i("MirrlyVPN", "VpnTunManager: Настроен DNS сервер VPN: $dns/$prefixLen")
                    AppLogger.i(TAG, "Настроен DNS сервер VPN: $dns/$prefixLen")
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
                        if (pkg.isBlank() || pkg == ownPackage || pkg == "com.android.vending") continue
                        try {
                            builder.addDisallowedApplication(pkg)
                            excludedCount++
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Blocklist: не удалось исключить пакет $pkg: ${e.message}")
                        }
                    }
                    try {
                        builder.addDisallowedApplication(ownPackage)
                        Log.i("MirrlyVPN", "VpnTunManager: собственный пакет ($ownPackage) гарантированно добавлен в исключения TUN (addDisallowedApplication)")
                        AppLogger.i(TAG, "VpnTunManager: собственный пакет ($ownPackage) гарантированно добавлен в исключения TUN (addDisallowedApplication)")
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Не удалось добавить исключение для собственного пакета: ${e.message}")
                    }
                    try {
                        builder.addDisallowedApplication("com.android.vending")
                        AppLogger.i(TAG, "VpnTunManager: Google Play (com.android.vending) добавлен в исключения TUN")
                    } catch (e: Exception) {
                        AppLogger.d(TAG, "Google Play (com.android.vending) не исключен: ${e.message}")
                    }
                    Log.i("MirrlyVPN", "VpnTunManager: собственный пакет ($ownPackage) исключен из маршрутизации TUN (Blocklist)")
                    AppLogger.i(TAG, "Раздельное туннелирование (Blocklist): исключено $excludedCount приложений (+ собственное и Google Play)")
                }
            } else {
                // По умолчанию все приложения идут в VPN, кроме самого Mirrly и Google Play
                try {
                    builder.addDisallowedApplication(ownPackage)
                    Log.i("MirrlyVPN", "VpnTunManager: собственный пакет ($ownPackage) гарантированно добавлен в исключения TUN (addDisallowedApplication)")
                    AppLogger.i(TAG, "VpnTunManager: собственный пакет ($ownPackage) гарантированно добавлен в исключения TUN (addDisallowedApplication)")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось добавить исключение для собственного пакета: ${e.message}")
                }
                try {
                    builder.addDisallowedApplication("com.android.vending")
                    AppLogger.i(TAG, "VpnTunManager: Google Play (com.android.vending) добавлен в исключения TUN")
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Google Play (com.android.vending) не исключен: ${e.message}")
                }
            }

            // 5. Блокирующий режим файлового дескриптора TUN для стабильного нативного I/O (Task N13)
            builder.setBlocking(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    builder.setMetered(false)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Не удалось вызвать setMetered(false): ${e.message}")
                }
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
