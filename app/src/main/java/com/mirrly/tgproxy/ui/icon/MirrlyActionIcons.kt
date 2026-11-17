/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui.icon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors

/**
 * MirrlyUI Procedural Vector Action Icons Specification (/mirrly-icons standard).
 * Category: Actions, Clipboard, Sharing & Data Protection (11 Components).
 *
 * Architectural & Dogmatic Compliance:
 * 1. Outline-Only: Constant 1.5dp stroke (StrokeCap.Round, StrokeJoin.Round). Zero opaque fills.
 * 2. Zero-Allocation (120 FPS): Reusable remembered Path instances outside DrawScope, reset() on frame.
 * 3. Optical Balance: Center of mass adjusted for each asymmetric vector.
 * 4. Dynamic Palette: Bound to LocalProtocolColors.current.primary with zero hardcoded colors.
 * 5. Strict Uniqueness: Distinct cybernetic semantics preventing icon collisions.
 */

// ============================================================================
// 1. ic_copy: Two offset silicon wafer crystal plates with contact notches
// ============================================================================
@Composable
fun MirrlyCopyIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val rearPlatePath = remember { Path() }
    val frontPlatePath = remember { Path() }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val r = 0.04f * w
        val chamfer = 0.08f * w

        // --- Rear Crystal Plate (offset diagonally towards top-right) ---
        rearPlatePath.reset()
        rearPlatePath.moveTo(0.34f * w, 0.32f * h)
        rearPlatePath.lineTo(0.34f * w, 0.16f * h + r)
        rearPlatePath.quadraticBezierTo(0.34f * w, 0.16f * h, 0.34f * w + r, 0.16f * h)
        rearPlatePath.lineTo(0.84f * w - chamfer, 0.16f * h)
        rearPlatePath.lineTo(0.84f * w, 0.16f * h + chamfer)
        rearPlatePath.lineTo(0.84f * w, 0.68f * h - r)
        rearPlatePath.quadraticBezierTo(0.84f * w, 0.68f * h, 0.84f * w - r, 0.68f * h)
        rearPlatePath.lineTo(0.66f * w, 0.68f * h)

        // Rear contact notches (контактные риски)
        rearPlatePath.moveTo(0.84f * w, 0.32f * h)
        rearPlatePath.lineTo(0.76f * w, 0.32f * h)
        rearPlatePath.moveTo(0.84f * w, 0.44f * h)
        rearPlatePath.lineTo(0.76f * w, 0.44f * h)

        drawPath(path = rearPlatePath, color = tint.copy(alpha = 0.70f), style = stroke)

        // --- Front Silicon Die / Primary Wafer (positioned at bottom-left) ---
        frontPlatePath.reset()
        frontPlatePath.moveTo(0.16f * w + r, 0.32f * h)
        frontPlatePath.lineTo(0.66f * w - chamfer, 0.32f * h)
        frontPlatePath.lineTo(0.66f * w, 0.32f * h + chamfer)
        frontPlatePath.lineTo(0.66f * w, 0.84f * h - r)
        frontPlatePath.quadraticBezierTo(0.66f * w, 0.84f * h, 0.66f * w - r, 0.84f * h)
        frontPlatePath.lineTo(0.16f * w + r, 0.84f * h)
        frontPlatePath.quadraticBezierTo(0.16f * w, 0.84f * h, 0.16f * w, 0.84f * h - r)
        frontPlatePath.lineTo(0.16f * w, 0.32f * h + r)
        frontPlatePath.quadraticBezierTo(0.16f * w, 0.32f * h, 0.16f * w + r, 0.32f * h)
        frontPlatePath.close()

        // Front contact bus pins & test pad notches (контактные риски)
        frontPlatePath.moveTo(0.16f * w, 0.48f * h)
        frontPlatePath.lineTo(0.24f * w, 0.48f * h)
        frontPlatePath.moveTo(0.16f * w, 0.60f * h)
        frontPlatePath.lineTo(0.24f * w, 0.60f * h)
        frontPlatePath.moveTo(0.16f * w, 0.72f * h)
        frontPlatePath.lineTo(0.24f * w, 0.72f * h)

        // Internal etched bus traces
        frontPlatePath.moveTo(0.32f * w, 0.50f * h)
        frontPlatePath.lineTo(0.54f * w, 0.50f * h)
        frontPlatePath.moveTo(0.32f * w, 0.64f * h)
        frontPlatePath.lineTo(0.48f * w, 0.64f * h)

        drawPath(path = frontPlatePath, color = tint, style = stroke)
    }
}

