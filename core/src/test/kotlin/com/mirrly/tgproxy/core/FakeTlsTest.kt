package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FakeTlsTest {

    @Test
    fun testIsTlsHandshake() {
        assertTrue(FakeTls.isTlsHandshake(byteArrayOf(0x16, 0x03, 0x01, 0x02, 0x00)))
        assertTrue(FakeTls.isTlsHandshake(byteArrayOf(0x16, 0x03, 0x03, 0x01, 0x00)))
        assertFalse(FakeTls.isTlsHandshake(byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte())))
        assertFalse(FakeTls.isTlsHandshake(byteArrayOf(0x17, 0x03, 0x03)))
    }

    @Test
    fun testBuildFakeTlsServerHello() {
        val sessionId = ByteArray(32) { 0x42.toByte() }
        val resp = FakeTls.buildFakeTlsServerHello(sessionId)

        // ServerHello (127) + CCS (6) + AppData (58) = 191 bytes
        assertEquals(191, resp.size)

        // Check ServerHello header (0x16, 0x03, 0x03)
        assertEquals(0x16.toByte(), resp[0])
        assertEquals(0x03.toByte(), resp[1])
        assertEquals(0x03.toByte(), resp[2])
        val recordLen = ((resp[3].toInt() and 0xFF) shl 8) or (resp[4].toInt() and 0xFF)
        assertEquals(122, recordLen)

        // Check Session ID in ServerHello at offset 44
        val extractedSessionId = resp.copyOfRange(44, 76)
        assertArrayEquals(sessionId, extractedSessionId)

        // Check ChangeCipherSpec at offset 127
        val expectedCcs = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)
        assertArrayEquals(expectedCcs, resp.copyOfRange(127, 133))

        // Check ApplicationData at offset 133
        assertEquals(0x17.toByte(), resp[133])
        assertEquals(0x03.toByte(), resp[134])
        assertEquals(0x03.toByte(), resp[135])
    }

    @Test
    fun testFakeTlsHandshakeAndAppDataFraming() {
        val clientHelloBody = ByteArrayOutputStream()
        clientHelloBody.write(0x01) // ClientHello
        clientHelloBody.write(byteArrayOf(0x00, 0x00, 0x4b)) // Length 75
        clientHelloBody.write(byteArrayOf(0x03, 0x03)) // Version
        clientHelloBody.write(ByteArray(32) { 0xaa.toByte() }) // Random
        clientHelloBody.write(32) // Session ID len
        val expectedSessionId = ByteArray(32) { 0x55.toByte() }
        clientHelloBody.write(expectedSessionId)
        clientHelloBody.write(byteArrayOf(0x00, 0x02, 0x13, 0x01)) // Cipher suite
        clientHelloBody.write(byteArrayOf(0x01, 0x00)) // Compression
        clientHelloBody.write(byteArrayOf(0x00, 0x00)) // Extensions len

        val bodyBytes = clientHelloBody.toByteArray()
        val recordLen = bodyBytes.size
        val clientHello = ByteArrayOutputStream()
        clientHello.write(byteArrayOf(0x16, 0x03, 0x01, ((recordLen shr 8) and 0xFF).toByte(), (recordLen and 0xFF).toByte()))
        clientHello.write(bodyBytes)

        // Add application data record with 64-byte payload
        val testPayload = ByteArray(64) { it.toByte() }
        val appDataRecord = ByteArrayOutputStream()
        appDataRecord.write(byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x40))
        appDataRecord.write(testPayload)

        val fullClientInput = ByteArrayOutputStream()
        fullClientInput.write(clientHello.toByteArray())
        fullClientInput.write(appDataRecord.toByteArray())

        val inputStream = ByteArrayInputStream(fullClientInput.toByteArray())
        val outputStream = ByteArrayOutputStream()

        val initial5 = ByteArray(5)
        inputStream.read(initial5)

        val receivedAppData = FakeTls.handleFakeTlsHandshake(inputStream, outputStream, initial5)
        assertNotNull(receivedAppData)
        assertArrayEquals(testPayload, receivedAppData)

        // Verify Server sent 191-byte handshake
        assertEquals(191, outputStream.size())

        // Test writing app data
        val srvOut = ByteArrayOutputStream()
        FakeTls.writeTlsAppData(srvOut, "Hello".toByteArray(Charsets.UTF_8))
        val srvBytes = srvOut.toByteArray()
        assertEquals(0x17.toByte(), srvBytes[0])
        assertEquals(0x03.toByte(), srvBytes[1])
        assertEquals(0x03.toByte(), srvBytes[2])
        assertEquals(0x00.toByte(), srvBytes[3])
        assertEquals(0x05.toByte(), srvBytes[4])
        assertEquals("Hello", String(srvBytes.copyOfRange(5, 10), Charsets.UTF_8))
    }
}
