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

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import org.json.JSONArray
import org.json.JSONObject

interface ProxyLibrary : Library {
    companion object {
        val INSTANCE: ProxyLibrary by lazy {
            Native.load("mirrlyengine", ProxyLibrary::class.java) as ProxyLibrary
        }
    }

    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StartSocks5Proxy(host: String, port: Int, verbose: Int): Int
    fun StopProxy(): Int
    fun ResetNetworkSockets()
    fun EmergencyKillAllSockets()
    fun SetPoolSize(size: Int)
    fun SetTcpNoDelay(enabled: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, userDomain: String)
    fun SetWorkerProtocol(domain: String, proto: Int)
    fun SetSecret(secret: String)
    fun SetSocks5Auth(username: String, password: String)
    fun SetDohEndpoints(endpoints: String)
    fun SetUplinkMode(mode: Int)
    fun SetWarpConfig(endpoint: String, sni: String, authToken: String, clientIpv4: String, clientIpv6: String)
    fun SetWarpCrypto(p256Priv: String, clientCert: String, peerPub: String)
    fun SetWarpUriTemplate(uriTemplate: String)
    fun SetWarpFullConfig(
        endpoint: String,
        sni: String,
        authToken: String,
        clientIpv4: String,
        clientIpv6: String,
        p256Priv: String,
        clientCert: String,
        peerPub: String,
        uriTemplate: String
    )
    fun ClearWarpCrypto()
    fun GetWarpConfigGeneration(): Long
    fun GetWarpStatus(): Pointer?
    fun GetWarpStickyEndpoint(): Pointer?
    fun SetWarpStickyEndpoint(endpoint: String)
    fun ClearWarpStickyProfile()
    fun RecordWarpStickySuccess()
    fun RecordWarpStickyTimeout(): Boolean
    fun SetVlessConfig(uuid: String, path: String, domain: String)
    fun SetVlessNetworkConfig(
        uuid: String,
        path: String,
        domain: String,
        serverAddress: String,
        serverPort: Int,
        tlsSni: String,
        hostHeader: String
    )
    fun SetVlessExtendedConfig(
        uuid: String,
        path: String,
        domain: String,
        serverAddress: String,
        serverPort: Int,
        tlsSni: String,
        hostHeader: String,
        transport: String,
        security: String,
        publicKey: String,
        shortId: String,
        fingerprint: String,
        spiderX: String,
        flow: String,
        headerType: String
    )
    fun SetVlessConfigJson(json: String): Int
    fun GetVlessConfigJson(): Pointer?
    fun SetVlessFallbackPool(domains: String)
    fun SetVlessFallbackProfilesJson(json: String): Int
    fun GetVlessStatus(): Pointer?
    fun SetOperaVpnConfig(vlessEnabled: Int, warpEnabled: Int, endpoint: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun SetAwgConfig(ini: String): Int
    fun SetAwgKeepalive(secs: Int)
    fun GetAwgStatus(): Pointer?
    fun SetBatteryQoSLevel(level: Int)
    fun GetActiveCascadeStage(): Int
    fun FreeString(p: Pointer)
    fun SetIpv6OnlyNetwork(isIpv6Only: Int)
    fun SetMtprotoStandbyPerActiveSlot(size: Int): Int
    fun GetTransportPoolStatusJson(): Pointer?
    fun SetSocketBufferSizes(recvSize: Int, sendSize: Int): Int
    fun GetSocketBufferStatusJson(): Pointer?
    fun GetLastOsSocketBufferSizes(recvOut: IntByReference, sendOut: IntByReference): Int
    fun SetNetworkGeneration(gen: Long)
    fun SetNetworkProfileJson(json: String): Int
    fun GetNetworkProfileJson(): Pointer?
    fun SuspendNetworkSockets()
    fun WarmupWsPool()
    fun OnScreenWakeup()
    fun SetTrustPolicy(isPrivateNode: Int, allowPublicRelayFallback: Int, allowOperaDirectExit: Int, allowOperaTransportHop: Int)
    fun GetStageTimelineJson(): Pointer?
    fun GetUsefulRxSliJson(): Pointer?
    fun GetDialBudgetStatsJson(): Pointer?
    fun GetDomainBalancerStatusJson(): Pointer?
    fun GetNodeIndependenceStatusJson(): Pointer?
    fun GetTlsObservabilityStatusJson(): Pointer?
}

object NativeProxy {
    @Volatile
    var isStarted: Boolean = false
        private set

    fun startProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int {
        return try {
            val code = ProxyLibrary.INSTANCE.StartProxy(host, port, dcIps, secret, verbose)
            if (code == 0) {
                isStarted = true
            }
            code
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [startProxy]: ${t.message}", t)
            -1
        }
    }

