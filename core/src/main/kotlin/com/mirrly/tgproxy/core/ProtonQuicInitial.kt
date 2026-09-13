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

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Генератор валидного пакета QUIC Initial (RFC 9001) для параметра `I1` AmneziaWG (AWG).
 *
 * `I1` — это UDP-пакет, который клиент отправляет на сервер перед началом WireGuard handshake.
 * Сервер WireGuard/WARP отбрасывает его как неизвестный, но для DPI ТСПУ поток выглядит
 * как легитимное QUIC Initial соединение с доверенным SNI (например, www.gosuslugi.ru или vk.com).
 *
 * Пакет формируется в строгом соответствии с RFC 9001:
 * - Соль версии QUIC v1
 * - HKDF-Expand-Label (client in, quic key, quic iv, quic hp)
 * - Минимальный ClientHello с расширением SNI (server_name)
 * - Шифрование AES-GCM (128-бит) поверх CRYPTO-фрейма
 * - Маскировка заголовка (Header Protection) через AES-ECB
 * - Дополнение (padding) ровно до 1250 байт по RFC 9000 §14.1
 */
object ProtonQuicInitial {

    /** Стандартная соль QUIC v1 из RFC 9001 §5.2 */
    private val INITIAL_SALT = byteArrayOf(
        0x38, 0x76, 0x2c, 0xf7.toByte(), 0xf5.toByte(), 0x59, 0x34, 0xb3.toByte(), 0x4d, 0x17,
        0x9a.toByte(), 0xe6.toByte(), 0xa4.toByte(), 0xc8.toByte(), 0x0c, 0xad.toByte(),
        0xcc.toByte(), 0xbb.toByte(), 0x7f, 0x0a
    )

    private val secureRandom = SecureRandom()

    private const val PAD_TO = 1250
    private const val DCID_SIZE = 8

    /** Доверенные российские домены для белого списка SNI маскировки ТСПУ */
    val DEFAULT_SNI_POOL = listOf(
        "www.gosuslugi.ru",
        "vk.com",
        "yandex.ru",
        "sberbank.ru",
        "tbank.ru"
    )

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun expandLabel(secret: ByteArray, length: Int, label: String): ByteArray {
        val full = "tls13 $label".toByteArray(Charsets.UTF_8)
        val info = ByteArray(2 + 1 + full.size + 1 + 1)
        info[0] = ((length shr 8) and 0xFF).toByte()
        info[1] = (length and 0xFF).toByte()
        info[2] = full.size.toByte()
        System.arraycopy(full, 0, info, 3, full.size)
        info[3 + full.size] = 0
        info[4 + full.size] = 1
        return hmacSha256(secret, info).copyOf(length)
    }

