package com.mirrly.tgproxy.core

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

class WarpObfuscatedHttpClientTest {

    @Test
    fun testFragmentedTlsHandshakeAndHttpRequest() {
        // GET /v0a4471/reg returns 404 from Cloudflare API, verifying TLS handshake and HTTP parsing
        try {
            val response = WarpObfuscatedHttpClient.execute("GET", "/v0a4471/reg", timeoutMs = 7000)
            println("OBFUSCATED TLS TEST: HTTP ${response.statusCode} via ${response.connectedIp}, body len=${response.body.length}")
            assertTrue(response.statusCode in 200..499, "Expected valid HTTP status from Cloudflare")
            assertTrue(response.connectedIp.isNotBlank(), "Connected IP must be set")
        } catch (e: Exception) {
            println("OBFUSCATED TLS TEST: Direct network connection to Cloudflare failed (${e.message}), skipping live check.")
        }
    }

    @Test
    fun testDefaultTimeoutMsIsStandardTlsBudget() {
        assertTrue(WarpObfuscatedHttpClient.DEFAULT_TIMEOUT_MS in 3000..10000, "Timeout must be between 3000 and 10000ms")
    }

    @Test
    fun testProbeDirectApiExecution() {
        val start = System.currentTimeMillis()
        // Fast probe should execute or fail within 1500 ms (probe timeout 500ms)
        val isReachable = WarpObfuscatedHttpClient.probeDirectApi(timeoutMs = 500)
        val duration = System.currentTimeMillis() - start
        println("Direct API reachable: $isReachable, took ${duration}ms")
        assertTrue(duration < 2500, "Fast direct probe must complete within 2500ms (took ${duration}ms)")
    }

    @Test
    fun testSslParametersDistinctSniAndEndpointIdentification() {
        val engine = WarpObfuscatedHttpClient.createEngine(host = "api.cloudflareclient.com", port = 443)
        assertTrue(engine.useClientMode, "Engine must be in client mode")

        val params = engine.sslParameters
        assertEquals("HTTPS", params.endpointIdentificationAlgorithm, "Endpoint identification must be set to HTTPS")

        val serverNames = params.serverNames
        assertNotNull(serverNames, "Server names (SNI) must be present")
        assertEquals(1, serverNames.size, "Exactly one SNI host expected")
        val sni = serverNames[0] as SNIHostName
        assertEquals("api.cloudflareclient.com", sni.asciiName, "SNI must match target host")

        // Demonstrate SNI and endpoint identification are distinct SSLParameters
        val modifiedParams = engine.sslParameters
        modifiedParams.serverNames = null
        assertEquals("HTTPS", modifiedParams.endpointIdentificationAlgorithm, "Clearing SNI must not alter endpoint identification")

        val customEngine = WarpObfuscatedHttpClient.createEngine(host = "custom.domain.test", port = 8443)
        assertEquals("HTTPS", customEngine.sslParameters.endpointIdentificationAlgorithm)
        assertEquals("custom.domain.test", (customEngine.sslParameters.serverNames[0] as SNIHostName).asciiName)
    }

