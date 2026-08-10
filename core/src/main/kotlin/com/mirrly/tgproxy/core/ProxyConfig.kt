package com.mirrly.tgproxy.core

enum class SpeedPreset(val displayName: String, val defaultPoolSize: Int, val defaultBufferSizeBytes: Int) {
    ECO("Эко (2 сокета)", 2, 32768),
    BALANCED("Баланс (8 сокетов)", 8, 262144),
    TURBO("Турбо (16 сокетов)", 16, 2097152)
}

/**
 * Режим работы прокси.
 * MTPROTO — нативный MTProto движок (чаты, медиа, файлы).
 * SOCKS5  — прозрачный TCP relay (чаты + звонки через SOCKS5).
 */
enum class ProxyMode {
    MTPROTO,
    SOCKS5
}

data class ProxyConfig(
    var bindHost: String = "127.0.0.1",
    var bindPort: Int = 1443,
    var secretHex: String = "ee000000000000000000000000000000",
    var cfProxyEnabled: Boolean = true,
    var customCfDomain: String = "",
    var poolSize: Int = 8, // 8 pre-warmed sockets per DC for instant 0ms response
    var isDcAuto: Boolean = true,
    var autostartOnBoot: Boolean = false,
    var verboseLogs: Boolean = true,
    var fallbackDirectTcp: Boolean = true,
    var isTestEnvironment: Boolean = false,
    var speedPresetName: String = SpeedPreset.BALANCED.name,
    var tcpNoDelay: Boolean = true,
    var bufferSizeBytes: Int = 131072, // 128KB default buffer
    var socks5Port: Int = 10808,
    // Раздельные флаги вызова встроенного (временно поднятого) воркера
    var useDefaultWorkerSocks5: Boolean = true,
    var useDefaultWorkerMtproto: Boolean = false,
    // proxyModeName — единый источник истины (MTPROTO или SOCKS5)
    var proxyModeName: String = ProxyMode.MTPROTO.name,
    // Оставляем для обратной совместимости с PreferencesManager
    @Deprecated("Используй proxyMode") var socks5Enabled: Boolean = false,
    @Deprecated("Более не используется") var dualPortMode: Boolean = false
) {
    val speedPreset: SpeedPreset
        get() = try { SpeedPreset.valueOf(speedPresetName) } catch (_: Exception) { SpeedPreset.BALANCED }

    /** Текущий режим прокси. Единый источник истины. */
    val proxyMode: ProxyMode
        get() = try { ProxyMode.valueOf(proxyModeName) } catch (_: Exception) { ProxyMode.MTPROTO }

    /** Короткий computed helper — true если включён режим SOCKS5. */
    val isSocks5Mode: Boolean
        get() = proxyMode == ProxyMode.SOCKS5

    /** Порт, который сейчас активен (зависит от режима). */
    val activePort: Int
        get() = if (isSocks5Mode) socks5Port else bindPort

    fun applyPreset(preset: SpeedPreset) {
        speedPresetName = preset.name
        poolSize = preset.defaultPoolSize
        bufferSizeBytes = preset.defaultBufferSizeBytes
    }

    fun getEffectiveCfDomain(): String {
        val userDomain = customCfDomain.trim()
        if (userDomain.isNotEmpty()) {
            return userDomain
        }
        return if (isSocks5Mode) {
            if (useDefaultWorkerSocks5) "mirrly-tg-proxy-worker.brawny-singer.workers.dev" else ""
        } else {
            if (useDefaultWorkerMtproto) "mirrly-tg-proxy-worker.brawny-singer.workers.dev" else ""
        }
    }

    val rawSecret32: String
        get() {
            var clean = secretHex.trim().lowercase()
            if (clean.startsWith("0x")) {
                clean = clean.substring(2)
            }
            if (clean.length >= 34 && (clean.startsWith("dd") || clean.startsWith("ee"))) {
                clean = clean.substring(2)
            }
            clean = clean.filter { it in '0'..'9' || it in 'a'..'f' }
            return if (clean.length >= 32) {
                clean.take(32)
            } else {
                clean.padStart(32, '0')
            }
        }

    val secretBytes: ByteArray
        get() = hexToBytes(rawSecret32)

    companion object {
        fun hexToBytes(hex: String): ByteArray {
            val cleanHex = hex.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.lowercase()
            val padded = if (cleanHex.length % 2 != 0) "0$cleanHex" else cleanHex
            val data = ByteArray(padded.length / 2)
            for (i in padded.indices step 2) {
                val high = Character.digit(padded[i], 16)
                val low = Character.digit(padded[i + 1], 16)
                data[i / 2] = ((high shl 4) or low).toByte()
            }
            return data
        }

        fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder()
            for (b in bytes) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        }

        fun generateRandomSecret(): String {
            val randomBytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(randomBytes)
            return bytesToHex(randomBytes)
        }
    }
}
