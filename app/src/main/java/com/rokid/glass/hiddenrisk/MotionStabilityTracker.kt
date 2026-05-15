package com.rokid.glass.hiddenrisk

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * 用于巡检链路的“相对静止”判断。
 * 当俯仰/偏航陀螺仪速率持续低于阈值一段时间后，认为用户保持静止。
 */
class MotionStabilityTracker(
    context: Context,
    private val stableDurationMs: Long = 500L,
    private val quietGyroMaxRad: Float = 0.20f,
) : SensorEventListener {

    interface Listener {
        fun onStabilityChanged(isStable: Boolean, stableSinceMillis: Long?)
    }

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroscopeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<Listener>()

    private var started = false
    private var quietWindowStartedAt: Long? = null
    private var stableQualifiedAt: Long? = null
    private var isStable = false

    fun isSupported(): Boolean = sensorManager != null && gyroscopeSensor != null

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun start(): Boolean {
        if (started) {
            return true
        }
        val manager = sensorManager ?: return false
        val sensor = gyroscopeSensor ?: return false
        reset()
        started = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        return started
    }

    fun stop() {
        if (!started) {
            return
        }
        sensorManager?.unregisterListener(this)
        started = false
        reset()
    }

    fun reset() {
        quietWindowStartedAt = null
        stableQualifiedAt = null
        if (isStable) {
            isStable = false
            notifyListeners()
        }
    }

    fun currentStableSinceMillis(): Long? = stableQualifiedAt

    override fun onSensorChanged(event: SensorEvent) {
        val gyroPitch = event.values.getOrElse(0) { 0f }
        val gyroYaw = event.values.getOrElse(2) { 0f }
        val quiet = abs(gyroPitch) <= quietGyroMaxRad && abs(gyroYaw) <= quietGyroMaxRad
        val now = System.currentTimeMillis()
        if (!quiet) {
            quietWindowStartedAt = null
            stableQualifiedAt = null
            if (isStable) {
                isStable = false
                notifyListeners()
            }
            return
        }

        val quietStart = quietWindowStartedAt ?: now.also { quietWindowStartedAt = it }
        val stableNow = now - quietStart >= stableDurationMs
        if (stableNow) {
            if (!isStable) {
                isStable = true
                stableQualifiedAt = quietStart + stableDurationMs
                notifyListeners()
            }
        } else if (isStable) {
            isStable = false
            stableQualifiedAt = null
            notifyListeners()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun notifyListeners() {
        val stableSince = stableQualifiedAt
        listeners.forEach { listener ->
            mainHandler.post {
                listener.onStabilityChanged(isStable, stableSince)
            }
        }
    }
}
