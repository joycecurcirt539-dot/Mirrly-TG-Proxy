/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * Gesture Swipe Orchestrator & Input Buffer Queue
 */

package com.mirrly.tgproxy.ui.snake

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * 2-command circular input buffer preventing dropped inputs during rapid swipes.
 */
class SnakeInputQueue {
    private val queue = ArrayDeque<SnakeDir>(2)

    @Synchronized
    fun offer(newDir: SnakeDir, currentHeading: SnakeDir) {
        val lastDir = queue.peekLast() ?: currentHeading
        if (!newDir.isOpposite(lastDir) && newDir != lastDir) {
            if (queue.size >= 2) {
                queue.pollFirst()
            }
            queue.offerLast(newDir)
        }
    }

    @Synchronized
    fun poll(): SnakeDir? = queue.pollFirst()

    @Synchronized
    fun clear() {
        queue.clear()
    }
}

/**
 * High-precision swipe gesture detector with dominant-axis locking and noise deadzone.
 */
fun Modifier.detectSnakeSwipe(
    deadzone: Dp = 22.dp,
    onSwipe: (SnakeDir) -> Unit
): Modifier = pointerInput(Unit) {
    val deadzonePx = deadzone.toPx()

    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var totalDragX = 0f
        var totalDragY = 0f

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break

            val delta = change.positionChange()
            totalDragX += delta.x
            totalDragY += delta.y

            if (abs(totalDragX) >= deadzonePx || abs(totalDragY) >= deadzonePx) {
                val direction = if (abs(totalDragX) > abs(totalDragY)) {
                    if (totalDragX > 0f) SnakeDir.RIGHT else SnakeDir.LEFT
                } else {
                    if (totalDragY > 0f) SnakeDir.DOWN else SnakeDir.UP
                }

                onSwipe(direction)
                change.consume()
                totalDragX = 0f
                totalDragY = 0f
            }
        }
    }
}
