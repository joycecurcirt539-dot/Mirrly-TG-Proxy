/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.core

import java.util.UUID

/**
 * Режим получения учетной записи Cloudflare WARP для генерации.
 */
enum class WarpAccountGenerationMode {
    REUSE_EXISTING,
    REGISTER_NEW,
    NEW_ACCOUNT
}

/**
 * Поддерживаемые форматы конфигурации генератора WARP.
 */
enum class WarpGeneratorFormat(
    val id: String,
    val title: String,
    val extension: String,
    val description: String,
    val isAwg: Boolean = false,
    val awgVersion: String = ""
) {
    WARP_MASQUE(
        id = "warp_masque",
        title = "WARP MASQUE",
        extension = "masque",
        description = "HTTP/3 CONNECT-IP через QUIC с маскировкой SNI и резервом AWG",
        isAwg = false
    ),
    AWG_3_1(
        id = "awg31",
        title = "AWG 3.1",
        extension = "conf",
        description = "AWG 3.1: подавление куки под нагрузкой DisableCookies",
        isAwg = true,
        awgVersion = "3.1"
    ),
    AWG_3_0(
        id = "awg3",
        title = "AWG 3.0",
        extension = "conf",
        description = "AWG 3.0: сигнатуры CPS I1, рандомизация таймингов",
        isAwg = true,
        awgVersion = "3.0"
    ),
    AWG_2_0(
        id = "awg2",
        title = "AWG 2.0",
        extension = "conf",
        description = "AWG 2.0: расширенная структура S1-S4 и H1-H4",
        isAwg = true,
        awgVersion = "2.0"
    ),
    AWG_1_0(
        id = "awg1",
        title = "AWG 1.0",
        extension = "conf",
        description = "AWG 1.0: обфускация мусорными пакетами Jc, Jmin, Jmax",
        isAwg = true,
        awgVersion = "1.0"
    )
}

/**
 * Предустановленные эндпоинты Cloudflare WARP.
 */
data class WarpEndpointPresetOption(
    val id: String,
    val label: String,
    val endpoint: String,
    val countryCode: String = "GLOBAL",
    val isCustom: Boolean = false,
    val isRandom: Boolean = false,
    val isBlocked: Boolean = false
) {
    companion object {
        // Проверенные Anycast эндпоинты Cloudflare WireGuard / AmneziaWG (незаблокированные порты 4500, 500, 1701)
        val WG_FRA_4500 = WarpEndpointPresetOption("wg_fra_4500", "WG Франкфурт 4500", "188.114.97.66:4500", countryCode = "DE")
        val WG_FRA_500 = WarpEndpointPresetOption("wg_fra_500", "WG Франкфурт 500", "188.114.96.125:500", countryCode = "DE")
        val WG_EDGE_1701 = WarpEndpointPresetOption("wg_edge_1701", "WG Anycast 1701", "162.159.193.10:1701", countryCode = "GLOBAL")
        val WG_EDGE_500 = WarpEndpointPresetOption("wg_edge_500", "WG Anycast 500", "162.159.192.1:500", countryCode = "GLOBAL")
        val WG_EDGE_4500 = WarpEndpointPresetOption("wg_edge_4500", "WG Anycast 4500", "162.159.195.1:4500", countryCode = "GLOBAL")

        // Проверенные Anycast эндпоинты Cloudflare MASQUE HTTP/3 (порты 8443, 8095, 443)
        val MASQUE_FRA_8443 = WarpEndpointPresetOption("masque_fra_8443", "MASQUE Франкфурт 8443", "188.114.97.1:8443", countryCode = "DE")
        val MASQUE_ANYCAST_8095 = WarpEndpointPresetOption("masque_anycast_8095", "MASQUE Anycast 8095", "162.159.198.2:8095", countryCode = "GLOBAL")
        val MASQUE_ANYCAST_443 = WarpEndpointPresetOption("masque_anycast_443", "MASQUE Anycast 443", "188.114.96.1:443", countryCode = "GLOBAL")
        val MASQUE_EDGE_8443 = WarpEndpointPresetOption("masque_edge_8443", "MASQUE Edge 8443", "engage.cloudflareclient.com:8443", countryCode = "GLOBAL")

        val RANDOM_POOL = WarpEndpointPresetOption("random", "Случайный узел", "", countryCode = "RANDOM", isRandom = true)
        val CUSTOM = WarpEndpointPresetOption("custom", "Свой эндпоинт", "", countryCode = "CUSTOM", isCustom = true)

        // Совместимость с существующим кодом
        val FRA_4500 get() = WG_FRA_4500
        val FRA_500 get() = WG_FRA_500
        val AMS_443 get() = MASQUE_ANYCAST_443
        val USA_2408 get() = WG_EDGE_1701
        val ANYCAST_443 get() = MASQUE_ANYCAST_443
        val ENGAGE_500 get() = WG_EDGE_500
        val ENGAGE_1701 get() = WG_EDGE_1701
        val FRA_1 get() = WG_FRA_4500
        val FRA_2 get() = WG_FRA_500
        val AMS_1 get() = MASQUE_ANYCAST_443
        val USA_1 get() = WG_EDGE_1701
        val ANYCAST_DEFAULT get() = MASQUE_FRA_8443
        val ENGAGE_ALT get() = WG_EDGE_500

        val ACTIVE_PRESETS = listOf(
            MASQUE_ANYCAST_443,
            WG_EDGE_1701,
            WG_FRA_500,
            WG_EDGE_500,
            MASQUE_ANYCAST_8095,
            MASQUE_FRA_8443,
            WG_FRA_4500,
            RANDOM_POOL,
            CUSTOM
        )

        val ALL_PRESETS = ACTIVE_PRESETS

        fun forFormats(formats: Set<WarpGeneratorFormat>): List<WarpEndpointPresetOption> {
            val hasMasque = formats.any { it == WarpGeneratorFormat.WARP_MASQUE }
            val hasAwg = formats.any { it.isAwg }
            return when {
                hasMasque && !hasAwg -> listOf(MASQUE_ANYCAST_443, MASQUE_ANYCAST_8095, MASQUE_FRA_8443, MASQUE_EDGE_8443, RANDOM_POOL, CUSTOM)
                hasAwg && !hasMasque -> listOf(WG_EDGE_1701, WG_FRA_500, WG_EDGE_500, WG_FRA_4500, WG_EDGE_4500, RANDOM_POOL, CUSTOM)
                else -> listOf(MASQUE_ANYCAST_443, WG_EDGE_1701, WG_FRA_500, WG_EDGE_500, MASQUE_ANYCAST_8095, MASQUE_FRA_8443, WG_FRA_4500, RANDOM_POOL, CUSTOM)
            }
        }
    }
}