// ============================================================================
// 2. ic_export: Data container bay with upward directed quantum beam-emitter
// ============================================================================
@Composable
fun MirrlyExportIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val containerPath = remember { Path() }
    val beamPath = remember { Path() }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // --- Data Container Chassis (Bottom retaining cradle) ---
        containerPath.reset()
        containerPath.moveTo(0.14f * w, 0.44f * h)
        containerPath.lineTo(0.20f * w, 0.44f * h)
        containerPath.lineTo(0.20f * w, 0.80f * h)
        containerPath.lineTo(0.80f * w, 0.80f * h)
        containerPath.lineTo(0.80f * w, 0.44f * h)
        containerPath.lineTo(0.86f * w, 0.44f * h)

        drawPath(path = containerPath, color = tint, style = stroke)

        // --- Vertical Directional Quantum Beam-Emitter (Piercing top boundary) ---
        beamPath.reset()
        // Central emitter beam axis
        beamPath.moveTo(0.50f * w, 0.68f * h)
        beamPath.lineTo(0.50f * w, 0.14f * h)

        // High-velocity emitter chevron apex
        beamPath.moveTo(0.32f * w, 0.32f * h)
        beamPath.lineTo(0.50f * w, 0.14f * h)
        beamPath.lineTo(0.68f * w, 0.32f * h)

        // Quantum collimator accelerator rings
        beamPath.moveTo(0.38f * w, 0.48f * h)
        beamPath.lineTo(0.62f * w, 0.48f * h)
        beamPath.moveTo(0.42f * w, 0.58f * h)
        beamPath.lineTo(0.58f * w, 0.58f * h)

        drawPath(path = beamPath, color = tint, style = stroke)
    }
}

// ============================================================================
// 3. MirrlyShareIcon (Icons.Default.Share Replacement):
//    Topological routing node — Central sensor hub splitting into 2 waveguides
// ============================================================================
@Composable
fun MirrlyShareIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val waveguidePath = remember { Path() }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val rHub = 0.12f * w
        val rCore = 0.045f * w
        val rNode = 0.09f * w

        val hubCenter = Offset(0.26f * w, 0.50f * h)
        val topNodeCenter = Offset(0.74f * w, 0.25f * h)
        val bottomNodeCenter = Offset(0.74f * w, 0.75f * h)

        // Central Sensor Hub (Outer telemetry ring + Inner micro-emitter core)
        drawCircle(color = tint, radius = rHub, center = hubCenter, style = stroke)
        drawCircle(color = tint, radius = rCore, center = hubCenter, style = stroke)

        // Diverging Receiver Micro-Spheres (Outline-only)
        drawCircle(color = tint, radius = rNode, center = topNodeCenter, style = stroke)
        drawCircle(color = tint, radius = rNode, center = bottomNodeCenter, style = stroke)

        // Smooth Cybernetic Waveguides (Волноводы)
        waveguidePath.reset()
        // Top Waveguide (Hub to Top Micro-Sphere)
        waveguidePath.moveTo(0.36f * w, 0.43f * h)
        waveguidePath.cubicTo(0.48f * w, 0.38f * h, 0.56f * w, 0.30f * h, 0.66f * w, 0.28f * h)

        // Bottom Waveguide (Hub to Bottom Micro-Sphere)
        waveguidePath.moveTo(0.36f * w, 0.57f * h)
        waveguidePath.cubicTo(0.48f * w, 0.62f * h, 0.56f * w, 0.70f * h, 0.66f * w, 0.72f * h)

        // Quantum transmission packet coupling ticks
        waveguidePath.moveTo(0.51f * w, 0.32f * h)
        waveguidePath.lineTo(0.53f * w, 0.37f * h)
        waveguidePath.moveTo(0.51f * w, 0.68f * h)
        waveguidePath.lineTo(0.53f * w, 0.63f * h)

        drawPath(path = waveguidePath, color = tint, style = stroke)
    }
}

