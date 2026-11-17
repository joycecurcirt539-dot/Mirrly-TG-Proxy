/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class WorkerManagerSwipeSettingTest {

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
    fun testWorkerManagerSwipeStringsExistAndValid() {
        val ruFile = locateResFile("src/main/res/values/strings.xml")
        val enFile = locateResFile("src/main/res/values-en/strings.xml")
        val faFile = locateResFile("src/main/res/values-fa/strings.xml")

        val ruStrings = parseStringsXml(ruFile)
        val enStrings = parseStringsXml(enFile)
        val faStrings = parseStringsXml(faFile)

        // Russian verification
        val ruTitle = ruStrings["settings_worker_swipe_title"]
        val ruDesc = ruStrings["settings_worker_swipe_desc"]
        assertNotNull("Russian swipe title must exist", ruTitle)
        assertNotNull("Russian swipe desc must exist", ruDesc)
        assertTrue(ruTitle!!.isNotBlank())
        assertTrue(ruTitle.contains("Свайп") || ruTitle.contains("свайп") || ruTitle.contains("Блокировка"))
        assertTrue(ruDesc!!.contains("шторк") || ruDesc.contains("менеджер"))

        // English verification
        val enTitle = enStrings["settings_worker_swipe_title"]
        val enDesc = enStrings["settings_worker_swipe_desc"]
        assertNotNull("English swipe title must exist", enTitle)
        assertNotNull("English swipe desc must exist", enDesc)
        assertTrue(enTitle!!.isNotBlank())
        assertTrue(enTitle.contains("Worker") || enTitle.contains("swipe") || enTitle.contains("Block"))
        assertTrue(enDesc!!.contains("Worker Manager") || enDesc.contains("worker"))

        // Persian verification
        val faTitle = faStrings["settings_worker_swipe_title"]
        val faDesc = faStrings["settings_worker_swipe_desc"]
        assertNotNull("Persian swipe title must exist", faTitle)
        assertNotNull("Persian swipe desc must exist", faDesc)
        assertTrue(faTitle!!.isNotBlank())
        assertTrue(faDesc!!.isNotBlank())
    }
}
