/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun CountryFlagBadge(
    countryCode: String,
    modifier: Modifier = Modifier,
    accentColor: Color? = null
) {
    val roundedShape = RoundedCornerShape(3.dp)
    Box(
        modifier = modifier
            .size(width = 24.dp, height = 16.dp)
            .clip(roundedShape)
            .border(0.8.dp, Color(0xFF33333A), roundedShape)
    ) {
        when (countryCode.uppercase()) {
            "DE" -> {
                // Germany: 3 horizontal stripes (Black, Red, Gold)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stripeHeight = size.height / 3f
                    drawRect(color = Color(0xFF1B1B1B), topLeft = Offset(0f, 0f), size = Size(size.width, stripeHeight))
                    drawRect(color = Color(0xFFD00000), topLeft = Offset(0f, stripeHeight), size = Size(size.width, stripeHeight))
                    drawRect(color = Color(0xFFFFCE00), topLeft = Offset(0f, stripeHeight * 2), size = Size(size.width, stripeHeight))
                }
            }
            "NL" -> {
                // Netherlands: 3 horizontal stripes (Red, White, Cobalt Blue)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stripeHeight = size.height / 3f
                    drawRect(color = Color(0xFFA81024), topLeft = Offset(0f, 0f), size = Size(size.width, stripeHeight))
                    drawRect(color = Color(0xFFFFFFFF), topLeft = Offset(0f, stripeHeight), size = Size(size.width, stripeHeight))
                    drawRect(color = Color(0xFF1E4287), topLeft = Offset(0f, stripeHeight * 2), size = Size(size.width, stripeHeight))
                }
            }
            "FI" -> {
                // Finland: White background, Blue Nordic cross
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = Color(0xFFFFFFFF))
                    val crossW = size.width * 0.20f
                    val crossH = size.height * 0.28f
                    val crossX = size.width * 0.32f
                    drawRect(color = Color(0xFF003580), topLeft = Offset(crossX, 0f), size = Size(crossW, size.height))
                    drawRect(color = Color(0xFF003580), topLeft = Offset(0f, (size.height - crossH) / 2f), size = Size(size.width, crossH))
                }
            }
            "SE" -> {
                // Sweden: Blue background, Yellow Nordic cross
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = Color(0xFF005293))
                    val crossW = size.width * 0.20f
                    val crossH = size.height * 0.26f
                    val crossX = size.width * 0.32f
                    drawRect(color = Color(0xFFFECC00), topLeft = Offset(crossX, 0f), size = Size(crossW, size.height))
                    drawRect(color = Color(0xFFFECC00), topLeft = Offset(0f, (size.height - crossH) / 2f), size = Size(size.width, crossH))
                }
            }
            "GB" -> {
                // United Kingdom: Royal Navy, White & Red Saltires, White & Red St George Cross
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = Color(0xFF012169))
                    val strokeDiagWhite = size.height * 0.26f
                    val strokeDiagRed = size.height * 0.12f
                    drawLine(color = Color(0xFFFFFFFF), start = Offset(0f, 0f), end = Offset(size.width, size.height), strokeWidth = strokeDiagWhite)
                    drawLine(color = Color(0xFFFFFFFF), start = Offset(0f, size.height), end = Offset(size.width, 0f), strokeWidth = strokeDiagRed)
                    drawLine(color = Color(0xFFC8102E), start = Offset(0f, 0f), end = Offset(size.width, size.height), strokeWidth = strokeDiagRed)
                    drawLine(color = Color(0xFFC8102E), start = Offset(0f, size.height), end = Offset(size.width, 0f), strokeWidth = strokeDiagRed)
                    val crossWhiteW = size.width * 0.28f
                    val crossWhiteH = size.height * 0.38f
                    drawRect(color = Color(0xFFFFFFFF), topLeft = Offset((size.width - crossWhiteW) / 2f, 0f), size = Size(crossWhiteW, size.height))
                    drawRect(color = Color(0xFFFFFFFF), topLeft = Offset(0f, (size.height - crossWhiteH) / 2f), size = Size(size.width, crossWhiteH))
                    val crossRedW = size.width * 0.18f
                    val crossRedH = size.height * 0.22f
                    drawRect(color = Color(0xFFC8102E), topLeft = Offset((size.width - crossRedW) / 2f, 0f), size = Size(crossRedW, size.height))
                    drawRect(color = Color(0xFFC8102E), topLeft = Offset(0f, (size.height - crossRedH) / 2f), size = Size(size.width, crossRedH))
                }
            }
            "US" -> {
                // USA: 7 Red/White stripes + Blue Canton + micro star points
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stripeHeight = size.height / 7f
                    for (i in 0..6) {
                        drawRect(
                            color = if (i % 2 == 0) Color(0xFFB22234) else Color(0xFFFFFFFF),
                            topLeft = Offset(0f, i * stripeHeight),
                            size = Size(size.width, stripeHeight)
                        )
                    }
                    val cantonW = size.width * 0.44f
                    val cantonH = stripeHeight * 4f
                    drawRect(color = Color(0xFF3C3B6E), topLeft = Offset(0f, 0f), size = Size(cantonW, cantonH))
                    drawCircle(color = Color.White, radius = 0.9f, center = Offset(cantonW * 0.35f, cantonH * 0.35f))
                    drawCircle(color = Color.White, radius = 0.9f, center = Offset(cantonW * 0.65f, cantonH * 0.35f))
                    drawCircle(color = Color.White, radius = 0.9f, center = Offset(cantonW * 0.50f, cantonH * 0.65f))
                }
            }
            "FR" -> {
                // France: 3 vertical stripes (Blue, White, Red)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val colW = size.width / 3f
                    drawRect(color = Color(0xFF002654), topLeft = Offset(0f, 0f), size = Size(colW, size.height))
                    drawRect(color = Color(0xFFFFFFFF), topLeft = Offset(colW, 0f), size = Size(colW, size.height))
                    drawRect(color = Color(0xFFED2939), topLeft = Offset(colW * 2f, 0f), size = Size(colW, size.height))
                }
            }
            "RANDOM" -> {
                RandomNodeBadgeIcon()
            }
            "CUSTOM" -> {
                CustomNodeBadgeIcon()
            }
            else -> {
                // GLOBAL / Anycast: Cyber globe with equator, meridian and ellipse
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                ) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = (size.height / 2f) * 0.78f
                    val globeColor = accentColor ?: Color(0xFFFF6B2B)
                    drawCircle(color = globeColor, radius = r, style = Stroke(width = 1.1f))
                    drawLine(color = globeColor.copy(alpha = 0.85f), start = Offset(cx - r, cy), end = Offset(cx + r, cy), strokeWidth = 0.9f)
                    drawLine(color = globeColor.copy(alpha = 0.85f), start = Offset(cx, cy - r), end = Offset(cx, cy + r), strokeWidth = 0.9f)
                    drawOval(color = globeColor.copy(alpha = 0.65f), topLeft = Offset(cx - r * 0.55f, cy - r), size = Size(r * 1.1f, r * 2f), style = Stroke(width = 0.8f))
                }
            }
        }
    }
}

