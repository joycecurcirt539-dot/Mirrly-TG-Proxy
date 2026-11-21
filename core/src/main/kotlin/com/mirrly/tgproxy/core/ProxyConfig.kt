package com.mirrly.tgproxy.core

import java.util.Base64

enum class SpeedPreset(
    val displayName: String,
    val defaultMtprotoStandbyPerActiveSlot: Int,
    val defaultBufferSizeBytes: Int
) {
    ECO("Эко (1 резерв/слот)", 1, 131072),
    BALANCED("Баланс (2 резерва/слот)", 2, 262144),
    TURBO("Турбо (3 резерва/слот)", 3, 524288),
    ULTRA("Ультра (4 резерва/слот)", 4, 1048576),
    AUTO("Авто (динамический)", 2, 0)
}

/**
 * Режим работы локального прокси.
 * MTPROTO — проксирование чистого MTProto трафика (каналы, чаты, медиа).
 * SOCKS5  — локальный TCP relay (все приложения + звонки через SOCKS5).
 */
enum class ProxyMode {
    MTPROTO,
    SOCKS5
}

/**
 * Режим управления алгоритмом Нагла (RFC 896).
 * ENABLED  — Алгоритм Нагла включен (по умолчанию). Буферизация и объединение мелких пакетов (TCP_NODELAY = false).
 * DISABLED — Алгоритм Нагла выключен (опасно). Немедленная отправка каждого пакета (TCP_NODELAY = true).
 */
enum class NagleMode(val displayName: String) {
    ENABLED("Включен"),
    DISABLED("Выключен")
}

/**
 * Устаревший enum управления TCP_NODELAY.
 */
@Deprecated("Use NagleMode instead")
enum class TcpNoDelayMode(val displayName: String) {
    AUTO("Авто"),
    ON("ВКЛ"),
    OFF("ВЫКЛ")
}

/** Address-family policy for outbound tunnel dials. */
enum class IpFamilyPreference {
    DUAL_STACK,
    IPV4_ONLY,
    IPV6_FIRST
}

/**
 * Режимы восходящего канала (Uplink).
 * WORKER  — стандартная маршрутизация через Cloudflare Workers по протоколу WebSocket (TLS 443).
 * AWG     — прямое туннелирование через Cloudflare WARP AmneziaWG (HTTP/3 CONNECT-UDP / QUIC Datagrams на порт 443).
 * HYBRID  — гибридный отказоустойчивый режим (основной канал через Worker с переключением на WARP AWG при ошибках/кодах 429).
 */
enum class UplinkMode(val displayName: String, val subtitle: String) {
    WORKER("Cloudflare Worker · WSS", "Классический режим туннелирования. Надежный WebSocket-транспорт через Cloudflare Workers"),
    AWG("WARP AmneziaWG", "Замаскированный протокол WireGuard Anycast с защитой от блокировок через QUIC")
}

/**
 * Режимы DNS-серверов для VPN-режима (WARP AWG).
 * CLOUDFLARE - стандартные Anycast DNS Cloudflare (1.1.1.1, 1.0.0.1, 2606:4700:4700::1111).
 * COMSS_DNS  - SmartDNS для доступа к сервисам ИИ (ChatGPT, Claude, Gemini, Copilot) без IPv6-утечек (83.220.169.155, 195.133.25.16).
 * CUSTOM     - произвольные пользовательские IPv4 DNS серверы.
 */
enum class VpnDnsMode(val displayName: String, val subtitle: String) {
    CLOUDFLARE("Cloudflare DNS", "Стандартные Anycast DNS (1.1.1.1, 1.0.0.1) с поддержкой IPv6"),
    COMSS_DNS("Comss.one DNS", "Обход блокировок ИИ (ChatGPT, Claude, Gemini, Copilot) без IPv6-утечек"),
    GOOGLE("Google DNS", "Anycast DNS от Google (8.8.8.8, 8.8.4.4)"),
    QUAD9("Quad9 DNS", "Безопасный приватный DNS без логирования (9.9.9.9)"),
    CUSTOM("Пользовательский", "Собственные IPv4 DNS серверы (AdGuard, Comss или локальные)")
}