    fun startSocks5Proxy(host: String, port: Int, verbose: Int): Int {
        return try {
            val code = ProxyLibrary.INSTANCE.StartSocks5Proxy(host, port, verbose)
            if (code == 0) {
                isStarted = true
            }
            code
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [startSocks5Proxy]: ${t.message}", t)
            -1
        }
    }

    fun stopProxy(): Int {
        if (!isStarted) {
            return 0
        }
        isStarted = false
        return try {
            ProxyLibrary.INSTANCE.StopProxy()
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [stopProxy]: ${t.message}", t)
            -1
        }
    }

    fun resetNetworkSockets() {
        if (!isStarted) return
        try {
            ProxyLibrary.INSTANCE.ResetNetworkSockets()
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [resetNetworkSockets]: ${t.message}", t)
        }
    }

    fun emergencyKillAllSockets(reason: String = "manual_emergency_kill") {
        if (!isStarted) return
        try {
            AppLogger.w("NativeProxy", "Экстренный сброс всех сокетов через FFI [EmergencyKillAllSockets] (reason=$reason)")
            ProxyLibrary.INSTANCE.EmergencyKillAllSockets()
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [emergencyKillAllSockets]: ${t.message}", t)
        }
    }

    fun setPoolSize(size: Int) {
        if (!isStarted) return
        try {
            ProxyLibrary.INSTANCE.SetPoolSize(size)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setPoolSize]: ${t.message}", t)
        }
    }

