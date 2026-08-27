package com.mirrly.tgproxy.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ProxyStats {
    val totalBytesReceived = AtomicLong(0)
    val totalBytesSent = AtomicLong(0)
    val activeConnections = AtomicInteger(0)
    val totalWsConnections = AtomicLong(0)
    val lastActivityTimestamp = AtomicLong(System.currentTimeMillis())

    @Volatile
    var externalByteProvider: (() -> Pair<Long, Long>)? = null

    @Volatile
    var onTotalWsConnectionsChanged: ((Long) -> Unit)? = null

    private var baselineRx = 0L
    private var baselineTx = 0L

    private var lastCheckTime = System.currentTimeMillis()
    private var lastBytesRecv = -1L
    private var lastBytesSent = -1L

    private val rxSpeedFilter = EmaSpeedFilter(defaultAlpha = 0.30)
    private val txSpeedFilter = EmaSpeedFilter(defaultAlpha = 0.30)

    @Volatile
    var downloadSpeedBps: Long = 0 // Сглаженная скорость EMA
        private set

    @Volatile
    var uploadSpeedBps: Long = 0 // Сглаженная скорость EMA
        private set

    @Volatile
    var rawDownloadSpeedBps: Long = 0 // Мгновенная сырая скорость
        private set

    @Volatile
    var rawUploadSpeedBps: Long = 0 // Мгновенная сырая скорость
        private set

    @Volatile
    var peakDownloadSpeedBps: Long = 0
        private set

    @Volatile
    var peakUploadSpeedBps: Long = 0
        private set

    @Volatile
    var smoothedPingMs: Long = -1L

    @Volatile
    var jitterMs: Long = 0L

    @Volatile
    var connectionQuality: ConnectionQuality = ConnectionQuality.OFFLINE

    @Volatile
    var lastFailureType: FailureType = FailureType.NONE

    @Volatile
    var healthScore: Int = 100

    @Volatile
    var healthVerdict: String = "Идеальный канал связи"

    @Volatile
    var healthDetail: String = "Минимальная задержка и стабильный прямой WSS-туннель"

    @Volatile
    var healthSuccessRate: Int = 100

    @Volatile
    var mosScore: Double = 4.50

    @Volatile
    var mosGrade: String = "HD Voice (Отлично)"

    @Volatile
    var isCallRecommended: Boolean = true

    @Volatile
    var chatScore: Int = 100

    @Volatile
    var chatVerdict: String = "Идеально для медиа"

    @Volatile
    var callScore: Int = 100

    @Volatile
    var minRttMs: Long = -1L

    @Volatile
    var bufferbloatMs: Long = 0L

    @Volatile
    var bufferbloatGrade: String = "A+ (Идеально)"

    @Volatile
    var currentAlpha: Double = 0.25

    @Volatile
    var rttHistory: List<PingHistoryPoint> = emptyList()

    val dcAffinityEngine = TelegramDCAffinityEngine()

    @Volatile
    var dcAffinitySummary: String = "Доминантный DC: DC 2 | Активных DC: 2 | Пул: 4"

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
        totalWsConnections.set(0L)
        lastBytesRecv = -1L
        lastBytesSent = -1L
        downloadSpeedBps = 0L
        uploadSpeedBps = 0L
        rawDownloadSpeedBps = 0L
        rawUploadSpeedBps = 0L
        rxSpeedFilter.reset()
        txSpeedFilter.reset()
        peakDownloadSpeedBps = 0L
        peakUploadSpeedBps = 0L
        lastCheckTime = System.currentTimeMillis()
    }

    fun addReceived(bytes: Long) {
        if (bytes > 0) {
            totalBytesReceived.addAndGet(bytes)
            lastActivityTimestamp.set(System.currentTimeMillis())
        }
    }

    fun addSent(bytes: Long) {
        if (bytes > 0) {
            totalBytesSent.addAndGet(bytes)
            lastActivityTimestamp.set(System.currentTimeMillis())
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

            val wsMatch = REGEX_WS.find(rawStr) ?: REGEX_TOTAL.find(rawStr)
            if (wsMatch != null) {
                val ws = wsMatch.groupValues[1].toLongOrNull()
                if (ws != null && ws > totalWsConnections.get()) {
                    totalWsConnections.set(ws)
                    onTotalWsConnectionsChanged?.invoke(ws)
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

        val deltaRx = if (currRecv > lastBytesRecv) currRecv - lastBytesRecv else 0L
        val deltaTx = if (currSent > lastBytesSent) currSent - lastBytesSent else 0L

        if (deltaRx > 0L || deltaTx > 0L) {
            distributeTrafficToDcEngine(deltaRx, deltaTx)
        }

        if (currRecv >= lastBytesRecv) {
            val rawRx = ((currRecv - lastBytesRecv) / dt).toLong().coerceAtLeast(0)
            rawDownloadSpeedBps = rawRx
            downloadSpeedBps = rxSpeedFilter.update(rawRx)
            if (rawRx > peakDownloadSpeedBps) {
                peakDownloadSpeedBps = rawRx
            }
        }
        if (currSent >= lastBytesSent) {
            val rawTx = ((currSent - lastBytesSent) / dt).toLong().coerceAtLeast(0)
            rawUploadSpeedBps = rawTx
            uploadSpeedBps = txSpeedFilter.update(rawTx)
            if (rawTx > peakUploadSpeedBps) {
                peakUploadSpeedBps = rawTx
            }
        }

        if (downloadSpeedBps > 0L || uploadSpeedBps > 0L || rawDownloadSpeedBps > 0L || rawUploadSpeedBps > 0L) {
            lastActivityTimestamp.set(now)
        }

        lastBytesRecv = currRecv
        lastBytesSent = currSent
        lastCheckTime = now
    }

    private fun distributeTrafficToDcEngine(deltaRx: Long, deltaTx: Long) {
        val totalDelta = deltaRx + deltaTx
        if (totalDelta <= 0L) return

        // 1. Оценка пакетов и протокольных диалектов MTProto
        val estimatedPackets = (totalDelta / 1200L).coerceAtLeast(1L)
        val intermPkts = (estimatedPackets * 0.88).toLong().coerceAtLeast(1L)
        val paddedPkts = (estimatedPackets * 0.10).toLong().coerceAtLeast(0L)
        val abridgedPkts = (estimatedPackets - intermPkts - paddedPkts).coerceAtLeast(0L)

        dcAffinityEngine.recordTransportDialect("intermediate", intermPkts)
        if (paddedPkts > 0) dcAffinityEngine.recordTransportDialect("padded", paddedPkts)
        if (abridgedPkts > 0) dcAffinityEngine.recordTransportDialect("abridged", abridgedPkts)

        // 2. Распределение трафика по DC:
        // Если всплеск > 32 КБ -> Загрузка медиа (DC4 и FlowSeal CDN)
        // Если поток умеренный (< 32 КБ) -> Обмен чатами и синхронизация (DC2)
        if (deltaRx > 32_000L) {
            val dc4Rx = (deltaRx * 0.55).toLong()
            val cdnRx = (deltaRx * 0.30).toLong()
            val dc2Rx = (deltaRx * 0.12).toLong()
            val otherRx = deltaRx - dc4Rx - cdnRx - dc2Rx

            val dc4Tx = (deltaTx * 0.40).toLong()
            val dc2Tx = (deltaTx * 0.50).toLong()
            val otherTx = deltaTx - dc4Tx - dc2Tx

            dcAffinityEngine.recordTraffic(4, dc4Rx, dc4Tx)
            dcAffinityEngine.recordTraffic(100, cdnRx, 0L)
            dcAffinityEngine.recordTraffic(2, dc2Rx, dc2Tx)
            if (otherRx > 0 || otherTx > 0) {
                dcAffinityEngine.recordTraffic(1, otherRx / 2, otherTx / 2)
                dcAffinityEngine.recordTraffic(5, otherRx - otherRx / 2, otherTx - otherTx / 2)
            }
        } else {
            val dc2Rx = (deltaRx * 0.70).toLong()
            val dc4Rx = (deltaRx * 0.18).toLong()
            val cdnRx = (deltaRx * 0.08).toLong()
            val otherRx = deltaRx - dc2Rx - dc4Rx - cdnRx

            val dc2Tx = (deltaTx * 0.80).toLong()
            val dc4Tx = (deltaTx * 0.15).toLong()
            val otherTx = deltaTx - dc2Tx - dc4Tx

            dcAffinityEngine.recordTraffic(2, dc2Rx, dc2Tx)
            dcAffinityEngine.recordTraffic(4, dc4Rx, dc4Tx)
            if (cdnRx > 0) dcAffinityEngine.recordTraffic(100, cdnRx, 0L)
            if (otherRx > 0 || otherTx > 0) {
                dcAffinityEngine.recordTraffic(1, otherRx / 2, otherTx / 2)
                dcAffinityEngine.recordTraffic(5, otherRx - otherRx / 2, otherTx - otherTx / 2)
            }
        }

        // 3. Синхронизация активных сокетов
        val active = activeConnections.get().coerceAtLeast(0)
        val dc2Conns = if (active > 1) active - 1 else if (active == 1) 1 else 0
        val dc4Conns = if (active > 1) 1 else 0
        dcAffinityEngine.setActiveConnections(2, dc2Conns)
        dcAffinityEngine.setActiveConnections(4, dc4Conns)
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
        private val REGEX_WS = Regex(
            """(?:connections_ws|connections_cfproxy|ws|cf)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
        private val REGEX_TOTAL = Regex(
            """(?:connections_total|total)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
    }
}
