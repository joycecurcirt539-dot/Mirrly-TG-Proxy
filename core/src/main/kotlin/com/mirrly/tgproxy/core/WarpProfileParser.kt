/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.core

import org.json.JSONObject
import java.util.Base64
import java.util.UUID

enum class ImportedProfileType(val title: String) {
    WARP_PLUS_KEY("Лицензионный ключ WARP+"),
    WIREGUARD_CONF("WireGuard (.conf)"),
    AMNEZIA_WG_CONF("AmneziaWG (.ini)"),
    CLASH_META_YAML("Clash Meta (.yaml)"),
    CLOUDFLARE_JSON("Cloudflare WARP JSON"),
    UNKNOWN("Неизвестный формат");

    val extension: String
        get() = when (this) {
            CLASH_META_YAML -> "yaml"
            CLOUDFLARE_JSON -> "json"
            AMNEZIA_WG_CONF -> "conf"
            WIREGUARD_CONF -> "conf"
            else -> "conf"
        }
}

sealed class WarpImportResult {
    data class Success(
        val type: ImportedProfileType,
        val profile: WarpProfile,
        val summary: String,
        val licenseKey: String? = null
    ) : WarpImportResult()

    data class LicenseKeyOnly(
        val licenseKey: String
    ) : WarpImportResult()

    data class Error(
        val message: String
    ) : WarpImportResult()
}

/**
 * Универсальный парсер профилей Cloudflare WARP, WireGuard и AmneziaWG.
 * Поддерживает форматы WGCF, сторонних ботов, .conf/.ini файлов, Clash Meta YAML и лицензионных ключей.
 */
object WarpProfileParser {

    private val WARP_LICENSE_KEY_REGEX = Regex("^[a-zA-Z0-9]{8}-[a-zA-Z0-9]{8}-[a-zA-Z0-9]{8}$")
    private val UUID_REGEX = Regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$")
    private const val DEFAULT_CF_PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    private const val DEFAULT_CF_ENDPOINT = "162.159.192.1:500"

    fun parse(rawInput: String): WarpImportResult {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return WarpImportResult.Error("Входные данные пусты")
        }

        // 1. Проверка на ключ лицензии WARP+
        val cleanKey = trimmed.replace("\"", "").replace("'", "").trim()
        if (WARP_LICENSE_KEY_REGEX.matches(cleanKey) || UUID_REGEX.matches(cleanKey)) {
            return WarpImportResult.LicenseKeyOnly(cleanKey)
        }

