package com.rokid.glass.hiddenrisk

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * 头部动作识别管理器。
 * 触发不再依赖中性基线，而是依赖陀螺仪角加速度的突增与短窗动态波形。
 * rotation vector 仅保留为姿态观察与弱交叉轴过滤，不参与触发门槛。
 */
object HeadGestureManager : SensorEventListener {

    private const val TAG = "HeadGestureManager"
    private const val DEFAULT_POSE_SMOOTHING_ALPHA = 0.22f
    private const val DEFAULT_GYRO_SMOOTHING_ALPHA = 0.28f
    private const val DEFAULT_ACCEL_SMOOTHING_ALPHA = 0.18f
    private const val NANOS_TO_SECONDS = 1_000_000_000f

    enum class HeadGestureType {
        NOD,
        SHAKE,
    }

    data class HeadGestureEvent(
        val type: HeadGestureType,
        val timestampMillis: Long,
        val pitchDeg: Float,
        val yawDeg: Float,
        val gyroPitchRate: Float,
        val gyroYawRate: Float,
    )

    interface Listener {
        fun onHeadGesture(event: HeadGestureEvent)
    }

    private data class Config(
        val pulsesRequiredToEmit: Int = 1,
        val quietWindowNs: Long = 180_000_000L,
        val quietTriggerGraceNs: Long = 260_000_000L,
        val quietGyroMaxRad: Float = 0.18f,
        val quietAngularAccelMaxRad: Float = 2.4f,
        val minCandidateWindowNs: Long = 240_000_000L,
        val maxCandidateWindowNs: Long = 1_000_000_000L,
        val settleDurationNs: Long = 120_000_000L,
        val emitCooldownNs: Long = 450_000_000L,
        val pulseWindowNs: Long = 1_200_000_000L,
        val triggerPitchAngularAccelEnterRad: Float = 7.5f,
        val triggerYawAngularAccelEnterRad: Float = 6.0f,
        val triggerAngularAccelExitRad: Float = 2.4f,
        val axisDominanceRatio: Float = 1.35f,
        val maxCrossAxisDynamicRatio: Float = 0.72f,
        val settleRateDecayRatio: Float = 0.45f,
        val settleAccelDecayRatio: Float = 0.55f,
        val maxCrossAxisAngleTravelDeg: Float = 12f,
        val minPitchGyroPeakRad: Float = 0.90f,
        val minYawGyroPeakRad: Float = 0.60f,
        val minPitchAngularAccelPeakRad: Float = 7.5f,
        val minYawAngularAccelPeakRad: Float = 5.2f,
        val minPitchReversePeakRad: Float = 0.55f,
        val minYawReversePeakRad: Float = 0.42f,
        val minPitchAngleTravelDeg: Float = 4f,
        val minYawAngleTravelDeg: Float = 4.8f,
        val maxRateAsymmetry: Float = 0.65f,
        val poseSmoothingAlpha: Float = DEFAULT_POSE_SMOOTHING_ALPHA,
        val gyroSmoothingAlpha: Float = DEFAULT_GYRO_SMOOTHING_ALPHA,
        val angularAccelSmoothingAlpha: Float = DEFAULT_ACCEL_SMOOTHING_ALPHA,
        val enableDecisionLogs: Boolean = false,
        val enableSensorLogs: Boolean = false,
    )

    private data class DynamicSnapshot(
        val timestampNs: Long,
        val gyroPitchRate: Float,
        val gyroYawRate: Float,
        val angularAccelPitch: Float,
        val angularAccelYaw: Float,
        val pitchDeg: Float,
        val yawDeg: Float,
    )

