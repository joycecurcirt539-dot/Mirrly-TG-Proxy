package com.mirrly.tgproxy.core

enum class SpeedPreset(val displayName: String, val defaultPoolSize: Int, val defaultBufferSizeBytes: Int) {
    ECO("Эко (2 сокета)", 2, 131072),
    BALANCED("Баланс (4 сокета)", 4, 262144),
    TURBO("Турбо (8 сокетов)", 8, 1048576),
    ULTRA("Ультра (16 сокетов)", 16, 2097152),
    AUTO("Авто (динамический)", 4, 262144)
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

/**
 * Режим управления алгоритмом Нагла (TCP_NODELAY).
 * AUTO — автоматическая адаптация по скорости (>= 50 Мбит/с) и пингу (<= 140 мс).
 * ON   — принудительно включено (мгновенная отдача пакетов во всех сетях).
 * OFF  — принудительно выключено (склеивание пакетов алгоритмом Нагла).
 */
enum class TcpNoDelayMode(val displayName: String) {
    AUTO("Авто"),
    ON("ВКЛ"),
    OFF("ВЫКЛ")
}

data class ProxyConfig(
    var bindHost: String = "127.0.0.1",
    var bindPort: Int = 1443,
    var secretHex: String = "dd00000000000000000000000000000000",
    var cfProxyEnabled: Boolean = true,
    var customCfDomain: String = "",
    var poolSize: Int = 4, // 4 pre-warmed sockets per DC for fast response with low battery impact
    var isDcAuto: Boolean = true,
    var autostartOnBoot: Boolean = true,
    var verboseLogs: Boolean = true,
    var isTestEnvironment: Boolean = false,
    var speedPresetName: String = SpeedPreset.AUTO.name,
    var tcpNoDelayModeName: String = TcpNoDelayMode.AUTO.name,
    var tcpNoDelay: Boolean = true,
    var bufferSizeBytes: Int = 262144, // 256KB default buffer
    var socks5Port: Int = 10808,
    var socks5Username: String = "",
    var socks5Password: String = "",
    var useDefaultWorkerSocks5: Boolean = true,
    var isBatteryGuardEnabled: Boolean = false,
    var batteryGuardThreshold: Int = 15,
    var batteryGuardStopOnPowerSave: Boolean = true,
    // proxyModeName — единый источник истины (MTPROTO или SOCKS5)
    var proxyModeName: String = ProxyMode.MTPROTO.name
) {
    val speedPreset: SpeedPreset
        get() = try { SpeedPreset.valueOf(speedPresetName) } catch (_: Exception) { SpeedPreset.AUTO }

    val isAutoSpeedPreset: Boolean
        get() = speedPreset == SpeedPreset.AUTO

    val tcpNoDelayMode: TcpNoDelayMode
        get() = try { TcpNoDelayMode.valueOf(tcpNoDelayModeName) } catch (_: Exception) { TcpNoDelayMode.AUTO }

    /** Текущий режим прокси. Единый источник истины. */
    val proxyMode: ProxyMode
        get() = try { ProxyMode.valueOf(proxyModeName) } catch (_: Exception) { ProxyMode.MTPROTO }

    /** Короткий computed helper — true если включён режим SOCKS5. */
    val isSocks5Mode: Boolean
        get() = proxyMode == ProxyMode.SOCKS5

    /** True, если для SOCKS5 настроена аутентификация (логин или пароль). */
    val hasSocks5Auth: Boolean
        get() = socks5Username.isNotBlank() || socks5Password.isNotBlank()

    /** Порт, который сейчас активен (зависит от режима). */
    val activePort: Int
        get() = if (isSocks5Mode) socks5Port else bindPort

    fun applyPreset(preset: SpeedPreset) {
        speedPresetName = preset.name
        if (preset != SpeedPreset.AUTO) {
            poolSize = preset.defaultPoolSize
            bufferSizeBytes = preset.defaultBufferSizeBytes
        }
    }

    /** Возвращает эффективный домен Cloudflare Worker для режима SOCKS5:
     *  - Используется пользовательский воркер (100% приоритет) или дефолтный узел разработчика.
     */
    fun getEffectiveCfDomain(): String {
        val userDomain = sanitizeDomain(customCfDomain)
        if (userDomain.isNotEmpty()) return userDomain
        return TgConstants.DEFAULT_SOCKS5_DEV_WORKER
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
        fun sanitizeDomain(input: String): String {
            return WorkerDomainNormalizer.sanitizeDomain(input)
        }

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
            return "dd" + bytesToHex(randomBytes)
        }

        fun generateRandomSocks5Credentials(): Pair<String, String> {
            val randomBytes = ByteArray(8)
            java.security.SecureRandom().nextBytes(randomBytes)
            val hex = bytesToHex(randomBytes)
            val user = "mirrly_" + hex.take(6)
            val pass = hex.substring(6)
            return Pair(user, pass)
        }
    }
}
