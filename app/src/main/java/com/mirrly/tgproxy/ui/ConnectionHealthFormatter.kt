/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui

import android.content.Context
import com.mirrly.tgproxy.R

object ConnectionHealthFormatter {

    fun formatVerdict(context: Context, rawVerdict: String): String {
        return when (rawVerdict.trim()) {
            "Optimal Connection", "Идеальный канал связи" -> context.getString(R.string.verdict_ideal_channel)
            "Operator DPI Block", "Блокировка DPI оператором" -> context.getString(R.string.verdict_dpi_blocked)
            "Secure TLS Failure", "Сбой защищенного TLS" -> context.getString(R.string.verdict_tls_failed)
            "Good Connection", "Хорошее соединение" -> context.getString(R.string.verdict_good_connection)
            "Operator Delays", "Задержки на стороне оператора" -> context.getString(R.string.verdict_operator_delays)
            "Link Degradation", "Деградация канала" -> context.getString(R.string.verdict_link_degradation)
            "Critical Instability", "Критическая нестабильность" -> context.getString(R.string.verdict_critical_instability)
            "Channel inactive", "Канал не активен" -> context.getString(R.string.verdict_channel_inactive)
            "Waiting for network", "Ожидание сети" -> context.getString(R.string.verdict_waiting_network)
            else -> rawVerdict
        }
    }

    fun formatDetail(context: Context, rawDetail: String): String {
        return when (rawDetail.trim()) {
            "Minimal latency and stable direct WSS tunnel", "Минимальная задержка и стабильный прямой WSS-туннель" ->
                context.getString(R.string.verdict_ideal_channel_desc)
            "TCP/ClientHello packet reset detected by middlebox", "Обнаружен сброс TCP/ClientHello пакетов middlebox-системой" ->
                context.getString(R.string.verdict_dpi_blocked_desc)
            "TLS handshake aborted or invalid server certificate", "Разрыв TLS-рукопожатия или попытка подмены сертификата узла" ->
                context.getString(R.string.verdict_tls_failed_desc)
            "Minor latency variation (mobile or Wi-Fi jitter)", "Небольшая вариация задержки (джиттер мобильной сети или Wi-Fi)" ->
                context.getString(R.string.verdict_good_connection_desc)
            "Increased cellular radio jitter (LTE/5G) or cell congestion", "Повышенный джиттер сотового радиоканала (LTE/5G) или перегрузка вышки" ->
                context.getString(R.string.verdict_operator_delays_desc)
            "Packet loss en route to Cloudflare Edge", "Потери пакетов на маршруте к узлу Cloudflare Edge" ->
                context.getString(R.string.verdict_link_degradation_desc)
            "High timeout rate or provider blocking", "Высокий процент таймаутов или блокировка провайдером" ->
                context.getString(R.string.verdict_critical_instability_desc)
            "Awaiting initial network probes", "Ожидание первых сетевых проб" ->
                context.getString(R.string.verdict_channel_inactive_desc)
            "No internet connection. Waiting for network...", "Нет подключения к интернету. Ожидание сети..." ->
                context.getString(R.string.verdict_waiting_network_desc)
            "Waiting for network check...", "Ожидание проверки канала..." ->
                context.getString(R.string.verdict_waiting_check_desc)
            else -> rawDetail
        }
    }

    fun formatChatVerdict(context: Context, rawChatVerdict: String): String {
        return when (rawChatVerdict.trim()) {
            "Ideal for media", "Идеально для медиа" -> context.getString(R.string.chat_verdict_ideal)
            "Stable", "Стабильно" -> context.getString(R.string.chat_verdict_stable)
            "Moderate speed", "Умеренная скорость" -> context.getString(R.string.chat_verdict_moderate)
            "Download delays", "Задержка загрузки" -> context.getString(R.string.chat_verdict_delays)
            "Delivery failures", "Сбои доставки" -> context.getString(R.string.chat_verdict_failures)
            "No connection", "Нет связи" -> context.getString(R.string.chat_verdict_none)
            else -> rawChatVerdict
        }
    }

    fun formatMosGrade(context: Context, rawMosGrade: String): String {
        return when (rawMosGrade.trim()) {
            "HD Voice (Excellent)", "HD Voice (Отлично)" -> context.getString(R.string.mos_grade_hd)
            "Good quality", "Хорошее качество" -> context.getString(R.string.mos_grade_good)
            "Acceptable", "Приемлемо" -> context.getString(R.string.mos_grade_acceptable)
            "With noise", "С помехами" -> context.getString(R.string.mos_grade_noise)
            "Unusable", "Непригодно" -> context.getString(R.string.mos_grade_unusable)
            "No connection", "Нет связи" -> context.getString(R.string.mos_grade_none)
            else -> rawMosGrade
        }
    }

