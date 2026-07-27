package com.mirrly.tgproxy.core

object TgConstants {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    val PROTO_TAG_ABRIDGED = byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())
    val PROTO_TAG_INTERMEDIATE = byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())
    val PROTO_TAG_SECURE = byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())

    val PROTO_ABRIDGED_INT = 0xEFEFEFEFU.toInt()
    val PROTO_INTERMEDIATE_INT = 0xEEEEEEEEU.toInt()
    val PROTO_PADDED_INTERMEDIATE_INT = 0xDDDDDDDDU.toInt()

    val RESERVED_FIRST_BYTES = setOf(0xEF.toByte())
    val RESERVED_CONTINUE = byteArrayOf(0, 0, 0, 0)

    val DC_DEFAULT_IPS = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "91.108.56.130",
        203 to "91.105.192.100"
    )

    val DC_TEST_IPS = mapOf(
        1 to "149.154.175.10",
        2 to "149.154.167.40",
        3 to "149.154.175.117"
    )

    val NAMED_GATEWAYS = mapOf(
        1 to "pluto.web.telegram.org",
        2 to "venus.web.telegram.org",
        3 to "aurora.web.telegram.org",
        4 to "vesta.web.telegram.org",
        5 to "flora.web.telegram.org"
    )

    const val WS_PATH = "/apiws"
    const val WS_PATH_TEST = "/apiws_test"

    fun getWsDomains(dc: Int, isMedia: Boolean?): List<String> {
        val targetDc = if (dc == 203) 2 else dc
        val named = NAMED_GATEWAYS[targetDc] ?: "venus.web.telegram.org"
        return if (isMedia == true) {
            listOf("kws$targetDc-1.web.telegram.org", "kws$targetDc.web.telegram.org", named)
        } else {
            listOf("kws$targetDc.web.telegram.org", "kws$targetDc-1.web.telegram.org", named)
        }
    }
}
