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

package com.mirrly.tgproxy.ui.theme

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Production AGSL 3D Water Droplet & Liquid Glass Shader for Android 13+ (API 33+).
 *
 * Features:
 * 1. 3D Meniscus Height Profile: Real circular fluid meniscus h(u) = sqrt(u * (2 - u)).
 * 2. 3D Surface Normals: Outward tilting normals along the rim leveling out to +Z in the center.
 * 3. Animated Key-Light Orbital Sun Glint: Smooth continuous Blinn-Phong specular glint gliding across the droplet.
 * 4. Physical Fresnel Reflection: Water-air optical boundary reflection (n ~ 1.33) intensifying at edges.
 * 5. Kinetic Liquid Breathing: Continuous 60/120 FPS fluid micro-oscillation.
 * 6. Transparent Pass-Through: Clear AMOLED translucency in the center allowing wallpapers and stars to shine through freely.
 * 7. Zero Foreground Distortion: Rendered as a live background canvas beneath the crisp content layer.
 */
private const val LIQUID_GLASS_AGSL = """
    uniform float2 size;
    uniform float cornerRadius;
    uniform float time;
    uniform float warpStrength;
    uniform float4 glassTint;
    uniform float4 primaryAccent;

    // 2D Rounded Rectangle Signed Distance Field
    float sdRoundedBox(float2 p, float2 halfSize, float r) {
        float2 d = abs(p) - halfSize + r;
        return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - r;
    }

    vec4 main(vec2 fragCoord) {
        float2 halfSize = size * 0.5;
        float2 centeredCoord = fragCoord - halfSize;

        // Signed distance to container boundary
        float d = sdRoundedBox(centeredCoord, halfSize, cornerRadius);

        // Outside droplet boundary: discard with smooth antialiasing
        if (d > 1.5) {
            return vec4(0.0);
        }

        // Delicate, razor-sharp liquid meniscus rim (6px to 10px, never a wide rubber bumper)
        float breathing = sin(time * 1.5) * 0.75;
        float bevelWidth = clamp(cornerRadius * 0.35 + breathing, 6.0, 10.0);

        // Normalized depth u: 0.0 at outer edge, 1.0 at central crystal plateau
        float u = clamp(-d / bevelWidth, 0.0, 1.0);

        // Circular meniscus height profile
        float h = sqrt(max(u * (2.0 - u), 0.0));

        // Direction vector from center to fragment
        float len = length(centeredCoord);
        float2 dir = len > 0.001 ? centeredCoord / len : float2(0.0, 1.0);

        // Surface slope (dh/du)
        float slope = (1.0 - u) / max(h, 0.12);

        // Reconstruct 3D surface normal: outward tilting at rim, +Z in the center
        vec3 N = normalize(vec3(-dir * slope * (0.65 + warpStrength * 0.35), 1.0));
        vec3 V = vec3(0.0, 0.0, 1.0);

        // Animated 3D Light Source (gentle orbital key light)
        float lightAngle = time * 0.65;
        vec3 L = normalize(vec3(cos(lightAngle) * 0.65 - 0.25, sin(lightAngle) * 0.55 - 0.55, 0.85));

        // Blinn-Phong Specular (sharp water sun glint on the curved meniscus)
        vec3 H = normalize(L + V);
        float NdotH = max(dot(N, H), 0.0);

        // Specular is tightly focused on the curved rim: rimFactor decays to 0 in center
        float rimFactor = (1.0 - u) * (1.0 - u);
        float glint = pow(NdotH, 64.0) * rimFactor * 0.50;

        // Physical Fresnel reflection (water IOR ~ 1.33)
        float cosTheta = max(dot(N, V), 0.0);
        float fresnel = 0.04 + 0.96 * pow(1.0 - cosTheta, 4.0);

        // Secondary ambient bounce rim catching protocol accent light
        vec3 L_accent = normalize(vec3(0.5, 0.7, 0.4));
        float accentRim = pow(max(dot(N, L_accent), 0.0), 12.0) * rimFactor * 0.25;

        // Base crystal tint: ultra-translucent AMOLED dark pass-through (never milky or muddy)
        vec4 col = vec4(glassTint.rgb * glassTint.a, glassTint.a);

        // Delicate Apple-style sub-pixel edge highlight
        float edgeSheen = rimFactor * fresnel;
        vec3 rimColor = mix(vec3(0.9, 0.95, 1.0), primaryAccent.rgb, 0.35);
        float edgeAlpha = edgeSheen * 0.30;
        col.rgb += rimColor * edgeAlpha;
        col.a = min(col.a + edgeAlpha, 1.0);

        // Protocol accent luminescence on the rim
        col.rgb += primaryAccent.rgb * accentRim;

        // Add tight, sparkling sun glint (premultiplied additive light)
        col.rgb += vec3(glint);
        col.a = min(col.a + glint * 0.5, 1.0);

        // Smooth sub-pixel outer edge antialiasing
        float edgeAlpha = clamp(0.5 - d, 0.0, 1.0);
        col *= edgeAlpha;

        return clamp(col, 0.0, 1.0);
    }
"""

