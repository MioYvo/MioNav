package io.mionav.watch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * Compass for the scene rotation.
 *
 * A motorcycle handlebar is a hostile place for a magnetometer: the engine, the speaker and the
 * fork bolts all bend the field. So the watch only rotates while it is moving — under
 * [MOVING_KMH] the last good heading is held — and the rotation-vector sensor (which fuses
 * accelerometer and magnetometer) is preferred over the raw pair.
 */
class HeadingProvider(context: Context) {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mag = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val matrix = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Circular low-pass: averaging degrees directly jumps at the 359 -> 1 seam. */
    private var sinSum = 0.0
    private var cosSum = 0.0
    private var samples = 0

    @Volatile
    var headingDeg: Float = 0f
        private set

    @Volatile
    var moving: Boolean = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val r = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(r, event.values)
                    push(r)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, gravity, 0, 3)
                    if (rotation == null) pushFromVectors()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                    if (rotation == null) pushFromVectors()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun pushFromVectors() {
        if (SensorManager.getRotationMatrix(matrix, null, gravity, geomagnetic)) push(matrix)
    }

    private fun push(r: FloatArray) {
        SensorManager.getOrientation(r, orientation)
        val deg = Math.toDegrees(orientation[0].toDouble())
        // Screen mounted across the bars: the watch reports 0 when it points at the rider's left.
        val corrected = (deg + MOUNT_OFFSET_DEG) % 360.0
        val rad = Math.toRadians(corrected)
        sinSum += sin(rad)
        cosSum += cos(rad)
        samples = (samples + 1).coerceAtMost(WEIGHT)
        val mean = Math.toDegrees(kotlin.math.atan2(sinSum, cosSum))
        headingDeg = ((mean + 360.0) % 360.0).toFloat()
    }

    /** Speed comes from the NAV frame; below walking pace the compass is noise. */
    fun onSpeed(speedKmh: Int) {
        moving = speedKmh >= MOVING_KMH
        if (!moving) {
            sinSum = sin(Math.toRadians(headingDeg.toDouble())) * samples
            cosSum = cos(Math.toRadians(headingDeg.toDouble())) * samples
        }
    }

    fun start() {
        val chosen = rotation ?: accel
        if (chosen != null) manager.registerListener(listener, chosen, SensorManager.SENSOR_DELAY_UI)
        if (rotation == null && mag != null) {
            manager.registerListener(listener, mag, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() = manager.unregisterListener(listener)

    private companion object {
        const val MOVING_KMH = 8
        const val WEIGHT = 12
        /** Rotate if the watch sits portrait but the rider reads it turned 90 degrees. */
        const val MOUNT_OFFSET_DEG = 0.0
    }
}