data class ProxyConfig(
    var bindHost: String = "127.0.0.1",
    var bindPort: Int = 1443,
    var secretHex: String = "dd00000000000000000000000000000000",
    var cfProxyEnabled: Boolean = true,
    var customCfDomain: String = "",
    var isTlsEnabled: Boolean = false,
    var isTlsRecordPaddingEnabled: Boolean = true,
    var isTlsClientHelloSplitEnabled: Boolean = true,
    var mtprotoStandbyPerActiveSlotValue: Int = 2,
    var isDcAuto: Boolean = true,
    var autostartOnBoot: Boolean = false,
    var verboseLogs: Boolean = true,
    var isTestEnvironment: Boolean = false,
    var speedPresetName: String = SpeedPreset.AUTO.name,
    var tcpNoDelayModeName: String = NagleMode.ENABLED.name,
    var tcpNoDelay: Boolean = false, // По умолчанию false: алгоритм Нагла включен для стабильности
    var bufferSizeBytes: Int = 0, // OS TCP buffer autotuning
    var happyEyeballsDelayMs: Long = 200L,
    var ipFamilyPreferenceName: String = IpFamilyPreference.IPV4_ONLY.name,
    var webSocketKeepAliveSeconds: Int = 60,
    var socks5Port: Int = 10888,
    var socks5Username: String = "",
    var socks5Password: String = "",
    var socks5OptimisticResponse: Boolean = true,
    var socks5UplinkMode: Int = 1,
    var targetTelegramPort: Int = 443,
    var useDefaultWorkerSocks5: Boolean = false,
    var useWorkerMtproto: Boolean = false,
    var isBatteryGuardEnabled: Boolean = false,
    var batteryGuardThreshold: Int = 15,
    var batteryGuardStopOnPowerSave: Boolean = false,
    var isAdaptiveQoSEnabled: Boolean = false,
    var isDialThrottlingEnabled: Boolean = false,
    var isAlarmClockWatchdogEnabled: Boolean = true,
    var isCpuAntiFreezeEnabled: Boolean = false,
    var isTrafficPulseEnabled: Boolean = true,
    // proxyModeName — единый источник истины (MTPROTO или SOCKS5)
    var proxyModeName: String = ProxyMode.MTPROTO.name,
    // Режим аплинка для прокси (MTProto / SOCKS5): всегда WORKER
    var uplinkModeName: String = UplinkMode.WORKER.name,
    // Выделенный режим туннеля для системного VPN (Android VpnService)
    var vpnUplinkModeName: String = UplinkMode.AWG.name,
    // Параметры архитектуры ядра маршрутизации VPN
    var vpnMtu: Int = 1280,
    var vpnBlockQuic: Boolean = true,
    var vpnBlockIpv6Leaks: Boolean = true,
    var warpAccountId: String = "",
    var warpToken: String = "",
    var warpLicenseKey: String = "",
    var warpClientIpv4: String = "172.16.0.2",
    var warpClientIpv6: String = "",
    var warpPeerEndpoint: String = "162.159.192.1:4500",
    var warpUriTemplate: String = "/",
    var warpPeerPublicKey: String = "",
    var warpPrivateKey: String = "",
    var warpPublicKey: String = "",
    var warpP256PrivateKey: String = "",
    var warpP256PublicKey: String = "",
    var warpClientCert: String = "",
    var isWarpPlus: Boolean = false,
    var isWarpAccountActive: Boolean = false,
    var isWgValid: Boolean = false,
    var warpApiEndpoint: String = "",
    var warpUserEndpointOverride: String = "",
    var warpMeasuredWgEndpoint: String = "",
    var warpClientId: String = "",

    // Параметры обфускации AmneziaWG (AWG) Jc, Jmin, Jmax, S1..S4, H1..H4
    var awgJc: Int = 0,
    var awgJmin: Int = 0,
    var awgJmax: Int = 0,
    var awgS1: Int = 0,
    var awgS2: Int = 0,
    var awgS3: Int = 0,
    var awgS4: Int = 0,
    var awgH1: Long = 1L,
    var awgH2: Long = 2L,
    var awgH3: Long = 3L,
    var awgH4: Long = 4L,

    // Стратегия обфускации AmneziaWG (FAST, BALANCED, DEEP_STEALTH, CUSTOM)
    var awgStrategyName: String = AwgObfuscationStrategy.BALANCED.name,
    // Кастомная INI-конфигурация AWG (если задана, используется вместо встроенного WARP-профиля)
    var awgCustomIni: String = "",
    // Параметры CPS-обфускации I1..I5 (QUIC Initial / кастомная маскировка первого пакета)
    var awgI1: String = "",
    var awgI2: String = "",
    var awgI3: String = "",
    var awgI4: String = "",
    var awgI5: String = "",
    // Явная политика доверия для fallback (O02)
    var allowPublicRelayFallbackForPrivateVps: Boolean = false,
    var isLivenessProbeEnabled: Boolean = false,
    var livenessProbeTimeoutMs: Int = 1500,
    var livenessProbeFailoverThreshold: Int = 2,
    // Режим DNS для VPN (Cloudflare, XboxDNS SmartDNS, Custom)
    var vpnDnsModeName: String = VpnDnsMode.CLOUDFLARE.name,
    var vpnCustomDnsIps: String = "",
    // Управление TLS (DoT :853 / WARP :443 probe fallback) в VPN-режиме
    var isVpnTlsEnabled: Boolean = true
) {
    var ipFamilyPreference: IpFamilyPreference
        get() = runCatching { IpFamilyPreference.valueOf(ipFamilyPreferenceName) }
            .getOrDefault(IpFamilyPreference.IPV4_ONLY)
        set(value) { ipFamilyPreferenceName = value.name }
    val awgStrategy: AwgObfuscationStrategy
        get() = AwgObfuscationStrategy.fromName(awgStrategyName)

    val isDedicatedVps: Boolean
        get() = awgStrategy == AwgObfuscationStrategy.CUSTOM && awgCustomIni.isNotBlank()

    val vpnDnsMode: VpnDnsMode
        get() = when (vpnDnsModeName) {
            "XBOX_DNS" -> VpnDnsMode.COMSS_DNS
            else -> runCatching { VpnDnsMode.valueOf(vpnDnsModeName) }.getOrDefault(VpnDnsMode.CLOUDFLARE)
        }

    /**
     * Возвращает эффективный список IPv4 DNS-серверов для VPN-туннеля.
     */
    fun getEffectiveVpnDnsIpv4(): List<String> {
        return when (vpnDnsMode) {
            VpnDnsMode.CLOUDFLARE -> CLOUDFLARE_DNS_IPV4
            VpnDnsMode.COMSS_DNS -> COMSS_DNS_IPV4
            VpnDnsMode.GOOGLE -> GOOGLE_DNS_IPV4
            VpnDnsMode.QUAD9 -> QUAD9_DNS_IPV4
            VpnDnsMode.CUSTOM -> {
                val parsed = parseCustomDnsIps(vpnCustomDnsIps)
                if (parsed.isNotEmpty()) parsed else CLOUDFLARE_DNS_IPV4
            }
        }
    }

    /**
     * Возвращает IPv6 DNS-сервер для VPN-туннеля или null.
     * Для SmartDNS (Comss.one) и Custom возвращает строго null, чтобы предотвратить параллельные AAAA-запросы в обход SmartDNS.
     */
    fun getEffectiveVpnDnsIpv6(): String? {
        return when (vpnDnsMode) {
            VpnDnsMode.CLOUDFLARE -> CLOUDFLARE_DNS_IPV6
            VpnDnsMode.COMSS_DNS -> null
            VpnDnsMode.GOOGLE -> GOOGLE_DNS_IPV6
            VpnDnsMode.QUAD9 -> QUAD9_DNS_IPV6
            VpnDnsMode.CUSTOM -> null
        }
    }

    /**
     * Генерирует валидную конфигурацию AmneziaWG (AWG) на основе профиля WARP
     * в соответствии с выбранной стратегией обфускации и настройками DNS.
     * Для серверов Cloudflare Anycast мусорные пакеты строго отключены (Jc = 0).
     */
    fun getAmneziaWgConfig(
        cleanEndpoint: String = effectivePeerEndpoint,
        strategy: AwgObfuscationStrategy = awgStrategy
    ): String {
        val isCf = isCloudflareEndpoint(cleanEndpoint) ||
            (cleanEndpoint == effectivePeerEndpoint && isCloudflareEndpoint(effectivePeerEndpoint)) ||
            (warpPeerPublicKey.isNotBlank() && warpPeerPublicKey == DEFAULT_CF_PEER_KEY)

        if (strategy == AwgObfuscationStrategy.CUSTOM && awgCustomIni.isNotBlank()) {
            val hasIncompatibleHeaders = awgCustomIni.lines().any { line ->
                val clean = line.substringBefore("#").substringBefore(";").trim().lowercase()
                (clean.startsWith("s1 =") && clean != "s1 = 0") ||
                (clean.startsWith("s2 =") && clean != "s2 = 0") ||
                (clean.startsWith("h1 =") && clean != "h1 = 1")
            }
            return if (isCf && hasIncompatibleHeaders) sanitizeAwgConfigForCloudflare(awgCustomIni).trim() else awgCustomIni.trim()
        }
        val peerKey = if (warpPeerPublicKey.isNotBlank()) warpPeerPublicKey else DEFAULT_CF_PEER_KEY
        val ipv4 = if (warpClientIpv4.isNotBlank()) warpClientIpv4 else "172.16.0.2"
        val hasValidIpv6 = warpClientIpv6.isNotBlank() && (isWarpAccountActive || warpPrivateKey.isNotBlank()) && warpClientIpv6 != "2606:4700:110:812c:a554:b442:26d4:1330"
        val addressStr = if (hasValidIpv6) "$ipv4/32, $warpClientIpv6/128" else "$ipv4/32"
        val allowedIps = if (hasValidIpv6) "0.0.0.0/0, ::/0" else "0.0.0.0/0"
        val privKey = if (warpPrivateKey.isNotBlank()) warpPrivateKey else {
            val (priv, _) = Curve25519.generateKeyPair()
            Base64.getEncoder().encodeToString(priv)
        }
        val dnsString = getEffectiveVpnDnsIpv4().joinToString(", ")
        val reservedBytes = formatClientIdToReserved(warpClientId).ifBlank { warpClientId }
        val reservedLine = if (reservedBytes.isNotBlank()) "Reserved = $reservedBytes\n" else ""
        val effectiveI1 = if (awgI1.isNotBlank()) awgI1 else if (!isCf && strategy.usesI1) DEFAULT_QUIC_I1 else ""
        val i1Line = if (effectiveI1.isNotBlank()) "I1 = ${effectiveI1.trim()}\n" else ""
        val i2Line = if (awgI2.isNotBlank()) "I2 = ${awgI2.trim()}\n" else ""
        val i3Line = if (awgI3.isNotBlank()) "I3 = ${awgI3.trim()}\n" else ""
        val i4Line = if (awgI4.isNotBlank()) "I4 = ${awgI4.trim()}\n" else ""
        val i5Line = if (awgI5.isNotBlank()) "I5 = ${awgI5.trim()}\n" else ""

        val effectiveJc = if (strategy == AwgObfuscationStrategy.CUSTOM && awgJc != 0) awgJc else if (isCf) 0 else strategy.defaultJc
        val effectiveJmin = if (strategy == AwgObfuscationStrategy.CUSTOM && (awgJmin != 0 || awgJc != 0)) awgJmin else if (isCf) 0 else strategy.defaultJmin
        val effectiveJmax = if (strategy == AwgObfuscationStrategy.CUSTOM && (awgJmax != 0 || awgJc != 0)) awgJmax else if (isCf) 0 else strategy.defaultJmax
        val effectiveS1 = if (isCf) 0 else awgS1
        val effectiveS2 = if (isCf) 0 else awgS2
        val effectiveH1 = if (isCf) 1L else awgH1
        val effectiveH2 = if (isCf) 2L else awgH2
        val effectiveH3 = if (isCf) 3L else awgH3
        val effectiveH4 = if (isCf) 4L else awgH4

        return """
            [Interface]
            PrivateKey = $privKey
            Address = $addressStr
            ${reservedLine}${i1Line}${i2Line}${i3Line}${i4Line}${i5Line}DNS = $dnsString
            MTU = 1280
            Jc = $effectiveJc
            Jmin = $effectiveJmin
            Jmax = $effectiveJmax
            S1 = $effectiveS1
            S2 = $effectiveS2
            H1 = $effectiveH1
            H2 = $effectiveH2
            H3 = $effectiveH3
            H4 = $effectiveH4
            [Peer]
            PublicKey = $peerKey
            AllowedIPs = $allowedIps
            Endpoint = $cleanEndpoint
        """.trimIndent()
    }

    fun getAmneziaWgConfig(
        cleanEndpoint: String,
        @Suppress("UNUSED_PARAMETER") sniCamouflage: String,
        strategy: AwgObfuscationStrategy = awgStrategy
    ): String = getAmneziaWgConfig(cleanEndpoint, strategy)

    val speedPreset: SpeedPreset
        get() = try { SpeedPreset.valueOf(speedPresetName) } catch (_: Exception) { SpeedPreset.AUTO }

    var uplinkMode: UplinkMode
        get() = try {
            UplinkMode.valueOf(uplinkModeName)
        } catch (_: Exception) {
            UplinkMode.WORKER
        }
        set(value) {
            uplinkModeName = value.name
        }

    var vpnUplinkMode: UplinkMode
        get() {
            val mode = try { UplinkMode.valueOf(vpnUplinkModeName) } catch (_: Exception) { UplinkMode.AWG }
            return if (mode == UplinkMode.WORKER) UplinkMode.AWG else mode
        }
        set(value) {
            vpnUplinkModeName = if (value == UplinkMode.WORKER) UplinkMode.AWG.name else value.name
        }

    val isVpnAwgUplink: Boolean
        get() = vpnUplinkMode == UplinkMode.AWG


    val isVpnAnyWarpUplink: Boolean
        get() = vpnUplinkMode == UplinkMode.AWG

    // Свойства аплинка для прокси: всегда false, так как эти туннели принадлежат исключительно Mirrly VPN

    val isAwgUplink: Boolean
        get() = false

    val isWorkerUplink: Boolean
        get() = true

    /** true для режимов прокси, требующих WARP (для прокси всегда false) */
    val isAnyWarpUplink: Boolean
        get() = false



    val effectivePeerEndpoint: String
        get() {
            val raw = when {
                warpUserEndpointOverride.isNotBlank() -> warpUserEndpointOverride
                warpMeasuredWgEndpoint.isNotBlank() -> warpMeasuredWgEndpoint
                warpApiEndpoint.isNotBlank() && !warpApiEndpoint.endsWith(":0") -> warpApiEndpoint
                warpPeerEndpoint.isNotBlank() -> warpPeerEndpoint
                warpApiEndpoint.isNotBlank() -> warpApiEndpoint
                else -> "162.159.192.1:4500"
            }
            val (ip, port) = parseEndpoint(raw)
            val safePort = if (port in 1..65535) port else 4500
            return "$ip:$safePort"
        }

    val isAutoSpeedPreset: Boolean
        get() = speedPreset == SpeedPreset.AUTO

    var nagleMode: NagleMode
        get() = when (tcpNoDelayModeName.uppercase()) {
            "DISABLED", "ON" -> NagleMode.DISABLED
            else -> NagleMode.ENABLED
        }
        set(value) {
            tcpNoDelayModeName = value.name
            tcpNoDelay = (value == NagleMode.DISABLED)
        }

    @Suppress("DEPRECATION")
    var tcpNoDelayMode: TcpNoDelayMode
        get() = when (nagleMode) {
            NagleMode.DISABLED -> TcpNoDelayMode.ON
            NagleMode.ENABLED -> TcpNoDelayMode.OFF
        }
        set(value) {
            nagleMode = when (value) {
                TcpNoDelayMode.ON -> NagleMode.DISABLED
                TcpNoDelayMode.OFF, TcpNoDelayMode.AUTO -> NagleMode.ENABLED
            }
        }

    /** Текущий режим прокси. Единый источник истины. */
    val proxyMode: ProxyMode
        get() = try { ProxyMode.valueOf(proxyModeName) } catch (_: Exception) { ProxyMode.MTPROTO }

    /** Короткий computed helper — true если включён режим SOCKS5. */
    val isSocks5Mode: Boolean
        get() = proxyMode == ProxyMode.SOCKS5

    /** True, если для SOCKS5 настроена аутентификация (логин или пароль). */
    val hasSocks5Auth: Boolean
        get() = socks5Username.isNotBlank() || socks5Password.isNotBlank()

    /** Порт, который сейчас активен (зависит от режима). */
    val activePort: Int
        get() = when (proxyMode) {
            ProxyMode.SOCKS5 -> socks5Port
            ProxyMode.MTPROTO -> bindPort
        }

    /**
     * Запрошенное количество сокетов ожидания (standby) на активный слот MTProto.
     * Диапазон запроса и native storage: 1..4.
     * На мобильной сети effective фиксируется в 1, а requested остаётся видимым в telemetry.
     */
    var mtprotoStandbyPerActiveSlot: Int
        get() = mtprotoStandbyPerActiveSlotValue.coerceIn(1, 4)
        set(value) { mtprotoStandbyPerActiveSlotValue = value.coerceIn(1, 4) }

    var poolSize: Int
        get() = mtprotoStandbyPerActiveSlot
        set(value) { mtprotoStandbyPerActiveSlot = value }

    /**
     * Вычисляет эффективное число сокетов ожидания MTProto на слот без скрытых clamp.
     * Мобильная сеть: ровно 1 сокет ожидания (снижение нагрузки на радиомодуль и батарею).
     * Wi-Fi: запрошенное значение, ограниченное аппаратным максимумом движка 1..4.
     */
    fun getEffectiveMtprotoStandby(isMobile: Boolean): Int {
        return if (isMobile) 1 else mtprotoStandbyPerActiveSlot.coerceIn(1, 4)
    }

    /**
     * Глобальный бюджет одновременных установок соединений (Dial Budget).
     * Мобильная сеть: 2 активных установления.
     * Wi-Fi: 4 активных установления.
     */
    fun getGlobalEstablishmentBudget(isMobile: Boolean): Int {
        return if (isMobile) 2 else 4
    }

    /**
     * Снимок состояния пула и параллельных потоков транспорта.
     */
    fun getTransportPoolStatus(
        isMobile: Boolean,
        isRunning: Boolean,
        activeConnections: Int = 0
    ): TransportPoolStatus {
        val transport = if (!isRunning) "idle" else when (proxyMode) {
            ProxyMode.SOCKS5 -> "socks5"
            ProxyMode.MTPROTO -> "mtproto"
        }
        val requested = mtprotoStandbyPerActiveSlot.coerceIn(1, 4)
        return TransportPoolStatus(
            transport = transport,
            isMobile = isMobile,
            mtprotoStandbyPerActiveSlotRequested = requested,
            mtprotoStandbyPerActiveSlotEffective = getEffectiveMtprotoStandby(isMobile),
            globalEstablishmentBudget = getGlobalEstablishmentBudget(isMobile),
            socksConcurrentFlows = if (isSocks5Mode) activeConnections.toLong() else 0L
        )
    }

    fun applyPreset(preset: SpeedPreset) {
        speedPresetName = preset.name
        if (preset != SpeedPreset.AUTO) {
            mtprotoStandbyPerActiveSlot = preset.defaultMtprotoStandbyPerActiveSlot
            bufferSizeBytes = preset.defaultBufferSizeBytes
        }
    }

    /** Применяет профиль Cloudflare WARP к текущей конфигурации. */
    fun applyWarpProfile(profile: WarpProfile, overrideDns: Boolean = true) {
        profile.requireUsable()
        warpAccountId = profile.accountId
        warpToken = profile.token
        if (profile.licenseKey.isNotBlank()) {
            warpLicenseKey = profile.licenseKey
        }
        warpClientIpv4 = profile.clientIpv4
        warpClientIpv6 = profile.clientIpv6
        warpPeerEndpoint = profile.effectivePeerEndpoint
        warpPeerPublicKey = profile.peerPublicKey
        warpPrivateKey = profile.privateKeyBase64
        warpPublicKey = profile.publicKeyBase64
        warpP256PrivateKey = profile.p256PrivateKeyBase64
        warpP256PublicKey = profile.p256PublicKeyBase64
        warpClientCert = profile.clientCertBase64
        isWarpPlus = profile.isWarpPlus
        isWarpAccountActive = profile.isWarpEnabled
        isWgValid = profile.isWgValid
        warpApiEndpoint = profile.apiEndpoint
        warpUserEndpointOverride = profile.userEndpointOverride ?: ""
        warpMeasuredWgEndpoint = profile.measuredWgEndpoint ?: ""
        if (profile.clientId.isNotBlank()) {
            warpClientId = profile.clientId
        }
        val isCf = isCloudflareWarpProfile(profile) ||
            isCloudflareEndpoint(profile.peerEndpoint) ||
            (!profile.userEndpointOverride.isNullOrBlank() && isCloudflareEndpoint(profile.userEndpointOverride))
        val hasIncompatibleHeaders = profile.awgS1 != 0 || profile.awgS2 != 0 || profile.awgS3 != 0 || profile.awgS4 != 0 ||
            (profile.awgH1 != 1L && profile.awgH1 != 0L) ||
            (profile.awgH2 != 2L && profile.awgH2 != 0L) ||
            (profile.awgH3 != 3L && profile.awgH3 != 0L) ||
            (profile.awgH4 != 4L && profile.awgH4 != 0L)

        if (isCf && hasIncompatibleHeaders) {
            awgJc = 0
            awgJmin = 0
            awgJmax = 0
            awgS1 = 0
            awgS2 = 0
            awgS3 = 0
            awgS4 = 0
            awgH1 = 1L
            awgH2 = 2L
            awgH3 = 3L
            awgH4 = 4L
            if (profile.awgI1.isNotBlank()) awgI1 = profile.awgI1
            if (profile.awgI2.isNotBlank()) awgI2 = profile.awgI2
            if (profile.awgI3.isNotBlank()) awgI3 = profile.awgI3
            if (profile.awgI4.isNotBlank()) awgI4 = profile.awgI4
            if (profile.awgI5.isNotBlank()) awgI5 = profile.awgI5
            if (profile.awgRawConfig.isNotBlank() && profile.awgRawConfig.contains("[Interface]", ignoreCase = true)) {
                awgCustomIni = sanitizeAwgConfigForCloudflare(profile.awgRawConfig)
            }
        } else if (isCf) {
            // Cloudflare с поддержкой AWG-обфускации (Jc, Jmin, Jmax, I1 при S1..S4=0, H1..H4=1..4)
            awgJc = profile.awgJc
            awgJmin = profile.awgJmin
            awgJmax = profile.awgJmax
            awgS1 = 0
            awgS2 = 0
            awgS3 = 0
            awgS4 = 0
            awgH1 = 1L
            awgH2 = 2L
            awgH3 = 3L
            awgH4 = 4L
            if (profile.awgI1.isNotBlank()) awgI1 = profile.awgI1
            if (profile.awgI2.isNotBlank()) awgI2 = profile.awgI2
            if (profile.awgI3.isNotBlank()) awgI3 = profile.awgI3
            if (profile.awgI4.isNotBlank()) awgI4 = profile.awgI4
            if (profile.awgI5.isNotBlank()) awgI5 = profile.awgI5
            if (profile.awgRawConfig.isNotBlank() && profile.awgRawConfig.contains("[Interface]", ignoreCase = true)) {
                awgCustomIni = profile.awgRawConfig.trim()
            }
        } else {
            awgJc = profile.awgJc
            awgJmin = profile.awgJmin
            awgJmax = profile.awgJmax
            awgS1 = profile.awgS1
            awgS2 = profile.awgS2
            awgS3 = profile.awgS3
            awgS4 = profile.awgS4
            awgH1 = profile.awgH1
            awgH2 = profile.awgH2
            awgH3 = profile.awgH3
            awgH4 = profile.awgH4
            if (profile.awgI1.isNotBlank()) awgI1 = profile.awgI1
            if (profile.awgI2.isNotBlank()) awgI2 = profile.awgI2
            if (profile.awgI3.isNotBlank()) awgI3 = profile.awgI3
            if (profile.awgI4.isNotBlank()) awgI4 = profile.awgI4
            if (profile.awgI5.isNotBlank()) awgI5 = profile.awgI5
            if (profile.awgRawConfig.isNotBlank() && profile.awgRawConfig.contains("[Interface]", ignoreCase = true)) {
                awgCustomIni = profile.awgRawConfig
                awgStrategyName = AwgObfuscationStrategy.CUSTOM.name
            }
        }
        if (overrideDns && profile.dnsServers.isNotBlank()) {
            vpnCustomDnsIps = profile.dnsServers
            vpnDnsModeName = VpnDnsMode.CUSTOM.name
        }
    }

    /** Возвращает эффективный домен Cloudflare Worker для режима SOCKS5:
     *  - Используется строго персональный воркер пользователя.
     *  - Дефолтные воркеры разработчика исключены (запуск без личного воркера запрещен).
     */
    fun getEffectiveCfDomain(): String {
        return sanitizeDomain(customCfDomain)
    }

    /**
     * Проверяет, требуется ли персональный воркер для запуска SOCKS5 (`socks5UplinkMode != 1`)
     * при отсутствии настроенного домена. В режиме Anycast CDN Flowseal (`socks5UplinkMode == 1`)
     * персональный воркер не требуется.
     */
    fun isSocks5WorkerMissing(fallbackWorkerDomain: String = ""): Boolean {
        if (!isSocks5Mode || socks5UplinkMode == SOCKS5_UPLINK_FLOWSEAL) {
            return false
        }
        return getEffectiveCfDomain().isBlank() && sanitizeDomain(fallbackWorkerDomain).isBlank()
    }

    val rawSecret32: String
        get() {
            var clean = secretHex.trim().lowercase()
            if (clean.startsWith("0x")) {
                clean = clean.substring(2)
            }
            if (clean.length >= 34 && (clean.startsWith("dd") || clean.startsWith("ee"))) {
                clean = clean.substring(2)
            }
            clean = clean.filter { it in '0'..'9' || it in 'a'..'f' }
            return if (clean.length >= 32) {
                clean.take(32)
            } else {
                clean.padStart(32, '0')
            }
        }

    val secretBytes: ByteArray
        get() = hexToBytes(rawSecret32)

    fun extractCustomFakeTlsDomainHex(): String? {
        val normalized = secretHex.trim().lowercase().removePrefix("0x")
        if (!normalized.startsWith("ee") || normalized.length <= 34) return null
        val secretPart = normalized.substring(2, 34)
        if (!secretPart.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val domainPart = normalized.substring(34)
        if (domainPart.isEmpty() || domainPart.length % 2 != 0) return null
        if (!domainPart.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return domainPart
    }

    fun getFakeTlsDomainHex(): String {
        return extractCustomFakeTlsDomainHex() ?: DEFAULT_FAKETLS_DOMAIN_HEX
    }

    fun getSecretWithPrefix(): String {
        val cleanSecret = rawSecret32
        return if (isTlsEnabled) {
            "ee$cleanSecret${getFakeTlsDomainHex()}"
        } else {
            "dd$cleanSecret"
        }
    }

    fun getNativeStartSecret(): String {
        val cleanSecret = rawSecret32
        val customDomainHex = extractCustomFakeTlsDomainHex()
        return if (customDomainHex != null) {
            "ee$cleanSecret$customDomainHex"
        } else {
            cleanSecret
        }
    }

    companion object {
        const val DEFAULT_FAKETLS_DOMAIN = "www.google.com"
        const val DEFAULT_FAKETLS_DOMAIN_HEX = "7777772e676f6f676c652e636f6d"
        const val SOCKS5_UPLINK_WORKER = 0
        const val SOCKS5_UPLINK_FLOWSEAL = 1
        const val DEFAULT_CF_PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
        const val DEFAULT_QUIC_I1 = "<b 0xc000000001><rc 8><rc 8><b 0x00><r 128>"



        val CLOUDFLARE_DNS_IPV4 = listOf("1.1.1.1", "1.0.0.1")
        const val CLOUDFLARE_DNS_IPV6 = "2606:4700:4700::1111"
        val COMSS_DNS_IPV4 = listOf("83.220.169.155", "195.133.25.16", "94.250.253.55", "212.109.195.93")
        val GOOGLE_DNS_IPV4 = listOf("8.8.8.8", "8.8.4.4")
        const val GOOGLE_DNS_IPV6 = "2001:4860:4860::8888"
        val QUAD9_DNS_IPV4 = listOf("9.9.9.9", "149.112.112.112")
        const val QUAD9_DNS_IPV6 = "2620:fe::fe"

        const val DEFAULT_TG_PORT = 443

        fun parseCustomDnsIps(input: String): List<String> {
            val ipv4Regex = Regex("""^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$""")
            return input.split(',', ' ', ';', '\n', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.matches(ipv4Regex) }
        }

        fun parseEndpoint(raw: String, defaultPort: Int = 500): Pair<String, Int> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "" to defaultPort
            return if (trimmed.startsWith("[")) {
                val closeBracket = trimmed.indexOf(']')
                if (closeBracket != -1) {
                    val ip = trimmed.substring(1, closeBracket)
                    val port = if (closeBracket + 1 < trimmed.length && trimmed[closeBracket + 1] == ':') {
                        trimmed.substring(closeBracket + 2).toIntOrNull() ?: defaultPort
                    } else {
                        defaultPort
                    }
                    ip to port
                } else {
                    trimmed to defaultPort
                }
            } else {
                val lastColon = trimmed.lastIndexOf(':')
                if (lastColon != -1 && trimmed.indexOf(':') == lastColon) {
                    val ip = trimmed.substring(0, lastColon)
                    val port = trimmed.substring(lastColon + 1).toIntOrNull() ?: defaultPort
                    ip to port
                } else {
                    trimmed to defaultPort
                }
            }
        }

        fun replaceDnsInAwgIni(rawIni: String, dnsIps: List<String>): String {
            val dnsLine = "DNS = " + dnsIps.joinToString(", ")
            return if (rawIni.contains(Regex("(?i)^\\s*DNS\\s*=.*", RegexOption.MULTILINE))) {
                rawIni.replace(Regex("(?i)^\\s*DNS\\s*=.*", RegexOption.MULTILINE), dnsLine)
            } else {
                rawIni.replace(Regex("(?i)\\[Interface\\]"), "[Interface]\n$dnsLine")
            }
        }

        fun sanitizeDomain(input: String): String {
            return WorkerDomainNormalizer.sanitizeDomain(input)
        }

        fun hexToBytes(hex: String): ByteArray {
            val cleanHex = hex.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.lowercase()
            val padded = if (cleanHex.length % 2 != 0) "0$cleanHex" else cleanHex
            val data = ByteArray(padded.length / 2)
            for (i in padded.indices step 2) {
                val high = Character.digit(padded[i], 16)
                val low = Character.digit(padded[i + 1], 16)
                data[i / 2] = ((high shl 4) or low).toByte()
            }
            return data
        }

        fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder()
            for (b in bytes) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        }

        fun generateRandomSecret(): String {
            val randomBytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(randomBytes)
            return "dd" + bytesToHex(randomBytes)
        }

        fun generateRandomSocks5Credentials(): Pair<String, String> {
            val randomBytes = ByteArray(8)
            java.security.SecureRandom().nextBytes(randomBytes)
            val hex = bytesToHex(randomBytes)
            val user = "mirrly_" + hex.take(6)
            val pass = hex.substring(6)
            return Pair(user, pass)
        }
    }
}

