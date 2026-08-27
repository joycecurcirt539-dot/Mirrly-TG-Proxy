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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Описание дата-центра Telegram (MTProto DC).
 */
data class TelegramDcInfo(
    val dcId: Int,
    val name: String,
    val location: String,
    val role: String,
    val defaultIp: String
)

/**
 * Метрики активности конкретного дата-центра Telegram.
 */
data class DcMetrics(
    val dcId: Int,
    val info: TelegramDcInfo,
    val bytesReceived: Long,
    val bytesSent: Long,
    val activeConnections: Int,
    val lastActivityTimestamp: Long,
    val weight: Double,
    val allocatedSockets: Int
) {
    val totalBytes: Long get() = bytesReceived + bytesSent
}

/**
 * Результат расчета распределения сокетов по дата-центрам.
 */
data class DcAffinityDistribution(
    val primaryDcId: Int?,
    val activeDcCount: Int,
    val socketDistribution: Map<Int, Int>, // DC ID -> allocated socket count
    val summary: String
)

/**
 * Срез комплексной аналитики MTProto трафика и CDN.
 */
data class MtprotoAnalyticsSnapshot(
    val dcMetrics: List<DcMetrics>,
    val primaryDcName: String,
    val totalRxBytes: Long,
    val totalTxBytes: Long,
    val totalBytes: Long,
    val cdnBytes: Long,
    val cdnPercentage: Float,
    val activeCdnNodes: Int,
    val savedDc4Bytes: Long,
    val intermediatePackets: Long,
    val paddedPackets: Long,
    val abridgedPackets: Long,
    val totalPackets: Long,
    val multiplexingRatio: Double,
    val dcAffinityEntropy: Double
)

/**
 * Движок поведенческого анализа и адаптивного распределения пула сокетов по дата-центрам Telegram (DC-Affinity).
 *
 * Оптимизирует расход батареи и памяти, динамически концентрируя ресурсы WSS-пула
 * на активных дата-центрах (DC2 — чаты/авторизация, DC4 — медиафайлы/видео) и переводя
 * неактивные DC в режим энергосбережения.
 */
class TelegramDCAffinityEngine {

    companion object {
        private const val TAG = "TelegramDCAffinity"
        private const val DECAY_HALF_LIFE_SECONDS = 60.0 // Время полураспада веса неактивного DC (60с)

        val KNOWN_DCS = mapOf(
            1 to TelegramDcInfo(1, "DC 1", "Майами (США)", "Америка (Чат/Сообщения)", "149.154.175.50"),
            2 to TelegramDcInfo(2, "DC 2", "Амстердам (Нидерланды)", "Европа/СНГ (Чат/Авторизация)", "149.154.167.51"),
            3 to TelegramDcInfo(3, "DC 3", "Майами (США)", "Америка (Медиа/Файлы)", "149.154.175.100"),
            4 to TelegramDcInfo(4, "DC 4", "Амстердам (Нидерланды)", "Европа/СНГ (Медиа/Файлы)", "149.154.167.91"),
            5 to TelegramDcInfo(5, "DC 5", "Сингапур", "Азия (Чат/Медиа)", "91.108.56.130"),
            100 to TelegramDcInfo(100, "CDN FlowSeal", "Распределенная сеть (20 узлов)", "Кэширование публичных медиа", "149.154.167.91"),
            203 to TelegramDcInfo(203, "DC 203", "Майами (США)", "Тестовый сервер", "91.105.192.100")
        )

        fun getDcInfo(dcId: Int): TelegramDcInfo {
            return KNOWN_DCS[dcId] ?: TelegramDcInfo(
                dcId = dcId,
                name = "DC $dcId",
                location = "Telegram Edge",
                role = "Резервный дата-центр",
                defaultIp = "149.154.167.51"
            )
        }
    }

    private class DcRecord(val dcId: Int) {
        val bytesReceived = AtomicLong(0L)
        val bytesSent = AtomicLong(0L)
        val activeConnections = AtomicInteger(0)
        val lastActivityTimestamp = AtomicLong(0L)
    }

    private val records = ConcurrentHashMap<Int, DcRecord>()
    private val intermediatePackets = AtomicLong(0L)
    private val paddedPackets = AtomicLong(0L)
    private val abridgedPackets = AtomicLong(0L)
    private val totalPackets = AtomicLong(0L)

    init {
        // Инициализируем записи для основных DC и CDN
        for (id in listOf(2, 4, 100, 1, 3, 5)) {
            records[id] = DcRecord(id)
        }
    }

    /**
     * Учет объема трафика для дата-центра.
     */
    fun recordTraffic(dcId: Int, rxBytes: Long, txBytes: Long) {
        if (dcId <= 0) return
        val rec = records.getOrPut(dcId) { DcRecord(dcId) }
        if (rxBytes > 0) rec.bytesReceived.addAndGet(rxBytes)
        if (txBytes > 0) rec.bytesSent.addAndGet(txBytes)
        rec.lastActivityTimestamp.set(System.currentTimeMillis())
    }

