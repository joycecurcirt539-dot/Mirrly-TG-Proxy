package com.mirrly.tgproxy.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ProxyStats {
    val totalBytesReceived = AtomicLong(0)
    val totalBytesSent = AtomicLong(0)
    val activeConnections = AtomicInteger(0)

    @Volatile
    var externalByteProvider: (() -> Pair<Long, Long>)? = null

    private var baselineRx = 0L
    private var baselineTx = 0L

    private var lastCheckTime = System.currentTimeMillis()
    private var lastBytesRecv = -1L
    private var lastBytesSent = -1L

    @Volatile
    var downloadSpeedBps: Long = 0
        private set

    @Volatile
    var uploadSpeedBps: Long = 0
        private set

    fun resetBaseline() {
        val ext = externalByteProvider?.invoke()
        if (ext != null && ext.first > 0) {
            baselineRx = ext.first
            baselineTx = ext.second
        } else {
            baselineRx = 0L
            baselineTx = 0L
        }
        totalBytesReceived.set(0)
        totalBytesSent.set(0)
        lastBytesRecv = -1L
        lastBytesSent = -1L
        downloadSpeedBps = 0L
        uploadSpeedBps = 0L
        lastCheckTime = System.currentTimeMillis()
    }

    fun addReceived(bytes: Long) {
        if (bytes > 0) {
            totalBytesReceived.addAndGet(bytes)
        }
    }

    fun addSent(bytes: Long) {
        if (bytes > 0) {
            totalBytesSent.addAndGet(bytes)
        }
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
                if (rx != null && rx > totalBytesReceived.get()) {
                    totalBytesReceived.set(rx)
                }
            }

            val txMatch = REGEX_TX.find(rawStr)
            if (txMatch != null) {
                val tx = txMatch.groupValues[1].toLongOrNull()
                if (tx != null && tx > totalBytesSent.get()) {
                    totalBytesSent.set(tx)
                }
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun updateSpeed() {
        val now = System.currentTimeMillis()
        val dt = (now - lastCheckTime) / 1000.0
        if (dt < 0.3) return

        val ext = externalByteProvider?.invoke()
        if (ext != null && ext.first > 0) {
            val extRx = (ext.first - baselineRx).coerceAtLeast(0)
            val extTx = (ext.second - baselineTx).coerceAtLeast(0)
            if (extRx > totalBytesReceived.get()) {
                totalBytesReceived.set(extRx)
            }
            if (extTx > totalBytesSent.get()) {
                totalBytesSent.set(extTx)
            }
        }

        val currRecv = totalBytesReceived.get()
        val currSent = totalBytesSent.get()

        if (lastBytesRecv < 0L || lastBytesSent < 0L) {
            lastBytesRecv = currRecv
            lastBytesSent = currSent
            lastCheckTime = now
            downloadSpeedBps = 0L
            uploadSpeedBps = 0L
            return
        }

        if (currRecv >= lastBytesRecv) {
            downloadSpeedBps = ((currRecv - lastBytesRecv) / dt).toLong().coerceAtLeast(0)
        }
        if (currSent >= lastBytesSent) {
            uploadSpeedBps = ((currSent - lastBytesSent) / dt).toLong().coerceAtLeast(0)
        }

        lastBytesRecv = currRecv
        lastBytesSent = currSent
        lastCheckTime = now
    }

    companion object {
        private val REGEX_CONNS = Regex(
            """(?:active_connections|active_conns|active_conn|active|conns|connections|conn)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
        private val REGEX_RX = Regex(
            """(?:rx_bytes|bytes_recv|download_bytes|bytes_received|rx|recv|download|bytes_in|in_bytes|received|read_bytes|bytes_read|rx_count)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
        private val REGEX_TX = Regex(
            """(?:tx_bytes|bytes_sent|upload_bytes|tx|sent|upload|bytes_out|out_bytes|written_bytes|bytes_written|tx_count)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
    }
}
