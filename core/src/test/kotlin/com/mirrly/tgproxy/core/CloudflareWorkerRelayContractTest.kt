/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudflareWorkerRelayContractTest {

    @Test
    fun `tcp relay confirms upstream before websocket upgrade`() {
        val worker = TgConstants.CLOUDFLARE_WORKER_JS_CODE
        val opened = worker.lastIndexOf("tcpSocket.opened")
        val upgrade = worker.lastIndexOf("const webSocketPair = new WebSocketPair()")

        assertTrue(opened >= 0, "Worker must await cloudflare socket opened")
        assertTrue(upgrade > opened, "WebSocket 101 must only be created after upstream TCP is open")
        assertTrue(worker.contains("status: 502"), "Upstream connection failures must reject the upgrade")
        assertTrue(worker.contains("tcpSocket.closed.then"), "Worker must observe upstream closure")
    }

    @Test
    fun `tcp relay has bounded websocket to tcp queue`() {
        val worker = TgConstants.CLOUDFLARE_WORKER_JS_CODE

        assertTrue(worker.contains("MAX_WS_MESSAGE_BYTES"))
        assertTrue(worker.contains("MAX_PENDING_WRITE_BYTES"))
        assertTrue(worker.contains("pendingWriteBytes + data.byteLength"))
        assertTrue(worker.contains("serverWs.close(1009"))
        assertTrue(worker.contains("createBoundedSequentialWriter"))
        assertTrue(worker.contains("TCP_WRITE_TIMEOUT_MS"))
    }

    @Test
    fun `tcp relay enforces downlink backpressure and slow reader protection`() {
        val worker = TgConstants.CLOUDFLARE_WORKER_JS_CODE

        assertTrue(worker.contains("DOWNLINK_HIGH_WATERMARK"))
        assertTrue(worker.contains("DOWNLINK_LOW_WATERMARK"))
        assertTrue(worker.contains("MAX_DOWNLINK_CHUNK"))
        assertTrue(worker.contains("SLOW_READER_SOAK_TIMEOUT_MS"))
        assertTrue(worker.contains("pumpTcpToWebSocket"))
        assertTrue(worker.contains("serverWs.close(1008"))
    }

    @Test
    fun `all worker deployment templates match flow control and relay contracts`() {
        val rootDir = java.io.File("..").canonicalFile
        val candidates = listOf(
            java.io.File(rootDir, "tools/deploy-worker/worker.js"),
            java.io.File(rootDir, "docs/cloudflare_worker.js")
        )

        for (file in candidates) {
            if (!file.exists()) continue
            val text = file.readText()
            assertTrue(text.contains("createBoundedSequentialWriter"), "${file.name} must have bounded sequential writer")
            assertTrue(text.contains("pumpTcpToWebSocket"), "${file.name} must have downlink pump with backpressure")
            assertTrue(text.contains("MAX_WS_MESSAGE_BYTES"), "${file.name} must specify MAX_WS_MESSAGE_BYTES")
            assertTrue(text.contains("MAX_PENDING_WRITE_BYTES"), "${file.name} must specify MAX_PENDING_WRITE_BYTES")
            assertTrue(text.contains("DOWNLINK_HIGH_WATERMARK"), "${file.name} must specify DOWNLINK_HIGH_WATERMARK")
            assertTrue(text.contains("0x56, 0x02, 0x00, 0x00"), "${file.name} must specify /tcp-v2 control ACK")
            assertTrue(text.contains("serverWs.binaryType = \"arraybuffer\""), "${file.name} must explicitly configure arraybuffer binaryType")
            assertTrue(text.contains("serverWs.accept()"), "${file.name} must call serverWs.accept()")
            assertTrue(text.contains("raw instanceof Blob"), "${file.name} must handle Blob payloads to prevent zero-length loss")
        }
    }

    @Test
    fun `worker specifies binaryType arraybuffer before accept`() {
        val worker = TgConstants.CLOUDFLARE_WORKER_JS_CODE
        val binaryTypeIdx = worker.indexOf("serverWs.binaryType = \"arraybuffer\"")
        val acceptIdx = worker.indexOf("serverWs.accept()")

        assertTrue(binaryTypeIdx >= 0, "Worker must set binaryType = arraybuffer")
        assertTrue(acceptIdx > binaryTypeIdx, "binaryType must be set BEFORE serverWs.accept()")
    }

    @Test
    fun `worker decodes Blob payloads to prevent zero-length loss`() {
        val worker = TgConstants.CLOUDFLARE_WORKER_JS_CODE
        assertTrue(worker.contains("raw instanceof Blob"), "Worker must check for Blob payloads")
        assertTrue(worker.contains("raw.arrayBuffer()"), "Worker must decode Blob via arrayBuffer()")
    }

    @Test
    fun `deployment scripts pin modern compatibility date and nodejs_compat`() {
        val rootDir = java.io.File("..").canonicalFile
        val ps1 = java.io.File(rootDir, "tools/deploy-worker/deploy.ps1").readText()
        val sh = java.io.File(rootDir, "tools/deploy-worker/deploy.sh").readText()

        assertTrue(ps1.contains("compatibility_date = \"2025-01-01\""), "deploy.ps1 must pin compatibility_date 2025-01-01")
        assertTrue(sh.contains("compatibility_date = \"2025-01-01\""), "deploy.sh must pin compatibility_date 2025-01-01")
    }

    @Test
    fun `tcp-v2 relay emits versioned control ack frame`() {
        val worker = TgConstants.CLOUDFLARE_WORKER_JS_CODE
        assertTrue(worker.contains("isTcpV2"), "Worker must detect /tcp-v2 route")
        assertTrue(worker.contains("0x56, 0x02, 0x00, 0x00"), "Worker must emit versioned relay-ready control ACK frame")
    }

    @Test
    fun `proxy stats parses v2 sessions and v1 downgrades`() {
        val stats = ProxyStats()
        stats.parseNativeStats("total=10 active=2 ws=5 cf=5 v2=4 v1_down=1")
        org.junit.jupiter.api.Assertions.assertEquals(4L, stats.totalSocks5V2Sessions.get())
        org.junit.jupiter.api.Assertions.assertEquals(1L, stats.totalSocks5V1Downgrades.get())
    }
}

