/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * Tactile Haptic Composition & Low-Latency Procedural Audio Synthesizer
 */

package com.mirrly.tgproxy.ui.snake

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.mirrly.tgproxy.core.AppLogger
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Controller providing crisp tactile feedback & algorithmic 8-bit sound synthesis.
 * Consumes 0 KB of static media assets.
 */
class SnakeAcousticEngine(private val context: Context) {
    private val TAG = "SnakeAcousticEngine"

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Throwable) {
        null
    }

    // Pre-synthesized PCM 16-bit sound buffers
    private var eatTrack: AudioTrack? = null
    private var turnTrack: AudioTrack? = null
    private var dieTrack: AudioTrack? = null

    var isSoundEnabled: Boolean = true
    var isHapticEnabled: Boolean = true

    private fun canVibrate(): Boolean {
        if (!isHapticEnabled || vibrator?.hasVibrator() != true) return false
        return try {
            val prefs = com.mirrly.tgproxy.MirrlyApplication.instance.prefsManager
            !com.mirrly.tgproxy.service.HapticProfile.isVibrationMuted(
                isGlobalDisabled = prefs.isVibrationDisabled(),
                profile = prefs.getHapticProfile()
            )
        } catch (_: Throwable) {
            true
        }
    }

    init {
        try {
            initAudioTracks()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Procedural audio init failed: ${t.message}")
        }
    }

    private fun initAudioTracks() {
        val sampleRate = 44100

        // 1. Eat Sound: Harmonic Ascending Arpeggio (C5 -> E5 -> G5)
        val eatSamples = synthesizeArpeggio(
            sampleRate = sampleRate,
            freqs = floatArrayOf(523.25f, 659.25f, 783.99f),
            durationMs = 70
        )
        eatTrack = createStaticTrack(eatSamples, sampleRate)

        // 2. Turn Sound: Crisp subtle micro-click (800 Hz -> 400 Hz decay, 12 ms)
        val turnSamples = synthesizeClick(sampleRate = sampleRate, durationMs = 12)
        turnTrack = createStaticTrack(turnSamples, sampleRate)

        // 3. Die Sound: Low frequency noise descent (160 Hz -> 45 Hz, 140 ms)
        val dieSamples = synthesizeCrash(sampleRate = sampleRate, durationMs = 140)
        dieTrack = createStaticTrack(dieSamples, sampleRate)
    }

    private fun createStaticTrack(pcmData: ShortArray, sampleRate: Int): AudioTrack {
        val bufferSize = pcmData.size * 2 // 2 bytes per 16-bit sample
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcmData, 0, pcmData.size)
        return track
    }

    private fun synthesizeArpeggio(sampleRate: Int, freqs: FloatArray, durationMs: Int): ShortArray {
        val totalSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val buffer = ShortArray(totalSamples)
        val segmentLen = totalSamples / freqs.size

        for (i in 0 until totalSamples) {
            val segIdx = (i / segmentLen).coerceIn(0, freqs.size - 1)
            val freq = freqs[segIdx]
            val t = i.toDouble() / sampleRate
            val progress = i.toFloat() / totalSamples
            val envelope = (1.0f - progress).toDouble().pow(1.6)
            val sample = sin(2.0 * PI * freq * t) * envelope * 0.45 * Short.MAX_VALUE
            buffer[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return buffer
    }

    private fun synthesizeClick(sampleRate: Int, durationMs: Int): ShortArray {
        val totalSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val buffer = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val progress = i.toFloat() / totalSamples
            val freq = 800.0 - (progress * 400.0)
            val t = i.toDouble() / sampleRate
            val envelope = (1.0f - progress).toDouble().pow(3.0)
            val sample = sin(2.0 * PI * freq * t) * envelope * 0.25 * Short.MAX_VALUE
            buffer[i] = sample.toInt().toShort()
        }
        return buffer
    }

    private fun synthesizeCrash(sampleRate: Int, durationMs: Int): ShortArray {
        val totalSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val buffer = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val progress = i.toFloat() / totalSamples
            val freq = 160.0 - (progress * 115.0)
            val t = i.toDouble() / sampleRate
            val envelope = (1.0f - progress).toDouble().pow(2.0)
            val sample = sin(2.0 * PI * freq * t) * envelope * 0.5 * Short.MAX_VALUE
            buffer[i] = sample.toInt().toShort()
        }
        return buffer
    }

    // =========================================================================
    // Public Playback API
    // =========================================================================

    fun playTurn() {
        if (isSoundEnabled) {
            try {
                turnTrack?.stop()
                turnTrack?.reloadStaticData()
                turnTrack?.play()
            } catch (_: Throwable) {}
        }

        if (canVibrate()) {
            val activeVibrator = vibrator ?: return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    activeVibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
                ) {
                    activeVibrator.vibrate(
                        VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f)
                            .compose()
                    )
                } else {
                    activeVibrator.vibrate(VibrationEffect.createOneShot(8, 70))
                }
            } catch (_: Throwable) {}
        }
    }

    fun playEat() {
        if (isSoundEnabled) {
            try {
                eatTrack?.stop()
                eatTrack?.reloadStaticData()
                eatTrack?.play()
            } catch (_: Throwable) {}
        }

        if (canVibrate()) {
            val activeVibrator = vibrator ?: return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    activeVibrator.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                        VibrationEffect.Composition.PRIMITIVE_CLICK
                    )
                ) {
                    activeVibrator.vibrate(
                        VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.5f)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f, 20)
                            .compose()
                    )
                } else {
                    activeVibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 15, 30, 20), intArrayOf(0, 120, 0, 220), -1))
                }
            } catch (_: Throwable) {}
        }
    }

    fun playDie() {
        if (isSoundEnabled) {
            try {
                dieTrack?.stop()
                dieTrack?.reloadStaticData()
                dieTrack?.play()
            } catch (_: Throwable) {}
        }

        if (canVibrate()) {
            val activeVibrator = vibrator ?: return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    activeVibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
                ) {
                    activeVibrator.vibrate(
                        VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                            .compose()
                    )
                } else {
                    activeVibrator.vibrate(VibrationEffect.createOneShot(35, 200))
                }
            } catch (_: Throwable) {}
        }
    }

    fun release() {
        try {
            eatTrack?.release()
            turnTrack?.release()
            dieTrack?.release()
        } catch (_: Throwable) {}
    }
}
