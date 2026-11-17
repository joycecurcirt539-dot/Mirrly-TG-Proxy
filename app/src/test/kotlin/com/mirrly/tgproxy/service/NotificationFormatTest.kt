/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationFormatTest {

    private fun sanitizeVpnProfileName(name: String, isAuto: Boolean): String {
        var clean = name.replace(Regex("""\s*\((?:AmneziaWG|AWG|Clash|WireGuard)\)""", RegexOption.IGNORE_CASE), "").trim()
        clean = clean.replace(Regex("""^Авто\s*\((.*)\)$"""), "$1").trim()
        return if (isAuto) "Автовыбор — $clean" else clean
    }

    private fun formatVpnNotificationTitle(profileName: String): String {
        val clean = profileName.trim()
        return when {
            clean.startsWith("Mirrly VPN • ") -> clean
            clean.isNotBlank() -> "Mirrly VPN • $clean"
            else -> "Mirrly VPN"
        }
    }

    @Test
    fun testVpnNotificationTitleAutoSelection() {
        val raw = "По умолчанию"
        val clean = sanitizeVpnProfileName(raw, isAuto = true)
        val title = formatVpnNotificationTitle(clean)

        assertEquals("Автовыбор — По умолчанию", clean)
        assertEquals("Mirrly VPN • Автовыбор — По умолчанию", title)
        assertFalse(title.contains("AmneziaWG"))
        assertFalse(title.contains("Авто (По умолчанию)"))
    }

    @Test
    fun testVpnNotificationTitleAutoSelectionCleansOldFormat() {
        val rawWithFormat = "Авто (По умолчанию) (AmneziaWG)"
        val clean = sanitizeVpnProfileName(rawWithFormat, isAuto = true)
        val title = formatVpnNotificationTitle(clean)

        assertEquals("Автовыбор — По умолчанию", clean)
        assertEquals("Mirrly VPN • Автовыбор — По умолчанию", title)
        assertFalse(title.contains("AmneziaWG"))
        assertFalse(title.contains("Авто (По умолчанию)"))
    }

    @Test
    fun testVpnNotificationTitleManualBuiltinServer() {
        val raw = "Нидерланды (AmneziaWG)"
        val clean = sanitizeVpnProfileName(raw, isAuto = false)
        val title = formatVpnNotificationTitle(clean)

        assertEquals("Нидерланды", clean)
        assertEquals("Mirrly VPN • Нидерланды", title)
        assertFalse(title.contains("AmneziaWG"))
        assertFalse(title.contains("Автовыбор"))
    }

    @Test
    fun testVpnNotificationTitleCustomProfile() {
        val raw = "My Custom Server (Clash)"
        val clean = sanitizeVpnProfileName(raw, isAuto = false)
        val title = formatVpnNotificationTitle(clean)

        assertEquals("My Custom Server", clean)
        assertEquals("Mirrly VPN • My Custom Server", title)
        assertFalse(title.contains("Clash"))
    }

    @Test
    fun testProxyNotificationTitleCleanFormatting() {
        val protoSocks5 = "SOCKS5"
        val protoMtproto = "MTProto"

        val titleSocks5 = "Mirrly Proxy • $protoSocks5"
        val titleMtproto = "Mirrly Proxy • $protoMtproto"

        assertEquals("Mirrly Proxy • SOCKS5", titleSocks5)
        assertEquals("Mirrly Proxy • MTProto", titleMtproto)
        assertFalse(titleSocks5.contains("[SOCKS5]"))
        assertFalse(titleSocks5.contains("Активен"))
    }

    @Test
    fun testProxyNotificationBodyCleanFormatting() {
        val uplink = "Cloudflare Worker"
        val ping = "128 мс"
        val speed = "↓ 7,7 KB/с  ↑ 2,1 KB/с"

        val body = "$uplink • $ping • $speed"

        assertEquals("Cloudflare Worker • 128 мс • ↓ 7,7 KB/с  ↑ 2,1 KB/с", body)
        assertFalse(body.contains("±"))
        assertFalse(body.contains("Wi-Fi"))
        assertFalse(body.contains("WSS:"))
    }

    @Test
    fun testProxyNotificationAnycastUplinkFormatting() {
        val uplink = "Anycast CDN"
        val ping = "45 мс"
        val speed = "↓ 12,4 KB/с  ↑ 3,8 KB/с"

        val body = "$uplink • $ping • $speed"

        assertEquals("Anycast CDN • 45 мс • ↓ 12,4 KB/с  ↑ 3,8 KB/с", body)
        assertFalse(body.contains("Flowseal"))
    }
}
