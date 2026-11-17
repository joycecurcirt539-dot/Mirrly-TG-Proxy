package com.mirrly.tgproxy.core

import org.json.JSONObject

/** One versioned snapshot prevents mixing keys and device IDs from different registrations. */
object WarpProfileCodec {
    fun encode(p: WarpProfile): String {
        p.requireUsable()
        return JSONObject().apply {
            put("version", 1)
            put("accountId", p.accountId); put("token", p.token); put("licenseKey", p.licenseKey)
            put("clientIpv4", p.clientIpv4); put("clientIpv6", p.clientIpv6)
            put("peerEndpoint", p.peerEndpoint); put("peerPublicKey", p.peerPublicKey)
            put("privateKey", p.privateKeyBase64); put("publicKey", p.publicKeyBase64)
            put("p256PrivateKey", p.p256PrivateKeyBase64); put("p256PublicKey", p.p256PublicKeyBase64)
            put("clientCert", p.clientCertBase64)
            put("isWarpPlus", p.isWarpPlus); put("isWarpEnabled", p.isWarpEnabled)
            put("registrationDate", p.registrationDate)
            put("isRegistered", p.isRegistered)
            put("isActivated", p.isActivated)
            put("credentialsValid", p.credentialsValid)
            put("dataPlaneReady", p.dataPlaneReady)
            put("entitlement", p.entitlement.name)
            put("needsVerification", p.needsVerification)
            put("isOfflineCached", p.isOfflineCached)
            put("isWgValid", p.isWgValid)
            put("apiEndpoint", p.apiEndpoint)
            p.userEndpointOverride?.let { put("userEndpointOverride", it) }
            p.measuredWgEndpoint?.let { put("measuredWgEndpoint", it) }
            if (p.dnsServers.isNotBlank()) { put("dnsServers", p.dnsServers) }
            if (p.clientId.isNotBlank()) { put("clientId", p.clientId) }
            if (p.awgI1.isNotBlank()) { put("awgI1", p.awgI1) }
            if (p.awgI2.isNotBlank()) { put("awgI2", p.awgI2) }
            if (p.awgI3.isNotBlank()) { put("awgI3", p.awgI3) }
            if (p.awgI4.isNotBlank()) { put("awgI4", p.awgI4) }
            if (p.awgI5.isNotBlank()) { put("awgI5", p.awgI5) }
            if (p.awgJc != 0) { put("awgJc", p.awgJc) }
            if (p.awgJmin != 0) { put("awgJmin", p.awgJmin) }
            if (p.awgJmax != 0) { put("awgJmax", p.awgJmax) }
            if (p.awgS1 != 0) { put("awgS1", p.awgS1) }
            if (p.awgS2 != 0) { put("awgS2", p.awgS2) }
            if (p.awgS3 != 0) { put("awgS3", p.awgS3) }
            if (p.awgS4 != 0) { put("awgS4", p.awgS4) }
            if (p.awgH1 != 1L) { put("awgH1", p.awgH1) }
            if (p.awgH2 != 2L) { put("awgH2", p.awgH2) }
            if (p.awgH3 != 3L) { put("awgH3", p.awgH3) }
            if (p.awgH4 != 4L) { put("awgH4", p.awgH4) }
            if (p.awgRawConfig.isNotBlank()) { put("awgRawConfig", p.awgRawConfig) }
        }.toString()
    }

