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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Комплексный набор тестов для WorkerDomainNormalizer, проверяющий все 20+ вариантов
 * некорректного, замусоренного, смешанного и ошибочного пользовательского ввода.
 */
class WorkerDomainNormalizerTest {

    @Test
    fun test01_CleanDomainDirectInput() {
        val res = WorkerDomainNormalizer.normalize("my-proxy.subdomain.workers.dev")
        assertEquals(DomainFormatStatus.VALID, res.status)
        assertEquals("my-proxy.subdomain.workers.dev", res.cleanDomain)
        assertEquals("My Proxy", res.suggestedName)
        assertTrue(res.isValid)
    }

    @Test
    fun test02_HttpsAndPortAndPathStripping() {
        val res = WorkerDomainNormalizer.normalize("https://my-proxy.username.workers.dev:443/apiws?foo=bar#section")
        assertEquals(DomainFormatStatus.VALID, res.status)
        assertEquals("my-proxy.username.workers.dev", res.cleanDomain)
        assertTrue(res.isValid)
    }

    @Test
    fun test03_WssSchemeAndTrailingSlash() {
        val res = WorkerDomainNormalizer.normalize("wss://home-worker.test.workers.dev/")
        assertEquals(DomainFormatStatus.VALID, res.status)
        assertEquals("home-worker.test.workers.dev", res.cleanDomain)
        assertEquals("Home Worker", res.suggestedName)
        assertTrue(res.isValid)
    }

    @Test
    fun test04_QuotesAndBrackets() {
        val res1 = WorkerDomainNormalizer.normalize("\"my-proxy.workers.dev\"")
        assertEquals("my-proxy.workers.dev", res1.cleanDomain)

        val res2 = WorkerDomainNormalizer.normalize("«my-proxy.workers.dev»")
        assertEquals("my-proxy.workers.dev", res2.cleanDomain)

        val res3 = WorkerDomainNormalizer.normalize("(https://my-proxy.workers.dev/ws)")
        assertEquals("my-proxy.workers.dev", res3.cleanDomain)

        val res4 = WorkerDomainNormalizer.normalize("[https://my-proxy.workers.dev]")
        assertEquals("my-proxy.workers.dev", res4.cleanDomain)

        val res5 = WorkerDomainNormalizer.normalize("<my-proxy.workers.dev>")
        assertEquals("my-proxy.workers.dev", res5.cleanDomain)
    }

    @Test
    fun test05_BackticksAndMarkdownFormatting() {
        val res1 = WorkerDomainNormalizer.normalize("`my-worker.subdomain.workers.dev`")
        assertEquals("my-worker.subdomain.workers.dev", res1.cleanDomain)
        assertEquals(DomainFormatStatus.VALID, res1.status)

        val res2 = WorkerDomainNormalizer.normalize("[Мой прокси](https://my-worker.subdomain.workers.dev)")
        assertEquals("my-worker.subdomain.workers.dev", res2.cleanDomain)
    }

    @Test
    fun test06_TextPrefixesAndLabels() {
        val res1 = WorkerDomainNormalizer.normalize("Домен: my-proxy.subdomain.workers.dev")
        assertEquals("my-proxy.subdomain.workers.dev", res1.cleanDomain)

        val res2 = WorkerDomainNormalizer.normalize("url = https://my-proxy.subdomain.workers.dev")
        assertEquals("my-proxy.subdomain.workers.dev", res2.cleanDomain)

        val res3 = WorkerDomainNormalizer.normalize("host: my-proxy.subdomain.workers.dev")
        assertEquals("my-proxy.subdomain.workers.dev", res3.cleanDomain)

        val res4 = WorkerDomainNormalizer.normalize("Адрес = my-proxy.subdomain.workers.dev")
        assertEquals("my-proxy.subdomain.workers.dev", res4.cleanDomain)
    }

    @Test
    fun test07_CloudflareDashboardUrlWorkerView() {
        val res = WorkerDomainNormalizer.normalize("https://dash.cloudflare.com/e7a4b8c9d0e1f2/workers-and-pages/view/my-tg-proxy")
        assertEquals(DomainFormatStatus.DASHBOARD_URL, res.status)
        assertEquals("my-tg-proxy", res.extractedDashboardWorkerName)
        assertEquals("my-tg-proxy.workers.dev", res.cleanDomain)
        assertFalse(res.isValid)
        assertTrue(res.userMessage.contains("Deployments"))
    }

