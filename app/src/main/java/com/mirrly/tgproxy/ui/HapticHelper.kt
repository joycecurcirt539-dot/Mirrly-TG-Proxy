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

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Advanced Hardware Haptic Feedback Controller.
 * Provides distinct, richly textured tactile vibration profiles for UI gestures:
 * - Tap: Crisp, short, and snappy click.
 * - Swipe: Smooth, elongated, multi-amplitude wave texture corresponding to the physical glide.
 * - Tick: Subtle soft detent tick.
 */
object HapticHelper {

    @Suppress("DEPRECATION")
    private fun getVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Crisp, snappy haptic click for standard taps and switches.
     */
    fun performTapClick(context: Context) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(12, 110))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(12)
            }
        } catch (_: Exception) {
            // Ignore if vibration service is unavailable or muted by system policy
        }
    }

    /**
     * Rich, elongated, silky-smooth waveform haptic texture for tactile swipe gestures.
     * Uses hardware primitive composition on modern devices (Android 12+) or smooth wave envelopes.
     */
    fun performSwipeGlide(context: Context) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    VibrationEffect.Composition.PRIMITIVE_SPIN,
                    VibrationEffect.Composition.PRIMITIVE_TICK
                )) {
                val composition = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.35f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, 0.65f, 25)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f, 20)
                    .compose()
                vibrator.vibrate(composition)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Smooth envelope wave: Soft start -> Mid swell -> Soft decay
                val timings = longArrayOf(0, 16, 22, 28, 20, 16)
                val amplitudes = intArrayOf(0, 35, 75, 125, 70, 35)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 20, 25, 20), -1)
            }
        } catch (_: Exception) {
            // Ignore if vibration service is unavailable or muted by system policy
        }
    }

    /**
     * Subtle micro tick for drag boundary contact.
     */
    fun performSoftTick(context: Context) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(8, 60))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(8)
            }
        } catch (_: Exception) {
            // Ignore
        }
    }
}
