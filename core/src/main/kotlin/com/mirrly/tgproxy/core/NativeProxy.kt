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
    fun SetPoolSize(size: Int): Int
    fun SetTcpNoDelay(enabled: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, userDomain: String)
    fun SetTlsEnabled(enabled: Int)
    fun IsTlsEnabled(): Int
    fun SetTlsRecordPaddingEnabled(enabled: Int)
    fun IsTlsRecordPaddingEnabled(): Int
    fun SetTlsClientHelloSplitEnabled(enabled: Int)
    fun IsTlsClientHelloSplitEnabled(): Int
    fun SetSocks5Optimistic(enabled: Int)
    fun IsSocks5Optimistic(): Int
    fun SetSocks5UplinkMode(mode: Int)
    fun GetSocks5UplinkMode(): Int
    fun PromoteCfproxyDomainForDc(dcId: Int, isMedia: Int, domain: String): Int
    fun SetSecret(secret: String)
    fun SetSocks5Auth(username: String, password: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(p: Pointer)
    fun SetIpv6OnlyNetwork(isIpv6Only: Int)
    fun SetMtprotoStandbyPerActiveSlot(size: Int): Int
    fun GetTransportPoolStatusJson(): Pointer?
    fun GetWarmPoolStatusJson(): Pointer?
    fun SetSocketBufferSizes(recvSize: Int, sendSize: Int): Int
    fun GetSocketBufferStatusJson(): Pointer?
    fun GetLastOsSocketBufferSizes(recvOut: IntByReference, sendOut: IntByReference): Int
    fun SetNetworkGeneration(gen: Long)
    fun SetNetworkProfileJson(json: String): Int
    fun GetNetworkProfileJson(): Pointer?
    fun SuspendNetworkSockets()
    fun SetDialThrottlingEnabled(enabled: Int)
    fun IsDialThrottlingEnabled(): Int
    fun WarmupWsPool()
    fun OnScreenWakeup()
    fun GetUsefulRxSliJson(): Pointer?
    fun GetDialBudgetStatsJson(): Pointer?
    fun GetDomainBalancerStatusJson(): Pointer?
    fun GetNodeIndependenceStatusJson(): Pointer?
    fun GetTlsObservabilityStatusJson(): Pointer?
    fun SetProtectSocketCallback(cb: ProtectCallback?): Unit
    fun PingHost(host: String, port: Int, timeoutMs: Int): Pointer?
    fun PingWorker(domain: String, timeoutMs: Int): Pointer?
    fun ProbeTransportHealth(mode: Int, domain: String, timeoutMs: Int): Pointer?
    fun GetLastStartError(): Pointer?
}

interface VpnLibrary : Library {
    companion object {
        val INSTANCE: VpnLibrary by lazy {
            val lib = Native.load("mirrlyvpn", VpnLibrary::class.java) as VpnLibrary
            NativeProxy.isVpnLibraryLoaded = true
            NativeProxy.onVpnLibraryLoaded(lib)
            lib
        }
    }

    fun StartVpn(tunFd: Int, verbose: Int): Int
    fun StopVpn(): Int
    fun SetVpnUplinkMode(mode: Int): Int
    fun GetVpnStatusJson(): Pointer?
    fun ResetVpnSockets()
    fun NotifyVpnReconnect()
    fun SetAwgConfigParams(
        endpoint: String,
        privateKeyB64: String,
        publicKeyB64: String,
        peerPubB64: String,
        clientIpv4: String,
        jc: Int,
        jmin: Int,
        jmax: Int,
        s1: Int,
        s2: Int,
        h1: Int,
        h2: Int,
        h3: Int,
        h4: Int
    )
    fun SetAwgConfig(ini: String): Int
    fun SetAwgWarpMode()
    fun SetAwgKeepalive(secs: Int)
    fun GetAwgStatus(): Pointer?
    fun ProbeWarpEndpoints(endpointsCsv: String, protocol: Int, timeoutMs: Int): Pointer?
    fun ProbeVpnTransportHealth(domain: String, timeoutMs: Int): Pointer?
    fun SetVpnTlsEnabled(enabled: Int)
    fun IsVpnTlsEnabled(): Int
    fun SetVpnProtectSocketCallback(cb: ProtectCallback?)
    fun SetVpnNetworkGeneration(gen: Long)
    fun SetVpnNetworkProfile(isMobile: Int)
    fun SetVpnNetworkProfileJson(json: String): Int
    fun SetVpnIpv6OnlyNetwork(isIpv6Only: Int)
    fun SetVpnBatteryQoSLevel(level: Int)
    fun OnVpnScreenWakeup()
    fun SetVpnSocketBufferSizes(recvSize: Int, sendSize: Int): Int
    fun GetVpnStats(): Pointer?
    fun GetVpnLastError(): Pointer?
    fun FreeVpnString(p: Pointer)
}

fun interface ProtectCallback : com.sun.jna.Callback {
    fun invoke(fd: Int): Int
}

