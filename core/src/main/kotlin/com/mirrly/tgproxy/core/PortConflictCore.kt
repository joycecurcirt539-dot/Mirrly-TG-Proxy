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

import java.io.File
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

enum class ConflictTrigger {
    APP_STARTUP,
    START_ERROR
}

enum class PortListenerProtocol {
    UNKNOWN,
    SOCKS5,
    HTTP_PROXY,
    TCP_ACTIVE
}

enum class AppAttributionConfidence {
    EXACT_UID,
    PORT_MATCH,
    INSTALLED_CANDIDATE
}

enum class KnownAppCategory {
    PROXY,
    VPN,
    LOCAL_SERVER
}

data class KnownProxyAppSpec(
    val packageName: String,
    val fallbackDisplayName: String,
    val typicalPorts: Set<Int>,
    val category: KnownAppCategory = KnownAppCategory.PROXY
)

data class PortCheckTarget(
    val port: Int,
    val bindHost: String,
    val protocolLabel: String,
    val isActiveModePort: Boolean
)

data class PortProbeResult(
    val isOccupied: Boolean,
    val listenerProtocol: PortListenerProtocol = PortListenerProtocol.UNKNOWN,
    val bindErrorDetail: String = ""
)

data class ConflictingAppInfo(
    val packageName: String,
    val appName: String,
    val confidence: AppAttributionConfidence,
    val matchedPorts: List<Int> = emptyList(),
    val category: KnownAppCategory = KnownAppCategory.PROXY,
    val isLaunchable: Boolean = true,
    val uid: Int? = null
)

data class OccupiedPortInfo(
    val port: Int,
    val bindHost: String,
    val protocolLabel: String,
    val listenerProtocol: PortListenerProtocol,
    val isActiveModePort: Boolean,
    val ownerUid: Int? = null,
    val exactOwnerApp: ConflictingAppInfo? = null,
    val diagnosticDetail: String = ""
)

data class PortConflictReport(
    val trigger: ConflictTrigger,
    val occupiedPorts: List<OccupiedPortInfo>,
    val candidateApps: List<ConflictingAppInfo>,
    val procNetReadable: Boolean,
    val hasActiveSystemVpn: Boolean,
    val failedMode: ProxyMode? = null,
    val failedPort: Int? = null,
    val nativeErrorDetail: String = "",
    val checkedAtMs: Long = System.currentTimeMillis()
) {
    val hasConflict: Boolean get() = occupiedPorts.isNotEmpty()
    val hasExactOwner: Boolean get() = occupiedPorts.any { it.exactOwnerApp != null } ||
        candidateApps.any { it.confidence == AppAttributionConfidence.EXACT_UID }
}

object PortConflictCore {

    private val WHITESPACE_REGEX = Regex("\\s+")

    val COMPANION_PROXY_PORTS: List<Int> = listOf(
        10808, 10809, 2080, 2081, 7890, 7891, 9050, 8118, 1080, 12334, 2334, 8085, 3066, 3067, 10888, 1443
    )

    val EXCLUDED_MESSENGER_PACKAGES: Set<String> = setOf(
        "com.mirrly.gram",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "com.radolyn.ayugram",
        "com.exteragram.messenger",
        "ir.ilmili.telegraph",
        "org.telegram.BifToGram",
        "tw.nekomimi.nekogram",
        "xyz.nextalone.nagram",
        "uz.unnarsx.cherrygram",
        "org.telegram.mdgram",
        "org.forkclient.messenger.beta",
        "app.nicegram",
        "top.qwq2333.nullgram",
        "com.iMe.android",
        "ru.dahl.messenger",
        "com.scriptsaz.litegram",
        "org.thunderdog.challegram"
    )