    fun setTcpNoDelay(enabled: Boolean) {
        try {
            ProxyLibrary.INSTANCE.SetTcpNoDelay(if (enabled) 1 else 0)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setTcpNoDelay]: ${t.message}", t)
        }
    }

    fun setCfProxyCacheDir(cacheDir: String) {
        try {
            ProxyLibrary.INSTANCE.SetCfProxyCacheDir(cacheDir)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setCfProxyCacheDir]: ${t.message}", t)
        }
    }

    fun setCfProxyConfig(enabled: Boolean, userDomain: String) {
        try {
            ProxyLibrary.INSTANCE.SetCfProxyConfig(
                if (enabled) 1 else 0,
                userDomain
            )
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setCfProxyConfig]: ${t.message}", t)
        }
    }

    fun setWorkerProtocol(domain: String, proto: Int) {
        try {
            ProxyLibrary.INSTANCE.SetWorkerProtocol(domain, proto)
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [setWorkerProtocol]: ${t.message}")
        }
    }

    fun setSecret(secret: String) {
        if (!isStarted) return
        try {
            ProxyLibrary.INSTANCE.SetSecret(secret)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setSecret]: ${t.message}", t)
        }
    }

    fun setSocks5Auth(username: String, password: String) {
        try {
            ProxyLibrary.INSTANCE.SetSocks5Auth(username, password)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setSocks5Auth]: ${t.message}", t)
        }
    }

    fun setDohEndpoints(endpoints: String) {
        try {
            ProxyLibrary.INSTANCE.SetDohEndpoints(endpoints)
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [setDohEndpoints] не доступен: ${t.message}")
        }
    }

    fun setUplinkMode(mode: UplinkMode) {
        try {
            val modeInt = when (mode) {
                UplinkMode.WORKER -> 0
                UplinkMode.MASQUE -> 1
                UplinkMode.HYBRID -> 2
                UplinkMode.VLESS -> 3
                UplinkMode.AWG -> 4
                UplinkMode.WARP_CASCADE -> 5
            }
            ProxyLibrary.INSTANCE.SetUplinkMode(modeInt)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setUplinkMode]: ${t.message}", t)
        }
    }

    /**
     * Загружает конфиг AmneziaWG из INI-строки в нативное ядро.
     * @return true при успехе, false при ошибке парсинга
     */
    fun setAwgConfig(ini: String): Boolean {
        return try {
            val code = ProxyLibrary.INSTANCE.SetAwgConfig(ini)
            if (code != 0) {
                AppLogger.e("NativeProxy", "SetAwgConfig вернул код ошибки: $code")
            }
            code == 0
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [SetAwgConfig]: ${t.message}", t)
            false
        }
    }

    /** Возвращает строку диагностического статуса AWG конфига из нативного ядра. */
    fun getAwgStatus(): String {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetAwgStatus() ?: return "awg:unavailable"
            try {
                ptr.getString(0, "UTF-8") ?: "awg:null"
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [GetAwgStatus] недоступен: ${t.message}")
            "awg:unavailable"
        }
    }

    fun setBatteryQoSLevel(level: QoSThrottleLevel) {
        if (!isStarted) return
        try {
            val code = when (level) {
                QoSThrottleLevel.NONE -> 0
                QoSThrottleLevel.MODERATE -> 1
                QoSThrottleLevel.SEVERE -> 2
            }
            ProxyLibrary.INSTANCE.SetBatteryQoSLevel(code)
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [setBatteryQoSLevel]: ${t.message}")
        }
    }

    fun setAwgKeepalive(secs: Int) {
        if (!isStarted) return
        try {
            ProxyLibrary.INSTANCE.SetAwgKeepalive(secs)
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [setAwgKeepalive]: ${t.message}")
        }
    }

    /**
     * Возвращает активный этап каскадного аплинка:
     * 0 = Нет данных / прямой режим
     * 1 = WARP MASQUE (HTTP/3 CONNECT-IP)
     * 2 = WARP AmneziaWG (Обфусцированный WireGuard)
     * 3 = Cloudflare Worker WSS (Гарантированный TCP 443)
     */
    fun getActiveCascadeStage(): Int {
        if (!isStarted) return 0
        return try {
            ProxyLibrary.INSTANCE.GetActiveCascadeStage()
        } catch (_: Throwable) {
            0
        }
    }

    fun setVlessConfigJson(json: String): Boolean {
        return try {
            val code = ProxyLibrary.INSTANCE.SetVlessConfigJson(json)
            code == 0
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [SetVlessConfigJson]: ${t.message}", t)
            false
        }
    }

    fun getVlessConfigJson(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetVlessConfigJson() ?: return null
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [GetVlessConfigJson]: ${t.message}", t)
            null
        }
    }

    fun setVlessConfig(uuid: String, path: String, domain: String = "") {
        // Попытка применить через JSON без затирания остальных параметров
        try {
            val json = JSONObject().apply {
                if (uuid.isNotBlank()) put("uuid", uuid)
                if (path.isNotBlank()) put("path", path)
                if (domain.isNotBlank()) put("domain", domain)
            }.toString()
            if (setVlessConfigJson(json)) return
        } catch (_: Throwable) {}

        try {
            ProxyLibrary.INSTANCE.SetVlessConfig(uuid, path, domain)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setVlessConfig]: ${t.message}", t)
        }
    }

    fun setVlessExtendedConfig(
        uuid: String,
        path: String = "",
        domain: String = "",
        serverAddress: String = "",
        serverPort: Int = 443,
        tlsSni: String = "",
        hostHeader: String = "",
        transport: String = "ws",
        security: String = "tls",
        publicKey: String = "",
        shortId: String = "",
        fingerprint: String = "",
        spiderX: String = "",
        flow: String = "",
        headerType: String = ""
    ) {
        // 1. Предпочтительный путь: полный JSON FFI (SetVlessConfigJson)
        try {
            val json = JSONObject().apply {
                if (uuid.isNotBlank()) put("uuid", uuid)
                if (path.isNotBlank()) put("path", path)
                if (domain.isNotBlank()) put("domain", domain)
                if (serverAddress.isNotBlank()) put("server_address", serverAddress)
                if (serverPort > 0) put("server_port", serverPort)
                if (tlsSni.isNotBlank()) put("tls_sni", tlsSni)
                if (hostHeader.isNotBlank()) put("host_header", hostHeader)
                if (transport.isNotBlank()) put("transport", transport)
                if (security.isNotBlank()) put("security", security)
                if (publicKey.isNotBlank()) put("public_key", publicKey)
                if (shortId.isNotBlank()) put("short_id", shortId)
                if (fingerprint.isNotBlank()) put("fingerprint", fingerprint)
                if (spiderX.isNotBlank()) put("spider_x", spiderX)
                if (flow.isNotBlank()) put("flow", flow)
                if (headerType.isNotBlank()) put("header_type", headerType)
            }.toString()

            if (setVlessConfigJson(json)) {
                return
            }
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "SetVlessConfigJson недоступен, переход к прямому FFI: ${t.message}")
        }

        // 2. Фоллбек: прямой FFI SetVlessExtendedConfig (15 параметров)
        try {
            ProxyLibrary.INSTANCE.SetVlessExtendedConfig(
                uuid,
                path,
                domain,
                serverAddress,
                serverPort,
                tlsSni,
                hostHeader,
                transport,
                security,
                publicKey,
                shortId,
                fingerprint,
                spiderX,
                flow,
                headerType
            )
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setVlessExtendedConfig]: ${t.message}", t)
            try {
                ProxyLibrary.INSTANCE.SetVlessNetworkConfig(
                    uuid,
                    path,
                    domain,
                    serverAddress,
                    serverPort,
                    tlsSni,
                    hostHeader
                )
            } catch (_: Throwable) {
                try {
                    ProxyLibrary.INSTANCE.SetVlessConfig(uuid, path, domain)
                } catch (_: Throwable) {}
            }
        }
    }

    fun setVlessNetworkConfig(
        uuid: String,
        path: String,
        domain: String = "",
        serverAddress: String = "",
        serverPort: Int = 443,
        tlsSni: String = "",
        hostHeader: String = ""
    ) {
        setVlessExtendedConfig(
            uuid = uuid,
            path = path,
            domain = domain,
            serverAddress = serverAddress,
            serverPort = serverPort,
            tlsSni = tlsSni,
            hostHeader = hostHeader
        )
    }

    fun setWarpConfig(
        endpoint: String,
        sni: String,
        authToken: String,
        clientIpv4: String,
        clientIpv6: String
    ) {
        try {
            ProxyLibrary.INSTANCE.SetWarpConfig(
                endpoint,
                sni,
                authToken,
                clientIpv4,
                clientIpv6
            )
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setWarpConfig]: ${t.message}", t)
        }
    }

    fun setWarpCrypto(
        p256PrivateKey: String,
        clientCert: String,
        peerPublicKey: String
    ) {
        try {
            ProxyLibrary.INSTANCE.SetWarpCrypto(
                p256PrivateKey,
                clientCert,
                peerPublicKey
            )
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setWarpCrypto]: ${t.message}", t)
        }
    }

    fun setWarpUriTemplate(uriTemplate: String) {
        try {
            ProxyLibrary.INSTANCE.SetWarpUriTemplate(uriTemplate)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setWarpUriTemplate]: ${t.message}", t)
        }
    }

    fun setWarpFullConfig(
        endpoint: String,
        sni: String,
        authToken: String,
        clientIpv4: String,
        clientIpv6: String,
        p256PrivateKey: String,
        clientCert: String,
        peerPublicKey: String,
        uriTemplate: String
    ) {
        try {
            ProxyLibrary.INSTANCE.SetWarpFullConfig(
                endpoint,
                sni,
                authToken,
                clientIpv4,
                clientIpv6,
                p256PrivateKey,
                clientCert,
                peerPublicKey,
                uriTemplate
            )
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setWarpFullConfig]: ${t.message}", t)
        }
    }

    fun clearWarpCrypto() {
        try {
            ProxyLibrary.INSTANCE.ClearWarpCrypto()
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [clearWarpCrypto]: ${t.message}", t)
        }
    }

    fun getWarpConfigGeneration(): Long {
        return try {
            ProxyLibrary.INSTANCE.GetWarpConfigGeneration()
        } catch (t: Throwable) {
            -1L
        }
    }

    fun getWarpStatus(): String? {
        if (!isStarted) return null
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetWarpStatus() ?: return null
            val res = ptr.getString(0)
            ProxyLibrary.INSTANCE.FreeString(ptr)
            res
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [getWarpStatus]: ${t.message}", t)
            null
        }
    }

    fun getWarpStickyEndpoint(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetWarpStickyEndpoint() ?: return null
            val res = ptr.getString(0)
            ProxyLibrary.INSTANCE.FreeString(ptr)
            res
        } catch (t: Throwable) {
            null
        }
    }

    fun setWarpStickyEndpoint(endpoint: String) {
        try {
            ProxyLibrary.INSTANCE.SetWarpStickyEndpoint(endpoint)
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [SetWarpStickyEndpoint]: ${t.message}")
        }
    }

    fun clearWarpStickyProfile() {
        try {
            ProxyLibrary.INSTANCE.ClearWarpStickyProfile()
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [ClearWarpStickyProfile]: ${t.message}")
        }
    }

    fun recordWarpStickySuccess() {
        try {
            ProxyLibrary.INSTANCE.RecordWarpStickySuccess()
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [RecordWarpStickySuccess]: ${t.message}")
        }
    }

    fun recordWarpStickyTimeout(): Boolean {
        return try {
            ProxyLibrary.INSTANCE.RecordWarpStickyTimeout()
        } catch (t: Throwable) {
            false
        }
    }

    fun getVlessStatus(): String? {
        if (!isStarted) return null
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetVlessStatus() ?: return null
            val res = ptr.getString(0)
            ProxyLibrary.INSTANCE.FreeString(ptr)
            res
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [getVlessStatus]: ${t.message}", t)
            null
        }
    }

    fun setVlessFallbackPool(domainsCsv: String) {
        try {
            ProxyLibrary.INSTANCE.SetVlessFallbackPool(domainsCsv)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setVlessFallbackPool]: ${t.message}", t)
        }
    }

    fun setVlessFallbackProfilesJson(json: String): Boolean {
        return try {
            val code = ProxyLibrary.INSTANCE.SetVlessFallbackProfilesJson(json)
            code >= 0
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [SetVlessFallbackProfilesJson]: ${t.message}", t)
            try {
                ProxyLibrary.INSTANCE.SetVlessFallbackPool(json)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun setOperaVpnConfig(vlessEnabled: Boolean, warpEnabled: Boolean, endpoint: String) {
        try {
            ProxyLibrary.INSTANCE.SetOperaVpnConfig(
                if (vlessEnabled) 1 else 0,
                if (warpEnabled) 1 else 0,
                endpoint
            )
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [setOperaVpnConfig]: ${t.message}", t)
        }
    }

    fun getSecretWithPrefix(): String? {
        if (!isStarted) return null
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetSecretWithPrefix() ?: return null
            val res = ptr.getString(0)
            ProxyLibrary.INSTANCE.FreeString(ptr)
            res
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [getSecretWithPrefix]: ${t.message}", t)
            null
        }
    }

    fun getStats(): String? {
        if (!isStarted) return null
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetStats() ?: return null
            val res = ptr.getString(0)
            ProxyLibrary.INSTANCE.FreeString(ptr)
            res
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [getStats]: ${t.message}", t)
            null
        }
    }

    fun setIpv6OnlyNetwork(isIpv6Only: Boolean) {
        try {
            ProxyLibrary.INSTANCE.SetIpv6OnlyNetwork(if (isIpv6Only) 1 else 0)
        } catch (_: UnsatisfiedLinkError) {
        } catch (_: NoClassDefFoundError) {
        } catch (_: Throwable) {}
    }

    fun setMtprotoStandbyPerActiveSlot(requested: Int): Int? {
        return try {
            ProxyLibrary.INSTANCE.SetMtprotoStandbyPerActiveSlot(requested)
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [SetMtprotoStandbyPerActiveSlot]: ${t.message}")
            null
        }
    }

    fun getTransportPoolStatusJson(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetTransportPoolStatusJson() ?: return null
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [GetTransportPoolStatusJson] недоступен: ${t.message}")
            null
        }
    }

    fun getTransportPoolStatus(): TransportPoolStatus? {
        val json = getTransportPoolStatusJson() ?: return null
        return TransportPoolStatus.fromJson(json)
    }

    fun setSocketBufferSizes(recvSize: Int, sendSize: Int): Boolean {
        return try {
            val code = ProxyLibrary.INSTANCE.SetSocketBufferSizes(recvSize, sendSize)
            code == 0
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [SetSocketBufferSizes]: ${t.message}")
            false
        }
    }

    fun getSocketBufferStatusJson(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetSocketBufferStatusJson() ?: return null
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [GetSocketBufferStatusJson] недоступен: ${t.message}")
            null
        }
    }

    fun getSocketBufferStatus(): SocketBufferStatus? {
        val json = getSocketBufferStatusJson() ?: return null
        return SocketBufferStatus.fromJson(json)
    }

    fun getLastOsSocketBufferSizes(): Pair<Int, Int>? {
        return try {
            val recvRef = IntByReference(0)
            val sendRef = IntByReference(0)
            val code = ProxyLibrary.INSTANCE.GetLastOsSocketBufferSizes(recvRef, sendRef)
            if (code == 0) {
                Pair(recvRef.value, sendRef.value)
            } else {
                null
            }
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [GetLastOsSocketBufferSizes] недоступен: ${t.message}")
            null
        }
    }

    fun setNetworkGeneration(gen: Long) {
        try {
            ProxyLibrary.INSTANCE.SetNetworkGeneration(gen)
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [SetNetworkGeneration]: ${t.message}")
        }
    }

    fun setNetworkProfileJson(json: String): Boolean {
        return try {
            val code = ProxyLibrary.INSTANCE.SetNetworkProfileJson(json)
            code == 0
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [SetNetworkProfileJson]: ${t.message}")
            false
        }
    }

    fun getNetworkProfileJson(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetNetworkProfileJson() ?: return null
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [GetNetworkProfileJson] недоступен: ${t.message}")
            null
        }
    }

    fun suspendNetworkSockets() {
        if (!isStarted) return
        try {
            ProxyLibrary.INSTANCE.SuspendNetworkSockets()
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [SuspendNetworkSockets]: ${t.message}", t)
        }
    }

    fun warmupWsPool() {
        if (!isStarted) return
        try {
            ProxyLibrary.INSTANCE.WarmupWsPool()
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [WarmupWsPool]: ${t.message}", t)
        }
    }

    fun onScreenWakeup() {
        if (!isStarted) return
        try {
            ProxyLibrary.INSTANCE.OnScreenWakeup()
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [OnScreenWakeup]: ${t.message}", t)
        }
    }

    fun setTrustPolicy(
        isPrivateNode: Boolean,
        allowPublicRelayFallback: Boolean,
        allowOperaDirectExit: Boolean,
        allowOperaTransportHop: Boolean
    ) {
        try {
            ProxyLibrary.INSTANCE.SetTrustPolicy(
                if (isPrivateNode) 1 else 0,
                if (allowPublicRelayFallback) 1 else 0,
                if (allowOperaDirectExit) 1 else 0,
                if (allowOperaTransportHop) 1 else 0
            )
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [SetTrustPolicy]: ${t.message}")
        }
    }

    fun getStageTimelineJson(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetStageTimelineJson() ?: return null
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun getUsefulRxSliJson(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetUsefulRxSliJson() ?: return null
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun getDialBudgetStatsJson(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetDialBudgetStatsJson() ?: return null
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun getDomainBalancerStatusJson(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetDomainBalancerStatusJson() ?: return null
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [GetDomainBalancerStatusJson] недоступен: ${t.message}")
            null
        }
    }

    fun getDomainBalancerStatus(): DomainBalancerStatus? {
        val json = getDomainBalancerStatusJson() ?: return null
        return DomainBalancerStatus.fromJson(json)
    }

    fun getNodeIndependenceStatusJson(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetNodeIndependenceStatusJson() ?: return null
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [GetNodeIndependenceStatusJson] недоступен: ${t.message}")
            null
        }
    }

    fun getNodeIndependenceStatus(): NodeIndependenceStatus? {
        val json = getNodeIndependenceStatusJson() ?: return null
        return NodeIndependenceStatus.fromJson(json)
    }

    fun getTlsObservabilityStatusJson(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetTlsObservabilityStatusJson() ?: return null
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [GetTlsObservabilityStatusJson] недоступен: ${t.message}")
            null
        }
    }

    fun getTlsObservabilityStatus(): TlsObservabilityStatus? {
        val json = getTlsObservabilityStatusJson() ?: return null
        return TlsObservabilityStatus.fromJson(json)
    }
}