// ============================================================================
// 4. ic_check: Asymmetric vector stroke (45° slope, 0.35/1.0 ratio, apex brace)
// ============================================================================
@Composable
fun MirrlyCheckIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val checkPath = remember { Path() }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        checkPath.reset()
        // Primary Asymmetric Vector Stroke (Slope 45°, Left: 0.35 ratio, Right: 1.0 ratio)
        // Left arm: dx = 0.16w, dy = 0.16h (length = 0.226w)
        // Right arm: dx = 0.46w, dy = -0.46h (length = 0.650w) => Ratio = 0.226 / 0.650 = 0.348 ~ 0.35
        checkPath.moveTo(0.20f * w, 0.56f * h)
        checkPath.lineTo(0.36f * w, 0.72f * h)
        checkPath.lineTo(0.82f * w, 0.26f * h)

        // Structural Thickening at the Vertex (Утолщение на изломе / Ребро жесткости)
        checkPath.moveTo(0.28f * w, 0.64f * h)
        checkPath.lineTo(0.36f * w, 0.66f * h)
        checkPath.lineTo(0.44f * w, 0.64f * h)

        // Apex mechanical stabilization collar
        checkPath.moveTo(0.31f * w, 0.71f * h)
        checkPath.lineTo(0.36f * w, 0.76f * h)
        checkPath.lineTo(0.41f * w, 0.71f * h)

        drawPath(path = checkPath, color = tint, style = stroke)
    }
}

// ============================================================================
// 5. ic_check_circle: Broken sensory ring (300° arc) with protruding check tip
// ============================================================================
@Composable
fun MirrlyCheckCircleIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val checkPath = remember { Path() }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val r = 0.36f * w
        val cx = 0.48f * w
        val cy = 0.50f * h

        // Broken External Sensory Ring: 300° circular arc
        // 60° gap positioned between -70° and -10° (top-right quadrant)
        drawArc(
            color = tint,
            startAngle = -10f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(2 * r, 2 * r),
            style = stroke
        )

        // Sensory confirmation vector protruding through the ring gap
        checkPath.reset()
        checkPath.moveTo(0.24f * w, 0.50f * h)
        checkPath.lineTo(0.39f * w, 0.65f * h)
        // Passes straight through the 60° gap out beyond the perimeter
        checkPath.lineTo(0.86f * w, 0.18f * h)

        drawPath(path = checkPath, color = tint, style = stroke)
    }
}