    val KNOWN_PROXY_APPS: List<KnownProxyAppSpec> = listOf(
        KnownProxyAppSpec("com.v2ray.ang", "v2rayNG", setOf(10808, 10809)),
        KnownProxyAppSpec("moe.nb4a", "NekoBox", setOf(2080, 2081, 10808, 10809)),
        KnownProxyAppSpec("io.nekohasekai.sagernet", "SagerNet", setOf(2080, 10808)),
        KnownProxyAppSpec("io.nekohasekai.sfa", "sing-box", setOf(2080, 1080, 10808)),
        KnownProxyAppSpec("moe.matsuri.lite", "Matsuri", setOf(2080, 10808)),
        KnownProxyAppSpec("app.hiddify.com", "Hiddify", setOf(12334, 2334, 10808, 10809)),
        KnownProxyAppSpec("com.github.kr328.clash", "Clash for Android", setOf(7890, 7891)),
        KnownProxyAppSpec("com.github.metacubed.clash.meta", "Clash Meta", setOf(7890, 7891)),
        KnownProxyAppSpec("com.follow.clash", "FlClash", setOf(7890, 7891)),
        KnownProxyAppSpec("com.getsurfboard", "Surfboard", setOf(7890, 10808)),
        KnownProxyAppSpec("io.github.dovecote.byedpi", "ByeDPI", setOf(1080, 10808)),
        KnownProxyAppSpec("io.github.krlvm.powertunnel.android", "PowerTunnel", setOf(8085, 8080)),
        KnownProxyAppSpec("org.torproject.android", "Orbot", setOf(9050, 8118, 9051)),
        KnownProxyAppSpec("com.github.shadowsocks", "Shadowsocks", setOf(1080, 10808)),
        KnownProxyAppSpec("org.outline.android.client", "Outline", setOf(1080), KnownAppCategory.VPN),
        KnownProxyAppSpec("com.nebula.karing", "Karing", setOf(3066, 3067, 7890)),
        KnownProxyAppSpec("com.happproxy", "Happ", setOf(10808, 1080)),
        KnownProxyAppSpec("com.adguard.android", "AdGuard", setOf(8080, 1080), KnownAppCategory.VPN),
        KnownProxyAppSpec("com.celzero.bravedns", "RethinkDNS", setOf(1080, 8080), KnownAppCategory.VPN),
        KnownProxyAppSpec("app.intra", "Intra", emptySet(), KnownAppCategory.VPN),
        KnownProxyAppSpec("org.amnezia.vpn", "AmneziaVPN", emptySet(), KnownAppCategory.VPN),
        KnownProxyAppSpec("org.amnezia.awg", "AmneziaWG", emptySet(), KnownAppCategory.VPN),
        KnownProxyAppSpec("com.wireguard.android", "WireGuard", emptySet(), KnownAppCategory.VPN),
        KnownProxyAppSpec("com.evozi.injector", "HTTP Injector", setOf(1080, 8989, 10808)),
        KnownProxyAppSpec("com.termux", "Termux", setOf(1080, 10808, 10888, 1443, 8080), KnownAppCategory.LOCAL_SERVER),
        KnownProxyAppSpec("com.mirrly.tgproxy", "Mirrly TG Proxy", setOf(10888, 1443)),
        KnownProxyAppSpec("com.mirrly.tgproxy.debug", "Mirrly TG Proxy Debug", setOf(10888, 1443)),
        KnownProxyAppSpec("com.mirrly.tgproxy.beta", "Mirrly TG Proxy Beta", setOf(10888, 1443))
    )

    private val PROXY_KEYWORDS: List<String> = listOf(
        "proxy", "socks", "v2ray", "xray", "clash", "singbox", "sing-box",
        "nekobox", "sagernet", "matsuri", "hiddify", "byedpi", "powertunnel",
        "shadowsocks", "orbot", "amnezia", "adguard", "rethink", "karing",
        "happproxy", "surfboard", "psiphon", "lantern", "mtproto", "termux"
    )