data class SocketBufferStatus(
    val configuredRecvBytes: Int,
    val configuredSendBytes: Int,
    val clampedRecvBytes: Int,
    val clampedSendBytes: Int,
    val lastOsRecvBytes: Int,
    val lastOsSendBytes: Int,
    val socketsConfiguredTotal: Long,
    val autotuneBaseline: Boolean
) {
    companion object {
        fun fromJson(json: String?): SocketBufferStatus? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                SocketBufferStatus(
                    configuredRecvBytes = obj.optInt("configured_recv_bytes", 0),
                    configuredSendBytes = obj.optInt("configured_send_bytes", 0),
                    clampedRecvBytes = obj.optInt("clamped_recv_bytes", 0),
                    clampedSendBytes = obj.optInt("clamped_send_bytes", 0),
                    lastOsRecvBytes = obj.optInt("last_os_recv_bytes", 0),
                    lastOsSendBytes = obj.optInt("last_os_send_bytes", 0),
                    socketsConfiguredTotal = obj.optLong("sockets_configured_total", 0L),
                    autotuneBaseline = obj.optBoolean("autotune_baseline", false)
                )
            } catch (t: Throwable) {
                AppLogger.d("NativeProxy", "Failed to parse SocketBufferStatus JSON: ${t.message}")
                null
            }
        }
    }
}

