/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

class ResourceParityTest {

    private fun locateResFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("..", relativePath),
            File("../app", relativePath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Resource file not found: $relativePath in candidates: $candidates")
    }

    private fun parseStringsXml(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val stringNodes = doc.getElementsByTagName("string")
        val result = mutableMapOf<String, String>()

        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i) as Element
            val name = node.getAttribute("name")
            val text = node.textContent
            result[name] = text
        }
        return result
    }

    @Test
    fun testLocalesConfigContainsRuEnAndFa() {
        val configFile = locateResFile("src/main/res/xml/locales_config.xml")
        val content = configFile.readText()
        assertTrue(content.contains("android:name=\"ru\""))
        assertTrue(content.contains("android:name=\"en\""))
        assertTrue(content.contains("android:name=\"fa\""))
    }

    @Test
    fun testStringResourcesParityBetweenRuAndEn() {
        val ruFile = locateResFile("src/main/res/values/strings.xml")
        val enFile = locateResFile("src/main/res/values-en/strings.xml")

        val ruStrings = parseStringsXml(ruFile)
        val enStrings = parseStringsXml(enFile)

        assertTrue("Russian strings must not be empty", ruStrings.isNotEmpty())
        assertTrue("English strings must not be empty", enStrings.isNotEmpty())

        val missingInEn = ruStrings.keys - enStrings.keys
        val missingInRu = enStrings.keys - ruStrings.keys

        assertEquals("Missing keys in English strings.xml: $missingInEn", emptySet<String>(), missingInEn)
        assertEquals("Missing keys in Russian strings.xml: $missingInRu", emptySet<String>(), missingInRu)
    }

    @Test
    fun testStringResourcesParityBetweenEnAndFa() {
        val enFile = locateResFile("src/main/res/values-en/strings.xml")
        val faFile = locateResFile("src/main/res/values-fa/strings.xml")
        val enStrings = parseStringsXml(enFile)
        val faStrings = parseStringsXml(faFile).toMutableMap().apply {
            putAll(parseStringsXml(locateResFile("src/main/res/values-fa/language_strings.xml")))
        }

        assertEquals("Missing keys in Persian strings.xml: ${enStrings.keys - faStrings.keys}", emptySet<String>(), enStrings.keys - faStrings.keys)
        assertEquals("Unexpected Persian-only keys in strings.xml: ${faStrings.keys - enStrings.keys}", emptySet<String>(), faStrings.keys - enStrings.keys)
    }

    @Test
    fun testNoEmptyStringResources() {
        val ruFile = locateResFile("src/main/res/values/strings.xml")
        val enFile = locateResFile("src/main/res/values-en/strings.xml")

        val ruStrings = parseStringsXml(ruFile)
        val enStrings = parseStringsXml(enFile)

        for ((key, value) in ruStrings) {
            assertFalse("Russian string '$key' should not be blank", value.isBlank())
        }
        for ((key, value) in enStrings) {
            assertFalse("English string '$key' should not be blank", value.isBlank())
        }
    }

    @Test
    fun testFormatSpecifierEquivalence() {
        val ruFile = locateResFile("src/main/res/values/strings.xml")
        val enFile = locateResFile("src/main/res/values-en/strings.xml")

        val ruStrings = parseStringsXml(ruFile)
        val enStrings = parseStringsXml(enFile)

        val formatPattern = Pattern.compile("%(?:(\\d+)\\$)?([sdf])")

        for (key in ruStrings.keys) {
            val ruText = ruStrings[key] ?: ""
            val enText = enStrings[key] ?: ""

            val ruMatchers = mutableListOf<String>()
            val mRu = formatPattern.matcher(ruText)
            while (mRu.find()) {
                ruMatchers.add(mRu.group())
            }

            val enMatchers = mutableListOf<String>()
            val mEn = formatPattern.matcher(enText)
            while (mEn.find()) {
                enMatchers.add(mEn.group())
            }

            assertEquals(
                "Format specifier mismatch for key '$key': RU=$ruMatchers vs EN=$enMatchers",
                ruMatchers,
                enMatchers
            )
        }
    }

    @Test
    fun testLocaleHelperLanguageResolution() {
        assertEquals("ru", LocaleHelper.getTargetLocale(LocaleHelper.LANG_RU).language)
        assertEquals("en", LocaleHelper.getTargetLocale(LocaleHelper.LANG_EN).language)
        assertEquals("fa", LocaleHelper.getTargetLocale(LocaleHelper.LANG_FA).language)
        assertNotNull(LocaleHelper.getTargetLocale(LocaleHelper.LANG_SYSTEM))
    }
}