    @Test
    fun test08_CloudflareDashboardServiceUrl() {
        val res = WorkerDomainNormalizer.normalize("https://dash.cloudflare.com/00000000000000000000000000000000/workers/services/view/fast-proxy/production")
        assertEquals(DomainFormatStatus.DASHBOARD_URL, res.status)
        assertEquals("fast-proxy", res.extractedDashboardWorkerName)
        assertEquals("fast-proxy.workers.dev", res.cleanDomain)
        assertFalse(res.isValid)
    }

    @Test
    fun test09_NameOnlyWithoutDot() {
        val res = WorkerDomainNormalizer.normalize("my-custom-proxy")
        assertEquals(DomainFormatStatus.NAME_ONLY, res.status)
        assertEquals("my-custom-proxy.workers.dev", res.cleanDomain)
        assertEquals("my-custom-proxy", res.suggestedName)
        assertFalse(res.isValid)
        assertTrue(res.userMessage.contains("Указано только имя"))
    }

    @Test
    fun test10_CyrillicHomoglyphsFixed() {
        // Русские 'о' (U+043E) и 'а' (U+0430) в домене
        val rawWithRussianOandA = "my-w\u043Erker.subdom\u0430in.workers.dev"
        val res = WorkerDomainNormalizer.normalize(rawWithRussianOandA)
        assertEquals(DomainFormatStatus.HOMOGLYPHS_FIXED, res.status)
        assertEquals("my-worker.subdomain.workers.dev", res.cleanDomain)
        assertTrue(res.isValid)
    }

    @Test
    fun test11_CyrillicHomoglyphsAllSupportedCharacters() {
        // Проверка замены полного набора букв-двойников: а, о, е, р, с, х, у, i, к, м, в, т
        val (fixed, modified) = WorkerDomainNormalizer.fixHomoglyphs("аоерсхуікмвт")
        assertTrue(modified)
        assertEquals("aoepcxyikmbt", fixed)
    }

    @Test
    fun test12_ZeroWidthAndNonBreakingSpaces() {
        val rawWithTrashSpaces = "\uFEFF\u200Bhttps://my-proxy.subdomain.workers.dev:443\u200D\u00A0"
        val res = WorkerDomainNormalizer.normalize(rawWithTrashSpaces)
        assertEquals("my-proxy.subdomain.workers.dev", res.cleanDomain)
        assertEquals(DomainFormatStatus.VALID, res.status)
        assertTrue(res.isValid)
    }

    @Test
    fun test13_WranglerDeployLogSnippet() {
        val logSnippet = "✨ Success! Deployed to https://my-worker.account.workers.dev in 1.45s"
        val res = WorkerDomainNormalizer.normalize(logSnippet)
        assertEquals("my-worker.account.workers.dev", res.cleanDomain)
        assertEquals(DomainFormatStatus.VALID, res.status)
        assertTrue(res.isValid)
    }

    @Test
    fun test14_DeepLinkExtraction() {
        val deepLink = "https://mirrly.app/worker?domain=tg-fast.sub.workers.dev&name=Super+Fast"
        val res = WorkerDomainNormalizer.normalize(deepLink)
        assertEquals("tg-fast.sub.workers.dev", res.cleanDomain)
        assertEquals("Super Fast", res.suggestedName)
        assertEquals(DomainFormatStatus.VALID, res.status)
    }

    @Test
    fun test15_TelegramSocksDeepLinkExtraction() {
        val tgLink = "tg://socks?server=tg-worker.subdomain.workers.dev&port=443&user=admin"
        val res = WorkerDomainNormalizer.normalize(tgLink)
        assertEquals("tg-worker.subdomain.workers.dev", res.cleanDomain)
        assertEquals(DomainFormatStatus.VALID, res.status)
        assertTrue(res.isValid)
    }

    @Test
    fun test16_TelegramWebProxyLinkExtraction() {
        val tmeLink = "https://t.me/socks?server=tg-vpn.subdomain.workers.dev&port=443"
        val res = WorkerDomainNormalizer.normalize(tmeLink)
        assertEquals("tg-vpn.subdomain.workers.dev", res.cleanDomain)
        assertEquals(DomainFormatStatus.VALID, res.status)
        assertTrue(res.isValid)
    }