    fun decode(text: String): WarpProfile? = try {
        require(text.length <= 64 * 1024)
        val j = JSONObject(text)
        require(j.getInt("version") == 1)
        val isPlus = j.optBoolean("isWarpPlus", false)
        val entName = j.optString("entitlement", "")
        val entitlement = try {
            if (entName.isNotBlank()) WarpEntitlement.valueOf(entName) else if (isPlus) WarpEntitlement.PLUS else WarpEntitlement.FREE
        } catch (_: Exception) {
            if (isPlus) WarpEntitlement.PLUS else WarpEntitlement.FREE
        }
        val isReg = j.optBoolean("isRegistered", true)
        val isAct = j.optBoolean("isActivated", j.getBoolean("isWarpEnabled"))
        val credsValid = j.optBoolean("credentialsValid", isReg)
        val isOffline = j.optBoolean("isOfflineCached", false)
        val needsVer = j.optBoolean("needsVerification", false)
        val dpReady = j.optBoolean("dataPlaneReady", isReg && isAct && !isOffline && !needsVer)
        val isWgValid = j.optBoolean("isWgValid", isReg)

        WarpProfile(
            accountId = j.getString("accountId"), token = j.getString("token"),
            licenseKey = j.optString("licenseKey"), clientIpv4 = j.getString("clientIpv4"),
            clientIpv6 = j.optString("clientIpv6"), peerEndpoint = j.getString("peerEndpoint"),
            peerPublicKey = j.getString("peerPublicKey"), privateKeyBase64 = j.getString("privateKey"),
            publicKeyBase64 = j.getString("publicKey"), p256PrivateKeyBase64 = j.optString("p256PrivateKey"),
            p256PublicKeyBase64 = j.optString("p256PublicKey"), clientCertBase64 = j.optString("clientCert"),
            isWarpPlus = isPlus, isWarpEnabled = j.getBoolean("isWarpEnabled"),
            registrationDate = j.getLong("registrationDate"),
            isRegistered = isReg,
            isActivated = isAct,
            credentialsValid = credsValid,
            dataPlaneReady = dpReady,
            entitlement = entitlement,
            needsVerification = needsVer,
            isOfflineCached = isOffline,
            isWgValid = isWgValid,
            apiEndpoint = j.optString("apiEndpoint", ""),
            userEndpointOverride = if (j.has("userEndpointOverride")) j.optString("userEndpointOverride").takeIf { it.isNotBlank() } else null,
            measuredWgEndpoint = if (j.has("measuredWgEndpoint")) j.optString("measuredWgEndpoint").takeIf { it.isNotBlank() } else null,
            dnsServers = j.optString("dnsServers", ""),
            clientId = j.optString("clientId").ifBlank { j.optString("client_id") }.ifBlank { j.optString("reserved") },
            awgI1 = j.optString("awgI1", ""),
            awgI2 = j.optString("awgI2", ""),
            awgI3 = j.optString("awgI3", ""),
            awgI4 = j.optString("awgI4", ""),
            awgI5 = j.optString("awgI5", ""),
            awgJc = j.optInt("awgJc", 0),
            awgJmin = j.optInt("awgJmin", 0),
            awgJmax = j.optInt("awgJmax", 0),
            awgS1 = j.optInt("awgS1", 0),
            awgS2 = j.optInt("awgS2", 0),
            awgS3 = j.optInt("awgS3", 0),
            awgS4 = j.optInt("awgS4", 0),
            awgH1 = j.optLong("awgH1", 1L),
            awgH2 = j.optLong("awgH2", 2L),
            awgH3 = j.optLong("awgH3", 3L),
            awgH4 = j.optLong("awgH4", 4L),
            awgRawConfig = j.optString("awgRawConfig", "")
        ).requireUsable()
    } catch (_: Exception) { null }
}

fun ProxyConfig.toWarpProfile() = WarpProfile(
    accountId = warpAccountId, token = warpToken, licenseKey = warpLicenseKey,
    clientIpv4 = warpClientIpv4, clientIpv6 = warpClientIpv6, peerEndpoint = warpPeerEndpoint,
    peerPublicKey = warpPeerPublicKey, privateKeyBase64 = warpPrivateKey, publicKeyBase64 = warpPublicKey,
    p256PrivateKeyBase64 = warpP256PrivateKey, p256PublicKeyBase64 = warpP256PublicKey,
    clientCertBase64 = warpClientCert, isWarpPlus = isWarpPlus, isWarpEnabled = isWarpAccountActive,
    isRegistered = warpAccountId.isNotBlank() && warpAccountId != "mirrly-warp-bootstrap-id",
    isActivated = isWarpAccountActive,
    credentialsValid = warpToken.isNotBlank() && warpToken != "mirrly-bootstrap-token",
    dataPlaneReady = warpAccountId.isNotBlank() && warpToken.isNotBlank() && warpAccountId != "mirrly-warp-bootstrap-id" && warpToken != "mirrly-bootstrap-token" && isWarpAccountActive && warpPeerPublicKey.isNotBlank(),
    entitlement = if (isWarpPlus) WarpEntitlement.PLUS else WarpEntitlement.FREE,
    needsVerification = false,
    isOfflineCached = false,
    isWgValid = if (isWgValid) true else (warpAccountId.isNotBlank() && warpAccountId != "mirrly-warp-bootstrap-id" && warpPrivateKey.isNotBlank()),
    apiEndpoint = warpApiEndpoint,
    userEndpointOverride = warpUserEndpointOverride.ifBlank { null },
    measuredWgEndpoint = warpMeasuredWgEndpoint.ifBlank { null },
    dnsServers = if (vpnDnsMode == VpnDnsMode.CUSTOM) vpnCustomDnsIps else "",
    clientId = warpClientId,
    awgI1 = awgI1,
    awgI2 = awgI2,
    awgI3 = awgI3,
    awgI4 = awgI4,
    awgI5 = awgI5,
    awgJc = awgJc,
    awgJmin = awgJmin,
    awgJmax = awgJmax,
    awgS1 = awgS1,
    awgS2 = awgS2,
    awgS3 = awgS3,
    awgS4 = awgS4,
    awgH1 = awgH1,
    awgH2 = awgH2,
    awgH3 = awgH3,
    awgH4 = awgH4,
    awgRawConfig = awgCustomIni
)
