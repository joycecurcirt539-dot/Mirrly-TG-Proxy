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

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Детектор аномалий и сигнатур DPI-блокировок операторов связи (ТСПУ / GFW / Middlebox).
 *
 * Классифицирует сетевые сбои:
 * 1. Инъекция TCP RST пакетов (ECONNRESET) во время или сразу после ClientHello.
 * 2. Разрыв или подмена TLS-сертификатов (SSLHandshakeException, CertificateException).
 * 3. Селективное зануление трафика (Blackholing / Silent Dropping).
 * 4. Ограничения Cloudflare (HTTP 429).
 */
object DpiAnomalyDetector {

    private val TCP_RESET_PATTERNS = listOf(
        "ECONNRESET",
        "Connection reset by peer",
        "Connection reset",
        "Software caused connection abort",
        "Broken pipe",
        "recvfrom failed: ECONNRESET"
    )

    private val TLS_ABORT_PATTERNS = listOf(
        "bad_record_mac",
        "handshake_failure",
        "unrecognized_name",
        "CertPathValidatorException",
        "Trust anchor for certification path not found",
        "Hostname not verified",
        "SSLHandshakeException",
        "SSLPeerUnverifiedException"
    )

    /**
     * Анализирует исключение и цепочку причин (cause chain), возвращая точный тип сетевого сбоя.
     */
    fun classifyException(throwable: Throwable?): FailureType {
        if (throwable == null) return FailureType.NONE

        var current: Throwable? = throwable
        var fullMessage = buildString {
            while (current != null) {
                append(current?.javaClass?.simpleName ?: "")
                append(": ")
                append(current?.message ?: "")
                append(" | ")
                current = current?.cause
            }
        }

        // 1. Проверка на сбои DNS / DoH
        if (throwable is UnknownHostException || fullMessage.contains("UnknownHostException", ignoreCase = true)) {
            return FailureType.DNS_FAILURE
        }

        // 2. Проверка сигнатур DPI TCP Reset
        if (TCP_RESET_PATTERNS.any { fullMessage.contains(it, ignoreCase = true) }) {
            return FailureType.DPI_BLOCKED
        }

        // 3. Проверка сигнатур сбоя или подмены TLS
        if (throwable is SSLHandshakeException ||
            throwable is SSLPeerUnverifiedException ||
            throwable is CertificateException ||
            TLS_ABORT_PATTERNS.any { fullMessage.contains(it, ignoreCase = true) }
        ) {
            return FailureType.TLS_HANDSHAKE_FAILED
        }

        // 4. Проверка ограничений Cloudflare (429)
        if (fullMessage.contains("429", ignoreCase = true) || fullMessage.contains("Too Many Requests", ignoreCase = true)) {
            return FailureType.RATE_LIMITED_429
        }

        // 5. Проверка потери сетевого интерфейса
        if (fullMessage.contains("ENETUNREACH", ignoreCase = true) ||
            fullMessage.contains("Network is unreachable", ignoreCase = true) ||
            fullMessage.contains("NetworkLost", ignoreCase = true)
        ) {
            return FailureType.NETWORK_LOST
        }

        // 6. Проверка таймаутов соединения
        if (throwable is SocketTimeoutException || fullMessage.contains("SocketTimeoutException", ignoreCase = true) || fullMessage.contains("timed out", ignoreCase = true)) {
            return FailureType.CONNECT_TIMEOUT
        }

        // 7. Проверка отказа узла (ECONNREFUSED)
        if (throwable is ConnectException || fullMessage.contains("ECONNREFUSED", ignoreCase = true) || fullMessage.contains("Connection refused", ignoreCase = true)) {
            return FailureType.HOST_UNREACHABLE
        }

        // 8. Прочие сокет-ошибки
        if (throwable is SocketException) {
            return FailureType.UNKNOWN_ERROR
        }

        return FailureType.UNKNOWN_ERROR
    }

    /**
     * Возвращает true, если тип сбоя указывает на целенаправленную блокировку цензором/провайдером.
     */
    fun isDpiOrCensorship(failureType: FailureType): Boolean {
        return failureType == FailureType.DPI_BLOCKED ||
                failureType == FailureType.TLS_HANDSHAKE_FAILED ||
                failureType == FailureType.RATE_LIMITED_429
    }
}
