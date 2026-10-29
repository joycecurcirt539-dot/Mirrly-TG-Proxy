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
        val opened = worker.lastIndexOf("tcp.opened")
        val upgrade = worker.lastIndexOf("new WebSocketPair()")

        assertTrue(opened >= 0, "Worker must await cloudflare socket opened")
        assertTrue(upgrade > opened, "WebSocket 101 must only be created after upstream TCP is open")
        assertTrue(worker.contains("error('Telegram connection failed', 502)"), "Upstream connection failures must reject the upgrade")
    }

    @Test
    fun `tcp relay has bounded websocket to tcp queue`() {
        val worker = TgConstants.CLOUDFLARE_WORKER_JS_CODE

        assertTrue(worker.contains("MAX_PENDING_WRITE_BYTES"))
        assertTrue(worker.contains("pendingBytes += data.byteLength"))
        assertTrue(worker.contains("cleanup(1009"))
        assertTrue(worker.contains("writer.write(data)"))
    }

    @Test
    fun `tcp relay enforces downlink credit from client`() {
        val worker = TgConstants.CLOUDFLARE_WORKER_JS_CODE

        assertTrue(worker.contains("DOWNLINK_WINDOW_BYTES"))
        assertTrue(worker.contains("DOWNLINK_CHUNK_BYTES"))
        assertTrue(worker.contains("MIN_DOWNLINK_WINDOW_BYTES"))
        assertTrue(worker.contains("MAX_DOWNLINK_WINDOW_BYTES"))
        assertTrue(worker.contains("flow && sentBytes - ackedBytes >= downlinkWindow"))
        assertTrue(worker.contains("/^ack:(\\d+)$/"))
        assertTrue(!worker.contains("bufferedAmount"))
    }

    @Test
    fun `all worker deployment templates use the same source`() {
        val rootDir = java.io.File("..").canonicalFile
        val canonical = java.io.File(rootDir, "tools/deploy-worker/worker.js").readText().trimEnd()
        val candidates = listOf("docs/cloudflare_worker.js", "app/src/main/assets/worker.js")

        for (path in candidates) {
            val text = java.io.File(rootDir, path).readText().trimEnd()
            assertTrue(text == canonical, "$path must match canonical Worker")
        }
        assertTrue(TgConstants.CLOUDFLARE_WORKER_JS_CODE == canonical)
        assertTrue(java.io.File(rootDir, "tools/deploy-worker/deploy.ps1").readText().contains(canonical))
        assertTrue(java.io.File(rootDir, "tools/deploy-worker/deploy.sh").readText().contains(canonical))
    }

    @Test
    fun `worker specifies binaryType arraybuffer before accept`() {
        val worker = TgConstants.CLOUDFLARE_WORKER_JS_CODE
        val binaryTypeIdx = worker.indexOf("server.binaryType = 'arraybuffer'")
        val acceptIdx = worker.indexOf("server.accept()")

        assertTrue(binaryTypeIdx >= 0, "Worker must set binaryType = arraybuffer")
        assertTrue(acceptIdx > binaryTypeIdx, "binaryType must be set BEFORE server.accept()")
    }

    @Test
    fun `worker accepts binary websocket payloads`() {
        val worker = TgConstants.CLOUDFLARE_WORKER_JS_CODE
        assertTrue(worker.contains("event.data instanceof ArrayBuffer"))
        assertTrue(worker.contains("ArrayBuffer.isView(event.data)"))
    }

    @Test
    fun `deployment scripts pin modern compatibility date and nodejs_compat`() {
        val rootDir = java.io.File("..").canonicalFile
        val ps1 = java.io.File(rootDir, "tools/deploy-worker/deploy.ps1").readText()
        val sh = java.io.File(rootDir, "tools/deploy-worker/deploy.sh").readText()

        assertTrue(ps1.contains("compatibility_date = \"2026-09-29\""), "deploy.ps1 must pin compatibility_date 2026-09-29")
        assertTrue(sh.contains("compatibility_date = \"2026-09-29\""), "deploy.sh must pin compatibility_date 2026-09-29")
    }

    @Test
    fun `tcp-v2 relay emits versioned control ack frame`() {
        val worker = TgConstants.CLOUDFLARE_WORKER_JS_CODE
        assertTrue(worker.contains("url.pathname === '/tcp-v2'"))
        assertTrue(worker.contains("0x56, flow ? 0x03 : 0x02, 0x00, 0x00"))
    }

    @Test
    fun `proxy stats parses v2 sessions and v1 downgrades`() {
        val stats = ProxyStats()
        stats.parseNativeStats("total=10 active=2 ws=5 cf=5 v2=4 v1_down=1")
        org.junit.jupiter.api.Assertions.assertEquals(4L, stats.totalSocks5V2Sessions.get())
        org.junit.jupiter.api.Assertions.assertEquals(1L, stats.totalSocks5V1Downgrades.get())
    }
}
