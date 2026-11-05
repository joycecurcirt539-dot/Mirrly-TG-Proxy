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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.RuntimeShader
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mirrly.tgproxy.service.AgslShaderPreset

/**
 * Procedural AGSL Shader sources for Android 13+ (API 33+).
 * Pure GPU execution with zero CPU battery drain.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object AgslShaderSource {

    const val PERSPECTIVE_GRID = """
        uniform float2 u_resolution;
        uniform float u_time;
        uniform float3 u_accent;

        half4 main(float2 fragCoord) {
            float horizon = u_resolution.y * 0.44;
            if (fragCoord.y < horizon) {
                float glow = exp(-(horizon - fragCoord.y) * 0.04) * 0.28;
                return half4(u_accent * glow, 1.0);
            }

            float d = (fragCoord.y - horizon) / (u_resolution.y - horizon);
            float z = 1.0 / (d * 0.85 + 0.04);
            float x = (fragCoord.x - u_resolution.x * 0.5) / (u_resolution.y - horizon);

            float worldX = x * z;
            float worldZ = z - u_time * 1.8;

            float fx = abs(fract(worldX * 0.6) - 0.5);
            float fz = abs(fract(worldZ * 0.6) - 0.5);

            float lineWidthX = (0.025 * z) * 0.5;
            float lineWidthZ = (0.025 * z) * 0.5;

            float lineX = 1.0 - smoothstep(0.0, clamp(lineWidthX, 0.03, 0.22), fx);
            float lineZ = 1.0 - smoothstep(0.0, clamp(lineWidthZ, 0.03, 0.22), fz);
            float grid = max(lineX, lineZ);

            float fade = smoothstep(0.02, 0.25, d) * smoothstep(1.0, 0.85, d);
            float horizonGlow = exp(-abs(fragCoord.y - horizon) * 0.05) * 0.35;

            float3 col = u_accent * (grid * fade * 0.75 + horizonGlow);
            return half4(col, 1.0);
        }
    """

    const val DIGITAL_STREAM = """
        uniform float2 u_resolution;
        uniform float u_time;
        uniform float3 u_accent;

        float streamHash(float n) {
            return fract(sin(n * 12.9898) * 43758.5453);
        }

        half4 main(float2 fragCoord) {
            float colWidth = 24.0;
            float colIndex = floor(fragCoord.x / colWidth);
            float colX = (fragCoord.x - colIndex * colWidth) / colWidth;

            float seed = streamHash(colIndex + 1.0);
            float speed = 0.35 + streamHash(colIndex * 3.17) * 0.65;
            float offset = streamHash(colIndex * 7.91) * 100.0;
            float trail = 0.22 + streamHash(colIndex * 5.31) * 0.35;

            float normY = fragCoord.y / u_resolution.y;
            float streamY = fract(normY + u_time * speed * 0.22 + offset);

            float fall = smoothstep(1.0 - trail, 1.0, streamY) * smoothstep(1.0, 0.98, streamY);
            float lateral = smoothstep(0.5, 0.0, abs(colX - 0.5));
            float head = smoothstep(0.96, 1.0, streamY) * lateral * 1.6;

            float intensity = (fall * 0.6 + head * 0.8) * lateral;
            float3 col = u_accent * intensity;
            return half4(col, 1.0);
        }
    """

    const val SCANLINES = """
        uniform float2 u_resolution;
        uniform float u_time;
        uniform float3 u_accent;

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / u_resolution;
            float scan = sin(fragCoord.y * 1.8) * 0.07 + 0.93;
            float roll = sin(uv.y * 4.0 - u_time * 0.7) * 0.035;
            scan += roll;

            float2 vig = (uv - 0.5) * 1.5;
            float vignette = clamp(1.0 - dot(vig, vig) * 0.55, 0.0, 1.0);

            float dist = length(uv - 0.5);
            float center = exp(-dist * 2.5) * 0.18;
            float3 base = u_accent * center;

            float shift = dist * dist * 0.025;
            float r = (base.r + shift * 0.15) * scan * vignette;
            float g = base.g * scan * vignette;
            float b = (base.b + shift * 0.25) * scan * vignette;

            return half4(r, g, b, 1.0);
        }
    """
}

/**
 * Hardware-accelerated AGSL runtime shader canvas composable.
 * Supports [AgslShaderPreset.PERSPECTIVE_GRID], [AgslShaderPreset.DIGITAL_STREAM],
 * and [AgslShaderPreset.SCANLINES].
 *
 * Automatically pauses animation when app is backgrounded to protect battery.
 */
