/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service.vpn

import com.mirrly.tgproxy.core.VpnSocketProtector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramSocket
import java.net.Socket

class VpnSocketProtectorTest {

    @Before
    @After
    fun cleanup() {
        VpnSocketProtector.unregister()
    }

    @Test
    fun testUnregisteredProtectorReturnsTrue() {
        assertFalse(VpnSocketProtector.isRegistered())
        val s = Socket()
        try {
            assertTrue(VpnSocketProtector.protect(s))
            val ds = DatagramSocket()
            try {
                assertTrue(VpnSocketProtector.protect(ds))
            } finally {
                ds.close()
            }
            assertTrue(VpnSocketProtector.protect(42))
        } finally {
            s.close()
        }
    }

    @Test
    fun testRegisteredProtectorDelegatesCorrectly() {
        val protectedFds = mutableListOf<Int>()
        var socketProtected = false
        var datagramProtected = false

        VpnSocketProtector.register(
            onProtectSocket = {
                socketProtected = true
                true
            },
            onProtectDatagram = {
                datagramProtected = true
                true
            },
            onProtectFd = { fd ->
                protectedFds.add(fd)
                true
            }
        )

        assertTrue(VpnSocketProtector.isRegistered())

        val s = Socket()
        try {
            assertTrue(VpnSocketProtector.protect(s))
            assertTrue(socketProtected)

            val ds = DatagramSocket()
            try {
                assertTrue(VpnSocketProtector.protect(ds))
                assertTrue(datagramProtected)
            } finally {
                ds.close()
            }

            assertTrue(VpnSocketProtector.protect(101))
            assertEquals(listOf(101), protectedFds)
        } finally {
            s.close()
        }
    }

    @Test
    fun testProtectorFailureReturnsFalse() {
        VpnSocketProtector.register(
            onProtectSocket = { false },
            onProtectDatagram = { false },
            onProtectFd = { false }
        )

        val s = Socket()
        try {
            assertFalse(VpnSocketProtector.protect(s))
            val ds = DatagramSocket()
            try {
                assertFalse(VpnSocketProtector.protect(ds))
            } finally {
                ds.close()
            }
            assertFalse(VpnSocketProtector.protect(99))
        } finally {
            s.close()
        }
    }
}
