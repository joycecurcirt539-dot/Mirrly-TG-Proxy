package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WarpPacketFragmenterTest {

    @Test
    fun testCreateWireGuardInitiationProbeHasExact148BytesAndValidStructure() {
        val probe = WarpPacketFragmenter.createWireGuardInitiationProbe()
        assertEquals(148, probe.size, "WireGuard Initiation probe must be exactly 148 bytes")
        assertEquals(0x01.toByte(), probe[0], "Message type must be 1 (Handshake Initiation)")
        assertEquals(0x00.toByte(), probe[1], "Reserved byte 1 must be 0")
        assertEquals(0x00.toByte(), probe[2], "Reserved byte 2 must be 0")
        assertEquals(0x00.toByte(), probe[3], "Reserved byte 3 must be 0")
    }

    @Test
    fun testCreateQuicVersionNegotiationProbeTriggersNegotiation() {
        val probe = WarpPacketFragmenter.createQuicVersionNegotiationProbe()
        assertTrue(probe.size >= 64, "QUIC probe must be at least 64 bytes")
        val buf = ByteBuffer.wrap(probe).order(ByteOrder.BIG_ENDIAN)
        val firstByte = buf.get().toInt() and 0xFF
        assertTrue((firstByte and 0x80) != 0, "Must have Long Header bit set")
        val version = buf.getInt()
        assertEquals(0x1a2a3a4a, version, "Must match version 0x1a2a3a4a to trigger version negotiation")
    }

    @Test
    fun testCreateDesyncedJunkPrefixReturnsRequestedSize() {
        val junk48 = WarpPacketFragmenter.createDesyncedJunkPrefix(48)
        assertEquals(48, junk48.size)

        val junkClamped = WarpPacketFragmenter.createDesyncedJunkPrefix(10)
        assertEquals(24, junkClamped.size, "Size should be clamped to minimum safe size 24")
    }

    @Test
    fun testCalculateWireGuardMac1Generates16ByteDigest() {
        val probe = WarpPacketFragmenter.createWireGuardInitiationProbe()
        val peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

        val mac1 = WarpPacketFragmenter.calculateWireGuardMac1(probe, peerPublicKey)
        assertNotNull(mac1)
        assertEquals(16, mac1.size, "mac1 must be exactly 16 bytes per RFC 7693 / WireGuard specification")

        // mac1 must be deterministic for identical packet content and peer key
        val mac1Repeat = WarpPacketFragmenter.calculateWireGuardMac1(probe, peerPublicKey)
        assertEquals(mac1.toList(), mac1Repeat.toList(), "mac1 calculation must be deterministic")

        // mac1 in probe packet at bytes 116..131 must match calculated mac1
        val probeMac1 = probe.copyOfRange(116, 132)
        assertEquals(mac1.toList(), probeMac1.toList(), "Probe packet mac1 bytes must match calculated mac1")
    }
}
