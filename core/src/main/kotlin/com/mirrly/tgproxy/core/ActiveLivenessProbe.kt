/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.mirrly.tgproxy.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * Этапы каскадного переключения аплинка при глушении сессии ТСПУ:
 * IPv6 WARP -> Scanned WARP (Frag) -> MASQUE (HTTP/3) -> VLESS Preset
 */
enum class CascadeStage(val title: String) {
    STAGE_0_IPV6_WARP("IPv6 WARP"),
    STAGE_1_SCANNED_WARP("Scanned WARP (Frag)"),
    STAGE_2_MASQUE_HTTP3("MASQUE (HTTP/3)"),
    STAGE_3_VLESS_PRESET("VLESS Preset");

    fun next(): CascadeStage {
        return when (this) {
            STAGE_0_IPV6_WARP -> STAGE_1_SCANNED_WARP
            STAGE_1_SCANNED_WARP -> STAGE_2_MASQUE_HTTP3
            STAGE_2_MASQUE_HTTP3 -> STAGE_3_VLESS_PRESET
            STAGE_3_VLESS_PRESET -> STAGE_0_IPV6_WARP
        }
    }
}

data class ActiveProbeResult(
    val isAlive: Boolean,
    val rttMs: Long,
    val targetDc: String,
    val failureReason: String? = null,
    val rxBytes: Int = 0,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Движок быстрой "живой пробы" (Active Liveness Probe) туннеля через активный аплинк
 * с каскадным переключением при обнаружении блокировки ТСПУ (таймаут > 800мс).
 */
class ActiveLivenessProbe(
    private val isSocks5ModeProvider: () -> Boolean = { true },
    private val socks5PortProvider: () -> Int = { 10808 },
    private val socks5AuthProvider: () -> Pair<String, String> = { Pair("", "") },
    private val trafficThroughputProvider: () -> Long = { 0L },
    private val onCascadeTriggered: (oldStage: CascadeStage, newStage: CascadeStage, reason: String) -> Unit
) {
    companion object {
        private const val TAG = "ActiveLivenessProbe"

        // Telegram DC2 (Амстердам, основной европейский DC)
        const val TELEGRAM_DC2_IP = "149.154.167.51"
        // Telegram DC4 (Амстердам, второй европейский DC)
        const val TELEGRAM_DC4_IP = "149.154.167.91"
        const val TELEGRAM_PORT = 443

        const val DEFAULT_TIMEOUT_MS = 3500
        const val DEFAULT_WARM_TIMEOUT_MS = 1200
        const val DEFAULT_COLD_TIMEOUT_MS = 4000
        const val DEFAULT_FAILOVER_CONSECUTIVE_COUNT = 2

        /**
         * Проверяет наличие глобального маршрутизируемого IPv6 на активных интерфейсах устройства.
         */
        fun isIpv6Available(): Boolean {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    for (addr in intf.inetAddresses) {
                        if (addr is Inet6Address &&
                            !addr.isLoopbackAddress &&
                            !addr.isLinkLocalAddress &&
                            !addr.isSiteLocalAddress &&
                            !addr.isMulticastAddress
                        ) {
                            return true
                        }
                    }
                }
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var probeJob: Job? = null

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var isDormant: Boolean = false

    @Volatile
    var isScreenOn: Boolean = true

    @Volatile
    var timeoutMs: Int = DEFAULT_TIMEOUT_MS

    @Volatile
    var failoverThreshold: Int = DEFAULT_FAILOVER_CONSECUTIVE_COUNT

    @Volatile
    var currentStage: CascadeStage = CascadeStage.STAGE_1_SCANNED_WARP
        private set

    @Volatile
    var consecutiveFailures: Int = 0
        private set

    @Volatile
    var lastProbeResult: ActiveProbeResult? = null
        private set

    @Volatile
    var onProbeCompleted: ((ActiveProbeResult) -> Unit)? = null

    fun start() {
        if (probeJob?.isActive == true) return
        probeJob = scope.launch {
            runProbeLoop()
        }
        AppLogger.i(TAG, "Active Liveness Probe запущен (таймаут: ${timeoutMs}мс, каскад: ${currentStage.title})")
    }

    fun stop() {
        probeJob?.cancel()
        probeJob = null
        consecutiveFailures = 0
        AppLogger.i(TAG, "Active Liveness Probe остановлен")
    }

    fun setInitialStage(stage: CascadeStage) {
        currentStage = stage
        consecutiveFailures = 0
    }

    private suspend fun runProbeLoop() {
        // Стартовая задержка 3 секунды для стабилизации сокетов после запуска прокси
        delay(3000L)

        while (scope.isActive) {
            if (!isEnabled || isDormant || !isSocks5ModeProvider()) {
                delay(15000L)
                continue
            }

            val rxThroughput = trafficThroughputProvider()
            // Пассивный мониторинг: только если через туннель поступает полезный входящий трафик (> 2 КБ/с RX).
            // Исходящий трафик (TX) или собственный SOCKS-ответ не должны маскировать глушение ТСПУ.
            if (rxThroughput > 2048L) {
                consecutiveFailures = 0
                WarpEndpointScanner.recordStickySuccess()
                val delayMs = if (isScreenOn) 15000L else 45000L
                delay(delayMs)
                continue
            }

            val effectiveTimeout = if (lastProbeResult?.isAlive == true) {
                timeoutMs.coerceAtMost(DEFAULT_WARM_TIMEOUT_MS).coerceAtLeast(800)
            } else {
                timeoutMs.coerceAtLeast(DEFAULT_COLD_TIMEOUT_MS)
            }

            val result = executeSocks5Probe(
                targetHost = TELEGRAM_DC2_IP,
                targetPort = TELEGRAM_PORT,
                timeoutMs = effectiveTimeout
            )
            lastProbeResult = result
            onProbeCompleted?.invoke(result)

            if (result.isAlive) {
                consecutiveFailures = 0
                WarpEndpointScanner.recordStickySuccess()
                val delayMs = if (isScreenOn) 15000L else 45000L
                delay(delayMs)
            } else {
                consecutiveFailures++
                val stickyInvalidated = WarpEndpointScanner.recordStickyTimeout()
                AppLogger.w(
                    TAG,
                    "Проба туннеля не ответила за ${timeoutMs}мс (сбой $consecutiveFailures/$failoverThreshold, stickyInvalidated=$stickyInvalidated): ${result.failureReason}"
                )

                if (consecutiveFailures >= failoverThreshold) {
                    triggerCascadeFailover("Превышен порог задержки/таймаута ($consecutiveFailures сбоев подряд)")
                    // Пауза 2.5 секунды после каскада для инициализации нового транспорта
                    delay(2500L)
                } else {
                    // Быстрый повторный зонд через 1.5 секунды для подтверждения сбоя
                    delay(1500L)
                }
            }
        }
    }

    /**
     * Выполняет единичную контрольную пробу туннеля через локальный SOCKS5 релей.
     * При [e2eProbe] == true выполняет отправку прикладного запроса и валидирует полезный RX.
     */
    suspend fun executeSocks5Probe(
        targetHost: String = TELEGRAM_DC2_IP,
        targetPort: Int = TELEGRAM_PORT,
        timeoutMs: Int = this.timeoutMs,
        e2eProbe: Boolean = false,
        probePayload: ByteArray? = null
    ): ActiveProbeResult = withContext(Dispatchers.IO) {
        val port = socks5PortProvider()
        val (user, pass) = socks5AuthProvider()
        val startNs = System.nanoTime()
        var socket: Socket? = null

        try {
            socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)

            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // 1. SOCKS5 Auth Handshake (RFC 1928 / RFC 1929)
            if (user.isNotEmpty() || pass.isNotEmpty()) {
                output.write(byteArrayOf(0x05, 0x01, 0x02)) // VER=5, NMETHODS=1, METHOD=0x02 (USER/PASS)
                output.flush()

                val authResp = ByteArray(2)
                readFully(input, authResp)
                if (authResp[0] != 0x05.toByte() || authResp[1] != 0x02.toByte()) {
                    return@withContext ActiveProbeResult(
                        isAlive = false,
                        rttMs = -1L,
                        targetDc = "$targetHost:$targetPort",
                        failureReason = "SOCKS5 отклонил авторизацию (метод ${authResp[1]})"
                    )
                }

                // RFC 1929 Subnegotiation
                val uBytes = user.toByteArray(StandardCharsets.UTF_8)
                val pBytes = pass.toByteArray(StandardCharsets.UTF_8)
                val authPayload = ByteArray(3 + uBytes.size + pBytes.size)
                authPayload[0] = 0x01
                authPayload[1] = uBytes.size.toByte()
                System.arraycopy(uBytes, 0, authPayload, 2, uBytes.size)
                authPayload[2 + uBytes.size] = pBytes.size.toByte()
                System.arraycopy(pBytes, 0, authPayload, 3 + uBytes.size, pBytes.size)
                output.write(authPayload)
                output.flush()

                val authStatus = ByteArray(2)
                readFully(input, authStatus)
                if (authStatus[0] != 0x01.toByte() || authStatus[1] != 0x00.toByte()) {
                    return@withContext ActiveProbeResult(
                        isAlive = false,
                        rttMs = -1L,
                        targetDc = "$targetHost:$targetPort",
                        failureReason = "SOCKS5 логин/пароль не принят (статус ${authStatus[1]})"
                    )
                }
            } else {
                output.write(byteArrayOf(0x05, 0x01, 0x00)) // VER=5, NMETHODS=1, METHOD=0x00 (NO AUTH)
                output.flush()

                val authResp = ByteArray(2)
                readFully(input, authResp)
                if (authResp[0] != 0x05.toByte() || authResp[1] != 0x00.toByte()) {
                    return@withContext ActiveProbeResult(
                        isAlive = false,
                        rttMs = -1L,
                        targetDc = "$targetHost:$targetPort",
                        failureReason = "SOCKS5 метод NO AUTH отклонен (${authResp[1]})"
                    )
                }
            }

            // 2. SOCKS5 CONNECT Request (RFC 1928 §4)
            val ipBytes = InetAddress.getByName(targetHost).address
            val connectReq = ByteArray(10)
            connectReq[0] = 0x05 // VER
            connectReq[1] = 0x01 // CMD = 1 (CONNECT)
            connectReq[2] = 0x00 // RSV
            connectReq[3] = 0x01 // ATYP = 1 (IPv4)
            System.arraycopy(ipBytes, 0, connectReq, 4, 4)
            connectReq[8] = ((targetPort shr 8) and 0xFF).toByte()
            connectReq[9] = (targetPort and 0xFF).toByte()

            output.write(connectReq)
            output.flush()

            // 3. Точный разбор ответа SOCKS5 (RFC 1928 §6)
            val (rep, _) = readSocks5Reply(input)

            if (rep != 0x00) {
                return@withContext ActiveProbeResult(
                    isAlive = false,
                    rttMs = (System.nanoTime() - startNs) / 1_000_000,
                    targetDc = "$targetHost:$targetPort",
                    failureReason = "SOCKS5 ошибка ответа сервера REP=0x${Integer.toHexString(rep)}"
                )
            }

            // 4. Опциональная сквозная E2E проба полезных данных
            if (e2eProbe || probePayload != null) {
                val payload = probePayload ?: NodeHealthProber.buildDefaultProbePayload(targetHost, targetPort)
                output.write(payload)
                output.flush()

                val rxBuf = ByteArray(1024)
                val usefulRx = input.read(rxBuf)
                if (usefulRx <= 0) {
                    return@withContext ActiveProbeResult(
                        isAlive = false,
                        rttMs = (System.nanoTime() - startNs) / 1_000_000,
                        targetDc = "$targetHost:$targetPort",
                        failureReason = "E2E: сервер не вернул ответные данные после CONNECT (RX=0)",
                        rxBytes = 0
                    )
                }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                return@withContext ActiveProbeResult(
                    isAlive = true,
                    rttMs = elapsedMs,
                    targetDc = "$targetHost:$targetPort",
                    rxBytes = usefulRx
                )
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            ActiveProbeResult(
                isAlive = true,
                rttMs = elapsedMs,
                targetDc = "$targetHost:$targetPort",
                rxBytes = 0
            )
        } catch (_: SocketTimeoutException) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            ActiveProbeResult(
                isAlive = false,
                rttMs = elapsedMs,
                targetDc = "$targetHost:$targetPort",
                failureReason = "Таймаут зонда (> ${timeoutMs}мс, ТСПУ дроп пакетов)"
            )
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            ActiveProbeResult(
                isAlive = false,
                rttMs = elapsedMs,
                targetDc = "$targetHost:$targetPort",
                failureReason = e.message ?: "Сетевая ошибка при пробе"
            )
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun readFully(stream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = stream.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw EOFException("Неожиданный конец потока SOCKS5: прочитано $offset из ${buffer.size} байт")
            offset += read
        }
    }

    private fun readSocks5Reply(stream: InputStream): Pair<Int, String> {
        val header = ByteArray(4)
        readFully(stream, header)
        if (header[0] != 0x05.toByte()) {
            throw IllegalArgumentException("Неверная версия SOCKS5 reply: ${header[0].toInt() and 0xFF}")
        }
        val rep = header[1].toInt() and 0xFF
        val atyp = header[3].toInt() and 0xFF
        val boundAddr = when (atyp) {
            0x01 -> {
                val bnd = ByteArray(6)
                readFully(stream, bnd)
                val ip = "${bnd[0].toInt() and 0xFF}.${bnd[1].toInt() and 0xFF}.${bnd[2].toInt() and 0xFF}.${bnd[3].toInt() and 0xFF}"
                val port = ((bnd[4].toInt() and 0xFF) shl 8) or (bnd[5].toInt() and 0xFF)
                "$ip:$port"
            }
            0x03 -> {
                val lenBuf = ByteArray(1)
                readFully(stream, lenBuf)
                val domainLen = lenBuf[0].toInt() and 0xFF
                val domainBuf = ByteArray(domainLen)
                readFully(stream, domainBuf)
                val portBuf = ByteArray(2)
                readFully(stream, portBuf)
                val domain = String(domainBuf, StandardCharsets.UTF_8)
                val port = ((portBuf[0].toInt() and 0xFF) shl 8) or (portBuf[1].toInt() and 0xFF)
                "$domain:$port"
            }
            0x04 -> {
                val bnd = ByteArray(18)
                readFully(stream, bnd)
                val port = ((bnd[16].toInt() and 0xFF) shl 8) or (bnd[17].toInt() and 0xFF)
                "[IPv6]:$port"
            }
            else -> throw IllegalArgumentException("Неподдерживаемый SOCKS5 ATYP: $atyp")
        }
        return Pair(rep, boundAddr)
    }

    /**
     * Выполняет переход на следующий транспорт по цепочке каскада:
     * IPv6 WARP -> Scanned WARP (Frag) -> MASQUE (HTTP/3) -> VLESS Preset
     */
    fun triggerCascadeFailover(reason: String) {
        val oldStage = currentStage
        var nextStage = oldStage.next()

        // Если следующий этап - IPv6 WARP, но на устройстве нет глобального IPv6,
        // мгновенно переходим к этапу Scanned WARP (Frag)
        if (nextStage == CascadeStage.STAGE_0_IPV6_WARP && !isIpv6Available()) {
            AppLogger.i(TAG, "IPv6 недоступен на сетевом интерфейсе устройства, пропуск этапа STAGE_0_IPV6_WARP")
            nextStage = CascadeStage.STAGE_1_SCANNED_WARP
        }

        currentStage = nextStage
        consecutiveFailures = 0
        AppLogger.w(TAG, "Каскадный переход: [${oldStage.title}] -> [${nextStage.title}]. Причина: $reason")
        onCascadeTriggered(oldStage, nextStage, reason)
    }
}
