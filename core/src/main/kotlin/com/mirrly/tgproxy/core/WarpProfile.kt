/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.core

enum class WarpEntitlement {
    FREE,
    PLUS,
    TEAM,
    UNVERIFIED;

    companion object {
        fun fromType(type: String?, isPlus: Boolean): WarpEntitlement {
            val t = type?.lowercase()?.trim() ?: ""
            return when {
                t.contains("team") -> TEAM
                t.contains("plus") || t.contains("unlimited") || isPlus -> PLUS
                t.contains("free") -> FREE
                else -> if (isPlus) PLUS else FREE
            }
        }
    }
}

fun formatClientIdToReserved(clientId: String): String {
    val clean = clientId.trim().replace("0x", "").replace(" ", "")
    if (clean.length == 6) {
        val b1 = clean.substring(0, 2).toIntOrNull(16) ?: return ""
        val b2 = clean.substring(2, 4).toIntOrNull(16) ?: return ""
        val b3 = clean.substring(4, 6).toIntOrNull(16) ?: return ""
        return "$b1, $b2, $b3"
    }
    return ""
}

fun maskLicenseKey(key: String?): String {
    if (key.isNullOrBlank()) return ""
    val trimmed = key.trim()
    return if (trimmed.length > 8) {
        val prefix = trimmed.take(4)
        val suffix = trimmed.takeLast(4)
        "$prefix-****-$suffix"
    } else {
        "****"
    }
}

