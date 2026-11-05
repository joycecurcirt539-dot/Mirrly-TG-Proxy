package com.mirrly.tgproxy.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Hardware Gyroscopic Tilt Engine with Low-Pass Filtering and Zero-Allocation polling.
 *
 * Provides smoothed 2D normalized tilt coordinates (-1f..1f) based on device rotation
 * vector sensor (falling back to gravity or accelerometer).
 */
@Composable
fun rememberSensorTilt(
    enabled: Boolean,
    powerSaveActive: Boolean,
    depthMultiplier: Float = 1.0f
): State<Offset> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tiltState = remember { mutableStateOf(Offset.Zero) }
    val shouldListen = enabled && !powerSaveActive

    DisposableEffect(context, lifecycleOwner, shouldListen, depthMultiplier) {
        if (!shouldListen) {
            tiltState.value = Offset.Zero
            return@DisposableEffect onDispose {}
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (sensorManager == null || sensor == null) {
            return@DisposableEffect onDispose {}
        }

        val rotationMatrix = FloatArray(9)
        val orientationValues = FloatArray(3)
        var filteredX = 0f
        var filteredY = 0f
        var isRegistered = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val type = event.sensor.type
                var targetX = 0f
                var targetY = 0f

                if (type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationValues)
                    val pitch = orientationValues[1] // radians, tilt around X axis
                    val roll = orientationValues[2]  // radians, tilt around Y axis

                    // Natural handheld reading angle: ~ -37° (-0.65 rad) pitch
                    // ±0.60 rad (~34°) normalized to [-1f..1f]
                    targetX = (roll / 0.60f).coerceIn(-1f, 1f)
                    targetY = ((pitch - (-0.65f)) / 0.60f).coerceIn(-1f, 1f)
                } else {
                    // Fallback to TYPE_GRAVITY or TYPE_ACCELEROMETER
                    val gx = -event.values[0] // Inverted for screen coordinate matching
                    val gy = event.values[1]
                    targetX = (gx / 5.0f).coerceIn(-1f, 1f)
                    targetY = ((gy - 6.5f) / 5.0f).coerceIn(-1f, 1f)
                }

                // Low-Pass Filter (alpha ≈ 0.14) provides instantaneous tactile response without jitter
                val alpha = 0.14f
                filteredX += alpha * (targetX - filteredX)
                filteredY += alpha * (targetY - filteredY)

                tiltState.value = Offset(filteredX * depthMultiplier, filteredY * depthMultiplier)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        fun register() {
            if (!isRegistered) {
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                isRegistered = true
            }
        }

        fun unregister() {
            if (isRegistered) {
                sensorManager.unregisterListener(listener)
                isRegistered = false
                tiltState.value = Offset.Zero
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> unregister()
                else -> {}
            }
        }

        val currentLifecycle = lifecycleOwner.lifecycle
        currentLifecycle.addObserver(observer)
        if (currentLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            register()
        }

        onDispose {
            currentLifecycle.removeObserver(observer)
            unregister()
        }
    }

    return tiltState
}
