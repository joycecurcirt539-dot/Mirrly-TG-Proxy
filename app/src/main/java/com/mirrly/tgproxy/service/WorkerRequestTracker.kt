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

package com.mirrly.tgproxy.service

import android.content.Context
import android.content.SharedPreferences
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.core.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Временные периоды для фильтрации аналитики запросов.
 */
enum class AnalyticsPeriod(val label: String, val hoursSpan: Int) {
    SESSION("Сессия", -1),
    HOUR_1("1 час", 1),
    HOUR_5("5 часов", 5),
    HOUR_12("12 часов", 12),
    HOUR_24("24 часа", 24),
    DAYS_7("7 дней", 24 * 7),
    DAYS_30("30 дней", 24 * 30),
    ALL_TIME("Всего", -2)
}

/**
 * Точка данных для отрисовки кривой на интерактивном графике Безье.
 */
data class ChartDataPoint(
    val timestampMs: Long,
    val timeLabel: String,
    val totalCount: Int,
    val wssCount: Int,
    val probeCount: Int
)

/**
 * Сводная аналитика за выбранный временной интервал.
 */
data class PeriodAnalyticsSummary(
    val period: AnalyticsPeriod,
    val totalRequests: Int,
    val wssRequests: Int,
    val probeRequests: Int,
    val chartPoints: List<ChartDataPoint>,
    val burnRatePerHour: Double,
    val dailyQuotaPercentage: Float,
    val hoursUntilReset: Int,
    val minutesUntilReset: Int
)

/**
 * Локальный трекер и аналитический менеджер запросов к Cloudflare Worker.
 *
 * Ключевые архитектурные принципы:
 * 1. Zero Network Cost: Не выполняет никаких внешних HTTP/API запросов, не расходует квоту Cloudflare.
 * 2. Точность: Считает факты WSS-хэндшейков туннелей и служебных проверок из ядра прокси.
 * 3. Локальное персистентное хранение: Почасовые срезы за 30 дней в кольцевом буфере SharedPreferences.
 */
object WorkerRequestTracker {
    private const val TAG = "WorkerRequestTracker"
    private const val PREFS_NAME = "mirrly_worker_request_stats"
    private const val KEY_HOURLY_BUCKETS = "hourly_buckets_v1"
    private const val KEY_ALL_TIME_WSS = "all_time_wss"
    private const val KEY_ALL_TIME_PROBES = "all_time_probes"
    private const val RETENTION_HOURS = 30 * 24 // 30 дней истории

    const val CLOUDFLARE_FREE_DAILY_LIMIT = 100_000

    private val sessionWssCount = AtomicInteger(0)
    private val sessionProbeCount = AtomicInteger(0)
    private val sessionStartTimestamp = AtomicLong(0L)

    private val hourlyBuckets = ConcurrentHashMap<Long, HourlyBucket>()
    private var allTimeWssCount = 0L
    private var allTimeProbeCount = 0L

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _trackerUpdateEvent = MutableStateFlow(0L)
    val trackerUpdateEvent: StateFlow<Long> = _trackerUpdateEvent.asStateFlow()

    private var lastRecordedNativeWssTotal = 0L

    data class HourlyBucket(
        val timestampHour: Long,
        var wssRequests: Int = 0,
        var probeRequests: Int = 0
    ) {
        val total: Int get() = wssRequests + probeRequests
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
        sessionStartTimestamp.set(System.currentTimeMillis())
        AppLogger.i(TAG, "Трекер запросов к воркерам инициализирован. Загружено ${hourlyBuckets.size} почасовых срезов.")
    }

    fun onSessionStarted() {
        sessionWssCount.set(0)
        sessionProbeCount.set(0)
        sessionStartTimestamp.set(System.currentTimeMillis())
        lastRecordedNativeWssTotal = 0L
        notifyUpdate()
    }

    /**
     * Вызывается при создании нового WSS-подключения к Cloudflare Worker (1 WSS = 1 CF Request).
     */
    fun recordWssConnection(count: Int = 1) {
        if (count <= 0) return
        val now = System.currentTimeMillis()
        val hourKey = normalizeToHour(now)

        sessionWssCount.addAndGet(count)
        allTimeWssCount += count

        val bucket = hourlyBuckets.getOrPut(hourKey) { HourlyBucket(hourKey) }
        synchronized(bucket) {
            bucket.wssRequests += count
        }

        scheduleSave()
        notifyUpdate()
    }

