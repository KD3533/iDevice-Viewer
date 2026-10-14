/*
 * iPod Touch emulator integration (QEMU-iOS running in Termux).
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ipod

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import com.gaurav.avnc.session.Messenger
import com.gaurav.avnc.vnc.VncClient
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hands the phone's accelerometer to the emulated device, so tilting and
 * turning the phone tilts and turns the iPod: iOS rotates its interface,
 * games steer, shaking shakes.
 *
 * The phone's screen is the iPod's screen, so the reading is taken along the
 * axes of the screen as it is shown (Android may present the activity turned
 * against the phone's natural orientation). The emulated framebuffer stays
 * fixed to the screen, as the real panel is fixed to the iPod: a landscape
 * app is upright once the phone is held landscape.
 *
 * Only qemu-ios-sam takes the readings (the server acknowledges a private VNC
 * encoding, see native-vnc.cpp); with any other server nothing is sent.
 */
class IPodAccelerometer(
        private val activity: Activity,
        private val client: () -> VncClient?,
        private val messenger: () -> Messenger?,
) : SensorEventListener {

    companion object {
        /** Send at most this often (iOS 3 apps poll at up to 100 Hz, most at 30-60 Hz) */
        private const val MIN_INTERVAL_MS = 20L
        /** Smaller changes are noise; still repeat the reading this often */
        private const val MIN_CHANGE_MG = 8
        private const val REPEAT_MS = 1000L
    }

    private val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var lastSent = IntArray(3)
    private var lastSentAt = 0L

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSentAt < MIN_INTERVAL_MS)
            return
        if (client()?.acceptsAccelerometer != true)
            return

        // Android reports the reaction to gravity in m/s² (held upright: y = +9.8),
        // iOS the acceleration in g (held upright: y = -1)
        val ax = event.values[0]
        val ay = event.values[1]
        val (sx, sy) = when (displayRotation()) {
            Surface.ROTATION_90 -> -ay to ax
            Surface.ROTATION_180 -> -ax to -ay
            Surface.ROTATION_270 -> ay to -ax
            else -> ax to ay
        }
        val scale = -1000f / SensorManager.GRAVITY_EARTH
        val v = intArrayOf((sx * scale).roundToInt(), (sy * scale).roundToInt(), (event.values[2] * scale).roundToInt())
                .map { it.coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()) }

        val changed = v.indices.any { abs(v[it] - lastSent[it]) >= MIN_CHANGE_MG }
        if (!changed && now - lastSentAt < REPEAT_MS)
            return
        if (messenger()?.sendAccelerometer(v[0], v[1], v[2]) == true) {
            lastSent = v.toIntArray()
            lastSentAt = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun displayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= 30)
            activity.display?.rotation ?: Surface.ROTATION_0
        else
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.rotation
    }
}
