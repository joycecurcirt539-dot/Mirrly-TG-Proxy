package com.mirrly.tgproxy.core

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
    var isTestEnvironment: Boolean = false
) {
    fun getEffectiveCfDomain(): String {
        return customCfDomain.trim()
    }

    val rawSecret32: String
        get() {
            val clean = secretHex.trim().lowercase().removePrefix("dd")
            return if (clean.length >= 32) {
                clean.takeLast(32)
            } else {
                clean.padStart(32, '0')
            }
        }

    val secretBytes: ByteArray
        get() = hexToBytes(rawSecret32)

    companion object {
        fun hexToBytes(hex: String): ByteArray {
            val cleanHex = hex.trim().lowercase()
            val len = cleanHex.length
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) +
                        Character.digit(cleanHex[i + 1], 16)).toByte()
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
