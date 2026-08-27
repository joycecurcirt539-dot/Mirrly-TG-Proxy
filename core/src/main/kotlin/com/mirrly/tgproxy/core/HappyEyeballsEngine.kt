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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Результат выполнения алгоритма Happy Eyeballs v2 (RFC 8305).
 */
data class HappyEyeballsResult(
    val winningAddress: InetAddress,
    val handshakeRttMs: Long,
    val attemptIndex: Int,
    val totalCandidates: Int
)

/**
 * Движок ступенчатого параллельного подключения Happy Eyeballs v2 (RFC 8305 / RFC 6555).
 *
 * Алгоритм:
 * 1. Сортирует пул Anycast IP Cloudflare с учетом локального рейтинга исторической задержки.
 * 2. Запускает TCP Handshake к первому IP.
 * 3. Если ответ не получен в течение Connection Attempt Delay (200 мс), запускает параллельное
 *    рукопожатие со следующим IP, не дожидаясь таймаута первого.
 * 4. Первый успешный IP становится победителем, остальные попытки немедленно отменяются.
 * 5. Обновляет рейтинг доступности IP (IP Fast-Path) с помощью фильтра EWMA.
 */
object HappyEyeballsEngine {
    private const val TAG = "HappyEyeballs"
    const val DEFAULT_ATTEMPT_DELAY_MS = 200L // RFC 8305 рекомендованный интервал 100-250 мс
    const val DEFAULT_CONNECT_TIMEOUT_MS = 2500L

    private val ipRttRatings = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var lastLoggedWinningIp: String? = null

    /**
     * Ступенчатый конкурентный опрос пула IP-адресов.
     */
    suspend fun raceConnect(
        addresses: List<InetAddress>,
        port: Int = 443,
        attemptDelayMs: Long = DEFAULT_ATTEMPT_DELAY_MS,
        timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS
    ): HappyEyeballsResult? = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) return@withContext null

        val prioritized = prioritizeAddresses(addresses)
        if (prioritized.size == 1) {
            val single = prioritized[0]
            val singleRtt = probeDirect(single, port, timeoutMs)
            return@withContext if (singleRtt != null) {
                recordIpRtt(single.hostAddress ?: "", singleRtt)
                HappyEyeballsResult(single, singleRtt, 0, 1)
            } else {
                null
            }
        }

        withTimeoutOrNull(timeoutMs + (prioritized.size * attemptDelayMs)) {
            val deferred = CompletableDeferred<HappyEyeballsResult>()
            val raceJob = Job()
            val failuresCount = AtomicInteger(0)
            val total = prioritized.size

            for (index in prioritized.indices) {
                val candidate = prioritized[index]
                scope.launch(raceJob) {
                    if (index > 0) {
                        delay(index * attemptDelayMs)
                    }

                    if (!isActive || deferred.isCompleted) return@launch

                    val rtt = probeDirect(candidate, port, timeoutMs)
                    if (rtt != null) {
                        val result = HappyEyeballsResult(
                            winningAddress = candidate,
                            handshakeRttMs = rtt,
                            attemptIndex = index,
                            totalCandidates = total
                        )
                        if (deferred.complete(result)) {
                            val candIp = candidate.hostAddress ?: ""
                            recordIpRtt(candIp, rtt)
                            if (lastLoggedWinningIp != candIp) {
                                lastLoggedWinningIp = candIp
                                AppLogger.i(
                                    TAG,
                                    "Happy Eyeballs v2 выбрал оптимальный IP $candIp (RTT: ${rtt}мс, попытка #$index из $total)"
                                )
                            }
                            raceJob.cancelChildren()
                        }
                    } else {
                        if (failuresCount.incrementAndGet() >= total) {
                            deferred.completeExceptionally(NoSuchElementException("Все Anycast IP недоступны"))
                        }
                    }
                }
            }

            try {
                deferred.await()
            } catch (_: Exception) {
                null
            } finally {
                raceJob.cancel()
            }
        }
    }

    /**
     * Прямая проверка TCP-соединения к указанному IP и порту.
     */
    private fun probeDirect(address: InetAddress, port: Int, timeoutMs: Long): Long? {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs.toInt()
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(address, port), timeoutMs.toInt())
            val elapsed = System.currentTimeMillis() - start
            elapsed.coerceAtLeast(1L)
        } catch (_: Exception) {
            null
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Сортировка пула IP-адресов на основе исторического рейтинга задержки (IP Fast-Path).
     */
    fun prioritizeAddresses(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.size <= 1) return addresses

        return addresses.sortedWith(Comparator { a, b ->
            val ipA = a.hostAddress ?: ""
            val ipB = b.hostAddress ?: ""
            val rttA = ipRttRatings[ipA] ?: 9999L
            val rttB = ipRttRatings[ipB] ?: 9999L
            rttA.compareTo(rttB)
        })
    }

    /**
     * Обновление рейтинга RTT для IP-адреса по формуле EWMA.
     */
    fun recordIpRtt(ip: String, newRtt: Long) {
        if (ip.isBlank()) return
        val current = ipRttRatings[ip]
        if (current == null) {
            ipRttRatings[ip] = newRtt
        } else {
            // 70% старого значения + 30% нового
            val smoothed = ((current * 0.7) + (newRtt * 0.3)).toLong().coerceAtLeast(1L)
            ipRttRatings[ip] = smoothed
        }
    }

    fun getKnownRatings(): Map<String, Long> = ipRttRatings.toMap()

    fun clearRating() {
        ipRttRatings.clear()
        lastLoggedWinningIp = null
        AppLogger.i(TAG, "Рейтинг Anycast IP очищен")
    }
}