    fun formatOperatorGrade(context: Context, rawGrade: String): String {
        return when (rawGrade.trim()) {
            "Excellent", "Отлично" -> context.getString(R.string.op_grade_excellent)
            "Normal", "В норме" -> context.getString(R.string.op_grade_normal)
            "High jitter", "Высокий джиттер" -> context.getString(R.string.op_grade_high_jitter)
            "Delays", "Задержки" -> context.getString(R.string.op_grade_delays)
            "Unavailable", "Недоступно" -> context.getString(R.string.op_grade_unavailable)
            else -> rawGrade
        }
    }

    fun formatWorkerGrade(context: Context, rawGrade: String): String {
        return when (rawGrade.trim()) {
            "DPI Block", "DPI Блок" -> context.getString(R.string.ws_grade_dpi_block)
            "TLS Failure", "TLS Сбой" -> context.getString(R.string.ws_grade_tls_failure)
            "Limit 429", "Лимит 429" -> context.getString(R.string.ws_grade_limit_429)
            "Stable", "Стабилен" -> context.getString(R.string.ws_grade_stable)
            "Reachable", "Доступен" -> context.getString(R.string.ws_grade_reachable)
            "Failures", "Сбои" -> context.getString(R.string.ws_grade_failures)
            "Awaiting", "Ожидание" -> context.getString(R.string.ws_grade_awaiting)
            else -> rawGrade
        }
    }

    /**
     * Пользовательское представление сбоя (Уровень 1: краткое, дружелюбное, без технического жаргона).
     */
    fun formatFailureForUser(context: Context, failureType: com.mirrly.tgproxy.core.FailureType): String {
        return when (failureType) {
            com.mirrly.tgproxy.core.FailureType.NONE -> ""
            com.mirrly.tgproxy.core.FailureType.WORKER_QUOTA_EXCEEDED,
            com.mirrly.tgproxy.core.FailureType.RATE_LIMITED_429 -> context.getString(R.string.failure_worker_quota_exceeded)
            com.mirrly.tgproxy.core.FailureType.DNS_RESOLUTION_UNAVAILABLE,
            com.mirrly.tgproxy.core.FailureType.DNS_FAILURE -> context.getString(R.string.failure_dns_resolution_unavailable)
            com.mirrly.tgproxy.core.FailureType.SOCKS5_AUTH_REJECTED -> context.getString(R.string.failure_socks5_auth_rejected)
            com.mirrly.tgproxy.core.FailureType.CLOUDFLARE_EDGE_BLOCKED,
            com.mirrly.tgproxy.core.FailureType.DPI_BLOCKED -> context.getString(R.string.failure_cloudflare_edge_blocked)
            com.mirrly.tgproxy.core.FailureType.WARP_HANDSHAKE_TIMEOUT -> context.getString(R.string.failure_warp_handshake_timeout)
            com.mirrly.tgproxy.core.FailureType.NETWORK_INTERFACE_DOWN,
            com.mirrly.tgproxy.core.FailureType.NETWORK_LOST -> context.getString(R.string.failure_network_interface_down)
            com.mirrly.tgproxy.core.FailureType.CONNECT_TIMEOUT -> context.getString(R.string.failure_connect_timeout)
            com.mirrly.tgproxy.core.FailureType.HOST_UNREACHABLE,
            com.mirrly.tgproxy.core.FailureType.FALLBACK_IP_FAILED -> context.getString(R.string.failure_host_unreachable)
            com.mirrly.tgproxy.core.FailureType.TLS_HANDSHAKE_FAILED -> context.getString(R.string.failure_tls_handshake_failed)
            com.mirrly.tgproxy.core.FailureType.RELAY_ACK_FAILED -> context.getString(R.string.failure_relay_ack_failed)
            com.mirrly.tgproxy.core.FailureType.PREDICTIVE_DEGRADATION -> context.getString(R.string.failure_predictive_degradation)
            com.mirrly.tgproxy.core.FailureType.UNKNOWN_ERROR,
            com.mirrly.tgproxy.core.FailureType.UNSUPPORTED_NETWORK_FAMILY -> context.getString(R.string.failure_unknown)
        }
    }

    /**
     * Техническое представление сбоя (Уровень 2: машиночитаемый код, фаза, HTTP статус для логов и отчёта).
     */
    fun formatFailureTechnical(
        failureType: com.mirrly.tgproxy.core.FailureType,
        httpStatus: Int? = null,
        detail: String? = null
    ): String {
        val code = failureType.technicalCode.ifEmpty { failureType.name }
        val sb = StringBuilder(code)
        sb.append(" [stage=").append(failureType.stage.name).append("]")
        if (httpStatus != null && httpStatus > 0) {
            sb.append(" [http=").append(httpStatus).append("]")
        }
        if (!detail.isNullOrBlank()) {
            sb.append(" (").append(detail.trim()).append(")")
        }
        return sb.toString()
    }
}
