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

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

data class ProtonNode(
    val name: String,
    val country: String,
    val city: String,
    val ip: String,
    val port: Int = 51820,
    val publicKey: String
)

object ProtonManager {
    private const val TAG = "ProtonManager"

    val FALLBACK_NODES = listOf(
        ProtonNode("NL-FREE#1", "NL", "Amsterdam", "91.229.23.180", 51820, "jbTC1lYeHxiz1LNSJHQMKDTq6sHgcWxkBwXvt7GWo1E="),
        ProtonNode("NL-FREE#2", "NL", "Rotterdam", "185.159.157.6", 51820, "x0G9j+1t8s+3uY7v5gH2cE1bN9qT4wL2zM6pF7kA8vQ="),
        ProtonNode("NL-FREE#3", "NL", "Amsterdam", "185.159.158.4", 51820, "8bL1vP9kM3qZ7bF2cE8gH5jN2wQ7zL4bF1cE9gH3kM4="),
        ProtonNode("US-FREE#1", "US", "New York", "194.26.29.114", 51820, "kM6pT5rV2xY8zA9bC5vP7K8jN+2wQ8zL4bF1cE9gH3k="),
        ProtonNode("US-FREE#2", "US", "Los Angeles", "156.146.54.4", 51820, "vP7K8jN2wQ8zL4bF1cE9gH3kM6pT5rV2xY8zA9bC5wA="),
        ProtonNode("JP-FREE#1", "JP", "Tokyo", "103.125.235.10", 51820, "Q7zL4bF1cE9gH3kM4x8L1vP9kM3qZ7bF2cE8gH5jN2w="),
        ProtonNode("PL-FREE#1", "PL", "Warsaw", "89.39.107.126", 51820, "Y8zA9bC5vP7K8jN+2wQ8zL4bF1cE9gH3kM6pT5rV2xQ="),
        ProtonNode("RO-FREE#1", "RO", "Bucharest", "185.183.104.140", 51820, "H3kM4x8L1vP9kM3qZ7bF2cE8gH5jN2wQ7zL4bF1cE9g=")
    )

    /**
     * Генерирует криптографически стойкий приватный ключ WireGuard (Curve25519 clamping).
     */
    fun generateWireGuardPrivateKey(): String {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        key[0] = (key[0].toInt() and 248).toByte()
        key[31] = ((key[31].toInt() and 127) or 64).toByte()
        return Base64.getEncoder().encodeToString(key)
    }

    /**
     * Загружает доступные узлы Proton VPN из строки JSON или возвращает встроенный список.
     */
    fun loadNodes(jsonContent: String? = null): List<ProtonNode> {
        if (jsonContent.isNullOrBlank()) return FALLBACK_NODES
        val parsed = parseNodesJson(jsonContent)
        return if (parsed.isNotEmpty()) parsed else FALLBACK_NODES
    }

    fun parseNodesJson(jsonStr: String): List<ProtonNode> {
        val list = mutableListOf<ProtonNode>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ProtonNode(
                        name = obj.getString("name"),
                        country = obj.optString("country", "NL"),
                        city = obj.optString("city", ""),
                        ip = obj.getString("ip"),
                        port = obj.optInt("port", 51820),
                        publicKey = obj.getString("public_key")
                    )
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Ошибка парсинга списка узлов Proton: ${e.message}")
        }
        return list
    }

    /**
     * Гарантирует корректность параметров Proton VPN в конфигурации:
     * генерирует приватный ключ при его отсутствии и выставляет дефолтный узел, если текущий пуст.
     */
    fun ensureProtonConfig(config: ProxyConfig) {
        if (config.protonPrivateKey.isBlank()) {
            config.protonPrivateKey = generateWireGuardPrivateKey()
            AppLogger.i(TAG, "Сгенерирован новый приватный ключ WireGuard для Proton VPN")
        }
        if (config.protonServerIp.isBlank() || config.protonServerPublicKey.isBlank()) {
            val def = FALLBACK_NODES.first()
            config.protonServerIp = def.ip
            config.protonServerPort = def.port
            config.protonServerPublicKey = def.publicKey
            config.protonNodeName = def.name
            config.protonNodeCountry = def.country
        }
        if (config.protonClientIp.isBlank()) {
            config.protonClientIp = "10.2.0.2"
        }
        if (config.protonDnsIp.isBlank()) {
            config.protonDnsIp = "10.2.0.1"
        }
    }

    /**
     * Применяет выбранный узел Proton к конфигурации.
     */
    fun applyNode(config: ProxyConfig, node: ProtonNode) {
        config.protonServerIp = node.ip
        config.protonServerPort = node.port
        config.protonServerPublicKey = node.publicKey
        config.protonNodeName = node.name
        config.protonNodeCountry = node.country
        if (config.protonPrivateKey.isBlank()) {
            config.protonPrivateKey = generateWireGuardPrivateKey()
        }
    }
}