    /**
     * Учет изменения числа активных соединений с дата-центром.
     */
    fun recordConnectionDelta(dcId: Int, delta: Int) {
        if (dcId <= 0) return
        val rec = records.getOrPut(dcId) { DcRecord(dcId) }
        val current = rec.activeConnections.addAndGet(delta)
        if (current < 0) rec.activeConnections.set(0)
        rec.lastActivityTimestamp.set(System.currentTimeMillis())
    }

    /**
     * Установка точного количества активных соединений с дата-центром.
     */
    fun setActiveConnections(dcId: Int, count: Int) {
        if (dcId <= 0) return
        val rec = records.getOrPut(dcId) { DcRecord(dcId) }
        rec.activeConnections.set(count.coerceAtLeast(0))
        if (count > 0) {
            rec.lastActivityTimestamp.set(System.currentTimeMillis())
        }
    }

    /**
     * Расчет динамического распределения емкости сокет-пула.
     */
    fun calculateSocketDistribution(totalPoolSize: Int): DcAffinityDistribution {
        val poolBudget = totalPoolSize.coerceIn(2, 32)
        val now = System.currentTimeMillis()

        // 1. Вычисляем вес каждого DC с учетом объема трафика, активных сокетов и затухания
        val weights = mutableMapOf<Int, Double>()
        var totalWeight = 0.0

        for ((id, rec) in records) {
            val totalBytes = rec.bytesReceived.get() + rec.bytesSent.get()
            val activeConns = rec.activeConnections.get().coerceAtLeast(0)
            val lastActive = rec.lastActivityTimestamp.get()

            if (totalBytes <= 0L && activeConns <= 0) {
                // Базовый дефолтный вес для основных европейских DC2/DC4
                val baseDefault = when (id) {
                    2 -> 3.0 // DC2 (Сообщения)
                    4 -> 2.0 // DC4 (Медиа)
                    else -> 0.5
                }
                weights[id] = baseDefault
                totalWeight += baseDefault
                continue
            }

            // Коэффициент свежести (Exponential Decay): затухает каждые 60 секунд неактивности
            val idleSeconds = if (lastActive > 0L) (now - lastActive) / 1000.0 else 300.0
            val decayFactor = exp(-idleSeconds / DECAY_HALF_LIFE_SECONDS).coerceIn(0.01, 1.0)

            // Формула веса: (sqrt(bytes) / 100 + active_connections * 20) * decay
            val trafficScore = kotlin.math.sqrt(totalBytes.toDouble()) / 100.0
            val connScore = activeConns * 25.0
            val weight = (trafficScore + connScore + 1.0) * decayFactor

            weights[id] = weight
            totalWeight += weight
        }

        // 2. Сортируем DC по убыванию веса
        val sortedDcs = weights.entries.sortedByDescending { it.value }
        val primaryDcId = sortedDcs.firstOrNull()?.key

        // 3. Распределяем сокеты пропорционально
        val allocation = mutableMapOf<Int, Int>()
        var remainingSockets = poolBudget

        // Минимальное гарантированное резервирование: топ-1 и топ-2 получают сокеты
        for ((id, w) in sortedDcs) {
            if (remainingSockets <= 0) {
                allocation[id] = 0
                continue
            }

            val share = if (totalWeight > 0) (w / totalWeight) * poolBudget else 1.0
            val rounded = share.roundToInt().coerceIn(1, remainingSockets)
            allocation[id] = rounded
            remainingSockets -= rounded
        }

        // Если остались нераспределенные сокеты из-за округления, отдаем их доминантному DC
        if (remainingSockets > 0 && primaryDcId != null) {
            allocation[primaryDcId] = (allocation[primaryDcId] ?: 0) + remainingSockets
        }

        val activeCount = allocation.values.count { it > 0 }
        val primaryName = if (primaryDcId != null) getDcInfo(primaryDcId).name else "Авто"
        val summary = "Доминантный DC: $primaryName | Активных DC: $activeCount | Пул: $poolBudget"

        return DcAffinityDistribution(
            primaryDcId = primaryDcId,
            activeDcCount = activeCount,
            socketDistribution = allocation,
            summary = summary
        )
    }

