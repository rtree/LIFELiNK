package com.rtree.LIFELiNK

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import kotlin.math.abs
import kotlin.math.sqrt

data class DeviceSignals(
    val batteryPercent: Int?,
    val batteryCharging: Boolean?,
    val motionState: String?,
    val motionPeakG: Float?,
)

private data class BatteryStatus(val percent: Int?, val charging: Boolean?)

private fun readBattery(context: Context): BatteryStatus {
    val intent: Intent? = try {
        context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (e: Exception) {
        null
    }
    if (intent == null) return BatteryStatus(null, null)

    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (level >= 0 && scale > 0) {
        (level * 100f / scale).toInt().coerceIn(0, 100)
    } else {
        null
    }

    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
        BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
        else -> null
    }
    return BatteryStatus(percent, charging)
}

object MotionMonitor : SensorEventListener {

    // Provisional thresholds in G above gravity; not yet validated on a real device.
    private const val STILL_PEAK_G = 0.25f
    private const val MOVING_PEAK_G = 1.2f
    private const val WINDOW_MS = 3_000L

    const val STATE_STILL = "still"
    const val STATE_MOVING = "moving"
    const val STATE_SHAKING = "shaking"

    private val lock = Any()
    private var sensorManager: SensorManager? = null
    private val samples = ArrayDeque<Pair<Long, Float>>()

    fun start(context: Context) {
        synchronized(lock) {
            if (sensorManager != null) return
            val manager = context.applicationContext
                .getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            samples.clear()
            if (manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)) {
                sensorManager = manager
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            sensorManager?.unregisterListener(this)
            sensorManager = null
            samples.clear()
        }
    }

    /** Returns null while no accelerometer sample is available. */
    fun current(): Pair<String, Float>? = synchronized(lock) {
        prune(System.currentTimeMillis())
        val peak = samples.maxOfOrNull { it.second } ?: return null
        val state = when {
            peak < STILL_PEAK_G -> STATE_STILL
            peak < MOVING_PEAK_G -> STATE_MOVING
            else -> STATE_SHAKING
        }
        state to peak
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitudeG = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val movement = abs(magnitudeG - 1f)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            samples.addLast(now to movement)
            prune(now)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun prune(now: Long) {
        while (samples.isNotEmpty() && now - samples.first().first > WINDOW_MS) {
            samples.removeFirst()
        }
    }
}

fun readDeviceSignals(context: Context): DeviceSignals {
    val battery = readBattery(context)
    val motion = MotionMonitor.current()
    return DeviceSignals(
        batteryPercent = battery.percent,
        batteryCharging = battery.charging,
        motionState = motion?.first,
        motionPeakG = motion?.second,
    )
}
