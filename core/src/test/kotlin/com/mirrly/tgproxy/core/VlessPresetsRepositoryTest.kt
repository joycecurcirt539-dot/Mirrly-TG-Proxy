package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.json.JSONObject

class VlessPresetsRepositoryTest {

    @Test
    fun testBuiltinPresetsAreNonEmptyAndValid() {
        val presets = VlessPresetsRepository.BUILTIN_PRESETS
        assertTrue(presets.isNotEmpty(), "Builtin presets must not be empty")

        for (p in presets) {
            assertTrue(p.uuid.isNotBlank(), "Preset UUID must not be blank")
            assertTrue(p.domain.isNotBlank(), "Preset domain must not be blank")
            assertTrue(p.path.startsWith("/"), "Preset path must start with /")
            assertEquals(443, p.port, "Cloudflare Pages port should be 443")
        }
    }

    @Test
    fun testParseVlessUriStandardFormat() {
        val uri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@free-cdn.pages.dev:443?encryption=none&security=tls&sni=free-cdn.pages.dev&type=ws&host=free-cdn.pages.dev&path=%2Fvless-ws%3Fed%3D2048#Custom%20CDN"
        val parsed = VlessPresetsRepository.parseVlessUri(uri)

        assertNotNull(parsed, "Must parse standard VLESS URI")
        assertEquals("d342d11e-d424-4583-b36e-524ab1f0afa4", parsed!!.uuid)
        assertEquals("free-cdn.pages.dev", parsed.domain)
        assertEquals(443, parsed.port)
        assertEquals("/vless-ws?ed=2048", parsed.path)
        assertEquals("Custom CDN", parsed.name)
        assertTrue(parsed.isCustom)
    }

    @Test
    fun testApplyPresetUpdatesConfig() {
        val config = ProxyConfig()
        val preset = VlessPreset(
            id = "preset_reality_1",
            name = "Reality Test Preset",
            domain = "dl.google.com",
            port = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            path = "/",
            security = "reality",
            publicKey = "my_public_key",
            shortId = "my_short_id",
            fingerprint = "chrome",
            spiderX = "/search",
            transport = "tcp",
            flow = "xtls-rprx-vision",
            headerType = "none"
        )

        VlessPresetsRepository.applyPreset(preset, config)

        assertEquals(preset.uuid, config.vlessUuid)
        assertEquals(preset.path, config.vlessPath)
        assertEquals(preset.domain, config.vlessDomain)
        assertEquals(preset.domain, config.getEffectiveVlessDomain())
        assertEquals("reality", config.vlessSecurity)
        assertEquals("my_public_key", config.vlessPublicKey)
        assertEquals("my_short_id", config.vlessShortId)
        assertEquals("chrome", config.vlessFingerprint)
        assertEquals("/search", config.vlessSpiderX)
        assertEquals("tcp", config.vlessTransport)
        assertEquals("xtls-rprx-vision", config.vlessFlow)
        assertEquals("none", config.vlessHeaderType)
        assertTrue(config.isVlessReality)
        assertTrue(config.isVlessTcpDirect)
        assertTrue(config.isVlessVision)

        val shareUrl = config.getVlessShareUrl()
        assertTrue(shareUrl.contains("security=reality"))
        assertTrue(shareUrl.contains("flow=xtls-rprx-vision"))
        assertTrue(shareUrl.contains("type=tcp"))
    }

    @Test
    fun testParseRealityUri() {
        val realityUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@reality.example.com:443?encryption=none&security=reality&sni=yahoo.com&type=tcp&pbk=1234567890abcdef1234567890abcdef1234567890a&sid=abcd1234&fp=chrome&spx=%2F#RealityNode"
        val parsedReality = VlessPresetsRepository.parseVlessUri(realityUri)
        assertNotNull(parsedReality, "Reality protocol configs must be accepted")
        assertEquals("d342d11e-d424-4583-b36e-524ab1f0afa4", parsedReality!!.uuid)
        assertEquals("yahoo.com", parsedReality.domain)
        assertEquals(443, parsedReality.port)
        assertEquals("reality", parsedReality.security)
        assertEquals("1234567890abcdef1234567890abcdef1234567890a", parsedReality.publicKey)
        assertEquals("abcd1234", parsedReality.shortId)
        assertEquals("chrome", parsedReality.fingerprint)
        assertEquals("/", parsedReality.spiderX)
        assertEquals("RealityNode", parsedReality.name)
        assertTrue(parsedReality.isReality)
        assertTrue(parsedReality.isDirectTcp)

        val shareUri = parsedReality.toShareableUri()
        assertTrue(shareUri.contains("security=reality"))
        assertTrue(shareUri.contains("pbk=1234567890abcdef1234567890abcdef1234567890a"))
        assertTrue(shareUri.contains("sid=abcd1234"))
        assertTrue(shareUri.contains("fp=chrome"))
        assertTrue(shareUri.contains("spx=%2F"))
    }

    @Test
    fun testParseDirectTcpWithVisionAndTls() {
        val uri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@tcp.example.com:443?encryption=none&security=tls&sni=tcp.example.com&type=tcp&flow=xtls-rprx-vision&headerType=none#TcpVisionNode"
        val parsed = VlessPresetsRepository.parseVlessUri(uri)
        assertNotNull(parsed, "Direct TCP with Vision must be accepted")
        assertEquals("d342d11e-d424-4583-b36e-524ab1f0afa4", parsed!!.uuid)
        assertEquals("tcp.example.com", parsed.domain)
        assertEquals("tcp", parsed.transport)
        assertEquals("tls", parsed.security)
        assertEquals("xtls-rprx-vision", parsed.flow)
        assertEquals("none", parsed.headerType)
        assertTrue(parsed.isDirectTcp)
        assertTrue(parsed.isVision)
        assertEquals("TCP Vision", parsed.region)

        val shareUri = parsed.toShareableUri()
        assertTrue(shareUri.contains("type=tcp"))
        assertTrue(shareUri.contains("flow=xtls-rprx-vision"))
        assertTrue(shareUri.contains("headerType=none"))
        assertTrue(shareUri.contains("security=tls"))
    }

    @Test
    fun testParseRealityDirectTcpWithVision() {
        val uri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@reality.example.com:443?encryption=none&security=reality&sni=yahoo.com&type=tcp&flow=xtls-rprx-vision&pbk=1234567890abcdef1234567890abcdef1234567890a&sid=abcd1234#RealityVision"
        val parsed = VlessPresetsRepository.parseVlessUri(uri)
        assertNotNull(parsed, "Reality TCP with Vision must be accepted")
        assertEquals("tcp", parsed!!.transport)
        assertEquals("reality", parsed.security)
        assertEquals("xtls-rprx-vision", parsed.flow)
        assertTrue(parsed.isDirectTcp)
        assertTrue(parsed.isVision)
        assertTrue(parsed.isReality)
        assertEquals("Reality Vision", parsed.region)

        val shareUri = parsed.toShareableUri()
        assertTrue(shareUri.contains("type=tcp"))
        assertTrue(shareUri.contains("flow=xtls-rprx-vision"))
        assertTrue(shareUri.contains("security=reality"))
    }