    /**
     * Вызывается при отправке служебного HTTP-запроса проверки доступности воркера.
     */
    fun recordProbeRequest(count: Int = 1) {
        if (count <= 0) return
        val now = System.currentTimeMillis()
        val hourKey = normalizeToHour(now)

        sessionProbeCount.addAndGet(count)
        allTimeProbeCount += count

        val bucket = hourlyBuckets.getOrPut(hourKey) { HourlyBucket(hourKey) }
        synchronized(bucket) {
            bucket.probeRequests += count
        }

        scheduleSave()
        notifyUpdate()
    }

    /**
     * Синхронизация с общим количеством WSS подключений, прочитанным из нативного ядра прокси.
     */
    fun syncNativeConnectionsTotal(currentNativeTotal: Long) {
        if (currentNativeTotal <= 0L) return
        val prev = lastRecordedNativeWssTotal
        if (currentNativeTotal > prev) {
            val delta = (currentNativeTotal - prev).toInt()
            lastRecordedNativeWssTotal = currentNativeTotal
            recordWssConnection(delta)
        } else if (currentNativeTotal < prev) {
            // Нативный движок был перезапущен (счетчики сбросились в 0)
            lastRecordedNativeWssTotal = currentNativeTotal
            recordWssConnection(currentNativeTotal.toInt())
        }
    }

