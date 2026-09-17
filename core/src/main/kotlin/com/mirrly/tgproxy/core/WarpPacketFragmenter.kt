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

import org.bouncycastle.crypto.digests.Blake2sDigest
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Base64

/**
 * Движок фрагментации и десинхронизации первого UDP-пакета (Handshake Initiation).
 *
 * Сигнатурные фильтры ТСПУ (DPI) в России блокируют пакеты WireGuard по следующим признакам:
 * 1. Фиксированная длина первого пакета: ровно 148 байт.
 * 2. Первый байт `0x01` (Handshake Initiation) и последующие 3 нулевых байта `0x00 0x00 0x00`.
 *
 * Данный модуль предоставляет:
 * - Фрагментацию первого IP-пакета на 2 фрагмента (IP-Frag / Split) для обхода потокового анализа DPI.
 *   Linux-стек серверов Cloudflare автоматически пересобирает IP-фрагменты на уровне ядра.
 * - Десинхронизацию UDP (отправку мусорного пре-пакета / QUIC-маскировки перед хэндшейком).
 * - Сборку тестовых зондов WireGuard и QUIC Version Negotiation для замера доступности портов.
 */
object WarpPacketFragmenter {

    private val secureRandom = SecureRandom()
    private const val WIREGUARD_INITIATION_SIZE = 148
    private const val DEFAULT_IPV4_SPLIT_OFFSET = 64
    const val CLOUDFLARE_WARP_PEER_PUBKEY_B64 = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    /**
     * Вычисляет MAC1 для пакета WireGuard Handshake Initiation согласно Noise IKpsk2 / RFC 7693.
     * Формула:
     * mac1_key = BLAKE2s-256("mac1----" || peerPublicKey)
     * mac1 = BLAKE2s-128(key = mac1_key, data = packet[0..116])
     */
    fun calculateWireGuardMac1(packet116: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val mac1KeyInput = ByteArray(8 + 32)
        System.arraycopy("mac1----".toByteArray(Charsets.US_ASCII), 0, mac1KeyInput, 0, 8)
        System.arraycopy(peerPublicKey, 0, mac1KeyInput, 8, 32)

        val keyDigest = Blake2sDigest(256)
        keyDigest.update(mac1KeyInput, 0, mac1KeyInput.size)
        val mac1Key = ByteArray(32)
        keyDigest.doFinal(mac1Key, 0)

        val macDigest = Blake2sDigest(mac1Key, 16, null, null)
        macDigest.update(packet116, 0, 116)
        val mac1 = ByteArray(16)
        macDigest.doFinal(mac1, 0)
        return mac1
    }

    fun calculateWireGuardMac1(packet116: ByteArray, peerPublicKeyBase64: String): ByteArray {
        val pubKeyBytes = try {
            Base64.getDecoder().decode(peerPublicKeyBase64)
        } catch (_: Exception) {
            ByteArray(32)
        }
        return calculateWireGuardMac1(packet116, pubKeyBytes)
    }