object NativeProxy {
    const val START_OK = 0
    const val START_ERR_ADDR_IN_USE = -3
    const val START_ERR_PERMISSION_DENIED = -4
    const val START_ERR_OTHER = -5

    @Volatile
    var isStarted: Boolean = false
        private set

    @Volatile
    var isVpnStarted: Boolean = false
        private set

    @Volatile
    var isVpnLibraryLoaded: Boolean = false
        internal set

    @Volatile
    private var activeProtectCallback: ProtectCallback? = null

    @Volatile
    private var cachedNetworkGeneration: Long? = null

    @Volatile
    private var cachedNetworkProfileJson: String? = null

    @Volatile
    private var cachedIpv6OnlyNetwork: Boolean? = null

    @Volatile
    private var cachedBatteryQoSLevel: QoSThrottleLevel? = null

    @Volatile
    private var cachedSocketBufferSizes: Pair<Int, Int>? = null

    @Volatile
    private var cachedVpnTlsEnabled: Boolean = true

    private fun isVpnEngineActive(): Boolean = isVpnStarted || isVpnLibraryLoaded

    internal fun onVpnLibraryLoaded(vpn: VpnLibrary) {
        try {
            activeProtectCallback?.let { vpn.SetVpnProtectSocketCallback(it) }
            vpn.SetVpnTlsEnabled(if (cachedVpnTlsEnabled) 1 else 0)
            cachedNetworkGeneration?.let { vpn.SetVpnNetworkGeneration(it) }
            cachedNetworkProfileJson?.let { vpn.SetVpnNetworkProfileJson(it) }
            cachedIpv6OnlyNetwork?.let { vpn.SetVpnIpv6OnlyNetwork(if (it) 1 else 0) }
            cachedBatteryQoSLevel?.let { lvl ->
                val code = when (lvl) {
                    QoSThrottleLevel.NONE -> 0
                    QoSThrottleLevel.MODERATE -> 1
                    QoSThrottleLevel.SEVERE -> 2
                }
                vpn.SetVpnBatteryQoSLevel(code)
            }
            cachedSocketBufferSizes?.let { (r, s) -> vpn.SetVpnSocketBufferSizes(r, s) }
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой первичной инициализации VpnLibrary: ${t.message}")
        }
    }

    fun setProtectCallback(onProtect: (Int) -> Boolean) {
        val cb = ProtectCallback { fd ->
            if (onProtect(fd)) 1 else 0
        }
        activeProtectCallback = cb
        try {
            ProxyLibrary.INSTANCE.SetProtectSocketCallback(cb)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой установки ProtectCallback [Proxy]: ${t.message}", t)
        }
        if (isVpnEngineActive()) {
            try {
                VpnLibrary.INSTANCE.SetVpnProtectSocketCallback(cb)
            } catch (t: Throwable) {
                AppLogger.e("NativeProxy", "Сбой установки ProtectCallback [VPN]: ${t.message}", t)
            }
        }
    }

    fun clearProtectCallback() {
        activeProtectCallback = null
        try {
            ProxyLibrary.INSTANCE.SetProtectSocketCallback(null)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой сброса ProtectCallback [Proxy]: ${t.message}", t)
        }
        if (isVpnEngineActive()) {
            try {
                VpnLibrary.INSTANCE.SetVpnProtectSocketCallback(null)
            } catch (t: Throwable) {
                AppLogger.e("NativeProxy", "Сбой сброса ProtectCallback [VPN]: ${t.message}", t)
            }
        }
    }

    fun syncVpnRuntimeState(
        tlsEnabled: Boolean? = null,
        networkGeneration: Long? = null,
        networkProfileJson: String? = null,
        isMobile: Boolean? = null,
        ipv6Only: Boolean? = null,
        qosLevel: QoSThrottleLevel? = null,
        socketBufferBytes: Int? = null
    ) {
        if (tlsEnabled != null) cachedVpnTlsEnabled = tlsEnabled
        if (networkGeneration != null && networkGeneration > 0L) cachedNetworkGeneration = networkGeneration
        if (networkProfileJson != null) cachedNetworkProfileJson = networkProfileJson
        if (ipv6Only != null) cachedIpv6OnlyNetwork = ipv6Only
        if (qosLevel != null) cachedBatteryQoSLevel = qosLevel
        if (socketBufferBytes != null) cachedSocketBufferSizes = Pair(socketBufferBytes, socketBufferBytes)

        try {
            val vpn = VpnLibrary.INSTANCE
            activeProtectCallback?.let { vpn.SetVpnProtectSocketCallback(it) }
            vpn.SetVpnTlsEnabled(if (cachedVpnTlsEnabled) 1 else 0)
            cachedNetworkGeneration?.let { vpn.SetVpnNetworkGeneration(it) }
            cachedNetworkProfileJson?.let { vpn.SetVpnNetworkProfileJson(it) }
            if (isMobile != null) {
                vpn.SetVpnNetworkProfile(if (isMobile) 1 else 0)
            }
            cachedIpv6OnlyNetwork?.let { vpn.SetVpnIpv6OnlyNetwork(if (it) 1 else 0) }
            cachedBatteryQoSLevel?.let { lvl ->
                val code = when (lvl) {
                    QoSThrottleLevel.NONE -> 0
                    QoSThrottleLevel.MODERATE -> 1
                    QoSThrottleLevel.SEVERE -> 2
                }
                vpn.SetVpnBatteryQoSLevel(code)
            }
            cachedSocketBufferSizes?.let { (r, s) -> vpn.SetVpnSocketBufferSizes(r, s) }
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой синхронизации состояния VPN: ${t.message}")
        }
    }

