/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service.vpn

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Изолированный кэш DNS-ответов туннеля VPN с поддержкой TTL и инвалидации поколений сети (Task N10).
 */
class VpnDnsCache {

    data class Entry(
        val addresses: List<InetAddress>,
        val isNxDomain: Boolean,
        val expiryMs: Long,
        val networkGeneration: Long
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() > expiryMs
    }

    private val cache = ConcurrentHashMap<String, Entry>()

    private fun makeKey(type: Int, domain: String): String {
        return "$type:${domain.trim().lowercase().trimEnd('.')}"
    }

    /**
     * Поиск записи в кэше с учетом TTL и совпадения поколения физической сети.
     */
    fun get(type: Int, domain: String, currentNetworkGeneration: Long): Entry? {
        val key = makeKey(type, domain)
        val entry = cache[key] ?: return null
        if (entry.isExpired || entry.networkGeneration != currentNetworkGeneration) {
            cache.remove(key)
            return null
        }
        return entry
    }

    /**
     * Сохранение ответа DNS в кэш.
     */
    fun put(
        type: Int,
        domain: String,
        addresses: List<InetAddress>,
        ttlSeconds: Long,
        currentNetworkGeneration: Long,
        isNxDomain: Boolean = false
    ) {
        val key = makeKey(type, domain)
        val clampedTtl = ttlSeconds.coerceIn(5L, 86400L)
        val expiry = System.currentTimeMillis() + (clampedTtl * 1000L)
        cache[key] = Entry(
            addresses = addresses,
            isNxDomain = isNxDomain,
            expiryMs = expiry,
            networkGeneration = currentNetworkGeneration
        )
    }

    /**
     * Очистка кэша (при смене профиля или сбросе сети).
     */
    fun clear() {
        cache.clear()
    }

    val size: Int get() = cache.size
}