data class TransportPoolStatus(
    val transport: String,
    val isMobile: Boolean,
    val mtprotoStandbyPerActiveSlotRequested: Int,
    val mtprotoStandbyPerActiveSlotEffective: Int,
    val globalEstablishmentBudget: Int,
    val socksConcurrentFlows: Long
) {
    companion object {
        fun fromJson(json: String?): TransportPoolStatus? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                TransportPoolStatus(
                    transport = obj.optString("transport", "unknown"),
                    isMobile = obj.optBoolean("is_mobile", false),
                    mtprotoStandbyPerActiveSlotRequested = obj.optInt("mtproto_standby_per_active_slot_requested", 1),
                    mtprotoStandbyPerActiveSlotEffective = obj.optInt("mtproto_standby_per_active_slot_effective", 1),
                    globalEstablishmentBudget = obj.optInt("global_establishment_budget", 2),
                    socksConcurrentFlows = obj.optLong("socks_concurrent_flows", 0L)
                )
            } catch (t: Throwable) {
                AppLogger.d("NativeProxy", "Failed to parse TransportPoolStatus JSON: ${t.message}")
                null
            }
        }
    }
}

data class RankedDomainSummary(
    val domain: String,
    val probeRttMs: Long?,
    val usefulSuccessCount: Long,
    val failureCount: Long,
    val consecutiveFailures: Int,
    val score: Long
)

