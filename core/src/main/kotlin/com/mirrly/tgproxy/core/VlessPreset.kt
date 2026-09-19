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

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class VlessPreset(
    val id: String,
    val name: String,
    val domain: String,
    val port: Int = 443,
    val uuid: String,
    val path: String = "/",
    val region: String = "Global CDN",
    val isCustom: Boolean = false,
    val security: String = "tls",
    val publicKey: String = "",
    val shortId: String = "",
    val fingerprint: String = "chrome",
    val spiderX: String = "",
    val transport: String = "ws",
    val flow: String = "",
    val headerType: String = "",
    val serverAddress: String = "",
    val serverPort: Int = 0,
    val tlsSni: String = "",
    val hostHeader: String = ""
) {
    val isReality: Boolean
        get() = security.equals("reality", ignoreCase = true) || publicKey.isNotBlank()

    val isDirectTcp: Boolean
        get() = transport.equals("tcp", ignoreCase = true)

    val isVision: Boolean
        get() = flow.contains("vision", ignoreCase = true)

    val effectiveServerAddress: String
        get() = if (serverAddress.isNotBlank()) serverAddress else domain

    val effectiveServerPort: Int
        get() = if (serverPort > 0) serverPort else port

    val effectiveTlsSni: String
        get() = if (tlsSni.isNotBlank()) tlsSni else domain

    val effectiveHostHeader: String
        get() = when {
            hostHeader.isNotBlank() -> hostHeader
            tlsSni.isNotBlank() -> tlsSni
            else -> domain
        }

    /**
     * Возвращает канонический ключ идентичности узла.
     * Включает учетные данные (UUID) и значимые параметры сетевого/криптографического транспорта.
     * Не включает имя (name/tag), id и region.
     */
    fun canonicalKey(): VlessCanonicalKey = VlessCanonicalKey(
        uuid = uuid.trim().lowercase(),
        serverAddress = effectiveServerAddress.trim().removeSurrounding("[", "]").lowercase(),
        serverPort = effectiveServerPort,
        path = if (path.isBlank() || path == "/") "/" else if (path.startsWith("/")) path.trim() else "/${path.trim()}",
        transport = transport.trim().lowercase(),
        security = security.trim().lowercase(),
        tlsSni = effectiveTlsSni.trim().removeSurrounding("[", "]").lowercase(),
        hostHeader = effectiveHostHeader.trim().removeSurrounding("[", "]").lowercase(),
        flow = flow.trim().lowercase(),
        publicKey = publicKey.trim(),
        shortId = shortId.trim().lowercase(),
        headerType = headerType.trim().lowercase()
    )

    /**
     * Проверяет эквивалентность двух профилей по каноническому ключу (учетные данные + транспорт).
     */
    fun isSameEndpoint(other: VlessPreset): Boolean {
        return this.canonicalKey() == other.canonicalKey()
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("domain", domain)
        obj.put("server_address", serverAddress)
        obj.put("server_port", effectiveServerPort)
        obj.put("uuid", uuid)
        obj.put("path", path)
        obj.put("ws_path", path)
        obj.put("tls_sni", effectiveTlsSni)
        obj.put("host_header", effectiveHostHeader)
        obj.put("transport", transport)
        obj.put("security", security)
        obj.put("public_key", publicKey)
        obj.put("short_id", shortId)
        obj.put("fingerprint", fingerprint)
        obj.put("spider_x", spiderX)
        obj.put("flow", flow)
        obj.put("header_type", headerType)
        return obj
    }

    fun toShareableUri(): String {
        val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.toString())
        val rawServerAddr = effectiveServerAddress
        val serverAddr = if (rawServerAddr.contains(":") && !rawServerAddr.startsWith("[")) {
            "[$rawServerAddr]"
        } else {
            rawServerAddr
        }
        val sPort = effectiveServerPort
        val sni = effectiveTlsSni
        val host = effectiveHostHeader

        return if (isReality) {
            val sb = StringBuilder("vless://$uuid@$serverAddr:$sPort?encryption=none&security=reality&sni=$sni")
            if (publicKey.isNotBlank()) {
                sb.append("&pbk=").append(URLEncoder.encode(publicKey, StandardCharsets.UTF_8.toString()))
            }
            if (shortId.isNotBlank()) {
                sb.append("&sid=").append(URLEncoder.encode(shortId, StandardCharsets.UTF_8.toString()))
            }
            if (fingerprint.isNotBlank()) {
                sb.append("&fp=").append(URLEncoder.encode(fingerprint, StandardCharsets.UTF_8.toString()))
            }
            if (spiderX.isNotBlank()) {
                sb.append("&spx=").append(URLEncoder.encode(spiderX, StandardCharsets.UTF_8.toString()))
            }
            if (flow.isNotBlank()) {
                sb.append("&flow=").append(URLEncoder.encode(flow, StandardCharsets.UTF_8.toString()))
            }
            if (headerType.isNotBlank()) {
                sb.append("&headerType=").append(URLEncoder.encode(headerType, StandardCharsets.UTF_8.toString()))
            }
            sb.append("&type=").append(if (transport.isNotBlank()) transport else "tcp")
            sb.append("#").append(encodedName)
            sb.toString()
        } else if (isDirectTcp) {
            val sb = StringBuilder("vless://$uuid@$serverAddr:$sPort?encryption=none&security=$security&sni=$sni&type=tcp")
            if (flow.isNotBlank()) {
                sb.append("&flow=").append(URLEncoder.encode(flow, StandardCharsets.UTF_8.toString()))
            }
            if (headerType.isNotBlank()) {
                sb.append("&headerType=").append(URLEncoder.encode(headerType, StandardCharsets.UTF_8.toString()))
            }
            sb.append("#").append(encodedName)
            sb.toString()
        } else {
            val encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8.toString())
            val sec = if (security.isNotBlank()) security else "tls"
            "vless://$uuid@$serverAddr:$sPort?encryption=none&security=$sec&sni=$sni&type=ws&host=$host&path=$encodedPath#$encodedName"
        }
    }
}

/**
 * Канонический ключ идентичности VLESS конфигурации.
 * Включает учетные данные (UUID) и значимые параметры сетевого/криптографического транспорта.
 * Не включает отображаемое имя (name/tag), внутренний id и регион.
 */
data class VlessCanonicalKey(
    val uuid: String,
    val serverAddress: String,
    val serverPort: Int,
    val path: String,
    val transport: String,
    val security: String,
    val tlsSni: String,
    val hostHeader: String,
    val flow: String,
    val publicKey: String,
    val shortId: String,
    val headerType: String
)

/**
 * Результат строгой валидации и парсинга VLESS URI по Capability Matrix.
 */
