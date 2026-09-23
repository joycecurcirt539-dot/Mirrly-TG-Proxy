package com.mirrly.tgproxy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoSectionParserTest {

    @Test
    fun parsesHeadingsBulletsAndParagraphsIntoSections() {
        val sections = parseInfoSections(
            """
            ПРИНЦИП РАБОТЫ:
            • Первый пункт
            • Второй пункт

            РЕКОМЕНДАЦИИ:
            Обычный абзац.
            """.trimIndent()
        )

        assertEquals(2, sections.size)
        assertEquals("ПРИНЦИП РАБОТЫ", sections[0].heading)
        assertEquals(2, sections[0].lines.size)
        assertEquals("РЕКОМЕНДАЦИИ", sections[1].heading)
    }

    @Test
    fun supportsEscapedNewlinesFromStringResources() {
        val sections = parseInfoSections("НАЗНАЧЕНИЕ:\\n• Пункт один\\n• Пункт два")

        assertEquals(1, sections.size)
        assertEquals("НАЗНАЧЕНИЕ", sections.single().heading)
        assertEquals(2, sections.single().lines.size)
    }

    @Test
    fun restoresSectionsWhenAndroidCollapsesSettingsXmlWhitespace() {
        val collapsed = "АРХИТЕКТУРА АПЛИНКА: • Общая схема маршрута. " +
            "1. CLOUDFLARE WORKER: • Работа через WSS. " +
            "2. CLOUDFLARE WARP: • Работа через MASQUE."

        val sections = parseInfoSections(collapsed)

        assertEquals(3, sections.size)
        assertEquals("АРХИТЕКТУРА АПЛИНКА", sections[0].heading)
        assertEquals("1. CLOUDFLARE WORKER", sections[1].heading)
        assertEquals("2. CLOUDFLARE WARP", sections[2].heading)
        assertTrue(sections.all { it.lines.isNotEmpty() })
    }

    @Test
    fun capsCardsAtFiveAndPreservesOverflowContent() {
        val body = (1..7).joinToString("\n\n") { index ->
            "РАЗДЕЛ $index:\nСодержимое $index"
        }
        val sections = parseInfoSections(body)

        assertEquals(5, sections.size)
        assertTrue(sections.last().lines.any { it.contains("Содержимое 7") })
        assertTrue(sections.last().lines.any { it.contains("РАЗДЕЛ 6") })
    }
}