@Composable
fun RandomNodeBadgeIcon(
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        val w = size.width
        val h = size.height

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x40FF9E0B), Color(0x00FF9E0B)),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = h * 0.85f
            )
        )

        val tickColor = Color(0x22FFB703)
        drawLine(color = tickColor, start = Offset(w * 0.5f, h * 0.08f), end = Offset(w * 0.5f, h * 0.20f), strokeWidth = 1f)
        drawLine(color = tickColor, start = Offset(w * 0.5f, h * 0.80f), end = Offset(w * 0.5f, h * 0.92f), strokeWidth = 1f)

        val yTop = h * 0.32f
        val yBottom = h * 0.68f
        val xStart = w * 0.16f
        val xEnd = w * 0.74f
        val strokeW = 1.35.dp.toPx()

        val pathTopToBottom = Path().apply {
            moveTo(xStart, yTop)
            lineTo(w * 0.28f, yTop)
            cubicTo(
                w * 0.44f, yTop,
                w * 0.56f, yBottom,
                w * 0.68f, yBottom
            )
            lineTo(xEnd, yBottom)
        }

        val pathBottomToTop = Path().apply {
            moveTo(xStart, yBottom)
            lineTo(w * 0.28f, yBottom)
            cubicTo(
                w * 0.44f, yBottom,
                w * 0.56f, yTop,
                w * 0.68f, yTop
            )
            lineTo(xEnd, yTop)
        }

        drawPath(
            path = pathTopToBottom,
            color = Color(0x35FF9E0B),
            style = Stroke(width = strokeW * 2.2f, cap = StrokeCap.Round)
        )
        drawPath(
            path = pathBottomToTop,
            color = Color(0x35FFB703),
            style = Stroke(width = strokeW * 2.2f, cap = StrokeCap.Round)
        )

        val streamBrush = Brush.horizontalGradient(
            colors = listOf(Color(0xFFFFB703), Color(0xFFFF9E0B), Color(0xFFFF6D00)),
            startX = xStart,
            endX = xEnd
        )
        drawPath(
            path = pathTopToBottom,
            brush = streamBrush,
            style = Stroke(width = strokeW, cap = StrokeCap.Round)
        )
        drawPath(
            path = pathBottomToTop,
            brush = streamBrush,
            style = Stroke(width = strokeW, cap = StrokeCap.Round)
        )

        val arrowTipX = w * 0.88f
        val arrowBaseX = w * 0.73f
        val arrowHalfH = 2.4.dp.toPx()

        val topArrow = Path().apply {
            moveTo(arrowTipX, yTop)
            lineTo(arrowBaseX, yTop - arrowHalfH)
            lineTo(arrowBaseX + 1.2.dp.toPx(), yTop)
            lineTo(arrowBaseX, yTop + arrowHalfH)
            close()
        }
        drawPath(path = topArrow, color = Color(0xFFFFD54F))

        val bottomArrow = Path().apply {
            moveTo(arrowTipX, yBottom)
            lineTo(arrowBaseX, yBottom - arrowHalfH)
            lineTo(arrowBaseX + 1.2.dp.toPx(), yBottom)
            lineTo(arrowBaseX, yBottom + arrowHalfH)
            close()
        }
        drawPath(path = bottomArrow, color = Color(0xFFFFD54F))

        val nodeHaloR = 2.4.dp.toPx()
        val nodeCoreR = 1.3.dp.toPx()
        drawCircle(color = Color(0x44FF9E0B), radius = nodeHaloR, center = Offset(xStart, yTop))
        drawCircle(color = Color(0xFFFFF9C4), radius = nodeCoreR, center = Offset(xStart, yTop))

        drawCircle(color = Color(0x44FF9E0B), radius = nodeHaloR, center = Offset(xStart, yBottom))
        drawCircle(color = Color(0xFFFFF9C4), radius = nodeCoreR, center = Offset(xStart, yBottom))

        val cx = w * 0.50f
        val cy = h * 0.50f
        val nexusHaloR = 2.8.dp.toPx()
        val nexusCoreR = 1.5.dp.toPx()
        drawCircle(color = Color(0x60FF6D00), radius = nexusHaloR, center = Offset(cx, cy))
        drawCircle(color = Color(0xFFFFD54F), radius = nexusCoreR, center = Offset(cx, cy))
        drawCircle(color = Color(0xFFFFFFFF), radius = nexusCoreR * 0.55f, center = Offset(cx, cy))
    }
}

