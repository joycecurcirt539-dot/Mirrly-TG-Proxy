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
    fun SetPoolSize(size: Int)
    fun SetTcpNoDelay(enabled: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, userDomain: String)
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
}
