package com.mirrly.tgproxy.core

/** User-facing route names. Raw supervisor telemetry remains unchanged for diagnostics. */
object ProxyDisplayLabels {
    const val CLOUDFLARE_WSS = "Cloudflare WSS"

    fun isCloudflareWorkerRoute(
        effectiveRoute: String,
        operator: String,
        configuredWorker: Boolean = false
    ): Boolean = configuredWorker ||
        effectiveRoute.contains("Cloudflare Worker", ignoreCase = true) ||
        operator.contains("Cloudflare Worker", ignoreCase = true)

    fun notificationRouteLabel(
        effectiveRoute: String,
        operator: String,
        fallbackLabel: String,
        configuredWorker: Boolean = false
    ): String = if (isCloudflareWorkerRoute(effectiveRoute, operator, configuredWorker)) {
        CLOUDFLARE_WSS
    } else {
        fallbackLabel
    }

    fun homeStatusLabel(
        effectiveRoute: String,
        operator: String,
        isTrustBoundaryMaintained: Boolean,
        configuredWorker: Boolean = false,
        securedLabel: String = "Защищено",
        publicFallbackLabel: String = "Публичный резерв"
    ): String {
        if (isCloudflareWorkerRoute(effectiveRoute, operator, configuredWorker)) {
            return "$CLOUDFLARE_WSS · $securedLabel"
        }

        val operatorSuffix = if (operator.isNotBlank()) " ($operator)" else ""
        val trustStatus = if (isTrustBoundaryMaintained) " · $securedLabel" else " · $publicFallbackLabel"
        return "$effectiveRoute$operatorSuffix$trustStatus"
    }

    /** Заголовок графика и индикатора активности: различает SOCKS5 flows и MTProto WsPool сокеты. */
    fun transportFlowTitle(
        isSocks5: Boolean,
        socks5Title: String = "SOCKS5 ПОТОКИ",
        wsPoolTitle: String = "WSPOOL СОКЕТЫ"
    ): String =
        if (isSocks5) socks5Title else wsPoolTitle

    /** Подпись статуса: не называет SOCKS5 потоки сокет-пулом, показывает requested/effective для MTProto. */
    fun transportFlowSubtitle(
        isSocks5: Boolean,
        isProxyActive: Boolean,
        activeConns: Int,
        requestedStandby: Int,
        effectiveStandby: Int,
        stoppedLabel: String = "остановлен",
        activeConnsFormat: (Int) -> String = { "$it активных" },
        activeStandbyFormat: (Int, Int, Int) -> String = { active, eff, req -> "$active активных · Резерв: $eff ($req)" }
    ): String {
        if (!isProxyActive) return stoppedLabel
        return if (isSocks5) {
            activeConnsFormat(activeConns)
        } else {
            activeStandbyFormat(activeConns, effectiveStandby, requestedStandby)
        }
    }
}