// ============================================================================
// 6. ic_trash: High-tech disintegrator hopper with 2dp hovering lid & laser guides
// ============================================================================
@Composable
fun MirrlyTrashIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val hopperPath = remember { Path() }
    val lidPath = remember { Path() }
    val laserPath = remember { Path() }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val yTop = 0.38f * h

        // --- Truncated Contour Disintegrator Hopper (Усеченный бункер) ---
        hopperPath.reset()
        hopperPath.moveTo(0.24f * w, yTop)
        hopperPath.lineTo(0.31f * w, 0.84f * h)
        hopperPath.lineTo(0.69f * w, 0.84f * h)
        hopperPath.lineTo(0.76f * w, yTop)
        drawPath(path = hopperPath, color = tint, style = stroke)

        // --- Hovering Top Lid (Парящая крышка на 2dp выше) ---
        val gapPx = with(density) { 2.dp.toPx() }
        val yLid = yTop - gapPx - strokeWidthPx

        lidPath.reset()
        // Horizontal aerodynamic lid bar
        lidPath.moveTo(0.18f * w, yLid)
        lidPath.lineTo(0.82f * w, yLid)
        // Emitter crown / handle
        lidPath.moveTo(0.38f * w, yLid)
        lidPath.lineTo(0.42f * w, yLid - 0.08f * h)
        lidPath.lineTo(0.58f * w, yLid - 0.08f * h)
        lidPath.lineTo(0.62f * w, yLid)
        drawPath(path = lidPath, color = tint, style = stroke)

        // --- Dual Vertical Laser Cleaning Guides (Направляющие лазерной очистки) ---
        laserPath.reset()
        laserPath.moveTo(0.42f * w, 0.48f * h)
        laserPath.lineTo(0.42f * w, 0.74f * h)
        laserPath.moveTo(0.58f * w, 0.48f * h)
        laserPath.lineTo(0.58f * w, 0.74f * h)

        // Focal ionization nodes
        laserPath.moveTo(0.39f * w, 0.61f * h)
        laserPath.lineTo(0.45f * w, 0.61f * h)
        laserPath.moveTo(0.55f * w, 0.61f * h)
        laserPath.lineTo(0.61f * w, 0.61f * h)

        drawPath(path = laserPath, color = tint, style = stroke)
    }
}

// ============================================================================
// 7. ic_send: Arrowhead hypersonic dart (delta airframe with angled elevons at 45°)
// ============================================================================
@Composable
fun MirrlySendIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val dartPath = remember { Path() }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Hypersonic Delta Dart strictly oriented along 45° vector (top-right)
        // Nose Cone Tip: (0.88w, 0.12h)
        dartPath.reset()
        dartPath.moveTo(0.88f * w, 0.12f * h)
        // Port leading edge to port wingtip
        dartPath.lineTo(0.22f * w, 0.32f * h)
        // Angled port elevon (скошенный закрылок)
        dartPath.lineTo(0.36f * w, 0.48f * h)
        // Keel exhaust throat bay (вырез сопла)
        dartPath.lineTo(0.42f * w, 0.58f * h)
        // Angled starboard elevon (скошенный закрылок)
        dartPath.lineTo(0.52f * w, 0.64f * h)
        // Starboard wingtip
        dartPath.lineTo(0.68f * w, 0.78f * h)
        // Starboard leading edge back to nose tip
        dartPath.lineTo(0.88f * w, 0.12f * h)
        dartPath.close()

        // Fuselage spine / warp keel centerline along 45°
        dartPath.moveTo(0.42f * w, 0.58f * h)
        dartPath.lineTo(0.88f * w, 0.12f * h)

        drawPath(path = dartPath, color = tint, style = stroke)
    }
}

// ============================================================================
// 8. ic_eye: Lens aperture geometry with floating outline pupil-core (radius 1.5dp)
// ============================================================================
@Composable
fun MirrlyEyeIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val eyePath = remember { Path() }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(0.50f * w, 0.50f * h)

        // --- Lens Aperture Visor Contours (Диафрагма объектива) ---
        eyePath.reset()
        eyePath.moveTo(0.12f * w, 0.50f * h)
        eyePath.cubicTo(0.28f * w, 0.22f * h, 0.72f * w, 0.22f * h, 0.88f * w, 0.50f * h)
        eyePath.cubicTo(0.72f * w, 0.78f * h, 0.28f * w, 0.78f * h, 0.12f * w, 0.50f * h)
        eyePath.close()

        // Diaphragm calibration indices
        eyePath.moveTo(0.12f * w, 0.50f * h)
        eyePath.lineTo(0.18f * w, 0.50f * h)
        eyePath.moveTo(0.82f * w, 0.50f * h)
        eyePath.lineTo(0.88f * w, 0.50f * h)

        drawPath(path = eyePath, color = tint, style = stroke)

        // Middle Telemetry Iris Ring (Контур диафрагмы)
        drawCircle(color = tint, radius = 0.18f * w, center = center, style = stroke)

        // Floating Outline Pupil-Core: Radius strictly 1.5dp (Zero fill!)
        val rPupil = with(density) { 1.5.dp.toPx() }
        drawCircle(color = tint, radius = rPupil, center = center, style = stroke)
    }
}