    fun startVpn(tunFd: Int, verbose: Boolean = false): Int {
        return try {
            syncVpnRuntimeState()
            val code = VpnLibrary.INSTANCE.StartVpn(tunFd, if (verbose) 1 else 0)
            if (code == 0) {
                isVpnStarted = true
            }
            code
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [startVpn]: ${t.message}", t)
            -1
        }
    }

    fun stopVpn(): Int {
        return try {
            isVpnStarted = false
            VpnLibrary.INSTANCE.StopVpn()
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [stopVpn]: ${t.message}", t)
            -1
        }
    }

    fun resetVpnSockets() {
        try {
            VpnLibrary.INSTANCE.ResetVpnSockets()
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [ResetVpnSockets]: ${t.message}")
        }
    }

    fun notifyVpnReconnect() {
        try {
            VpnLibrary.INSTANCE.NotifyVpnReconnect()
        } catch (t: Throwable) {
            resetVpnSockets()
        }
    }

    fun getVpnStatusJson(): String? {
        return try {
            val ptr = VpnLibrary.INSTANCE.GetVpnStatusJson() ?: return null
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                VpnLibrary.INSTANCE.FreeVpnString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [getVpnStatusJson]: ${t.message}", t)
            null
        }
    }

    fun setVpnUplinkMode(mode: UplinkMode): Int {
        val nativeMode = when (mode) {
            UplinkMode.AWG -> 0
            else -> 0
        }
        return try {
            VpnLibrary.INSTANCE.SetVpnUplinkMode(nativeMode)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [SetVpnUplinkMode]: ${t.message}", t)
            -1
        }
    }

