package com.mirrly.tgproxy.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ProxyStats {
    val totalBytesReceived = AtomicLong(0)
    val totalBytesSent = AtomicLong(0)
    val activeConnections = AtomicInteger(0)

    private var lastCheckTime = System.currentTimeMillis()
    private var lastBytesRecv = 0L
    private var lastBytesSent = 0L

    @Volatile
    var downloadSpeedBps: Long = 0
        private set

    @Volatile
    var uploadSpeedBps: Long = 0
        private set

    fun addReceived(bytes: Long) {
        totalBytesReceived.addAndGet(bytes)
    }

    fun addSent(bytes: Long) {
        totalBytesSent.addAndGet(bytes)
    }

    fun updateRawBytes(rxBytes: Long, txBytes: Long) {
        if (rxBytes >= 0) {
            totalBytesReceived.set(rxBytes)
        }
        if (txBytes >= 0) {
            totalBytesSent.set(txBytes)
        }
    }

    fun parseNativeStats(rawStr: String) {
        if (rawStr.isBlank()) return
        try {
            val connsMatch = REGEX_CONNS.find(rawStr)
            if (connsMatch != null) {
                val active = connsMatch.groupValues[1].toIntOrNull()
                if (active != null) activeConnections.set(active)
            }

            val rxMatch = REGEX_RX.find(rawStr)
            if (rxMatch != null) {
                val rx = rxMatch.groupValues[1].toLongOrNull()
                if (rx != null && rx >= totalBytesReceived.get()) {
                    totalBytesReceived.set(rx)
                }
            }

            val txMatch = REGEX_TX.find(rawStr)
            if (txMatch != null) {
                val tx = txMatch.groupValues[1].toLongOrNull()
                if (tx != null && tx >= totalBytesSent.get()) {
                    totalBytesSent.set(tx)
                }
            }
        } catch (_: Exception) {}
    }

    fun updateSpeed() {
        val now = System.currentTimeMillis()
        val dt = (now - lastCheckTime) / 1000.0
        if (dt > 0.4) {
            val currRecv = totalBytesReceived.get()
            val currSent = totalBytesSent.get()

            downloadSpeedBps = if (lastBytesRecv > 0) ((currRecv - lastBytesRecv) / dt).toLong().coerceAtLeast(0) else 0L
            uploadSpeedBps = if (lastBytesSent > 0) ((currSent - lastBytesSent) / dt).toLong().coerceAtLeast(0) else 0L

            lastBytesRecv = currRecv
            lastBytesSent = currSent
            lastCheckTime = now
        }
    }

    companion object {
        private val REGEX_CONNS = Regex(
            """(?:active_connections|active_conns|active|conns|connections)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
        private val REGEX_RX = Regex(
            """(?:rx_bytes|bytes_recv|download_bytes|bytes_received)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
        private val REGEX_TX = Regex(
            """(?:tx_bytes|bytes_sent|upload_bytes)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
    }
}