    private fun varInt(value: Int): ByteArray = when {
        value < 0x40 -> byteArrayOf(value.toByte())
        value < 0x4000 -> byteArrayOf((((value shr 8) and 0xFF) or 0x40).toByte(), (value and 0xFF).toByte())
        else -> byteArrayOf(
            (((value shr 24) and 0xFF) or 0x80).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    private fun varIntLength(value: Int): Int = when {
        value < 0x40 -> 1
        value < 0x4000 -> 2
        else -> 4
    }

    private fun u16(value: Int) = byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    private fun clientHello(sni: String, alpn: String? = null): ByteArray {
        val name = sni.toByteArray(Charsets.UTF_8)
        val serverNameList = u16(name.size + 3) + byteArrayOf(0) + u16(name.size) + name
        val sniExtension = u16(0) + u16(serverNameList.size) + serverNameList

        val extensionsBytes = if (alpn != null && alpn.isNotEmpty()) {
            val alpnBytes = alpn.toByteArray(Charsets.UTF_8)
            val protoList = byteArrayOf(alpnBytes.size.toByte()) + alpnBytes
            val alpnExtValue = u16(protoList.size) + protoList
            val alpnExtension = u16(16) + u16(alpnExtValue.size) + alpnExtValue
            sniExtension + alpnExtension
        } else {
            sniExtension
        }
        val extensions = u16(extensionsBytes.size) + extensionsBytes

        val random = ByteArray(32).also { secureRandom.nextBytes(it) }
        val body = byteArrayOf(0x03, 0x03) + random + byteArrayOf(0, 0, 0, 0) + extensions
        return byteArrayOf(
            0x01,
            ((body.size shr 16) and 0xFF).toByte(),
            ((body.size shr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte()
        ) + body
    }

    /**
     * Формирует сырой бинарный UDP-пакет QUIC Initial (RFC 9001) с шифрованием AES-GCM и маскировкой заголовка.
     *
     * @param sni Доменное имя (SNI), например "consumer-masque.cloudflareclient.com"
     * @param alpn Идентификатор протокола прикладного уровня, например "h3"
     * @return Бинарный массив байтов пакета QUIC Initial (1250 байт)
     */
    fun createQuicInitialPacket(
        sni: String = "consumer-masque.cloudflareclient.com",
        alpn: String? = "h3"
    ): ByteArray {
        val host = sni.trim().trimEnd('.')
        if (host.isBlank() || host.length > 250) return ByteArray(0)
        return try {
            val dcid = ByteArray(DCID_SIZE).also { secureRandom.nextBytes(it) }
            val pkn = byteArrayOf(0)
            val hello = clientHello(host, alpn)
            val payload = byteArrayOf(0x06) + varInt(0) + varInt(hello.size) + hello

            val tag = 16
            val baseHeader = 8 + dcid.size + 0 + 0 + pkn.size
            fun overall(padding: Int): Int =
                baseHeader + varIntLength(pkn.size + payload.size + padding + tag) +
                    payload.size + padding + tag
            var padding = 0
            if (overall(0) < PAD_TO) {
                padding = PAD_TO - overall(0)
                while (padding > 0 && overall(padding) > PAD_TO) padding--
                if (overall(padding) < PAD_TO) padding++
            }
            if (pkn.size + payload.size + padding + tag < 20) {
                padding = 20 - pkn.size - payload.size - tag
            }
            val remainder = pkn.size + payload.size + padding + tag

            val header = byteArrayOf((0xC0 or (pkn.size - 1)).toByte(), 0, 0, 0, 1) +
                byteArrayOf(dcid.size.toByte()) + dcid +
                byteArrayOf(0) +
                byteArrayOf(0) +
                varInt(remainder) + pkn

            val initialSecret = hmacSha256(INITIAL_SALT, dcid)
            val clientSecret = expandLabel(initialSecret, 32, "client in")
            val key = expandLabel(clientSecret, 16, "quic key")
            val iv = expandLabel(clientSecret, 12, "quic iv")
            val hp = expandLabel(clientSecret, 16, "quic hp")
            for (i in pkn.indices) {
                val at = iv.size - pkn.size + i
                iv[at] = (iv[at].toInt() xor pkn[i].toInt()).toByte()
            }

            val gcm = Cipher.getInstance("AES/GCM/NoPadding")
            gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            gcm.updateAAD(header)
            val encrypted = gcm.doFinal(payload + ByteArray(padding))

            val sampleOffset = 4 - pkn.size
            val sample = encrypted.copyOfRange(sampleOffset, sampleOffset + 16)
            val ecb = Cipher.getInstance("AES/ECB/NoPadding")
            ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hp, "AES"))
            val mask = ecb.doFinal(sample)

            val protectedHeader = header.copyOf()
            protectedHeader[0] = (protectedHeader[0].toInt() xor (mask[0].toInt() and 0x0F)).toByte()
            for (i in pkn.indices) {
                val at = protectedHeader.size - pkn.size + i
                protectedHeader[at] = (protectedHeader[at].toInt() xor mask[1 + i].toInt()).toByte()
            }

            protectedHeader + encrypted
        } catch (e: Exception) {
            AppLogger.w("ProtonQuicInitial", "Ошибка сборки пакета QUIC Initial для $host: ${e.message}")
            ByteArray(0)
        }
    }

    /**
     * Формирует готовую строку параметра `I1` в формате AmneziaWG: `<b 0x...>`
     *
     * @param sni Доменное имя для маскировки (по умолчанию www.gosuslugi.ru)
     * @return Шестнадцатеричная строка в формате `<b 0x...>`
     */
    fun buildI1(sni: String = DEFAULT_SNI_POOL[0]): String {
        val packet = createQuicInitialPacket(sni, alpn = null)
        if (packet.isEmpty()) return ""
        val hex = StringBuilder(packet.size * 2)
        packet.forEach { hex.append(String.format("%02x", it)) }
        return "<b 0x$hex>"
    }
}
