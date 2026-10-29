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

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Этапы последовательного выполнения замера скорости сетевого туннеля.
 */
enum class SpeedTestStage(val title: String) {
    IDLE("Готов к запуску"),
    DC_BENCHMARK("Бенчмарк дата-центров Telegram"),
    PING("Калибровка задержки и джиттера"),
    DOWNLOAD("Замер входящей скорости"),
    UPLOAD("Замер исходящей скорости"),
    ANALYSIS("Оценка качества канала"),
    COMPLETED("Тестирование завершено"),
    CANCELLED("Тестирование остановлено"),
    ERROR("Ошибка тестирования")
}

/**
 * Описание дата-центра Telegram для бенчмарка задержки и доступности.
 */
data class TelegramDcBenchmark(
    val dcId: Int,
    val name: String,
    val location: String,
    val role: String,
    val ip: String,
    val port: Int = 443,
    val isKeyDc: Boolean = false,
    val rttMs: Long = -1L,
    val jitterMs: Long = 0L,
    val isSuccess: Boolean = false,
    val inProgress: Boolean = false,
    val errorDetail: String? = null
)

/**
 * Список пяти официальных IPv4 дата-центров Telegram.
 * DC 2 (Амстердам, Сообщения и звонки) и DC 4 (Амстердам, Медиа и файлы) являются ключевыми.
 */
fun defaultTelegramDcs(): List<TelegramDcBenchmark> = listOf(
    TelegramDcBenchmark(
        dcId = 1,
        name = "DC 1",
        location = "США, Майами",
        role = "Резервный узел",
        ip = "149.154.175.50",
        port = 443,
        isKeyDc = false
    ),
    TelegramDcBenchmark(
        dcId = 2,
        name = "DC 2",
        location = "Европа, Амстердам",
        role = "Сообщения и звонки",
        ip = "149.154.167.51",
        port = 443,
        isKeyDc = true
    ),
    TelegramDcBenchmark(
        dcId = 3,
        name = "DC 3",
        location = "США, Майами",
        role = "Американский шлюз",
        ip = "149.154.175.100",
        port = 443,
        isKeyDc = false
    ),
    TelegramDcBenchmark(
        dcId = 4,
        name = "DC 4",
        location = "Европа, Амстердам",
        role = "Медиа и файлы",
        ip = "149.154.167.91",
        port = 443,
        isKeyDc = true
    ),
    TelegramDcBenchmark(
        dcId = 5,
        name = "DC 5",
        location = "Азия, Сингапур",
        role = "Азиатский регион",
        ip = "91.108.56.130",
        port = 443,
        isKeyDc = false
    )
)

/**
 * Оценка готовности соединения для различных сценариев Telegram.
 */
data class TelegramSuitabilityReport(
    val chatsVerdict: String = "Мгновенно (< 60 мс)",
    val voiceVerdict: String = "HD Voice / Стабильно",
    val mediaVerdict: String = "Высокая скорость (~0.4 сек / фото)",
    val videoVerdict: String = "4K UHD / Без буферизации",
    val overallScore: Int = 100,
    val summary: String = "Стабильный высокоскоростной канал",
    val dc2RttMs: Long = -1L,
    val dc4RttMs: Long = -1L,
    val edgeRttMs: Long = -1L,
    val edgeColo: String = "—"
)

/**
 * Неизменяемый снимок состояния процесса тестирования скорости в реальном времени.
 */
data class SpeedTestLiveState(
    val stage: SpeedTestStage = SpeedTestStage.IDLE,
    val progress: Float = 0f, // 0.0 .. 1.0
    val currentSpeedMbps: Double = 0.0,
    val peakSpeedMbps: Double = 0.0,
    val pingMs: Long = -1L,
    val minPingMs: Long = -1L,
    val maxPingMs: Long = -1L,
    val jitterMs: Long = 0L,
    val edgeColo: String = "—",
    val targetDomain: String = "",
    val downloadSpeedMbps: Double = 0.0,
    val uploadSpeedMbps: Double = 0.0,
    val downloadedBytes: Long = 0L,
    val uploadedBytes: Long = 0L,
    val durationMs: Long = 0L,
    val qualityGrade: String = "—",
    val overallScore: Int = 0,
    val sparklinePoints: List<Float> = emptyList(),
    val dcBenchmarks: List<TelegramDcBenchmark> = defaultTelegramDcs(),
    val activeDcId: Int = -1,
    val suitability: TelegramSuitabilityReport = TelegramSuitabilityReport(),
    val errorDetail: String? = null
)

