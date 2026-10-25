package com.motocam.g37.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.sqrt

data class GyroState(
    val angularSpeedRadPerSec: Float = 0f,
    val isHandSteady: Boolean = true,
    val rollDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val isHorizonLevel: Boolean = true
)

class GyroStabilityMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _gyroState = MutableStateFlow(GyroState())
    val gyroState: StateFlow<GyroState> = _gyroState.asStateFlow()

    companion object {
        // Below 0.065 rad/s (~3.7 deg/sec) yields razor-sharp 1/20s handheld exposures without OIS
        const val STEADY_THRESHOLD_RAD_S = 0.065f
    }

    fun start() {
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /**
     * Waits up to [maxWaitMs] for a micro-window of minimal hand tremor before firing shutter.
     */
    suspend fun awaitStableWindow(maxWaitMs: Long = 350L): Float {
        if (gyroSensor == null) return 0f
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
            val currentSpeed = _gyroState.value.angularSpeedRadPerSec
            if (currentSpeed <= STEADY_THRESHOLD_RAD_S) {
                return currentSpeed
            }
            delay(15L)
        }
        return _gyroState.value.angularSpeedRadPerSec
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val wx = event.values[0]
                val wy = event.values[1]
                val wz = event.values[2]
                val speed = sqrt(wx * wx + wy * wy + wz * wz)
                val smoothed = _gyroState.value.angularSpeedRadPerSec * 0.6f + speed * 0.4f
                _gyroState.value = _gyroState.value.copy(
                    angularSpeedRadPerSec = smoothed,
                    isHandSteady = smoothed <= STEADY_THRESHOLD_RAD_S
                )
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                val rollRad = atan2(-gx.toDouble(), gy.toDouble())
                val rollDeg = Math.toDegrees(rollRad).toFloat()
                val pitchDeg = Math.toDegrees(atan2(gz.toDouble(), sqrt((gx * gx + gy * gy).toDouble()))).toFloat()
                _gyroState.value = _gyroState.value.copy(
                    rollDegrees = rollDeg,
                    pitchDegrees = pitchDeg,
                    isHorizonLevel = kotlin.math.abs(rollDeg) < 1.5f
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