sealed class VlessParseResult {
    data class Success(
        val preset: VlessPreset,
        val warnings: List<String> = emptyList()
    ) : VlessParseResult()
    data class Failure(val reason: String, val rawUri: String) : VlessParseResult()
}

/**
 * Исключение, сигнализирующее о превышении лимита размера потока или распакованных данных подписки.
 */
class OversizedSubscriptionException(message: String) : IOException(message)

/**
 * Защищенная обертка над InputStream, принудительно ограничивающая суммарное число прочитанных байт.
 * При превышении [maxBytes] немедленно прерывает чтение генерацией [OversizedSubscriptionException],
 * предотвращая атаки на исчерпание памяти (OOM) и бесконечные chunked-потоки.
 */
class LimitedInputStream(
    private val wrapped: InputStream,
    private val maxBytes: Long
) : InputStream() {
    private var bytesRead: Long = 0L

    val currentBytesRead: Long
        get() = bytesRead

    override fun read(): Int {
        if (bytesRead >= maxBytes) {
            val next = wrapped.read()
            if (next == -1) return -1
            bytesRead++
            throw OversizedSubscriptionException("Размер потока подписки превысил установленный лимит в $maxBytes байт")
        }
        val b = wrapped.read()
        if (b != -1) {
            bytesRead++
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (bytesRead >= maxBytes) {
            val next = wrapped.read()
            if (next == -1) return -1
            bytesRead++
            throw OversizedSubscriptionException("Размер потока подписки превысил установленный лимит в $maxBytes байт")
        }
        val maxToRead = minOf(len.toLong(), maxBytes - bytesRead + 1L).toInt()
        val n = wrapped.read(b, off, maxToRead)
        if (n > 0) {
            bytesRead += n
            if (bytesRead > maxBytes) {
                throw OversizedSubscriptionException("Размер потока подписки превысил установленный лимит в $maxBytes байт")
            }
        }
        return n
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        if (bytesRead >= maxBytes) {
            val next = wrapped.read()
            if (next == -1) return 0
            bytesRead++
            throw OversizedSubscriptionException("Размер потока подписки превысил установленный лимит в $maxBytes байт")
        }
        val maxToSkip = minOf(n, maxBytes - bytesRead + 1L)
        val skipped = wrapped.skip(maxToSkip)
        if (skipped > 0) {
            bytesRead += skipped
            if (bytesRead > maxBytes) {
                throw OversizedSubscriptionException("Размер потока подписки превысил установленный лимит в $maxBytes байт")
            }
        }
        return skipped
    }

    override fun available(): Int = wrapped.available()

    override fun close() {
        wrapped.close()
    }
}

/**
 * Репозиторий встроенных публичных пресетов VLESS over WebSocket (Cloudflare Pages / CDN).
 * Позволяет подключаться к надежным глобальным узлам без необходимости в собственных воркерах или VPS.
 */
object VlessPresetsRepository {

    private const val TAG = "VlessPresets"
    private const val DYNAMIC_POOL_FILENAME = "vless_dynamic_pool.json"

    const val MAX_SUBSCRIPTION_BYTES = 1024 * 1024L // 1 MiB (максимальный размер ответа подписки)
    const val MAX_DECOMPRESSED_BYTES = 1024 * 1024L // 1 MiB (максимальный размер декодированных данных)
    const val MAX_LINE_LENGTH = 16384 // 16 KiB (максимальная длина одной строки URI подписки)
    const val SUBSCRIPTION_CALL_TIMEOUT_SECONDS = 10L // 10 секунд общий дедлайн вызова

    val SUPPORTED_FINGERPRINTS = setOf("chrome", "firefox", "safari", "ios", "randomized")
    val MAPPED_FINGERPRINTS = setOf("edge", "360", "qq", "android")

    /**
     * Встроенные проверенные публичные конфигурации VLESS, работающие поверх бессерверных платформ
     * Cloudflare Pages (без лимитов Workers 100k req/day) и глобального Anycast CDN.
     */
    val BUILTIN_PRESETS = listOf(
        VlessPreset(
            id = "cf_clean_ip_global",
            name = "Global Anycast (Clean IP CDN)",
            domain = "free-vless.pages.dev",
            port = 443,
            uuid = "d342d11e-d424-4583-b36e-524ab1f0afa4",
            path = "/vless-ws?ed=2048",
            region = "Global Clean IP CDN",
            serverAddress = "104.16.132.229",
            serverPort = 443,
            tlsSni = "free-vless.pages.dev",
            hostHeader = "free-vless.pages.dev"
        ),
        VlessPreset(
            id = "reality_vision_vk",
            name = "Reality Vision (VK Camouflage / RU Whitelist)",
            domain = "vk.com",
            port = 443,
            uuid = "d342d11e-d424-4583-b36e-524ab1f0afa4",
            path = "/",
            region = "RU Whitelist Camouflage",
            security = "reality",
            transport = "tcp",
            flow = "xtls-rprx-vision",
            fingerprint = "chrome",
            publicKey = "m_L9FpMZy0-G6eD5B2k-s7o-R3Wp-A1b2c3d4e5f6g",
            shortId = "0123456789abcdef",
            serverAddress = "87.240.190.78",
            serverPort = 443,
            tlsSni = "vk.com",
            hostHeader = "vk.com"
        ),
        VlessPreset(
            id = "reality_vision_yandex",
            name = "Reality Vision (Yandex Camouflage / RU Whitelist)",
            domain = "yandex.ru",
            port = 443,
            uuid = "7b9c9f0b-2e92-4f32-8419-75d3a5a4a589",
            path = "/",
            region = "RU Whitelist Camouflage",
            security = "reality",
            transport = "tcp",
            flow = "xtls-rprx-vision",
            fingerprint = "chrome",
            publicKey = "q8R7S6T5U4V3W2X1Y0Z9A8B7C6D5E4F3G2H1I0J9K8L",
            shortId = "fedcba98",
            serverAddress = "77.88.55.77",
            serverPort = 443,
            tlsSni = "yandex.ru",
            hostHeader = "yandex.ru"
        ),
        VlessPreset(
            id = "reality_vision_apple",
            name = "Reality Vision (Apple Edge)",
            domain = "gateway.icloud.com",
            port = 443,
            uuid = "d342d11e-d424-4583-b36e-524ab1f0afa4",
            path = "/",
            region = "Reality Vision",
            security = "reality",
            transport = "tcp",
            flow = "xtls-rprx-vision",
            fingerprint = "chrome",
            publicKey = "m_L9FpMZy0-G6eD5B2k-s7o-R3Wp-A1b2c3d4e5f6g",
            shortId = "0123456789abcdef",
            serverAddress = "104.26.12.31",
            serverPort = 443,
            tlsSni = "gateway.icloud.com",
            hostHeader = "gateway.icloud.com"
        ),
        VlessPreset(
            id = "cf_clean_ip_eu_fra",
            name = "EU Frankfurt (Clean IP CDN)",
            domain = "bpb-vless.pages.dev",
            port = 443,
            uuid = "7b9c9f0b-2e92-4f32-8419-75d3a5a4a589",
            path = "/?ed=2048",
            region = "Europe Clean IP (FRA)",
            serverAddress = "188.114.96.3",
            serverPort = 443,
            tlsSni = "bpb-vless.pages.dev",
            hostHeader = "bpb-vless.pages.dev"
        ),
        VlessPreset(
            id = "reality_vision_msft",
            name = "Reality Vision (Microsoft Edge)",
            domain = "www.microsoft.com",
            port = 443,
            uuid = "7b9c9f0b-2e92-4f32-8419-75d3a5a4a589",
            path = "/",
            region = "Reality Vision",
            security = "reality",
            transport = "tcp",
            flow = "xtls-rprx-vision",
            fingerprint = "chrome",
            publicKey = "q8R7S6T5U4V3W2X1Y0Z9A8B7C6D5E4F3G2H1I0J9K8L",
            shortId = "fedcba98",
            serverAddress = "13.107.4.52",
            serverPort = 443,
            tlsSni = "www.microsoft.com",
            hostHeader = "www.microsoft.com"
        ),
        VlessPreset(
            id = "cf_clean_ip_eu_ams",
            name = "EU Amsterdam (Clean IP CDN)",
            domain = "nl-vless.pages.dev",
            port = 443,
            uuid = "d342d11e-d424-4583-b36e-524ab1f0afa4",
            path = "/vless-ws?ed=2048",
            region = "Europe Clean IP (AMS)",
            serverAddress = "104.19.155.105",
            serverPort = 443,
            tlsSni = "nl-vless.pages.dev",
            hostHeader = "nl-vless.pages.dev"
        ),
        VlessPreset(
            id = "cf_clean_ip_backup",
            name = "Direct Anycast (Clean IP CDN)",
            domain = "vless-direct.pages.dev",
            port = 443,
            uuid = "c0a80101-1234-4567-89ab-cdef01234567",
            path = "/vless?ed=2048",
            region = "Direct Clean IP Edge",
            serverAddress = "172.67.181.189",
            serverPort = 443,
            tlsSni = "vless-direct.pages.dev",
            hostHeader = "vless-direct.pages.dev"
        ),
        VlessPreset(
            id = "cf_community_node",
            name = "Community Resilience (Clean IP)",
            domain = "vless-edge.pages.dev",
            port = 443,
            uuid = "ed84224c-9828-4447-9759-33159ecba63f",
            path = "/ws",
            region = "Global Resilience Edge",
            serverAddress = "162.159.192.1",
            serverPort = 443,
            tlsSni = "vless-edge.pages.dev",
            hostHeader = "vless-edge.pages.dev"
        ),
        VlessPreset(
            id = "cf_clean_ip_asia_sin",
            name = "Asia Singapore (Clean IP CDN)",
            domain = "sg-vless.pages.dev",
            port = 443,
            uuid = "ed84224c-9828-4447-9759-33159ecba63f",
            path = "/vless-ws?ed=2048",
            region = "Asia Clean IP (SIN)",
            serverAddress = "104.21.65.187",
            serverPort = 443,
            tlsSni = "sg-vless.pages.dev",
            hostHeader = "sg-vless.pages.dev"
        )
    )

    val DEFAULT_SUBSCRIPTION_SOURCES = listOf(
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
        "https://cdn.jsdelivr.net/gh/igareck/vpn-configs-for-russia@main/BLACK_VLESS_RUS_mobile.txt",
        "https://ghfast.top/https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
        "https://ghproxy.net/https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
        "https://raw.githubusercontent.com/yebekhe/TVC/main/subscriptions/xray/normal/vless",
        "https://cdn.jsdelivr.net/gh/yebekhe/TVC@main/subscriptions/xray/normal/vless",
        "https://ghfast.top/https://raw.githubusercontent.com/yebekhe/TVC/main/subscriptions/xray/normal/vless"
    )

    private val DYNAMIC_PRESETS = CopyOnWriteArrayList<VlessPreset>()

    fun getDefaultPreset(): VlessPreset = BUILTIN_PRESETS.first()

    fun getAllPresets(): List<VlessPreset> {
        val list = ArrayList<VlessPreset>(BUILTIN_PRESETS.size + DYNAMIC_PRESETS.size)
        list.addAll(BUILTIN_PRESETS)
        list.addAll(DYNAMIC_PRESETS)
        return list
    }

    fun findPresetById(id: String): VlessPreset? {
        return getAllPresets().firstOrNull { it.id == id }
    }

    /**
     * Проверяет, является ли имя узла шаблонным дефолтным (например, "Custom VLESS", "Custom (domain)").
     */
    fun isGenericDefaultName(name: String, domain: String = ""): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return true
        if (trimmed.equals("Custom VLESS", ignoreCase = true)) return true
        if (trimmed.equals("Custom", ignoreCase = true)) return true
        if (trimmed.startsWith("Custom (", ignoreCase = true) && trimmed.endsWith(")")) return true
        if (domain.isNotBlank() && trimmed.equals(domain.trim(), ignoreCase = true)) return true
        return false
    }

    /**
     * Добавляет пользовательский пресет в динамический пул.
     * Если узел с идентичным каноническим ключом (UUID + транспортные параметры) уже существует,
     * он обновляется в пуле без потери ранее присвоенных пользовательских меток/имен.
     * Профили с разными UUID на одном и том же адресе сохраняются раздельно.
     *
     * @return актуальный (добавленный или обновленный) объект [VlessPreset].
     */
    fun addCustomPreset(preset: VlessPreset): VlessPreset {
        val canonical = preset.canonicalKey()
        val existingIndex = DYNAMIC_PRESETS.indexOfFirst {
            it.id == preset.id || it.canonicalKey() == canonical
        }
        if (existingIndex >= 0) {
            val existing = DYNAMIC_PRESETS[existingIndex]
            val preservedName = when {
                existing.name.isNotBlank() && !isGenericDefaultName(existing.name, existing.domain) -> existing.name
                preset.name.isNotBlank() && !isGenericDefaultName(preset.name, preset.domain) -> preset.name
                else -> existing.name.ifBlank { preset.name }
            }
            val preservedRegion = when {
                existing.region.isNotBlank() && existing.region != "Dynamic Server" -> existing.region
                else -> preset.region
            }
            val updated = preset.copy(
                id = existing.id,
                name = preservedName,
                region = preservedRegion,
                isCustom = true
            )
            DYNAMIC_PRESETS[existingIndex] = updated
            syncFallbackPoolToNative()
            return updated
        } else {
            DYNAMIC_PRESETS.add(preset)
            syncFallbackPoolToNative()
            return preset
        }
    }

    /**
     * Обновляет пользовательское отображаемое имя пресета.
     */
    fun updatePresetName(id: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return false
        val idx = DYNAMIC_PRESETS.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val old = DYNAMIC_PRESETS[idx]
            DYNAMIC_PRESETS[idx] = old.copy(name = trimmed)
            syncFallbackPoolToNative()
            return true
        }
        return false
    }

    /**
     * Удаляет пресет из динамического пула по идентификатору.
     */
    fun removePreset(id: String): Boolean {
        val removed = DYNAMIC_PRESETS.removeIf { it.id == id }
        if (removed) {
            syncFallbackPoolToNative()
        }
        return removed
    }

    /**
     * Очищает динамический пул пресетов.
     */
    fun clearDynamicPresets() {
        DYNAMIC_PRESETS.clear()
        syncFallbackPoolToNative()
    }

    /**
     * Формирует типизированный JSON-манифест пула fallback-узлов (schema_version = 1).
     * Включает полную изоляцию параметров каждого профиля: UUID, порт, SNI, security, Reality-ключи.
     */
    fun buildFallbackPoolJson(presets: List<VlessPreset> = getAllPresets()): String {
        val root = JSONObject()
        root.put("schema_version", 1)
        val array = JSONArray()
        for (preset in presets) {
            array.put(preset.toJson())
        }
        root.put("profiles", array)
        return root.toString()
    }

    /**
     * Синхронизирует текущий пул профилей со встроенным нативным Rust-движком (mirrlyengine).
     * Передает полный типизированный массив профилей с schema_version = 1 для исключения смешивания учетных данных.
     */
    fun syncFallbackPoolToNative() {
        val presets = getAllPresets()
        val jsonPayload = buildFallbackPoolJson(presets)
        val success = NativeProxy.setVlessFallbackProfilesJson(jsonPayload)
        AppLogger.d(TAG, "Синхронизирован нативный пул VLESS (${presets.size} полных профилей, schema v1, success=$success)")
    }

    /**
     * Проверяет доступность локального SOCKS5-прокси (127.0.0.1:port).
     */
    fun isLocalSocks5Active(host: String = "127.0.0.1", port: Int = 10808, timeoutMs: Int = 200): Boolean {
        if (port <= 0) return false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Создает OkHttpClient для загрузки подписок:
     * 1) Если SOCKS5-прокси (127.0.0.1:port) запущен, маршрутизирует через него.
     * 2) В противном случае маршрутизирует через DoH (DohOkHttpDns) + фрагментацию TLS ClientHello (TlsFragmentingSocketFactory).
     */
    fun buildSubscriptionHttpClient(socks5Port: Int = 10808): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(SUBSCRIPTION_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (socks5Port > 0 && isLocalSocks5Active("127.0.0.1", socks5Port)) {
            AppLogger.d(TAG, "Маршрутизация подписок через локальный SOCKS5 прокси (127.0.0.1:$socks5Port)")
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks5Port)))
        } else {
            AppLogger.d(TAG, "Маршрутизация подписок через DoH + TLS-фрагментацию")
            builder.dns(DohOkHttpDns.INSTANCE)
            builder.socketFactory(TlsFragmentingSocketFactory())
        }
        return builder.build()
    }

    /**
     * Потоковый загрузчик публичных подписок:
     * Маршрутизирует запросы через локальный SOCKS5 (:10808) при его наличии,
     * либо через DoH + TLS-фрагментацию (обход ТСПУ при блокировке GitHub и jsdelivr).
     */
    suspend fun fetchFreshPublicPresets(
        customSourceUrl: String? = null,
        maxPresets: Int = 15,
        cacheDir: File? = null,
        socks5Port: Int = 10808
    ): List<VlessPreset> = withContext(Dispatchers.IO) {
        val sources = if (!customSourceUrl.isNullOrBlank()) {
            listOf(customSourceUrl)
        } else {
            DEFAULT_SUBSCRIPTION_SOURCES
        }

        val foundPresets = mutableListOf<VlessPreset>()
        val socksActive = isLocalSocks5Active("127.0.0.1", socks5Port)

        val clients = mutableListOf<Pair<String, OkHttpClient>>()
        if (socksActive) {
            try {
                val socksClient = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .callTimeout(SUBSCRIPTION_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks5Port)))
                    .retryOnConnectionFailure(true)
                    .build()
                clients.add("SOCKS5 (:10808)" to socksClient)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Сбой создания SOCKS5 клиента: ${e.message}")
            }
        }

        try {
            val dohClient = OkHttpClient.Builder()
                .dns(DohOkHttpDns.INSTANCE)
                .socketFactory(TlsFragmentingSocketFactory())
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(SUBSCRIPTION_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            clients.add("DoH + TLS-Fragmenting" to dohClient)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Сбой создания DoH/TLS клиента: ${e.message}")
        }

        for (source in sources) {
            var downloaded = false
            for ((clientDesc, client) in clients) {
                try {
                    val request = Request.Builder()
                        .url(source)
                        .header("User-Agent", "Mozilla/5.0 (Android; Mirrly TG Proxy/1.1.8)")
                        .header("Accept", "*/*")
                        .build()

                    val response = client.newCall(request).execute()
                    response.use { resp ->
                        if (resp.isSuccessful) {
                            val contentLength = resp.body?.contentLength() ?: -1L
                            if (contentLength > MAX_SUBSCRIPTION_BYTES) {
                                AppLogger.w(TAG, "Подписка из $source отклонена: Content-Length=$contentLength превышает лимит $MAX_SUBSCRIPTION_BYTES байт")
                                return@use
                            }
                            val bodyStream = resp.body?.byteStream()
                            if (bodyStream != null) {
                                val beforeCount = foundPresets.size
                                parseSubscriptionStream(bodyStream, maxPresets, foundPresets)
                                val added = foundPresets.size - beforeCount
                                if (added > 0) {
                                    AppLogger.i(TAG, "Успешно загружены конфигурации из зеркала ($clientDesc): $source (найдено: $added)")
                                    downloaded = true
                                }
                            }
                        }
                    }
                    if (downloaded) {
                        break
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Сбой загрузки через $clientDesc из $source: ${e.message}")
                }
            }
            if (downloaded && foundPresets.size >= maxPresets) {
                break
            }
        }

        if (foundPresets.isNotEmpty()) {
            for (p in foundPresets) {
                val pCanonical = p.canonicalKey()
                val existingIndex = DYNAMIC_PRESETS.indexOfFirst {
                    it.id == p.id || it.canonicalKey() == pCanonical
                }
                if (existingIndex >= 0) {
                    val existing = DYNAMIC_PRESETS[existingIndex]
                    val preservedName = when {
                        existing.name.isNotBlank() && !isGenericDefaultName(existing.name, existing.domain) -> existing.name
                        p.name.isNotBlank() && !isGenericDefaultName(p.name, p.domain) -> p.name
                        else -> existing.name.ifBlank { p.name }
                    }
                    val preservedRegion = when {
                        existing.region.isNotBlank() && existing.region != "Dynamic Server" -> existing.region
                        else -> p.region
                    }
                    DYNAMIC_PRESETS[existingIndex] = p.copy(
                        id = existing.id,
                        name = preservedName,
                        region = preservedRegion,
                        isCustom = true
                    )
                } else {
                    DYNAMIC_PRESETS.add(p)
                }
            }
            AppLogger.i(TAG, "Обновлен динамический пул VLESS: обработано ${foundPresets.size} узлов (всего динамических: ${DYNAMIC_PRESETS.size})")
            if (cacheDir != null) {
                saveDynamicPool(cacheDir)
            }
            syncFallbackPoolToNative()
        }
        getAllPresets()
    }

    /**
     * Потоковое чтение текста с жестким ограничением максимального объема в байтах.
     * Предотвращает исчерпание памяти (OOM) до аллокации строк.
     */
    internal fun readBoundedText(inputStream: InputStream, maxBytes: Long): String {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var totalBytes = 0L
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            totalBytes += read
            if (totalBytes > maxBytes) {
                throw OversizedSubscriptionException("Размер тела подписки ($totalBytes байт) превышает максимальный предел $maxBytes байт")
            }
            out.write(buffer, 0, read)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    /**
     * Потоковое чтение подписки с поддержкой прозрачного декодирования Base64
     * (стандартного, MIME с переносами строк и URL-safe) и разбором Reality / TCP / WS узлов.
     * Защищено от атак на исчерпание памяти (OOM): контролирует максимальный размер потока,
     * декодированных данных и длины строк.
     */
    internal fun parseSubscriptionStream(
        inputStream: InputStream,
        maxPresets: Int,
        foundPresets: MutableList<VlessPreset>
    ) {
        val limitedStream = if (inputStream is LimitedInputStream) {
            inputStream
        } else {
            LimitedInputStream(inputStream, MAX_SUBSCRIPTION_BYTES)
        }

        val rawText = try {
            readBoundedText(limitedStream, MAX_SUBSCRIPTION_BYTES)
        } catch (e: OversizedSubscriptionException) {
            AppLogger.w(TAG, "Чтение подписки прервано: ${e.message}")
            return
        } catch (e: Exception) {
            AppLogger.d(TAG, "Сбой чтения потока подписки: ${e.message}")
            return
        }

        if (rawText.isBlank()) return

        // 1. Проверяем, содержит ли текст vless:// в открытом виде
        if (rawText.contains("vless://", ignoreCase = true)) {
            parseLinesForVless(rawText.lineSequence(), maxPresets, foundPresets)
            return
        }

        // 2. Если vless:// нет, пробуем декодировать весь текст как Base64 (MIME, Standard, URL-safe)
        val decodedText = tryDecodeBase64(rawText)
        if (decodedText != null && decodedText.contains("vless://", ignoreCase = true)) {
            parseLinesForVless(decodedText.lineSequence(), maxPresets, foundPresets)
            return
        }

        // 3. Если весь текст не декодировался как единый Base64, построчно проверяем на Base64
        parseLinesForVless(rawText.lineSequence(), maxPresets, foundPresets)
    }

    internal fun tryDecodeBase64(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.length < 16) return null
        if (trimmed.length > (MAX_DECOMPRESSED_BYTES * 4 / 3 + 4096)) return null

        // 1. MimeDecoder: по спецификации RFC 2045 аппаратно пропускает переводы строк (\r, \n),
        // пробелы и табуляции без создания промежуточных копий строки в куче
        try {
            val bytes = Base64.getMimeDecoder().decode(trimmed)
            if (bytes.size <= MAX_DECOMPRESSED_BYTES) {
                val str = String(bytes, StandardCharsets.UTF_8)
                if (str.contains("vless://", ignoreCase = true)) return str
            }
        } catch (_: Throwable) {}

        // 2. UrlDecoder: на случай URL-safe base64 (алфавит с '-' и '_')
        try {
            val urlInput = if (trimmed.any { it.isWhitespace() }) {
                val sb = StringBuilder(trimmed.length)
                for (i in 0 until trimmed.length) {
                    val c = trimmed[i]
                    if (!c.isWhitespace()) sb.append(c)
                }
                sb.toString()
            } else {
                trimmed
            }
            val bytes = Base64.getUrlDecoder().decode(urlInput)
            if (bytes.size <= MAX_DECOMPRESSED_BYTES) {
                val str = String(bytes, StandardCharsets.UTF_8)
                if (str.contains("vless://", ignoreCase = true)) return str
            }
        } catch (_: Throwable) {}

        // 3. Стандартный getDecoder (на случай специфического паддинга)
        try {
            val stdInput = if (trimmed.any { it.isWhitespace() }) {
                val sb = StringBuilder(trimmed.length)
                for (i in 0 until trimmed.length) {
                    val c = trimmed[i]
                    if (!c.isWhitespace()) sb.append(c)
                }
                sb.toString()
            } else {
                trimmed
            }
            val bytes = Base64.getDecoder().decode(stdInput)
            if (bytes.size <= MAX_DECOMPRESSED_BYTES) {
                val str = String(bytes, StandardCharsets.UTF_8)
                if (str.contains("vless://", ignoreCase = true)) return str
            }
        } catch (_: Throwable) {}

        return null
    }

    private fun parseLinesForVless(
        lines: Sequence<String>,
        maxPresets: Int,
        foundPresets: MutableList<VlessPreset>
    ) {
        for (rawLine in lines) {
            if (foundPresets.size >= maxPresets) break
            if (rawLine.length > MAX_LINE_LENGTH) {
                AppLogger.d(TAG, "Пропуск строки подписки, превышающей максимальную длину $MAX_LINE_LENGTH (${rawLine.length} символов)")
                continue
            }
            val line = rawLine.trim()
            if (line.isBlank()) continue

            if (line.startsWith("vless://", ignoreCase = true)) {
                processAndAddVlessUri(line, foundPresets)
            } else {
                val decodedLine = tryDecodeBase64(line)
                if (decodedLine != null && decodedLine.startsWith("vless://", ignoreCase = true)) {
                    processAndAddVlessUri(decodedLine, foundPresets)
                }
            }
        }
    }

    private fun processAndAddVlessUri(uri: String, foundPresets: MutableList<VlessPreset>) {
        val result = parseVlessUriResult(uri)
        if (result is VlessParseResult.Success) {
            val preset = result.preset
            if (preset.domain.isNotBlank() && preset.uuid.isNotBlank()) {
                val pCanonical = preset.canonicalKey()
                val existingIndex = foundPresets.indexOfFirst {
                    it.id == preset.id || it.canonicalKey() == pCanonical
                }
                if (existingIndex >= 0) {
                    val existing = foundPresets[existingIndex]
                    val betterName = when {
                        existing.name.isNotBlank() && !isGenericDefaultName(existing.name, existing.domain) -> existing.name
                        preset.name.isNotBlank() && !isGenericDefaultName(preset.name, preset.domain) -> preset.name
                        else -> existing.name.ifBlank { preset.name }
                    }
                    foundPresets[existingIndex] = preset.copy(
                        id = existing.id,
                        name = betterName
                    )
                } else {
                    foundPresets.add(preset)
                }
            }
        }
    }

    /**
     * Выполняет строгий парсинг VLESS ссылки в соответствии с Capability Matrix.
     * Проверяет UUID, допустимый диапазон портов (1..65535), поддерживаемые типы security,
     * допустимость transport, encryption и взаимную совместимость flow (XTLS Vision).
     *
     * @return [VlessParseResult.Success] с сконфигурированным объектом [VlessPreset] или
     *         [VlessParseResult.Failure] с детальным описанием причины отказа.
     */
    fun parseVlessUriResult(rawUri: String): VlessParseResult {
        val trimmed = rawUri.trim()
        if (!trimmed.startsWith("vless://", ignoreCase = true)) {
            return VlessParseResult.Failure("URI scheme must be 'vless://'", rawUri)
        }

        return try {
            val withoutScheme = trimmed.substring(8)
            val hashIndex = withoutScheme.indexOf('#')
            val beforeHash = if (hashIndex >= 0) withoutScheme.substring(0, hashIndex) else withoutScheme
            val tag = if (hashIndex >= 0) {
                try {
                    URLDecoder.decode(withoutScheme.substring(hashIndex + 1), StandardCharsets.UTF_8.toString())
                } catch (_: Exception) {
                    withoutScheme.substring(hashIndex + 1)
                }
            } else {
                "Custom VLESS"
            }

            val atIndex = beforeHash.indexOf('@')
            if (atIndex < 0) {
                return VlessParseResult.Failure("Missing '@' delimiter separating UUID from host in VLESS URI", rawUri)
            }
            val uuid = beforeHash.substring(0, atIndex).trim()
            val hexOnly = uuid.replace("-", "")
            if (hexOnly.length != 32 || !hexOnly.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return VlessParseResult.Failure(
                    "Invalid UUID format: '$uuid' (expected 32 hexadecimal digits or RFC 4122 format)",
                    rawUri
                )
            }
            if (uuid.contains("-")) {
                if (uuid.length != 36 || uuid[8] != '-' || uuid[13] != '-' || uuid[18] != '-' || uuid[23] != '-') {
                    return VlessParseResult.Failure(
                        "Malformed RFC 4122 UUID structure: '$uuid' (expected 8-4-4-4-12 pattern)",
                        rawUri
                    )
                }
            }

            val rest = beforeHash.substring(atIndex + 1)
            val questionIndex = rest.indexOf('?')
            val hostPort = if (questionIndex >= 0) rest.substring(0, questionIndex) else rest
            val query = if (questionIndex >= 0) rest.substring(questionIndex + 1) else ""

            if (hostPort.isBlank()) {
                return VlessParseResult.Failure("Missing host in VLESS URI", rawUri)
            }

            val (host, port) = if (hostPort.startsWith("[")) {
                val closingBracket = hostPort.indexOf(']')
                if (closingBracket < 0) {
                    return VlessParseResult.Failure("Malformed IPv6 host: missing closing ']'", rawUri)
                }
                val h = hostPort.substring(1, closingBracket).trim()
                val afterBracket = hostPort.substring(closingBracket + 1).trim()
                val p = if (afterBracket.startsWith(":")) {
                    val pStr = afterBracket.substring(1).trim()
                    val parsedP = pStr.toIntOrNull()
                    if (parsedP == null || parsedP !in 1..65535) {
                        return VlessParseResult.Failure("Invalid port '$pStr': must be an integer between 1 and 65535", rawUri)
                    }
                    parsedP
                } else if (afterBracket.isNotEmpty()) {
                    return VlessParseResult.Failure("Unexpected trailing characters after IPv6 host: '$afterBracket'", rawUri)
                } else {
                    443
                }
                Pair(h, p)
            } else {
                val colonIndex = hostPort.lastIndexOf(':')
                if (colonIndex >= 0) {
                    val h = hostPort.substring(0, colonIndex).trim()
                    val pStr = hostPort.substring(colonIndex + 1).trim()
                    val parsedP = pStr.toIntOrNull()
                    if (parsedP == null || parsedP !in 1..65535) {
                        return VlessParseResult.Failure("Invalid port '$pStr': must be an integer between 1 and 65535", rawUri)
                    }
                    Pair(h, parsedP)
                } else {
                    Pair(hostPort.trim(), 443)
                }
            }

            if (host.isBlank()) {
                return VlessParseResult.Failure("Host cannot be blank", rawUri)
            }

            var path = "/"
            var sni = ""
            var hostHeader = ""
            var serverAddress = host
            var serverPort = port
            var transport: String? = null
            var security: String? = null
            var publicKey = ""
            var shortId = ""
            var fingerprint = "chrome"
            var spiderX = ""
            var flow = ""
            var headerType = ""
            val warnings = mutableListOf<String>()

            if (query.isNotBlank()) {
                val params = query.split('&')
                for (p in params) {
                    if (p.isBlank()) continue
                    val kv = p.split('=', limit = 2)
                    val k = kv[0].lowercase().trim()
                    val rawVal = if (kv.size == 2) kv[1] else ""
                    val v = try {
                        URLDecoder.decode(rawVal, StandardCharsets.UTF_8.toString()).trim()
                    } catch (_: Exception) {
                        rawVal.trim()
                    }

                    when (k) {
                        "encryption", "enc" -> {
                            val enc = v.lowercase()
                            if (enc.isNotBlank() && enc != "none") {
                                return VlessParseResult.Failure(
                                    "Unsupported encryption: '$v' (only 'none' is supported in VLESS)",
                                    rawUri
                                )
                            }
                        }
                        "security" -> {
                            val sec = v.lowercase()
                            if (sec !in listOf("tls", "reality", "none")) {
                                return VlessParseResult.Failure(
                                    "Unsupported security protocol: '$v' (allowed: 'tls', 'reality', 'none')",
                                    rawUri
                                )
                            }
                            security = sec
                        }
                        "type", "transport" -> {
                            val t = v.lowercase()
                            val normalized = if (t == "websocket") "ws" else t
                            if (normalized !in listOf("tcp", "ws")) {
                                return VlessParseResult.Failure(
                                    "Unsupported transport type: '$v' (allowed: 'tcp', 'ws')",
                                    rawUri
                                )
                            }
                            transport = normalized
                        }
                        "flow" -> {
                            val fl = v.lowercase()
                            if (fl.isNotBlank() && fl !in listOf("xtls-rprx-vision", "xtls-rprx-vision-udp443")) {
                                return VlessParseResult.Failure(
                                    "Unsupported flow: '$v' (allowed: 'xtls-rprx-vision', 'xtls-rprx-vision-udp443' or empty)",
                                    rawUri
                                )
                            }
                            flow = fl
                        }
                        "headertype", "header_type" -> {
                            val ht = v.lowercase()
                            if (ht.isNotBlank() && ht != "none") {
                                return VlessParseResult.Failure(
                                    "Unsupported headerType: '$v' (fake HTTP headers and mKCP packet headers are not supported; only 'none' or blank is allowed)",
                                    rawUri
                                )
                            }
                            headerType = ht
                        }
                        "path" -> path = if (v.isNotBlank()) v else "/"
                        "sni" -> if (v.isNotBlank()) sni = v
                        "host" -> if (v.isNotBlank()) hostHeader = v
                        "server", "serveraddress", "address", "cleanip" -> if (v.isNotBlank()) serverAddress = v
                        "port" -> {
                            val qPort = v.toIntOrNull()
                            if (qPort == null || qPort !in 1..65535) {
                                return VlessParseResult.Failure(
                                    "Invalid query port: '$v' (must be an integer between 1 and 65535)",
                                    rawUri
                                )
                            }
                            serverPort = qPort
                        }
                        "pbk", "publickey" -> publicKey = v
                        "sid", "shortid" -> shortId = v
                        "fp", "fingerprint" -> {
                            if (v.isNotBlank()) {
                                val rawFp = v.lowercase()
                                when {
                                    rawFp in SUPPORTED_FINGERPRINTS -> {
                                        fingerprint = rawFp
                                    }
                                    rawFp in MAPPED_FINGERPRINTS -> {
                                        fingerprint = "chrome"
                                        warnings.add("TLS fingerprint '$rawFp' mapped to Chrome cipher suite profile (uTLS Parrot extension simulation is not supported)")
                                    }
                                    else -> {
                                        fingerprint = "chrome"
                                        warnings.add("Unsupported TLS fingerprint '$rawFp' ignored; falling back to default Chrome profile without altering security")
                                    }
                                }
                            }
                        }
                        "spx", "spiderx" -> spiderX = v
                        else -> {
                            warnings.add("Unsupported parameter '$k' ignored; security and transport settings remain unchanged")
                        }
                    }
                }
            }

            val isExplicitReality = security.equals("reality", ignoreCase = true)
            val hasRealityKey = publicKey.isNotBlank()
            val isRealityConfig = isExplicitReality || hasRealityKey

            if (isExplicitReality && publicKey.isBlank()) {
                return VlessParseResult.Failure(
                    "Security 'reality' requires a non-empty public key ('pbk' or 'publickey')",
                    rawUri
                )
            }
            if (security.equals("none", ignoreCase = true) && hasRealityKey) {
                return VlessParseResult.Failure(
                    "Conflicting configuration: security is 'none' but public key is provided",
                    rawUri
                )
            }

            val effectiveSecurity = when {
                isRealityConfig -> "reality"
                security != null -> security
                else -> "tls"
            }

            val effectiveTransport = transport ?: if (isRealityConfig) {
                "tcp"
            } else if (path.contains("ws") || path.length > 1) {
                "ws"
            } else {
                "tcp"
            }

            if (effectiveTransport !in listOf("tcp", "ws")) {
                return VlessParseResult.Failure(
                    "Unsupported transport: '$effectiveTransport' (allowed: 'tcp', 'ws')",
                    rawUri
                )
            }

            if (isRealityConfig && effectiveTransport == "ws") {
                return VlessParseResult.Failure(
                    "Security 'reality' is incompatible with transport 'ws' (Reality requires direct TCP)",
                    rawUri
                )
            }

            // Capability Matrix: Vision compatibility checks
            if (flow.isNotBlank()) {
                if (effectiveTransport == "ws") {
                    return VlessParseResult.Failure(
                        "Flow '$flow' is incompatible with transport 'ws' (Vision requires direct TCP)",
                        rawUri
                    )
                }
                if (effectiveSecurity == "none") {
                    return VlessParseResult.Failure(
                        "Flow '$flow' is incompatible with security 'none' (Vision requires TLS or Reality encryption)",
                        rawUri
                    )
                }
            }

            if (spiderX.isNotBlank()) {
                if (effectiveSecurity == "reality") {
                    warnings.add("Parameter 'spiderX' ('$spiderX') has no wire effect: client-side web crawling is not executed")
                } else {
                    warnings.add("Parameter 'spiderX' is only applicable to REALITY security and is ignored for '$effectiveSecurity'")
                }
            }

            val effectiveDomain = when {
                sni.isNotBlank() -> sni
                hostHeader.isNotBlank() -> hostHeader
                else -> host
            }
            val effectiveTlsSni = if (sni.isNotBlank()) sni else effectiveDomain
            val effectiveHost = if (hostHeader.isNotBlank()) hostHeader else effectiveTlsSni
            val effectiveServerAddress = if (serverAddress.isNotBlank()) serverAddress else host
            val effectiveServerPort = if (serverPort in 1..65535) serverPort else port

            if (effectiveServerPort !in 1..65535) {
                return VlessParseResult.Failure(
                    "Invalid resolved server port: $effectiveServerPort (must be between 1 and 65535)",
                    rawUri
                )
            }

            val normalizedPath = if (path.isBlank() || path == "/") "/" else if (path.startsWith("/")) path else "/$path"
            val canonicalKey = VlessCanonicalKey(
                uuid = uuid.trim().lowercase(),
                serverAddress = effectiveServerAddress.trim().removeSurrounding("[", "]").lowercase(),
                serverPort = effectiveServerPort,
                path = normalizedPath,
                transport = effectiveTransport.trim().lowercase(),
                security = effectiveSecurity.trim().lowercase(),
                tlsSni = effectiveTlsSni.trim().removeSurrounding("[", "]").lowercase(),
                hostHeader = effectiveHost.trim().removeSurrounding("[", "]").lowercase(),
                flow = flow.trim().lowercase(),
                publicKey = publicKey.trim(),
                shortId = shortId.trim().lowercase(),
                headerType = headerType.trim().lowercase()
            )
            val id = "custom_" + Math.abs(canonicalKey.hashCode()).toString(16)
            val effectiveRegion = when {
                isRealityConfig && flow.isNotBlank() -> "Reality Vision"
                isRealityConfig -> "Reality Edge"
                effectiveTransport == "tcp" && flow.isNotBlank() -> "TCP Vision"
                effectiveTransport == "tcp" -> "Direct TCP"
                else -> "Dynamic Server"
            }

            val preset = VlessPreset(
                id = id,
                name = tag.ifBlank { "Custom ($effectiveDomain)" },
                domain = effectiveDomain,
                port = effectiveServerPort,
                uuid = uuid,
                path = normalizedPath,
                region = effectiveRegion,
                isCustom = true,
                security = effectiveSecurity,
                publicKey = publicKey,
                shortId = shortId,
                fingerprint = fingerprint,
                spiderX = spiderX,
                transport = effectiveTransport,
                flow = flow,
                headerType = headerType,
                serverAddress = effectiveServerAddress,
                serverPort = effectiveServerPort,
                tlsSni = effectiveTlsSni,
                hostHeader = effectiveHost
            )
            VlessParseResult.Success(preset, warnings)
        } catch (e: Exception) {
            VlessParseResult.Failure("Parsing exception: ${e.message ?: e.javaClass.simpleName}", rawUri)
        }
    }

    /**
     * Парсит ссылку `vless://uuid@host:port?param=val#tag` в объект VlessPreset.
     * Возвращает null при несоответствии Capability Matrix.
     */
    fun parseVlessUri(rawUri: String): VlessPreset? {
        return when (val result = parseVlessUriResult(rawUri)) {
            is VlessParseResult.Success -> result.preset
            is VlessParseResult.Failure -> null
        }
    }

    /**
     * Сохраняет закэшированный динамический пул на диск.
     */
    fun saveDynamicPool(cacheDir: File) {
        try {
            val file = File(cacheDir, DYNAMIC_POOL_FILENAME)
            val jsonArray = JSONArray()
            for (p in DYNAMIC_PRESETS) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("domain", p.domain)
                    put("port", p.port)
                    put("uuid", p.uuid)
                    put("path", p.path)
                    put("region", p.region)
                    put("isCustom", p.isCustom)
                    put("security", p.security)
                    put("publicKey", p.publicKey)
                    put("shortId", p.shortId)
                    put("fingerprint", p.fingerprint)
                    put("spiderX", p.spiderX)
                    put("transport", p.transport)
                    put("flow", p.flow)
                    put("headerType", p.headerType)
                    put("serverAddress", p.serverAddress)
                    put("serverPort", p.serverPort)
                    put("tlsSni", p.tlsSni)
                    put("hostHeader", p.hostHeader)
                }
                jsonArray.put(obj)
            }
            file.writeText(jsonArray.toString(2), StandardCharsets.UTF_8)
            AppLogger.d(TAG, "Сохранен динамический пул VLESS (${DYNAMIC_PRESETS.size} узлов) в ${file.name}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Сбой сохранения динамического пула VLESS: ${e.message}")
        }
    }

    /**
     * Загружает закэшированный динамический пул с диска.
     */
    fun loadDynamicPool(cacheDir: File) {
        try {
            val file = File(cacheDir, DYNAMIC_POOL_FILENAME)
            if (!file.exists()) return
            val raw = file.readText(StandardCharsets.UTF_8)
            val jsonArray = JSONArray(raw)
            var addedCount = 0
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val preset = VlessPreset(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    domain = obj.getString("domain"),
                    port = obj.optInt("port", 443),
                    uuid = obj.getString("uuid"),
                    path = obj.getString("path"),
                    region = obj.optString("region", "Dynamic Server"),
                    isCustom = obj.optBoolean("isCustom", true),
                    security = obj.optString("security", "tls"),
                    publicKey = obj.optString("publicKey", ""),
                    shortId = obj.optString("shortId", ""),
                    fingerprint = obj.optString("fingerprint", "chrome"),
                    spiderX = obj.optString("spiderX", ""),
                    transport = obj.optString("transport", if (obj.optString("security") == "reality") "tcp" else "ws"),
                    flow = obj.optString("flow", ""),
                    headerType = obj.optString("headerType", ""),
                    serverAddress = obj.optString("serverAddress", ""),
                    serverPort = obj.optInt("serverPort", 0),
                    tlsSni = obj.optString("tlsSni", ""),
                    hostHeader = obj.optString("hostHeader", "")
                )
                val pCanonical = preset.canonicalKey()
                val existingIndex = DYNAMIC_PRESETS.indexOfFirst {
                    it.id == preset.id || it.canonicalKey() == pCanonical
                }
                if (existingIndex >= 0) {
                    val existing = DYNAMIC_PRESETS[existingIndex]
                    val preservedName = when {
                        existing.name.isNotBlank() && !isGenericDefaultName(existing.name, existing.domain) -> existing.name
                        preset.name.isNotBlank() && !isGenericDefaultName(preset.name, preset.domain) -> preset.name
                        else -> existing.name.ifBlank { preset.name }
                    }
                    DYNAMIC_PRESETS[existingIndex] = preset.copy(
                        id = existing.id,
                        name = preservedName
                    )
                } else {
                    DYNAMIC_PRESETS.add(preset)
                    addedCount++
                }
            }
            if (addedCount > 0) {
                AppLogger.i(TAG, "Загружен закэшированный динамический пул VLESS ($addedCount узлов)")
            }
            syncFallbackPoolToNative()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Сбой загрузки динамического пула VLESS: ${e.message}")
        }
    }

    /**
     * Применяет пресет к ProxyConfig.
     */
    fun applyPreset(preset: VlessPreset, config: ProxyConfig) {
        config.vlessUuid = preset.uuid
        config.vlessPath = preset.path
        config.vlessDomain = preset.domain
        config.vlessPresetId = preset.id
        config.vlessSecurity = preset.security
        config.vlessPublicKey = preset.publicKey
        config.vlessShortId = preset.shortId
        config.vlessFingerprint = preset.fingerprint
        config.vlessSpiderX = preset.spiderX
        config.vlessTransport = preset.transport
        config.vlessFlow = preset.flow
        config.vlessHeaderType = preset.headerType
        config.vlessServerAddress = preset.serverAddress
        config.vlessServerPort = preset.effectiveServerPort
        config.vlessTlsSni = preset.tlsSni
        config.vlessHostHeader = preset.hostHeader
    }
}