    private data class GestureCandidate(
        val type: HeadGestureType,
        val startTimestampNs: Long,
        val startPitchDeg: Float,
        val startYawDeg: Float,
        val initialPrimaryDirection: Int,
        var lastTimestampNs: Long,
        var lastPrimaryRate: Float,
        var settleSinceNs: Long = 0L,
        var maxPitchDeg: Float = startPitchDeg,
        var minPitchDeg: Float = startPitchDeg,
        var maxYawDeg: Float = startYawDeg,
        var minYawDeg: Float = startYawDeg,
        var maxPrimaryRate: Float = 0f,
        var minPrimaryRate: Float = 0f,
        var maxCrossRateAbs: Float = 0f,
        var maxPrimaryAccelAbs: Float = 0f,
        var maxCrossAccelAbs: Float = 0f,
        var reversePeakAbs: Float = 0f,
        var signFlipCount: Int = 0,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val config = Config()

    @Volatile
    private var initialized = false

    @Volatile
    private var started = false

    private var sensorManager: SensorManager? = null
    private var rotationVectorSensor: Sensor? = null
    private var gyroscopeSensor: Sensor? = null

    private var latestPitchDeg = 0f
    private var latestYawDeg = 0f
    private var smoothedPitchDeg = 0f
    private var smoothedYawDeg = 0f
    private var hasSmoothedPose = false

    private var latestGyroValues = FloatArray(3)
    private var smoothedGyroValues = FloatArray(3)
    private var angularAccelValues = FloatArray(3)
    private var lastGyroValues = FloatArray(3)
    private var lastGyroTimestampNs = 0L
    private var hasGyroHistory = false

    private var lastYawRad: Float? = null
    private var unwrappedYawRad = 0f

    private var activeCandidate: GestureCandidate? = null
    private var lastDynamicActivityTimestampNs = 0L
    private var triggerArmedUntilNs = 0L
    private var lastEmitTimestampNs = 0L

    private val nodPulseTimestamps = ArrayDeque<Long>()
    private val shakePulseTimestamps = ArrayDeque<Long>()

    fun initialize(context: Context) {
        if (initialized) {
            return
        }
        val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager = manager
        rotationVectorSensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gyroscopeSensor = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        initialized = true
    }

    fun isSupported(): Boolean {
        return rotationVectorSensor != null && gyroscopeSensor != null
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun start(): Boolean {
        val manager = sensorManager
        val rotationSensor = rotationVectorSensor
        val gyroSensor = gyroscopeSensor
        if (started) {
            return true
        }
        if (manager == null || rotationSensor == null || gyroSensor == null) {
            Log.w(TAG, "start ignored: sensors unavailable")
            return false
        }
        resetTracking()
        val rotationRegistered = manager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        val gyroRegistered = manager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        started = rotationRegistered && gyroRegistered
        if (!started) {
            manager.unregisterListener(this)
            Log.w(TAG, "start failed rotationRegistered=$rotationRegistered gyroRegistered=$gyroRegistered")
        }
        return started
    }

    fun stop() {
        if (!started) {
            return
        }
        sensorManager?.unregisterListener(this)
        started = false
        resetTracking()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GAME_ROTATION_VECTOR -> handleRotationVector(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handleGyroscope(event: SensorEvent) {
        val timestampNs = event.timestamp
        val gyroX = event.values.getOrElse(0) { 0f }
        val gyroY = event.values.getOrElse(1) { 0f }
        val gyroZ = event.values.getOrElse(2) { 0f }
        latestGyroValues[0] = gyroX
        latestGyroValues[1] = gyroY
        latestGyroValues[2] = gyroZ

        if (!hasGyroHistory) {
            smoothedGyroValues[0] = gyroX
            smoothedGyroValues[1] = gyroY
            smoothedGyroValues[2] = gyroZ
            lastGyroValues[0] = gyroX
            lastGyroValues[1] = gyroY
            lastGyroValues[2] = gyroZ
            angularAccelValues.fill(0f)
            lastGyroTimestampNs = timestampNs
            lastDynamicActivityTimestampNs = timestampNs
            hasGyroHistory = true
            maybeLogSensorSnapshot(timestampNs, quiet = false)
            return
        }

        val dtNs = timestampNs - lastGyroTimestampNs
        val dtSeconds = if (dtNs > 0L) dtNs / NANOS_TO_SECONDS else 0f
        if (dtSeconds > 0f) {
            angularAccelValues[0] = lowPass(
                angularAccelValues[0],
                (gyroX - lastGyroValues[0]) / dtSeconds,
                config.angularAccelSmoothingAlpha,
            )
            angularAccelValues[1] = lowPass(
                angularAccelValues[1],
                (gyroY - lastGyroValues[1]) / dtSeconds,
                config.angularAccelSmoothingAlpha,
            )
            angularAccelValues[2] = lowPass(
                angularAccelValues[2],
                (gyroZ - lastGyroValues[2]) / dtSeconds,
                config.angularAccelSmoothingAlpha,
            )
        }

        smoothedGyroValues[0] = lowPass(smoothedGyroValues[0], gyroX, config.gyroSmoothingAlpha)
        smoothedGyroValues[1] = lowPass(smoothedGyroValues[1], gyroY, config.gyroSmoothingAlpha)
        smoothedGyroValues[2] = lowPass(smoothedGyroValues[2], gyroZ, config.gyroSmoothingAlpha)

        lastGyroValues[0] = gyroX
        lastGyroValues[1] = gyroY
        lastGyroValues[2] = gyroZ
        lastGyroTimestampNs = timestampNs

        val snapshot = currentSnapshot(timestampNs)
        val quietWindowReady = hasQuietWindow(timestampNs)
        val quiet = isQuiet(snapshot)
        updateTriggerArming(timestampNs, quiet, quietWindowReady)
        if (!quiet) {
            lastDynamicActivityTimestampNs = timestampNs
        }
        updateCandidate(snapshot)
        maybeStartCandidate(snapshot)
        maybeLogSensorSnapshot(timestampNs, quiet)
    }

    private fun handleRotationVector(event: SensorEvent) {
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        val yawDeg = Math.toDegrees(unwrapYawRadians(orientationAngles[0]).toDouble()).toFloat()

        latestPitchDeg = pitchDeg
        latestYawDeg = yawDeg
        if (!hasSmoothedPose) {
            smoothedPitchDeg = pitchDeg
            smoothedYawDeg = yawDeg
            hasSmoothedPose = true
        } else {
            smoothedPitchDeg = lowPass(smoothedPitchDeg, pitchDeg, config.poseSmoothingAlpha)
            smoothedYawDeg = lowPass(smoothedYawDeg, yawDeg, config.poseSmoothingAlpha)
        }
    }

    private fun currentSnapshot(timestampNs: Long): DynamicSnapshot {
        return DynamicSnapshot(
            timestampNs = timestampNs,
            gyroPitchRate = smoothedGyroValues[0],
            gyroYawRate = smoothedGyroValues[2],
            angularAccelPitch = angularAccelValues[0],
            angularAccelYaw = angularAccelValues[2],
            pitchDeg = smoothedPitchDeg,
            yawDeg = smoothedYawDeg,
        )
    }

    private fun isQuiet(snapshot: DynamicSnapshot): Boolean {
        return abs(snapshot.gyroPitchRate) <= config.quietGyroMaxRad &&
            abs(snapshot.gyroYawRate) <= config.quietGyroMaxRad &&
            abs(snapshot.angularAccelPitch) <= config.quietAngularAccelMaxRad &&
            abs(snapshot.angularAccelYaw) <= config.quietAngularAccelMaxRad
    }

    private fun hasQuietWindow(timestampNs: Long): Boolean {
        return lastDynamicActivityTimestampNs != 0L &&
            timestampNs - lastDynamicActivityTimestampNs >= config.quietWindowNs
    }

    private fun updateTriggerArming(timestampNs: Long, quiet: Boolean, quietWindowReady: Boolean) {
        if (quiet && quietWindowReady) {
            triggerArmedUntilNs = timestampNs + config.quietTriggerGraceNs
        } else if (timestampNs > triggerArmedUntilNs) {
            triggerArmedUntilNs = 0L
        }
    }

    private fun isTriggerArmed(timestampNs: Long): Boolean {
        return triggerArmedUntilNs != 0L && timestampNs <= triggerArmedUntilNs
    }

    private fun maybeStartCandidate(snapshot: DynamicSnapshot) {
        if (activeCandidate != null || !hasSmoothedPose || !isTriggerArmed(snapshot.timestampNs)) {
            return
        }

        val pitchAccelAbs = abs(snapshot.angularAccelPitch)
        val yawAccelAbs = abs(snapshot.angularAccelYaw)
        val pitchDominant = pitchAccelAbs >= config.triggerPitchAngularAccelEnterRad &&
            pitchAccelAbs >= yawAccelAbs * config.axisDominanceRatio
        val yawDominant = yawAccelAbs >= config.triggerYawAngularAccelEnterRad &&
            yawAccelAbs >= pitchAccelAbs * config.axisDominanceRatio
        if (!pitchDominant && !yawDominant) {
            return
        }

        val type = if (pitchDominant && !yawDominant) {
            HeadGestureType.NOD
        } else if (yawDominant && !pitchDominant) {
            HeadGestureType.SHAKE
        } else {
            if (pitchAccelAbs >= yawAccelAbs) HeadGestureType.NOD else HeadGestureType.SHAKE
        }
        val primaryRate = primaryRate(type, snapshot)
        val crossRate = crossRate(type, snapshot)
        val primaryAccel = primaryAccel(type, snapshot)
        val crossAccel = crossAccel(type, snapshot)
        val initialDirection = signNonZero(primaryRate, primaryAccel)
        activeCandidate = GestureCandidate(
            type = type,
            startTimestampNs = snapshot.timestampNs,
            startPitchDeg = snapshot.pitchDeg,
            startYawDeg = snapshot.yawDeg,
            initialPrimaryDirection = initialDirection,
            lastTimestampNs = snapshot.timestampNs,
            lastPrimaryRate = primaryRate,
            maxPrimaryRate = primaryRate,
            minPrimaryRate = primaryRate,
            maxCrossRateAbs = abs(crossRate),
            maxPrimaryAccelAbs = abs(primaryAccel),
            maxCrossAccelAbs = abs(crossAccel),
        )
        if (config.enableDecisionLogs) {
            Log.d(
                TAG,
                "candidate started type=$type quietMs=${(snapshot.timestampNs - lastDynamicActivityTimestampNs) / 1_000_000} " +
                    "armedForMs=${(triggerArmedUntilNs - snapshot.timestampNs) / 1_000_000} " +
                    "gyroPitch=${format2(snapshot.gyroPitchRate)} gyroYaw=${format2(snapshot.gyroYawRate)} " +
                    "accPitch=${format2(snapshot.angularAccelPitch)} accYaw=${format2(snapshot.angularAccelYaw)}",
            )
        }
        triggerArmedUntilNs = 0L
    }

    private fun updateCandidate(snapshot: DynamicSnapshot) {
        val candidate = activeCandidate ?: return
        candidate.lastTimestampNs = snapshot.timestampNs
        candidate.maxPitchDeg = maxOf(candidate.maxPitchDeg, snapshot.pitchDeg)
        candidate.minPitchDeg = minOf(candidate.minPitchDeg, snapshot.pitchDeg)
        candidate.maxYawDeg = maxOf(candidate.maxYawDeg, snapshot.yawDeg)
        candidate.minYawDeg = minOf(candidate.minYawDeg, snapshot.yawDeg)

        val primaryRate = primaryRate(candidate.type, snapshot)
        val crossRate = crossRate(candidate.type, snapshot)
        val primaryAccel = primaryAccel(candidate.type, snapshot)
        val crossAccel = crossAccel(candidate.type, snapshot)

        candidate.maxPrimaryRate = maxOf(candidate.maxPrimaryRate, primaryRate)
        candidate.minPrimaryRate = minOf(candidate.minPrimaryRate, primaryRate)
        candidate.maxCrossRateAbs = maxOf(candidate.maxCrossRateAbs, abs(crossRate))
        candidate.maxPrimaryAccelAbs = maxOf(candidate.maxPrimaryAccelAbs, abs(primaryAccel))
        candidate.maxCrossAccelAbs = maxOf(candidate.maxCrossAccelAbs, abs(crossAccel))

        updateReversePeak(candidate, primaryRate)

        val primaryRatePeak = max(abs(candidate.maxPrimaryRate), abs(candidate.minPrimaryRate))
        val settleRateThreshold = max(config.quietGyroMaxRad, primaryRatePeak * config.settleRateDecayRatio)
        val settleAccelThreshold = max(config.triggerAngularAccelExitRad, candidate.maxPrimaryAccelAbs * config.settleAccelDecayRatio)
        val waveformComplete = candidate.signFlipCount >= 2
        val dynamicSettled = waveformComplete &&
            abs(primaryRate) <= settleRateThreshold &&
            abs(primaryAccel) <= settleAccelThreshold
        if (dynamicSettled) {
            if (candidate.settleSinceNs == 0L) {
                candidate.settleSinceNs = snapshot.timestampNs
            }
        } else {
            candidate.settleSinceNs = 0L
        }

        val durationNs = snapshot.timestampNs - candidate.startTimestampNs
        if (durationNs > config.maxCandidateWindowNs) {
            if (config.enableDecisionLogs) {
                Log.d(TAG, "candidate timeout type=${candidate.type} durationMs=${durationNs / 1_000_000}")
            }
            activeCandidate = null
            return
        }

        if (candidate.settleSinceNs == 0L ||
            snapshot.timestampNs - candidate.settleSinceNs < config.settleDurationNs ||
            durationNs < config.minCandidateWindowNs
        ) {
            candidate.lastPrimaryRate = primaryRate
            return
        }

        finalizeCandidate(candidate, snapshot)
    }

    private fun updateReversePeak(candidate: GestureCandidate, primaryRate: Float) {
        val previousDirection = signNonZero(candidate.lastPrimaryRate)
        val currentDirection = signNonZero(primaryRate)
        if (previousDirection != 0 && currentDirection != 0 && previousDirection != currentDirection) {
            candidate.signFlipCount += 1
        }

        val oppositeDirection = candidate.initialPrimaryDirection != 0 && currentDirection == -candidate.initialPrimaryDirection
        if (oppositeDirection) {
            candidate.reversePeakAbs = maxOf(candidate.reversePeakAbs, abs(primaryRate))
        }
        candidate.lastPrimaryRate = primaryRate
    }

    private fun finalizeCandidate(candidate: GestureCandidate, snapshot: DynamicSnapshot) {
        val durationMs = (snapshot.timestampNs - candidate.startTimestampNs) / 1_000_000
        val primaryAngleTravel = primaryAngleTravel(candidate)
        val crossAngleTravel = crossAngleTravel(candidate)
        val primaryRatePeak = max(abs(candidate.maxPrimaryRate), abs(candidate.minPrimaryRate))
        val posPeak = candidate.maxPrimaryRate
        val negPeak = candidate.minPrimaryRate

        val valid = when (candidate.type) {
            HeadGestureType.NOD -> {
                primaryAngleTravel >= config.minPitchAngleTravelDeg &&
                    crossAngleTravel <= config.maxCrossAxisAngleTravelDeg &&
                    primaryRatePeak >= config.minPitchGyroPeakRad &&
                    candidate.maxPrimaryAccelAbs >= config.minPitchAngularAccelPeakRad &&
                    candidate.reversePeakAbs >= config.minPitchReversePeakRad &&
                    candidate.maxCrossRateAbs <= primaryRatePeak * config.maxCrossAxisDynamicRatio &&
                    candidate.maxCrossAccelAbs <= candidate.maxPrimaryAccelAbs * config.maxCrossAxisDynamicRatio &&
                    hasBidirectionalRate(posPeak, negPeak, config.minPitchReversePeakRad) &&
                    hasSymmetricRate(posPeak, negPeak, config.maxRateAsymmetry)
            }

            HeadGestureType.SHAKE -> {
                primaryAngleTravel >= config.minYawAngleTravelDeg &&
                    crossAngleTravel <= config.maxCrossAxisAngleTravelDeg &&
                    primaryRatePeak >= config.minYawGyroPeakRad &&
                    candidate.maxPrimaryAccelAbs >= config.minYawAngularAccelPeakRad &&
                    candidate.reversePeakAbs >= config.minYawReversePeakRad &&
                    candidate.maxCrossRateAbs <= primaryRatePeak * config.maxCrossAxisDynamicRatio &&
                    candidate.maxCrossAccelAbs <= candidate.maxPrimaryAccelAbs * config.maxCrossAxisDynamicRatio &&
                    hasBidirectionalRate(posPeak, negPeak, config.minYawReversePeakRad) &&
                    hasSymmetricRate(posPeak, negPeak, config.maxRateAsymmetry)
            }
        }

        if (valid) {
            if (config.enableDecisionLogs) {
                Log.d(
                    TAG,
                    "candidate accepted type=${candidate.type} durationMs=$durationMs primaryAngle=${format1(primaryAngleTravel)} " +
                        "crossAngle=${format1(crossAngleTravel)} primaryGyro=${format2(primaryRatePeak)} " +
                        "primaryAccel=${format2(candidate.maxPrimaryAccelAbs)} reversePeak=${format2(candidate.reversePeakAbs)} " +
                        "crossGyro=${format2(candidate.maxCrossRateAbs)} crossAccel=${format2(candidate.maxCrossAccelAbs)}",
                )
            }
            recordPulse(snapshot.timestampNs, candidate.type)
        } else if (config.enableDecisionLogs) {
            Log.d(
                TAG,
                "candidate rejected type=${candidate.type} durationMs=$durationMs reasons=${buildRejectionReasons(candidate).joinToString(",")} " +
                    "primaryAngle=${format1(primaryAngleTravel)} crossAngle=${format1(crossAngleTravel)} " +
                    "gyroPos=${format2(posPeak)} gyroNeg=${format2(negPeak)} primaryAccel=${format2(candidate.maxPrimaryAccelAbs)} " +
                    "reversePeak=${format2(candidate.reversePeakAbs)} crossGyro=${format2(candidate.maxCrossRateAbs)} " +
                    "crossAccel=${format2(candidate.maxCrossAccelAbs)} signFlips=${candidate.signFlipCount}",
            )
        }
        activeCandidate = null
    }

    private fun buildRejectionReasons(candidate: GestureCandidate): List<String> {
        val primaryAngleTravel = primaryAngleTravel(candidate)
        val crossAngleTravel = crossAngleTravel(candidate)
        val primaryRatePeak = max(abs(candidate.maxPrimaryRate), abs(candidate.minPrimaryRate))
        val posPeak = candidate.maxPrimaryRate
        val negPeak = candidate.minPrimaryRate
        return buildList {
            when (candidate.type) {
                HeadGestureType.NOD -> {
                    if (primaryAngleTravel < config.minPitchAngleTravelDeg) add("pitchTravel<${format1(config.minPitchAngleTravelDeg)}")
                    if (crossAngleTravel > config.maxCrossAxisAngleTravelDeg) add("yawTravel>${format1(config.maxCrossAxisAngleTravelDeg)}")
                    if (primaryRatePeak < config.minPitchGyroPeakRad) add("pitchGyroPeak<${format2(config.minPitchGyroPeakRad)}")
                    if (candidate.maxPrimaryAccelAbs < config.minPitchAngularAccelPeakRad) add("pitchAccelPeak<${format2(config.minPitchAngularAccelPeakRad)}")
                    if (candidate.reversePeakAbs < config.minPitchReversePeakRad) add("pitchReversePeak<${format2(config.minPitchReversePeakRad)}")
                    if (candidate.maxCrossRateAbs > primaryRatePeak * config.maxCrossAxisDynamicRatio) add("crossGyroRatio>${format2(config.maxCrossAxisDynamicRatio)}")
                    if (candidate.maxCrossAccelAbs > candidate.maxPrimaryAccelAbs * config.maxCrossAxisDynamicRatio) add("crossAccelRatio>${format2(config.maxCrossAxisDynamicRatio)}")
                    if (!hasBidirectionalRate(posPeak, negPeak, config.minPitchReversePeakRad)) add("pitchNoBidirectional")
                    if (!hasSymmetricRate(posPeak, negPeak, config.maxRateAsymmetry)) add("pitchGyroAsymmetric")
                }

                HeadGestureType.SHAKE -> {
                    if (primaryAngleTravel < config.minYawAngleTravelDeg) add("yawTravel<${format1(config.minYawAngleTravelDeg)}")
                    if (crossAngleTravel > config.maxCrossAxisAngleTravelDeg) add("pitchTravel>${format1(config.maxCrossAxisAngleTravelDeg)}")
                    if (primaryRatePeak < config.minYawGyroPeakRad) add("yawGyroPeak<${format2(config.minYawGyroPeakRad)}")
                    if (candidate.maxPrimaryAccelAbs < config.minYawAngularAccelPeakRad) add("yawAccelPeak<${format2(config.minYawAngularAccelPeakRad)}")
                    if (candidate.reversePeakAbs < config.minYawReversePeakRad) add("yawReversePeak<${format2(config.minYawReversePeakRad)}")
                    if (candidate.maxCrossRateAbs > primaryRatePeak * config.maxCrossAxisDynamicRatio) add("crossGyroRatio>${format2(config.maxCrossAxisDynamicRatio)}")
                    if (candidate.maxCrossAccelAbs > candidate.maxPrimaryAccelAbs * config.maxCrossAxisDynamicRatio) add("crossAccelRatio>${format2(config.maxCrossAxisDynamicRatio)}")
                    if (!hasBidirectionalRate(posPeak, negPeak, config.minYawReversePeakRad)) add("yawNoBidirectional")
                    if (!hasSymmetricRate(posPeak, negPeak, config.maxRateAsymmetry)) add("yawGyroAsymmetric")
                }
            }
        }
    }

    private fun primaryAngleTravel(candidate: GestureCandidate): Float {
        return when (candidate.type) {
            HeadGestureType.NOD -> candidate.maxPitchDeg - candidate.minPitchDeg
            HeadGestureType.SHAKE -> candidate.maxYawDeg - candidate.minYawDeg
        }
    }

    private fun crossAngleTravel(candidate: GestureCandidate): Float {
        return when (candidate.type) {
            HeadGestureType.NOD -> candidate.maxYawDeg - candidate.minYawDeg
            HeadGestureType.SHAKE -> candidate.maxPitchDeg - candidate.minPitchDeg
        }
    }

    private fun recordPulse(timestampNs: Long, type: HeadGestureType) {
        val requiredPulses = config.pulsesRequiredToEmit.coerceAtLeast(1)
        when (type) {
            HeadGestureType.NOD -> {
                prunePulses(nodPulseTimestamps, timestampNs)
                nodPulseTimestamps.addLast(timestampNs)
                if (config.enableDecisionLogs) {
                    Log.d(
                        TAG,
                        "aggregate updated type=NOD count=${nodPulseTimestamps.size}/$requiredPulses " +
                            "windowMs=${config.pulseWindowNs / 1_000_000}",
                    )
                }
                if (nodPulseTimestamps.size >= requiredPulses) {
                    if (config.enableDecisionLogs) {
                        Log.d(TAG, "aggregate trigger type=NOD count=$requiredPulses/$requiredPulses")
                    }
                    nodPulseTimestamps.clear()
                    shakePulseTimestamps.clear()
                    emitAggregatedGesture(timestampNs, HeadGestureType.NOD)
                }
            }

            HeadGestureType.SHAKE -> {
                prunePulses(shakePulseTimestamps, timestampNs)
                shakePulseTimestamps.addLast(timestampNs)
                if (config.enableDecisionLogs) {
                    Log.d(
                        TAG,
                        "aggregate updated type=SHAKE count=${shakePulseTimestamps.size}/$requiredPulses " +
                            "windowMs=${config.pulseWindowNs / 1_000_000}",
                    )
                }
                if (shakePulseTimestamps.size >= requiredPulses) {
                    if (config.enableDecisionLogs) {
                        Log.d(TAG, "aggregate trigger type=SHAKE count=$requiredPulses/$requiredPulses")
                    }
                    nodPulseTimestamps.clear()
                    shakePulseTimestamps.clear()
                    emitAggregatedGesture(timestampNs, HeadGestureType.SHAKE)
                }
            }
        }
    }

    private fun prunePulses(queue: ArrayDeque<Long>, timestampNs: Long) {
        val before = queue.size
        while (queue.isNotEmpty() && timestampNs - queue.first() > config.pulseWindowNs) {
            queue.removeFirst()
        }
        if (config.enableDecisionLogs && before != queue.size) {
            Log.d(TAG, "aggregate pruned removed=${before - queue.size} remaining=${queue.size}")
        }
    }

    private fun emitAggregatedGesture(timestampNs: Long, type: HeadGestureType) {
        if (lastEmitTimestampNs != 0L && timestampNs - lastEmitTimestampNs < config.emitCooldownNs) {
            if (config.enableDecisionLogs) {
                Log.d(
                    TAG,
                    "aggregate suppressed type=$type cooldownMs=${config.emitCooldownNs / 1_000_000} " +
                        "sinceLastMs=${(timestampNs - lastEmitTimestampNs) / 1_000_000}",
                )
            }
            return
        }
        lastEmitTimestampNs = timestampNs
        if (config.enableDecisionLogs) {
            Log.d(TAG, "aggregate emit type=$type")
        }
        emitGesture(type)
    }

    private fun emitGesture(type: HeadGestureType) {
        val event = HeadGestureEvent(
            type = type,
            timestampMillis = System.currentTimeMillis(),
            pitchDeg = smoothedPitchDeg,
            yawDeg = smoothedYawDeg,
            gyroPitchRate = smoothedGyroValues[0],
            gyroYawRate = smoothedGyroValues[2],
        )
        Log.i(
            TAG,
            "gesture detected type=$type absPitch=${format1(smoothedPitchDeg)} absYaw=${format1(smoothedYawDeg)} " +
                "gyroPitch=${format3(smoothedGyroValues[0])} gyroYaw=${format3(smoothedGyroValues[2])} " +
                "accPitch=${format3(angularAccelValues[0])} accYaw=${format3(angularAccelValues[2])}",
        )
        listeners.forEach { listener ->
            mainHandler.post { listener.onHeadGesture(event) }
        }
    }

    private fun maybeLogSensorSnapshot(timestampNs: Long, quiet: Boolean) {
        if (!config.enableSensorLogs) {
            return
        }
        val candidate = activeCandidate
        Log.d(
            TAG,
                "sensor absPitch=${format1(smoothedPitchDeg)} absYaw=${format1(smoothedYawDeg)} " +
                "gyroPitch=${format2(smoothedGyroValues[0])} gyroYaw=${format2(smoothedGyroValues[2])} " +
                "accPitch=${format2(angularAccelValues[0])} accYaw=${format2(angularAccelValues[2])} " +
                "quiet=$quiet armed=${isTriggerArmed(timestampNs)} candidate=${candidate?.type ?: "NONE"} " +
                "nodPulses=${nodPulseTimestamps.size} shakePulses=${shakePulseTimestamps.size}",
        )
    }

    private fun primaryRate(type: HeadGestureType, snapshot: DynamicSnapshot): Float {
        return when (type) {
            HeadGestureType.NOD -> snapshot.gyroPitchRate
            HeadGestureType.SHAKE -> snapshot.gyroYawRate
        }
    }

    private fun crossRate(type: HeadGestureType, snapshot: DynamicSnapshot): Float {
        return when (type) {
            HeadGestureType.NOD -> snapshot.gyroYawRate
            HeadGestureType.SHAKE -> snapshot.gyroPitchRate
        }
    }

    private fun primaryAccel(type: HeadGestureType, snapshot: DynamicSnapshot): Float {
        return when (type) {
            HeadGestureType.NOD -> snapshot.angularAccelPitch
            HeadGestureType.SHAKE -> snapshot.angularAccelYaw
        }
    }

    private fun crossAccel(type: HeadGestureType, snapshot: DynamicSnapshot): Float {
        return when (type) {
            HeadGestureType.NOD -> snapshot.angularAccelYaw
            HeadGestureType.SHAKE -> snapshot.angularAccelPitch
        }
    }

    private fun unwrapYawRadians(rawYawRad: Float): Float {
        val last = lastYawRad
        if (last == null) {
            lastYawRad = rawYawRad
            unwrappedYawRad = rawYawRad
            return unwrappedYawRad
        }
        val delta = normalizeRadians(rawYawRad - last)
        unwrappedYawRad += delta
        lastYawRad = rawYawRad
        return unwrappedYawRad
    }

    private fun normalizeRadians(value: Float): Float {
        var normalized = value
        while (normalized > Math.PI.toFloat()) {
            normalized -= (Math.PI * 2.0).toFloat()
        }
        while (normalized < -Math.PI.toFloat()) {
            normalized += (Math.PI * 2.0).toFloat()
        }
        return normalized
    }

    private fun hasBidirectionalRate(positivePeak: Float, negativePeak: Float, minMagnitude: Float): Boolean {
        return positivePeak >= minMagnitude && negativePeak <= -minMagnitude
    }

    private fun hasSymmetricRate(positivePeak: Float, negativePeak: Float, maxAsymmetry: Float): Boolean {
        val posAbs = abs(positivePeak)
        val negAbs = abs(negativePeak)
        val maxPeak = max(posAbs, negAbs)
        if (maxPeak == 0f) return false
        return abs(posAbs - negAbs) / maxPeak <= maxAsymmetry
    }

    private fun signNonZero(primary: Float, secondary: Float = 0f): Int {
        return when {
            abs(primary) > 1e-4f -> sign(primary).toInt()
            abs(secondary) > 1e-4f -> sign(secondary).toInt()
            else -> 0
        }
    }

    private fun lowPass(previous: Float, current: Float, alpha: Float): Float {
        return previous + alpha * (current - previous)
    }

    private fun format1(value: Float): String = "%.1f".format(value)

    private fun format2(value: Float): String = "%.2f".format(value)

    private fun format3(value: Float): String = "%.3f".format(value)

    private fun resetTracking() {
        latestPitchDeg = 0f
        latestYawDeg = 0f
        smoothedPitchDeg = 0f
        smoothedYawDeg = 0f
        hasSmoothedPose = false
        latestGyroValues = FloatArray(3)
        smoothedGyroValues = FloatArray(3)
        angularAccelValues = FloatArray(3)
        lastGyroValues = FloatArray(3)
        lastGyroTimestampNs = 0L
        hasGyroHistory = false
        lastYawRad = null
        unwrappedYawRad = 0f
        activeCandidate = null
        lastDynamicActivityTimestampNs = 0L
        triggerArmedUntilNs = 0L
        lastEmitTimestampNs = 0L
        nodPulseTimestamps.clear()
        shakePulseTimestamps.clear()
    }
}
