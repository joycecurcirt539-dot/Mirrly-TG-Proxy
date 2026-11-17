/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * Ultra-smooth Zero-Allocation 120 FPS Particle Simulation Engine
 */

package com.mirrly.tgproxy.ui.snake

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Flat FloatArray Particle Pool (Zero Garbage Collection during 120 FPS loops).
 *
 * Each particle uses 8 contiguous floats:
 * [0: posX, 1: posY, 2: velX, 3: velY, 4: lifeRemaining, 5: maxLife, 6: baseRadius, 7: colorCode]
 *
 * Color Codes:
 * 0f = Accent / Cyan (Primary protocol theme)
 * 1f = Quantum Gold (0xFFFFB703) for food singularities and energy absorption
 * 2f = Hazard Red (0xFFFF1744) for deadly laser walls and catastrophic collisions
 * 3f = Pure Photon White (0xFFFFFFFF) for high-energy thrusters and cores
 */
class ParticlePool(val maxParticles: Int = 3000) {
    // 8 floats per particle
    private val data = FloatArray(maxParticles * 8)
    var activeCount: Int = 0
        private set
    var activeLimit: Int = 3000

    private val goldColor = Color(0xFFFFB703)
    private val hazardColor = Color(0xFFFF1744)
    private val whiteColor = Color(0xFFFFFFFF)

    fun clear() {
        activeCount = 0
    }

    fun spawn(
        x: Float,
        y: Float,
        vx: Float,
        vy: Float,
        maxLife: Float,
        size: Float,
        colorCode: Float = 0f
    ) {
        if (activeCount >= activeLimit.coerceAtMost(maxParticles)) return
        val idx = activeCount * 8
        data[idx + 0] = x
        data[idx + 1] = y
        data[idx + 2] = vx
        data[idx + 3] = vy
        data[idx + 4] = maxLife
        data[idx + 5] = maxLife
        data[idx + 6] = size
        data[idx + 7] = colorCode
        activeCount++
    }

    /**
     * Updates physics, velocity damping and life decay in a tight primitive loop.
     */
    fun update(dt: Float) {
        var i = 0
        while (i < activeCount) {
            val idx = i * 8
            data[idx + 4] -= dt // decrease life

            if (data[idx + 4] <= 0f) {
                // Swap-with-last O(1) removal
                activeCount--
                if (i < activeCount) {
                    val lastIdx = activeCount * 8
                    System.arraycopy(data, lastIdx, data, idx, 8)
                }
                continue
            }

            // Position integration
            data[idx + 0] += data[idx + 2] * dt
            data[idx + 1] += data[idx + 3] * dt

            // Air friction / damping
            data[idx + 2] *= 0.94f
            data[idx + 3] *= 0.94f
            i++
        }
    }

    /**
     * Batch draws all active particles using hardware additive blending.
     */
    fun drawBatch(drawScope: DrawScope, accentColor: Color) {
        for (i in 0 until activeCount) {
            val idx = i * 8
            val lifeRem = data[idx + 4]
            val maxLife = data[idx + 5]
            if (maxLife <= 0f) continue

            val alpha = (lifeRem / maxLife).coerceIn(0f, 1f)
            if (alpha <= 0.01f) continue

            val radius = data[idx + 6] * (0.35f + 0.65f * alpha)
            val code = data[idx + 7].toInt()
            val baseColor = when (code) {
                1 -> goldColor
                2 -> hazardColor
                3 -> whiteColor
                else -> accentColor
            }

            drawScope.drawCircle(
                color = baseColor.copy(alpha = alpha),
                radius = radius,
                center = Offset(data[idx + 0], data[idx + 1]),
                blendMode = BlendMode.Plus
            )
        }
    }

    // =========================================================================
    // Specialized Visual Effects Emitters
    // =========================================================================

    /**
     * Radial explosion when snake consumes food (Shockwave burst of gold and white photons).
     */
    fun spawnEatBurst(centerX: Float, centerY: Float, particleCount: Int = 48) {
        for (i in 0 until particleCount) {
            val angle = (Math.PI * 2.0 * i / particleCount) + Random.nextDouble(-0.15, 0.15)
            val speed = Random.nextFloat() * 300f + 120f
            val vx = (cos(angle) * speed).toFloat()
            val vy = (sin(angle) * speed).toFloat()
            val maxLife = Random.nextFloat() * 0.50f + 0.30f
            val size = Random.nextFloat() * 4.5f + 2.5f
            val colorCode = if (i % 2 == 0) 1f else 3f
            spawn(centerX, centerY, vx, vy, maxLife, size, colorCode)
        }
    }