@Composable
fun CustomNodeBadgeIcon(
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        val w = size.width
        val h = size.height

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x3500E5FF), Color(0x0000E5FF)),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = h * 0.85f
            )
        )

        val tickColor = Color(0x3000E5FF)
        drawLine(color = tickColor, start = Offset(w * 0.50f, h * 0.08f), end = Offset(w * 0.50f, h * 0.20f), strokeWidth = 1f)
        drawLine(color = tickColor, start = Offset(w * 0.50f, h * 0.80f), end = Offset(w * 0.50f, h * 0.92f), strokeWidth = 1f)

        val xStart = w * 0.15f
        val xEnd = w * 0.85f
        val yTop = h * 0.35f
        val yBottom = h * 0.65f
        val railStrokeW = 1.6.dp.toPx()

        val railColor = Color(0xFF16252C)
        drawLine(color = railColor, start = Offset(xStart, yTop), end = Offset(xEnd, yTop), strokeWidth = railStrokeW, cap = StrokeCap.Round)
        drawLine(color = railColor, start = Offset(xStart, yBottom), end = Offset(xEnd, yBottom), strokeWidth = railStrokeW, cap = StrokeCap.Round)

        val xKnobTop = w * 0.67f
        val topTrailBrush = Brush.horizontalGradient(
            colors = listOf(Color(0xFF00E676), Color(0xFF00E5FF)),
            startX = xStart,
            endX = xKnobTop
        )
        drawLine(brush = topTrailBrush, start = Offset(xStart, yTop), end = Offset(xKnobTop, yTop), strokeWidth = railStrokeW, cap = StrokeCap.Round)

        val xKnobBottom = w * 0.33f
        val bottomTrailBrush = Brush.horizontalGradient(
            colors = listOf(Color(0xFF00E676), Color(0xFF69F0AE)),
            startX = xStart,
            endX = xKnobBottom
        )
        drawLine(brush = bottomTrailBrush, start = Offset(xStart, yBottom), end = Offset(xKnobBottom, yBottom), strokeWidth = railStrokeW, cap = StrokeCap.Round)

        val knobW = 3.6.dp.toPx()
        val knobH = 6.8.dp.toPx()
        val knobCornerR = 1.6.dp.toPx()

        drawRoundRect(
            color = Color(0x4000E5FF),
            topLeft = Offset(xKnobTop - (knobW + 2f) / 2f, yTop - (knobH + 2f) / 2f),
            size = Size(knobW + 2f, knobH + 2f),
            cornerRadius = CornerRadius(knobCornerR + 1f)
        )
        drawRoundRect(
            color = Color(0xFF0C1D24),
            topLeft = Offset(xKnobTop - knobW / 2f, yTop - knobH / 2f),
            size = Size(knobW, knobH),
            cornerRadius = CornerRadius(knobCornerR)
        )
        drawRoundRect(
            color = Color(0xFF00E5FF),
            topLeft = Offset(xKnobTop - knobW / 2f, yTop - knobH / 2f),
            size = Size(knobW, knobH),
            cornerRadius = CornerRadius(knobCornerR),
            style = Stroke(width = 0.9.dp.toPx())
        )
        drawLine(
            color = Color(0xFFFFFFFF),
            start = Offset(xKnobTop, yTop - 1.6.dp.toPx()),
            end = Offset(xKnobTop, yTop + 1.6.dp.toPx()),
            strokeWidth = 1.1.dp.toPx(),
            cap = StrokeCap.Round
        )

        drawRoundRect(
            color = Color(0x4000E676),
            topLeft = Offset(xKnobBottom - (knobW + 2f) / 2f, yBottom - (knobH + 2f) / 2f),
            size = Size(knobW + 2f, knobH + 2f),
            cornerRadius = CornerRadius(knobCornerR + 1f)
        )
        drawRoundRect(
            color = Color(0xFF0C1D24),
            topLeft = Offset(xKnobBottom - knobW / 2f, yBottom - knobH / 2f),
            size = Size(knobW, knobH),
            cornerRadius = CornerRadius(knobCornerR)
        )
        drawRoundRect(
            color = Color(0xFF00E676),
            topLeft = Offset(xKnobBottom - knobW / 2f, yBottom - knobH / 2f),
            size = Size(knobW, knobH),
            cornerRadius = CornerRadius(knobCornerR),
            style = Stroke(width = 0.9.dp.toPx())
        )
        drawLine(
            color = Color(0xFFFFFFFF),
            start = Offset(xKnobBottom, yBottom - 1.6.dp.toPx()),
            end = Offset(xKnobBottom, yBottom + 1.6.dp.toPx()),
            strokeWidth = 1.1.dp.toPx(),
            cap = StrokeCap.Round
        )

        val termR = 1.1.dp.toPx()
        drawCircle(color = Color(0x5000E5FF), radius = termR, center = Offset(xStart, yTop))
        drawCircle(color = Color(0x5000E676), radius = termR, center = Offset(xStart, yBottom))
        drawCircle(color = Color(0x3000E5FF), radius = termR, center = Offset(xEnd, yTop))
        drawCircle(color = Color(0x3000E676), radius = termR, center = Offset(xEnd, yBottom))
    }
}