data class TargetRouteStatus(
    val dcId: Int,
    val isMedia: Boolean,
    val activeDomain: String?,
    val rankings: List<RankedDomainSummary>
)

data class DomainBalancerStatus(
    val currentNetworkGeneration: Long,
    val routes: List<TargetRouteStatus>
) {
    companion object {
        fun fromJson(json: String?): DomainBalancerStatus? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val gen = obj.optLong("current_network_generation", 1L)
                val routesArr = obj.optJSONArray("routes") ?: JSONArray()
                val routesList = ArrayList<TargetRouteStatus>()
                for (i in 0 until routesArr.length()) {
                    val rObj = routesArr.getJSONObject(i)
                    val dcId = rObj.getInt("dc_id")
                    val isMedia = rObj.getBoolean("is_media")
                    val activeDomain = if (rObj.isNull("active_domain")) null else rObj.optString("active_domain", null)
                    val rankingsArr = rObj.optJSONArray("rankings") ?: JSONArray()
                    val rankingsList = ArrayList<RankedDomainSummary>()
                    for (j in 0 until rankingsArr.length()) {
                        val rankObj = rankingsArr.getJSONObject(j)
                        val domain = rankObj.getString("domain")
                        val probeRtt = if (rankObj.isNull("probe_rtt_ms")) null else rankObj.optLong("probe_rtt_ms")
                        val useful = rankObj.optLong("useful_success_count", 0L)
                        val fail = rankObj.optLong("failure_count", 0L)
                        val consec = rankObj.optInt("consecutive_failures", 0)
                        val score = rankObj.optLong("score", 0L)
                        rankingsList.add(RankedDomainSummary(domain, probeRtt, useful, fail, consec, score))
                    }
                    routesList.add(TargetRouteStatus(dcId, isMedia, activeDomain, rankingsList))
                }
                DomainBalancerStatus(gen, routesList)
            } catch (t: Throwable) {
                AppLogger.d("NativeProxy", "Failed to parse DomainBalancerStatus JSON: ${t.message}")
                null
            }
        }
    }
}