    fun buildPortCheckTargets(
        config: ProxyConfig,
        runningMode: ProxyMode?,
        failedMode: ProxyMode? = null,
        failedPort: Int? = null,
        excludedSelfPorts: Set<Int> = emptySet()
    ): List<PortCheckTarget> {
        val bindHost = config.bindHost.ifBlank { "127.0.0.1" }
        val rawTargets = mutableListOf<PortCheckTarget>()

        if (failedPort != null && failedPort in 1..65535 && failedPort !in excludedSelfPorts) {
            val modeLabel = when (failedMode ?: config.proxyMode) {
                ProxyMode.SOCKS5 -> "SOCKS5"
                ProxyMode.MTPROTO -> "MTProto"
            }
            rawTargets += PortCheckTarget(
                port = failedPort,
                bindHost = bindHost,
                protocolLabel = modeLabel,
                isActiveModePort = true
            )
        }

        val activeSocks5Self = runningMode == ProxyMode.SOCKS5
        val activeMtprotoSelf = runningMode == ProxyMode.MTPROTO

        if (!activeSocks5Self && config.socks5Port in 1..65535 && config.socks5Port !in excludedSelfPorts) {
            rawTargets += PortCheckTarget(
                port = config.socks5Port,
                bindHost = bindHost,
                protocolLabel = "SOCKS5",
                isActiveModePort = (config.proxyMode == ProxyMode.SOCKS5)
            )
        }

        if (!activeMtprotoSelf && config.bindPort in 1..65535 && config.bindPort !in excludedSelfPorts) {
            rawTargets += PortCheckTarget(
                port = config.bindPort,
                bindHost = bindHost,
                protocolLabel = "MTProto",
                isActiveModePort = (config.proxyMode == ProxyMode.MTPROTO)
            )
        }

        // Deduplicate by port while preserving priority order and merging labels
        val merged = linkedMapOf<Int, PortCheckTarget>()
        for (item in rawTargets) {
            val existing = merged[item.port]
            if (existing == null) {
                merged[item.port] = item
            } else {
                val combinedLabels = (existing.protocolLabel.split(" · ") + item.protocolLabel.split(" · "))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString(" · ")
                merged[item.port] = existing.copy(
                    protocolLabel = combinedLabels,
                    isActiveModePort = existing.isActiveModePort || item.isActiveModePort
                )
            }
        }
        return merged.values.toList()
    }

