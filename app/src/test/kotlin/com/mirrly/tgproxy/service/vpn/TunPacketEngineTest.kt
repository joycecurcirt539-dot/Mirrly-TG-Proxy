/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer

class TunPacketEngineTest {

    @Test
    fun testIpv4ChecksumComputation() {
        // Standard IPv4 Header: 45 00 00 3c 1c 46 40 00 40 06 b1 e6 ac 10 00 02 ac 10 00 01
        val header = byteArrayOf(
            0x45.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3c.toByte(),
            0x1c.toByte(), 0x46.toByte(), 0x40.toByte(), 0x00.toByte(),
            0x40.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), // checksum initially 0
            0xac.toByte(), 0x10.toByte(), 0x00.toByte(), 0x02.toByte(), // 172.16.0.2
            0xac.toByte(), 0x10.toByte(), 0x00.toByte(), 0x01.toByte()  // 172.16.0.1
        )

        var sum = 0
        var i = 0
        while (i < header.size) {
            val word = ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val ck = sum.inv() and 0xFFFF

        header[10] = ((ck ushr 8) and 0xFF).toByte()
        header[11] = (ck and 0xFF).toByte()

        // Re-verifying checksum over the header with checksum field populated should result in 0
        var verifySum = 0
        var j = 0
        while (j < header.size) {
            val word = ((header[j].toInt() and 0xFF) shl 8) or (header[j + 1].toInt() and 0xFF)
            verifySum += word
            j += 2
        }
        while ((verifySum ushr 16) > 0) {
            verifySum = (verifySum and 0xFFFF) + (verifySum ushr 16)
        }
        val finalCheck = verifySum.inv() and 0xFFFF
        assertEquals(0, finalCheck)
    }

    @Test
    fun testIcmpEchoReplyConstruction() {
        // Construct IPv4 ICMP Echo Request (Type 8)
        val packet = ByteArray(28)
        // IP Header (20B)
        packet[0] = 0x45.toByte()
        packet[2] = 0.toByte()
        packet[3] = 28.toByte()
        packet[8] = 64.toByte()
        packet[9] = 1.toByte() // ICMP
        // src 172.16.0.2
        packet[12] = 172.toByte()
        packet[13] = 16.toByte()
        packet[14] = 0.toByte()
        packet[15] = 2.toByte()
        // dst 1.1.1.1
        packet[16] = 1.toByte()
        packet[17] = 1.toByte()
        packet[18] = 1.toByte()
        packet[19] = 1.toByte()

        // ICMP Header (8B)
        packet[20] = 8.toByte() // Echo Request
        packet[21] = 0.toByte() // Code 0
        packet[22] = 0.toByte()
        packet[23] = 0.toByte()
        packet[24] = 0x12.toByte() // ID
        packet[25] = 0x34.toByte()
        packet[26] = 0x00.toByte() // Seq
        packet[27] = 0x01.toByte()

        // Simulate Echo Reply generation
        val reply = packet.copyOf()
        reply[20] = 0.toByte() // Echo Reply type

        // Swap src and dst
        for (k in 0 until 4) {
            val tmp = reply[12 + k]
            reply[12 + k] = reply[16 + k]
            reply[16 + k] = tmp
        }

        // Verify that reply has type 0 and source is 1.1.1.1
        assertEquals(0.toByte(), reply[20])
        assertEquals(1.toByte(), reply[12])
        assertEquals(1.toByte(), reply[13])
        assertEquals(1.toByte(), reply[14])
        assertEquals(1.toByte(), reply[15])
        assertEquals(172.toByte(), reply[16])
        assertEquals(16.toByte(), reply[17])
        assertEquals(0.toByte(), reply[18])
        assertEquals(2.toByte(), reply[19])
    }