    /**
     * Построение сводной аналитики и точек графика за запрошенный период.
     */
    fun getAnalytics(period: AnalyticsPeriod): PeriodAnalyticsSummary {
        val now = System.currentTimeMillis()
        val currentHour = normalizeToHour(now)
        val resetTime = getTimeUntilCloudflareReset()

        when (period) {
            AnalyticsPeriod.SESSION -> {
                val wss = sessionWssCount.get()
                val probes = sessionProbeCount.get()
                val total = wss + probes
                val start = sessionStartTimestamp.get().coerceAtLeast(now - 3600_000L)
                val durationHours = ((now - start) / 3600_000.0).coerceAtLeast(0.05)
                val burnRate = total / durationHours

                // Поминутные / почасовые точки сессии
                val points = generateSessionPoints(start, now)
                val dailyPercentage = (total.toFloat() / CLOUDFLARE_FREE_DAILY_LIMIT) * 100f

                return PeriodAnalyticsSummary(
                    period = period,
                    totalRequests = total,
                    wssRequests = wss,
                    probeRequests = probes,
                    chartPoints = points,
                    burnRatePerHour = burnRate,
                    dailyQuotaPercentage = dailyPercentage,
                    hoursUntilReset = resetTime.first,
                    minutesUntilReset = resetTime.second
                )
            }

            AnalyticsPeriod.HOUR_1,
            AnalyticsPeriod.HOUR_5,
            AnalyticsPeriod.HOUR_12,
            AnalyticsPeriod.HOUR_24 -> {
                val hours = period.hoursSpan
                val points = mutableListOf<ChartDataPoint>()
                var totalWss = 0
                var totalProbes = 0

                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

                for (i in (hours - 1) downTo 0) {
                    val hourTimestamp = currentHour - (i * 3600_000L)
                    val bucket = hourlyBuckets[hourTimestamp]
                    val wss = bucket?.wssRequests ?: 0
                    val probes = bucket?.probeRequests ?: 0
                    val total = wss + probes

                    totalWss += wss
                    totalProbes += probes

                    points.add(
                        ChartDataPoint(
                            timestampMs = hourTimestamp,
                            timeLabel = timeFormat.format(Date(hourTimestamp)),
                            totalCount = total,
                            wssCount = wss,
                            probeCount = probes
                        )
                    )
                }

                val grandTotal = totalWss + totalProbes
                val burnRate = grandTotal.toDouble() / hours.toDouble()
                // Для суточного расчета привязка к лимиту 100k
                val dailyEquivalent = if (hours == 24) grandTotal else (burnRate * 24).toInt()
                val dailyPercentage = (dailyEquivalent.toFloat() / CLOUDFLARE_FREE_DAILY_LIMIT) * 100f

                return PeriodAnalyticsSummary(
                    period = period,
                    totalRequests = grandTotal,
                    wssRequests = totalWss,
                    probeRequests = totalProbes,
                    chartPoints = points,
                    burnRatePerHour = burnRate,
                    dailyQuotaPercentage = dailyPercentage,
                    hoursUntilReset = resetTime.first,
                    minutesUntilReset = resetTime.second
                )
            }

            AnalyticsPeriod.DAYS_7,
            AnalyticsPeriod.DAYS_30 -> {
                val days = if (period == AnalyticsPeriod.DAYS_7) 7 else 30
                val points = mutableListOf<ChartDataPoint>()
                var totalWss = 0
                var totalProbes = 0

                val dayFormat = SimpleDateFormat("dd.MM", Locale.getDefault())
                val dayMillis = 24 * 3600_000L
                val currentDayStart = (now / dayMillis) * dayMillis

                for (d in (days - 1) downTo 0) {
                    val dayTimestamp = currentDayStart - (d * dayMillis)
                    var dayWss = 0
                    var dayProbes = 0

                    for (h in 0..23) {
                        val hourTs = dayTimestamp + (h * 3600_000L)
                        val b = hourlyBuckets[hourTs]
                        if (b != null) {
                            dayWss += b.wssRequests
                            dayProbes += b.probeRequests
                        }
                    }

                    val dayTotal = dayWss + dayProbes
                    totalWss += dayWss
                    totalProbes += dayProbes

                    points.add(
                        ChartDataPoint(
                            timestampMs = dayTimestamp,
                            timeLabel = dayFormat.format(Date(dayTimestamp)),
                            totalCount = dayTotal,
                            wssCount = dayWss,
                            probeCount = dayProbes
                        )
                    )
                }

                val grandTotal = totalWss + totalProbes
                val burnRate = grandTotal.toDouble() / (days * 24.0)
                val avgDaily = grandTotal.toFloat() / days
                val dailyPercentage = (avgDaily / CLOUDFLARE_FREE_DAILY_LIMIT) * 100f

                return PeriodAnalyticsSummary(
                    period = period,
                    totalRequests = grandTotal,
                    wssRequests = totalWss,
                    probeRequests = totalProbes,
                    chartPoints = points,
                    burnRatePerHour = burnRate,
                    dailyQuotaPercentage = dailyPercentage,
                    hoursUntilReset = resetTime.first,
                    minutesUntilReset = resetTime.second
                )
            }

            AnalyticsPeriod.ALL_TIME -> {
                var totalWss = allTimeWssCount.toInt()
                var totalProbes = allTimeProbeCount.toInt()

                // Если персистентные счетчики не сохранены, берем сумму из ведер
                if (totalWss == 0 && totalProbes == 0) {
                    hourlyBuckets.values.forEach { b ->
                        totalWss += b.wssRequests
                        totalProbes += b.probeRequests
                    }
                }

                val grandTotal = totalWss + totalProbes
                val daysCount = (hourlyBuckets.size / 24).coerceAtLeast(1)
                val burnRate = grandTotal.toDouble() / (daysCount * 24.0)
                val avgDaily = grandTotal.toFloat() / daysCount
                val dailyPercentage = (avgDaily / CLOUDFLARE_FREE_DAILY_LIMIT) * 100f

                // Формируем точки по дням
                val points = generateAllTimePoints()

                return PeriodAnalyticsSummary(
                    period = period,
                    totalRequests = grandTotal,
                    wssRequests = totalWss,
                    probeRequests = totalProbes,
                    chartPoints = points,
                    burnRatePerHour = burnRate,
                    dailyQuotaPercentage = dailyPercentage,
                    hoursUntilReset = resetTime.first,
                    minutesUntilReset = resetTime.second
                )
            }
        }
    }

    /**
     * Расчет времени до ежедневного сброса лимитов Cloudflare (00:00 UTC).
     */
    fun getTimeUntilCloudflareReset(): Pair<Int, Int> {
        val nowUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val nextMidnightUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val diffMs = nextMidnightUtc.timeInMillis - nowUtc.timeInMillis
        val totalMinutes = (diffMs / 60_000L).coerceAtLeast(0)
        val hours = (totalMinutes / 60).toInt()
        val minutes = (totalMinutes % 60).toInt()
        return Pair(hours, minutes)
    }