    /**
     * Формирует тестовый 148-байтный пакет инициализации WireGuard с криптографически валидным MAC1.
     * Используется для проверки доступности Anycast-эндпоинтов Cloudflare.
     */
    fun createWireGuardInitiationProbe(
        peerPublicKeyBase64: String = CLOUDFLARE_WARP_PEER_PUBKEY_B64
    ): ByteArray {
        val peerPub = try {
            Base64.getDecoder().decode(peerPublicKeyBase64)
        } catch (_: Exception) {
            Base64.getDecoder().decode(CLOUDFLARE_WARP_PEER_PUBKEY_B64)
        }

        val buf = ByteBuffer.allocate(WIREGUARD_INITIATION_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        // Message Type: 1 (Initiation)
        buf.put(0x01.toByte())
        // Reserved: 3 zero bytes
        buf.put(0x00.toByte())
        buf.put(0x00.toByte())
        buf.put(0x00.toByte())
        // Sender Index (4 bytes random)
        val senderIndex = secureRandom.nextInt()
        buf.putInt(senderIndex)
        // Unencrypted Ephemeral Key (32 bytes)
        val ephemeral = ByteArray(32)
        secureRandom.nextBytes(ephemeral)
        buf.put(ephemeral)
        // Encrypted Static (48 bytes: 32 ciphertext + 16 auth tag)
        val staticKeyCipher = ByteArray(48)
        secureRandom.nextBytes(staticKeyCipher)
        buf.put(staticKeyCipher)
        // Encrypted Timestamp (28 bytes: 12 ciphertext + 16 auth tag)
        val timestampCipher = ByteArray(28)
        secureRandom.nextBytes(timestampCipher)
        buf.put(timestampCipher)

        // MAC1 (16 bytes calculated via BLAKE2s)
        val packet116 = ByteArray(116)
        System.arraycopy(buf.array(), 0, packet116, 0, 116)
        val mac1 = calculateWireGuardMac1(packet116, peerPub)
        buf.put(mac1)

        // MAC2 (16 zero bytes)
        buf.put(ByteArray(16))

        return buf.array()
    }

    /**
     * Формирует зонд QUIC с неподдерживаемой версией (0x1a2a3a4a).
     * Согласно RFC 9000 §5.2, любой валидный QUIC/HTTP3 сервер (включая порты Cloudflare WARP/MASQUE)
     * ОБЯЗАН ответить пакетом Version Negotiation.
     */
    fun createQuicVersionNegotiationProbe(): ByteArray {
        val dcid = ByteArray(8)
        val scid = ByteArray(8)
        secureRandom.nextBytes(dcid)
        secureRandom.nextBytes(scid)

        val buf = ByteBuffer.allocate(64)
        // Long Header, Type: Initial (0xC0), Version: 0x1a2a3a4a (Reserved for negotiation trigger)
        buf.put(0xC0.toByte())
        buf.putInt(0x1a2a3a4a)
        buf.put(dcid.size.toByte())
        buf.put(dcid)
        buf.put(scid.size.toByte())
        buf.put(scid)
        // Token Length = 0
        buf.put(0x00.toByte())
        // Remaining padding
        while (buf.hasRemaining()) {
            buf.put(0x00.toByte())
        }
        return buf.array()
    }

    /**
     * Создает мусорный пре-пакет (Junk Decoy) для отправки за несколько миллисекунд
     * перед основным Handshake Initiation. Сбивает конвейер распознавания ТСПУ.
     */
    fun createDesyncedJunkPrefix(size: Int = 48): ByteArray {
        val safeSize = size.coerceIn(24, 128)
        val bytes = ByteArray(safeSize)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /**
     * Разрезает UDP-пакет на 2 фрагмента IPv4 (IP Fragmentation).
     *
     * @param srcIp Локальный IP адрес
     * @param srcPort Локальный UDP порт
     * @param dstIp IP адрес Cloudflare
     * @param dstPort UDP порт Cloudflare
     * @param udpPayload Тело UDP пакета (например, 148 байт WireGuard)
     * @param splitOffset Смещение разделения полезной нагрузки (кратно 8 байтам)
     * @return Пара (Фрагмент 1, Фрагмент 2) в формате сырых IPv4-пакетов
     */
    fun fragmentIpv4Udp(
        srcIp: InetAddress,
        srcPort: Int,
        dstIp: InetAddress,
        dstPort: Int,
        udpPayload: ByteArray,
        splitOffset: Int = DEFAULT_IPV4_SPLIT_OFFSET
    ): Pair<ByteArray, ByteArray> {
        require(srcIp is Inet4Address && dstIp is Inet4Address) { "IP адреса должны быть IPv4" }
        // В IPv4 смещение фрагмента выражается в 8-байтных блоках.
        // Суммарный размер первого блока в UDP = 8 байт (UDP Header) + splitOffset.
        val alignedSplit = (splitOffset / 8) * 8
        val effectiveSplit = alignedSplit.coerceIn(16, (udpPayload.size - 16).coerceAtLeast(16))

        val ipId = secureRandom.nextInt(0xFFFF)
        val totalUdpLength = 8 + udpPayload.size

        // Сборка полного заголовка UDP (8 байт)
        val udpHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        udpHeader.putShort(srcPort.toShort())
        udpHeader.putShort(dstPort.toShort())
        udpHeader.putShort(totalUdpLength.toShort())
        udpHeader.putShort(0.toShort()) // Checksum (0 в IPv4 допустима)

        // --- Фрагмент 1 ---
        // Содержит: Заголовок IPv4 (20 байт) + Заголовок UDP (8 байт) + первые `effectiveSplit` байт полезной нагрузки
        val frag1PayloadSize = 8 + effectiveSplit
        val frag1TotalLength = 20 + frag1PayloadSize
        val frag1Buf = ByteBuffer.allocate(frag1TotalLength).order(ByteOrder.BIG_ENDIAN)

        frag1Buf.put(0x45.toByte()) // Version: 4, IHL: 5 (20 bytes)
        frag1Buf.put(0x00.toByte()) // DSCP / ECN
        frag1Buf.putShort(frag1TotalLength.toShort())
        frag1Buf.putShort(ipId.toShort())
        // Flags: More Fragments (MF = 1, бит 13) | Fragment Offset: 0
        frag1Buf.putShort(0x2000.toShort())
        frag1Buf.put(64.toByte()) // TTL
        frag1Buf.put(17.toByte()) // Protocol: 17 (UDP)
        frag1Buf.putShort(0.toShort()) // Placeholder для чексуммы
        frag1Buf.put(srcIp.address)
        frag1Buf.put(dstIp.address)

        // Расчет контрольной суммы заголовка IPv4
        val frag1HeaderChecksum = calculateIpChecksum(frag1Buf.array(), 0, 20)
        frag1Buf.putShort(10, frag1HeaderChecksum.toShort())

        // Добавление UDP заголовка и первой части данных
        frag1Buf.put(udpHeader.array())
        frag1Buf.put(udpPayload, 0, effectiveSplit)

        // --- Фрагмент 2 ---
        // Содержит: Заголовок IPv4 (20 байт) + остаток полезной нагрузки (без UDP заголовка)
        val frag2PayloadSize = udpPayload.size - effectiveSplit
        val frag2TotalLength = 20 + frag2PayloadSize
        val frag2Buf = ByteBuffer.allocate(frag2TotalLength).order(ByteOrder.BIG_ENDIAN)

        frag2Buf.put(0x45.toByte())
        frag2Buf.put(0x00.toByte())
        frag2Buf.putShort(frag2TotalLength.toShort())
        frag2Buf.putShort(ipId.toShort())
        // Flags: MF = 0 | Fragment Offset: (8 + effectiveSplit) / 8
        val frag2OffsetUnits = (8 + effectiveSplit) / 8
        frag2Buf.putShort(frag2OffsetUnits.toShort())
        frag2Buf.put(64.toByte())
        frag2Buf.put(17.toByte())
        frag2Buf.putShort(0.toShort())
        frag2Buf.put(srcIp.address)
        frag2Buf.put(dstIp.address)

        val frag2HeaderChecksum = calculateIpChecksum(frag2Buf.array(), 0, 20)
        frag2Buf.putShort(10, frag2HeaderChecksum.toShort())

        // Добавление остатка полезной нагрузки
        frag2Buf.put(udpPayload, effectiveSplit, frag2PayloadSize)

        return Pair(frag1Buf.array(), frag2Buf.array())
    }

    /**
     * Разрезает UDP-пакет на 2 фрагмента IPv6 (IPv6 Fragment Extension Header, Next Header 44).
     */
    fun fragmentIpv6Udp(
        srcIp: InetAddress,
        srcPort: Int,
        dstIp: InetAddress,
        dstPort: Int,
        udpPayload: ByteArray,
        splitOffset: Int = DEFAULT_IPV4_SPLIT_OFFSET
    ): Pair<ByteArray, ByteArray> {
        require(srcIp is Inet6Address && dstIp is Inet6Address) { "IP адреса должны быть IPv6" }
        val alignedSplit = (splitOffset / 8) * 8
        val effectiveSplit = alignedSplit.coerceIn(16, (udpPayload.size - 16).coerceAtLeast(16))

        val ipId = secureRandom.nextInt()
        val totalUdpLength = 8 + udpPayload.size

        val udpHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        udpHeader.putShort(srcPort.toShort())
        udpHeader.putShort(dstPort.toShort())
        udpHeader.putShort(totalUdpLength.toShort())
        udpHeader.putShort(0.toShort())

        // Фрагмент 1
        val frag1PayloadLen = 8 + 8 + effectiveSplit // 8 bytes frag header + 8 bytes udp + split
        val frag1Buf = ByteBuffer.allocate(40 + frag1PayloadLen).order(ByteOrder.BIG_ENDIAN)
        frag1Buf.putInt(0x60000000) // Version 6, Traffic Class 0, Flow Label 0
        frag1Buf.putShort(frag1PayloadLen.toShort())
        frag1Buf.put(44.toByte()) // Next Header: Fragment Header (44)
        frag1Buf.put(64.toByte()) // Hop Limit
        frag1Buf.put(srcIp.address)
        frag1Buf.put(dstIp.address)
        // Fragment Header: Next Header (17 UDP), Reserved (0), Offset (0) | M=1 (бит 0 = 1)
        frag1Buf.put(17.toByte())
        frag1Buf.put(0.toByte())
        frag1Buf.putShort(0x0001.toShort()) // Offset 0, M=1
        frag1Buf.putInt(ipId)
        frag1Buf.put(udpHeader.array())
        frag1Buf.put(udpPayload, 0, effectiveSplit)

        // Фрагмент 2
        val frag2Remaining = udpPayload.size - effectiveSplit
        val frag2PayloadLen = 8 + frag2Remaining
        val frag2Buf = ByteBuffer.allocate(40 + frag2PayloadLen).order(ByteOrder.BIG_ENDIAN)
        frag2Buf.putInt(0x60000000)
        frag2Buf.putShort(frag2PayloadLen.toShort())
        frag2Buf.put(44.toByte())
        frag2Buf.put(64.toByte())
        frag2Buf.put(srcIp.address)
        frag2Buf.put(dstIp.address)
        // Fragment Header: Next Header (17 UDP), Reserved (0), Offset = (8 + effectiveSplit) / 8, M=0
        val offsetUnits = (8 + effectiveSplit) / 8
        val offsetAndM = (offsetUnits shl 3) and 0xFFF8 // M = 0
        frag2Buf.put(17.toByte())
        frag2Buf.put(0.toByte())
        frag2Buf.putShort(offsetAndM.toShort())
        frag2Buf.putInt(ipId)
        frag2Buf.put(udpPayload, effectiveSplit, frag2Remaining)

        return Pair(frag1Buf.array(), frag2Buf.array())
    }

    /**
     * Отправляет UDP-пакет с десинхронизацией DPI:
     * сначала отсылается маленький мусорный пакет для сбивания автомата состояний ТСПУ,
     * затем с микрозадержкой отправляется целевой пакет.
     */
    fun sendDesyncedUdp(
        socket: DatagramSocket,
        target: InetSocketAddress,
        payload: ByteArray,
        delayMs: Long = 2
    ) {
        val junk = createDesyncedJunkPrefix(48)
        val junkPacket = DatagramPacket(junk, junk.size, target)
        socket.send(junkPacket)

        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val mainPacket = DatagramPacket(payload, payload.size, target)
        socket.send(mainPacket)
    }

    /**
     * Контрольная сумма заголовка IPv4 (RFC 791).
     */
    private fun calculateIpChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
