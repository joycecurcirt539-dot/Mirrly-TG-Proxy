package com.mirrly.tgproxy.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

data class NetworkEvaluationResult(
    val isInstantSendRecommended: Boolean,
    val statusDescription: String,
    val detailReason: String
)

object NetworkConditionEvaluator {

    private const val BANDWIDTH_THRESHOLD_KBPS = 50_000 // 50 Mbps
    private const val PING_THRESHOLD_MS = 140L          // 140 ms sweet spot

    fun evaluate(
        context: Context,
        capabilities: NetworkCapabilities?,
        currentPingMs: Long = -1L,
        currentThroughputBps: Long = 0L
    ): NetworkEvaluationResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = capabilities ?: try {
            val activeNet = cm?.activeNetwork
            if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
        } catch (_: Exception) {
            null
        }

        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkEvaluationResult(
                isInstantSendRecommended = false,
                statusDescription = "Авто (Нет сети: Склеивание)",
                detailReason = "Нет активного подключения к интернету"
            )
        }

        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        val isCongested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
        } else {
            false
        }

        // 1. Проверка перегрузки сети
        if (isCongested) {
            return NetworkEvaluationResult(
                isInstantSendRecommended = false,
                statusDescription = "Авто (Перегрузка: Склеивание)",
                detailReason = "Сеть перегружена (NET_CAPABILITY_NOT_CONGESTED = false)"
            )
        }

        // 2. Проверка задержки (RTT / Пинг)
        if (currentPingMs > PING_THRESHOLD_MS) {
            return NetworkEvaluationResult(
                isInstantSendRecommended = false,
                statusDescription = "Авто (Пинг > 140мс: Склеивание)",
                detailReason = "Высокая задержка канала ($currentPingMs мс > $PING_THRESHOLD_MS мс)"
            )
        }

        // 3. Проверка пропускной способности (Bandwidth)
        val downstreamBandwidth = caps.linkDownstreamBandwidthKbps
        val isThroughputHigh = currentThroughputBps >= (BANDWIDTH_THRESHOLD_KBPS * 1024L / 8L) // 6.25 MB/s
        val isBandwidthSufficient = downstreamBandwidth >= BANDWIDTH_THRESHOLD_KBPS || isThroughputHigh

        // Если это сотовая связь или лимитная точка доступа
        if (isCellular || isMetered) {
            if (isBandwidthSufficient && (currentPingMs in 1..PING_THRESHOLD_MS)) {
                return NetworkEvaluationResult(
                    isInstantSendRecommended = true,
                    statusDescription = "Авто (Скоростная сеть: Мгновенно)",
                    detailReason = "Скоростная мобильная сеть (>= 50 Мбит/с, пинг <= 140 мс)"
                )
            }
            return NetworkEvaluationResult(
                isInstantSendRecommended = false,
                statusDescription = "Авто (Мобильная сеть: Склеивание)",
                detailReason = "Мобильная или лимитная сеть со скоростью < 50 Мбит/с"
            )
        }

        // Wi-Fi или Ethernet
        if (isWifi || isEthernet) {
            // Если ОС не возвращает пропускную способность (0 или дефолт), но Wi-Fi безлимитен и пинг в норме
            val isWifiFast = downstreamBandwidth >= BANDWIDTH_THRESHOLD_KBPS || downstreamBandwidth <= 0 || isThroughputHigh
            if (isWifiFast && (currentPingMs <= 0L || currentPingMs <= PING_THRESHOLD_MS)) {
                return NetworkEvaluationResult(
                    isInstantSendRecommended = true,
                    statusDescription = "Авто (Скоростная сеть: Мгновенно)",
                    detailReason = "Безлимитный Wi-Fi с низкой задержкой"
                )
            } else if (!isWifiFast) {
                return NetworkEvaluationResult(
                    isInstantSendRecommended = false,
                    statusDescription = "Авто (Слабый канал: Склеивание)",
                    detailReason = "Пропускная способность Wi-Fi ниже 50 Мбит/с ($downstreamBandwidth Кбит/с)"
                )
            }
        }

        // Дефолтная оценка для прочих сетей
        return if (isBandwidthSufficient) {
            NetworkEvaluationResult(
                isInstantSendRecommended = true,
                statusDescription = "Авто (Скоростная сеть: Мгновенно)",
                detailReason = "Скорость канала >= 50 Мбит/с"
            )
        } else {
            NetworkEvaluationResult(
                isInstantSendRecommended = false,
                statusDescription = "Авто (Слабый канал: Склеивание)",
                detailReason = "Скорость канала < 50 Мбит/с"
            )
        }
    }
}