    /**
     * Parses Linux /proc/net/tcp or /proc/net/tcp6 contents for sockets in TCP_LISTEN (st == 0A).
     * Returns a map of listening port -> set of owning UIDs.
     */
    fun parseProcNetTcpListeners(procContent: String): Map<Int, Set<Int>> {
        if (procContent.isBlank()) return emptyMap()
        val result = linkedMapOf<Int, MutableSet<Int>>()
        val lines = procContent.lineSequence()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("sl", ignoreCase = true)) continue
            val cols = line.split(WHITESPACE_REGEX)
            if (cols.size < 8) continue
            val stateHex = cols[3]
            if (!stateHex.equals("0A", ignoreCase = true)) continue
            val localAddr = cols[1]
            val colonIdx = localAddr.lastIndexOf(':')
            if (colonIdx <= 0 || colonIdx >= localAddr.length - 1) continue
            val port = localAddr.substring(colonIdx + 1).toIntOrNull(16) ?: continue
            if (port !in 1..65535) continue
            val uid = cols[7].toIntOrNull() ?: continue
            if (uid < 0) continue
            result.getOrPut(port) { linkedSetOf() }.add(uid)
        }
        return result
    }

    fun readProcNetTcpSnapshot(): Pair<Boolean, Map<Int, Set<Int>>> {
        val paths = listOf("/proc/net/tcp", "/proc/net/tcp6")
        var anyReadable = false
        val combined = linkedMapOf<Int, MutableSet<Int>>()
        for (path in paths) {
            try {
                val file = File(path)
                if (!file.exists() || !file.canRead()) continue
                val text = file.readText()
                anyReadable = true
                val parsed = parseProcNetTcpListeners(text)
                for ((port, uids) in parsed) {
                    combined.getOrPut(port) { linkedSetOf() }.addAll(uids)
                }
            } catch (_: Throwable) {
                // Expected EACCES under Android SELinux untrusted_app policy on Android 8+/10+
            }
        }
        return Pair(anyReadable, combined)
    }

    fun isLikelyProxyPackageOrLabel(
        packageName: String,
        appLabel: String,
        selfPackageName: String
    ): Boolean {
        val pkgLower = packageName.trim().lowercase()
        val selfLower = selfPackageName.trim().lowercase()
        if (pkgLower.isEmpty() || pkgLower == selfLower) return false
        if (pkgLower in EXCLUDED_MESSENGER_PACKAGES) return false

        val labelLower = appLabel.trim().lowercase()
        if (KNOWN_PROXY_APPS.any { it.packageName.equals(pkgLower, ignoreCase = true) }) {
            return true
        }
        return PROXY_KEYWORDS.any { kw -> pkgLower.contains(kw) || labelLower.contains(kw) }
    }

    fun probePortOccupancy(
        host: String,
        port: Int,
        probeProtocol: Boolean = true
    ): PortProbeResult {
        if (port !in 1..65535) return PortProbeResult(isOccupied = false)
        val cleanHost = host.trim().ifEmpty { "127.0.0.1" }
        val connectHosts = when (cleanHost) {
            "0.0.0.0" -> listOf("127.0.0.1", "::1")
            "::" -> listOf("::1", "127.0.0.1")
            else -> listOf(cleanHost)
        }

        // 1. Check active loopback listener via TCP connect (also works when SO_REUSEPORT is set)
        for (targetHost in connectHosts) {
            val proto = tryConnectAndFingerprint(targetHost, port, probeProtocol)
            if (proto != null) {
                return PortProbeResult(
                    isOccupied = true,
                    listenerProtocol = proto
                )
            }
        }

        // 2. Check SO_REUSEADDR bind (matches Mirrly Rust bind_reuse_tcp behavior without triggering TIME_WAIT)
        val bindException = tryBindReusableServerSocket(cleanHost, port)
        if (bindException != null) {
            // Brief re-check in case a transient socket was closing
            try {
                Thread.sleep(45)
            } catch (_: InterruptedException) {}
            val retryException = tryBindReusableServerSocket(cleanHost, port)
            if (retryException != null) {
                return PortProbeResult(
                    isOccupied = true,
                    listenerProtocol = PortListenerProtocol.UNKNOWN,
                    bindErrorDetail = retryException.message ?: "Address already in use"
                )
            }
        }

        return PortProbeResult(isOccupied = false)
    }

    fun isLoopbackListenerAccepting(host: String, port: Int, timeoutMs: Int = 70): Boolean {
        if (port !in 1..65535) return false
        val connectHost = when (host.trim()) {
            "", "0.0.0.0" -> "127.0.0.1"
            "::" -> "::1"
            else -> host.trim()
        }
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(connectHost, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun findNearestFreePort(
        host: String,
        preferredPort: Int,
        excludedPorts: Set<Int> = emptySet()
    ): Int? {
        val normalizedHost = host.trim().ifEmpty { "127.0.0.1" }
        val start = (preferredPort + 1).coerceIn(1024, 65000)
        val candidates = (start..65000).asSequence() + (10880 until start).asSequence()
        var checked = 0
        for (port in candidates) {
            if (port in excludedPorts || port == preferredPort) continue
            checked++
            if (checked > 250) break
            val probe = probePortOccupancy(normalizedHost, port, probeProtocol = false)
            if (!probe.isOccupied) {
                return port
            }
        }
        return null
    }

    private fun tryBindReusableServerSocket(host: String, port: Int): BindException? {
        return try {
            ServerSocket().use { ss ->
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName(host), port), 1)
            }
            null
        } catch (e: BindException) {
            e
        } catch (_: Exception) {
            null
        }
    }

    private fun tryConnectAndFingerprint(
        host: String,
        port: Int,
        probeProtocol: Boolean
    ): PortListenerProtocol? {
        val firstConnected = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 130)
                if (!probeProtocol) return PortListenerProtocol.TCP_ACTIVE
                socket.soTimeout = 140
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val buf = ByteArray(32)
                val read = try {
                    input.read(buf)
                } catch (_: Exception) {
                    -1
                }
                if (read >= 2 && buf[0] == 0x05.toByte()) {
                    return PortListenerProtocol.SOCKS5
                }
                if (read >= 5 && String(buf, 0, read, Charsets.US_ASCII).startsWith("HTTP/", ignoreCase = true)) {
                    return PortListenerProtocol.HTTP_PROXY
                }
                true
            }
        } catch (_: Exception) {
            false
        }

        if (!firstConnected) return null

        // Second tiny HTTP probe if SOCKS5 greeting didn't get a clear HTTP/SOCKS5 header
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 110)
                socket.soTimeout = 140
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write("HEAD / HTTP/1.0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                val buf = ByteArray(32)
                val read = try {
                    input.read(buf)
                } catch (_: Exception) {
                    -1
                }
                if (read >= 5 && String(buf, 0, read, Charsets.US_ASCII).startsWith("HTTP/", ignoreCase = true)) {
                    return PortListenerProtocol.HTTP_PROXY
                }
            }
        } catch (_: Exception) {
        }

        return PortListenerProtocol.TCP_ACTIVE
    }
}