    /**
     * Получение сводного списка телеметрических метрик по всем DC.
     */
    fun getDcMetrics(totalPoolSize: Int = 4): List<DcMetrics> {
        val distribution = calculateSocketDistribution(totalPoolSize)
        val now = System.currentTimeMillis()

        return records.map { (id, rec) ->
            val totalBytes = rec.bytesReceived.get() + rec.bytesSent.get()
            val lastActive = rec.lastActivityTimestamp.get()
            val idleSec = if (lastActive > 0L) (now - lastActive) / 1000.0 else 300.0
            val decay = exp(-idleSec / DECAY_HALF_LIFE_SECONDS).coerceIn(0.01, 1.0)
            val weight = (kotlin.math.sqrt(totalBytes.toDouble()) / 100.0 + rec.activeConnections.get() * 25.0 + 1.0) * decay

            DcMetrics(
                dcId = id,
                info = getDcInfo(id),
                bytesReceived = rec.bytesReceived.get(),
                bytesSent = rec.bytesSent.get(),
                activeConnections = rec.activeConnections.get(),
                lastActivityTimestamp = lastActive,
                weight = (weight * 10.0).roundToInt() / 10.0,
                allocatedSockets = distribution.socketDistribution[id] ?: 0
            )
        }.sortedByDescending { it.allocatedSockets }
    }

    fun recordTransportDialect(dialect: String, count: Long = 1) {
        if (count <= 0) return
        totalPackets.addAndGet(count)
        when (dialect.lowercase()) {
            "padded", "0xdd", "dd" -> paddedPackets.addAndGet(count)
            "abridged", "0xef", "ef" -> abridgedPackets.addAndGet(count)
            else -> intermediatePackets.addAndGet(count)
        }
    }

    fun recordPacket(dcId: Int, rxBytes: Long, txBytes: Long, dialect: String? = null) {
        recordTraffic(dcId, rxBytes, txBytes)
        if (dialect != null) {
            recordTransportDialect(dialect, 1)
        } else {
            totalPackets.incrementAndGet()
            intermediatePackets.incrementAndGet()
        }
    }

    /**
     * Получение сводного снимка аналитики MTProto трафика и CDN.
     */
    fun getMtprotoSnapshot(totalPoolSize: Int = 4, totalWsConnections: Long = 1): MtprotoAnalyticsSnapshot {
        val dcMetrics = getDcMetrics(totalPoolSize)
        val distribution = calculateSocketDistribution(totalPoolSize)
        val primaryName = if (distribution.primaryDcId != null) getDcInfo(distribution.primaryDcId).name else "DC 2"

        var totalRx = 0L
        var totalTx = 0L
        var cdnTotal = 0L

        for (m in dcMetrics) {
            totalRx += m.bytesReceived
            totalTx += m.bytesSent
            if (m.dcId == 100) {
                cdnTotal += m.totalBytes
            }
        }

        val grandTotal = totalRx + totalTx
        val cdnPct = if (grandTotal > 0) (cdnTotal.toFloat() / grandTotal.toFloat()) * 100f else 0f
        val savedDc4 = cdnTotal

        val interm = intermediatePackets.get().coerceAtLeast(0)
        val padded = paddedPackets.get().coerceAtLeast(0)
        val abridged = abridgedPackets.get().coerceAtLeast(0)
        var totalPkts = totalPackets.get().coerceAtLeast(0)

        if (totalPkts == 0L && grandTotal > 0L) {
            totalPkts = (grandTotal / 1200L).coerceAtLeast(1L)
        }

        val wsCount = totalWsConnections.coerceAtLeast(1L)
        val multiplexing = if (totalPkts > 0) (totalPkts.toDouble() / wsCount.toDouble()) else 0.0

        val allocated = distribution.socketDistribution.values.filter { it > 0 }
        val entropy = if (allocated.isNotEmpty()) {
            val totalAlloc = allocated.sum().toDouble()
            allocated.sumOf { s ->
                val p = s / totalAlloc
                if (p > 0) -p * kotlin.math.ln(p) else 0.0
            }
        } else 0.0

        return MtprotoAnalyticsSnapshot(
            dcMetrics = dcMetrics,
            primaryDcName = primaryName,
            totalRxBytes = totalRx,
            totalTxBytes = totalTx,
            totalBytes = grandTotal,
            cdnBytes = cdnTotal,
            cdnPercentage = (cdnPct * 10.0f).roundToInt() / 10.0f,
            activeCdnNodes = if (cdnTotal > 0) 20 else 0,
            savedDc4Bytes = savedDc4,
            intermediatePackets = interm,
            paddedPackets = padded,
            abridgedPackets = abridged,
            totalPackets = totalPkts,
            multiplexingRatio = (multiplexing * 10.0).roundToInt() / 10.0,
            dcAffinityEntropy = (entropy * 100.0).roundToInt() / 100.0
        )
    }

    fun reset() {
        records.clear()
        for (id in listOf(2, 4, 100, 1, 3, 5)) {
            records[id] = DcRecord(id)
        }
        intermediatePackets.set(0L)
        paddedPackets.set(0L)
        abridgedPackets.set(0L)
        totalPackets.set(0L)
        AppLogger.i(TAG, "Статистика DC-Affinity и MTProto сброшена")
    }
}