/**
 * Определение кода страны по IP-адресу или строке эндпоинта.
 */
fun resolveCountryCodeForEndpoint(endpoint: String): String {
    val clean = endpoint.trim().lowercase()
    val host = if (clean.startsWith("[")) {
        clean.substringAfter("[").substringBefore("]")
    } else {
        clean.substringBefore(":")
    }
    return when {
        clean.contains("random") -> "RANDOM"
        clean.contains("custom") -> "CUSTOM"
        clean.contains("engage.cloudflareclient.com") -> "GLOBAL"
        clean.contains("clean8095") -> "DE"
        host == "162.159.195.1" || host == "162.159.195.2" || host.startsWith("162.159.195.") -> "GLOBAL"
        host == "188.114.96.1" || host == "188.114.97.1" || host == "188.114.97.66" || host == "188.114.96.125" -> "DE"
        host == "188.114.96.2" || host == "188.114.97.2" -> "NL"
        host == "188.114.96.3" || host == "188.114.97.3" -> "FI"
        host == "188.114.96.5" || host == "188.114.97.5" || host == "188.114.97.15" -> "SE"
        host == "188.114.96.10" || host == "188.114.97.10" -> "GB"
        host.startsWith("162.159.192.") || host.startsWith("162.159.193.") || host.startsWith("162.159.204.") -> "US"
        host.startsWith("188.114.") -> "DE"
        host.startsWith("2606:") -> "GLOBAL"
        else -> "GLOBAL"
    }
}

/**
 * Предустановленные DNS-провайдеры для WARP туннеля.
 */