/**
 * Native Apple Liquid Glass modifier for Android 13+ (API 33+) with AGSL RuntimeShader.
 *
 * Implements a live, voluminous 3D water droplet surface with dynamic key-light glint,
 * circular meniscus curvature, physical Fresnel reflectance, and fluid animation.
 *
 * When [enabled] is false, instantly falls back to classic lightweight MirrlyUI
 * (transparent background + 1dp AmoledBorder), preserving battery and GPU cycles.
 */
@Composable
fun Modifier.liquidGlass(
    enabled: Boolean = true,
    cornerRadiusDp: Dp = 18.dp,
    warpStrength: Float = 0.20f,
    tint: Color = Color(0xFF04060A).copy(alpha = 0.22f)
): Modifier {
    val shape = remember(cornerRadiusDp) { RoundedCornerShape(cornerRadiusDp) }

    if (!enabled) {
        return this
            .clip(shape)
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, shape)
    }

    val density = LocalDensity.current
    val primaryColor = LocalProtocolColors.current.primary

    // 60/120 FPS continuous orbital time animation for liquid light glint
    val infiniteTransition = rememberInfiniteTransition(label = "liquidGlassClock")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283185f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "liquidGlassTime"
    )

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember {
            try {
                RuntimeShader(LIQUID_GLASS_AGSL)
            } catch (t: Throwable) {
                null
            }
        }
        val paint = remember { Paint() }

        if (shader != null) {
            this
                .clip(shape)
                .drawBehind {
                    if (size.width > 0f && size.height > 0f) {
                        shader.setFloatUniform("size", size.width, size.height)
                        shader.setFloatUniform("cornerRadius", with(density) { cornerRadiusDp.toPx() })
                        shader.setFloatUniform("time", time)
                        shader.setFloatUniform("warpStrength", warpStrength)
                        shader.setFloatUniform("glassTint", tint.red, tint.green, tint.blue, tint.alpha)
                        shader.setFloatUniform(
                            "primaryAccent",
                            primaryColor.red,
                            primaryColor.green,
                            primaryColor.blue,
                            primaryColor.alpha
                        )

                        paint.shader = shader
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
                        }
                    }
                }
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.18f),
                            primaryColor.copy(alpha = 0.28f),
                            AmoledBorder.copy(alpha = 0.40f)
                        )
                    ),
                    shape = shape
                )
        } else {
            // Fallback if RuntimeShader fails to compile
            this
                .clip(shape)
                .background(tint)
                .drawBehind {
                    val sheenShift = (time / 6.283185f) * size.width
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.22f),
                                Color.Transparent
                            ),
                            startX = sheenShift - size.width * 0.4f,
                            endX = sheenShift + size.width * 0.4f
                        ),
                        topLeft = Offset.Zero,
                        size = Size(size.width, 1.5.dp.toPx())
                    )
                }
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            primaryColor.copy(alpha = 0.35f),
                            AmoledBorder.copy(alpha = 0.70f)
                        )
                    ),
                    shape = shape
                )
        }
    } else {
        // Fallback for Android 12 and below:
        // Translucent dark tint + subtle animated cut-glass gradient border & sheen (never blur text!)
        this
            .clip(shape)
            .background(tint)
            .drawBehind {
                val sheenShift = (time / 6.283185f) * size.width
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.20f),
                            Color.Transparent
                        ),
                        startX = sheenShift - size.width * 0.4f,
                        endX = sheenShift + size.width * 0.4f
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, 1.5.dp.toPx())
                )
            }
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.20f),
                        primaryColor.copy(alpha = 0.30f),
                        AmoledBorder.copy(alpha = 0.70f)
                    )
                ),
                shape = shape
            )
    }
}
