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
import kotlin.math.abs

class HomeScreenSwipeNavigationTest {

    enum class SimulatedAxis { NONE, HORIZONTAL, VERTICAL }

    data class SwipeResult(
        val newTab: HomeScreenTab,
        val axis: SimulatedAxis,
        val consumed: Boolean
    )

    private fun simulateGesture(
        currentTab: HomeScreenTab,
        totalDragX: Float,
        totalDragY: Float,
        velocityX: Float,
        touchSlop: Float = 16f,
        thresholdPx: Float = 36f,
        isWorkerSwipeEnabled: Boolean = false,
        isHomeModeSwipeEnabled: Boolean = true
    ): SwipeResult {
        var axis = SimulatedAxis.NONE
        val dist = kotlin.math.hypot(totalDragX, totalDragY)

        if (dist > touchSlop) {
            axis = if (isHomeModeSwipeEnabled && abs(totalDragX) >= abs(totalDragY)) {
                SimulatedAxis.HORIZONTAL
            } else if (isWorkerSwipeEnabled && totalDragY > 0f) {
                SimulatedAxis.VERTICAL
            } else {
                SimulatedAxis.NONE
            }
        }

        var newTab = currentTab
        var consumed = false

        if (axis == SimulatedAxis.HORIZONTAL) {
            consumed = true
            val isFlingLeft = velocityX < -700f && totalDragX < -12f
            val isFlingRight = velocityX > 700f && totalDragX > 12f

            if (totalDragX < -thresholdPx || isFlingLeft) {
                // Swipe Left -> Next Tab
                newTab = when (currentTab) {
                    HomeScreenTab.MTPROTO -> HomeScreenTab.SOCKS5
                    HomeScreenTab.SOCKS5 -> HomeScreenTab.VPN
                    HomeScreenTab.VPN -> HomeScreenTab.VPN
                }
            } else if (totalDragX > thresholdPx || isFlingRight) {
                // Swipe Right -> Previous Tab
                newTab = when (currentTab) {
                    HomeScreenTab.VPN -> HomeScreenTab.SOCKS5
                    HomeScreenTab.SOCKS5 -> HomeScreenTab.MTPROTO
                    HomeScreenTab.MTPROTO -> HomeScreenTab.MTPROTO
                }
            }
        } else if (axis == SimulatedAxis.VERTICAL && totalDragY > 0f) {
            consumed = true
        }

        return SwipeResult(newTab, axis, consumed)
    }

    @Test
    fun testSwipeLeftTransitionsForward() {
        // MTPROTO -> SOCKS5
        val r1 = simulateGesture(
            currentTab = HomeScreenTab.MTPROTO,
            totalDragX = -50f,
            totalDragY = 0f,
            velocityX = 0f
        )
        assertEquals(HomeScreenTab.SOCKS5, r1.newTab)
        assertEquals(SimulatedAxis.HORIZONTAL, r1.axis)
        assertTrue(r1.consumed)

        // SOCKS5 -> VPN
        val r2 = simulateGesture(
            currentTab = HomeScreenTab.SOCKS5,
            totalDragX = -60f,
            totalDragY = -5f,
            velocityX = 0f
        )
        assertEquals(HomeScreenTab.VPN, r2.newTab)
        assertEquals(SimulatedAxis.HORIZONTAL, r2.axis)
        assertTrue(r2.consumed)

        // VPN at edge -> stays VPN
        val r3 = simulateGesture(
            currentTab = HomeScreenTab.VPN,
            totalDragX = -70f,
            totalDragY = 0f,
            velocityX = 0f
        )
        assertEquals(HomeScreenTab.VPN, r3.newTab)
        assertEquals(SimulatedAxis.HORIZONTAL, r3.axis)
        assertTrue(r3.consumed)
    }

    @Test
    fun testSwipeRightTransitionsBackward() {
        // VPN -> SOCKS5
        val r1 = simulateGesture(
            currentTab = HomeScreenTab.VPN,
            totalDragX = 50f,
            totalDragY = 0f,
            velocityX = 0f
        )
        assertEquals(HomeScreenTab.SOCKS5, r1.newTab)
        assertEquals(SimulatedAxis.HORIZONTAL, r1.axis)
        assertTrue(r1.consumed)

        // SOCKS5 -> MTPROTO
        val r2 = simulateGesture(
            currentTab = HomeScreenTab.SOCKS5,
            totalDragX = 55f,
            totalDragY = 2f,
            velocityX = 0f
        )
        assertEquals(HomeScreenTab.MTPROTO, r2.newTab)
        assertEquals(SimulatedAxis.HORIZONTAL, r2.axis)
        assertTrue(r2.consumed)

        // MTPROTO at edge -> stays MTPROTO
        val r3 = simulateGesture(
            currentTab = HomeScreenTab.MTPROTO,
            totalDragX = 80f,
            totalDragY = 0f,
            velocityX = 0f
        )
        assertEquals(HomeScreenTab.MTPROTO, r3.newTab)
        assertEquals(SimulatedAxis.HORIZONTAL, r3.axis)
        assertTrue(r3.consumed)
    }

