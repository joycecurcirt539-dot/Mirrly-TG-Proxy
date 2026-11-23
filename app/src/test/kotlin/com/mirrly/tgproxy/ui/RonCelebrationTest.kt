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

package com.mirrly.tgproxy.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RonCelebrationTest {

    @Test
    fun particlePool_capsAtMaxParticlesAndRecyclesExpired() {
        val pool = RonParticlePool(maxParticles = 4)
        repeat(6) { i ->
            pool.spawn(
                x = i * 10f,
                y = 0f,
                vx = 100f,
                vy = -50f,
                maxLife = if (i < 2) 0.02f else 1.0f,
                size = 4f,
                shapeKind = RonParticlePool.SHAPE_ORB.toFloat(),
                angleDeg = 0f,
                angularVelDeg = 45f,
                colorIndex = 2f
            )
        }
        assertEquals(4, pool.activeCount)

        // Advance enough to expire the first 2 particles while keeping the last 2 alive
        pool.update(dt = 0.03f, gravityPx = 500f)
        assertEquals(2, pool.activeCount)

        pool.clear()
        assertEquals(0, pool.activeCount)
    }

    @Test
    fun mascotMotionState_resetRestoresIdentityTransforms() {
        val motion = RonMascotMotionState().apply {
            translationYPx = -42f
            scaleX = 1.15f
            scaleY = 0.9f
            rotationYDeg = 180f
            rotationZDeg = -7f
        }

        motion.reset()

        assertEquals(0f, motion.translationYPx, 0.0001f)
        assertEquals(1f, motion.scaleX, 0.0001f)
        assertEquals(1f, motion.scaleY, 0.0001f)
        assertEquals(0f, motion.rotationYDeg, 0.0001f)
        assertEquals(0f, motion.rotationZDeg, 0.0001f)
    }
}