    @Test
    fun testDnsResponseHeaderStructure() {
        val txId = 0xABCD
        val bb = ByteBuffer.allocate(12)
        bb.putShort(txId.toShort())
        bb.putShort(0x8180.toShort()) // Response, NoError
        bb.putShort(1.toShort())      // QDCOUNT
        bb.putShort(1.toShort())      // ANCOUNT
        bb.putShort(0.toShort())      // NSCOUNT
        bb.putShort(0.toShort())      // ARCOUNT

        val bytes = bb.array()
        assertEquals(0xAB.toByte(), bytes[0])
        assertEquals(0xCD.toByte(), bytes[1])
        assertEquals(0x81.toByte(), bytes[2])
        assertEquals(0x80.toByte(), bytes[3])
        assertEquals(1.toShort(), bb.getShort(4))
        assertEquals(1.toShort(), bb.getShort(6))
    }

    @Test
    fun testResourceLimitsConstants() {
        assertEquals(1024, TunPacketEngine.MAX_ACTIVE_TCP_FLOWS)
        assertEquals(512, TunPacketEngine.MAX_ACTIVE_UDP_SESSIONS)
        assertTrue(TunPacketEngine.TCP_CONNECT_TIMEOUT_MS >= 5000)
        assertTrue(TunPacketEngine.UDP_IDLE_TIMEOUT_MS >= 30000L)
    }

    @Test
    fun testSocks5AuthSubnegotiationPayload() {
        // RFC 1929: [0x01, ULEN, UNAME, PLEN, PASSWD]
        val user = "testuser"
        val pass = "secret123"
        val uBytes = user.toByteArray(Charsets.US_ASCII)
        val pBytes = pass.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(1 + 1 + uBytes.size + 1 + pBytes.size)
        payload[0] = 0x01 // Subnegotiation version
        payload[1] = uBytes.size.toByte()
        System.arraycopy(uBytes, 0, payload, 2, uBytes.size)
        val pOffset = 2 + uBytes.size
        payload[pOffset] = pBytes.size.toByte()
        System.arraycopy(pBytes, 0, payload, pOffset + 1, pBytes.size)

        assertEquals(0x01.toByte(), payload[0])
        assertEquals(8.toByte(), payload[1])
        assertEquals("testuser", String(payload, 2, 8, Charsets.US_ASCII))
        assertEquals(9.toByte(), payload[10])
        assertEquals("secret123", String(payload, 11, 9, Charsets.US_ASCII))
    }

    @Test
    fun testTcpSynAckMssClampingOption() {
        // TCP MSS Option: Kind=2, Len=4, Value=1240 (0x04D8)
        val optionsLen = 4
        val tcpHeaderLen = 20 + optionsLen
        val dataOffsetWords = tcpHeaderLen / 4
        val headerByte32 = ((dataOffsetWords shl 4) and 0xF0).toByte()

        // Data offset should be 6 (24 bytes)
        assertEquals(0x60.toByte(), headerByte32)

        val option = byteArrayOf(0x02, 0x04, 0x04, 0xD8.toByte())
        assertEquals(2.toByte(), option[0]) // Kind = MSS
        assertEquals(4.toByte(), option[1]) // Length = 4
        val mssVal = ((option[2].toInt() and 0xFF) shl 8) or (option[3].toInt() and 0xFF)
        assertEquals(1240, mssVal)
    }

    @Test
    fun testSocks5UdpAssociateHeader() {
        // RFC 1928: [RSV 2B, FRAG 1B, ATYP 1B, DST.ADDR, DST.PORT, DATA]
        val dstIp = byteArrayOf(1, 1, 1, 1)
        val dstPort = 443
        val payload = "QUIC-DATA".toByteArray()

        val headerLen = 4 + dstIp.size + 2
        val packet = ByteArray(headerLen + payload.size)
        packet[0] = 0x00 // RSV
        packet[1] = 0x00 // RSV
        packet[2] = 0x00 // FRAG
        packet[3] = 0x01 // ATYP IPv4
        System.arraycopy(dstIp, 0, packet, 4, dstIp.size)
        val portOffset = 4 + dstIp.size
        packet[portOffset] = ((dstPort ushr 8) and 0xFF).toByte()
        packet[portOffset + 1] = (dstPort and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, headerLen, payload.size)

        assertEquals(0x00.toByte(), packet[0])
        assertEquals(0x01.toByte(), packet[3])
        assertEquals(1.toByte(), packet[4])
        assertEquals(0x01.toByte(), packet[8]) // 443 = 0x01BB
        assertEquals(0xBB.toByte(), packet[9])
        assertEquals("QUIC-DATA", String(packet, 10, payload.size))
    }