/**
 * Стратегии обфускации протокола AmneziaWG (AWG).
 */
enum class AwgObfuscationStrategy(
    val displayName: String,
    val defaultJc: Int,
    val defaultJmin: Int,
    val defaultJmax: Int,
    val usesI1: Boolean = false
) {
    FAST("Быстрая", defaultJc = 2, defaultJmin = 40, defaultJmax = 70, usesI1 = false),
    BALANCED("Сбалансированная", defaultJc = 4, defaultJmin = 40, defaultJmax = 80, usesI1 = false),
    DEEP_STEALTH("Скрытная", defaultJc = 5, defaultJmin = 64, defaultJmax = 128, usesI1 = true),
    CUSTOM("Свой профиль", defaultJc = 4, defaultJmin = 40, defaultJmax = 70, usesI1 = false);

    companion object {
        fun fromName(name: String?): AwgObfuscationStrategy {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BALANCED
        }
    }
}

/**
 * Детекция эндпоинтов Cloudflare Anycast (IPv4 162.159.*, 188.114.*, 172.64.*,
 * IPv6 2606:4700::/32, домены *.cloudflareclient.com, *.cloudflare.com, *.cloudflareaccess.com).
 */
fun isCloudflareEndpoint(endpoint: String?): Boolean {
    if (endpoint.isNullOrBlank()) return false
    val clean = endpoint.trim().lowercase()
    if (clean.startsWith("2606:4700:")) return true
    val host = when {
        clean.startsWith("[") -> clean.substringAfter("[").substringBefore("]")
        clean.count { it == ':' } == 1 -> clean.substringBefore(":")
        else -> clean.substringBefore("/")
    }
    return host.startsWith("162.159.") ||
        host.startsWith("188.114.") ||
        host.startsWith("8.34.") ||
        host.startsWith("2606:4700:") ||
        host.startsWith("172.64.") ||
        host == "1.1.1.1" ||
        host == "1.0.0.1" ||
        host.endsWith(".cloudflareclient.com") ||
        host == "cloudflareclient.com" ||
        host.endsWith(".cloudflare.com") ||
        host == "cloudflare.com" ||
        host.endsWith(".cloudflareaccess.com") ||
        host == "cloudflareaccess.com" ||
        host.endsWith(".tribukvy.ltd") ||
        host == "tribukvy.ltd"
}