        // 2. Проверка на JSON
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return parseJson(trimmed)
        }

        // 3. Проверка на Clash Meta / Mihomo YAML
        if (trimmed.contains("type: wireguard", ignoreCase = true) ||
            trimmed.contains("warp-common:", ignoreCase = true) ||
            (trimmed.contains("private-key:", ignoreCase = true) && trimmed.contains("server:", ignoreCase = true))
        ) {
            return parseYaml(trimmed)
        }

        // 4. Проверка на WireGuard / AmneziaWG INI/CONF
        if (trimmed.contains("[Interface]", ignoreCase = true) ||
            trimmed.contains("PrivateKey", ignoreCase = true) ||
            trimmed.contains("[Peer]", ignoreCase = true)
        ) {
            return parseIniConf(trimmed)
        }

        return WarpImportResult.Error("Формат не распознан. Поддерживаются: .conf WireGuard/AmneziaWG, YAML Clash Meta, JSON WGCF и ключи WARP+")
    }

    private fun parseJson(jsonStr: String): WarpImportResult {
        return try {
            // Сначала пробуем встроенный кодек Mirrly
            val mirrlyProfile = WarpProfileCodec.decode(jsonStr)
            if (mirrlyProfile != null && mirrlyProfile.isUsable()) {
                return WarpImportResult.Success(
                    type = ImportedProfileType.CLOUDFLARE_JSON,
                    profile = mirrlyProfile,
                    summary = "Mirrly JSON • IPv4: ${mirrlyProfile.clientIpv4}",
                    licenseKey = mirrlyProfile.licenseKey.ifBlank { null }
                )
            }

            val root = JSONObject(jsonStr)

            // Извлечение ключей Curve25519
            var rawPriv = root.optString("private_key")
                .ifBlank { root.optString("privateKey") }
                .ifBlank { root.optString("key") }
                .trim()

            if (rawPriv.isBlank()) {
                return WarpImportResult.Error("JSON не содержит приватного ключа (private_key)")
            }

            val privBytes = try {
                Base64.getDecoder().decode(rawPriv)
            } catch (_: Exception) {
                return WarpImportResult.Error("Некорректная Base64-кодировка приватного ключа")
            }

            if (privBytes.size != 32) {
                return WarpImportResult.Error("Приватный ключ Curve25519 должен содержать ровно 32 байта")
            }

            // Публичный ключ: из JSON или деривация Curve25519
            var pubBase64 = root.optString("public_key")
                .ifBlank { root.optString("publicKey") }
                .trim()

            if (pubBase64.isBlank()) {
                val computedPubBytes = Curve25519.computePublicKey(privBytes)
                pubBase64 = Base64.getEncoder().encodeToString(computedPubBytes)
            }

            // Извлечение адресов (поддержка WGCF address {v4, v6} и плоской строки address)
            var ipv4 = "172.16.0.2"
            var ipv6 = ""

            if (root.has("address") && root.get("address") is JSONObject) {
                val addrObj = root.getJSONObject("address")
                ipv4 = addrObj.optString("v4", "172.16.0.2")
                ipv6 = addrObj.optString("v6", "")
            } else {
                val addrStr = root.optString("address")
                    .ifBlank { root.optString("client_ip") }
                    .ifBlank { root.optString("clientIpv4") }

                if (addrStr.isNotBlank()) {
                    val parts = addrStr.split(",").map { it.trim() }
                    for (part in parts) {
                        val clean = part.substringBefore("/")
                        if (clean.contains(":") && ipv6.isBlank()) {
                            ipv6 = clean
                        } else if (clean.contains(".") && ipv4 == "172.16.0.2") {
                            ipv4 = clean
                        }
                    }
                }
            }

            // Извлечение пира и эндпоинта
            var peerPub = root.optString("peer_public_key")
                .ifBlank { root.optString("peerPublicKey") }
                .trim()

            var endpoint = root.optString("endpoint")
                .ifBlank { root.optString("peerEndpoint") }
                .trim()

            // Проверка WGCF структуры: config.peers[0]
            if (root.has("config")) {
                val cfg = root.optJSONObject("config")
                val peers = cfg?.optJSONArray("peers")
                if (peers != null && peers.length() > 0) {
                    val firstPeer = peers.getJSONObject(0)
                    if (peerPub.isBlank()) {
                        peerPub = firstPeer.optString("public_key", "")
                    }
                    if (endpoint.isBlank() && firstPeer.has("endpoint")) {
                        val epObj = firstPeer.optJSONObject("endpoint")
                        if (epObj != null) {
                            endpoint = epObj.optString("host").ifBlank { epObj.optString("v4", "") }
                        } else {
                            endpoint = firstPeer.optString("endpoint", "")
                        }
                    }
                }
            }

            if (peerPub.isBlank()) peerPub = DEFAULT_CF_PEER_KEY
            if (endpoint.isBlank()) endpoint = DEFAULT_CF_ENDPOINT

            val accountId = root.optString("account_id")
                .ifBlank { root.optString("accountId") }
                .ifBlank { root.optString("id") }
                .ifBlank { "custom-json-" + UUID.randomUUID().toString().take(8) }

            val token = root.optString("access_token")
                .ifBlank { root.optString("token") }
                .ifBlank { "custom-token-" + UUID.randomUUID().toString().take(8) }

            val licenseKey = root.optString("license_key")
                .ifBlank { root.optString("licenseKey") }
                .trim()

            val isPlus = root.optBoolean("isWarpPlus", false) ||
                licenseKey.isNotBlank() ||
                root.optString("account_type").contains("plus", ignoreCase = true)

            val jsonArr = root.optJSONArray("client_id")
                ?: root.optJSONArray("reserved")
                ?: root.optJSONObject("config")?.optJSONArray("client_id")
                ?: root.optJSONObject("config")?.optJSONArray("reserved")
                ?: root.optJSONObject("config")?.optJSONObject("interface")?.optJSONArray("client_id")
                ?: root.optJSONObject("config")?.optJSONObject("interface")?.optJSONArray("reserved")

            var rawClientId = if (jsonArr != null && jsonArr.length() == 3) {
                "${jsonArr.optInt(0)}, ${jsonArr.optInt(1)}, ${jsonArr.optInt(2)}"
            } else {
                (root.optString("clientId", "")
                    .ifBlank { root.optString("client_id", "") }
                    .ifBlank { root.optString("reserved", "") }
                    .ifBlank { root.optJSONObject("config")?.optString("client_id", "") ?: "" }
                    .ifBlank { root.optJSONObject("config")?.optString("reserved", "") ?: "" }
                    .ifBlank { root.optJSONObject("config")?.optJSONObject("interface")?.optString("client_id", "") ?: "" }
                    .ifBlank { root.optJSONObject("config")?.optJSONObject("interface")?.optString("reserved", "") ?: "" })
                    .trim()
            }

            val cleanRaw = rawClientId.removePrefix("[").removeSuffix("]").trim()
            val clientId = formatClientIdToReserved(cleanRaw).ifBlank { cleanRaw }

            val profile = WarpProfile(
                accountId = accountId,
                token = token,
                licenseKey = licenseKey,
                clientIpv4 = ipv4.substringBefore("/"),
                clientIpv6 = ipv6.substringBefore("/"),
                peerEndpoint = endpoint,
                peerPublicKey = peerPub,
                privateKeyBase64 = rawPriv,
                publicKeyBase64 = pubBase64,
                p256PrivateKeyBase64 = "",
                p256PublicKeyBase64 = "",
                clientCertBase64 = "",
                isWarpPlus = isPlus,
                isWarpEnabled = true,
                isRegistered = true,
                isActivated = true,
                credentialsValid = true,
                dataPlaneReady = true,
                isBundledSeed = true,
                isWgValid = true,
                entitlement = if (isPlus) WarpEntitlement.PLUS else WarpEntitlement.FREE,
                clientId = clientId
            )

            WarpImportResult.Success(
                type = ImportedProfileType.CLOUDFLARE_JSON,
                profile = profile,
                summary = "Cloudflare JSON • $ipv4 -> $endpoint",
                licenseKey = licenseKey.ifBlank { null }
            )
        } catch (e: Exception) {
            WarpImportResult.Error("Ошибка парсинга JSON: ${e.message ?: "некорректная структура"}")
        }
    }

    private fun parseIniConf(iniText: String): WarpImportResult {
        return try {
            val lines = iniText.lines()
            var currentSection = ""

            var privateKey = ""
            var addresses = ""
            var peerPublicKey = ""
            var endpoint = ""
            var dnsServers = ""
            var clientId = ""
            var i1 = ""
            var i2 = ""
            var i3 = ""
            var i4 = ""
            var i5 = ""
            var jc: Int? = null
            var jmin: Int? = null
            var jmax: Int? = null
            var s1: Int? = null
            var s2: Int? = null
            var s3: Int? = null
            var s4: Int? = null
            var h1: Long? = null
            var h2: Long? = null
            var h3: Long? = null
            var h4: Long? = null

            var hasAwgParams = false

            for (rawLine in lines) {
                val line = rawLine.substringBefore("#").substringBefore(";").trim()
                if (line.isBlank()) continue

                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length - 1).trim().lowercase()
                    continue
                }

                val eqIdx = line.indexOf('=')
                if (eqIdx <= 0) continue

                val key = line.substring(0, eqIdx).trim()
                val value = line.substring(eqIdx + 1).trim()
                val cleanValue = value.removePrefix("\"").removeSuffix("\"").removePrefix("'").removeSuffix("'").trim()

                when (currentSection) {
                    "interface" -> {
                        when (key.lowercase()) {
                            "privatekey" -> privateKey = value
                            "address" -> addresses = value
                            "dns" -> dnsServers = value
                            "reserved", "clientid", "client_id" -> {
                                val clean = cleanValue.removePrefix("[").removeSuffix("]").trim()
                                clientId = formatClientIdToReserved(clean).ifBlank { clean }
                                hasAwgParams = true
                            }
                            "i1" -> {
                                i1 = value
                                hasAwgParams = true
                            }
                            "i2" -> {
                                i2 = value
                                hasAwgParams = true
                            }
                            "i3" -> {
                                i3 = value
                                hasAwgParams = true
                            }
                            "i4" -> {
                                i4 = value
                                hasAwgParams = true
                            }
                            "i5" -> {
                                i5 = value
                                hasAwgParams = true
                            }
                            "jc" -> { jc = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "jmin" -> { jmin = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "jmax" -> { jmax = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "s1" -> { s1 = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "s2" -> { s2 = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "s3" -> { s3 = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "s4" -> { s4 = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "h1" -> { h1 = cleanValue.toLongOrNull(); hasAwgParams = true }
                            "h2" -> { h2 = cleanValue.toLongOrNull(); hasAwgParams = true }
                            "h3" -> { h3 = cleanValue.toLongOrNull(); hasAwgParams = true }
                            "h4" -> { h4 = cleanValue.toLongOrNull(); hasAwgParams = true }
                            "contentpaddingaddition", "rekeyaftertime", "rekeytimeout",
                            "rejectaftertime", "keepalivetimeout", "maxhandshakeattempts",
                            "disablecookies", "randomtrailers" -> {
                                hasAwgParams = true
                            }
                        }
                    }
                    "peer" -> {
                        when (key.lowercase()) {
                            "publickey" -> peerPublicKey = value
                            "endpoint" -> endpoint = value
                            "reserved", "clientid", "client_id" -> {
                                if (clientId.isBlank()) {
                                    clientId = formatClientIdToReserved(cleanValue).ifBlank { cleanValue }
                                }
                                hasAwgParams = true
                            }
                            "i1" -> {
                                if (i1.isBlank()) i1 = value
                                hasAwgParams = true
                            }
                            "i2" -> {
                                if (i2.isBlank()) i2 = value
                                hasAwgParams = true
                            }
                            "i3" -> {
                                if (i3.isBlank()) i3 = value
                                hasAwgParams = true
                            }
                            "i4" -> {
                                if (i4.isBlank()) i4 = value
                                hasAwgParams = true
                            }
                            "i5" -> {
                                if (i5.isBlank()) i5 = value
                                hasAwgParams = true
                            }
                            "jc" -> { if (jc == null) jc = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "jmin" -> { if (jmin == null) jmin = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "jmax" -> { if (jmax == null) jmax = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "s1" -> { if (s1 == null) s1 = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "s2" -> { if (s2 == null) s2 = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "s3" -> { if (s3 == null) s3 = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "s4" -> { if (s4 == null) s4 = cleanValue.toIntOrNull(); hasAwgParams = true }
                            "h1" -> { if (h1 == null) h1 = cleanValue.toLongOrNull(); hasAwgParams = true }
                            "h2" -> { if (h2 == null) h2 = cleanValue.toLongOrNull(); hasAwgParams = true }
                            "h3" -> { if (h3 == null) h3 = cleanValue.toLongOrNull(); hasAwgParams = true }
                            "h4" -> { if (h4 == null) h4 = cleanValue.toLongOrNull(); hasAwgParams = true }
                        }
                    }
                }
            }

            if (privateKey.isBlank()) {
                return WarpImportResult.Error("В секции [Interface] отсутствует PrivateKey")
            }

            val privBytes = try {
                Base64.getDecoder().decode(privateKey)
            } catch (_: Exception) {
                return WarpImportResult.Error("Некорректная Base64-кодировка PrivateKey")
            }

            if (privBytes.size != 32) {
                return WarpImportResult.Error("PrivateKey Curve25519 должен содержать ровно 32 байта")
            }

            // Деривация публичного ключа клиента
            val clientPubBytes = Curve25519.computePublicKey(privBytes)
            val clientPubKeyBase64 = Base64.getEncoder().encodeToString(clientPubBytes)

            // Разбор адресов
            var ipv4 = "172.16.0.2"
            var ipv6 = ""
            if (addresses.isNotBlank()) {
                val split = addresses.split(",").map { it.trim() }
                for (item in split) {
                    val clean = item.substringBefore("/")
                    if (clean.contains(":") && ipv6.isBlank()) {
                        ipv6 = clean
                    } else if (clean.contains(".") && ipv4 == "172.16.0.2") {
                        ipv4 = clean
                    }
                }
            }

            val effectivePeerKey = peerPublicKey.ifBlank { DEFAULT_CF_PEER_KEY }
            val effectiveEndpoint = endpoint.ifBlank { DEFAULT_CF_ENDPOINT }

            val isCf = isCloudflareEndpoint(effectiveEndpoint) || effectivePeerKey == DEFAULT_CF_PEER_KEY
            val hasIncompatibleHeaders = (s1 ?: 0) != 0 || (s2 ?: 0) != 0 || (s3 ?: 0) != 0 || (s4 ?: 0) != 0 ||
                (h1 != null && h1 != 1L) || (h2 != null && h2 != 2L) || (h3 != null && h3 != 3L) || (h4 != null && h4 != 4L)

            val finalJc = if (isCf && hasIncompatibleHeaders) 0 else (jc ?: 0)
            val finalJmin = if (isCf && hasIncompatibleHeaders) 0 else (jmin ?: 0)
            val finalJmax = if (isCf && hasIncompatibleHeaders) 0 else (jmax ?: 0)
            val finalS1 = if (isCf) 0 else (s1 ?: 0)
            val finalS2 = if (isCf) 0 else (s2 ?: 0)
            val finalS3 = if (isCf) 0 else (s3 ?: 0)
            val finalS4 = if (isCf) 0 else (s4 ?: 0)
            val finalH1 = if (isCf) 1L else (h1 ?: 1L)
            val finalH2 = if (isCf) 2L else (h2 ?: 2L)
            val finalH3 = if (isCf) 3L else (h3 ?: 3L)
            val finalH4 = if (isCf) 4L else (h4 ?: 4L)
            val finalRawConfig = if (isCf && hasIncompatibleHeaders) sanitizeAwgConfigForCloudflare(iniText) else iniText.trim()

            val profileType = if (hasAwgParams) ImportedProfileType.AMNEZIA_WG_CONF else ImportedProfileType.WIREGUARD_CONF
            val accountId = "custom-conf-" + UUID.randomUUID().toString().take(8)
            val token = "custom-conf-token-" + UUID.randomUUID().toString().take(8)

            val profile = WarpProfile(
                accountId = accountId,
                token = token,
                licenseKey = "",
                clientIpv4 = ipv4,
                clientIpv6 = ipv6,
                peerEndpoint = effectiveEndpoint,
                peerPublicKey = effectivePeerKey,
                privateKeyBase64 = privateKey,
                publicKeyBase64 = clientPubKeyBase64,
                isWarpPlus = false,
                isWarpEnabled = true,
                isRegistered = true,
                isActivated = true,
                credentialsValid = true,
                dataPlaneReady = true,
                isBundledSeed = true,
                isWgValid = true,
                awgRawConfig = finalRawConfig,
                dnsServers = dnsServers,
                clientId = clientId,
                awgI1 = i1,
                awgI2 = i2,
                awgI3 = i3,
                awgI4 = i4,
                awgI5 = i5,
                awgJc = finalJc,
                awgJmin = finalJmin,
                awgJmax = finalJmax,
                awgS1 = finalS1,
                awgS2 = finalS2,
                awgS3 = finalS3,
                awgS4 = finalS4,
                awgH1 = finalH1,
                awgH2 = finalH2,
                awgH3 = finalH3,
                awgH4 = finalH4
            )

            val summaryPrefix = if (hasAwgParams) "AmneziaWG (AWG)" else "WireGuard"
            WarpImportResult.Success(
                type = profileType,
                profile = profile,
                summary = "$summaryPrefix • $ipv4 -> $effectiveEndpoint"
            )
        } catch (e: Exception) {
            WarpImportResult.Error("Ошибка разбора .conf: ${e.message ?: "некорректный синтаксис"}")
        }
    }

    private fun parseYaml(yamlText: String): WarpImportResult {
        return try {
            val lines = yamlText.lines()
            var currentSection = ""

            // WireGuard / Warp-common реквизиты
            var privateKey = ""
            var peerPublicKey = ""
            var server = ""
            var port = "1701"
            var ip = "172.16.0.2"
            var ipv6 = ""
            var clientId = ""

            // AmneziaWG опции
            var jc: Int? = null
            var jmin: Int? = null
            var jmax: Int? = null
            var s1: Int? = null
            var s2: Int? = null
            var h1: Long? = null
            var h2: Long? = null
            var h3: Long? = null
            var h4: Long? = null
            var i1 = ""
            var i2 = ""

            // Списки обнаруженных серверов
            val originServers = mutableListOf<String>()

            var currentProxyName = ""
            var currentProxyServer = ""
            var currentProxyPort = ""
            var currentProxyType = ""

            fun flushCurrentProxy() {
                if (currentProxyServer.isNotBlank()) {
                    val ep = "$currentProxyServer:${currentProxyPort.ifBlank { "4500" }}"
                    originServers.add(ep)
                }
                currentProxyName = ""
                currentProxyServer = ""
                currentProxyPort = ""
                currentProxyType = ""
            }

            for (rawLine in lines) {
                val trimmedLine = rawLine.trim()
                if (trimmedLine.startsWith("#") || trimmedLine.isBlank()) continue

                // Проверка на anchors типа i1-default: &i1-default <b 0x...>
                if (trimmedLine.startsWith("i1") && trimmedLine.contains("<b 0x") && i1.isBlank()) {
                    val hexStart = trimmedLine.indexOf("<b 0x")
                    if (hexStart >= 0) {
                        i1 = trimmedLine.substring(hexStart).trim()
                    }
                }
                if (trimmedLine.startsWith("i2") && trimmedLine.contains("<b 0x") && i2.isBlank()) {
                    val hexStart = trimmedLine.indexOf("<b 0x")
                    if (hexStart >= 0) {
                        i2 = trimmedLine.substring(hexStart).trim()
                    }
                }

                // Определение верхнеуровневых секций
                if (!rawLine.startsWith(" ") && !rawLine.startsWith("\t") && trimmedLine.endsWith(":")) {
                    flushCurrentProxy()
                    val secName = trimmedLine.removeSuffix(":").substringBefore("&").trim().lowercase()
                    currentSection = secName
                    continue
                }

                // Элементы списка в proxies:
                if (currentSection == "proxies" && trimmedLine.startsWith("- name:")) {
                    flushCurrentProxy()
                    currentProxyName = trimmedLine.substringAfter("- name:").trim().removePrefix("\"").removeSuffix("\"").removePrefix("'").removeSuffix("'")
                    continue
                }

                val colonIdx = trimmedLine.indexOf(':')
                if (colonIdx <= 0) continue

                val key = trimmedLine.substring(0, colonIdx).trim().lowercase()
                var value = trimmedLine.substring(colonIdx + 1).trim()
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length - 1)
                }

                when (currentSection) {
                    "amnezia-common", "amnezia-base", "amnezia-wg-option" -> {
                        when (key) {
                            "jc" -> jc = value.toIntOrNull()
                            "jmin" -> jmin = value.toIntOrNull()
                            "jmax" -> jmax = value.toIntOrNull()
                            "s1" -> s1 = value.toIntOrNull()
                            "s2" -> s2 = value.toIntOrNull()
                            "h1" -> h1 = value.toLongOrNull()
                            "h2" -> h2 = value.toLongOrNull()
                            "h3" -> h3 = value.toLongOrNull()
                            "h4" -> h4 = value.toLongOrNull()
                        }
                    }
                    "warp-common" -> {
                        when (key) {
                            "private-key", "private_key" -> privateKey = value
                            "public-key", "public_key" -> peerPublicKey = value
                            "ip" -> ip = value.substringBefore("/")
                            "ipv6" -> ipv6 = value.substringBefore("/")
                            "reserved", "client-id", "client_id", "clientid" -> {
                                val clean = value.removePrefix("[").removeSuffix("]").trim()
                                clientId = formatClientIdToReserved(clean).ifBlank { clean }
                            }
                            "jc" -> jc = value.toIntOrNull()
                            "jmin" -> jmin = value.toIntOrNull()
                            "jmax" -> jmax = value.toIntOrNull()
                            "s1" -> s1 = value.toIntOrNull()
                            "s2" -> s2 = value.toIntOrNull()
                            "h1" -> h1 = value.toLongOrNull()
                            "h2" -> h2 = value.toLongOrNull()
                            "h3" -> h3 = value.toLongOrNull()
                            "h4" -> h4 = value.toLongOrNull()
                        }
                    }
                    "proxies" -> {
                        when (key) {
                            "server" -> currentProxyServer = value
                            "port" -> currentProxyPort = value
                            "type" -> currentProxyType = value
                        }
                    }
                    else -> {
                        // Плоский fallback для простых файлов
                        when (key) {
                            "private-key", "private_key" -> if (privateKey.isBlank()) privateKey = value
                            "public-key", "public_key" -> if (peerPublicKey.isBlank()) peerPublicKey = value
                            "server" -> if (server.isBlank()) server = value
                            "port" -> if (port == "1701") port = value
                            "ip" -> if (ip == "172.16.0.2") ip = value.substringBefore("/")
                            "ipv6" -> if (ipv6.isBlank()) ipv6 = value.substringBefore("/")
                            "reserved", "client-id", "client_id", "clientid" -> {
                                if (clientId.isBlank()) {
                                    val clean = value.removePrefix("[").removeSuffix("]").trim()
                                    clientId = formatClientIdToReserved(clean).ifBlank { clean }
                                }
                            }
                        }
                    }
                }
            }
            flushCurrentProxy()

            // Если приватный ключ не найден в warp-common, используем дефолтный
            if (privateKey.isBlank()) {
                privateKey = "GNwwhRNXM6Ef5ZIFYqJH8aU2Mqt8tHnRPcJp1Lb93G4="
            }

            val privBytes = try {
                Base64.getDecoder().decode(privateKey)
            } catch (_: Exception) {
                return WarpImportResult.Error("Некорректная Base64-кодировка PrivateKey в YAML")
            }

            val clientPubBytes = Curve25519.computePublicKey(privBytes)
            val clientPubKeyBase64 = Base64.getEncoder().encodeToString(clientPubBytes)

            // Разрешение эндпоинта: приоритет Origin или первый найденный сервер
            val resolvedOriginEp = originServers.find { it.contains("engage.cloudflareclient.com") || it.contains("162.159.192.1") }
                ?: originServers.firstOrNull()
                ?: if (server.isNotBlank()) "$server:$port" else "162.159.192.1:500"

            val effectivePeerKey = peerPublicKey.ifBlank { DEFAULT_CF_PEER_KEY }

            val profile = WarpProfile(
                accountId = "clash-yaml-" + UUID.randomUUID().toString().take(8),
                token = "clash-token-" + UUID.randomUUID().toString().take(8),
                licenseKey = "",
                clientIpv4 = ip,
                clientIpv6 = ipv6,
                peerEndpoint = resolvedOriginEp,
                peerPublicKey = effectivePeerKey,
                privateKeyBase64 = privateKey,
                publicKeyBase64 = clientPubKeyBase64,
                p256PrivateKeyBase64 = "",
                p256PublicKeyBase64 = "",
                clientCertBase64 = "",
                isWarpPlus = false,
                isWarpEnabled = true,
                isRegistered = true,
                isActivated = true,
                credentialsValid = true,
                dataPlaneReady = true,
                isBundledSeed = true,
                isWgValid = true,
                awgRawConfig = yamlText,
                clientId = clientId.ifBlank { "136, 33, 12" },
                awgJc = jc ?: 4,
                awgJmin = jmin ?: 40,
                awgJmax = jmax ?: 70,
                awgS1 = s1 ?: 0,
                awgS2 = s2 ?: 0,
                awgH1 = h1 ?: 1L,
                awgH2 = h2 ?: 2L,
                awgH3 = h3 ?: 3L,
                awgH4 = h4 ?: 4L,
                awgI1 = i1,
                awgI2 = i2
            )

            WarpImportResult.Success(
                type = ImportedProfileType.CLASH_META_YAML,
                profile = profile,
                summary = "Clash YAML • $ip -> $resolvedOriginEp (AWG)"
            )
        } catch (e: Exception) {
            WarpImportResult.Error("Ошибка разбора YAML: ${e.message ?: "некорректный синтаксис"}")
        }
    }
}
