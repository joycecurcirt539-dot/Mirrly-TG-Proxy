package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtonQuicInitialTest {

    @Test
    fun testBuildI1GeneratesValidHexPacket() {
        val i1 = ProtonQuicInitial.buildI1("www.gosuslugi.ru")
        assertTrue(i1.startsWith("<b 0x"), "i1 must start with <b 0x")
        assertTrue(i1.endsWith(">"), "i1 must end with >")

        val hex = i1.substringAfter("0x").substringBefore(">")
        // Length of hex should be around 2500 chars (1250 bytes * 2)
        assertTrue(hex.length >= 2400, "hex length should be >= 2400 chars, got ${hex.length}")

        // First byte should be long header with fixed bit (0xc0..0xcf)
        val firstByte = hex.substring(0, 2).toInt(16)
        assertTrue((firstByte and 0xC0) == 0xC0, "First byte should have 0xC0 set")

        // QUIC v1 version is 00000001 (bytes 1..4)
        val version = hex.substring(2, 10)
        assertTrue(version.equals("00000001", ignoreCase = true), "QUIC version must be 00000001")
    }

    @Test
    fun testBuildI1WithBlankSniReturnsEmpty() {
        val i1 = ProtonQuicInitial.buildI1("   ")
        assertTrue(i1.isEmpty())
    }
}