    @Test
    fun testFlingVelocityNavigation() {
        // Quick fling left with small displacement: should switch tab
        val flingLeft = simulateGesture(
            currentTab = HomeScreenTab.MTPROTO,
            totalDragX = -20f,
            totalDragY = 0f,
            velocityX = -1200f
        )
        assertEquals(HomeScreenTab.SOCKS5, flingLeft.newTab)
        assertEquals(SimulatedAxis.HORIZONTAL, flingLeft.axis)

        // Quick fling right with small displacement: should switch tab
        val flingRight = simulateGesture(
            currentTab = HomeScreenTab.VPN,
            totalDragX = 22f,
            totalDragY = 0f,
            velocityX = 1100f
        )
        assertEquals(HomeScreenTab.SOCKS5, flingRight.newTab)
        assertEquals(SimulatedAxis.HORIZONTAL, flingRight.axis)
    }

    @Test
    fun testSubThresholdDragSettlesWithoutTransition() {
        // Below touch slop: axis NONE, unconsumed (pure tap allowed)
        val tap = simulateGesture(
            currentTab = HomeScreenTab.MTPROTO,
            totalDragX = 3f,
            totalDragY = 4f,
            velocityX = 0f
        )
        assertEquals(HomeScreenTab.MTPROTO, tap.newTab)
        assertEquals(SimulatedAxis.NONE, tap.axis)
        assertFalse(tap.consumed)

        // Between touchSlop and threshold without fling: settles back without changing tab
        val minorDrag = simulateGesture(
            currentTab = HomeScreenTab.MTPROTO,
            totalDragX = -22f,
            totalDragY = 0f,
            velocityX = 0f
        )
        assertEquals(HomeScreenTab.MTPROTO, minorDrag.newTab)
        assertEquals(SimulatedAxis.HORIZONTAL, minorDrag.axis)
        assertTrue(minorDrag.consumed)
    }

    @Test
    fun testDominantAxisArbitrationWithWorkerSwipeDisabled() {
        // Downward vertical drag when worker swipe is disabled (default)
        val verticalDown = simulateGesture(
            currentTab = HomeScreenTab.MTPROTO,
            totalDragX = 5f,
            totalDragY = 80f,
            velocityX = 0f,
            isWorkerSwipeEnabled = false
        )
        assertEquals(HomeScreenTab.MTPROTO, verticalDown.newTab)
        assertEquals(SimulatedAxis.NONE, verticalDown.axis)
        assertFalse(verticalDown.consumed)

        // Downward vertical drag when worker swipe is enabled
        val verticalDownEnabled = simulateGesture(
            currentTab = HomeScreenTab.MTPROTO,
            totalDragX = 5f,
            totalDragY = 80f,
            velocityX = 0f,
            isWorkerSwipeEnabled = true
        )
        assertEquals(HomeScreenTab.MTPROTO, verticalDownEnabled.newTab)
        assertEquals(SimulatedAxis.VERTICAL, verticalDownEnabled.axis)
        assertTrue(verticalDownEnabled.consumed)
    }

    @Test
    fun testHomeModeSwipeDisabledDisallowsHorizontalTransition() {
        val swipeDisabled = simulateGesture(
            currentTab = HomeScreenTab.MTPROTO,
            totalDragX = -80f,
            totalDragY = 0f,
            velocityX = 0f,
            isHomeModeSwipeEnabled = false
        )
        // Since swipe is disabled, tab remains unchanged and gesture is unconsumed
        assertEquals(HomeScreenTab.MTPROTO, swipeDisabled.newTab)
        assertEquals(SimulatedAxis.NONE, swipeDisabled.axis)
        assertFalse(swipeDisabled.consumed)
    }

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
    fun testHomeModeSwipeStringsExistAndValid() {
        val ruFile = locateResFile("src/main/res/values/strings.xml")
        val enFile = locateResFile("src/main/res/values-en/strings.xml")
        val faFile = locateResFile("src/main/res/values-fa/strings.xml")

        val ruStrings = parseStringsXml(ruFile)
        val enStrings = parseStringsXml(enFile)
        val faStrings = parseStringsXml(faFile)

        // Russian verification
        val ruTitle = ruStrings["settings_home_mode_swipe_title"]
        val ruDesc = ruStrings["settings_home_mode_swipe_desc"]
        assertNotNull("Russian swipe title must exist", ruTitle)
        assertNotNull("Russian swipe desc must exist", ruDesc)
        assertTrue(ruTitle!!.isNotBlank())
        assertTrue(ruTitle.contains("Свайп") || ruTitle.contains("режим"))
        assertTrue(ruDesc!!.contains("MTProto") && ruDesc.contains("SOCKS5") && ruDesc.contains("VPN"))

        // English verification
        val enTitle = enStrings["settings_home_mode_swipe_title"]
        val enDesc = enStrings["settings_home_mode_swipe_desc"]
        assertNotNull("English swipe title must exist", enTitle)
        assertNotNull("English swipe desc must exist", enDesc)
        assertTrue(enTitle!!.isNotBlank())
        assertTrue(enTitle.contains("Mode") || enTitle.contains("swipe"))
        assertTrue(enDesc!!.contains("MTProto") && enDesc.contains("SOCKS5") && enDesc.contains("VPN"))

        // Persian verification
        val faTitle = faStrings["settings_home_mode_swipe_title"]
        val faDesc = faStrings["settings_home_mode_swipe_desc"]
        assertNotNull("Persian swipe title must exist", faTitle)
        assertNotNull("Persian swipe desc must exist", faDesc)
        assertTrue(faTitle!!.isNotBlank())
        assertTrue(faDesc!!.isNotBlank())
        assertTrue(faDesc.contains("MTProto") && faDesc.contains("SOCKS5") && faDesc.contains("VPN"))
    }
}