/**
 * Проверка, принадлежит ли профиль инфраструктуре Cloudflare WARP.
 */
fun isCloudflareWarpProfile(profile: WarpProfile?): Boolean {
    if (profile == null) return false
    if (profile.isWarpPlus) return true
    if (profile.peerPublicKey.isNotBlank() && profile.peerPublicKey.trim() == ProxyConfig.DEFAULT_CF_PEER_KEY) return true
    if (isCloudflareEndpoint(profile.peerEndpoint)) return true
    if (!profile.userEndpointOverride.isNullOrBlank() && isCloudflareEndpoint(profile.userEndpointOverride)) return true
    if (isCloudflareEndpoint(profile.apiEndpoint)) return true
    val acc = profile.accountId.lowercase().trim()
    if (acc.startsWith("warp-") || acc.startsWith("custom-json-") || acc.contains("cloudflare")) return true
    return false
}

/**
 * Санитизация INI-конфигурации AmneziaWG для серверов Cloudflare Anycast:
 * - Принудительно устанавливает Jc = 0, Jmin = 0, Jmax = 0 (полное отключение мусорных UDP-пакетов)
 * - Сбрасывает S1..S4 в 0 и H1..H4 в 1..4 (стандартный RFC WireGuard)
 * - Удаляет неподдерживаемые сигнатуры I1..I5 и отключает DisableCookies
 */
