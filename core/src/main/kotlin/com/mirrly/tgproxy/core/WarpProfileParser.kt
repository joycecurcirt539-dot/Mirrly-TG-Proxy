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
    UNKNOWN("Неизвестный формат")
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

            // Извлечение ключей
            val rawPriv = root.optString("private_key")
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
                isWarpPlus = isPlus,
                isWarpEnabled = true,
                isRegistered = true,
                isActivated = true,
                credentialsValid = true,
                dataPlaneReady = true,
                isBundledSeed = true,
                isWgValid = true,
                entitlement = if (isPlus) WarpEntitlement.PLUS else WarpEntitlement.FREE
            )

            WarpImportResult.Success(
                type = ImportedProfileType.CLOUDFLARE_JSON,
                profile = profile,
                summary = "JSON профиль • $ipv4 -> $endpoint",
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

                when (currentSection) {
                    "interface" -> {
                        when (key.lowercase()) {
                            "privatekey" -> privateKey = value
                            "address" -> addresses = value
                            "dns" -> dnsServers = value
                            "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
                            "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
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
                            "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
                            "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5" -> {
                                hasAwgParams = true
                            }
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
                awgRawConfig = iniText.trim(),
                dnsServers = dnsServers
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
            var privateKey = ""
            var peerPublicKey = ""
            var server = ""
            var port = "1701"
            var ip = "172.16.0.2"
            var ipv6 = ""

            for (rawLine in lines) {
                val line = rawLine.substringBefore("#").trim()
                if (line.isBlank()) continue
                val colonIdx = line.indexOf(':')
                if (colonIdx <= 0) continue
                val key = line.substring(0, colonIdx).trim().lowercase()
                var value = line.substring(colonIdx + 1).trim()
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length - 1)
                }

                when (key) {
                    "private-key", "private_key" -> privateKey = value
                    "public-key", "public_key" -> peerPublicKey = value
                    "server" -> server = value
                    "port" -> port = value
                    "ip" -> ip = value.substringBefore("/")
                    "ipv6" -> ipv6 = value.substringBefore("/")
                }
            }

            if (privateKey.isBlank()) {
                return WarpImportResult.Error("В YAML отсутствует private-key")
            }

            val privBytes = try {
                Base64.getDecoder().decode(privateKey)
            } catch (_: Exception) {
                return WarpImportResult.Error("Некорректная Base64-кодировка PrivateKey")
            }

            val clientPubBytes = Curve25519.computePublicKey(privBytes)
            val clientPubKeyBase64 = Base64.getEncoder().encodeToString(clientPubBytes)

            val effectiveEndpoint = if (server.isNotBlank()) "$server:$port" else DEFAULT_CF_ENDPOINT
            val effectivePeerKey = peerPublicKey.ifBlank { DEFAULT_CF_PEER_KEY }

            val profile = WarpProfile(
                accountId = "clash-yaml-" + UUID.randomUUID().toString().take(8),
                token = "clash-token-" + UUID.randomUUID().toString().take(8),
                licenseKey = "",
                clientIpv4 = ip,
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
                isWgValid = true
            )

            WarpImportResult.Success(
                type = ImportedProfileType.CLASH_META_YAML,
                profile = profile,
                summary = "Clash Meta • $ip -> $effectiveEndpoint"
            )
        } catch (e: Exception) {
            WarpImportResult.Error("Ошибка разбора YAML: ${e.message ?: "некорректный синтаксис"}")
        }
    }
}
