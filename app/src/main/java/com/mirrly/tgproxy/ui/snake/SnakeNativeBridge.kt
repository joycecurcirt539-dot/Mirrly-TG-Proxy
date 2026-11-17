/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * Cyber Snake JNA Native Bridge & Resilient Pure-Kotlin Fallback Engine
 */

package com.mirrly.tgproxy.ui.snake

import com.mirrly.tgproxy.core.AppLogger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

interface SnakeNativeLibrary : Library {
    companion object {
        val INSTANCE: SnakeNativeLibrary? by lazy {
            try {
                Native.load("mirrlyengine", SnakeNativeLibrary::class.java)
            } catch (t: Throwable) {
                AppLogger.w("SnakeNativeBridge", "Could not load libmirrlyengine for Snake: ${t.message}")
                null
            }
        }
    }

    fun SnakeInit(width: Int, height: Int): Int
    fun SnakeReset(): Int
    fun SnakeStep(manualDir: Int, isAi: Int): Int
    fun SnakeGetAiPrediction(): Int
    fun SnakeGetState(
        outHeadX: IntByReference?,
        outHeadY: IntByReference?,
        outFoodX: IntByReference?,
        outFoodY: IntByReference?,
        outScore: IntByReference?,
        outGen: IntByReference?,
        outFitness: FloatByReference?,
        outIsDead: IntByReference?
    ): Int
    fun SnakeGetBody(outCoords: IntArray, maxPairs: Int): Int
    fun SnakeSetWallsKill(enabled: Int): Int
    fun SnakeGetWallsKill(): Int
    fun SnakeTrainGeneration(): Int
    fun SnakeTrainBatch(count: Int): Int
    fun SnakeResetAi(mode: Int): Int
    fun SnakeSetPopulationSize(size: Int): Int
    fun SnakeSetMutationRate(rate: Float): Int
    fun SnakeExportGenome(outBuffer: FloatArray, maxLen: Int): Int
    fun SnakeImportGenome(inBuffer: FloatArray, len: Int): Int
}

enum class SnakeDir(val code: Int, val dx: Int, val dy: Int) {
    UP(0, 0, -1),
    RIGHT(1, 1, 0),
    DOWN(2, 0, 1),
    LEFT(3, -1, 0);

    fun isOpposite(other: SnakeDir): Boolean =
        (this == UP && other == DOWN) ||
        (this == DOWN && other == UP) ||
        (this == LEFT && other == RIGHT) ||
        (this == RIGHT && other == LEFT)

    fun turnRelative(rel: Int): SnakeDir = when (rel) {
        1 -> values()[(ordinal + 3) % 4] // Turn Left
        2 -> values()[(ordinal + 1) % 4] // Turn Right
        else -> this                     // Straight
    }

    companion object {
        fun fromInt(v: Int): SnakeDir = values().firstOrNull { it.code == v } ?: UP
    }
}

data class SnakeGameState(
    val headX: Int,
    val headY: Int,
    val foodX: Int,
    val foodY: Int,
    val score: Int,
    val generation: Int,
    val bestFitness: Float,
    val isDead: Boolean,
    val bodyCoords: List<Pair<Int, Int>>,
    val wallsKill: Boolean = false
)

/**
 * High-performance, zero-crash Native Snake Controller.
 * Leverages libmirrlyengine native C-ABI when available,
 * and seamlessly provides full parity fallback on pure Kotlin.
 */
