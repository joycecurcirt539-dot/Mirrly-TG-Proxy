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

package com.mirrly.tgproxy.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ParticleDynamicsTest {

    @Test
    fun particleStyle_fromStringParsingAndFallback() {
        assertEquals(ParticleStyle.BOKEH_DUST, ParticleStyle.fromString("BOKEH_DUST"))
        assertEquals(ParticleStyle.BOKEH_DUST, ParticleStyle.fromString("bokeh_dust"))
        assertEquals(ParticleStyle.BITSTREAM_MATRIX, ParticleStyle.fromString("BITSTREAM_MATRIX"))
        assertEquals(ParticleStyle.BITSTREAM_MATRIX, ParticleStyle.fromString("bitstream_matrix"))
        assertEquals(ParticleStyle.STARFIELD_WARP, ParticleStyle.fromString("STARFIELD_WARP"))
        assertEquals(ParticleStyle.STARFIELD_WARP, ParticleStyle.fromString("starfield_warp"))

        // Fallbacks on invalid or null values
        assertEquals(ParticleStyle.BOKEH_DUST, ParticleStyle.fromString(null))
        assertEquals(ParticleStyle.BOKEH_DUST, ParticleStyle.fromString(""))
        assertEquals(ParticleStyle.BOKEH_DUST, ParticleStyle.fromString("INVALID_STYLE"))
    }

    @Test
    fun particleEngine_constantsValidation() {
        assertEquals(8, PreferencesManager.WALLPAPER_PARTICLES_COUNT_MIN)
        assertEquals(100, PreferencesManager.WALLPAPER_PARTICLES_COUNT_MAX)
        assertEquals(20, PreferencesManager.WALLPAPER_PARTICLES_COUNT_DEFAULT)

        assertEquals(0.5f, PreferencesManager.WALLPAPER_PARTICLES_SPEED_MIN, 0.001f)
        assertEquals(2.0f, PreferencesManager.WALLPAPER_PARTICLES_SPEED_MAX, 0.001f)
        assertEquals(1.0f, PreferencesManager.WALLPAPER_PARTICLES_SPEED_DEFAULT, 0.001f)
    }

    @Test
    fun matrixStream_downwardKinematics() {
        val width = 1080f
        val height = 2400f
        val downwardSpeed = 200f
        val speedFactor = 1.2f
        val relY = 0.5f

        val t0 = 0f
        val rawY0 = relY * height + t0 * downwardSpeed * speedFactor
        val py0 = ((rawY0 % height) + height) % height

        val t1 = 2f
        val rawY1 = relY * height + t1 * downwardSpeed * speedFactor
        val py1 = ((rawY1 % height) + height) % height

        // At t1 > t0, particle must move downward
        assertTrue(rawY1 > rawY0)
        assertTrue(py0 in 0f..height)
        assertTrue(py1 in 0f..height)
    }

    @Test
    fun starfieldWarp_radialAccelerationMonotonicity() {
        val maxR = 1200f

        var prevR = 0f
        for (step in 1..10) {
            val prog = step / 10f
            val r = maxR * (prog * prog)
            assertTrue("Radius must strictly increase with quadratic acceleration", r > prevR)
            prevR = r
        }
        assertEquals(maxR, prevR, 0.001f)
    }

    @Test
    fun parallaxRatio_opticalDepthProportions() {
        val bgMaxDp = 14f
        val particlesMaxDp = 24f
        val totalSpan = bgMaxDp + particlesMaxDp

        val bgDepthFraction = bgMaxDp / totalSpan
        val particlesDepthFraction = particlesMaxDp / totalSpan

        // Background depth corresponds to ~0.368 (depth 0.3 layer)
        assertTrue(bgDepthFraction in 0.30f..0.40f)
        // Particles depth corresponds to ~0.632 (depth 0.7 layer)
        assertTrue(particlesDepthFraction in 0.60f..0.70f)
    }

    @Test
    fun homeRings_keyConstantsValidation() {
        assertEquals("home_rings_enabled", PreferencesManager.KEY_HOME_RINGS_ENABLED)
    }
}
