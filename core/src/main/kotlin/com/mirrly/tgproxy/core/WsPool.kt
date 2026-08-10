package com.mirrly.tgproxy.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class PoolKey(val dcId: Int, val isMedia: Boolean)

class WsPool(private val poolSize: Int = 4) {

    private class PooledSocket(val client: RawWebSocketClient, val createdAt: Long)

    private val idlePool = ConcurrentHashMap<PoolKey, ConcurrentLinkedQueue<PooledSocket>>()
    private val refillingSet = ConcurrentHashMap<PoolKey, Boolean>()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun get(dcId: Int, isMedia: Boolean, isTestEnv: Boolean): RawWebSocketClient? {
        val key = PoolKey(dcId, isMedia)
        val queue = idlePool[key] ?: return null

        val now = System.currentTimeMillis()
        while (!queue.isEmpty()) {
            val item = queue.poll() ?: break
            val ageMs = now - item.createdAt
            // 1. Max pool connection age: 60 seconds (prevents stale NAT timeouts on mobile networks)
            if (ageMs > 60_000) {
                item.client.close()
                continue
            }
            // 2. Zero-Latency Health Check: discard dead or closed sockets
            if (!item.client.isAlive) {
                item.client.close()
                continue
            }
            triggerRefill(key, isTestEnv)
            return item.client
        }

        triggerRefill(key, isTestEnv)
        return null
    }

    fun triggerRefill(key: PoolKey, isTestEnv: Boolean) {
        if (refillingSet.putIfAbsent(key, true) == null) {
            scope.launch {
                try {
                    refill(key, isTestEnv)
                } finally {
                    refillingSet.remove(key)
                }
            }
        }
    }

    private fun refill(key: PoolKey, isTestEnv: Boolean) {
        val queue = idlePool.computeIfAbsent(key) { ConcurrentLinkedQueue() }
        val needed = poolSize - queue.size
        if (needed <= 0) return

        val domains = TgConstants.getWsDomains(key.dcId, key.isMedia)
        val wsPath = if (isTestEnv) TgConstants.WS_PATH_TEST else TgConstants.WS_PATH

        for (i in 0 until needed) {
            for (domain in domains) {
                val url = "wss://$domain$wsPath"
                try {
                    val client = RawWebSocketClient(url)
                    client.connect()
                    queue.add(PooledSocket(client, System.currentTimeMillis()))
                    break
                } catch (_: Exception) {}
            }
        }
    }

    fun clear() {
        for (queue in idlePool.values) {
            while (!queue.isEmpty()) {
                val item = queue.poll()
                item?.client?.close()
            }
        }
        idlePool.clear()
    }

    /**
     * Instantly triggers background refill for the most active Telegram DCs (DC2 and DC4).
     * Called upon network restoration so fresh WSS sockets are ready within milliseconds.
     */
    fun warmUpPrimaryDCs(isTestEnv: Boolean = false) {
        val primaryKeys = listOf(
            PoolKey(2, false),
            PoolKey(4, false),
            PoolKey(2, true),
            PoolKey(4, true)
        )
        for (key in primaryKeys) {
            triggerRefill(key, isTestEnv)
        }
    }
}