    @Test
    fun testRejectUnsupportedTransports() {
        val grpcUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@grpc.example.com:443?encryption=none&security=tls&type=grpc&serviceName=test#GrpcNode"
        val parsedGrpc = VlessPresetsRepository.parseVlessUri(grpcUri)
        org.junit.jupiter.api.Assertions.assertNull(parsedGrpc, "gRPC configs must be rejected")

        val quicUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@quic.example.com:443?encryption=none&security=tls&type=quic#QuicNode"
        val parsedQuic = VlessPresetsRepository.parseVlessUri(quicUri)
        org.junit.jupiter.api.Assertions.assertNull(parsedQuic, "QUIC configs must be rejected")
    }

    @Test
    fun testParseSubscriptionStreamPlainText() {
        val rawContent = """
            vless://11111111-1111-1111-1111-111111111111@stream1.pages.dev:443?security=tls&type=ws&path=%2Fvless#Node1
            vless://22222222-2222-2222-2222-222222222222@stream2.pages.dev:443?security=tls&type=grpc&serviceName=test#BadGrpc
            vless://33333333-3333-3333-3333-333333333333@stream3.pages.dev:443?security=reality&type=tcp&pbk=pubkey123#RealityNode
            vless://44444444-4444-4444-4444-444444444444@stream4.example.com:443?security=tls&type=tcp&flow=xtls-rprx-vision#TcpVisionNode
            vless://11111111-1111-1111-1111-111111111111@stream1.pages.dev:443?security=tls&type=ws&path=%2Fvless#ExactDuplicateNode
            vless://55555555-5555-5555-5555-555555555555@stream1.pages.dev:443?security=tls&type=ws&path=%2Fvless#DistinctUserSameDomain
        """.trimIndent()

        val found = mutableListOf<VlessPreset>()
        val stream = java.io.ByteArrayInputStream(rawContent.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        VlessPresetsRepository.parseSubscriptionStream(stream, maxPresets = 10, foundPresets = found)

        // 4 valid nodes: Node1, RealityNode, TcpVisionNode, DistinctUserSameDomain
        // BadGrpc is rejected, ExactDuplicateNode is deduplicated
        assertEquals(4, found.size, "Should find 4 valid distinct nodes (retaining distinct UUIDs on same domain)")
        assertEquals("stream1.pages.dev", found[0].domain)
        assertEquals("stream3.pages.dev", found[1].domain)
        assertTrue(found[1].isReality)
        assertEquals("pubkey123", found[1].publicKey)
        assertEquals("stream4.example.com", found[2].domain)
        assertTrue(found[2].isDirectTcp)
        assertTrue(found[2].isVision)
        assertEquals("stream1.pages.dev", found[3].domain)
        assertEquals("55555555-5555-5555-5555-555555555555", found[3].uuid)
    }

    @Test
    fun testParseSubscriptionStreamBase64() {
        val rawLines = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@b64.pages.dev:443?security=tls&type=ws&path=%2Fws#B64Node\n"
        val base64Encoded = java.util.Base64.getEncoder().encodeToString(rawLines.toByteArray(java.nio.charset.StandardCharsets.UTF_8))

        val found = mutableListOf<VlessPreset>()
        val stream = java.io.ByteArrayInputStream(base64Encoded.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        VlessPresetsRepository.parseSubscriptionStream(stream, maxPresets = 10, foundPresets = found)

        assertEquals(1, found.size, "Should successfully decode Base64 stream and parse node")
        assertEquals("b64.pages.dev", found[0].domain)
        assertEquals("/ws", found[0].path)
    }

    @Test
    fun testParseSubscriptionStreamBase64WithMimeLinebreaksAndReality() {
        val rawLines = """
            vless://11111111-2222-3333-4444-555555555555@reality.domain.com:443?encryption=none&security=reality&sni=gateway.icloud.com&type=tcp&flow=xtls-rprx-vision&pbk=key123&sid=sid123#MimeRealityNode
            vless://22222222-3333-4444-5555-666666666666@104.16.132.229:2053?encryption=none&security=tls&sni=my-fronted.pages.dev&type=ws&host=my-fronted.pages.dev&path=%2Fvless-ws#CleanIpNode
        """.trimIndent()
        // MIME base64 with newlines every 40 characters
        val singleLineB64 = java.util.Base64.getEncoder().encodeToString(rawLines.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val mimeB64 = singleLineB64.chunked(40).joinToString("\r\n")

        val found = mutableListOf<VlessPreset>()
        val stream = java.io.ByteArrayInputStream(mimeB64.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        VlessPresetsRepository.parseSubscriptionStream(stream, maxPresets = 10, foundPresets = found)

        assertEquals(2, found.size, "MIME Base64 stream with newlines must be decoded")
        val realityNode = found[0]
        assertEquals("gateway.icloud.com", realityNode.domain)
        assertEquals("reality", realityNode.security)
        assertTrue(realityNode.isReality)
        assertTrue(realityNode.isDirectTcp)
        assertTrue(realityNode.isVision)
        assertEquals("key123", realityNode.publicKey)
        assertEquals("sid123", realityNode.shortId)

        val cleanIpNode = found[1]
        assertEquals("104.16.132.229", cleanIpNode.serverAddress)
        assertEquals(2053, cleanIpNode.serverPort)
        assertEquals("my-fronted.pages.dev", cleanIpNode.tlsSni)
        assertEquals("my-fronted.pages.dev", cleanIpNode.hostHeader)
    }

    @Test
    fun testParseSubscriptionStreamUrlSafeBase64() {
        val rawLines = "vless://33333333-4444-5555-6666-777777777777@url-safe.example.com:443?security=tls&type=tcp&flow=xtls-rprx-vision#UrlSafeNode"
        val urlSafeB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(rawLines.toByteArray(java.nio.charset.StandardCharsets.UTF_8))

        val found = mutableListOf<VlessPreset>()
        val stream = java.io.ByteArrayInputStream(urlSafeB64.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        VlessPresetsRepository.parseSubscriptionStream(stream, maxPresets = 10, foundPresets = found)

        assertEquals(1, found.size, "URL-Safe Base64 stream must be decoded")
        assertEquals("url-safe.example.com", found[0].domain)
        assertTrue(found[0].isDirectTcp)
        assertTrue(found[0].isVision)
    }

    @Test
    fun testParseSubscriptionStreamIndividualBase64Lines() {
        val uri1 = "vless://44444444-5555-6666-7777-888888888888@line1.example.com:443?security=tls&type=ws&path=%2Fws#Line1"
        val uri2 = "vless://55555555-6666-7777-8888-999999999999@line2.example.com:443?security=reality&type=tcp&pbk=key999#Line2"

        val b64Line1 = java.util.Base64.getEncoder().encodeToString(uri1.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val b64Line2 = java.util.Base64.getEncoder().encodeToString(uri2.toByteArray(java.nio.charset.StandardCharsets.UTF_8))

        val mixedPayload = "$b64Line1\n# Commentary Line\n$b64Line2\n"

        val found = mutableListOf<VlessPreset>()
        val stream = java.io.ByteArrayInputStream(mixedPayload.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        VlessPresetsRepository.parseSubscriptionStream(stream, maxPresets = 10, foundPresets = found)

        assertEquals(2, found.size, "Individual base64 lines must be parsed")
        assertEquals("line1.example.com", found[0].domain)
        assertEquals("line2.example.com", found[1].domain)
        assertTrue(found[1].isReality)
        assertEquals("key999", found[1].publicKey)
    }

    @Test
    fun testBuildSubscriptionHttpClientAndSocketCheck() {
        // Port 59999 is unlikely to have a socks5 proxy listening in tests
        val isActive = VlessPresetsRepository.isLocalSocks5Active("127.0.0.1", 59999, 100)
        org.junit.jupiter.api.Assertions.assertFalse(isActive, "Random port must not be reported as active socks5")

        val client = VlessPresetsRepository.buildSubscriptionHttpClient(59999)
        assertNotNull(client, "Must create OkHttpClient instance")
        assertNotNull(client.dns, "Client must have DoH DNS configured when socks5 is not active")
        assertNotNull(client.socketFactory, "Client must have TlsFragmentingSocketFactory configured")
    }

    @Test
    fun testBuiltinPresetsContainCleanIpAndReality() {
        val presets = VlessPresetsRepository.BUILTIN_PRESETS
        val hasCleanIp = presets.any { it.serverAddress.isNotBlank() && it.tlsSni.isNotBlank() }
        val hasReality = presets.any { it.isReality }

        assertTrue(hasCleanIp, "BUILTIN_PRESETS must contain Clean IP fronted nodes")
        assertTrue(hasReality, "BUILTIN_PRESETS must contain VLESS Reality nodes")
    }

    @Test
    fun testSaveAndLoadDynamicPool() {
        val tempDir = java.nio.file.Files.createTempDirectory("vless_cache_test").toFile()
        try {
            val custom = VlessPreset(
                id = "test_custom_1",
                name = "Test Custom Node",
                domain = "test-node.pages.dev",
                port = 443,
                uuid = "ffffffff-ffff-ffff-ffff-ffffffffffff",
                path = "/custom-ws",
                region = "Test Region",
                isCustom = true,
                security = "reality",
                publicKey = "pubkey123",
                shortId = "sid123",
                fingerprint = "firefox",
                spiderX = "/spider",
                transport = "tcp",
                flow = "xtls-rprx-vision",
                headerType = "none"
            )
            VlessPresetsRepository.addCustomPreset(custom)
            VlessPresetsRepository.saveDynamicPool(tempDir)

            val cacheFile = java.io.File(tempDir, "vless_dynamic_pool.json")
            assertTrue(cacheFile.exists(), "Dynamic pool cache file should exist")

            // Re-load into repository
            VlessPresetsRepository.loadDynamicPool(tempDir)
            val found = VlessPresetsRepository.findPresetById("test_custom_1")
            assertNotNull(found, "Saved custom preset must be retrievable after load")
            assertEquals("test-node.pages.dev", found!!.domain)
            assertEquals("reality", found.security)
            assertEquals("pubkey123", found.publicKey)
            assertEquals("sid123", found.shortId)
            assertEquals("firefox", found.fingerprint)
            assertEquals("/spider", found.spiderX)
            assertEquals("tcp", found.transport)
            assertEquals("xtls-rprx-vision", found.flow)
            assertEquals("none", found.headerType)
            assertTrue(found.isReality)
            assertTrue(found.isDirectTcp)
            assertTrue(found.isVision)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testParseVlessCleanIpAndFronting() {
        val uri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@104.16.123.96:2053?encryption=none&security=tls&sni=free-vless.pages.dev&type=ws&host=free-vless.pages.dev&path=%2Fvless-ws#FrontedNode"
        val parsed = VlessPresetsRepository.parseVlessUri(uri)

        assertNotNull(parsed, "Must parse Clean IP fronting URI")
        assertEquals("d342d11e-d424-4583-b36e-524ab1f0afa4", parsed!!.uuid)
        assertEquals("free-vless.pages.dev", parsed.domain)
        assertEquals("104.16.123.96", parsed.serverAddress)
        assertEquals(2053, parsed.serverPort)
        assertEquals("free-vless.pages.dev", parsed.tlsSni)
        assertEquals("free-vless.pages.dev", parsed.hostHeader)
        assertEquals("104.16.123.96", parsed.effectiveServerAddress)
        assertEquals(2053, parsed.effectiveServerPort)
        assertEquals("free-vless.pages.dev", parsed.effectiveTlsSni)
        assertEquals("free-vless.pages.dev", parsed.effectiveHostHeader)

        val shareUri = parsed.toShareableUri()
        assertTrue(shareUri.contains("@104.16.123.96:2053"), "Share URI must dial Clean IP socket")
        assertTrue(shareUri.contains("sni=free-vless.pages.dev"), "Share URI must have TLS SNI")
        assertTrue(shareUri.contains("host=free-vless.pages.dev"), "Share URI must have HTTP Host")
    }

    @Test
    fun testApplyPresetCleanIpToConfig() {
        val config = ProxyConfig()
        val preset = VlessPreset(
            id = "preset_clean_ip",
            name = "Clean IP Preset",
            domain = "my-worker.pages.dev",
            port = 443,
            uuid = "22222222-3333-4444-5555-666666666666",
            path = "/ws",
            serverAddress = "172.67.180.12",
            serverPort = 8443,
            tlsSni = "my-worker.pages.dev",
            hostHeader = "my-worker.pages.dev"
        )

        VlessPresetsRepository.applyPreset(preset, config)

        assertEquals("172.67.180.12", config.vlessServerAddress)
        assertEquals(8443, config.vlessServerPort)
        assertEquals("my-worker.pages.dev", config.vlessTlsSni)
        assertEquals("my-worker.pages.dev", config.vlessHostHeader)
        assertEquals("172.67.180.12", config.getEffectiveVlessServerAddress())
        assertEquals(8443, config.getEffectiveVlessServerPort())
        assertEquals("my-worker.pages.dev", config.getEffectiveVlessSni())
        assertEquals("my-worker.pages.dev", config.getEffectiveVlessHost())

        val shareUrl = config.getVlessShareUrl()
        assertTrue(shareUrl.contains("@172.67.180.12:8443"))
        assertTrue(shareUrl.contains("sni=my-worker.pages.dev"))
        assertTrue(shareUrl.contains("host=my-worker.pages.dev"))
    }

    @Test
    fun testSaveAndLoadDynamicPoolWithCleanIp() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "vless_test_pool_cleanip_" + System.currentTimeMillis())
        tempDir.mkdirs()
        try {
            val custom = VlessPreset(
                id = "test_clean_ip_custom",
                name = "Clean IP Custom Node",
                domain = "target.pages.dev",
                port = 443,
                uuid = "33333333-4444-5555-6666-777777777777",
                path = "/vless-ws",
                isCustom = true,
                serverAddress = "104.16.123.96",
                serverPort = 2053,
                tlsSni = "target.pages.dev",
                hostHeader = "target.pages.dev"
            )
            VlessPresetsRepository.addCustomPreset(custom)
            VlessPresetsRepository.saveDynamicPool(tempDir)

            VlessPresetsRepository.loadDynamicPool(tempDir)
            val found = VlessPresetsRepository.findPresetById("test_clean_ip_custom")
            assertNotNull(found, "Saved Clean IP preset must be retrievable")
            assertEquals("104.16.123.96", found!!.serverAddress)
            assertEquals(2053, found.serverPort)
            assertEquals("target.pages.dev", found.tlsSni)
            assertEquals("target.pages.dev", found.hostHeader)
            assertEquals("104.16.123.96", found.effectiveServerAddress)
            assertEquals(2053, found.effectiveServerPort)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testVlessJsonSerializationAndExtendedPresets() {
        val preset = VlessPreset(
            id = "custom_reality_json_test",
            name = "Test Reality Node",
            uuid = "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
            domain = "reality.example.com",
            path = "/vless-reality",
            serverAddress = "194.87.1.100",
            serverPort = 8443,
            tlsSni = "gateway.icloud.com",
            hostHeader = "gateway.icloud.com",
            transport = "tcp",
            security = "reality",
            publicKey = "xK8_test_public_key_reality_12345",
            shortId = "0123456789abcdef",
            fingerprint = "chrome",
            spiderX = "/download",
            flow = "xtls-rprx-vision",
            headerType = "none"
        )

        val json = JSONObject().apply {
            put("uuid", preset.uuid)
            put("path", preset.path)
            put("domain", preset.domain)
            put("server_address", preset.effectiveServerAddress)
            put("server_port", preset.effectiveServerPort)
            put("tls_sni", preset.effectiveTlsSni)
            put("host_header", preset.effectiveHostHeader)
            put("transport", preset.transport)
            put("security", preset.security)
            put("public_key", preset.publicKey)
            put("short_id", preset.shortId)
            put("fingerprint", preset.fingerprint)
            put("spider_x", preset.spiderX)
            put("flow", preset.flow)
            put("header_type", preset.headerType)
        }

        assertEquals("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d", json.getString("uuid"))
        assertEquals("/vless-reality", json.getString("path"))
        assertEquals("reality.example.com", json.getString("domain"))
        assertEquals("194.87.1.100", json.getString("server_address"))
        assertEquals(8443, json.getInt("server_port"))
        assertEquals("gateway.icloud.com", json.getString("tls_sni"))
        assertEquals("tcp", json.getString("transport"))
        assertEquals("reality", json.getString("security"))
        assertEquals("xK8_test_public_key_reality_12345", json.getString("public_key"))
        assertEquals("0123456789abcdef", json.getString("short_id"))
        assertEquals("xtls-rprx-vision", json.getString("flow"))

        val config = ProxyConfig()
        VlessPresetsRepository.applyPreset(preset, config)

        assertEquals(preset.uuid, config.vlessUuid)
        assertEquals(preset.effectiveServerAddress, config.getEffectiveVlessServerAddress())
        assertEquals(preset.effectiveServerPort, config.getEffectiveVlessServerPort())
        assertEquals(preset.effectiveTlsSni, config.getEffectiveVlessSni())
        assertEquals("reality", config.vlessSecurity)
        assertEquals("xtls-rprx-vision", config.vlessFlow)
        assertTrue(config.isVlessReality)
        assertTrue(config.isVlessTcpDirect)
        assertTrue(config.isVlessVision)
    }

    @Test
    fun testBuildFallbackPoolJsonSchemaVersionAndFields() {
        val p1 = VlessPreset(
            id = "preset_vps_1",
            name = "VPS Reality",
            domain = "vps1.example.com",
            port = 443,
            uuid = "11111111-1111-1111-1111-111111111111",
            path = "/",
            serverAddress = "194.87.1.10",
            serverPort = 8443,
            tlsSni = "dl.google.com",
            hostHeader = "dl.google.com",
            transport = "tcp",
            security = "reality",
            publicKey = "pubkey_vps_1",
            shortId = "sid_1",
            fingerprint = "chrome",
            spiderX = "/search",
            flow = "xtls-rprx-vision",
            headerType = "none"
        )

        val p2 = VlessPreset(
            id = "preset_cf_2",
            name = "Cloudflare Worker",
            domain = "worker2.pages.dev",
            port = 443,
            uuid = "22222222-2222-2222-2222-222222222222",
            path = "/cf-vless?ed=2048",
            serverAddress = "",
            serverPort = 0,
            tlsSni = "worker2.pages.dev",
            hostHeader = "worker2.pages.dev",
            transport = "ws",
            security = "tls",
            publicKey = "",
            shortId = "",
            fingerprint = "firefox",
            spiderX = "",
            flow = "",
            headerType = ""
        )

        val jsonString = VlessPresetsRepository.buildFallbackPoolJson(listOf(p1, p2))
        val root = JSONObject(jsonString)

        assertEquals(1, root.getInt("schema_version"), "Fallback pool must have schema_version = 1")
        val profiles = root.getJSONArray("profiles")
        assertEquals(2, profiles.length(), "Must contain 2 profiles")

        val obj1 = profiles.getJSONObject(0)
        assertEquals("preset_vps_1", obj1.getString("id"))
        assertEquals("VPS Reality", obj1.getString("name"))
        assertEquals("11111111-1111-1111-1111-111111111111", obj1.getString("uuid"))
        assertEquals(8443, obj1.getInt("server_port"))
        assertEquals("194.87.1.10", obj1.getString("server_address"))
        assertEquals("reality", obj1.getString("security"))
        assertEquals("pubkey_vps_1", obj1.getString("public_key"))
        assertEquals("xtls-rprx-vision", obj1.getString("flow"))

        val obj2 = profiles.getJSONObject(1)
        assertEquals("preset_cf_2", obj2.getString("id"))
        assertEquals("Cloudflare Worker", obj2.getString("name"))
        assertEquals("22222222-2222-2222-2222-222222222222", obj2.getString("uuid"))
        assertEquals(443, obj2.getInt("server_port"))
        assertEquals("/cf-vless?ed=2048", obj2.getString("path"))
        assertEquals("tls", obj2.getString("security"))
        assertEquals("", obj2.getString("public_key"))
        assertEquals("", obj2.getString("flow"))
    }

    @Test
    fun testCapabilityMatrixRejectInvalidUuid() {
        // 1. Too short UUID
        val shortUuidUri = "vless://d342d11e-short@example.com:443?security=tls"
        val res1 = VlessPresetsRepository.parseVlessUriResult(shortUuidUri)
        assertTrue(res1 is VlessParseResult.Failure, "Short UUID must be rejected")
        assertNull(VlessPresetsRepository.parseVlessUri(shortUuidUri))
        assertTrue((res1 as VlessParseResult.Failure).reason.contains("Invalid UUID"))

        // 2. Non-hex characters in UUID
        val nonHexUuidUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afag@example.com:443?security=tls"
        val res2 = VlessPresetsRepository.parseVlessUriResult(nonHexUuidUri)
        assertTrue(res2 is VlessParseResult.Failure, "Non-hex UUID must be rejected")
        assertTrue((res2 as VlessParseResult.Failure).reason.contains("Invalid UUID"))

        // 3. Malformed RFC 4122 layout (wrong hyphens placement)
        val malformedLayoutUri = "vless://d342d11e-d4244583-b36e-524ab1-f0afa4@example.com:443?security=tls"
        val res3 = VlessPresetsRepository.parseVlessUriResult(malformedLayoutUri)
        assertTrue(res3 is VlessParseResult.Failure, "Malformed RFC 4122 layout must be rejected")
        assertTrue((res3 as VlessParseResult.Failure).reason.contains("RFC 4122"))

        // 4. Valid 32-hex characters without hyphens must succeed
        val compactHexUri = "vless://d342d11ed4244583b36e524ab1f0afa4@example.com:443?security=tls"
        val res4 = VlessPresetsRepository.parseVlessUriResult(compactHexUri)
        assertTrue(res4 is VlessParseResult.Success, "32-char hex UUID must succeed")
        assertEquals("d342d11ed4244583b36e524ab1f0afa4", (res4 as VlessParseResult.Success).preset.uuid)
    }

    @Test
    fun testCapabilityMatrixRejectOversizedAndNegativePorts() {
        // 1. Port > 65535 in host authority
        val oversizedUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:65536?security=tls"
        val res1 = VlessPresetsRepository.parseVlessUriResult(oversizedUri)
        assertTrue(res1 is VlessParseResult.Failure, "Port 65536 must be rejected")
        assertTrue((res1 as VlessParseResult.Failure).reason.contains("between 1 and 65535"))

        // 2. Port = 0 in host authority
        val zeroPortUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:0?security=tls"
        val res2 = VlessPresetsRepository.parseVlessUriResult(zeroPortUri)
        assertTrue(res2 is VlessParseResult.Failure, "Port 0 must be rejected")

        // 3. Port = -1 in host authority
        val negativePortUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:-1?security=tls"
        val res3 = VlessPresetsRepository.parseVlessUriResult(negativePortUri)
        assertTrue(res3 is VlessParseResult.Failure, "Negative port must be rejected")

        // 4. Non-numeric port in host authority
        val invalidPortStrUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:xyz?security=tls"
        val res4 = VlessPresetsRepository.parseVlessUriResult(invalidPortStrUri)
        assertTrue(res4 is VlessParseResult.Failure, "String port must be rejected")

        // 5. Query port parameter oversized
        val queryPortOversizedUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?port=70000&security=tls"
        val res5 = VlessPresetsRepository.parseVlessUriResult(queryPortOversizedUri)
        assertTrue(res5 is VlessParseResult.Failure, "Query port 70000 must be rejected")

        // 6. Query port parameter 0
        val queryPortZeroUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?port=0&security=tls"
        val res6 = VlessPresetsRepository.parseVlessUriResult(queryPortZeroUri)
        assertTrue(res6 is VlessParseResult.Failure, "Query port 0 must be rejected")
    }

    @Test
    fun testCapabilityMatrixRejectUnknownSecurity() {
        val unknownSecUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=xtls"
        val res1 = VlessPresetsRepository.parseVlessUriResult(unknownSecUri)
        assertTrue(res1 is VlessParseResult.Failure, "Unknown security 'xtls' must be rejected")
        assertTrue((res1 as VlessParseResult.Failure).reason.contains("Unsupported security protocol"))

        val shadowsocksSecUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=shadowsocks"
        val res2 = VlessPresetsRepository.parseVlessUriResult(shadowsocksSecUri)
        assertTrue(res2 is VlessParseResult.Failure, "Unknown security 'shadowsocks' must be rejected")
    }

    @Test
    fun testCapabilityMatrixRejectRealityWithoutPublicKey() {
        val realityNoKeyUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=reality"
        val res1 = VlessPresetsRepository.parseVlessUriResult(realityNoKeyUri)
        assertTrue(res1 is VlessParseResult.Failure, "Reality without public key must be rejected")
        assertTrue((res1 as VlessParseResult.Failure).reason.contains("requires a non-empty public key"))

        val realityEmptyKeyUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=reality&pbk="
        val res2 = VlessPresetsRepository.parseVlessUriResult(realityEmptyKeyUri)
        assertTrue(res2 is VlessParseResult.Failure, "Reality with empty pbk must be rejected")

        // Conflicting security=none with public key
        val conflictUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=none&pbk=key123"
        val res3 = VlessPresetsRepository.parseVlessUriResult(conflictUri)
        assertTrue(res3 is VlessParseResult.Failure, "Conflicting security=none with pbk must be rejected")
    }

    @Test
    fun testCapabilityMatrixRejectUnsupportedEncryption() {
        val aesUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?encryption=aes-128-gcm&security=tls"
        val res1 = VlessPresetsRepository.parseVlessUriResult(aesUri)
        assertTrue(res1 is VlessParseResult.Failure, "Encryption aes-128-gcm must be rejected")
        assertTrue((res1 as VlessParseResult.Failure).reason.contains("Unsupported encryption"))

        val chachaUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?encryption=chacha20-poly1305&security=tls"
        val res2 = VlessPresetsRepository.parseVlessUriResult(chachaUri)
        assertTrue(res2 is VlessParseResult.Failure, "Encryption chacha20-poly1305 must be rejected")

        // encryption=none must succeed
        val noneEncUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?encryption=none&security=tls"
        val res3 = VlessPresetsRepository.parseVlessUriResult(noneEncUri)
        assertTrue(res3 is VlessParseResult.Success, "Encryption none must be accepted")
    }

    @Test
    fun testCapabilityMatrixRejectIncompatibleVisionFlow() {
        // Vision flow over WebSocket
        val visionWsUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=tls&type=ws&flow=xtls-rprx-vision"
        val res1 = VlessPresetsRepository.parseVlessUriResult(visionWsUri)
        assertTrue(res1 is VlessParseResult.Failure, "Vision over WS must be rejected")
        assertTrue((res1 as VlessParseResult.Failure).reason.contains("incompatible with transport 'ws'"))

        // Vision flow with security=none
        val visionPlainUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=none&type=tcp&flow=xtls-rprx-vision"
        val res2 = VlessPresetsRepository.parseVlessUriResult(visionPlainUri)
        assertTrue(res2 is VlessParseResult.Failure, "Vision with security=none must be rejected")
        assertTrue((res2 as VlessParseResult.Failure).reason.contains("incompatible with security 'none'"))

        // Unknown flow
        val unknownFlowUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=tls&type=tcp&flow=xtls-rprx-unknown"
        val res3 = VlessPresetsRepository.parseVlessUriResult(unknownFlowUri)
        assertTrue(res3 is VlessParseResult.Failure, "Unknown flow must be rejected")
        assertTrue((res3 as VlessParseResult.Failure).reason.contains("Unsupported flow"))
    }

    @Test
    fun testCapabilityMatrixRejectUnsupportedHeaderType() {
        val httpHeaderTypeUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=tls&headerType=http"
        val res = VlessPresetsRepository.parseVlessUriResult(httpHeaderTypeUri)
        assertTrue(res is VlessParseResult.Failure, "headerType=http must be rejected")
        assertTrue((res as VlessParseResult.Failure).reason.contains("Unsupported headerType"))
    }

    @Test
    fun testCapabilityMatrixFingerprintHandling() {
        // Supported native rustls/Reality profiles
        for (fp in listOf("chrome", "firefox", "safari", "ios", "randomized")) {
            val uri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=tls&type=ws&fp=$fp"
            val res = VlessPresetsRepository.parseVlessUriResult(uri)
            assertTrue(res is VlessParseResult.Success, "Fingerprint '$fp' must be accepted")
            val success = res as VlessParseResult.Success
            assertEquals(fp, success.preset.fingerprint)
            assertTrue(
                success.warnings.none { it.contains("Unsupported TLS fingerprint") || it.contains("mapped to Chrome") },
                "Native supported fingerprint '$fp' should not have fallback warnings"
            )
        }

        // Mapped browser profiles (edge, 360, qq, android)
        for (mappedFp in listOf("edge", "360", "qq", "android")) {
            val uri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=tls&type=ws&fp=$mappedFp"
            val res = VlessPresetsRepository.parseVlessUriResult(uri)
            assertTrue(res is VlessParseResult.Success, "Mapped fingerprint '$mappedFp' must succeed")
            val success = res as VlessParseResult.Success
            assertEquals("chrome", success.preset.fingerprint)
            assertTrue(
                success.warnings.any { it.contains("mapped to Chrome cipher suite profile") },
                "Mapped fingerprint '$mappedFp' must record an explicit mapping warning"
            )
        }

        // Unsupported/unrecognized fingerprint string
        val unknownFpUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=tls&type=ws&fp=custom_agent_bot"
        val resUnknown = VlessPresetsRepository.parseVlessUriResult(unknownFpUri)
        assertTrue(resUnknown is VlessParseResult.Success, "Unsupported fingerprint must safely succeed")
        val successUnknown = resUnknown as VlessParseResult.Success
        assertEquals("chrome", successUnknown.preset.fingerprint)
        assertTrue(
            successUnknown.warnings.any { it.contains("Unsupported TLS fingerprint 'custom_agent_bot' ignored") },
            "Unsupported fingerprint must record a fallback warning"
        )
    }

    @Test
    fun testCapabilityMatrixSpiderXWarnings() {
        // Reality with spiderX
        val realitySpiderUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=reality&pbk=m_L9FpMZy0-G6eD5B2k-s7o-R3Wp-A1b2c3d4e5f6g&spx=%2Fapi%2Fv1"
        val resReality = VlessPresetsRepository.parseVlessUriResult(realitySpiderUri)
        assertTrue(resReality is VlessParseResult.Success)
        val successReality = resReality as VlessParseResult.Success
        assertEquals("/api/v1", successReality.preset.spiderX)
        assertTrue(
            successReality.warnings.any { it.contains("Parameter 'spiderX' ('/api/v1') has no wire effect") },
            "Reality with spiderX must warn that client crawling is not executed on wire"
        )

        // Non-reality (TLS) with spiderX
        val tlsSpiderUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=tls&spx=%2Fapi%2Fv1"
        val resTls = VlessPresetsRepository.parseVlessUriResult(tlsSpiderUri)
        assertTrue(resTls is VlessParseResult.Success)
        val successTls = resTls as VlessParseResult.Success
        assertTrue(
            successTls.warnings.any { it.contains("Parameter 'spiderX' is only applicable to REALITY security") },
            "TLS with spiderX must warn that it is inapplicable to non-REALITY security"
        )
    }

    @Test
    fun testCapabilityMatrixUnsupportedQueryParameters() {
        val uri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=tls&allowInsecure=1&packetEncoding=xudp"
        val res = VlessPresetsRepository.parseVlessUriResult(uri)
        assertTrue(res is VlessParseResult.Success)
        val success = res as VlessParseResult.Success
        assertTrue(success.warnings.any { it.contains("Unsupported parameter 'allowinsecure' ignored") })
        assertTrue(success.warnings.any { it.contains("Unsupported parameter 'packetencoding' ignored") })
        assertEquals("tls", success.preset.security)
    }

    @Test
    fun testCapabilityMatrixHeaderTypeValidation() {
        // headerType=none is explicitly accepted
        val noneUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=tls&type=tcp&headerType=none"
        val resNone = VlessPresetsRepository.parseVlessUriResult(noneUri)
        assertTrue(resNone is VlessParseResult.Success)
        assertEquals("none", (resNone as VlessParseResult.Success).preset.headerType)

        // headerType=srtp is rejected
        val srtpUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@example.com:443?security=tls&type=tcp&headerType=srtp"
        val resSrtp = VlessPresetsRepository.parseVlessUriResult(srtpUri)
        assertTrue(resSrtp is VlessParseResult.Failure)
        assertTrue((resSrtp as VlessParseResult.Failure).reason.contains("Unsupported headerType"))
    }

    @Test
    fun testCapabilityMatrixRoundTripPreservesSemantics() {
        // 1. WebSocket node
        val originalWsUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@free-cdn.pages.dev:443?encryption=none&security=tls&sni=free-cdn.pages.dev&type=ws&host=free-cdn.pages.dev&path=%2Fvless-ws%3Fed%3D2048#Custom+CDN"
        val parseWs1 = VlessPresetsRepository.parseVlessUriResult(originalWsUri)
        assertTrue(parseWs1 is VlessParseResult.Success)
        val presetWs1 = (parseWs1 as VlessParseResult.Success).preset
        val shareWs = presetWs1.toShareableUri()
        val parseWs2 = VlessPresetsRepository.parseVlessUriResult(shareWs)
        assertTrue(parseWs2 is VlessParseResult.Success)
        val presetWs2 = (parseWs2 as VlessParseResult.Success).preset

        assertEquals(presetWs1.uuid, presetWs2.uuid)
        assertEquals(presetWs1.domain, presetWs2.domain)
        assertEquals(presetWs1.port, presetWs2.port)
        assertEquals(presetWs1.serverAddress, presetWs2.serverAddress)
        assertEquals(presetWs1.serverPort, presetWs2.serverPort)
        assertEquals(presetWs1.tlsSni, presetWs2.tlsSni)
        assertEquals(presetWs1.hostHeader, presetWs2.hostHeader)
        assertEquals(presetWs1.path, presetWs2.path)
        assertEquals(presetWs1.security, presetWs2.security)
        assertEquals(presetWs1.transport, presetWs2.transport)

        // 2. Reality Direct TCP with Vision
        val originalRealityUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@gateway.icloud.com:443?encryption=none&security=reality&sni=gateway.icloud.com&pbk=m_L9FpMZy0-G6eD5B2k-s7o-R3Wp-A1b2c3d4e5f6g&sid=1234abcd&fp=chrome&spx=%2F&flow=xtls-rprx-vision&type=tcp#Apple+Reality"
        val parseR1 = VlessPresetsRepository.parseVlessUriResult(originalRealityUri)
        assertTrue(parseR1 is VlessParseResult.Success)
        val presetR1 = (parseR1 as VlessParseResult.Success).preset
        val shareR = presetR1.toShareableUri()
        val parseR2 = VlessPresetsRepository.parseVlessUriResult(shareR)
        assertTrue(parseR2 is VlessParseResult.Success)
        val presetR2 = (parseR2 as VlessParseResult.Success).preset

        assertEquals(presetR1.uuid, presetR2.uuid)
        assertEquals(presetR1.domain, presetR2.domain)
        assertEquals(presetR1.port, presetR2.port)
        assertEquals(presetR1.security, presetR2.security)
        assertEquals(presetR1.publicKey, presetR2.publicKey)
        assertEquals(presetR1.shortId, presetR2.shortId)
        assertEquals(presetR1.fingerprint, presetR2.fingerprint)
        assertEquals(presetR1.flow, presetR2.flow)
        assertEquals(presetR1.transport, presetR2.transport)

        // 3. Direct TCP with TLS and Vision
        val originalTcpUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@direct.example.com:443?encryption=none&security=tls&sni=direct.example.com&type=tcp&flow=xtls-rprx-vision&headerType=none#Tcp+Vision"
        val parseT1 = VlessPresetsRepository.parseVlessUriResult(originalTcpUri)
        assertTrue(parseT1 is VlessParseResult.Success)
        val presetT1 = (parseT1 as VlessParseResult.Success).preset
        val shareT = presetT1.toShareableUri()
        val parseT2 = VlessPresetsRepository.parseVlessUriResult(shareT)
        assertTrue(parseT2 is VlessParseResult.Success)
        val presetT2 = (parseT2 as VlessParseResult.Success).preset

        assertEquals(presetT1.uuid, presetT2.uuid)
        assertEquals(presetT1.domain, presetT2.domain)
        assertEquals(presetT1.port, presetT2.port)
        assertEquals(presetT1.security, presetT2.security)
        assertEquals(presetT1.transport, presetT2.transport)
        assertEquals(presetT1.flow, presetT2.flow)
        assertEquals(presetT1.headerType, presetT2.headerType)
    }

    @Test
    fun testCapabilityMatrixIPv6Support() {
        val ipv6Uri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@[2001:db8::1]:8443?security=tls&type=tcp#IPv6Node"
        val res = VlessPresetsRepository.parseVlessUriResult(ipv6Uri)
        assertTrue(res is VlessParseResult.Success, "Valid bracketed IPv6 must succeed")
        val preset = (res as VlessParseResult.Success).preset
        assertEquals("2001:db8::1", preset.domain)
        assertEquals(8443, preset.port)
        assertEquals("2001:db8::1", preset.serverAddress)
        assertEquals(8443, preset.serverPort)

        val shareUri = preset.toShareableUri()
        assertTrue(shareUri.contains("@[2001:db8::1]:8443"), "Share URI must bracket IPv6 address")

        val malformedIpv6Uri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@[2001:db8::1:8443?security=tls"
        val malformedRes = VlessPresetsRepository.parseVlessUriResult(malformedIpv6Uri)
        assertTrue(malformedRes is VlessParseResult.Failure, "Malformed IPv6 must be rejected")
    }

    @Test
    fun testCapabilityMatrixPlainWebSocketSupport() {
        val plainWsUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@194.87.1.10:80?encryption=none&security=none&type=ws&host=plain.example.com&path=%2Fvless-plain#Plain+WebSocket"
        val res = VlessPresetsRepository.parseVlessUriResult(plainWsUri)
        assertTrue(res is VlessParseResult.Success, "Plain WebSocket URI must parse successfully")
        val preset = (res as VlessParseResult.Success).preset
        assertEquals("none", preset.security)
        assertEquals("ws", preset.transport)
        assertEquals("194.87.1.10", preset.serverAddress)
        assertEquals(80, preset.serverPort)
        assertEquals("plain.example.com", preset.hostHeader)
        assertEquals("/vless-plain", preset.path)

        // Round-trip verification
        val shareUri = preset.toShareableUri()
        val shareRes = VlessPresetsRepository.parseVlessUriResult(shareUri)
        assertTrue(shareRes is VlessParseResult.Success, "Shareable plain WS URI must round-trip")
        val sharePreset = (shareRes as VlessParseResult.Success).preset
        assertEquals("none", sharePreset.security)
        assertEquals("ws", sharePreset.transport)
        assertEquals(preset.uuid, sharePreset.uuid)
        assertEquals(preset.hostHeader, sharePreset.hostHeader)
        assertEquals(preset.path, sharePreset.path)
    }

    @Test
    fun testCapabilityMatrixRejectRealityOverWebSocket() {
        val realityWsUri = "vless://d342d11e-d424-4583-b36e-524ab1f0afa4@gateway.icloud.com:443?encryption=none&security=reality&pbk=m_L9FpMZy0-G6eD5B2k-s7o-R3Wp-A1b2c3d4e5f6g&type=ws&path=%2Fws#Reality+WS"
        val res = VlessPresetsRepository.parseVlessUriResult(realityWsUri)
        assertTrue(res is VlessParseResult.Failure, "Reality over WebSocket must be rejected")
        val failure = res as VlessParseResult.Failure
        assertTrue(failure.reason.contains("Reality requires direct TCP"), "Failure reason: ${failure.reason}")
    }

    @Test
    fun testDistinctUuidsOnSameAddressArePreserved() {
        VlessPresetsRepository.clearDynamicPresets()
        val initialCount = VlessPresetsRepository.getAllPresets().size

        val uri1 = "vless://11111111-1111-1111-1111-111111111111@shared-node.example.com:443?encryption=none&security=tls&type=ws#AccountOne"
        val uri2 = "vless://22222222-2222-2222-2222-222222222222@shared-node.example.com:443?encryption=none&security=tls&type=ws#AccountTwo"

        val p1 = VlessPresetsRepository.parseVlessUri(uri1)
        val p2 = VlessPresetsRepository.parseVlessUri(uri2)
        assertNotNull(p1)
        assertNotNull(p2)

        // Must have distinct canonical keys because UUID differs
        assertFalse(p1!!.isSameEndpoint(p2!!), "Different UUIDs on same endpoint must NOT be equal")
        assertEquals(p1.effectiveServerAddress, p2.effectiveServerAddress)
        assertEquals(p1.effectiveServerPort, p2.effectiveServerPort)

        VlessPresetsRepository.addCustomPreset(p1)
        VlessPresetsRepository.addCustomPreset(p2)

        val all = VlessPresetsRepository.getAllPresets()
        assertEquals(initialCount + 2, all.size, "Both distinct UUID profiles must be preserved")

        val found1 = VlessPresetsRepository.findPresetById(p1.id)
        val found2 = VlessPresetsRepository.findPresetById(p2.id)
        assertNotNull(found1)
        assertNotNull(found2)
        assertEquals("11111111-1111-1111-1111-111111111111", found1!!.uuid)
        assertEquals("22222222-2222-2222-2222-222222222222", found2!!.uuid)

        VlessPresetsRepository.clearDynamicPresets()
    }

    @Test
    fun testRepeatIdenticalUriUpdatesPredictablyAndPreservesUserLabel() {
        VlessPresetsRepository.clearDynamicPresets()
        val initialCount = VlessPresetsRepository.getAllPresets().size

        // 1. Initial import with explicit custom user label
        val customUri = "vless://33333333-3333-3333-3333-333333333333@edge.example.com:443?encryption=none&security=tls&type=ws#My%20Work%20Server"
        val p1 = VlessPresetsRepository.parseVlessUri(customUri)
        assertNotNull(p1)
        assertEquals("My Work Server", p1!!.name)

        val added1 = VlessPresetsRepository.addCustomPreset(p1)
        assertEquals(initialCount + 1, VlessPresetsRepository.getAllPresets().size)
        assertEquals("My Work Server", added1.name)

        // 2. Re-importing identical connection parameters without a tag (generic default name)
        val repeatNoTagUri = "vless://33333333-3333-3333-3333-333333333333@edge.example.com:443?encryption=none&security=tls&type=ws"
        val p2 = VlessPresetsRepository.parseVlessUri(repeatNoTagUri)
        assertNotNull(p2)
        assertTrue(VlessPresetsRepository.isGenericDefaultName(p2!!.name, p2.domain), "Name should be generic default")

        val updated = VlessPresetsRepository.addCustomPreset(p2)

        // Count must not increase
        assertEquals(initialCount + 1, VlessPresetsRepository.getAllPresets().size, "Duplicate link must not increase preset count")

        // User custom label "My Work Server" must be preserved!
        assertEquals("My Work Server", updated.name, "Custom user label must be preserved on re-import")
        assertEquals(added1.id, updated.id, "Stable preset ID must be preserved")

        // 3. User renames preset via repository
        assertTrue(VlessPresetsRepository.updatePresetName(updated.id, "VIP Office Node"))
        val renamed = VlessPresetsRepository.findPresetById(updated.id)
        assertNotNull(renamed)
        assertEquals("VIP Office Node", renamed!!.name)

        // 4. Re-import again: user's renamed label must still be preserved
        val reimportAgain = VlessPresetsRepository.addCustomPreset(p2)
        assertEquals("VIP Office Node", reimportAgain.name, "Renamed user label must be preserved")

        VlessPresetsRepository.clearDynamicPresets()
    }

    @Test
    fun testCanonicalKeyComparisonIgnoresDisplayName() {
        val uriA = "vless://44444444-4444-4444-4444-444444444444@node.test.com:8443?security=tls&type=tcp#NameA"
        val uriB = "vless://44444444-4444-4444-4444-444444444444@node.test.com:8443?security=tls&type=tcp#NameB"
        val uriDiffUuid = "vless://55555555-5555-5555-5555-555555555555@node.test.com:8443?security=tls&type=tcp#NameA"

        val pA = VlessPresetsRepository.parseVlessUri(uriA)!!
        val pB = VlessPresetsRepository.parseVlessUri(uriB)!!
        val pDiff = VlessPresetsRepository.parseVlessUri(uriDiffUuid)!!

        // Identical parameters with different display names are the same endpoint
        assertTrue(pA.isSameEndpoint(pB), "Same endpoint with different display names must match canonicalKey")
        assertEquals(pA.canonicalKey(), pB.canonicalKey())

        // Different UUID with same display name is NOT the same endpoint
        assertFalse(pA.isSameEndpoint(pDiff), "Different UUID must NOT match canonicalKey")
        assertNotEquals(pA.canonicalKey(), pDiff.canonicalKey())
    }

    @Test
    fun testSubscriptionStreamDeduplicationWithDistinctUuids() {
        val subContent = """
            vless://11111111-1111-1111-1111-111111111111@sub.example.com:443?security=tls&type=ws#SubUser1
            vless://22222222-2222-2222-2222-222222222222@sub.example.com:443?security=tls&type=ws#SubUser2
            vless://11111111-1111-1111-1111-111111111111@sub.example.com:443?security=tls&type=ws#SubUser1Duplicate
        """.trimIndent()

        val stream = subContent.byteInputStream(Charsets.UTF_8)
        val found = mutableListOf<VlessPreset>()
        VlessPresetsRepository.parseSubscriptionStream(stream, 10, found)

        // Must have 2 presets (SubUser1 and SubUser2); the duplicate SubUser1 must be deduplicated
        assertEquals(2, found.size, "Subscription parsing must retain both distinct UUIDs and deduplicate exact matches")
        assertTrue(found.any { it.uuid == "11111111-1111-1111-1111-111111111111" })
        assertTrue(found.any { it.uuid == "22222222-2222-2222-2222-222222222222" })
    }

    @Test
    fun testLimitedInputStreamEnforcesLimit() {
        val data = "0123456789ABCDEF".toByteArray(Charsets.UTF_8) // 16 bytes

        // 1. Stream within limit completes normally
        val okStream = LimitedInputStream(data.inputStream(), maxBytes = 16)
        val readOk = okStream.readBytes()
        assertEquals(16, readOk.size)
        assertEquals(-1, okStream.read(), "EOF reached without exception")

        // 2. Stream exceeding limit throws OversizedSubscriptionException on block read
        val overStream = LimitedInputStream(data.inputStream(), maxBytes = 10)
        val buf = ByteArray(16)
        assertThrows(OversizedSubscriptionException::class.java) {
            overStream.read(buf, 0, 16)
        }

        // 3. Stream exceeding limit throws OversizedSubscriptionException on byte-by-byte read
        val byteStream = LimitedInputStream(data.inputStream(), maxBytes = 5)
        for (i in 0 until 5) {
            val b = byteStream.read()
            assertTrue(b >= 0)
        }
        assertThrows(OversizedSubscriptionException::class.java) {
            byteStream.read()
        }
    }

    @Test
    fun testReadBoundedTextThrowsOnOversized() {
        val largeData = ByteArray(1024 * 1024 + 100) { 'x'.code.toByte() }
        val stream = largeData.inputStream()

        assertThrows(OversizedSubscriptionException::class.java) {
            VlessPresetsRepository.readBoundedText(stream, VlessPresetsRepository.MAX_SUBSCRIPTION_BYTES)
        }
    }

    @Test
    fun testParseSubscriptionStreamOversizedIsGracefullyRejectedAndPreservesPresets() {
        VlessPresetsRepository.clearDynamicPresets()
        val customPreset = VlessPreset(
            id = "preserved_node_1",
            name = "Existing Node",
            domain = "preserved.com",
            port = 443,
            uuid = "11111111-1111-1111-1111-111111111111"
        )
        VlessPresetsRepository.addCustomPreset(customPreset)
        val initialPresets = VlessPresetsRepository.getAllPresets()
        val initialCount = initialPresets.size

        // Feed an oversized stream (> 1 MiB)
        val oversizedData = ByteArray(1024 * 1024 + 5000) { 'A'.code.toByte() }
        val stream = oversizedData.inputStream()
        val found = mutableListOf<VlessPreset>()

        // Must not throw unhandled exception
        VlessPresetsRepository.parseSubscriptionStream(stream, 10, found)

        // Found list must be empty
        assertTrue(found.isEmpty(), "Oversized stream must yield no parsed presets")

        // Existing presets must be completely preserved
        assertEquals(initialCount, VlessPresetsRepository.getAllPresets().size)
        assertNotNull(VlessPresetsRepository.findPresetById("preserved_node_1"))

        VlessPresetsRepository.clearDynamicPresets()
    }

    @Test
    fun testParseSubscriptionStreamOversizedLineIsSkipped() {
        val validUri1 = "vless://11111111-1111-1111-1111-111111111111@node1.example.com:443?security=tls&type=ws#Node1"
        val validUri2 = "vless://22222222-2222-2222-2222-222222222222@node2.example.com:443?security=tls&type=ws#Node2"
        val oversizedLine = "vless://invalid@" + "a".repeat(20000) + ".com:443?security=tls"

        val payload = "$validUri1\n$oversizedLine\n$validUri2\n"
        val stream = payload.byteInputStream(Charsets.UTF_8)
        val found = mutableListOf<VlessPreset>()

        VlessPresetsRepository.parseSubscriptionStream(stream, 10, found)

        assertEquals(2, found.size, "Oversized line must be skipped while valid lines are processed")
        assertEquals("node1.example.com", found[0].domain)
        assertEquals("node2.example.com", found[1].domain)
    }

    @Test
    fun testTryDecodeBase64RejectsDecompressionBombs() {
        // Base64 string that would decode to > 1 MiB
        val giantBase64 = "A".repeat((VlessPresetsRepository.MAX_DECOMPRESSED_BYTES * 4 / 3 + 10000).toInt())
        val result = VlessPresetsRepository.tryDecodeBase64(giantBase64)
        assertNull(result, "Oversized base64 string must be rejected before decoding")
    }

    @Test
    fun testSimulatedInfiniteChunkedStreamTerminatesSafely() {
        // InputStream that generates endless bytes without closing
        val infiniteStream = object : java.io.InputStream() {
            override fun read(): Int = 'X'.code
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val toFill = minOf(len, 4096)
                java.util.Arrays.fill(b, off, off + toFill, 'X'.code.toByte())
                return toFill
            }
        }

        val found = mutableListOf<VlessPreset>()
        // Must return quickly and not run forever or throw OutOfMemoryError
        VlessPresetsRepository.parseSubscriptionStream(infiniteStream, 10, found)
        assertTrue(found.isEmpty(), "Infinite chunked stream must be halted and yield no presets")
    }
}


