/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service.vpn

import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.service.PreferencesManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnSecretsAndDiagnosticsTest {

    @Test
    fun testRedactedDiagnosticReportMasksSensitiveData() {
        val rawPrivateKey = "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v="
        val rawToken = "cf_super_secret_bearer_token_xyz9876543210"
        val rawPassword = "UltraSecurePassword123!"
        val rawVlessUuid = "e7b1a2c3-4d5e-6f7a-8b9c-0d1e2f3a4b5c"
        val rawLicenseKey = "12345678-abcd-ef01-2345-6789abcdef01"

        val config = ProxyConfig(
            warpAccountId = "test_account_12345678",
            warpToken = rawToken,
            warpPrivateKey = rawPrivateKey,
            warpLicenseKey = rawLicenseKey,
            vlessUuid = rawVlessUuid,
            socks5Password = rawPassword
        )

        val reportJson = PreferencesManager.getRedactedDiagnosticReport(config)

        // Raw secrets must NEVER be present anywhere in the diagnostic JSON text
        assertFalse("Raw private key leaked in diagnostic report", reportJson.contains(rawPrivateKey))
        assertFalse("Raw token leaked in diagnostic report", reportJson.contains(rawToken))
        assertFalse("Raw socks5 password leaked in diagnostic report", reportJson.contains(rawPassword))
        assertFalse("Raw full VLESS UUID leaked in diagnostic report", reportJson.contains(rawVlessUuid))
        assertFalse("Raw full WARP license key leaked in diagnostic report", reportJson.contains(rawLicenseKey))

        val json = JSONObject(reportJson)
        assertEquals(2, json.getInt("schema_version"))
        assertEquals("***REDACTED***", json.getString("socks5_password_redacted"))
        assertEquals("***REDACTED_KEY***", json.getString("warp_private_key_redacted"))
        assertTrue(json.getBoolean("warp_has_token"))
        assertTrue(json.getBoolean("warp_has_private_key"))

        // Masked abbreviations
        val maskedVlessUuid = json.getString("vless_uuid_redacted")
        assertTrue(maskedVlessUuid.startsWith("e7b1..."))
        assertTrue(maskedVlessUuid.endsWith("4b5c"))

        val maskedLicenseKey = json.getString("warp_license_key_redacted")
        assertTrue(maskedLicenseKey.startsWith("1234..."))
        assertTrue(maskedLicenseKey.endsWith("ef01"))

        // VPN telemetry fields presence
        assertTrue(json.has("vpn_internal_state"))
        assertTrue(json.has("vpn_failure_reason"))
        assertTrue(json.has("vpn_generation"))
        assertTrue(json.has("vpn_bytes_in"))
        assertTrue(json.has("vpn_bytes_out"))
    }

    @Test
    fun testVpnTunManagerConstantsAndSplitConfig() {
        assertEquals(1280, VpnTunManager.VPN_MTU)

        val defaultConfig = VpnTunManager.SplitTunnelConfig()
        assertFalse(defaultConfig.isEnabled)
        assertFalse(defaultConfig.isAllowlist)
        assertTrue(defaultConfig.packageNames.isEmpty())

        val customConfig = VpnTunManager.SplitTunnelConfig(
            isEnabled = true,
            isAllowlist = true,
            packageNames = setOf("org.telegram.messenger", "org.thunderdog.challegram")
        )
        assertTrue(customConfig.isEnabled)
        assertTrue(customConfig.isAllowlist)
        assertEquals(2, customConfig.packageNames.size)
        assertTrue(customConfig.packageNames.contains("org.telegram.messenger"))
    }
}
