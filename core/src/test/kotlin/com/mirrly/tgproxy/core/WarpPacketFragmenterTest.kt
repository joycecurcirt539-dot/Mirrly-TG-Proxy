package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
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
    fun testFragmentIpv4UdpSplitsDatagramWithCorrectHeadersAndOffsets() {
        val src = InetAddress.getByName("192.168.1.100")
        val dst = InetAddress.getByName("188.114.96.1")
        val payload = WarpPacketFragmenter.createWireGuardInitiationProbe() // 148 bytes

        val (frag1, frag2) = WarpPacketFragmenter.fragmentIpv4Udp(
            srcIp = src,
            srcPort = 51820,
            dstIp = dst,
            dstPort = 500,
            udpPayload = payload,
            splitOffset = 64
        )

        assertNotNull(frag1)
        assertNotNull(frag2)

        // Parse Fragment 1 (IPv4 header = 20 bytes)
        val buf1 = ByteBuffer.wrap(frag1).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x45.toByte(), buf1.get(), "Frag 1 Version 4, IHL 5")
        buf1.get() // DSCP
        val totalLen1 = buf1.getShort().toInt() and 0xFFFF
        assertEquals(frag1.size, totalLen1)
        val id1 = buf1.getShort().toInt() and 0xFFFF
        val flagsAndOffset1 = buf1.getShort().toInt() and 0xFFFF
        // MF = 1 (bit 13 = 0x2000), Offset = 0
        assertEquals(0x2000, flagsAndOffset1 and 0x2000, "Frag 1 must have MF=1")
        assertEquals(0, flagsAndOffset1 and 0x1FFF, "Frag 1 offset must be 0")
        assertEquals(17.toByte(), buf1.get(9), "Protocol must be 17 (UDP)")

        // Parse Fragment 2
        val buf2 = ByteBuffer.wrap(frag2).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x45.toByte(), buf2.get(), "Frag 2 Version 4, IHL 5")
        buf2.get()
        val totalLen2 = buf2.getShort().toInt() and 0xFFFF
        assertEquals(frag2.size, totalLen2)
        val id2 = buf2.getShort().toInt() and 0xFFFF
        assertEquals(id1, id2, "Both fragments must share the same IP identification")
        val flagsAndOffset2 = buf2.getShort().toInt() and 0xFFFF
        assertEquals(0, flagsAndOffset2 and 0x2000, "Frag 2 must have MF=0")
        val offsetUnits = flagsAndOffset2 and 0x1FFF
        // Offset = (8 UDP header + 64 split bytes) / 8 = 9 units
        assertEquals(9, offsetUnits, "Frag 2 offset must match 9 units (72 bytes)")

        // Check combined payload size (frag1 payload + frag2 payload = 8 UDP header + 148 payload = 156)
        val frag1DataLen = frag1.size - 20
        val frag2DataLen = frag2.size - 20
        assertEquals(156, frag1DataLen + frag2DataLen, "Combined IP payload must equal full UDP datagram size")
    }

    @Test
    fun testFragmentIpv6UdpSplitsDatagramWithFragmentExtensionHeader() {
        val src = InetAddress.getByName("2001:db8::1")
        val dst = InetAddress.getByName("2606:4700:d0::a29f:c001")
        val payload = WarpPacketFragmenter.createWireGuardInitiationProbe()

        val (frag1, frag2) = WarpPacketFragmenter.fragmentIpv6Udp(
            srcIp = src,
            srcPort = 51820,
            dstIp = dst,
            dstPort = 500,
            udpPayload = payload,
            splitOffset = 64
        )

        assertNotNull(frag1)
        assertNotNull(frag2)

        // Check Next Header is Fragment Header (44)
        val buf1 = ByteBuffer.wrap(frag1).order(ByteOrder.BIG_ENDIAN)
        val nextHeader1 = buf1.get(6)
        assertEquals(44.toByte(), nextHeader1, "IPv6 Next Header must be 44 (Fragment Header)")

        val buf2 = ByteBuffer.wrap(frag2).order(ByteOrder.BIG_ENDIAN)
        val nextHeader2 = buf2.get(6)
        assertEquals(44.toByte(), nextHeader2, "IPv6 Next Header must be 44 (Fragment Header)")
    }
}