    private fun generateSessionPoints(startMs: Long, endMs: Long): List<ChartDataPoint> {
        val total = sessionWssCount.get() + sessionProbeCount.get()
        val wss = sessionWssCount.get()
        val probes = sessionProbeCount.get()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        val steps = 6
        val stepMs = ((endMs - startMs) / steps).coerceAtLeast(10_000L)
        val list = mutableListOf<ChartDataPoint>()

        for (i in 0..steps) {
            val ts = startMs + (i * stepMs)
            val fraction = i.toFloat() / steps
            list.add(
                ChartDataPoint(
                    timestampMs = ts,
                    timeLabel = timeFormat.format(Date(ts)),
                    totalCount = (total * fraction).toInt(),
                    wssCount = (wss * fraction).toInt(),
                    probeCount = (probes * fraction).toInt()
                )
            )
        }
        return list
    }

    private fun generateAllTimePoints(): List<ChartDataPoint> {
        val dayFormat = SimpleDateFormat("dd.MM", Locale.getDefault())
        val dayMillis = 24 * 3600_000L
        val sortedKeys = hourlyBuckets.keys().toList().sorted()

        if (sortedKeys.isEmpty()) {
            val now = System.currentTimeMillis()
            return listOf(
                ChartDataPoint(now, dayFormat.format(Date(now)), 0, 0, 0)
            )
        }

        val minHour = sortedKeys.first()
        val maxHour = sortedKeys.last()
        val minDay = (minHour / dayMillis) * dayMillis
        val maxDay = (maxHour / dayMillis) * dayMillis

        val points = mutableListOf<ChartDataPoint>()
        var curDay = minDay
        while (curDay <= maxDay) {
            var dayWss = 0
            var dayProbes = 0
            for (h in 0..23) {
                val hourTs = curDay + (h * 3600_000L)
                val b = hourlyBuckets[hourTs]
                if (b != null) {
                    dayWss += b.wssRequests
                    dayProbes += b.probeRequests
                }
            }
            points.add(
                ChartDataPoint(
                    timestampMs = curDay,
                    timeLabel = dayFormat.format(Date(curDay)),
                    totalCount = dayWss + dayProbes,
                    wssCount = dayWss,
                    probeCount = dayProbes
                )
            )
            curDay += dayMillis
        }
        return points
    }

    private fun normalizeToHour(timestampMs: Long): Long {
        return (timestampMs / 3600_000L) * 3600_000L
    }

    private fun notifyUpdate() {
        _trackerUpdateEvent.value = System.currentTimeMillis()
    }

    private var saveJob: Job? = null
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1500) // Дебаунс записи на диск
            saveToPrefs()
        }
    }

    private fun saveToPrefs() {
        val p = prefs ?: return
        try {
            val jsonArray = JSONArray()
            val cutoff = normalizeToHour(System.currentTimeMillis()) - (RETENTION_HOURS * 3600_000L)

            // Очистка устаревших ведер (> 30 дней)
            val iterator = hourlyBuckets.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key < cutoff) {
                    iterator.remove()
                } else {
                    val b = entry.value
                    val obj = JSONObject().apply {
                        put("ts", b.timestampHour)
                        put("wss", b.wssRequests)
                        put("probe", b.probeRequests)
                    }
                    jsonArray.put(obj)
                }
            }

            p.edit()
                .putString(KEY_HOURLY_BUCKETS, jsonArray.toString())
                .putLong(KEY_ALL_TIME_WSS, allTimeWssCount)
                .putLong(KEY_ALL_TIME_PROBES, allTimeProbeCount)
                .apply()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Ошибка сохранения аналитики запросов: ${t.message}")
        }
    }

    private fun loadFromPrefs() {
        val p = prefs ?: return
        try {
            allTimeWssCount = p.getLong(KEY_ALL_TIME_WSS, 0L)
            allTimeProbeCount = p.getLong(KEY_ALL_TIME_PROBES, 0L)

            val jsonStr = p.getString(KEY_HOURLY_BUCKETS, null) ?: return
            val jsonArray = JSONArray(jsonStr)
            val cutoff = normalizeToHour(System.currentTimeMillis()) - (RETENTION_HOURS * 3600_000L)

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val ts = obj.getLong("ts")
                if (ts >= cutoff) {
                    val wss = obj.optInt("wss", 0)
                    val probe = obj.optInt("probe", 0)
                    hourlyBuckets[ts] = HourlyBucket(ts, wss, probe)
                }
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Ошибка загрузки аналитики запросов: ${t.message}")
        }
    }
}