    @Test
    fun testMismatchedHostnameRejectedEvenWithTrustedCa() {
        val rootCa = HeldCertificate.Builder()
            .certificateAuthority(0)
            .commonName("Trusted Root CA")
            .build()

        // Certificate signed by trusted CA, but for a different hostname
        val mismatchedCert = HeldCertificate.Builder()
            .signedBy(rootCa)
            .commonName("wrong.domain.attacker.com")
            .addSubjectAlternativeName("wrong.domain.attacker.com")
            .build()

        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(mismatchedCert)
            .build()

        val clientCerts = HandshakeCertificates.Builder()
            .addTrustedCertificate(rootCa.certificate)
            .build()

        val serverSocket = serverCerts.sslContext().serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val port = serverSocket.localPort

        val serverThread = thread {
            try {
                serverSocket.accept().use { client ->
                    client.getInputStream().read()
                }
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }

        try {
            // Target host is api.cloudflareclient.com, but server presents wrong.domain.attacker.com
            val exception = assertThrows(IOException::class.java) {
                WarpObfuscatedHttpClient.executeInternal(
                    method = "GET",
                    path = "/v0a4471/reg",
                    timeoutMs = 3000,
                    candidatesList = listOf("127.0.0.1"),
                    customContext = clientCerts.sslContext(),
                    host = "api.cloudflareclient.com",
                    port = port
                )
            }
            assertTrue(
                exception is SSLException || exception.cause is SSLException,
                "Connection with mismatched hostname must be rejected with SSLException, got: $exception"
            )
        } finally {
            serverSocket.close()
            serverThread.join(2000)
        }
    }

    @Test
    fun testMatchingHostnameAcceptedWithAndWithoutFragmentation() {
        val rootCa = HeldCertificate.Builder()
            .certificateAuthority(0)
            .commonName("Trusted Root CA")
            .build()

        val matchingCert = HeldCertificate.Builder()
            .signedBy(rootCa)
            .commonName("api.cloudflareclient.com")
            .addSubjectAlternativeName("api.cloudflareclient.com")
            .build()

        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(matchingCert)
            .build()

        val clientCerts = HandshakeCertificates.Builder()
            .addTrustedCertificate(rootCa.certificate)
            .build()

        // 1. Test with fragmentation enabled
        runMockServerAndClient(serverCerts, clientCerts, enableFragmentation = true)

        // 2. Test without fragmentation
        runMockServerAndClient(serverCerts, clientCerts, enableFragmentation = false)
    }

    @Test
    fun testUntrustedCaRejected() {
        val trustedRootCa = HeldCertificate.Builder()
            .certificateAuthority(0)
            .commonName("Trusted Root CA")
            .build()

        val untrustedRootCa = HeldCertificate.Builder()
            .certificateAuthority(0)
            .commonName("Untrusted Rogue CA")
            .build()

        val rogueCert = HeldCertificate.Builder()
            .signedBy(untrustedRootCa)
            .commonName("api.cloudflareclient.com")
            .addSubjectAlternativeName("api.cloudflareclient.com")
            .build()

        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(rogueCert)
            .build()

        val clientCerts = HandshakeCertificates.Builder()
            .addTrustedCertificate(trustedRootCa.certificate)
            .build()

        val serverSocket = serverCerts.sslContext().serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val port = serverSocket.localPort

        val serverThread = thread {
            try {
                serverSocket.accept().use { client ->
                    client.getInputStream().read()
                }
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }

        try {
            val exception = assertThrows(IOException::class.java) {
                WarpObfuscatedHttpClient.executeInternal(
                    method = "GET",
                    path = "/v0a4471/reg",
                    timeoutMs = 3000,
                    candidatesList = listOf("127.0.0.1"),
                    customContext = clientCerts.sslContext(),
                    host = "api.cloudflareclient.com",
                    port = port
                )
            }
            assertTrue(
                exception is SSLException || exception.cause is SSLException,
                "Certificate from untrusted CA must be rejected with SSLException, got: $exception"
            )
        } finally {
            serverSocket.close()
            serverThread.join(2000)
        }
    }

    private fun runMockServerAndClient(
        serverCerts: HandshakeCertificates,
        clientCerts: HandshakeCertificates,
        enableFragmentation: Boolean
    ) {
        val serverSocket = serverCerts.sslContext().serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val port = serverSocket.localPort

        val expectedJson = "{\"result\":\"success\",\"fragmented\":$enableFragmentation}"
        val responseBytes = (
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${expectedJson.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n" +
            expectedJson
        ).toByteArray(Charsets.US_ASCII)

        val serverThread = thread {
            try {
                serverSocket.accept().use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        line = reader.readLine()
                    }
                    val out = client.getOutputStream()
                    out.write(responseBytes)
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }

        try {
            val response = WarpObfuscatedHttpClient.executeInternal(
                method = "GET",
                path = "/v0a4471/reg",
                timeoutMs = 3000,
                candidatesList = listOf("127.0.0.1"),
                customContext = clientCerts.sslContext(),
                host = "api.cloudflareclient.com",
                port = port,
                enableFragmentation = enableFragmentation
            )
            assertEquals(200, response.statusCode)
            assertEquals(expectedJson, response.body)
            assertEquals("127.0.0.1", response.connectedIp)
        } finally {
            serverSocket.close()
            serverThread.join(2000)
        }
    }
}