fun sanitizeAwgConfigForCloudflare(rawIni: String): String {
    if (rawIni.isBlank()) return rawIni
    val lines = rawIni.lines()
    val result = mutableListOf<String>()
    var currentSection = ""

    for (rawLine in lines) {
        val lineWithoutComment = rawLine.substringBefore("#").substringBefore(";").trim()

        if (lineWithoutComment.startsWith("[") && lineWithoutComment.endsWith("]")) {
            currentSection = lineWithoutComment.substring(1, lineWithoutComment.length - 1).trim().lowercase()
            result.add(rawLine)
            continue
        }

        val eqIdx = lineWithoutComment.indexOf('=')
        if (eqIdx <= 0) {
            result.add(rawLine)
            continue
        }

        val key = lineWithoutComment.substring(0, eqIdx).trim().lowercase()

        when (currentSection) {
            "interface" -> {
                when (key) {
                    "jc" -> result.add("Jc = 0")
                    "jmin" -> result.add("Jmin = 0")
                    "jmax" -> result.add("Jmax = 0")
                    "s1" -> result.add("S1 = 0")
                    "s2" -> result.add("S2 = 0")
                    "s3" -> result.add("S3 = 0")
                    "s4" -> result.add("S4 = 0")
                    "h1" -> result.add("H1 = 1")
                    "h2" -> result.add("H2 = 2")
                    "h3" -> result.add("H3 = 3")
                    "h4" -> result.add("H4 = 4")
                    "i1", "i2", "i3", "i4", "i5" -> {}
                    "disablecookies" -> result.add("DisableCookies = off")
                    "randomtrailers" -> result.add("RandomTrailers = off")
                    else -> result.add(rawLine)
                }
            }
            "peer" -> {
                when (key) {
                    "s1" -> result.add("S1 = 0")
                    "s2" -> result.add("S2 = 0")
                    "s3" -> result.add("S3 = 0")
                    "s4" -> result.add("S4 = 0")
                    "h1" -> result.add("H1 = 1")
                    "h2" -> result.add("H2 = 2")
                    "h3" -> result.add("H3 = 3")
                    "h4" -> result.add("H4 = 4")
                    else -> result.add(rawLine)
                }
            }
            else -> result.add(rawLine)
        }
    }

    return result.joinToString("\n").trim()
}
