/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiquidGlassTest {

    @Test
    fun testLiquidGlass3dWaterDropletShaderInvariants() {
        val file = File("src/main/java/com/mirrly/tgproxy/ui/theme/LiquidGlass.kt")
        val altFile = File("app/src/main/java/com/mirrly/tgproxy/ui/theme/LiquidGlass.kt")
        val source = if (file.exists()) file.readText() else altFile.readText()

        // 1. Must define dynamic 3D lighting, time and tint uniforms
        assertTrue("Shader must declare uniform float time", source.contains("uniform float time;"))
        assertTrue("Shader must declare uniform float4 glassTint", source.contains("uniform float4 glassTint;"))
        assertTrue("Shader must declare uniform float4 primaryAccent", source.contains("uniform float4 primaryAccent;"))

        // 2. Must model 3D water droplet meniscus curvature
        assertTrue(
            "Shader must model circular meniscus arc h(u) = sqrt(u * (2 - u))",
            source.contains("sqrt(max(u * (2.0 - u), 0.0))")
        )

        // 3. Must calculate true 3D surface normal and orbital light
        assertTrue(
            "Shader must reconstruct 3D surface normal",
            source.contains("vec3 N = normalize(")
        )
        assertTrue(
            "Shader must calculate Blinn-Phong specular glint",
            source.contains("pow(NdotH, 64.0)")
        )
        assertTrue(
            "Shader must calculate physical Fresnel reflectance",
            source.contains("fresnel = 0.04 + 0.96 * pow(1.0 - cosTheta, 4.0);")
        )

        // 4. Must NOT warp child text or hardcode white specular
        assertFalse(
            "Shader must not hardcode specular RGB to 1.0 (causes solid white screen)",
            source.contains("vec4 specular = vec4(1.0, 1.0, 1.0,")
        )

        // 5. Must clamp final result to prevent buffer saturation
        assertTrue(
            "Shader must clamp final result to prevent buffer saturation",
            source.contains("return clamp(col, 0.0, 1.0);")
        )
    }
}
