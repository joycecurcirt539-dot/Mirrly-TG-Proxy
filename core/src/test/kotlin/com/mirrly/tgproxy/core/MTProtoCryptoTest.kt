package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MTProtoCryptoTest {

    @Test
    fun testGenerateRelayInitAndHandshake() {
        val protoTag = TgConstants.PROTO_TAG_INTERMEDIATE
        val dcIdx: Short = 4

        val relayInit = MTProtoCrypto.generateRelayInit(protoTag, dcIdx)
        assertEquals(TgConstants.HANDSHAKE_LEN, relayInit.size)

        // Verify reserved first byte check
        assertNotEquals(0xEF.toByte(), relayInit[0])
    }

    @Test
    fun testProxyConfigSecretToBytes() {
        val hex32 = "00112233445566778899aabbccddeeff"
        val bytes32 = ProxyConfig.hexToBytes(hex32)
        assertEquals(16, bytes32.size)
        assertEquals(0x00.toByte(), bytes32[0])
        assertEquals(0xff.toByte(), bytes32[15])
        assertEquals(hex32, ProxyConfig.bytesToHex(bytes32))

        val hex34 = "ee00112233445566778899aabbccddeeff"
        val bytes34 = ProxyConfig.hexToBytes(hex34)
        assertEquals(17, bytes34.size)
        assertEquals(0xee.toByte(), bytes34[0])
        assertEquals(0xff.toByte(), bytes34[16])
        assertEquals(hex34, ProxyConfig.bytesToHex(bytes34))

        // Test odd length input does not crash
        val oddHex = "123"
        val oddBytes = ProxyConfig.hexToBytes(oddHex)
        assertEquals(2, oddBytes.size) // "0123" -> 2 bytes
    }

    @Test
    fun testMsgSplitterAbridged() {
        val relayInit = ByteArray(64) { it.toByte() }
        val splitter = MsgSplitter(relayInit, TgConstants.PROTO_ABRIDGED_INT)

        // Test with empty chunk
        val parts = splitter.split(ByteArray(0))
        assertTrue(parts.isEmpty())
    }
}
