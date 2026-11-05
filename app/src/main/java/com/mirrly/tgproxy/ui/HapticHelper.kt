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
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.service.HapticProfile

/**
 * Advanced Hardware Haptic Feedback Controller.
 * Provides distinct, richly textured tactile vibration profiles for UI gestures:
 * - Mechanical Click: Crisp, short, and snappy mechanical toggle click.
 * - Sub-bass Pulse: Deep low-frequency tactile rumble.
 * - Cybernetic Double-Tick: Rapid dual micro-ticks in close succession.
 * - Stealth: Silent mode (disabled).
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

    private fun getActiveProfile(): HapticProfile {
        return try {
            MirrlyApplication.instance.prefsManager.getHapticProfile()
        } catch (_: Throwable) {
            HapticProfile.MECHANICAL_CLICK
        }
    }

    /**
     * Executes tap/click vibration according to the active [HapticProfile].
     * An [overrideProfile] can be supplied to preview a specific profile without saving it.
     */
    fun performTapClick(context: Context, overrideProfile: HapticProfile? = null) {
        val profile = overrideProfile ?: getActiveProfile()
        if (profile == HapticProfile.STEALTH) return

        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            when (profile) {
                HapticProfile.STEALTH -> return

                HapticProfile.MECHANICAL_CLICK -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(14, 180))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(14)
                    }
                }

                HapticProfile.SUBBASS_PULSE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(
                            VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                        )) {
                        val composition = VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 1.0f)
                            .compose()
                        vibrator.vibrate(composition)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val timings = longArrayOf(0, 32, 12, 28)
                        val amplitudes = intArrayOf(0, 255, 0, 170)
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(longArrayOf(0, 32, 12, 28), -1)
                    }
                }

                HapticProfile.CYBERNETIC_DOUBLE_TICK -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(
                            VibrationEffect.Composition.PRIMITIVE_TICK
                        )) {
                        val composition = VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.60f)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1.0f, 32)
                            .compose()
                        vibrator.vibrate(composition)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val timings = longArrayOf(0, 10, 28, 12)
                        val amplitudes = intArrayOf(0, 160, 0, 240)
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(longArrayOf(0, 10, 25, 12), -1)
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore if vibration service is unavailable or muted by system policy
        }
    }

    /**
     * Rich, elongated, silky-smooth waveform haptic texture for tactile swipe gestures.
     * Silent in [HapticProfile.STEALTH].
     */
    fun performSwipeGlide(context: Context) {
        if (getActiveProfile() == HapticProfile.STEALTH) return

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
                val timings = longArrayOf(0, 16, 22, 28, 20, 16)
                val amplitudes = intArrayOf(0, 35, 75, 125, 70, 35)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 20, 25, 20), -1)
            }
        } catch (_: Exception) {
            // Ignore
        }
    }

    /**
     * Subtle micro tick for drag boundary contact.
     * Silent in [HapticProfile.STEALTH].
     */
    fun performSoftTick(context: Context) {
        if (getActiveProfile() == HapticProfile.STEALTH) return

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