data class PathFingerprintData(
    val family: String,
    val ipPrefix: String,
    val colo: String,
    val asn: String
)

data class NodeTelemetryData(
    val domain: String,
    val resolvedIp: String?,
    val family: String?,
    val colo: String,
    val asn: String,
    val bytesBeforeStall: Long,
    val totalBytesTransferred: Long,
    val successCount: Long,
    val failureCount: Long,
    val consecutiveFailures: Int,
    val pathFingerprint: PathFingerprintData?,
    val failureCorrelationScore: Double
)

data class PathGroupStatsData(
    val fingerprintKey: String,
    val family: String,
    val ipPrefix: String,
    val colo: String,
    val asn: String,
    val nodeCount: Int,
    val totalSuccesses: Long,
    val totalFailures: Long,
    val failureRate: Double,
    val isBlocked: Boolean
)

data class NodeIndependenceStatus(
    val totalNodes: Int,
    val nodes: List<NodeTelemetryData>,
    val pathGroups: List<PathGroupStatsData>,
    val diversityRatio: Double
) {
    companion object {
        fun fromJson(json: String?): NodeIndependenceStatus? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val totalNodes = obj.optInt("total_nodes", 0)
                val diversityRatio = obj.optDouble("diversity_ratio", 1.0)

                val nodesArr = obj.optJSONArray("nodes") ?: JSONArray()
                val nodesList = ArrayList<NodeTelemetryData>()
                for (i in 0 until nodesArr.length()) {
                    val nObj = nodesArr.getJSONObject(i)
                    val domain = nObj.getString("domain")
                    val resolvedIp = if (nObj.isNull("resolved_ip")) null else nObj.optString("resolved_ip", null)
                    val family = if (nObj.isNull("family")) null else nObj.optString("family", null)
                    val colo = nObj.optString("colo", "UNKNOWN")
                    val asn = nObj.optString("asn", "UNKNOWN")
                    val bytesBeforeStall = nObj.optLong("bytes_before_stall", 0L)
                    val totalBytes = nObj.optLong("total_bytes_transferred", 0L)
                    val succ = nObj.optLong("success_count", 0L)
                    val fail = nObj.optLong("failure_count", 0L)
                    val consec = nObj.optInt("consecutive_failures", 0)
                    val corr = nObj.optDouble("failure_correlation_score", 0.0)

                    val fp = if (nObj.has("path_fingerprint") && !nObj.isNull("path_fingerprint")) {
                        val fpObj = nObj.getJSONObject("path_fingerprint")
                        PathFingerprintData(
                            family = fpObj.optString("family", ""),
                            ipPrefix = fpObj.optString("ip_prefix", ""),
                            colo = fpObj.optString("colo", ""),
                            asn = fpObj.optString("asn", "")
                        )
                    } else null

                    nodesList.add(
                        NodeTelemetryData(
                            domain, resolvedIp, family, colo, asn,
                            bytesBeforeStall, totalBytes, succ, fail, consec, fp, corr
                        )
                    )
                }

                val groupsArr = obj.optJSONArray("path_groups") ?: JSONArray()
                val groupsList = ArrayList<PathGroupStatsData>()
                for (i in 0 until groupsArr.length()) {
                    val gObj = groupsArr.getJSONObject(i)
                    groupsList.add(
                        PathGroupStatsData(
                            fingerprintKey = gObj.getString("fingerprint_key"),
                            family = gObj.optString("family", ""),
                            ipPrefix = gObj.optString("ip_prefix", ""),
                            colo = gObj.optString("colo", ""),
                            asn = gObj.optString("asn", ""),
                            nodeCount = gObj.optInt("node_count", 0),
                            totalSuccesses = gObj.optLong("total_successes", 0L),
                            totalFailures = gObj.optLong("total_failures", 0L),
                            failureRate = gObj.optDouble("failure_rate", 0.0),
                            isBlocked = gObj.optBoolean("is_blocked", false)
                        )
                    )
                }

                NodeIndependenceStatus(totalNodes, nodesList, groupsList, diversityRatio)
            } catch (t: Throwable) {
                AppLogger.d("NativeProxy", "Failed to parse NodeIndependenceStatus JSON: ${t.message}")
                null
            }
        }
    }
}

