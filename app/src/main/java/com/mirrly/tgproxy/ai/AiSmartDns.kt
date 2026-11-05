/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ai

import com.mirrly.tgproxy.core.AppLogger
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Интеллектуальный SmartDNS резолвер для обхода региональных геоблокировок ИИ-сервисов
 * (Google Gemini, OpenAI ChatGPT, Anthropic Claude, OpenRouter) без использования стороннего VPN.
 *
 * Поддерживаемые провайдеры:
 * 1. GeoHide DNS (по умолчанию, приоритетный SNI-прокси узел)
 * 2. DNS-AI (https://dns.dns-ai.ru/dns-query)
 * 3. Comss.one (резервный узел)
 * 4. Авто-каскад (GeoHide -> DNS-AI -> Comss.one)
 */
object AiSmartDns {

    private const val TAG = "AiSmartDns"

    const val PROVIDER_GEOHIDE = "geohide"
    const val PROVIDER_DNS_AI = "dns_ai"
    const val PROVIDER_COMSS = "comss"
    const val PROVIDER_AUTO = "auto"

    // ── Bootstrap IP-адреса для исключения зависимости от системного DNS ──
    val GEOHIDE_BOOTSTRAP_IPS: List<InetAddress> by lazy {
        listOf(
            "193.233.112.88",
            "159.194.200.33",
            "37.230.192.51",
            "193.233.112.68",
            "193.233.112.67"
        ).mapNotNull { ip -> runCatching { InetAddress.getByName(ip) }.getOrNull() }
    }

    val DNS_AI_BOOTSTRAP_IPS: List<InetAddress> by lazy {
        listOf(
            "45.151.102.219",
            "194.87.196.13"
        ).mapNotNull { ip -> runCatching { InetAddress.getByName(ip) }.getOrNull() }
    }

    val COMSS_BOOTSTRAP_IPS: List<InetAddress> by lazy {
        listOf(
            "195.133.25.16",
            "212.109.195.93",
            "185.146.157.78",
            "212.109.221.20"
        ).mapNotNull { ip -> runCatching { InetAddress.getByName(ip) }.getOrNull() }
    }

    private val AI_DOMAIN_SUFFIXES = listOf(
        "generativelanguage.googleapis.com",
        "googleapis.com",
        "google.dev",
        "alkalimakersuite-pa.clients6.google.com",
        "api.openai.com",
        "chatgpt.com",
        "openai.com",
        "anthropic.com",
        "claude.ai",
        "openrouter.ai"
    )

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // 1. Приоритетный узел: GeoHide DoH
    val geohideDoh: Dns by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://geohide.ru/dns-query".toHttpUrl())
            .bootstrapDnsHosts(GEOHIDE_BOOTSTRAP_IPS)
            .includeIPv6(false)
            .build()
    }

    // 2. Резервный узел 1: DNS-AI DoH
    val dnsAiDoh: Dns by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.dns-ai.ru/dns-query".toHttpUrl())
            .bootstrapDnsHosts(DNS_AI_BOOTSTRAP_IPS)
            .includeIPv6(false)
            .build()
    }

    // 3. Резервный узел 2: Comss.one DoH
    val comssDoh: Dns by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.comss.one/dns-query".toHttpUrl())
            .bootstrapDnsHosts(COMSS_BOOTSTRAP_IPS)
            .includeIPv6(false)
            .build()
    }

    /**
     * Прямой UDP DNS резолвер для GeoHide (порт 53) на случай блокировки DoH на уровне ТСПУ.
     */
    fun resolveGeoHideUdp(hostname: String, timeoutMs: Int = 3000): List<InetAddress> {
        for (ip in GEOHIDE_BOOTSTRAP_IPS) {
            try {
                val results = queryUdpDns(hostname, ip, 53, timeoutMs)
                if (results.isNotEmpty()) {
                    return results
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "GeoHide UDP ${ip.hostAddress} failure: ${e.message}")
            }
        }
        return emptyList()
    }

    /**
     * Отправляет бинарный UDP DNS-пакет RFC 1035 и парсит IP-адреса из секции Answer.
     */
    fun queryUdpDns(hostname: String, serverIp: InetAddress, port: Int = 53, timeoutMs: Int = 3000): List<InetAddress> {
        val socket = DatagramSocket()
        socket.soTimeout = timeoutMs
        return try {
            val queryBytes = buildDnsQueryPacket(hostname)
            val packet = DatagramPacket(queryBytes, queryBytes.size, serverIp, port)
            socket.send(packet)

            val buffer = ByteArray(1024)
            val receivePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(receivePacket)

            parseDnsResponsePacket(receivePacket.data, receivePacket.length)
        } finally {
            socket.close()
        }
    }

    private fun buildDnsQueryPacket(hostname: String): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        val id = (Math.random() * 65535).toInt()
        dos.writeShort(id)
        dos.writeShort(0x0100) // Standard query with recursion desired
        dos.writeShort(1) // QDCOUNT = 1
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(0)

        // QNAME
        for (part in hostname.split('.')) {
            val bytes = part.toByteArray(Charsets.US_ASCII)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0)

        dos.writeShort(1) // QTYPE = A (IPv4)
        dos.writeShort(1) // QCLASS = IN
        return bos.toByteArray()
    }

    private fun parseDnsResponsePacket(data: ByteArray, length: Int): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        if (length < 12) return addresses
        val buffer = ByteBuffer.wrap(data, 0, length)
        buffer.short // ID
        buffer.short // Flags
        val qdcount = buffer.short.toInt() and 0xFFFF
        val ancount = buffer.short.toInt() and 0xFFFF

        for (i in 0 until qdcount) {
            skipDnsName(buffer)
            buffer.short // qtype
            buffer.short // qclass
        }

        for (i in 0 until ancount) {
            skipDnsName(buffer)
            val type = buffer.short.toInt() and 0xFFFF
            buffer.short // clazz
            buffer.int // ttl
            val rdLength = buffer.short.toInt() and 0xFFFF
            if (type == 1 && rdLength == 4 && buffer.remaining() >= 4) {
                val ipBytes = ByteArray(4)
                buffer.get(ipBytes)
                runCatching { InetAddress.getByAddress(ipBytes) }.getOrNull()?.let {
                    addresses.add(it)
                }
            } else if (buffer.remaining() >= rdLength) {
                buffer.position(buffer.position() + rdLength)
            }
        }
        return addresses
    }

    private fun skipDnsName(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val len = buffer.get().toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                if (buffer.hasRemaining()) buffer.get()
                break
            } else {
                if (buffer.remaining() >= len) {
                    buffer.position(buffer.position() + len)
                } else {
                    break
                }
            }
        }
    }

    private data class CachedDnsEntry(
        val addresses: List<InetAddress>,
        val expiresAt: Long
    )

    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, CachedDnsEntry>()
    private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 минут

    /**
     * Принудительно очищает in-memory кэш SmartDNS.
     */
    fun clearCache(): Int {
        val count = dnsCache.size
        dnsCache.clear()
        AppLogger.i(TAG, "Кэш SmartDNS принудительно очищен ($count записей)")
        return count
    }

    fun getCacheSize(): Int = dnsCache.size

    /**
     * Разрешает домен с учетом приоритета выбранного провайдера и автоматического фейловера.
     */
    fun resolveWithProvider(provider: String, hostname: String): List<InetAddress> {
        val cacheKey = "$provider:$hostname".lowercase()
        val now = System.currentTimeMillis()

        dnsCache[cacheKey]?.let { cached ->
            if (now < cached.expiresAt && cached.addresses.isNotEmpty()) {
                AppLogger.d(TAG, "SmartDNS (Кэш) $hostname -> ${cached.addresses.firstOrNull()?.hostAddress}")
                return cached.addresses
            }
        }

        val plan = when (provider) {
            PROVIDER_GEOHIDE -> listOf(
                "GeoHide DoH" to { geohideDoh.lookup(hostname) },
                "GeoHide UDP" to { resolveGeoHideUdp(hostname) },
                "DNS-AI DoH" to { dnsAiDoh.lookup(hostname) },
                "Comss.one DoH" to { comssDoh.lookup(hostname) }
            )
            PROVIDER_DNS_AI -> listOf(
                "DNS-AI DoH" to { dnsAiDoh.lookup(hostname) },
                "GeoHide DoH" to { geohideDoh.lookup(hostname) },
                "GeoHide UDP" to { resolveGeoHideUdp(hostname) },
                "Comss.one DoH" to { comssDoh.lookup(hostname) }
            )
            PROVIDER_COMSS -> listOf(
                "Comss.one DoH" to { comssDoh.lookup(hostname) },
                "GeoHide DoH" to { geohideDoh.lookup(hostname) },
                "GeoHide UDP" to { resolveGeoHideUdp(hostname) },
                "DNS-AI DoH" to { dnsAiDoh.lookup(hostname) }
            )
            else -> listOf(
                // Auto / Cascade default: GeoHide -> DNS-AI -> Comss.one
                "GeoHide DoH" to { geohideDoh.lookup(hostname) },
                "GeoHide UDP" to { resolveGeoHideUdp(hostname) },
                "DNS-AI DoH" to { dnsAiDoh.lookup(hostname) },
                "Comss.one DoH" to { comssDoh.lookup(hostname) }
            )
        }

        for ((name, resolver) in plan) {
            try {
                val addrs = resolver()
                if (addrs.isNotEmpty()) {
                    dnsCache[cacheKey] = CachedDnsEntry(addrs, now + CACHE_TTL_MS)
                    AppLogger.d(TAG, "SmartDNS ($name) успешно отрезолвил $hostname -> ${addrs.firstOrNull()?.hostAddress}")
                    return addrs
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "SmartDNS ($name) сбой для $hostname: ${e.message}, пробуем резерв...")
            }
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    fun resolveWithGeoHide(hostname: String): List<InetAddress> = resolveWithProvider(PROVIDER_GEOHIDE, hostname)
    fun resolveWithDnsAi(hostname: String): List<InetAddress> = resolveWithProvider(PROVIDER_DNS_AI, hostname)
    fun resolveWithComss(hostname: String): List<InetAddress> = resolveWithProvider(PROVIDER_COMSS, hostname)

    val dohDns: Dns by lazy {
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val lower = hostname.lowercase()
                val isAiDomain = AI_DOMAIN_SUFFIXES.any { lower == it || lower.endsWith(".$it") }
                if (isAiDomain) {
                    val prefProvider = runCatching {
                        com.mirrly.tgproxy.MirrlyApplication.instance.prefsManager.getAiSmartDnsProvider()
                    }.getOrDefault(PROVIDER_GEOHIDE)
                    return resolveWithProvider(prefProvider, hostname)
                }
                return Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    val smartDnsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .dns(dohDns)
            .build()
    }
}
