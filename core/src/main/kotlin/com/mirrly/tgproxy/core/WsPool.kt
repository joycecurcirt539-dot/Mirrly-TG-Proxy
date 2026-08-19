package com.mirrly.tgproxy.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class PoolKey(val dcId: Int, val isMedia: Boolean)

class WsPool(@Volatile var poolSize: Int = 4) {

    private class PooledSocket(val client: RawWebSocketClient, val createdAt: Long)

    private val idlePool = ConcurrentHashMap<PoolKey, ConcurrentLinkedQueue<PooledSocket>>()
    private val refillingSet = ConcurrentHashMap<PoolKey, Boolean>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val availableSockets: Int
        get() {
            val now = System.currentTimeMillis()
            var count = 0
            for (queue in idlePool.values) {
                for (item in queue) {
                    if (item.client.isAlive && (now - item.createdAt) <= 60_000) {
                        count++
                    }
                }
            }
            return count
        }

    fun updatePoolSize(newSize: Int, isTestEnv: Boolean = false) {
        val clamped = newSize.coerceIn(2, 16)
        poolSize = clamped
        for (queue in idlePool.values) {
            while (queue.size > clamped) {
                val item = queue.poll() ?: break
                item.client.close()
            }
        }
        warmUpPrimaryDCs(isTestEnv)
    }

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

    private suspend fun refill(key: PoolKey, isTestEnv: Boolean) {
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
                    val connected = client.connectAndAwait(2000)
                    if (connected && client.isAlive) {
                        if (queue.size < poolSize) {
                            queue.add(PooledSocket(client, System.currentTimeMillis()))
                        } else {
                            client.close()
                        }
                        break
                    } else {
                        client.close()
                    }
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
