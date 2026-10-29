/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.core

import java.security.SecureRandom
import java.util.UUID

/**
 * Валидатор и генератор конфигураций Cloudflare WARP для WireGuard и AmneziaWG.
 */
object WarpConfigGeneratorEngine {

    private val secureRandom = SecureRandom()

    /**
     * Стандартные инварианты WireGuard / Cloudflare WARP.
     * Значения S1..S4 обязаны быть 0, а H1..H4 обязаны быть 1..4 для совместимости
     * со стандартным сервером Cloudflare Anycast.
     */
    val WARP_SAFE_S = listOf(0, 0, 0, 0)
    val WARP_SAFE_H = listOf(1L, 2L, 3L, 4L)
    const val DEFAULT_CF_PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    const val DEFAULT_CF_ENDPOINT = "188.114.96.1:8095"

    /**
     * Безопасное маскирование приватного ключа для предпросмотра в UI.
     * Показывает первые 4 и последние 4 символа Base64-ключа: `aB3d...xYz9`.
     */
    fun maskPrivateKey(key: String): String {
        val trimmed = key.trim()
        return if (trimmed.length > 8) {
            "${trimmed.take(4)}••••••••••••••••••••••••••••${trimmed.takeLast(4)}"
        } else {
            "••••••••••••••••"
        }
    }

