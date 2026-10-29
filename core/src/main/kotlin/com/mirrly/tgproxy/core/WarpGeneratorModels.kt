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
    ),
    WIREGUARD(
        id = "wireguard",
        title = "WireGuard",
        extension = "conf",
        description = "Классический профиль WireGuard без параметров обфускации"
    ),
    WIRESOCK(
        id = "wiresock",
        title = "WireSock",
        extension = "conf",
        description = "Профиль WireSock с маскировкой под QUIC и TLS трафик"
    ),
    CLASH_META(
        id = "clash",
        title = "Clash Meta",
        extension = "yaml",
        description = "Конфигурация прокси для клиентов Clash Meta / Mihomo"
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
        // Активные эндпоинты Cloudflare (порты 4500, 8443, 2408, 500)
        val FRA_1 = WarpEndpointPresetOption("fra1", "Франкфурт 1", "188.114.97.66:4500", countryCode = "DE")
        val FRA_2 = WarpEndpointPresetOption("fra2", "Франкфурт 2", "188.114.96.125:4500", countryCode = "DE")
        val AMS_1 = WarpEndpointPresetOption("ams1", "Амстердам", "188.114.96.2:8443", countryCode = "NL")
        val USA_1 = WarpEndpointPresetOption("usa1", "США Edge", "162.159.192.1:2408", countryCode = "US")
        val ANYCAST_DEFAULT = WarpEndpointPresetOption("default", "Anycast", "162.159.195.1:500", countryCode = "GLOBAL")
        val ENGAGE_ALT = WarpEndpointPresetOption("engage", "Cloudflare Edge", "engage.cloudflareclient.com:2408", countryCode = "GLOBAL")
        val RANDOM_POOL = WarpEndpointPresetOption("random", "Случайный узел", "", countryCode = "RANDOM", isRandom = true)
        val CUSTOM = WarpEndpointPresetOption("custom", "Свой эндпоинт", "", countryCode = "CUSTOM", isCustom = true)

        // Заблокированные в РФ порты 8095 ТСПУ
        val CLEAN_8095 = WarpEndpointPresetOption("clean8095", "Германия 8095", "188.114.96.1:8095", countryCode = "DE", isBlocked = true)
        val HEL_1 = WarpEndpointPresetOption("hel1", "Хельсинки", "188.114.96.3:8095", countryCode = "FI", isBlocked = true)
        val STO_1 = WarpEndpointPresetOption("sto1", "Стокгольм", "188.114.97.15:8095", countryCode = "SE", isBlocked = true)
        val LON_1 = WarpEndpointPresetOption("lon1", "Лондон", "188.114.96.10:8095", countryCode = "GB", isBlocked = true)

        val ACTIVE_PRESETS = listOf(
            FRA_1,
            FRA_2,
            AMS_1,
            USA_1,
            ANYCAST_DEFAULT,
            ENGAGE_ALT,
            RANDOM_POOL,
            CUSTOM
        )

        val BLOCKED_8095_PRESETS = listOf(
            CLEAN_8095,
            HEL_1,
            STO_1,
            LON_1
        )

        val ALL_PRESETS = ACTIVE_PRESETS + BLOCKED_8095_PRESETS
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
    val i1: String = "",
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
        fun defaultWarpSafe(): WarpAwgConfigParams = WarpAwgConfigParams()
    }
}

/**
 * Параметры сборки профиля генератором.
 */
data class WarpGeneratorBuildSettings(
    val formats: Set<WarpGeneratorFormat> = setOf(WarpGeneratorFormat.AWG_3_1),
    val endpoints: List<WarpEndpointPresetOption> = listOf(WarpEndpointPresetOption.FRA_1),
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
