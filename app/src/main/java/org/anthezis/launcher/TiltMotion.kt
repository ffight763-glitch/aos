package org.anthezis.launcher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs

/** Gentle, calibrated motion depth. Register only while the owning surface is visible. */
internal class TiltMotion(private val context: Context, private val update: (Float, Float) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var active = false
    private var calibrated = false
    private var neutralX = 0f
    private var neutralY = 0f
    private var x = 0f
    private var y = 0f

    fun start() {
        if (active || sensor == null || !Fx.on(context, "tilt")) return
        calibrated = false
        active = sensors.registerListener(this, sensor, 33_333)
    }

    fun stop() {
        if (active) sensors.unregisterListener(this)
        active = false; calibrated = false; x = 0f; y = 0f
        update(0f, 0f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        if (!Fx.on(context, "tilt")) { stop(); return }
        val gx = event.values[0] / SensorManager.GRAVITY_EARTH
        val gy = event.values[1] / SensorManager.GRAVITY_EARTH
        @Suppress("DEPRECATION")
        val rotation = context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
        val (rx, ry) = when (rotation) {
            Surface.ROTATION_90 -> gy to -gx
            Surface.ROTATION_180 -> -gx to -gy
            Surface.ROTATION_270 -> -gy to gx
            else -> gx to gy
        }
        if (!calibrated) { neutralX = rx; neutralY = ry; calibrated = true }
        val nextX = x + (((rx - neutralX) / 0.5f).coerceIn(-1f, 1f) - x) * 0.12f
        val nextY = y + (((ry - neutralY) / 0.5f).coerceIn(-1f, 1f) - y) * 0.12f
        if (abs(nextX - x) + abs(nextY - y) < 0.001f) return
        x = nextX; y = nextY; update(x, y)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
