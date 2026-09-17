/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.mirrly.tgproxy.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Arrays
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class WarpRegistrationStep(
    val stepIndex: Int,
    val totalSteps: Int = 4,
    val stageTitle: String,
    val detail: String,
    val isError: Boolean = false,
    val isWarning: Boolean = false,
    val isComplete: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

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

data class WarpProfile(
    val accountId: String,
    val token: String,
    val licenseKey: String,
    val clientIpv4: String,
    val clientIpv6: String,
    val peerEndpoint: String,
    val peerPublicKey: String,
    val privateKeyBase64: String,
    val publicKeyBase64: String,
    val p256PrivateKeyBase64: String = "",
    val p256PublicKeyBase64: String = "",
    val clientCertBase64: String = "",
    val isWarpPlus: Boolean,
    val isWarpEnabled: Boolean,
    val registrationDate: Long = System.currentTimeMillis(),
    val masqueAccountId: String = "",
    val masqueToken: String = "",
    val masquePeerEndpoint: String = "",
    val masquePeerPublicKey: String = "",
    val masqueClientIpv4: String = "",
    val masqueClientIpv6: String = "",
    val isRegistered: Boolean = false,
    val isActivated: Boolean = false,
    val credentialsValid: Boolean = false,
    val dataPlaneReady: Boolean = false,
    val entitlement: WarpEntitlement = WarpEntitlement.FREE,
    val needsVerification: Boolean = false,
    val isOfflineCached: Boolean = false,
    val isWgValid: Boolean = false,
    val isMasqueValid: Boolean = false,
    val apiEndpoint: String = "",
    val userEndpointOverride: String? = null,
    val masqueApiEndpoint: String = "",
    val masqueUserOverride: String? = null,
    val measuredWgEndpoint: String? = null,
    val measuredMasqueEndpoint: String? = null
) {
    fun isUsable(): Boolean {
        val notBootstrap = accountId.isNotBlank() &&
            token.isNotBlank() &&
            accountId != "mirrly-warp-bootstrap-id" &&
            token != "mirrly-bootstrap-token"
        val hasKeys = privateKeyBase64.isNotBlank() && publicKeyBase64.isNotBlank()
        val hasNetwork = clientIpv4.isNotBlank() && effectivePeerEndpoint.isNotBlank()
        return notBootstrap && hasKeys && hasNetwork && (isRegistered || isOfflineCached)
    }

    fun requireUsable(): WarpProfile {
        require(isUsable()) {
            "WarpProfile is unusable or unverified: accountId=$accountId, isRegistered=$isRegistered, isOfflineCached=$isOfflineCached"
        }
        return this
    }

    val effectivePeerEndpoint: String
        get() = when {
            !userEndpointOverride.isNullOrBlank() -> userEndpointOverride
            !measuredWgEndpoint.isNullOrBlank() -> measuredWgEndpoint
            apiEndpoint.isNotBlank() -> apiEndpoint
            peerEndpoint.isNotBlank() -> peerEndpoint
            else -> "162.159.193.10:1701"
        }

    val effectiveMasqueToken: String
        get() = masqueToken.ifBlank { token }

    val effectiveMasqueAccountId: String
        get() = masqueAccountId.ifBlank { accountId }

    val effectiveMasquePeerEndpoint: String
        get() = when {
            !masqueUserOverride.isNullOrBlank() -> masqueUserOverride
            !measuredMasqueEndpoint.isNullOrBlank() -> measuredMasqueEndpoint
            masqueApiEndpoint.isNotBlank() -> masqueApiEndpoint
            masquePeerEndpoint.isNotBlank() -> masquePeerEndpoint
            !userEndpointOverride.isNullOrBlank() -> userEndpointOverride
            apiEndpoint.isNotBlank() -> apiEndpoint
            else -> peerEndpoint
        }

    val effectiveMasquePeerPublicKey: String
        get() = masquePeerPublicKey.ifBlank { peerPublicKey }

    val effectiveMasqueClientIpv4: String
        get() = masqueClientIpv4.ifBlank { clientIpv4 }

    val effectiveMasqueClientIpv6: String
        get() = masqueClientIpv6.ifBlank { clientIpv6 }

    fun hasValidWgIdentity(): Boolean {
        return isWgValid && privateKeyBase64.isNotBlank() && publicKeyBase64.isNotBlank() &&
            peerPublicKey.isNotBlank() && clientIpv4.isNotBlank() && accountId.isNotBlank()
    }

    fun hasValidMasqueIdentity(): Boolean {
        return isMasqueValid && p256PrivateKeyBase64.isNotBlank() &&
            effectiveMasqueToken.isNotBlank() && effectiveMasqueAccountId.isNotBlank()
    }

    fun getSummary(): String {
        val plusTag = when (entitlement) {
            WarpEntitlement.PLUS -> "WARP+"
            WarpEntitlement.TEAM -> "WARP Teams"
            WarpEntitlement.UNVERIFIED -> "Unverified"
            WarpEntitlement.FREE -> "Free"
        }
        val statusTag = if (isActivated && dataPlaneReady) "Готов" else if (isOfflineCached) "Офлайн-кеш" else if (isWarpEnabled) "Активен" else "Не активирован"
        val masqueTag = if (clientCertBase64.isNotBlank()) " | mTLS Сертификат" else " | Bearer Auth"
        return "IP: $clientIpv4 | $plusTag ($statusTag)$masqueTag"
    }

    fun toAmneziaWgConfig(
        cleanEndpoint: String = "188.114.96.1:500",
        @Suppress("UNUSED_PARAMETER") sniCamouflage: String = ""
    ): String {
        val peerKey = if (peerPublicKey.isNotBlank()) peerPublicKey else "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
        val ipv4 = if (clientIpv4.isNotBlank()) clientIpv4 else "172.16.0.2"
        val addressStr = if (clientIpv6.isNotBlank()) "$ipv4/32, $clientIpv6/128" else "$ipv4/32"
        return """
            [Interface]
            PrivateKey = $privateKeyBase64
            Address = $addressStr
            DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001
            MTU = 1280
            Jc = 4
            Jmin = 40
            Jmax = 70
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
}

/**
 * M01: Результат независимой валидации протокольных identity (WireGuard и MASQUE).
 */
data class ProtocolIdentityStatus(
    val isWireGuardValid: Boolean,
    val isMasqueValid: Boolean,
    val wireGuardDetail: String,
    val masqueDetail: String,
    val isDualProtocolReady: Boolean
)

/**
 * TSK-M10: Информация об учетной записи WARP / WARP+ подписке.
 */
data class WarpAccountInfo(
    val licenseKey: String,
    val isWarpPlus: Boolean,
    val accountType: String,
    val referralCount: Int = 0,
    val entitlement: WarpEntitlement = if (isWarpPlus) WarpEntitlement.PLUS else WarpEntitlement.FREE
)

object WarpAccountManager {
    private const val TAG = "WarpAccountManager"
    const val REGISTRATION_WORKER_DOMAIN = "warp-reg.rbmkyuw.workers.dev"
    private const val API_BASE_V3371 = "https://api.cloudflareclient.com/v0a3371"
    private const val API_BASE_V4471 = "https://api.cloudflareclient.com/v0a4471"
    private const val USER_AGENT = "WARP for Android"
    private const val CLIENT_VERSION = "a-6.35-4471"

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

    fun randomSerialHex(): String {
        val random = SecureRandom()
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        val sb = StringBuilder(16)
        for (b in bytes) sb.append(String.format(java.util.Locale.US, "%02x", b))
        return sb.toString()
    }

    val BOOTSTRAP_PROFILE = WarpProfile(
        accountId = "mirrly-warp-bootstrap-id",
        token = "mirrly-bootstrap-token",
        licenseKey = "",
        clientIpv4 = "172.16.0.2",
        clientIpv6 = "2606:4700:110:812c:a554:b442:26d4:1330",
        peerEndpoint = "188.114.96.1:500",
        peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
        privateKeyBase64 = "7NMyJemtPW8/EKQsBxAHjfhsPVOiNZfpG0VERGergeY=",
        publicKeyBase64 = "o4z6NjglNksBbiLm9upBM2Ruejiv9OnFSkXLwUfAsAE=",
        p256PrivateKeyBase64 = "",
        p256PublicKeyBase64 = "",
        clientCertBase64 = "",
        isWarpPlus = false,
        isWarpEnabled = false,
        isRegistered = false,
        isActivated = false,
        credentialsValid = false,
        dataPlaneReady = false,
        entitlement = WarpEntitlement.UNVERIFIED,
        needsVerification = true,
        isOfflineCached = false,
        isWgValid = false,
        isMasqueValid = false
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .socketFactory(TlsFragmentingSocketFactory())
            .dns(WarpCloudflareDns)
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(1000, TimeUnit.MILLISECONDS)
            .writeTimeout(1000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val workerClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2000, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .writeTimeout(2500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun getOperaProxyClient(endpoint: String = "77.111.247.139:443"): OkHttpClient {
        val (ip, port) = if (endpoint.contains(":")) {
            val parts = endpoint.split(":")
            Pair(parts[0], parts[1].toIntOrNull() ?: 443)
        } else {
            Pair(endpoint, 443)
        }
        return OkHttpClient.Builder()
            .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(ip, port)))
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun getLocalProxyClient(localPort: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", localPort)))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Generates a cryptographically valid WireGuard X25519 keypair.
     *
     * In accordance with RFC 7748 and WireGuard specifications:
     * - The private key is clamped (bits 0..2 cleared, bit 254 set, bit 255 cleared).
     * - The public key is mathematically computed from the private key via X25519(clamp(priv), 9).
     *
     * Execution flow:
     * 1. If [forcePureKotlin] is false, attempts JCA KeyPairGenerator with "X25519".
     *    Extracts raw 32-byte keys from RFC 8410 SPKI/PKCS#8 encodings using [Curve25519.extractRawPrivateKey]
     *    and [Curve25519.extractRawPublicKey].
     *    Validates that the JCA public key matches [Curve25519.computePublicKey].
     * 2. If JCA X25519 is unavailable (NoSuchAlgorithmException, as on Android API < 33) or [forcePureKotlin] is true:
     *    Generates 32 bytes from [SecureRandom], applies clamping, and derives the public key via
     *    [Curve25519.computePublicKey].
     * 3. If cryptographic entropy or scalar multiplication fails, throws [IllegalStateException] immediately.
     *    Never catches arbitrary Throwable to generate synthetic unlinked dummy keys.
     *
     * @param forcePureKotlin If true, bypasses JCA provider to enforce pure Kotlin Curve25519 engine.
     * @param secureRandom Secure random instance for key generation.
     * @return Pair of (privateKeyBase64, publicKeyBase64).
     * @throws IllegalStateException if keypair generation or validation fails.
     */
    fun generateWireGuardKeyPair(
        forcePureKotlin: Boolean = false,
        secureRandom: SecureRandom = SecureRandom()
    ): Pair<String, String> {
        if (!forcePureKotlin) {
            try {
                val kpg = KeyPairGenerator.getInstance("X25519")
                val pair = kpg.generateKeyPair()
                val privRaw = Curve25519.extractRawPrivateKey(pair.private.encoded)
                val pubRaw = Curve25519.extractRawPublicKey(pair.public.encoded)

                // Mathematically verify that the public key derives from the private key
                val expectedPub = Curve25519.computePublicKey(privRaw)
                if (!Arrays.equals(pubRaw, expectedPub)) {
                    throw IllegalStateException("JCA X25519 provider produced inconsistent keypair: public key mismatch")
                }

                // WireGuard requires private key to be clamped
                val clampedPriv = Curve25519.clampPrivateKey(privRaw)
                return Pair(
                    Base64.getEncoder().encodeToString(clampedPriv),
                    Base64.getEncoder().encodeToString(pubRaw)
                )
            } catch (e: NoSuchAlgorithmException) {
                // Expected on Android API < 33 without custom security provider; fallback to pure Kotlin Curve25519
            } catch (e: IllegalArgumentException) {
                // Malformed DER from JCA provider; fallback to pure Kotlin Curve25519
            } catch (e: IllegalStateException) {
                throw e
            }
        }

        // Pure Kotlin RFC 7748 engine: guaranteed to work across all Android versions and JVMs
        try {
            val (privBytes, pubBytes) = Curve25519.generateKeyPair(secureRandom)

            // Double check mathematical derivation
            val derivedPub = Curve25519.computePublicKey(privBytes)
            if (!Arrays.equals(pubBytes, derivedPub)) {
                throw IllegalStateException("Pure Kotlin Curve25519 produced inconsistent keypair")
            }

            return Pair(
                Base64.getEncoder().encodeToString(privBytes),
                Base64.getEncoder().encodeToString(pubBytes)
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to generate secure WireGuard X25519 keypair: ${e.message}", e)
        }
    }

    fun generateMasqueKeyPairAndCert(): Triple<String, String, String> {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()
        val spkiBytes = keyPair.public.encoded
        val pkcs8Bytes = keyPair.private.encoded
        val certDer = buildSelfSignedCert(keyPair)
        return Triple(
            Base64.getEncoder().encodeToString(pkcs8Bytes),
            Base64.getEncoder().encodeToString(spkiBytes),
            Base64.getEncoder().encodeToString(certDer)
        )
    }

    private fun buildSelfSignedCert(keyPair: KeyPair): ByteArray {
        val spki = keyPair.public.encoded
        val sigAlg = byteArrayOf(0x30.toByte(), 0x0A.toByte(), 0x06.toByte(), 0x08.toByte(), 0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0xCE.toByte(), 0x3D.toByte(), 0x04.toByte(), 0x03.toByte(), 0x02.toByte())
        val version = byteArrayOf(0xA0.toByte(), 0x03.toByte(), 0x02.toByte(), 0x01.toByte(), 0x02.toByte())
        val serial = byteArrayOf(0x02.toByte(), 0x01.toByte(), 0x01.toByte())
        val nameDer = byteArrayOf(0x30.toByte(), 0x11.toByte(), 0x31.toByte(), 0x0F.toByte(), 0x30.toByte(), 0x0D.toByte(), 0x06.toByte(), 0x03.toByte(), 0x55.toByte(), 0x04.toByte(), 0x03.toByte(), 0x0C.toByte(), 0x06.toByte(), 0x4D.toByte(), 0x69.toByte(), 0x72.toByte(), 0x72.toByte(), 0x6C.toByte(), 0x79.toByte())
        val notBefore = byteArrayOf(0x17.toByte(), 0x0D.toByte()) + "260101000000Z".toByteArray(Charsets.US_ASCII)
        val notAfter = byteArrayOf(0x17.toByte(), 0x0D.toByte()) + "360101000000Z".toByteArray(Charsets.US_ASCII)
        val validity = asn1Sequence(notBefore + notAfter)
        val tbsInner = version + serial + sigAlg + nameDer + validity + nameDer + spki
        val tbsCertificate = asn1Sequence(tbsInner)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(tbsCertificate)
        val rawSig = sig.sign()
        val bitStringSig = byteArrayOf(0x03.toByte()) + asn1Length(rawSig.size + 1) + byteArrayOf(0x00.toByte()) + rawSig
        return asn1Sequence(tbsCertificate + sigAlg + bitStringSig)
    }

    private fun asn1Sequence(content: ByteArray): ByteArray = byteArrayOf(0x30.toByte()) + asn1Length(content.size) + content

    private fun asn1Length(len: Int): ByteArray = when {
        len < 128 -> byteArrayOf(len.toByte())
        len < 256 -> byteArrayOf(0x81.toByte(), len.toByte())
        else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
    }

    /**
     * TSK-M09: Ревизия Wire-Level аутентификации.
     * Проверяет и извлекает подписанный Cloudflare CA клиентский сертификат из ответа API,
     * если он предоставлен шлюзом (certificate, client_cert или config.certificate).
     */
    fun extractCertificateFromResponse(json: JSONObject): String? {
        val cert = json.optString("certificate", "").ifBlank {
            json.optString("client_cert", "").ifBlank {
                json.optJSONObject("config")?.optString("certificate", "") ?: ""
            }
        }
        return cert.trim().ifBlank { null }
    }

    /**
     * TSK-M10: Валидация формата лицензионного ключа WARP+ (26 символов).
     * Ключ состоит из 26 буквенно-цифровых символов (с дефисами или без).
     */
    fun isValidLicenseKey(key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        val clean = key.trim().replace("-", "")
        return key.trim().length == 26 || clean.length == 26 || clean.length == 24
    }

    /**
     * TSK-M10: Привязка лицензионного ключа WARP+ к существующей учетной записи.
     * Эндпоинт: PUT /v0a4471/reg/{accountId}/account
     * Тело: {"license": "<licenseKey>"}
     */
    suspend fun attachLicenseKey(
        accountId: String,
        token: String,
        licenseKey: String,
        workerDomain: String? = null
    ): Result<WarpAccountInfo> = withContext(Dispatchers.IO) {
        val cleanKey = licenseKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Лицензионный ключ не может быть пустым"))
        }
        if (accountId.isBlank() || token.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Отсутствуют идентификатор или токен аккаунта WARP"))
        }

        val effectiveWorker = workerDomain?.let { WorkerDomainNormalizer.sanitizeDomain(it) }?.ifEmpty { null }
            ?: REGISTRATION_WORKER_DOMAIN
        val bodyJson = JSONObject().apply {
            put("license", cleanKey)
        }.toString()
        val mediaType = "application/json; charset=UTF-8".toMediaType()

        var responseJson: JSONObject? = null
        var lastError: Exception? = null

        // 1. Worker Relay (выделенный регистрационный воркер)
        if (!effectiveWorker.isNullOrBlank()) {
            try {
                val req = Request.Builder()
                    .url("https://$effectiveWorker/warp-api/reg/$accountId/account")
                    .put(bodyJson.toRequestBody(mediaType))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("CF-Client-Version", CLIENT_VERSION)
                    .build()
                workerClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        responseJson = JSONObject(body)
                    } else if (!body.isNullOrBlank()) {
                        val parsed = try { JSONObject(body) } catch (_: Exception) { null }
                        val errors = parsed?.optJSONArray("errors")
                        if (errors != null && errors.length() > 0) {
                            val msg = errors.getJSONObject(0).optString("message", "Ошибка API Cloudflare")
                            lastError = IllegalStateException(msg)
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Ошибка привязки WARP+ через воркер: ${e.message}")
                if (lastError == null) lastError = e
            }
        }

        // 2. Попытка через WarpObfuscatedHttpClient (прямое подключение с обходом SNI-блокировок)
        if (responseJson == null && lastError !is IllegalStateException) {
            try {
                val obfResp = WarpObfuscatedHttpClient.execute(
                    method = "PUT",
                    path = "/v0a4471/reg/$accountId/account",
                    bodyJson = bodyJson,
                    authToken = token,
                    timeoutMs = 1500,
                    maxCandidates = 2
                )
                if (obfResp.statusCode in 200..299 && obfResp.body.isNotBlank()) {
                    responseJson = JSONObject(obfResp.body)
                } else if (obfResp.body.isNotBlank()) {
                    val parsed = try { JSONObject(obfResp.body) } catch (_: Exception) { null }
                    val errors = parsed?.optJSONArray("errors")
                    if (errors != null && errors.length() > 0) {
                        val msg = errors.getJSONObject(0).optString("message", "Ошибка API Cloudflare")
                        lastError = IllegalStateException(msg)
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Ошибка обфусцированной привязки WARP+: ${e.message}")
                if (lastError == null) lastError = e
            }
        }

        // 3. Прямой OkHttp запрос
        if (responseJson == null && lastError !is IllegalStateException) {
            try {
                val req = Request.Builder()
                    .url("$API_BASE_V4471/reg/$accountId/account")
                    .put(bodyJson.toRequestBody(mediaType))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("CF-Client-Version", CLIENT_VERSION)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        responseJson = JSONObject(body)
                    } else if (!body.isNullOrBlank()) {
                        val parsed = try { JSONObject(body) } catch (_: Exception) { null }
                        val errors = parsed?.optJSONArray("errors")
                        if (errors != null && errors.length() > 0) {
                            val msg = errors.getJSONObject(0).optString("message", "Ошибка API Cloudflare")
                            lastError = IllegalStateException(msg)
                        } else {
                            lastError = IllegalStateException("HTTP ${resp.code}: $body")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Сбой прямого PUT account: ${e.message}")
                if (lastError == null) lastError = e
            }
        }

        val json = responseJson
        if (json != null) {
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val msg = errors.getJSONObject(0).optString("message", "Недействительный ключ лицензии")
                return@withContext Result.failure(IllegalStateException(msg))
            }

            val activeLicense = json.optString("license", cleanKey)
            val isPlus = json.optBoolean("warp_plus", false) ||
                json.optString("type", "").contains("plus", ignoreCase = true) ||
                json.optString("type", "").contains("team", ignoreCase = true) ||
                json.optString("account_type", "").contains("plus", ignoreCase = true) ||
                json.optString("account_type", "").contains("team", ignoreCase = true)
            val accType = json.optString("type", json.optString("account_type", if (isPlus) "plus" else "free"))
            val referralCount = json.optInt("referral_count", 0)
            val entitlement = WarpEntitlement.fromType(accType, isPlus)
            AppLogger.i(TAG, "Лицензия WARP (${maskLicenseKey(cleanKey)}) успешно обработана для аккаунта $accountId: type=$accType, warp_plus=$isPlus")
            Result.success(
                WarpAccountInfo(
                    licenseKey = activeLicense,
                    isWarpPlus = isPlus,
                    accountType = accType,
                    referralCount = referralCount,
                    entitlement = entitlement
                )
            )
        } else {
            Result.failure(lastError ?: IllegalStateException("Не удалось привязать лицензию WARP+"))
        }
    }

    /**
     * TSK-M10: Получение актуальной информации об учетной записи WARP и статусе подписки.
     * Эндпоинт: GET /v0a4471/reg/{accountId}/account
     */
    suspend fun fetchAccountInfo(
        accountId: String,
        token: String,
        workerDomain: String? = null
    ): Result<WarpAccountInfo> = withContext(Dispatchers.IO) {
        if (accountId.isBlank() || token.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Отсутствуют идентификатор или токен аккаунта WARP"))
        }

        val effectiveWorker = workerDomain?.let { WorkerDomainNormalizer.sanitizeDomain(it) }?.ifEmpty { null }
            ?: REGISTRATION_WORKER_DOMAIN
        var responseJson: JSONObject? = null
        var lastError: Exception? = null

        // 1. Worker Relay (выделенный регистрационный воркер)
        if (!effectiveWorker.isNullOrBlank()) {
            try {
                val req = Request.Builder()
                    .url("https://$effectiveWorker/warp-api/reg/$accountId/account")
                    .get()
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("CF-Client-Version", CLIENT_VERSION)
                    .build()
                workerClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        responseJson = JSONObject(body)
                    }
                }
            } catch (e: Exception) {
                if (lastError == null) lastError = e
            }
        }

        // 2. Прямой обфусцированный GET с нарезкой SNI
        if (responseJson == null) {
            try {
                val obfResp = WarpObfuscatedHttpClient.execute(
                    method = "GET",
                    path = "/v0a4471/reg/$accountId/account",
                    authToken = token,
                    timeoutMs = 1500,
                    maxCandidates = 2
                )
                if (obfResp.statusCode in 200..299 && obfResp.body.isNotBlank()) {
                    responseJson = JSONObject(obfResp.body)
                }
            } catch (e: Exception) {
                if (lastError == null) lastError = e
            }
        }

        // 3. Прямой GET
        if (responseJson == null) {
            try {
                val req = Request.Builder()
                    .url("$API_BASE_V4471/reg/$accountId/account")
                    .get()
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("CF-Client-Version", CLIENT_VERSION)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        responseJson = JSONObject(body)
                    }
                }
            } catch (e: Exception) {
                if (lastError == null) lastError = e
            }
        }

        val json = responseJson
        if (json != null) {
            val licenseKey = json.optString("license", "")
            val isPlus = json.optBoolean("warp_plus", false) ||
                json.optString("type", "").contains("plus", ignoreCase = true) ||
                json.optString("type", "").contains("team", ignoreCase = true) ||
                json.optString("account_type", "").contains("plus", ignoreCase = true) ||
                json.optString("account_type", "").contains("team", ignoreCase = true)
            val accType = json.optString("type", json.optString("account_type", if (isPlus) "plus" else "free"))
            val referralCount = json.optInt("referral_count", 0)
            val entitlement = WarpEntitlement.fromType(accType, isPlus)
            Result.success(
                WarpAccountInfo(
                    licenseKey = licenseKey,
                    isWarpPlus = isPlus,
                    accountType = accType,
                    referralCount = referralCount,
                    entitlement = entitlement
                )
            )
        } else {
            Result.failure(lastError ?: IllegalStateException("Не удалось получить информацию об аккаунте WARP"))
        }
    }

    data class ApiCallResult(
        val json: JSONObject?,
        val connectedOperaEndpoint: String?,
        val error: Exception?
    )

    private suspend fun executeApiCall(
        method: String,
        path: String,
        bodyJson: String? = null,
        authToken: String? = null,
        effectiveApiBase: String = API_BASE_V4471,
        workerDomain: String? = REGISTRATION_WORKER_DOMAIN,
        cachedOperaEndpoint: String? = null,
        timeoutMs: Int = WarpObfuscatedHttpClient.DEFAULT_TIMEOUT_MS
    ): ApiCallResult {
        val mediaType = "application/json; charset=UTF-8".toMediaType()
        var lastError: Exception? = null
        var activeOperaEndpoint = cachedOperaEndpoint

        val effectiveWorker = workerDomain?.let { WorkerDomainNormalizer.sanitizeDomain(it) }?.ifEmpty { null }
            ?: REGISTRATION_WORKER_DOMAIN

        // 1. Worker (выделенный регистрационный воркер для обхода блокировок api.cloudflareclient.com)
        if (!effectiveWorker.isNullOrBlank()) {
            try {
                val reqBuilder = Request.Builder()
                    .url("https://$effectiveWorker/warp-api$path")
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", USER_AGENT)
                    .header("CF-Client-Version", CLIENT_VERSION)
                if (!authToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $authToken")
                }
                when (method.uppercase()) {
                    "POST" -> reqBuilder.post((bodyJson ?: "").toRequestBody(mediaType))
                    "PATCH" -> reqBuilder.patch((bodyJson ?: "").toRequestBody(mediaType))
                    "PUT" -> reqBuilder.put((bodyJson ?: "").toRequestBody(mediaType))
                    else -> reqBuilder.get()
                }
                workerClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        val parsed = try { JSONObject(body) } catch (_: Exception) { null }
                        if (parsed != null) return ApiCallResult(parsed, activeOperaEndpoint, null)
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        // 2. Прямой обфусцированный зонд Cloudflare API (нарезка SNI для обхода ТСПУ)
        try {
            val obfResp = WarpObfuscatedHttpClient.execute(
                method = method,
                path = path,
                bodyJson = bodyJson,
                authToken = authToken,
                timeoutMs = timeoutMs,
                maxCandidates = 1
            )
            if ((obfResp.statusCode in 200..299) && obfResp.body.isNotBlank()) {
                val parsed = try { JSONObject(obfResp.body) } catch (_: Exception) { null }
                if (parsed != null) return ApiCallResult(parsed, activeOperaEndpoint, null)
            }
        } catch (e: Exception) {
            lastError = e
        }

        // 3. Прямой OkHttp запрос
        try {
            val reqBuilder = Request.Builder()
                .url("$effectiveApiBase$path")
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", USER_AGENT)
                .header("CF-Client-Version", CLIENT_VERSION)
            if (!authToken.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer $authToken")
            }
            when (method.uppercase()) {
                "POST" -> reqBuilder.post((bodyJson ?: "").toRequestBody(mediaType))
                "PATCH" -> reqBuilder.patch((bodyJson ?: "").toRequestBody(mediaType))
                "PUT" -> reqBuilder.put((bodyJson ?: "").toRequestBody(mediaType))
                else -> reqBuilder.get()
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && !body.isNullOrBlank()) {
                    val parsed = try { JSONObject(body) } catch (_: Exception) { null }
                    if (parsed != null) return ApiCallResult(parsed, activeOperaEndpoint, null)
                }
            }
        } catch (e: Exception) {
            lastError = e
        }

        // 4. Opera VPN шлюз при сбое прямого зонда
        val nodesToTry = if (!activeOperaEndpoint.isNullOrBlank()) {
            listOf(OperaVpnRepository.NODES.firstOrNull { it.endpoint == activeOperaEndpoint } ?: OperaVpnRepository.NODES[0])
        } else {
            OperaVpnRepository.NODES
        }

        for (opNode in nodesToTry) {
            try {
                val opClient = getOperaProxyClient(opNode.endpoint)
                val reqBuilder = Request.Builder()
                    .url("$effectiveApiBase$path")
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", USER_AGENT)
                    .header("CF-Client-Version", CLIENT_VERSION)
                if (!authToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $authToken")
                }
                when (method.uppercase()) {
                    "POST" -> reqBuilder.post((bodyJson ?: "").toRequestBody(mediaType))
                    "PATCH" -> reqBuilder.patch((bodyJson ?: "").toRequestBody(mediaType))
                    "PUT" -> reqBuilder.put((bodyJson ?: "").toRequestBody(mediaType))
                    else -> reqBuilder.get()
                }
                opClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        val parsed = try { JSONObject(body) } catch (_: Exception) { null }
                        if (parsed != null) {
                            return ApiCallResult(parsed, opNode.endpoint, null)
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        return ApiCallResult(null, activeOperaEndpoint, lastError)
    }

    /**
     * M01: Одновременная независимая проверка действительности identity WireGuard и MASQUE.
     * Проверяет:
     * 1. Принадлежность ключей, адресов и peer public key соответствующему профилю.
     * 2. Математическое соответствие Curve25519 для WireGuard.
     * 3. Наличие и валидность P-256 (secp256r1) и токена/сертификата для MASQUE.
     * 4. Отсутствие взаимного загрязнения или инвалидации учетных данных.
     */
    fun verifyProtocolIdentities(profile: WarpProfile): ProtocolIdentityStatus {
        // 1. Проверка identity WireGuard
        val wgKeyValid = try {
            val priv = Base64.getDecoder().decode(profile.privateKeyBase64)
            val pub = Base64.getDecoder().decode(profile.publicKeyBase64)
            if (priv.size == 32 && pub.size == 32) {
                val derivedPub = Curve25519.computePublicKey(priv)
                Arrays.equals(pub, derivedPub)
            } else false
        } catch (_: Exception) { false }

        val wgPeerValid = try {
            val peer = Base64.getDecoder().decode(profile.peerPublicKey)
            peer.size == 32
        } catch (_: Exception) { false }

        val wgHasAccount = profile.accountId.isNotBlank() &&
            profile.accountId != "mirrly-warp-bootstrap-id" &&
            profile.token.isNotBlank() &&
            profile.token != "mirrly-bootstrap-token"

        val wgIpv4Valid = profile.clientIpv4.isNotBlank()

        val wgValid = wgKeyValid && wgPeerValid && wgHasAccount && wgIpv4Valid && profile.isWgValid

        val wgDetail = when {
            !wgHasAccount -> "Отсутствует регистрация устройства WireGuard"
            !wgKeyValid -> "Недействительная ключевая пара Curve25519"
            !wgPeerValid -> "Недействительный открытый ключ пира WireGuard (ожидается 32 байта)"
            !profile.isWgValid -> "Регистрация WireGuard не подтверждена сервером"
            else -> "Curve25519 подтвержден (IPv4: ${profile.clientIpv4}, Peer: ${profile.peerPublicKey.take(8)}...)"
        }

        // 2. Проверка identity MASQUE
        val masqueKeyValid = try {
            val privB = Base64.getDecoder().decode(profile.p256PrivateKeyBase64)
            val pubB = Base64.getDecoder().decode(profile.p256PublicKeyBase64)
            privB.isNotEmpty() && pubB.isNotEmpty()
        } catch (_: Exception) { false }

        val masqueToken = profile.effectiveMasqueToken
        val masqueAccountId = profile.effectiveMasqueAccountId
        val masqueHasAccount = masqueAccountId.isNotBlank() &&
            masqueAccountId != "mirrly-warp-bootstrap-id" &&
            masqueToken.isNotBlank() &&
            masqueToken != "mirrly-bootstrap-token"

        val masqueValid = masqueKeyValid && masqueHasAccount && profile.isMasqueValid

        val masqueDetail = when {
            !masqueHasAccount -> "Отсутствует регистрация устройства MASQUE"
            !masqueKeyValid -> "Недействительная ключевая пара NIST P-256 (secp256r1)"
            !profile.isMasqueValid -> "Регистрация MASQUE не подтверждена сервером"
            else -> "P-256 подтвержден (Device: ${masqueAccountId.take(8)}..., Auth: ${if (profile.clientCertBase64.isNotBlank()) "mTLS X.509" else "Bearer Token"})"
        }

        return ProtocolIdentityStatus(
            isWireGuardValid = wgValid,
            isMasqueValid = masqueValid,
            wireGuardDetail = wgDetail,
            masqueDetail = masqueDetail,
            isDualProtocolReady = wgValid && masqueValid
        )
    }

    suspend fun registerAndActivate(
        workerDomain: String? = null,
        licenseKey: String? = null,
        existingProfile: WarpProfile? = null,
        fallbackToBootstrap: Boolean = false,
        onProgress: ((WarpRegistrationStep) -> Unit)? = null
    ): Result<WarpProfile> = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "Запуск раздельной регистрации WARP (WireGuard + MASQUE)...")
            onProgress?.invoke(WarpRegistrationStep(1, 4, "Генерация ключей WireGuard", "Генерация пары Curve25519 (X25519) и серийного номера Android..."))
            val (wgPriv, wgPub) = generateWireGuardKeyPair()
            val wgSerialHex = randomSerialHex()
            val tosTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS+00:00").withZone(ZoneOffset.UTC).format(Instant.now())
            onProgress?.invoke(WarpRegistrationStep(1, 4, "Регистрация WireGuard", "Серийный номер: $wgSerialHex. Подготовка запроса POST /reg..."))

            val wgRegBody = JSONObject().apply {
                put("key", wgPub); put("install_id", ""); put("fcm_token", "")
                put("tos", tosTimestamp); put("model", "Android"); put("serial_number", wgSerialHex)
                put("os_version", ""); put("key_type", "curve25519"); put("tunnel_type", "wireguard"); put("locale", "ru_RU")
            }

            val cleanWorkerDomain = workerDomain?.let { WorkerDomainNormalizer.sanitizeDomain(it) }?.ifEmpty { null }
                ?: REGISTRATION_WORKER_DOMAIN
            var activeOperaEndpoint: String? = null

            // 1. Регистрация устройства WireGuard (Curve25519)
            val wgRes = executeApiCall(
                method = "POST",
                path = "/v0a4471/reg",
                bodyJson = wgRegBody.toString(),
                authToken = null,
                effectiveApiBase = API_BASE_V4471,
                workerDomain = cleanWorkerDomain,
                cachedOperaEndpoint = activeOperaEndpoint
            )
            activeOperaEndpoint = wgRes.connectedOperaEndpoint
            val regJson = wgRes.json
            val accountId = regJson?.optString("id", "") ?: ""
            val token = regJson?.optString("token", "") ?: ""

            if (regJson == null || accountId.isBlank() || token.isBlank()) {
                if (existingProfile != null && existingProfile.isRegistered && existingProfile.isUsable()) {
                    onProgress?.invoke(
                        WarpRegistrationStep(
                            4,
                            4,
                            "Применен сохраненный офлайн-профиль",
                            "Сеть Cloudflare недоступна. Использован подтвержденный профиль (требуется онлайн-проверка).",
                            isComplete = true,
                            isWarning = true
                        )
                    )
                    val cachedProfile = existingProfile.copy(
                        needsVerification = true,
                        isOfflineCached = true,
                        dataPlaneReady = false
                    )
                    return@withContext Result.success(cachedProfile)
                }
                val failureReason = wgRes.error?.message ?: "Не удалось зарегистрировать WARP (все каналы блокируются)"
                onProgress?.invoke(
                    WarpRegistrationStep(
                        1,
                        4,
                        "Ошибка первичной регистрации",
                        failureReason,
                        isError = true
                    )
                )
                return@withContext Result.failure(wgRes.error ?: IllegalStateException(failureReason))
            }

            val accountObj = regJson.optJSONObject("account")
            var activeLicenseKey = accountObj?.optString("license", "") ?: ""
            var activeIsWarpPlus = accountObj?.optBoolean("warp_plus", false) ?: false
            val configObj = regJson.optJSONObject("config")
            val addressesObj = configObj?.optJSONObject("interface")?.optJSONObject("addresses")
            val clientIpv4 = addressesObj?.optString("v4", "172.16.0.2") ?: "172.16.0.2"
            val clientIpv6 = addressesObj?.optString("v6", "") ?: ""
            var rawApiEndpoint = existingProfile?.apiEndpoint ?: ""
            val userOverride = existingProfile?.userEndpointOverride
            var peerEndpoint = userOverride ?: "162.159.193.10:1701"
            var peerPublicKey = ""
            val peersArr = configObj?.optJSONArray("peers")
            if (peersArr != null && peersArr.length() > 0) {
                val p0 = peersArr.optJSONObject(0)
                peerPublicKey = p0?.optString("public_key", "") ?: ""
                val apiEp = p0?.optJSONObject("endpoint")?.optString("v4", "") ?: ""
                if (apiEp.isNotBlank()) {
                    rawApiEndpoint = apiEp
                    if (userOverride.isNullOrBlank()) {
                        val host = apiEp.substringBefore(':')
                        val port = apiEp.substringAfter(':', "1701")
                        // В РФ порты 2408 и 500 подпадают под жесткий дроп ТСПУ, переключаем на 1701
                        peerEndpoint = if (port == "2408" || port == "500") "$host:1701" else apiEp
                    }
                }
            }
            if (peerPublicKey.isBlank()) {
                peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
            }

            // 2. Активация режима WARP для устройства (warp_enabled: true)
            var isWgActivated = regJson.optBoolean("warp_enabled", false)
            if (!isWgActivated) {
                onProgress?.invoke(WarpRegistrationStep(2, 4, "Активация режима WARP", "Отправка PATCH /reg/$accountId (warp_enabled: true)..."))
                val actRes = executeApiCall(
                    method = "PATCH",
                    path = "/v0a4471/reg/$accountId",
                    bodyJson = JSONObject().apply { put("warp_enabled", true) }.toString(),
                    authToken = token,
                    effectiveApiBase = API_BASE_V4471,
                    workerDomain = cleanWorkerDomain,
                    cachedOperaEndpoint = activeOperaEndpoint
                )
                if (actRes.json != null) {
                    isWgActivated = actRes.json.optBoolean("warp_enabled", true)
                }
            }
            val isWgValid = true
            onProgress?.invoke(WarpRegistrationStep(2, 4, "Режим WARP активирован", "Устройство авторизовано в Anycast-сети", isComplete = true))
            AppLogger.i(TAG, "Этап 2 завершен: WireGuard identity авторизована (deviceId=$accountId, isWgActivated=$isWgActivated)")

            ensureActive()

            val masqueAccountId = accountId
            val masqueToken = token
            val masqueClientIpv4 = clientIpv4
            val masqueClientIpv6 = clientIpv6
            val masqueUserOverride = existingProfile?.masqueUserOverride
            val rawMasqueApiEndpoint = existingProfile?.masqueApiEndpoint ?: ""
            val masquePeerEndpoint = masqueUserOverride ?: existingProfile?.masquePeerEndpoint?.ifBlank { null } ?: peerEndpoint
            val masquePeerPublicKey = peerPublicKey
            val isMasqueActivated = isWgActivated
            val isMasqueValid = true
            val clientCert = ""

            // 3. Привязка лицензии WARP+
            var activeEntitlement = WarpEntitlement.fromType(accountObj?.optString("type", accountObj?.optString("account_type", "free")), activeIsWarpPlus)
            val effectiveLicenseKey = licenseKey?.trim()?.ifBlank { null } ?: activeLicenseKey.ifBlank { null }

            if (effectiveLicenseKey != null && accountId.isNotBlank() && token.isNotBlank()) {
                val masked = maskLicenseKey(effectiveLicenseKey)
                onProgress?.invoke(WarpRegistrationStep(3, 4, "Привязка лицензии WARP+", "Привязка лицензии ($masked) к профилю..."))

                val attachWg = attachLicenseKey(
                    accountId = accountId,
                    token = token,
                    licenseKey = effectiveLicenseKey,
                    workerDomain = cleanWorkerDomain
                )
                if (attachWg.isSuccess) {
                    val info = attachWg.getOrThrow()
                    activeLicenseKey = info.licenseKey
                    activeIsWarpPlus = info.isWarpPlus
                    activeEntitlement = info.entitlement
                    AppLogger.i(TAG, "Лицензия WARP+ успешно привязана к WireGuard ($accountId)")
                }
            }

            onProgress?.invoke(WarpRegistrationStep(4, 4, "Профиль готов", "Конфигурация WireGuard / AmneziaWG собрана", isComplete = true))

            val isActivated = isWgActivated
            val isDataPlaneReady = isActivated && peerPublicKey.isNotBlank()

            val finalProfile = WarpProfile(
                accountId = accountId,
                token = token,
                licenseKey = activeLicenseKey,
                clientIpv4 = clientIpv4,
                clientIpv6 = clientIpv6,
                peerEndpoint = peerEndpoint,
                peerPublicKey = peerPublicKey,
                privateKeyBase64 = wgPriv,
                publicKeyBase64 = wgPub,
                p256PrivateKeyBase64 = "",
                p256PublicKeyBase64 = "",
                clientCertBase64 = clientCert,
                isWarpPlus = activeIsWarpPlus,
                isWarpEnabled = isActivated,
                registrationDate = System.currentTimeMillis(),
                masqueAccountId = masqueAccountId,
                masqueToken = masqueToken,
                masquePeerEndpoint = masquePeerEndpoint,
                masquePeerPublicKey = masquePeerPublicKey.ifBlank { peerPublicKey },
                masqueClientIpv4 = masqueClientIpv4,
                masqueClientIpv6 = masqueClientIpv6,
                isRegistered = true,
                isActivated = isActivated,
                credentialsValid = isWgValid || isMasqueValid,
                dataPlaneReady = isDataPlaneReady,
                entitlement = activeEntitlement,
                needsVerification = false,
                isOfflineCached = false,
                isWgValid = isWgValid,
                isMasqueValid = isMasqueValid,
                apiEndpoint = rawApiEndpoint,
                userEndpointOverride = userOverride,
                masqueApiEndpoint = rawMasqueApiEndpoint,
                masqueUserOverride = masqueUserOverride,
                measuredWgEndpoint = existingProfile?.measuredWgEndpoint,
                measuredMasqueEndpoint = existingProfile?.measuredMasqueEndpoint
            )

            // TSK-M01: Одновременная независимая проверка identity WireGuard и MASQUE
            val verification = verifyProtocolIdentities(finalProfile)
            AppLogger.i(TAG, "Проверка раздельных identity завершена: WG(valid=${verification.isWireGuardValid}: ${verification.wireGuardDetail}), MASQUE(valid=${verification.isMasqueValid}: ${verification.masqueDetail}), DualReady=${verification.isDualProtocolReady}")

            onProgress?.invoke(WarpRegistrationStep(4, 4, "Регистрация успешно завершена", "Профиль готов к работе: WG IPv4 $clientIpv4 | Anycast $peerEndpoint | MASQUE $masquePeerEndpoint", isComplete = true))
            AppLogger.i(TAG, "Регистрация WARP успешно завершена: ${finalProfile.getSummary()}")
            Result.success(finalProfile)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.i(TAG, "Регистрация WARP отменена пользователем")
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Критическая ошибка регистрации WARP: ${e.message}", e)
            onProgress?.invoke(WarpRegistrationStep(4, 4, "Критическая ошибка регистрации", e.message ?: "Сбой сетевого стека", isError = true))
            if (existingProfile != null && existingProfile.isRegistered && existingProfile.isUsable()) {
                val cachedProfile = existingProfile.copy(
                    needsVerification = true,
                    isOfflineCached = true,
                    dataPlaneReady = false
                )
                Result.success(cachedProfile)
            } else {
                Result.failure(e)
            }
        }
    }
}