data class WarpProfile(
    val accountId: String,
    val token: String,
    val licenseKey: String = "",
    val clientIpv4: String,
    val clientIpv6: String = "",
    val peerEndpoint: String = "",
    val peerPublicKey: String,
    val privateKeyBase64: String,
    val publicKeyBase64: String,
    val p256PrivateKeyBase64: String = "",
    val p256PublicKeyBase64: String = "",
    val clientCertBase64: String = "",
    val isWarpPlus: Boolean = false,
    val isWarpEnabled: Boolean = true,
    val registrationDate: Long = System.currentTimeMillis(),
    val isRegistered: Boolean = false,
    val isActivated: Boolean = false,
    val credentialsValid: Boolean = false,
    val dataPlaneReady: Boolean = false,
    val entitlement: WarpEntitlement = WarpEntitlement.FREE,
    val needsVerification: Boolean = false,
    val isOfflineCached: Boolean = false,
    val isWgValid: Boolean = false,
    val isBundledSeed: Boolean = false,
    val apiEndpoint: String = "",
    val userEndpointOverride: String? = null,
    val measuredWgEndpoint: String? = null,
    val dnsServers: String = "",
    val clientId: String = "",
    val awgI1: String = "",
    val awgI2: String = "",
    val awgI3: String = "",
    val awgI4: String = "",
    val awgI5: String = "",
    val awgJc: Int = 0,
    val awgJmin: Int = 0,
    val awgJmax: Int = 0,
    val awgS1: Int = 0,
    val awgS2: Int = 0,
    val awgS3: Int = 0,
    val awgS4: Int = 0,
    val awgH1: Long = 1L,
    val awgH2: Long = 2L,
    val awgH3: Long = 3L,
    val awgH4: Long = 4L,
    val awgRawConfig: String = ""
) {
    fun isBootstrap(): Boolean =
        !isBundledSeed && (
            accountId.isBlank() ||
            token.isBlank() ||
            accountId == "mirrly-warp-bootstrap-id" ||
            token == "mirrly-bootstrap-token" ||
            privateKeyBase64 == "7NMyJemtPW8/EKQsBxAHjfhsPVOiNZfpG0VERGergeY="
        )

    fun isUsable(): Boolean {
        val notBootstrap = (accountId.isNotBlank() &&
            token.isNotBlank() &&
            accountId != "mirrly-warp-bootstrap-id" &&
            token != "mirrly-bootstrap-token") || isBundledSeed
        val hasKeys = privateKeyBase64.isNotBlank() && publicKeyBase64.isNotBlank()
        val hasNetwork = clientIpv4.isNotBlank() && effectivePeerEndpoint.isNotBlank()
        return (notBootstrap || isOfflineCached || isBundledSeed) && hasKeys && hasNetwork
    }

    fun requireUsable(): WarpProfile {
        require(isUsable()) {
            "WarpProfile is unusable: accountId=$accountId, isRegistered=$isRegistered, isOfflineCached=$isOfflineCached, isBundledSeed=$isBundledSeed"
        }
        return this
    }

    val reserved: String
        get() = formatClientIdToReserved(clientId).ifBlank { clientId }

    val effectivePeerEndpoint: String
        get() {
            if (!userEndpointOverride.isNullOrBlank()) {
                val (_, uPort) = ProxyConfig.parseEndpoint(userEndpointOverride, 1701)
                if (uPort > 0 && uPort != 2408) return userEndpointOverride
            }
            val candidate = when {
                !measuredWgEndpoint.isNullOrBlank() -> measuredWgEndpoint
                !apiEndpoint.isNullOrBlank() -> apiEndpoint
                peerEndpoint.isNotBlank() -> peerEndpoint
                else -> "162.159.193.10:1701"
            }
            val (ip, port) = ProxyConfig.parseEndpoint(candidate, 1701)
            val safePort = if (port <= 0 || port == 2408 || port == 443 || port == 8095 || port == 8443 || port !in listOf(500, 1701, 4500)) 1701 else port
            return "$ip:$safePort"
        }

    fun hasValidWgIdentity(): Boolean {
        return isWgValid && privateKeyBase64.isNotBlank() && publicKeyBase64.isNotBlank() &&
            peerPublicKey.isNotBlank() && clientIpv4.isNotBlank() && accountId.isNotBlank()
    }

    fun getSummary(): String {
        val plusTag = when (entitlement) {
            WarpEntitlement.PLUS -> "WARP+"
            WarpEntitlement.TEAM -> "WARP Teams"
            WarpEntitlement.UNVERIFIED -> "Unverified"
            WarpEntitlement.FREE -> "Free"
        }
        val statusTag = if (isActivated && dataPlaneReady) "Готов" else if (isOfflineCached) "Офлайн-кеш" else if (isWarpEnabled) "Активен" else "Не активирован"
        return "IP: $clientIpv4 | $plusTag ($statusTag)"
    }

    fun toAmneziaWgConfig(
        cleanEndpoint: String = "162.159.193.10:1701"
    ): String {
        val peerKey = if (peerPublicKey.isNotBlank()) peerPublicKey else "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
        val ipv4 = if (clientIpv4.isNotBlank()) clientIpv4 else "172.16.0.2"
        val addressStr = if (clientIpv6.isNotBlank()) "$ipv4/32, $clientIpv6/128" else "$ipv4/32"
        val reservedVal = if (reserved.isNotBlank()) reserved else clientId
        val reservedPart = if (reservedVal.isNotBlank()) "Reserved = $reservedVal\n" else ""
        val i1Part = if (awgI1.isNotBlank()) "I1 = $awgI1\n" else ""
        val i2Part = if (awgI2.isNotBlank()) "I2 = $awgI2\n" else ""
        val jc = if (awgJc > 0) awgJc else 4
        val jmin = if (awgJmin > 0) awgJmin else 40
        val jmax = if (awgJmax > 0) awgJmax else 70
        val h1 = if (awgH1 != 0L) awgH1 else 1L
        val h2 = if (awgH2 != 0L) awgH2 else 2L
        val h3 = if (awgH3 != 0L) awgH3 else 3L
        val h4 = if (awgH4 != 0L) awgH4 else 4L
        return """
            [Interface]
            PrivateKey = $privateKeyBase64
            Address = $addressStr
            DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001
            MTU = 1280
            $reservedPart$i1Part${i2Part}Jc = $jc
            Jmin = $jmin
            Jmax = $jmax
            S1 = $awgS1
            S2 = $awgS2
            H1 = $h1
            H2 = $h2
            H3 = $h3
            H4 = $h4
            [Peer]
            PublicKey = $peerKey
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = $cleanEndpoint
        """.trimIndent()
    }
}

data class WarpAccountInfo(
    val licenseKey: String,
    val isWarpPlus: Boolean,
    val accountType: String,
    val referralCount: Int = 0,
    val entitlement: WarpEntitlement = if (isWarpPlus) WarpEntitlement.PLUS else WarpEntitlement.FREE
)

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