// ============================================================================
// 9. ic_eye_slash: 45° screening blade physically cleaving aperture contours
// ============================================================================
@Composable
fun MirrlyEyeSlashIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val upperSegmentPath = remember { Path() }
    val lowerSegmentPath = remember { Path() }
    val bladePath = remember { Path() }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // --- Upper-Right Severed Aperture Sector ---
        upperSegmentPath.reset()
        upperSegmentPath.moveTo(0.38f * w, 0.27f * h)
        upperSegmentPath.cubicTo(0.55f * w, 0.22f * h, 0.72f * w, 0.22f * h, 0.88f * w, 0.50f * h)
        upperSegmentPath.cubicTo(0.82f * w, 0.62f * h, 0.75f * w, 0.70f * h, 0.66f * w, 0.74f * h)
        drawPath(path = upperSegmentPath, color = tint, style = stroke)

        // Upper-right iris arc
        drawArc(
            color = tint,
            startAngle = -75f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(0.32f * w, 0.32f * h),
            size = Size(0.36f * w, 0.36f * h),
            style = stroke
        )

        // --- Lower-Left Severed Aperture Sector ---
        lowerSegmentPath.reset()
        lowerSegmentPath.moveTo(0.12f * w, 0.50f * h)
        lowerSegmentPath.cubicTo(0.20f * w, 0.35f * h, 0.26f * w, 0.29f * h, 0.34f * w, 0.26f * h)
        lowerSegmentPath.moveTo(0.62f * w, 0.73f * h)
        lowerSegmentPath.cubicTo(0.45f * w, 0.78f * h, 0.28f * w, 0.78f * h, 0.12f * w, 0.50f * h)
        drawPath(path = lowerSegmentPath, color = tint, style = stroke)

        // Lower-left iris arc
        drawArc(
            color = tint,
            startAngle = 105f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(0.32f * w, 0.32f * h),
            size = Size(0.36f * w, 0.36f * h),
            style = stroke
        )

        // --- 45° Diagonal Screening Blade (Экранирующий клинок) ---
        bladePath.reset()
        bladePath.moveTo(0.16f * w, 0.16f * h)
        bladePath.lineTo(0.84f * w, 0.84f * h)
        drawPath(path = bladePath, color = tint, style = stroke)
    }
}

// ============================================================================
// 10. ic_flash_on: Two-segment broken plasma discharge (zigzag) with acute angles
// ============================================================================
@Composable
fun MirrlyFlashOnIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val boltPath = remember { Path() }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Pure acute wireframe polygon (Outline-only, zero fill)
        boltPath.reset()
        boltPath.moveTo(0.56f * w, 0.12f * h) // Top apex electrode
        boltPath.lineTo(0.28f * w, 0.48f * h) // Port downward sweep
        boltPath.lineTo(0.48f * w, 0.48f * h) // Internal step
        boltPath.lineTo(0.42f * w, 0.88f * h) // Terminal discharge spike
        boltPath.lineTo(0.72f * w, 0.44f * h) // Starboard return cut
        boltPath.lineTo(0.52f * w, 0.44f * h) // Inner return facet
        boltPath.close()

        drawPath(path = boltPath, color = tint, style = stroke)
    }
}

