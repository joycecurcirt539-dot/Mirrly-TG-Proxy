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

interface ProxyLibrary : Library {
    companion object {
        val INSTANCE: ProxyLibrary by lazy {
            Native.load("tgwsproxy", ProxyLibrary::class.java) as ProxyLibrary
        }
    }

    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun SetSecret(secret: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
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
        } catch (_: Throwable) {
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
            -1
        }
    }

    fun setPoolSize(size: Int) {
        if (!isStarted) return
        try {
            ProxyLibrary.INSTANCE.SetPoolSize(size)
        } catch (_: Throwable) {}
    }

    fun setCfProxyCacheDir(cacheDir: String) {
        try {
            ProxyLibrary.INSTANCE.SetCfProxyCacheDir(cacheDir)
        } catch (_: Throwable) {}
    }

    fun setCfProxyConfig(enabled: Boolean, priority: Boolean, userDomain: String) {
        try {
            ProxyLibrary.INSTANCE.SetCfProxyConfig(
                if (enabled) 1 else 0,
                if (priority) 1 else 0,
                userDomain
            )
        } catch (_: Throwable) {}
    }

    fun setSecret(secret: String) {
        if (!isStarted) return
        try {
            ProxyLibrary.INSTANCE.SetSecret(secret)
        } catch (_: Throwable) {}
    }

    fun getSecretWithPrefix(): String? {
        if (!isStarted) return null
        return try {
            val ptr = ProxyLibrary.INSTANCE.GetSecretWithPrefix() ?: return null
            val res = ptr.getString(0)
            ProxyLibrary.INSTANCE.FreeString(ptr)
            res
        } catch (_: Throwable) {
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
        } catch (_: Throwable) {
            null
        }
    }
}