    /**
     * Разбирает скаляр или диапазон (например, "10-100" или "40") и при необходимости
     * генерирует конкретное случайное значение в границах диапазона.
     */
    fun resolveRangeValue(
        raw: String,
        minAllowed: Int = 0,
        maxAllowed: Int = 65535,
        randomize: Boolean = true
    ): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.equals("off", ignoreCase = true)) return "off"

        val rangeMatch = Regex("^(\\d+)-(\\d+)$").matchEntire(trimmed)
        if (rangeMatch != null) {
            val lower = rangeMatch.groupValues[1].toIntOrNull() ?: minAllowed
            val upper = rangeMatch.groupValues[2].toIntOrNull() ?: maxAllowed
            val safeLower = lower.coerceIn(minAllowed, maxAllowed)
            val safeUpper = upper.coerceIn(safeLower, maxAllowed)
            return if (randomize) {
                if (safeLower == safeUpper) safeLower.toString()
                else (safeLower + secureRandom.nextInt(safeUpper - safeLower + 1)).toString()
            } else {
                "$safeLower-$safeUpper"
            }
        }

        val single = trimmed.toIntOrNull()
        return if (single != null) {
            single.coerceIn(minAllowed, maxAllowed).toString()
        } else {
            trimmed
        }
    }

    /**
     * Формирует строку адресов интерфейса с учетом флага IPv6.
     */
    fun buildAddressLine(ipv4: String, ipv6: String, includeIPv6: Boolean): String {
        val cleanV4 = ipv4.substringBefore("/").trim().ifBlank { "172.16.0.2" }
        val cleanV6 = ipv6.substringBefore("/").trim()
        return if (includeIPv6 && cleanV6.isNotBlank()) {
            "$cleanV4/32, $cleanV6/128"
        } else {
            "$cleanV4/32"
        }
    }

    /**
     * Сборка конфигурации WireGuard (.conf).
     */
    fun buildWireguardConf(
        privateKey: String,
        addressLine: String,
        dnsLine: String,
        peerPublicKey: String,
        endpoint: String,
        mtu: Int = 1280,
        keepalive: Int = 25
    ): String {
        val sb = StringBuilder()
        sb.appendLine("[Interface]")
        sb.appendLine("PrivateKey = $privateKey")
        sb.appendLine("Address = $addressLine")
        if (dnsLine.isNotBlank()) sb.appendLine("DNS = $dnsLine")
        sb.appendLine("MTU = $mtu")
        sb.appendLine()
        sb.appendLine("[Peer]")
        sb.appendLine("PublicKey = $peerPublicKey")
        sb.appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        sb.appendLine("Endpoint = $endpoint")
        if (keepalive > 0) sb.appendLine("PersistentKeepalive = $keepalive")
        return sb.toString().trim()
    }

    /**
     * Сборка конфигурации AmneziaWG 1.0 (.conf) с параметрами Jc, Jmin, Jmax.
     */
    fun buildAwg1Conf(
        privateKey: String,
        addressLine: String,
        dnsLine: String,
        peerPublicKey: String,
        endpoint: String,
        params: WarpAwgConfigParams,
        mtu: Int = 1280,
        keepalive: Int = 25,
        randomize: Boolean = true
    ): String {
        val jc = params.jc.coerceIn(1, 128)
        val jmin = params.jmin.coerceIn(0, 1280)
        val jmax = params.jmax.coerceIn(jmin, 1280)

        val sb = StringBuilder()
        sb.appendLine("[Interface]")
        sb.appendLine("PrivateKey = $privateKey")
        sb.appendLine("Address = $addressLine")
        if (dnsLine.isNotBlank()) sb.appendLine("DNS = $dnsLine")
        sb.appendLine("MTU = $mtu")
        sb.appendLine("Jc = $jc")
        sb.appendLine("Jmin = $jmin")
        sb.appendLine("Jmax = $jmax")
        sb.appendLine("S1 = 0")
        sb.appendLine("S2 = 0")
        sb.appendLine("H1 = 1")
        sb.appendLine("H2 = 2")
        sb.appendLine("H3 = 3")
        sb.appendLine("H4 = 4")
        sb.appendLine()
        sb.appendLine("[Peer]")
        sb.appendLine("PublicKey = $peerPublicKey")
        sb.appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        sb.appendLine("Endpoint = $endpoint")
        if (keepalive > 0) sb.appendLine("PersistentKeepalive = $keepalive")
        return sb.toString().trim()
    }

    /**
     * Сборка конфигурации AmneziaWG 2.0 (.conf) с полной структурой S1-S4 и H1-H4.
     */
    fun buildAwg2Conf(
        privateKey: String,
        addressLine: String,
        dnsLine: String,
        peerPublicKey: String,
        endpoint: String,
        params: WarpAwgConfigParams,
        mtu: Int = 1280,
        keepalive: Int = 25,
        randomize: Boolean = true
    ): String {
        val jc = params.jc.coerceIn(1, 128)
        val jmin = params.jmin.coerceIn(0, 1280)
        val jmax = params.jmax.coerceIn(jmin, 1280)

        val sb = StringBuilder()
        sb.appendLine("[Interface]")
        sb.appendLine("PrivateKey = $privateKey")
        sb.appendLine("Address = $addressLine")
        if (dnsLine.isNotBlank()) sb.appendLine("DNS = $dnsLine")
        sb.appendLine("MTU = $mtu")
        sb.appendLine("Jc = $jc")
        sb.appendLine("Jmin = $jmin")
        sb.appendLine("Jmax = $jmax")
        sb.appendLine("S1 = 0")
        sb.appendLine("S2 = 0")
        sb.appendLine("S3 = 0")
        sb.appendLine("S4 = 0")
        sb.appendLine("H1 = 1")
        sb.appendLine("H2 = 2")
        sb.appendLine("H3 = 3")
        sb.appendLine("H4 = 4")
        sb.appendLine()
        sb.appendLine("[Peer]")
        sb.appendLine("PublicKey = $peerPublicKey")
        sb.appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        sb.appendLine("Endpoint = $endpoint")
        if (keepalive > 0) sb.appendLine("PersistentKeepalive = $keepalive")
        return sb.toString().trim()
    }

    /**
     * Сборка конфигурации AmneziaWG 3.0 (.conf) с поддержкой сигнатуры I1, паддинга и таймингов.
     */
    fun buildAwg3Conf(
        privateKey: String,
        addressLine: String,
        dnsLine: String,
        peerPublicKey: String,
        endpoint: String,
        params: WarpAwgConfigParams,
        mtu: Int = 1280,
        keepalive: Int = 25,
        randomize: Boolean = true
    ): String {
        val jc = params.jc.coerceIn(1, 128)
        val jmin = params.jmin.coerceIn(0, 1280)
        val jmax = params.jmax.coerceIn(jmin, 1280)
        val contentPadding = resolveRangeValue(params.contentPaddingAddition, 0, 1280, randomize)
        val rekeyAfter = resolveRangeValue(params.rekeyAfterTime, 1, 3600, randomize)
        val rekeyTimeout = resolveRangeValue(params.rekeyTimeout, 1, 60, randomize)
        val rejectAfter = resolveRangeValue(params.rejectAfterTime, 1, 3600, randomize)
        val keepaliveTimeout = resolveRangeValue(params.keepaliveTimeout, 1, 60, randomize)
        val maxHandshakeAttempts = resolveRangeValue(params.maxHandshakeAttempts, 1, 100, randomize)

        val sb = StringBuilder()
        sb.appendLine("[Interface]")
        sb.appendLine("PrivateKey = $privateKey")
        sb.appendLine("Address = $addressLine")
        if (dnsLine.isNotBlank()) sb.appendLine("DNS = $dnsLine")
        sb.appendLine("MTU = $mtu")
        sb.appendLine("Jc = $jc")
        sb.appendLine("Jmin = $jmin")
        sb.appendLine("Jmax = $jmax")
        sb.appendLine("S1 = 0")
        sb.appendLine("S2 = 0")
        sb.appendLine("S3 = 0")
        sb.appendLine("S4 = 0")
        sb.appendLine("H1 = 1")
        sb.appendLine("H2 = 2")
        sb.appendLine("H3 = 3")
        sb.appendLine("H4 = 4")
        if (params.i1.isNotBlank()) {
            sb.appendLine("I1 = ${params.i1.trim()}")
        }
        if (contentPadding.isNotBlank() && contentPadding != "off") {
            sb.appendLine("ContentPaddingAddition = $contentPadding")
        }
        if (rekeyAfter.isNotBlank()) sb.appendLine("RekeyAfterTime = $rekeyAfter")
        if (rekeyTimeout.isNotBlank()) sb.appendLine("RekeyTimeout = $rekeyTimeout")
        if (rejectAfter.isNotBlank()) sb.appendLine("RejectAfterTime = $rejectAfter")
        if (keepaliveTimeout.isNotBlank()) sb.appendLine("KeepaliveTimeout = $keepaliveTimeout")
        if (maxHandshakeAttempts.isNotBlank()) sb.appendLine("MaxHandshakeAttempts = $maxHandshakeAttempts")
        sb.appendLine()
        sb.appendLine("[Peer]")
        sb.appendLine("PublicKey = $peerPublicKey")
        sb.appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        sb.appendLine("Endpoint = $endpoint")
        if (keepalive > 0) sb.appendLine("PersistentKeepalive = $keepalive")
        return sb.toString().trim()
    }

    /**
     * Сборка конфигурации AmneziaWG 3.1 (.conf) с поддержкой DisableCookies.
     */
    fun buildAwg31Conf(
        privateKey: String,
        addressLine: String,
        dnsLine: String,
        peerPublicKey: String,
        endpoint: String,
        params: WarpAwgConfigParams,
        mtu: Int = 1280,
        keepalive: Int = 25,
        randomize: Boolean = true
    ): String {
        val base = buildAwg3Conf(
            privateKey = privateKey,
            addressLine = addressLine,
            dnsLine = dnsLine,
            peerPublicKey = peerPublicKey,
            endpoint = endpoint,
            params = params,
            mtu = mtu,
            keepalive = keepalive,
            randomize = randomize
        )
        val disableCookieVal = if (params.disableCookies) "on" else "off"
        // Вставляем DisableCookies перед секцией [Peer]
        return if (base.contains("[Peer]")) {
            base.replace("[Peer]", "DisableCookies = $disableCookieVal\nRandomTrailers = off\n\n[Peer]")
        } else {
            "$base\nDisableCookies = $disableCookieVal\nRandomTrailers = off"
        }
    }

    /**
     * Сборка конфигурации Clash Meta / Mihomo (.yaml).
     */
    fun buildClashMetaYaml(
        profileName: String,
        privateKey: String,
        ipv4: String,
        ipv6: String,
        includeIPv6: Boolean,
        dnsList: List<String>,
        peerPublicKey: String,
        endpoint: String,
        mtu: Int = 1280,
        keepalive: Int = 25
    ): String {
        val (server, port) = WarpEndpointScanner.parseEndpoint(endpoint)
        val safePort = if (port <= 0) 8095 else port
        val cleanV4 = ipv4.substringBefore("/").trim().ifBlank { "172.16.0.2" }
        val cleanV6 = ipv6.substringBefore("/").trim()
        val dnsEntries = if (dnsList.isNotEmpty()) dnsList.joinToString(", ") { "\"$it\"" } else "\"1.1.1.1\", \"1.0.0.1\""

        val sb = StringBuilder()
        sb.appendLine("proxies:")
        sb.appendLine("  - name: \"$profileName\"")
        sb.appendLine("    type: wireguard")
        sb.appendLine("    server: $server")
        sb.appendLine("    port: $safePort")
        sb.appendLine("    ip: $cleanV4")
        if (includeIPv6 && cleanV6.isNotBlank()) {
            sb.appendLine("    ipv6: $cleanV6")
        }
        sb.appendLine("    public-key: $peerPublicKey")
        sb.appendLine("    private-key: $privateKey")
        sb.appendLine("    udp: true")
        sb.appendLine("    remote-dns-resolve: true")
        sb.appendLine("    dns: [$dnsEntries]")
        sb.appendLine("    mtu: $mtu")
        if (keepalive > 0) {
            sb.appendLine("    keepalive: $keepalive")
        }
        return sb.toString().trim()
    }

    /**
     * Сборка конфигурации WireSock (.conf).
     */
    fun buildWiresockConf(
        privateKey: String,
        addressLine: String,
        dnsLine: String,
        peerPublicKey: String,
        endpoint: String,
        mtu: Int = 1280,
        keepalive: Int = 25
    ): String {
        val sb = StringBuilder()
        sb.appendLine("[Interface]")
        sb.appendLine("PrivateKey = $privateKey")
        sb.appendLine("Address = $addressLine")
        if (dnsLine.isNotBlank()) sb.appendLine("DNS = $dnsLine")
        sb.appendLine("MTU = $mtu")
        sb.appendLine()
        sb.appendLine("[Peer]")
        sb.appendLine("PublicKey = $peerPublicKey")
        sb.appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        sb.appendLine("Endpoint = $endpoint")
        if (keepalive > 0) sb.appendLine("PersistentKeepalive = $keepalive")
        return sb.toString().trim()
    }

    /**
     * Валидация совместимости настроек с Cloudflare WARP.
     */
    fun validateWarpSafety(
        format: WarpGeneratorFormat,
        params: WarpAwgConfigParams
    ): Pair<Boolean, List<String>> {
        val warnings = mutableListOf<String>()

        if (format.isAwg) {
            if (params.s1 != 0 || params.s2 != 0 || params.s3 != 0 || params.s4 != 0) {
                warnings.add("S1..S4 должны быть равны 0 для серверов Cloudflare Anycast (иначе хэндшейк отбрасывается)")
            }
            if (params.h1 != 1L || params.h2 != 2L || params.h3 != 3L || params.h4 != 4L) {
                warnings.add("Заголовки H1..H4 должны быть стандартными (1..4) для Cloudflare WARP")
            }
        }

        return Pair(warnings.isEmpty(), warnings)
    }

    /**
     * Генерация пакета конфигураций на основе выбранных параметров.
     * Не выполняет повторных сетевых вызовов в Cloudflare — использует уже зарегистрированную
     * идентичность из baseProfile.
     */
    fun generateBatch(
        baseProfile: WarpProfile,
        settings: WarpGeneratorBuildSettings
    ): List<WarpGeneratedProfileItem> {
        val results = mutableListOf<WarpGeneratedProfileItem>()

        val effectiveFormats = if (settings.formats.isNotEmpty()) settings.formats.toList() else listOf(WarpGeneratorFormat.AWG_1_0)
        val effectiveEndpoints = if (settings.endpoints.isNotEmpty()) settings.endpoints else listOf(WarpEndpointPresetOption.CLEAN_8095)
        val effectiveDnsPresets = if (settings.dnsPresets.isNotEmpty()) settings.dnsPresets else listOf(WarpDnsPresetOption.CLOUDFLARE)

        // Защита от чрезмерного декартова произведения (максимум 64 комбинации)
        val maxBatchLimit = 64
        var generatedCount = 0

        for (format in effectiveFormats) {
            for (endpointOpt in effectiveEndpoints) {
                for (dnsOpt in effectiveDnsPresets) {
                    if (generatedCount >= maxBatchLimit) break

                    val endpointStr = when {
                        endpointOpt.isCustom -> settings.customEndpoint.trim().ifBlank { DEFAULT_CF_ENDPOINT }
                        endpointOpt.isRandom -> {
                            val pool = listOf(
                                "162.159.192.1:2408", "162.159.195.5:2408", "188.114.96.1:8095",
                                "188.114.97.1:8095", "188.114.96.1:500", "188.114.97.1:4500"
                            )
                            pool[secureRandom.nextInt(pool.size)]
                        }
                        else -> endpointOpt.endpoint.ifBlank { DEFAULT_CF_ENDPOINT }
                    }

                    val (dnsLine, dnsList) = when {
                        dnsOpt.isCustom -> {
                            val clean = settings.customDns.trim().ifBlank { "1.1.1.1, 1.0.0.1" }
                            Pair(clean, clean.split(",").map { it.trim() })
                        }
                        else -> {
                            val line = dnsOpt.buildDnsLine(settings.includeIPv6)
                            val list = if (settings.includeIPv6 && dnsOpt.ipv6.isNotEmpty()) dnsOpt.ipv4 + dnsOpt.ipv6 else dnsOpt.ipv4
                            Pair(line, list)
                        }
                    }

                    val addressLine = buildAddressLine(
                        ipv4 = baseProfile.clientIpv4,
                        ipv6 = baseProfile.clientIpv6,
                        includeIPv6 = settings.includeIPv6
                    )

                    val (isSafe, safetyWarnings) = validateWarpSafety(format, settings.awgParams)

                    // Формирование уникального понятного имени профиля (максимум 10 символов)
                    val totalBatchCombinations = effectiveFormats.size * effectiveEndpoints.size * effectiveDnsPresets.size
                    val profileName = if (!settings.customProfileName.isNullOrBlank()) {
                        val cleanCustom = settings.customProfileName.trim()
                        if (totalBatchCombinations > 1) {
                            val indexSuffix = "-${results.size + 1}"
                            (cleanCustom.take((10 - indexSuffix.length).coerceAtLeast(1)) + indexSuffix).take(10)
                        } else {
                            cleanCustom.take(10)
                        }
                    } else {
                        val fmtCode = when (format) {
                            WarpGeneratorFormat.AWG_1_0 -> "AWG1"
                            WarpGeneratorFormat.AWG_2_0 -> "AWG2"
                            WarpGeneratorFormat.AWG_3_0 -> "AWG3"
                            WarpGeneratorFormat.AWG_3_1 -> "AWG31"
                            WarpGeneratorFormat.WIREGUARD -> "WG"
                            WarpGeneratorFormat.CLASH_META -> "CLASH"
                            WarpGeneratorFormat.WIRESOCK -> "WSOCK"
                        }
                        val epCode = when {
                            endpointOpt.id.contains("8095") -> "8095"
                            endpointOpt.id.contains("2408") -> "2408"
                            endpointOpt.id.contains("500") -> "500"
                            endpointOpt.id.contains("4500") -> "4500"
                            endpointOpt.id.contains("de", ignoreCase = true) -> "DE"
                            endpointOpt.id.contains("nl", ignoreCase = true) -> "NL"
                            endpointOpt.id.contains("fi", ignoreCase = true) -> "FI"
                            endpointOpt.id.contains("gb", ignoreCase = true) -> "GB"
                            endpointOpt.id.contains("us", ignoreCase = true) -> "US"
                            else -> endpointOpt.id.filter { it.isLetterOrDigit() }.take(4)
                        }
                        val autoCandidate = if (epCode.isNotBlank()) "$fmtCode-$epCode" else fmtCode
                        autoCandidate.take(10)
                    }

                    val content = when (format) {
                        WarpGeneratorFormat.WIREGUARD -> buildWireguardConf(
                            privateKey = baseProfile.privateKeyBase64,
                            addressLine = addressLine,
                            dnsLine = dnsLine,
                            peerPublicKey = baseProfile.peerPublicKey.ifBlank { DEFAULT_CF_PEER_KEY },
                            endpoint = endpointStr,
                            mtu = settings.mtu,
                            keepalive = settings.persistentKeepalive
                        )
                        WarpGeneratorFormat.AWG_1_0 -> buildAwg1Conf(
                            privateKey = baseProfile.privateKeyBase64,
                            addressLine = addressLine,
                            dnsLine = dnsLine,
                            peerPublicKey = baseProfile.peerPublicKey.ifBlank { DEFAULT_CF_PEER_KEY },
                            endpoint = endpointStr,
                            params = settings.awgParams,
                            mtu = settings.mtu,
                            keepalive = settings.persistentKeepalive,
                            randomize = settings.randomizeRanges
                        )
                        WarpGeneratorFormat.AWG_2_0 -> buildAwg2Conf(
                            privateKey = baseProfile.privateKeyBase64,
                            addressLine = addressLine,
                            dnsLine = dnsLine,
                            peerPublicKey = baseProfile.peerPublicKey.ifBlank { DEFAULT_CF_PEER_KEY },
                            endpoint = endpointStr,
                            params = settings.awgParams,
                            mtu = settings.mtu,
                            keepalive = settings.persistentKeepalive,
                            randomize = settings.randomizeRanges
                        )
                        WarpGeneratorFormat.AWG_3_0 -> buildAwg3Conf(
                            privateKey = baseProfile.privateKeyBase64,
                            addressLine = addressLine,
                            dnsLine = dnsLine,
                            peerPublicKey = baseProfile.peerPublicKey.ifBlank { DEFAULT_CF_PEER_KEY },
                            endpoint = endpointStr,
                            params = settings.awgParams,
                            mtu = settings.mtu,
                            keepalive = settings.persistentKeepalive,
                            randomize = settings.randomizeRanges
                        )
                        WarpGeneratorFormat.AWG_3_1 -> buildAwg31Conf(
                            privateKey = baseProfile.privateKeyBase64,
                            addressLine = addressLine,
                            dnsLine = dnsLine,
                            peerPublicKey = baseProfile.peerPublicKey.ifBlank { DEFAULT_CF_PEER_KEY },
                            endpoint = endpointStr,
                            params = settings.awgParams,
                            mtu = settings.mtu,
                            keepalive = settings.persistentKeepalive,
                            randomize = settings.randomizeRanges
                        )
                        WarpGeneratorFormat.CLASH_META -> buildClashMetaYaml(
                            profileName = profileName,
                            privateKey = baseProfile.privateKeyBase64,
                            ipv4 = baseProfile.clientIpv4,
                            ipv6 = baseProfile.clientIpv6,
                            includeIPv6 = settings.includeIPv6,
                            dnsList = dnsList,
                            peerPublicKey = baseProfile.peerPublicKey.ifBlank { DEFAULT_CF_PEER_KEY },
                            endpoint = endpointStr,
                            mtu = settings.mtu,
                            keepalive = settings.persistentKeepalive
                        )
                        WarpGeneratorFormat.WIRESOCK -> buildWiresockConf(
                            privateKey = baseProfile.privateKeyBase64,
                            addressLine = addressLine,
                            dnsLine = dnsLine,
                            peerPublicKey = baseProfile.peerPublicKey.ifBlank { DEFAULT_CF_PEER_KEY },
                            endpoint = endpointStr,
                            mtu = settings.mtu,
                            keepalive = settings.persistentKeepalive
                        )
                    }

                    // Формируем связанный WarpProfile с настроенным эндпоинтом
                    val associatedProfile = baseProfile.copy(
                        peerEndpoint = endpointStr,
                        userEndpointOverride = endpointStr,
                        awgRawConfig = content
                    )

                    results.add(
                        WarpGeneratedProfileItem(
                            id = UUID.randomUUID().toString(),
                            name = profileName,
                            format = format,
                            endpoint = endpointStr,
                            dnsLine = dnsLine,
                            content = content,
                            isWarpSafe = isSafe,
                            warnings = safetyWarnings,
                            associatedWarpProfile = associatedProfile
                        )
                    )
                    generatedCount++
                }
            }
        }

        return results
    }
}