data class TlsHostStatsData(
    val hostname: String,
    val networkGeneration: Long,
    val fullHandshakes: Long,
    val resumedHandshakes: Long,
    val handshakeFailures: Long,
    val totalDurationMs: Long,
    val minDurationMs: Long,
    val maxDurationMs: Long,
    val avgDurationMs: Double,
    val resumptionRatio: Double,
    val lastHandshakeKind: String?,
    val lastDurationMs: Long,
    val lastError: String?
)

data class TlsObservabilityStatus(
    val currentNetworkGeneration: Long,
    val totalFullHandshakes: Long,
    val totalResumedHandshakes: Long,
    val totalHandshakeFailures: Long,
    val globalResumptionRatio: Double,
    val hosts: List<TlsHostStatsData>
) {
    companion object {
        fun fromJson(json: String?): TlsObservabilityStatus? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val currentGen = obj.optLong("current_network_generation", 1L)
                val totalFull = obj.optLong("total_full_handshakes", 0L)
                val totalResumed = obj.optLong("total_resumed_handshakes", 0L)
                val totalFailures = obj.optLong("total_handshake_failures", 0L)
                val globalResumptionRatio = obj.optDouble("global_resumption_ratio", 0.0)

                val hostsArr = obj.optJSONArray("hosts") ?: JSONArray()
                val hostsList = ArrayList<TlsHostStatsData>()
                for (i in 0 until hostsArr.length()) {
                    val hObj = hostsArr.getJSONObject(i)
                    hostsList.add(
                        TlsHostStatsData(
                            hostname = hObj.getString("hostname"),
                            networkGeneration = hObj.optLong("network_generation", 1L),
                            fullHandshakes = hObj.optLong("full_handshakes", 0L),
                            resumedHandshakes = hObj.optLong("resumed_handshakes", 0L),
                            handshakeFailures = hObj.optLong("handshake_failures", 0L),
                            totalDurationMs = hObj.optLong("total_duration_ms", 0L),
                            minDurationMs = hObj.optLong("min_duration_ms", 0L),
                            maxDurationMs = hObj.optLong("max_duration_ms", 0L),
                            avgDurationMs = hObj.optDouble("avg_duration_ms", 0.0),
                            resumptionRatio = hObj.optDouble("resumption_ratio", 0.0),
                            lastHandshakeKind = if (hObj.isNull("last_handshake_kind")) null else hObj.optString("last_handshake_kind", null),
                            lastDurationMs = hObj.optLong("last_duration_ms", 0L),
                            lastError = if (hObj.isNull("last_error")) null else hObj.optString("last_error", null)
                        )
                    )
                }

                TlsObservabilityStatus(
                    currentNetworkGeneration = currentGen,
                    totalFullHandshakes = totalFull,
                    totalResumedHandshakes = totalResumed,
                    totalHandshakeFailures = totalFailures,
                    globalResumptionRatio = globalResumptionRatio,
                    hosts = hostsList
                )
            } catch (t: Throwable) {
                AppLogger.d("NativeProxy", "Failed to parse TlsObservabilityStatus JSON: ${t.message}")
                null
            }
        }
    }
}