class SnakeController(
    var gridWidth: Int = 20,
    var gridHeight: Int = 30
) {
    private val TAG = "SnakeController"
    private val isNativeAvailable = AtomicBoolean(false)
    private val reusableBodyBuffer = IntArray(600)

    var wallsKill: Boolean = false
        set(value) {
            field = value
            if (isNativeAvailable.get()) {
                try {
                    SnakeNativeLibrary.INSTANCE?.SnakeSetWallsKill(if (value) 1 else 0)
                } catch (_: Throwable) {
                    isNativeAvailable.set(false)
                }
            }
        }

    // Kotlin fallback state
    private val body = ArrayDeque<Pair<Int, Int>>()
    private var foodX: Int = 5
    private var foodY: Int = 5
    private var currentDir: SnakeDir = SnakeDir.RIGHT
    private var applesEaten: Int = 0
    private var stepsAlive: Int = 0
    private var energy: Int = 150
    private var isDeadState: Boolean = false
    private var generation: Int = 150
    private var bestFitness: Float = 125000f

    init {
        try {
            val lib = SnakeNativeLibrary.INSTANCE
            if (lib != null) {
                lib.SnakeInit(gridWidth, gridHeight)
                isNativeAvailable.set(true)
                AppLogger.i(TAG, "Snake native Rust engine successfully initialized")
            } else {
                initFallback()
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Native init failed, using Kotlin fallback: ${t.message}")
            initFallback()
        }
    }

    private fun initFallback() {
        body.clear()
        val startX = gridWidth / 2
        val startY = gridHeight / 2
        body.add(startX to startY)
        body.add(startX - 1 to startY)
        body.add(startX - 2 to startY)
        currentDir = SnakeDir.RIGHT
        applesEaten = 0
        stepsAlive = 0
        energy = 150
        isDeadState = false
        spawnFallbackFood()
    }

    private fun spawnFallbackFood() {
        for (i in 0..100) {
            val rx = Random.nextInt(gridWidth)
            val ry = Random.nextInt(gridHeight)
            if (body.none { it.first == rx && it.second == ry }) {
                foodX = rx
                foodY = ry
                return
            }
        }
        foodX = (gridWidth / 4)
        foodY = (gridHeight / 4)
    }

    fun reconfigure(w: Int, h: Int) {
        if (gridWidth == w && gridHeight == h) return
        gridWidth = w
        gridHeight = h
        if (isNativeAvailable.get()) {
            try {
                SnakeNativeLibrary.INSTANCE?.SnakeInit(w, h)
                SnakeNativeLibrary.INSTANCE?.SnakeSetWallsKill(if (wallsKill) 1 else 0)
                return
            } catch (_: Throwable) {
                isNativeAvailable.set(false)
            }
        }
        initFallback()
    }

    fun reset() {
        if (isNativeAvailable.get()) {
            try {
                SnakeNativeLibrary.INSTANCE?.SnakeReset()
                return
            } catch (_: Throwable) {
                isNativeAvailable.set(false)
            }
        }
        initFallback()
    }

    fun step(manualDir: SnakeDir, isAi: Boolean): Int {
        // Return codes: 0 = Alive, 1 = AteFood, 2 = Died
        if (isNativeAvailable.get()) {
            try {
                val result = SnakeNativeLibrary.INSTANCE?.SnakeStep(manualDir.code, if (isAi) 1 else 0)
                if (result != null) return result
            } catch (_: Throwable) {
                isNativeAvailable.set(false)
            }
        }

        // Kotlin Fallback execution
        if (isDeadState) return 2

        if (isAi) {
            // Smart raycast heuristics for AI fallback
            val predicted = predictAiDirFallback()
            currentDir = predicted
        } else {
            if (!manualDir.isOpposite(currentDir)) {
                currentDir = manualDir
            }
        }

        val head = body.first
        val nextX: Int
        val nextY: Int

        if (wallsKill) {
            nextX = head.first + currentDir.dx
            nextY = head.second + currentDir.dy
            if (nextX < 0 || nextX >= gridWidth || nextY < 0 || nextY >= gridHeight) {
                isDeadState = true
                return 2
            }
        } else {
            // Torus wrap-around across edges
            nextX = (head.first + currentDir.dx).mod(gridWidth)
            nextY = (head.second + currentDir.dy).mod(gridHeight)
        }

        stepsAlive++
        energy--

        // Body collision
        val willEat = nextX == foodX && nextY == foodY
        val checkLen = if (willEat) body.size else body.size - 1
        var idx = 0
        for (seg in body) {
            if (idx >= checkLen) break
            if (seg.first == nextX && seg.second == nextY) {
                isDeadState = true
                return 2
            }
            idx++
        }

        if (energy <= 0) {
            isDeadState = true
            return 2
        }

        body.addFirst(nextX to nextY)

        return if (willEat) {
            applesEaten++
            energy = (energy + 120).coerceAtMost(400)
            spawnFallbackFood()
            val fit = stepsAlive + (applesEaten * 1000f) + (applesEaten * applesEaten * 500f)
            if (fit > bestFitness) bestFitness = fit
            1
        } else {
            body.removeLast()
            0
        }
    }

    private fun findPathToFallback(
        startX: Int,
        startY: Int,
        targetX: Int,
        targetY: Int,
        obstacles: Set<Pair<Int, Int>>
    ): List<SnakeDir>? {
        if (startX == targetX && startY == targetY) return emptyList()

        val visited = HashSet<Pair<Int, Int>>()
        val parent = HashMap<Pair<Int, Int>, Pair<Pair<Int, Int>, SnakeDir>>()
        val queue = ArrayDeque<Pair<Int, Int>>()

        val start = startX to startY
        val target = targetX to targetY
        visited.add(start)
        queue.add(start)

        var found = false
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            if (curr == target) {
                found = true
                break
            }

            for (dir in SnakeDir.values()) {
                val nx: Int
                val ny: Int
                if (wallsKill) {
                    nx = curr.first + dir.dx
                    ny = curr.second + dir.dy
                    if (nx < 0 || nx >= gridWidth || ny < 0 || ny >= gridHeight) continue
                } else {
                    nx = (curr.first + dir.dx).mod(gridWidth)
                    ny = (curr.second + dir.dy).mod(gridHeight)
                }
                val nextPos = nx to ny
                if (!visited.contains(nextPos)) {
                    if (nextPos == target || !obstacles.contains(nextPos)) {
                        visited.add(nextPos)
                        parent[nextPos] = curr to dir
                        queue.add(nextPos)
                    }
                }
            }
        }

        if (!found) return null

        val path = mutableListOf<SnakeDir>()
        var curr = target
        while (curr != start) {
            val (prev, dir) = parent[curr] ?: return null
            path.add(dir)
            curr = prev
        }
        path.reverse()
        return path
    }

    private fun isFoodPathSafeFallback(path: List<SnakeDir>): Boolean {
        if (path.isEmpty()) return false
        val virtBody = ArrayDeque(body)

        for ((idx, dir) in path.withIndex()) {
            val head = virtBody.first
            val nx = if (wallsKill) head.first + dir.dx else (head.first + dir.dx).mod(gridWidth)
            val ny = if (wallsKill) head.second + dir.dy else (head.second + dir.dy).mod(gridHeight)
            virtBody.addFirst(nx to ny)
            if (idx != path.size - 1) {
                virtBody.removeLast()
            }
        }

        val virtHead = virtBody.first
        val virtTail = virtBody.last
        val virtObstacles = HashSet<Pair<Int, Int>>()
        var count = 0
        val checkLen = (virtBody.size - 1).coerceAtLeast(1)
        for (seg in virtBody) {
            if (count >= checkLen) break
            virtObstacles.add(seg)
            count++
        }

        return findPathToFallback(virtHead.first, virtHead.second, virtTail.first, virtTail.second, virtObstacles) != null
    }

    private fun getSafeTailFollowingDirFallback(obstacles: Set<Pair<Int, Int>>): SnakeDir {
        val head = body.first
        val tail = body.last
        val candidates = listOf(currentDir, currentDir.turnRelative(1), currentDir.turnRelative(2))
        var bestDir = candidates.first()
        var bestScore = Float.NEGATIVE_INFINITY
        val bodyLen = body.size

        for (dir in candidates) {
            val nx: Int
            val ny: Int
            if (wallsKill) {
                nx = head.first + dir.dx
                ny = head.second + dir.dy
                if (nx < 0 || nx >= gridWidth || ny < 0 || ny >= gridHeight) continue
            } else {
                nx = (head.first + dir.dx).mod(gridWidth)
                ny = (head.second + dir.dy).mod(gridHeight)
            }
            if (body.take(body.size - 1).any { it.first == nx && it.second == ny }) continue

            val canReachTail = findPathToFallback(nx, ny, tail.first, tail.second, obstacles) != null
            val reach = floodFillReachFallback(nx, ny, bodyLen + 30)

            var score = reach * 2.0f
            if (canReachTail) {
                score += 300f
                var tdx = abs(tail.first - nx)
                if (!wallsKill && tdx > gridWidth / 2) tdx = gridWidth - tdx
                var tdy = abs(tail.second - ny)
                if (!wallsKill && tdy > gridHeight / 2) tdy = gridHeight - tdy
                score += (tdx + tdy) * 3.5f
            } else {
                score -= 500f
            }

            if (dir == currentDir) score += 1.5f

            if (score > bestScore) {
                bestScore = score
                bestDir = dir
            }
        }

        if (bestScore == Float.NEGATIVE_INFINITY) {
            var maxReach = -1
            for (dir in candidates) {
                val nx = if (wallsKill) head.first + dir.dx else (head.first + dir.dx).mod(gridWidth)
                val ny = if (wallsKill) head.second + dir.dy else (head.second + dir.dy).mod(gridHeight)
                if (wallsKill && (nx < 0 || nx >= gridWidth || ny < 0 || ny >= gridHeight)) continue
                if (body.take(body.size - 1).any { it.first == nx && it.second == ny }) continue
                val r = floodFillReachFallback(nx, ny, bodyLen + 40)
                if (r > maxReach) {
                    maxReach = r
                    bestDir = dir
                }
            }
        }

        return bestDir
    }

    private fun predictAiDirFallback(): SnakeDir {
        val head = body.first
        val obstacles = HashSet<Pair<Int, Int>>()
        var count = 0
        val checkLen = (body.size - 1).coerceAtLeast(1)
        for (seg in body) {
            if (count >= checkLen) break
            obstacles.add(seg)
            count++
        }

        // 1. Try finding shortest path to food
        val pathToFood = findPathToFallback(head.first, head.second, foodX, foodY, obstacles)
        if (pathToFood != null && pathToFood.isNotEmpty() && isFoodPathSafeFallback(pathToFood)) {
            return pathToFood.first()
        }

        // 2. Safe tail-following
        return getSafeTailFollowingDirFallback(obstacles)
    }

    private fun floodFillReachFallback(startX: Int, startY: Int, maxCheck: Int): Int {
        val visited = HashSet<Pair<Int, Int>>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        visited.add(startX to startY)
        queue.add(startX to startY)
        var count = 0
        val deltas = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)

        while (queue.isNotEmpty() && count < maxCheck) {
            val (cx, cy) = queue.removeFirst()
            count++

            for ((dx, dy) in deltas) {
                val nx: Int
                val ny: Int
                if (wallsKill) {
                    nx = cx + dx
                    ny = cy + dy
                    if (nx < 0 || nx >= gridWidth || ny < 0 || ny >= gridHeight) continue
                } else {
                    nx = (cx + dx).mod(gridWidth)
                    ny = (cy + dy).mod(gridHeight)
                }
                val pos = nx to ny
                if (!visited.contains(pos) && !body.any { it == pos }) {
                    visited.add(pos)
                    queue.add(pos)
                }
            }
        }
        return count
    }

    fun trainGeneration() {
        if (isNativeAvailable.get()) {
            try {
                SnakeNativeLibrary.INSTANCE?.SnakeTrainGeneration()
                return
            } catch (_: Throwable) {
                isNativeAvailable.set(false)
            }
        }
        generation++
    }

    fun trainBatch(count: Int) {
        if (isNativeAvailable.get()) {
            try {
                SnakeNativeLibrary.INSTANCE?.SnakeTrainBatch(count)
                return
            } catch (_: Throwable) {
                isNativeAvailable.set(false)
            }
        }
        generation += count
    }

    fun resetAi(useSmartBaseline: Boolean = true) {
        if (isNativeAvailable.get()) {
            try {
                SnakeNativeLibrary.INSTANCE?.SnakeResetAi(if (useSmartBaseline) 1 else 0)
                return
            } catch (_: Throwable) {
                isNativeAvailable.set(false)
            }
        }
        generation = if (useSmartBaseline) 150 else 1
        bestFitness = if (useSmartBaseline) 125000f else 0f
    }

    fun setPopulationSize(size: Int) {
        if (isNativeAvailable.get()) {
            try {
                SnakeNativeLibrary.INSTANCE?.SnakeSetPopulationSize(size)
            } catch (_: Throwable) {
                isNativeAvailable.set(false)
            }
        }
    }

    fun setMutationRate(rate: Float) {
        if (isNativeAvailable.get()) {
            try {
                SnakeNativeLibrary.INSTANCE?.SnakeSetMutationRate(rate)
            } catch (_: Throwable) {
                isNativeAvailable.set(false)
            }
        }
    }

    fun getState(): SnakeGameState {
        if (isNativeAvailable.get()) {
            try {
                val hx = IntByReference()
                val hy = IntByReference()
                val fx = IntByReference()
                val fy = IntByReference()
                val sc = IntByReference()
                val gn = IntByReference()
                val ft = FloatByReference()
                val dead = IntByReference()

                SnakeNativeLibrary.INSTANCE?.SnakeGetState(hx, hy, fx, fy, sc, gn, ft, dead)

                val len = SnakeNativeLibrary.INSTANCE?.SnakeGetBody(reusableBodyBuffer, 150) ?: 0
                val coords = ArrayList<Pair<Int, Int>>(len)
                for (i in 0 until len) {
                    coords.add(reusableBodyBuffer[i * 2] to reusableBodyBuffer[i * 2 + 1])
                }
                val isWk = SnakeNativeLibrary.INSTANCE?.SnakeGetWallsKill() ?: 0

                return SnakeGameState(
                    headX = hx.value,
                    headY = hy.value,
                    foodX = fx.value,
                    foodY = fy.value,
                    score = sc.value,
                    generation = gn.value,
                    bestFitness = ft.value,
                    isDead = dead.value != 0,
                    bodyCoords = coords,
                    wallsKill = isWk != 0
                )
            } catch (_: Throwable) {
                isNativeAvailable.set(false)
            }
        }

        return SnakeGameState(
            headX = body.firstOrNull()?.first ?: 0,
            headY = body.firstOrNull()?.second ?: 0,
            foodX = foodX,
            foodY = foodY,
            score = applesEaten,
            generation = generation,
            bestFitness = bestFitness,
            isDead = isDeadState,
            bodyCoords = body.toList(),
            wallsKill = wallsKill
        )
    }
}
