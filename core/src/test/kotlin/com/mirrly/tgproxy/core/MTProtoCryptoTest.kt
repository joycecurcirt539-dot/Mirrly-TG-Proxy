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
        val hex = "ee00112233445566778899aabbccddeeff"
        val bytes = ProxyConfig.hexToBytes(hex)
        assertEquals(16, bytes.size)
        assertEquals(0xee.toByte(), bytes[0])
        assertEquals(0xff.toByte(), bytes[15])
        assertEquals(hex, ProxyConfig.bytesToHex(bytes))
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
