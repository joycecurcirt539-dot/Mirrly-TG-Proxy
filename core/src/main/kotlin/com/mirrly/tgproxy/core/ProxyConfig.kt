package com.mirrly.tgproxy.core

enum class SpeedPreset(
    val displayName: String,
    val defaultMtprotoStandbyPerActiveSlot: Int,
    val defaultBufferSizeBytes: Int
) {
    ECO("Эко (1 резерв/слот)", 1, 131072),
    BALANCED("Баланс (2 резерва/слот)", 2, 262144),
    TURBO("Турбо (3 резерва/слот)", 3, 1048576),
    ULTRA("Ультра (4 резерва/слот)", 4, 2097152),
    AUTO("Авто (динамический)", 2, 262144)
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
 * Режим управления оптимизацией сокетов (TCP_NODELAY).
 * AUTO — адаптивное включение при высокой скорости (>= 50 КБ/с) и низком пинге (<= 140 мс).
 * ON   — принудительно включен (максимальный отклик в ущерб энергопотреблению).
 * OFF  — выключен (стандартная буферизация Нагла).
 */
enum class TcpNoDelayMode(val displayName: String) {
    AUTO("Авто"),
    ON("ВКЛ"),
    OFF("ВЫКЛ")
}

/**
 * Режимы восходящего канала (Uplink).
 * WORKER  — стандартная маршрутизация через Cloudflare Workers по протоколу WebSocket (TLS 443).
 * MASQUE  — прямое туннелирование через Cloudflare WARP MASQUE (HTTP/3 CONNECT-UDP / QUIC Datagrams на порт 443).
 * HYBRID  — гибридный отказоустойчивый режим (основной канал через Worker с быстрым переключением на WARP MASQUE при ошибках/кодах 429).
 */
enum class UplinkMode(val displayName: String, val subtitle: String) {
    WORKER("Cloudflare Worker (WSS)", "Классический режим туннелирования. Надежный WebSocket-транспорт через Cloudflare Workers"),
    MASQUE("WARP MASQUE (HTTP/3)", "Прямой Anycast HTTP/3 через QUIC без прокси-воркеров и AmneziaWG"),
    HYBRID("Гибридный каскад (Worker + WARP)", "Основной Worker WSS с мгновенным подхватом через WARP при блокировках"),
    VLESS("VLESS over WebSocket (CDN)", "VLESS через WSS TLS 1.3 на порт 443. 100% маскировка под обычный HTTPS"),
    AWG("WARP AmneziaWG (AWG)", "Замаскированный протокол WireGuard Anycast с защитой от блокировок через QUIC"),
    WARP_CASCADE("WARP Cascade (MASQUE + AWG)", "Умный WARP: MASQUE с автоматическим failover на AWG и аварийным Worker WSS")
}

data class ProxyConfig(
    var bindHost: String = "127.0.0.1",
    var bindPort: Int = 1443,
    var secretHex: String = "dd00000000000000000000000000000000",
    var cfProxyEnabled: Boolean = true,
    var customCfDomain: String = "",
    var mtprotoStandbyPerActiveSlotValue: Int = 2,
    var isDcAuto: Boolean = true,
    var autostartOnBoot: Boolean = false,
    var verboseLogs: Boolean = true,
    var isTestEnvironment: Boolean = false,
    var speedPresetName: String = SpeedPreset.AUTO.name,
    var tcpNoDelayModeName: String = TcpNoDelayMode.AUTO.name,
    var tcpNoDelay: Boolean = true,
    var bufferSizeBytes: Int = 262144, // 256KB default buffer
    var socks5Port: Int = 10808,
    var socks5Username: String = "",
    var socks5Password: String = "",
    var useDefaultWorkerSocks5: Boolean = true,
    var isBatteryGuardEnabled: Boolean = false,
    var batteryGuardThreshold: Int = 15,
    var batteryGuardStopOnPowerSave: Boolean = true,
    var isAdaptiveQoSEnabled: Boolean = true,
    // proxyModeName — единый источник истины (MTPROTO или SOCKS5)
    var proxyModeName: String = ProxyMode.MTPROTO.name,
    // Активные провайдеры DoH (DNS-over-HTTPS)
    var enabledDohProviderIds: Set<String> = DohResolver.DEFAULT_ENABLED_PROVIDER_IDS,
    // Режим аплинка: WORKER, MASQUE или HYBRID
    var uplinkModeName: String = UplinkMode.WORKER.name,
    // Параметры WARP MASQUE
    var warpAccountId: String = "",
    var warpToken: String = "",
    var warpLicenseKey: String = "",
    var warpClientIpv4: String = "172.16.0.2",
    var warpClientIpv6: String = "",
    var warpPeerEndpoint: String = "188.114.96.1:8095",
    var warpUriTemplate: String = "/.well-known/masque/ip/",
    var warpPeerPublicKey: String = "",
    var warpPrivateKey: String = "",
    var warpPublicKey: String = "",
    var warpP256PrivateKey: String = "",
    var warpP256PublicKey: String = "",
    var warpClientCert: String = "",
    var isWarpPlus: Boolean = false,
    var isWarpAccountActive: Boolean = false,
    var warpMasqueAccountId: String = "",
    var warpMasqueToken: String = "",
    var warpMasquePeerEndpoint: String = "",
    var warpMasquePeerPublicKey: String = "",
    var warpMasqueClientIpv4: String = "",
    var warpMasqueClientIpv6: String = "",
    var isWgValid: Boolean = false,
    var isMasqueValid: Boolean = false,
    var warpApiEndpoint: String = "",
    var warpUserEndpointOverride: String = "",
    var warpMasqueApiEndpoint: String = "",
    var warpMasqueUserOverride: String = "",
    var warpMeasuredWgEndpoint: String = "",
    var warpMeasuredMasqueEndpoint: String = "",
    // Параметры VLESS over WebSocket & Reality
    var vlessUuid: String = "d342d11e-d424-4583-b36e-524ab1f0afa4",
    var vlessPath: String = "/vless-ws?ed=2048",
    var vlessDomain: String = "",
    var vlessPresetId: String = "cf_pages_global",
    var vlessSecurity: String = "tls",
    var vlessPublicKey: String = "",
    var vlessShortId: String = "",
    var vlessFingerprint: String = "chrome",
    var vlessSpiderX: String = "",
    var vlessTransport: String = "ws",
    var vlessFlow: String = "",
    var vlessHeaderType: String = "",
    var vlessServerAddress: String = "",
    var vlessServerPort: Int = 443,
    var vlessTlsSni: String = "",
    var vlessHostHeader: String = "",
    // Настройки Opera VPN как прокси для VLESS и WARP
    var useOperaVpnForVless: Boolean = false,
    var useOperaVpnForWarp: Boolean = false,
    var operaVpnEndpoint: String = "77.111.247.139:443",
    var operaVpnNodeId: String = "opera_eu_central",
    // Выделенный домен Cloudflare Worker для WARP
    var warpWorkerDomain: String = "",
    // Настройки WARP Cascade (UPLINK_WARP_CASCADE): автоматический каскадный failover
    // true — разрешает автоматический откат MASQUE -> AWG -> аварийный WSS
    var awgCascadeFallbackEnabled: Boolean = true,
    // Стратегия обфускации AmneziaWG (FAST, BALANCED, DEEP_STEALTH, CUSTOM)
    var awgStrategyName: String = AwgObfuscationStrategy.BALANCED.name,
    // Кастомная INI-конфигурация AWG (если задана, используется вместо встроенного WARP-профиля)
    var awgCustomIni: String = "",
    // Явная политика доверия для fallback (O02)
    var allowPublicRelayFallbackForPrivateVps: Boolean = false,
    var allowOperaDirectExit: Boolean = false,
    var isLivenessProbeEnabled: Boolean = false,
    var livenessProbeTimeoutMs: Int = 1500,
    var livenessProbeFailoverThreshold: Int = 2
) {
    val awgStrategy: AwgObfuscationStrategy
        get() = AwgObfuscationStrategy.fromName(awgStrategyName)

    val isDedicatedVps: Boolean
        get() = awgStrategy == AwgObfuscationStrategy.CUSTOM && awgCustomIni.isNotBlank()

    /**
     * Генерирует валидную конфигурацию AmneziaWG (AWG) на основе профиля WARP
     * в соответствии с выбранной стратегией обфускации.
     */
    fun getAmneziaWgConfig(
        cleanEndpoint: String = warpPeerEndpoint.ifBlank { "188.114.96.1:8095" },
        strategy: AwgObfuscationStrategy = awgStrategy
    ): String {
        if (strategy == AwgObfuscationStrategy.CUSTOM && awgCustomIni.isNotBlank()) {
            return awgCustomIni.trim()
        }
        val peerKey = if (warpPeerPublicKey.isNotBlank()) warpPeerPublicKey else "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
        val ipv4 = if (warpClientIpv4.isNotBlank()) warpClientIpv4 else "172.16.0.2"
        val addressStr = if (warpClientIpv6.isNotBlank()) "$ipv4/32, $warpClientIpv6/128" else "$ipv4/32"
        val privKey = if (warpPrivateKey.isNotBlank()) warpPrivateKey else WarpAccountManager.BOOTSTRAP_PROFILE.privateKeyBase64
        return """
            [Interface]
            PrivateKey = $privKey
            Address = $addressStr
            DNS = 1.1.1.1, 1.0.0.1
            MTU = 1280
            Jc = ${strategy.defaultJc}
            Jmin = ${strategy.defaultJmin}
            Jmax = ${strategy.defaultJmax}
            S1 = 0
            S2 = 0
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4
            [Peer]
            PublicKey = $peerKey
            AllowedIPs = 0.0.0.0/0, ::/0
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

    val uplinkMode: UplinkMode
        get() = try { UplinkMode.valueOf(uplinkModeName) } catch (_: Exception) { UplinkMode.WORKER }

    val isMasqueUplink: Boolean
        get() = uplinkMode == UplinkMode.MASQUE || uplinkMode == UplinkMode.WARP_CASCADE

    val isHybridUplink: Boolean
        get() = uplinkMode == UplinkMode.HYBRID

    val isVlessUplink: Boolean
        get() = uplinkMode == UplinkMode.VLESS

    val isAwgUplink: Boolean
        get() = uplinkMode == UplinkMode.AWG

    val isWarpCascadeUplink: Boolean
        get() = uplinkMode == UplinkMode.WARP_CASCADE

    /** true для всех режимов, требующих наличия WARP-аккаунта и крипто-конфигурации */
    val isAnyWarpUplink: Boolean
        get() = uplinkMode == UplinkMode.MASQUE
            || uplinkMode == UplinkMode.HYBRID
            || uplinkMode == UplinkMode.AWG
            || uplinkMode == UplinkMode.WARP_CASCADE

    val effectiveMasqueToken: String
        get() = warpMasqueToken.ifBlank { warpToken }

    val effectiveMasqueAccountId: String
        get() = warpMasqueAccountId.ifBlank { warpAccountId }

    val effectivePeerEndpoint: String
        get() = when {
            warpUserEndpointOverride.isNotBlank() -> warpUserEndpointOverride
            warpMeasuredWgEndpoint.isNotBlank() -> warpMeasuredWgEndpoint
            warpApiEndpoint.isNotBlank() -> warpApiEndpoint
            warpPeerEndpoint.isNotBlank() -> warpPeerEndpoint
            else -> "188.114.96.1:8095"
        }

    val effectiveMasquePeerEndpoint: String
        get() = when {
            warpMasqueUserOverride.isNotBlank() -> warpMasqueUserOverride
            warpMeasuredMasqueEndpoint.isNotBlank() -> warpMeasuredMasqueEndpoint
            warpMasqueApiEndpoint.isNotBlank() -> warpMasqueApiEndpoint
            warpMasquePeerEndpoint.isNotBlank() -> warpMasquePeerEndpoint
            warpUserEndpointOverride.isNotBlank() -> warpUserEndpointOverride
            warpApiEndpoint.isNotBlank() -> warpApiEndpoint
            else -> warpPeerEndpoint
        }

    val effectiveMasquePeerPublicKey: String
        get() = warpMasquePeerPublicKey.ifBlank { warpPeerPublicKey }

    val effectiveMasqueClientIpv4: String
        get() = warpMasqueClientIpv4.ifBlank { warpClientIpv4 }

    val effectiveMasqueClientIpv6: String
        get() = warpMasqueClientIpv6.ifBlank { warpClientIpv6 }

    val isVlessReality: Boolean
        get() = vlessSecurity.equals("reality", ignoreCase = true) || vlessPublicKey.isNotBlank()

    val isVlessTcpDirect: Boolean
        get() = vlessTransport.equals("tcp", ignoreCase = true)

    val isVlessVision: Boolean
        get() = vlessFlow.contains("vision", ignoreCase = true)

    val isPrivateVpsNode: Boolean
        get() {
            if (!isVlessUplink && !isHybridUplink) return false
            if (isVlessReality) return true
            val ep = getEffectiveVlessServerAddress().trim()
            if (ep.isEmpty()) return false
            return !DohResolver.isCloudflareTargetDomain(ep)
        }

    fun getEffectiveVlessDomain(): String {
        return vlessDomain.trim().ifEmpty {
            if (customCfDomain.isNotBlank()) customCfDomain.trim()
            else VlessPresetsRepository.getDefaultPreset().domain
        }
    }

    fun getEffectiveVlessServerAddress(): String {
        return vlessServerAddress.trim().ifEmpty { getEffectiveVlessDomain() }
    }

    fun getEffectiveVlessServerPort(): Int {
        return if (vlessServerPort > 0) vlessServerPort else 443
    }

    fun getEffectiveVlessSni(): String {
        return vlessTlsSni.trim().ifEmpty { getEffectiveVlessDomain() }
    }

    fun getEffectiveVlessHost(): String {
        return vlessHostHeader.trim().ifEmpty { getEffectiveVlessSni() }
    }

    /**
     * Возвращает готовую ссылку VLESS для импорта в сторонние клиенты (Xray, v2rayNG, Sing-box, Nekobox).
     */
    fun getVlessShareUrl(workerDomain: String = ""): String {
        val serverAddr = if (workerDomain.isNotBlank()) workerDomain.trim() else getEffectiveVlessServerAddress()
        val serverPort = getEffectiveVlessServerPort()
        val sni = getEffectiveVlessSni()
        val host = getEffectiveVlessHost()

        if (isVlessReality) {
            val sb = StringBuilder("vless://$vlessUuid@$serverAddr:$serverPort?encryption=none&security=reality&sni=$sni")
            if (vlessPublicKey.isNotBlank()) {
                sb.append("&pbk=").append(java.net.URLEncoder.encode(vlessPublicKey, "UTF-8"))
            }
            if (vlessShortId.isNotBlank()) {
                sb.append("&sid=").append(java.net.URLEncoder.encode(vlessShortId, "UTF-8"))
            }
            if (vlessFingerprint.isNotBlank()) {
                sb.append("&fp=").append(java.net.URLEncoder.encode(vlessFingerprint, "UTF-8"))
            }
            if (vlessSpiderX.isNotBlank()) {
                sb.append("&spx=").append(java.net.URLEncoder.encode(vlessSpiderX, "UTF-8"))
            }
            if (vlessFlow.isNotBlank()) {
                sb.append("&flow=").append(java.net.URLEncoder.encode(vlessFlow, "UTF-8"))
            }
            if (vlessHeaderType.isNotBlank()) {
                sb.append("&headerType=").append(java.net.URLEncoder.encode(vlessHeaderType, "UTF-8"))
            }
            sb.append("&type=").append(if (vlessTransport.isNotBlank()) vlessTransport else "tcp")
            sb.append("#Mirrly-TG-Proxy")
            return sb.toString()
        }

        if (isVlessTcpDirect) {
            val sb = StringBuilder("vless://$vlessUuid@$serverAddr:$serverPort?encryption=none&security=$vlessSecurity&sni=$sni&type=tcp")
            if (vlessFlow.isNotBlank()) {
                sb.append("&flow=").append(java.net.URLEncoder.encode(vlessFlow, "UTF-8"))
            }
            if (vlessHeaderType.isNotBlank()) {
                sb.append("&headerType=").append(java.net.URLEncoder.encode(vlessHeaderType, "UTF-8"))
            }
            sb.append("#Mirrly-TG-Proxy")
            return sb.toString()
        }

        val cleanPath = if (vlessPath.startsWith("/")) vlessPath else "/$vlessPath"
        val encodedPath = java.net.URLEncoder.encode(cleanPath, "UTF-8")
        return "vless://$vlessUuid@$serverAddr:$serverPort?encryption=none&security=tls&sni=$sni&type=ws&host=$host&path=$encodedPath#Mirrly-TG-Proxy"
    }

    val isAutoSpeedPreset: Boolean
        get() = speedPreset == SpeedPreset.AUTO

    var tcpNoDelayMode: TcpNoDelayMode
        get() = try { TcpNoDelayMode.valueOf(tcpNoDelayModeName) } catch (_: Exception) { TcpNoDelayMode.AUTO }
        set(value) { tcpNoDelayModeName = value.name }

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
        get() = if (isSocks5Mode) socks5Port else bindPort

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
        val transport = if (!isRunning) "idle" else if (isSocks5Mode) "socks5" else "mtproto"
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

    /** Применяет профиль Cloudflare WARP MASQUE к текущей конфигурации. */
    fun applyWarpProfile(profile: WarpProfile) {
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
        warpMasqueAccountId = profile.masqueAccountId
        warpMasqueToken = profile.masqueToken
        warpMasquePeerEndpoint = profile.effectiveMasquePeerEndpoint
        warpMasquePeerPublicKey = profile.masquePeerPublicKey
        warpMasqueClientIpv4 = profile.masqueClientIpv4
        warpMasqueClientIpv6 = profile.masqueClientIpv6
        isWarpPlus = profile.isWarpPlus
        isWarpAccountActive = profile.isWarpEnabled
        isWgValid = profile.isWgValid
        isMasqueValid = profile.isMasqueValid
        warpApiEndpoint = profile.apiEndpoint
        warpUserEndpointOverride = profile.userEndpointOverride ?: ""
        warpMasqueApiEndpoint = profile.masqueApiEndpoint
        warpMasqueUserOverride = profile.masqueUserOverride ?: ""
        warpMeasuredWgEndpoint = profile.measuredWgEndpoint ?: ""
        warpMeasuredMasqueEndpoint = profile.measuredMasqueEndpoint ?: ""
    }

    /** Возвращает эффективный домен Cloudflare Worker для режима SOCKS5:
     *  - Используется пользовательский воркер (100% приоритет) или дефолтный узел разработчика.
     */
    fun getEffectiveCfDomain(): String {
        val userDomain = sanitizeDomain(customCfDomain)
        if (userDomain.isNotEmpty()) return userDomain
        return TgConstants.DEFAULT_SOCKS5_DEV_WORKER
    }

    /** Возвращает эффективный домен Cloudflare Worker для WARP. */
    fun getEffectiveWarpWorkerDomain(): String {
        val domain = sanitizeDomain(warpWorkerDomain)
        if (domain.isNotEmpty()) return domain
        return getEffectiveCfDomain()
    }

    /** Возвращает эффективный узел Opera VPN. */
    fun getEffectiveOperaEndpoint(): String {
        return operaVpnEndpoint.trim().ifEmpty { "77.111.247.139:443" }
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

    companion object {
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

        fun generateVlessUuid(): String = java.util.UUID.randomUUID().toString()
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
    DEEP_STEALTH("Скрытная", defaultJc = 5, defaultJmin = 64, defaultJmax = 128, usesI1 = false),
    CUSTOM("Свой профиль", defaultJc = 4, defaultJmin = 40, defaultJmax = 70, usesI1 = false);

    companion object {
        fun fromName(name: String?): AwgObfuscationStrategy {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BALANCED
        }
    }
}