    fun setVpnTlsEnabled(enabled: Boolean) {
        cachedVpnTlsEnabled = enabled
        if (!isVpnEngineActive()) return
        try {
            VpnLibrary.INSTANCE.SetVpnTlsEnabled(if (enabled) 1 else 0)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "FFI call failed [SetVpnTlsEnabled]: ${t.message}", t)
        }
    }

    fun isVpnTlsEnabled(): Boolean {
        if (!isVpnEngineActive()) return cachedVpnTlsEnabled
        return try {
            VpnLibrary.INSTANCE.IsVpnTlsEnabled() != 0
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "FFI call failed [IsVpnTlsEnabled]: ${t.message}", t)
            cachedVpnTlsEnabled
        }
    }

    fun setVpnNetworkGeneration(gen: Long) {
        if (gen > 0L) {
            cachedNetworkGeneration = gen
        }
        if (!isVpnEngineActive()) return
        try {
            VpnLibrary.INSTANCE.SetVpnNetworkGeneration(gen)
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [SetVpnNetworkGeneration]: ${t.message}")
        }
    }

    fun setVpnNetworkProfile(isMobile: Boolean) {
        if (!isVpnEngineActive()) return
        try {
            VpnLibrary.INSTANCE.SetVpnNetworkProfile(if (isMobile) 1 else 0)
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [SetVpnNetworkProfile]: ${t.message}")
        }
    }

    fun getVpnStats(): String? {
        return try {
            val ptr = VpnLibrary.INSTANCE.GetVpnStats() ?: return null
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                VpnLibrary.INSTANCE.FreeVpnString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [GetVpnStats] error: ${t.message}")
            null
        }
    }

    fun getVpnLastError(): String? {
        return try {
            val ptr = VpnLibrary.INSTANCE.GetVpnLastError() ?: return null
            try {
                ptr.getString(0, "UTF-8").trim().takeIf { it.isNotEmpty() }
            } finally {
                VpnLibrary.INSTANCE.FreeVpnString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [GetVpnLastError] error: ${t.message}")
            null
        }
    }

    fun probeWarpEndpoints(endpointsCsv: String, protocol: Int = 0, timeoutMs: Int = 600): String {
        return try {
            activeProtectCallback?.let { VpnLibrary.INSTANCE.SetVpnProtectSocketCallback(it) }
            val ptr = VpnLibrary.INSTANCE.ProbeWarpEndpoints(endpointsCsv, protocol, timeoutMs) ?: return "[]"
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                VpnLibrary.INSTANCE.FreeVpnString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [ProbeWarpEndpoints] error: ${t.message}")
            "[]"
        }
    }

    fun pingHost(host: String, port: Int = 443, timeoutMs: Int = 2500): Pair<Boolean, Long> {
        return try {
            val ptr = ProxyLibrary.INSTANCE.PingHost(host, port, timeoutMs) ?: return Pair(false, -1L)
            val jsonStr = try {
                ptr.getString(0, "UTF-8")
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
            val json = JSONObject(jsonStr)
            val success = json.optBoolean("success", false)
            val rtt = json.optLong("rtt_ms", -1L)
            Pair(success, rtt)
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [PingHost] error: ${t.message}")
            Pair(false, -1L)
        }
    }

    fun pingWorker(domain: String, timeoutMs: Int = 5000): Pair<String, Long?> {
        return try {
            val ptr = ProxyLibrary.INSTANCE.PingWorker(domain, timeoutMs) ?: return Pair("ERR_NULL_PTR", null)
            val jsonStr = try {
                ptr.getString(0, "UTF-8")
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
            val json = JSONObject(jsonStr)
            val status = json.optString("status", "UNKNOWN")
            val rtt = if (json.isNull("rtt_ms")) null else json.optLong("rtt_ms")
            Pair(status, rtt)
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [PingWorker] error: ${t.message}")
            Pair("ERR_EXCEPTION", null)
        }
    }

    fun probeTransportHealth(
        mode: Int,
        domain: String = "",
        timeoutMs: Int = 2500
    ): TransportProbeOutcome? {
        return try {
            val jsonStr = if (mode == 4) {
                val ptr = VpnLibrary.INSTANCE.ProbeVpnTransportHealth(domain, timeoutMs) ?: return null
                try {
                    ptr.getString(0, "UTF-8")
                } finally {
                    VpnLibrary.INSTANCE.FreeVpnString(ptr)
                }
            } else {
                val ptr = ProxyLibrary.INSTANCE.ProbeTransportHealth(mode, domain, timeoutMs) ?: return null
                try {
                    ptr.getString(0, "UTF-8")
                } finally {
                    ProxyLibrary.INSTANCE.FreeString(ptr)
                }
            }
            TransportProbeOutcome.fromJson(jsonStr)
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [ProbeTransportHealth] error: ${t.message}")
            null
        }
    }

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

    fun getLastStartError(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetLastStartError() ?: return null
            try {
                ptr.getString(0, "UTF-8").trim().takeIf { it.isNotEmpty() }
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [GetLastStartError] error: ${t.message}")
            null
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

    fun setPoolSize(size: Int): Int? {
        if (!isStarted) return null
        return try {
            ProxyLibrary.INSTANCE.SetPoolSize(size)
        } catch (_: UnsatisfiedLinkError) {
            // Плавный фоллбек для устаревших нативных библиотек
            try {
                ProxyLibrary.INSTANCE.SetMtprotoStandbyPerActiveSlot(size.coerceIn(1, 4))
            } catch (_: Throwable) {
                null
            }
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [setPoolSize]: ${t.message}")
            null
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

    /**
     * Pins a preflight-verified Flowseal Anycast base domain for one Telegram
     * DC. This is an MTProto CDN preference, never a Cloudflare Worker route.
     */
    fun promoteMtprotoAnycastDomain(dcId: Int, domain: String, isMedia: Boolean = false): Boolean {
        if (dcId !in 1..5 || domain.isBlank()) return false
        return try {
            ProxyLibrary.INSTANCE.PromoteCfproxyDomainForDc(dcId, if (isMedia) 1 else 0, domain) != 0
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Не удалось применить Anycast-кандидат DC$dcId: ${t.message}")
            false
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

    fun setSocks5Optimistic(enabled: Boolean) {
        try {
            ProxyLibrary.INSTANCE.SetSocks5Optimistic(if (enabled) 1 else 0)
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "FFI [SetSocks5Optimistic] не доступен: ${t.message}")
        }
    }

    fun isSocks5Optimistic(): Boolean {
        return try {
            ProxyLibrary.INSTANCE.IsSocks5Optimistic() != 0
        } catch (_: Throwable) {
            true
        }
    }

    fun setSocks5UplinkMode(mode: Int) {
        try {
            ProxyLibrary.INSTANCE.SetSocks5UplinkMode(mode)
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "FFI [SetSocks5UplinkMode] не доступен: ${t.message}")
        }
    }

    fun getSocks5UplinkMode(): Int {
        return try {
            ProxyLibrary.INSTANCE.GetSocks5UplinkMode()
        } catch (_: Throwable) {
            0
        }
    }

    fun setUplinkMode(mode: UplinkMode) {
        if (isVpnEngineActive()) {
            setVpnUplinkMode(mode)
        }
    }

    /**
     * Загружает конфиг AmneziaWG из INI-строки в нативное ядро.
     * @return true при успехе, false при ошибке парсинга
     */
    fun setAwgConfig(ini: String): Boolean {
        return try {
            val code = VpnLibrary.INSTANCE.SetAwgConfig(ini)
            if (code != 0) {
                AppLogger.e("NativeProxy", "SetAwgConfig вернул код ошибки: $code")
            }
            code == 0
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [SetAwgConfig]: ${t.message}", t)
            false
        }
    }

    fun setAwgConfigParams(
        endpoint: String,
        privateKeyB64: String,
        publicKeyB64: String,
        peerPubB64: String,
        clientIpv4: String,
        jc: Int,
        jmin: Int,
        jmax: Int,
        s1: Int,
        s2: Int,
        h1: Int,
        h2: Int,
        h3: Int,
        h4: Int
    ) {
        try {
            VpnLibrary.INSTANCE.SetAwgConfigParams(
                endpoint,
                privateKeyB64,
                publicKeyB64,
                peerPubB64,
                clientIpv4,
                jc,
                jmin,
                jmax,
                s1,
                s2,
                h1,
                h2,
                h3,
                h4
            )
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [SetAwgConfigParams]: ${t.message}", t)
        }
    }

    fun setAwgWarpMode() {
        try {
            VpnLibrary.INSTANCE.SetAwgWarpMode()
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [SetAwgWarpMode]: ${t.message}")
        }
    }

    /** Возвращает строку диагностического статуса AWG конфига из нативного ядра. */
    fun getAwgStatus(): String {
        return try {
            val ptr = VpnLibrary.INSTANCE.GetAwgStatus() ?: return "awg:unavailable"
            try {
                ptr.getString(0, "UTF-8") ?: "awg:null"
            } finally {
                VpnLibrary.INSTANCE.FreeVpnString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.d("NativeProxy", "FFI [GetAwgStatus] недоступен: ${t.message}")
            "awg:unavailable"
        }
    }

    fun setBatteryQoSLevel(level: QoSThrottleLevel) {
        cachedBatteryQoSLevel = level
        val code = when (level) {
            QoSThrottleLevel.NONE -> 0
            QoSThrottleLevel.MODERATE -> 1
            QoSThrottleLevel.SEVERE -> 2
        }
        if (isVpnEngineActive()) {
            try {
                VpnLibrary.INSTANCE.SetVpnBatteryQoSLevel(code)
            } catch (t: Throwable) {
                AppLogger.w("NativeProxy", "Сбой вызова FFI [SetVpnBatteryQoSLevel]: ${t.message}")
            }
        }
    }

    fun setAwgKeepalive(secs: Int) {
        if (!isVpnEngineActive()) return
        try {
            VpnLibrary.INSTANCE.SetAwgKeepalive(secs)
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [setAwgKeepalive]: ${t.message}")
        }
    }

    fun getActiveCascadeStage(): Int {
        return 0
    }

    fun getSecretWithPrefix(): String? {
        if (!isStarted) return null
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetSecretWithPrefix() ?: return null
            try {
                ptr.getString(0, "UTF-8").trim().takeIf { it.isNotEmpty() }
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [getSecretWithPrefix]: ${t.message}", t)
            null
        }
    }

    fun getStats(): String? {
        if (!isStarted) return null
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetStats() ?: return null
            try {
                ptr.getString(0)
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "Сбой вызова FFI [getStats]: ${t.message}", t)
            null
        }
    }

    fun setIpv6OnlyNetwork(isIpv6Only: Boolean) {
        cachedIpv6OnlyNetwork = isIpv6Only
        val flag = if (isIpv6Only) 1 else 0
        try {
            ProxyLibrary.INSTANCE.SetIpv6OnlyNetwork(flag)
        } catch (_: UnsatisfiedLinkError) {
        } catch (_: NoClassDefFoundError) {
        } catch (_: Throwable) {}
        if (isVpnEngineActive()) {
            try {
                VpnLibrary.INSTANCE.SetVpnIpv6OnlyNetwork(flag)
            } catch (_: UnsatisfiedLinkError) {
            } catch (_: NoClassDefFoundError) {
            } catch (_: Throwable) {}
        }
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
        cachedSocketBufferSizes = Pair(recvSize, sendSize)
        val proxyOk = try {
            ProxyLibrary.INSTANCE.SetSocketBufferSizes(recvSize, sendSize) == 0
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [SetSocketBufferSizes]: ${t.message}")
            false
        }
        if (isVpnEngineActive()) {
            try {
                VpnLibrary.INSTANCE.SetVpnSocketBufferSizes(recvSize, sendSize)
            } catch (t: Throwable) {
                AppLogger.w("NativeProxy", "Сбой вызова FFI [SetVpnSocketBufferSizes]: ${t.message}")
            }
        }
        return proxyOk
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
        if (gen > 0L) {
            cachedNetworkGeneration = gen
        }
        try {
            ProxyLibrary.INSTANCE.SetNetworkGeneration(gen)
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [SetNetworkGeneration]: ${t.message}")
        }
        if (isVpnEngineActive()) {
            try {
                VpnLibrary.INSTANCE.SetVpnNetworkGeneration(gen)
            } catch (t: Throwable) {
                AppLogger.w("NativeProxy", "Сбой вызова FFI [SetVpnNetworkGeneration]: ${t.message}")
            }
        }
    }

    fun setNetworkProfileJson(json: String): Boolean {
        cachedNetworkProfileJson = json
        val proxyOk = try {
            ProxyLibrary.INSTANCE.SetNetworkProfileJson(json) == 0
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [SetNetworkProfileJson]: ${t.message}")
            false
        }
        if (isVpnEngineActive()) {
            try {
                VpnLibrary.INSTANCE.SetVpnNetworkProfileJson(json)
            } catch (t: Throwable) {
                AppLogger.w("NativeProxy", "Сбой вызова FFI [SetVpnNetworkProfileJson]: ${t.message}")
            }
        }
        return proxyOk
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

    fun setDialThrottlingEnabled(enabled: Boolean) {
        try {
            ProxyLibrary.INSTANCE.SetDialThrottlingEnabled(if (enabled) 1 else 0)
        } catch (t: Throwable) {
            AppLogger.w("NativeProxy", "Сбой вызова FFI [SetDialThrottlingEnabled]: ${t.message}")
        }
    }

    fun isDialThrottlingEnabled(): Boolean {
        return try {
            ProxyLibrary.INSTANCE.IsDialThrottlingEnabled() != 0
        } catch (_: Throwable) {
            false
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
        if (isStarted) {
            try {
                ProxyLibrary.INSTANCE.OnScreenWakeup()
            } catch (t: Throwable) {
                AppLogger.e("NativeProxy", "Сбой вызова FFI [OnScreenWakeup]: ${t.message}", t)
            }
        }
        if (isVpnEngineActive()) {
            try {
                VpnLibrary.INSTANCE.OnVpnScreenWakeup()
            } catch (t: Throwable) {
                AppLogger.e("NativeProxy", "Сбой вызова FFI [OnVpnScreenWakeup]: ${t.message}", t)
            }
        }
    }

    fun setTrustPolicy(
        @Suppress("UNUSED_PARAMETER") isPrivateNode: Boolean = false,
        @Suppress("UNUSED_PARAMETER") allowPublicRelayFallback: Boolean = false
    ) {
        // Legacy trust policy stub removed from standalone mirrlyengine
    }

    fun getStageTimelineJson(): String? {
        return null
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

    fun getUsefulRxSli(): EngineSliSnapshot? {
        val json = getUsefulRxSliJson() ?: return null
        return EngineSliSnapshot.fromJson(json)
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

    fun getWarmPoolStatusJson(): String? {
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetWarmPoolStatusJson() ?: return null
            try {
                ptr.getString(0, "UTF-8")
            } finally {
                ProxyLibrary.INSTANCE.FreeString(ptr)
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun getWarmPoolStatus(): WarmPoolStatus? {
        val json = getWarmPoolStatusJson() ?: return null
        return WarmPoolStatus.fromJson(json)
    }

    fun setEchEnabled(enabled: Boolean) {
        // ECH handled at transport layer
    }

    fun isEchEnabled(): Boolean {
        return false
    }

    fun setCustomEchConfig(base64: String): Boolean {
        return false
    }

    fun setWsRandomizationEnabled(enabled: Boolean) {
        // WS randomization handled at transport layer
    }

    fun isWsRandomizationEnabled(): Boolean {
        return false
    }

    fun setTlsEnabled(enabled: Boolean) {
        try {
            ProxyLibrary.INSTANCE.SetTlsEnabled(if (enabled) 1 else 0)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "FFI call failed [SetTlsEnabled]: ${t.message}", t)
        }
    }

    fun isTlsEnabled(): Boolean {
        return try {
            ProxyLibrary.INSTANCE.IsTlsEnabled() != 0
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "FFI call failed [IsTlsEnabled]: ${t.message}", t)
            false
        }
    }

    fun setTlsRecordPaddingEnabled(enabled: Boolean) {
        try {
            ProxyLibrary.INSTANCE.SetTlsRecordPaddingEnabled(if (enabled) 1 else 0)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "FFI call failed [SetTlsRecordPaddingEnabled]: ${t.message}", t)
        }
    }

    fun isTlsRecordPaddingEnabled(): Boolean {
        return try {
            ProxyLibrary.INSTANCE.IsTlsRecordPaddingEnabled() != 0
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "FFI call failed [IsTlsRecordPaddingEnabled]: ${t.message}", t)
            true
        }
    }

    fun setTlsClientHelloSplitEnabled(enabled: Boolean) {
        try {
            ProxyLibrary.INSTANCE.SetTlsClientHelloSplitEnabled(if (enabled) 1 else 0)
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "FFI call failed [SetTlsClientHelloSplitEnabled]: ${t.message}", t)
        }
    }

    fun isTlsClientHelloSplitEnabled(): Boolean {
        return try {
            ProxyLibrary.INSTANCE.IsTlsClientHelloSplitEnabled() != 0
        } catch (t: Throwable) {
            AppLogger.e("NativeProxy", "FFI call failed [IsTlsClientHelloSplitEnabled]: ${t.message}", t)
            true
        }
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

data class WarmPoolStatus(
    val ready_total: Int = 0,
    val target_total: Int = 0,
    val in_flight_total: Int = 0,
    val dc2_chat_ready: Int = 0,
    val dc2_chat_target: Int = 0,
    val dc2_media_ready: Int = 0,
    val dc2_media_target: Int = 0,
    val dc4_media_ready: Int = 0,
    val dc4_media_target: Int = 0
) {
    val remaining: Int
        get() = (target_total - ready_total).coerceAtLeast(0)

    companion object {
        fun fromJson(json: String?): WarmPoolStatus? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                WarmPoolStatus(
                    ready_total = obj.optInt("ready_total", 0),
                    target_total = obj.optInt("target_total", 0),
                    in_flight_total = obj.optInt("in_flight_total", 0),
                    dc2_chat_ready = obj.optInt("dc2_chat_ready", 0),
                    dc2_chat_target = obj.optInt("dc2_chat_target", 0),
                    dc2_media_ready = obj.optInt("dc2_media_ready", 0),
                    dc2_media_target = obj.optInt("dc2_media_target", 0),
                    dc4_media_ready = obj.optInt("dc4_media_ready", 0),
                    dc4_media_target = obj.optInt("dc4_media_target", 0)
                )
            } catch (t: Throwable) {
                AppLogger.d("NativeProxy", "Failed to parse WarmPoolStatus JSON: ${t.message}")
                null
            }
        }
    }
}

data class EngineSliSnapshot(
    val transport: String = "",
    val tlsEnabled: Boolean = false,
    val activeConns: Int = 0,
    val connectionsTotal: Long = 0L,
    val connectionsWs: Long = 0L,
    val connectionsCfproxy: Long = 0L,
    val connectionsAwg: Long = 0L,
    val socks5V2Sessions: Long = 0L,
    val socks5V1Downgrades: Long = 0L,
    val socks5FastpathHits: Long = 0L,
    val socks5FallbackTriggers: Long = 0L,
    val connectionsBad: Long = 0L,
    val wsErrors: Long = 0L,
    val poolHits: Long = 0L,
    val poolMisses: Long = 0L,
    val warmReady: Int = 0,
    val warmTarget: Int = 0,
    val warmInflight: Int = 0,
    val mtproto429Hits: Long = 0L,
    val socks5429Hits: Long = 0L,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val tlsFailures: Long = 0L,
    val balancerActiveDomain: String = "",
    val balancerHealthyDomains: Int = 0,
    val balancerTotalDomains: Int = 0,
    val balancerMinRttMs: Long = -1L,
    val balancerMedianRttMs: Long = -1L,
    val balancerUsefulSuccessCount: Long = 0L,
    val balancerFailureCount: Long = 0L,
    val vpnEstablished: Boolean = false,
    // Deltas compared to the previous 1s sample in LocalProxyServer
    val deltaRxBytes: Long = 0L,
    val deltaUsefulSuccess: Long = 0L,
    val deltaWsErrors: Long = 0L,
    val deltaTlsFailures: Long = 0L,
    val delta429Hits: Long = 0L,
    val lastLiveTrafficAtMs: Long = 0L
) {
    val total429Hits: Long
        get() = mtproto429Hits + socks5429Hits

    val effectiveBalancerRttMs: Long
        get() = if (balancerMedianRttMs > 0L) balancerMedianRttMs else balancerMinRttMs

    fun hasFreshLiveTraffic(nowMs: Long = System.currentTimeMillis()): Boolean {
        val recentActivity = lastLiveTrafficAtMs > 0L && (nowMs - lastLiveTrafficAtMs) <= 20_000L
        val healthyVpn = vpnEstablished && (connectionsAwg > 0L || rxBytes > 0L)
        return recentActivity || deltaUsefulSuccess > 0L || deltaRxBytes > 256L || healthyVpn
    }

    val liveSessionSuccessFloorPercent: Int
        get() = when {
            deltaWsErrors == 0L && delta429Hits == 0L -> 86
            delta429Hits > 0L -> 55
            else -> 68
        }

    fun withDeltaFrom(previous: EngineSliSnapshot?, nowMs: Long = System.currentTimeMillis()): EngineSliSnapshot {
        val dRx = if (previous != null && rxBytes >= previous.rxBytes) rxBytes - previous.rxBytes else 0L
        val dUseful = if (previous != null && balancerUsefulSuccessCount >= previous.balancerUsefulSuccessCount) {
            balancerUsefulSuccessCount - previous.balancerUsefulSuccessCount
        } else {
            0L
        }
        val dErr = if (previous != null && wsErrors >= previous.wsErrors) wsErrors - previous.wsErrors else 0L
        val dTlsErr = if (previous != null && tlsFailures >= previous.tlsFailures) tlsFailures - previous.tlsFailures else 0L
        val d429 = if (previous != null && total429Hits >= previous.total429Hits) total429Hits - previous.total429Hits else 0L

        val trafficNow = dRx > 128L ||
            dUseful > 0L ||
            (previous != null && connectionsWs > previous.connectionsWs && dErr == 0L) ||
            (vpnEstablished && dRx > 0L)
        val liveTs = if (trafficNow) {
            nowMs
        } else {
            previous?.lastLiveTrafficAtMs ?: if (balancerUsefulSuccessCount > 0L || rxBytes > 1024L || vpnEstablished) nowMs else 0L
        }

        return copy(
            deltaRxBytes = dRx,
            deltaUsefulSuccess = dUseful,
            deltaWsErrors = dErr,
            deltaTlsFailures = dTlsErr,
            delta429Hits = d429,
            lastLiveTrafficAtMs = liveTs
        )
    }

    companion object {
        fun fromJson(json: String?): EngineSliSnapshot? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                if (obj.length() == 0) return null
                val activeDomain = if (obj.isNull("balancer_active_domain")) "" else obj.optString("balancer_active_domain", "")
                val minRtt = if (obj.isNull("balancer_min_rtt_ms")) -1L else obj.optLong("balancer_min_rtt_ms", -1L)
                val medRtt = if (obj.isNull("balancer_median_rtt_ms")) -1L else obj.optLong("balancer_median_rtt_ms", -1L)
                EngineSliSnapshot(
                    transport = obj.optString("transport", ""),
                    tlsEnabled = obj.optBoolean("tls_enabled", false),
                    activeConns = obj.optInt("active_conns", 0),
                    connectionsTotal = obj.optLong("connections_total", 0L),
                    connectionsWs = obj.optLong("connections_ws", 0L),
                    connectionsCfproxy = obj.optLong("connections_cfproxy", 0L),
                    connectionsAwg = obj.optLong("connections_awg", 0L),
                    socks5V2Sessions = obj.optLong("socks5_v2_sessions", 0L),
                    socks5V1Downgrades = obj.optLong("socks5_v1_downgrades", 0L),
                    socks5FastpathHits = obj.optLong("socks5_fastpath_hits", 0L),
                    socks5FallbackTriggers = obj.optLong("socks5_fallback_triggers", 0L),
                    connectionsBad = obj.optLong("connections_bad", 0L),
                    wsErrors = obj.optLong("ws_errors", 0L),
                    poolHits = obj.optLong("pool_hits", 0L),
                    poolMisses = obj.optLong("pool_misses", 0L),
                    warmReady = obj.optInt("warm_ready", 0),
                    warmTarget = obj.optInt("warm_target", 0),
                    warmInflight = obj.optInt("warm_inflight", 0),
                    mtproto429Hits = obj.optLong("mtproto_429_hits", 0L),
                    socks5429Hits = obj.optLong("socks5_429_hits", 0L),
                    rxBytes = obj.optLong("rx_bytes", 0L),
                    txBytes = obj.optLong("tx_bytes", 0L),
                    tlsFailures = obj.optLong("tls_failures", 0L),
                    balancerActiveDomain = activeDomain,
                    balancerHealthyDomains = obj.optInt("balancer_healthy_domains", 0),
                    balancerTotalDomains = obj.optInt("balancer_total_domains", 0),
                    balancerMinRttMs = minRtt,
                    balancerMedianRttMs = medRtt,
                    balancerUsefulSuccessCount = obj.optLong("balancer_useful_success_count", 0L),
                    balancerFailureCount = obj.optLong("balancer_failure_count", 0L),
                    vpnEstablished = obj.optBoolean("vpn_established", false)
                )
            } catch (t: Throwable) {
                AppLogger.d("NativeProxy", "Failed to parse EngineSliSnapshot JSON: ${t.message}")
                null
            }
        }
    }
}

data class TransportProbeOutcome(
    val alive: Boolean,
    val rttMs: Long,
    val domain: String,
    val ip: String,
    val colo: String,
    val stage: String,
    val failure: FailureType
) {
    companion object {
        fun fromJson(json: String?): TransportProbeOutcome? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val alive = obj.optBoolean("alive", false)
                val rtt = obj.optLong("rtt_ms", -1L)
                val failStr = obj.optString("failure", "NONE").uppercase()
                val failure = when (failStr) {
                    "NONE" -> FailureType.NONE
                    "RATE_LIMITED_429" -> FailureType.RATE_LIMITED_429
                    "DPI_BLOCKED" -> FailureType.DPI_BLOCKED
                    "TLS_HANDSHAKE_FAILED" -> FailureType.TLS_HANDSHAKE_FAILED
                    "CONNECT_TIMEOUT" -> FailureType.CONNECT_TIMEOUT
                    "HOST_RESOLUTION_FAILED", "DNS_FAILURE" -> FailureType.DNS_FAILURE
                    "WARP_HANDSHAKE_TIMEOUT" -> FailureType.WARP_HANDSHAKE_TIMEOUT
                    else -> if (alive) FailureType.NONE else FailureType.HOST_UNREACHABLE
                }
                TransportProbeOutcome(
                    alive = alive,
                    rttMs = rtt,
                    domain = obj.optString("domain", ""),
                    ip = obj.optString("ip", ""),
                    colo = obj.optString("colo", ""),
                    stage = obj.optString("stage", ""),
                    failure = failure
                )
            } catch (t: Throwable) {
                AppLogger.d("NativeProxy", "Failed to parse TransportProbeOutcome JSON: ${t.message}")
                null
            }
        }
    }
}