    @Test
    fun test17_CustomDomainWithPortAndPath() {
        val res = WorkerDomainNormalizer.normalize("https://proxy.custom-site.com:8443/ws")
        assertEquals("proxy.custom-site.com", res.cleanDomain)
        assertEquals(DomainFormatStatus.VALID, res.status)
    }

    @Test
    fun test18_EmptyAndWhitespaceInput() {
        val res = WorkerDomainNormalizer.normalize("   \n\t  ")
        assertEquals(DomainFormatStatus.EMPTY, res.status)
        assertEquals("", res.cleanDomain)
        assertFalse(res.isValid)
    }

    @Test
    fun test19_InvalidLoopbackAndLocalIps() {
        val res1 = WorkerDomainNormalizer.normalize("http://127.0.0.1:8080")
        assertEquals(DomainFormatStatus.INVALID, res1.status)
        assertFalse(res1.isValid)

        val res2 = WorkerDomainNormalizer.normalize("0.0.0.0")
        assertEquals(DomainFormatStatus.INVALID, res2.status)
        assertFalse(res2.isValid)
    }

    @Test
    fun test20_FormNormalizationSwappedFields() {
        // Юзер вставил домен в поле названия, а поле домена пустое
        val formRes = WorkerDomainNormalizer.normalizeForm(
            nameInput = "https://my-proxy.subdomain.workers.dev/apiws",
            domainInput = ""
        )
        assertTrue(formRes.wasSwapped)
        assertEquals("my-proxy.subdomain.workers.dev", formRes.normalizedDomain)
        assertEquals("My Proxy", formRes.normalizedName)
    }

    @Test
    fun test21_FormNormalizationSwappedFieldsWithCustomNameInDomainField() {
        // Юзер перепутал поля: ввел ссылку в Название, а имя "Домашний" ввел в поле Домен
        val formRes = WorkerDomainNormalizer.normalizeForm(
            nameInput = "my-proxy.subdomain.workers.dev",
            domainInput = "Домашний прокси"
        )
        assertTrue(formRes.wasSwapped)
        assertEquals("my-proxy.subdomain.workers.dev", formRes.normalizedDomain)
        assertEquals("Домашний прокси", formRes.normalizedName)
    }

    @Test
    fun test22_FormNormalizationStandardFields() {
        val formRes = WorkerDomainNormalizer.normalizeForm(
            nameInput = "Офисный узел",
            domainInput = "office.company.workers.dev"
        )
        assertFalse(formRes.wasSwapped)
        assertEquals("office.company.workers.dev", formRes.normalizedDomain)
        assertEquals("Офисный узел", formRes.normalizedName)
    }

    @Test
    fun test23_IsLikelyDomainHelper() {
        assertTrue(WorkerDomainNormalizer.isLikelyDomain("https://my-worker.workers.dev"))
        assertTrue(WorkerDomainNormalizer.isLikelyDomain("my-worker.workers.dev"))
        assertTrue(WorkerDomainNormalizer.isLikelyDomain("proxy.site.org"))
        assertTrue(WorkerDomainNormalizer.isLikelyDomain("dash.cloudflare.com/workers"))
        assertTrue(WorkerDomainNormalizer.isLikelyDomain("tg://socks?server=worker.workers.dev"))

        assertFalse(WorkerDomainNormalizer.isLikelyDomain("Мой любимый воркер"))
        assertFalse(WorkerDomainNormalizer.isLikelyDomain("home server"))
        assertFalse(WorkerDomainNormalizer.isLikelyDomain(""))
    }

    @Test
    fun test24_SuggestWorkerNameFromVariousFormats() {
        assertEquals("Fast Proxy", WorkerDomainNormalizer.suggestWorkerName("fast-proxy.user.workers.dev"))
        assertEquals("Singapore Node", WorkerDomainNormalizer.suggestWorkerName("singapore-node.workers.dev"))
        assertEquals("My Cloud", WorkerDomainNormalizer.suggestWorkerName("my_cloud.custom.com"))
    }
}