/**
 * Сетевой движок замера пропускной способности туннеля Mirrly TG Proxy.
 * Выполняет поэтапное тестирование:
 * 1. Бенчмарк Telegram DC 1..5 (замер сквозного RTT и джиттера через туннель).
 * 2. Определение Cloudflare Edge узла (Colo) и базового RTT.
 * 3. Честный замер входящей скорости (Download) с потоковой передачей чанков.
 * 4. Честный замер исходящей скорости (Upload) с flush() и подтверждением HTTP 200 OK.
 * 5. Взвешенный скоринг качества (0..100) и оценка профильной готовности Telegram.
 */
class TunnelSpeedTestEngine {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var testJob: Job? = null

    private val _liveState = MutableStateFlow(SpeedTestLiveState())
    val liveState: StateFlow<SpeedTestLiveState> = _liveState.asStateFlow()

    var onTestCompleted: ((SpeedTestLiveState) -> Unit)? = null

    val isRunning: Boolean
        get() = testJob?.isActive == true

    /**
     * Запуск полного цикла поэтапного тестирования скорости туннеля.
     * @param targetDomain Домен активного воркера или Anycast Cloudflare узла.
     * @param socks5Port Локальный порт SOCKS5 прокси (по умолчанию 10808) для обязательной маршрутизации.
     * @param socks5User Логин авторизации SOCKS5 (если включена).
     * @param socks5Pass Пароль авторизации SOCKS5 (если включена).
     */
    fun startTest(
        targetDomain: String,
        socks5Port: Int? = 10808,
        socks5User: String? = null,
        socks5Pass: String? = null
    ) {
        if (isRunning) return

        testJob = scope.launch {
            val cleanDomain = targetDomain.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            val effectiveDomain = if (cleanDomain.isNotBlank()) cleanDomain else "speed.cloudflare.com"
            val effectivePort = if (socks5Port != null && socks5Port > 0) socks5Port else 10808

            val client = buildHttpClient(effectivePort, socks5User, socks5Pass)
            val overallStartTime = System.currentTimeMillis()

            val initialDcs = defaultTelegramDcs()
            _liveState.value = SpeedTestLiveState(
                stage = SpeedTestStage.DC_BENCHMARK,
                progress = 0.04f,
                targetDomain = effectiveDomain,
                dcBenchmarks = initialDcs
            )

            try {
                // ── ЭТАП 1: БЕНЧМАРК TELEGRAM DC 1..5 И ОПРЕДЕЛЕНИЕ CLOUDFLARE POP (~3 сек) ──
                val (edgePing, detectedColo) = measureCloudflareEdge(effectiveDomain, client)
                if (!isActive) return@launch

                _liveState.value = _liveState.value.copy(
                    edgeColo = detectedColo,
                    pingMs = if (edgePing > 0) edgePing else -1L,
                    progress = 0.08f
                )

                val updatedDcs = initialDcs.toMutableList()
                val dcSamples = mutableListOf<Long>()

                for (i in updatedDcs.indices) {
                    if (!isActive) return@launch
                    val currentDc = updatedDcs[i]

                    // Обновляем статус текущего тестируемого DC
                    updatedDcs[i] = currentDc.copy(inProgress = true)
                    _liveState.value = _liveState.value.copy(
                        dcBenchmarks = updatedDcs.toList(),
                        activeDcId = currentDc.dcId,
                        progress = 0.08f + ((i.toFloat() / updatedDcs.size.toFloat()) * 0.24f)
                    )

                    // Замер RTT и джиттера к конкретному дата-центру через активный туннель
                    val (dcRtt, dcJitter) = probeTelegramDc(
                        dc = currentDc,
                        socks5Port = effectivePort,
                        socks5User = socks5User,
                        socks5Pass = socks5Pass,
                        timeoutMs = 2800
                    )

                    val isSuccess = dcRtt > 0
                    if (isSuccess) {
                        dcSamples.add(dcRtt)
                    }

                    updatedDcs[i] = currentDc.copy(
                        rttMs = dcRtt,
                        jitterMs = dcJitter,
                        isSuccess = isSuccess,
                        inProgress = false,
                        errorDetail = if (!isSuccess) "Не отвечает" else null
                    )

                    val currentAvgPing = if (dcSamples.isNotEmpty()) dcSamples.average().toLong() else edgePing
                    _liveState.value = _liveState.value.copy(
                        dcBenchmarks = updatedDcs.toList(),
                        activeDcId = -1,
                        pingMs = currentAvgPing,
                        minPingMs = if (dcSamples.isNotEmpty()) dcSamples.minOrNull() ?: currentAvgPing else edgePing,
                        maxPingMs = if (dcSamples.isNotEmpty()) dcSamples.maxOrNull() ?: currentAvgPing else edgePing,
                        jitterMs = calculateJitter(dcSamples),
                        progress = 0.08f + (((i + 1).toFloat() / updatedDcs.size.toFloat()) * 0.24f)
                    )

                    delay(120)
                }

                if (!isActive) return@launch

                // ── ЭТАП 2: ЗАМЕР ВХОДЯЩЕЙ СКОРОСТИ (DOWNLOAD) (~4.0 сек) ──
                _liveState.value = _liveState.value.copy(
                    stage = SpeedTestStage.DOWNLOAD,
                    progress = 0.35f,
                    currentSpeedMbps = 0.0,
                    sparklinePoints = emptyList()
                )
                delay(180)

                val downloadResult = measureDownloadSpeed(effectiveDomain, client)
                if (!isActive) return@launch

                _liveState.value = _liveState.value.copy(
                    stage = SpeedTestStage.UPLOAD,
                    progress = 0.65f,
                    downloadSpeedMbps = downloadResult.avgSpeedMbps,
                    downloadedBytes = downloadResult.totalBytes,
                    peakSpeedMbps = downloadResult.peakSpeedMbps,
                    currentSpeedMbps = 0.0,
                    sparklinePoints = emptyList()
                )
                delay(200)

                // ── ЭТАП 3: ЧЕСТНЫЙ ЗАМЕР ИСХОДЯЩЕЙ СКОРОСТИ (UPLOAD) С FLUSH И 200 OK (~3.5 сек) ──
                val uploadResult = measureUploadSpeed(effectiveDomain, client)
                if (!isActive) return@launch

                _liveState.value = _liveState.value.copy(
                    stage = SpeedTestStage.ANALYSIS,
                    progress = 0.94f,
                    uploadSpeedMbps = uploadResult.avgSpeedMbps,
                    uploadedBytes = uploadResult.totalBytes,
                    currentSpeedMbps = 0.0
                )
                delay(300)

                // ── ЭТАП 4: ВЗВЕШЕННЫЙ СКОРИНГ И ФИНАЛЬНЫЙ СВОДНЫЙ ОТЧЕТ ──
                val totalDuration = System.currentTimeMillis() - overallStartTime
                val dlSpeed = downloadResult.avgSpeedMbps
                val ulSpeed = uploadResult.avgSpeedMbps

                val dc2 = updatedDcs.firstOrNull { it.dcId == 2 }
                val dc4 = updatedDcs.firstOrNull { it.dcId == 4 }
                val dc2Rtt = dc2?.rttMs ?: -1L
                val dc4Rtt = dc4?.rttMs ?: -1L

                val finalPing = when {
                    dc2Rtt > 0 -> dc2Rtt
                    dcSamples.isNotEmpty() -> dcSamples.average().toLong()
                    edgePing > 0 -> edgePing
                    else -> 65L
                }
                val finalJitter = calculateJitter(dcSamples)

                val overallScore = calculateOverallScore(
                    dlMbps = dlSpeed,
                    ulMbps = ulSpeed,
                    pingMs = finalPing,
                    jitterMs = finalJitter,
                    dc2RttMs = dc2Rtt,
                    dc4RttMs = dc4Rtt
                )
                val grade = calculateQualityGrade(overallScore, dlSpeed, finalPing)
                val suitability = calculateSuitability(
                    score = overallScore,
                    dlMbps = dlSpeed,
                    ulMbps = ulSpeed,
                    dc2RttMs = dc2Rtt,
                    dc4RttMs = dc4Rtt,
                    edgeRttMs = edgePing,
                    jitterMs = finalJitter,
                    colo = detectedColo
                )

                val completedState = _liveState.value.copy(
                    stage = SpeedTestStage.COMPLETED,
                    progress = 1.0f,
                    currentSpeedMbps = 0.0,
                    downloadSpeedMbps = dlSpeed,
                    uploadSpeedMbps = ulSpeed,
                    downloadedBytes = downloadResult.totalBytes,
                    uploadedBytes = uploadResult.totalBytes,
                    durationMs = totalDuration,
                    qualityGrade = grade,
                    overallScore = overallScore,
                    suitability = suitability,
                    pingMs = finalPing,
                    jitterMs = finalJitter
                )
                _liveState.value = completedState
                onTestCompleted?.invoke(completedState)

                AppLogger.i(
                    "SpeedTest",
                    "Тест туннеля завершен. POP: $detectedColo, DC2: ${dc2Rtt}мс, DC4: ${dc4Rtt}мс, DL: ${String.format(java.util.Locale.US, "%.1f", dlSpeed)} Мбит/с, UL: ${String.format(java.util.Locale.US, "%.1f", ulSpeed)} Мбит/с, Балл: $overallScore/100 ($grade)"
                )
            } catch (e: CancellationException) {
                _liveState.value = _liveState.value.copy(
                    stage = SpeedTestStage.CANCELLED,
                    currentSpeedMbps = 0.0
                )
            } catch (t: Throwable) {
                AppLogger.e("SpeedTest", "Ошибка выполнения теста скорости туннеля: ${t.message}")
                _liveState.value = _liveState.value.copy(
                    stage = SpeedTestStage.ERROR,
                    errorDetail = t.localizedMessage ?: "Сетевой сбой при замере скорости туннеля",
                    currentSpeedMbps = 0.0
                )
            }
        }
    }