    /**
     * Catastrophic shockwave burst when snake crashes (Hazard red and electric white).
     */
    fun spawnDieBurst(centerX: Float, centerY: Float, particleCount: Int = 64) {
        for (i in 0 until particleCount) {
            val angle = (Math.PI * 2.0 * i / particleCount) + Random.nextDouble(-0.25, 0.25)
            val speed = Random.nextFloat() * 380f + 160f
            val vx = (cos(angle) * speed).toFloat()
            val vy = (sin(angle) * speed).toFloat()
            val maxLife = Random.nextFloat() * 0.70f + 0.35f
            val size = Random.nextFloat() * 5.5f + 3.0f
            val colorCode = if (i % 3 == 0) 3f else 2f
            spawn(centerX, centerY, vx, vy, maxLife, size, colorCode)
        }
    }

    /**
     * Propulsion thruster micro-trail behind snake head.
     */
    fun spawnHeadJet(rearX: Float, rearY: Float, dirX: Float, dirY: Float) {
        val spread = Random.nextFloat() * 16f - 8f
        val speed = Random.nextFloat() * 65f + 30f
        val vx = -dirX * speed + (if (kotlin.math.abs(dirX) < 0.1f) spread else Random.nextFloat() * 8f - 4f)
        val vy = -dirY * speed + (if (kotlin.math.abs(dirY) < 0.1f) spread else Random.nextFloat() * 8f - 4f)
        val life = Random.nextFloat() * 0.16f + 0.09f
        val size = Random.nextFloat() * 2.4f + 1.2f
        val colorCode = if (Random.nextBoolean()) 3f else 0f
        spawn(rearX, rearY, vx, vy, life, size, colorCode)
    }

    /**
     * Dissolution dust for snake tail.
     */
    fun spawnTailDissolve(tailX: Float, tailY: Float) {
        val count = 4
        for (i in 0 until count) {
            val angle = Random.nextFloat() * (Math.PI.toFloat() * 2f)
            val speed = Random.nextFloat() * 45f + 15f
            val vx = cos(angle) * speed
            val vy = sin(angle) * speed
            val life = Random.nextFloat() * 0.40f + 0.20f
            val size = Random.nextFloat() * 2.6f + 1.4f
            spawn(tailX, tailY, vx, vy, life, size, 0f)
        }
    }

    /**
     * Shimmering orbital stardust around snake body segments.
     */
    fun spawnBodyStardust(x: Float, y: Float) {
        val angle = Random.nextFloat() * (Math.PI.toFloat() * 2f)
        val dist = Random.nextFloat() * 6f + 2f
        val px = x + cos(angle) * dist
        val py = y + sin(angle) * dist
        val speed = Random.nextFloat() * 14f + 4f
        val vx = cos(angle + 1.57f) * speed
        val vy = sin(angle + 1.57f) * speed
        val life = Random.nextFloat() * 0.28f + 0.12f
        val size = Random.nextFloat() * 2.0f + 1.0f
        spawn(px, py, vx, vy, life, size, 0f)
    }

    /**
     * Gravitational attraction micro-pulses for quantum food singularity.
     */
    fun spawnFoodAmbient(foodX: Float, foodY: Float) {
        val angle = Random.nextFloat() * (Math.PI.toFloat() * 2f)
        val dist = Random.nextFloat() * 28f + 14f
        val startX = foodX + cos(angle) * dist
        val startY = foodY + sin(angle) * dist
        val speed = 50f
        val vx = -cos(angle) * speed
        val vy = -sin(angle) * speed
        val life = dist / speed
        spawn(startX, startY, vx, vy, life, 2.0f, 1f)
    }

    /**
     * Crackling electric sparks along the lethal laser boundary.
     */
    fun spawnWallHazardSpark(x: Float, y: Float, normalX: Float, normalY: Float) {
        val count = 2
        for (i in 0 until count) {
            val spread = Random.nextFloat() * 60f - 30f
            val speed = Random.nextFloat() * 70f + 30f
            val vx = normalX * speed + (if (normalX == 0f) spread else Random.nextFloat() * 10f - 5f)
            val vy = normalY * speed + (if (normalY == 0f) spread else Random.nextFloat() * 10f - 5f)
            val life = Random.nextFloat() * 0.30f + 0.15f
            val size = Random.nextFloat() * 2.8f + 1.2f
            val colorCode = if (Random.nextBoolean()) 2f else 1f
            spawn(x, y, vx, vy, life, size, colorCode)
        }
    }

    /**
     * Subtle portal particle shimmer when playing in Torus mode.
     */
    fun spawnTorusPortalSpark(x: Float, y: Float) {
        val angle = Random.nextFloat() * (Math.PI.toFloat() * 2f)
        val speed = Random.nextFloat() * 18f + 5f
        val vx = cos(angle) * speed
        val vy = sin(angle) * speed
        val life = Random.nextFloat() * 0.35f + 0.15f
        val size = Random.nextFloat() * 1.8f + 0.8f
        spawn(x, y, vx, vy, life, size, 0f)
    }
}
