package com.mirrly.tgproxy.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ProxyStats {
    val totalBytesReceived = AtomicLong(0)
    val totalBytesSent = AtomicLong(0)
    val activeConnections = AtomicInteger(0)
    val totalWsConnections = AtomicLong(0)
    val totalMasqueConnections = AtomicLong(0)
    val totalAwgConnections = AtomicLong(0)
    val totalVlessConnections = AtomicLong(0)
    val totalOperaConnections = AtomicLong(0)
    val totalSocks5V2Sessions = AtomicLong(0)
    val totalSocks5V1Downgrades = AtomicLong(0)
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
    var healthVerdict: String = "Optimal Connection"

    @Volatile
    var healthDetail: String = "Minimal latency and stable direct WSS tunnel"

    @Volatile
    var healthSuccessRate: Int = 100

    @Volatile
    var mosScore: Double = 4.50

    @Volatile
    var mosGrade: String = "HD Voice (Excellent)"

    @Volatile
    var isCallRecommended: Boolean = true

    @Volatile
    var chatScore: Int = 100

    @Volatile
    var chatVerdict: String = "Ideal for media"

    @Volatile
    var callScore: Int = 100

    @Volatile
    var minRttMs: Long = -1L

    @Volatile
    var bufferbloatMs: Long = 0L

    @Volatile
    var bufferbloatGrade: String = "A+ (Excellent)"

    @Volatile
    var currentAlpha: Double = 0.25

    @Volatile
    var rttHistory: List<PingHistoryPoint> = emptyList()

    val dcAffinityEngine = TelegramDCAffinityEngine()

    @Volatile
    var dcAffinitySummary: String = "Dominant DC: DC 2 | Active DC: 2 | MTProto standby: 2"

    @Volatile
    var activeCascadeStage: String = "Scanned WARP (Frag)"

    @Volatile
    var activeCascadeStageCode: Int = 0

    @Volatile
    var activeRouteGeneration: Long = 0L

    @Volatile
    var activeRouteId: String = ""

    @Volatile
    var activeRouteTrace: String = ""

    @Volatile
    var lastActiveProbeRttMs: Long = -1L

    @Volatile
    var isProbeAlive: Boolean = true

    @Volatile
    var activeEffectiveRoute: String = ""

    @Volatile
    var activeOperator: String = ""

    @Volatile
    var isTrustBoundaryMaintained: Boolean = true

    @Volatile
    var isPrivateNode: Boolean = false

    @Volatile
    var isTransportReady: Boolean = false

    @Volatile
    var isAppReady: Boolean = false

    @Volatile
    var isUdpSupported: Boolean = true

    @Volatile
    var lastAppError: String = ""

    @Volatile
    var appSuccessCount: Long = 0L

    @Volatile
    var timeToUsefulRxMs: Long = -1L

    @Volatile
    var stageTimelineSli: StageTimelineSliSummary? = null

    @Volatile
    var recentTimelineAttempts: List<ConnectionTimelineItem> = emptyList()

    @Volatile
    var dialBudgetStats: DialBudgetStats? = null

    @Volatile
    var appFailureCount: Long = 0L


    fun resetHealthSnapshot() {
        smoothedPingMs = -1L
        jitterMs = 0L
        connectionQuality = ConnectionQuality.OFFLINE
        lastFailureType = FailureType.NONE
        healthScore = 100
        healthVerdict = "Optimal Connection"
        healthDetail = "Waiting for network check..."
        healthSuccessRate = 100
        mosScore = 4.50
        mosGrade = "HD Voice (Excellent)"
        isCallRecommended = true
        chatScore = 100
        chatVerdict = "Ideal for media"
        callScore = 100
        minRttMs = -1L
        bufferbloatMs = 0L
        bufferbloatGrade = "A+ (Excellent)"
        currentAlpha = 0.25
        rttHistory = emptyList()
        lastActiveProbeRttMs = -1L
        isProbeAlive = false
        isTransportReady = false
        isAppReady = false
        lastAppError = ""
    }

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
        totalMasqueConnections.set(0L)
        totalAwgConnections.set(0L)
        totalVlessConnections.set(0L)
        totalOperaConnections.set(0L)
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
        activeEffectiveRoute = ""
        activeOperator = ""
        isTrustBoundaryMaintained = true
        isPrivateNode = false
        lastCheckTime = System.currentTimeMillis()
        resetHealthSnapshot()
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

            val masqueMatch = REGEX_MASQUE.find(rawStr)
            if (masqueMatch != null) {
                val m = masqueMatch.groupValues[1].toLongOrNull()
                if (m != null && m > totalMasqueConnections.get()) {
                    totalMasqueConnections.set(m)
                }
            }

            val awgMatch = REGEX_AWG.find(rawStr)
            if (awgMatch != null) {
                val a = awgMatch.groupValues[1].toLongOrNull()
                if (a != null && a > totalAwgConnections.get()) {
                    totalAwgConnections.set(a)
                }
            }

            val vlessMatch = REGEX_VLESS.find(rawStr)
            if (vlessMatch != null) {
                val v = vlessMatch.groupValues[1].toLongOrNull()
                if (v != null && v > totalVlessConnections.get()) {
                    totalVlessConnections.set(v)
                }
            }

            val operaMatch = REGEX_OPERA.find(rawStr)
            if (operaMatch != null) {
                val o = operaMatch.groupValues[1].toLongOrNull()
                if (o != null && o > totalOperaConnections.get()) {
                    totalOperaConnections.set(o)
                }
            }

            val v2Match = REGEX_V2.find(rawStr)
            if (v2Match != null) {
                val v2 = v2Match.groupValues[1].toLongOrNull()
                if (v2 != null && v2 > totalSocks5V2Sessions.get()) {
                    totalSocks5V2Sessions.set(v2)
                }
            }

            val v1DownMatch = REGEX_V1_DOWN.find(rawStr)
            if (v1DownMatch != null) {
                val v1Down = v1DownMatch.groupValues[1].toLongOrNull()
                if (v1Down != null && v1Down > totalSocks5V1Downgrades.get()) {
                    totalSocks5V1Downgrades.set(v1Down)
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

    companion object {
        private val REGEX_CONNS = Regex(
            """(?:active_connections|active_conns|active_conn|active|conns|connections|conn|акт)[\s=:]+['"]?(\d+)""",
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
        private val REGEX_MASQUE = Regex(
            """(?:connections_masque|masque)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
        private val REGEX_AWG = Regex(
            """(?:connections_awg|awg)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
        private val REGEX_VLESS = Regex(
            """(?:connections_vless|vless)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
        private val REGEX_OPERA = Regex(
            """(?:connections_opera|opera)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
        private val REGEX_V2 = Regex(
            """(?:v2)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
        private val REGEX_V1_DOWN = Regex(
            """(?:v1_down)[\s=:]+['"]?(\d+)""",
            RegexOption.IGNORE_CASE
        )
    }
}
