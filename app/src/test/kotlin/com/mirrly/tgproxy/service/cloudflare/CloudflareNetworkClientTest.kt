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

package com.mirrly.tgproxy.service.cloudflare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class CloudflareNetworkClientTest {

    @Test
    fun testIsLoopbackAddress_IPv4() {
        val addr = InetAddress.getByName("127.0.0.1")
        assertTrue("127.0.0.1 must be recognized as loopback", CloudflareNetworkClient.isLoopbackAddress(addr))

        val addrSubnet = InetAddress.getByName("127.0.0.42")
        assertTrue("127.0.0.42 must be recognized as loopback", CloudflareNetworkClient.isLoopbackAddress(addrSubnet))
    }

    @Test
    fun testIsLoopbackAddress_IPv6() {
        val addr = InetAddress.getByName("::1")
        assertTrue("::1 must be recognized as loopback", CloudflareNetworkClient.isLoopbackAddress(addr))
    }

    @Test
    fun testIsLoopbackAddress_IPv4MappedIPv6() {
        // Dual-stack Android Linux kernel returns IPv4 mapped into IPv6 format
        val mappedBytes = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 1
        )
        val mappedAddr = InetAddress.getByAddress(mappedBytes)
        assertTrue("::ffff:127.0.0.1 must be recognized as loopback", CloudflareNetworkClient.isLoopbackAddress(mappedAddr))
    }

    @Test
    fun testIsLoopbackAddress_NonLoopback() {
        assertFalse("null must not be loopback", CloudflareNetworkClient.isLoopbackAddress(null))
        assertFalse("192.168.1.1 is not loopback", CloudflareNetworkClient.isLoopbackAddress(InetAddress.getByName("192.168.1.1")))
        assertFalse("8.8.8.8 is not loopback", CloudflareNetworkClient.isLoopbackAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse("1.1.1.1 is not loopback", CloudflareNetworkClient.isLoopbackAddress(InetAddress.getByName("1.1.1.1")))
    }
}