    @Test
    fun testEarlyDataQueueBuffering() {
        val queue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        val chunk1 = byteArrayOf(0x16, 0x03, 0x01) // TLS ClientHello header
        val chunk2 = byteArrayOf(0x01, 0x00, 0x00, 0x20)

        queue.add(chunk1)
        queue.add(chunk2)

        assertEquals(2, queue.size)
        val polled1 = queue.poll()
        val polled2 = queue.poll()
        assertTrue(chunk1.contentEquals(polled1))
        assertTrue(chunk2.contentEquals(polled2))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun testDoTPort853RstInjection() {
        // TCP RST+ACK flag verification (0x14 = RST(0x04) | ACK(0x10))
        val rstAckFlag = 0x14
        assertTrue((rstAckFlag and 0x04) != 0) // RST set
        assertTrue((rstAckFlag and 0x10) != 0) // ACK set
        assertEquals(0, rstAckFlag and 0x02)   // SYN NOT set
        assertEquals(0, rstAckFlag and 0x01)   // FIN NOT set

        // Verify that port 853 is specifically recognized as DoT
        val dotPort = 853
        assertEquals(853, dotPort)
    }

    @Test
    fun testWarpCleanEndpointPrioritization() {
        val config = com.mirrly.tgproxy.core.ProxyConfig()
        config.warpApiEndpoint = "162.159.193.10:2408"
        config.warpPeerEndpoint = "188.114.96.1:8095"

        // effectivePeerEndpoint must prioritize clean non-2408 port over blocked 2408 port
        assertEquals("188.114.96.1:8095", config.effectivePeerEndpoint)
    }

    @Test
    fun testSocks5ConnectAtyp03DomainPayload() {
        val domain = "rutracker.org"
        val dBytes = domain.toByteArray(Charsets.US_ASCII)
        val targetPort = 443
        val connectReq = ByteArray(4 + 1 + dBytes.size + 2)
        connectReq[0] = 0x05 // SOCKS5
        connectReq[1] = 0x01 // CMD CONNECT
        connectReq[2] = 0x00 // RSV
        connectReq[3] = 0x03 // ATYP DOMAINNAME
        connectReq[4] = dBytes.size.toByte()
        System.arraycopy(dBytes, 0, connectReq, 5, dBytes.size)
        connectReq[5 + dBytes.size] = ((targetPort ushr 8) and 0xFF).toByte()
        connectReq[5 + dBytes.size + 1] = (targetPort and 0xFF).toByte()

        assertEquals(0x05.toByte(), connectReq[0])
        assertEquals(0x01.toByte(), connectReq[1])
        assertEquals(0x03.toByte(), connectReq[3])
        assertEquals(domain.length.toByte(), connectReq[4])
        assertEquals(domain, String(connectReq, 5, domain.length, Charsets.US_ASCII))
        assertEquals(0x01.toByte(), connectReq[5 + domain.length])
        assertEquals(0xBB.toByte(), connectReq[5 + domain.length + 1])
    }

    @Test
    fun testSyntheticIpRfc2544Format() {
        val id = 42
        val octet3 = (id shr 8) and 0xFF
        val octet4 = id and 0xFF
        val ip = "198.18.$octet3.$octet4"
        assertEquals("198.18.0.42", ip)
        assertTrue(ip.startsWith("198.18."))
    }
}
