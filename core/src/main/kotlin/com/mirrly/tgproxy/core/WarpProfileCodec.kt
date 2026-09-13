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
            put("masqueAccountId", p.masqueAccountId); put("masqueToken", p.masqueToken)
            put("masquePeerEndpoint", p.masquePeerEndpoint); put("masquePeerPublicKey", p.masquePeerPublicKey)
            put("masqueClientIpv4", p.masqueClientIpv4); put("masqueClientIpv6", p.masqueClientIpv6)
            put("isRegistered", p.isRegistered)
            put("isActivated", p.isActivated)
            put("credentialsValid", p.credentialsValid)
            put("dataPlaneReady", p.dataPlaneReady)
            put("entitlement", p.entitlement.name)
            put("needsVerification", p.needsVerification)
            put("isOfflineCached", p.isOfflineCached)
            put("isWgValid", p.isWgValid)
            put("isMasqueValid", p.isMasqueValid)
            put("apiEndpoint", p.apiEndpoint)
            p.userEndpointOverride?.let { put("userEndpointOverride", it) }
            put("masqueApiEndpoint", p.masqueApiEndpoint)
            p.masqueUserOverride?.let { put("masqueUserOverride", it) }
            p.measuredWgEndpoint?.let { put("measuredWgEndpoint", it) }
            p.measuredMasqueEndpoint?.let { put("measuredMasqueEndpoint", it) }
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
        val isMasqueValid = j.optBoolean("isMasqueValid", isAct && j.optString("p256PrivateKey").isNotBlank())

        WarpProfile(
            accountId = j.getString("accountId"), token = j.getString("token"),
            licenseKey = j.optString("licenseKey"), clientIpv4 = j.getString("clientIpv4"),
            clientIpv6 = j.optString("clientIpv6"), peerEndpoint = j.getString("peerEndpoint"),
            peerPublicKey = j.getString("peerPublicKey"), privateKeyBase64 = j.getString("privateKey"),
            publicKeyBase64 = j.getString("publicKey"), p256PrivateKeyBase64 = j.optString("p256PrivateKey"),
            p256PublicKeyBase64 = j.optString("p256PublicKey"), clientCertBase64 = j.optString("clientCert"),
            isWarpPlus = isPlus, isWarpEnabled = j.getBoolean("isWarpEnabled"),
            registrationDate = j.getLong("registrationDate"),
            masqueAccountId = j.optString("masqueAccountId"), masqueToken = j.optString("masqueToken"),
            masquePeerEndpoint = j.optString("masquePeerEndpoint"), masquePeerPublicKey = j.optString("masquePeerPublicKey"),
            masqueClientIpv4 = j.optString("masqueClientIpv4"), masqueClientIpv6 = j.optString("masqueClientIpv6"),
            isRegistered = isReg,
            isActivated = isAct,
            credentialsValid = credsValid,
            dataPlaneReady = dpReady,
            entitlement = entitlement,
            needsVerification = needsVer,
            isOfflineCached = isOffline,
            isWgValid = isWgValid,
            isMasqueValid = isMasqueValid,
            apiEndpoint = j.optString("apiEndpoint", ""),
            userEndpointOverride = if (j.has("userEndpointOverride")) j.optString("userEndpointOverride").takeIf { it.isNotBlank() } else null,
            masqueApiEndpoint = j.optString("masqueApiEndpoint", ""),
            masqueUserOverride = if (j.has("masqueUserOverride")) j.optString("masqueUserOverride").takeIf { it.isNotBlank() } else null,
            measuredWgEndpoint = if (j.has("measuredWgEndpoint")) j.optString("measuredWgEndpoint").takeIf { it.isNotBlank() } else null,
            measuredMasqueEndpoint = if (j.has("measuredMasqueEndpoint")) j.optString("measuredMasqueEndpoint").takeIf { it.isNotBlank() } else null
        ).requireUsable()
    } catch (_: Exception) { null }
}

fun ProxyConfig.toWarpProfile() = WarpProfile(
    accountId = warpAccountId, token = warpToken, licenseKey = warpLicenseKey,
    clientIpv4 = warpClientIpv4, clientIpv6 = warpClientIpv6, peerEndpoint = warpPeerEndpoint,
    peerPublicKey = warpPeerPublicKey, privateKeyBase64 = warpPrivateKey, publicKeyBase64 = warpPublicKey,
    p256PrivateKeyBase64 = warpP256PrivateKey, p256PublicKeyBase64 = warpP256PublicKey,
    clientCertBase64 = warpClientCert, isWarpPlus = isWarpPlus, isWarpEnabled = isWarpAccountActive,
    masqueAccountId = warpMasqueAccountId, masqueToken = warpMasqueToken,
    masquePeerEndpoint = warpMasquePeerEndpoint, masquePeerPublicKey = warpMasquePeerPublicKey,
    masqueClientIpv4 = warpMasqueClientIpv4, masqueClientIpv6 = warpMasqueClientIpv6,
    isRegistered = warpAccountId.isNotBlank() && warpAccountId != "mirrly-warp-bootstrap-id",
    isActivated = isWarpAccountActive,
    credentialsValid = warpToken.isNotBlank() && warpToken != "mirrly-bootstrap-token",
    dataPlaneReady = warpAccountId.isNotBlank() && warpToken.isNotBlank() && warpAccountId != "mirrly-warp-bootstrap-id" && warpToken != "mirrly-bootstrap-token" && isWarpAccountActive && warpPeerPublicKey.isNotBlank(),
    entitlement = if (isWarpPlus) WarpEntitlement.PLUS else WarpEntitlement.FREE,
    needsVerification = false,
    isOfflineCached = false,
    isWgValid = if (isWgValid) true else (warpAccountId.isNotBlank() && warpAccountId != "mirrly-warp-bootstrap-id" && warpPrivateKey.isNotBlank()),
    isMasqueValid = if (isMasqueValid) true else (warpP256PrivateKey.isNotBlank() && (warpMasqueToken.isNotBlank() || warpToken.isNotBlank())),
    apiEndpoint = warpApiEndpoint,
    userEndpointOverride = warpUserEndpointOverride.ifBlank { null },
    masqueApiEndpoint = warpMasqueApiEndpoint,
    masqueUserOverride = warpMasqueUserOverride.ifBlank { null },
    measuredWgEndpoint = warpMeasuredWgEndpoint.ifBlank { null },
    measuredMasqueEndpoint = warpMeasuredMasqueEndpoint.ifBlank { null }
)