// ============================================================================
// 11. ic_flash_off: Same plasma discharge contour bisected by outline breaker line
// ============================================================================
@Composable
fun MirrlyFlashOffIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = LocalProtocolColors.current.primary,
    strokeWidth: Dp = 1.5.dp
) {
    val upperBoltPath = remember { Path() }
    val lowerBoltPath = remember { Path() }
    val breakerPath = remember { Path() }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // --- Upper Plasma Discharge Cluster ---
        upperBoltPath.reset()
        upperBoltPath.moveTo(0.56f * w, 0.12f * h)
        upperBoltPath.lineTo(0.37f * w, 0.36f * h)
        upperBoltPath.lineTo(0.44f * w, 0.36f * h)
        upperBoltPath.lineTo(0.55f * w, 0.40f * h)
        upperBoltPath.close()
        drawPath(path = upperBoltPath, color = tint.copy(alpha = 0.70f), style = stroke)

        // --- Lower Plasma Discharge Cluster ---
        lowerBoltPath.reset()
        lowerBoltPath.moveTo(0.31f * w, 0.48f * h)
        lowerBoltPath.lineTo(0.48f * w, 0.48f * h)
        lowerBoltPath.lineTo(0.42f * w, 0.88f * h)
        lowerBoltPath.lineTo(0.70f * w, 0.47f * h)
        lowerBoltPath.lineTo(0.54f * w, 0.47f * h)
        lowerBoltPath.lineTo(0.44f * w, 0.58f * h)
        lowerBoltPath.close()
        drawPath(path = lowerBoltPath, color = tint.copy(alpha = 0.70f), style = stroke)

        // --- Circuit Interruption / Dielectric Blocking Line (Линия блокировки) ---
        breakerPath.reset()
        breakerPath.moveTo(0.20f * w, 0.18f * h)
        breakerPath.lineTo(0.80f * w, 0.82f * h)
        drawPath(path = breakerPath, color = tint, style = stroke)
    }
}

// ============================================================================
// MirrlyActionIcons Namespace / Catalog Accessor
// ============================================================================
object MirrlyActionIcons {
    @Composable
    fun Copy(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary,
        strokeWidth: Dp = 1.5.dp
    ) = MirrlyCopyIcon(modifier, tint, strokeWidth)

    @Composable
    fun Export(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary,
        strokeWidth: Dp = 1.5.dp
    ) = MirrlyExportIcon(modifier, tint, strokeWidth)

    @Composable
    fun Share(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary,
        strokeWidth: Dp = 1.5.dp
    ) = MirrlyShareIcon(modifier, tint, strokeWidth)

    @Composable
    fun Check(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary,
        strokeWidth: Dp = 1.5.dp
    ) = MirrlyCheckIcon(modifier, tint, strokeWidth)

    @Composable
    fun CheckCircle(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary,
        strokeWidth: Dp = 1.5.dp
    ) = MirrlyCheckCircleIcon(modifier, tint, strokeWidth)

    @Composable
    fun Trash(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary,
        strokeWidth: Dp = 1.5.dp
    ) = MirrlyTrashIcon(modifier, tint, strokeWidth)

    @Composable
    fun Send(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary,
        strokeWidth: Dp = 1.5.dp
    ) = MirrlySendIcon(modifier, tint, strokeWidth)

    @Composable
    fun Eye(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary,
        strokeWidth: Dp = 1.5.dp
    ) = MirrlyEyeIcon(modifier, tint, strokeWidth)

    @Composable
    fun EyeSlash(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary,
        strokeWidth: Dp = 1.5.dp
    ) = MirrlyEyeSlashIcon(modifier, tint, strokeWidth)

    @Composable
    fun FlashOn(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary,
        strokeWidth: Dp = 1.5.dp
    ) = MirrlyFlashOnIcon(modifier, tint, strokeWidth)

    @Composable
    fun FlashOff(
        modifier: Modifier = Modifier.size(24.dp),
        tint: Color = LocalProtocolColors.current.primary,
        strokeWidth: Dp = 1.5.dp
    ) = MirrlyFlashOffIcon(modifier, tint, strokeWidth)
}