data class WarpDnsPresetOption(
    val id: String,
    val label: String,
    val ipv4: List<String>,
    val ipv6: List<String>,
    val isCommunity: Boolean = false,
    val isCustom: Boolean = false
) {
    fun buildDnsLine(includeIPv6: Boolean): String {
        val ips = if (includeIPv6 && ipv6.isNotEmpty()) ipv4 + ipv6 else ipv4
        return ips.joinToString(", ")
    }

    companion object {
        val CLOUDFLARE = WarpDnsPresetOption(
            id = "cf",
            label = "Cloudflare",
            ipv4 = listOf("1.1.1.1", "1.0.0.1"),
            ipv6 = listOf("2606:4700:4700::1111", "2606:4700:4700::1001")
        )

        val GOOGLE = WarpDnsPresetOption(
            id = "google",
            label = "Google",
            ipv4 = listOf("8.8.8.8", "8.8.4.4"),
            ipv6 = listOf("2001:4860:4860::8888", "2001:4860:4860::8844")
        )

        val QUAD9 = WarpDnsPresetOption(
            id = "quad9",
            label = "Quad9",
            ipv4 = listOf("9.9.9.9", "149.112.112.112"),
            ipv6 = listOf("2620:fe::fe", "2620:fe::9")
        )

        val MALW = WarpDnsPresetOption(
            id = "malw",
            label = "dns.malw.link",
            ipv4 = listOf("179.254.166.171", "164.5.224.81"),
            ipv6 = listOf("2a12:bec4:1460:294::2", "2a01:ecc0:680:120::2"),
            isCommunity = true
        )

        val GEOHIDE = WarpDnsPresetOption(
            id = "geohide",
            label = "dns.geohide.ru",
            ipv4 = listOf("159.194.200.33", "37.230.192.51", "193.233.112.67"),
            ipv6 = emptyList(),
            isCommunity = true
        )

        val COMSS = WarpDnsPresetOption(
            id = "comss",
            label = "dns.comss.one",
            ipv4 = listOf("83.220.169.155", "195.133.25.16", "94.250.253.55", "212.109.195.93"),
            ipv6 = emptyList(),
            isCommunity = true
        )

        val MAFIOZNIK = WarpDnsPresetOption(
            id = "mafioznik",
            label = "dns.mafioznik.xyz",
            ipv4 = listOf("103.27.157.38", "103.27.157.100"),
            ipv6 = emptyList(),
            isCommunity = true
        )

        val ALL_PRESETS = listOf(
            CLOUDFLARE,
            GOOGLE,
            QUAD9,
            COMSS,
            GEOHIDE,
            MALW,
            MAFIOZNIK
        )
    }
}

/**
 * Настройки AmneziaWG (AWG) обфускации.
 */
data class WarpAwgConfigParams(
    val jc: Int = 4,
    val jmin: Int = 40,
    val jmax: Int = 70,
    val s1: Int = 0, // WARP Safe: strictly 0
    val s2: Int = 0, // WARP Safe: strictly 0
    val s3: Int = 0, // WARP Safe: strictly 0
    val s4: Int = 0, // WARP Safe: strictly 0
    val h1: Long = 1L, // WARP Safe: strictly 1
    val h2: Long = 2L, // WARP Safe: strictly 2
    val h3: Long = 3L, // WARP Safe: strictly 3
    val h4: Long = 4L, // WARP Safe: strictly 4
    val i1: String = DEFAULT_I1,
    val contentPaddingAddition: String = "10-100",
    val rekeyAfterTime: String = "100-120",
    val rekeyTimeout: String = "3-7",
    val rejectAfterTime: String = "150-180",
    val keepaliveTimeout: String = "5-15",
    val maxHandshakeAttempts: String = "15-20",
    val disableCookies: Boolean = true,
    val maskDomain: String = "gosuslugi.ru"
) {
    companion object {
        const val DEFAULT_I1 = "<r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001c00c000100010000105a00044d583737>"
        fun defaultWarpSafe(): WarpAwgConfigParams = WarpAwgConfigParams()
    }
}

/**
 * Параметры сборки профиля генератором.
 */
data class WarpGeneratorBuildSettings(
    val formats: Set<WarpGeneratorFormat> = setOf(WarpGeneratorFormat.WARP_MASQUE),
    val endpoints: List<WarpEndpointPresetOption> = listOf(WarpEndpointPresetOption.MASQUE_ANYCAST_443),
    val customEndpoint: String = "",
    val dnsPresets: List<WarpDnsPresetOption> = listOf(WarpDnsPresetOption.CLOUDFLARE),
    val customDns: String = "",
    val includeIPv6: Boolean = false,
    val mtu: Int = 1280,
    val persistentKeepalive: Int = 25,
    val awgParams: WarpAwgConfigParams = WarpAwgConfigParams.defaultWarpSafe(),
    val randomizeRanges: Boolean = true,
    val customProfileName: String? = null
) {
    /**
     * Подсчитывает общее число конфигураций в пакете генерации.
     */
    fun calculateCombinationCount(): Int {
        val fCount = formats.size.coerceAtLeast(1)
        val epCount = endpoints.size.coerceAtLeast(1)
        val dnsCount = dnsPresets.size.coerceAtLeast(1)
        return fCount * epCount * dnsCount
    }
}

/**
 * Один сгенерированный результат профиля.
 */
data class WarpGeneratedProfileItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val format: WarpGeneratorFormat,
    val endpoint: String,
    val dnsLine: String,
    val content: String,
    val isWarpSafe: Boolean = true,
    val warnings: List<String> = emptyList(),
    val associatedWarpProfile: WarpProfile
)