@Composable
fun AgslWallpaperShader(
    preset: AgslShaderPreset,
    accentColor: Color,
    modifier: Modifier = Modifier,
    speed: Float = 1.0f
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // Fallback for Android < 13
        return
    }

    AgslWallpaperShaderImpl(
        preset = preset,
        accentColor = accentColor,
        speed = speed,
        modifier = modifier
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AgslWallpaperShaderImpl(
    preset: AgslShaderPreset,
    accentColor: Color,
    speed: Float = 1.0f,
    modifier: Modifier = Modifier
) {
    val shaderCode = remember(preset) {
        when (preset) {
            AgslShaderPreset.PERSPECTIVE_GRID -> AgslShaderSource.PERSPECTIVE_GRID
            AgslShaderPreset.DIGITAL_STREAM -> AgslShaderSource.DIGITAL_STREAM
            AgslShaderPreset.SCANLINES -> AgslShaderSource.SCANLINES
        }
    }

    val runtimeShader = remember(shaderCode) {
        try {
            RuntimeShader(shaderCode)
        } catch (_: Throwable) {
            null
        }
    }

    if (runtimeShader == null) return

    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var isPaused by remember { mutableStateOf(false) }
    var isPowerSaveMode by remember { mutableStateOf(false) }
    var isLowBattery by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isPaused = (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Power Guard: Pause procedural AGSL animations if Power Save Mode is active or battery < 15%
    DisposableEffect(context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        isPowerSaveMode = powerManager?.isPowerSaveMode == true

        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        isPowerSaveMode = powerManager?.isPowerSaveMode == true
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        if (level >= 0 && scale > 0) {
                            val pct = (level * 100) / scale
                            isLowBattery = pct < 15
                        }
                    }
                }
            }
        }
        val initialBattery = context.registerReceiver(receiver, filter)
        if (initialBattery != null) {
            val level = initialBattery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = initialBattery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                isLowBattery = ((level * 100) / scale) < 15
            }
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Throwable) {}
        }
    }

    val shouldPause = isPaused || isPowerSaveMode || isLowBattery
    val currentSpeed by rememberUpdatedState(speed)

    val timeSeconds by produceState(initialValue = 0f, shouldPause) {
        if (shouldPause) return@produceState
        var lastNano = System.nanoTime()
        var accumulatedTime = 0f
        while (!shouldPause) {
            withFrameNanos { frameTimeNanos ->
                val dt = (frameTimeNanos - lastNano) / 1_000_000_000f
                lastNano = frameTimeNanos
                accumulatedTime += dt.coerceIn(0f, 0.1f) * currentSpeed
                value = accumulatedTime
            }
        }
    }

    val brush = remember(runtimeShader) { ShaderBrush(runtimeShader) }

    Canvas(modifier = modifier.fillMaxSize()) {
        try {
            runtimeShader.setFloatUniform("u_resolution", size.width, size.height)
            runtimeShader.setFloatUniform("u_time", timeSeconds)
            runtimeShader.setFloatUniform("u_accent", accentColor.red, accentColor.green, accentColor.blue)
            drawRect(brush)
        } catch (_: Throwable) {
            // Guard against runtime shader uniform errors
        }
    }
}