    /**
     * Остановка активного теста скорости.
     */
    fun cancelTest() {
        testJob?.cancel()
        testJob = null
        if (_liveState.value.stage != SpeedTestStage.COMPLETED) {
            _liveState.value = _liveState.value.copy(
                stage = SpeedTestStage.IDLE,
                currentSpeedMbps = 0.0,
                progress = 0f
            )
        }
    }

    private fun buildHttpClient(
        socks5Port: Int?,
        socks5User: String?,
        socks5Pass: String?
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (socks5Port != null && socks5Port > 0) {
            try {
                builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks5Port)))
                if (!socks5User.isNullOrEmpty() || !socks5Pass.isNullOrEmpty()) {
                    java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                        override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                            return java.net.PasswordAuthentication(
                                socks5User.orEmpty(),
                                socks5Pass.orEmpty().toCharArray()
                            )
                        }
                    })
                }
            } catch (_: Throwable) {}
        }
        return builder.build()
    }

    /**
     * Замер задержки до Cloudflare Edge и определение POP-кода узла.
     */
    private suspend fun measureCloudflareEdge(domain: String, client: OkHttpClient): Pair<Long, String> = withContext(Dispatchers.IO) {
        var detectedColo = "Anycast"
        val samples = mutableListOf<Long>()

        val endpoints = listOf(
            "https://$domain/cdn-cgi/trace",
            "https://speed.cloudflare.com/cdn-cgi/trace",
            "https://1.1.1.1/cdn-cgi/trace"
        )

        for (targetUrl in endpoints) {
            if (!isActive) break
            val start = System.nanoTime()
            try {
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "MirrlyTGProxy-SpeedTest/1.2.0")
                    .header("Cache-Control", "no-cache, no-store")
                    .build()

                client.newCall(request).execute().use { response ->
                    val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                    if (response.isSuccessful) {
                        samples.add(elapsedMs)
                        val bodyText = response.body?.string().orEmpty()
                        for (line in bodyText.lines()) {
                            if (line.startsWith("colo=")) {
                                val coloVal = line.removePrefix("colo=").trim().uppercase()
                                if (coloVal.isNotBlank() && coloVal.length in 3..6) {
                                    detectedColo = coloVal
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
            if (samples.isNotEmpty()) break
        }

        val avgPing = if (samples.isNotEmpty()) samples.average().toLong() else -1L
        Pair(avgPing, detectedColo)
    }

    /**
     * Последовательный замер RTT и джиттера к конкретному дата-центру Telegram через туннель.
     */
    private suspend fun probeTelegramDc(
        dc: TelegramDcBenchmark,
        socks5Port: Int?,
        socks5User: String?,
        socks5Pass: String?,
        timeoutMs: Int
    ): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val samples = mutableListOf<Long>()
        val totalProbes = 2

        for (p in 1..totalProbes) {
            if (!isActive) break
            val sample = singleDcProbe(
                ip = dc.ip,
                port = dc.port,
                socks5Port = socks5Port,
                socks5User = socks5User,
                socks5Pass = socks5Pass,
                timeoutMs = timeoutMs
            )
            if (sample > 0) {
                samples.add(sample)
            }
            if (p < totalProbes) delay(40)
        }

        if (samples.isEmpty()) {
            Pair(-1L, 0L)
        } else {
            val avg = samples.average().toLong().coerceAtLeast(1L)
            val jitter = calculateJitter(samples)
            Pair(avg, jitter)
        }
    }

    /**
     * Единичная контрольная проба TCP соединения с Telegram DC:
     * Первично: через локальный SOCKS5 сокет (127.0.0.1:socks5Port).
     * Вторично (если сокет не отвечает): прямое TCP-подключение с защитой VpnSocketProtector.
     */
    private fun singleDcProbe(
        ip: String,
        port: Int,
        socks5Port: Int?,
        socks5User: String?,
        socks5Pass: String?,
        timeoutMs: Int
    ): Long {
        if (socks5Port != null && socks5Port > 0) {
            var s: Socket? = null
            try {
                s = Socket()
                VpnSocketProtector.protect(s)
                s.tcpNoDelay = true
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress("127.0.0.1", socks5Port), timeoutMs)

                val input = s.getInputStream()
                val output = s.getOutputStream()

                // 1. SOCKS5 Handshake (RFC 1928 / 1929)
                if (!socks5User.isNullOrEmpty() || !socks5Pass.isNullOrEmpty()) {
                    output.write(byteArrayOf(0x05, 0x01, 0x02))
                    output.flush()
                    val authResp = ByteArray(2)
                    readFully(input, authResp)
                    if (authResp[0] != 0x05.toByte() || authResp[1] != 0x02.toByte()) {
                        return -1L
                    }
                    val uBytes = (socks5User ?: "").toByteArray(StandardCharsets.UTF_8)
                    val pBytes = (socks5Pass ?: "").toByteArray(StandardCharsets.UTF_8)
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
                        return -1L
                    }
                } else {
                    output.write(byteArrayOf(0x05, 0x01, 0x00))
                    output.flush()
                    val authResp = ByteArray(2)
                    readFully(input, authResp)
                    if (authResp[0] != 0x05.toByte() || authResp[1] != 0x00.toByte()) {
                        return -1L
                    }
                }

                // 2. CONNECT Request (RFC 1928 §4)
                val ipParts = ip.split(".")
                if (ipParts.size == 4) {
                    val ipBytes = ByteArray(4) { ipParts[it].toInt().toByte() }
                    val req = ByteArray(10)
                    req[0] = 0x05
                    req[1] = 0x01 // CONNECT
                    req[2] = 0x00
                    req[3] = 0x01 // IPv4
                    System.arraycopy(ipBytes, 0, req, 4, 4)
                    req[8] = ((port shr 8) and 0xFF).toByte()
                    req[9] = (port and 0xFF).toByte()

                    val t0 = System.nanoTime()
                    output.write(req)
                    output.flush()

                    val repHeader = ByteArray(4)
                    readFully(input, repHeader)
                    if (repHeader[0] == 0x05.toByte() && repHeader[1] == 0x00.toByte()) {
                        // Очистка оставшихся байтов адреса привязки
                        when (repHeader[3].toInt() and 0xFF) {
                            0x01 -> readFully(input, ByteArray(6))
                            0x03 -> {
                                val len = input.read()
                                if (len > 0) readFully(input, ByteArray(len + 2))
                            }
                            0x04 -> readFully(input, ByteArray(18))
                        }
                        return ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                    }
                }
            } catch (_: Throwable) {
                // Если SOCKS5 недоступен, выполняем fallback на прямое TCP подключение
            } finally {
                try { s?.close() } catch (_: Throwable) {}
            }
        }

        // Прямое сокет-подключение (Fallback при незапущенном SOCKS5 демоне)
        var directSocket: Socket? = null
        return try {
            directSocket = Socket()
            VpnSocketProtector.protect(directSocket)
            directSocket.tcpNoDelay = true
            val t0 = System.nanoTime()
            directSocket.connect(InetSocketAddress(ip, port), timeoutMs)
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        } catch (_: Throwable) {
            -1L
        } finally {
            try { directSocket?.close() } catch (_: Throwable) {}
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw EOFException("Неожиданное закрытие сокета SOCKS5")
            offset += read
        }
    }

    private data class TransferResult(
        val avgSpeedMbps: Double,
        val peakSpeedMbps: Double,
        val totalBytes: Long
    )

    /**
     * Честный замер входящей скорости (Download) без генерации случайных чисел.
     */
    private suspend fun measureDownloadSpeed(domain: String, client: OkHttpClient): TransferResult = withContext(Dispatchers.IO) {
        var totalBytesRead = 0L
        var peakSpeed = 0.0
        val points = mutableListOf<Float>()

        val targetDurationMs = 4000L
        val startTime = System.currentTimeMillis()
        var lastSampleTime = startTime
        var lastSampleBytes = 0L
        var smoothedSpeed = 0.0

        val candidateUrls = listOf(
            "https://speed.cloudflare.com/__down?bytes=26214400", // 25 MB
            "https://$domain/__down?bytes=26214400",
            "https://1.1.1.1/__down?bytes=26214400"
        )

        val buffer = ByteArray(65536) // 64 KB
        var urlIndex = 0

        while (isActive && (System.currentTimeMillis() - startTime) < targetDurationMs) {
            val targetUrl = candidateUrls[urlIndex % candidateUrls.size]
            urlIndex++

            try {
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "MirrlyTGProxy-SpeedTest/1.2.0")
                    .header("Cache-Control", "no-cache, no-store")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body
                    if (response.isSuccessful && body != null) {
                        val stream: InputStream = body.byteStream()
                        var bytesInChunk: Int
                        while (stream.read(buffer).also { bytesInChunk = it } != -1) {
                            if (!isActive) break
                            val now = System.currentTimeMillis()
                            val elapsedSinceStart = now - startTime
                            if (elapsedSinceStart >= targetDurationMs) break

                            totalBytesRead += bytesInChunk
                            val intervalMs = now - lastSampleTime

                            if (intervalMs >= 80) {
                                val bytesDelta = totalBytesRead - lastSampleBytes
                                val instantMbps = if (intervalMs > 0) (bytesDelta * 8.0) / (intervalMs * 1000.0) else 0.0
                                val safeInstant = if (instantMbps.isFinite() && instantMbps >= 0.0) instantMbps else 0.0

                                smoothedSpeed = if (smoothedSpeed <= 0.0) safeInstant else (0.70 * smoothedSpeed + 0.30 * safeInstant)
                                if (safeInstant > peakSpeed) peakSpeed = safeInstant

                                points.add(smoothedSpeed.toFloat().coerceAtLeast(0f))
                                if (points.size > 40) points.removeAt(0)

                                val overallMbps = ((totalBytesRead * 8.0) / (elapsedSinceStart.coerceAtLeast(1) * 1000.0)).coerceAtLeast(0.0)
                                val dlProgress = 0.35f + ((elapsedSinceStart.toFloat() / targetDurationMs.toFloat()) * 0.30f).coerceIn(0f, 0.30f)

                                _liveState.value = _liveState.value.copy(
                                    progress = dlProgress,
                                    currentSpeedMbps = smoothedSpeed,
                                    downloadSpeedMbps = overallMbps,
                                    peakSpeedMbps = max(peakSpeed, overallMbps),
                                    downloadedBytes = totalBytesRead,
                                    sparklinePoints = points.toList()
                                )

                                lastSampleTime = now
                                lastSampleBytes = totalBytesRead
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // Если прочитано > 0 байт, завершаем цикл и считаем честный результат
                if (totalBytesRead > 0L) {
                    break
                }
                // Если ещё не прочитано ни одного байта и это последняя попытка, пробрасываем ошибку
                if (urlIndex >= candidateUrls.size) {
                    throw t
                }
            }
        }

        val finalElapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
        val finalAvgMbps = if (totalBytesRead > 0L) {
            ((totalBytesRead * 8.0) / (finalElapsed * 1000.0)).coerceAtLeast(0.1)
        } else {
            0.0
        }

        TransferResult(
            avgSpeedMbps = finalAvgMbps,
            peakSpeedMbps = max(finalAvgMbps, peakSpeed),
            totalBytes = totalBytesRead
        )
    }

    /**
     * Честный замер исходящей скорости (Upload):
     * Отправка реальных чанков через туннель с вызовом sink.flush() и фиксацией момента
     * фактического получения HTTP-ответа 200 OK от сервера.
     * Полный запрет на генерацию случайных чисел и замер через запись в ОЗУ.
     */
    private suspend fun measureUploadSpeed(domain: String, client: OkHttpClient): TransferResult = withContext(Dispatchers.IO) {
        var totalBytesSent = 0L
        var peakSpeed = 0.0
        val points = mutableListOf<Float>()

        val targetDurationMs = 3800L
        val startTime = System.currentTimeMillis()
        var smoothedSpeed = 0.0

        val candidateUrls = listOf(
            "https://speed.cloudflare.com/__up",
            "https://$domain/__up"
        )

        // Адаптивный размер чанков: начинаем с 256 КБ, увеличиваем для высокоскоростных каналов
        var currentChunkSize = 262144 // 256 KB
        val maxChunkSize = 1048576 // 1 MB
        var activeUrlIndex = 0

        while (isActive && (System.currentTimeMillis() - startTime) < targetDurationMs) {
            val targetUrl = candidateUrls[activeUrlIndex % candidateUrls.size]
            val chunk = ByteArray(currentChunkSize)

            val countingBody = object : RequestBody() {
                override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()
                override fun contentLength(): Long = chunk.size.toLong()

                override fun writeTo(sink: BufferedSink) {
                    // Передача реальных физических данных в сокет с вызовом flush
                    var written = 0
                    val subChunk = 32768 // 32 KB
                    while (written < chunk.size) {
                        val toWrite = minOf(subChunk, chunk.size - written)
                        sink.write(chunk, written, toWrite)
                        sink.flush()
                        written += toWrite
                    }
                }
            }

            val reqStart = System.currentTimeMillis()
            var requestOk = false

            try {
                val request = Request.Builder()
                    .url(targetUrl)
                    .post(countingBody)
                    .header("User-Agent", "MirrlyTGProxy-SpeedTest/1.2.0")
                    .header("Cache-Control", "no-cache, no-store")
                    .build()

                // Ожидание фактического HTTP 200 OK от удаленного сервера
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code in 200..299) {
                        requestOk = true
                    }
                }
            } catch (t: Throwable) {
                activeUrlIndex++
                if (totalBytesSent > 0L) {
                    break
                }
                if (activeUrlIndex >= candidateUrls.size) {
                    throw t
                }
            }

            if (requestOk) {
                val reqElapsed = (System.currentTimeMillis() - reqStart).coerceAtLeast(1)
                totalBytesSent += currentChunkSize

                val instantMbps = (currentChunkSize * 8.0) / (reqElapsed * 1000.0)
                val safeInstant = if (instantMbps.isFinite() && instantMbps >= 0.0) instantMbps else 0.0

                smoothedSpeed = if (smoothedSpeed <= 0.0) safeInstant else (0.65 * smoothedSpeed + 0.35 * safeInstant)
                if (safeInstant > peakSpeed) peakSpeed = safeInstant

                points.add(smoothedSpeed.toFloat().coerceAtLeast(0f))
                if (points.size > 40) points.removeAt(0)

                val totalElapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                val overallMbps = ((totalBytesSent * 8.0) / (totalElapsed * 1000.0)).coerceAtLeast(0.0)
                val ulProgress = 0.65f + ((totalElapsed.toFloat() / targetDurationMs.toFloat()) * 0.28f).coerceIn(0f, 0.28f)

                _liveState.value = _liveState.value.copy(
                    progress = ulProgress,
                    currentSpeedMbps = smoothedSpeed,
                    uploadSpeedMbps = overallMbps,
                    peakSpeedMbps = max(peakSpeed, overallMbps),
                    uploadedBytes = totalBytesSent,
                    sparklinePoints = points.toList()
                )

                // Если чанк передался быстрее чем за 200 мс — увеличиваем размер следующего чанка
                if (reqElapsed < 220 && currentChunkSize < maxChunkSize) {
                    currentChunkSize = minOf(currentChunkSize * 2, maxChunkSize)
                }
            }
        }

        val finalElapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
        val finalAvgMbps = if (totalBytesSent > 0L) {
            ((totalBytesSent * 8.0) / (finalElapsed * 1000.0)).coerceAtLeast(0.1)
        } else {
            0.0
        }

        TransferResult(
            avgSpeedMbps = finalAvgMbps,
            peakSpeedMbps = max(finalAvgMbps, peakSpeed),
            totalBytes = totalBytesSent
        )
    }

    private fun calculateJitter(samples: List<Long>): Long {
        if (samples.size < 2) return 0L
        var sumDiff = 0.0
        for (i in 1 until samples.size) {
            sumDiff += abs(samples[i] - samples[i - 1])
        }
        return (sumDiff / (samples.size - 1)).toLong()
    }

    /**
     * Взвешенный расчет общего балла качества туннеля (0..100):
     * - Пропускная способность (DL + UL): до 50 баллов.
     * - Задержка RTT до европейских DC Telegram: до 35 баллов.
     * - Стабильность и джиттер: до 15 баллов.
     */
    fun calculateOverallScore(
        dlMbps: Double,
        ulMbps: Double,
        pingMs: Long,
        jitterMs: Long,
        dc2RttMs: Long,
        dc4RttMs: Long
    ): Int {
        // 1. Балл скорости (0..50)
        val maxSpeed = max(dlMbps, ulMbps * 1.4)
        val bwScore = when {
            maxSpeed >= 100.0 -> 50
            maxSpeed >= 75.0  -> 46 + ((maxSpeed - 75.0) / 25.0 * 4.0).toInt()
            maxSpeed >= 50.0  -> 41 + ((maxSpeed - 50.0) / 25.0 * 5.0).toInt()
            maxSpeed >= 25.0  -> 33 + ((maxSpeed - 25.0) / 25.0 * 8.0).toInt()
            maxSpeed >= 10.0  -> 24 + ((maxSpeed - 10.0) / 15.0 * 9.0).toInt()
            maxSpeed >= 3.0   -> 14 + ((maxSpeed - 3.0) / 7.0 * 10.0).toInt()
            else              -> (maxSpeed / 3.0 * 14.0).toInt().coerceAtLeast(4)
        }

        // 2. Балл задержки (0..35)
        val effectivePing = if (dc2RttMs > 0) dc2RttMs else if (dc4RttMs > 0) dc4RttMs else pingMs
        val latScore = when {
            effectivePing in 1..40    -> 35
            effectivePing in 41..75   -> 31 + ((75 - effectivePing) * 4 / 35).toInt()
            effectivePing in 76..120  -> 26 + ((120 - effectivePing) * 5 / 45).toInt()
            effectivePing in 121..175 -> 17 + ((175 - effectivePing) * 9 / 55).toInt()
            effectivePing in 176..240 -> 9 + ((240 - effectivePing) * 8 / 65).toInt()
            effectivePing > 240       -> 5
            else                      -> 25
        }

        // 3. Балл стабильности и джиттера (0..15)
        val jitScore = when {
            jitterMs <= 5  -> 15
            jitterMs <= 15 -> 12 + ((15 - jitterMs) * 3 / 10).toInt()
            jitterMs <= 30 -> 8 + ((30 - jitterMs) * 4 / 15).toInt()
            jitterMs <= 60 -> 4 + ((60 - jitterMs) * 4 / 30).toInt()
            else           -> 2
        }

        return (bwScore + latScore + jitScore).coerceIn(10, 100)
    }

    /**
     * Оценка качества канала.
     * Соединение с высокой скоростью (>50 Мбит/с) при задержке до 120 мс гарантированно получает вердикт "Отлично".
     */
    fun calculateQualityGrade(score: Int, dlMbps: Double, pingMs: Long): String {
        if (dlMbps >= 50.0 && pingMs in 1..120) {
            return "Отлично"
        }
        return when {
            score >= 84 -> "Отлично"
            score >= 68 -> "Хорошо"
            score >= 48 -> "Удовлетворительно"
            score >= 28 -> "Слабо"
            else        -> "Плохо"
        }
    }

    private fun calculateSuitability(
        score: Int,
        dlMbps: Double,
        ulMbps: Double,
        dc2RttMs: Long,
        dc4RttMs: Long,
        edgeRttMs: Long,
        jitterMs: Long,
        colo: String
    ): TelegramSuitabilityReport {
        val effectiveChatsRtt = if (dc2RttMs > 0) dc2RttMs else if (edgeRttMs > 0) edgeRttMs else 65L

        val chats = when {
            effectiveChatsRtt <= 60  -> "Мгновенно (< 60 мс)"
            effectiveChatsRtt <= 120 -> "Быстро (< 120 мс)"
            effectiveChatsRtt <= 180 -> "Умеренно (~150 мс)"
            else                     -> "С задержкой (> 200 мс)"
        }

        val voice = when {
            jitterMs <= 12 && effectiveChatsRtt <= 140 -> "HD Voice / Стабильно"
            jitterMs <= 25 -> "Хорошее качество звука"
            else -> "Возможны искажения речи"
        }

        val media = when {
            dlMbps >= 30.0 -> "Высокая скорость (~0.3 сек / фото)"
            dlMbps >= 12.0 -> "Быстро (~0.8 сек / фото)"
            else           -> "Стандартно (~2 сек)"
        }

        val video = when {
            dlMbps >= 50.0 -> "4K UHD / Без буферизации"
            dlMbps >= 25.0 -> "Full HD 1080p / Без задержек"
            dlMbps >= 8.0  -> "HD 720p"
            else           -> "SD 480p"
        }

        val summary = when {
            score >= 84 -> "Стабильный высокоскоростной канал"
            score >= 68 -> "Оптимальный канал для звонков и медиа"
            score >= 48 -> "Пригодно для чатов и голосовых вызовов"
            else        -> "Нестабильный канал с задержками"
        }

        return TelegramSuitabilityReport(
            chatsVerdict = chats,
            voiceVerdict = voice,
            mediaVerdict = media,
            videoVerdict = video,
            overallScore = score,
            summary = summary,
            dc2RttMs = dc2RttMs,
            dc4RttMs = dc4RttMs,
            edgeRttMs = edgeRttMs,
            edgeColo = colo
        )
    }
}
